@file:Suppress("DEPRECATION") // Robolectric 的 NetworkInfo/TYPE_WIFI 注入 API 本身已弃用

package com.puretone.player.playback.cast

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [CastNetworkBridge] 所有权单测（第五轮复审 B2/B3 修复后重写）。
 *
 * ## 为什么这一版不是假绿灯
 *
 * 旧版 6 个用例只断言 `keepAliveSnapshot()` 里的 `bound=` / `session=`，而这两个串
 * 直接来自被测的 [CastNetworkBridge] 内部布尔字段：把 `acquireKeepAlive()`、
 * `bindToWifiNetwork()`、`restoreNetwork()` 整段删掉、只留布尔赋值，测试仍会全绿。
 * 本版把断言锚定在**被测代码之外的真实副作用**上：
 *
 * 1. `ShadowPowerManager.getLatestWakeLock()` 的 `isHeld`，以及快照里的
 *    `wake=` / `wifi=`（它们读的是真实 `WakeLock` / `WifiLock.isHeld`，不是布尔）；
 * 2. `ShadowConnectivityManager` 注入一条带 `TRANSPORT_WIFI` 的 Network 后，
 *    `cm.boundNetworkForProcess` 在持有期指向该 Wi-Fi、在 [CastNetworkBridge.endCastSession]
 *    之后**还原到绑定前**的 Network —— 这正是 B2「退出投屏后进程被永久绑死 Wi-Fi」的判据；
 * 3. 两线程并发 [CastNetworkBridge.holdCastNetwork]，只允许一个 `true`。
 *
 * 另做一次变异验证（临时删掉 `acquireKeepAlive()` 调用）确认至少一条用例变红，
 * 结果记录在 `dist/` 复核文书里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastNetworkBridgeTest {

    private lateinit var bridge: CastNetworkBridge
    private lateinit var cm: ConnectivityManager
    private lateinit var shadowCm: ShadowConnectivityManager
    private lateinit var wifiNet: Network
    private lateinit var previousNet: Network

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ShadowPowerManager.clearWakeLocks()
        cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowCm = Shadows.shadowOf(cm)

        // 注入一条「可用 Wi-Fi」：activeNetwork 保持 null，让 findWifiNetwork 走 allNetworks
        // 分支；caps 必须同时带 TRANSPORT_WIFI 与 NET_CAPABILITY_INTERNET（A4 判据未改动）。
        wifiNet = ShadowNetwork.newInstance(WIFI_NET_ID)
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        shadowCm.setNetworkCapabilities(wifiNet, caps)
        shadowCm.addNetwork(wifiNet, wifiInfo())

        // 绑定前的进程网络（模拟蜂窝 / 上一次绑定）；B2 修复必须把它原样还原回来。
        previousNet = ShadowNetwork.newInstance(PREVIOUS_NET_ID)
        assertTrue(cm.bindProcessToNetwork(previousNet))
        assertEquals(previousNet, cm.boundNetworkForProcess)

        bridge = CastNetworkBridge(app)
    }

    /** 直接读公开诊断串；wake/wifi 来自真实锁对象，bound/session 来自所有权布尔 */
    private fun snapshot() = bridge.keepAliveSnapshot()

    private fun wifiInfo(): NetworkInfo = ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED,
        ConnectivityManager.TYPE_WIFI,
        0,
        true,
        true
    )

    @Test
    fun `首次 holdCastNetwork 真实建立绑定并持有保活锁`() {
        assertTrue(bridge.holdCastNetwork())

        assertTrue("绑定应指向注入的 Wi-Fi 网络", cm.boundNetworkForProcess == wifiNet)
        assertTrue("快照应报告真实持有的 CPU 锁", snapshot().contains("wake=true"))
        assertTrue("快照应报告真实持有的 Wi-Fi 锁", snapshot().contains("wifi=true"))
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("生产代码必须真的 newWakeLock + acquire", wake)
        assertTrue("生产代码必须真的持有 WakeLock", wake!!.isHeld)
        assertTrue(snapshot().contains("bound=true"))
    }

    @Test
    fun `重复 holdCastNetwork 复用绑定且不重复持锁`() {
        assertTrue(bridge.holdCastNetwork())
        assertFalse("第二次调用应复用既有绑定而非再次建立", bridge.holdCastNetwork())
        assertEquals("重复 hold 不得改写进程绑定", wifiNet, cm.boundNetworkForProcess)
        assertTrue(snapshot().contains("wake=true"))
        assertTrue(snapshot().contains("bound=true"))
    }

    @Test
    fun `无会话时借用方归还释放绑定与保活锁并还原原网络`() {
        assertTrue(bridge.holdCastNetwork())
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertNotNull("生产代码必须真的创建 WakeLock", wake)

        bridge.releaseBorrowedNetwork(owned = true)

        assertTrue("无会话时借用方归还应真正释放", snapshot().contains("bound=false"))
        assertTrue("释放后 WakeLock 不得仍持有", snapshot().contains("wake=false"))
        assertTrue("释放后 WifiLock 不得仍持有", snapshot().contains("wifi=false"))
        assertFalse("真实 WakeLock 必须已 release", wake!!.isHeld)
        assertEquals("归还必须还原到绑定前的网络", previousNet, cm.boundNetworkForProcess)
        assertTrue("释放后可再次建立绑定", bridge.holdCastNetwork())
    }

    @Test
    fun `会话已接管时借用方归还不释放且停投后才还原`() {
        bridge.beginCastSession()
        assertTrue(snapshot().contains("session=true"))
        assertTrue(snapshot().contains("wake=true"))
        assertFalse("会话已持有时借用方复用绑定", bridge.holdCastNetwork())

        bridge.releaseBorrowedNetwork(owned = true)   // 搜索被取消执行归还（事故分水岭）

        assertTrue("会话接管期间借用方归还不得释放锁", snapshot().contains("bound=true"))
        assertTrue("会话接管期间保活锁必须仍在", snapshot().contains("wake=true"))
        assertEquals("接管期间不得还原进程网络", wifiNet, cm.boundNetworkForProcess)

        bridge.endCastSession()
        assertTrue(snapshot().contains("bound=false"))
        assertTrue(snapshot().contains("session=false"))
        assertTrue(snapshot().contains("wake=false"))
        assertEquals(previousNet, cm.boundNetworkForProcess)
    }

    @Test
    fun `复用别人绑定的借用方归还是无操作`() {
        assertTrue(bridge.holdCastNetwork())
        bridge.releaseBorrowedNetwork(owned = false)
        assertTrue(snapshot().contains("bound=true"))
        assertTrue(snapshot().contains("wake=true"))
        bridge.releaseBorrowedNetwork(owned = false)
        assertTrue(snapshot().contains("bound=true"))
        assertEquals(wifiNet, cm.boundNetworkForProcess)
    }

    @Test
    fun `真机时序_发现借锁_会话接管_搜索取消归还_锁仍在_停投后才释放`() {
        assertTrue(bridge.holdCastNetwork())
        bridge.beginCastSession()
        assertTrue(snapshot().contains("session=true"))
        bridge.releaseBorrowedNetwork(owned = true)
        assertTrue("归还不得释放会话已接管的锁", snapshot().contains("bound=true"))
        assertEquals("归还不得改写绑定", wifiNet, cm.boundNetworkForProcess)
        bridge.endCastSession()
        assertTrue(snapshot().contains("bound=false"))
        assertEquals(previousNet, cm.boundNetworkForProcess)
    }

    /**
     * B2 回归判据：搜索 / 音量探针 / beginCastSession 三路并发首次进入时，
     * 只允许一个真正建立绑定，且结束投屏后必须还原到**绑定前**的 Network。
     * 修复前两次 `bindToWifiNetwork()` 会把 castNetPrevious 覆盖成 Wi-Fi 本身。
     */
    @Test
    fun `并发首次 hold 只有一个建立绑定且还原目标是原始网络`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val owned = AtomicInteger(0)
        try {
            val tasks = (1..2).map {
                pool.submit {
                    start.await()
                    if (bridge.holdCastNetwork()) owned.incrementAndGet()
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals("并发首次 hold 只允许一个调用真正建立绑定", 1, owned.get())
        assertEquals("并发后进程绑定仍应指向 Wi-Fi", wifiNet, cm.boundNetworkForProcess)
        assertTrue("并发后保活锁仍应持有", snapshot().contains("wake=true"))

        bridge.endCastSession()
        assertEquals("退出投屏后必须还原到绑定前网络", previousNet, cm.boundNetworkForProcess)
    }

    private companion object {
        const val WIFI_NET_ID = 101
        const val PREVIOUS_NET_ID = 200
    }
}

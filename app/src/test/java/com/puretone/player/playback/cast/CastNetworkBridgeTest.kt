package com.puretone.player.playback.cast

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CastNetworkBridge 所有权单测（投屏加固主线收尾项，纯本地、零发版风险）。
 *
 * 测的是「借用 vs 接管」这套所有权契约的**真实行为**，而不是替身：
 * - [CastNetworkBridge.holdCastNetwork] 返回 true = 本次调用真正建立了绑定（借用方负责归还）；
 *   返回 false = 复用既有绑定（幂等无操作）。
 * - 绑定 / 接管 / 会话的真实状态，通过公开诊断 [CastNetworkBridge.keepAliveSnapshot] 的
 *   `bound=` / `session=` 字段断言，避免反射私有字段、也避免假绿灯。
 * - 决策入口 [CastKeepAlivePolicy.shouldReleaseOnBorrowerReturn] 已单测覆盖，这里验证的是
 *   它在桥里的集成时序：借用方归还是否会误杀「已被会话接管的锁」。
 *
 * 不需要真实网络：Robolectric 下 `ConnectivityManager.activeNetwork` 为 null，
 * `bindToWifiNetwork()` 自然走「未找到 Wi-Fi → 返回 null」分支，但 `castNetHeld` /
 * `sessionActive` 这两个布尔仍严格按契约推进。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastNetworkBridgeTest {

    private lateinit var bridge: CastNetworkBridge

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        bridge = CastNetworkBridge(app)
    }

    /** 直接读公开诊断串里的 bound= / session= 字段，断言真实所有权状态 */
    private fun snapshot() = bridge.keepAliveSnapshot()

    @Test
    fun `首次 holdCastNetwork 建立绑定并返回 true`() {
        assertTrue(bridge.holdCastNetwork())
        assertTrue("bound 应被置位", snapshot().contains("bound=true"))
    }

    @Test
    fun `重复 holdCastNetwork 复用绑定并返回 false`() {
        assertTrue(bridge.holdCastNetwork())
        assertFalse("第二次调用应复用既有绑定而非再次建立", bridge.holdCastNetwork())
        assertTrue(snapshot().contains("bound=true"))
    }

    @Test
    fun `无会话时借用方归还释放绑定`() {
        assertTrue(bridge.holdCastNetwork())          // 设备发现借到所有权
        bridge.releaseBorrowedNetwork(owned = true)   // 搜索正常结束归还
        assertTrue("无会话时借用方归还应真正释放", snapshot().contains("bound=false"))
        assertTrue("释放后可再次建立绑定", bridge.holdCastNetwork())
    }

    @Test
    fun `会话已接管时借用方归还不释放`() {
        bridge.beginCastSession()                     // 接管：bound=true session=true
        assertTrue(snapshot().contains("session=true"))
        assertFalse("会话已持有时借用方复用绑定", bridge.holdCastNetwork())
        bridge.releaseBorrowedNetwork(owned = true)   // 搜索被取消执行归还（事故分水岭）
        assertTrue("会话接管期间借用方归还不得释放锁", snapshot().contains("bound=true"))
        bridge.endCastSession()                        // 唯一无条件释放出口
        assertTrue(snapshot().contains("bound=false"))
        assertTrue(snapshot().contains("session=false"))
    }

    @Test
    fun `复用别人绑定的借用方归还是无操作`() {
        assertTrue(bridge.holdCastNetwork())
        bridge.releaseBorrowedNetwork(owned = false)   // owned=false 永远不释放
        assertTrue(snapshot().contains("bound=true"))
        bridge.releaseBorrowedNetwork(owned = false)
        assertTrue(snapshot().contains("bound=true"))
    }

    @Test
    fun `真机时序_发现借锁_会话接管_搜索取消归还_锁仍在_停投后才释放`() {
        // 1) 设备发现先借到所有权
        assertTrue(bridge.holdCastNetwork())
        // 2) 用户点设备投屏，castSong 在 cast-ok 之前就让会话接管
        bridge.beginCastSession()
        assertTrue(snapshot().contains("session=true"))
        // 3) 关弹窗使搜索协程取消并归还 —— 这一行就是 2026-11-06 真机事故分水岭
        bridge.releaseBorrowedNetwork(owned = true)
        assertTrue("归还不得释放会话已接管的锁", snapshot().contains("bound=true"))
        // 4) 停止投屏由会话自身释放
        bridge.endCastSession()
        assertTrue(snapshot().contains("bound=false"))
    }
}

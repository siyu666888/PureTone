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

/**
 * B5：findWifiNetwork 的 active 优先 / allNetworks 兜底两条路由的 Robolectric 集成测试。
 *
 * 这是「非假绿灯」判据：纯函数 pickWifiNetwork 可被单测锁定，但若生产 findWifiNetwork
 * 没把 activeNetwork / allNetworks 正确映射进去，纯函数测试仍全绿。本类通过真实
 * ShadowConnectivityManager 注入 active 网络，观察 holdCastNetwork() 后进程绑定的
 * 究竟是哪个 Network（生产副作用），从而证明两条路由真的接对了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiRouteTest {

    private lateinit var app: Application
    private lateinit var cm: ConnectivityManager
    private lateinit var shadowCm: ShadowConnectivityManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowCm = Shadows.shadowOf(cm)
        shadowCm.clearAllNetworks()
        shadowCm.setActiveNetworkInfo(null)
    }

    private fun wifiCaps(): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().apply {
        Shadows.shadowOf(this).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun cellularCaps(): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().apply {
        Shadows.shadowOf(this).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun netInfo(type: Int): NetworkInfo = ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED, type, 0, true, true
    )

    /** 注入一条网络到 allNetworks，并设其 capabilities */
    private fun addNet(netId: Int, caps: NetworkCapabilities, type: Int): Network {
        val net = ShadowNetwork.newInstance(netId)
        shadowCm.setNetworkCapabilities(net, caps)
        shadowCm.addNetwork(net, netInfo(type))
        return net
    }

    @Test
    fun `B5 active是可上网Wi-Fi时绑定active网络而非兜底网络`() {
        // activeNetwork 由 setActiveNetworkInfo(TYPE_WIFI) 派生：getActiveNetwork() 取
        // netIdToNetwork[TYPE_WIFI]，故用 netId==TYPE_WIFI 的网络作为 active 候选。
        val activeType = ConnectivityManager.TYPE_WIFI
        val activeNet = addNet(activeType, wifiCaps(), activeType)
        val fallbackWifi = addNet(201, wifiCaps(), ConnectivityManager.TYPE_WIFI)
        shadowCm.setActiveNetworkInfo(netInfo(activeType))

        val bridge = CastNetworkBridge(app)
        assertTrue("应成功绑定", bridge.holdCastNetwork())
        assertEquals("双网卡必须绑定 active Wi-Fi 网络", activeNet, cm.boundNetworkForProcess)
        assertTrue("兜底网络不得被选中", cm.boundNetworkForProcess != fallbackWifi)
    }

    @Test
    fun `B5 active是蜂窝时兜底绑定allNetworks里的Wi-Fi`() {
        val cellularType = ConnectivityManager.TYPE_MOBILE
        addNet(cellularType, cellularCaps(), cellularType)
        val fallbackWifi = addNet(202, wifiCaps(), ConnectivityManager.TYPE_WIFI)
        shadowCm.setActiveNetworkInfo(netInfo(cellularType))

        val bridge = CastNetworkBridge(app)
        assertTrue("应成功绑定", bridge.holdCastNetwork())
        assertEquals("active=cellular 必须兜底绑定 Wi-Fi", fallbackWifi, cm.boundNetworkForProcess)
    }

    @Test
    fun `B5 无可用Wi-Fi时不建立绑定`() {
        val cellularType = ConnectivityManager.TYPE_MOBILE
        addNet(cellularType, cellularCaps(), cellularType)
        shadowCm.setActiveNetworkInfo(netInfo(cellularType))

        val bridge = CastNetworkBridge(app)
        // findWifiNetwork 返回 null → bindToWifiNetwork 返回 null → 无绑定（保持系统默认 null）
        bridge.holdCastNetwork()
        assertEquals("无可用 Wi-Fi 不得绑定蜂窝", null, cm.boundNetworkForProcess)
    }
}

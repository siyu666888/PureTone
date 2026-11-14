package com.puretone.player.playback.cast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CastVolumeController] 单测：锁定注释里点名的真实历史 bug（不依赖真机/库通道）。
 *
 * 覆盖：
 * - 滑块连发音量只下发最终值（防连按漂移，原 bug：回读 ±2 误差反复覆盖 UI 初值）；
 * - 会话未建立时（isCasting=false）不下发、不写状态；
 * - 相对调节以「最近已受理请求值」为基准，连按不丢档；
 * - reset 清空待下发请求，且复位后允许设备回读初值写入；
 * - 用户已手动调过音量，则设备回读不覆盖 UI 初值（触摸守卫）；
 * - 能力判定（volumeSupported）只写一次，不随回读翻转。
 *
 * [DlnaTransport] 在 v1.9.12 后改为 `open`，使此处可用替身断言「实际下发了哪条 SetVolume」，
 * 不碰真实 SOAP/网络（那种测出来的是假绿灯）。
 *
 * drain 协程（collectLatest 无限循环）跑在 [UnconfinedTestDispatcher] 子作用域里：
 * 订阅即立即执行、发射同步投递，时序确定；测试结束显式 cancel，避免 runTest 的
 * UncompletedCoroutinesError。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class CastVolumeControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 替身传输：只记录 setVolume 调用、回读固定值，不发真实 SOAP */
    private class FakeTransport(volumeResult: Int? = 70) :
        DlnaTransport("http://192.168.1.5:8200/desc.xml", "FakeTV") {
        val setVolumeCalls = mutableListOf<Int>()
        var currentVolume: Int? = volumeResult
        override suspend fun setVolume(volume: Int): Boolean? {
            setVolumeCalls.add(volume)
            return true
        }
        override suspend fun volume(): Int? = currentVolume
    }

    private fun controller(
        state: MutableStateFlow<CastState>,
        transport: DlnaTransport,
        scope: CoroutineScope,
    ) = CastVolumeController(state, CastNetworkBridge(context), scope) { transport }

    @Test
    fun `滑块连发音量只下发最终值（防漂移）`() = runTest {
        val state = MutableStateFlow(CastState(isCasting = true))
        val t = FakeTransport()
        val drainScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(state, t, drainScope)
        c.start() // Unconfined：立即订阅，进入对首个值的合并窗口
        c.setVolume(10)
        c.setVolume(20)
        c.setVolume(30)
        advanceUntilIdle()
        // 合并后只对最终值发一次 SetVolume，中间的 10/20 被 collectLatest 取消
        assertEquals(listOf(30), t.setVolumeCalls)
        assertEquals(30, state.value.castVolume)
        drainScope.cancel()
    }

    @Test
    fun `会话未建立时不下发音量且不写状态`() = runTest {
        val state = MutableStateFlow(CastState(isCasting = false))
        val t = FakeTransport()
        val drainScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(state, t, drainScope)
        c.start()
        c.setVolume(50)
        advanceUntilIdle()
        assertTrue("未建立会话不应下发 SetVolume", t.setVolumeCalls.isEmpty())
        assertEquals(100, state.value.castVolume) // 保持默认未校准值
        drainScope.cancel()
    }

    @Test
    fun `相对调节以最近请求值为基准（连按不丢档）`() = runTest {
        val state = MutableStateFlow(CastState(isCasting = true, castVolume = 15))
        val t = FakeTransport()
        val drainScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(state, t, drainScope)
        c.start()
        c.adjustVolume(+5) // resolveRelative(null, 15, 5) = 20
        advanceUntilIdle()
        assertEquals(listOf(20), t.setVolumeCalls)
        assertEquals(20, state.value.castVolume)
        drainScope.cancel()
    }

    @Test
    fun `reset 清空待下发请求（不再补发旧值）`() = runTest {
        val state = MutableStateFlow(CastState(isCasting = true))
        val t = FakeTransport()
        val drainScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = controller(state, t, drainScope)
        c.start()
        c.setVolume(40) // 40 进入合并窗口（delay 中，块已起飞）
        // 模拟 stopCast：会话置 false（drain 延迟后会靠此检查拦下在途请求）+ reset 清空
        state.update { it.copy(isCasting = false) }
        c.reset() // 待下发与「用户已调」一并复位
        advanceUntilIdle()
        assertTrue("reset + 会话结束不应再补发旧请求", t.setVolumeCalls.isEmpty())
        drainScope.cancel()
    }

    @Test
    fun `reset 后允许设备回读初值写入 UI`() = runTest {
        val state = MutableStateFlow(CastState(isCasting = true, castVolume = 100, volumeSupported = null))
        val t = FakeTransport(volumeResult = 70)
        val c = controller(state, t, this)
        c.setVolume(40) // touched=true，但 drain 未跑，castVolume 仍 100
        c.reset() // touched=false
        c.syncInitialVolumeNow()
        assertEquals(70, state.value.castVolume)
        assertEquals(true, state.value.volumeSupported)
    }

    @Test
    fun `用户已调过音量则设备回读不覆盖 UI 初值`() = runTest {
        val state = MutableStateFlow(CastState(isCasting = true, castVolume = 100, volumeSupported = null))
        val t = FakeTransport(volumeResult = 70)
        val c = controller(state, t, this)
        c.setVolume(40) // touched=true
        c.syncInitialVolumeNow()
        assertEquals(100, state.value.castVolume) // 回读被触摸守卫拦截
        assertEquals(true, state.value.volumeSupported)
    }

    @Test
    fun `能力判定只写一次（不会因回读翻转）`() = runTest {
        val state = MutableStateFlow(CastState(isCasting = true, castVolume = 100, volumeSupported = true))
        val t = FakeTransport(volumeResult = 70)
        val c = controller(state, t, this)
        c.syncInitialVolumeNow()
        assertEquals(true, state.value.volumeSupported) // 已 true，不应被改写或翻转
        assertEquals(70, state.value.castVolume) // 初值仍可校准
    }
}

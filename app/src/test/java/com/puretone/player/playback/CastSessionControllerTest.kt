package com.puretone.player.playback

import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.cast.CastDevice
import com.puretone.player.playback.cast.CastResult
import com.puretone.player.playback.cast.CastState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CastSessionController] 编排层单测。
 *
 * 这一层此前零覆盖的根因是它直接持有两个 final 具体类（DlnaCastManager / PlaybackAudioFocus），
 * 状态还是私有的，替身根本喂不进去。现在依赖收窄成 [CastSessionCastPort] /
 * [CastSessionFocusPort]，用记录型 fake 就能把「顺序」这类最容易写反的契约钉死：
 *
 * - 投屏前先暂停本地并释放手机焦点，再选设备投送；
 * - 快照必须在 stopCast **之前**取（stopCast 会整包清掉 CastState，电视进度当场丢失）；
 * - 被动失联时先 stopCast 再 play，否则 play 会按「仍在投屏」把命令发给已死的渲染器；
 * - 失败但仍在准备中时不能恢复本地播放，否则会打断用户刚发起的新一轮投送。
 *
 * 刻意不调 `bind()`：它会启动 250ms 无限外推 ticker，在虚拟时间下不会自行结束。
 * 进度镜像与外推的纯判据已由 `CastProgressPolicyTest` 覆盖，这里只测命令编排。
 * Robolectric 是必需的——编排过程会写 `CastNetworkLog`，纯 JVM 下 `Log.i` 未 mock 会抛。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastSessionControllerTest {

    private fun song(duration: Long = 200_000L) = SongEntity(
        id = 1L,
        mediaStoreId = 1L,
        title = "测试曲目",
        artist = "测试歌手",
        album = "测试专辑",
        albumId = 1L,
        duration = duration,
        path = "/music/test.flac",
        size = 1_000L,
        mimeType = "audio/flac"
    )

    private fun device() = CastDevice(
        id = "http://192.168.101.20:49152/description.xml",
        name = "623(192.168.101.20)",
        address = "192.168.101.20",
        isTV = true
    )

    /** 所有替身共用一条时间线，才能断言「先快照、再停投屏、最后起播」这类顺序 */
    private class Harness(scope: CoroutineScope) {
        val timeline = mutableListOf<String>()
        val castPort = FakeCastPort(timeline)
        val focus = FakeFocusPort()
        val uiState = MutableStateFlow(PlaybackUiState())

        val subject = CastSessionController(
            dlnaCast = castPort,
            playbackFocus = focus,
            uiState = uiState,
            scope = scope,
            playerProvider = { null },
            sessionProvider = { null },
            pause = { timeline += "pause-local" },
            play = { timeline += "play-local" },
            next = { timeline += "next" },
            previous = { timeline += "previous" },
            playAtIndex = { timeline += "index:$it" },
            seekTo = { timeline += "seek-local:$it" },
            followPhoneVolume = { true }
        )
    }

    private class FakeFocusPort : CastSessionFocusPort {
        var abandonCount = 0
        override fun abandon() {
            abandonCount++
        }
    }

    private class FakeCastPort(
        private val timeline: MutableList<String>
    ) : CastSessionCastPort {
        override val state: MutableStateFlow<CastState> = MutableStateFlow(CastState())
        override var progressHoldUntilMs = 0L

        var castResult: CastResult = CastResult.Success
        var stopCastThrows = false

        /** startCast 成功后会先 delay(600) 等渲染器进入 PLAYING，用信号避免测试依赖虚拟时钟 */
        val seekSignal = CompletableDeferred<Long>()

        override fun selectDevice(device: CastDevice) {
            timeline += "select:${device.address}"
        }

        override suspend fun castSong(song: SongEntity): CastResult {
            timeline += "cast:${song.id}"
            return castResult
        }

        override suspend fun seek(positionMs: Long): Boolean {
            timeline += "seek-renderer:$positionMs"
            seekSignal.complete(positionMs)
            return true
        }

        override fun noteSeekOptimistic() {
            timeline += "optimistic"
        }

        override suspend fun stopCast() {
            timeline += "stop"
            if (stopCastThrows) throw RuntimeException("模拟停止投屏异常")
        }

        override fun setVolume(volume: Int): Boolean {
            timeline += "vol:$volume"
            return true
        }

        override fun adjustVolume(delta: Int): Boolean {
            timeline += "adjust:$delta"
            return true
        }

        override suspend fun play(): Boolean {
            timeline += "renderer-play"
            return true
        }

        override suspend fun pause(): Boolean {
            timeline += "renderer-pause"
            return true
        }
    }

    private fun runHarness(block: Harness.() -> Unit) = runTest {
        // Unconfined 让 scope.launch 立即执行；startCast 里等渲染器 PLAYING 的
        // delay(600) 需要显式推进虚拟时间才会恢复，光 advanceUntilIdle 不够。
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.block()
        advanceTimeBy(1_000L)
        runCurrent()
        advanceUntilIdle()
        harness.subject.unbind()
    }

    // ---------- 开始投屏 ----------

    @Test
    fun `开始投屏先暂停本地并释放焦点再选设备投送`() = runHarness {
        uiState.value = PlaybackUiState(isPlaying = true)

        subject.startCast(device(), song())

        assertEquals(1, focus.abandonCount)
        assertEquals(
            listOf("pause-local", "select:192.168.101.20", "cast:1"),
            timeline
        )
    }

    @Test
    fun `从中段投屏成功后 seek 到本地暂停位置并延长乐观窗口`() = runBlocking {
        // 这条路径在 delay(600) 之后，虚拟时钟推不动它；改用真实调度器 + 完成信号，
        // 既不 sleep 猜时间，也不让测试绑定 coroutines-test 的调度细节。
        val harness = Harness(CoroutineScope(Dispatchers.Unconfined))
        harness.uiState.value = PlaybackUiState(isPlaying = true, positionMs = 30_000L)

        harness.subject.startCast(device(), song())
        val seeked = withTimeout(3_000L) { harness.castPort.seekSignal.await() }

        assertEquals(30_000L, seeked)
        assertTrue("timeline=${harness.timeline}", harness.timeline.contains("optimistic"))
        assertTrue(
            harness.timeline.indexOf("cast:1") < harness.timeline.indexOf("seek-renderer:30000")
        )
        harness.subject.unbind()
    }

    @Test
    fun `本地位置不足一秒半时不 seek`() = runHarness {
        uiState.value = PlaybackUiState(isPlaying = true, positionMs = 1_000L)

        subject.startCast(device(), song())

        assertFalse(timeline.any { it.startsWith("seek-renderer:") })
    }

    @Test
    fun `本地位置已超过总时长时不 seek`() = runHarness {
        uiState.value = PlaybackUiState(isPlaying = true, positionMs = 250_000L)

        subject.startCast(device(), song(duration = 200_000L))

        assertFalse(timeline.any { it.startsWith("seek-renderer:") })
    }

    @Test
    fun `首投失败且未在准备中时恢复本地播放`() = runHarness {
        castPort.castResult = CastResult.Failed("设备拒绝")

        subject.startCast(device(), song())

        assertTrue(timeline.contains("play-local"))
    }

    @Test
    fun `首投失败但仍在准备或投屏中不恢复本地播放`() = runHarness {
        castPort.castResult = CastResult.Failed("设备拒绝")
        castPort.state.value = CastState(isPreparing = true)

        subject.startCast(device(), song())

        assertFalse(timeline.contains("play-local"))
    }

    @Test
    fun `投送已过期不恢复本地播放以免打断新一轮投送`() = runHarness {
        castPort.castResult = CastResult.Superseded

        subject.startCast(device(), song())

        assertFalse(timeline.contains("play-local"))
    }

    // ---------- 停止投屏与回退 ----------

    @Test
    fun `停止投屏在 stopCast 之前取快照并从电视进度接管`() = runHarness {
        castPort.state.value = CastState(
            isCasting = true,
            progressMs = 130_000L,
            durationMs = 238_000L,
            hasProgressInfo = true
        )

        subject.stopCast()

        assertEquals("stop", timeline.first())
        assertTrue(timeline.contains("seek-local:130000"))
        assertTrue(timeline.contains("play-local"))
        assertTrue(timeline.indexOf("seek-local:130000") < timeline.indexOf("play-local"))
    }

    @Test
    fun `渲染器没给进度时停止投屏只起播不接管`() = runHarness {
        castPort.state.value = CastState(isCasting = true, hasProgressInfo = false)

        subject.stopCast()

        assertTrue(timeline.contains("play-local"))
        assertFalse(timeline.any { it.startsWith("seek-local:") })
    }

    @Test
    fun `被动失联先停投屏再本地起播并给出提示`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.subject.fallbackToLocalPlayback("投屏设备已断开")
        advanceUntilIdle()

        assertEquals(listOf("stop", "play-local"), harness.timeline)
        assertEquals("投屏设备已断开", harness.uiState.value.error)
    }

    @Test
    fun `stopCast 抛异常时回退仍会恢复本地播放`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.stopCastThrows = true
        harness.subject.fallbackToLocalPlayback("设备异常")
        advanceUntilIdle()

        assertTrue(harness.timeline.contains("play-local"))
        assertEquals("设备异常", harness.uiState.value.error)
    }

    @Test
    fun `连接失效回调走回退路径`() = runHarness {
        subject.onCastConnectionLost("设备离线")

        assertTrue(timeline.contains("stop"))
        assertTrue(timeline.contains("play-local"))
        assertEquals("设备离线", uiState.value.error)
    }

    @Test
    fun `渲染器播完推进队列`() = runHarness {
        subject.onCastTrackEnded()

        assertEquals(listOf("next"), timeline)
    }

    // ---------- 门面回调与状态透传 ----------

    @Test
    fun `门面状态与展示进度时长取自 uiState`() {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher()))
        // 1Hz 轮询值故意与统一展示态相反：门面必须跟 uiState，避免瞬时 PAUSED
        // 让通知栏/锁屏的媒体按键判反方向（真机 18:24:25 的误暂停）。
        harness.castPort.state.value = CastState(
            isCasting = true, isPlaying = false, castVolume = 42
        )
        harness.uiState.value = PlaybackUiState(
            isPlaying = true, positionMs = 12_000L, durationMs = 200_000L
        )

        assertEquals(42, harness.subject.castState.castVolume)
        assertEquals(12_000L, harness.subject.displayPositionMs)
        assertEquals(200_000L, harness.subject.displayDurationMs)
        assertTrue(harness.subject.displayIsPlaying)
        assertTrue(harness.subject.castVolumeFollowPhone)
    }

    @Test
    fun `设备明确不支持调音量时不下发音量命令`() {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher()))
        harness.castPort.state.value = CastState(isCasting = true, volumeSupported = false)

        harness.subject.setCastVolume(50)
        harness.subject.adjustCastVolume(5)

        assertTrue(harness.timeline.isEmpty())
    }

    @Test
    fun `音量命令正常下发给渲染器`() {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher()))
        harness.castPort.state.value = CastState(isCasting = true, volumeSupported = true)

        harness.subject.setCastVolume(50)
        harness.subject.adjustCastVolume(-5)

        assertEquals(listOf("vol:50", "adjust:-5"), harness.timeline)
    }

    @Test
    fun `门面播放与切歌命令转发到对应通道`() = runHarness {
        subject.playOnRenderer()
        subject.pauseOnRenderer()
        subject.seekOnRenderer(1_000L)
        subject.skipToIndex(3)
        subject.skipToNext()
        subject.skipToPrevious()

        assertTrue(timeline.contains("renderer-play"))
        assertTrue(timeline.contains("renderer-pause"))
        assertTrue(timeline.contains("seek-renderer:1000"))
        assertTrue(timeline.contains("index:3"))
        assertTrue(timeline.contains("next"))
        assertTrue(timeline.contains("previous"))
    }
}

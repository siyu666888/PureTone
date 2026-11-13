package com.puretone.player.playback

import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.cast.CastDevice
import com.puretone.player.playback.cast.CastQueuePolicy
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
            followPhoneVolume = { true },
            recastCurrent = { timeline += "recast-current" }
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
        /** play() 是否把渲染器拉回 PLAYING（模拟 seek-recover 成功/失败两条路径） */
        var playRecovers: Boolean = true

        /** startCast 成功后会先 delay(600) 等渲染器进入 PLAYING，用信号避免测试依赖虚拟时钟 */
        val seekSignal = CompletableDeferred<Long>()

        /**
         * 生产侧是「先 seek()、返回后才 noteSeekOptimistic()」。
         * 旧实现只等 [seekSignal]，await 恢复时乐观窗口可能尚未写入 timeline，
         * 造成偶发 AssertionError（并非产品缺陷）。补一个完成信号，断言不放宽，
         * 只是等到完整动作结束。
         */
        val optimisticSignal = CompletableDeferred<Unit>()

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
            optimisticSignal.complete(Unit)
        }

        override fun noteUserSeek() {
            timeline += "user-seek"
        }

        override fun markAutoAdvanceNext() {
            timeline += "mark-auto-advance"
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

        /** 记录命令来源：验证通知栏与 App 内路径在诊断里可区分（C3） */
        val commandSources = mutableListOf<String>()

        override suspend fun play(source: String): Boolean {
            timeline += "renderer-play"
            commandSources += source
            if (playRecovers) {
                state.value = state.value.copy(playbackState = CastQueuePolicy.STATE_PLAYING)
            }
            return true
        }

        override suspend fun pause(source: String): Boolean {
            timeline += "renderer-pause"
            commandSources += source
            return true
        }
    }

    private fun runHarness(block: Harness.() -> Unit) = runTest {
        // Unconfined 让 scope.launch 立即执行；startCast 里等渲染器 PLAYING 的
        // delay(600) 需要显式推进虚拟时间才会恢复，光 advanceUntilIdle 不够。
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.block()
        // 曲末换曲握手是 1800ms，跑满 2s 才能让「推进队列」这类断言稳定。
        advanceTimeBy(2_000L)
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
        // 生产顺序是 seek() 返回后才 noteSeekOptimistic()；等这一步完成再查 timeline
        withTimeout(3_000L) { harness.castPort.optimisticSignal.await() }

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
    fun `曲末播完经终止态握手后才推进队列`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(
            isCasting = true, isPlaying = false, playbackState = "STOPPED", pushGeneration = 3L
        )

        harness.subject.onCastTrackEnded()
        // 关键：STOPPED 那一秒绝不能立刻 SetAVTransportURI 换 URI（184914 18:45:37）
        assertFalse(harness.timeline.contains("next"))
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(harness.timeline.contains("next"))

        advanceTimeBy(1_000L)
        runCurrent()
        advanceUntilIdle()
        // F1：推进前必须先标记「曲末自动推进」，让建立确认放宽且不重发（224000）
        assertEquals(listOf("mark-auto-advance", "next"), harness.timeline)
        harness.subject.unbind()
    }

    // ---------- C1：曲末换曲握手的取消条件 ----------

    @Test
    fun `握手期间用户停止投屏则放弃推进`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(isCasting = true, pushGeneration = 3L)
        harness.subject.onCastTrackEnded()
        harness.castPort.state.value = CastState(isCasting = false, pushGeneration = 3L)
        advanceTimeBy(2_000L)
        runCurrent()
        advanceUntilIdle()

        assertFalse(harness.timeline.contains("next"))
        harness.subject.unbind()
    }

    @Test
    fun `握手期间手动切歌换代次则放弃推进`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(isCasting = true, pushGeneration = 3L)
        harness.subject.onCastTrackEnded()
        harness.castPort.state.value = CastState(isCasting = true, pushGeneration = 4L)
        advanceTimeBy(2_000L)
        runCurrent()
        advanceUntilIdle()

        assertFalse(harness.timeline.contains("next"))
        harness.subject.unbind()
    }

    @Test
    fun `握手期间电视自己恢复播放则不换URI`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(isCasting = true, pushGeneration = 3L)
        harness.subject.onCastTrackEnded()
        harness.castPort.state.value = CastState(
            isCasting = true, isPlaying = true, pushGeneration = 3L
        )
        advanceTimeBy(2_000L)
        runCurrent()
        advanceUntilIdle()

        assertFalse(harness.timeline.contains("next"))
        harness.subject.unbind()
    }

    // ---------- C2：末首播完收尾 ----------

    @Test
    fun `末首播完结束投屏并写停止态_不自动续播`() = runHarness {
        castPort.state.value = CastState(isCasting = true, isPlaying = false)
        uiState.value = PlaybackUiState(isPlaying = true)

        subject.onCastQueueFinished()

        assertTrue(timeline.contains("stop"))
        assertFalse("产品语义：结束投屏并回本地，不自动续播", timeline.contains("play-local"))
        assertFalse(uiState.value.isPlaying)
        assertEquals("投屏已播完最后一首，已结束投屏", uiState.value.error)
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

    @Test
    fun `通知栏seek即时写进度并延长乐观窗口_203641回归`() = runHarness {
        subject.seekOnRenderer(30_000L)

        // 与 App 内 PlaybackController.seekTo 对齐：立即写 uiState + 乐观窗口，
        // 否则 1Hz 镜像把「seek 前的旧值」拉回，进度条和电视对不上。
        assertEquals(30_000L, uiState.value.positionMs)
        assertTrue(timeline.contains("optimistic"))
        assertTrue(timeline.contains("seek-renderer:30000"))
    }

    @Test
    fun `曲中停止重投当前曲并续播到中断位置_绝不推进队列`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(isCasting = true, isEstablished = true)

        harness.subject.onCastInterrupted(28_000L)
        advanceTimeBy(1_000L)
        runCurrent()
        advanceUntilIdle()

        assertTrue(harness.timeline.contains("recast-current"))
        assertTrue(harness.timeline.contains("seek-renderer:28000"))
        assertTrue(harness.timeline.contains("optimistic"))
        assertFalse(harness.timeline.contains("next"))
        assertEquals("投屏中断，已自动重连当前曲目", harness.uiState.value.error)
        harness.subject.unbind()
    }

    // ---------- A 方案：近期拖动曲中 STOPPED 的就地 play 恢复（174553 体验层修复） ----------

    @Test
    fun `近期拖动曲中STOPPED先原地play恢复_不整套重投`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(isCasting = true, isEstablished = true)
        harness.castPort.playRecovers = true

        harness.subject.onCastInterruptedSeekRecover(28_000L)
        advanceTimeBy(2_000L)
        runCurrent()
        advanceUntilIdle()

        // 渲染器被 play 拉回 PLAYING → 就地恢复，不进整套重投（无静音拉长）
        assertFalse("不应整套重投", harness.timeline.contains("recast-current"))
        assertFalse("不应再 seek 渲染器", harness.timeline.contains("seek-renderer:28000"))
        assertFalse(harness.timeline.contains("optimistic"))
        assertEquals(listOf("seek-recover"), harness.castPort.commandSources)
        harness.subject.unbind()
    }

    @Test
    fun `近期拖动曲中STOPPED原地play失败则降级整套重投`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(
            isCasting = true, isEstablished = true, playbackState = CastQueuePolicy.STATE_STOPPED
        )
        harness.castPort.playRecovers = false

        harness.subject.onCastInterruptedSeekRecover(28_000L)
        advanceTimeBy(2_000L)
        runCurrent()
        advanceUntilIdle()

        // play 未拉回（渲染器仍 STOPPED）→ 降级整套重投当前曲并续播到中断位置
        assertTrue(harness.timeline.contains("recast-current"))
        assertTrue(harness.timeline.contains("seek-renderer:28000"))
        assertTrue(harness.timeline.contains("optimistic"))
        assertFalse(harness.timeline.contains("next"))
        assertEquals("投屏中断，已自动重连当前曲目", harness.uiState.value.error)
        harness.subject.unbind()
    }

    // ---------- C3：通知栏 / 锁屏播放态闭环与命令回读确认 ----------

    @Test
    fun `通知栏Play即时写展示态_回读仍未播则重发再重投`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(
            isCasting = true, isPlaying = false, isEstablished = true
        )
        harness.uiState.value = PlaybackUiState(isPlaying = false)

        harness.subject.playOnRenderer()

        // 旧实现完全不写 uiState，门面 isPlaying() 永远读不到命令结果，
        // 用户点「播放」被取反成 Pause —— 真机 184914 现象③。
        assertTrue(harness.uiState.value.isPlaying)
        assertEquals(listOf("notification"), harness.castPort.commandSources)

        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(
            listOf("notification", "notification-retry"),
            harness.castPort.commandSources
        )

        advanceTimeBy(3_000L)
        runCurrent()
        advanceUntilIdle()
        assertTrue(harness.timeline.contains("recast-current"))
        assertEquals("电视未响应播放，已重新投送当前曲目", harness.uiState.value.error)
        harness.subject.unbind()
    }

    @Test
    fun `通知栏Play回读已在播则不重发也不重投`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(
            isCasting = true, isPlaying = false, isEstablished = true
        )
        harness.subject.playOnRenderer()
        // 渲染器回读已确认 PLAYING
        harness.castPort.state.value = CastState(
            isCasting = true, isPlaying = true, isEstablished = true
        )
        advanceTimeBy(4_000L)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("notification"), harness.castPort.commandSources)
        assertFalse(harness.timeline.contains("recast-current"))
        harness.subject.unbind()
    }

    @Test
    fun `Play回读期间App内暂停则放弃补救_不把渲染器又拉起来`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(
            isCasting = true, isPlaying = false, isEstablished = true
        )
        harness.uiState.value = PlaybackUiState(isPlaying = false)

        harness.subject.playOnRenderer()
        // App 内 PlaybackController.pause() 只写 uiState，不经过 pauseOnRenderer，
        // 因此不会 cancel 在途的回读确认 job —— 这里必须靠展示态校验挡住。
        harness.uiState.value = PlaybackUiState(isPlaying = false)
        advanceTimeBy(7_000L)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("notification"), harness.castPort.commandSources)
        assertFalse(harness.timeline.contains("recast-current"))
        harness.subject.unbind()
    }

    @Test
    fun `通知栏Pause即时写暂停态并取消在途回读确认`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(
            isCasting = true, isPlaying = false, isEstablished = true
        )
        harness.uiState.value = PlaybackUiState(isPlaying = true)

        harness.subject.playOnRenderer()
        harness.subject.pauseOnRenderer()
        advanceTimeBy(7_000L)
        runCurrent()
        advanceUntilIdle()

        assertFalse(harness.uiState.value.isPlaying)
        assertEquals(listOf("notification", "notification"), harness.castPort.commandSources)
        assertFalse("暂停必须取消在途的 Play 回读确认", harness.timeline.contains("recast-current"))
        harness.subject.unbind()
    }

    // ---------- R4-2：seek-recover 异步窗口的代次守护与提示清空边界 ----------

    @Test
    fun `观察窗内切歌则放弃恢复且不覆盖新提示`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(
            isCasting = true, isEstablished = true, pushGeneration = 1L
        )
        harness.uiState.value = PlaybackUiState(currentSongId = 1L)
        harness.castPort.playRecovers = true

        harness.subject.onCastInterruptedSeekRecover(28_000L)
        // 观察窗内用户切歌：代次和曲目都变了，还写入了一条新提示
        harness.castPort.state.value = harness.castPort.state.value.copy(pushGeneration = 2L)
        harness.uiState.value = harness.uiState.value.copy(currentSongId = 2L, error = "新建的提示")
        advanceTimeBy(2_000L)
        runCurrent()
        advanceUntilIdle()

        assertFalse(harness.timeline.contains("recast-current"))
        assertFalse(harness.timeline.contains("seek-renderer:28000"))
        assertEquals("新建的提示", harness.uiState.value.error)
        harness.subject.unbind()
    }

    @Test
    fun `原地恢复成功只清自己写入的提示`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(isCasting = true, isEstablished = true)
        harness.castPort.playRecovers = true

        harness.subject.onCastInterruptedSeekRecover(28_000L)
        // 恢复期间另有新提示写入：旧实现会用 error=null 把它抹掉
        harness.uiState.value = harness.uiState.value.copy(error = "用户的新提示")
        advanceTimeBy(2_000L)
        runCurrent()
        advanceUntilIdle()

        assertEquals("用户的新提示", harness.uiState.value.error)
        assertFalse(harness.timeline.contains("recast-current"))
        harness.subject.unbind()
    }

    @Test
    fun `降级重投后若已切歌则不把旧位置写回新曲`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(
            isCasting = true,
            isEstablished = true,
            playbackState = CastQueuePolicy.STATE_STOPPED
        )
        harness.uiState.value = PlaybackUiState(currentSongId = 1L)
        harness.castPort.playRecovers = false

        harness.subject.onCastInterruptedSeekRecover(28_000L)
        // 观察窗走完、进入降级重投后的第二个 delay
        advanceTimeBy(900L)
        runCurrent()
        assertTrue(harness.timeline.contains("recast-current"))

        // 重投自身会自增 pushGeneration，所以这里只改曲目标识来模拟期间切歌
        harness.uiState.value = harness.uiState.value.copy(currentSongId = 2L)
        advanceTimeBy(900L)
        runCurrent()
        advanceUntilIdle()

        assertFalse(harness.timeline.contains("seek-renderer:28000"))
        assertFalse(harness.timeline.contains("optimistic"))
        harness.subject.unbind()
    }

    // ---------- 第 6 刀：既有 onCastInterrupted 的异步 seek 窗口守护 ----------

    @Test
    fun `曲中重投观察窗内切歌则放弃回写旧位置`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(
            isCasting = true, isEstablished = true, pushGeneration = 1L
        )
        harness.uiState.value = PlaybackUiState(currentSongId = 1L)

        harness.subject.onCastInterrupted(28_000L)
        // 800ms 观察窗内用户切歌：代次与曲目都变了，还写入了一条新提示
        harness.castPort.state.value = harness.castPort.state.value.copy(pushGeneration = 2L)
        harness.uiState.value = harness.uiState.value.copy(currentSongId = 2L, error = "新建的提示")
        advanceTimeBy(1_000L)
        runCurrent()
        advanceUntilIdle()

        assertTrue("重投本身仍应发生", harness.timeline.contains("recast-current"))
        assertFalse(harness.timeline.contains("seek-renderer:28000"))
        assertFalse(harness.timeline.contains("optimistic"))
        assertEquals("新建的提示", harness.uiState.value.error)
        harness.subject.unbind()
    }

    @Test
    fun `曲中重投观察窗内结束投屏则放弃回写`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(isCasting = true, isEstablished = true)
        harness.uiState.value = PlaybackUiState(currentSongId = 1L)

        harness.subject.onCastInterrupted(28_000L)
        harness.castPort.state.value = harness.castPort.state.value.copy(isCasting = false)
        advanceTimeBy(1_000L)
        runCurrent()
        advanceUntilIdle()

        assertTrue(harness.timeline.contains("recast-current"))
        assertFalse(harness.timeline.contains("seek-renderer:28000"))
        assertFalse(harness.timeline.contains("optimistic"))
        harness.subject.unbind()
    }

    @Test
    fun `重投自增代次但曲目未变时仍要seek回中断位置`() = runTest {
        val harness = Harness(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        harness.castPort.state.value = CastState(
            isCasting = true, isEstablished = true, pushGeneration = 1L
        )
        harness.uiState.value = PlaybackUiState(currentSongId = 1L)

        harness.subject.onCastInterrupted(28_000L)
        // 模拟 recastCurrent() 的异步 castSong 自增代次：不能因此误判为「已换代」而丢弃续播
        harness.castPort.state.value = harness.castPort.state.value.copy(pushGeneration = 2L)
        advanceTimeBy(1_000L)
        runCurrent()
        advanceUntilIdle()

        assertTrue(harness.timeline.contains("recast-current"))
        assertTrue(harness.timeline.contains("seek-renderer:28000"))
        assertTrue(harness.timeline.contains("optimistic"))
        assertFalse(harness.timeline.contains("next"))
        harness.subject.unbind()
    }
}

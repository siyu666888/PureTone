package com.puretone.player.playback.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 投屏队列协调器的时序测试。
 *
 * 这里锁的是**状态机**的推演，判定本身的数值边界由 [CastQueuePolicyTest] 覆盖。
 * 重点是那些「错了不会立刻报错、而是在几十秒后才听得出来」的行为：
 *
 * - 手动切歌会不会被误判成「自然播完」而凭空多跳一首；
 * - 推进之后会不会因为渲染器迟迟不启动而把整个队列一路跳空；
 * - 失联回退会不会反复触发，把用户正在听的本地播放反复打断。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastQueueCoordinatorTest {

    private class FakeSink : CastQueueSink {
        var endedCount: Int = 0
        var lostCount: Int = 0
        var interruptedCount: Int = 0
        var finishedCount: Int = 0
        var lastInterruptProgressMs: Long = -1L
        var lastReason: String? = null

        override fun onCastTrackEnded() {
            endedCount++
        }

        override fun onCastQueueFinished() {
            finishedCount++
        }

        override fun onCastInterrupted(progressMs: Long) {
            interruptedCount++
            lastInterruptProgressMs = progressMs
        }

        override fun onCastConnectionLost(reason: String) {
            lostCount++
            lastReason = reason
        }
    }

    private val total = 300_000L

    private fun state(
        isCasting: Boolean = true,
        isPlaying: Boolean = false,
        playbackState: String = CastQueuePolicy.STATE_STOPPED,
        progressMs: Long = total,
        durationMs: Long = total,
        hasProgressInfo: Boolean = true,
        pushGeneration: Long = 1L,
        isConnectionLost: Boolean = false,
        error: String? = null,
        castStartMs: Long = 0L,
        lastFetchMs: Long = 0L
    ) = CastState(
        isCasting = isCasting,
        isPlaying = isPlaying,
        playbackState = playbackState,
        progressMs = progressMs,
        durationMs = durationMs,
        hasProgressInfo = hasProgressInfo,
        pushGeneration = pushGeneration,
        isConnectionLost = isConnectionLost,
        error = error,
        castStartMs = castStartMs,
        lastFetchMs = lastFetchMs
    )

    private fun coordinator(
        sink: FakeSink,
        scope: TestScope,
        hasNext: Boolean = true,
        localDurationMs: Long = 0L
    ) = CastQueueCoordinator(
        scope = scope,
        castState = MutableStateFlow(state()),
        queueInfo = { CastQueueInfo(hasNext = hasNext, currentDurationMs = localDurationMs) },
        sink = sink
    )

    // ---------- 自然播完 → 推进 ----------

    @Test
    fun `渲染器播完后触发一次推进`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this)
        // 第一轮：投送刚发生，isPlaying 描述的是上一首 → 本轮不采信
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        // 第二轮：新曲在播
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        // 第三轮：播完了
        c.onState(
            state(
                pushGeneration = 1L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = total
            )
        )
        assertEquals(1, sink.endedCount)
    }

    @Test
    fun `STOPPED 且进度完全未知时仍推进（v1_9_2 真机回归）`() = runTest {
        // 极光TV 曲目结束后 STOPPED，GetPositionInfo 返回 500：
        // progressMs/durationMs 皆 0、hasProgressInfo=false。旧链路在此永久卡住，
        // 手动点下一首却因绕过本协调器而能续投——正是用户反馈的现象。
        val sink = FakeSink()
        val c = coordinator(sink, this)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(
            state(
                pushGeneration = 1L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 0L,
                durationMs = 0L,
                hasProgressInfo = false
            )
        )
        assertEquals(1, sink.endedCount)
    }

    @Test
    fun `推进后未重新观测到在播则不重复推进`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(state(pushGeneration = 1L, isPlaying = false, progressMs = total))
        assertEquals(1, sink.endedCount)
        // 新曲投送耗时（APE 转码），渲染器迟迟不启动 → 不能再推进，否则把队列跳空
        c.onState(state(pushGeneration = 2L, isPlaying = false, progressMs = 0L))
        c.onState(state(pushGeneration = 2L, isPlaying = false, progressMs = 0L))
        c.onState(state(pushGeneration = 2L, isPlaying = false, progressMs = 0L))
        assertEquals(1, sink.endedCount)
    }

    @Test
    fun `重新观测到在播并再次播完后可再次推进`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(state(pushGeneration = 1L, isPlaying = false, progressMs = total))
        // 第二首：投送 → 在播 → 播完
        c.onState(state(pushGeneration = 2L, isPlaying = false))
        c.onState(state(pushGeneration = 2L, isPlaying = true, progressMs = 0L))
        c.onState(state(pushGeneration = 2L, isPlaying = false, progressMs = total))
        assertEquals(2, sink.endedCount)
    }

    // ---------- 手动切歌不得被误判 ----------

    @Test
    fun `手动切歌导致代次变化时不会被误判为播完`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this)
        // 正在播第一首
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        // 用户手动切歌 → 新的投送代次；这一轮 isPlaying 仍是旧的（渲染器还没切换）
        c.onState(state(pushGeneration = 2L, isPlaying = true, progressMs = 120_000L))
        // 渲染器切换窗口：旧曲恰好播完，上报停止态 + 旧曲末尾进度
        c.onState(
            state(
                pushGeneration = 2L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = total
            )
        )
        assertEquals(0, sink.endedCount)
    }

    @Test
    fun `用户中途暂停不会被误判为播完`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        // 暂停在曲中
        c.onState(
            state(
                pushGeneration = 1L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_PAUSED,
                progressMs = 30_000L
            )
        )
        assertEquals(0, sink.endedCount)
    }

    @Test
    fun `队列末曲播完不推进但触发收尾`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this, hasNext = false)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(state(pushGeneration = 1L, isPlaying = false, progressMs = total))
        assertEquals(0, sink.endedCount)
        // 真机 20261107_184914：18:48:40 STOPPED 之后电视已退出画面，
        // 旧实现在这里什么都没做，手机一直显示「已连接」。
        assertEquals(1, sink.finishedCount)
    }

    // ---------- 末首收尾（C2） ----------

    @Test
    fun `末首收尾每轮投送只触发一次`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this, hasNext = false)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        repeat(4) {
            c.onState(state(pushGeneration = 1L, isPlaying = false, progressMs = total))
        }
        assertEquals(1, sink.finishedCount)
        assertEquals(0, sink.endedCount)
    }

    @Test
    fun `末首用户暂停不会结束投屏`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this, hasNext = false)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(
            state(
                pushGeneration = 1L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_PAUSED,
                progressMs = 30_000L
            )
        )
        assertEquals(0, sink.finishedCount)
    }

    @Test
    fun `有下一首时只推进不收尾`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this, hasNext = true)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(state(pushGeneration = 1L, isPlaying = false, progressMs = total))
        assertEquals(1, sink.endedCount)
        assertEquals(0, sink.finishedCount)
    }

    @Test
    fun `新一轮投送后可再次触发末首收尾`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this, hasNext = false)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(state(pushGeneration = 1L, isPlaying = false, progressMs = total))
        // 用户重新投屏 → 代次变化 → 收尾去重标记复位
        c.onState(state(pushGeneration = 2L, isPlaying = false))
        c.onState(state(pushGeneration = 2L, isPlaying = true, progressMs = 0L))
        c.onState(state(pushGeneration = 2L, isPlaying = false, progressMs = total))
        assertEquals(2, sink.finishedCount)
    }

    // ---------- 失联回退 ----------

    @Test
    fun `设备失联触发一次回退本地`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(
            state(
                pushGeneration = 1L,
                isCasting = false,
                isPlaying = false,
                isConnectionLost = true,
                error = "投屏设备已断开"
            )
        )
        assertEquals(1, sink.lostCount)
        assertEquals("投屏设备已断开", sink.lastReason)
    }

    @Test
    fun `失联状态重复上报不重复触发回退`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this)
        val lost = state(pushGeneration = 1L, isCasting = false, isConnectionLost = true, error = "已断开")
        c.onState(lost)
        c.onState(lost)
        c.onState(lost)
        assertEquals(1, sink.lostCount)
    }

    @Test
    fun `新一轮投送后可再次触发失联回退`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this)
        c.onState(state(pushGeneration = 1L, isCasting = false, isConnectionLost = true, error = "已断开"))
        assertEquals(1, sink.lostCount)
        // 用户重新投屏 → 代次变化 → 失联标记复位
        c.onState(state(pushGeneration = 2L, isPlaying = false))
        c.onState(state(pushGeneration = 2L, isPlaying = true, progressMs = 0L))
        c.onState(state(pushGeneration = 2L, isCasting = false, isConnectionLost = true, error = "再次断开"))
        assertEquals(2, sink.lostCount)
    }

    // ---------- 未投屏 ----------

    @Test
    fun `未投屏时收到停止态不推进`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this)
        c.onState(state(isCasting = false, isPlaying = false, pushGeneration = 0L))
        c.onState(state(isCasting = false, isPlaying = false, pushGeneration = 0L))
        assertEquals(0, sink.endedCount)
    }

    // ---------- 真实状态流路径 ----------

    @Test
    fun `通过状态流订阅同样能推进`() = runTest {
        val sink = FakeSink()
        val flow = MutableStateFlow(state(pushGeneration = 1L, isPlaying = false))
        val c = CastQueueCoordinator(
            scope = this,
            castState = flow,
            queueInfo = { CastQueueInfo(hasNext = true, currentDurationMs = 0L) },
            sink = sink
        )
        c.start()
        advanceUntilIdle()

        flow.value = state(pushGeneration = 1L, isPlaying = true, progressMs = 0L)
        advanceUntilIdle()
        flow.value = state(pushGeneration = 1L, isPlaying = false, progressMs = total)
        advanceUntilIdle()

        assertEquals(1, sink.endedCount)
        c.stop()
    }

    @Test
    fun `停止后不再响应状态变化`() = runTest {
        val sink = FakeSink()
        val flow = MutableStateFlow(state(pushGeneration = 1L, isPlaying = false))
        val c = CastQueueCoordinator(
            scope = this,
            castState = flow,
            queueInfo = { CastQueueInfo(hasNext = true, currentDurationMs = 0L) },
            sink = sink
        )
        c.start()
        advanceUntilIdle()
        flow.value = state(pushGeneration = 1L, isPlaying = true, progressMs = 0L)
        advanceUntilIdle()
        c.stop()
        flow.value = state(pushGeneration = 1L, isPlaying = false, progressMs = total)
        advanceUntilIdle()
        assertEquals(0, sink.endedCount)
    }

    // ---------- 曲中 STOPPED：确认窗口 + 重投当前曲（2026-11-07 真机「电视画面断开」修复） ----------

    /** 真机 16:55:25 现场：28 秒 / 344 秒的曲中停止，本轮投出后墙钟已走 188 秒 */
    private fun midTrackState(fetchMs: Long = 0L) = state(
        pushGeneration = 1L,
        isPlaying = false,
        playbackState = CastQueuePolicy.STATE_STOPPED,
        progressMs = 28_000L,
        durationMs = 344_000L,
        castStartMs = 1_000L,
        lastFetchMs = fetchMs
    )

    private fun midTrackCoordinator(
        sink: FakeSink,
        scope: TestScope,
        now: () -> Long
    ) = CastQueueCoordinator(
        scope = scope,
        castState = MutableStateFlow(state()),
        queueInfo = { CastQueueInfo(hasNext = true, currentDurationMs = 344_000L) },
        sink = sink,
        nowProvider = now,
        confirmMs = 2_500L
    )

    /** 三帧走完「代次首轮 → 在播 → 曲中 STOPPED」 */
    private fun CastQueueCoordinator.feedMidTrackStop() {
        onState(state(pushGeneration = 1L, isPlaying = false))
        onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        onState(midTrackState())
    }

    @Test
    fun `曲中STOPPED 不立即推进_窗口到期才重投当前曲`() = runTest {
        val sink = FakeSink()
        var now = 189_000L
        val c = midTrackCoordinator(sink, this) { now }

        c.feedMidTrackStop()
        assertEquals(0, sink.endedCount)
        assertEquals(0, sink.interruptedCount)

        now += 1_000L
        c.onState(midTrackState())
        assertEquals(0, sink.interruptedCount)

        now += 2_000L
        c.onState(midTrackState())
        assertEquals(0, sink.endedCount) // 绝不跳下一首——跳了就会顶掉电视正在做的重连
        assertEquals(1, sink.interruptedCount)
        assertEquals(28_000L, sink.lastInterruptProgressMs)
    }

    @Test
    fun `曲中STOPPED 后窗口内回到PLAYING 视为电视自己重连成功`() = runTest {
        val sink = FakeSink()
        var now = 189_000L
        val c = midTrackCoordinator(sink, this) { now }

        c.feedMidTrackStop()
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 30_000L))
        now += 5_000L
        c.onState(midTrackState())
        assertEquals(0, sink.interruptedCount)
        assertEquals(0, sink.endedCount)
    }

    @Test
    fun `曲中STOPPED 后电视又来取流则窗口顺延不打断重连`() = runTest {
        val sink = FakeSink()
        var now = 189_000L
        val c = midTrackCoordinator(sink, this) { now }

        c.feedMidTrackStop()
        now += 2_500L
        val fetchAt = now
        c.onState(midTrackState(fetchMs = fetchAt)) // 与 own-state STOPPED 同秒的 http-fetch
        assertEquals(0, sink.interruptedCount)

        now += 2_400L
        c.onState(midTrackState(fetchMs = fetchAt))
        assertEquals(0, sink.interruptedCount)

        now += 200L
        c.onState(midTrackState(fetchMs = fetchAt))
        assertEquals(1, sink.interruptedCount)
    }

    @Test
    fun `曲末STOPPED 仍立即推进_历史曲末卡死修复不回归`() = runTest {
        val sink = FakeSink()
        var now = 350_000L
        val c = midTrackCoordinator(sink, this) { now }

        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(
            state(
                pushGeneration = 1L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 300_000L,
                durationMs = 300_000L,
                castStartMs = 1_000L
            )
        )
        assertEquals(1, sink.endedCount)
        assertEquals(0, sink.interruptedCount)
    }

    @Test
    fun `投送完成时刻未知时保持原有立即推进语义`() = runTest {
        val sink = FakeSink()
        var now = 189_000L
        val c = midTrackCoordinator(sink, this) { now }

        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(
            state(
                pushGeneration = 1L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 28_000L,
                durationMs = 344_000L,
                castStartMs = 0L
            )
        )
        assertEquals(1, sink.endedCount)
        assertEquals(0, sink.interruptedCount)
    }

    // ---------- D2：确认窗口定时器兜底（真机 20261107_203641） ----------

    @Test
    fun `STOPPED后无新状态帧时_定时器兜底触发重投`() = runTest {
        val sink = FakeSink()
        val base = 189_000L
        val c = CastQueueCoordinator(
            scope = this,
            castState = MutableStateFlow(state()),
            queueInfo = { CastQueueInfo(hasNext = true, currentDurationMs = 344_000L) },
            sink = sink,
            nowProvider = { base + testScheduler.currentTime },
            confirmMs = 2_500L
        )
        c.feedMidTrackStop()
        assertEquals(0, sink.interruptedCount)

        // 关键：不喂任何新状态帧，只推进虚拟时间。旧实现靠「下一帧恰好会来」驱动
        // 到期判定，STOPPED 后状态稳定时 StateFlow 不发帧 → 永久卡死；新实现由
        // 独立定时器兜底，到点即重投当前曲（电视黑屏不再等用户手动切歌）。
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(1, sink.interruptedCount)
        assertEquals(28_000L, sink.lastInterruptProgressMs)
        assertEquals(0, sink.endedCount)
    }

    @Test
    fun `确认窗口内电视又来取流_定时器不误触发并顺延`() = runTest {
        val sink = FakeSink()
        val base = 189_000L
        val c = CastQueueCoordinator(
            scope = this,
            castState = MutableStateFlow(state()),
            queueInfo = { CastQueueInfo(hasNext = true, currentDurationMs = 344_000L) },
            sink = sink,
            nowProvider = { base + testScheduler.currentTime },
            confirmMs = 2_500L
        )
        c.feedMidTrackStop()
        // 窗口内（2s）电视又取流：顺延窗口，定时器不触发
        advanceTimeBy(2_000L)
        runCurrent()
        c.onState(midTrackState(fetchMs = base + testScheduler.currentTime))
        assertEquals(0, sink.interruptedCount)

        // 顺延后再走满一个确认窗口，定时器才触发
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(1, sink.interruptedCount)
    }

    // ---------- E2：连续同位置重投上限（真机 20261107_214943 死循环兜底） ----------

    @Test
    fun `连续同位置曲中停止_重投2次后改按曲末推进`() = runTest {
        val sink = FakeSink()
        var now = 189_000L
        val c = midTrackCoordinator(sink, this) { now }

        // 第一次：代次 1 曲中停止在 28s，窗口到期触发第一次重投
        c.feedMidTrackStop()
        now += 3_000L
        c.onState(midTrackState())
        assertEquals(1, sink.interruptedCount)
        assertEquals(0, sink.endedCount)

        // 模拟 recast 后新代次 2：电视又 PLAYING、又在同一 28s 位置 STOPPED
        val recastAt = now
        c.onState(state(pushGeneration = 2L, isPlaying = false))
        c.onState(state(pushGeneration = 2L, isPlaying = true, progressMs = 0L))
        now += 2_000L
        c.onState(
            state(
                pushGeneration = 2L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 28_000L,
                durationMs = 344_000L,
                castStartMs = recastAt
            )
        )
        now += 3_000L
        c.onState(
            state(
                pushGeneration = 2L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 28_000L,
                durationMs = 344_000L,
                castStartMs = recastAt
            )
        )

        // 第二次同位置中断：不再重投当前曲，改为按曲末推进下一首
        assertEquals(1, sink.interruptedCount)
        assertEquals(1, sink.endedCount)
        assertEquals(0, sink.finishedCount)
    }

    @Test
    fun `末首连续同位置曲中停止_重投2次后结束会话`() = runTest {
        val sink = FakeSink()
        var now = 189_000L
        val c = CastQueueCoordinator(
            scope = this,
            castState = MutableStateFlow(state()),
            queueInfo = { CastQueueInfo(hasNext = false, currentDurationMs = 344_000L) },
            sink = sink,
            nowProvider = { now },
            confirmMs = 2_500L
        )

        // 末首：代次 1 曲中停止在 28s，窗口到期触发第一次重投
        c.feedMidTrackStop()
        now += 3_000L
        c.onState(midTrackState())
        assertEquals(1, sink.interruptedCount)

        // 模拟 recast 后新代次 2：又在同一 28s 位置 STOPPED
        val recastAt = now
        c.onState(state(pushGeneration = 2L, isPlaying = false))
        c.onState(state(pushGeneration = 2L, isPlaying = true, progressMs = 0L))
        now += 2_000L
        c.onState(
            state(
                pushGeneration = 2L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 28_000L,
                durationMs = 344_000L,
                castStartMs = recastAt
            )
        )
        now += 3_000L
        c.onState(
            state(
                pushGeneration = 2L,
                isPlaying = false,
                playbackState = CastQueuePolicy.STATE_STOPPED,
                progressMs = 28_000L,
                durationMs = 344_000L,
                castStartMs = recastAt
            )
        )

        // 第二次同位置中断且无下一首：结束会话而非推进
        assertEquals(1, sink.interruptedCount)
        assertEquals(1, sink.finishedCount)
        assertEquals(0, sink.endedCount)
    }
}

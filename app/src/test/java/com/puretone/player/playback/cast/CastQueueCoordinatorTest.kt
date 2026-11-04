package com.puretone.player.playback.cast

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
        var lastReason: String? = null

        override fun onCastTrackEnded() {
            endedCount++
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
        error: String? = null
    ) = CastState(
        isCasting = isCasting,
        isPlaying = isPlaying,
        playbackState = playbackState,
        progressMs = progressMs,
        durationMs = durationMs,
        hasProgressInfo = hasProgressInfo,
        pushGeneration = pushGeneration,
        isConnectionLost = isConnectionLost,
        error = error
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
    fun `队列末曲播完不推进`() = runTest {
        val sink = FakeSink()
        val c = coordinator(sink, this, hasNext = false)
        c.onState(state(pushGeneration = 1L, isPlaying = false))
        c.onState(state(pushGeneration = 1L, isPlaying = true, progressMs = 0L))
        c.onState(state(pushGeneration = 1L, isPlaying = false, progressMs = total))
        assertEquals(0, sink.endedCount)
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
}

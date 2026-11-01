package com.puretone.player.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放事件纯逻辑单测（P1-1 拆分第六刀的可测性收益）。
 *
 * 这些类不依赖 ExoPlayer，可在纯 JVM 上验证——正是把它们从 [PlaybackManager]
 * 抽出来的目的。测试锁定两类易错语义：
 * - [PlaybackEventClassifier]：转场原因 → 自然推进 / 新开始 / 用户手动 的判定；
 * - [CompletionRecorder]：5s 窗口去重，避免 AUTO 过渡与 STATE_ENDED 双计播放完成。
 */
class PlaybackEventTest {

    // ---------- 转场分类 ----------

    @Test
    fun `自然推进：AUTO 与 REPEAT 计为自然推进`() {
        assertTrue(PlaybackEventClassifier.isNaturalAdvance(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertTrue(PlaybackEventClassifier.isNaturalAdvance(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT))
    }

    @Test
    fun `非自然推进：SEEK 与 PLAYLIST_CHANGED 不计为自然推进`() {
        assertFalse(PlaybackEventClassifier.isNaturalAdvance(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK))
        assertFalse(PlaybackEventClassifier.isNaturalAdvance(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED))
    }

    @Test
    fun `新开始：换队列且换了歌才算`() {
        assertTrue(
            PlaybackEventClassifier.isFreshStart(
                prevId = 1L, songId = 2L,
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            )
        )
    }

    @Test
    fun `非新开始：换队列但同一首、或用户手动切歌都不算`() {
        // 换队列但仍是同一首（如重复点击）：不算新开始
        assertFalse(
            PlaybackEventClassifier.isFreshStart(
                prevId = 1L, songId = 1L,
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            )
        )
        // 用户手动上/下一首（SEEK）：不算新开始
        assertFalse(
            PlaybackEventClassifier.isFreshStart(
                prevId = 1L, songId = 2L,
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            )
        )
    }

    @Test
    fun `新开始：prevId 未知但换了歌也算（首曲以换队列起播）`() {
        // 与原实现一致：prevId 为 null（首曲）时，PLAYLIST_CHANGED 且 songId 非 null
        // 视为新开始，避免首曲被漏记到播放历史。
        assertTrue(
            PlaybackEventClassifier.isFreshStart(
                prevId = null, songId = 2L,
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            )
        )
    }

    // ---------- 完成去重 ----------

    @Test
    fun `首次记录该歌曲返回其 id`() {
        val recorder = CompletionRecorder(clock = { 0L })
        assertEqualsOrRef(7L, recorder.record(7L))
    }

    @Test
    fun `5s 窗口内同一首去重返回 null`() {
        val recorder = CompletionRecorder(clock = { 1_000L })
        recorder.record(7L) // 首次
        assertNull(recorder.record(7L)) // 1s 后同首 → 去重
    }

    @Test
    fun `超过 5s 后同一首可再次记录`() {
        var now = 0L
        val recorder = CompletionRecorder(clock = { now })
        assertEqualsOrRef(7L, recorder.record(7L))
        now = 6_000L // 超过 5s 窗口
        assertEqualsOrRef(7L, recorder.record(7L))
    }

    @Test
    fun `不同歌曲之间不去重`() {
        var now = 0L
        val recorder = CompletionRecorder(clock = { now })
        assertEqualsOrRef(7L, recorder.record(7L))
        now = 1_000L // 未超窗口，但换了歌
        assertEqualsOrRef(8L, recorder.record(8L))
    }

    // 小工具：Android 工程里 assertEquals 需要装箱，这里用 Long 精确比较
    private fun assertEqualsOrRef(expected: Long, actual: Long?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}

package com.puretone.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [PlaybackEndDiagnostics] 判据单测：只在「循环模式却意外结束」时报警，不误报正常收尾。 */
class PlaybackEndDiagnosticsTest {

    @Test
    fun `列表循环加非空队列加仍在播却结束属于异常`() {
        assertTrue(
            PlaybackEndDiagnostics.isUnexpected(
                repeatMode = RepeatMode.ALL,
                queueSize = 80,
                playWhenReady = true,
                crossfading = false
            )
        )
    }

    @Test
    fun `单曲循环同样不应进入结束态`() {
        assertTrue(
            PlaybackEndDiagnostics.isUnexpected(
                repeatMode = RepeatMode.ONE,
                queueSize = 1,
                playWhenReady = true,
                crossfading = false
            )
        )
    }

    @Test
    fun `不循环时播完是正常收尾`() {
        assertFalse(
            PlaybackEndDiagnostics.isUnexpected(
                repeatMode = RepeatMode.OFF,
                queueSize = 80,
                playWhenReady = true,
                crossfading = false
            )
        )
    }

    @Test
    fun `队列为空时不报警`() {
        assertFalse(
            PlaybackEndDiagnostics.isUnexpected(
                repeatMode = RepeatMode.ALL,
                queueSize = 0,
                playWhenReady = true,
                crossfading = false
            )
        )
    }

    @Test
    fun `用户主动暂停后结束不报警`() {
        assertFalse(
            PlaybackEndDiagnostics.isUnexpected(
                repeatMode = RepeatMode.ALL,
                queueSize = 80,
                playWhenReady = false,
                crossfading = false
            )
        )
    }

    @Test
    fun `淡化收尾中的结束不报警`() {
        assertFalse(
            PlaybackEndDiagnostics.isUnexpected(
                repeatMode = RepeatMode.ALL,
                queueSize = 80,
                playWhenReady = true,
                crossfading = true
            )
        )
    }
}

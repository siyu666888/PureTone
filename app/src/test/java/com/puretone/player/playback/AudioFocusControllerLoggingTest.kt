package com.puretone.player.playback

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.playback.AudioFocusController.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 焦点埋点确实写出去、且**没有改动**命令下发（2026-11-07 蓝牙后台取证）。
 *
 * 用注入的 record 替身收集日志，避免依赖 Android Log；`onFocusChange` 包内可见，
 * 不必借助 ShadowAudioManager 去反查 AudioFocusRequest 的监听器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioFocusControllerLoggingTest {

    private fun controller(
        lines: MutableList<String>,
        commands: MutableList<Command>
    ): AudioFocusController = AudioFocusController(
        ApplicationProvider.getApplicationContext<Context>(),
        { command, _ -> commands += command }
    ) { event, detail -> lines += "$event | $detail" }

    @Test
    fun `永久丢失：记录迁移前后状态且 PAUSE 仍照常下发`() {
        val lines = mutableListOf<String>()
        val commands = mutableListOf<Command>()

        controller(lines, commands).onFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(listOf(Command.PAUSE), commands)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("audio-focus-change"))
        assertTrue(lines[0].contains("type=LOSS(-1)"))
        assertTrue(lines[0].contains("cmd=PAUSE"))
        assertTrue(lines[0].contains("holding=false->false"))
    }

    @Test
    fun `瞬态丢失后拿回焦点：日志能看到恢复意图被消费且 RESUME 下发`() {
        val lines = mutableListOf<String>()
        val commands = mutableListOf<Command>()
        val subject = controller(lines, commands)

        subject.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        subject.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(listOf(Command.PAUSE, Command.RESUME), commands)
        assertTrue(lines[0].contains("type=LOSS_TRANSIENT(-2)"))
        assertTrue(lines[0].contains("resume=false->true"))
        assertTrue(lines[1].contains("type=GAIN(1)"))
        assertTrue(lines[1].contains("cmd=RESUME"))
        assertTrue(lines[1].contains("resume=true->false"))
    }

    @Test
    fun `未持焦点时放弃：记录 skipped 而不是静默返回`() {
        val lines = mutableListOf<String>()
        val commands = mutableListOf<Command>()

        controller(lines, commands).abandon()

        assertTrue(commands.isEmpty())
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("audio-focus-abandon | action=skipped"))
    }
}

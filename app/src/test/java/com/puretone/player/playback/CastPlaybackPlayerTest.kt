package com.puretone.player.playback

import android.content.Context
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.playback.cast.CastState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CastPlaybackPlayer] 单测：投屏门面的「命令路由 + 状态替换 + 远程音量」三层契约。
 *
 * 门面继承 [androidx.media3.common.ForwardingPlayer]，非投屏分支会把调用原样转给
 * 本地播放器，所以这里用 Robolectric + 真实 [ExoPlayer] 作 delegate（与
 * [PlaybackEventEffectsTest] 同款做法，不 prepare、不解码）；投屏分支全部落在
 * [FakeCastCommandSink] 上，断言只看它收到的命令，不依赖 Android 音频运行时。
 *
 * 重点钉死三条真机踩过的坑：
 * - 跨曲目的 `seekTo(index, pos)` 必须重新投送，否则电视还在播旧歌、通知栏显示新歌；
 * - 进度/时长必须取 sink 的「镜像 + 外推 + 本地兜底」值，直读 1Hz 轮询会让通知栏慢一拍；
 * - 投屏期必须把两个设备音量命令加进 `availableCommands`，否则 Media3 根本不建
 *   `VolumeProviderCompat`，音量键永远空转。
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastPlaybackPlayerTest {

    private lateinit var local: ExoPlayer
    private lateinit var sink: FakeCastCommandSink
    private lateinit var subject: CastPlaybackPlayer

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        local = ExoPlayer.Builder(context).build()
        sink = FakeCastCommandSink()
        subject = CastPlaybackPlayer(local, sink)
    }

    @After
    fun tearDown() {
        local.release()
    }

    private fun casting(
        isPlaying: Boolean = true,
        castVolume: Int = 40,
        volumeSupported: Boolean? = null
    ) {
        sink.castState = CastState(
            isCasting = true,
            isPlaying = isPlaying,
            castVolume = castVolume,
            volumeSupported = volumeSupported
        )
        sink.castVolumeFollowPhone = true
    }

    // ---------- 命令路由 ----------

    @Test
    fun `投屏期 play 与 pause 转发渲染器`() {
        casting()
        subject.play()
        subject.pause()
        assertEquals(listOf("play", "pause"), sink.commands)
    }

    @Test
    fun `投屏期 seekTo 负值钳到零`() {
        casting()
        subject.seekTo(-500L)
        assertEquals(listOf("seek:0"), sink.commands)
    }

    @Test
    fun `投屏期同索引 seek 只拖进度`() {
        casting()
        subject.seekTo(local.currentMediaItemIndex, 12_000L)
        assertEquals(listOf("seek:12000"), sink.commands)
    }

    @Test
    fun `投屏期跨索引 seek 改为重新投送而不是拖进度`() {
        casting()
        val otherIndex = local.currentMediaItemIndex + 3
        subject.seekTo(otherIndex, 12_000L)
        assertEquals(listOf("index:$otherIndex"), sink.commands)
    }

    @Test
    fun `投屏期上下曲四个入口全部转发渲染器`() {
        casting()
        subject.seekToNextMediaItem()
        subject.seekToNext()
        subject.seekToPreviousMediaItem()
        subject.seekToPrevious()
        assertEquals(listOf("next", "next", "prev", "prev"), sink.commands)
    }

    @Test
    fun `非投屏期 play 与 pause 不落到渲染器`() {
        sink.castState = CastState(isCasting = false)
        subject.play()
        subject.pause()
        assertTrue(sink.commands.isEmpty())
    }

    // ---------- 状态替换 ----------

    @Test
    fun `投屏期播放态取自渲染器且 playbackState 固定 READY`() {
        casting(isPlaying = true)
        assertTrue(subject.isPlaying)
        assertTrue(subject.playWhenReady)
        assertEquals(Player.STATE_READY, subject.playbackState)

        sink.castState = sink.castState.copy(isPlaying = false)
        assertFalse(subject.isPlaying)
        assertFalse(subject.playWhenReady)
    }

    @Test
    fun `投屏期进度取 displayPositionMs 且负值钳零`() {
        casting()
        sink.displayPositionMs = 42_000L
        assertEquals(42_000L, subject.currentPosition)

        sink.displayPositionMs = -1L
        assertEquals(0L, subject.currentPosition)
    }

    @Test
    fun `投屏期时长取 displayDurationMs`() {
        casting()
        sink.displayDurationMs = 238_760L
        assertEquals(238_760L, subject.duration)
    }

    @Test
    fun `渲染器与 sink 都没时长时回落本地播放器`() {
        casting()
        sink.displayDurationMs = 0L
        assertEquals(local.duration, subject.duration)
    }

    // ---------- 远程音量通道 ----------

    @Test
    fun `未开启跟随手机音量时不建立远程音量通道`() {
        sink.castState = CastState(isCasting = true, castVolume = 40)
        sink.castVolumeFollowPhone = false

        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, subject.deviceInfo.playbackType)
        assertFalse(subject.availableCommands.contains(Player.COMMAND_SET_DEVICE_VOLUME))
        subject.setDeviceVolume(70)
        subject.increaseDeviceVolume()
        assertTrue(sink.commands.isEmpty())
    }

    @Test
    fun `未投屏时即使开启设置也不建立远程音量通道`() {
        sink.castState = CastState(isCasting = false)
        sink.castVolumeFollowPhone = true
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, subject.deviceInfo.playbackType)
    }

    @Test
    fun `开启跟随手机音量时补齐两个设备音量命令`() {
        casting()
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, subject.deviceInfo.playbackType)
        assertTrue(subject.availableCommands.contains(Player.COMMAND_SET_DEVICE_VOLUME))
        assertTrue(subject.availableCommands.contains(Player.COMMAND_ADJUST_DEVICE_VOLUME))
    }

    @Test
    fun `能力探测明确不支持时门面仍保持远程档位`() {
        // 关键回归：remoteVolume 刻意不依赖 volumeSupported，否则探测结果中途翻转
        // 会让音量键在远程/本地档位间跳，真机表现为「音量忽高忽低」。
        casting(volumeSupported = false)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, subject.deviceInfo.playbackType)
        assertEquals(40, subject.deviceVolume)
    }

    @Test
    fun `设置设备音量钳制在 0 到 100`() {
        casting()
        subject.setDeviceVolume(150)
        subject.setDeviceVolume(-20, 0)
        assertEquals(listOf("set:100", "set:0"), sink.commands)
    }

    @Test
    fun `音量键相对调节下发正负 5 档`() {
        casting()
        subject.increaseDeviceVolume()
        subject.decreaseDeviceVolume()
        subject.increaseDeviceVolume(0)
        subject.decreaseDeviceVolume(0)
        assertEquals(listOf("adjust:5", "adjust:-5", "adjust:5", "adjust:-5"), sink.commands)
    }

    @Test
    fun `远程音量与静音态取自渲染器当前档位`() {
        casting(castVolume = 63)
        assertEquals(63, subject.deviceVolume)
        assertFalse(subject.isDeviceMuted)

        sink.castState = sink.castState.copy(castVolume = 0)
        assertEquals(0, subject.deviceVolume)
        assertTrue(subject.isDeviceMuted)
    }

    @Test
    fun `静音后取消静音恢复静音前的档位`() {
        casting(castVolume = 55)
        subject.setDeviceMuted(true)
        subject.setDeviceMuted(false)
        assertEquals(listOf("set:0", "set:55"), sink.commands)
    }

    @Test
    fun `没有静音记录时取消静音用默认档位 30`() {
        casting(castVolume = 55)
        subject.setDeviceMuted(false)
        assertEquals(listOf("set:30"), sink.commands)
    }

    /** [CastCommandSink] 的记录型替身：只留命令痕迹，不触碰任何 DLNA / 网络。 */
    private class FakeCastCommandSink : CastCommandSink {
        override var castState: CastState = CastState()
        override var displayPositionMs: Long = 0L
        override var displayDurationMs: Long = 0L
        override var castVolumeFollowPhone: Boolean = false

        val commands = mutableListOf<String>()

        override fun setCastVolume(volume: Int) {
            commands += "set:$volume"
        }

        override fun adjustCastVolume(delta: Int) {
            commands += "adjust:$delta"
        }

        override fun playOnRenderer() {
            commands += "play"
        }

        override fun pauseOnRenderer() {
            commands += "pause"
        }

        override fun seekOnRenderer(positionMs: Long) {
            commands += "seek:$positionMs"
        }

        override fun skipToIndex(index: Int) {
            commands += "index:$index"
        }

        override fun skipToNext() {
            commands += "next"
        }

        override fun skipToPrevious() {
            commands += "prev"
        }
    }
}

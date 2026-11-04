package com.puretone.player.playback

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.settings.PureToneSettings

/**
 * PlaybackEventEffects / PlaybackSettingsApplier / PlaybackSessionRestorer 单测用的窄端口替身。
 *
 * 三个被测类在 P1-1 拆分收尾中把依赖收敛成 [PlaybackPorts] 里的窄接口，这里实现这些接口，
 * 让测试能注入 fake 并断言调用。所有替身都是无副作用的计数器/记录器，不触碰 Android 运行时
 * （除 [FakeCrossfade.isOutgoing] 接收的 [ExoPlayer] 仅作占位，不实际播放）。
 */

internal class FakeAudioEffect : AudioEffectPort {
    var attachCount = 0
    var applyCount = 0

    override fun attachToSession(audioSessionId: Int) {
        attachCount++
    }

    override fun applySettings(settings: PureToneSettings) {
        applyCount++
    }
}

internal class FakeErrorHandler : PlaybackErrorPort {
    var onPlayingCount = 0
    var handled: PlaybackException? = null

    override fun onPlaying() {
        onPlayingCount++
    }

    override fun handle(error: PlaybackException) {
        handled = error
    }
}

internal class FakeFocus : AudioFocusPort {
    var calls = 0

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        calls++
    }
}

internal class FakeWidget : WidgetUpdatePort {
    var updates = 0

    override fun requestUpdate() {
        updates++
    }
}

internal class FakeSessionStore : SessionStorePort {
    var saveNowCount = 0
    var debouncedInvocations = 0
    var lastSnapshot: SavedSession? = null
    var restoreQueueResult: RestoredQueue? = null
    var onRestore: (() -> Unit)? = null

    override fun saveDebounced(snapshot: () -> SavedSession?) {
        debouncedInvocations++
        lastSnapshot = snapshot()
    }

    override fun saveNow(snapshot: SavedSession) {
        saveNowCount++
        lastSnapshot = snapshot
    }

    override fun positionToSave(rawPositionMs: Long, durationMs: Long): Long = rawPositionMs

    override suspend fun restoreQueue(): RestoredQueue? {
        onRestore?.invoke()
        return restoreQueueResult
    }
}

internal class FakeRepo : PlaybackRepoPort {
    val plays = mutableListOf<Long>()
    val completions = mutableListOf<Long>()
    val settlements = mutableListOf<Pair<Long, Long>>()

    override suspend fun recordPlay(songId: Long) {
        plays += songId
    }

    override suspend fun recordCompletion(songId: Long) {
        completions += songId
    }

    override suspend fun settlePlayback(songId: Long, playedMs: Long) {
        settlements += (songId to playedMs)
    }
}

internal class FakeCrossfade : CrossfadePort {
    var crossfading = false
    var outgoing = false
    var finalizeCount = 0

    override val isCrossfading: Boolean get() = crossfading

    override fun isOutgoing(player: ExoPlayer): Boolean = outgoing

    override fun finalize() {
        finalizeCount++
    }
}

internal class FakeGap : GapPort {
    var startCount = 0

    override fun start() {
        startCount++
    }
}

internal class FakeSleep : SleepTimerPort {
    var applyCount = 0
    var finishCount = 0

    override fun applySettings(settings: PureToneSettings) {
        applyCount++
    }

    override fun finishEndOfTrack() {
        finishCount++
    }
}

internal class FakeSpeedControl : SpeedControlPort {
    var setSpeedCalls = 0
    var lastSpeed: Float = Float.NaN

    override fun setSpeed(speed: Float) {
        setSpeedCalls++
        lastSpeed = speed
    }
}

internal class FakeQueueApply : QueueApplyPort {
    var applyCount = 0
    var lastSongs: List<SongEntity> = emptyList()
    var lastIndex = -1
    var lastPositionMs = -1L
    var lastAutoPlay = true

    override suspend fun applyQueueAndPlay(
        songs: List<SongEntity>,
        startIndex: Int,
        startPositionMs: Long,
        autoPlay: Boolean
    ) {
        applyCount++
        lastSongs = songs
        lastIndex = startIndex
        lastPositionMs = startPositionMs
        lastAutoPlay = autoPlay
    }
}

package com.puretone.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.puretone.player.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 播放前台服务。
 *
 * 持有 ExoPlayer 与 MediaSession（通知/锁屏控制由 Media3 据此自动生成）。
 * 在 onCreate 创建二者并 [PlaybackManager.bind] 注入单例；onDestroy 解绑并释放，
 * 释放顺序符合 MediaSessionService 约定（先解绑 manager，再释放 player/session）。
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playbackManager: PlaybackManager

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent())
            .build()

        playbackManager.bind(player, session)

        // 将会话注册到 Media3 通知管理器。
        //
        // 背景：MediaSessionService.addSession() 默认只在两种情况下被自动调用——
        // onStartCommand 收到 android.intent.action.MEDIA_BUTTON，或被外部以
        // MediaBrowserService 兼容方式绑定。普通的 startService()/绑定都不会触发。
        // 不注册时，MediaNotificationManager 拿不到内部控制器，
        // shouldRunInForeground() 恒为 false，Media3 永远不会调用 startForeground()，
        // 媒体通知也不会出现；此时任何外部对 startForegroundService() 的调用都会
        // 触发 Android 8+ 的 5 秒前台化超时（ForegroundServiceDidNotStartInTimeException），
        // 进程被系统直接杀掉——这正是「播放约 30 秒后闪退」的根因。
        // addSession() 幂等（已注册则跳过），可安全调用。
        runCatching { addSession(session) }
            .onFailure { Log.w(TAG, "addSession failed", it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放顺序：先解绑 manager（含 detach 音效），再释放 player/session。
        // 每步独立 runCatching，避免某一步异常导致后续资源泄漏或进程崩溃。
        runCatching { playbackManager.unbind() }
        runCatching { player.release() }
        runCatching { session.release() }
    }

    private companion object {
        const val TAG = "PlaybackService"
    }

    private fun sessionActivityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

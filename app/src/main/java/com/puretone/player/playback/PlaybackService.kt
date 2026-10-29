package com.puretone.player.playback

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
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
 * 释放顺序为 unbind → session → player → super（P3-21，对齐官方 demo）。
 */
// ConnectionResult.DEFAULT_* 常量是 Media3 的 UnstableApi（与 PlaybackManager 同）
@OptIn(UnstableApi::class)
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
            .setCallback(SessionCallback())   // P1-4：控制器白名单
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

    /**
     * 会话回调：控制器准入白名单（P1-4）。
     *
     * 本服务在清单里 `exported=true`（Media3 媒体会话的标准做法），不加校验时
     * 设备上**任意 App** 都能连上会话：读取当前播放的歌名/歌手/队列元数据，
     * 还能控制播放、暂停、切歌。这里只放行自身包与已知的系统级媒体控制器
     * （系统控制中心、Android Auto、语音助手、Wear OS），其余一律 reject。
     *
     * 注意：准入是「会话级」的（MediaSession.Callback.onConnect），
     * 服务基类 MediaSessionService 没有 onConnect 可覆写。
     */
    @OptIn(UnstableApi::class)
    private inner class SessionCallback : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            if (!isAllowedController(controller.packageName)) {
                Log.w(TAG, "拒绝未授权控制器：${controller.packageName}")
                return MediaSession.ConnectionResult.reject()
            }
            return MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }
    }

    private fun isAllowedController(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        // 自身（UI / 本 App 发布的通知控制器）
        if (pkg == packageName) return true
        // 已知的系统级媒体客户端
        if (pkg in KNOWN_CONTROLLERS) return true
        // 持有 MEDIA_CONTENT_CONTROL 的系统媒体控制器（部分 ROM 的通知控制中心/语音助手）
        return runCatching {
            packageManager.checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, pkg) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // P3-21：释放顺序对齐官方 demo —— session → player → super。
        // 旧实现把 super.onDestroy() 放在最前、且 player 先于 session 释放：
        //   - super 提前调用意味着基类清理跑在我们释放资源之前，语义倒置；
        //   - session 持有 player 引用，先释放 player 会让 session 短暂持有已死对象。
        // unbind() 必须最先执行：它要从 player 上摘 listener 并 detach 音效，
        // 对已 release 的 player 调用 removeListener 会抛异常。
        // 每步独立 runCatching，避免某一步异常导致后续资源泄漏或进程崩溃。
        runCatching { playbackManager.unbind() }
        runCatching { session.release() }
        runCatching { player.release() }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlaybackService"

        /**
         * 允许连接的已知媒体控制器包名（P1-4）。
         * 与「任意 App 可连」相比已大幅收敛；如需支持新的车载/穿戴/语音助手，
         * 在这里补包名即可。
         */
        val KNOWN_CONTROLLERS = setOf(
            "com.android.systemui",                    // 系统控制中心 / 锁屏媒体
            "com.google.android.projection.gearhead",  // Android Auto
            "com.google.android.apps.auto",            // Android Auto（部分版本）
            "com.google.android.googlequicksearchbox", // Google Assistant
            "com.google.android.apps.googleassistant", // Google Assistant（独立包）
            "com.google.android.wearable.app"          // Wear OS
        )
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

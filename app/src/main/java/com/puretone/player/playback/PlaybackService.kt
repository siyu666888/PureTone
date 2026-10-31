package com.puretone.player.playback

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.puretone.player.MainActivity
import com.puretone.player.playback.ReplayGainAudioProcessor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 播放前台服务。
 *
 * 持有 ExoPlayer 与 MediaSession（通知/锁屏控制由 Media3 据此自动生成）。
 * 在 onCreate 创建二者并 [PlaybackManager.bind] 注入单例；onDestroy 解绑并释放，
 * 释放顺序为 unbind → session → player → super（P3-21，对齐官方 demo）。
 *
 * 交叉淡化（A 路线：双实例真重叠）需要两个 ExoPlayer 实例：
 * - [player] 为「主」播放器，承载 MediaSession 与当前曲目；
 * - [playerB] 为「备援」播放器，仅在交叉淡化窗口内短暂起播下一首，与主播放器
 *   两路同时发声（音量此消彼长）形成真重叠淡化，平时空闲。
 * 两个播放器各自携带独立的 ReplayGain 处理器（增益按各自曲目实时刷新）。
 */
// ConnectionResult.DEFAULT_* 常量是 Media3 的 UnstableApi（与 PlaybackManager 同）
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playbackManager: PlaybackManager

    private lateinit var player: ExoPlayer
    /** 交叉淡化备援播放器（A 路线：双实例真重叠）。仅在交叉淡化窗口内短暂起播，平时空闲。 */
    private lateinit var playerB: ExoPlayer
    private lateinit var session: MediaSession

    override fun onCreate() {
        try {
            super.onCreate()
            // ReplayGain 音频处理器：两个播放器各持一个独立实例（增益按各自当前曲目实时刷新，
            // 交叉淡化期间两路同时发声，必须各自施加）。
            val rgProcessorA = ReplayGainAudioProcessor()
            val rgProcessorB = ReplayGainAudioProcessor()
            player = buildPlayer(rgProcessorA)
            playerB = buildPlayer(rgProcessorB)

            session = MediaSession.Builder(this, player)
                .setSessionActivity(sessionActivityIntent())
                .setCallback(SessionCallback())   // P1-4：控制器白名单
                .build()

            playbackManager.bind(player, playerB, session, rgProcessorA, rgProcessorB)

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
        } catch (t: Throwable) {
            // 启动期构建播放栈 / 会话若因设备/版本兼容问题抛异常（Media3 1.6.0 大版本升级
            // 后个别 ROM 上可能出现），绝不让它冒泡杀进程——否则表现为「打开应用即闪退」。
            // 这里降级：应用仍可正常打开浏览曲库，仅本次暂不可用播放（异常已落盘到
            // crash_last.txt，供后续在真机精确复现与修复）。
            Log.e(TAG, "PlaybackService startup failed; degrading without playback", t)
        }
    }

    /**
     * 构建单个 ExoPlayer 实例，各自携带独立的 ReplayGain 处理器链。
     *
     * Media3 1.4.1 的 ExoPlayer.Builder 没有 setAudioProcessors；
     * 必须走 DefaultAudioSink（承载 AudioProcessor 链）+ 自定义 DefaultRenderersFactory
     * （把带处理器的 sink 注入 MediaCodecAudioRenderer）。每个播放器用各自的 sink 与
     * 渲染器工厂，避免共享 RG 处理器实例（交叉淡化时两路增益不同）。
     */
    @OptIn(UnstableApi::class)
    private fun buildPlayer(rgProcessor: ReplayGainAudioProcessor): ExoPlayer {
        val rgAudioSink = DefaultAudioSink.Builder(this)
            .setAudioProcessors(arrayOf(rgProcessor))
            .build()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                // 用承载 RG 处理器的 sink 替换默认 sink，其余渲染器逻辑全部沿用父类
                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    rgAudioSink,
                    eventHandler,
                    eventListener,
                    out
                )
            }
        }
        return ExoPlayer.Builder(this, renderersFactory)
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        // onCreate 降级（构建播放栈失败）时 session 未初始化：不对外提供服务，
        // 让系统侧连接收到异常（binder 线程异常不会杀本进程），避免回调里 NPE 杀进程。
        if (!::session.isInitialized) {
            throw IllegalStateException("PlaybackService session not initialized (startup degraded)")
        }
        return session
    }

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
        if (::player.isInitialized && (!player.playWhenReady || player.mediaItemCount == 0)) {
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
        // 每步独立 runCatching，避免某一步异常导致后续资源泄漏或进程崩溃；
        // 并用 ::x.isInitialized 守卫：onCreate 降级（启动失败）时这些字段可能未初始化。
        runCatching { playbackManager.unbind() }
        if (::session.isInitialized) runCatching { session.release() }
        if (::player.isInitialized) runCatching { player.release() }
        if (::playerB.isInitialized) runCatching { playerB.release() }
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

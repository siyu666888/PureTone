package com.puretone.player.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放服务的拉起 / 停止（P1-1 拆分第二刀）。
 *
 * 从 [PlaybackManager] 抽出这一簇的理由：这里装的不是播放逻辑，而是**平台限制的绕行
 * 知识**——Android 8+ 的后台 `startService` 禁令与 MediaSessionService 的绑定通道。
 * 这类知识一旦和播放编排混在 1400 行里，改动时极易被误伤（历史上「播放约 30s 后闪退」
 * 就是前台服务时限被误触，见 [ensureStarted] 注释），而且 Glance 小部件、未来的
 * 通知快捷入口都要复用同一套逻辑，独立成类才谈得上复用。
 *
 * 唯一持有的状态是「后台 bind 兜底」临时 controller：绑定成功、服务真正注入播放器后
 * 由 [onServiceBound] 延迟释放（此时若服务已有播放内容，MediaSessionService 语义会
 * 让它自己活下去）。
 */
@Singleton
class PlaybackServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 后台启动兜底临时持有的 MediaController（见 [startByBinding]） */
    private var bootstrapController: MediaController? = null

    /**
     * 确保播放服务已启动（冷启动兜底）。
     *
     * 刻意使用 startService() 而非 startForegroundService()：
     * 后者自 Android 8 起带有「5 秒内必须调用 Service.startForeground()」的硬性时限，
     * 否则系统抛 ForegroundServiceDidNotStartInTimeException 直接杀进程。
     * 前台化时机由 Media3 的 MediaSessionService 自行判断（内部通知控制器 +
     * shouldRunInForeground），本 App 无从保证时限，故绝不主动 arm 这颗炸弹。
     * 前台服务类型 mediaPlayback 已在清单声明，Media3 转前台时会自带该类型。
     */
    fun ensureStarted() {
        runCatching {
            context.startService(Intent(context, PlaybackService::class.java))
        }.onFailure { e ->
            Log.w(TAG, "startService failed, fallback to session bind", e)
            startByBinding()
        }
    }

    /**
     * 服务已完成 bind（播放器已注入）：释放兜底 controller。
     *
     * 延迟 3s 再释放，给服务留出「真正开始播放」的窗口——若立刻释放，服务在
     * 尚未持有播放内容时可能被系统回收，用户会看到「点一下就没了」。
     */
    fun onServiceBound() {
        val controller = bootstrapController ?: return
        bootstrapController = null
        scope.launch {
            delay(3_000L)
            runCatching { controller.release() }
                .onFailure { Log.w(TAG, "release bootstrap controller failed", it) }
        }
    }

    /** 停止播放服务（空闲判定由调用方负责，这里只兜住 stopService 的异常） */
    fun stopService() {
        runCatching {
            context.stopService(Intent(context, PlaybackService::class.java))
        }.onFailure { Log.w(TAG, "stopService failed", it) }
    }

    /**
     * 后台启动兜底：通过 MediaSession 的绑定通道拉起服务。
     *
     * Android 8+ 禁止后台应用 [Context.startService]，而「桌面小部件点击」正是后台场景
     * （App 在后台、进程可能连播放器都没创建）。[MediaController] 走的是 bindService，
     * 不受该限制——MediaSessionService 被绑定后会自动创建并自行前台化。
     */
    private fun startByBinding() {
        runCatching {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { bootstrapController = it }
                        .onFailure { Log.w(TAG, "bind MediaSessionService failed", it) }
                },
                MoreExecutors.directExecutor()
            )
        }.onFailure { Log.w(TAG, "build MediaController failed", it) }
    }

    private companion object {
        const val TAG = "PlaybackServiceLauncher"
    }
}

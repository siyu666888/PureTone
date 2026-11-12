package com.puretone.player.playback

import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.cast.CastDevice
import com.puretone.player.playback.cast.CastResult
import com.puretone.player.playback.cast.CastState
import kotlinx.coroutines.flow.StateFlow

/**
 * [CastSessionController] 的两个窄端口（P1-1 拆分同款手法，见 [PlaybackPorts]）。
 *
 * 编排层原本直接持有 [com.puretone.player.playback.cast.DlnaCastManager] 与
 * [PlaybackAudioFocus] 两个 final 具体类：前者构造要 Context / 转码器 / DataStore，
 * 后者要真实音频焦点控制器，且状态是私有的 `MutableStateFlow`——单测既造不出来，
 * 也没法把「投屏中 / 播完 / 失联」这些状态喂进去，于是编排层一直零覆盖。
 *
 * 这里只把**编排层真正用到的成员**收成接口，实现类照旧，不改任何一行行为逻辑：
 * 投屏期的进度镜像、换主顺序、回退路径、队列推进都能用记录型替身直接断言。
 *
 * 保持 public 可见性：[com.puretone.player.playback.cast.DlnaCastManager] 是 public 的
 * Hilt 单例，实现 internal 超类会触发 Kotlin 的可见性约束。
 */
interface CastSessionCastPort {

    /** 1Hz 轮询刷新的渲染器状态；进度镜像与路由切换都订阅它 */
    val state: StateFlow<CastState>

    /** 拖动进度后的乐观窗口截止时刻：在此之前不把 1Hz 旧值镜像回 UI */
    val progressHoldUntilMs: Long

    fun selectDevice(device: CastDevice)

    suspend fun castSong(song: SongEntity): CastResult

    /**
     * 标记「下一次投送是曲末自动推进」（渲染器自然播完上一首 → 自动投下一首）。
     *
     * 在 [com.puretone.player.playback.CastSessionController.onCastTrackEnded] 确认推进、
     * 调 [next] 之前调用；[castSong] 开头读取并清除，写入 [CastState.autoAdvance]。
     * 据此投屏建立确认用更宽的宽限且不重发（见 [CastEstablishPolicy]），避免把电视
     * 慢取流误判成卡死而重发打断（真机 20261107_224000 开头重复/静默）。
     */
    fun markAutoAdvanceNext()

    suspend fun seek(positionMs: Long): Boolean

    /** 记一次乐观 seek，延长不镜像窗口 */
    fun noteSeekOptimistic()

    /**
     * 记一次用户**手动拖动**进度条（投屏态）。
     *
     * 写入 [CastState.lastSeekMs] 供协调器识别「拖进度条引起的瞬间 STOPPED」，
     * 改用就地 play 恢复而非整套重投（A 方案，见 174553 体验层判读）。
     */
    fun noteUserSeek()

    suspend fun stopCast()

    fun setVolume(volume: Int): Boolean

    fun adjustVolume(delta: Int): Boolean

    /**
     * 下发 Play。 [source] 只用于诊断日志区分命令来源：
     * `ui` = App 内播放页 / 迷你条，`notification` = 通知栏 / 锁屏媒体卡片，
     * `notification-retry` = 回读未达播放态后的自动重发。
     * 真机 20261107_184914 里两条路径的表现不同，必须先能区分才能定位。
     */
    suspend fun play(source: String = "ui"): Boolean

    /** 下发 Pause，[source] 语义同 [play] */
    suspend fun pause(source: String = "ui"): Boolean
}

/** 进入投屏时释放手机侧音频焦点所需的最小能力 */
interface CastSessionFocusPort {
    fun abandon()
}

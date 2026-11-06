package com.puretone.player.playback.cast

/**
 * 投屏设备（PureTone 自有类型）。
 *
 * 用来隔离第三方 UPnPCast 的 `DLNACast.Device`：UI / ViewModel 只与本类型打交道，
 * 底层 `DLNACast.Device` 仅在 [DlnaCastManager] 内部持有，降低耦合。
 */
data class CastDevice(
    val id: String,
    val name: String,
    val address: String,
    val isTV: Boolean
)

/**
 * 投屏运行时状态（PureTone 自有，供 UI 直接收集）。
 */
data class CastState(
    /** 是否正在投屏（已选设备并成功发起 push） */
    val isCasting: Boolean = false,
    /** 当前投屏设备名 */
    val deviceName: String? = null,
    /** 当前投屏设备 IP */
    val deviceAddress: String? = null,
    /** 渲染器是否正在播放 */
    val isPlaying: Boolean = false,
    /** 渲染器播放态枚举名（IDLE/PLAYING/PAUSED/STOPPED/BUFFERING/ERROR） */
    val playbackState: String = "IDLE",
    /** 当前播放进度（毫秒） */
    val progressMs: Long = 0L,
    /** 总时长（毫秒） */
    val durationMs: Long = 0L,
    /** 最近一次错误描述（无错为 null） */
    val error: String? = null,
    /**
     * 是否正在为投屏做准备（M2：APE 等需转码的格式，首投要等转码完成）。
     *
     * 与 [isCasting] 是两段：本字段为 true 时还没 cast 成功，UI 应显示「转码中」；
     * 转码结束（成功或失败）即置回 false。
     */
    val isPreparing: Boolean = false,
    /**
     * 投送代次（M3）。
     *
     * 每次 [DlnaCastManager.castSong] **开始投送时**（转码之前）自增。
     *
     * 用途：区分「渲染器自然播完这一首」与「用户手动切歌把旧曲换掉了」——
     * 后者同样会让渲染器短暂上报停止态，若不靠代次区分就会多跳一首。
     * 代次在转码**之前**自增是刻意的：APE 转码可能耗时数十秒，若等投送成功才自增，
     * 这期间渲染器早已上报停止态，判定会失准。
     */
    val pushGeneration: Long = 0L,
    /**
     * 投屏是否因设备失联而中断（M3 断网回退）。
     *
     * 与 [error] 的区别：[error] 是「上一次操作失败」（可能已自行恢复）；
     * 本字段表示「连接已不可用」，上层据此停止投屏并把播放回退到本地。
     */
    val isConnectionLost: Boolean = false,
    /**
     * 渲染器是否提供了进度信息（M3）。
     *
     * `DLNACast.getProgress` 拿不到时返回 null，此时 [progressMs]/[durationMs] 均为 0。
     * 这与「进度真的是 0」是两回事——判定「播完」时必须区分，否则会把
     * 「进度未知」误判成「渲染器播完后把进度复位为 0」。
     */
    val hasProgressInfo: Boolean = false,
    /**
     * 投射音量（M3，0~100，默认 100 = 尚未校准）。
     *
     * 首投时由 [DlnaCastManager.syncInitialVolumeNow] 同步回读设备真实音量写入；
     * 之后只由 [DlnaCastManager.setVolume] 按用户下发值更新，设备回读**不回写**，
     * 避免量程取整误差让档位逐次漂移。换曲沿用同一会话音量，不再重设渲染器。
     */
    val castVolume: Int = 100,
    /**
     * 当前渲染器是否支持用 DLNA 调音量（null = 尚未探测）。
     *
     * 投送成功后由 [CastProbe] 读设备描述判定：没有 RenderingControl 服务的接收端
     * （部分「多屏互动」类中间层）**根本无法**用 `SetVolume` 调音量。此时 UI 必须
     * 禁用滑块并提示用遥控器——给一个拖了没反应的滑块，比不给更糟。
     */
    val volumeSupported: Boolean? = null
)

/**
 * 投送结果（M3）。
 *
 * 为什么要区分「失败」与「已过期」：断网回退只在**失败**时触发。
 * - 失败 = 渲染器上仍是旧曲（或压根没投出去），而手机端已经换曲了 →
 *   继续投屏就会出现「电视播旧歌、手机显示新歌」的状态撕裂，必须回退本地；
 * - 已过期 = 期间用户主动停了投屏或改了目标，此时本地播放已由上层恢复，
 *   再回退一次就是重复动作（甚至会在新一轮投送途中把本地也拉起来，
 *   导致手机与电视同时出声）。
 */
sealed interface CastResult {
    /** 投送成功 */
    data object Success : CastResult

    /** 投送失败（设备不可达 / 转码失败 / 渲染器拒绝） */
    data class Failed(val reason: String) : CastResult

    /** 投送已过期：期间用户停止投屏或改了目标，本次整段放弃 */
    data object Superseded : CastResult
}

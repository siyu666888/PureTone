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
    val volumeSupported: Boolean? = null,
    /**
     * 本轮投送**实际完成**时刻（`onCastSucceeded`，非转码开始时刻）。
     *
     * 用于「曲中停止」的墙钟判据（见 [CastStopPolicy]）、建立宽限窗口、取流空窗
     * 兜底基准与诊断耗时。**不是**取流有效性的门槛：电视可能在 `Play` 返回前就
     * 发起 GET，`lastFetchMs` 会略早于本值（170435）。
     */
    val castStartMs: Long = 0L,
    /**
     * 本轮投送后，电视最近一次真正取流的时刻（0=本轮从未取流）。
     *
     * 真机 20261107_165555：16:55:25 `cast-ok` 之后到导出报告一条新曲 `http-fetch`
     * 都没有，App 却报 PLAYING、手机卡片显示 00:24——那是 uiState 外推的假进度。
     * 投屏「已建立」必须以此为准，不能只看 SOAP 200。
     *
     * 「本轮」由 [CastHttpServer.urlFor] 换 token 时清零 + `markFetchIfActive()`
     * 只认当前 token 保证：> 0 即本轮取流，允许早于 [castStartMs]。
     * 遥控页据此区分「连接中」与「假 00:00」，见 [CastEstablishUiPolicy]。
     */
    val lastFetchMs: Long = 0L,
    /**
     * 用户最近一次**手动拖动**投屏进度条的墙钟时刻（0 = 本轮从未手动拖）。
     *
     * 用于「曲中 STOPPED 保护窗口」：用户拖进度条时渲染器会瞬间 STOPPED 再续播，
     * 这正是 174553 真机每拖一次出现 2~4s 静音的根因——协调器把它当成「曲中中断」
     * 触发整套重投（`CONFIRM_MS` + `SetAVTransportURI`+`Play`+`Seek`）。在该窗口内
     * 出现的 STOPPED 改走「就地 play 恢复」而非整套重投，把静音压到 1s 内（A 方案）。
     */
    val lastSeekMs: Long = 0L,
    /**
     * 本轮投屏是否已确认建立：自有通道须观测到电视真来取流（见 [CastEstablishPolicy]），
     * 库通道取流不经过本应用 HTTP 服务，直通为 true。
     *
     * 默认 true 表示「未启用取流确认的历史状态 / 库通道」；自有通道每次投送成功时由
     * [DlnaCastManager] 显式置 false，等确认后再翻回 true。未建立时 UI 不镜像播放态
     * 与进度，避免「电视已退出画面、手机还在显示播放中」。
     */
    val isEstablished: Boolean = true,
    /**
     * 本轮投送是否「曲末自动推进」（渲染器自然播完上一首 → 自动投下一首）。
     *
     * 与用户主动切歌 / 首次投屏区分：曲末推进时电视刚 STOPPED 复位，重新取下一首流
     * 的延迟波动大（真机 20261107_224000 实测 7~8s，远超手动切歌的 <1s），这是电视
     * 固有行为而非「卡死」。据此 [CastEstablishPolicy] 用更宽的建立宽限且不重发，
     * 避免 6s 一到就 `SetAVTransportURI+Play` 重发把电视的取流打断（开头重复/静默）。
     */
    val autoAdvance: Boolean = false
)

/**
 * 新一轮投送成功时的状态重置清单（供 DlnaCastManager 的 cast-ok 收尾调用）。
 *
 * 抽成纯函数是为了让「每轮必须清零的字段」可被 JVM 单测锁死：此前用 `it.copy(...)`
 * 逐字段重置时漏了 [lastSeekMs]，上一曲用户拖进度条留下的 3s 保护窗会跨曲泄漏到
 * 新曲（第四轮审查 R4-1）。新增任何「按轮次」的状态字段，都必须在此同步清零。
 *
 * [established] 由通道决定：库通道取流不经过本应用 HTTP 服务，直通 true；自有通道
 * 置 false，须等观测到本轮真实取流才翻回 true（红线 `lastFetchMs > 0L` 不动）。
 */
internal fun CastState.forNewCast(
    nowMs: Long,
    deviceName: String?,
    deviceAddress: String?,
    established: Boolean
): CastState = copy(
    isCasting = true,
    isPreparing = false,
    deviceName = deviceName,
    deviceAddress = deviceAddress,
    error = null,
    isConnectionLost = false,
    progressMs = 0L,
    durationMs = 0L,
    hasProgressInfo = false,
    castStartMs = nowMs,
    lastFetchMs = 0L,
    lastSeekMs = 0L,
    isEstablished = established
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

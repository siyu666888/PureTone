package com.puretone.player.playback.cast

/**
 * 投屏队列推进策略（M3）。
 *
 * 纯判定函数集合：不碰播放器、不发网络请求、不读协程状态，因此「渲染器到底有没有
 * 把这一首播完」这类最容易写歪的边界可以在纯 JVM 上全部锁死。
 *
 * ## 背景
 *
 * DLNA 的投送是**一次性 push**（`SetAVTransportURI`）：手机端把一条媒体 URL 推给
 * 渲染器就结束了，渲染器**不会**自动接着播下一首——手机队列怎么变，电视都不知道。
 * 而投屏态下本地 ExoPlayer 是暂停的（避免手机与电视同时出声），于是「一首播完」
 * 之后没有任何东西推进队列，表现为**投屏只能听一首就停住**。
 *
 * ## 「自然播完」需要三个信号同时成立
 *
 * 1. **状态跃迁**：曾经观测到渲染器在播（[wasPlaying]），现在不在播（[isPlaying] 为 false）。
 *    单看当前状态不够——渲染器暂停时也是「不在播」，从未播起过也是「不在播」。
 * 2. **终止态**：渲染器进入停止态或暂停态（[STATE_STOPPED] / [STATE_PAUSED]）。
 * 3. **进度佐证**：`STOPPED` 是渲染器对「本条 URI 已终止」的明确表态，与用户中途暂停
 *    （`PAUSED_PLAYBACK` → [STATE_PAUSED]）天然分开，故不再要求进度可验证；
 *    `PAUSED` 则必须靠「进度已到末尾 / 已归零」排除用户按暂停的误判。
 *
 * ## 为什么 STOPPED 一律推进（v1.9.2 / v1.9.3 三次真机故障）
 *
 * DLNA 自有通道用 `GetPositionInfo` 取进度，但电视在**曲目结束后**对该动作的回报
 * 完全不可预测。同一台 623(192.168.101.20) 在三份真机诊断里给出三种组合：
 * - `GetPositionInfo` 返回 500 → 进度/时长皆 0、`hasProgressInfo=false`（v1.9.2）；
 * - `tv=185000/0` → 只回 `RelTime`、不回 `TrackDuration`（19:50:30）；
 * - `tv=8000/193000` → 自报了完整时长，进度却复位到 8 秒（20:43:58，本曲实际
 *   已播约 3 分钟，STOPPED 后 14 秒没有 `cast-attempt`，20:44:12 才 `cast-resume`）。
 *
 * 旧判据用「进度落在曲中」否决推进，三种组合里两种被误判成「用户在电视上按了
 * Stop」→ 队列不推进 → 电视静音，用户回来看见的就是「投屏自动退出」，于是点断开
 * 重连（诊断里的 `cast-resume`）；重投后又能正常播——这就是「偶尔」的由来。
 * v1.9.3 第一次修正只把尺子从「本地时长」换成「渲染器自报时长」，挡住了
 * `185000/0`，却挡不住 `8000/193000`（自报时长 >0 时仍会启用曲中否决）。
 *
 * 根子在于：UPnP AVTransport 的 `STOPPED` 只表示「当前 URI 的传输已终止」，
 * **不含**「自然播完 / 用户按 Stop」的区分信息，STOPPED 时刻的 `RelTime` 可能归零、
 * 复位到上次 seek 位置或干脆不给，不足以反推是否播完。故 STOPPED 一律推进；
 * 防误跳交给 [CastQueueCoordinator] 的三道时序保险：必须经历「在播 → 不在播」
 * 跃迁、代次变化后第一轮不采信、推进后重新观测到在播才允许下一次推进。
 *
 * 唯一取舍：用户在电视上按 Stop 时手机会自动投下一首。DLNA push 模式无法区分，
 * 且远比「播完卡死、投屏像自动退出」可接受。`PAUSED` 仍保持严格判据，
 * 用户按暂停不会擅自跳歌。
 */
internal object CastQueuePolicy {

    /** 渲染器播放态：正在播放 */
    const val STATE_PLAYING = "PLAYING"

    /** 渲染器播放态：已暂停（用户按暂停；部分渲染器在曲目自然结束时也报此态） */
    const val STATE_PAUSED = "PAUSED"

    /** 渲染器播放态：已停止（曲目自然结束时的典型上报态） */
    const val STATE_STOPPED = "STOPPED"

    /** 渲染器播放态：缓冲中 */
    const val STATE_BUFFERING = "BUFFERING"

    /** 渲染器播放态：流错误 */
    const val STATE_ERROR = "ERROR"

    /**
     * 进度容差（毫秒）。
     *
     * 渲染器在曲目末尾上报的位置常略小于总时长——网络往返延迟与渲染器自身取整都会
     * 带来 1~2 秒的偏差，卡得太死会漏判「播完」，队列就会卡在曲末。
     */
    const val END_TOLERANCE_MS = 2_000L

    /**
     * 连续轮询失败多少次判定设备失联。
     *
     * 轮询是 1Hz，故 3 次 ≈ 3 秒。单次失败可能是渲染器正忙（正在换曲/转码），
     * 不足以判定失联——误判失联会让投屏莫名中断并把播放拉回本地。
     */
    const val MAX_CONSECUTIVE_POLL_FAILURES = 3

    /**
     * 是否应把「渲染器已播完当前这一首」判定为真，进而自动投下一首。
     *
     * @param wasPlaying 此前是否观测到渲染器在播（状态跃迁的起点）
     * @param isPlaying 本次观测渲染器是否仍在播
     * @param playbackState 渲染器本次上报的播放态名（[STATE_STOPPED] 等）
     * @param progressMs 渲染器上报的播放进度（毫秒）
     * @param hasProgressInfo 渲染器是否**提供了**进度信息（`GetPositionInfo` 返回非 null）。
     *        仅 `PAUSED` 使用：严格区分「未知」与「真的是 0」。
     * @param rendererDurationMs 渲染器上报的总时长（毫秒，0 = 未知）。仅 `PAUSED` 使用。
     * @param localDurationMs 本地歌曲记录的总时长（毫秒，0 = 未知）。
     *        `PAUSED` 且渲染器不上报时长时用它兜底。
     * @param hasNext 队列是否还有下一首（与本地 `next()` 的循环语义对齐后传入）
     */
    fun shouldAutoAdvance(
        wasPlaying: Boolean,
        isPlaying: Boolean,
        playbackState: String,
        progressMs: Long,
        hasProgressInfo: Boolean,
        rendererDurationMs: Long,
        localDurationMs: Long,
        hasNext: Boolean
    ): Boolean {
        // 队列已到头：投完这一首就停，不推进（回绕与否由宿主按 repeatMode 判定）
        if (!hasNext) return false
        // 必须经历「在播 → 不在播」的跃迁：从未播起过（刚投送、还在转码）不算播完
        if (!wasPlaying) return false
        if (isPlaying) return false
        // 终止态：停止或暂停。缓冲中 / 播放中 / 出错都不算「播完」
        // （出错由失联回退处理，见 DlnaCastManager.markConnectionLost）。
        if (playbackState != STATE_STOPPED && playbackState != STATE_PAUSED) return false

        // STOPPED = 当前 URI 的传输已终止，是「本首结束」唯一可靠的信号。
        // 绝不拿 progressMs / durationMs 反推「是不是停在曲中」：真机上 STOPPED 时刻的
        // RelTime 会归零、复位到上次 seek 位置或直接不给（见类注释三次真机故障）。
        // 防误推进由 CastQueueCoordinator 的「在播→不在播」跃迁与代次跳过负责。
        if (playbackState == STATE_STOPPED) return true

        // PAUSED 保持 M3 原判据：用户中途暂停也落在此态，必须靠进度排除误跳。
        // 总时长优先用渲染器上报的，不上报时用本地歌曲时长兜底。
        val total = rendererDurationMs.takeIf { it > 0 } ?: localDurationMs
        if (total <= 0L) return false
        // 进度信息本身不可用 → 无法判定是否到末尾，宁可不推进也不误跳
        if (!hasProgressInfo && progressMs <= 0L) return false
        // 进度已接近末尾 → 判定为播完
        if (progressMs >= total - END_TOLERANCE_MS) return true
        // 渲染器播完后把进度复位为 0：只有它自报了总时长（说明有完整时间轴）时，
        // 才把「归零」当作播完。渲染器不给时长时，0 也可能只是「刚投出去 / 暂停时
        // 不上报位置」，拿它跳歌会在用户按暂停后凭空切到下一首。
        return progressMs <= 0L && rendererDurationMs > 0L
    }

    /**
     * 队列最后一首播完（循环关闭、[hasNext] 为 false）时，是否应结束投屏会话。
     *
     * 真机 20261107_184914：18:48:40 `own-state STOPPED` 之后电视已经退出投屏画面，
     * 但 App 侧 `isCasting` 仍为 true——因为 [shouldAutoAdvance] 在 `!hasNext` 时直接
     * 返回 false，协调器把这次终止态当成「不用做任何事」丢掉了。DLNA push 模式下
     * 电视播完就自己结束传输，手机若不给会话一个收尾语义，就会长期停在「已连接」
     * 却没有声音、也没有画面的幽灵状态（用户只能手动点断开）。
     *
     * 判据刻意比 [shouldAutoAdvance] 更窄：
     * - 必须经历过「在播 → 不在播」跃迁，避免刚投出去还没起播就结束会话；
     * - 只认 [STATE_STOPPED]，用户中途暂停（[STATE_PAUSED]）绝不关投屏；
     * - 有下一首时返回 false，收尾只属于队列末尾。
     *
     * 收尾动作（停渲染器、复位 [CastState]、写停止态）由宿主
     * [com.puretone.player.playback.CastSessionController] 落地，本函数只判定。
     */
    fun shouldFinishQueue(
        wasPlaying: Boolean,
        isPlaying: Boolean,
        playbackState: String,
        hasNext: Boolean
    ): Boolean {
        if (hasNext) return false
        if (!wasPlaying || isPlaying) return false
        return playbackState == STATE_STOPPED
    }
}

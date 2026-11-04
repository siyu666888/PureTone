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
 * ## 为什么 STOPPED 要放宽（v1.9.2 真机故障）
 *
 * DLNA 自有通道用 `GetPositionInfo` 取进度，但不少电视在**曲目结束后**直接对该动作
 * 返回 500，于是 [hasProgressInfo] 为 false、进度与时长都是 0。旧判据把「进度未知」
 * 一票否决，协调器便永远等不到 `onCastTrackEnded()`：投屏停在曲末，而手动点下一首
 * 因为**完全不经过本判据**（`next()` → `recastCurrentIfCasting()`）反而能续投。
 * 这正是「播完就退出投屏、手动下一首却继续投」的根因。
 *
 * 放宽只在 `STOPPED` 生效，且保留「有明确进度且落在曲中 → 不推进」的保守分支；
 * `PAUSED` 的严格判据一字未动，用户按暂停仍不会擅自跳歌。
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
     *        `STOPPED` 下不再作为前置条件；`PAUSED` 下仍严格区分「未知」与「真的是 0」。
     * @param rendererDurationMs 渲染器上报的总时长（毫秒，0 = 未知）
     * @param localDurationMs 本地歌曲记录的总时长（毫秒，0 = 未知）。
     *        渲染器不上报时长时用它兜底——App 自己知道这首歌有多长。
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

        // 总时长：优先用渲染器上报的（进度是相对渲染器时间轴量的），
        // 渲染器不上报时用本地时长兜底。
        val total = rendererDurationMs.takeIf { it > 0 } ?: localDurationMs

        if (playbackState == STATE_STOPPED) {
            // STOPPED = 渲染器明确终止了本条 URI。真机上此时常拿不到 GetPositionInfo，
            // 「进度未知」绝不能等价于「还没播完」，否则队列永久卡在曲末。
            // 唯一保守分支：确实拿到了进度，且明确停在曲中（用户在电视上按了 Stop）。
            val clearlyMidTrack = hasProgressInfo && total > 0L &&
                progressMs > 0L && progressMs < total - END_TOLERANCE_MS
            return !clearlyMidTrack
        }

        // PAUSED 保持 M3 原判据：用户中途暂停也落在此态，必须靠进度排除误跳。
        if (total <= 0L) return false
        // 进度信息本身不可用 → 无法判定是否到末尾，宁可不推进也不误跳
        if (!hasProgressInfo && progressMs <= 0L) return false
        // 进度已接近末尾 → 判定为播完
        if (progressMs >= total - END_TOLERANCE_MS) return true
        // 渲染器播完后常把进度复位为 0（GetPositionInfo 归零）——同样视为已到末尾。
        // 注意这与「进度未知」不同，故上面已用 hasProgressInfo 把两者区分开。
        return progressMs <= 0L
    }
}

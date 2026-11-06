package com.puretone.player.playback.cast

/**
 * 投屏期「对外展示的进度」纯判定（v1.9.3 真机回归）。
 *
 * ## 背景
 *
 * 有一类渲染器（本次真机 623 / 192.168.101.20）`GetPositionInfo` 只回
 * `RelTime`、不回 `TrackDuration`，诊断里表现为 `tv=185000/0`。旧实现把
 * [CastState.durationMs] 直接当总时长，于是投屏遥控页判定「进度未知」→ 进度条
 * 被禁用、时间恒显 --:--，而电视其实在正常播；同时遥控页读 1Hz 原始轮询值，
 * 播放页读 250ms 外推后的 uiState，两处进度天然对不上。
 *
 * 本对象把「总时长怎么兜底」「外推最多走到哪」「锚点还新不新」三件最容易写歪的
 * 判定收成纯函数，由 [com.puretone.player.playback.CastSessionController] 调用，
 * 与 [CastQueuePolicy] / [CastResumePolicy] 一样可在纯 JVM 上逐条锁死。
 */
internal object CastProgressPolicy {

    /**
     * 投屏期应展示的总时长。
     *
     * 优先级：渲染器自报（仅当本轮允许镜像）→ 本地播放器已知 → 队列条目（Room）
     * 记录 → 0（真的未知）。渲染器没给时**必须**用本地值兜底，否则进度条没有刻度；
     * 但渲染器给了也不能在拖动乐观窗口里覆盖，避免拖动中总时长突变。
     *
     * @param mirrorRenderer 本轮是否允许把渲染器轮询值镜像进 UI（拖动乐观窗口内为 false）
     */
    fun displayDurationMs(
        rendererDurationMs: Long,
        mirrorRenderer: Boolean,
        uiDurationMs: Long,
        entryDurationMs: Long?
    ): Long = when {
        mirrorRenderer && rendererDurationMs > 0L -> rendererDurationMs
        uiDurationMs > 0L -> uiDurationMs
        entryDurationMs != null && entryDurationMs > 0L -> entryDurationMs
        else -> 0L
    }

    /**
     * 外推进度的上限：优先渲染器时长，其次本地已知时长，都没有则不封顶
     * （宁可多走一点，也不要因为渲染器给 0 就把进度条钉死在 0）。
     */
    fun projectionCapMs(rendererDurationMs: Long, uiDurationMs: Long): Long =
        rendererDurationMs.takeIf { it > 0L }
            ?: uiDurationMs.takeIf { it > 0L }
            ?: Long.MAX_VALUE

    /**
     * 外推锚点是否仍可用。
     *
     * 锚点由 1Hz 轮询刷新。拖动乐观窗口内镜像被挂起，锚点会变旧；窗口结束后若继续
     * 用「旧位置 + 累积墙钟」外推，进度条会瞬间跳到没播到的地方，再被下一帧拉回。
     * 过旧就停写这一帧，等下一次轮询重设锚点。
     */
    fun isAnchorFresh(elapsedMs: Long, maxAgeMs: Long): Boolean =
        elapsedMs > 0L && elapsedMs <= maxAgeMs
}

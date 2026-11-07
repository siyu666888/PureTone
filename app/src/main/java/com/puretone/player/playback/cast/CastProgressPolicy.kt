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
     * 外推锚点是否仍「新鲜」（尚未跨过一次 1Hz 轮询间隔）。
     *
     * 编排层据此决定**是否需要前移锚点**：过旧时把墙钟增量折进锚点后继续外推，
     * 而不是停写。拖动乐观窗口期间的停写由 `progressHoldUntilMs` 单独负责，
     * 不再靠「锚点过旧就不外推」来防跳变。
     */
    fun isAnchorFresh(elapsedMs: Long, maxAgeMs: Long): Boolean =
        elapsedMs > 0L && elapsedMs <= maxAgeMs

    /**
     * 一次外推应展示的位置；返回 null 表示本帧不写（锚点未初始化 / 时钟回拨）。
     *
     * 轮询静默（息屏 / Doze / 渲染器短时不应答）时电视仍在播，若因锚点超过 1.5s
     * 就冻结进度，手机会一直停在最后一次成功轮询的位置，与电视越差越大——真机
     * 18:26:45 结束投屏时 `tv=112000` 却是已播约 155 秒的陈旧快照。这里允许
     * 按墙钟继续线性外推，由 [projectionCapMs] 钳到总时长，下一次成功轮询再校正。
     */
    fun projectedPositionMs(anchorMs: Long, elapsedMs: Long, capMs: Long): Long? =
        if (elapsedMs <= 0L) null
        else (anchorMs + elapsedMs).coerceAtLeast(0L).coerceAtMost(capMs)
}

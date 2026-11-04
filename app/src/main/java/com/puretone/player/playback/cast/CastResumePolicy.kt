package com.puretone.player.playback.cast

/**
 * 结束投屏时「手机从电视当前进度接管」的纯判定（可纯 JVM 单测）。
 *
 * 投屏是 DLNA 一次性 push，本地 ExoPlayer 自投屏开始被暂停后就一直冻结在那一刻
 * 的位置；若期间切过歌，`next()` 还会把它归到 00:00。
 * 旧的退出链路只 `play()` 不 `seekTo()`，于是手机从旧位置续播、与电视脱节。
 *
 * 判定保守是刻意的：**进度不可得时宁可不接管**，否则 `seekTo(0)` 会让手机从头开始，
 * 比保持原位置更糟。
 */
internal object CastResumePolicy {

    /** 曲末余量：钳到 duration 之前，避免接管瞬间就触发自然结束又跳一首 */
    const val END_GUARD_MS = 1_000L

    /**
     * @param snapshot 必须在 `DlnaCastManager.stopCast()` **之前**取的状态快照
     *  （stopCast 最后一行会把 [CastState] 整体清零，电视进度当场丢失）。
     * @param localDurationMs 本地已知时长；取不到传 0。
     * @return 应 `seekTo` 的毫秒；null = 保持本地原位置，不接管。
     */
    fun takeOverPositionMs(snapshot: CastState, localDurationMs: Long): Long? {
        if (!snapshot.isCasting || snapshot.isPreparing) return null
        // 换曲重投期间进度已被清零；进度未知与「真的在 0」也必须分开。
        if (!snapshot.hasProgressInfo || snapshot.progressMs <= 0L) return null
        // 时长未知或过短时无法安全钳制，保持现状，别把手机 seek 到一个越界位置。
        if (localDurationMs <= END_GUARD_MS) return null
        return snapshot.progressMs.coerceAtMost(localDurationMs - END_GUARD_MS)
    }
}

package com.puretone.player.playback

/**
 * 交叉淡化的渐变步进计划（纯函数，便于单测）。
 *
 * 抽取自 [PlaybackManager.startCrossfade] 的音量渐变内联计算。淡化听感的好坏取决于
 * 两件极小的事：**总时长要与实际剩余时间对齐**（否则尾段被强行截断或淡化早就结束），
 * 以及**最后一步的音量必须精确落到 0 / 1**（否则会有残余音量或起播突跳，听感上是爆音）。
 *
 * 这两点都是纯数值关系，与 ExoPlayer / 协程无关，因此抽出来独立锁定。
 *
 * 语义与原实现严格等价（见各类注释中的等价说明）。
 */
object CrossfadeRamp {

    /** 渐变步长（毫秒）。50ms 一步 ≈ 20Hz 更新率，听感平滑且开销可忽略。 */
    const val STEP_MS = 50L

    /**
     * 本次淡化的实际总时长。
     *
     * 取「配置时长」与「本曲剩余时间」的较小者：剩余时间不足时按剩余时间做完，
     * 避免淡化尚未结束就被播放器切歌打断。
     *
     * 剩余时间向下钳到 1ms，保证退化路径（remain<=0，例如 position 已越过 duration）
     * 仍能得到正的时长而非 0/负数——原实现用 `remainMs.coerceAtLeast(1)` 表达同一意图。
     *
     * @param crossfadeMs 用户配置的淡化时长；<=0 表示关闭（正常路径不可达，返回 0 安全降级）
     * @param remainingMs 当前曲目剩余时间，可能 <=0（进度越界）
     * @return 实际演化时长（毫秒），调用方据此计算步数
     */
    fun rampDurationMs(crossfadeMs: Int, remainingMs: Long): Long {
        if (crossfadeMs <= 0) return 0L
        val remain = remainingMs.coerceAtLeast(1L)
        return minOf(crossfadeMs.toLong(), remain)
    }

    /**
     * 渐变步数。
     *
     * 至少 1 步：即使 [rampMs] 极短（<100ms，典型于剩余时间几乎耗尽）也必须有一次
     * 音量下发，否则备援播放器会停在 setVolume(0) 的静音态起播。
     *
     * 等价说明：原实现 `if (rampMs / 50 > 1) (rampMs / 50).toInt() else 1`——
     * 当 rampMs/50 <= 1（即 <=99ms）时取 1，本实现同。
     */
    fun steps(rampMs: Long): Int {
        if (rampMs <= 0L) return 1
        val n = rampMs / STEP_MS
        return if (n > 1L) n.toInt() else 1
    }

    /** 每步实际延时。整数除法会让总时略短于 [rampMs]（至多 1 步的零头），可忽略。 */
    fun stepMs(rampMs: Long): Long {
        if (rampMs <= 0L) return 0L
        return rampMs / steps(rampMs)
    }

    /**
     * 第 [stepIndex] 步（0 基）时**淡出方**（旧主播放器）的音量。
     *
     * 从 1 线性降到 0；最后一步（stepIndex == totalSteps-1）精确为 0f。
     */
    fun outgoingVolume(stepIndex: Int, totalSteps: Int): Float {
        if (totalSteps <= 0) return 0f
        val t = (stepIndex + 1).toFloat() / totalSteps
        return (1f - t).coerceIn(0f, 1f)
    }

    /**
     * 第 [stepIndex] 步（0 基）时**淡入方**（备援播放器）的音量。
     *
     * 从 0 线性升到 1；最后一步（stepIndex == totalSteps-1）精确为 1f。
     * 与 [outgoingVolume] 之和恒为 1（等功率近似下的线性交叉，听感上无整体音量凹陷）。
     */
    fun incomingVolume(stepIndex: Int, totalSteps: Int): Float {
        if (totalSteps <= 0) return 1f
        val t = (stepIndex + 1).toFloat() / totalSteps
        return t.coerceIn(0f, 1f)
    }
}

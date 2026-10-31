package com.puretone.player.playback

/**
 * 交叉淡化触发决策（纯函数，便于单测）。
 *
 * 抽取自 [PlaybackManager.maybeStartCrossfade] 的前置守卫，把「何时该启动淡化」的判定
 * 与播放器/队列状态解耦，使最易错的边界条件（临近结尾的时间窗、APE 退化、关闭/无下一首）
 * 可被独立锁定，未来改动不会静默破坏触发逻辑。
 *
 * 设计要点：本对象不参与任何音频/协程操作，仅做数值判定，无副作用。
 */
object CrossfadePolicy {

    /**
     * ticker 粒度余量（毫秒）。ticker 每 500ms 轮询一次，预留 400ms 防止在两次轮询之间
     * 错过「本曲结尾」边界（错过则主播放器按原路径正常切歌，仅本次无淡化，不崩溃）。
     */
    private const val LOOKAHEAD_HEADROOM_MS = 400L

    /**
     * 是否应在当前播放进度启动交叉淡化。
     *
     * 触发条件（全部满足才返回 true）：
     * 1. 淡化已开启（[crossfadeMs] > 0）；
     * 2. 存在下一首（[hasNext]）；
     * 3. 当前曲目时长已知（[durationMs] > 0）；
     * 4. 剩余时间落在窗口 (0, crossfadeMs + 余量] 内——即「临近结尾」；
     * 5. 下一首非需转码格式（[isApe]，APE 在边界无法精确对齐，退化为无缝/曲间停顿）。
     *
     * @param durationMs 当前曲目总时长（毫秒，<=0 视为未知）
     * @param positionMs 当前播放位置（毫秒）
     * @param crossfadeMs 淡化时长（毫秒，<=0 表示关闭）
     * @param hasNext 是否存在下一首
     * @param isApe 下一首是否为需异步转码的格式
     */
    fun shouldTrigger(
        durationMs: Long,
        positionMs: Long,
        crossfadeMs: Int,
        hasNext: Boolean,
        isApe: Boolean
    ): Boolean {
        if (crossfadeMs <= 0 || !hasNext) return false
        if (durationMs <= 0) return false
        val remain = durationMs - positionMs
        // 仅在「临近结尾的时间窗」内触发：remain ∈ (0, crossfadeMs + 余量]
        if (remain <= 0 || remain > crossfadeMs + LOOKAHEAD_HEADROOM_MS) return false
        if (isApe) return false
return true
    }
}

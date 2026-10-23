package com.puretone.player.data.metadata

/**
 * 歌词提供者：按「歌手 + 歌名」联网获取歌词文本。
 *
 * 实现需自行切 IO 线程；网络失败 / 无结果时返回 null（优雅降级，不抛异常）。
 */
interface LyricsProvider {
    /** 数据源名称（UI 展示用，如 "LRCLIB"） */
    val sourceName: String

    /**
     * 获取 LRC 文本。
     *
     * 优先返回逐行同步歌词（带时间戳）；仅纯文本歌词时按 [durationMs] 均排为伪 LRC，
     * 保证播放页高亮可用。找不到返回 null。
     *
     * @param artist 歌手（可为空）
     * @param title 歌名
     * @param durationMs 歌曲时长（毫秒），用于纯文本歌词均排；<=0 时按每行约 3.5s 兜底
     */
    suspend fun fetch(artist: String, title: String, durationMs: Long): String?
}

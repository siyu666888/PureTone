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

    /**
     * 带音频路径的取词（可选增强）。
     *
     * 实现了本地歌词渠道（歌曲同目录 `.lrc` / 音频内嵌歌词）的实现应覆写本方法，
     * 以便在联网之前先命中本地歌词——本地有就无需联网，既快又省流量。
     * 默认实现退化为 [fetch]，保证既有实现无需改动即可工作。
     *
     * @param audioPath 音频绝对路径
     */
    suspend fun fetchWithPath(
        audioPath: String?,
        artist: String,
        title: String,
        durationMs: Long
    ): String? = fetch(artist, title, durationMs)

    /**
     * 上次取词尝试过的渠道摘要（UI 展示用）。
     *
     * 用户反馈「歌词有时候会下载失败」时，只说「未找到歌词」会让人以为是歌名打错；
     * 列出实际试过的渠道，能把排查方向直接指出来（本地没文件 / 内嵌没有 / 词库确实没有）。
     * 单渠道实现没有这个概念，返回 null（不展示）。
     */
    fun lastAttemptSummary(): String? = null
}

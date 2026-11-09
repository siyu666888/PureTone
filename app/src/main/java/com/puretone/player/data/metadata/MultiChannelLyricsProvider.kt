package com.puretone.player.data.metadata

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 多渠道歌词聚合器。
 *
 * ## 为什么要有多个渠道
 *
 * 用户反馈「歌词有时候会下载失败」。这类失败其实分三类，**成因完全不同**，
 * 靠给单一渠道加重试是治不好的：
 *
 * 1. **本地本来就有歌词**（用户手动放的 `.lrc`、文件内嵌的歌词）——
 *    联网再多次也没用，因为压根不需要联网；
 * 2. **词库里没有这首歌**（LRCLIB 覆盖以英文/欧美为主，华语/日语常查不到）——
 *    重试一万次也查不到，得换一个覆盖更广的词库；
 * 3. **网络/服务端抖动**（限流、境外接口慢、临时不可用）——
 *    这才轮到重试与多源兜底。
 *
 * 所以本聚合器按**「先本地、再广覆盖、后精确」的顺序**依次尝试，
 * 命中即止，不做无谓的联网：
 *
 * | 顺序 | 渠道 | 类型 | 覆盖 |
 * |---|---|---|---|
 * | 1 | 同目录同名 `.lrc` | 本地 | 用户自备，最准 |
 * | 2 | 音频内嵌歌词（ID3 `USLT`/`SYLT`） | 本地 | 转换器导出的歌常有 |
 * | 3 | AMLL TTML 词库 | 联网 | 华语/日语覆盖好，逐词 |
 * | 4 | LRCLIB 精确匹配 | 联网 | 欧美流行乐覆盖好，逐行 |
 * | 5 | LRCLIB 搜索兜底 | 联网 | 同上，但模糊匹配 |
 *
 * 联网渠道放在本地渠道之后，是**为了省流量与省时间**：本地有就绝不联网。
 * AMLL 排在 LRCLIB 之前，是因为对中文用户而言 AMLL 的命中率更高；
 * 两者都失败时才算「确实找不到」。
 *
 * ## 与 [LyricsProvider] 接口的关系
 *
 * 本类是 [LyricsProvider] 的**唯一对外实现**（DI 绑定见
 * `MetadataModule.bindLyricsProvider`）；原先的 `LrcLibLyricsProvider`
 * 被降级为本类的内部渠道之一，接口从外部看完全不变——
 * 上层的 [com.puretone.player.domain.usecase.DownloadLyricsUseCase]
 * 无需任何改动。
 */
@Singleton
class MultiChannelLyricsProvider @Inject constructor(
    private val localProvider: LocalLyricsProvider,
    private val amllProvider: AmllLyricsProvider,
    private val lrcLibProvider: LrcLibLyricsProvider,
) : LyricsProvider {

    /**
     * 数据源名称（UI 展示用）。
     *
     * 聚合后不再单一，故展示为「本地 / AMLL / LRCLIB」这样的合并表述，
     * 让用户知道有多条路可走，而不是误以为只有某一个源。
     */
    override val sourceName: String = "本地 / AMLL / LRCLIB"

    /**
     * P3-3：命中渠道与尝试轨迹收敛到**一份原子快照**，
     * 不再用两个独立的 `@Volatile var` 分两步写（并发取词会读到错配状态）。
     */
    private val attemptLog = LyricsAttemptLog()

    /** 各渠道命中情况，供 UI/诊断展示（key 见 [Channel]） */
    val lastHitChannel: Channel? get() = attemptLog.hit

    /**
     * 本次尝试过的渠道（按顺序），供失败提示展示。
     *
     * 用户反馈「歌词有时候会下载失败」时，最需要知道的是**到底试过哪些路**——
     * 只说「未找到歌词」会让用户以为是歌名打错，而实际可能是本地没文件、
     * 内嵌没有、词库确实没有。列出尝试轨迹能把排查方向直接指出来。
     */
    val lastAttemptedChannels: List<Channel> get() = attemptLog.attempted

    /** 渠道的简短中文名（UI 展示用） */
    fun Channel.displayName(): String = when (this) {
        Channel.SIDECAR_LRC -> "本地 LRC"
        Channel.EMBEDDED -> "内嵌歌词"
        Channel.AMLL -> "AMLL"
        Channel.LRCLIB_EXACT -> "LRCLIB"
        Channel.LRCLIB_SEARCH -> "LRCLIB 搜索"
    }

    /** 尝试轨迹摘要（UI 展示用）：「本地 LRC、内嵌歌词、AMLL、LRCLIB」 */
    override fun lastAttemptSummary(): String? =
        lastAttemptedChannels.joinToString("、") { it.displayName() }.ifBlank { null }

    /** 歌词渠道枚举 */
    enum class Channel {
        /** 同目录同名 .lrc */
        SIDECAR_LRC,

        /** 音频内嵌歌词（ID3） */
        EMBEDDED,

        /** AMLL TTML 词库 */
        AMLL,

        /** LRCLIB 精确匹配 */
        LRCLIB_EXACT,

        /** LRCLIB 搜索兜底 */
        LRCLIB_SEARCH,
    }

    /**
     * 按优先级依次尝试各渠道，返回第一个成功的 LRC 文本。
     *
     * @param artist 歌手（可为空）
     * @param title 歌名
     * @param durationMs 歌曲时长，用于把纯文本歌词均排为伪 LRC
     * @param audioPath 音频绝对路径；本地渠道需要它（为空则跳过本地渠道）
     */
    override suspend fun fetch(artist: String, title: String, durationMs: Long): String? =
        fetchWithPath(audioPath = null, artist = artist, title = title, durationMs = durationMs)

    /**
     * 带音频路径的取词（本地渠道需要）。
     *
     * 与 [fetch] 的区别仅在于多了本地渠道的入参；
     * 覆写接口的默认实现，让调用方只要传了路径就能自动获得本地歌词优先的能力。
     */
    override suspend fun fetchWithPath(
        audioPath: String?,
        artist: String,
        title: String,
        durationMs: Long
    ): String? {
        if (title.isBlank()) {
            attemptLog.reset()
            return null
        }
        attemptLog.reset()
        // 记录本次尝试过的渠道（顺序即优先级），失败时用于提示用户
        val attempted = mutableListOf<Channel>()

        // 1) 本地：同目录 .lrc + 内嵌歌词（不联网，最快最准）
        if (!audioPath.isNullOrBlank()) {
            localProvider.fetch(audioPath, durationMs)?.let {
                // 本地渠道内部有两条：先判断到底是 .lrc 还是内嵌，便于诊断
                val hit = if (hasSidecarLrc(audioPath)) Channel.SIDECAR_LRC else Channel.EMBEDDED
                attemptLog.record(hit, attempted + hit)
                return it
            }
            // 本地没命中：两条本地渠道都算试过了
            attempted.add(Channel.SIDECAR_LRC)
            attempted.add(Channel.EMBEDDED)
        }

        // 2) AMLL 词库（华语/日语覆盖好）
        attempted.add(Channel.AMLL)
        amllProvider.fetch(artist, title, durationMs)?.let {
            attemptLog.record(Channel.AMLL, attempted)
            return it
        }

        // 3) LRCLIB（精确 + 搜索兜底）
        attempted.add(Channel.LRCLIB_EXACT)
        lrcLibProvider.fetch(artist, title, durationMs)?.let {
            val hit = when (lrcLibProvider.lastMatch) {
                LrcLibLyricsProvider.Match.EXACT -> Channel.LRCLIB_EXACT
                else -> Channel.LRCLIB_SEARCH
            }
            attemptLog.record(hit, attempted)
            return it
        }
        attempted.add(Channel.LRCLIB_SEARCH)

        attemptLog.record(null, attempted)
        return null
    }

    /** 是否存在同目录同名 .lrc（仅用于判定命中的是哪条本地渠道） */
    private fun hasSidecarLrc(audioPath: String): Boolean {
        val file = java.io.File(audioPath)
        val dir = file.parentFile ?: return false
        val base = file.nameWithoutExtension
        return listOf(java.io.File(dir, "$base.lrc"), java.io.File(dir, "$base.LRC"))
            .any { it.isFile && it.length() > 0 }
    }
}

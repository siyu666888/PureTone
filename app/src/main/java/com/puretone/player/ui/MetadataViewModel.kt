package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.metadata.LyricsProvider
import com.puretone.player.data.metadata.MetadataCandidate
import com.puretone.player.data.metadata.SongMetadataProvider
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.lyrics.LyricsStore
import com.puretone.player.util.ChineseText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 联网元数据匹配视图模型：搜索候选、应用到歌曲、自动填充、下载歌词、批量补全缺失封面。
 * 全程失败降级——网络不可用时 results 保持空并给出提示，不崩溃。
 */
@HiltViewModel
class MetadataViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val provider: SongMetadataProvider,
    private val lyricsProvider: LyricsProvider,
    private val lyricsStore: LyricsStore
) : ViewModel() {

    /** 数据源名称（UI 展示） */
    val providerName: String = provider.sourceName

    /** 歌词数据源名称（UI 展示） */
    val lyricsProviderName: String = lyricsProvider.sourceName

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<MetadataCandidate>>(emptyList())
    val results: StateFlow<List<MetadataCandidate>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** 歌词下载进行中（按钮 loading 态） */
    private val _lyricsDownloading = MutableStateFlow(false)
    val lyricsDownloading: StateFlow<Boolean> = _lyricsDownloading.asStateFlow()

    /** 一次性提示（应用成功 / 网络失败等） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 批量补全进度：已处理 / 总数（null 表示未在进行） */
    private val _backfill = MutableStateFlow<Pair<Int, Int>?>(null)
    val backfill: StateFlow<Pair<Int, Int>?> = _backfill.asStateFlow()

    fun setQuery(q: String) { _query.value = q }

    fun consumeMessage() { _message.value = null }

    /** 候选信息统一转简体（标题 / 歌手 / 专辑），保证展示与落库均为简体。 */
    private fun simplify(c: MetadataCandidate): MetadataCandidate = c.copy(
        title = ChineseText.toSimplified(c.title) ?: c.title,
        artist = ChineseText.toSimplified(c.artist) ?: c.artist,
        album = ChineseText.toSimplified(c.album) ?: c.album
    )

    /** 构造搜索词：跳过「未知艺术家 / 空」，避免污染搜索 */
    private fun buildTerm(artist: String, title: String): String =
        listOf(
            artist.takeIf { it.isNotBlank() && it != "<unknown>" && it != "未知艺术家" },
            title
        ).filter { !it.isNullOrBlank() }.joinToString(" ").trim()

    fun search(term: String = _query.value) {
        if (term.isBlank()) {
            _message.value = "请输入歌手或歌名"
            return
        }
        viewModelScope.launch {
            _searching.value = true
            val list = runCatching { provider.search(term) }.getOrDefault(emptyList()).map { simplify(it) }
            _searching.value = false
            _results.value = list
            if (list.isEmpty()) _message.value = "未找到匹配（${provider.sourceName}），可检查网络或更换关键词"
        }
    }

    /**
     * 自动填充：按当前表单的「歌手 歌名」搜索并自动套用首个候选（封面 + 信息）。
     * @param onApplied 套用成功回调（用于同步表单字段，入参为被套用的候选）
     */
    fun autoFill(songId: Long, artist: String, title: String, onApplied: (MetadataCandidate) -> Unit = {}) {
        val term = buildTerm(artist, title)
        if (term.isBlank()) {
            _message.value = "请先填写歌名或歌手"
            return
        }
        viewModelScope.launch {
            _searching.value = true
            val list = runCatching { provider.search(term, 5) }.getOrDefault(emptyList()).map { simplify(it) }
            _searching.value = false
            _results.value = list
            val best = list.firstOrNull()
            if (best == null) {
                _message.value = "未找到匹配（${provider.sourceName}），可检查网络或更换关键词"
                return@launch
            }
            runCatching {
                repo.updateSongInfo(
                    id = songId,
                    title = best.title,
                    artist = best.artist,
                    album = best.album,
                    coverUri = best.artworkUrl,
                    year = best.year
                )
            }.onSuccess {
                _message.value = "已自动填充：「${best.title}」"
                _results.value = emptyList()
                onApplied(best)
            }.onFailure {
                _message.value = "更新失败：${it.message}"
            }
        }
    }

    /** 把候选信息写入歌曲 */
    fun apply(songId: Long, candidate: MetadataCandidate, onApplied: (MetadataCandidate) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                repo.updateSongInfo(
                    id = songId,
                    title = candidate.title,
                    artist = candidate.artist,
                    album = candidate.album,
                    coverUri = candidate.artworkUrl,
                    year = candidate.year
                )
            }.onSuccess {
                _message.value = "已更新为「${candidate.title}」"
                _results.value = emptyList()
                onApplied(candidate)
            }.onFailure {
                _message.value = "更新失败：${it.message}"
            }
        }
    }

    /**
     * 下载歌词：按「歌手 歌名」从歌词源抓取，经 [LyricsStore] 存入 App 外部目录。
     * @param audioPath 音频文件路径（仅用于映射歌词存储位置，不要求可写）
     * @param onDone 完成回调（true=已保存，供播放页重载歌词）
     */
    fun downloadLyrics(audioPath: String, artist: String, title: String, durationMs: Long, onDone: (Boolean) -> Unit = {}) {
        if (audioPath.isBlank()) {
            _message.value = "无法定位歌曲文件"
            onDone(false)
            return
        }
        val term = buildTerm(artist, title)
        if (term.isBlank()) {
            _message.value = "请先填写歌名或歌手"
            onDone(false)
            return
        }
        viewModelScope.launch {
            _lyricsDownloading.value = true
            val lrc = runCatching { lyricsProvider.fetch(artist, title, durationMs) }.getOrNull()
            _lyricsDownloading.value = false
            if (lrc.isNullOrBlank()) {
                _message.value = "未找到歌词（${lyricsProvider.sourceName}）"
                onDone(false)
                return@launch
            }
            val ok = runCatching {
                withContext(Dispatchers.IO) { lyricsStore.save(audioPath, lrc) }
            }.getOrDefault(false)
            _message.value = if (ok) "歌词已下载并保存" else "歌词保存失败"
            onDone(ok)
        }
    }

    /**
     * 批量补全缺失封面：对每首无封面歌曲按「歌手 歌名」搜索首个候选并套用其封面。
     * @param onDone 完成回调（成功补全数量）
     */
    fun backfillMissingCovers(onDone: (Int) -> Unit) {
        if (_backfill.value != null) return
        viewModelScope.launch {
            val missing = runCatching { repo.getSongsMissingCover() }.getOrDefault(emptyList())
            if (missing.isEmpty()) {
                _message.value = "没有需要补全封面的歌曲"
                onDone(0)
                return@launch
            }
            var done = 0
            missing.forEachIndexed { i, song ->
                _backfill.value = i to missing.size
                val term = "${song.artist} ${song.title}".trim()
                val candidate = runCatching { provider.search(term, 1).firstOrNull() }.getOrNull()
                if (candidate?.artworkUrl != null) {
                    runCatching { repo.updateCover(song.id, candidate.artworkUrl) }
                    done++
                }
            }
            _backfill.value = null
            _message.value = "封面补全完成：${done} / ${missing.size} 首"
            onDone(done)
        }
    }
}

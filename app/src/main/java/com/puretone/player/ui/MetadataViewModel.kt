package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.metadata.LyricsProvider
import com.puretone.player.data.metadata.MetadataCandidate
import com.puretone.player.data.metadata.SongMetadataProvider
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.lyrics.LyricsStore
import com.puretone.player.util.ChineseText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** 自动填充是否可撤销（最近一次 autoFill 成功写入后为真） */
    private val _canUndoAutoFill = MutableStateFlow(false)
    val canUndoAutoFill: StateFlow<Boolean> = _canUndoAutoFill.asStateFlow()

    /** 最近一次自动填充前的原歌曲（用于撤销） */
    private var lastAutoFilled: SongEntity? = null

    /** 批量补全任务（用于取消） */
    private var backfillJob: Job? = null

    fun setQuery(q: String) {
        _query.value = q
        _results.value = emptyList()   // P2-2：改查询词时清空上一轮候选，避免结果残留
    }

    /** 清空搜索候选（P2-2：「清空结果」与切歌时调用） */
    fun clearResults() {
        _results.value = emptyList()
    }

    /**
     * 低置信匹配保护：去掉空白与标点后，候选标题与查询标题存在包含关系才算高置信。
     * 避免 autoFill 无脑套用第一条结果导致错误匹配被永久写入。
     */
    private fun isLikelyMatch(queryTitle: String, candidateTitle: String): Boolean {
        val norm = { s: String ->
            s.lowercase().replace(Regex("[\\s\\p{Punct}]"), "")
        }
        val a = norm(queryTitle)
        val b = norm(candidateTitle)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

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
            // P2-2：低置信匹配不自动写入，改为展示候选让用户手动选择，避免错误匹配被永久保留
            if (!isLikelyMatch(title, best.title)) {
                _message.value = "未找到高置信匹配，请从候选中选择或手动修改"
                return@launch
            }
            val original = runCatching { repo.getSong(songId) }.getOrNull()
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
                lastAutoFilled = original
                _canUndoAutoFill.value = true
                _message.value = "已自动填充：「${best.title}」"
                _results.value = emptyList()
                onApplied(best)
            }.onFailure {
                _message.value = "更新失败：${it.message}"
            }
        }
    }

    /**
     * 撤销最近一次自动填充：恢复原歌曲信息（P2-2）。
     * @param onUndone 撤销成功回调，入参为被恢复的原始歌曲实体
     *                 （供调用方把表单字段一并还原，避免「库里撤回了、
     *                 表单还留着自动填充的值」——再点保存会把候选值又写回去）。
     */
    fun undoAutoFill(onUndone: (SongEntity) -> Unit = {}) {
        val original = lastAutoFilled ?: return
        viewModelScope.launch {
            runCatching {
                repo.updateSongInfo(
                    id = original.id,
                    title = original.title,
                    artist = original.artist,
                    album = original.album,
                    coverUri = original.coverUri,
                    year = original.year
                )
            }.onSuccess {
                lastAutoFilled = null
                _canUndoAutoFill.value = false
                _message.value = "已撤销自动填充"
                onUndone(original)
            }.onFailure {
                _message.value = "撤销失败：${it.message}"
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
     * 串行处理、每次请求间隔限流，支持取消；完成后重新查询实际剩余数量用于校验。
     * @param onDone 完成回调（成功补全数量）
     */
    fun backfillMissingCovers(onDone: (Int) -> Unit) {
        if (_backfill.value != null) return
        backfillJob = viewModelScope.launch {
            val missing = runCatching { repo.getSongsMissingCover() }.getOrDefault(emptyList())
            if (missing.isEmpty()) {
                _message.value = "没有需要补全封面的歌曲"
                onDone(0)
                return@launch
            }
            var done = 0
            try {
                missing.forEachIndexed { i, song ->
                    _backfill.value = i to missing.size
                    val term = "${song.artist} ${song.title}".trim()
                    val candidate = runCatching { provider.search(term, 1).firstOrNull() }.getOrNull()
                    if (candidate?.artworkUrl != null) {
                        // P2-8：旧实现丢弃 updateCover 的結果、无条件 done++，
                        // 与上面「仅当确实写入了封面才计入成功」的注释自相矛盾
                        // （写库失败也被算成成功，进度数字误导用户）。
                        if (runCatching { repo.updateCover(song.id, candidate.artworkUrl) }.isSuccess) {
                            done++
                        }
                    }
                    delay(700) // P2-2：限流，避免触发数据源配额导致大量失败
                }
            } finally {
                _backfill.value = null
            }
            // P2-2：计数校验——重新查询实际剩余缺失封面数
            val remaining = runCatching { repo.getSongsMissingCover().size }.getOrDefault(0)
            _message.value = "封面补全完成：$done / ${missing.size} 首（剩余 $remaining 首未补全）"
            onDone(done)
        }
    }

    /** 取消正在进行的批量封面补全（P2-2） */
    fun cancelBackfill() {
        backfillJob?.cancel()
        backfillJob = null
        _backfill.value = null
    }
}

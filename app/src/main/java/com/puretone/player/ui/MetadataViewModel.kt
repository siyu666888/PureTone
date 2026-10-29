package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.metadata.LyricsProvider
import com.puretone.player.data.metadata.MetadataCandidate
import com.puretone.player.data.metadata.SongMetadataProvider
import com.puretone.player.domain.metadata.buildSearchTerm
import com.puretone.player.domain.usecase.ApplyCandidateUseCase
import com.puretone.player.domain.usecase.AutoFillMetadataUseCase
import com.puretone.player.domain.usecase.BackfillCoversUseCase
import com.puretone.player.domain.usecase.DownloadLyricsUseCase
import com.puretone.player.domain.usecase.SearchMetadataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 联网元数据匹配视图模型：搜索候选、应用到歌曲、自动填充、下载歌词、批量补全缺失封面。
 *
 * 编排逻辑已收口到 domain 用例（Search / ApplyCandidate / AutoFill / DownloadLyrics /
 * BackfillCovers），本 VM 只负责：
 * - 暴露数据源名称、UI 状态（搜索中 / 结果 / 进度 / 提示）；
 * - 取消在途任务（P2-16 连点取消旧搜索、可取消批量补全）；
 * - 把用例返回的结果映射为 message / 撤销快照 / 表单同步回调。
 * 全程失败降级——网络不可用时 results 保持空并给出提示，不崩溃。
 */
@HiltViewModel
class MetadataViewModel @Inject constructor(
    private val searchUseCase: SearchMetadataUseCase,
    private val applyCandidateUseCase: ApplyCandidateUseCase,
    private val autoFillUseCase: AutoFillMetadataUseCase,
    private val downloadLyricsUseCase: DownloadLyricsUseCase,
    private val backfillCoversUseCase: BackfillCoversUseCase,
    private val provider: SongMetadataProvider,
    private val lyricsProvider: LyricsProvider
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

    /** 当前搜索任务（P2-16：连点时取消上一个在途请求，避免旧结果覆盖新结果） */
    private var searchJob: Job? = null

    fun setQuery(q: String) {
        _query.value = q
        _results.value = emptyList()   // P2-2：改查询词时清空上一轮候选，避免结果残留
    }

    /** 清空搜索候选（P2-2：「清空结果」与切歌时调用） */
    fun clearResults() {
        _results.value = emptyList()
    }

    fun consumeMessage() { _message.value = null }

    fun search(term: String = _query.value) {
        if (term.isBlank()) {
            _message.value = "请输入歌手或歌名"
            return
        }
        // P2-16：先取消上一个在途搜索，连点时只保留最新结果、且不会提前清掉转圈
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            val list = searchUseCase(term)
            _searching.value = false
            _results.value = list
            if (list.isEmpty()) _message.value = "未找到匹配（${provider.sourceName}），可检查网络或更换关键词"
        }
    }

    /**
     * 自动填充：按当前表单的「歌手 歌名」搜索并自动套用首个高置信候选（封面 + 信息）。
     * @param onApplied 套用成功回调（用于同步表单字段，入参为被套用的候选）
     */
    fun autoFill(songId: Long, artist: String, title: String, onApplied: (MetadataCandidate) -> Unit = {}) {
        viewModelScope.launch {
            _searching.value = true
            when (val r = autoFillUseCase(songId, artist, title)) {
                is AutoFillMetadataUseCase.AutoFillResult.Applied -> {
                    lastAutoFilled = r.original
                    _canUndoAutoFill.value = true
                    _message.value = "已自动填充：「${r.candidate.title}」"
                    _results.value = emptyList()
                    _searching.value = false
                    onApplied(r.candidate)
                }
                is AutoFillMetadataUseCase.AutoFillResult.NoMatch -> {
                    _results.value = r.candidates
                    _searching.value = false
                    _message.value = "未找到高置信匹配，请从候选中选择或手动修改"
                }
                is AutoFillMetadataUseCase.AutoFillResult.Failed -> {
                    _results.value = r.candidates
                    _searching.value = false
                    _message.value = r.message
                }
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
            applyCandidateUseCase(
                original.id,
                MetadataCandidate(
                    title = original.title,
                    artist = original.artist,
                    album = original.album,
                    artworkUrl = original.coverUri,
                    year = original.year
                )
            ).onSuccess {
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
            applyCandidateUseCase(songId, candidate)
                .onSuccess {
                    _message.value = "已更新为「${candidate.title}」"
                    _results.value = emptyList()
                    onApplied(candidate)
                }
                .onFailure {
                    _message.value = "更新失败：${it.message}"
                }
        }
    }

    /**
     * 下载歌词：按「歌手 歌名」从歌词源抓取，经 [DownloadLyricsUseCase] 存入 App 外部目录。
     * @param onDone 完成回调（true=已保存，供播放页重载歌词）
     */
    fun downloadLyrics(audioPath: String, artist: String, title: String, durationMs: Long, onDone: (Boolean) -> Unit = {}) {
        if (audioPath.isBlank()) {
            _message.value = "无法定位歌曲文件"
            onDone(false)
            return
        }
        val term = buildSearchTerm(artist, title)
        if (term.isBlank()) {
            _message.value = "请先填写歌名或歌手"
            onDone(false)
            return
        }
        viewModelScope.launch {
            _lyricsDownloading.value = true
            val outcome = downloadLyricsUseCase(audioPath, artist, title, durationMs)
            _lyricsDownloading.value = false
            _message.value = when (outcome) {
                DownloadLyricsUseCase.Outcome.SAVED -> "歌词已下载并保存"
                DownloadLyricsUseCase.Outcome.NO_LYRICS -> "未找到歌词（${lyricsProvider.sourceName}）"
                DownloadLyricsUseCase.Outcome.SAVE_FAILED -> "歌词保存失败"
            }
            onDone(outcome == DownloadLyricsUseCase.Outcome.SAVED)
        }
    }

    /**
     * 批量补全缺失封面：委托 [BackfillCoversUseCase] 串行搜索+套用（限流 700ms、仅成功计数、重查校验）。
     * 本 VM 仅维护进度状态与取消，编排细节收口到用例。
     * @param onDone 完成回调（成功补全数量）
     */
    fun backfillMissingCovers(onDone: (Int) -> Unit) {
        if (_backfill.value != null) return
        backfillJob = viewModelScope.launch {
            try {
                val (done, total, remaining) = backfillCoversUseCase { i, t -> _backfill.value = i to t }
                _backfill.value = null
                if (total == 0) {
                    _message.value = "没有需要补全封面的歌曲"
                    onDone(0)
                } else {
                    _message.value = "封面补全完成：$done / $total 首（剩余 $remaining 首未补全）"
                    onDone(done)
                }
            } finally {
                _backfill.value = null
            }
        }
    }

    /** 取消正在进行的批量封面补全（P2-2） */
    fun cancelBackfill() {
        backfillJob?.cancel()
        backfillJob = null
        _backfill.value = null
    }
}

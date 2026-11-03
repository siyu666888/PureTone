package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.metadata.LyricsProvider
import com.puretone.player.data.metadata.MetadataCandidate
import com.puretone.player.data.metadata.SongMetadataProvider
import com.puretone.player.domain.metadata.NetworkMetadataGate
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
import com.puretone.player.R

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
    private val lyricsProvider: LyricsProvider,
    /** P2-4：联网总开关守卫，用于给出准确提示（拦截本身在用例层） */
    private val gate: NetworkMetadataGate
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

    /** 一次性提示（应用成功 / 网络失败等），以 UiMessage 承载（资源模板或动态文本） */
    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

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
            _message.value = UiMessage.Res(R.string.pt_meta_001)
            return
        }
        // P2-16：先取消上一个在途搜索，连点时只保留最新结果、且不会提前清掉转圈
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // P2-4：联网总开关关闭时给出准确文案。用例层也会拦（防止其他调用方绕过），
            // 但只有 VM 知道「为什么是空」——不查一次开关的话，空结果会被显示成
            // 「未找到匹配」，用户会以为是自己搜索词写错了。
            if (!gate.isEnabled()) {
                _searching.value = false
                _results.value = emptyList()
                _message.value = UiMessage.Res(R.string.pt_meta_002)
                return@launch
            }
            _searching.value = true
            val list = searchUseCase(term)
            _searching.value = false
            _results.value = list
            if (list.isEmpty()) _message.value = UiMessage.Res(R.string.pt_meta_003, listOf<Any>(provider.sourceName))
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
                    // 候选标题为用户内容（可能含 % 等特殊字符），保持 Raw 避免 format 崩溃
                    _message.value = UiMessage.Raw("已自动填充：「${r.candidate.title}」")
                    _results.value = emptyList()
                    _searching.value = false
                    onApplied(r.candidate)
                }
                is AutoFillMetadataUseCase.AutoFillResult.NoMatch -> {
                    _results.value = r.candidates
                    _searching.value = false
                    _message.value = UiMessage.Res(R.string.pt_meta_004)
                }
                is AutoFillMetadataUseCase.AutoFillResult.Failed -> {
                    _results.value = r.candidates
                    _searching.value = false
                    _message.value = UiMessage.Raw(r.message)
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
                _message.value = UiMessage.Res(R.string.pt_meta_005)
                onUndone(original)
            }.onFailure {
                // 异常 message 为动态内容，保持 Raw
                _message.value = UiMessage.Raw("撤销失败：${it.message}")
            }
        }
    }

    /** 把候选信息写入歌曲 */
    fun apply(songId: Long, candidate: MetadataCandidate, onApplied: (MetadataCandidate) -> Unit = {}) {
        viewModelScope.launch {
            applyCandidateUseCase(songId, candidate)
                .onSuccess {
                    // 候选标题为用户内容，保持 Raw
                    _message.value = UiMessage.Raw("已更新为「${candidate.title}」")
                    _results.value = emptyList()
                    onApplied(candidate)
                }
                .onFailure {
                    // 异常 message 为动态内容，保持 Raw
                    _message.value = UiMessage.Raw("更新失败：${it.message}")
                }
        }
    }

    /**
     * 下载歌词：按「歌手 歌名」从歌词源抓取，经 [DownloadLyricsUseCase] 存入 App 外部目录。
     * @param onDone 完成回调（true=已保存，供播放页重载歌词）
     */
    fun downloadLyrics(audioPath: String, artist: String, title: String, durationMs: Long, onDone: (Boolean) -> Unit = {}) {
        if (audioPath.isBlank()) {
            _message.value = UiMessage.Res(R.string.pt_meta_006)
            onDone(false)
            return
        }
        val term = buildSearchTerm(artist, title)
        if (term.isBlank()) {
            _message.value = UiMessage.Res(R.string.pt_meta_007)
            onDone(false)
            return
        }
        viewModelScope.launch {
            _lyricsDownloading.value = true
            val outcome = downloadLyricsUseCase(audioPath, artist, title, durationMs)
            _lyricsDownloading.value = false
            _message.value = when (outcome) {
                DownloadLyricsUseCase.Outcome.SAVED -> UiMessage.Res(R.string.pt_meta_008)
                DownloadLyricsUseCase.Outcome.NO_LYRICS -> {
                    // 多渠道实现能给出尝试轨迹——直接告诉用户试过哪些路，
                    // 避免用户以为是歌名打错而反复重试。
                    val attempted = lyricsProvider.lastAttemptSummary()
                    if (attempted.isNullOrBlank()) {
                        UiMessage.Res(R.string.pt_meta_009, listOf<Any>(lyricsProvider.sourceName))
                    } else {
                        UiMessage.Res(R.string.pt_meta_013, listOf<Any>(attempted))
                    }
                }
                DownloadLyricsUseCase.Outcome.SAVE_FAILED -> UiMessage.Res(R.string.pt_meta_010)
                // P2-4：联网总开关已关闭——明确告知是开关拦下的，
                // 否则用户会误以为「这首歌就是没歌词」而去反复重试。
                DownloadLyricsUseCase.Outcome.DISABLED -> UiMessage.Res(R.string.pt_meta_002)
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
                    _message.value = UiMessage.Res(R.string.pt_meta_011)
                    onDone(0)
                } else {
                    _message.value = UiMessage.Res(R.string.pt_meta_012, listOf<Any>(done, total, remaining))
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

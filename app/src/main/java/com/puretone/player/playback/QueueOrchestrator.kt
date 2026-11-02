package com.puretone.player.playback

import android.net.Uri
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.cast.DlnaCastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 队列编排（P1-1 拆分第八刀）。
 *
 * 把「队列如何构造、落盘、按用户操作增删改」这一簇逻辑从 [PlaybackManager] 下沉到本类。
 * 这是 god class 拆分里咬合最深的几块之一，因为它同时牵动：
 * - [PlaybackCommandGate]（代次 / 挂起命令 / APE 重试标记三份隐式状态）；
 * - [ApePlaybackResolver]（APE 按需转码与「转码中」标记语义）；
 * - [DlnaCastManager]（投屏态不起播本地、切歌后重投渲染器）；
 * - [PlaybackSessionStore]（清空队列时清存档）。
 *
 * 设计要点（与 [PlaybackController] 同源手法）：
 * - 可共享的依赖（player 提供方、uiState、crossfade、gate、dlnaCast、serviceLauncher、
 *   sessionStore、apeResolver、scope）直接持有，零间接层；
 * - 仅「宿主才拥有的副作用」经 [PlaybackQueueSink] 窄接口回调：投屏重投、立即存档、
 *   ReplayGain 刷新、APE 预热、空闲停服。这些是 [PlaybackManager] 的私有方法，且
 *   依赖事件层（onTrackSideEffects 等），故留在宿主。
 * - cancelCrossfade 本就只是 [CrossfadeCoordinator.cancel] 的转发，这里直接持 crossfade。
 *
 * 队列项唯一实例序号（拖拽重排稳定 key 的来源）此前散在主类，现归一至此。
 *
 * ExoPlayer 为 final、项目无 mock 框架，故队列编排不强行构造播放器替身；其行为等价性
 * 由逐行对齐 + 播放包回归测试 + CI 守门保证。
 */
internal class QueueOrchestrator(
    private val playerProvider: () -> ExoPlayer?,
    private val uiState: MutableStateFlow<PlaybackUiState>,
    private val crossfade: CrossfadeCoordinator,
    private val gate: PlaybackCommandGate,
    private val dlnaCast: DlnaCastManager,
    private val serviceLauncher: PlaybackServiceLauncher,
    private val sessionStore: PlaybackSessionStore,
    private val apeResolver: ApePlaybackResolver,
    private val scope: CoroutineScope,
    private val sink: PlaybackQueueSink
) {
    /** 队列项唯一实例序号（拖拽重排需稳定 key，同一首歌可多次入队） */
    private var queueEntrySeq: Long = 0L
    private fun nextQueueKey(): Long = ++queueEntrySeq

    /** 设置队列并播放 */
    fun setQueueAndPlay(songs: List<SongEntity>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        crossfade.cancel()
        val p = playerProvider()
        if (p == null) {
            // P1-3：显式点歌优先于挂起的续播（理由同 bind）——互斥由 gate 内部强制，
            // 不再依赖此处手动清 pendingRestore。
            gate.pendPlay(PendingPlay(songs, startIndex, startPositionMs))
            serviceLauncher.ensureStarted()
            return
        }
        scope.launch { applyQueueAndPlay(songs, startIndex, startPositionMs, autoPlay = true) }
    }

    /**
     * 真正设置队列。[autoPlay] 显式控制是否 play()：
     * - 用户主动播放 / 续播恢复都走这里，恢复时传 false，避免「取决于队列里有没有 APE」
     *   （APE 会在 ensureWav 挂起，pause() 先于 play() 执行 → 恢复后不自动播放，非 APE 又自动播放）。
     */
    internal suspend fun applyQueueAndPlay(
        songs: List<SongEntity>,
        startIndex: Int,
        startPositionMs: Long,
        autoPlay: Boolean
    ) {
        val p = playerProvider() ?: return
        if (songs.isEmpty()) return
        // P1-3：本次「设置队列」命令的代次。下面 APE 转码会挂起（长曲可达数十秒），
        // 期间用户完全可能又点了别的歌；本协程恢复后若发现代次已被后来的命令刷新，
        // 就说明自己是过期任务，必须放弃——否则会用旧队列覆盖掉新队列
        // （表现为「点了新歌，播的却是上一首/旧队列」）。
        val generation = gate.nextGeneration()
        val entries = songs.map { PlaybackMediaItems.toQueueEntry(it, nextQueueKey()) }

        // 只转码「即将播放的这一首」：整队预转码会让 20 首 APE 的专辑等几十秒才出声。
        // 其余 APE 在切歌/出错时按需转码（见 playAtIndex / handlePlayerError / warmUpFollowingApe）。
        // P3-17：索引不再裸取——越界（调用方传错）时静默放弃，而不是抛 IndexOutOfBounds。
        val startSong = songs.getOrNull(startIndex) ?: return
        // 转码中标记与异常/取消语义统一由 apeResolver 负责（含 finally 复位）
        val startUri = apeResolver.resolveUri(
            songId = startSong.id,
            path = startSong.path,
            isApe = startSong.isApe,
            mediaStoreUri = { PlaybackMediaItems.mediaStoreUri(startSong) }
        )
        if (startUri == null) {
            uiState.update { it.copy(error = "无法解析播放地址") }
            return
        }

        // 其余项先按「非 APE 直连、APE 占位源路径」入库；切到它们时再按需转码替换 URI。
        val mediaItems = songs.mapIndexed { index, song ->
            if (index == startIndex) {
                PlaybackMediaItems.toMediaItem(song, startUri)
            } else if (song.isApe) {
                PlaybackMediaItems.toMediaItem(song, PlaybackMediaItems.rawPathUri(song.path))
            } else {
                PlaybackMediaItems.toMediaItem(song, PlaybackMediaItems.mediaStoreUri(song))
            }
        }

        // 挂起点（APE 转码）之后重新确认自己仍是「最新」的队列命令
        if (!gate.isCurrent(generation)) return

        // P2-14：新队列生效前复位 APE 重试标记——它是按「队列内索引」记的，
        // 旧队列 index=2 的 APE 重试过一次，新队列同样 index=2 的 APE
        // 首次出错就会被误判「已重试过」而跳过转码，直接卡在 IDLE。
        // 用 resetApeRetry 而非 onQueueStructureChanged：后者会再递增代次，
        // 把本协程刚取的号弄失效、导致下面全部写入被跳过。
        gate.resetApeRetry()

        p.setMediaItems(mediaItems, startIndex, startPositionMs)
        p.prepare()
        // 投屏态不起播本地：这里只是重建「镜像队列」，出声的是随后重新投送的渲染器。
        // 否则手机与电视同时出声，且电视仍停在旧曲上。
        if (dlnaCast.state.value.isCasting) p.pause() else if (autoPlay) p.play() else p.pause()
        uiState.update {
            it.copy(
                queue = entries,
                queueIndex = startIndex,
                queueOffset = 0,
                currentSongId = startSong.id,
                durationMs = startSong.duration,
                error = null
            )
        }
        // 投屏中直接点了另一首：把新曲投给渲染器，否则电视继续播旧的那首
        sink.recastCurrentIfCasting()
        sink.saveSessionNow()
        sink.applyReplayGainForCurrent()
        if (!autoPlay) sink.warmUpFollowingApe()
    }

    /** 追加到队列末尾（不打断当前播放）；player 未就绪时挂起，bind 后执行 */
    fun enqueue(songs: List<SongEntity>) {
        crossfade.cancel()
        val p = playerProvider()
        if (p == null) {
            gate.pendEnqueue(songs)
            serviceLauncher.ensureStarted()
            return
        }
        if (songs.isEmpty()) return
        // P2-13：先作废在途的 setQueueAndPlay（如 APE 首播挂起在 ensureWav）——它恢复后
        // 检测到代次不符会放弃，避免用户的追加被旧队列覆盖。
        // 连带复位 APE 重试标记：追加本身不改已有索引，但标记与代次同属「队列状态已变」，
        // 由同一个入口维护可避免以后有人只改一半。
        gate.onQueueStructureChanged()
        val entries = songs.map { PlaybackMediaItems.toQueueEntry(it, nextQueueKey()) }
        scope.launch {
            p.addMediaItems(songs.map { PlaybackMediaItems.lazyMediaItem(it) })
            uiState.update { it.copy(queue = it.queue + entries) }
        }
    }

    /**
     * 插到当前曲目之后优先播放（不打断当前播放）。
     *
     * 队列为空或尚未起播时退化为 [setQueueAndPlay]，保证首次操作也能出声。
     */
    fun playNext(songs: List<SongEntity>) {
        crossfade.cancel()
        val p = playerProvider()
        if (p == null || uiState.value.queue.isEmpty()) {
            setQueueAndPlay(songs)
            return
        }
        if (songs.isEmpty()) return
        // P2-13：先作废在途 setQueueAndPlay，避免插播被旧队列覆盖。
        // 插到当前曲之后会让后续项索引整体右移，APE 重试标记（按索引记）必须一并复位。
        gate.onQueueStructureChanged()
        val entries = songs.map { PlaybackMediaItems.toQueueEntry(it, nextQueueKey()) }
        scope.launch {
            val insertAt = (uiState.value.queueIndex + 1).coerceIn(0, uiState.value.queue.size)
            p.addMediaItems(insertAt, songs.map { PlaybackMediaItems.lazyMediaItem(it) })
            uiState.update { state ->
                val merged = state.queue.toMutableList().also { q -> q.addAll(insertAt, entries) }
                state.copy(queue = merged)
            }
        }
    }

    /** 播放队列中指定索引（APE 按需转码后替换 URI） */
    fun playAtIndex(index: Int) {
        crossfade.cancel()
        // P2-13：服务未就绪时旧实现写 PendingPlay(emptyList(), index, 0L)，
        // bind 后 doSetQueueAndPlay 在 songs.isEmpty() 处直接 return → 点了队列项却无声。
        // 改为标记「待恢复会话」，服务起来后由 bind() 真正恢复上次队列。
        val p = playerProvider() ?: run { gate.pendRestore(); serviceLauncher.ensureStarted(); return }
        if (index !in uiState.value.queue.indices) return
        scope.launch {
            val entry = uiState.value.queue[index]
            if (entry.isApe) {
                apeResolver.transcodeToFile(entry.songId, entry.filePath)
                    ?.let { replaceMediaItemUri(index, it) }
            }
            p.seekTo(index, 0L)
            p.prepare()
            if (dlnaCast.state.value.isCasting) {
                // 投屏态不本地起播：只推进本地镜像（通知栏据此显示当前曲），
                // 真正出声的是随后重新投送的渲染器。否则手机与电视同时出声。
                p.pause()
            } else {
                p.play()
            }
            sink.recastCurrentIfCasting()
        }
    }

    /**
     * 从队列移除指定索引。
     * 必须同步修正 [PlaybackUiState.queueIndex]：删除当前曲之前的条目后
     * player.currentMediaItemIndex 已左移，UI 若按旧索引取 currentEntry 会显示错歌。
     */
    fun removeFromQueue(index: Int) {
        crossfade.cancel()
        val p = playerProvider() ?: return
        if (index !in uiState.value.queue.indices) return
        // P2-13：先作废在途 setQueueAndPlay，避免删除被旧队列还原。
        // 删除会让后续项索引左移——旧实现漏了复位 APE 重试标记，导致删掉前一首后，
        // 另一个 APE 的下标被错认成「已重试过」而跳过转码，直接卡在 IDLE。
        gate.onQueueStructureChanged()
        val currentIndex = p.currentMediaItemIndex
        p.removeMediaItem(index)
        uiState.update { state ->
            val newQueue = state.queue.filterIndexed { i, _ -> i != index }
            val newIndex = when {
                index < currentIndex -> currentIndex - 1
                index == currentIndex -> currentIndex.coerceAtMost(newQueue.lastIndex)
                else -> currentIndex
            }
            state.copy(queue = newQueue, queueIndex = newIndex)
        }
    }

    /**
     * 队列内拖拽重排：把 [from] 移到 [to] 位置，并同步当前播放索引。
     *
     * [androidx.media3.common.Player.moveMediaItem] 会自动维护正在播放项的
     * currentMediaItemIndex（当前曲被移动时索引跟随），故直接用真实索引对齐
     * [PlaybackUiState.queueIndex]，比 [removeFromQueue] 的手动计算更稳。
     * 同步清除 APE 重试标记（按索引记，重排后失效，理由同 [clearQueue]）。
     */
    fun moveQueueItem(from: Int, to: Int) {
        crossfade.cancel()
        val p = playerProvider() ?: return
        val queue = uiState.value.queue
        if (from !in queue.indices || to !in queue.indices || from == to) return
        // P2-13：先作废在途 setQueueAndPlay，避免重排被旧队列覆盖。
        // 重排会打乱索引映射，APE 重试标记由 onQueueStructureChanged 一并复位。
        gate.onQueueStructureChanged()
        p.moveMediaItem(from, to)
        uiState.update { state ->
            val newQueue = state.queue.toMutableList().also { it.add(to, it.removeAt(from)) }
            state.copy(queue = newQueue, queueIndex = p.currentMediaItemIndex)
        }
    }

    /**
     * 清空队列。只重置「瞬时」字段，保留用户偏好（播放顺序/随机/倍速），
     * 否则 UI 显示的播放顺序会和播放器真实状态不一致（历史 bug）。
     */
    fun clearQueue() {
        crossfade.cancel()
        playerProvider()?.clearMediaItems()
        uiState.update { state ->
            state.copy(
                queue = emptyList(),
                queueIndex = -1,
                queueOffset = 0,
                currentSongId = null,
                positionMs = 0L,
                durationMs = 0L,
                isTranscoding = false,
                isBuffering = false,
                error = null
            )
        }
        // 清空同样属于「队列结构变化」：既作废 APE 重试标记（P2-14），也递增代次——
        // 旧实现漏了后者，仍挂起在 APE 转码里的旧 setQueue 恢复后会把队列又写回来
        // （历史现象：点了清空，队列隔一会儿自己冒回来）。
        gate.onQueueStructureChanged()
        sessionStore.clear()
        sink.stopServiceIfIdle()
    }

    /**
     * 把队列指定索引的媒体项 URI 替换为转码产物（WAV）。
     *
     * P0-2：转码可挂起数十秒，期间用户可能 clearQueue() / removeFromQueue() / stop()
     * 把 playlist 清空或缩短；协程恢复后若仍用旧 index 调 getMediaItemAt 会抛
     * IndexOutOfBoundsException，而该异常发生在无 try/catch 的 scope.launch 里，
     * 经 SupervisorJob 直达未捕获异常处理器 → 进程崩溃。此处做边界 + 异常双保险。
     */
    fun replaceMediaItemUri(index: Int, wavFile: File) {
        val p = playerProvider() ?: return
        if (index !in 0 until p.mediaItemCount) return
        runCatching {
            val updated = p.getMediaItemAt(index).buildUpon().setUri(Uri.fromFile(wavFile)).build()
            p.replaceMediaItem(index, updated)
        }.onFailure { Log.w(TAG, "replaceMediaItemUri failed at index=$index", it) }
    }

    private companion object {
        const val TAG = "QueueOrchestrator"
    }
}

/**
 * 队列编排无法在 [QueueOrchestrator] 内落地的宿主副作用。
 * [PlaybackManager] 实现本接口——这些方法依赖它独有的私有字段与事件层
 * （如 onTrackSideEffects 在切歌时触发 warmUpFollowingApe / applyReplayGainForCurrent）。
 */
interface PlaybackQueueSink {
    /** 投屏期本地切歌后，把当前曲重新投给渲染器 */
    fun recastCurrentIfCasting()

    /** 立即把当前会话写入存档 */
    fun saveSessionNow()

    /** 刷新当前曲目的 ReplayGain 增益 */
    fun applyReplayGainForCurrent()

    /** 预热下一首 APE 转码（仅一首，fire-and-forget） */
    fun warmUpFollowingApe()

    /** 播放器空闲（非播放且队列空）时停掉前台服务 */
    fun stopServiceIfIdle()
}

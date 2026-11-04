package com.puretone.player.playback

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放错误自愈处理器（P1-1 收尾抽取）。
 *
 * 把「坏文件怎么办」这一簇逻辑从 [PlaybackManager] 下沉到本类：
 * - APE 失败：同一索引只允许自动转码重试一次（[PlaybackCommandGate.tryAcquireApeRetry]），
 *   转码成功则替换媒体项 URI 续播，失败则跳过；
 * - 非 APE 错误（文件被删 / MediaStore URI 失效 / 编码不支持 / 读盘瞬时 IO 失败…）：与主流
 *   播放器一致，「坏一首跳一首」继续往下放，而不是让整条队列停住——这正是「播着播着自动停、
 *   且只在恰好轮到那一首时出现、听起来像一定概率」的根因。
 *
 * 防死循环：连续跳过超过 [MAX_CONSECUTIVE_ERROR_SKIPS] 首判定「队列不可用」，暂停但保留队列。
 * 连续失败计数由 [onPlaying] 在真正播起来时归零——只有「一直在失败」才判定为坏队列。
 */
internal class PlaybackErrorHandler(
    private val playerProvider: () -> ExoPlayer?,
    private val uiState: MutableStateFlow<PlaybackUiState>,
    private val gate: PlaybackCommandGate,
    private val apeResolver: ApePlaybackResolver,
    private val queueOrchestrator: QueueOrchestrator,
    private val scope: CoroutineScope,
    private val stop: () -> Unit,
) : PlaybackErrorPort {
    /** 连续「播放失败自动跳过」的计数（防止整条队列都是坏文件时无限空转） */
    private var consecutiveErrorSkips: Int = 0

    private companion object {
        /** 连续失败跳过上限：超过就判定队列不可用，暂停但保留队列 */
        const val MAX_CONSECUTIVE_ERROR_SKIPS = 3
    }

    /** 处理一次播放错误（由 [PlaybackManager.onError] 转发） */
    override fun handle(error: PlaybackException) {
        val p = playerProvider() ?: return
        val index = p.currentMediaItemIndex
        val entry = uiState.value.queue.getOrNull(index)
        PlaybackEventLog.record(
            "player-error",
            "code=${error.errorCodeName} idx=$index song=${entry?.songId ?: -1} isApe=${entry?.isApe} " +
                "msg=${error.message}"
        )
        // 按队列内索引记忆重试资格：同一索引只自动转码重试一次，防止
        // 「出错 → 转码 → 再出错 → 再转码」死循环。写法保持「判断 + 占位」原子完成。
        if (entry != null && entry.isApe && gate.tryAcquireApeRetry(index)) {
            scope.launch {
                // 转码失败/取消的语义统一由 apeResolver 处理（finally 复位标记，
                // 异常收敛为 null，取消原样抛出），此处只负责结果分支。
                val wav = apeResolver.transcodeToFile(entry.songId, entry.filePath)
                if (wav != null) {
                    queueOrchestrator.replaceMediaItemUri(index, wav)
                    p.prepare()
                    p.play()
                } else {
                    uiState.update { it.copy(error = "「${entry.title}」APE 转码失败，已跳过") }
                    if (p.hasNextMediaItem()) p.seekToNextMediaItem() else stop()
                }
            }
            return
        }
        uiState.update { it.copy(error = error.errorCodeName + ": " + (error.message ?: "播放失败")) }
        // 关键修复：非 APE 错误原实现只写一条错误文案就结束，ExoPlayer 已落 IDLE，
        // 整条队列就此停住。正确做法与主流播放器一致：坏一首跳一首，继续往下放。
        skipAfterPlaybackError(p, entry)
    }

    /**
     * 单曲播放失败后的自愈：跳过这一首继续放，而不是让整条队列停在这里。
     *
     * 防死循环：连续跳过超过 [MAX_CONSECUTIVE_ERROR_SKIPS] 首就判定「队列不可用」，
     * 暂停但**保留队列**（用户还能手动挑别的歌），并在 UI 上留下错误文案。
     * 计数由 [onPlaying] 在真正播起来时归零。
     */
    private fun skipAfterPlaybackError(p: ExoPlayer, failed: QueueEntry?) {
        consecutiveErrorSkips++
        PlaybackEventLog.record(
            "error-skip",
            "consecutive=$consecutiveErrorSkips failedSong=${failed?.songId ?: -1} " +
                "hasNext=${p.hasNextMediaItem()}"
        )
        if (consecutiveErrorSkips > MAX_CONSECUTIVE_ERROR_SKIPS) {
            consecutiveErrorSkips = 0
            PlaybackEventLog.record("error-give-up", "连续失败超过上限，暂停并保留队列")
            p.pause()
            return
        }
        when {
            p.hasNextMediaItem() -> {
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            }
            // 已到队列末尾：列表循环（ALL / ONE，ONE 时 hasNext 恒 true 走不到这里）
            // 情况下回绕到第一首再试，否则正常结束。
            p.mediaItemCount > 0 && uiState.value.repeatMode != RepeatMode.OFF -> {
                val first = p.currentTimeline.getFirstWindowIndex(p.shuffleModeEnabled)
                p.seekTo(if (first >= 0) first else 0, 0L)
                p.prepare()
                p.play()
            }
            else -> p.pause()
        }
    }

    /** 真正播起来时归零连续失败计数（由 [PlaybackManager.onIsPlayingChanged] 转发） */
    override fun onPlaying() {
        consecutiveErrorSkips = 0
    }
}

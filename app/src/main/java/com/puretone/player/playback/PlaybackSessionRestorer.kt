package com.puretone.player.playback

import androidx.media3.exoplayer.ExoPlayer

/**
 * 续播恢复（P1-1 拆分收尾第十一刀）。
 *
 * 恢复以 [autoPlay=false] 重建队列并暂停；守卫 [canRestoreSession] 必须在进程级单例层做，
 * 因为「播放器里已有内容」时恢复会打断当前播放——每个导航目的地用 `hiltViewModel()`
 * 拿到的是独立实例，其 init 都会跑一次恢复，二次恢复会打断正在播放的歌。
 *
 * 本类只搬运「读取存档 → 二次守卫 → 重建队列」这段逻辑；真正的落地动作（applyQueueAndPlay）
 * 仍在 [QueueOrchestrator]，因为队列重建依赖它独有的播放器与 APE 转码编排。
 */
internal class PlaybackSessionRestorer(
    private val playerProvider: () -> ExoPlayer?,
    private val sessionStore: SessionStorePort,
    private val queueOrchestrator: QueueApplyPort
) {
    /**
     * 恢复上次播放会话；返回 true 表示本次已执行恢复。
     *
     * 守卫重复两次：上面的 DB 读取是挂起点，这期间用户可能已经点了别的歌起播
     * （setQueueAndPlay 会重建队列）。起播前必须再查一次守卫，否则旧会话队列会覆盖
     * 用户的新选择。
     */
    suspend fun restore(): Boolean {
        val p = playerProvider()
        if (!canRestoreSession(playerItemCount = p?.mediaItemCount ?: 0, isPlaying = p?.isPlaying == true)) {
            return false
        }
        // 按保存顺序重建队列（含重复项）：顺序还原与「曲目已删除」过滤都在 sessionStore 内
        val restored = sessionStore.restoreQueue() ?: return false
        val songs = restored.songs
        // 挂起期间用户可能已点别的歌起播，起播前再查一次守卫
        val current = playerProvider()
        if (!canRestoreSession(
                playerItemCount = current?.mediaItemCount ?: 0,
                isPlaying = current?.isPlaying == true
            )
        ) {
            return false
        }
        // autoPlay=false：恢复后停在当前位置，是否播交给用户
        queueOrchestrator.applyQueueAndPlay(
            songs,
            restored.index,
            restored.positionMs,
            autoPlay = false
        )
        return true
    }
}

package com.puretone.player.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.entity.SongEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlaybackSessionRestorer] 单测（P1-1 拆分收尾第十一刀的落地验证）。
 *
 * 锁定「读取存档 → 二次守卫 → 重建队列」这段在搬出 [PlaybackManager] 后正确：
 * - 守卫通过 + 存档存在 → 调 [QueueApplyPort.applyQueueAndPlay]（autoPlay=false）返回 true；
 * - 存档为空 → 返回 false，不重建；
 * - 首次守卫拒绝（播放器已有内容）→ 返回 false，不读存档；
 * - 二次守卫（恢复期间用户起播）→ 返回 false，不重建。
 *
 * 守卫谓词本身已由 [RestoreGuardTest] 固化，这里验证的是 restorer 对它的两次调用与短路。
 * 需要「播放器已有内容」的状态，故用 Robolectric 构造真实 [ExoPlayer] 并塞入一条媒体项
 * （不实际播放，仅让 `mediaItemCount > 0` 使守卫拒绝）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSessionRestorerTest {

    private lateinit var context: Context
    private lateinit var realPlayer: ExoPlayer
    private var currentPlayer: ExoPlayer? = null
    private val sessionStore = FakeSessionStore()
    private val queue = FakeQueueApply()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        realPlayer = ExoPlayer.Builder(context).build()
        // 塞入一条媒体项让 mediaItemCount == 1，使守卫拒绝（不实际播放）
        realPlayer.setMediaItem(MediaItem.fromUri("file:///dev/null"))
        currentPlayer = null
    }

    @After
    fun tearDown() {
        realPlayer.release()
    }

    private fun song(id: Long) = SongEntity(
        mediaStoreId = id,
        title = "t$id",
        artist = "a",
        album = "al",
        albumId = 1L,
        duration = 1000L,
        path = "/p/$id",
        size = 100L,
        mimeType = "audio/mpeg"
    )

    private fun build() = PlaybackSessionRestorer(
        playerProvider = { currentPlayer },
        sessionStore = sessionStore,
        queueOrchestrator = queue
    )

    @Test
    fun `守卫通过且存档存在时重建队列并返回 true`() = runTest {
        currentPlayer = null
        sessionStore.restoreQueueResult = RestoredQueue(listOf(song(1L), song(2L)), 1, 500L)
        val ok = build().restore()
        assertTrue(ok)
        assertEquals(1, queue.applyCount)
        assertEquals(listOf(song(1L), song(2L)), queue.lastSongs)
        assertEquals(1, queue.lastIndex)
        assertEquals(500L, queue.lastPositionMs)
        assertEquals(false, queue.lastAutoPlay)
    }

    @Test
    fun `存档为空时返回 false 不重建队列`() = runTest {
        currentPlayer = null
        sessionStore.restoreQueueResult = null
        val ok = build().restore()
        assertFalse(ok)
        assertEquals(0, queue.applyCount)
    }

    @Test
    fun `首次守卫拒绝时不读存档直接返回 false`() = runTest {
        currentPlayer = realPlayer // mediaItemCount == 1 → 守卫拒绝
        var restoreRead = false
        sessionStore.onRestore = { restoreRead = true }
        val ok = build().restore()
        assertFalse(ok)
        assertFalse(restoreRead)
        assertEquals(0, queue.applyCount)
    }

    @Test
    fun `二次守卫拦截恢复期间的并发起播`() = runTest {
        currentPlayer = null
        sessionStore.restoreQueueResult = RestoredQueue(listOf(song(1L)), 0, 0L)
        // 模拟「读存档的挂起间隙里用户点别的歌起播」：恢复队列时被切换成有内容的播放器
        sessionStore.onRestore = { currentPlayer = realPlayer }
        val ok = build().restore()
        assertFalse(ok)
        assertEquals(0, queue.applyCount)
    }
}

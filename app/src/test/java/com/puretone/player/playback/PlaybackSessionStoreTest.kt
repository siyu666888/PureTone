package com.puretone.player.playback

import com.puretone.player.data.local.entity.SongEntity
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放会话存档的语义测试。
 *
 * 锁的是两个已经踩过、且错了要等「下次冷启动」才发现的修复点：
 * 1. 播完时 `currentPosition == duration`，原样存会让下次恢复 seek 到结尾立刻再次播完
 *    （P2-17，表现为「续播恢复了但什么都没发生」）；
 * 2. 按 id 批量查询是 `IN (:ids)`，既无 ORDER BY 也会被 SQL 去重（P0-7，
 *    直接拿结果恢复会顺序错位、重复曲目丢失）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSessionStoreTest {

    /** 替身档案：模拟真实 SQL 的行为——返回**无序且已去重**的结果 */
    private class FakeArchive(
        var saved: SavedSession? = null,
        var available: List<SongEntity> = emptyList()
    ) : PlaybackSessionArchive {

        val writes = mutableListOf<SavedSession>()
        var clearCount = 0

        override suspend fun load(): SavedSession? = saved

        override suspend fun songsByIds(ids: List<Long>): List<SongEntity> =
            // 模拟 IN(:ids)：去重且不保证顺序（这里刻意按 id 升序返回，与保存顺序不同）
            available.filter { it.id in ids }.distinctBy { it.id }.sortedBy { it.id }

        override suspend fun save(songIds: List<Long>, index: Int, positionMs: Long) {
            writes += SavedSession(songIds, index, positionMs)
        }

        override suspend fun clear() {
            clearCount++
        }
    }

    private fun song(id: Long) = SongEntity(
        id = id,
        mediaStoreId = 100L + id,
        title = "曲$id",
        artist = "歌手",
        album = "专辑",
        albumId = 7L,
        duration = 180_000L,
        path = "/sdcard/Music/$id.mp3",
        size = 1024L,
        coverUri = null,
        mimeType = "audio/mpeg"
    )

    private fun TestScope.store(archive: FakeArchive) =
        PlaybackSessionStore(archive = archive, scope = this)

    // ---------- 位置钳制 ----------

    @Test
    fun `播完时位置钳到结尾前 500ms`() {
        val store = runTestStore()
        // 30000ms 的曲子播完：原样存 30000 → 恢复后立刻再次播完
        assertEquals(29_500L, store.positionToSave(rawPositionMs = 30_000L, durationMs = 30_000L))
    }

    @Test
    fun `正常播放位置原样保留`() {
        val store = runTestStore()
        assertEquals(12_345L, store.positionToSave(rawPositionMs = 12_345L, durationMs = 180_000L))
    }

    @Test
    fun `极短曲目保底为 0`() {
        val store = runTestStore()
        // 时长 300ms < 500ms 余量，(duration-500) 会被 coerceAtLeast 拉到 0
        assertEquals(0L, store.positionToSave(rawPositionMs = 300L, durationMs = 300L))
    }

    @Test
    fun `时长未知时不做钳制`() {
        val store = runTestStore()
        assertEquals(4_200L, store.positionToSave(rawPositionMs = 4_200L, durationMs = 0L))
    }

    @Test
    fun `负位置归零`() {
        val store = runTestStore()
        assertEquals(0L, store.positionToSave(rawPositionMs = -1L, durationMs = 180_000L))
    }

    // ---------- 去抖 ----------

    @Test
    fun `去抖窗口内连续保存只写一次`() = runTest {
        val archive = FakeArchive()
        val store = store(archive)

        store.saveDebounced { SavedSession(listOf(1L, 2L), 0, 1_000L) }
        advanceTimeBy(300)
        store.saveDebounced { SavedSession(listOf(1L, 2L), 0, 2_000L) }
        advanceTimeBy(300)
        store.saveDebounced { SavedSession(listOf(1L, 2L), 0, 3_000L) }
        advanceTimeBy(1_000)

        assertEquals(1, archive.writes.size)
        assertEquals(3_000L, archive.writes.first().positionMs)
    }

    @Test
    fun `立即保存会取消待写入的去抖任务`() = runTest {
        val archive = FakeArchive()
        val store = store(archive)

        store.saveDebounced { SavedSession(listOf(1L), 0, 1_000L) }
        store.saveNow(SavedSession(listOf(1L), 0, 5_000L))
        advanceTimeBy(2_000)

        assertEquals(1, archive.writes.size)
        assertEquals(5_000L, archive.writes.first().positionMs)
    }

    @Test
    fun `空队列不写入`() = runTest {
        val archive = FakeArchive()
        val store = store(archive)

        store.saveNow(SavedSession(emptyList(), 0, 0L))
        advanceTimeBy(1_000)

        assertEquals(0, archive.writes.size)
    }

    @Test
    fun `clear 会取消待写入的去抖任务并清档`() = runTest {
        val archive = FakeArchive()
        val store = store(archive)

        store.saveDebounced { SavedSession(listOf(1L), 0, 1_000L) }
        store.clear()
        advanceTimeBy(2_000)

        assertEquals(0, archive.writes.size)
        assertEquals(1, archive.clearCount)
    }

    // ---------- 恢复顺序 ----------

    @Test
    fun `恢复按保存顺序重排，不被 SQL 的无序去重破坏`() = runTest {
        val archive = FakeArchive(
            saved = SavedSession(songIds = listOf(3L, 1L, 2L), index = 1, positionMs = 5_000L),
            available = listOf(song(1), song(2), song(3))
        )
        val store = store(archive)

        val restored = store.restoreQueue()

        // 档案返回的是按 id 升序的 [1,2,3]，必须还原成保存时的 [3,1,2]
        assertEquals(listOf(3L, 1L, 2L), restored!!.songs.map { it.id })
        assertEquals(1, restored.index)
        assertEquals(5_000L, restored.positionMs)
    }

    @Test
    fun `重复曲目在恢复中保留`() = runTest {
        val archive = FakeArchive(
            saved = SavedSession(songIds = listOf(7L, 7L, 8L), index = 0, positionMs = 0L),
            available = listOf(song(7), song(8))
        )
        val store = store(archive)

        // IN(:ids) 会去重成 [7,8]，按保存顺序重排后必须回到三条
        assertEquals(listOf(7L, 7L, 8L), store.restoreQueue()!!.songs.map { it.id })
    }

    @Test
    fun `曲目已被删除时过滤掉，且索引收敛到有效范围`() = runTest {
        val archive = FakeArchive(
            saved = SavedSession(songIds = listOf(1L, 2L, 3L), index = 2, positionMs = 0L),
            available = listOf(song(1), song(3)) // 2 已被删除
        )
        val store = store(archive)

        val restored = store.restoreQueue()!!

        assertEquals(listOf(1L, 3L), restored.songs.map { it.id })
        assertEquals(1, restored.index) // 原索引 2 越界，收敛到最后一项
    }

    @Test
    fun `全部曲目已删除则不恢复`() = runTest {
        val archive = FakeArchive(
            saved = SavedSession(songIds = listOf(1L, 2L), index = 0, positionMs = 0L),
            available = emptyList()
        )
        assertNull(store(archive).restoreQueue())
    }

    @Test
    fun `无存档或空存档不恢复`() = runTest {
        assertNull(store(FakeArchive(saved = null)).restoreQueue())
        assertNull(
            store(
                FakeArchive(saved = SavedSession(emptyList(), 0, 0L), available = listOf(song(1)))
            ).restoreQueue()
        )
    }

    // positionToSave 是纯函数，不需要协程环境；这里只为拿到一个实例
    private fun runTestStore(): PlaybackSessionStore =
        PlaybackSessionStore(archive = FakeArchive(), scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()))
}

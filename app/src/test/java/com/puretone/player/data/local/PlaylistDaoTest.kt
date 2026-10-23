package com.puretone.player.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.dao.PlaylistWithSongs
import com.puretone.player.data.local.entity.PlayHistoryEntity
import com.puretone.player.data.local.entity.PlaylistEntity
import com.puretone.player.data.local.entity.PlaylistSongEntity
import com.puretone.player.data.local.entity.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 歌单 DAO 行为测试（P1-4 + P3-5）。
 *
 * 覆盖审查报告点名的三个盲区：
 * 1. 歌单歌曲必须按 position 排序（旧版 @Relation 不保证，导致顺序错乱）；
 * 2. 删除歌单 / 删除歌曲时关联行级联清理（旧版留孤儿行）；
 * 3. 增删歌曲刷新 updated_at（「我的」页按更新时间排序的依据）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistDaoTest {

    private lateinit var db: PureToneDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PureToneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun song(id: Long, path: String) = SongEntity(
        id = id,
        mediaStoreId = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        duration = 200_000,
        path = path,
        size = 1_000L,
        mimeType = "audio/mpeg"
    )

    @Test
    fun `playlist songs are ordered by position not insertion`() = runTest {
        db.songDao().upsertAll(listOf(song(1L, "/a.mp3"), song(2L, "/b.mp3"), song(3L, "/c.mp3")))
        val playlistId = db.playlistDao().insert(PlaylistEntity(name = "列表"))

        // 故意按 C、A、B 的顺序加入——position 依次为 0、1、2
        db.playlistDao().addSongsAtEnd(playlistId, listOf(3L, 1L, 2L))

        val withSongs: PlaylistWithSongs? = db.playlistDao().observeWithSongs(playlistId).first()
        assertNotNull(withSongs)
        assertEquals(listOf("/c.mp3", "/a.mp3", "/b.mp3"), withSongs!!.songs.map { it.path })
    }

    @Test
    fun `observeAllWithSongs keeps empty playlists and orders by updatedAt`() = runTest {
        val older = db.playlistDao().insert(PlaylistEntity(name = "旧列表", createdAt = 1, updatedAt = 1))
        val newer = db.playlistDao().insert(PlaylistEntity(name = "新列表", createdAt = 2, updatedAt = 2))
        db.songDao().upsertAll(listOf(song(1L, "/a.mp3")))

        // 初始按 updatedAt 倒序：新列表在前
        assertEquals(
            listOf("新列表", "旧列表"),
            db.playlistDao().observeAllWithSongs().first().map { it.playlist.name }
        )

        // 给旧列表加歌 → touch 刷新 updatedAt → 反超到最前
        db.playlistDao().addSongsAtEnd(older, listOf(1L))
        val all = db.playlistDao().observeAllWithSongs().first()
        assertEquals(listOf("旧列表", "新列表"), all.map { it.playlist.name })
        // 空歌单保留（关联行为空，不能从结果里消失）
        assertEquals(0, all.first { it.playlist.id == newer }.songs.size)
        assertEquals(1, all.first { it.playlist.id == older }.songs.size)
    }

    @Test
    fun `deleting playlist cascades junction rows`() = runTest {
        db.songDao().upsertAll(listOf(song(1L, "/a.mp3")))
        val playlistId = db.playlistDao().insert(PlaylistEntity(name = "列表"))
        db.playlistDao().addSongsAtEnd(playlistId, listOf(1L))
        assertEquals(1, db.playlistDao().observeWithSongs(playlistId).first()!!.songs.size)

        db.playlistDao().delete(PlaylistEntity(id = playlistId, name = "列表", createdAt = 0, updatedAt = 0))

        assertNull(db.playlistDao().observeWithSongs(playlistId).first())
        // 关联行必须随歌单消失（旧版只删 playlists 行，junction 永久残留）
        assertEquals(0, db.playlistDao().observePlaylistSongRowsOf(playlistId).first().size)
    }

    @Test
    fun `deleting song cascades playlist and history rows`() = runTest {
        db.songDao().upsertAll(listOf(song(1L, "/a.mp3")))
        val playlistId = db.playlistDao().insert(PlaylistEntity(name = "列表"))
        db.playlistDao().addSongsAtEnd(playlistId, listOf(1L))
        db.playHistoryDao().insert(PlayHistoryEntity(songId = 1L))

        db.songDao().deleteById(1L)

        assertEquals(0, db.playlistDao().observePlaylistSongRowsOf(playlistId).first().size)
        assertEquals(0, db.playHistoryDao().observeRecent(50).first().size)
    }

    @Test
    fun `add remove touch updatedAt but duplicate add does not`() = runTest {
        db.songDao().upsertAll(listOf(song(1L, "/a.mp3"), song(2L, "/b.mp3")))
        val playlistId = db.playlistDao().insert(PlaylistEntity(name = "列表", createdAt = 1000, updatedAt = 1000))

        db.playlistDao().addSongsAtEnd(playlistId, listOf(1L))
        val afterAdd = db.playlistDao().getById(playlistId)!!.updatedAt
        assertTrue("加入歌曲应刷新 updatedAt", afterAdd > 1000)

        // 重复加入已存在的歌：IGNORE，不刷新
        db.playlistDao().addSongsAtEnd(playlistId, listOf(1L))
        assertEquals(afterAdd, db.playlistDao().getById(playlistId)!!.updatedAt)

        db.playlistDao().addSongsAtEnd(playlistId, listOf(2L))
        assertTrue(db.playlistDao().getById(playlistId)!!.updatedAt >= afterAdd)

        db.playlistDao().removeSongAndTouch(playlistId, 1L)
        assertTrue("移除歌曲应刷新 updatedAt", db.playlistDao().getById(playlistId)!!.updatedAt >= afterAdd)
        assertEquals(listOf("/b.mp3"), db.playlistDao().observeWithSongs(playlistId).first()!!.songs.map { it.path })
    }

    @Test
    fun `upsertAll keeps playlist membership when song row changes`() = runTest {
        // 回归：REPLACE upsert 会触发 CASCADE 清掉子表（"同步一次歌单全没"）
        db.songDao().upsertAll(listOf(song(1L, "/a.mp3")))
        val playlistId = db.playlistDao().insert(PlaylistEntity(name = "列表"))
        db.playlistDao().addSongsAtEnd(playlistId, listOf(1L))

        // 模拟同步对账：同一路径、文件被改写（size 变化），保留本地 id 重写
        val updated = song(1L, "/a.mp3").copy(size = 2_000L, title = "Song A (fixed)")
        db.songDao().upsertAll(listOf(updated))

        val withSongs = db.playlistDao().observeWithSongs(playlistId).first()!!
        assertEquals(1, withSongs.songs.size)
        assertEquals("Song A (fixed)", withSongs.songs.first().title)
    }
}

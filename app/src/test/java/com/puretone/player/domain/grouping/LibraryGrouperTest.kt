package com.puretone.player.domain.grouping

import com.puretone.player.data.local.entity.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 曲库分组纯函数单测（domain 层，无需数据库/上下文）。
 *
 * 分组逻辑从 [com.puretone.player.ui.LibraryViewModel] 抽到
 * [com.puretone.player.domain.grouping]，此处锁死其不可变口径：
 * - 专辑：(volumeName, albumId) 复合键，跨卷同名 album_id 不误并（P2-4）；
 * - albumId=0 回退到 album 名；
 * - 歌手：按 artist；
 * - 文件夹：按 path 父目录，name 取末段；
 * - 封面：分组内首个非空封面。
 */
class LibraryGrouperTest {

    private fun song(
        title: String,
        artist: String,
        album: String,
        albumId: Long,
        volumeName: String = "",
        path: String = "/music/$title.mp3",
        coverUri: String? = null,
        trackNumber: Int = 0
    ) = SongEntity(
        mediaStoreId = 0L,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        volumeName = volumeName,
        duration = 1000L,
        path = path,
        size = 100L,
        mimeType = "audio/mpeg",
        coverUri = coverUri,
        trackNumber = trackNumber
    )

    @Test
    fun `groupAlbums 按卷+albumId 复合键分组，跨卷不误并`() {
        val songs = listOf(
            song("A1", "X", "AlbumA", 1L, volumeName = "external_primary", path = "/a/A1.mp3"),
            song("A2", "X", "AlbumA", 1L, volumeName = "external_primary", path = "/a/A2.mp3"),
            song("A3", "X", "AlbumA", 1L, volumeName = "sd_card", path = "/sd/A3.mp3")
        )
        val albums = groupAlbums(songs)
        assertEquals(2, albums.size)
        val primary = albums.first { it.volumeName == "external_primary" }
        assertEquals(2, primary.songCount)
        assertTrue(albums.any { it.volumeName == "sd_card" && it.songCount == 1 })
    }

    @Test
    fun `groupAlbums albumId=0 时回退到 album 名分组`() {
        val songs = listOf(
            song("B1", "Y", "AlbumB", 0L, path = "/b/B1.mp3"),
            song("B2", "Y", "AlbumB", 0L, path = "/b/B2.mp3")
        )
        val albums = groupAlbums(songs)
        assertEquals(1, albums.size)
        assertEquals("AlbumB", albums.first().album)
    }

    @Test
    fun `groupArtists 按 artist 分组`() {
        val songs = listOf(
            song("C1", "Z", "Any", 9L, path = "/c/C1.mp3"),
            song("C2", "Z", "Any", 9L, path = "/c/C2.mp3"),
            song("C3", "W", "Any", 9L, path = "/c/C3.mp3")
        )
        val artists = groupArtists(songs)
        assertEquals(2, artists.size)
        val z = artists.first { it.artist == "Z" }
        assertEquals(2, z.songCount)
    }

    @Test
    fun `groupFolders 按 path 父目录分组，name 取末段`() {
        val songs = listOf(
            song("D1", "Q", "Any", 0L, path = "/folderOne/D1.mp3"),
            song("D2", "Q", "Any", 0L, path = "/folderOne/D2.mp3"),
            song("D3", "Q", "Any", 0L, path = "/folderTwo/D3.mp3")
        )
        val folders = groupFolders(songs)
        assertEquals(2, folders.size)
        assertEquals(2, folders.first { it.folder == "/folderOne" }.songCount)
        assertEquals("folderTwo", folders.first { it.folder == "/folderTwo" }.name)
    }

    @Test
    fun `groupAlbums 封面取分组内首个非空封面`() {
        val songs = listOf(
            song("E1", "R", "AlbumE", 5L, coverUri = null, path = "/e/E1.mp3"),
            song("E2", "R", "AlbumE", 5L, coverUri = "content://cover", path = "/e/E2.mp3")
        )
        val albums = groupAlbums(songs)
        assertEquals(1, albums.size)
        assertEquals("content://cover", albums.first().coverUri)
    }
}

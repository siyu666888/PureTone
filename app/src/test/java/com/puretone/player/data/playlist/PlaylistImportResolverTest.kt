package com.puretone.player.data.playlist

import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.lyrics.M3uEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistImportResolverTest {

    private fun song(id: Long, path: String) = SongEntity(
        id = id,
        mediaStoreId = id,
        title = path.substringAfterLast('/').substringBeforeLast('.'),
        artist = "A",
        album = "B",
        albumId = 1,
        duration = 1000,
        path = path,
        size = 1,
        coverUri = null,
        mimeType = "audio/mpeg"
    )

    @Test
    fun `matches by exact path`() {
        val library = listOf(song(1, "/storage/emulated/0/Music/a.mp3"))
        val entries = listOf(M3uEntry("/storage/emulated/0/Music/a.mp3"))
        val result = PlaylistImportResolver.resolve(entries, library)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `matches by path suffix when mount point differs`() {
        val library = listOf(song(1, "/storage/emulated/0/Music/a.mp3"))
        val entries = listOf(M3uEntry("/sdcard/Music/a.mp3"))
        val result = PlaylistImportResolver.resolve(entries, library)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `matches by file name when unique`() {
        val library = listOf(song(1, "/storage/emulated/0/Music/a.mp3"))
        val entries = listOf(M3uEntry("Music/a.mp3"))
        val result = PlaylistImportResolver.resolve(entries, library)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `suffix match respects path segment boundary`() {
        // P2-12：旧实现裸 endsWith 会让条目 "music/a.mp3" 命中 ".../mymusic/a.mp3"
        // （目录名恰好同后缀），且因唯一而静默导入——导入的是另一首歌。
        // 修复后：后缀层要求 "/" 边界 → 不命中；文件名层两首同名 → 歧义跳过。
        val library = listOf(
            song(1, "/storage/emulated/0/mymusic/a.mp3"),
            song(2, "/storage/emulated/0/other/a.mp3")
        )
        val entries = listOf(M3uEntry("music/a.mp3"))
        assertEquals(emptyList<Long>(), PlaylistImportResolver.resolve(entries, library).map { it.id })
    }

    @Test
    fun `skips ambiguous file name matches`() {
        val library = listOf(
            song(1, "/storage/emulated/0/Music/a.mp3"),
            song(2, "/storage/emulated/0/Download/a.mp3")
        )
        val entries = listOf(M3uEntry("a.mp3"))
        val result = PlaylistImportResolver.resolve(entries, library)
        assertEquals(emptyList<Long>(), result.map { it.id })
    }

    @Test
    fun `deduplicates repeated entries`() {
        val library = listOf(song(1, "/storage/emulated/0/Music/a.mp3"))
        val entries = listOf(
            M3uEntry("/storage/emulated/0/Music/a.mp3"),
            M3uEntry("/storage/emulated/0/Music/a.mp3")
        )
        val result = PlaylistImportResolver.resolve(entries, library)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `no match returns empty`() {
        val library = listOf(song(1, "/storage/emulated/0/Music/a.mp3"))
        val entries = listOf(M3uEntry("/nowhere/b.mp3"))
        assertEquals(emptyList<Long>(), PlaylistImportResolver.resolve(entries, library).map { it.id })
    }

    @Test
    fun `empty inputs`() {
        assertEquals(emptyList<Long>(), PlaylistImportResolver.resolve(emptyList(), emptyList()).map { it.id })
    }

    @Test
    fun `P2-8 剥离 file 前缀并 URL 解码后匹配`() {
        // 很多导出工具把条目写成 file:///... 且空格编码为 %20
        val library = listOf(song(1, "/storage/emulated/0/Music/my song.mp3"))
        val entries = listOf(M3uEntry("file:///storage/emulated/0/Music/my%20song.mp3"))
        val result = PlaylistImportResolver.resolve(entries, library)
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `P2-8 仅 URL 解码（无 file 前缀）也可匹配`() {
        val library = listOf(song(1, "/Music/a&b.mp3"))
        val entries = listOf(M3uEntry("/Music/a%26b.mp3"))
        val result = PlaylistImportResolver.resolve(entries, library)
        assertEquals(listOf(1L), result.map { it.id })
    }
}

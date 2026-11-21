package com.puretone.player.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2：MusicRepository 纯函数直接断言（历史 bug 集中区）。
 *
 * 这些是 companion object 下的静态纯函数，此前只靠 syncLibrary 集成路径间接覆盖，
 * P0-1（SQL 参数上限）/P1-7（SD 卡坏图）/P3-26（LIKE 通配符）三处历史 bug
 * 的防线没有直接断言。这里逐条锁定，改坏任一 replace 或切分都会变红。
 */
class MusicRepositoryPureTest {

    @Test
    fun `B2 escapeLike 转义反斜杠与两个通配符且空串原样`() {
        assertEquals("a\\%b\\_c\\\\d", MusicRepository.escapeLike("a%b_c\\d"))
        assertEquals("", MusicRepository.escapeLike(""))
        assertEquals("plain", MusicRepository.escapeLike("plain"))
        // 单独的三个字符不得漏转义
        assertEquals("\\%", MusicRepository.escapeLike("%"))
        assertEquals("\\_", MusicRepository.escapeLike("_"))
        assertEquals("\\\\", MusicRepository.escapeLike("\\"))
    }

    @Test
    fun `B2 isAlbumArtPlaceholder 认任意卷占位但拒绝普通 content URI`() {
        assertTrue(MusicRepository.isAlbumArtPlaceholder("content://media/external_primary/audio/albumart/123"))
        assertTrue(MusicRepository.isAlbumArtPlaceholder("content://media/SDCARD/audio/albumart/456"))
        assertFalse(MusicRepository.isAlbumArtPlaceholder("content://media/external/images/media/789"))
        assertFalse(MusicRepository.isAlbumArtPlaceholder(null))
    }

    @Test
    fun `B2 folderOf 按末级斜杠切父目录且无斜杠原样`() {
        assertEquals("/storage/emulated/0/Music", MusicRepository.folderOf("/storage/emulated/0/Music/a.mp3"))
        assertEquals("a.mp3", MusicRepository.folderOf("a.mp3"))
        // 根目录下文件（idx==0）保持原样，不返回空串
        assertEquals("/a.mp3", MusicRepository.folderOf("/a.mp3"))
    }

    @Test
    fun `B2 分片常量留足 SQLite 变量上限余量`() {
        // P0-1：>999 个 id 会触发 SQLite 参数上限；900 留 99 余量
        assertTrue(MusicRepository.ID_CHUNK > 0 && MusicRepository.ID_CHUNK < 999)
    }
}

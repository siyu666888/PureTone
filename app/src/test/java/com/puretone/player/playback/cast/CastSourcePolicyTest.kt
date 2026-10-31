package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CastSourcePolicy] 行为测试（M2 投屏源分流）。
 *
 * 这层是「投屏能不能出声」的第一道判定，判错的代价是二选一：
 * - 该转的没转（如把 APE 当直投）→ 渲染器拉流后解不了，电视端黑屏/报不支持，
 *   而手机这边显示「已投屏」，用户只会认为投屏坏了；
 * - 不该转的转了（如把 MP3 也转一遍）→ 首播白等几十秒，且白白吃 CPU。
 *
 * 因此这里把「扩展名解析」的边角情况单独钉死：大小写、目录名带点、无扩展名、
 * 隐藏文件、尾点、Windows 反斜杠路径。
 */
class CastSourcePolicyTest {

    // ------------------------------------------------------------------ 白名单

    @Test
    fun `白名单内的格式不需要转码`() {
        listOf("mp3", "flac", "wav", "m4a", "aac", "mp4").forEach { ext ->
            assertFalse(".$ext 应直投", CastSourcePolicy.requiresTranscode("/sdcard/Music/song.$ext"))
        }
    }

    @Test
    fun `扩展名大小写不影响判定`() {
        assertFalse(CastSourcePolicy.requiresTranscode("/sdcard/Music/SONG.MP3"))
        assertFalse(CastSourcePolicy.requiresTranscode("/sdcard/Music/song.FlAc"))
        assertTrue(CastSourcePolicy.requiresTranscode("/sdcard/Music/song.APE"))
    }

    // -------------------------------------------------------------- 需转码格式

    @Test
    fun `APE 必须转码`() {
        assertTrue(CastSourcePolicy.requiresTranscode("/sdcard/Music/album/track01.ape"))
        assertTrue(CastSourcePolicy.requiresTranscode("/sdcard/Music/album/track01.Ape"))
    }

    @Test
    fun `渲染器普遍不支持的容器也走转码`() {
        listOf("ogg", "opus", "aiff", "aif", "wma", "dsf", "dff", "wv").forEach { ext ->
            assertTrue(".$ext 应转码", CastSourcePolicy.requiresTranscode("/sdcard/Music/song.$ext"))
        }
    }

    // ------------------------------------------------------------ 扩展名解析边角

    @Test
    fun `无扩展名视为需转码`() {
        assertNull(CastSourcePolicy.extensionOf("/sdcard/Music/song"))
        assertTrue(CastSourcePolicy.requiresTranscode("/sdcard/Music/song"))
    }

    @Test
    fun `目录名里的点不能被当成扩展名`() {
        // 文件名本身没有扩展名 → 必须落到「需转码」；不能因目录名 Music.2024 判出 "2024"
        assertNull(CastSourcePolicy.extensionOf("/sdcard/Music.2024/song"))
        assertTrue(CastSourcePolicy.requiresTranscode("/sdcard/Music.2024/song"))
    }

    @Test
    fun `目录名带点时仍能正确取到文件扩展名`() {
        assertEquals("flac", CastSourcePolicy.extensionOf("/sdcard/Music.2024/song.flac"))
        assertFalse(CastSourcePolicy.requiresTranscode("/sdcard/Music.2024/song.flac"))
    }

    @Test
    fun `隐藏文件不算有扩展名`() {
        // ".ape" 是文件名整体，不是「扩展名为 ape」——判成 APE 会走转码，
        // 虽然结果一样但语义错；这里固定为「无法识别 → 需转码」
        assertNull(CastSourcePolicy.extensionOf("/sdcard/Music/.ape"))
        assertTrue(CastSourcePolicy.requiresTranscode("/sdcard/Music/.ape"))
    }

    @Test
    fun `尾部单独一个点不算扩展名`() {
        assertNull(CastSourcePolicy.extensionOf("/sdcard/Music/song."))
        assertTrue(CastSourcePolicy.requiresTranscode("/sdcard/Music/song."))
    }

    @Test
    fun `兼容 Windows 反斜杠路径`() {
        assertEquals("flac", CastSourcePolicy.extensionOf("C:\\Music\\song.flac"))
        assertFalse(CastSourcePolicy.requiresTranscode("C:\\Music\\song.flac"))
    }

    @Test
    fun `扩展名统一返回小写`() {
        assertEquals("ape", CastSourcePolicy.extensionOf("/sdcard/Music/TRACK.APE"))
        assertEquals("mp3", CastSourcePolicy.extensionOf("/sdcard/Music/Track.Mp3"))
    }

    @Test
    fun `文件名含多个点时取最后一段`() {
        assertEquals("flac", CastSourcePolicy.extensionOf("/sdcard/Music/01 - song (live).Take 2.flac"))
    }
}

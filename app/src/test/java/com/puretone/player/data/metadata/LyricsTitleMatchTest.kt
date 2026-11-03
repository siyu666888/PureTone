package com.puretone.player.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词候选曲名一致性校验的测试。
 *
 * 这条校验是 2026-11-03 真机实测踩坑后补的：给《晴天》下载到了同歌手的
 * 《告白气球》——歌手名兜底检索必然带回别的歌，不校验曲名就会给用户错歌词。
 */
class LyricsTitleMatchTest {

    @Test
    fun `完全相同的曲名强匹配`() {
        assertEquals(
            LyricsTitleMatch.SCORE_EXACT,
            LyricsTitleMatch.score("晴天", "晴天")
        )
    }

    @Test
    fun `大小写与空格差异视为相同`() {
        assertEquals(
            LyricsTitleMatch.SCORE_EXACT,
            LyricsTitleMatch.score("Qing Tian", "qingtian")
        )
        assertEquals(
            LyricsTitleMatch.SCORE_EXACT,
            LyricsTitleMatch.score("qing tian", "Qing Tian")
        )
    }

    @Test
    fun `标点差异视为相同`() {
        assertEquals(
            LyricsTitleMatch.SCORE_EXACT,
            LyricsTitleMatch.score("Qing-Tian", "Qing Tian")
        )
        assertEquals(
            LyricsTitleMatch.SCORE_EXACT,
            LyricsTitleMatch.score("晴天。", "晴天")
        )
    }

    @Test
    fun `同曲变体按包含判定为可接受`() {
        // Live 版 / 伴奏 /  remaster 这类同曲变体必须放行
        assertTrue(LyricsTitleMatch.isAcceptable("晴天 (Live)", "晴天"))
        assertTrue(LyricsTitleMatch.isAcceptable("晴天（Live版）", "晴天"))
        assertTrue(LyricsTitleMatch.isAcceptable("晴天 Remastered", "晴天"))
        assertEquals(
            LyricsTitleMatch.SCORE_CONTAINS,
            LyricsTitleMatch.score("晴天 (Live)", "晴天")
        )
    }

    @Test
    fun `同歌手的不同歌曲判为不匹配`() {
        // 这是踩坑的真实案例：给《晴天》捞到了《告白气球》
        assertFalse(LyricsTitleMatch.isAcceptable("告白气球", "晴天"))
        assertEquals(
            LyricsTitleMatch.SCORE_NONE,
            LyricsTitleMatch.score("告白气球", "晴天")
        )
        assertFalse(LyricsTitleMatch.isAcceptable("七里香", "晴天"))
    }

    @Test
    fun `英文歌名不会匹配到中文歌名`() {
        // 元数据写英文、词库只有中文名时，宁可返回 null 也不能给错歌
        assertFalse(LyricsTitleMatch.isAcceptable("告白气球", "Qing Tian"))
    }

    @Test
    fun `空曲名判为不匹配`() {
        assertFalse(LyricsTitleMatch.isAcceptable("", "晴天"))
        assertFalse(LyricsTitleMatch.isAcceptable("晴天", ""))
        assertEquals(LyricsTitleMatch.SCORE_NONE, LyricsTitleMatch.score("", ""))
    }

    @Test
    fun `归一化保留中日韩字符`() {
        assertEquals("晴天live", LyricsTitleMatch.normalize("晴天 (Live)"))
        assertEquals("告白气球", LyricsTitleMatch.normalize("告白气球"))
    }
}

package com.puretone.player.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** P2-9：`<unknown>` 等占位符归一 */
class UnknownTextTest {

    @Test
    fun `placeholders normalize to empty`() {
        assertEquals("", UnknownText.normalizeArtist("<unknown>"))
        assertEquals("", UnknownText.normalizeArtist("<Unknown>"))
        assertEquals("", UnknownText.normalizeArtist("unknown"))
        assertEquals("", UnknownText.normalizeArtist("  <unknown>  "))
        assertEquals("", UnknownText.normalizeArtist("未知"))
        assertEquals("", UnknownText.normalizeArtist(null))
        assertEquals("", UnknownText.normalizeArtist(""))
        assertEquals("", UnknownText.normalizeAlbum("<unknown>"))
    }

    @Test
    fun `real names pass through trimmed`() {
        assertEquals("周杰伦", UnknownText.normalizeArtist("周杰伦"))
        assertEquals("周杰伦", UnknownText.normalizeArtist("  周杰伦  "))
        // 名字里含 unknown 子串的不是占位符
        assertEquals("Unknown Artist", UnknownText.normalizeArtist("Unknown Artist"))
    }

    @Test
    fun `display helpers fall back for blanks`() {
        assertEquals("未知艺术家", UnknownText.displayArtist("<unknown>", "未知艺术家"))
        assertEquals("未知专辑", UnknownText.displayAlbum(null, "未知专辑"))
        assertEquals("周杰伦", UnknownText.displayArtist("周杰伦", "未知艺术家"))
    }
}

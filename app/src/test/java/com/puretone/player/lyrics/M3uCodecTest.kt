package com.puretone.player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uCodecTest {

    @Test
    fun `parses extended m3u`() {
        val content = """
            #EXTM3U
            #EXTINF:213,青花瓷 - 周杰伦
            /storage/emulated/0/Music/青花瓷.mp3
            #EXTINF:0,纯路径
            /storage/emulated/0/Music/纯路径.flac
        """.trimIndent()

        val entries = M3uCodec.parse(content)

        assertEquals(2, entries.size)
        assertEquals("/storage/emulated/0/Music/青花瓷.mp3", entries[0].path)
        assertEquals("青花瓷 - 周杰伦", entries[0].title)
        assertEquals(213.0, entries[0].durationSec, 0.0)
        assertEquals(0.0, entries[1].durationSec, 0.0)
        // P2-7：时长为 0 时标题也应保留（不再丢弃），保证往返自洽
        assertEquals("纯路径", entries[1].title)
    }

    @Test
    fun `P2-7 保留小数时长且标题不随时长丢弃`() {
        val content = """
            #EXTM3U
            #EXTINF:273.5,小数时长
            /music/a.mp3
            #EXTINF:-5,负数时长
            /music/b.mp3
        """.trimIndent()

        val entries = M3uCodec.parse(content)

        assertEquals(2, entries.size)
        assertEquals(273.5, entries[0].durationSec, 0.0)
        assertEquals("小数时长", entries[0].title)
        // 负数时长 coerce 为 0，但标题仍保留
        assertEquals(0.0, entries[1].durationSec, 0.0)
        assertEquals("负数时长", entries[1].title)
    }

    @Test
    fun `P2-7 小数时长往返自洽`() {
        val entries = listOf(M3uEntry("/m/c.mp3", "C", 273.5))
        val reparsed = M3uCodec.parse(M3uCodec.serialize(entries))
        assertEquals(entries, reparsed)
    }

    @Test
    fun `ignores comments and blank lines`() {
        val content = """
            #EXTM3U
            #EXTGRP:华语

            # 普通注释
            /music/a.mp3
        """.trimIndent()

        val entries = M3uCodec.parse(content)

        assertEquals(1, entries.size)
        assertEquals("/music/a.mp3", entries[0].path)
    }

    @Test
    fun `roundtrip parse serialize`() {
        val entries = listOf(
            M3uEntry("/music/a.mp3", "歌曲A", 200.0),
            M3uEntry("/music/b.flac", null, 0.0),
            M3uEntry("/music/c.ape", "歌曲C", 305.0)
        )
        val serialized = M3uCodec.serialize(entries)
        val reparsed = M3uCodec.parse(serialized)
        assertEquals(entries, reparsed)
    }

    @Test
    fun `serialize has header and extinf lines`() {
        val text = M3uCodec.serialize(listOf(M3uEntry("/m/x.mp3", "X", 60.0)))
        assertEquals(
            "#EXTM3U\n#EXTINF:60,X\n/m/x.mp3\n",
            text
        )
    }
}

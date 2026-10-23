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
        assertEquals(213, entries[0].durationSec)
        assertEquals(0, entries[1].durationSec)
        assertNull(entries[1].title)
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
            M3uEntry("/music/a.mp3", "歌曲A", 200),
            M3uEntry("/music/b.flac", null, 0),
            M3uEntry("/music/c.ape", "歌曲C", 305)
        )
        val serialized = M3uCodec.serialize(entries)
        val reparsed = M3uCodec.parse(serialized)
        assertEquals(entries, reparsed)
    }

    @Test
    fun `serialize has header and extinf lines`() {
        val text = M3uCodec.serialize(listOf(M3uEntry("/m/x.mp3", "X", 60)))
        assertEquals(
            "#EXTM3U\n#EXTINF:60,X\n/m/x.mp3\n",
            text
        )
    }
}

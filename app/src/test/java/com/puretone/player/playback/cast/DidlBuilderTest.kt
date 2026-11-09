package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DidlBuilder] 单测。
 *
 * 锁死两件事：标题/URL 的 XML 转义（`&` 不转义会让整条 SOAP 变成非法 XML，
 * 渲染器直接 500），以及 protocolInfo 候选顺序（最小形态在前，DLNA 参数在后）。
 */
class DidlBuilderTest {

    @Test
    fun `首档 protocolInfo 是最小形态`() {
        val variants = DidlBuilder.protocolInfoVariants("audio/flac")
        assertEquals("http-get:*:audio/flac:*", variants.first())
        assertEquals(2, variants.size)
    }

    @Test
    fun `第二档带 DLNA 组织参数`() {
        val second = DidlBuilder.protocolInfoVariants("audio/x-wav")[1]
        assertEquals(true, second.startsWith("http-get:*:audio/x-wav:DLNA.ORG_OP="))
        assertEquals(true, second.contains("DLNA.ORG_FLAGS="))
    }

    @Test
    fun `DIDL 包含必要命名空间与类目`() {
        val didl = DidlBuilder.build(
            title = "Call Me Maybe",
            mediaUrl = "http://192.168.101.16:8081/file/abc",
            protocolInfo = "http-get:*:audio/flac:*",
            upnpClass = "object.item.audioItem.musicTrack"
        )
        assertEquals(true, didl.contains("urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"))
        assertEquals(true, didl.contains("<dc:title>Call Me Maybe</dc:title>"))
        assertEquals(true, didl.contains("object.item.audioItem.musicTrack"))
        assertEquals(true, didl.contains("http-get:*:audio/flac:*"))
    }

    @Test
    fun `标题与 URL 中的和号会被转义`() {
        val didl = DidlBuilder.build(
            title = "Rock & Roll",
            mediaUrl = "http://h/f?a=1&b=2",
            protocolInfo = "http-get:*:audio/mpeg:*",
            upnpClass = "object.item.audioItem.musicTrack"
        )
        assertEquals(true, didl.contains("Rock &amp; Roll"))
        assertEquals(true, didl.contains("a=1&amp;b=2"))
        assertEquals(false, didl.contains("Rock & Roll"))
    }

    @Test
    fun `空标题有兜底名`() {
        val didl = DidlBuilder.build("", "http://h/f", "http-get:*:audio/mpeg:*", "object.item.audioItem.musicTrack")
        assertEquals(true, didl.contains("<dc:title>PureTone</dc:title>"))
    }

    @Test
    fun `P3-2 合法 MIME 原样保留且首尾空白被裁剪`() {
        listOf("audio/flac", "audio/x-wav", "audio/mp4", "video/mp4", "application/ogg")
            .forEach { assertEquals(it, DidlBuilder.safeMimeType(it)) }
        assertEquals("audio/flac", DidlBuilder.safeMimeType("  audio/flac  "))
        assertEquals("audio/flac", DidlBuilder.safeMimeType("audio/flac\n"))
    }

    @Test
    fun `P3-2 含引号分号空格或注入字符的 MIME 回退到安全值`() {
        listOf(
            "audio/flac\" onload=\"x",
            "audio/flac;DLNA.ORG_OP=01",
            "audio /flac",
            "audio/flac\r\nX-Injected: 1",
            "",
            "../etc/passwd",
            "audio/flac:x",
        ).forEach {
            assertEquals("脏 MIME「$it」应回退", DidlBuilder.FALLBACK_MIME, DidlBuilder.safeMimeType(it))
        }
    }

    @Test
    fun `P3-2 脏 MIME 不会破坏 protocolInfo 属性结构`() {
        val variants = DidlBuilder.protocolInfoVariants("audio/flac\" onload=\"x")
        assertEquals(2, variants.size)
        variants.forEach {
            assertEquals(true, it.contains(DidlBuilder.FALLBACK_MIME))
            assertEquals(false, it.contains("\""))
            assertEquals(false, it.contains("onload"))
        }
    }

    @Test
    fun `P3-2 build 对直接传入的脏 protocolInfo 做属性转义`() {
        val didl = DidlBuilder.build(
            title = "T",
            mediaUrl = "http://h/f",
            protocolInfo = "http-get:*:audio/mpeg:*\" onload=\"x",
            upnpClass = "object.item.audioItem.musicTrack"
        )
        assertEquals(true, didl.contains("&quot; onload=&quot;x"))
        assertEquals(false, didl.contains("\" onload=\"x"))
    }
}

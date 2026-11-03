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
}

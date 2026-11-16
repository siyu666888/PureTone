package com.puretone.player.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 封面 URL 准入策略（纯 JVM）。
 *
 * 两条底线：① 外部 API 只能给出 `https` + 白名单主机的网络 URL，其它网络来源
 * 一律不落库；② MediaStore albumart 占位 URI 与用户本地封面必须原样保留，
 * 不能因为加了一道网络校验而误清空（撤销自动填充也依赖这条通道）。
 */
class CoverUrlPolicyTest {

    private val legal = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/abc/100x100bb.jpg"

    @Test
    fun `空值返回null`() {
        assertNull(CoverUrlPolicy.sanitize(null))
        assertNull(CoverUrlPolicy.sanitize(""))
        assertNull(CoverUrlPolicy.sanitize("   "))
    }

    @Test
    fun `合法iTunes封面原样放行`() {
        assertEquals(legal, CoverUrlPolicy.sanitize(legal))
        assertTrue(CoverUrlPolicy.isAllowed(legal))
    }

    @Test
    fun `首尾空白被去除`() {
        assertEquals(legal, CoverUrlPolicy.sanitize("  $legal\n"))
    }

    @Test
    fun `白名单根域与子域都放行`() {
        listOf(
            "https://mzstatic.com/a.jpg",
            "https://is1-ssl.mzstatic.com/a.jpg",
            "https://a.b.mzstatic.com/a.jpg",
            "https://itunes.apple.com/a.jpg",
            "https://apple.com/a.jpg"
        ).forEach { assertTrue(it, CoverUrlPolicy.isAllowed(it)) }
    }

    @Test
    fun `协议与主机大小写不敏感`() {
        assertTrue(CoverUrlPolicy.isAllowed("HTTPS://IS1-SSL.MZSTATIC.COM/a.jpg"))
    }

    @Test
    fun `http明文一律拒绝`() {
        assertNull(CoverUrlPolicy.sanitize("http://is1-ssl.mzstatic.com/a.jpg"))
        assertFalse(CoverUrlPolicy.isAllowed("HTTP://evil.com/a.jpg"))
    }

    @Test
    fun `任意主机拒绝`() {
        assertNull(CoverUrlPolicy.sanitize("https://evil.example.com/a.jpg"))
        assertNull(CoverUrlPolicy.sanitize("https://192.168.1.1/a.jpg"))
    }

    @Test
    fun `后缀伪装与同前缀主机拒绝`() {
        listOf(
            "https://mzstatic.com.evil.com/a.jpg",
            "https://evilmzstatic.com/a.jpg",
            "https://apple.com.evil.com/a.jpg",
            "https://notapple.com/a.jpg"
        ).forEach { assertNull(it, CoverUrlPolicy.sanitize(it)) }
    }

    @Test
    fun `userinfo与非443端口拒绝`() {
        assertNull(CoverUrlPolicy.sanitize("https://evil.com@is1-ssl.mzstatic.com/a.jpg"))
        assertNull(CoverUrlPolicy.sanitize("https://is1-ssl.mzstatic.com:8443/a.jpg"))
        assertTrue(CoverUrlPolicy.isAllowed("https://is1-ssl.mzstatic.com:443/a.jpg"))
    }

    @Test
    fun `无host与非法URI拒绝`() {
        assertNull(CoverUrlPolicy.sanitize("https://"))
        assertNull(CoverUrlPolicy.sanitize("https:///a.jpg"))
        assertNull(CoverUrlPolicy.sanitize("https://is1-ssl.mzstatic.com/a b.jpg"))
    }

    @Test
    fun `超长URL拒绝`() {
        val long = legal + "x".repeat(CoverUrlPolicy.MAX_URL_LENGTH)
        assertNull(CoverUrlPolicy.sanitize(long))
    }

    @Test
    fun `MediaStore占位URI与本地封面原样放行`() {
        val placeholder = "content://media/external_primary/audio/albumart/42"
        assertEquals(placeholder, CoverUrlPolicy.sanitize(placeholder))
        // 含中文的 file 路径会让 java.net.URI 解析失败：本地 scheme 必须在解析之前放行
        val local = "file:///sdcard/封面.jpg"
        assertEquals(local, CoverUrlPolicy.sanitize(local))
        assertEquals("/sdcard/a.jpg", CoverUrlPolicy.sanitize("/sdcard/a.jpg"))
        assertTrue(CoverUrlPolicy.isAllowed("android.resource://com.puretone.player/1"))
    }

    @Test
    fun `未知网络协议拒绝`() {
        assertNull(CoverUrlPolicy.sanitize("ftp://evil.com/a.jpg"))
        assertNull(CoverUrlPolicy.sanitize("javascript:alert(1)"))
    }
}

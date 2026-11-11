package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CastTargetDiagnostics] 纯 JVM 单测。
 *
 * 重点是「1Hz 轮询不会刷爆诊断文件」与「未注入真实 sink 时零 Android 依赖」两条：
 * 前者靠整行去重，后者靠默认 noop——生产侧在 Application.onCreate 才注入
 * [CastNetworkLog.log]，而 [SoapClient] / [CastProbe.resolveControlUrl] 会被单测直接调用。
 */
class CastTargetDiagnosticsTest {

    private val collected = mutableListOf<String>()

    @Before
    fun setUp() {
        collected.clear()
        CastTargetDiagnostics.resetForTest { collected += it }
    }

    @Test
    fun `同一行全文只落一次`() {
        val line = "soap-target reject url=http://127.0.0.1/x host=127.0.0.1 reason=loopback"
        CastTargetDiagnostics.emit(line)
        CastTargetDiagnostics.emit(line)
        assertEquals(listOf(line), collected)
        assertEquals(1, CastTargetDiagnostics.seenCountForTest())
    }

    @Test
    fun `判定要素变化会重新落一行`() {
        CastTargetDiagnostics.emit("soap-target reject host=127.0.0.1 reason=loopback")
        CastTargetDiagnostics.emit("soap-target reject host=127.0.0.1 reason=user-info")
        CastTargetDiagnostics.emit("soap-target unverified host=example.tv reason=domain")
        assertEquals(3, collected.size)
        assertEquals(3, collected.toSet().size)
    }

    @Test
    fun `空行不落且不计入判重`() {
        CastTargetDiagnostics.emit("")
        assertTrue(collected.isEmpty())
        assertEquals(0, CastTargetDiagnostics.seenCountForTest())
    }

    @Test
    fun `去重集合有硬上限且旧行仍可判重`() {
        repeat(70) { i -> CastTargetDiagnostics.emit("soap-target reject host=10.0.0.$i reason=x") }
        assertEquals(64, collected.size)
        assertEquals(64, CastTargetDiagnostics.seenCountForTest())

        // 已满：已在集合里的旧行不重复落，新行静默丢弃，两者都不增加条目
        CastTargetDiagnostics.emit("soap-target reject host=10.0.0.0 reason=x")
        CastTargetDiagnostics.emit("soap-target reject host=10.0.0.69 reason=fresh")
        assertEquals(64, collected.size)
        assertEquals(64, CastTargetDiagnostics.seenCountForTest())
    }

    @Test
    fun `install只替换sink不清空判重`() {
        CastTargetDiagnostics.emit("soap-target unverified host=example.tv reason=domain")
        assertEquals(1, collected.size)

        val later = mutableListOf<String>()
        CastTargetDiagnostics.install { later += it }
        // 已见过的行不会因换 sink 重放
        CastTargetDiagnostics.emit("soap-target unverified host=example.tv reason=domain")
        assertTrue(later.isEmpty())
        // 新行交给新 sink
        CastTargetDiagnostics.emit("soap-target reject host=127.0.0.1 reason=loopback")
        assertEquals(listOf("soap-target reject host=127.0.0.1 reason=loopback"), later)
        assertEquals(2, CastTargetDiagnostics.seenCountForTest())
    }

    @Test
    fun `默认noop在纯JVM里安全`() {
        CastTargetDiagnostics.resetForTest()
        CastTargetDiagnostics.emit("soap-target reject host=127.0.0.1 reason=loopback")
        assertEquals(1, CastTargetDiagnostics.seenCountForTest())
    }

    @Test
    fun `SoapClient的GET拒绝目标经emit落一行且零网络访问`() {
        val result = SoapClient.get(
            "ftp://192.168.1.9/desc.xml",
            diagnostic = CastTargetDiagnostics::emit
        )
        assertEquals(-1, result.status)
        assertTrue(result.body.startsWith("blocked-target"))
        assertEquals(1, collected.size)
        assertTrue(collected[0].startsWith("soap-target reject "))
        assertTrue(collected[0].contains("bad-scheme"))
    }

    @Test
    fun `SoapClient的POST同样经emit落一行`() {
        val result = SoapClient.post(
            "http://user:pass@192.168.1.9/AVTransport/ctrl",
            "urn:schemas-upnp-org:service:AVTransport:1#Play",
            "<u:Play/>",
            diagnostic = CastTargetDiagnostics::emit
        )
        assertEquals(-1, result.status)
        assertEquals(1, collected.size)
        assertTrue(collected[0].contains("user-info"))
    }
}

package com.puretone.player.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 构建身份行的导出兜底（永久消除「两版诊断同形」）。
 *
 * 三条设计判据：① 空日志也带身份；② 正文已有身份时不重复注入、一字不改；
 * ③ 启动行被 `MAX_LINES` 环形上限裁掉后仍补回，且原有投屏日志全部保留。
 *
 * 第五轮复审 A1/B8 追加：外部输入的净化/截断，以及落盘与环形裁剪的原子性。
 * 这些用例走 [CastNetworkLog.appendLine] / [CastNetworkLog.trim] 的显式文件参数，
 * 不触碰 `init()` 的静态状态，纯 JVM 即可运行。
 */
class CastNetworkLogTest {

    @Test
    fun `空日志也带构建身份`() {
        val out = CastNetworkLog.withIdentity("")
        assertTrue(out.contains("cast-build variant="))
        assertTrue(out.contains(CastNetworkLog.IDENTITY_LINE))
        assertEquals(1, out.lines().count { it.contains("cast-build variant=") })
    }

    @Test
    fun `已有身份行时原样返回不重复`() {
        val body = "[2026-11-11 22:00:00] ${CastNetworkLog.IDENTITY_LINE}\n" +
            "[2026-11-11 22:00:01] http-fetch from=192.168.1.2\n"
        assertEquals(body, CastNetworkLog.withIdentity(body))
    }

    @Test
    fun `启动行被裁剪后补回且原日志不丢`() {
        val body = (1..200).joinToString("\n", postfix = "\n") { "[t] http-fetch n=$it" }
        val out = CastNetworkLog.withIdentity(body)
        assertEquals(1, out.lines().count { it.contains("cast-build variant=") })
        assertTrue(out.endsWith(body))
        assertTrue(out.indexOf("cast-build variant=") < out.indexOf("http-fetch n=1"))
    }

    @Test
    fun `身份行含版本与构建类型`() {
        assertTrue(CastNetworkLog.IDENTITY_LINE.startsWith("cast-build variant="))
        assertTrue(CastNetworkLog.IDENTITY_LINE.contains("version="))
        assertTrue(CastNetworkLog.IDENTITY_LINE.contains("code="))
        assertTrue(CastNetworkLog.IDENTITY_LINE.contains("type="))
    }

    @Test
    fun `超长单行被截断到 512 字符`() {
        val sanitized = CastNetworkLog.sanitizeMessage("A".repeat(10_000))
        assertEquals(CastNetworkLog.MAX_MESSAGE_CHARS, sanitized.length)
        assertTrue(sanitized.all { it == 'A' })
    }

    @Test
    fun `换行与回车被净化为空格不能伪造日志行`() {
        val raw = "http-fetch from=1.2.3.4\r\n[stamp] forged-cast-ok\rstatus=500"
        val sanitized = CastNetworkLog.sanitizeMessage(raw)
        assertFalse(sanitized.contains('\n'))
        assertFalse(sanitized.contains('\r'))
        assertFalse(sanitized.contains("forged-cast-ok\r"))
        assertEquals(1, sanitized.lines().size)
    }

    @Test
    fun `appendLine 含换行输入只落一行不伪造日志`() {
        val raw = "range=bytes=0-99\n[stamp] forged-line"
        CastNetworkLog.appendLine(logFile, raw)

        val lines = logFile.readLines()
        assertEquals("含换行的输入不得产生第二行日志", 1, lines.size)
        assertTrue(lines[0].startsWith("["))
        assertTrue("换行应被净化成空格、内容仍可读", lines[0].contains("range=bytes=0-99 [stamp] forged-line"))
        assertFalse(lines[0].contains('\n'))
    }

    @Test
    fun `appendLine 超长 range 被截断到 512 字符`() {
        CastNetworkLog.appendLine(logFile, "range=${"9".repeat(20_000)}")

        val lines = logFile.readLines()
        assertEquals(1, lines.size)
        // 时间戳 "[yyyy-MM-dd HH:mm:ss] " 固定 22 字符 + 512 字符正文
        assertEquals(22 + CastNetworkLog.MAX_MESSAGE_CHARS, lines[0].length)
    }

    @Test
    fun `超过 48KB 时环形裁剪保留最后 120 行且新内容不丢`() {
        // 200 行 × 300 字符 ≈ 60KB，同时越过字节阈值与 MAX_LINES
        logFile.writeText((1..200).joinToString("\n", postfix = "\n") { "[t] pad n=$it ${"x".repeat(280)}" })
        assertTrue("前置条件：文件应超过 48KB", logFile.length() > 48 * 1024)

        CastNetworkLog.appendLine(logFile, "http-fetch status=200")

        val lines = logFile.readLines()
        // trim 保留最后 120 行，再追加 1 行
        assertEquals(121, lines.size)
        assertTrue("新追加的行必须留在文件末尾", lines.last().endsWith("http-fetch status=200"))
        assertTrue("裁剪不得丢掉被保留的旧行", lines[0].contains("pad n=81"))
        assertFalse("临时文件必须已清理", File("${logFile.path}.tmp").exists())
    }

    @Test
    fun `trim 原子替换后内容与临时文件状态正确`() {
        logFile.writeText((1..200).joinToString("\n", postfix = "\n") { "[t] line=$it" })

        CastNetworkLog.trim(logFile)

        val lines = logFile.readLines()
        assertEquals(120, lines.size)
        assertEquals("[t] line=81", lines.first())
        assertEquals("[t] line=200", lines.last())
        assertFalse("临时文件必须已清理", File("${logFile.path}.tmp").exists())
    }

    private lateinit var logFile: File

    @Before
    fun createTempLog() {
        logFile = File.createTempFile("cast-network-log-test", ".txt")
    }

    @Test
    fun `B1 wifi_net_bound 三态映射锁定真值不造假的诊断口径`() {
        // 这三态是「双网卡 SSDP 收不到包」诊断的核心证据，映射写反会把用户引向错误方向
        assertEquals("yes", CastNetworkLog.wifiBoundText(true))
        assertEquals("failed", CastNetworkLog.wifiBoundText(false))
        assertEquals("n/a", CastNetworkLog.wifiBoundText(null))
    }

    @After
    fun deleteTempLog() {
        logFile.delete()
        File("${logFile.path}.tmp").delete()
    }
}

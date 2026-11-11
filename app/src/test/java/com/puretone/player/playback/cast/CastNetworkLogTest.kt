package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 构建身份行的导出兜底（永久消除「两版诊断同形」）。
 *
 * 三条设计判据：① 空日志也带身份；② 正文已有身份时不重复注入、一字不改；
 * ③ 启动行被 `MAX_LINES` 环形上限裁掉后仍补回，且原有投屏日志全部保留。
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
}

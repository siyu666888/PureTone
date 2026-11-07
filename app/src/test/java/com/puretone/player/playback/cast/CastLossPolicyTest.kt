package com.puretone.player.playback.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换曲窗口失联抑制判据（2026-11-06 19:56 真机「切歌时投屏断开、下一首又连上」回归）。
 *
 * 诊断里曲末 `STOPPED → cast-track-ended → cast-attempt → cast-ok` 全在同一秒完成，
 * 链路本身没有断；但换 URI 期间旧轮询仍在跑，库通道的一帧 ERROR / 连续异常、
 * 以及自有通道的「最终复探」都能直达 `markConnectionLost()`，一帧把 `isCasting`
 * 写成 false 让遥控页整页跳回普通播放页，随后在途投送成功又写回 true。这里逐条锁死
 * 宽限边界，保证只有真正离线才会被判失联。
 */
class CastLossPolicyTest {

    private val start = 1_000_000L

    @Test
    fun `刚发起换曲重投时抑制失联判定`() {
        assertTrue(
            CastLossPolicy.isWithinSwitchGrace(start, start)
        )
    }

    @Test
    fun `十二秒宽限内的一帧 ERROR 不算设备离线`() {
        assertTrue(
            CastLossPolicy.isWithinSwitchGrace(
                start, start + CastLossPolicy.SWITCH_GRACE_MS - 1
            )
        )
    }

    @Test
    fun `宽限刚好到期即可判失联`() {
        assertFalse(
            CastLossPolicy.isWithinSwitchGrace(
                start, start + CastLossPolicy.SWITCH_GRACE_MS
            )
        )
    }

    @Test
    fun `超过宽限仍收不到回包按真失联处理`() {
        assertFalse(
            CastLossPolicy.isWithinSwitchGrace(start, start + 126_252L)
        )
    }

    @Test
    fun `本次会话尚未投送过时不抑制`() {
        assertFalse(CastLossPolicy.isWithinSwitchGrace(0L, start))
    }

    @Test
    fun `时钟回拨不抑制避免吞掉真实失联`() {
        assertFalse(CastLossPolicy.isWithinSwitchGrace(start, start - 1L))
    }

    @Test
    fun `宽限时长可注入边界值`() {
        assertTrue(CastLossPolicy.isWithinSwitchGrace(start, start + 499L, graceMs = 500L))
        assertFalse(CastLossPolicy.isWithinSwitchGrace(start, start + 500L, graceMs = 500L))
    }
}

package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 投屏「真建立」确认状态机（B3）的纯 JVM 锁死。
 *
 * 核心是「SOAP 200 ≠ 电视在播」：只有本轮投送之后当前 URL 被真正取流才算建立；
 * 超时后先重发一次，仍无取流只提示一次，不无限骚扰渲染器。
 */
class CastEstablishPolicyTest {

    private fun resolve(
        isCasting: Boolean = true,
        isEstablished: Boolean = false,
        castStartMs: Long = 10_000L,
        lastFetchMs: Long = 0L,
        nowMs: Long = 11_000L,
        retries: Int = 0,
        warned: Boolean = false,
        retrySinceMs: Long = 0L,
        autoAdvance: Boolean = false,
        rendererPlaying: Boolean = false
    ) = CastEstablishPolicy.resolve(
        isCasting = isCasting,
        isEstablished = isEstablished,
        castStartMs = castStartMs,
        lastFetchMs = lastFetchMs,
        nowMs = nowMs,
        retries = retries,
        warned = warned,
        retrySinceMs = retrySinceMs,
        autoAdvance = autoAdvance,
        rendererPlaying = rendererPlaying,
        maxRetries = 1
    )

    @Test
    fun `库通道直通已建立时无需动作`() {
        assertEquals(CastEstablishPolicy.Action.NONE, resolve(isEstablished = true))
    }

    @Test
    fun `未在投屏或投送时刻未知时无需动作`() {
        assertEquals(CastEstablishPolicy.Action.NONE, resolve(isCasting = false))
        assertEquals(CastEstablishPolicy.Action.NONE, resolve(castStartMs = 0L))
    }

    @Test
    fun `本轮投送后电视来取流即确认建立`() {
        assertEquals(
            CastEstablishPolicy.Action.MARK_ESTABLISHED,
            resolve(castStartMs = 10_000L, lastFetchMs = 10_300L, nowMs = 11_000L)
        )
    }

    @Test
    fun `上一首的取流不算本轮建立`() {
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(castStartMs = 10_000L, lastFetchMs = 9_000L, nowMs = 11_000L)
        )
    }

    @Test
    fun `宽限内继续等不重发`() {
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(castStartMs = 10_000L, nowMs = 15_999L)
        )
    }

    @Test
    fun `超时未取流先用同一URL重发一次`() {
        assertEquals(
            CastEstablishPolicy.Action.RETRY,
            resolve(castStartMs = 10_000L, nowMs = 16_000L, retries = 0)
        )
    }

    @Test
    fun `重发后仍无取流只提示一次`() {
        assertEquals(
            CastEstablishPolicy.Action.WARN,
            resolve(castStartMs = 10_000L, nowMs = 28_000L, retries = 1, warned = false)
        )
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(castStartMs = 10_000L, nowMs = 29_000L, retries = 1, warned = true)
        )
    }

    @Test
    fun `时钟回拨不误判为超时`() {
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(castStartMs = 50_000L, nowMs = 11_000L, retries = 0)
        )
    }

    // ---------- D1：宽限 6 秒，区分「慢换曲」与「真卡死」（真机 20261107_203641） ----------

    @Test
    fun `正常换曲取流延迟3到6秒内不重发_203641回归`() {
        assertEquals(6_000L, CastEstablishPolicy.GRACE_MS)
        // 电视曲末换曲后正常取流延迟实测 3.2~4.6s：6s 宽限内不得重发，
        // 否则重发 SetAVTransportURI+Play 会让电视从头重播（用户听到前 3 秒重复）。
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            CastEstablishPolicy.resolve(
                isCasting = true,
                isEstablished = false,
                castStartMs = 10_000L,
                lastFetchMs = 0L,
                nowMs = 14_600L, // 换曲后 4.6s，正常上限
                retries = 0,
                warned = false
            )
        )
        // 真卡死（超过 6s 仍无取流）才重发救回。
        assertEquals(
            CastEstablishPolicy.Action.RETRY,
            CastEstablishPolicy.resolve(
                isCasting = true,
                isEstablished = false,
                castStartMs = 10_000L,
                lastFetchMs = 0L,
                nowMs = 16_000L,
                retries = 0,
                warned = false
            )
        )
    }

    @Test
    fun `重发之后宽限内不急着提示未响应`() {
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(
                castStartMs = 10_000L,
                nowMs = 20_000L,
                retries = 1,
                retrySinceMs = 18_000L
            )
        )
    }

    @Test
    fun `重发宽限到期仍无取流才提示一次`() {
        assertEquals(
            CastEstablishPolicy.Action.WARN,
            resolve(
                castStartMs = 10_000L,
                nowMs = 26_000L,
                retries = 1,
                retrySinceMs = 18_000L,
                warned = false
            )
        )
    }

    @Test
    fun `重发之后电视来取流立即确认建立_优先于重发宽限`() {
        assertEquals(
            CastEstablishPolicy.Action.MARK_ESTABLISHED,
            resolve(
                castStartMs = 10_000L,
                lastFetchMs = 19_000L,
                nowMs = 20_000L,
                retries = 1,
                retrySinceMs = 18_000L
            )
        )
    }

    // ---------- F1：曲末自动推进不重发（真机 20261107_224000） ----------

    @Test
    fun `曲末推进取流延迟7到8秒内不重发_224000回归`() {
        assertEquals(15_000L, CastEstablishPolicy.AUTO_ADVANCE_GRACE_MS)
        assertEquals(1, CastEstablishPolicy.AUTO_ADVANCE_KICK_COUNT)
        // 224000 实测曲末推进后电视取流 7~8s：6s 宽限会误重发，重发 SetAVTransportURI+Play
        // 把电视取流打断（用户听到开头几秒重复、重发前静默=没声音）。曲末推进用 15s 宽限，
        // 8s 时仍在宽限内，不得重发。
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            CastEstablishPolicy.resolve(
                isCasting = true,
                isEstablished = false,
                castStartMs = 10_000L,
                lastFetchMs = 0L,
                nowMs = 18_000L, // 曲末推进后 8s，慢换曲上限
                retries = 0,
                warned = false,
                autoAdvance = true
            )
        )
        // 对比：同样 8s，主动投送（非曲末推进）早已超 6s 宽限，需要重发。
        assertEquals(
            CastEstablishPolicy.Action.RETRY,
            CastEstablishPolicy.resolve(
                isCasting = true,
                isEstablished = false,
                castStartMs = 10_000L,
                lastFetchMs = 0L,
                nowMs = 18_000L,
                retries = 0,
                warned = false,
                autoAdvance = false
            )
        )
    }

    @Test
    fun `曲末推进超过宽限先踢脚不直接提示`() {
        // 064254：电视曲末推进后取流可延迟 18~39s，15s 一到期直接报「未响应」是误报。
        // 正确动作是发 Seek 踢一脚触发电视取流，而不是 WARN。
        assertEquals(
            CastEstablishPolicy.Action.KICK,
            CastEstablishPolicy.resolve(
                isCasting = true,
                isEstablished = false,
                castStartMs = 10_000L,
                lastFetchMs = 0L,
                nowMs = 25_000L, // 曲末推进后 15s，宽限刚到期
                retries = 0,
                warned = false,
                autoAdvance = true
            )
        )
    }

    @Test
    fun `曲末推进踢脚后宽限内不急着提示`() {
        // 踢脚后电视要重启控制服务再重新拉流，下一秒就弹「未响应」是误报。
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(
                castStartMs = 10_000L,
                nowMs = 30_000L,
                retries = 1,
                retrySinceMs = 25_000L,
                autoAdvance = true
            )
        )
    }

    @Test
    fun `曲末推进踢脚耗尽后电视已PLAYING不提示_064254回归`() {
        // 064254 现场：踢脚后电视一直报告 PLAYING（在线且在响应，只是取流慢），
        // 此时报「未响应」是误报，应继续等取流。
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(
                castStartMs = 10_000L,
                nowMs = 42_000L,
                retries = 1,
                retrySinceMs = 25_000L,
                warned = false,
                autoAdvance = true,
                rendererPlaying = true
            )
        )
    }

    @Test
    fun `曲末推进踢脚耗尽且电视不PLAYING才提示`() {
        // 电视既不取流也不报告 PLAYING = 真卡死（设备坏了），才提示用户。
        assertEquals(
            CastEstablishPolicy.Action.WARN,
            resolve(
                castStartMs = 10_000L,
                nowMs = 42_000L,
                retries = 1,
                retrySinceMs = 25_000L,
                warned = false,
                autoAdvance = true,
                rendererPlaying = false
            )
        )
    }

    @Test
    fun `主动投屏不受rendererPlaying佐证影响_仍提示`() {
        // rendererPlaying 佐证只作用于曲末推进（autoAdvance），主动投屏重发耗尽后
        // 即使电视报告 PLAYING 也要提示——B3 场景（SOAP PLAYING 但没取流=假播放）。
        assertEquals(
            CastEstablishPolicy.Action.WARN,
            resolve(
                castStartMs = 10_000L,
                nowMs = 28_000L,
                retries = 1,
                warned = false,
                autoAdvance = false,
                rendererPlaying = true
            )
        )
    }

    @Test
    fun `曲末推进电视来取流即确认建立`() {
        assertEquals(
            CastEstablishPolicy.Action.MARK_ESTABLISHED,
            resolve(
                castStartMs = 10_000L,
                lastFetchMs = 17_500L,
                nowMs = 18_000L,
                autoAdvance = true
            )
        )
    }
}

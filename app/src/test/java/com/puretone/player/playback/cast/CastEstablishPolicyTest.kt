package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 投屏「真建立」确认状态机（B3）的纯 JVM 锁死。
 *
 * 核心是「SOAP 200 ≠ 电视在播」：只有**当前 token** 的 URL 被真正取流才算建立；
 * 超时后先重发一次，仍无取流只提示一次，不无限骚扰渲染器。
 *
 * 「本轮」由 `CastHttpServer.urlFor()` 换 token + 清零保证，取流时间戳允许早于
 * `castStartMs`（170435 的 Play 返回前早到 GET），策略层不再重复做时间门槛。
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
    fun `上一首的重连不算本轮建立_由换token清零表达`() {
        // CastHttpServer.urlFor() 每轮换 token 时把 lastFetchMs 清零，
        // markFetchIfActive() 只认当前 activeToken；所以「上一首的重连」在策略层
        // 表现为 lastFetchMs=0，而不是一个比 castStartMs 更早的时间戳。
        // 反过来，比 castStartMs 更早的时间戳只要 > 0，就一定是本轮早到的首次取流。
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(castStartMs = 10_000L, lastFetchMs = 0L, nowMs = 11_000L)
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
    fun `曲末推进踢脚失效且电视仍PLAYING则补发单独Play_054751回归`() {
        // 054751：15s Seek 踢脚返回 200 却 81s 没取流，G2 的「PLAYING 佐证」
        // 原先是无上限永久静默。踢脚宽限到期后电视仍报 PLAYING，应补发一次
        // 单独 Play（真机手动点播放后 1s 即 fetch），而不是继续沉默。
        assertEquals(
            CastEstablishPolicy.Action.NUDGE_PLAY,
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

    // ---------- H1：踢脚失效兜底 + 给 PLAYING 佐证加上限（真机 20261109_054751） ----------

    @Test
    fun `补发Play后宽限内不急着提示`() {
        assertEquals(1, CastEstablishPolicy.AUTO_ADVANCE_NUDGE_COUNT)
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(
                castStartMs = 10_000L,
                nowMs = 48_000L,
                retries = 2,
                retrySinceMs = 42_000L,
                autoAdvance = true,
                rendererPlaying = true
            )
        )
    }

    @Test
    fun `补发Play动作耗尽且未超上限仍安静等_064254慢取流不误报`() {
        // 动作 30s 发出、宽限到 45s；45~60s 之间仍属「在线只是慢」，不弹未响应。
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(
                castStartMs = 10_000L,
                nowMs = 58_000L,
                retries = 2,
                retrySinceMs = 42_000L,
                autoAdvance = true,
                rendererPlaying = true
            )
        )
    }

    @Test
    fun `PLAYING佐证超过上限必须提示不再无限静默_054751回归`() {
        assertEquals(60_000L, CastEstablishPolicy.AUTO_ADVANCE_MAX_SILENT_MS)
        assertEquals(
            CastEstablishPolicy.Action.WARN,
            resolve(
                castStartMs = 10_000L,
                nowMs = 71_000L,
                retries = 2,
                retrySinceMs = 42_000L,
                autoAdvance = true,
                rendererPlaying = true
            )
        )
    }

    @Test
    fun `超限后只提示一次且取流到达仍优先确认建立`() {
        assertEquals(
            CastEstablishPolicy.Action.NONE,
            resolve(
                castStartMs = 10_000L,
                nowMs = 72_000L,
                retries = 2,
                retrySinceMs = 42_000L,
                warned = true,
                autoAdvance = true,
                rendererPlaying = true
            )
        )
        assertEquals(
            CastEstablishPolicy.Action.MARK_ESTABLISHED,
            resolve(
                castStartMs = 10_000L,
                lastFetchMs = 71_500L,
                nowMs = 72_000L,
                retries = 2,
                warned = true,
                autoAdvance = true,
                rendererPlaying = true
            )
        )
    }

    @Test
    fun `踢脚后电视不PLAYING时不补发Play直接提示_保持旧行为`() {
        assertEquals(
            CastEstablishPolicy.Action.WARN,
            resolve(
                castStartMs = 10_000L,
                nowMs = 42_000L,
                retries = 1,
                retrySinceMs = 25_000L,
                autoAdvance = true,
                rendererPlaying = false
            )
        )
    }

    // ---------- 170435：Play 返回前「早到的首次取流」不得丢弃（Knock 冻结 15s） ----------

    @Test
    fun `Play返回前早到的本轮首次取流立即确认建立_170435回归`() {
        // 电视在 SetAVTransportURI 与 Play 之间就发起 GET，而 castStartMs 写在
        // client.play() 返回之后：fetchMs 会比 castStartMs 早几百毫秒。
        // urlFor() 已换 token 并清零，这个时间戳必然属于本轮；旧判据把它当成
        // 「上一首的重连」丢掉，白等 15s 才由 KICK 的 Seek 逼出第二次 GET。
        assertEquals(
            CastEstablishPolicy.Action.MARK_ESTABLISHED,
            resolve(castStartMs = 10_000L, lastFetchMs = 9_650L, nowMs = 10_300L)
        )
    }

    @Test
    fun `曲末推进早到取流也立即建立_不再白踢一脚`() {
        assertEquals(
            CastEstablishPolicy.Action.MARK_ESTABLISHED,
            resolve(
                castStartMs = 10_000L,
                lastFetchMs = 9_800L,
                nowMs = 10_200L,
                autoAdvance = true,
                rendererPlaying = true
            )
        )
    }

    @Test
    fun `早到取流优先于任何超时动作与已提示状态`() {
        assertEquals(
            CastEstablishPolicy.Action.MARK_ESTABLISHED,
            resolve(
                castStartMs = 10_000L,
                lastFetchMs = 9_990L,
                nowMs = 42_000L,
                retries = 2,
                retrySinceMs = 25_000L,
                warned = true,
                autoAdvance = true,
                rendererPlaying = true
            )
        )
    }

    @Test
    fun `确认零取流时曲末推进仍按原路径踢脚并提示`() {
        // 放宽「本轮」判据不等于放弃未取流兜底：真的没有取流时，
        // 15s KICK 与踢脚耗尽后的 WARN 必须照旧。
        assertEquals(
            CastEstablishPolicy.Action.KICK,
            resolve(
                castStartMs = 10_000L,
                lastFetchMs = 0L,
                nowMs = 25_000L,
                autoAdvance = true
            )
        )
        assertEquals(
            CastEstablishPolicy.Action.WARN,
            resolve(
                castStartMs = 10_000L,
                lastFetchMs = 0L,
                nowMs = 42_000L,
                retries = 1,
                retrySinceMs = 25_000L,
                autoAdvance = true,
                rendererPlaying = false
            )
        )
    }
}

package com.puretone.player.playback.cast

/**
 * 投屏「真建立」确认的纯判据（2026-11-07 真机「电视画面断开」修复 B3）。
 *
 * ## 背景
 *
 * `SetAVTransportURI` + `Play` 返回 200 只证明渲染器**收下了** URI，不等于电视会来
 * 取流播放。诊断 20261107_165555 里 16:55:25 `cast-ok` + `own-state PLAYING` 之后，
 * 新曲一条 `http-fetch` 都没有，手机卡片却靠 uiState 外推显示 00:24——电视画面
 * 早已退出。故以「本轮投送后当前 URL 真被取流」为建立信号，不再把 SOAP 200 当播放。
 *
 * ## 状态机
 *
 * 未建立 → 观测到取流 = 建立；超过宽限仍无取流 = 用同一 URL 重发一次；重发后仍无
 * 取流 = 只提示一次用户，不反复打扰渲染器。曲末自动推进则用更宽宽限，超时后发 Seek
 * 踢脚（不重发 seturi），踢脚耗尽且电视未报告 PLAYING 才提示。库通道不经过本应用
 * HTTP 服务，[CastState.isEstablished] 直通 true，本判据返回 [Action.NONE]。
 *
 * 与 [CastStopPolicy] / [CastQueuePolicy] 一样收成纯函数，便于纯 JVM 逐条锁死。
 */
internal object CastEstablishPolicy {

    /**
     * 投送后等待电视首次取流的宽限（覆盖 SOAP 往返 + 渲染器缓冲起播）。
     *
     * 取值是「慢换曲」与「真卡死」的分界，两边都有真机证据：
     * - 20261107_184914：18:45:37 `cast-ok` 后新曲 9 秒都无 `http-fetch`，8 秒宽限
     *   才重发，重发后 1 秒取流——**真卡死时有效动作是重发**；
     * - 20261107_203641：曲末换曲后电视**正常**取流延迟实测 3.2~4.6 秒。上一版收紧到
     *   3 秒，卡在正常换曲耗时之内，每次切歌都误触发 `cast-reestablish` 重发
     *   `SetAVTransportURI + Play`，电视从头重新拉流播放——用户听到「切歌后前 3 秒
     *   重复两边」，电视侧表现成「断开重连」。
     *
     * 故取 6 秒：覆盖正常换曲（≤4.6s）不重发，真卡死（>6s）仍能比重发 8s 更快救回。
     * 重发之后同样留一个宽限再判定，见 [resolve]。
     */
    const val GRACE_MS = 6_000L

    /**
     * 「曲末自动推进」投送的建立宽限。
     *
     * 曲末推进时电视刚自然播完上一首、正处 STOPPED 复位，重新取下一首流的延迟波动大：
     * 真机 20261107_224000 实测 7~8 秒（Blue Skies 7.1s / Call Me Maybe 7.8s / Lucky
     * 7.4s / Take Me To Your Heart 7.4s），远超手动切歌的 <1s。这期间电视**必然**会来
     * 取流（它播完上一首、在等下一首），慢不是卡死。用 6s 宽限一到就重发
     * `SetAVTransportURI+Play` 会把电视正在进行的取流/播放位置重置——用户听到「开头
     * 几秒重复」，重发前 6~7 秒的静默就是「没有声音」。
     *
     * 故曲末推进用 15s 宽限；超时后不重发 seturi+play（那会打断慢取流），而是发一次
     * Seek 踢脚触发取流（真机 20261108_064254 实测这台电视收到 Seek 立即 fetch），
     * 见 [Action.KICK] 与 [AUTO_ADVANCE_KICK_COUNT]。
     */
    const val AUTO_ADVANCE_GRACE_MS = 15_000L

    /** 建立确认最多自动重发几次（主动投屏：同一 URL 重发 SetAVTransportURI + Play） */
    const val MAX_RETRIES = 1

    /**
     * 曲末自动推进时 Seek 踢脚次数。
     *
     * 真机 20261108_064254：电视曲末推进后报告 PLAYING 却不主动取流（延迟 18~39s），
     * 且实测「收到一个 Seek 命令就立即 fetch」。故不重发 seturi+play（那会打断慢取流），
     * 改为超时后发一次 Seek 踢脚触发取流；踢脚耗尽仍无取流才提示。见 [Action.KICK]。
     */
    const val AUTO_ADVANCE_KICK_COUNT = 1

    enum class Action {
        /** 无需动作（已建立 / 未在投屏 / 还在宽限内 / 已提示过） */
        NONE,

        /** 观测到本轮取流：置 isEstablished=true */
        MARK_ESTABLISHED,

        /** 超时未取流且还有重试额度：用同一 URL 重发 SetAVTransportURI + Play */
        RETRY,

        /** 曲末推进超时：发 Seek 踢一脚触发电视取流（重发 seturi 会打断慢取流） */
        KICK,

        /** 重试耗尽：写一次提示，不再重复 */
        WARN
    }

    fun resolve(
        isCasting: Boolean,
        isEstablished: Boolean,
        castStartMs: Long,
        lastFetchMs: Long,
        nowMs: Long,
        retries: Int,
        warned: Boolean,
        /** 最近一次重发/踢脚的墙钟（0 = 尚未动作）；动作后也要留一个宽限再判定 */
        retrySinceMs: Long = 0L,
        /**
         * 是否「曲末自动推进」投送：true 时用 [AUTO_ADVANCE_GRACE_MS] 宽限，超时后发
         * [Action.KICK]（Seek 踢脚触发取流）而非 [Action.RETRY]。见 [CastState.autoAdvance]。
         */
        autoAdvance: Boolean = false,
        /**
         * 电视当前是否报告 PLAYING。曲末推进踢脚耗尽后若电视已 PLAYING，说明它在线且
         * 在响应（只是取流慢），此时报「未响应」是误报，继续等取流即可。
         */
        rendererPlaying: Boolean = false,
        graceMs: Long = GRACE_MS,
        maxRetries: Int = MAX_RETRIES
    ): Action {
        // 曲末推进：宽限 + 踢脚（不重发 seturi），两条都按场景切换，其余判据与主动投送一致
        val effectiveGrace = if (autoAdvance) AUTO_ADVANCE_GRACE_MS else graceMs
        val effectiveMaxRetries = if (autoAdvance) AUTO_ADVANCE_KICK_COUNT else maxRetries
        if (!isCasting || isEstablished || castStartMs <= 0L) return Action.NONE
        // 只认「本轮投送之后」的取流：上一首的重连不算数
        if (lastFetchMs >= castStartMs) return Action.MARK_ESTABLISHED
        // 时钟回拨 / 刚投出去：还在宽限内，继续等
        if (nowMs - castStartMs < effectiveGrace) return Action.NONE
        // 动作之后同样留宽限：渲染器要重启控制服务再重新拉流，动作完下一秒就
        // 弹「电视未响应」是误报。retrySinceMs=0 表示尚未动作，保持旧行为。
        if (retries > 0 && retrySinceMs > 0L && nowMs - retrySinceMs < effectiveGrace) return Action.NONE
        if (retries < effectiveMaxRetries) {
            return if (autoAdvance) Action.KICK else Action.RETRY
        }
        // 曲末推进踢脚耗尽后，若电视已 PLAYING（在线且在响应），报「未响应」是误报：
        // 真机 20261108_064254 电视取流延迟 18~39s，期间一直 PLAYING，继续等取流即可。
        if (autoAdvance && rendererPlaying) return Action.NONE
        return if (warned) Action.NONE else Action.WARN
    }
}

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
 * ## 取流时间戳的「本轮」语义（2026-11-09 真机 20261109_170435 修正）
 *
 * [CastHttpServer] 的 `urlFor()` 每轮投送都会换 token 并把 `lastFetchMs` 清零，
 * `markFetchIfActive()` 又只认当前 `activeToken`，因此 `lastSuccessfulFetchMs()`
 * 只要 > 0 **必然属于本轮**。旧判据再要求 `lastFetchMs >= castStartMs` 是多余且
 * 有害的：电视常在 `SetAVTransportURI` 与 `Play` 之间就发起 GET，而 `castStartMs`
 * 写在 `client.play()` 返回之后，首次取流会早到几百毫秒，被误判成「上一首的重连」
 * 丢掉。170435 里 Knock 因此白等 15s，直到 KICK 的 Seek 逼出第二次 GET 才建立
 * （电视一直在出声，手机进度条却冻结在 00:00）。同一报告另两首
 * `cast-established elapsed=265ms / 5ms`，可见这竞态窗口只有几毫秒到几百毫秒。
 *
 * `castStartMs` 仍用于宽限窗口、[CastStopPolicy] 曲中停止墙钟、`CastFetchIdleLog`
 * 的兜底基准与诊断耗时，但不再作为「这次取流算不算数」的硬门槛。
 *
 * ## 状态机
 *
 * 未建立 → 观测到取流 = 建立；超过宽限仍无取流 = 用同一 URL 重发一次；重发后仍无
 * 取流 = 只提示一次用户，不反复打扰渲染器。曲末自动推进则用更宽宽限，超时后发 Seek
 * 踢脚（不重发 seturi），踢脚失效且电视仍报 PLAYING 时补发一次单独 Play，动作耗尽
 * 且总静默超上限才提示。库通道不经过本应用 HTTP 服务，[CastState.isEstablished]
 * 直通 true，本判据返回 [Action.NONE]。
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

    /**
     * 曲末推进时 Seek 踢脚失效后，补发「单独 Play」的次数。
     *
     * 真机 20261109_054751：踢脚前 6 次都在 1~2s 内救回，第 7 次 Seek 返回 200 却
     * 整整 81 秒没取流；用户手动点播放下发单独 `Play` 后 1 秒即 `cast-established`。
     * 故补发**单独 Play**（绝不重发 seturi+play，那会打断慢取流、造成开头重复）。
     */
    const val AUTO_ADVANCE_NUDGE_COUNT = 1

    /**
     * 曲末推进动作耗尽后，「电视仍报 PLAYING」这条佐证最多再等到投送后多久。
     *
     * 064254 的 18~39s 慢取流靠 G2 佐证避免误报，但原先实现成无上限永久静默：
     * 054751 里电视卡了 97.4s，应用既不补动作也不提示，只能靠用户手动点播放。
     * 上限之内继续安静等，超过则必须告知用户。
     */
    const val AUTO_ADVANCE_MAX_SILENT_MS = 60_000L

    enum class Action {
        /** 无需动作（已建立 / 未在投屏 / 还在宽限内 / 已提示过） */
        NONE,

        /** 观测到本轮取流：置 isEstablished=true */
        MARK_ESTABLISHED,

        /** 超时未取流且还有重试额度：用同一 URL 重发 SetAVTransportURI + Play */
        RETRY,

        /** 曲末推进超时：发 Seek 踢一脚触发电视取流（重发 seturi 会打断慢取流） */
        KICK,

        /** 踢脚失效且电视仍 PLAYING：补发一次单独 Play（054751 实测 1s 内取流） */
        NUDGE_PLAY,

        /** 重试耗尽：写一次提示，不再重复 */
        WARN
    }

    fun resolve(
        isCasting: Boolean,
        isEstablished: Boolean,
        /** 本轮投送完成时刻（`onCastSucceeded`）；只用于宽限 / 墙钟 / 诊断，不作取流门槛 */
        castStartMs: Long,
        /**
         * 当前 token 最近一次取流墙钟（`CastHttpServer.lastSuccessfulFetchMs()`）。
         * 0 = 本轮从未取流；> 0 即本轮来过，**允许早于 `castStartMs`**。上一轮
         * 的重连由 `urlFor()` 切 token 时清零，不会混进来。
         */
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
        // 曲末推进：宽限 + Seek 踢脚 + 补发 Play（都不重发 seturi），按场景切换
        val effectiveGrace = if (autoAdvance) AUTO_ADVANCE_GRACE_MS else graceMs
        val effectiveMaxRetries = if (autoAdvance) {
            AUTO_ADVANCE_KICK_COUNT + AUTO_ADVANCE_NUDGE_COUNT
        } else maxRetries
        if (!isCasting || isEstablished || castStartMs <= 0L) return Action.NONE
        // 只认「当前 token」的取流：urlFor() 换 token 时已把 lastFetchMs 清零，
        // markFetchIfActive() 也只记 activeToken，所以 > 0 就必然属于本轮。
        // 不再要求 fetchMs >= castStartMs——电视可能在 Play 返回前就来 GET，
        // 那次早到的首次取流被丢掉会让手机白等 15s（170435）。
        if (lastFetchMs > 0L) return Action.MARK_ESTABLISHED
        // 时钟回拨 / 刚投出去：还在宽限内，继续等
        if (nowMs - castStartMs < effectiveGrace) return Action.NONE
        // 动作之后同样留宽限：渲染器要重启控制服务再重新拉流，动作完下一秒就
        // 弹「电视未响应」是误报。retrySinceMs=0 表示尚未动作，保持旧行为。
        if (retries > 0 && retrySinceMs > 0L && nowMs - retrySinceMs < effectiveGrace) return Action.NONE
        if (retries < effectiveMaxRetries) {
            // 第一次动作：主动投屏重发 SetAVTransportURI+Play；曲末推进只发 Seek 踢脚。
            if (retries == 0) return if (autoAdvance) Action.KICK else Action.RETRY
            // 第二次动作只在「曲末推进 + 电视仍报 PLAYING」时补发一次单独 Play。
            // 电视连 PLAYING 都不报视为真卡死，不补发，落到下面的提示判据。
            if (autoAdvance && rendererPlaying) return Action.NUDGE_PLAY
        }
        // 曲末推进动作耗尽：PLAYING 佐证不再是无限期。064254 的 18~39s 慢取流仍在
        // 上限内安静等，但超过上限还没声音必须提示——054751 就是被「永久静默」
        // 拖到 97s 才靠用户手动点播放救回。
        if (autoAdvance && rendererPlaying && nowMs - castStartMs < AUTO_ADVANCE_MAX_SILENT_MS) {
            return Action.NONE
        }
        return if (warned) Action.NONE else Action.WARN
    }
}

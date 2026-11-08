package com.puretone.player.playback.cast

/**
 * 投屏播放态的「稳定化」纯判据（2026-11-06 真机回归）。
 *
 * ## 背景
 *
 * 1Hz 轮询把渲染器 `GetTransportInfo` 的 `CurrentTransportState` 直接写成
 * [CastState.isPlaying]：`PLAYING` 为 true，其余一律 false。真机 623 会在切歌过渡、
 * 缓冲、甚至正常播放中偶发**一帧** `PAUSED_PLAYBACK`，下一帧又回到 `PLAYING`
 * （诊断 18:24:25 PAUSED / 18:24:26 PLAYING）。而 `togglePlayPause()`、遥控页图标、
 * `CastPlaybackPlayer.isPlaying()`（通知栏/锁屏媒体按键）全都以这个瞬时值为准：
 * UI 会闪一下「已暂停」，用户去点「播放」时轮询却已恢复 `PLAYING`，取反后下发的是
 * `Pause` —— 表现就是「投屏播着播着自己暂停了」，诊断里留下 `user-pause | 投屏态`。
 *
 * ## 两条规则
 *
 * - **命令宽限**：本地刚下发 Play/Pause 后，渲染器状态在 1Hz 轮询里会滞后 1~2 帧，
 *   期间一律以「命令期望态」为准，直到渲染器回包确认或宽限到期。这样命令即时写入的
 *   `uiState.isPlaying` 不会被滞后轮询覆盖回相反值。
 * - **暂停去抖**：没有本地命令时，渲染器自发的一帧 `PAUSED` 不采信，须持续
 *   [PAUSE_CONFIRM_MS] 才翻成「已暂停」；`STOPPED` / `IDLE` 必须立即采信——
 *   `CastQueueCoordinator` 靠「在播→不在播」的跃迁判断曲末，对它去抖会漏掉切歌。
 * - **终止态优先**：`STOPPED` 是「本条 URI 已终止」的明确表态，也是曲末推进唯一可靠的
 *   信号。它不能因为命令宽限被伪装成「在播」，否则 `playingSeen` 会被提前置位，
 *   宽限一过再读到 STOPPED 就会被误判成曲末、凭空多跳一首。用户按 Play 后渲染器
 *   短暂 STOPPED 同样必须如实上报。
 *
 * 与 [CastProgressPolicy] / [CastQueuePolicy] 一样收成纯函数，便于纯 JVM 逐条锁死。
 */
internal object CastPlayStatePolicy {

    /** 本地 Play/Pause 命令后，忽略渲染器过渡帧的时长（覆盖至少两次 1Hz 轮询） */
    const val COMMAND_GUARD_MS = 2_500L

    /** 渲染器自发的 PAUSED 需持续多久才认定为真暂停（滤掉一帧缓冲抖动） */
    const val PAUSE_CONFIRM_MS = 2_000L

    data class Decision(
        /** 本轮应写入 [CastState.isPlaying] 的稳定值 */
        val isPlaying: Boolean,
        /** 更新后的「首次进入 PAUSED」时刻，0 表示当前不在 PAUSED 去抖窗口内 */
        val pausedSinceMs: Long,
        /** 是否清除命令期望态（命令已确认 / 宽限已过期） */
        val clearCommand: Boolean
    )

    /**
     * @param rawPlaying 本轮渲染器是否字面 `PLAYING`
     * @param isPausedState 本轮映射后是否为 PAUSED（仅此态参与去抖）
     * @param previousStable 上一轮已写入的稳定值
     * @param commandExpected 本地最近一次命令的期望态，null 表示没有在途命令
     * @param guardRemainingMs 命令宽限剩余毫秒，<=0 表示已过期
     * @param pausedSinceMs 上一轮的 PAUSED 起始时刻，0 表示首次进入
     * @param nowMs 当前墙钟（由调用方传入，保持本函数纯）
     * @param isStoppedState 本轮映射后是否为 STOPPED；终止态优先于命令宽限，见类注释
     */
    fun resolve(
        rawPlaying: Boolean,
        isPausedState: Boolean,
        previousStable: Boolean,
        commandExpected: Boolean?,
        guardRemainingMs: Long,
        pausedSinceMs: Long,
        nowMs: Long,
        isStoppedState: Boolean = false
    ): Decision {
        val hadCommand = commandExpected != null

        // 终止态优先：STOPPED 绝不能被命令期望态写成「在播」。曲末推进靠
        // CastQueueCoordinator.playingSeen 的「在播→不在播」跃迁，一旦被伪造就
        // 可能在宽限到期后凭空多跳一首（慢速换曲 / APE 转码时甚至连锁跳）。
        if (isStoppedState) return Decision(false, 0L, clearCommand = hadCommand)

        // 命令宽限内：期望态优先。渲染器回报一致即确认并提前解除宽限；
        // 不一致说明只是过渡帧（SetAVTransportURI / 缓冲 / 旧轮询值），继续忽略。
        if (commandExpected != null && guardRemainingMs > 0L) {
            return if (rawPlaying == commandExpected) {
                Decision(commandExpected, 0L, clearCommand = true)
            } else {
                Decision(commandExpected, 0L, clearCommand = false)
            }
        }

        // 正常播放：立刻恢复并清掉 PAUSED 计时
        if (rawPlaying) return Decision(true, 0L, clearCommand = hadCommand)

        // STOPPED / IDLE / NO_MEDIA：曲末推进与失联判定依赖「立即不在播」，不能去抖
        if (!isPausedState) return Decision(false, 0L, clearCommand = hadCommand)

        // 渲染器自发的 PAUSED：连续确认才采信，期间保持上一稳定值
        val since = if (pausedSinceMs > 0L) pausedSinceMs else nowMs
        val confirmed = nowMs - since >= PAUSE_CONFIRM_MS
        return Decision(
            isPlaying = if (confirmed) false else previousStable,
            pausedSinceMs = since,
            clearCommand = hadCommand
        )
    }

    /**
     * 计算命令宽限的截止时刻：**同一期望态连点不续期**。
     *
     * 真机 20261107_184914 的「卡片可暂停、不可播放」有一条放大器：通知栏连点 Pause
     * 每次都把宽限重置到 `now + [COMMAND_GUARD_MS]`，于是 [CastState.isPlaying] 被
     * 永久钉在命令期望态，1Hz 轮询的真实状态一直进不来；UI 取反基准随之锁死，
     * 用户点「播放」被取反成 Pause。命令宽限应当只覆盖「这一条命令发出后的一次收敛」，
     * 重复同一期望态只是重试，不该把窗口无限往后推。
     *
     * 期望态翻转（Play ↔ Pause）或原宽限已过期时，才重新起一个完整宽限。
     */
    fun guardUntilMs(
        previousExpected: Boolean?,
        expected: Boolean,
        previousGuardUntilMs: Long,
        nowMs: Long,
        guardMs: Long = COMMAND_GUARD_MS
    ): Long = if (previousExpected == expected && previousGuardUntilMs > nowMs) {
        previousGuardUntilMs
    } else {
        nowMs + guardMs
    }
}

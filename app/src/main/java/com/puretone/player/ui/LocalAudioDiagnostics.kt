package com.puretone.player.ui

import android.content.Context
import com.puretone.player.R
import com.puretone.player.playback.AudioRouteSnapshot
import com.puretone.player.playback.PlaybackEventLog

/**
 * 诊断报告的「本地播放音频自检」段落（2026-11-07 蓝牙后台「自动暂停」取证）。
 *
 * 07:40 那份报告里「后台投屏自检」只回答了 battery/doze，本地播放侧一片空白：
 * 看不到焦点事件类型、看不到当时是不是蓝牙 A2DP、也看不到 duck 后的音量。
 * 这里沿用投屏自检的做法——**只读系统状态 + 摘一条已有事件**，不加设置项、
 * 不新增权限、不碰播放链路。
 *
 * 注意本段是「导出报告时刻」的快照，而故障往往发生在一小时前；因此额外把
 * [PlaybackEventLog] 里最后一条 `audio-focus-*` 原文附上，那是**故障当时**的现场。
 * 设备项只记类型名（见 [AudioRouteSnapshot]），不含蓝牙名称或地址。
 */
internal object LocalAudioDiagnostics {

    private const val FOCUS_MARKER = "audio-focus-"

    fun append(context: Context, sb: StringBuilder) {
        val state = runCatching { AudioRouteSnapshot.read(context) }
            .getOrDefault(AudioRouteSnapshot.State(emptyList(), null, null, null, null))
        val events = runCatching { PlaybackEventLog.read() }.getOrDefault("")
        sb.appendLine(context.getString(R.string.pt_diag_030))
        sb.appendLine(context.getString(R.string.pt_diag_031, AudioRouteSnapshot.describeTypes(state.outputTypes)))
        sb.appendLine(
            context.getString(
                R.string.pt_diag_032,
                AudioRouteSnapshot.triState(AudioRouteSnapshot.hasBluetoothOutput(state.outputTypes))
            )
        )
        sb.appendLine(context.getString(R.string.pt_diag_033, AudioRouteSnapshot.volumeText(state)))
        sb.appendLine(context.getString(R.string.pt_diag_034, AudioRouteSnapshot.modeName(state.mode)))
        sb.appendLine(context.getString(R.string.pt_diag_035, AudioRouteSnapshot.triState(state.musicActive)))
        sb.appendLine(
            context.getString(
                R.string.pt_diag_036,
                lastFocusEvent(events) ?: context.getString(R.string.pt_diag_037)
            )
        )
        sb.appendLine(context.getString(R.string.pt_diag_038))
    }

    /** 取最近一条焦点事件原文；普通播放事件（crossfade / user-pause 等）一律不算。 */
    fun lastFocusEvent(log: String): String? =
        log.lineSequence().lastOrNull { FOCUS_MARKER in it }?.trim()
}

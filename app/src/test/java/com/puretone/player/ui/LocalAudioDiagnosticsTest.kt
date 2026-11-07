package com.puretone.player.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断报告「最近焦点事件」摘取的纯文本契约。 */
class LocalAudioDiagnosticsTest {

    @Test
    fun `多条焦点事件取最后一条原文`() {
        val log = listOf(
            "11-07 06:37:22.041 audio-focus-change | type=LOSS(-1) cmd=PAUSE",
            "11-07 06:40:32.100 crossfade-start | idx=66",
            "11-07 07:10:00.975 audio-focus-duck | action=duck route=蓝牙A2DP"
        ).joinToString("\n")

        val last = LocalAudioDiagnostics.lastFocusEvent(log)

        assertTrue(last!!.startsWith("11-07 07:10:00.975 audio-focus-duck"))
        assertTrue(last.contains("route=蓝牙A2DP"))
    }

    @Test
    fun `只取焦点事件，普通播放事件不算`() {
        val log = listOf(
            "11-07 07:21:28.199 audio-focus-pause-skipped | reason=local-keep-playing",
            "11-07 07:32:20.000 crossfade-finalize | idx=78",
            "11-07 07:38:05.000 playback-ended | idx=79"
        ).joinToString("\n")

        assertTrue(LocalAudioDiagnostics.lastFocusEvent(log)!!.contains("audio-focus-pause-skipped"))
    }

    @Test
    fun `没有焦点事件或日志为空时返回 null`() {
        assertNull(LocalAudioDiagnostics.lastFocusEvent(""))
        assertNull(LocalAudioDiagnostics.lastFocusEvent("11-07 07:38:05.000 playback-ended | idx=79"))
    }
}

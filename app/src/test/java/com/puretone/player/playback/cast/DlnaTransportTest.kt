package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DlnaTransport] 纯函数单测：UPnP 时间串与传输状态映射。
 *
 * 这两个值直接决定 UI 上的进度条与「是否在播」，映射错会让进度条永远停在 0、
 * 或把「暂停」显示成「播放中」。
 */
class DlnaTransportTest {

    @Test
    fun `解析标准时分秒`() {
        assertEquals(201_000L, DlnaTransport.parseTime("00:03:21"))
    }

    @Test
    fun `解析带小数秒与进位的小时`() {
        // 小数秒部分按毫秒精度可忽略：进度展示按秒对齐即可
        assertEquals(1_001_000L, DlnaTransport.parseTime("00:16:41.500"))
        assertEquals(3_600_000L, DlnaTransport.parseTime("01:00:00"))
    }

    @Test
    fun `非法时间串返回零`() {
        assertEquals(0L, DlnaTransport.parseTime(""))
        assertEquals(0L, DlnaTransport.parseTime("NOT_IMPLEMENTED"))
    }

    @Test
    fun `毫秒格式化为时分秒`() {
        assertEquals("00:03:21", DlnaTransport.formatTime(201_000L))
        assertEquals("01:00:00", DlnaTransport.formatTime(3_600_000L))
        assertEquals("00:00:00", DlnaTransport.formatTime(-5L))
    }

    @Test
    fun `暂停态映射为 PAUSED`() {
        assertEquals("PAUSED", DlnaTransport.stateName("PAUSED_PLAYBACK"))
        assertEquals("PLAYING", DlnaTransport.stateName("playing"))
        assertEquals("STOPPED", DlnaTransport.stateName("STOPPED"))
    }

    @Test
    fun `过渡态与无媒体态分别映射为缓冲与空闲`() {
        assertEquals("BUFFERING", DlnaTransport.stateName("TRANSITIONING"))
        assertEquals("IDLE", DlnaTransport.stateName("NO_MEDIA_PRESENT"))
        assertEquals("IDLE", DlnaTransport.stateName(null))
    }

    // ------------------------------------------------------------------ 音量量程换算

    @Test
    fun `标准量程下百分比原样下发`() {
        assertEquals(93, DlnaTransport.toDeviceVolume(93, 100))
        assertEquals(93, DlnaTransport.toPercent(93, 100))
    }

    @Test
    fun `量程为 30 时按比例压缩`() {
        // 真机上的 QQLiveTV 渲染器就是这种形态：GetVolume 恒回读 18，
        // 而 0~100 的原值发过去会被按上限截断，表现为「调了没反应」。
        assertEquals(28, DlnaTransport.toDeviceVolume(93, 30))
        assertEquals(0, DlnaTransport.toDeviceVolume(0, 30))
        assertEquals(30, DlnaTransport.toDeviceVolume(100, 30))
        assertEquals(60, DlnaTransport.toPercent(18, 30))
    }

    @Test
    fun `量程为 255 时按比例放大且两端钳死`() {
        assertEquals(237, DlnaTransport.toDeviceVolume(93, 255))
        assertEquals(255, DlnaTransport.toDeviceVolume(150, 255))
        assertEquals(0, DlnaTransport.toDeviceVolume(-10, 255))
        assertEquals(100, DlnaTransport.toPercent(999, 255))
    }

    @Test
    fun `量程非法时退回 0 到 100`() {
        assertEquals(50, DlnaTransport.toDeviceVolume(50, 0))
        assertEquals(50, DlnaTransport.toPercent(50, 0))
    }

    // ------------------------------------------------------------------ SCPD 解析

    @Test
    fun `从 SCPD 取 Volume 变量的上限`() {
        val scpd = """
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <serviceStateTable>
                <stateVariable><name>PresetNameList</name><dataType>string</dataType></stateVariable>
                <stateVariable>
                  <name>Volume</name><dataType>ui2</dataType>
                  <allowedValueRange><minimum>0</minimum><maximum>30</maximum><step>1</step></allowedValueRange>
                </stateVariable>
              </serviceStateTable>
            </scpd>
        """.trimIndent()
        assertEquals(30, DlnaTransport.parseVolumeMax(scpd))
    }

    @Test
    fun `SCPD 没有 Volume 变量时返回 null`() {
        val scpd = """<scpd><stateVariable><name>Mute</name><dataType>boolean</dataType></stateVariable></scpd>"""
        assertEquals(null, DlnaTransport.parseVolumeMax(scpd))
    }

    @Test
    fun `SCPD 的 Volume 没有取值范围时返回 null`() {
        val scpd = """<scpd><stateVariable><name>Volume</name><dataType>ui2</dataType></stateVariable></scpd>"""
        assertEquals(null, DlnaTransport.parseVolumeMax(scpd))
    }
}

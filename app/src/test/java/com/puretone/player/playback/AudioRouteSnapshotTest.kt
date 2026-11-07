package com.puretone.player.playback

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频路由快照的纯函数契约（2026-11-07 蓝牙后台取证）。
 *
 * 重点是「读不到 ≠ 没有蓝牙」：诊断文本里一旦把取证缺失写成「否」，
 * 排查方向会被直接带偏。设备类型集合则覆盖 A2DP / SCO 之外的 BLE 与助听器，
 * 否则新版蓝牙耳机在日志里会 fall through 成「其他」，取证等于没做。
 */
class AudioRouteSnapshotTest {

    @Test
    fun `蓝牙输出类型全覆盖：A2DP SCO BLE 与助听器`() {
        val bluetoothTypes = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            23, // TYPE_HEARING_AID
            26, // TYPE_BLE_HEADSET
            27, // TYPE_BLE_SPEAKER
            30 // TYPE_BLE_BROADCAST
        )
        bluetoothTypes.forEach { type ->
            assertTrue("type=$type 应识别为蓝牙", AudioRouteSnapshot.isBluetooth(listOf(type)))
        }
    }

    @Test
    fun `有线耳机与内置扬声器不算蓝牙输出`() {
        val wired = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE
        )
        assertFalse(AudioRouteSnapshot.isBluetooth(wired))
    }

    @Test
    fun `类型名可读，未知类型保留原始码`() {
        assertEquals("蓝牙A2DP", AudioRouteSnapshot.typeName(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals("内置扬声器", AudioRouteSnapshot.typeName(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals("BLE耳机", AudioRouteSnapshot.typeName(26))
        assertEquals("其他(type=999)", AudioRouteSnapshot.typeName(999))
    }

    @Test
    fun `设备列表去重且保序，空列表给未知而不是空串`() {
        val a2dp = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertEquals("蓝牙A2DP+内置扬声器", AudioRouteSnapshot.describeTypes(listOf(a2dp, a2dp, speaker)))
        assertEquals("未知", AudioRouteSnapshot.describeTypes(emptyList()))
        assertNull(AudioRouteSnapshot.hasBluetoothOutput(emptyList()))
    }

    @Test
    fun `未知状态三态不得伪装成否`() {
        val state = AudioRouteSnapshot.State(emptyList(), null, null, null, null)

        assertEquals("未知", AudioRouteSnapshot.volumeText(state))
        assertEquals("未知", AudioRouteSnapshot.modeName(null))
        assertEquals("未知", AudioRouteSnapshot.triState(null))
        assertEquals("否", AudioRouteSnapshot.triState(false))
        assertTrue(AudioRouteSnapshot.compact(state).contains("bt=未知"))
        assertTrue(AudioRouteSnapshot.compact(state).contains("route=未知"))
    }

    @Test
    fun `紧凑快照同时给出音量、模式与系统播放判定`() {
        val state = AudioRouteSnapshot.State(
            outputTypes = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            musicVolume = 8,
            musicMaxVolume = 15,
            mode = AudioManager.MODE_IN_COMMUNICATION,
            musicActive = false
        )
        val line = AudioRouteSnapshot.compact(state)

        assertTrue(line.contains("route=蓝牙A2DP"))
        assertTrue(line.contains("bt=是"))
        assertTrue(line.contains("vol=8/15"))
        assertTrue(line.contains("mode=IN_COMMUNICATION"))
        assertTrue(line.contains("musicActive=否"))
    }

    @Test
    fun `音量缺一项就整体给未知，避免拼出残缺数值`() {
        val state = AudioRouteSnapshot.State(emptyList(), 8, null, null, null)
        assertEquals("未知", AudioRouteSnapshot.volumeText(state))
    }
}

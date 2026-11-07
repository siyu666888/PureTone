package com.puretone.player.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 本地播放的音频路由快照（2026-11-07 蓝牙后台「自动暂停」取证）。
 *
 * 07:40 诊断里播放事件干干净净：12 首歌全部 crossfade 正常收尾、没有一条
 * `paused-with-queue`，却出现 3 次 `audio-focus-pause-skipped`。要区分到底是
 * 「系统夺焦点、应用按策略继续播但输出被掐」「蓝牙 A2DP 断开被 becoming-noisy
 * 置 playWhenReady=false」还是「duck 后音量没恢复」，必须同时看到**焦点事件**与
 * **当时的输出设备**。
 *
 * 本类只做只读快照，绝不改音量、不申请焦点、不申请权限：
 * - [PlaybackAudioFocus] 在每条焦点命令落地时把 [compact] 写进播放事件的 `route=` 字段；
 * - 诊断报告「本地播放音频自检」段落展示导出时刻的 [State]。
 *
 * 隐私：只记 `AudioDeviceInfo.type` 的**类型名**，不取设备名、地址或产品名——
 * 蓝牙设备名常含用户姓名，不该随诊断报告外发。读取失败一律给「未知」而非「否」，
 * 否则会把「取证缺失」误读成「没连蓝牙」。
 */
internal object AudioRouteSnapshot {

    data class State(
        val outputTypes: List<Int>,
        val musicVolume: Int?,
        val musicMaxVolume: Int?,
        val mode: Int?,
        val musicActive: Boolean?
    )

    // API 28/31/33 才公开的蓝牙音频设备类型。这里用数值而不是 AudioDeviceInfo 常量，
    // 避免 minSdk 26 触发 lint 的 NewApi；值取自 AOSP，纯整数比较不依赖平台实现。
    private const val TYPE_HEARING_AID = 23
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27
    private const val TYPE_BLE_BROADCAST = 30

    private val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        TYPE_HEARING_AID,
        TYPE_BLE_HEADSET,
        TYPE_BLE_SPEAKER,
        TYPE_BLE_BROADCAST
    )

    /** 逐字段独立兜底：某个系统服务调用抛异常不能让整份快照变成「全未知」。 */
    fun read(context: Context): State {
        val am = runCatching {
            context.applicationContext.getSystemService(AudioManager::class.java)
        }.getOrNull() ?: return State(emptyList(), null, null, null, null)
        return State(
            outputTypes = runCatching {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map { it.type } ?: emptyList()
            }.getOrDefault(emptyList()),
            musicVolume = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull(),
            musicMaxVolume = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull(),
            mode = runCatching { am.mode }.getOrNull(),
            musicActive = runCatching { am.isMusicActive }.getOrNull()
        )
    }

    /** 播放事件日志里的紧凑一行；三态用「未知」占位，绝不伪装成否定值。 */
    fun compact(state: State): String = buildString {
        append("route=").append(describeTypes(state.outputTypes))
        append(" bt=").append(triState(hasBluetoothOutput(state.outputTypes)))
        append(" vol=").append(volumeText(state))
        append(" mode=").append(modeName(state.mode))
        append(" musicActive=").append(triState(state.musicActive))
    }

    /** 设备列表读不到时返回 null，而不是「没有蓝牙输出」。 */
    fun hasBluetoothOutput(types: List<Int>): Boolean? =
        if (types.isEmpty()) null else isBluetooth(types)

    fun isBluetooth(types: List<Int>): Boolean = types.any { it in BLUETOOTH_TYPES }

    fun describeTypes(types: List<Int>): String =
        if (types.isEmpty()) "未知" else types.distinct().joinToString("+") { typeName(it) }

    fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机(带麦)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB音频"
        TYPE_HEARING_AID -> "助听器"
        TYPE_BLE_HEADSET -> "BLE耳机"
        TYPE_BLE_SPEAKER -> "BLE音箱"
        TYPE_BLE_BROADCAST -> "BLE广播"
        else -> "其他(type=$type)"
    }

    fun volumeText(state: State): String {
        val current = state.musicVolume
        val max = state.musicMaxVolume
        return if (current == null || max == null) "未知" else "$current/$max"
    }

    fun modeName(mode: Int?): String = when (mode) {
        null -> "未知"
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "mode=$mode"
    }

    fun triState(value: Boolean?): String = when (value) {
        true -> "是"
        false -> "否"
        null -> "未知"
    }
}

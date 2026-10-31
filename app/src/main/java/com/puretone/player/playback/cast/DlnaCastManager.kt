package com.puretone.player.playback.cast

import android.content.Context
import android.util.Log
import com.puretone.player.data.local.entity.SongEntity
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.DLNACast.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 投屏设备（PureTone 自有类型）。
 *
 * 用来隔离第三方 UPnPCast 的 [Device]：UI / ViewModel 只与本类型打交道，
 * 底层 [DLNACast.Device] 仅在 [DlnaCastManager] 内部持有，降低耦合。
 */
data class CastDevice(
    val id: String,
    val name: String,
    val address: String,
    val isTV: Boolean
)

/**
 * 投屏运行时状态（PureTone 自有，供 UI 直接收集）。
 */
data class CastState(
    /** 是否正在投屏（已选设备并成功发起 push） */
    val isCasting: Boolean = false,
    /** 当前投屏设备名 */
    val deviceName: String? = null,
    /** 当前投屏设备 IP */
    val deviceAddress: String? = null,
    /** 渲染器是否正在播放 */
    val isPlaying: Boolean = false,
    /** 渲染器播放态枚举名（IDLE/PLAYING/PAUSED/STOPPED/BUFFERING/ERROR） */
    val playbackState: String = "IDLE",
    /** 当前播放进度（毫秒） */
    val progressMs: Long = 0L,
    /** 总时长（毫秒） */
    val durationMs: Long = 0L,
    /** 最近一次错误描述（无错为 null） */
    val error: String? = null
)

/**
 * DLNA 投屏管理器（M1）。
 *
 * 封装 UPnPCast 单例门面 [DLNACast]：SSDP 设备发现、本地文件直投（库内置 HTTP 服务
 * 经 NanoHTTPD 按绝对路径伺服）、播放控制与状态轮询。对外只暴露协程安全的
 * [CastDevice]/[CastState]，不泄漏第三方类型。
 *
 * 设计要点：
 * - [init] 在 Application 生命周期调一次（创建底层引擎）；[release] 在进程退出时释放；
 * - 所有投屏/网络调用包 try/catch，任何异常只更新 [CastState.error]，绝不抛到调用方——
 *   投屏是附加能力，失败绝不能拖垮主播放链路或引发闪退；
 * - 投屏态与本地播放是「二选一」：进入投屏时由上层暂停本地 ExoPlayer，退出时恢复，
 *   本管理器不触碰本地播放器，路由决策在 [com.puretone.player.ui.PlayerViewModel] 完成。
 */
@Singleton
class DlnaCastManager @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    private var lastDevices: List<Device> = emptyList()
    private var currentDevice: Device? = null
    private var pollJob: Job? = null

    /** 初始化底层引擎（Application.onCreate 调用一次；可重复调用，库内部幂等替换） */
    fun init(context: Context) {
        try {
            DLNACast.init(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "DLNACast.init failed", e)
        }
    }

    /** 释放底层引擎（进程退出） */
    fun release() {
        try {
            DLNACast.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "DLNACast.cleanup failed", e)
        }
        stopPolling()
    }

    /**
     * SSDP 设备发现，返回局域网内渲染器列表（已映射为 [CastDevice]）。
     * 失败返回空列表并记录日志，不抛异常。
     */
    suspend fun searchDevices(timeoutMs: Long = 5000): List<CastDevice> =
        try {
            DLNACast.search(timeout = timeoutMs)
                .also { lastDevices = it }
                .map { CastDevice(it.id, it.name, it.address, it.isTV) }
        } catch (e: Exception) {
            Log.e(TAG, "DLNA search failed", e)
            emptyList()
        }

    /** 选定投屏设备（按 id 反查底层 [Device]） */
    fun selectDevice(device: CastDevice) {
        currentDevice = lastDevices.firstOrNull { it.id == device.id }
    }

    /**
     * 投屏当前本地歌曲：按 [SongEntity.path] 绝对路径直投，库内置 HTTP 服务伺服该文件。
     * 成功置 isCasting=true 并启动状态轮询；失败仅记录 error 并返回 false。
     */
    suspend fun castSong(song: SongEntity): Boolean {
        val device = currentDevice ?: run {
            _state.update { it.copy(error = "未选择投屏设备") }
            return false
        }
        return try {
            DLNACast.castLocalFile(song.path, device, song.title)
            _state.update {
                it.copy(
                    isCasting = true,
                    deviceName = device.name,
                    deviceAddress = device.address,
                    error = null
                )
            }
            startPolling()
            true
        } catch (e: Exception) {
            Log.e(TAG, "castLocalFile failed: ${song.path}", e)
            _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            false
        }
    }

    /** 投屏态下的播放控制（均已 try/catch，失败返回 false） */
    suspend fun play(): Boolean = try { DLNACast.play() } catch (e: Exception) { Log.w(TAG, "play failed", e); false }
    suspend fun pause(): Boolean = try { DLNACast.pause() } catch (e: Exception) { Log.w(TAG, "pause failed", e); false }
    suspend fun stop(): Boolean = try { DLNACast.stop() } catch (e: Exception) { Log.w(TAG, "stop failed", e); false }
    suspend fun seek(positionMs: Long): Boolean =
        try { DLNACast.seek(positionMs) } catch (e: Exception) { Log.w(TAG, "seek failed", e); false }
    suspend fun setVolume(volume: Int): Boolean =
        try { DLNACast.setVolume(volume.coerceIn(0, 100)) } catch (e: Exception) { Log.w(TAG, "setVolume failed", e); false }

    /** 停止投屏：停止渲染器、清空状态（本地播放恢复由上层负责） */
    suspend fun stopCast() {
        try {
            DLNACast.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        stopPolling()
        currentDevice = null
        lastDevices = emptyList()
        _state.value = CastState()
    }

    /** 1Hz 轮询渲染器播放态与进度，注入 [CastState] 供 UI 刷新 */
    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val st = DLNACast.getPlaybackState()
                    val (cur, total) = DLNACast.getProgress() ?: (0L to 0L)
                    _state.update {
                        it.copy(
                            isPlaying = st == DLNACast.PlaybackState.PLAYING,
                            playbackState = st.name,
                            progressMs = cur,
                            durationMs = total
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "cast polling failed", e)
                }
                delay(1_000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        private const val TAG = "DlnaCastManager"
    }
}

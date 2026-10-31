package com.puretone.player.playback.cast

import android.content.Context
import android.util.Log
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.ApeTranscoder
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
import java.util.concurrent.atomic.AtomicLong
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
    val error: String? = null,
    /**
     * 是否正在为投屏做准备（M2：APE 等需转码的格式，首投要等转码完成）。
     *
     * 与 [isCasting] 是两段：本字段为 true 时还没 cast 成功，UI 应显示「转码中」；
     * 转码结束（成功或失败）即置回 false。
     */
    val isPreparing: Boolean = false
)

/**
 * DLNA 投屏管理器（M1 直投 / M2 转码投送）。
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
 *   本管理器不触碰本地播放器，路由决策在 [com.puretone.player.ui.PlayerViewModel] 完成；
 * - 源文件分流（M2）见 [CastSourcePolicy] 与 [CastFilePreparer]：能直投就直投，
 *   渲不动的格式先转 WAV 再走同一条投送通道。
 */
@Singleton
class DlnaCastManager @Inject constructor(
    private val apeTranscoder: ApeTranscoder,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    /**
     * 投屏源准备（M2）：白名单格式直投，其余先转 PCM WAV。
     *
     * 转码**复用**本地播放的 [ApeTranscoder]——缓存命中（本地已经播过这首）时
     * 投屏秒开，未命中才现转；缓存 / 同曲并发去重 / LRU 淘汰三件事都已在那一层
     * 做过，这里不再重复实现。
     */
    private val preparer = CastFilePreparer { songId, sourcePath ->
        apeTranscoder.ensureWav(songId, sourcePath)
    }

    private var lastDevices: List<Device> = emptyList()
    private var currentDevice: Device? = null
    private var pollJob: Job? = null

    /**
     * 投屏代次（M2 竞态防护）。
     *
     * [castSong] 里的转码可能挂起数十秒，这期间用户完全可能点「停止投屏」、
     * 换设备或换歌。挂起点恢复后若代次已被刷新，说明本次投送已经过期，
     * 必须整段放弃——否则会出现「明明已经停止投屏，却又自己投过去」的状态回魂。
     * 用 [AtomicLong] 而非 Long：castSong 跑在 IO 协程，[stopCast] 可能来自主线程。
     */
    private val castEpoch = AtomicLong(0L)

    /** 初始化底层引擎（Application.onCreate 调用一次；可重复调用，库内部幂等替换） */
    fun init(context: Context) {
        try {
            DLNACast.init(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "DLNACast.init failed", e)
        }
    }

    /** 释放底层引擎（进程退出）；同时作废仍在转码中的投屏任务 */
    fun release() {
        castEpoch.incrementAndGet()
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
     * 投屏一首歌。
     *
     * 源文件选择（M2）：
     * - 白名单格式（mp3/flac/wav/m4a/aac/mp4）→ 按 [SongEntity.path] 绝对路径直投，
     *   由 UPnPCast 内置的 NanoHTTPD 按扩展名推 MIME 伺服（其 `LocalFileServer`
     *   已实现 Range，渲染器 seek 可用）；
     * - 其余格式（APE 为主）→ 先转 PCM WAV（复用 [ApeTranscoder] 缓存）再投同一通道。
     *   转码期间 [CastState.isPreparing] 为 true，UI 据此显示「转码中」。
     *
     * 竞态：转码是挂起点，期间用户可能停止投屏/换歌，用 [castEpoch] 判定过期后
     * 整段放弃，避免「已停止投屏却自己投过去」。
     *
     * 失败（设备拒绝/网络异常/转码失败）只回写 [CastState.error] 并返回 false，
     * 绝不抛异常。
     */
    suspend fun castSong(song: SongEntity): Boolean {
        val device = currentDevice ?: run {
            _state.update { it.copy(error = "未选择投屏设备") }
            return false
        }
        val epoch = castEpoch.incrementAndGet()
        val needsTranscode = CastSourcePolicy.requiresTranscode(song.path)

        if (needsTranscode) {
            // 先把目标设备写进状态：转码可能数十秒，UI 需要「正在为投屏转码…」，
            // 同时告知用户最终要投给哪台设备（此时 isCasting 仍 false，尚未投出去）
            _state.update {
                it.copy(
                    isPreparing = true,
                    error = null,
                    deviceName = device.name,
                    deviceAddress = device.address
                )
            }
        } else {
            _state.update { it.copy(error = null) }
        }

        val prepared = try {
            preparer.prepare(song.id, song.path)
        } finally {
            // 无论成功/失败/被取消都要撤掉「准备中」；若此刻状态已被 stopCast 整体
            // 重置，这里写 false 也无副作用
            if (needsTranscode) _state.update { it.copy(isPreparing = false) }
        }

        if (epoch != castEpoch.get()) {
            Log.d(TAG, "castSong 已过期（期间停止投屏或换了目标），放弃投送")
            return false
        }

        val target = when (prepared) {
            is CastFilePreparer.Result.Failed -> {
                _state.update { it.copy(error = prepared.reason) }
                return false
            }
            is CastFilePreparer.Result.Ready -> prepared.file
        }
        if (prepared.transcoded) {
            Log.i(TAG, "投送转码产物 ${target.name}（源：${song.path}）")
        }

        return try {
            DLNACast.castLocalFile(target.absolutePath, device, song.title)
            _state.update {
                it.copy(
                    isCasting = true,
                    isPreparing = false,
                    deviceName = device.name,
                    deviceAddress = device.address,
                    error = null
                )
            }
            startPolling()
            true
        } catch (e: Exception) {
            Log.e(TAG, "castLocalFile failed: ${target.absolutePath}", e)
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

    /**
     * 停止投屏：停止渲染器、清空状态（本地播放恢复由上层负责）。
     *
     * 首行刷新 [castEpoch]：中止所有仍在转码中的 [castSong]——它们挂起恢复后
     * 会发现代次已变并整段放弃，不会把已停止的投屏又投出去。
     */
    suspend fun stopCast() {
        castEpoch.incrementAndGet()
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

package com.puretone.player.playback.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.ApeTranscoder
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.DLNACast.Device
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
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
    val isPreparing: Boolean = false,
    /**
     * 投送代次（M3）。
     *
     * 每次 [DlnaCastManager.castSong] **开始投送时**（转码之前）自增。
     *
     * 用途：区分「渲染器自然播完这一首」与「用户手动切歌把旧曲换掉了」——
     * 后者同样会让渲染器短暂上报停止态，若不靠代次区分就会多跳一首。
     * 代次在转码**之前**自增是刻意的：APE 转码可能耗时数十秒，若等投送成功才自增，
     * 这期间渲染器早已上报停止态，判定会失准。
     */
    val pushGeneration: Long = 0L,
    /**
     * 投屏是否因设备失联而中断（M3 断网回退）。
     *
     * 与 [error] 的区别：[error] 是「上一次操作失败」（可能已自行恢复）；
     * 本字段表示「连接已不可用」，上层据此停止投屏并把播放回退到本地。
     */
    val isConnectionLost: Boolean = false,
    /**
     * 渲染器是否提供了进度信息（M3）。
     *
     * [DLNACast.getProgress] 拿不到时返回 null，此时 [progressMs]/[durationMs] 均为 0。
     * 这与「进度真的是 0」是两回事——判定「播完」时必须区分，否则会把
     * 「进度未知」误判成「渲染器播完后把进度复位为 0」。
     */
    val hasProgressInfo: Boolean = false
)

/**
 * 投送结果（M3）。
 *
 * 为什么要区分「失败」与「已过期」：断网回退只在**失败**时触发。
 * - 失败 = 渲染器上仍是旧曲（或压根没投出去），而手机端已经换曲了 →
 *   继续投屏就会出现「电视播旧歌、手机显示新歌」的状态撕裂，必须回退本地；
 * - 已过期 = 期间用户主动停了投屏或改了目标，此时本地播放已由上层恢复，
 *   再回退一次就是重复动作（甚至会在新一轮投送途中把本地也拉起来，
 *   导致手机与电视同时出声）。
 */
sealed interface CastResult {
    /** 投送成功 */
    data object Success : CastResult

    /** 投送失败（设备不可达 / 转码失败 / 渲染器拒绝） */
    data class Failed(val reason: String) : CastResult

    /** 投送已过期：期间用户停止投屏或改了目标，本次整段放弃 */
    data object Superseded : CastResult
}

/**
 * DLNA 投屏管理器（M1 直投 / M2 转码投送 / M3 队列续播与断网回退）。
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
    @ApplicationContext private val appContext: Context,
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
        CastNetworkLog.init(context.filesDir)
        try {
            DLNACast.init(context.applicationContext)
            CastNetworkLog.log("DLNACast.init ok")
        } catch (e: Exception) {
            Log.w(TAG, "DLNACast.init failed", e)
            CastNetworkLog.log("DLNACast.init failed：${e.javaClass.simpleName} ${e.message}")
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
     *
     * ## 真机发现失败的头号根因：没有持有 MulticastLock
     *
     * Android 的 Wi-Fi 驱动为省电，会**丢弃没有被应用显式「订阅」的组播包**。
     * SSDP 的 M-SEARCH 回包正是组播（239.255.255.250:1900），所以不持锁就收不到，
     * 表现就是「永远发现不到设备」——模拟器上却往往正常（模拟器网络栈与真机不同，
     * 没有这层省电过滤），这正是「模拟器能投、真机投不了」的由来。
     *
     * UPnPCast v1.3.0 库自身**没有**任何 `MulticastLock` 代码（已反编译核实），
     * 权限虽然由库的清单合并进来了（`CHANGE_WIFI_MULTICAST_STATE`），
     * 但「有权限」不等于「拿了锁」，必须由应用在搜索期间 `acquire()`。
     * 本方法在库搜索的整个窗口内持锁，搜索结束立即释放。
     *
     * 另有两处真机常见坑，一并处理：
     * - **端口 1900 被占用**：库内部 bind 失败会退化为随机端口，此时 M-SEARCH 仍会发出，
     *   但回包可能对不上；我们把实际发现数落盘，便于判断是「没发出去」还是「回不来」。
     * - **手机未连 Wi-Fi / 连的是访客网络（AP 隔离）**：这两设备天然发现不到，
     *   且用户无从判断，故把网络快照落盘到诊断报告。
     *
     * 线程：`DLNACast.search()` 是**阻塞**调用（内部等 SSDP 回包直到超时），
     * 必须放到 IO 线程，否则整个搜索期间主线程被占住 5 秒——UI 冻结、
     * 点击无响应，用户会以为 App 崩了。故此处用 `withContext(Dispatchers.IO)` 包住。
     */
    suspend fun searchDevices(timeoutMs: Long = 5000): List<CastDevice> {
        CastNetworkLog.log("=== 开始设备发现 ===")

        val lock = acquireMulticastLock()
        // 快照放在持锁**之后**：这样 multicast_lock_last 反映的是本次搜索的真实状态，
        // 而不是上一次（用户第一次打开投屏时会是 never，白白多跑一轮排查）。
        CastNetworkLog.log(CastNetworkLog.snapshot(appContext).trimEnd())
        val started = System.currentTimeMillis()
        return try {
            val raw = withContext(Dispatchers.IO) { DLNACast.search(timeout = timeoutMs) }
            val elapsed = System.currentTimeMillis() - started
            val result = raw
                .also { lastDevices = it }
                .map { CastDevice(it.id, it.name, it.address, it.isTV) }
            CastNetworkLog.logSearch("ssdp", timeoutMs, result.size, elapsed)
            CastNetworkLog.logDevices(result)
            if (result.isEmpty()) {
                CastNetworkLog.log(
                    "空结果：请确认手机与投屏设备处于同一 Wi-Fi（非访客网络）、" +
                        "手机未开启 VPN/移动数据优先、投屏设备已开机并允许被发现"
                )
            }
            result
        } catch (e: CancellationException) {
            // 协程被取消（用户关掉弹窗 / 组合离开）**不是**搜索失败：
            // 必须原样抛出，否则协程的取消信号被吞掉，父作用域会继续跑后续逻辑。
            CastNetworkLog.log("search 已取消（用户关闭弹窗）")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "DLNA search failed", e)
            CastNetworkLog.log("search 异常：${e.javaClass.simpleName} ${e.message}")
            emptyList()
        } finally {
            releaseMulticastLock(lock)
            CastNetworkLog.log("=== 设备发现结束（耗时 ${System.currentTimeMillis() - started}ms）===")
        }
    }

    /**
     * 取得组播锁（Wi-Fi 省电过滤组播包的直接对策）。
     *
     * 锁是**引用计数**的：每 acquire 一次必须对应一次 release，否则锁会一直持有、
     * 白白耗电。异常路径（拿不到锁）不能影响搜索本身——搜不到只是「没设备」，
     * 不能变成「崩溃」，故全部包在 runCatching 里。
     */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            CastNetworkLog.log("multicast_lock=unavailable（无 WifiManager）")
            return null
        }
        // 注：WifiManager.isMulticastLockSupported() 是 @hide 的非公开 API，不能用；
        // createMulticastLock 在所有 Android 设备上都可用，无需先探测支持性。
        wm.createMulticastLock(CAST_LOCK_TAG).apply {
            setReferenceCounted(true)
            acquire()
        }
    }.onFailure {
        CastNetworkLog.log("multicast_lock=error ${it.message}")
    }.getOrNull().also { lock ->
        // 把**真实**持锁结果写进诊断日志：这是判断「真机收不到组播包」的第一现场证据。
        CastNetworkLog.markLockAcquired(lock != null)
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        runCatching {
            if (lock != null && lock.isHeld) {
                lock.release()
                CastNetworkLog.log("multicast_lock=released")
            }
        }.onFailure { CastNetworkLog.log("multicast_lock=release_error ${it.message}") }
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
    suspend fun castSong(song: SongEntity): CastResult = castSong(song.id, song.title, song.path)

    /**
     * 投屏一首歌（按 id / 标题 / 源文件路径三要素）。
     *
     * 切歌场景（M1 收尾）拿到的只有队列条目 [com.puretone.player.playback.QueueEntry]，
     * 没有完整的 [SongEntity]；而投送真实需要的正是这三样：
     * - [songId] 用于查 [ApeTranscoder] 转码缓存；
     * - [path] 是转码输入 / 直投的绝对文件路径；
     * - [title] 只是渲染器上显示的曲名。
     *
     * @return [CastResult]：成功 / 失败（含原因）/ 已过期
     */
    suspend fun castSong(songId: Long, title: String, path: String): CastResult {
        val device = currentDevice ?: run {
            _state.update { it.copy(error = "未选择投屏设备") }
            return CastResult.Failed("未选择投屏设备")
        }
        val epoch = castEpoch.incrementAndGet()
        val needsTranscode = CastSourcePolicy.requiresTranscode(path)

        if (needsTranscode) {
            // 先把目标设备写进状态：转码可能数十秒，UI 需要「正在为投屏转码…」，
            // 同时告知用户最终要投给哪台设备（此时 isCasting 仍 false，尚未投出去）
            _state.update {
                it.copy(
                    isPreparing = true,
                    error = null,
                    deviceName = device.name,
                    deviceAddress = device.address,
                    // M3：投送代次在**转码之前**自增——APE 转码可能耗时数十秒，
                    // 期间渲染器随时可能上报旧曲的停止态，此时必须已经知道
                    // 「换了一首」，否则会把「手动切歌」误判成「自然播完」而多跳一首。
                    pushGeneration = epoch
                )
            }
        } else {
            _state.update { it.copy(error = null, pushGeneration = epoch) }
        }

        val prepared = try {
            preparer.prepare(songId, path)
        } finally {
            // 无论成功/失败/被取消都要撤掉「准备中」；若此刻状态已被 stopCast 整体
            // 重置，这里写 false 也无副作用
            if (needsTranscode) _state.update { it.copy(isPreparing = false) }
        }

        if (epoch != castEpoch.get()) {
            Log.d(TAG, "castSong 已过期（期间停止投屏或换了目标），放弃投送")
            return CastResult.Superseded
        }

        val target = when (prepared) {
            is CastFilePreparer.Result.Failed -> {
                _state.update { it.copy(error = prepared.reason) }
                return CastResult.Failed(prepared.reason)
            }
            is CastFilePreparer.Result.Ready -> prepared.file
        }
        if (prepared.transcoded) {
            Log.i(TAG, "投送转码产物 ${target.name}（源：$path）")
        }

        return try {
            DLNACast.castLocalFile(target.absolutePath, device, title)
            _state.update {
                it.copy(
                    isCasting = true,
                    isPreparing = false,
                    deviceName = device.name,
                    deviceAddress = device.address,
                    error = null,
                    isConnectionLost = false
                )
            }
            startPolling()
            CastResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "castLocalFile failed: ${target.absolutePath}", e)
            val reason = e.message ?: e.javaClass.simpleName
            _state.update { it.copy(error = reason) }
            CastResult.Failed(reason)
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
            var consecutiveFailures = 0
            while (isActive) {
                try {
                    val st = DLNACast.getPlaybackState()
                    val progress = DLNACast.getProgress()
                    consecutiveFailures = 0
                    _state.update {
                        it.copy(
                            isPlaying = st == DLNACast.PlaybackState.PLAYING,
                            playbackState = st.name,
                            progressMs = progress?.first ?: 0L,
                            durationMs = progress?.second ?: 0L,
                            // M3：进度拿不到（getProgress 返回 null）与「进度真的是 0」
                            // 必须分开记，否则「渲染器播完后把进度复位为 0」这一常见行为
                            // 会与「进度未知」混淆，判定「播完」时就会误判。
                            hasProgressInfo = progress != null
                        )
                    }
                    // 渲染器上报流错误：投屏已不可用，交由上层回退本地播放
                    if (st == DLNACast.PlaybackState.ERROR) {
                        markConnectionLost("投屏设备播放异常")
                        return@launch
                    }
                } catch (e: Exception) {
                    consecutiveFailures++
                    Log.w(TAG, "cast polling failed ($consecutiveFailures)", e)
                    // M3：连续多次取不到状态 → 设备大概率已离线（断网/关机/离开 Wi-Fi）。
                    // 单次失败可能是渲染器正忙，不足以判定失联。
                    if (consecutiveFailures >= CastQueuePolicy.MAX_CONSECUTIVE_POLL_FAILURES) {
                        markConnectionLost("投屏设备已断开")
                        return@launch
                    }
                }
                delay(1_000)
            }
        }
    }

    /**
     * 判定投屏连接已不可用（M3）。
     *
     * 置 [CastState.isConnectionLost] 是给上层的**唯一信号**：由它决定停投屏、
     * 切回本地播放并提示用户。此处不直接动本地播放器——投屏是附加能力，
     * 播放链路的归属权始终在 [com.puretone.player.playback.PlaybackManager] 手上。
     */
    private fun markConnectionLost(reason: String) {
        Log.w(TAG, "投屏连接失效：$reason")
        _state.update {
            it.copy(
                isCasting = false,
                isPlaying = false,
                isConnectionLost = true,
                error = reason
            )
        }
        stopPolling()
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        private const val TAG = "DlnaCastManager"

        /** 组播锁标签（系统设置里可按此查到本应用的网络使用情况） */
        private const val CAST_LOCK_TAG = "PureTone-DLNA"
    }
}

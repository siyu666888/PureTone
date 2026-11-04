package com.puretone.player.playback.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.ApeTranscoder
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.DLNACast.Device
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
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
    val hasProgressInfo: Boolean = false,
    /**
     * 投射音量（M3，0~100，默认 100 = 尚未校准）。
     *
     * 首投时由 [DlnaCastManager.syncInitialVolumeNow] 同步回读设备真实音量写入；
     * 之后只由 [DlnaCastManager.setVolume] 按用户下发值更新，设备回读**不回写**，
     * 避免量程取整误差让档位逐次漂移。换曲沿用同一会话音量，不再重设渲染器。
     */
    val castVolume: Int = 100,
    /**
     * 当前渲染器是否支持用 DLNA 调音量（null = 尚未探测）。
     *
     * 投送成功后由 [CastProbe] 读设备描述判定：没有 RenderingControl 服务的接收端
     * （部分「多屏互动」类中间层）**根本无法**用 `SetVolume` 调音量。此时 UI 必须
     * 禁用滑块并提示用遥控器——给一个拖了没反应的滑块，比不给更糟。
     */
    val volumeSupported: Boolean? = null
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
    private val castHistory: CastHistoryStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()

    /**
     * 历史投屏设备流（跨会话持久化，最近使用在前）。
     *
     * 直接透传 [CastHistoryStore.entries]，UI 自己决定怎么分组展示；
     * 本管理器只负责在投送**真正成功**后写入，不做任何展示层判断。
     */
    val history: Flow<List<CastHistoryEntry>> = castHistory.entries

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

    /** 上次成功搜到设备的时刻：连续空结果时用来判断「近期列表还能不能沿用」 */
    private var lastDevicesAt: Long = 0L

    /**
     * 历史设备地址 → 设备对象的缓存（按 IP 索引）。
     *
     * 用途：SSDP 组播首搜经常空手（电视刚唤醒时对 M-SEARCH 的响应不稳，真机上
     * 出现过连续多轮 0 设备），但一旦搜到过一次，它的 IP 就记在这里。后续再空结果时，
     * 直接对这些已知 IP 发 **unicast M-SEARCH**（点对点，绕过组播抑制），电视对单播
     * 的应答率远高于组播，往往能「唤醒」它重新出现在列表里。
     * 冷启动时由 [init] 用 [CastHistoryStore] 里「投成功过」的设备预热，因此
     * 「重装/重启后第一次搜索就空手」也能直接单播唤醒。
     * 并发：搜索线程读、投送线程与 init 协程写，必须用并发容器。
     */
    private val knownDevices = ConcurrentHashMap<String, CastDevice>()

    /** 当前投送通道：库优先，库失败后切自有通道 */
    private enum class CastEngine { LIBRARY, OWN }

    private var engine = CastEngine.LIBRARY

    /**
     * 自有投送通道（库失败后的兜底 + 音量控制）。
     *
     * 音量**始终**优先走它：库的 `setVolume` 只回一个 Boolean，既不给状态码也不给
     * fault 正文，收下请求却不执行的接收端在库里和"调成功"无法区分。
     */
    private var transport: DlnaTransport? = null

    /** 自有取流服务（仅自有通道使用；端口由系统分配） */
    private val httpServer = CastHttpServer()

    /** 已做过能力探针的设备（每台每进程一次，避免每次切歌都多打两次 SOAP） */
    private val probedDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 已确认「库通道必然被拒、只能走自有通道」的设备（按 [Device.id]）。
     *
     * 真机上极光TV 的 QQLiveTV 渲染器就是这样：库投送每次都被拒，改走自有通道立刻成功。
     * 记住结论后，同一台设备的后续投送直连自有通道——否则每首歌都要先白等一轮
     * 库的超时失败（真机实测约 2~3 秒），用户会觉得「投屏就是慢」。
     */
    private val ownOnlyDevices: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 投屏会话期间是否持有 Wi-Fi 进程绑定，以及绑定前的网络。
     *
     * **必须单点持有、单点释放**：`ConnectivityManager.bindProcessToNetwork` 是进程级
     * 全局设置，若每次音量/轮询都 bind 一次再 restore 一次，多路协程会把彼此
     * 「还原」成默认网络——真机日志里 1 秒内十几对 `wifi_net_bound/restored` 交织，
     * 轮询恰好落在被还原的窗口就会误判「投屏设备已断开」。
     */
    private var castNetHeld = false
    private var castNetPrevious: Network? = null

    /**
     * 用户最近一次请求的投射音量（0~100）。
     *
     * 滑块 `onValueChange` 每像素触发一次，一次拖动能连发十几条 `SetVolume`；
     * 直接往下发既刷爆日志又会让电视来不及执行（回读必然读到旧值）。改为
     * 「只保留最新值 + [drainVolumeRequests] 合并」，一次拖动只发一条。
     */
    private val requestedVolume = MutableStateFlow<Int?>(null)

    /** 用户本会话是否已主动调过音量：为真时不再用设备回读值覆盖 UI 初始值 */
    @Volatile
    private var volumeTouchedByUser = false

    /** 最近一次投送开始时刻：换曲窗口内渲染器短暂不应答不应判失联 */
    @Volatile
    private var lastCastStartMs = 0L

    /** 拖动投屏进度后的乐观窗口：期间不拿渲染器回读的旧值覆盖手机进度条 */
    @Volatile
    var progressHoldUntilMs = 0L
        private set

    fun noteSeekOptimistic() {
        progressHoldUntilMs = System.currentTimeMillis() + 2_500L
    }

    init {
        // 音量下发通道常驻：合并连发的请求，并对最终值做一次回读校验
        scope.launch { drainVolumeRequests() }
    }

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
        // 用历史设备预填进程内的 IP 缓存：冷启动后第一次搜索就空手时，
        // 也能对上次投过的电视发 unicast M-SEARCH，不必先「成功搜到过一次」。
        scope.launch {
            val known = runCatching { castHistory.entries.first() }.getOrDefault(emptyList())
            if (known.isEmpty()) return@launch
            for (entry in known) knownDevices[entry.address] = entry.toDevice()
            CastNetworkLog.log("cast-history 预热 knownDevices=${known.size}")
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
        httpServer.stop()
        stopPolling()
        releaseCastNetwork()
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
     * 另有三处真机常见坑，一并处理：
     * - **端口 1900 被占用**：库内部 bind 失败会退化为随机端口，此时 M-SEARCH 仍会发出，
     *   但回包可能对不上；我们把实际发现数落盘，便于判断是「没发出去」还是「回不来」。
     * - **手机未连 Wi-Fi / 连的是访客网络（AP 隔离）**：这两设备天然发现不到，
     *   且用户无从判断，故把网络快照落盘到诊断报告。
     * - **双网卡路由错乱（最隐蔽）**：UPnPCast 用 `joinGroup(组播地址, null)` 不指定网卡，
     *   组播走系统默认接口；手机同时开 Wi-Fi+移动数据时，部分厂商（小米/HyperOS 尤甚）
     *   会把默认组播接口落到蜂窝接口，M-SEARCH 出不了局域网。对策见 [bindToWifiNetwork]：
     *   搜索期间把进程默认网络 `bindProcessToNetwork(wifi)` 绑到 Wi-Fi，强制组播走 wlan0，
     *   搜索结束立即还原。
     *
     * 线程：`DLNACast.search()` 是**阻塞**调用（内部等 SSDP 回包直到超时），
     * 必须放到 IO 线程，否则整个搜索期间主线程被占住 5 秒——UI 冻结、
     * 点击无响应，用户会以为 App 崩了。故此处用 `withContext(Dispatchers.IO)` 包住。
     */
    suspend fun searchDevices(timeoutMs: Long = 5000): List<CastDevice> {
        CastNetworkLog.log("=== 开始设备发现 ===")

        val lock = acquireMulticastLock()
        // 双网卡设备上把进程默认网络绑定到 Wi-Fi：UPnPCast 用 joinGroup(组播地址, null)，
        // 不指定网卡，组播默认走系统默认接口；手机同时开 Wi-Fi+移动数据时，部分厂商会把
        // 组播路由到蜂窝接口，导致 SSDP 收不到回包、永远 0 设备。绑到 Wi-Fi 即修复。
        // 返回 true 表示本次由搜索建立的绑定，结束时由搜索自己还原（投屏会话中的绑定不动）。
        val ownsNetwork = holdCastNetwork()
        // 快照放在持锁、**绑定 Wi-Fi 之后**：multicast_lock_last 与 wifi_net_bound 都反映本次。
        CastNetworkLog.log(CastNetworkLog.snapshot(appContext).trimEnd())
        val started = System.currentTimeMillis()
        return try {
            // 主通道：自建 SSDP（显式网卡 + 三轮重传 + 组播与子网广播并行）。
            // 库那套的问题见 [SsdpDiscovery] 顶部注释——它把「必须重启 Wi-Fi 才能搜到」
            // 变成了必然：joinGroup 不指定网卡、每轮只发一次、且只走组播。
            val own = SsdpDiscovery.discover(
                wifiIp = wifiIpv4(),
                knownAddresses = knownDevices.keys,
                timeoutMs = OWN_SEARCH_TIMEOUT_MS
            )
            val result = if (own.devices.isNotEmpty()) {
                recordKnown(own.devices)
                // 自建发现的结果也要回填 lastDevices：selectDevice() 按 id 反查底层 Device，
                // 投送链路（库通道与自有通道）拿的都是这个对象。只填 CastDevice 列表会导致
                // currentDevice 为 null，投送直接报「未选择投屏设备」。
                lastDevices = own.devices.map { Device(it.id, it.name, it.address, it.isTV) }
                lastDevicesAt = System.currentTimeMillis()
                own.devices
            } else {
                // 兜底：仍然问一次库。少数设备的应答形态（例如只回 NOTIFY 不回 200 OK）
                // 可能被自建通道漏掉，库能捞到就捞；都空才退回近期列表——「设备列表突然消失」
                // 比「列表里有一台已关机的设备」更容易让人以为功能坏了。
                CastNetworkLog.log("ownssdp 0 台，回退库搜索（${timeoutMs}ms）")
                searchOnce(timeoutMs).ifEmpty { recentDevices() }
            }
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
            if (ownsNetwork) releaseCastNetwork()
            releaseMulticastLock(lock)
            CastNetworkLog.log("=== 设备发现结束（耗时 ${System.currentTimeMillis() - started}ms）===")
        }
    }

    /** 跑一轮 SSDP 搜索（阻塞调用放到 IO），并落盘耗时与设备清单 */
    private suspend fun searchOnce(timeoutMs: Long): List<CastDevice> {
        val started = System.currentTimeMillis()
        val raw = withContext(Dispatchers.IO) { DLNACast.search(timeout = timeoutMs) }
        val elapsed = System.currentTimeMillis() - started
        val result = raw.map { CastDevice(it.id, it.name, it.address, it.isTV) }
        CastNetworkLog.logSearch("ssdp", timeoutMs, result.size, elapsed)
        if (result.isNotEmpty()) {
            lastDevices = raw
            lastDevicesAt = System.currentTimeMillis()
            recordKnown(result)
        }
        return result
    }

    /**
     * 把成功发现的设备记入 [knownDevices]（按 IP 索引），供后续空结果时做 unicast 唤醒。
     * 同名/同 IP 设备以最新一次为准（电视换了端口或 friendlyName 也能更新）。
     */
    private fun recordKnown(devices: List<CastDevice>) {
        for (d in devices) {
            if (d.address.isNotBlank()) knownDevices[d.address] = d
        }
    }

    /**
     * 沿用近期的设备列表（仅在连续搜索为空时兜底）。
     *
     * 上限 [RECENT_DEVICE_TTL_MS]：超过就当作真的没有设备，不能拿十分钟前的
     * 幽灵设备骗用户去点一个必然失败的投送。
     */
    private fun recentDevices(): List<CastDevice> {
        val age = System.currentTimeMillis() - lastDevicesAt
        if (lastDevices.isEmpty() || age > RECENT_DEVICE_TTL_MS) return emptyList()
        CastNetworkLog.log("search 沿用 ${age / 1000}s 前的设备列表（count=${lastDevices.size}）")
        return lastDevices.map { CastDevice(it.id, it.name, it.address, it.isTV) }
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

    /**
     * 把进程在搜索期间绑定到 Wi-Fi 网络（双网卡设备的 SSDP 修复核心）。
     *
     * ## 根因
     * UPnPCast v1.3.0 的 `SsdpDeviceDiscovery.initializeSocket()` 调用
     * `MulticastSocket.joinGroup(组播地址, null)`——**不指定网卡**，组播走系统默认接口。
     * 手机同时开着 Wi-Fi 与移动数据时（常见），部分厂商（小米/HyperOS 尤甚）的默认组播
     * 接口会落到蜂窝接口，M-SEARCH 出不了局域网，表现就是 MulticastLock 拿了、权限有了、
     * Wi-Fi 也连了，却永远 0 设备。
     *
     * 应用层无法改库内部套接字（它没暴露网卡/网络绑定参数），唯一杠杆是
     * `ConnectivityManager.bindProcessToNetwork(wifi)`：把进程默认网络切成 Wi-Fi，
     * 库的 `joinGroup(null)` 就会顺着 wlan0 走（UPnP/SSDP 双网卡收不到的标准 workaround）。
     * 搜索结束由 [restoreNetwork] 立即还原，不长期劫持 App 网络。
     *
     * @return 调用前应还原的网络（null=系统默认）；无法绑定时返回 null 由调用方无操作还原
     */
    private fun bindToWifiNetwork(): Network? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val wifi = findWifiNetwork(cm) ?: run {
            CastNetworkLog.log("wifi_net=not_found（未找到可用 Wi-Fi 网络，跳过进程绑定）")
            return null
        }
        val previous = cm.boundNetworkForProcess
        val ok = runCatching { cm.bindProcessToNetwork(wifi) }.getOrDefault(false)
        val ip = wifiIp(cm, wifi)
        CastNetworkLog.markWifiBound(ok, ip)
        CastNetworkLog.log("wifi_net_bound=$ok ip=${ip ?: "?"}")
        return previous
    }

    /**
     * 取得（或复用）**投屏会话级**的 Wi-Fi 进程绑定。
     *
     * 与「每次调用 bind 完再 restore」的区别很重要：`bindProcessToNetwork` 是进程级
     * 全局状态，多路并发（轮询 + 音量 + 探针）各自 restore 会把别人的绑定也撤掉。
     * 这里改为引用式：谁先来谁真正绑定，其余调用直接复用；只在
     * [releaseCastNetwork]（停止投屏 / 投送失败 / 释放）时统一还原一次。
     *
     * @return true = 本次调用真正建立了绑定，调用方负责在结束时释放
     */
    private fun holdCastNetwork(): Boolean {
        if (castNetHeld) {
            CastNetworkLog.log("wifi_net_bound=reused（投屏会话已持有）")
            return false
        }
        castNetPrevious = bindToWifiNetwork()
        castNetHeld = true
        return true
    }

    /** 还原投屏会话的进程网络（幂等：未持有时无操作，不会误撤别人的绑定） */
    private fun releaseCastNetwork() {
        if (!castNetHeld) return
        restoreNetwork(castNetPrevious)
        castNetHeld = false
        castNetPrevious = null
    }

    /** 还原进程默认网络（搜索结束后调用，避免长期劫持 App 联网） */
    private fun restoreNetwork(previous: Network?) {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.bindProcessToNetwork(previous) }
        CastNetworkLog.log("wifi_net_restored prev=${if (previous == null) "default" else "bound"}")
    }

    /** 取一个「已连接且能上网」的 Wi-Fi 网络；优先当前 active 网络（通常就是 Wi-Fi） */
    private fun findWifiNetwork(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) return active
        return cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.let { caps ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
    }

    /** 取某网络的 IPv4 地址（用于诊断与确认走的是 Wi-Fi 子网） */
    private fun wifiIp(cm: ConnectivityManager, net: Network): String? = runCatching {
        cm.getLinkProperties(net)?.linkAddresses
            ?.firstOrNull { la -> la.address is Inet4Address }
            ?.address?.hostAddress
    }.getOrNull()

    /** 选定投屏设备（按 id 反查底层 [Device]；查不到就按 UI 传来的字段现造一个） */
    fun selectDevice(device: CastDevice) {
        currentDevice = lastDevices.firstOrNull { it.id == device.id }
            ?: Device(device.id, device.name, device.address, device.isTV)
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
        lastCastStartMs = System.currentTimeMillis()
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
                    pushGeneration = epoch,
                    // 换曲瞬间清掉上一首的进度：否则下一次 1Hz 轮询到来前，
                    // 手机进度条与歌词会短暂挂在新曲名下显示旧曲的时间。
                    progressMs = 0L,
                    durationMs = 0L,
                    hasProgressInfo = false
                )
            }
        } else {
            _state.update {
                it.copy(
                    error = null,
                    pushGeneration = epoch,
                    progressMs = 0L,
                    durationMs = 0L,
                    hasProgressInfo = false
                )
            }
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

        // 按**实际被投送的文件**算 MIME / UPnP class 并显式覆盖：
        // 库按 URL 扩展名推断，而本地投送 URL（…/file/<token>）无扩展名，会一律落到
        // video/mp4（音频被当成视频）→ 电视 SOAP 拒绝 → 本次投送失败 → 回退本地。
        // 详见 [CastSourcePolicy.mimeTypeOf] 的注释。
        val mimeType = CastSourcePolicy.mimeTypeOf(target.name)
        val upnpClass = CastSourcePolicy.upnpClassOf(mimeType)
        CastNetworkLog.log(
            "cast-attempt device=${device.name} addr=${device.address} " +
                "file=${target.name} size=${target.length()} mime=$mimeType " +
                "upnp=$upnpClass transcoded=${prepared.transcoded}"
        )
        CastNetworkLog.log("cast-ip-candidates=${CastNetworkLog.ipCandidates()}")

        // 双网卡设备上把进程绑到 Wi-Fi：SOAP 控制请求与取流都必须是 wlan0，否则手机
        // 默认网落到蜂窝时，同一局域网的电视也够不着（与设备发现同理）。
        // 绑定是**会话级**的：投送成功后就一直持到 stopCast（取流服务与轮询都要用），
        // 只有本次投送失败才立刻还原，不长期劫持 App 联网。
        holdCastNetwork()
        var casted = false
        return try {
            val failure = castByPreferredEngine(device, target, title, mimeType, upnpClass)
            if (failure == null) {
                casted = true
                CastResult.Success
            } else {
                _state.update { it.copy(error = failure) }
                CastResult.Failed(failure)
            }
        } finally {
            if (!casted) releaseCastNetwork()
        }
    }

    /**
     * 按设备的通道偏好投送：优先用库，库被拒过的设备直接用自有通道。
     *
     * @return null=成功；否则为最后一次失败原因
     */
    private suspend fun castByPreferredEngine(
        device: Device,
        target: File,
        title: String,
        mimeType: String,
        upnpClass: String
    ): String? {
        // 已确认库通道必然被拒的设备（真机上的 QQLiveTV 渲染器），后续投送直连自有通道：
        // 否则每首歌都要先白等一轮库的超时失败，用户会觉得「投屏就是慢」。
        if (ownOnlyDevices.contains(device.id)) {
            CastNetworkLog.log("cast-engine 该设备已确认库通道被拒，直接走自有通道")
            val ownFailure = castViaOwn(device, target, title, mimeType, upnpClass)
            if (ownFailure == null) return null
            CastNetworkLog.log("cast-engine 自有通道也不再成功，回退库通道重试")
            return castViaLibrary(device, target, title, mimeType, upnpClass)
        }

        val libraryFailure = castViaLibrary(device, target, title, mimeType, upnpClass)
        if (libraryFailure == null) return null

        // 库只抛「Device rejected the cast request」、不带状态码与 cause，单看这句话
        // 无法区分「控制地址错 / SOAP 被拒 / 格式不支持」。失败后先异步补一次只读取证
        // （不阻塞），再换自有通道重试一次。
        launchProbe(device)
        val ownFailure = castViaOwn(device, target, title, mimeType, upnpClass)
        if (ownFailure == null) {
            ownOnlyDevices.add(device.id)
            return null
        }
        CastNetworkLog.log("cast-fail 自有通道同样失败：$ownFailure")
        return libraryFailure
    }

    /** 库通道投送（成功即收尾）。@return null=成功；否则失败原因 */
    private suspend fun castViaLibrary(
        device: Device,
        target: File,
        title: String,
        mimeType: String,
        upnpClass: String
    ): String? {
        val error = runCatching {
            DLNACast.castLocalFile(
                target.absolutePath,
                device,
                title,
                CastOptions(mimeType = mimeType, upnpClass = upnpClass)
            )
        }.exceptionOrNull()
        if (error == null) {
            engine = CastEngine.LIBRARY
            transport = null
            syncInitialVolumeNow()
            onCastSucceeded(device, mimeType, "library")
            if (probedDevices.add(device.id)) launchProbe(device)
            return null
        }
        CastNetworkLog.log(
            "cast-fail type=${error.javaClass.simpleName} msg=${error.message} " +
                "cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message}"
        )
        return error.message ?: error.javaClass.simpleName
    }

    /** 自有通道投送（成功即收尾）。@return null=成功；否则失败原因 */
    private suspend fun castViaOwn(
        device: Device,
        target: File,
        title: String,
        mimeType: String,
        upnpClass: String
    ): String? {
        if (!tryOwnCast(device, target, title, mimeType, upnpClass)) return "自有通道投送失败"
        engine = CastEngine.OWN
        syncInitialVolumeNow()
        onCastSucceeded(device, mimeType, "own")
        return null
    }

    /** 投送成功后的统一收尾：状态、轮询、日志 */
    private fun onCastSucceeded(device: Device, mimeType: String, engineName: String) {
        _state.update {
            it.copy(
                isCasting = true,
                isPreparing = false,
                deviceName = device.name,
                deviceAddress = device.address,
                error = null,
                isConnectionLost = false,
                progressMs = 0L,
                durationMs = 0L,
                hasProgressInfo = false
            )
        }
        startPolling()
        CastNetworkLog.log("cast-ok device=${device.name} mime=$mimeType engine=$engineName")
        val castDevice = CastDevice(device.id, device.name, device.address, device.isTV)
        recordKnown(listOf(castDevice))
        // 投**成功**才写历史：失败也记的话，历史里会堆满「点了必然失败」的幽灵设备。
        // 异步写：DataStore 落盘不该挡住投送成功的返回路径（UI 正在等这一下）。
        scope.launch { castHistory.record(castDevice) }
    }

    /**
     * 库投送失败后的兜底通道：自有 HTTP 取流服务 + 自有 AVTransport。
     *
     * 之所以要重走一遍而不是"换个参数再让库试"：库把取流服务、控制地址、SOAP 报文
     * 全封在内部，且失败信息只剩一句无 cause 的 `DeviceError`。真机上极光TV 的
     * 控制端点本身是通的（`GetTransportInfo` 返回 200），所以问题只可能落在
     * 「报文的 protocolInfo」或「取流地址」——这两件事在库里都改不了，必须自己发。
     *
     * @return true=已成功让渲染器开始播放
     */
    private suspend fun tryOwnCast(
        device: Device,
        target: File,
        title: String,
        mimeType: String,
        upnpClass: String
    ): Boolean = withContext(Dispatchers.IO) {
        // 取流地址必须是**局域网** IPv4：库取「枚举顺序第一个非环回地址」，双网卡时
        // 可能落到蜂窝内网（10.x），电视永远拉不到——这是本次要绕开的头号坑。
        val host = wifiIpv4()
        if (host == null) {
            CastNetworkLog.log("own-cast 跳过：取不到 Wi-Fi IPv4（渲染器不可能拉到流）")
            return@withContext false
        }
        val mediaUrl = httpServer.urlFor(target, mimeType, host) ?: return@withContext false
        CastNetworkLog.log("own-cast url=$mediaUrl")

        val client = DlnaTransport(device.id, device.name)
        if (client.endpoints() == null) {
            CastNetworkLog.log("own-cast 端点解析失败，放弃")
            return@withContext false
        }
        transport = client
        val accepted = client.setAvTransportUri(mediaUrl, title, mimeType, upnpClass)
        if (!accepted) {
            CastNetworkLog.log("own-cast SetAVTransportURI 全部候选被拒")
            transport = null
            return@withContext false
        }
        val played = client.play()
        CastNetworkLog.log("own-cast play=$played")
        if (!played) transport = null
        played
    }

    /** 取 Wi-Fi 网卡上的 IPv4（不能用网卡枚举顺序，理由见 [tryOwnCast]） */
    private fun wifiIpv4(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val net = findWifiNetwork(cm) ?: return null
        return wifiIp(cm, net)
    }

    /**
     * 投送链路内**同步**回读真实音量，必须在 `isCasting=true` 之前完成：否则 MediaSession
     * 建 `VolumeProviderCompat` 时读到的仍是假 100，按一下音量键就把电视从 15 拉到 100。
     * 库通道的 `DLNACast.getVolume()` 返回值量纲不明，不做同步校准，交给 [CastProbe] 折算。
     */
    private suspend fun syncInitialVolumeNow() {
        val client = transport ?: return
        val actual = try {
            client.volume()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "initial volume read failed", e)
            null
        }
        if (actual != null) {
            // GetVolume 成功 = RenderingControl 确实可用，是能力事实而非探测冒充。
            // 必须在路由切换前落定，否则 remoteVolume 会在 null/true 间翻转。
            _state.update {
                if (CastVolumePolicy.shouldSetSupport(it.volumeSupported)) {
                    it.copy(volumeSupported = true)
                } else {
                    it
                }
            }
            applyInitialVolume(actual)
            CastNetworkLog.log("cast-vol init actual=$actual")
        }
    }

    /** 同步设备真实音量；用户已调过或已校准过就不再覆盖（防止多个来源互相冲掉） */
    private fun applyInitialVolume(volume: Int?) {
        if (volume == null) return
        if (!CastVolumePolicy.shouldApplyInitialVolume(volumeTouchedByUser, _state.value.castVolume)) return
        _state.update { it.copy(castVolume = volume.coerceIn(0, 100)) }
    }

    /**
     * 投送后异步取证：问电视「控制地址在哪、状态码是什么、支持哪些格式、能不能调音量」。
     *
     * 刻意放在协程里而不是在 [castSong] 里同步等：探针最多要跑三次 HTTP，
     * 同步跑会把「回退到本地播放」推迟数秒——用户点投屏后最不能忍的就是这个。
     * 探针期间自己绑 Wi-Fi 再还原，避免默认网络落到蜂窝时取证也失败。
     *
     * 副作用：把 [CastState.volumeSupported] 落到真实能力值（仅当描述取到才写，
     * 取不到时保持 null=未知，不能用「探测失败」冒充「不支持」）。
     */
    private fun launchProbe(device: Device) {
        scope.launch {
            holdCastNetwork()
            try {
                val report = CastProbe.inspect(device.id, device.name)
                if (report.descriptionOk) {
                    // 能力判定只写一次：反复改写会让 remoteVolume 翻转、Media3 重建
                    // VolumeProvider，音量键在远程/本地两套档位间来回跳。
                    if (CastVolumePolicy.shouldSetSupport(_state.value.volumeSupported)) {
                        _state.update { it.copy(volumeSupported = report.hasRenderingControl) }
                    }
                    // 库通道没有自己的 GetVolume 回读，用探针折算后的音量补校准初值
                    applyInitialVolume(report.currentVolume)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cast probe failed", e)
            }
        }
    }

    /** 音量下发通道（常驻）：合并连发请求，只对最终值发一次 SOAP。
     *  [collectLatest] 刻意用于取消上一轮，避免滑块拖动时十几个中间值刷屏。 */
    private suspend fun drainVolumeRequests() {
        requestedVolume.filterNotNull().collectLatest { target ->
            delay(VOLUME_COALESCE_MS)
            // stopCast 已把 isCasting 置 false：不能让延迟中的请求在会话结束后还写状态
            if (!_state.value.isCasting) return@collectLatest
            val client = transport
            val accepted = if (client != null) {
                client.setVolume(target) == true
            } else {
                runCatching { DLNACast.setVolume(target) }.getOrDefault(false)
            }
            if (accepted) _state.update { it.copy(castVolume = target) }
            if (client == null) return@collectLatest
            delay(VOLUME_SETTLE_MS)
            if (!_state.value.isCasting) return@collectLatest
            verifyVolume(client, target, accepted)
        }
    }

    /** 回读只做取证：`SetVolume` 返回 200 不代表电视真变了。
     *  绝不把回读值写回 castVolume——量程折算的 ±2 误差会成为下次按键的基准，
     *  连按几次就一路漂移；也不改 volumeSupported，避免 Media3 反复重建 VolumeProvider。 */
    private suspend fun verifyVolume(client: DlnaTransport, requested: Int, accepted: Boolean) {
        try {
            val actual = client.volume()
            CastNetworkLog.log("own-vol readback req=$requested actual=${actual ?: "none"} accepted=$accepted")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "volume verify failed", e)
        }
    }

    /** 按当前通道分派控制动作：自有通道可用时优先，否则回落库 */
    private suspend fun dispatch(
        action: String,
        own: suspend (DlnaTransport) -> Boolean,
        library: suspend () -> Boolean
    ): Boolean = try {
        val client = transport
        // Wi-Fi 绑定已由投屏会话持有（见 [holdCastNetwork]），这里不再自行 bind/restore：
        // 每次调用都还原一次会把并发协程的绑定互相撤掉。
        if (engine == CastEngine.OWN && client != null) own(client) else library()
    } catch (e: Exception) {
        Log.w(TAG, "$action failed", e)
        false
    }

    suspend fun play(): Boolean = dispatch("play", { it.play() }, { DLNACast.play() })

    suspend fun pause(): Boolean = dispatch("pause", { it.pause() }, { DLNACast.pause() })

    suspend fun stop(): Boolean = dispatch("stop", { it.stop() }, { DLNACast.stop() })

    suspend fun seek(positionMs: Long): Boolean =
        dispatch("seek", { it.seek(positionMs) }, { DLNACast.seek(positionMs) })

    /** 受理一次音量请求：值交给 [drainVolumeRequests] 合并下发，不在这里同步发 SOAP。
     *  [adjustVolume] 以「最近一次请求值」而非滞后的状态值为基准，连按音量键才不丢档。 */
    fun setVolume(volume: Int): Boolean {
        val target = volume.coerceIn(0, 100)
        CastNetworkLog.log("cast-vol req=$target")
        volumeTouchedByUser = true
        requestedVolume.value = target
        return true
    }

    /** 相对调节（音量键 +/-）：以最后一次请求值为基准，避免连按时读到旧档位 */
    fun adjustVolume(delta: Int): Boolean = setVolume(
        CastVolumePolicy.resolveRelative(requestedVolume.value, _state.value.castVolume, delta)
    )

    /**
     * 停止投屏：停止渲染器、清空状态（本地播放恢复由上层负责）。
     *
     * 首行刷新 [castEpoch]：中止所有仍在转码中的 [castSong]——它们挂起恢复后
     * 会发现代次已变并整段放弃，不会把已停止的投屏又投出去。
     */
    suspend fun stopCast() {
        castEpoch.incrementAndGet()
        val client = transport
        try {
            if (engine == CastEngine.OWN && client != null) client.stop() else DLNACast.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        stopPolling()
        httpServer.stop()
        // 会话结束：撤掉进程级 Wi-Fi 绑定，避免 App 的其它联网长期被限制在局域网
        releaseCastNetwork()
        requestedVolume.value = null
        volumeTouchedByUser = false
        progressHoldUntilMs = 0L
        transport = null
        engine = CastEngine.LIBRARY
        currentDevice = null
        lastDevices = emptyList()
        lastDevicesAt = 0L
        _state.value = CastState()
    }

    /** 1Hz 轮询渲染器播放态与进度，注入 [CastState] 供 UI 刷新 */
    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            if (engine == CastEngine.OWN) pollOwnTransport() else pollLibraryTransport()
        }
    }

    /**
     * 自有通道的状态轮询（与库轮询语义一致：连续失败达上限才判失联）。
     *
     * 库的 `getPlaybackState()` 经 `mapTransportState` **永不返回 ERROR**（异常也吞成
     * IDLE），所以「断连」只能靠连续失败计数判定——这里沿用同一标准。
     */
    private suspend fun pollOwnTransport() {
        val client = transport ?: return
        var consecutiveFailures = 0
        // 只记状态「变化」：1Hz 轮询如果每次都写一行，诊断文件会被刷爆
        var lastState: String? = null
        var lastPositionAvailable: Boolean? = null
        while (consecutiveFailures < CastQueuePolicy.MAX_CONSECUTIVE_POLL_FAILURES) {
            val state = client.transportState()
            if (state == null) {
                // 换曲窗口内渲染器在 SetAVTransportURI/Play 之间常短暂无应答，
                // 此时计失败会在 3 秒后误判失联、把投屏整个退回本地。
                if (System.currentTimeMillis() - lastCastStartMs > 12_000L) consecutiveFailures++
            } else {
                consecutiveFailures = 0
                val position = client.position()
                val posAvailable = position != null
                val mapped = DlnaTransport.stateName(state)
                // 进度不可得正是「播完」判定的关键证据：仅在变化时落一行，避免 1Hz 刷屏
                if (posAvailable != lastPositionAvailable) {
                    CastNetworkLog.log("own-pos available=$posAvailable state=$state")
                    lastPositionAvailable = posAvailable
                }
                // 落盘状态迁移：这是「电视到底播没播」唯一的一手证据，
                // 用户报「没声音」时先看这一行有没有 PLAYING。
                if (mapped != lastState) {
                    CastNetworkLog.log("own-state $mapped（原始 $state）")
                    lastState = mapped
                }
                _state.update {
                    it.copy(
                        isPlaying = state.equals("PLAYING", ignoreCase = true),
                        playbackState = mapped,
                        progressMs = position?.first ?: 0L,
                        durationMs = position?.second ?: 0L,
                        hasProgressInfo = position != null
                    )
                }
            }
            delay(1_000)
        }
        markConnectionLost("投屏设备已断开")
    }

    /** 库通道的状态轮询（原实现，保持不变） */
    private suspend fun pollLibraryTransport() {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
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
                    return
                }
            } catch (e: Exception) {
                consecutiveFailures++
                Log.w(TAG, "cast polling failed ($consecutiveFailures)", e)
                // M3：连续多次取不到状态 → 设备大概率已离线（断网/关机/离开 Wi-Fi）。
                // 单次失败可能是渲染器正忙，不足以判定失联。
                if (consecutiveFailures >= CastQueuePolicy.MAX_CONSECUTIVE_POLL_FAILURES) {
                    markConnectionLost("投屏设备已断开")
                    return
                }
            }
            delay(1_000)
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
        CastNetworkLog.log("cast-conn-lost reason=$reason")
        _state.update {
            it.copy(
                isCasting = false,
                isPlaying = false,
                isConnectionLost = true,
                error = reason
            )
        }
        // 设备已离线：取流服务与会话级网络绑定都没有意义了，一并释放，
        // 避免 App 其它联网被长期限制在一个已断开的 Wi-Fi 上。
        stopPolling()
        httpServer.stop()
        releaseCastNetwork()
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        private const val TAG = "DlnaCastManager"

        /** 组播锁标签（系统设置里可按此查到本应用的网络使用情况） */
        private const val CAST_LOCK_TAG = "PureTone-DLNA"

        /**
         * 自建 SSDP 发现的总预算。
         *
         * 内部按 700ms 一轮发三轮（组播 + 子网广播 × 3 个 ST），有命中就早退；
         * 3.5s 足够覆盖最坏的重传，又不至于让用户对着转圈干等。
         */
        private const val OWN_SEARCH_TIMEOUT_MS = 3500L

        /** 「近期设备列表」的有效期：超过就当作真的没有设备，不能用幽灵设备误导投送 */
        private const val RECENT_DEVICE_TTL_MS = 10 * 60 * 1000L

        /**
         * 音量合并窗口：滑块一次拖动会连发十几条 setVolume，等这么久让后续值
         * 覆盖前面的，只对最终值发一次 SOAP。
         */
        private const val VOLUME_COALESCE_MS = 120L

        /** 下发后到回读取证之间的等待：渲染器执行音量是异步的，立刻读只会读到旧值 */
        private const val VOLUME_SETTLE_MS = 700L
    }
}

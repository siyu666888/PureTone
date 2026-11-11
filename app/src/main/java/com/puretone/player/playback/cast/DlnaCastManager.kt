package com.puretone.player.playback.cast

import android.content.Context
import android.util.Log
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.ApeTranscoder
import com.puretone.player.playback.CastSessionCastPort
import com.yinnho.upnpcast.CastOptions
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.DLNACast.Device
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DLNA 投屏管理器（M1 直投 / M2 转码投送 / M3 队列续播与断网回退）。
 *
 * 本类只负责**投屏状态机**：投送编排、播放控制、音量、1Hz 轮询。四块自成一体的
 * 支撑逻辑已拆到同包的独立文件，避免本文件继续膨胀：
 * - [CastModels.kt] 里的 [CastDevice] / [CastState] / [CastResult]（对外数据契约）；
 * - [CastDeviceDiscovery]（SSDP 发现、已知设备缓存、底层 Device 反查）；
 * - [CastNetworkBridge]（组播锁、Wi-Fi 进程绑定、本机 IPv4）；
 * - [CastVolumeController]（音量合并下发、初值回读、能力探针与回读取证）。
 *
 * 封装 UPnPCast 单例门面 [DLNACast]：本地文件直投（库内置 HTTP 服务经 NanoHTTPD
 * 按绝对路径伺服）、播放控制与状态轮询。对外只暴露协程安全的 [CastDevice]/[CastState]，
 * 不泄漏第三方类型。
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
) : CastSessionCastPort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CastState())
    override val state: StateFlow<CastState> = _state.asStateFlow()

    /**
     * 历史投屏设备流（跨会话持久化，最近使用在前）。
     *
     * 直接透传 [CastHistoryStore.entries]，UI 自己决定怎么分组展示；
     * 本管理器只负责在投送**真正成功**后写入，不做任何展示层判断。
     */
    val history: Flow<List<CastHistoryEntry>> = castHistory.entries

    /** 网络底层（组播锁 / Wi-Fi 进程绑定 / IPv4），投屏会话全程共用一份状态 */
    private val net = CastNetworkBridge(appContext)

    /** 音量控制协作者：合并下发、初值回读、能力探针与回读取证，见 [CastVolumeController] */
    private val volumeController = CastVolumeController(
        state = _state,
        net = net,
        scope = scope,
        transportProvider = { transport },
    )

    /** 设备发现与反查缓存（含历史预热），投屏会话全程共用一份缓存 */
    private val discovery = CastDeviceDiscovery(appContext, castHistory)

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

    private var currentDevice: Device? = null
    private var pollJob: Job? = null

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

    /** 最近一次投送开始时刻：换曲窗口内渲染器短暂不应答不应判失联 */
    @Volatile
    private var lastCastStartMs = 0L

    /**
     * 本轮投送**实际完成**时刻（`onCastSucceeded`），写进 [CastState.castStartMs]。
     *
     * 与 [lastCastStartMs]（转码之前就记）分开：曲中/曲末判据要的是「电视从这一刻
     * 开始播」，APE 转码几十秒不能算进已播时长，否则曲中停止会被误判成曲末。
     */
    @Volatile
    private var lastCastSentMs = 0L

    /** 自有通道本轮投送参数：「电视始终没来取流」时据此重发一次，不重走转码 */
    private var lastOwnCast: OwnCastParams? = null

    /** 本轮建立确认已重发次数 / 是否已提示过，避免无限重试与 repeated 弹提示 */
    private var establishRetries = 0
    private var establishWarned = false

    /** 最近一次建立重发的墙钟：重发后同样要留一个宽限，不能下一秒就判「未响应」 */
    private var establishRetryAtMs = 0L

    /** 取流空窗诊断（R7 / 054751）：logger 注入，判据在纯 JVM 单测里锁死 */
    private val fetchIdleLog = CastFetchIdleLog { CastNetworkLog.log(it) }

    /**
     * 「下一次投送是曲末自动推进」的待生效标志。
     *
     * [CastSessionController.onCastTrackEnded] 在确认推进、调 [next] 之前调
     * [markAutoAdvanceNext] 置位；[castSong] 开头读取后立即清除并写入
     * [CastState.autoAdvance]。用普通布尔即可——投送在单协程里串行发起，且清除动作
     * 与读取同处 [castSong] 开头，即使残留也只会让下一次投送多走一次宽限判断，
     * 是「宁可多等也不打断」的安全降级。
     */
    @Volatile
    private var autoAdvancePending = false

    private data class OwnCastParams(
        val url: String,
        val title: String,
        val mimeType: String,
        val upnpClass: String
    )

    /** 拖动投屏进度后的乐观窗口：期间不拿渲染器回读的旧值覆盖手机进度条 */
    @Volatile
    override var progressHoldUntilMs = 0L
        private set

    override fun noteSeekOptimistic() {
        progressHoldUntilMs = System.currentTimeMillis() + 2_500L
    }

    override fun markAutoAdvanceNext() {
        autoAdvancePending = true
    }

    /**
     * 本地刚下发的 Play/Pause 期望态，以及忽略渲染器过渡帧的截止时刻。
     *
     * 1Hz 轮询比命令滞后 1~2 帧：`pause()` 已把 uiState 写成暂停，下一帧轮询却还报
     * `PLAYING`，`CastSessionController` 会立刻把 uiState 覆盖回去；反之 `play()` 后
     * 渲染器还停在 `PAUSED`，UI 就闪一下「已暂停」。宽限期内一律以期望态为准，
     * 详见 [CastPlayStatePolicy]。
     */
    @Volatile
    private var expectedPlaying: Boolean? = null

    @Volatile
    private var playStateGuardUntilMs = 0L

    private fun notePlayStateCommand(expected: Boolean) {
        val nowMs = System.currentTimeMillis()
        // 同一期望态连点不续期：否则通知栏连点 Pause 会把宽限无限后推，
        // CastState.isPlaying 被永久钉在期望态，UI 取反基准锁死（184914 现象③）。
        playStateGuardUntilMs = CastPlayStatePolicy.guardUntilMs(
            previousExpected = expectedPlaying,
            expected = expected,
            previousGuardUntilMs = playStateGuardUntilMs,
            nowMs = nowMs
        )
        expectedPlaying = expected
    }

    private fun clearPlayStateCommand() {
        expectedPlaying = null
        playStateGuardUntilMs = 0L
    }

    init {
        // 音量下发通道常驻：合并连发的请求，并对最终值做一次回读校验
        volumeController.start()
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
        // 启动期落盘构建身份：cast_network.txt 里也能直接看出装的是哪枚包。
        // 诊断导出另有 CastNetworkLog.withIdentity() 兜底，不受 120 行环形上限影响。
        CastNetworkLog.log(CastNetworkLog.IDENTITY_LINE)
        // P2-3：把目标校验诊断接到真实落盘通道。CastTargetDiagnostics 默认是 noop，
        // 所以 JVM 单测里那些被直接调用的纯函数不会踩 Android Log stub。
        CastTargetDiagnostics.install(CastNetworkLog::log)
        try {
            DLNACast.init(context.applicationContext)
            CastNetworkLog.log("DLNACast.init ok")
        } catch (e: Exception) {
            Log.w(TAG, "DLNACast.init failed", e)
            CastNetworkLog.log("DLNACast.init failed：${e.javaClass.simpleName} ${e.message}")
        }
        // 用历史设备预填进程内的 IP 缓存：冷启动后第一次搜索就空手时，
        // 也能对上次投过的电视发 unicast M-SEARCH，不必先「成功搜到过一次」。
        scope.launch { discovery.warmUp() }
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
        net.endCastSession()
    }

    /**
     * SSDP 设备发现，返回局域网内渲染器列表（已映射为 [CastDevice]）。
     *
     * 实现与真机踩坑说明（组播锁、双网卡绑定、三轮重传、unicast 唤醒、按 IP 去重）
     * 全部在 [CastDeviceDiscovery.search]；本方法保持公开签名不变，只做委托。
     */
    suspend fun searchDevices(timeoutMs: Long = 5000): List<CastDevice> =
        discovery.search(net, timeoutMs)

    /** 选定投屏设备（按 id 反查底层 [Device]；查不到就按 UI 传来的字段现造一个） */
    override fun selectDevice(device: CastDevice) {
        currentDevice = discovery.select(device)
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
    override suspend fun castSong(song: SongEntity): CastResult = castSong(song.id, song.title, song.path)

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
        // 曲末自动推进标志：读取即清除，写入本轮 [CastState.autoAdvance]。
        // 必须在 [lastCastStartMs] 之后、状态更新之前取走，保证标志只作用于这一轮投送。
        val autoAdvance = autoAdvancePending.also { autoAdvancePending = false }
        // 这里**不能**登记「期望在播」：此刻新曲还没投出去，旧轮询读到的仍是上一首的
        // STOPPED / PAUSED。命令宽限会把这些旧曲帧伪装成「新曲正在播」，令
        // CastQueueCoordinator.playingSeen 提前置位；宽限一过又读到 STOPPED，就会被
        // CastQueuePolicy 判成曲末而凭空多跳一首（慢速换曲 / APE 转码时可能连锁跳）。
        // 起播缓冲的一帧 PAUSED 由 CastPlayStatePolicy 的 2s 去抖过滤，不需要再借命令
        // 宽限；STOPPED 则必须立即采信，那是曲末推进的唯一信号。详见 onCastSucceeded。
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
                    hasProgressInfo = false,
                    autoAdvance = autoAdvance
                )
            }
        } else {
            _state.update {
                it.copy(
                    error = null,
                    pushGeneration = epoch,
                    progressMs = 0L,
                    durationMs = 0L,
                    hasProgressInfo = false,
                    autoAdvance = autoAdvance
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
        // 投送**开始前**就让会话接管绑定与保活锁：设备发现的 finally 可能早于 cast-ok
        // 执行（用户点设备后关弹窗，搜索被取消），晚一步接管锁就已经被释放了——
        // 2026-11-06 06:56 真机「后台投屏 11 分钟后自动断开」即由此而来。
        // 成功后一直持到 stopCast（取流服务与 1Hz 轮询都要用），只有失败才立刻归还。
        net.beginCastSession()
        var casted = false
        return try {
            val failure = castByPreferredEngine(device, target, title, mimeType, upnpClass, epoch)
            if (failure == null) {
                casted = true
                CastResult.Success
            } else {
                _state.update { it.copy(error = failure) }
                CastResult.Failed(failure)
            }
        } finally {
            // 只归还「本次投送失败且仍是当前代次」的会话。投送途中 castEpoch 变化有两种：
            // stopCast()（已自行 endCastSession）与新一轮 castSong()（已重新接管会话），
            // 二者都不能在这里释放——beginCastSession/endCastSession 是布尔标记而非引用
            // 计数，旧发一句无条件 endCastSession 就会把新会话刚拿到的保活锁与 Wi-Fi
            // 绑定一起撤掉（2026-11-06 06:57 真机老坑的并发变体）。成功但已过期的那一发
            // 由 onCastSucceeded() 按 epoch 作废状态，不动网络资源。
            if (!casted && epoch == castEpoch.get()) net.endCastSession()
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
        upnpClass: String,
        epoch: Long
    ): String? {
        // 已确认库通道必然被拒的设备（真机上的 QQLiveTV 渲染器），后续投送直连自有通道：
        // 否则每首歌都要先白等一轮库的超时失败，用户会觉得「投屏就是慢」。
        if (ownOnlyDevices.contains(device.id)) {
            CastNetworkLog.log("cast-engine 该设备已确认库通道被拒，直接走自有通道")
            val ownFailure = castViaOwn(device, target, title, mimeType, upnpClass, epoch)
            if (ownFailure == null) return null
            CastNetworkLog.log("cast-engine 自有通道也不再成功，回退库通道重试")
            return castViaLibrary(device, target, title, mimeType, upnpClass, epoch)
        }

        val libraryFailure = castViaLibrary(device, target, title, mimeType, upnpClass, epoch)
        if (libraryFailure == null) return null

        // 库只抛「Device rejected the cast request」、不带状态码与 cause，单看这句话
        // 无法区分「控制地址错 / SOAP 被拒 / 格式不支持」。失败后先异步补一次只读取证
        // （不阻塞），再换自有通道重试一次。
        volumeController.launchProbe(device)
        val ownFailure = castViaOwn(device, target, title, mimeType, upnpClass, epoch)
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
        upnpClass: String,
        epoch: Long
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
            volumeController.syncInitialVolumeNow()
            onCastSucceeded(device, mimeType, "library", epoch)
            if (probedDevices.add(device.id)) volumeController.launchProbe(device)
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
        upnpClass: String,
        epoch: Long
    ): String? {
        if (!tryOwnCast(device, target, title, mimeType, upnpClass)) return "自有通道投送失败"
        engine = CastEngine.OWN
        volumeController.syncInitialVolumeNow()
        onCastSucceeded(device, mimeType, "own", epoch)
        return null
    }

    /**
     * 投送成功后的统一收尾：状态、轮询、日志。
     *
     * [epoch] 二次校验是「状态回魂」的唯一闸门：投送期间用户点「结束投屏」或又发了
     * 新一轮投送时，[castEpoch] 已被刷新；此时不能再把 `isCasting` 写回 true，否则
     * 明明已经断开，下一首投送完成又会自己「连上」（castSong 只在转码后校验过一次
     * epoch，挡不住已经进入 SOAP 投送阶段的这一发）。
     *
     * 这里也**不要**补登记 `notePlayStateCommand(true)`：命令宽限期内的 PAUSED /
     * PLAYING 都算「伪造的在播观测」，会让 CastQueueCoordinator.playingSeen 提前置位，
     * 起播慢时下一次 STOPPED 就会被判成曲末而连跳队列。起播缓冲帧交给
     * [CastPlayStatePolicy] 的 PAUSED 去抖，曲末 STOPPED 则必须立即采信。
     */
    private fun onCastSucceeded(device: Device, mimeType: String, engineName: String, epoch: Long) {
        if (epoch != castEpoch.get()) {
            CastNetworkLog.log(
                "cast-ok-superseded epoch=$epoch current=${castEpoch.get()} " +
                    "device=${device.name} engine=$engineName"
            )
            return
        }
        val nowMs = System.currentTimeMillis()
        lastCastSentMs = nowMs
        establishRetries = 0
        establishWarned = false
        establishRetryAtMs = 0L
        fetchIdleLog.reset()
        // 自有通道的取流经过本应用 HTTP 服务，必须等电视真来取流才算「已建立」；
        // 库通道用它自己的 NanoHTTPD，我们看不到取流，直通为 true（默认语义）。
        val established = engine != CastEngine.OWN
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
                hasProgressInfo = false,
                castStartMs = nowMs,
                lastFetchMs = 0L,
                isEstablished = established
            )
        }
        startPolling()
        CastNetworkLog.log(
            "cast-ok device=${device.name} mime=$mimeType engine=$engineName established=$established"
        )
        val castDevice = CastDevice(device.id, device.name, device.address, device.isTV)
        discovery.remember(castDevice)
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
        val host = net.wifiIpv4()
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
        if (played) {
            // 记下本轮参数：轮询确认「电视一直没来取流」时，用同一 URL 重发一次
            lastOwnCast = OwnCastParams(mediaUrl, title, mimeType, upnpClass)
        } else {
            transport = null
        }
        played
    }

    /** 按当前通道分派控制动作：自有通道可用时优先，否则回落库 */
    private suspend fun dispatch(
        action: String,
        own: suspend (DlnaTransport) -> Boolean,
        library: suspend () -> Boolean
    ): Boolean = try {
        val client = transport
        // Wi-Fi 绑定已由投屏会话持有（见 [CastNetworkBridge.holdCastNetwork]），这里不再自行 bind/restore：
        // 每次调用都还原一次会把并发协程的绑定互相撤掉。
        if (engine == CastEngine.OWN && client != null) own(client) else library()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // 命令异常过去只进 logcat，诊断报告里完全看不到「点播放到底发没发出去」。
        // 补这一行是 20261107_165555 那类「卡片无法控制」问题的取证前提。
        Log.w(TAG, "$action failed", e)
        CastNetworkLog.log("cast-cmd-failed action=$action ex=${e.javaClass.simpleName}")
        false
    }

    override suspend fun play(source: String): Boolean {
        notePlayStateCommand(true)
        // source 区分 App 内播放页与通知栏/锁屏卡片：184914 里两者行为不同，
        // 日志不带来源时无法判断「点播放到底走的是哪条路径」。
        CastNetworkLog.log("cast-cmd play expected=true source=$source")
        val ok = dispatch("play", { it.play() }, { DLNACast.play() })
        if (!ok) {
            clearPlayStateCommand()
            CastNetworkLog.log("cast-cmd play result=failed source=$source")
        }
        return ok
    }

    override suspend fun pause(source: String): Boolean {
        notePlayStateCommand(false)
        CastNetworkLog.log("cast-cmd pause expected=false source=$source")
        val ok = dispatch("pause", { it.pause() }, { DLNACast.pause() })
        if (!ok) {
            clearPlayStateCommand()
            CastNetworkLog.log("cast-cmd pause result=failed source=$source")
        }
        return ok
    }

    suspend fun stop(): Boolean = dispatch("stop", { it.stop() }, { DLNACast.stop() })

    override suspend fun seek(positionMs: Long): Boolean =
        dispatch("seek", { it.seek(positionMs) }, { DLNACast.seek(positionMs) })

    /** 受理一次音量请求：值交给 [CastVolumeController] 合并下发，不在这里同步发 SOAP。
     *  [adjustVolume] 以「最近一次请求值」而非滞后的状态值为基准，连按音量键才不丢档。 */
    override fun setVolume(volume: Int): Boolean = volumeController.setVolume(volume)

    /** 相对调节（音量键 +/-）：以最后一次请求值为基准，避免连按时读到旧档位 */
    override fun adjustVolume(delta: Int): Boolean = volumeController.adjustVolume(delta)

    /**
     * 停止投屏：停止渲染器、清空状态（本地播放恢复由上层负责）。
     *
     * 首行刷新 [castEpoch]：中止所有仍在转码中的 [castSong]——它们挂起恢复后
     * 会发现代次已变并整段放弃，不会把已停止的投屏又投出去。
     */
    override suspend fun stopCast() {
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
        net.endCastSession()
        volumeController.reset()
        progressHoldUntilMs = 0L
        clearPlayStateCommand()
        transport = null
        engine = CastEngine.LIBRARY
        lastCastSentMs = 0L
        lastOwnCast = null
        establishRetries = 0
        establishWarned = false
        establishRetryAtMs = 0L
        fetchIdleLog.reset()
        currentDevice = null
        discovery.reset()
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
     * 投屏「真建立」确认（B3）：SOAP 200 只说明渲染器收下了 URI，**不代表电视会来取流**。
     *
     * 真机 20261107_165555 里 `cast-ok` + `own-state PLAYING` 之后，新曲一条 `http-fetch`
     * 都没有，手机卡片却靠 uiState 外推显示 00:24——电视画面其实早已退出。
     * 自有通道的取流必经本应用 [CastHttpServer]，故以「本轮投送后当前 token 被取流」
     * 为建立信号；超时未取流就用同一 URL 重发一次 SetAVTransportURI + Play，
     * 再不行才提示用户，绝不把「收下 URI」当成「正在播放」。
     *
     * 「本轮」由 [CastHttpServer] 换 token 时清零保证，故取流时间戳早于
     * `castStartMs` 也算数：电视可能在 `Play` 返回前就来 GET（170435 的 Knock
     * 因此被旧判据丢掉、白等 15s）。确认日志同时打 `elapsed` 与 `fetchOffset`，
     * 后者为负即这次早到取流。
     *
     * 库通道取流走它自己的 NanoHTTPD，看不到取流，[CastState.isEstablished] 在
     * [onCastSucceeded] 直通为 true，本方法直接返回。
     */
    private suspend fun refreshEstablishment(nowMs: Long, fetchMs: Long, client: DlnaTransport) {
        val st = _state.value
        val elapsedMs = nowMs - st.castStartMs
        when (
            CastEstablishPolicy.resolve(
                isCasting = st.isCasting,
                isEstablished = st.isEstablished,
                castStartMs = st.castStartMs,
                lastFetchMs = fetchMs,
                nowMs = nowMs,
                retries = establishRetries,
                warned = establishWarned,
                retrySinceMs = establishRetryAtMs,
                autoAdvance = st.autoAdvance,
                rendererPlaying = st.isPlaying
            )
        ) {
            CastEstablishPolicy.Action.NONE -> Unit

            CastEstablishPolicy.Action.MARK_ESTABLISHED -> {
                _state.update { it.copy(isEstablished = true) }
                // elapsed = 投送完成到确认建立的耗时；fetchOffset = 相对 castStartMs 的
                // 取流偏移，负值即「电视在 Play 返回前就来 GET」的早到取流（170435）。
                // 旧日志把 fetchOffset 误写成 elapsed，早到取流打出负数也看不出含义。
                CastNetworkLog.log(
                    "cast-established by=fetch elapsed=${elapsedMs}ms " +
                        "fetchOffset=${fetchMs - st.castStartMs}ms"
                )
            }

            CastEstablishPolicy.Action.RETRY -> {
                establishRetries++
                establishRetryAtMs = nowMs
                CastNetworkLog.log("cast-reestablish attempt=$establishRetries elapsed=${elapsedMs}ms")
                val params = lastOwnCast ?: return
                try {
                    client.setAvTransportUri(params.url, params.title, params.mimeType, params.upnpClass)
                    client.play()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CastNetworkLog.log("cast-reestablish failed ${e.javaClass.simpleName}")
                }
            }

            CastEstablishPolicy.Action.KICK -> {
                establishRetries++
                establishRetryAtMs = nowMs
                CastNetworkLog.log("cast-establish-kick attempt=$establishRetries elapsed=${elapsedMs}ms")
                // 曲末推进超时：发 Seek 踢一脚触发电视取流。真机 20261108_064254 实测
                // 这台电视报告 PLAYING 却不主动 fetch，收到一个 Seek 就立即取流。
                // seek 到当前进度（新曲通常为 0）；重发 seturi+play 反而打断慢取流。
                val target = st.progressMs.coerceAtLeast(0L)
                try {
                    client.seek(target)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CastNetworkLog.log("cast-establish-kick failed ${e.javaClass.simpleName}")
                }
            }

            CastEstablishPolicy.Action.NUDGE_PLAY -> {
                establishRetries++
                establishRetryAtMs = nowMs
                CastNetworkLog.log("cast-establish-nudge elapsed=${elapsedMs}ms")
                // 054751：Seek 踢脚失效后电视报 PLAYING 却 81s 不取流，用户手动点
                // 播放下发单独 Play 后 1s 即 fetch。只发 Play，绝不重发 seturi。
                try {
                    client.play()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CastNetworkLog.log("cast-establish-nudge failed ${e.javaClass.simpleName}")
                }
            }

            CastEstablishPolicy.Action.WARN -> {
                establishWarned = true
                CastNetworkLog.log("cast-establish-failed elapsed=${elapsedMs}ms retries=$establishRetries")
                _state.update { it.copy(error = "电视未响应投屏，请检查设备后重试") }
            }
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
        var lastSuccessMs = System.currentTimeMillis()
        // 只记状态「变化」：1Hz 轮询如果每次都写一行，诊断文件会被刷爆
        var lastState: String? = null
        var lastPositionAvailable: Boolean? = null
        // 渲染器自发 PAUSED 的去抖计时：一帧缓冲抖动不能翻 UI，见 CastPlayStatePolicy
        var pausedSinceMs = 0L
        while (true) {
            val pollNowMs = System.currentTimeMillis()
            // 本轮取流时刻不受「每条投送最多 8 条 http-fetch 日志」的上限影响：
            // 供建立确认与曲中 STOPPED 的窗口顺延使用（见 CastStopPolicy）。
            val fetchMs = httpServer.lastSuccessfulFetchMs()
            refreshEstablishment(pollNowMs, fetchMs, client)
            // 取流空窗诊断：未建立时按档位采样，PAUSED 跳变时再单独记一条。
            val st = _state.value
            val idleMs = fetchIdleLog.sample(pollNowMs, fetchMs, st.castStartMs, st.isEstablished)
            // 「连续 3 次没回包」只证明手机侧最近收不到响应，不等于设备离线：
            // 息屏/Doze 短暂限制后台网络、刚从维护窗口醒来都会造成连续空窗
            // （真机上三次失败间隔 55s/35s，不是 1s，正是协程被冻结的痕迹）。
            // 断开投屏是很重的动作（直接回退本地播放），给一次延迟复探再定案。
            if (consecutiveFailures >= CastQueuePolicy.MAX_CONSECUTIVE_POLL_FAILURES) {
                delay(LOSS_CONFIRM_DELAY_MS)
                val stillDown = client.transportState() == null
                CastNetworkLog.log(
                    "cast-loss-confirm down=$stillDown ${net.keepAliveSnapshot()}"
                )
                if (stillDown && markConnectionLost("投屏设备已断开")) return
                // 设备还在、或本次判定被换曲窗口抑制：清零重新进入正常轮询，
                // 不打断用户正在听的投屏。
                consecutiveFailures = 0
                lastSuccessMs = System.currentTimeMillis()
            }
            val state = client.transportState()
            if (state == null) {
                // 换曲窗口内渲染器在 SetAVTransportURI/Play 之间常短暂无应答；
                // 曲目结束时也会重启控制服务再静默几秒。立刻计失败会让投屏在
                // 「播完」这一刻被误判失联、整个退回本地播放。只有持续静默超过
                // 宽限期且不在换曲窗口内，才开始累计失败并留下取证日志。
                val quietMs = System.currentTimeMillis() - lastSuccessMs
                if (quietMs >= POLL_LOSS_GRACE_MS &&
                    !CastLossPolicy.isWithinSwitchGrace(
                        lastCastStartMs, System.currentTimeMillis()
                    )
                ) {
                    consecutiveFailures++
                    // 带上保活锁真实状态：下一次再断，一眼就能分清是「锁丢了」
                    // （生命周期 bug）还是「锁在但系统仍掐网」（电池优化白名单）。
                    CastNetworkLog.log(
                        "own-poll-null quiet=${quietMs}ms failures=$consecutiveFailures " +
                            net.keepAliveSnapshot()
                    )
                }
            } else {
                lastSuccessMs = System.currentTimeMillis()
                consecutiveFailures = 0
                val position = client.position()
                val posAvailable = position != null
                val mapped = DlnaTransport.stateName(state)
                fetchIdleLog.onState(mapped == CastQueuePolicy.STATE_PAUSED, idleMs) {
                    // R7 现场快照：只在刚进入 PAUSED 那一帧求值，1Hz 其余帧零开销。
                    // keepAliveSnapshot 已含 wake/wifi/bound/session/battery/doze；
                    // fetchTrace 给「最近几次请求何时到达 + 累计写出字节 + 写失败原因」。
                    "progress=${position?.first ?: -1} duration=${position?.second ?: -1} " +
                        "${net.keepAliveSnapshot()} ${httpServer.fetchTraceSnapshot(pollNowMs)}"
                }
                // 进度不可得正是「播完」判定的关键证据：仅在变化时落一行，避免 1Hz 刷屏
                if (posAvailable != lastPositionAvailable) {
                    CastNetworkLog.log("own-pos available=$posAvailable state=$state")
                    lastPositionAvailable = posAvailable
                }
                // 落盘状态迁移：这是「电视到底播没播」唯一的一手证据，
                // 用户报「没声音」时先看这一行有没有 PLAYING。
                if (mapped != lastState) {
                    CastNetworkLog.log("own-state $mapped（原始 $state）")
                    // 曲末取证：STOPPED 时把 RelTime/TrackDuration 的真实组合落盘。
                    // 同一台电视在三份诊断里给过 0/0、185000/0、8000/193000，
                    // 只有看到这组值才能解释「为什么队列没有推进」。
                    if (mapped == CastQueuePolicy.STATE_STOPPED) {
                        CastNetworkLog.log(
                            "own-stop-pos progress=${position?.first ?: -1} " +
                                "duration=${position?.second ?: -1} available=$posAvailable"
                        )
                    }
                    lastState = mapped
                }
                // 播放态要经过「命令宽限 + PAUSED 去抖」才落进 CastState：
                // UI/取反基准/通知栏都读它，直接采信 1Hz 瞬时值会造成误暂停与抖动。
                // STOPPED 优先于命令宽限、立即生效且不去抖，曲末推进靠「在播→不在播」
                // 的跃迁；把它伪装成「在播」会让 playingSeen 提前置位而连跳队列。
                val nowMs = System.currentTimeMillis()
                val decision = CastPlayStatePolicy.resolve(
                    rawPlaying = state.equals("PLAYING", ignoreCase = true),
                    isPausedState = mapped == CastQueuePolicy.STATE_PAUSED,
                    previousStable = _state.value.isPlaying,
                    commandExpected = expectedPlaying,
                    guardRemainingMs = playStateGuardUntilMs - nowMs,
                    pausedSinceMs = pausedSinceMs,
                    nowMs = nowMs,
                    isStoppedState = mapped == CastQueuePolicy.STATE_STOPPED
                )
                pausedSinceMs = decision.pausedSinceMs
                if (decision.clearCommand) clearPlayStateCommand()
                _state.update {
                    it.copy(
                        isPlaying = decision.isPlaying,
                        playbackState = mapped,
                        progressMs = position?.first ?: 0L,
                        durationMs = position?.second ?: 0L,
                        hasProgressInfo = position != null,
                        lastFetchMs = fetchMs
                    )
                }
            }
            delay(1_000)
        }
    }

    /** 库通道的状态轮询（原实现，保持不变） */
    private suspend fun pollLibraryTransport() {
        var consecutiveFailures = 0
        // 与自有通道共用同一套播放态稳定化判据，避免两条通道行为分叉
        var pausedSinceMs = 0L
        while (currentCoroutineContext().isActive) {
            try {
                val st = DLNACast.getPlaybackState()
                val progress = DLNACast.getProgress()
                consecutiveFailures = 0
                val nowMs = System.currentTimeMillis()
                val decision = CastPlayStatePolicy.resolve(
                    rawPlaying = st == DLNACast.PlaybackState.PLAYING,
                    isPausedState = DlnaTransport.stateName(st.name) == CastQueuePolicy.STATE_PAUSED,
                    previousStable = _state.value.isPlaying,
                    commandExpected = expectedPlaying,
                    guardRemainingMs = playStateGuardUntilMs - nowMs,
                    pausedSinceMs = pausedSinceMs,
                    nowMs = nowMs,
                    isStoppedState = DlnaTransport.stateName(st.name) == CastQueuePolicy.STATE_STOPPED
                )
                pausedSinceMs = decision.pausedSinceMs
                if (decision.clearCommand) clearPlayStateCommand()
                _state.update {
                    it.copy(
                        isPlaying = decision.isPlaying,
                        playbackState = st.name,
                        progressMs = progress?.first ?: 0L,
                        durationMs = progress?.second ?: 0L,
                        // M3：进度拿不到（getProgress 返回 null）与「进度真的是 0」
                        // 必须分开记，否则「渲染器播完后把进度复位为 0」这一常见行为
                        // 会与「进度未知」混淆，判定「播完」时就会误判。
                        hasProgressInfo = progress != null
                    )
                }
                // 渲染器上报流错误：投屏已不可用，交由上层回退本地播放。
                // 换曲窗口内的 ERROR 只是旧曲过渡帧，markConnectionLost 会自行抑制。
                if (st == DLNACast.PlaybackState.ERROR &&
                    markConnectionLost("投屏设备播放异常")
                ) return
            } catch (e: Exception) {
                consecutiveFailures++
                Log.w(TAG, "cast polling failed ($consecutiveFailures)", e)
                // M3：连续多次取不到状态 → 设备大概率已离线（断网/关机/离开 Wi-Fi）。
                // 单次失败可能是渲染器正忙，不足以判定失联。
                if (consecutiveFailures >= CastQueuePolicy.MAX_CONSECUTIVE_POLL_FAILURES) {
                    if (markConnectionLost("投屏设备已断开")) return
                    // 换曲窗口内抑制：复位计数继续轮询，别每 1s 刷一条取证日志
                    consecutiveFailures = 0
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
     *
     * @return true=确实判定失联（调用方应结束轮询）；false=处于换曲窗口，本次判定被
     *   抑制，调用方必须复位失败计数并继续轮询，不能就此退出循环。
     */
    private fun markConnectionLost(reason: String): Boolean {
        val nowMs = System.currentTimeMillis()
        if (CastLossPolicy.isWithinSwitchGrace(lastCastStartMs, nowMs)) {
            // SetAVTransportURI/Play 换 URI 窗口内，旧轮询读到 STOPPED / ERROR / 空回包
            // 都只是过渡帧；此处若一帧把 isCasting 写成 false，遥控页整页跳回普通播放页，
            // 随后 onCastSucceeded 又写回 true，就是真机看到的「切歌时断开又连上」。
            CastNetworkLog.log(
                "cast-loss-skipped reason=$reason sinceCast=${nowMs - lastCastStartMs}ms " +
                    net.keepAliveSnapshot()
            )
            return false
        }
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
        net.endCastSession()
        return true
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        private const val TAG = "DlnaCastManager"

        /**
         * 判定失联前的静默宽限期（毫秒）。
         *
         * 曲目自然结束时，不少渲染器会重启 AVTransport 服务、短暂几秒不回
         * `GetTransportInfo`。旧实现连续 3 次（≈3 秒）取不到状态就判失联，于是
         * 「一首播完」偶发变成「投屏自动退出、播放被拉回本地」。现在只有距最后一次
         * 成功回包超过这个宽限才开始计失败；真正离线仍会在宽限 + 3 次轮询后回退。
         */
        private const val POLL_LOSS_GRACE_MS = 5_000L

        /**
         * 连续失败达上限后的「最终复探」等待（毫秒）。
         *
         * 只放宽宽限期不够：息屏后系统限制后台网络时，三次失败可能分别落在
         * 三个 Doze 维护窗口（真机间隔 55s/35s），醒来那一刻网络刚恢复，
         * 立刻断开就会把一次本可自愈的空窗变成「投屏自动退出」。
         * 等这个窗口再问一次，设备还活着就清零继续轮询。
         * 真正离线时只多等 2 秒，代价可忽略。
         */
        private const val LOSS_CONFIRM_DELAY_MS = 2_000L
        // 建立确认的宽限与重试上限收在 CastEstablishPolicy，便于纯 JVM 单测注入边界
    }
}

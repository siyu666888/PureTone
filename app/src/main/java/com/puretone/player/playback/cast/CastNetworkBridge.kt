package com.puretone.player.playback.cast

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import java.net.Inet4Address

/**
 * 投屏期的 Android 网络底层：组播锁、Wi-Fi 进程绑定、本机 Wi-Fi IPv4。
 *
 * 从 [DlnaCastManager] 拆出，原因是这三件事共享同一组「进程级全局状态」
 * （[castNetHeld] / [castNetPrevious]）与同一套网卡查询逻辑，但和投屏状态机
 * 本身无关：设备发现、投送、音量探针、轮询都只是它的使用方。
 *
 * **必须单点持有、单点释放**：`ConnectivityManager.bindProcessToNetwork` 是进程级
 * 全局设置，若每次音量/轮询都 bind 一次再 restore 一次，多路协程会把彼此
 * 「还原」成默认网络——真机日志里 1 秒内十几对 `wifi_net_bound/restored` 交织，
 * 轮询恰好落在被还原的窗口就会误判「投屏设备已断开」。
 */
internal class CastNetworkBridge(private val appContext: Context) {

    /** 投屏会话期间是否持有 Wi-Fi 进程绑定，以及绑定前的网络 */
    private var castNetHeld = false
    private var castNetPrevious: Network? = null

    /**
     * 投屏会话是否已接管上面这份「绑定 + 保活锁」。
     *
     * 2026-11-06 06:56 真机事故：设备发现先 `holdCastNetwork()` 拿到所有权，
     * 用户点设备投屏时 `castSong()` 只是 reused（不持有所有权），随后用户关弹窗
     * 使搜索协程被取消，`finally` 按 `ownsNetwork=true` 调 `releaseCastNetwork()`
     * ——把正在进行的投屏会话的锁一并释放。更隐蔽的是这次释放发生在 `cast-ok`
     * **之前**，所以「投送成功后再接管」也来不及。结果整个 11 分钟会话
     * 无 WakeLock/WifiLock/Wi-Fi 绑定，息屏进 Doze 后 1Hz 轮询被拉长到几十秒
     * 一圈，连续 3 次 null 就判定「投屏设备已断开」并回退本地。
     *
     * 因此所有权必须分成两类：**借用**（搜索/探针，用完归还）与**接管**
     * （投屏会话，一直持到 stopCast）。借用方归还时若会话已接管，一律不释放。
     */
    private var sessionActive = false

    /** 投屏会话保活锁：手机既是遥控器、也是电视拉流的 HTTP 源，进程不能在后台休眠 */
    private var sessionWakeLock: PowerManager.WakeLock? = null
    private var sessionWifiLock: WifiManager.WifiLock? = null

    /**
     * 取得组播锁（Wi-Fi 省电过滤组播包的直接对策）。
     *
     * 锁是**引用计数**的：每 acquire 一次必须对应一次 release，否则锁会一直持有、
     * 白白耗电。异常路径（拿不到锁）不能影响搜索本身——搜不到只是「没设备」，
     * 不能变成「崩溃」，故全部包在 runCatching 里。
     */
    fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
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

    fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
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
     * 这里改为引用式：谁先来谁真正绑定，其余调用直接复用。
     *
     * 归还方式按调用方身份二选一，**不要直接调 `releaseCastNetwork`**（已私有）：
     * - 临时借用（设备发现、能力探针）：用 [releaseBorrowedNetwork] 配对本方法返回值；
     * - 投屏会话（castSong / stopCast）：用 [beginCastSession] / [endCastSession]。
     *
     * @return true = 本次调用真正建立了绑定，借用方负责归还
     */
    fun holdCastNetwork(): Boolean {
        if (castNetHeld) {
            CastNetworkLog.log("wifi_net_bound=reused（投屏会话已持有）")
            return false
        }
        acquireKeepAlive()
        castNetPrevious = bindToWifiNetwork()
        castNetHeld = true
        return true
    }

    /**
     * 投屏会话接管这份「绑定 + 保活锁」。
     *
     * 必须在 [DlnaCastManager.castSong] **投送开始前**调用，不能等 `cast-ok`：
     * 真机上「设备发现结束」的清理可能先于投送成功发生，晚一步锁就被释放了。
     * 投送失败由 [endCastSession] 归还，成功则一直持有到停止投屏。
     */
    fun beginCastSession() {
        holdCastNetwork()
        sessionActive = true
        CastNetworkLog.log("cast-keepalive session=active ${keepAliveSnapshot()}")
    }

    /** 投屏会话结束（stopCast / 投送失败 / 失联回退 / release）：唯一无条件释放出口 */
    fun endCastSession() {
        sessionActive = false
        releaseCastNetwork()
    }

    /**
     * 临时借用方归还网络（设备发现、能力探针）。
     *
     * 会话已接管时**不释放**：投屏期间手机既是电视拉流的 HTTP 源、又是 1Hz 轮询的
     * 发起方，这两件事必须活到 [endCastSession]，不能被一次搜索结束清掉。
     * 幂等：复用别人的绑定（owned=false）时本就是无操作。
     */
    fun releaseBorrowedNetwork(owned: Boolean) {
        if (CastKeepAlivePolicy.shouldReleaseOnBorrowerReturn(owned, sessionActive)) {
            releaseCastNetwork()
        } else if (owned) {
            // owned=true 却被策略挡下，只可能是会话已接管：留一行证据，
            // 下一轮诊断里能直接看出「搜索结束没有误放锁」。
            CastNetworkLog.log("cast-keepalive kept（投屏会话接管，借用结束不释放）")
        }
    }

    /**
     * 诊断用：保活锁、网络绑定与**系统省电策略**的真实状态，落进轮询失败日志便于下一轮取证。
     *
     * 2026-11-06 20:08 真机（小米 / Android 16）出现 `quiet=176747ms failures=1` 且
     * wake/wifi/bound/session 四项全为 true：WakeLock 只保证 CPU 不睡，挡不住 Doze
     * 应用待机与国产 ROM 的后台冻结。把电池优化白名单与 Doze 状态写进同一行，
     * 下一份诊断就能直接区分「锁丢了」（生命周期 bug）与「锁在但系统仍掐网/冻结」
     * （需要用户放行白名单），不用再靠猜。
     */
    fun keepAliveSnapshot(): String =
        "wake=${sessionWakeLock?.isHeld == true} wifi=${sessionWifiLock?.isHeld == true} " +
            "bound=$castNetHeld session=$sessionActive " +
            "battery=${batteryOptimizationLabel()} doze=${deviceIdleLabel()}"

    /** 电池优化白名单：true=已忽略（放行）、false=受限制、null=读取失败 */
    private fun batteryOptimizationLabel(): String = runCatching {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(appContext.packageName)
    }.getOrNull().let { if (it == true) "ignored" else if (it == false) "restricted" else "unknown" }

    /** 设备是否处于低电耗（Doze）模式 */
    private fun deviceIdleLabel(): String = runCatching {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isDeviceIdleMode
    }.getOrNull().let { if (it == true) "on" else if (it == false) "off" else "unknown" }

    /** 还原投屏会话的进程网络（幂等：未持有时无操作，不会误撤别人的绑定） */
    private fun releaseCastNetwork() {
        if (!castNetHeld) return
        restoreNetwork(castNetPrevious)
        castNetHeld = false
        castNetPrevious = null
        releaseKeepAlive()
    }

    /**
     * 投屏会话级保活：持有 [PowerManager.PARTIAL_WAKE_LOCK] 与 Wi-Fi 高性能锁。
     *
     * ## 为什么投屏必须显式持锁（2026-11-05 21:35 真机）
     *
     * 本 App 的 DLNA 是 push 模式，**手机自己就是 HTTP 媒体源**：电视按
     * `http://手机IP:端口/...` 拉流，同时手机以 1Hz 轮询渲染器状态来推进队列。
     * 两者都跑在 App 进程里。投屏时本地 ExoPlayer 恒暂停，ExoPlayer 自带的
     * WakeLockManager 不会持锁，于是息屏后进程进入休眠：
     * - 取流服务不再响应 → 电视缓冲耗尽 → STOPPED；
     * - 轮询协程 `delay(1000)` 被拉长到 Doze 维护窗口 → 队列不推进。
     *
     * 诊断里 `own-poll-null quiet=165223ms failures=1` 就是这次休眠：最后一次
     * 成功回包后整整 165 秒没有跑过一圈轮询，用户解锁屏幕后第一圈才恢复，
     * 立刻读到 STOPPED 并自动续投。观感就是「后台投屏自动断开」。
     *
     * 锁与 Wi-Fi 进程绑定同生共死（[beginCastSession] / [endCastSession]），
     * 这两个出口已覆盖 stopCast / 投送失败 / 失联回退 / release 全部路径，
     * 不会出现「投屏停了锁还攥着」的耗电事故。**搜索/探针这类临时借用方
     * 只能走 [releaseBorrowedNetwork]**：2026-11-06 真机事故就是搜索结束
     * 直接释放，把刚建立、尚未 cast-ok 的会话锁一起清掉。
     * 故意不加 acquire 超时：听歌会话可能持续数小时，超时自动释放会重新引入后台断流。
     *
     * Wi-Fi 锁在高版本对第三方应用可能被系统忽略，属 best-effort；CPU 锁是硬保证。
     * 两者都包在 runCatching 里，任何机型上的拒绝都只记日志、不影响投屏。
     */
    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    private fun acquireKeepAlive() {
        if (sessionWakeLock != null || sessionWifiLock != null) releaseKeepAlive()
        sessionWakeLock = runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, KEEPALIVE_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            CastNetworkLog.log("cast-keepalive wake=error ${it.message}")
        }.getOrNull()

        sessionWifiLock = runCatching {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wm?.createWifiLock(mode, KEEPALIVE_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            CastNetworkLog.log("cast-keepalive wifi=error ${it.message}")
        }.getOrNull()

        CastNetworkLog.log(
            "cast-keepalive acquired wake=${sessionWakeLock?.isHeld == true} " +
                "wifi=${sessionWifiLock?.isHeld == true}"
        )
    }

    private fun releaseKeepAlive() {
        // 幂等：借用方归还与会话结束可能先后到达，没持锁时不刷 released 日志
        val hadLocks = sessionWifiLock != null || sessionWakeLock != null
        runCatching { if (sessionWifiLock?.isHeld == true) sessionWifiLock?.release() }
            .onFailure { CastNetworkLog.log("cast-keepalive wifi_release_error ${it.message}") }
        runCatching { if (sessionWakeLock?.isHeld == true) sessionWakeLock?.release() }
            .onFailure { CastNetworkLog.log("cast-keepalive wake_release_error ${it.message}") }
        if (hadLocks) CastNetworkLog.log("cast-keepalive=released")
        sessionWifiLock = null
        sessionWakeLock = null
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

    /** 取 Wi-Fi 网卡上的 IPv4（不能用网卡枚举顺序：双网卡时可能落到蜂窝内网 10.x） */
    fun wifiIpv4(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val net = findWifiNetwork(cm) ?: return null
        return wifiIp(cm, net)
    }

    private companion object {
        /** 组播锁标签（系统设置里可按此查到本应用的网络使用情况） */
        private const val CAST_LOCK_TAG = "PureTone-DLNA"

        /**
         * 投屏会话保活锁标签（与搜索期组播锁区分，便于在电池/网络排查里定位）。
         * PowerManager 要求 `前缀:名称` 形式，否则 lint InvalidWakeLockTag 直接报错。
         */
        private const val KEEPALIVE_TAG = "puretone:CastSession"
    }
}

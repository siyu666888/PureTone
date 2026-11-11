package com.puretone.player.playback.cast

import android.content.Context
import android.util.Log
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.DLNACast.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * SSDP 设备发现编排 + 设备缓存（从 [DlnaCastManager] 拆出）。
 *
 * 持有三类彼此强相关、但与投屏状态机无关的数据：
 * - [knownDevices]：按 IP 索引的「曾经见过 / 投成功过」的设备，用于空结果时 unicast 唤醒；
 * - [lastDevices]：最近一次发现拿到的**底层** `Device` 列表，供 [select] 反查投送目标；
 * - [lastDevicesAt]：上表时间戳，用于「近期列表」兜底的有效期判定。
 *
 * 这三者必须同生共死（发现成功同时回填、停止投屏同时清空），放在一起才能避免
 * 「换数据源却忘了反查缓存」那类事故。网络底层由 [CastNetworkBridge] 提供。
 */
internal class CastDeviceDiscovery(
    private val appContext: Context,
    private val castHistory: CastHistoryStore,
) {

    /**
     * 历史设备地址 → 设备对象的缓存（按 IP 索引）。
     *
     * 用途：SSDP 组播首搜经常空手（电视刚唤醒时对 M-SEARCH 的响应不稳，真机上
     * 出现过连续多轮 0 设备），但一旦搜到过一次，它的 IP 就记在这里。后续再空结果时，
     * 直接对这些已知 IP 发 **unicast M-SEARCH**（点对点，绕过组播抑制），电视对单播
     * 的应答率远高于组播，往往能「唤醒」它重新出现在列表里。
     * 冷启动时由 [warmUp] 用 [CastHistoryStore] 里「投成功过」的设备预热，因此
     * 「重装/重启后第一次搜索就空手」也能直接单播唤醒。
     * 并发：搜索线程读、投送线程与预热协程写，必须用并发容器。
     */
    private val knownDevices = ConcurrentHashMap<String, CastDevice>()

    private var lastDevices: List<Device> = emptyList()

    /** 上次成功搜到设备的时刻：连续空结果时用来判断「近期列表还能不能沿用」 */
    private var lastDevicesAt: Long = 0L

    /**
     * 用历史设备预填进程内的 IP 缓存：冷启动后第一次搜索就空手时，
     * 也能对上次投过的电视发 unicast M-SEARCH，不必先「成功搜到过一次」。
     */
    suspend fun warmUp() {
        val known = runCatching { castHistory.entries.first() }.getOrDefault(emptyList())
        if (known.isEmpty()) return
        for (entry in known) knownDevices[entry.address] = entry.toDevice()
        CastNetworkLog.log("cast-history 预热 knownDevices=${known.size}")
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
     *   会把默认组播接口落到蜂窝接口，M-SEARCH 出不了局域网。对策见 [CastNetworkBridge]：
     *   搜索期间把进程默认网络 `bindProcessToNetwork(wifi)` 绑到 Wi-Fi，强制组播走 wlan0，
     *   搜索结束立即还原。
     *
     * 线程：`DLNACast.search()` 是**阻塞**调用（内部等 SSDP 回包直到超时），
     * 必须放到 IO 线程，否则整个搜索期间主线程被占住 5 秒——UI 冻结、
     * 点击无响应，用户会以为 App 崩了。故此处用 `withContext(Dispatchers.IO)` 包住。
     */
    suspend fun search(net: CastNetworkBridge, timeoutMs: Long = 5000): List<CastDevice> {
        CastNetworkLog.log("=== 开始设备发现 ===")

        val lock = net.acquireMulticastLock()
        // 双网卡设备上把进程默认网络绑定到 Wi-Fi：UPnPCast 用 joinGroup(组播地址, null)，
        // 不指定网卡，组播默认走系统默认接口；手机同时开 Wi-Fi+移动数据时，部分厂商会把
        // 组播路由到蜂窝接口，导致 SSDP 收不到回包、永远 0 设备。绑到 Wi-Fi 即修复。
        // 返回 true 表示本次由搜索建立的绑定。归还走 releaseBorrowedNetwork：
        // 若期间用户已点设备开始投屏（castSong 已 beginCastSession），会话会接管这份
        // 绑定与保活锁，搜索结束**不能**把正在投屏的会话锁一起释放。
        val ownsNetwork = net.holdCastNetwork()
        // 快照放在持锁、**绑定 Wi-Fi 之后**：multicast_lock_last 与 wifi_net_bound 都反映本次。
        CastNetworkLog.log(CastNetworkLog.snapshot(appContext).trimEnd())
        val started = System.currentTimeMillis()
        return try {
            // 主通道：自建 SSDP（显式网卡 + 三轮重传 + 组播与子网广播并行）。
            // 库那套的问题见 [SsdpDiscovery] 顶部注释——它把「必须重启 Wi-Fi 才能搜到」
            // 变成了必然：joinGroup 不指定网卡、每轮只发一次、且只走组播。
            val own = SsdpDiscovery.discover(
                wifiIp = net.wifiIpv4(),
                knownAddresses = knownDevices.keys,
                timeoutMs = OWN_SEARCH_TIMEOUT_MS
            )
            val result = if (own.devices.isNotEmpty()) {
                recordKnown(own.devices)
                // 自建发现的结果也要回填 lastDevices：select() 按 id 反查底层 Device，
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
            // 非 MediaRenderer 已在 [SsdpDiscovery.resolveDevices] 按设备描述剔除：
            // 路由器 / MediaServer 不会下发到 UI，也不会被回填进 lastDevices / knownDevices。
            // 库兜底通道与「最近投屏」历史不在此过滤：前者由库按 DMR 目标搜索，
            // 后者只在投送成功时写入，都不是「宽泛 ST 应答」这条污染路径。
            //
            // 同一台设备可能在同 IP 上暴露多个 UPnP 描述（常见于电视：不同端口各一个
            // friendlyName，例如极光TV 同时有 `623(192.168.101.20)` 与 `客厅极光TV(dlna)`），
            // 会被当成多条记录。若不处理，投屏弹窗用 IP 当 LazyColumn 的 key 会撞车，
            // 抛 IllegalArgumentException 直接闪退。按 IP 去重（保留首个），既止血也避免
            // 同一电视在列表里出现两次。lastDevices/knownDevices 仍按 address 索引、本就唯一，不受影响。
            val devices = result.distinctBy { it.address }
            CastNetworkLog.logDevices(devices)
            if (devices.isEmpty()) {
                CastNetworkLog.log(
                    "空结果：请确认手机与投屏设备处于同一 Wi-Fi（非访客网络）、" +
                        "手机未开启 VPN/移动数据优先、投屏设备已开机并允许被发现"
                )
            }
            devices
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
            // 注意：搜索被取消（用户点设备后关弹窗）时 finally 照样执行，且这一刻
            // 往往早于 cast-ok。若这里无条件释放，就会把刚接管的投屏会话保活锁清掉，
            // 息屏后 1Hz 轮询被 Doze 拉长、电视拉流断供，最终误判「设备已断开」。
            net.releaseBorrowedNetwork(ownsNetwork)
            net.releaseMulticastLock(lock)
            CastNetworkLog.log("=== 设备发现结束（耗时 ${System.currentTimeMillis() - started}ms）===")
        }
    }

    /** 跑一轮 SSDP 搜索（阻塞调用放到 IO），并落盘耗时与设备清单 */
    private suspend fun searchOnce(timeoutMs: Long): List<CastDevice> {
        val started = System.currentTimeMillis()
        val raw = withContext(Dispatchers.IO) { DLNACast.search(timeout = timeoutMs) }
        // P2-3 第五轮：库通道的 LOCATION 不经 onDatagram（拿不到应答源），库自发
        // 的描述 GET 也不经自有 SoapClient。这里在设备进入 lastDevices / UI /
        // 投送链路之前补一道目标校验：明显畸形目标剔除，域名 / IPv6 / 非 URL
        // 形状的 id 放行留痕，合法单播 IPv4 零变化。注意拦不住库内部那次请求。
        val keep = LibraryDeviceTargetPolicy.screenAll(raw.map { it.id }, CastTargetDiagnostics::emit)
        val accepted = raw.filterIndexed { index, _ -> keep[index] }
        val elapsed = System.currentTimeMillis() - started
        val result = accepted.map { CastDevice(it.id, it.name, it.address, it.isTV) }
        CastNetworkLog.logSearch("ssdp", timeoutMs, result.size, elapsed)
        if (result.isNotEmpty()) {
            lastDevices = accepted
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

    /** 选定投屏设备（按 id 反查底层 [Device]；查不到就按 UI 传来的字段现造一个） */
    fun select(device: CastDevice): Device =
        lastDevices.firstOrNull { it.id == device.id }
            ?: Device(device.id, device.name, device.address, device.isTV)

    /** 投送成功后记入已知设备（供后续 unicast 唤醒） */
    fun remember(device: CastDevice) {
        recordKnown(listOf(device))
    }

    /** 停止投屏时清掉「近期列表」（[knownDevices] 跨会话保留，不清） */
    fun reset() {
        lastDevices = emptyList()
        lastDevicesAt = 0L
    }

    private companion object {
        /** 沿用主管理器的日志 TAG：诊断时按同一个 tag 过滤即可看全链路 */
        private const val TAG = "DlnaCastManager"

        /**
         * 自建 SSDP 发现的总预算。
         *
         * 内部按 700ms 一轮发三轮（组播 + 子网广播 × 3 个 ST），有命中就早退；
         * 3.5s 足够覆盖最坏的重传，又不至于让用户对着转圈干等。
         */
        private const val OWN_SEARCH_TIMEOUT_MS = 3500L

        /** 「近期设备列表」的有效期：超过就当作真的没有设备，不能用幽灵设备误导投送 */
        private const val RECENT_DEVICE_TTL_MS = 10 * 60 * 1000L
    }
}

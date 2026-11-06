package com.puretone.player.playback.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URI

/**
 * 自建 SSDP 发现（取代库 [com.yinnho.upnpcast.DLNACast] 的 `search()`）。
 *
 * ## 为什么不能用库的发现
 *
 * 真机现象是「每次要重启一次手机 Wi-Fi 才能搜到投屏设备」；诊断日志把范围压到了网络层
 * （权限已授、`MulticastLock` 已持、`bindProcessToNetwork` 返回 true、Wi-Fi `validated`、
 * IPv4 正确），可连续数轮 `devices=0`。反汇编 UPnPCast v1.3.0 的 `SsdpDeviceDiscovery`
 * 后确认四个硬伤，正好对应这个现象：
 *
 * 1. **`joinGroup(组播地址, null)`** 不指定网卡：手机同时开移动数据时，内核按默认路由选
 *    接口，可能落到 `rmnet_data0`，组成员关系加在错网卡上，应答永远回不来。
 *    `bindProcessToNetwork()` 只改「新建套接字的默认网络」，管不住 `joinGroup(…, null)`。
 * 2. **每轮只发一次**（3 个 ST 各一条），没有任何重传。Wi-Fi 省电 / AP 组播过滤丢一个包
 *    就是「0 设备」——真机上出现过连续 4 轮（12 条 M-SEARCH）全空。
 * 3. **只发组播，不发子网定向广播**。组播一旦被 AP 的 IGMP snooping 挡住，
 *    `x.y.z.255:1900` 往往还能通；这条路库完全没走。
 * 4. **1900 被占时静默退化为随机端口**，只打一条 Log.w，上层完全不知情。
 *
 * 本类一一对应：显式 `setNetworkInterface` + `joinGroup(addr, nic)`、三轮重传 + 命中早退、
 * 组播与子网广播并行、并把 bind 端口与收发计数落进诊断。
 */
internal object SsdpDiscovery {

    private const val TAG = "SsdpDiscovery"

    const val MULTICAST_HOST = "239.255.255.250"
    const val SSDP_PORT = 1900

    /** 与库对齐的搜索目标：DMR 精确匹配 + rootdevice + 全量兜底 */
    val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "upnp:rootdevice",
        "ssdp:all"
    )

    /** 单轮监听窗口：MX=1 时设备应在 1s 内应答；靠重传而不是靠一次等很久 */
    private const val ROUND_WINDOW_MS = 700L

    /** 最多解析多少台设备的描述（防止满网段设备把发现拖成十几秒） */
    private const val MAX_DESCRIPTIONS = 8

    /** 重传轮数：三轮覆盖偶发丢包，命中即早退 */
    private const val MAX_ROUNDS = 3

    /** 发现结果（含取证统计，供诊断落盘） */
    data class Result(
        val devices: List<CastDevice>,
        val bindPort: Int,
        val iface: String?,
        val joined: Boolean,
        val tx: Int,
        val rx: Int,
        val locations: Int
    ) {
        val ok: Boolean get() = devices.isNotEmpty()
    }

    /** 一条 SSDP 应答里我们关心的字段 */
    data class Located(val location: String, val st: String, val usn: String)

    /** 一轮收发后的累计计数 */
    private data class Traffic(val tx: Int, val rx: Int)

    /**
     * 跑一轮完整发现。
     *
     * @param wifiIp Wi-Fi 的 IPv4（算子网广播、兜底选网卡）；null 时只发组播
     * @param knownAddresses 历史上发现过的设备 IP，额外发定向单播（最省事的唤醒手段）
     * @param timeoutMs 总预算（含重传与描述解析）
     */
    suspend fun discover(
        wifiIp: String?,
        knownAddresses: Collection<String> = emptyList(),
        timeoutMs: Long = 3500
    ): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val socket = openSocket() ?: return@withContext Result(emptyList(), -1, null, false, 0, 0, 0)
        try {
            val bindPort = socket.localPort
            val nic = pickInterface(wifiIp)
            val joined = joinMulticast(socket, nic)
            val targets = buildTargets(wifiIp, knownAddresses)
            CastNetworkLog.log(
                "ownssdp bind=$bindPort iface=${nic?.name} join=$joined targets=" +
                    targets.joinToString(",") { "${it.address.hostAddress}:${it.port}" }
            )
            val found = LinkedHashMap<String, Located>()
            val traffic = runRounds(socket, targets, started + timeoutMs, found)
            val devices = resolveDevices(found.values.toList())
            CastNetworkLog.log(
                "ownssdp 完成 tx=${traffic.tx} rx=${traffic.rx} locations=${found.size} " +
                    "devices=${devices.size} 耗时=${System.currentTimeMillis() - started}ms"
            )
            Result(devices, bindPort, nic?.name, joined, traffic.tx, traffic.rx, found.size)
        } catch (e: Exception) {
            Log.w(TAG, "ssdp discover failed", e)
            CastNetworkLog.log("ownssdp 异常：${e.javaClass.simpleName} ${e.message}")
            Result(emptyList(), socket.localPort, null, false, 0, 0, 0)
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * 建组播套接字：优先占住 1900（能收到 NOTIFY 与发给 1900 的应答），
     * 被系统投屏服务占用时退到随机端口（SSDP 应答按源端口回，仍可工作），并把真实端口交给诊断。
     */
    private fun openSocket(): MulticastSocket? = runCatching {
        MulticastSocket(null).apply {
            // reuse 必须在 bind 前开：与系统投屏服务/库的套接字共存于 1900
            reuseAddress = true
            runCatching { bind(InetSocketAddress(SSDP_PORT)) }.getOrElse {
                bind(InetSocketAddress(0))
                CastNetworkLog.log("ownssdp 1900 被占用，退到随机端口（收不到 NOTIFY）")
            }
        }
    }.getOrElse {
        CastNetworkLog.log("ownssdp socket 创建失败：${it.javaClass.simpleName} ${it.message}")
        null
    }

    /**
     * 显式把「发」和「收」都钉到 Wi-Fi 网卡——这是与库最本质的区别。
     * `setNetworkInterface` 管发送接口，`joinGroup(addr, nic)` 管组成员关系，少一个都可能走错网卡。
     */
    private fun joinMulticast(socket: MulticastSocket, nic: NetworkInterface?): Boolean {
        if (nic == null) {
            CastNetworkLog.log("ownssdp 未找到 Wi-Fi 网卡，仅发组播（不指定接口）")
            return false
        }
        runCatching { socket.networkInterface = nic }
            .onFailure { CastNetworkLog.log("ownssdp setNetworkInterface 失败：${it.message}") }
        return runCatching {
            socket.joinGroup(InetSocketAddress(InetAddress.getByName(MULTICAST_HOST), SSDP_PORT), nic)
            true
        }.getOrElse {
            CastNetworkLog.log("ownssdp joinGroup 失败：${it.javaClass.simpleName} ${it.message}")
            false
        }
    }

    /** 目标集合：组播 + 子网定向广播 + 历史设备单播 */
    private fun buildTargets(wifiIp: String?, knownAddresses: Collection<String>): List<InetSocketAddress> =
        buildList {
            runCatching { add(InetSocketAddress(InetAddress.getByName(MULTICAST_HOST), SSDP_PORT)) }
            // 子网广播：绕开 AP 的 IGMP snooping，很多电视对广播照样应答
            broadcastOf(wifiIp)?.let { ip ->
                runCatching { add(InetSocketAddress(InetAddress.getByName(ip), SSDP_PORT)) }
            }
            knownAddresses.forEach { addr ->
                runCatching { add(InetSocketAddress(InetAddress.getByName(addr), SSDP_PORT)) }
            }
        }

    /**
     * 按轮发送 + 收包：每轮把所有目标、所有 ST 各发一条，然后收 700ms；
     * 有命中立即返回（用户等的是设备列表，不是「把三轮跑满」）。
     */
    private fun runRounds(
        socket: MulticastSocket,
        targets: List<InetSocketAddress>,
        deadline: Long,
        found: MutableMap<String, Located>
    ): Traffic {
        var tx = 0
        var rx = 0
        socket.soTimeout = 250
        val buf = ByteArray(8192)
        repeat(MAX_ROUNDS) {
            if (found.isNotEmpty() || System.currentTimeMillis() >= deadline) return Traffic(tx, rx)
            for (target in targets) {
                for (st in SEARCH_TARGETS) {
                    val host = "${target.address.hostAddress}:${target.port}"
                    val data = searchRequest(st, host).toByteArray(Charsets.UTF_8)
                    if (runCatching { socket.send(DatagramPacket(data, data.size, target)) }.isSuccess) tx++
                }
            }
            val roundEnd = minOf(System.currentTimeMillis() + ROUND_WINDOW_MS, deadline)
            while (System.currentTimeMillis() < roundEnd) {
                val packet = DatagramPacket(buf, buf.size)
                if (runCatching { socket.receive(packet) }.isFailure) continue
                rx++
                onDatagram(packet, found)
            }
        }
        return Traffic(tx, rx)
    }

    /** 处理一条收到的数据报：只认 M-SEARCH 的 200 OK，按 LOCATION 去重 */
    private fun onDatagram(packet: DatagramPacket, found: MutableMap<String, Located>) {
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val located = parseResponse(text) ?: return
        if (found.containsKey(located.location)) return
        found[located.location] = located
        CastNetworkLog.log(
            "ownssdp rx from=${(packet.address as? Inet4Address)?.hostAddress} " +
                "st=${located.st.ifBlank { "-" }} location=${located.location}"
        )
    }

    /** 构造 M-SEARCH 报文（MX=1：设备应在 1s 内随机应答，配合短窗口重传） */
    fun searchRequest(target: String, host: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $host\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: $target\r\n" +
            "USER-AGENT: PureTone/1.0 UPnP/1.1\r\n" +
            "\r\n"

    /**
     * 解析一条 SSDP 应答。
     *
     * 只认 `HTTP/1.1 200 OK`（M-SEARCH 应答）；NOTIFY 不带请求上下文、也不保证含完整
     * LOCATION，这里不参与发现，避免把已下线设备的 byebye 当成在线。
     * 头名大小写不敏感——不同厂商写法不一（`LOCATION:` / `Location:` / `location:`）。
     */
    fun parseResponse(text: String): Located? {
        val lines = text.split("\r\n", "\n")
        if (lines.isEmpty() || !lines[0].trim().startsWith("HTTP/1.1 200", true)) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
        }.toMap()
        val location = headers["location"]?.takeIf { it.isNotBlank() } ?: return null
        return Located(location, headers["st"].orEmpty(), headers["usn"].orEmpty())
    }

    /** 从 LOCATION 里取主机地址（[CastDevice.address] 用它） */
    fun hostOf(location: String): String? {
        val host = runCatching { URI(location).host }.getOrNull()
        if (!host.isNullOrBlank()) return host
        return location.removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')
            .takeIf { it.isNotBlank() }
    }

    /** 由 IPv4 推子网定向广播地址（/24 假设；家用网段适用，取不到就算了） */
    fun broadcastOf(ipv4: String?): String? {
        val parts = ipv4?.split('.') ?: return null
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.255"
    }

    /**
     * 选 Wi-Fi 网卡：优先「带与 wifiIp 相同 IPv4 的 up 网卡」，否则按名字找 wlan*。
     * 不做「第一个非回环网卡」这种猜测——那正是库里出错的写法。
     */
    private fun pickInterface(wifiIp: String?): NetworkInterface? = runCatching {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
        val matched = wifiIp?.let { ip ->
            all.firstOrNull { nic ->
                nic.inetAddresses.toList().any { it is Inet4Address && it.hostAddress == ip }
            }
        }
        matched ?: all.firstOrNull { it.name.startsWith("wlan") }
    }.getOrNull()

    /**
     * 逐台取设备描述拿 friendlyName / deviceType，并按 [CastDeviceFilter] 剔除非渲染器。
     *
     * 并发解析期间**不直接写诊断日志**：`CastNetworkLog.log` 没有跨线程同步，
     * 多协程同时 append 可能把行写花。剔除信息先收集，awaitAll 之后统一落一条。
     */
    private suspend fun resolveDevices(found: List<Located>): List<CastDevice> = coroutineScope {
        val outcomes = found.take(MAX_DESCRIPTIONS).map { located ->
            async<Pair<CastDevice?, String?>> {
                val host = hostOf(located.location).orEmpty()
                val body = runCatching { SoapClient.get(located.location) }.getOrNull()
                    ?.takeIf { it.ok }?.body
                val parsed = body?.let { CastProbe.parseDescription(it) }
                val verdict = CastDeviceFilter.evaluate(
                    descriptionFetched = parsed != null,
                    deviceType = parsed?.deviceType,
                    serviceTypes = parsed?.services?.map { it.serviceType }.orEmpty()
                )
                if (!verdict.keep) {
                    // 宽泛 ST（rootdevice / ssdp:all）会引来路由器与媒体服务器，
                    // 点进去必然投送失败。诊断里留一行，便于确认「设备少了」不是漏搜。
                    return@async null to "name=${parsed?.friendlyName ?: host} " +
                        "type=${parsed?.deviceType ?: "?"} st=${located.st} reason=${verdict.reason}"
                }
                CastDevice(
                    id = located.location,
                    name = parsed?.friendlyName ?: located.usn.ifBlank { host.ifBlank { "DLNA 设备" } },
                    address = host,
                    // 只把确认过的 MediaRenderer 标成 TV；描述取不到时保守保留但用普通图标
                    isTV = verdict.isRenderer
                ) to null
            }
        }.awaitAll()
        val dropped = outcomes.mapNotNull { it.second }
        if (dropped.isNotEmpty()) {
            CastNetworkLog.log("ssdp-filter 剔除 ${dropped.size} 台非渲染器：${dropped.joinToString(" | ")}")
        }
        outcomes.mapNotNull { it.first }.filter { it.address.isNotBlank() }
    }
}

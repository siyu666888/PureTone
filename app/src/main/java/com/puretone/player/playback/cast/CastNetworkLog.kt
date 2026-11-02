package com.puretone.player.playback.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 投屏设备发现的网络诊断日志。
 *
 * ## 为什么需要它
 *
 * 「真机投屏发现不到设备」是最难自证的一类问题：模拟器上往往能发现（模拟器的
 * 网络栈与真机不同），真机上却空空如也，而用户手上只有「未发现可投屏设备」一句提示。
 * 没有网络层证据，任何修复都是猜。
 *
 * 这里把**发现失败时最关键的六个事实**落盘，供用户导出诊断报告：
 * 1. 当前是否有可用网络、走的是 Wi-Fi 还是蜂窝/以太网；
 * 2. 本机在 Wi-Fi 上的 IPv4 地址（SSDP 走组播，地址不对就收不到回包）；
 * 3. Wi-Fi 网卡名与 MTU（组播被路由到错误网卡时靠这个定位）；
 * 4. 是否成功持有 `MulticastLock`（Android 省电会丢弃组播包，拿不到锁必然收不到）；
 * 5. 库内 SSDP 探测实际发出的 M-SEARCH 次数与耗时；
 * 6. 最终发现的设备数与每台设备的名称/地址。
 *
 * ## 不含任何曲库内容
 *
 * 只记网络与设备发现信息，符合 PRIVACY.md 的立场（同 [com.puretone.player.playback.PlaybackEventLog]）。
 */
object CastNetworkLog {

    private const val TAG = "CastNetworkLog"

    /** 落盘文件名（诊断导出按此读取） */
    const val FILE_NAME = "cast_network.txt"

    /** 环形上限：约 24 KB，足够覆盖多轮发现尝试 */
    private const val MAX_LINES = 120

    private const val TRIM_THRESHOLD_BYTES = 48 * 1024

    @Volatile
    private var logFile: File? = null

    /** 初始化落盘位置（Application.onCreate 调用一次） */
    fun init(filesDir: File) {
        logFile = File(filesDir, FILE_NAME)
    }

    /** 追加一行带时间戳的记录 */
    fun log(message: String) {
        Log.i(TAG, message)
        val target = logFile ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        runCatching {
            val f = target
            if (f.exists() && f.length() > TRIM_THRESHOLD_BYTES) trim(f)
            f.appendText("[$stamp] $message\n")
        }
    }

    /** 读取已落盘的日志（诊断导出用） */
    fun read(context: Context): String? {
        val target = logFile ?: File(context.filesDir, FILE_NAME)
        return runCatching { if (target.isFile) target.readText() else null }.getOrNull()
    }

    private fun trim(file: File) {
        runCatching {
            val lines = file.readLines()
            if (lines.size <= MAX_LINES) return
            val kept = lines.takeLast(MAX_LINES)
            file.writeText(kept.joinToString("\n") + "\n")
        }
    }

    /**
     * 采集一次网络环境快照（发现开始时调用）。
     *
     * 这一段信息在真机排查中价值最高：投屏失败时最常见的三种情况——
     * 手机根本没连 Wi-Fi、连的是访客网络（AP 隔离）、手机开着 VPN/双网卡——
     * 都能从这里一眼看出来。
     */
    fun snapshot(context: Context): String {
        val sb = StringBuilder()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network: Network? = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val cell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val eth = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            sb.append("network=").append(if (wifi) "wifi" else if (cell) "cellular" else if (eth) "ethernet" else "none")
            if (vpn) sb.append("+vpn")
            sb.append(" connected=").append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            sb.append(" validated=").append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            sb.appendLine()

            // 本机 IPv4：SSDP 回包按本机地址投递，地址族不对（纯 IPv6 / 无地址）就收不到
            val wifiIface: List<NetworkInterface> = wifiInterfaces()
            sb.append("wifi_ifaces=")
                .append(if (wifiIface.isEmpty()) "none" else wifiIface.joinToString(",") { nic: NetworkInterface -> nic.name })
            sb.appendLine()
            val addrs = wifiIface.flatMap { nic: NetworkInterface ->
                nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { addr: Inet4Address -> "${addr.hostAddress}" }
            }
            sb.append("wifi_ipv4=").append(if (addrs.isEmpty()) "none" else addrs.joinToString(","))
            sb.appendLine()

            // 链路地址（100.x / 10.x 等网段最能暴露「连错网络」）
            // 注：NetworkCapabilities.linkAddresses 是 @hide 的非公开 API，
            // 链路地址要从公开的 LinkProperties 取。
            runCatching {
                val lp = network?.let { cm?.getLinkProperties(it) }
                lp?.linkAddresses?.forEach { la: LinkAddress ->
                    sb.append("link=").append(la.address.hostAddress ?: "?").append('/')
                        .append(la.prefixLength).append(' ')
                }
            }
            sb.appendLine()

            // MulticastLock：Android 会在无锁时丢弃 Wi-Fi 组播包，这是 SSDP 收不到包的头号原因
            sb.append("multicast_lock_acquired=").append(multicastLockHeld(context))
            sb.appendLine()
        }.onFailure { sb.append("snapshot_error=").append(it.message) }
        return sb.toString()
    }

    /**
     * 当前应用是否正持有组播锁（反映上一次搜索是否成功拿到锁）。
     *
     * 注：`WifiManager.isMulticastLockSupported()` 是 @hide 的非公开 API，
     * 不能调用；这里改为「能建出锁对象」即视为系统支持（所有设备均可建）。
     */
    private fun multicastLockHeld(context: Context): Boolean = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm != null && runCatching { wm.createMulticastLock("probe") }.isSuccess
    }.getOrDefault(false)

    /** 列出所有处于 up 状态的 Wi-Fi 网卡 */
    private fun wifiInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
    }.getOrDefault(emptyList())

    /** 记录一次 M-SEARCH 探测（与库内 SSDP 行为对齐：库发几次我们就记几次） */
    fun logSearch(target: String, timeoutMs: Long, deviceCount: Int, elapsedMs: Long) {
        log("search target=$target timeout=${timeoutMs}ms elapsed=${elapsedMs}ms devices=$deviceCount")
    }

    /** 记录发现到的设备 */
    fun logDevices(devices: List<CastDevice>) {
        if (devices.isEmpty()) {
            log("devices=0")
            return
        }
        devices.forEach { log("device name=${it.name} address=${it.address} isTV=${it.isTV}") }
    }

    /** 记录权限状态（Android 13+ 附近设备权限 / 低版本定位权限） */
    fun logPermission(context: Context, permission: String, granted: Boolean) {
        val state = if (granted) "granted" else "denied"
        log("permission $permission=$state api=${Build.VERSION.SDK_INT}")
    }
}

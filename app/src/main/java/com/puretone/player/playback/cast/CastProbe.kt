package com.puretone.player.playback.cast

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 投送失败的**现场取证**探针（只读，不改变电视播放状态）。
 *
 * ## 为什么需要它
 *
 * UPnPCast v1.3.0 在投送失败时链路是**全黑**的：
 * `DlnaMediaController.setMediaUri()` → `UpnpHttp.postSoap()` 只在 HTTP **200** 时返回正文，
 * 任何非 200（404 控制地址错、500 SOAP fault、超时、连接被拒）都被静默吞成 `null`，
 * 再经 `playMediaDirect` 变成 `false`，最后 `LocalCastManager` 抛出
 * `DeviceError("Device rejected the cast request")` —— **不带 cause、不带状态码**。
 *
 * 本探针绕开库，用设备描述地址（[CastDevice.id] 就是 SSDP 的 LOCATION）自己去问：
 * 1. 设备描述里 AVTransport 的 `controlURL` **原始值**与正确解析结果；
 * 2. 按正确地址发一条**只读**的 `GetTransportInfo`，拿到真实 HTTP 状态码与正文；
 * 3. `ConnectionManager.GetProtocolInfo` 的 `SinkProtocolInfo`——这台电视声明支持哪些格式；
 * 4. 库会退化的「猜路径」`/<默认路径>` 是否可用——用来判断库有没有解析到服务列表。
 */
internal object CastProbe {

    private const val TAG = "CastProbe"

    private const val AVT_NS = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val CMS_NS = "urn:schemas-upnp-org:service:ConnectionManager:1"
    private const val RC_NS = "urn:schemas-upnp-org:service:RenderingControl:1"

    /** 原始描述落盘上限：够看出 controlURL 写法与服务类型，又不至于刷爆诊断 */
    private const val RAW_DESC_LIMIT = 700

    /**
     * 设备描述里的一个服务端点。
     *
     * @param controlUrl SOAP 动作地址
     * @param scpdUrl 服务描述（SCPD）地址——**音量上限只能从这里读**：
     *   `RenderingControl` 的 `Volume` 状态变量可能声明 `0..100`，也可能声明
     *   `0..30`/`0..255`。把 0~100 的原值直接塞给一台 0~30 的电视，它会返回 200
     *   却按上限截断（表现为「调了没反应」）——真机上极光TV 回读恒为 18 正是这种形态。
     */
    data class Service(val serviceType: String, val controlUrl: String, val scpdUrl: String = "")

    /** 设备描述中与投送相关的字段 */
    data class Description(
        val friendlyName: String,
        val deviceType: String,
        val services: List<Service>
    )

    /**
     * 探针结论。
     *
     * @param descriptionOk 设备描述是否成功取到（false 时其余字段无意义，不能据此下结论）
     * @param hasRenderingControl 设备是否暴露 RenderingControl 服务——**没有就绝无可能用 DLNA 调音量**
     * @param currentVolume 设备当前音量（0~100 百分比，已按 SCPD 量程折算；null = 未读到）。
     *        库通道没有 `GetVolume` 回读能力，投屏建立时用它把 UI/系统音量条的初值
     *        校准到电视真实音量，避免「显示 100%、一拖就跳到 78%」。
     */
    data class Report(
        val descriptionOk: Boolean,
        val hasRenderingControl: Boolean,
        val currentVolume: Int? = null
    )

    /**
     * 跑一遍探针并把结果写进 [CastNetworkLog]。
     *
     * 全程不抛异常：探针本身失败只落一行 `probe-error`，不能影响已经发生的投送失败处理。
     */
    suspend fun inspect(locationUrl: String, deviceName: String): Report = withContext(Dispatchers.IO) {
        try {
            val descResult = SoapClient.get(locationUrl)
            if (!descResult.ok) {
                CastNetworkLog.log("probe-desc http=${descResult.status} url=$locationUrl")
                return@withContext Report(
                    descriptionOk = false,
                    hasRenderingControl = false
                )
            }
            val xml = descResult.body
            val desc = parseDescription(xml)
            CastNetworkLog.log(
                "probe-desc device=$deviceName http=200 len=${xml.length} name=${desc.friendlyName} " +
                    "type=${desc.deviceType} services=${desc.services.size}"
            )
            // 原始描述落盘：controlURL 是绝对路径还是 `_urn:…_control` 这类相对写法、
            // 服务类型是 v1 还是 v2，都只能从原文看出来。
            CastNetworkLog.log("probe-desc-raw ${SoapClient.snippet(xml, RAW_DESC_LIMIT)}")
            if (desc.services.isEmpty()) {
                CastNetworkLog.log("probe-desc 未解析到任何控制端点（库将退化为猜路径）")
                return@withContext Report(descriptionOk = true, hasRenderingControl = false)
            }

            desc.services.forEach { svc ->
                val resolved = resolveControlUrl(locationUrl, svc.controlUrl)
                CastNetworkLog.log("probe-svc ${shortType(svc.serviceType)} raw=${svc.controlUrl} resolved=$resolved")
            }

            probeAvTransport(desc, locationUrl)
            probeSinkFormats(desc, locationUrl)
            val currentVolume = probeVolume(desc, locationUrl)
            probeLibraryFallback(desc, locationUrl)
            Report(
                descriptionOk = true,
                hasRenderingControl = desc.services.any { it.serviceType.contains("RenderingControl", true) },
                currentVolume = currentVolume
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "probe failed", e)
            CastNetworkLog.log("probe-error ${e.javaClass.simpleName} ${e.message}")
            Report(descriptionOk = false, hasRenderingControl = false)
        }
    }

    /** 只读：读当前传输状态，用于判定控制地址是否真的通 */
    private fun probeAvTransport(desc: Description, locationUrl: String) {
        val svc = desc.services.firstOrNull { it.serviceType.contains("AVTransport", true) }
        if (svc == null) {
            CastNetworkLog.log("probe-avt 缺失：设备描述无 AVTransport 服务（可能是媒体服务器而非渲染器）")
            return
        }
        val url = resolveControlUrl(locationUrl, svc.controlUrl) ?: run {
            CastNetworkLog.log("probe-avt 控制地址无法解析 raw=${svc.controlUrl}")
            return
        }
        val body = "<u:GetTransportInfo xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID></u:GetTransportInfo>"
        val r = SoapClient.post(url, "$AVT_NS#GetTransportInfo", body)
        CastNetworkLog.log("probe-avt http=${r.status} body=${SoapClient.snippet(r.body)}")
    }

    /** 只读：读渲染器声明支持的格式清单 */
    private fun probeSinkFormats(desc: Description, locationUrl: String) {
        val svc = desc.services.firstOrNull { it.serviceType.contains("ConnectionManager", true) }
        if (svc == null) {
            CastNetworkLog.log("probe-sink 缺失：设备描述无 ConnectionManager 服务")
            return
        }
        val url = resolveControlUrl(locationUrl, svc.controlUrl) ?: run {
            CastNetworkLog.log("probe-sink 控制地址无法解析 raw=${svc.controlUrl}")
            return
        }
        val body = "<u:GetProtocolInfo xmlns:u=\"$CMS_NS\"></u:GetProtocolInfo>"
        val r = SoapClient.post(url, "$CMS_NS#GetProtocolInfo", body)
        val sink = sinkProtocols(r.body)
        CastNetworkLog.log("probe-sink http=${r.status} ${formatSupport(sink)}")
        if (sink.isEmpty() && r.ok) {
            // 空清单 ≠ 不支持：不少接收端根本不填 SinkProtocolInfo，把「未声明」
            // 当成「不支持」会把排查引向转码，方向就错了。
            CastNetworkLog.log("probe-sink 该渲染器未声明 SinkProtocolInfo（空清单，不能据此判格式不支持）")
        }
        if (sink.isNotEmpty()) CastNetworkLog.log("probe-sink-list ${SoapClient.snippet(sink)}")
    }

    /**
     * 只读：音量能力取证（`GetVolume` 回读 + SCPD 里 `Volume` 的取值范围）。
     *
     * 这两件事合起来才能回答「为什么调了没反应」：
     * - 取值上限不是 100 → 我们之前发的是越界值，被静默截断（这是**能修**的）；
     * - 回读值与请求值无关、恒为同一个数 → 接收端收下请求却不执行（只能提示用遥控器）。
     */
    /** @return 设备当前音量（0~100 百分比，已按 SCPD 量程折算）；null = 未读到 */
    private fun probeVolume(desc: Description, locationUrl: String): Int? {
        val svc = desc.services.firstOrNull { it.serviceType.contains("RenderingControl", true) }
        if (svc == null) {
            CastNetworkLog.log("probe-vol 缺失：设备描述无 RenderingControl（DLNA 无法调音量）")
            return null
        }
        val url = resolveControlUrl(locationUrl, svc.controlUrl) ?: run {
            CastNetworkLog.log("probe-vol 控制地址无法解析 raw=${svc.controlUrl}")
            return null
        }
        val body = "<u:GetVolume xmlns:u=\"$RC_NS\"><InstanceID>0</InstanceID><Channel>Master</Channel></u:GetVolume>"
        val r = SoapClient.post(url, "$RC_NS#GetVolume", body)
        val raw = SoapClient.xmlValue(r.body, "CurrentVolume")?.toIntOrNull()
        CastNetworkLog.log("probe-vol http=${r.status} current=${raw ?: "-"}")
        val scpd = ControlUrls.join(locationUrl, svc.scpdUrl)
        val max = if (scpd == null) {
            CastNetworkLog.log("probe-vol scpd=无法解析 raw=${svc.scpdUrl}")
            DlnaTransport.DEFAULT_VOLUME_MAX
        } else {
            val scpdResult = SoapClient.get(scpd)
            val parsed = DlnaTransport.parseVolumeMax(scpdResult.body)
            CastNetworkLog.log(
                "probe-vol scpd http=${scpdResult.status} 音量上限=${parsed ?: "未声明（按 100 处理）"}"
            )
            parsed ?: DlnaTransport.DEFAULT_VOLUME_MAX
        }
        return raw?.let { DlnaTransport.toPercent(it, max) }
    }

    /**
     * 只读：库在「服务列表解析为空」时会退化成 `http://ip:port/AVTransport/control`。
     *
     * 探一下这个猜路径，用来区分「库解析到了服务但报文被拒」与「库压根没解析到服务」——
     * 后者无论报文怎么写都会 404，改报文是白费力气。
     */
    private fun probeLibraryFallback(desc: Description, locationUrl: String) {
        val host = ControlUrls.authorityOf(locationUrl) ?: return
        val guessed = "$host/AVTransport/control"
        val body = "<u:GetTransportInfo xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID></u:GetTransportInfo>"
        val r = SoapClient.post(guessed, "$AVT_NS#GetTransportInfo", body)
        val real = desc.services.firstOrNull { it.serviceType.contains("AVTransport", true) }
            ?.let { resolveControlUrl(locationUrl, it.controlUrl) }
        CastNetworkLog.log(
            "probe-fallback url=$guessed http=${r.status} " +
                "real=${if (real == null) "none" else if (real == guessed) "same" else "different"}"
        )
    }

    // ---------------------------------------------------------------- 纯函数（可单测）

    /** 解析设备描述：friendlyName / deviceType / 所有 `<service>` 块 */
    fun parseDescription(xml: String): Description = Description(
        friendlyName = SoapClient.xmlValue(xml, "friendlyName") ?: "?",
        deviceType = SoapClient.xmlValue(xml, "deviceType") ?: "?",
        services = parseServices(xml)
    )

    /**
     * 取所有 `<service>` 块（含全部服务类型，不做过滤）。
     *
     * 与库不同：库只保留 AVTransport / RenderingControl / ConnectionManager，
     * 这里全量保留，才能在诊断里看出「设备到底暴露了什么」。
     */
    fun parseServices(xml: String): List<Service> =
        SERVICE_BLOCK.findAll(xml).mapNotNull { m ->
            val block = m.groupValues[1]
            val type = SoapClient.xmlValue(block, "serviceType") ?: return@mapNotNull null
            Service(
                serviceType = type,
                controlUrl = SoapClient.xmlValue(block, "controlURL") ?: "",
                scpdUrl = SoapClient.xmlValue(block, "SCPDURL") ?: ""
            )
        }.toList()

    /**
     * 把描述里的 `controlURL` 解析成绝对地址。
     *
     * 实现在 [ControlUrls.join]：**不能用 `java.net.URI.resolve`**——真机上存在
     * `_urn:schemas-upnp-org:service:AVTransport_control` 这种相对写法，
     * JDK 会因「非法 scheme 首字符」直接抛异常（详见该类注释）。
     */
    fun resolveControlUrl(locationUrl: String, raw: String): String? = ControlUrls.join(locationUrl, raw)

    /** 服务类型标签：诊断里看全名太长，只取末尾的短名 */
    fun shortType(serviceType: String): String = when {
        serviceType.contains("AVTransport", true) -> "AVTransport"
        serviceType.contains("RenderingControl", true) -> "RenderingControl"
        serviceType.contains("ConnectionManager", true) -> "ConnectionManager"
        else -> serviceType.substringAfterLast(':').ifBlank { "unknown" }
    }

    /** 从 GetProtocolInfo 响应里取 SinkProtocolInfo（渲染器能拉的格式清单） */
    fun sinkProtocols(soapResponse: String): String =
        SoapClient.xmlValue(soapResponse, "SinkProtocolInfo")?.trim().orEmpty()

    /** 把格式清单压成一行「支持/不支持」结论 */
    fun formatSupport(sink: String): String {
        val s = sink.lowercase()
        fun has(vararg keys: String) = if (keys.any { s.contains(it) }) "yes" else "no"
        return "mp3=${has("audio/mpeg", "audio/mp3")} " +
            "flac=${has("audio/flac", "audio/x-flac")} " +
            "wav=${has("audio/x-wav", "audio/wav")} " +
            "m4a=${has("audio/mp4", "audio/aac")} " +
            "mp4=${has("video/mp4")}"
    }

    private val SERVICE_BLOCK = Regex(
        "<service>(.*?)</service>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
}

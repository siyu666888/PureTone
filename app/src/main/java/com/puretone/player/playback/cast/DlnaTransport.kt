package com.puretone.player.playback.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自有 DLNA 控制通道（AVTransport + RenderingControl）。
 *
 * ## 什么时候用它
 *
 * 库的 `DLNACast.castLocalFile()` 失败后作为**兜底通道**：库把所有 SOAP 失败
 * 压成一句不带 cause 的 `DeviceError`，既不告诉我们断在哪一步，也不给我们改的余地
 * （报文、地址、取流服务全在库内部）。本类把整条链路握在自己手里：
 *
 * 1. 用设备描述里的 `controlURL` 算控制地址（[ControlUrls]，不依赖 `URI.resolve`）；
 * 2. `SetAVTransportURI` 按 [DidlBuilder.protocolInfoVariants] **逐档试**，
 *    每次的真实状态码与 fault 正文都落盘；
 * 3. `Play` 同样落盘。
 *
 * 于是「电视拒绝投送」不再是黑箱：诊断里能看到「第几档 protocolInfo 被拒、
 * 返回 500 还是 404、fault 说的什么」。
 */
internal class DlnaTransport(private val locationUrl: String, private val deviceLabel: String) {

    /** 设备描述里与本项目相关的一组控制端点 */
    data class Endpoints(
        val avTransport: String,
        val renderingControl: String?,
        /** RenderingControl 的 SCPD 地址：音量取值上限只能从这里读，缺失时按 0~100 处理 */
        val renderingControlScpd: String? = null
    )

    private var cached: Endpoints? = null

    /** 设备声明的音量上限（null=尚未读取；缓存避免每次调音量都去取 SCPD） */
    private var volumeMax: Int? = null

    /**
     * 解析控制端点（首次调用取一次设备描述并缓存）。
     *
     * 缓存是刻意的：部分接收端（本次的「多屏互动」）在**拉流期间**控制服务会变得
     * 极慢甚至不回包，若每次调音量都重取描述就会必然超时（真机日志里表现为
     * `probe-vol 描述不可达 http=-1`）。
     */
    suspend fun endpoints(): Endpoints? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        val desc = SoapClient.get(locationUrl, diagnostic = CastTargetDiagnostics::emit)
        if (!desc.ok) {
            CastNetworkLog.log("own-desc http=${desc.status} url=$locationUrl")
            return@withContext null
        }
        val parsed = CastProbe.parseDescription(desc.body)
        val avt = resolve(parsed, "AVTransport")
        val rc = resolve(parsed, "RenderingControl")
        val rcScpd = resolveScpd(parsed, "RenderingControl")
        CastNetworkLog.log("own-desc device=$deviceLabel services=${parsed.services.size} avt=$avt rc=$rc")
        if (avt == null) return@withContext null
        Endpoints(avt, rc, rcScpd).also { cached = it }
    }

    private fun resolve(desc: CastProbe.Description, keyword: String): String? {
        val raw = desc.services.firstOrNull { it.serviceType.contains(keyword, ignoreCase = true) }?.controlUrl
            ?: return null
        return ControlUrls.join(locationUrl, raw, CastNetworkLog::log)
    }

    private fun resolveScpd(desc: CastProbe.Description, keyword: String): String? {
        val raw = desc.services.firstOrNull { it.serviceType.contains(keyword, ignoreCase = true) }?.scpdUrl
        if (raw.isNullOrBlank()) return null
        return ControlUrls.join(locationUrl, raw, CastNetworkLog::log)
    }

    /**
     * 逐档尝试 `SetAVTransportURI`，返回**成功的那一档**；全失败返回 null。
     *
     * 每一档的结果都落盘（`own-seturi idx=… pi=… http=… fault=…`），
     * 这样下一次诊断能直接看出渲染器到底卡在哪种报文上。
     */
    suspend fun setAvTransportUri(mediaUrl: String, title: String, mimeType: String, upnpClass: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = endpoints()?.avTransport ?: return@withContext false
            val variants = DidlBuilder.protocolInfoVariants(mimeType)
            for ((index, protocolInfo) in variants.withIndex()) {
                val didl = DidlBuilder.build(title, mediaUrl, protocolInfo, upnpClass)
                val body = "<u:SetAVTransportURI xmlns:u=\"$AVT_NS\">" +
                    "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>${SoapClient.escape(mediaUrl)}</CurrentURI>" +
                    "<CurrentURIMetaData>${SoapClient.escape(didl)}</CurrentURIMetaData>" +
                    "</u:SetAVTransportURI>"
                val result = SoapClient.post(url, "$AVT_NS#SetAVTransportURI", body, diagnostic = CastTargetDiagnostics::emit)
                CastNetworkLog.log(
                    "own-seturi idx=${index + 1}/${variants.size} pi=${protocolInfo.take(24)} " +
                        "http=${result.status} fault=${if (result.ok) "-" else SoapClient.snippet(result.body)}"
                )
                if (result.ok) return@withContext true
            }
            false
        }

    suspend fun play(): Boolean = action("Play", "<Speed>1</Speed>")
    suspend fun pause(): Boolean = action("Pause")
    suspend fun stop(): Boolean = action("Stop")
    suspend fun seek(positionMs: Long): Boolean =
        action("Seek", "<Unit>REL_TIME</Unit><Target>${formatTime(positionMs)}</Target>")

    private suspend fun action(name: String, extra: String = ""): Boolean = withContext(Dispatchers.IO) {
        val url = endpoints()?.avTransport ?: run {
            // 端点没了还谈什么「命令下发成功」：这一行让诊断能区分「没发」与「发了没生效」
            CastNetworkLog.log("own-$name skipped=no-endpoint")
            return@withContext false
        }
        val body = "<u:$name xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID>$extra</u:$name>"
        val result = SoapClient.post(url, "$AVT_NS#$name", body, diagnostic = CastTargetDiagnostics::emit)
        // 成功也落盘：过去只有失败才写日志，诊断报告里看不到手动 Play/Pause 是否下发，
        // 只能靠 own-state 反推（20261107_165555 的盲区）。命令不频繁，不会刷爆日志。
        if (result.ok) {
            CastNetworkLog.log("own-$name http=${result.status} ok=true")
        } else {
            CastNetworkLog.log("own-$name http=${result.status} fault=${SoapClient.snippet(result.body)}")
        }
        result.ok
    }

    /** 当前传输状态（PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING / NO_MEDIA_PRESENT） */
    suspend fun transportState(): String? = withContext(Dispatchers.IO) {
        val url = endpoints()?.avTransport ?: return@withContext null
        val body = "<u:GetTransportInfo xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID></u:GetTransportInfo>"
        val result = SoapClient.post(url, "$AVT_NS#GetTransportInfo", body, diagnostic = CastTargetDiagnostics::emit)
        if (!result.ok) return@withContext null
        SoapClient.xmlValue(result.body, "CurrentTransportState")
    }

    /** 进度：(当前位置 ms, 总时长 ms) */
    suspend fun position(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val url = endpoints()?.avTransport ?: return@withContext null
        val body = "<u:GetPositionInfo xmlns:u=\"$AVT_NS\"><InstanceID>0</InstanceID></u:GetPositionInfo>"
        val result = SoapClient.post(url, "$AVT_NS#GetPositionInfo", body, diagnostic = CastTargetDiagnostics::emit)
        if (!result.ok) return@withContext null
        val current = parseTime(SoapClient.xmlValue(result.body, "RelTime") ?: "")
        val total = parseTime(SoapClient.xmlValue(result.body, "TrackDuration") ?: "")
        current to total
    }

    /**
     * 下发音量（入参是 **0~100 的百分比**，内部按设备真实上限换算）。
     *
     * 换算不是可选优化：真机上极光TV 的 `GetVolume` 恒回读 18、而我们的请求从
     * 80 到 93 全是 `http=200` —— 典型的「越界值被静默截断」。所以先读 SCPD
     * 拿到 `Volume` 的上限（可能是 30/255 而不是 100）再换算，才有机会真正生效。
     *
     * 控制地址缺失时返回 null（调用方据此退回库的通道）。
     */
    suspend fun setVolume(volume: Int): Boolean? = withContext(Dispatchers.IO) {
        val url = endpoints()?.renderingControl ?: return@withContext null
        val max = deviceVolumeMax()
        val scaled = toDeviceVolume(volume, max)
        val body = "<u:SetVolume xmlns:u=\"$RC_NS\"><InstanceID>0</InstanceID>" +
            "<Channel>Master</Channel><DesiredVolume>$scaled</DesiredVolume></u:SetVolume>"
        val result = SoapClient.post(url, "$RC_NS#SetVolume", body, diagnostic = CastTargetDiagnostics::emit)
        CastNetworkLog.log(
            "own-vol-set $volume% -> $scaled/$max http=${result.status} " +
                "fault=${if (result.ok) "-" else SoapClient.snippet(result.body)}"
        )
        result.ok
    }

    /**
     * 回读音量（返回 **0~100 的百分比**，已按设备上限折算）。
     *
     * 同时把响应原文落盘：只有看到设备自己报的 `CurrentVolume`，才能区分
     * 「我们发错了」与「它收下不做」。读不到返回 null（与「音量为 0」严格区分）。
     */
    suspend fun volume(): Int? = withContext(Dispatchers.IO) {
        val url = endpoints()?.renderingControl ?: return@withContext null
        val max = deviceVolumeMax()
        val body = "<u:GetVolume xmlns:u=\"$RC_NS\"><InstanceID>0</InstanceID><Channel>Master</Channel></u:GetVolume>"
        val result = SoapClient.post(url, "$RC_NS#GetVolume", body, diagnostic = CastTargetDiagnostics::emit)
        val raw = SoapClient.xmlValue(result.body, "CurrentVolume")
        CastNetworkLog.log(
            "own-vol-get http=${result.status} current=$raw/${max} body=${SoapClient.snippet(result.body, 120)}"
        )
        if (!result.ok) return@withContext null
        raw?.toIntOrNull()?.let { toPercent(it, max) }
    }

    /**
     * 设备声明的音量上限（读 RenderingControl 的 SCPD，进程内缓存一次）。
     *
     * 读不到就按 100 处理：宁可发标准值，也不要凭猜测压缩量程。
     */
    private suspend fun deviceVolumeMax(): Int {
        volumeMax?.let { return it }
        val scpd = endpoints()?.renderingControlScpd
        if (scpd == null) {
            CastNetworkLog.log("own-vol scpd 缺失（设备未声明 SCPDURL，音量按 0~100 处理）")
            volumeMax = DEFAULT_VOLUME_MAX
            return DEFAULT_VOLUME_MAX
        }
        val result = SoapClient.get(scpd, diagnostic = CastTargetDiagnostics::emit)
        val max = if (result.ok) parseVolumeMax(result.body) else null
        CastNetworkLog.log(
            "own-vol scpd http=${result.status} 音量上限=${max ?: "未声明（按 $DEFAULT_VOLUME_MAX 处理）"}"
        )
        val resolved = max ?: DEFAULT_VOLUME_MAX
        volumeMax = resolved
        return resolved
    }

    companion object {
        private const val AVT_NS = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RC_NS = "urn:schemas-upnp-org:service:RenderingControl:1"

        /** 渲染器未声明音量量程时的默认上限（UPnP RenderingControl 的标准约定） */
        const val DEFAULT_VOLUME_MAX = 100

        /** 百分比 → 设备量程（四舍五入，越界钳死） */
        fun toDeviceVolume(percent: Int, max: Int): Int {
            if (max <= 0) return percent.coerceIn(0, 100)
            return Math.round(percent.coerceIn(0, 100) * max / 100f).coerceIn(0, max)
        }

        /** 设备量程 → 百分比（四舍五入，越界钳死） */
        fun toPercent(raw: Int, max: Int): Int {
            if (max <= 0) return raw.coerceIn(0, 100)
            return Math.round(raw.coerceIn(0, max) * 100f / max).coerceIn(0, 100)
        }

        /**
         * 从 RenderingControl 的 SCPD 里取 `Volume` 状态变量的 `maximum`。
         *
         * SCPD 是一串 `<stateVariable>`，只有 `name=Volume` 那一个的 `allowedValueRange`
         * 描述音量量程；其它变量的 maximum 与音量无关，不能顺手取第一个。
         */
        fun parseVolumeMax(scpd: String): Int? =
            STATE_VARIABLE.findAll(scpd)
                .map { it.groupValues[1] }
                .firstOrNull { SoapClient.xmlValue(it, "name").equals("Volume", ignoreCase = true) }
                ?.let { SoapClient.xmlValue(it, "maximum")?.toIntOrNull() }
                ?.takeIf { it > 0 }

        private val STATE_VARIABLE = Regex(
            "<stateVariable[^>]*>(.*?)</stateVariable>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        /**
         * 解析 UPnP 时间串（`H+:MM:SS[.f+]`）。
         *
         * 非法/空串返回 0——这个值只用于进度展示与「播完」判定，
         * 解析不出时当作「位置未知」，由调用方看 [position] 是否为 null 区分。
         */
        fun parseTime(text: String): Long {
            val parts = text.trim().split(':')
            if (parts.size < 3) return 0L
            val hours = parts[0].toLongOrNull() ?: return 0L
            val minutes = parts[1].toLongOrNull() ?: return 0L
            val seconds = parts[2].substringBefore('.').toLongOrNull() ?: return 0L
            return ((hours * 60 + minutes) * 60 + seconds) * 1000
        }

        /** 毫秒 → `HH:MM:SS` */
        fun formatTime(ms: Long): String {
            val total = (ms.coerceAtLeast(0L)) / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return "%02d:%02d:%02d".format(h, m, s)
        }

        /** 把渲染器的传输状态映射成 [CastState.playbackState] 用的枚举名 */
        fun stateName(raw: String?): String = when (raw?.uppercase()) {
            "PLAYING" -> "PLAYING"
            "PAUSED_PLAYBACK", "PAUSED_RECORDING", "PAUSED" -> "PAUSED"
            "TRANSITIONING" -> "BUFFERING"
            "STOPPED" -> "STOPPED"
            "NO_MEDIA_PRESENT" -> "IDLE"
            null -> "IDLE"
            else -> raw.uppercase()
        }
    }
}

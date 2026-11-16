package com.puretone.player.data.metadata

import java.net.URI
import java.util.Locale

/**
 * 联网封面 URL 的准入策略（第五轮复审 A2）。
 *
 * 背景：外部 API 返回的 `artworkUrl100` 会直接落进 `songs.cover_uri`，之后交给 Coil
 * 发 GET；而 NSC 的 `<base-config cleartextTrafficPermitted="true">` 是 DLNA 局域网
 * 投屏的既定取舍，无法按网段收窄。若响应被代理 / DNS 劫持，或字段被改成任意主机，
 * App 就会成为内网探测跳板 / 发出明文请求。因此**应用层必须自己校验来源**，
 * `network_security_config.xml` 的文件头也写明了这条补偿控制。
 *
 * 判据（只约束网络 URL，本地封面一律原样放行）：
 * - `http://` 一律拒绝（已知公网端点已被 NSC 拦，任意主机更要拒）；
 * - `https://` 要求 host 命中 `mzstatic.com` / `apple.com` 或其子域，且不带
 *   userinfo、不带非 443 端口、总长不超过 [MAX_URL_LENGTH]；
 * - `content://` / `file://` / 无 scheme 等本地来源不参与网络校验：MediaStore 的
 *   albumart 占位 URI、用户手动选择的封面、以及「撤销自动填充」恢复的原值都靠这条
 *   通道保真，不能因为新增策略被误清空；其余未知协议（ftp 等）一律拒绝。
 *
 * 用 `java.net.URI` 而非 `android.net.Uri`，使策略可纯 JVM 单测，不依赖 Robolectric。
 */
internal object CoverUrlPolicy {

    const val MAX_URL_LENGTH = 2048

    private val ALLOWED_HOSTS = listOf("mzstatic.com", "apple.com")
    private val LOCAL_SCHEMES = setOf("content", "file", "android.resource", "res", "data")

    /** 不合格返回 null（调用方据此不写库）；合格返回去掉首尾空白的原串 */
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        val schemeEnd = value.indexOf(':')
        if (schemeEnd < 0) return value // 无 scheme：本地路径 / 相对标识
        val scheme = value.substring(0, schemeEnd).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return if (scheme in LOCAL_SCHEMES) value else null
        }
        if (value.length > MAX_URL_LENGTH) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (scheme == "http") return null
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return if (ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }) value else null
    }

    fun isAllowed(raw: String?): Boolean = sanitize(raw) != null
}

package com.puretone.player.data.metadata

/**
 * 联网元数据 / 歌词请求的统一配置（P2-3：收敛硬编码常量）。
 *
 * 此前超时值散落在 [ItunesMetadataProvider]（2 处）与 [LrcLibLyricsProvider]（4 处），
 * 一律写死 8000ms。调参必须跨文件搜索，且改一处漏三处的风险实在——这里收敛为
 * 单一真源，任何调用方都从本对象取。
 *
 * 超时取值的考量：
 * - 8s 是两条链路的共同折中：iTunes Search 与 LRCLIB 均为境外接口，弱网下
 *   握手常需 2~3s，8s 既能让正常请求稳定完成，又不至于让 UI 长时间转圈；
 * - **连接超时与读超时必须分别设**：连接超时管「能不能连上」（DNS + TCP + TLS），
 *   读超时管「连上后多久没数据」。两者混为一谈会在「连上但服务端迟迟不响应」
 *   时退化成无限等待——读超时不生效，协程挂死。
 */
object NetworkConfig {

    /** 建立连接（DNS + TCP + TLS 握手）的超时（毫秒） */
    const val CONNECT_TIMEOUT_MS: Int = 8_000

    /** 连接建立后等待响应数据的超时（毫秒） */
    const val READ_TIMEOUT_MS: Int = 8_000

    /** User-Agent：便于服务端识别来源，也便于运营方在异常时联系（LRCLIB 明确要求） */
    const val USER_AGENT: String = "PureTone (https://github.com/puretone)"

    /** iTunes Search API 根地址 */
    const val ITUNES_SEARCH_BASE: String = "https://itunes.apple.com/search"

    /** LRCLIB 精确匹配接口 */
    const val LRCLIB_GET_URL: String = "https://lrclib.net/api/get"

    /** LRCLIB 搜索兜底接口 */
    const val LRCLIB_SEARCH_URL: String = "https://lrclib.net/api/search"
}

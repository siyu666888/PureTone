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

    /**
     * 响应体上限（字节）：与投屏通道 `SoapClient.MAX_BODY_BYTES` 同为 256 KiB。
     *
     * 刻意不直接引用 `SoapClient` 的常量：那会把 `data/metadata` 反向挂到 `playback/cast`，
     * 让歌词渠道依赖投屏实现。两处口径在各自 KDoc 里互相注明，改一处时须同步另一处。
     * 正常歌词 / iTunes JSON 远小于该值，仅用于拦住异常服务端把内存吃满。
     */
    const val MAX_RESPONSE_BYTES: Int = 256 * 1024

    /** iTunes Search API 根地址 */
    const val ITUNES_SEARCH_BASE: String = "https://itunes.apple.com/search"

    /** LRCLIB 精确匹配接口 */
    const val LRCLIB_GET_URL: String = "https://lrclib.net/api/get"

    /** LRCLIB 搜索兜底接口 */
    const val LRCLIB_SEARCH_URL: String = "https://lrclib.net/api/search"

    /** AMLL TTML 词库搜索接口（开源、免鉴权；华语/日语覆盖好） */
    const val AMLL_SEARCH_URL: String = "https://api.amll.dev/v1/lyrics/search"

    /** AMLL TTML 词库取全文接口（按 id 取 TTML，需再转 LRC） */
    const val AMLL_GET_URL: String = "https://api.amll.dev/v1/lyrics/get"
}

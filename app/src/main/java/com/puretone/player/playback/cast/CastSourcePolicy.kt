package com.puretone.player.playback.cast

/**
 * 投屏源格式策略：判断某首歌的**源文件**能否原样交给 DLNA 渲染器拉流。
 *
 * 背景（M2）：M1 的 [DlnaCastManager.castSong] 一律 `castLocalFile(song.path)` 直投，
 * 于是 APE 这类「ExoPlayer 本地能放（靠转码）/ 渲染器解不了」的格式投过去只会得到
 * 一个电视端「不支持该格式」或黑屏。M2 的分流点就是这里：
 * - 命中 [DIRECT_EXTENSIONS] → 源文件直投（零转码，秒开）；
 * - 其余 → 先转成渲染器一定能解的 PCM WAV，再投（走 [CastFilePreparer]）。
 *
 * 为什么是白名单而不是「只转 APE」：
 * - DLNA 渲染器的解码能力参差不齐，白名单是**可解释的保守承诺**——名单内的格式
 *   几乎全部电视/音箱都支持，名单外的（ogg/opus/aiff/wma/dsf…）走了转码反而更稳；
 * - 白名单也覆盖了「M1 曾投成功」的全部格式，行为向后兼容。
 *
 * 为什么白名单里没有 m4a 之外的无损封装（如 .alac 独立扩展名）：不存在这种扩展名，
 * ALAC 装在 .m4a 里，无法从扩展名区分；渲染器不解 ALAC 时会自行拒绝，属已知限制。
 */
object CastSourcePolicy {

    /**
     * 可直接投流、无需转码的扩展名（小写）。
     *
     * - mp3 / aac：DLNA 事实标准；
     * - flac：国内电视与 Hi-Fi 网播普遍支持，且本项目的损失受众主要用它；
     * - wav：PCM 基线与 [CastFilePreparer] 的转码产物同款；
     * - m4a / mp4：UPnPCast 内置 MIME 表已将二者映射到 audio/mp4、video/mp4。
     */
    private val DIRECT_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "mp4")

    /**
     * 取文件扩展名（小写，不含点）；无法判定时返回 null。
     *
     * 注意只认**文件名最后一段**的扩展名：`/sdcard/Music.2024/song` 这类
     * 目录名带点的路径不能被误判出扩展名，否则会拿错误的格式去匹配白名单。
     * 同时兼容 Windows 风格反斜杠（测试/导入歌单可能带过来）。
     */
    fun extensionOf(path: String): String? {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        // dot <= 0：无点，或形如 ".hidden" 的隐藏文件（点在最前，无扩展名语义）
        // dot == name.lastIndex：形如 "song." 的尾点，同样不算扩展名
        if (dot <= 0 || dot == name.lastIndex) return null
        return name.substring(dot + 1).lowercase()
    }

    /**
     * 投屏前是否需要转码。
     *
     * 无法识别扩展名时返回 true——渲染器拿不到可信 MIME 大概率直接拒绝，
     * 与其投一个必失败的文件，不如让 ffmpeg 试一次转码（失败由上层给出错误提示）。
     */
    fun requiresTranscode(path: String): Boolean {
        val ext = extensionOf(path) ?: return true
        return ext !in DIRECT_EXTENSIONS
    }
}

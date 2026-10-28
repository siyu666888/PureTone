package com.puretone.player.playback

/**
 * APE 转码缓存的容量策略。
 *
 * 抽成可注入对象（而非硬编码常量）的原因：上限是**策略**，测试需要用极小值
 * 验证 LRU 淘汰行为，而 2GB 的真实上限在单测里根本造不出那么大的文件。
 * 生产默认值由 `di/TranscoderModule` 提供，测试直接构造即可。
 */
data class ApeCachePolicy(
    /**
     * 缓存总字节上限，超出后按 LRU 淘汰最久未播放的曲目。
     *
     * 默认值 2GB：WAV（16bit/44.1kHz/立体声）约 10MB/分钟，2GB ≈ 200 分钟。
     * 按文件数计上限（旧实现 100 个）在长曲/无损现场录音下可到数 GB，撑爆 cacheDir。
     */
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024
    }
}

package com.puretone.player.playback

import android.net.Uri
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * APE 按需转码的**统一入口**（P1-1 拆分第三刀）。
 *
 * 从 [PlaybackManager] 抽出这一簇的直接原因：同一个「开转码标记 → ensureWav →
 * 关转码标记」的模式在三处各写了一遍（设置队列 / 点播队列项 / 播放出错重试），
 * 而三处的异常与取消语义并不一致：
 * - `handlePlayerError` 用了 `try/finally` 保证 [onTranscoding] 复位；
 * - 另外两处只靠 `runCatching`，一旦中间抛出就没走复位路径，
 *   UI 会永久停在「APE 转码中…」（历史上确实出现过）。
 *
 * 因此本类把所有路径收敛到一条：
 * 1. [onTranscoding] 的开关包在 `finally` 里，**任何**退出路径都会复位；
 * 2. 转码异常收敛为 null（调用方按「转码失败」处理），但
 *    [CancellationException] **原样抛出**——协程取消不是失败，吞掉它会让
 *    已取消的任务继续往下执行（例如取消后仍然 `setMediaItems` 覆盖用户的队列）。
 *
 * 非 APE 曲目由 [resolveUri] 直接返回 MediaStore URI，不触碰转码层。
 *
 * 依赖全部通过构造参数注入（转码 lambda + 标记回调），因此不持有 Context、
 * 不依赖 ffmpeg-kit，可在纯 JVM 单测里验证上述契约。
 */
class ApePlaybackResolver(
    private val transcode: suspend (songId: Long, sourcePath: String) -> File?,
    private val onTranscoding: (transcoding: Boolean) -> Unit,
) {

    /**
     * 解析可直接交给 ExoPlayer 的播放 URI。
     *
     * @param isApe 是否 APE（走转码）
     * @param mediaStoreUri 非 APE 时使用的 URI 提供者（由调用方决定，保持与
     *        [PlaybackMediaItems.mediaStoreUri] 一致；做成 lambda 是为了让本类
     *        不直接依赖 `SongEntity`，测试构造更简单）
     * @return 转码失败或产物不可用时为 null
     */
    suspend fun resolveUri(
        songId: Long,
        path: String,
        isApe: Boolean,
        mediaStoreUri: () -> Uri,
    ): Uri? {
        if (!isApe) return mediaStoreUri()
        val wav = transcodeToFile(songId, path) ?: return null
        return Uri.fromFile(wav)
    }

    /**
     * 只做转码并返回 WAV 文件（调用方需要自己替换 media item 时用，如「点播队列项」
     * 与「出错重试」两条路径——它们要保留原索引位置）。
     *
     * @return 失败/超时为 null；协程取消时抛出 [CancellationException]
     */
    suspend fun transcodeToFile(songId: Long, path: String): File? {
        onTranscoding(true)
        return try {
            try {
                transcode(songId, path)
            } catch (ce: CancellationException) {
                // 取消必须向上传播：否则调用方会在「已被取消」的上下文里继续改队列/起播
                throw ce
            } catch (t: Throwable) {
                // IO / FFmpegKit / 原生库缺失等一律视为转码失败，由调用方给出用户提示
                null
            }
        } finally {
            onTranscoding(false)
        }
    }
}

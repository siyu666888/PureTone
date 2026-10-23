package com.puretone.player.playback

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * APE（Monkey's Audio）懒转码器。
 *
 * 背景：ExoPlayer 不支持 APE 解码，而需求将 APE 列入 P0（决策 D3）。
 * 方案：首次播放某首 APE 时，经 FFmpegKit 转码为 16bit PCM WAV 缓存至
 * cacheDir/ape_transcode/，随后由 ExoPlayer 原生播放 WAV。
 *
 * - 转码结果按歌曲 id 缓存，重复播放零开销；
 * - 缓存上限 [MAX_CACHE_FILES] 个文件，超出时按最近修改时间淘汰；
 * - 全程 IO 线程，调用方负责在 UI 上展示转码中状态。
 */
@Singleton
class ApeTranscoder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val cacheDir: File
        get() = File(context.cacheDir, "ape_transcode")

    /** 目标 WAV 是否已缓存 */
    fun isCached(songId: Long): Boolean =
        File(cacheDir, fileName(songId)).let { it.exists() && it.length() > 0 }

    /**
     * 确保歌曲已转码为 WAV 并返回文件；失败返回 null。
     * 已缓存则直接返回（幂等）。
     */
    suspend fun ensureWav(songId: Long, sourcePath: String): File? =
        withContext(Dispatchers.IO) {
            val target = File(cacheDir, fileName(songId))
            if (target.exists() && target.length() > 0) {
                touch(target)
                return@withContext target
            }
            val source = File(sourcePath)
            if (!source.exists()) return@withContext null

            cacheDir.mkdirs()
            val command = "-y -i \"${source.absolutePath}\" " +
                "-c:a pcm_s16le -f wav \"${target.absolutePath}\""
            val session = runCatching { FFmpegKit.execute(command) }.getOrNull()
                ?: return@withContext null

            if (ReturnCode.isSuccess(session.returnCode) && target.length() > 0) {
                touch(target)
                trimCache()
                target
            } else {
                target.delete()
                null
            }
        }

    /** 清除全部转码缓存 */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /** 缓存总大小（字节） */
    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun touch(file: File) = file.setLastModified(System.currentTimeMillis())

    /** 超出上限时淘汰最久未使用的缓存 */
    private fun trimCache() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_CACHE_FILES) return
        files.take(files.size - MAX_CACHE_FILES).forEach { it.delete() }
    }

    private fun fileName(songId: Long) = "ape_$songId.wav"

    companion object {
        /** 最多保留 100 首 APE 的转码缓存 */
        const val MAX_CACHE_FILES = 100
    }
}

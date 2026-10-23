package com.puretone.player.playback

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * APE（Monkey's Audio）懒转码器。
 *
 * 背景：ExoPlayer 不支持 APE 解码，而需求将 APE 列入 P0（决策 D3）。
 * 方案：首次播放某首 APE 时，经 FFmpegKit 转码为 16bit PCM WAV 缓存至
 * cacheDir/ape_transcode/，随后由 ExoPlayer 原生播放 WAV。
 *
 * 可靠性设计（P0-5）：
 * - 每首歌一把 [Mutex]：首播、预热（warmUpFollowingApe）、错误重试三条路径
 *   可能并发触发同一首，去重后只会真正转一次——否则两个入口同时写出半截 WAV，
 *   会被后续调用当成「缓存命中」播出一个损坏文件；
 * - 先写 `.tmp`，成功后原子 rename：进程被杀/崩溃不会留下假缓存；
 * - `FFmpegKit.executeWithArguments`：路径含引号/空格不会被截断或注入参数；
 * - 缓存按**总字节数** LRU 淘汰（[MAX_CACHE_BYTES]）：WAV 约 10MB/分钟，
 *   按文件数计上限（旧实现 100 个）在长曲/无损现场录音下可到数 GB，撑爆 cacheDir。
 */
@Singleton
class ApeTranscoder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val cacheDir: File
        get() = File(context.cacheDir, "ape_transcode")

    /** 同一首歌的转码互斥（按 songId 懒创建） */
    private val songLocks = ConcurrentHashMap<Long, Mutex>()

    private fun lockFor(songId: Long): Mutex {
        val lock = songLocks.getOrPut(songId) { Mutex() }
        // 防御：超长 APE 队列后清理未被持有的锁，避免 map 无限增长
        if (songLocks.size > LOCK_MAP_MAX) {
            songLocks.entries.removeAll { it.key != songId && !it.value.isLocked }
        }
        return lock
    }

    /** 目标 WAV 是否已缓存 */
    fun isCached(songId: Long): Boolean =
        File(cacheDir, fileName(songId)).let { it.exists() && it.length() > 0 }

    /**
     * 确保歌曲已转码为 WAV 并返回文件；失败返回 null。
     * 已缓存则直接返回（幂等）；同一首歌并发调用只会真正转码一次。
     */
    suspend fun ensureWav(songId: Long, sourcePath: String): File? =
        withContext(Dispatchers.IO) {
            val target = File(cacheDir, fileName(songId))
            lockFor(songId).withLock {
                if (target.exists() && target.length() > 0) {
                    touch(target)
                    return@withContext target
                }
                val source = File(sourcePath)
                if (!source.exists()) return@withContext null

                cacheDir.mkdirs()
                val tmp = File(cacheDir, "${fileName(songId)}.tmp")
                tmp.delete()
                val session = runCatching {
                    FFmpegKit.executeWithArguments(
                        arrayOf(
                            "-y",
                            "-i", source.absolutePath,
                            "-c:a", "pcm_s16le",
                            "-f", "wav",
                            tmp.absolutePath
                        )
                    )
                }.getOrNull() ?: return@withContext null

                if (ReturnCode.isSuccess(session.returnCode) && tmp.length() > 0) {
                    val ok = tmp.renameTo(target) || runCatching {
                        tmp.copyTo(target, overwrite = true).let { tmp.delete(); true }
                    }.getOrDefault(false)
                    if (!ok) {
                        tmp.delete()
                        return@withContext null
                    }
                    touch(target)
                    trimCache()
                    target
                } else {
                    tmp.delete()
                    null
                }
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

    /** 超出字节上限时淘汰最久未使用的缓存（按 lastModified LRU） */
    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files) {
            if (total <= MAX_CACHE_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun fileName(songId: Long) = "ape_$songId.wav"

    companion object {
        /**
         * 转码缓存字节上限：WAV（16bit/44.1kHz/立体声）约 10MB/分钟，
         * 2GB ≈ 200 分钟，超出后按 LRU 淘汰最久未播放的曲目。
         */
        const val MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024

        /** 锁 map 容量上限（防御性清理阈值） */
        private const val LOCK_MAP_MAX = 128
    }
}

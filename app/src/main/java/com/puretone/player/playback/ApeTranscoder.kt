package com.puretone.player.playback

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * 同一首歌的转码互斥（按 songId 懒创建）。
     *
     * P1-4：必须用 [ConcurrentHashMap.computeIfAbsent]（原子），不能用
     * `getOrPut { Mutex() }`——后者是「get + put」两步非原子：两个线程
     * 可能各建一把锁都 put 进去，同曲并发互斥失效 → 两个 ffmpeg 同写一个
     * `.tmp`，半截 WAV 还会被后续调用当成「缓存命中」播出去。
     *
     * 锁条目永不删除：songId 有界（曲库曲目数），[Mutex] 对象本身极小；
     * 旧实现按 `LOCK_MAP_MAX` 清理会删掉「别人即将持有」的锁，反而制造竞态。
     */
    private fun lockFor(songId: Long): Mutex = songLocks.computeIfAbsent(songId) { Mutex() }

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
                // P3-22：转码带墙钟上限（见 [transcode]），超时会 cancel 掉 ffmpeg
                val transcoded = runCatching { transcode(source, tmp) }.getOrDefault(false)

                if (transcoded) {
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

    /**
     * 执行 ffmpeg 转码并等待结果（P3-22：带墙钟上限）。
     *
     * 旧实现同步 `FFmpegKit.executeWithArguments` 且不设上限：损坏或编码异常的
     * APE 会让 ffmpeg 长时间不返回，该曲永久卡在「转码中」，还一直占着每曲 Mutex
     * （同曲的后续播放请求全部排队等它）。这里改为异步执行 + [withTimeoutOrNull]，
     * 超时主动 cancel 会话，让调用方能正常走失败分支。
     */
    private suspend fun transcode(source: File, tmp: File): Boolean {
        val done = CompletableDeferred<Boolean>()
        val session = FFmpegKit.executeWithArgumentsAsync(
            arrayOf(
                "-y",
                "-i", source.absolutePath,
                "-c:a", "pcm_s16le",
                "-f", "wav",
                tmp.absolutePath
            )
        ) { s -> done.complete(ReturnCode.isSuccess(s.returnCode)) }
        val success = withTimeoutOrNull(FFMPEG_TIMEOUT_MS) { done.await() }
        if (success == null) {
            // 超时：终止 ffmpeg，避免残留进程继续写这个 tmp
            runCatching { FFmpegKit.cancel(session.sessionId) }
            return false
        }
        return success && tmp.length() > 0
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
        // P2-16：排除正在写的 .tmp——它也会被算进总字节并参与 LRU，
        // 缓存接近上限时可能把「正在转码」的临时文件删掉，导致该次转码白做
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() } ?: return
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

        /**
         * 单次转码的墙钟上限（P3-22）。
         * WAV 是无损 PCM，转码本身不重编码，正常是秒级到几十秒；
         * 5 分钟足够覆盖超长单曲与低端机，超出即判定为异常并放弃。
         */
        private const val FFMPEG_TIMEOUT_MS = 5L * 60 * 1000
    }
}

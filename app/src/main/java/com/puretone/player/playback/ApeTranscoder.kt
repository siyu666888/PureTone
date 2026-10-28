package com.puretone.player.playback

import android.content.Context
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
 * APE（Monkey's Audio）懒转码**编排层**。
 *
 * 背景：ExoPlayer 不支持 APE 解码，而需求将 APE 列入 P0（决策 D3）。
 * 方案：首次播放某首 APE 时转码为 16bit PCM WAV 缓存至 cacheDir/ape_transcode/，
 * 随后由 ExoPlayer 原生播放 WAV。
 *
 * **职责边界**：本类只负责「缓存 / 并发 / 生命周期」这类编排逻辑，
 * 真正的转码由注入的 [Transcoder] 完成——ffmpeg-kit 被隔离在
 * [FfmpegKitTranscoder] 里，替换引擎不需要改动本文件。
 *
 * 可靠性设计（P0-5 / P1-4 / P2-16）：
 * - 每首歌一把 [Mutex]：首播、预热（warmUpFollowingApe）、错误重试三条路径
 *   可能并发触发同一首，去重后只会真正转一次——否则两个入口同时写出半截 WAV，
 *   会被后续调用当成「缓存命中」播出一个损坏文件；
 * - 锁条目用 [ConcurrentHashMap.computeIfAbsent] 原子创建（P1-4），
 *   `getOrPut { Mutex() }` 是「get + put」两步非原子，会让互斥失效；
 * - 先写 `.tmp`，成功后原子 rename：进程被杀/崩溃不会留下假缓存；
 * - 缓存按**总字节数** LRU 淘汰（[ApeCachePolicy.maxBytes]）。
 */
@Singleton
class ApeTranscoder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcoder: Transcoder,
    private val cachePolicy: ApeCachePolicy,
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
     * 可能各建一把锁都 put 进去，同曲并发互斥失效 → 两个转码进程同写一个
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
                // 引擎不可用（如原生库缺失）时快速失败：转码必然失败，与其让用户
                // 卡在「转码中」不如立刻返回 null 走失败分支
                if (!transcoder.isAvailable()) return@withContext null

                cacheDir.mkdirs()
                val tmp = File(cacheDir, "${fileName(songId)}.tmp")
                tmp.delete()
                // 超时由 TranscodeRequest 携带（P3-22），转码器负责到点终止底层进程
                val transcoded = transcoder.transcode(
                    TranscodeRequest(
                        source = source,
                        destination = tmp,
                        timeoutMs = TranscodeRequest.DEFAULT_TIMEOUT_MS,
                    )
                )

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
        if (total <= cachePolicy.maxBytes) return
        for (f in files) {
            if (total <= cachePolicy.maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun fileName(songId: Long) = "ape_$songId.wav"
}

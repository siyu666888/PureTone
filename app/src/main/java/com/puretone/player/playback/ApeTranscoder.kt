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
     *
     * P2-15：缓存 key 除 songId 外还隐含源文件指纹（长度 + 最后修改时间）。
     * 命中时会校验指纹元文件，源文件被替换（同名覆盖 / 重新下载）后旧 WAV 视为失效，
     * 重新转码，避免用户听到替换前的内容。
     */
    suspend fun ensureWav(songId: Long, sourcePath: String): File? =
        withContext(Dispatchers.IO) {
            val target = File(cacheDir, fileName(songId))
            lockFor(songId).withLock {
                val source = File(sourcePath)
                if (!source.exists()) return@withContext null
                // 命中且源指纹一致才复用；否则丢弃旧缓存（源已变更）
                if (target.exists() && target.length() > 0 && metaMatches(songId, source)) {
                    touch(target)
                    return@withContext target
                }
                target.delete()
                metaFile(songId).delete()
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
                    // 记录源指纹，供后续命中校验（P2-15）
                    writeMeta(songId, source)
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

    /** 缓存总大小（字节，仅计入 WAV，排除 .meta/.tmp 簿记文件） */
    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) {
        cacheDir.listFiles()
            ?.filter { it.isFile && it.extension == "wav" }
            ?.sumOf { it.length() } ?: 0L
    }

    private fun touch(file: File) = file.setLastModified(System.currentTimeMillis())

    /** 超出字节上限时淘汰最久未使用的缓存（按 lastModified LRU） */
    private fun trimCache() {
        // 仅 WAV 参与 LRU 与字节统计；指纹元文件（.meta）与临时文件（.tmp）是簿记，
        // 不计入额度、不参与淘汰（P2-15 引入 .meta 后必须排除，否则会挤掉真实缓存）。
        // 被淘汰的 WAV 一并删除其 .meta，避免遗留孤儿元文件。
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && it.extension == "wav" }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= cachePolicy.maxBytes) return
        for (f in files) {
            if (total <= cachePolicy.maxBytes) break
            val len = f.length()
            if (f.delete()) {
                metaFileFor(f)?.delete()
                total -= len
            }
        }
    }

    /** 由 wav 文件反推其指纹元文件（P2-15） */
    private fun metaFileFor(wav: File): File? {
        if (wav.extension != "wav") return null
        return File(wav.parentFile, "${wav.nameWithoutExtension}.meta")
    }

    private fun fileName(songId: Long) = "ape_$songId.wav"

    /** 源文件指纹：长度 + 最后修改时间（毫秒）。源被替换即失效，触发重转（P2-15） */
    private fun fingerprint(source: File): String = "${source.length()}_${source.lastModified()}"

    /** 指纹元文件（与 wav 同目录，记录源指纹） */
    private fun metaFile(songId: Long) = File(cacheDir, "ape_$songId.meta")

    /** 命中校验：元文件存在且记录的源指纹与当前源一致（P2-15） */
    private fun metaMatches(songId: Long, source: File): Boolean {
        val meta = metaFile(songId)
        if (!meta.isFile) return false
        return runCatching { meta.readText() == fingerprint(source) }.getOrDefault(false)
    }

    /** 写入源指纹（P2-15） */
    private fun writeMeta(songId: Long, source: File) {
        runCatching { metaFile(songId).writeText(fingerprint(source)) }
    }
}

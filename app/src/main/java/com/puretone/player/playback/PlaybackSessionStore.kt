package com.puretone.player.playback

import com.puretone.player.data.local.entity.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放会话持久化（P1-1 拆分第五刀）。
 *
 * 装的是两个**已经踩过、且错了很难复现**的修复点：
 *
 * 1. **保存位置必须钳到 `duration - 500ms`**（P2-17）。曲目播完时 ExoPlayer 的
 *    `currentPosition` 等于总时长，原样存下去，下次恢复会 seek 到结尾并立即再次
 *    ENDED——表现为「续播恢复了，但什么都没发生」。
 * 2. **恢复时必须按保存顺序重排**（P0-7）。按 id 批量查询是 `IN (:ids)`，既没有
 *    ORDER BY 也会被 SQL 去重，直接拿结果恢复会顺序错位、重复曲目丢失。
 *
 * 这两点都不在播放编排的主线里，混在千余行的管理器中既难发现也无测试覆盖。
 * 抽出来后可以用替身档案（[PlaybackSessionArchive]）在纯 JVM 上锁定。
 */

/** 会话存档的最小操作面（[com.puretone.player.data.repository.MusicRepository] 是 final 类，无法替身） */
internal interface PlaybackSessionArchive {
    /** 读取上次会话；无存档返回 null */
    suspend fun load(): SavedSession?

    /** 按 id 批量取歌曲：结果**无序且已去重**，调用方必须自行按保存顺序重排 */
    suspend fun songsByIds(ids: List<Long>): List<SongEntity>

    suspend fun save(songIds: List<Long>, index: Int, positionMs: Long)

    suspend fun clear()
}

/** 上次会话的存档内容 */
internal data class SavedSession(
    val songIds: List<Long>,
    val index: Int,
    val positionMs: Long
)

/** 恢复出的队列（含播放起点） */
internal data class RestoredQueue(
    val songs: List<SongEntity>,
    val index: Int,
    val positionMs: Long
)

internal class PlaybackSessionStore(
    private val archive: PlaybackSessionArchive,
    private val scope: CoroutineScope
) : SessionStorePort {

    /**
     * 去抖保存（毫秒）。连续播放/暂停、快速切歌时每次都新起一个 sleep 协程会堆一串写入。
     */
    private val debounceMs = 800L

    private var debounceJob: Job? = null

    /**
     * 计算应写入的位置。
     *
     * 播完（STATE_ENDED）时 [rawPositionMs] 等于总时长，直接存会让下次恢复 seek 到结尾
     * 立即再次播完。统一钳到 `duration - 500ms`；时长未知或极短（<500ms）时保底 0。
     */
    override fun positionToSave(rawPositionMs: Long, durationMs: Long): Long {
        val raw = rawPositionMs.coerceAtLeast(0L)
        // duration 未知时 ExoPlayer 返回 C.TIME_UNSET（远小于 0），由调用方过滤后传 0
        if (durationMs <= 0L) return raw
        return raw.coerceIn(0L, (durationMs - END_MARGIN_MS).coerceAtLeast(0L))
    }

    /**
     * 去抖保存：取消上一个待写入任务再排新的，避免连续操作堆出一串并发写入。
     *
     * 快照在**写入时刻**才求值（而不是调用时刻）：去抖的意义就是「以最新状态写一次」，
     * 若用调用时的快照，用户在窗口内切歌后存档会停在上一首。
     */
    override fun saveDebounced(snapshot: () -> SavedSession?) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            val s = snapshot() ?: return@launch
            write(s.songIds, s.index, s.positionMs)
        }
    }

    /** 立即保存（停止 / 暂停 / 切歌等关键节点） */
    override fun saveNow(snapshot: SavedSession) {
        debounceJob?.cancel()
        debounceJob = null
        scope.launch { write(snapshot.songIds, snapshot.index, snapshot.positionMs) }
    }

    /** 停止/清空队列时丢弃存档 */
    fun clear() {
        debounceJob?.cancel()
        debounceJob = null
        scope.launch { runCatching { archive.clear() } }
    }

    private suspend fun write(songIds: List<Long>, index: Int, positionMs: Long) {
        if (songIds.isEmpty()) return
        runCatching { archive.save(songIds, index, positionMs) }
    }

    /**
     * 读取存档并按**保存时的顺序**重建队列；无存档 / 曲目已全部删除时返回 null。
     *
     * 顺序必须在这里还原：批量查询是 `IN (:ids)`，既不保证顺序也会去重，
     * 直接用它的结果会让队列错位、重复曲目丢失（P0-7）。
     */
    override suspend fun restoreQueue(): RestoredQueue? {
        val saved = runCatching { archive.load() }.getOrNull() ?: return null
        if (saved.songIds.isEmpty()) return null
        val byId = runCatching { archive.songsByIds(saved.songIds) }
            .getOrDefault(emptyList())
            .associateBy { it.id }
        val songs = saved.songIds.mapNotNull { byId[it] }
        if (songs.isEmpty()) return null
        return RestoredQueue(
            songs = songs,
            index = saved.index.coerceIn(0, songs.lastIndex),
            positionMs = saved.positionMs.coerceAtLeast(0L)
        )
    }

    private companion object {
        /** 位置与结尾的安全间距：留一点余量避免恢复后立刻播完 */
        const val END_MARGIN_MS = 500L
    }
}

package com.puretone.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puretone.player.data.local.dao.HourCountRow
import com.puretone.player.data.local.dao.NamedCountRow
import com.puretone.player.data.local.dao.TopSongRow
import com.puretone.player.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import javax.inject.Inject

/** 单日听歌桶（用于趋势图，缺失日补 0） */
data class DayBucket(
    val dayKey: String,
    val listenMs: Long,
    val plays: Long
)

/** 听歌统计 UI 状态 */
data class StatsUiState(
    val totalPlays: Long = 0L,
    val totalListenMs: Long = 0L,
    val playedSongs: Int = 0,
    val dailyTrend: List<DayBucket> = emptyList(),
    val topSongs: List<TopSongRow> = emptyList(),
    val topArtists: List<NamedCountRow> = emptyList(),
    /** 0-23 时每时段播放次数（缺省 0） */
    val hourPlays: List<Pair<Int, Long>> = emptyList()
) {
    /** 近 30 天有播放的天数 */
    val activeDays: Int get() = dailyTrend.count { it.plays > 0 }

    /** 有播放的日均时长（毫秒） */
    val avgListenMsPerActiveDay: Long
        get() {
            val days = activeDays.coerceAtLeast(1)
            val sum = dailyTrend.sumOf { it.listenMs }
            return sum / days
        }

    /** 播放最勤时段的小时（0-23），无数据返回 -1 */
    val peakHour: Int
        get() = hourPlays.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first ?: -1

    /** 是否"深夜听众"（峰值时段落在 0-5 点） */
    val isNightOwl: Boolean get() = peakHour in 0..5
}

/**
 * 听歌统计视图模型：聚合曲库与播放历史的统计查询，供统计看板消费。
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    repo: MusicRepository
) : ViewModel() {

    private val sinceMs: Long = System.currentTimeMillis() - TREND_DAYS * 24L * 60 * 60 * 1000

    val state: StateFlow<StatsUiState> = combine(
        combine(
            repo.observeTotalPlays(),
            repo.observeTotalListenMs(),
            repo.observePlayedSongs()
        ) { plays, listenMs, playedSongs -> CoreStats(plays, listenMs, playedSongs) },
        combine(
            repo.observeDailyTrend(sinceMs),
            repo.observeTopSongs(10),
            repo.observeTopArtists(5),
            repo.observeHourDistribution()
        ) { trend, topSongs, topArtists, hours -> ListStats(trend, topSongs, topArtists, hours) }
    ) { core, lists ->
        StatsUiState(
            totalPlays = core.plays,
            totalListenMs = core.listenMs,
            playedSongs = core.playedSongs,
            dailyTrend = buildFullTrend(lists.trend, sinceMs),
            topSongs = lists.topSongs,
            topArtists = lists.topArtists,
            hourPlays = (0..23).map { h -> h to (lists.hours.firstOrNull { it.hour == h }?.plays ?: 0L) }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private data class CoreStats(val plays: Long, val listenMs: Long, val playedSongs: Int)
    private data class ListStats(
        val trend: List<com.puretone.player.data.local.dao.DailyTrendRow>,
        val topSongs: List<TopSongRow>,
        val topArtists: List<NamedCountRow>,
        val hours: List<HourCountRow>
    )

    companion object {
        const val TREND_DAYS = 30

        /** 把稀疏的每日记录补全为连续 [TREND_DAYS] 天序列（缺失日 listenMs/plays=0） */
        private fun buildFullTrend(
            rows: List<com.puretone.player.data.local.dao.DailyTrendRow>,
            sinceMs: Long
        ): List<DayBucket> {
            val byKey = rows.associateBy { it.dayKey }
            val cal = Calendar.getInstance()
            // 从最早一天开始逐日生成 key，保证连续
            cal.timeInMillis = sinceMs
            val start = Calendar.getInstance().apply { timeInMillis = sinceMs }
            start.set(Calendar.HOUR_OF_DAY, 0)
            start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0)
            start.set(Calendar.MILLISECOND, 0)
            val out = ArrayList<DayBucket>(TREND_DAYS)
            val cursor = (start.clone() as Calendar)
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            while (!cursor.after(today) && out.size < 400) {
                val key = dayKey(cursor)
                val row = byKey[key]
                out += DayBucket(dayKey = key, listenMs = row?.listenMs ?: 0L, plays = row?.plays ?: 0L)
                cursor.add(Calendar.DAY_OF_YEAR, 1)
            }
            cal.clear()
            return out
        }

        private fun dayKey(cal: Calendar): String {
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return "%04d-%02d-%02d".format(y, m, d)
        }
    }
}

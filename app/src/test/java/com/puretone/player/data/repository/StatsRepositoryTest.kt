package com.puretone.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.PureToneDatabase
import com.puretone.player.data.local.entity.PlayHistoryEntity
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.domain.report.PeriodType
import com.puretone.player.domain.report.ReportPeriod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * StatsRepository 的转发契约（真实 Room + 真实 SQL）。
 *
 * 2026-11-04 从 MusicRepository 拆出 9 个统计只读聚合 / 历史清空方法后，
 * 用两个用例锁定「方法确实委托到 StatsDao / PlayHistoryDao」，
 * 避免纯搬移日后被误改成无实现的空壳。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsRepositoryTest {

    private lateinit var db: PureToneDatabase
    private lateinit var repo: StatsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PureToneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = StatsRepository(db.statsDao(), db.playHistoryDao(), db.songDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `aggregate flows delegate to StatsDao`() = runTest {
        assertEquals(0L, repo.observeHistoryCount().first())
        assertTrue(repo.observeTopSongs(10).first().isEmpty())
        assertTrue(repo.observeDailyTrend(0L).first().isEmpty())
        assertTrue(repo.observeHourDistribution().first().isEmpty())
    }

    @Test
    fun `clearHistory delegates to PlayHistoryDao`() = runTest {
        db.songDao().upsertAll(
            listOf(
                SongEntity(
                    id = 1L,
                    mediaStoreId = 1L,
                    title = "a.mp3",
                    artist = "Artist",
                    album = "Album",
                    albumId = 1L,
                    duration = 200_000,
                    path = "/storage/emulated/0/Music/a.mp3",
                    size = 1_000L,
                    mimeType = "audio/mpeg"
                )
            )
        )
        db.playHistoryDao().insert(
            PlayHistoryEntity(songId = 1L, playedAt = 1_000L, durationMs = 200_000L)
        )
        assertEquals(1L, repo.observeHistoryCount().first())

        repo.clearHistory()
        assertEquals(0L, repo.observeHistoryCount().first())
    }

    @Test
    fun `clearHistory also resets play_count and last_played_at`() = runTest {
        db.songDao().upsertAll(
            listOf(
                SongEntity(
                    id = 1L,
                    mediaStoreId = 1L,
                    title = "a.mp3",
                    artist = "Artist",
                    album = "Album",
                    albumId = 1L,
                    duration = 200_000,
                    path = "/storage/emulated/0/Music/a.mp3",
                    size = 1_000L,
                    mimeType = "audio/mpeg",
                    playCount = 7,
                    lastPlayedAt = 1_234L
                )
            )
        )
        assertEquals(7, db.songDao().getById(1L)?.playCount)

        repo.clearHistory()

        val song = db.songDao().getById(1L)
        assertEquals(0, song?.playCount)
        assertEquals(0L, song?.lastPlayedAt)
        // 曲库本身不受影响
        assertEquals("a.mp3", song?.title)
    }

    /**
     * 周报聚合：播放次数 / 听歌时长（含 played_ms=0 回退 duration_ms 并标「含估算」）/
     * 收听歌曲数 / 活跃天数 / Top 排序 / 环比（WoW）。
     */
    @Test
    fun `getReport week aggregates plays listenMs topSongs and WoW with estimate fallback`() = runTest {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.of(2026, 11, 10).atTime(10, 0).atZone(zoneId).toInstant().toEpochMilli()
        val ranges = ReportPeriod.lastCompletedWeeks(2, now, zoneId)
        val cur = ranges[0]
        val prev = ranges[1]

        db.songDao().upsertAll(
            listOf(
                SongEntity(
                    id = 1L, mediaStoreId = 1L, title = "SongA", artist = "ArtistX",
                    album = "Al", albumId = 1L, duration = 200_000, path = "/a.mp3",
                    size = 1_000L, mimeType = "audio/mpeg"
                ),
                SongEntity(
                    id = 2L, mediaStoreId = 2L, title = "SongB", artist = "ArtistY",
                    album = "Al", albumId = 1L, duration = 200_000, path = "/b.mp3",
                    size = 1_000L, mimeType = "audio/mpeg"
                )
            )
        )

        val dayKeyAt: (Long) -> String = { ReportPeriod.dayKeyFromEpoch(it, zoneId) }
        // 当前完整周：3 次播放，分布周一/二/三；第 1 次 played_ms=0 回退 duration_ms 并标「含估算」
        db.playHistoryDao().insert(
            PlayHistoryEntity(
                songId = 1L, playedAt = cur.start + 3_600_000L, durationMs = 120_000L,
                dayKey = dayKeyAt(cur.start + 3_600_000L), playedMs = 0L, completed = 1
            )
        )
        db.playHistoryDao().insert(
            PlayHistoryEntity(
                songId = 1L, playedAt = cur.start + 24 * 3_600_000L + 3_600_000L,
                durationMs = 60_000L, dayKey = dayKeyAt(cur.start + 24 * 3_600_000L + 3_600_000L),
                playedMs = 60_000L, completed = 1
            )
        )
        db.playHistoryDao().insert(
            PlayHistoryEntity(
                songId = 2L, playedAt = cur.start + 2 * 24 * 3_600_000L + 3_600_000L,
                durationMs = 30_000L, dayKey = dayKeyAt(cur.start + 2 * 24 * 3_600_000L + 3_600_000L),
                playedMs = 30_000L, completed = 0
            )
        )
        // 上一完整周：1 次，用于环比
        db.playHistoryDao().insert(
            PlayHistoryEntity(
                songId = 1L, playedAt = prev.start + 3_600_000L,
                durationMs = 10_000L, dayKey = dayKeyAt(prev.start + 3_600_000L),
                playedMs = 10_000L, completed = 1
            )
        )

        val data = repo.getReport(PeriodType.WEEK, now, zoneId)

        assertEquals(PeriodType.WEEK, data.type)
        assertEquals(3L, data.plays)
        assertEquals(120_000L + 60_000L + 30_000L, data.listenMs)
        assertTrue(data.hasEstimate)
        assertEquals(2, data.distinctSongs)
        assertEquals(3, data.activeDays)
        assertEquals(2L, data.completed)
        // Top 歌曲按播放次数降序：SongA(2) 在前
        assertEquals(2, data.topSongs.size)
        assertEquals(1L, data.topSongs[0].id)
        assertEquals(2, data.topSongs[0].playCount)
        assertEquals(2L, data.topSongs[1].id)
        assertEquals(1, data.topSongs[1].playCount)
        // Top 歌手
        assertEquals("ArtistX", data.topArtists[0].name)
        assertEquals(2L, data.topArtists[0].playCount)
        // 环比：cur 210000 / prev 10000 → +2000%
        assertEquals(10_000L, data.prevListenMs)
        assertTrue("deltaPct=$data.deltaPct", data.deltaPct > 1900.0 && data.deltaPct < 2100.0)
    }

    /** 当前周无播放 → 报告为空（但往期有数据，验证环比不影响 isEmpty 判定） */
    @Test
    fun `getReport empty current week reports empty`() = runTest {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val now = LocalDate.of(2026, 11, 10).atTime(10, 0).atZone(zoneId).toInstant().toEpochMilli()
        val prev = ReportPeriod.lastCompletedWeeks(2, now, zoneId)[1]
        db.songDao().upsertAll(
            listOf(
                SongEntity(
                    id = 1L, mediaStoreId = 1L, title = "SongA", artist = "ArtistX",
                    album = "Al", albumId = 1L, duration = 200_000, path = "/a.mp3",
                    size = 1_000L, mimeType = "audio/mpeg"
                )
            )
        )
        db.playHistoryDao().insert(
            PlayHistoryEntity(
                songId = 1L, playedAt = prev.start + 3_600_000L,
                durationMs = 10_000L,
                dayKey = ReportPeriod.dayKeyFromEpoch(prev.start + 3_600_000L, zoneId),
                playedMs = 10_000L
            )
        )
        val data = repo.getReport(PeriodType.WEEK, now, zoneId)
        assertTrue(data.isEmpty)
        assertEquals(0L, data.plays)
    }
}

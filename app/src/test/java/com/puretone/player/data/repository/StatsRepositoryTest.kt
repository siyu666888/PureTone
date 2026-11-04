package com.puretone.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.PureToneDatabase
import com.puretone.player.data.local.entity.PlayHistoryEntity
import com.puretone.player.data.local.entity.SongEntity
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
        repo = StatsRepository(db.statsDao(), db.playHistoryDao())
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
}

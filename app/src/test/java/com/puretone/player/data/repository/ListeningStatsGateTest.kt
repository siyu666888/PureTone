package com.puretone.player.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.PureToneDatabase
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.media.MediaStoreScanner
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.playback.ReplayGainExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 「隐私 → 听歌数据统计」开关的写入侧回归。
 *
 * 断言的是**数据库里真的没有多出一行 / play_count 没有 +1**，而不是某个返回值：
 * 两个写库收口（recordPlay / recordCompletion）都必须实时读开关，任何一处漏拦
 * 都意味着隐私承诺失效。用真实 Room + 真实 DataStore，避免替身掩盖接线错误。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListeningStatsGateTest {

    private lateinit var db: PureToneDatabase
    private lateinit var repo: MusicRepository
    private lateinit var settings: SettingsRepository
    private lateinit var settingsFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PureToneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsFile = File(context.cacheDir, "listening_stats_gate_test.preferences_pb")
        settingsFile.delete()
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + Job())
            ) { settingsFile }
        )
        repo = MusicRepository(
            db = db,
            songDao = db.songDao(),
            playlistDao = db.playlistDao(),
            playHistoryDao = db.playHistoryDao(),
            scanner = MediaStoreScanner(context),
            replayGainExtractor = ReplayGainExtractor(),
            settings = settings
        )
        runBlocking {
            db.songDao().upsertAll(
                listOf(
                    SongEntity(
                        id = 1L,
                        mediaStoreId = 1L,
                        title = "a.flac",
                        artist = "Artist",
                        album = "Album",
                        albumId = 1L,
                        duration = 200_000,
                        path = "/storage/emulated/0/Music/a.flac",
                        size = 1_000L,
                        mimeType = "audio/flac"
                    )
                )
            )
        }
    }

    @After
    fun tearDown() {
        settingsFile.delete()
        db.close()
    }

    private suspend fun historyCount(): Long = db.statsDao().observeHistoryCount().first()
    private suspend fun playCount(): Int = db.songDao().getById(1L)?.playCount ?: -1

    @Test
    fun `默认开启时两个收口正常写入`() = runTest {
        repo.recordPlay(1L)
        repo.recordCompletion(1L)
        assertEquals(1L, historyCount())
        assertEquals(1, playCount())
    }

    @Test
    fun `关闭后不再写播放历史与播放次数`() = runTest {
        settings.setListeningStatsEnabled(false)
        repo.recordPlay(1L)
        repo.recordCompletion(1L)
        assertEquals(0L, historyCount())
        assertEquals(0, playCount())
    }

    @Test
    fun `关闭即刻生效且重新开启立即恢复`() = runTest {
        repo.recordPlay(1L)
        assertEquals(1L, historyCount())
        settings.setListeningStatsEnabled(false)
        repo.recordPlay(1L)
        repo.recordCompletion(1L)
        assertEquals(1L, historyCount())
        assertEquals(0, playCount())
        settings.setListeningStatsEnabled(true)
        repo.recordPlay(1L)
        repo.recordCompletion(1L)
        assertEquals(2L, historyCount())
        assertEquals(1, playCount())
    }
}

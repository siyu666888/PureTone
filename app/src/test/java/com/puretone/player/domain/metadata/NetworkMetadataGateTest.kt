package com.puretone.player.domain.metadata

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.PureToneDatabase
import com.puretone.player.data.media.MediaStoreScanner
import com.puretone.player.data.metadata.LyricsProvider
import com.puretone.player.data.metadata.MetadataCandidate
import com.puretone.player.data.metadata.SongMetadataProvider
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.domain.usecase.AutoFillMetadataUseCase
import com.puretone.player.domain.usecase.BackfillCoversUseCase
import com.puretone.player.domain.usecase.DownloadLyricsUseCase
import com.puretone.player.domain.usecase.SearchMetadataUseCase
import com.puretone.player.lyrics.LyricsStore
import com.puretone.player.playback.ReplayGainExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 联网元数据总开关的短路行为回归测试（P2-4）。
 *
 * 核心断言不是「返回值是什么」，而是 **provider 一次都没有被调用**——
 * 这才是隐私边界的真实含义。若只断言返回空列表，短路被改成「仍然请求但丢弃结果」
 * 时测试仍会通过，而隐私承诺已经失效。
 *
 * 依赖用真实实现（Room in-memory + 真实 DataStore + 真实 LyricsStore），
 * 只把两个 provider 换成计数替身——被测对象周边的接线错误正是这类缺陷的温床。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkMetadataGateTest {

    private lateinit var db: PureToneDatabase
    private lateinit var repo: MusicRepository
    private lateinit var settings: SettingsRepository
    private lateinit var gate: NetworkMetadataGate
    private lateinit var lyricsStore: LyricsStore
    private lateinit var settingsFile: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PureToneDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settingsFile = File(context.cacheDir, "gate_test_settings.preferences_pb")
        settingsFile.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job())
        ) { settingsFile }
        settings = SettingsRepository(dataStore)
        gate = NetworkMetadataGate(settings)
        repo = MusicRepository(
            db = db,
            songDao = db.songDao(),
            playlistDao = db.playlistDao(),
            playHistoryDao = db.playHistoryDao(),
            statsDao = db.statsDao(),
            scanner = MediaStoreScanner(context),
            replayGainExtractor = ReplayGainExtractor(),
            settings = settings
        )
        lyricsStore = LyricsStore(context)
    }

    @After
    fun tearDown() {
        settingsFile.delete()
        db.close()
    }

    // ---------- 守卫本身 ----------

    @Test
    fun `守卫默认放行`() = runTest {
        assertTrue(gate.isEnabled())
    }

    @Test
    fun `关闭后守卫拦截`() = runTest {
        settings.setNetworkMetadataEnabled(false)
        assertEquals(false, gate.isEnabled())
    }

    @Test
    fun `关闭后可重新放行（无需重启）`() = runTest {
        settings.setNetworkMetadataEnabled(false)
        settings.setNetworkMetadataEnabled(true)
        assertTrue(gate.isEnabled())
    }

    // ---------- 用例层短路：provider 不被调用 ----------

    @Test
    fun `开关关闭时搜索用例不调用 provider`() = runTest {
        settings.setNetworkMetadataEnabled(false)
        val provider = CountingMetadataProvider()
        val result = SearchMetadataUseCase(provider, gate)("周杰伦 稻香")
        assertEquals(emptyList<MetadataCandidate>(), result)
        assertEquals("provider 不应被调用", 0, provider.searchCount)
    }

    @Test
    fun `开关开启时搜索用例正常调用 provider`() = runTest {
        val provider = CountingMetadataProvider()
        val result = SearchMetadataUseCase(provider, gate)("周杰伦 稻香")
        assertEquals(1, provider.searchCount)
        assertEquals(1, result.size)
    }

    @Test
    fun `开关关闭时歌词用例不调用 provider`() = runTest {
        settings.setNetworkMetadataEnabled(false)
        val provider = CountingLyricsProvider()
        val outcome = DownloadLyricsUseCase(provider, lyricsStore, gate)(
            audioPath = "/tmp/a.mp3",
            artist = "周杰伦",
            title = "稻香",
            durationMs = 200_000L
        )
        assertEquals(DownloadLyricsUseCase.Outcome.DISABLED, outcome)
        assertEquals("provider 不应被调用", 0, provider.fetchCount)
    }

    @Test
    fun `开关开启时歌词用例正常调用 provider`() = runTest {
        val provider = CountingLyricsProvider()
        val outcome = DownloadLyricsUseCase(provider, lyricsStore, gate)(
            audioPath = "/tmp/gate_test_a.mp3",
            artist = "周杰伦",
            title = "稻香",
            durationMs = 200_000L
        )
        assertEquals(DownloadLyricsUseCase.Outcome.SAVED, outcome)
        assertEquals(1, provider.fetchCount)
    }

    @Test
    fun `开关关闭时自动填充不调用 provider`() = runTest {
        settings.setNetworkMetadataEnabled(false)
        val provider = CountingMetadataProvider()
        val useCase = AutoFillMetadataUseCase(repo, provider, gate)
        val result = useCase(1L, "周杰伦", "稻香")
        // 明确文案而非「未找到匹配」——否则用户会以为是搜索词不对
        assertTrue(result is AutoFillMetadataUseCase.AutoFillResult.Failed)
        assertEquals(
            "已关闭联网补全元数据",
            (result as AutoFillMetadataUseCase.AutoFillResult.Failed).message
        )
        assertEquals("provider 不应被调用", 0, provider.searchCount)
    }

    @Test
    fun `开关关闭时批量补封面不调用 provider`() = runTest {
        settings.setNetworkMetadataEnabled(false)
        val provider = CountingMetadataProvider()
        val useCase = BackfillCoversUseCase(repo, provider, gate)
        val (done, total, remaining) = useCase()
        assertEquals(0, done)
        assertEquals(0, total)
        assertEquals(0, remaining)
        assertEquals("provider 不应被调用", 0, provider.searchCount)
    }

    // ---------- 测试替身 ----------

    /** 记录调用次数的元数据 provider 替身 */
    private class CountingMetadataProvider : SongMetadataProvider {
        override val sourceName: String = "Test"
        var searchCount: Int = 0
            private set

        override suspend fun search(term: String, limit: Int): List<MetadataCandidate> {
            searchCount++
            return listOf(
                MetadataCandidate(
                    title = "稻香",
                    artist = "周杰伦",
                    album = "魔杰座",
                    artworkUrl = null,
                    year = 2008
                )
            )
        }
    }

    /** 记录调用次数的歌词 provider 替身 */
    private class CountingLyricsProvider : LyricsProvider {
        override val sourceName: String = "Test"
        var fetchCount: Int = 0
            private set

        override suspend fun fetch(artist: String, title: String, durationMs: Long): String? {
            fetchCount++
            return "[00:00.00]稻香"
        }
    }
}

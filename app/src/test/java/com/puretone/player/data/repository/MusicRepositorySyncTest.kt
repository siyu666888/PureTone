package com.puretone.player.data.repository

import android.content.Context
import android.provider.MediaStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.PureToneDatabase
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.media.MediaStoreScanner
import com.puretone.player.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor
import java.io.File

/**
 * 曲库同步对账的回归测试（真实 Room + 真实 SQL）。
 *
 * 背景：用户反馈"扫描结果全是录音类文件"。根因是 [MusicRepository.syncLibrary]
 * 把"应删除的路径集合"传给了 `DELETE ... WHERE path NOT IN (...)`，语义反了，
 * 结果音乐被删光、录音全部留下。这两个用例锁定正确语义与空扫描保护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicRepositorySyncTest {

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
        settingsFile = File(context.cacheDir, "sync_test_settings.preferences_pb")
        settingsFile.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job())
        ) { settingsFile }
        settings = SettingsRepository(dataStore)
        repo = MusicRepository(
            db = db,
            songDao = db.songDao(),
            playlistDao = db.playlistDao(),
            playHistoryDao = db.playHistoryDao(),
            statsDao = db.statsDao(),
            scanner = MediaStoreScanner(context),
            settings = settings
        )
    }

    @After
    fun tearDown() {
        db.close()
        settingsFile.delete()
    }

    private fun song(path: String, id: Long = 0L, favorite: Boolean = false) = SongEntity(
        id = id,
        mediaStoreId = path.hashCode().toLong(),
        title = path.substringAfterLast('/'),
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        duration = 200_000,
        path = path,
        size = 1_000L,
        mimeType = "audio/mpeg",
        favorite = favorite
    )

    /** 注册 MediaStore 扫描结果（模拟系统媒体库当前内容） */
    private fun registerMediaStore(vararg paths: String) {
        val rows = Array(paths.size) { i ->
            arrayOf<Any?>(
                i.toLong(), paths[i].substringAfterLast('/'), "Artist", "Album", 1L,
                200_000L, paths[i], 1_000L, "audio/mpeg", 2024, 1, 1_000L, 1_000L
            )
        }
        val cursor = RoboCursor()
        cursor.setColumnNames(PROJECTION.toList())
        cursor.setResults(rows)
        shadowOf(ApplicationProvider.getApplicationContext<Context>().contentResolver)
            .setCursor(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor)
    }

    @Test
    fun `sync removes filtered recordings but keeps music`() = runTest {
        val musicA = "/storage/emulated/0/Music/a.mp3"
        val musicB = "/storage/emulated/0/Music/b.flac"
        // 曲库里有 2 首音乐 + 3 个录音（模拟旧版本扫进来的残留）
        db.songDao().upsertAll(
            listOf(
                song(musicA, id = 1L, favorite = true),
                song(musicB, id = 2L),
                song("/storage/emulated/0/Recordings/rec1.m4a", id = 3L),
                song("/storage/emulated/0/Sounds/voice.amr", id = 4L),
                song("/storage/emulated/0/Music/Voice 002.amr", id = 5L)
            )
        )
        // 本次扫描（过滤后）只剩 2 首音乐
        registerMediaStore(musicA, musicB)

        val result = repo.syncLibrary()

        val remaining = db.songDao().getAll()
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.path == musicA })
        assertTrue(remaining.any { it.path == musicB })
        // 三个录音必须被移除——修复前它们会被保留、音乐反被删光
        assertFalse(remaining.any { it.path.contains("Recordings", ignoreCase = true) })
        assertFalse(remaining.any { it.path.endsWith(".amr", ignoreCase = true) })
        assertEquals(3, result.removed)
        assertEquals(2, result.total)
        // 收藏标记不因同步丢失
        assertTrue(remaining.first { it.path == musicA }.favorite)
    }

    /**
     * 库为空时必须走全量，即使存在历史扫描时间戳。
     *
     * 场景：用户清了 App 数据（或数据被外部清除），但 DataStore 里的 lastScanMs
     * 可能仍在。此时若走增量，只会拉到「上次之后的变更行」——一个空库永远补不回来。
     *
     * 判别原理：测试环境下增量路径查询拿不到数据（见下一条用例），会命中安全闸
     * 返回 total=0；只有真正走了全量才会有 added=2 / total=2。
     */
    @Test
    fun `empty library forces full scan even with previous timestamp`() = runTest {
        settings.setLastScanMs(System.currentTimeMillis() - 60_000)
        registerMediaStore(
            "/storage/emulated/0/Music/a.mp3",
            "/storage/emulated/0/Music/b.flac"
        )

        val result = repo.syncLibrary()

        assertEquals("空库应走全量并入库 2 首", 2, result.added)
        assertEquals(2, result.total)
        assertEquals(2, db.songDao().getAll().size)
    }

    /**
     * 增量同步拿不到路径清单时，绝不删除任何歌曲。
     *
     * 删除对账的依据是「当前媒体库全量路径」。若这份额外查询失败（返回空），
     * 说明本次同步不可信——此时按安全闸处理，而不是把库里所有行都当成「已删除」。
     * 注意这与全量扫描不同：增量下「变更行」为空是完全正常的（没有改动），
     * 所以安全闸的判据必须是路径清单，不能是变更行。
     */
    @Test
    fun `incremental sync without path list never deletes`() = runTest {
        db.songDao().upsertAll(
            listOf(
                song("/storage/emulated/0/Music/a.mp3", id = 1L),
                song("/storage/emulated/0/Music/b.flac", id = 2L)
            )
        )
        settings.setLastScanMs(System.currentTimeMillis() - 60_000)
        // 库非空 + 有时间戳 → 走增量；路径清单在测试环境下必然为空
        registerMediaStore("/storage/emulated/0/Music/a.mp3")

        val result = repo.syncLibrary()

        assertEquals("路径清单不可信时不得删歌", 0, result.removed)
        assertEquals("曲库应保持原样", 2, db.songDao().getAll().size)
    }

    @Test
    fun `empty scan does not wipe library`() = runTest {
        db.songDao().upsertAll(
            listOf(
                song("/storage/emulated/0/Music/a.mp3", id = 1L),
                song("/storage/emulated/0/Music/b.flac", id = 2L)
            )
        )
        // 扫描结果为空（如授权被撤、媒体库暂不可读）：不得清空曲库
        registerMediaStore()

        val result = repo.syncLibrary()

        assertEquals(2, db.songDao().getAll().size)
        assertEquals(0, result.removed)
        assertEquals(2, result.total)
    }

    private companion object {
        val PROJECTION = listOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED
        )
    }
}

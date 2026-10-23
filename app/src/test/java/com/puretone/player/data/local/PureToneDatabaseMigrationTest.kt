package com.puretone.player.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
 * 数据库迁移 3 → 4 的实证测试（P1-4）。
 *
 * 迁移 SQL 是手写的（SQLite 不能 ALTER 加外键，只能重建表），必须与
 * Room 按实体注解生成的 v4 schema 逐字一致，否则运行期 schema 校验直接崩。
 * 本测试不依赖 assets 挂载：直接按导出的 3.json 造一个"旧版本真实库"，
 * 再用 Room 的正常打开路径升级——schema 校验、数据搬运、外键级联
 * 全链路走一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PureToneDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.cacheDir, TEST_DB)
        dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun `migrate 3 to 4 cleans orphans adds fk cascade and keeps data`() = runTest {
        createV3DatabaseWithData()

        // 用 Room 的正常打开路径升级：跑 MIGRATION_3_4 + schema 校验 + 开外键。
        // 任何一步不对（DDL 漂移、数据丢失、校验失败）都会在这里抛错。
        val db = Room.databaseBuilder(context, PureToneDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                PureToneDatabase.MIGRATION_1_2,
                PureToneDatabase.MIGRATION_2_3,
                PureToneDatabase.MIGRATION_3_4
            )
            .build()

        // 1) 悬空引用被清理，有效数据完整保留
        val withSongs = db.playlistDao().observeWithSongs(PLAYLIST_ID).first()!!
        assertEquals(listOf("/music/a.mp3"), withSongs.songs.map { it.path })
        assertEquals(1, db.playHistoryDao().observeRecent(50).first().size)
        val song = db.songDao().getById(SONG_ID)!!
        assertEquals(3, song.playCount)
        assertTrue(song.favorite)

        // 2) 外键级联真实生效：删歌曲 → 关联行与历史一并清空
        db.songDao().deleteById(SONG_ID)
        assertEquals(0, db.playlistDao().observePlaylistSongRowsOf(PLAYLIST_ID).first().size)
        assertEquals(0, db.playHistoryDao().observeRecent(50).first().size)

        // 3) 删歌单 → 关联行清空
        val playlistId = db.playlistDao().insert(
            com.puretone.player.data.local.entity.PlaylistEntity(name = "列表二")
        )
        db.songDao().upsertAll(
            listOf(
                com.puretone.player.data.local.entity.SongEntity(
                    id = 2L, mediaStoreId = 2L, title = "B", artist = "A", album = "Al",
                    albumId = 1L, duration = 1_000, path = "/music/b.mp3", size = 1,
                    mimeType = "audio/mpeg"
                )
            )
        )
        db.playlistDao().addSongsAtEnd(playlistId, listOf(2L))
        db.playlistDao().getById(playlistId)?.let {
            db.playlistDao().delete(it)
        }
        assertEquals(0, db.playlistDao().observePlaylistSongRowsOf(playlistId).first().size)

        db.close()
    }

    /** 按导出的 3.json 建库并灌入数据（含两条悬空引用） */
    private fun createV3DatabaseWithData() {
        val schema = readSchema(3)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.first.forEach { (table, ddl) -> db.execSQL(ddl) }
                        schema.second.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                """
                INSERT INTO songs (id, media_store_id, title, artist, album, album_id, duration,
                    path, size, cover_uri, mimeType, year, track_number, date_added, date_modified,
                    favorite, play_count, last_played_at, user_edited)
                VALUES ($SONG_ID, 100, 'Song A', 'Artist', 'Album', 1, 200000, '/music/a.mp3', 1000,
                    NULL, 'audio/mpeg', 2024, 1, 1000, 2000, 1, 3, 99, 0)
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO playlists (id, name, created_at, updated_at) " +
                    "VALUES ($PLAYLIST_ID, '我的列表', 100, 100)"
            )
            db.execSQL(
                "INSERT INTO playlist_songs (playlist_id, song_id, position, added_at) " +
                    "VALUES ($PLAYLIST_ID, $SONG_ID, 0, 100)"
            )
            // 悬空引用：旧版删歌不级联子表的遗留，迁移必须清理
            db.execSQL(
                "INSERT INTO playlist_songs (playlist_id, song_id, position, added_at) " +
                    "VALUES ($PLAYLIST_ID, 999, 1, 100)"
            )
            db.execSQL(
                "INSERT INTO play_history (id, song_id, played_at, duration_ms) " +
                    "VALUES (1, $SONG_ID, 100, 5000)"
            )
            db.execSQL(
                "INSERT INTO play_history (id, song_id, played_at, duration_ms) " +
                    "VALUES (2, 999, 100, 5000)"
            )
        }
        helper.close()
    }

    /** 读导出的 schema JSON：返回（表名 → 建表 DDL）与 setupQueries（含 identity hash） */
    private fun readSchema(version: Int): Pair<List<Pair<String, String>>, List<String>> {
        val candidates = listOf(
            File("schemas/com.puretone.player.data.local.PureToneDatabase/$version.json"),
            File("app/schemas/com.puretone.player.data.local.PureToneDatabase/$version.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("未找到 schema $version.json（工作目录：${File("").absolutePath}）")
        val database = JSONObject(file.readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val ddls = (0 until entities.length()).map { i ->
            val e = entities.getJSONObject(i)
            e.getString("tableName") to e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))
        }
        val setup = database.getJSONArray("setupQueries")
        val setupQueries = (0 until setup.length()).map { setup.getString(it) }
        return ddls to setupQueries
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val SONG_ID = 1L
        const val PLAYLIST_ID = 1L
    }
}

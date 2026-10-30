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
 * 数据库迁移 3 → 4 → 5 的实证测试（P1-4 / P2-2 / P2-3）。
 *
 * 迁移 SQL 是手写的（SQLite 不能 ALTER 加外键，只能重建表），必须与
 * Room 按实体注解生成的目标 schema 逐字一致，否则运行期 schema 校验直接崩。
 * 本测试不依赖 assets 挂载：直接按导出的 3.json / 4.json 造一个"旧版本真实库"，
 * 再用 Room 的正常打开路径升级——schema 校验、数据搬运、外键级联、
 * v5 索引创建全链路走一遍。
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

        // 用 Room 的正常打开路径升级：跑 MIGRATION_3_4 + MIGRATION_4_5 +
        // v5 schema 校验 + 开外键。任何一步不对（DDL 漂移、数据丢失、
        // 校验失败）都会在这里抛错。
        val db = Room.databaseBuilder(context, PureToneDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                PureToneDatabase.MIGRATION_1_2,
                PureToneDatabase.MIGRATION_2_3,
                PureToneDatabase.MIGRATION_3_4,
                PureToneDatabase.MIGRATION_4_5,
                PureToneDatabase.MIGRATION_5_6,
                PureToneDatabase.MIGRATION_6_7
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

    @Test
    fun `migrate 4 to 5 adds query indexes and keeps data`() = runTest {
        createV4DatabaseWithData()

        // 只注册 4 → 5：验证纯索引迁移 + v5 schema 校验（索引名/列序与实体
        // @Index 注解不一致会在这里抛错）
        val db = Room.databaseBuilder(context, PureToneDatabase::class.java, dbFile.absolutePath)
            .addMigrations(PureToneDatabase.MIGRATION_4_5, PureToneDatabase.MIGRATION_5_6, PureToneDatabase.MIGRATION_6_7)
            .build()

        // 1) 触发真实打开：跑迁移 + schema 校验，数据完整保留
        assertEquals(1, db.songDao().count())
        assertEquals(1, db.playHistoryDao().observeRecent(50).first().size)

        // 2) 四个新索引真实存在于各自表上（PRAGMA index_list）
        val songIndexes = indexNamesOf(db, "songs")
        assertTrue("缺 index_songs_date_added：$songIndexes", songIndexes.contains("index_songs_date_added"))
        assertTrue(
            "缺 index_songs_play_count_last_played_at：$songIndexes",
            songIndexes.contains("index_songs_play_count_last_played_at")
        )
        assertTrue("缺 index_songs_duration：$songIndexes", songIndexes.contains("index_songs_duration"))
        val historyIndexes = indexNamesOf(db, "play_history")
        assertTrue(
            "缺 index_play_history_played_at：$historyIndexes",
            historyIndexes.contains("index_play_history_played_at")
        )

        db.close()
    }

    @Test
    fun `migrate 5 to 6 adds volume_name column and keeps data`() = runTest {
        createV5DatabaseWithData()

        // 只注册 5 → 6：验证纯加列迁移 + v6 schema 校验（缺列 / 列类型不一致会抛错）
        val db = Room.databaseBuilder(context, PureToneDatabase::class.java, dbFile.absolutePath)
            .addMigrations(PureToneDatabase.MIGRATION_5_6, PureToneDatabase.MIGRATION_6_7)
            .build()

        // 1) 触发真实打开：跑迁移 + schema 校验，数据完整保留
        assertEquals(1, db.songDao().count())
        val song = db.songDao().getById(SONG_ID)!!
        assertEquals("Song A", song.title)

        // 2) volume_name 列真实存在，且既有行默认空串（主卷时代旧数据）
        assertTrue("songs 缺 volume_name 列：${columnNamesOf(db, "songs")}",
            columnNamesOf(db, "songs").contains("volume_name"))
        assertEquals("", song.volumeName)

        // 3) 按 (volume_name, albumId) 复合键查询可用，且卷隔离生效
        // P3-25：DAO 已拆成 albumId>0 与 albumId<=0 两条查询（避免 OR 条件让索引失效），
        // 两条分支都要验。
        // albumId>0 分支：精确匹配 album_id=1
        assertEquals(1, db.songDao().getSongsByAlbumId(1L, "").first().size)
        assertEquals(0, db.songDao().getSongsByAlbumId(1L, "other_volume").first().size)
        // albumId<=0 回退分支：按专辑名匹配（该行 album='Album'，故命中 1 首），
        // 同样受 volume_name 隔离约束
        assertEquals(1, db.songDao().getSongsByAlbumName("Album", "").first().size)
        assertEquals(0, db.songDao().getSongsByAlbumName("Album", "other_volume").first().size)
        // 未匹配到的专辑名返回空
        assertEquals(0, db.songDao().getSongsByAlbumName("不存在的专辑", "").first().size)

        db.close()
    }

    @Test
    fun `migrate 6 to 7 adds replaygain columns and keeps data`() = runTest {
        createV6DatabaseWithData()

        // 只注册 6 → 7：验证纯加列迁移 + v7 schema 校验
        // （rg_track_gain / rg_album_gain 可空 REAL、rg_analyzed NOT NULL DEFAULT 0，
        // 缺列 / 类型 / 默认值不一致都会在 Room 打开路径的 schema 校验里抛错）
        val db = Room.databaseBuilder(context, PureToneDatabase::class.java, dbFile.absolutePath)
            .addMigrations(PureToneDatabase.MIGRATION_6_7)
            .build()

        // 1) 触发真实打开：跑迁移 + schema 校验，数据完整保留
        assertEquals(1, db.songDao().count())
        val song = db.songDao().getById(SONG_ID)!!
        assertEquals("Song A", song.title)

        // 2) 三列真实存在，既有行默认值正确
        val cols = columnNamesOf(db, "songs")
        assertTrue("songs 缺 rg_track_gain 列：$cols", cols.contains("rg_track_gain"))
        assertTrue("songs 缺 rg_album_gain 列：$cols", cols.contains("rg_album_gain"))
        assertTrue("songs 缺 rg_analyzed 列：$cols", cols.contains("rg_analyzed"))
        // 既有行未分析过 → 默认 false / null
        assertEquals(false, song.rgAnalyzed)
        assertEquals(null, song.rgTrackGain)
        assertEquals(null, song.rgAlbumGain)

        // 3) 回填一条真实 RG 标签能被写入并读回（验证列可写、类型正确）
        db.songDao().updateReplayGain(SONG_ID, -6.42f, -7.1f)
        val reread = db.songDao().getById(SONG_ID)!!
        assertEquals(true, reread.rgAnalyzed)
        assertEquals(-6.42f, reread.rgTrackGain!!, 0.001f)
        assertEquals(-7.1f, reread.rgAlbumGain!!, 0.001f)

        db.close()
    }

    /** 查询表上真实存在的索引名集合 */
    private fun indexNamesOf(db: PureToneDatabase, table: String): Set<String> =
        indexNamesOf(db.openHelper.writableDatabase, table)

    private fun indexNamesOf(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA index_list(`$table`)").use { c ->
            val nameCol = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) names += c.getString(nameCol)
        }
        return names
    }

    /** 查询表上真实存在的列名集合 */
    private fun columnNamesOf(db: PureToneDatabase, table: String): Set<String> =
        columnNamesOf(db.openHelper.writableDatabase, table)

    private fun columnNamesOf(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameCol = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) names += c.getString(nameCol)
        }
        return names
    }

    /**
     * 造库保真度自检：v3 / v4 的真实库都带这三个 songs 索引。
     *
     * 若造库漏建索引，后续「4→5 纯加索引」的迁移会拿一个残缺的旧库做校验，
     * 反而给出错误结论（本地 Windows 曾因此假绿，Linux CI 才暴露）。
     * 加这道断言后，造库逻辑一旦退化，本地也会立刻失败。
     */
    private fun assertLegacySongIndexes(db: SupportSQLiteDatabase) {
        val found = indexNamesOf(db, "songs")
        assertTrue(
            "造出的旧库缺少历史索引：$found",
            found.containsAll(
                listOf("index_songs_path", "index_songs_album_id", "index_songs_artist")
            )
        )
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
            assertLegacySongIndexes(db)
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

    /** 按导出的 4.json 建库并灌入数据（v4 真实库，尚无 v5 的新索引） */
    private fun createV4DatabaseWithData() {
        val schema = readSchema(4)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
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
            assertLegacySongIndexes(db)
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
                "INSERT INTO play_history (id, song_id, played_at, duration_ms) " +
                    "VALUES (1, $SONG_ID, 100, 5000)"
            )
        }
        helper.close()
    }

    /** 按导出的 5.json 建库并灌入数据（v5 真实库，尚无 volume_name 列） */
    private fun createV5DatabaseWithData() {
        val schema = readSchema(5)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
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
            // 不写 volume_name：依赖迁移的 NOT NULL DEFAULT '' 兜底
            db.execSQL(
                """
                INSERT INTO songs (id, media_store_id, title, artist, album, album_id, duration,
                    path, size, cover_uri, mimeType, year, track_number, date_added, date_modified,
                    favorite, play_count, last_played_at, user_edited)
                VALUES ($SONG_ID, 100, 'Song A', 'Artist', 'Album', 1, 200000, '/music/a.mp3', 1000,
                    NULL, 'audio/mpeg', 2024, 1, 1000, 2000, 1, 3, 99, 0)
                """.trimIndent()
            )
        }
        helper.close()
    }

    /** 按导出的 6.json 建库并灌入数据（v6 真实库，尚无 ReplayGain 三列） */
    private fun createV6DatabaseWithData() {
        val schema = readSchema(6)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
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
            // 不写 rg_* 三列：依赖 6→7 迁移加列 + 默认值的兜底
            db.execSQL(
                """
                INSERT INTO songs (id, media_store_id, title, artist, album, album_id, volume_name,
                    duration, path, size, cover_uri, mimeType, year, track_number, date_added,
                    date_modified, favorite, play_count, last_played_at, user_edited)
                VALUES ($SONG_ID, 100, 'Song A', 'Artist', 'Album', 1, '',
                    200000, '/music/a.mp3', 1000, NULL, 'audio/mpeg', 2024, 1, 1000, 2000,
                    1, 3, 99, 0)
                """.trimIndent()
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

        // 必须补建索引：Room 的 createSql 只含 CREATE TABLE，索引由 Room 运行时
        // 单独下发 CREATE INDEX。旧实现只执行建表 + setupQueries，造出的「旧库」
        // 比真实旧库少掉全部索引——于是 4→5 这种「纯加索引」的迁移永远看起来
        // 没问题（本地 Windows 因此假绿），而 Linux 上 Room 的 schema 校验会
        // 实测出索引缺失并抛 IllegalStateException。
        val indexQueries = (0 until entities.length()).flatMap { i ->
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            val indices = e.optJSONArray("indices") ?: return@flatMap emptyList<String>()
            (0 until indices.length()).map { j ->
                indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
            }
        }
        return ddls to (setupQueries + indexQueries)
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val SONG_ID = 1L
        const val PLAYLIST_ID = 1L
    }
}

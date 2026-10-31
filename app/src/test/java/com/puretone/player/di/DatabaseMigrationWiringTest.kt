package com.puretone.player.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.PureToneDatabase
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
 * 生产数据库接线回归测试（v1.7.4 引入）。
 *
 * 痛点：v1.7.0 的线上事故是 `DataModule.provideDatabase` 漏注册了
 * `MIGRATION_6_7`——@Database(version=7) 但 builder 只注册到 5→6。
 * 升级用户（v1.6.0=schema 6）首次开库即抛
 * "A migration from 6 to 7 was required but not found"，启动即闪退。
 * 而 [PureToneDatabaseMigrationTest] 用**独立**的 addMigrations 列表注册了
 * 全量迁移，根本替生产代码兜不住这个"漏接线"的 bug。
 *
 * 本测试直接调用生产 builder [DataModule.provideDatabase]，预先造一个
 * 旧版本（当前为 schema 6）的真实库，再走生产开库路径升级：
 * 只要生产 builder 漏注册任何一个版本区间，这里就会抛 IllegalStateException 而失败。
 *
 * **维护约定**：每当 @Database(version) 自 N 升到 N+1 并新增 MIGRATION_(N)_(N+1)
 * 时，把下方 START_VERSION 改为 N，并把建库 INSERT 改成 N 版对应的列集合
 * （参照 app/schemas 下 (N).json 的 createSql）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationWiringTest {

    private lateinit var context: Context

    /** 升级起点版本：当前 @Database(version=7)，起点取 7-1=6 */
    private val START_VERSION = 6

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 删掉可能残留的生产库文件，确保从干净的旧版本库开始
        context.getDatabasePath(PureToneDatabase.DATABASE_NAME).delete()
    }

    @After
    fun tearDown() {
        context.getDatabasePath(PureToneDatabase.DATABASE_NAME).delete()
    }

    @Test
    fun `production builder migrates from previous schema without missing-migration crash`() = runTest {
        val dbFile = context.getDatabasePath(PureToneDatabase.DATABASE_NAME)
        createPreviousVersionDatabase(dbFile, START_VERSION)

        // 关键：走生产 builder（DataModule 的真实接线），而非测试自带的迁移列表。
        val db = DataModule.provideDatabase(context)

        // 触发真实打开 + 迁移 + schema 校验；任何一步不对（缺迁移 / DDL 漂移）都会抛。
        assertEquals("旧版本库的数据应在升级后完整保留", 1, db.songDao().count())
        val song = db.songDao().getById(1L)
        assertTrue("应能读到升级前的歌曲", song != null)
        assertEquals("Song A", song!!.title)

        // 终点版本必须等于当前导出的最高 schema 版本（即 @Database(version) 的值），
        // 否则说明版本号与迁移没对齐。@Database 注解 RetentionPolicy=CLASS，运行时反射
        // 取不到，故改用 schemas 目录里最大的版本号推导（同样能随发版自动对齐）。
        val expectedVersion = latestSchemaVersion()
        assertEquals(
            "生产库实际版本应与当前 schema 版本一致",
            expectedVersion,
            db.openHelper.writableDatabase.version
        )

        db.close()
    }

    /** 按导出的 (START_VERSION).json 造一个"真实旧版本库"，并灌入一条数据 */
    private fun createPreviousVersionDatabase(dbFile: File, version: Int) {
        val schema = readSchema(version)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
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
            // v6 schema：20 列（无 rg_* 三列，迁移 6→7 负责加），依赖迁移兜底默认值。
            db.execSQL(
                """
                INSERT INTO songs (id, media_store_id, title, artist, album, album_id, volume_name,
                    duration, path, size, cover_uri, mimeType, year, track_number, date_added,
                    date_modified, favorite, play_count, last_played_at, user_edited)
                VALUES (1, 100, 'Song A', 'Artist', 'Album', 1, '',
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

    /** 当前导出的 schema 最高版本号（等于 @Database(version)），用于断言开库终点版本 */
    private fun latestSchemaVersion(): Int {
        val candidates = listOf(
            File("schemas/com.puretone.player.data.local.PureToneDatabase"),
            File("app/schemas/com.puretone.player.data.local.PureToneDatabase")
        )
        val dir = candidates.firstOrNull { it.isDirectory }
            ?: error("未找到 schema 目录（已尝试 ${candidates.map { it.path }}）")
        return dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.maxOrNull()
            ?: error("未在 $dir 找到任何 schema 版本文件")
    }
}

package com.puretone.player.data.media

import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 生产 SQL selection 的**语义**回归测试（第五轮复审 D2）。
 *
 * 与 [MediaStoreScannerTest] 形成两层覆盖，二者缺一不可：
 * - [MediaStoreScannerTest] 用 [SqlLikeDisabledProvider] 故意忽略 selection，
 *   锁定「SQL LIKE 在个别机型失效时由 Kotlin 兜底」这一层；
 * - 本测试用**真实 SQLite 引擎**执行生产的 [MediaStoreScanner.buildSelection] +
 *   [MediaStoreScanner.buildSelectionArgs]，锁定「SQL 层本身就写对了」这一层。
 *
 * 为什么必须补这一层：替身忽略 selection 时，把 `NOT LIKE` 写成 `LIKE`、
 * `DURATION >= ?` 写成 `<=`、`DATE_MODIFIED >= ?` 写成 `<`、或把 args 顺序打乱，
 * 原有 1000+ 个用例仍然全绿——Kotlin 兜底会把 SQL 漏掉的行再过滤一遍，
 * 恰好掩盖了 SQL 写错。真机上有大量正常音乐时，SQL 写错会表现为**漏歌**或
 * 增量扫描扫到错误的时间范围，这类问题只能靠真实 SQL 执行暴露。
 *
 * 每个用例都先断言「不带 selection 的全量行数」> 0，防止空表导致假绿灯；
 * 再断言生产 selection 过滤后的路径集合，正负向一并锁定。
 *
 * 表列名全部用 [MediaStore] 常量拼装，与生产 selection 用的是同一套常量，
 * 不会因列名漂移而让测试「看起来通过但实际上查的是另一列」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreSelectionSqlTest {

    private lateinit var context: Context
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // 内存库，用例之间互不污染
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(CREATE_TABLE)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `SQL 层按目录 NOT LIKE 过滤录音且大小写不敏感`() {
        insert(db, "/storage/emulated/0/Music/a.mp3")
        insert(db, "/storage/emulated/0/Recordings/rec.m4a")
        insert(db, "/storage/emulated/0/recordings/lower.m4a")
        insert(db, "/storage/emulated/0/Call Recorder/call.m4a")
        assertFullRows(4)

        val paths = queryPaths()

        assertEquals(listOf("/storage/emulated/0/Music/a.mp3"), paths)
    }

    @Test
    fun `SQL 层按 MIME NOT IN 过滤全部录音格式`() {
        insert(db, "/storage/emulated/0/Music/a.mp3", mime = "audio/mpeg")
        insert(db, "/storage/emulated/0/Music/voice.amr", mime = "audio/amr")
        insert(db, "/storage/emulated/0/Music/voice.awb", mime = "audio/amr-wb")
        insert(db, "/storage/emulated/0/Music/voice.3gp", mime = "audio/3gpp")
        insert(db, "/storage/emulated/0/Music/voice.gsm", mime = "audio/gsm")
        assertFullRows(5)

        val paths = queryPaths()

        assertEquals(listOf("/storage/emulated/0/Music/a.mp3"), paths)
    }

    @Test
    fun `SQL 层按时长下限过滤短音频且保留等于边界的行`() {
        insert(db, "/storage/emulated/0/Music/long.mp3", durationMs = 200_000L)
        insert(db, "/storage/emulated/0/Music/short.mp3", durationMs = 29_999L)
        insert(db, "/storage/emulated/0/Music/exact.mp3", durationMs = 30_000L)
        assertFullRows(3)

        val paths = queryPaths()

        assertEquals(
            setOf(
                "/storage/emulated/0/Music/long.mp3",
                "/storage/emulated/0/Music/exact.mp3"
            ),
            paths.toSet()
        )
    }

    @Test
    fun `SQL 层过滤 IS_MUSIC 为零与 IS_PENDING IS_TRASHED 的行`() {
        insert(db, "/storage/emulated/0/Music/a.mp3")
        insert(db, "/storage/emulated/0/Music/not-music.wav", isMusic = 0)
        insert(db, "/storage/emulated/0/Music/pending.mp3", pending = 1)
        insert(db, "/storage/emulated/0/Music/trashed.mp3", trashed = 1)
        assertFullRows(4)

        val paths = queryPaths()

        assertEquals(listOf("/storage/emulated/0/Music/a.mp3"), paths)
    }

    @Test
    fun `增量 selection 的 DATE_MODIFIED 方向是大于等于而非小于`() {
        insert(db, "/storage/emulated/0/Music/old.mp3", modifiedSec = 1_999L)
        insert(db, "/storage/emulated/0/Music/boundary.mp3", modifiedSec = 2_000L)
        insert(db, "/storage/emulated/0/Music/new.mp3", modifiedSec = 2_001L)
        assertFullRows(3)

        val paths = queryPaths(sinceSec = 2_000L)

        assertEquals(
            setOf(
                "/storage/emulated/0/Music/boundary.mp3",
                "/storage/emulated/0/Music/new.mp3"
            ),
            paths.toSet()
        )
    }

    @Test
    fun `增量 selection 追加时间条件后参数仍与占位符一一对应`() {
        val scanner = MediaStoreScanner(context)
        val fullSel = scanner.buildSelection(null)
        val fullArgs = scanner.buildSelectionArgs(null)
        val incSel = scanner.buildSelection(SINCE_SEC)
        val incArgs = scanner.buildSelectionArgs(SINCE_SEC)

        assertEquals(fullSel.count { it == '?' }, fullArgs.size)
        assertEquals(incSel.count { it == '?' }, incArgs.size)
        assertEquals(fullArgs.size + 1, incArgs.size)
        assertEquals(MediaStoreScanner.MIN_DURATION_MS.toString(), incArgs[0])
        assertEquals(SINCE_SEC.toString(), incArgs[1])
        // 目录 LIKE 参数与 MIME 参数必须跟在时长/时间之后，顺序错位会让真实 SQL 绑错列。
        assertEquals("%/Ringtones/%", incArgs[2])
        assertEquals("audio/gsm", incArgs.last())

        insert(db, "/storage/emulated/0/Music/old.mp3", modifiedSec = SINCE_SEC - 1)
        insert(db, "/storage/emulated/0/Music/new.mp3", modifiedSec = SINCE_SEC)
        assertFullRows(2)
        assertEquals(
            listOf("/storage/emulated/0/Music/new.mp3"),
            queryPaths(sinceSec = SINCE_SEC)
        )
    }

    // ------------------------------------------------------------ helpers

    /** 用生产 selection + 生产 args 在真实 SQLite 上执行查询。 */
    private fun queryPaths(sinceSec: Long? = null): List<String> {
        val scanner = MediaStoreScanner(context)
        val sql = "SELECT ${MediaStore.Audio.Media.DATA} FROM $TABLE " +
            "WHERE ${scanner.buildSelection(sinceSec)}"
        val args: Array<Any?> = scanner.buildSelectionArgs(sinceSec)
            .map { it as Any? }
            .toTypedArray()
        return db.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            readPaths(cursor)
        }
    }

    private fun readPaths(cursor: Cursor): List<String> {
        val idx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val result = mutableListOf<String>()
        while (cursor.moveToNext()) result += cursor.getString(idx)
        return result
    }

    /** 负向断言：表里确实有数据，避免「空表 → 过滤结果为空 → 假绿灯」。 */
    private fun assertFullRows(expected: Int) {
        val count = db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $TABLE")).use { cursor ->
            assertTrue("全量查询必须能返回一行计数", cursor.moveToFirst())
            cursor.getInt(0)
        }
        assertEquals("测试数据未真正插入", expected, count)
    }

    @Suppress("LongParameterList")
    private fun insert(
        database: SupportSQLiteDatabase,
        path: String,
        mime: String = "audio/mpeg",
        durationMs: Long = 200_000L,
        isMusic: Int = 1,
        pending: Int = 0,
        trashed: Int = 0,
        modifiedSec: Long = 3_000L
    ) {
        database.execSQL(
            "INSERT INTO $TABLE (" +
                "${MediaStore.Audio.Media.IS_MUSIC}, " +
                "${MediaStore.Audio.Media.DURATION}, " +
                "${MediaStore.Audio.Media.DATE_MODIFIED}, " +
                "${MediaStore.MediaColumns.IS_PENDING}, " +
                "${MediaStore.MediaColumns.IS_TRASHED}, " +
                "${MediaStore.Audio.Media.DATA}, " +
                "${MediaStore.Audio.Media.MIME_TYPE}" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(isMusic, durationMs, modifiedSec, pending, trashed, path, mime)
        )
    }

    private companion object {
        const val TABLE = "media_rows"
        const val SINCE_SEC = 2_000L

        /** 列名与生产 selection 使用同一套 MediaStore 常量，避免列名漂移。 */
        val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                ${MediaStore.Audio.Media.IS_MUSIC} INTEGER,
                ${MediaStore.Audio.Media.DURATION} INTEGER,
                ${MediaStore.Audio.Media.DATE_MODIFIED} INTEGER,
                ${MediaStore.MediaColumns.IS_PENDING} INTEGER,
                ${MediaStore.MediaColumns.IS_TRASHED} INTEGER,
                ${MediaStore.Audio.Media.DATA} TEXT,
                ${MediaStore.Audio.Media.MIME_TYPE} TEXT
            )
        """.trimIndent()
    }
}

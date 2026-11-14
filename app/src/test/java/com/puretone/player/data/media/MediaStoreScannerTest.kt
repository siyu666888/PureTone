package com.puretone.player.data.media

import android.content.Context
import android.database.MatrixCursor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * 扫描器过滤规则回归测试。
 *
 * 背景：用户反馈"扫描结果全是录音类文件"。根因是曲库同步的删除方向写反
 * （把应删除的录音路径传给 NOT IN 查询，反而删掉音乐、留下录音）。
 * 本测试锁定 Kotlin 侧二次过滤这一兜底层：即使 SQL LIKE 在个别机型失效，
 * 录音/铃声/提示音也必须被剔除，正常音乐必须保留。
 *
 * robolectric 4.18 适配：4.15+ 的 `FakeMediaProvider` 会真执行 selection，
 * 原来的 `setCursor(RoboCursor)` 注入失效；统一改用 [SqlLikeDisabledProvider]
 * （忽略 selection + `MatrixCursor`），既保住「SQL LIKE 失效」语义，也出清了
 * deprecated `RoboCursor`。每个走 `scan()` 的用例都补了负向断言，证明 provider
 * 确实被调用且确实返回全量行，避免假绿灯。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreScannerTest {

    private fun songRow(
        id: Long,
        title: String,
        path: String,
        mime: String,
        durationMs: Long,
        artist: String = "Artist",
        album: String = "Album"
    ): Array<Any?> = arrayOf(
        id, title, artist, album, 1L, durationMs, path, 1_000L,
        mime, 2024, 1, 1_000L, 1_000L
    )

    /** 注册「忽略 selection」的 MediaStore provider，返回替身供负向断言使用。 */
    private fun register(vararg rows: Array<Any?>): SqlLikeDisabledProvider {
        val provider = SqlLikeDisabledProvider(PROJECTION.toTypedArray(), rows.asList())
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        return provider
    }

    @Test
    fun `keeps music and drops recordings even when sql like fails`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 4 首正常音乐（含 Download/ 下的 ogg），5 个录音/非音乐
        // provider 忽略 selection，9 行原样返回——恰好模拟"SQL LIKE 失效"的机型，
        // 验证 Kotlin 兜底过滤。
        val provider = register(
            songRow(1, "Song A", "/storage/emulated/0/Music/a.mp3", "audio/mpeg", 200_000),
            songRow(2, "Song B", "/storage/emulated/0/Music/b.flac", "audio/flac", 300_000),
            songRow(3, "Song C", "/storage/emulated/0/Music/c.m4a", "audio/mp4", 180_000),
            songRow(4, "Song D", "/storage/emulated/0/Download/d.ogg", "audio/ogg", 240_000),
            songRow(5, "录音001", "/storage/emulated/0/Recordings/rec1.m4a", "audio/mp4", 60_000),
            songRow(6, "Voice", "/storage/emulated/0/Sounds/voice.amr", "audio/amr", 45_000),
            songRow(7, "Call", "/storage/emulated/0/CallRecordings/call.m4a", "audio/mp4", 90_000),
            songRow(8, "Note", "/storage/emulated/0/Music/Voice 002.amr", "audio/amr", 50_000),
            songRow(9, "Rec", "/storage/emulated/0/VoiceRecorder/rec2.3gp", "audio/3gpp", 70_000)
        )

        val result = MediaStoreScanner(context).scan()
        val paths = result.songs.map { it.path }

        // 防假绿灯负向断言：selection 确实经真实 query 路径送达 provider，
        // 且 provider 确实忽略了它、返回全部 9 行——否则 Kotlin 兜底过滤根本没被覆盖。
        assertTrue(
            "SQL selection 未送达 provider → 未走真实查询路径",
            provider.receivedSelections.isNotEmpty()
        )
        assertTrue(
            "selection 缺少时长/MIME 条件 → 查询路径可疑：${provider.receivedSelections}",
            provider.receivedSelections.any { it.contains("duration") && it.contains("mime_type") }
        )
        assertEquals("provider 应忽略 selection 返回全部 9 行", 9, provider.returnedRows)

        assertEquals(4, result.songs.size)
        assertTrue(paths.contains("/storage/emulated/0/Music/a.mp3"))
        assertTrue(paths.contains("/storage/emulated/0/Music/b.flac"))
        assertTrue(paths.contains("/storage/emulated/0/Music/c.m4a"))
        assertTrue(paths.contains("/storage/emulated/0/Download/d.ogg"))
        // 录音一律不得出现（目录命中或录音扩展名命中）
        assertFalse(paths.any { it.contains("recordings", ignoreCase = true) })
        assertFalse(paths.any { it.contains("sounds", ignoreCase = true) })
        assertFalse(paths.any { it.contains("callrecording", ignoreCase = true) })
        assertFalse(paths.any { it.contains("voicerecorder", ignoreCase = true) })
        assertFalse(paths.any { it.endsWith(".amr", ignoreCase = true) })
        assertFalse(paths.any { it.endsWith(".3gp", ignoreCase = true) })
        // 跳过数量 = 候选总数 - 入库数 = 5
        assertEquals(5, result.skippedNonMusic)
    }

    @Test
    fun `keeps music that merely has music-like names`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = register(
            // 名字里带"录音"但确属音乐目录的音乐文件不应被误杀
            songRow(1, "录音棚的春天", "/storage/emulated/0/Music/录音棚的春天.mp3", "audio/mpeg", 200_000),
            songRow(2, "Recorded Live", "/storage/emulated/0/Music/live.flac", "audio/flac", 300_000)
        )
        val result = MediaStoreScanner(context).scan()

        // 负向断言：确认 2 行确实经 provider 送达，而不是查询恒空导致的巧合通过。
        assertTrue("查询未送达 provider", provider.receivedSelections.isNotEmpty())
        assertEquals("provider 应返回全部 2 行", 2, provider.returnedRows)
        assertEquals(2, result.songs.size)
        assertEquals(0, result.skippedNonMusic)
    }

    /**
     * 多卷去重（P1-6）：同一路径经主卷与 SD 卷各返回一次（mediaStoreId 不同），
     * 只入库一次。这里直接验证 readRows 的合并语义（不经过 provider）。
     */
    @Test
    fun `readRows dedups by path across volumes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scanner = MediaStoreScanner(context)
        val songsByPath = LinkedHashMap<String, ScannedSong>()
        val albumArtCache = HashMap<String, Boolean>()

        fun cursor(vararg rows: Array<Any?>) = MatrixCursor(PROJECTION.toTypedArray()).apply {
            rows.forEach { addRow(it.asList()) }
        }

        val primary = cursor(songRow(1, "A", "/storage/emulated/0/Music/a.mp3", "audio/mpeg", 200_000))
        val sd = cursor(songRow(9, "A", "/storage/emulated/0/Music/a.mp3", "audio/mpeg", 200_000))

        val pathsFromPrimary = scanner.readRows(primary, "external", songsByPath, albumArtCache)
        val pathsFromSd = scanner.readRows(sd, "1234-5678", songsByPath, albumArtCache)

        assertEquals(setOf("/storage/emulated/0/Music/a.mp3"), pathsFromPrimary)
        assertEquals(setOf("/storage/emulated/0/Music/a.mp3"), pathsFromSd)
        assertEquals(1, songsByPath.size)
        // 先到先得：保留主卷扫描到的行
        assertEquals(1L, songsByPath.values.first().mediaStoreId)
    }

    /**
     * P2-9：MediaStore 对缺失的歌手/专辑写入 `<unknown>`（大小写不一），
     * 扫描器必须归一为空串，交由上层兜底「未知艺术家/未知专辑」，
     * 不能原样入库（截图里播放页/队列/迷你条直接显示 <unknown>）。
     */
    @Test
    fun `normalizes unknown artist and album placeholders`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = register(
            songRow(
                1, "Song A", "/storage/emulated/0/Music/a.mp3", "audio/mpeg", 200_000,
                artist = "<unknown>", album = "<Unknown>"
            ),
            songRow(
                2, "Song B", "/storage/emulated/0/Music/b.flac", "audio/flac", 200_000,
                artist = "unknown", album = "未知"
            ),
            songRow(
                3, "Song C", "/storage/emulated/0/Music/c.m4a", "audio/mp4", 200_000,
                artist = "  <unknown>  ", album = "  "
            )
        )
        val result = MediaStoreScanner(context).scan()

        // 负向断言：确认 3 行确实经 provider 送达。
        assertTrue("查询未送达 provider", provider.receivedSelections.isNotEmpty())
        assertEquals("provider 应返回全部 3 行", 3, provider.returnedRows)
        assertEquals(3, result.songs.size)
        // 占位符一律归一为空串（真实值原样保留 trim）
        assertTrue(result.songs.all { it.artist.isEmpty() })
        assertTrue(result.songs.all { it.album.isEmpty() })
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

    // ------------------------------------------------------------ 增量扫描

    /**
     * selection 的 `?` 个数必须与 selectionArgs 长度严格相等。
     *
     * 增量扫描新加了 DATE_MODIFIED 条件，参数顺序是「时长 → 时间下界 → 目录 → MIME」。
     * 将来再加条件时若忘了同步 args，SQLite 不会报错、只会把值绑到错误的列上
     * （典型症状：扫描莫名其妙少歌/多歌）。这条断言把这类静默错误挡在 CI 之外。
     */
    @Test
    fun `selection 占位符数量与参数数组长度一致`() {
        val scanner = MediaStoreScanner(ApplicationProvider.getApplicationContext())

        val fullSel = scanner.buildSelection(null)
        val fullArgs = scanner.buildSelectionArgs(null)
        assertEquals(
            "全量：占位符数与参数数不符 → $fullSel / ${fullArgs.toList()}",
            fullSel.count { it == '?' },
            fullArgs.size
        )
        assertFalse("全量不应带时间条件", fullSel.contains("date_modified"))

        val incSel = scanner.buildSelection(1_700_000_000L)
        val incArgs = scanner.buildSelectionArgs(1_700_000_000L)
        assertEquals(
            "增量：占位符数与参数数不符 → $incSel / ${incArgs.toList()}",
            incSel.count { it == '?' },
            incArgs.size
        )
        assertTrue("增量必须带时间条件", incSel.contains("date_modified"))
        // 参数顺序：0=时长下界，1=时间下界
        assertEquals(MediaStoreScanner.MIN_DURATION_MS.toString(), incArgs[0])
        assertEquals("1700000000", incArgs[1])
        assertEquals(
            "增量应比全量多一个时间参数",
            fullArgs.size + 1,
            incArgs.size
        )
    }

    /**
     * 路径清单（删除对账）必须与入库口径一致：录音/系统目录里的文件不能进清单。
     *
     * 否则旧版本误入的录音会一直留在库里——清单里有它，就永远不算「已删除」。
     */
    @Test
    fun `readPaths 与入库口径一致，排除录音与系统目录`() {
        val scanner = MediaStoreScanner(ApplicationProvider.getApplicationContext())
        val cursor = MatrixCursor(
            arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.MIME_TYPE)
        )
        listOf(
            arrayOf<Any?>("/storage/emulated/0/Music/a.mp3", "audio/mpeg"),
            arrayOf<Any?>("/storage/emulated/0/Music/b.flac", "audio/flac"),
            arrayOf<Any?>("/storage/emulated/0/Recordings/rec.m4a", "audio/mp4"),
            arrayOf<Any?>("/storage/emulated/0/Sounds/voice.amr", "audio/amr")
        ).forEach { cursor.addRow(it.asList()) }

        val paths = scanner.readPaths(cursor)

        assertTrue(paths.contains("/storage/emulated/0/Music/a.mp3"))
        assertTrue(paths.contains("/storage/emulated/0/Music/b.flac"))
        assertFalse("录音目录不应进清单", paths.contains("/storage/emulated/0/Recordings/rec.m4a"))
        assertFalse("录音扩展名不应进清单", paths.contains("/storage/emulated/0/Sounds/voice.amr"))
        assertEquals(2, paths.size)
    }
}

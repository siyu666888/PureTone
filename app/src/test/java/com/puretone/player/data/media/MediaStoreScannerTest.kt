package com.puretone.player.data.media

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

/**
 * 扫描器过滤规则回归测试。
 *
 * 背景：用户反馈"扫描结果全是录音类文件"。根因是曲库同步的删除方向写反
 * （把应删除的录音路径传给 NOT IN 查询，反而删掉音乐、留下录音）。
 * 本测试锁定 Kotlin 侧二次过滤这一兜底层：即使 SQL LIKE 在个别机型失效，
 * 录音/铃声/提示音也必须被剔除，正常音乐必须保留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreScannerTest {

    private fun songRow(
        id: Long,
        title: String,
        path: String,
        mime: String,
        durationMs: Long
    ): Array<Any?> = arrayOf(
        id, title, "Artist", "Album", 1L, durationMs, path, 1_000L,
        mime, 2024, 1, 1_000L, 1_000L
    )

    private fun register(vararg rows: Array<Any?>) {
        val cursor = RoboCursor()
        cursor.setColumnNames(PROJECTION.toList())
        cursor.setResults(rows)
        shadowOf(ApplicationProvider.getApplicationContext<Context>().contentResolver)
            .setCursor(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor)
    }

    @Test
    fun `keeps music and drops recordings even when sql like fails`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 4 首正常音乐（含 Download/ 下的 ogg），5 个录音/非音乐
        register(
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
        // RoboCursor 不应用 selection，全部 9 行都会返回——
        // 恰好模拟"SQL LIKE 失效"的机型，验证 Kotlin 兜底过滤。
        val result = MediaStoreScanner(context).scan()
        val paths = result.songs.map { it.path }

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
        register(
            // 名字里带"录音"但确属音乐目录的音乐文件不应被误杀
            songRow(1, "录音棚的春天", "/storage/emulated/0/Music/录音棚的春天.mp3", "audio/mpeg", 200_000),
            songRow(2, "Recorded Live", "/storage/emulated/0/Music/live.flac", "audio/flac", 300_000)
        )
        val result = MediaStoreScanner(context).scan()

        assertEquals(2, result.songs.size)
        assertEquals(0, result.skippedNonMusic)
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

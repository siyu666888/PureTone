package com.puretone.player.playback.cast

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [CastFilePreparer] 行为测试（M2 投屏源准备）。
 *
 * 覆盖的是「分流 + 失败收敛」这两个本类自己的契约，转码质量由
 * `ApeTranscoderTest` 与真机负责，这里用假回调注入。
 */
class CastFilePreparerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun existingFile(name: String, size: Int = 64): File =
        tmp.newFile(name).apply { writeBytes(ByteArray(size)) }

    // ------------------------------------------------------------------ 直投路径

    @Test
    fun `白名单格式直接返回源文件且不触发转码`() = runTest {
        val src = existingFile("song.mp3")
        var calls = 0
        val preparer = CastFilePreparer { _, _ -> calls++; null }

        val result = preparer.prepare(songId = 1L, path = src.absolutePath)

        assertTrue(result is CastFilePreparer.Result.Ready)
        result as CastFilePreparer.Result.Ready
        assertEquals(src, result.file)
        assertFalse("直投不应标记为转码产物", result.transcoded)
        assertEquals("直投不应调用转码", 0, calls)
    }

    // ------------------------------------------------------------------ 转码路径

    @Test
    fun `APE 走转码并返回转码产物`() = runTest {
        val src = existingFile("song.ape")
        val wav = existingFile("song_1.wav", size = 1024)
        var seenId = -1L
        var seenPath: String? = null
        val preparer = CastFilePreparer { id, path ->
            seenId = id
            seenPath = path
            wav
        }

        val result = preparer.prepare(songId = 42L, path = src.absolutePath)

        assertTrue(result is CastFilePreparer.Result.Ready)
        result as CastFilePreparer.Result.Ready
        assertEquals(wav, result.file)
        assertTrue(result.transcoded)
        assertEquals("转码回调应收到曲目 id（缓存 key）", 42L, seenId)
        assertEquals(src.absolutePath, seenPath)
    }

    @Test
    fun `非白名单的非 APE 格式同样走转码`() = runTest {
        val src = existingFile("song.ogg")
        val wav = existingFile("song_2.wav", size = 512)
        var calls = 0
        val preparer = CastFilePreparer { _, _ -> calls++; wav }

        val result = preparer.prepare(songId = 2L, path = src.absolutePath)

        assertTrue(result is CastFilePreparer.Result.Ready)
        assertTrue((result as CastFilePreparer.Result.Ready).transcoded)
        assertEquals(1, calls)
    }

    // ------------------------------------------------------------------ 失败收敛

    @Test
    fun `源文件不存在时直接失败且不调用转码`() = runTest {
        var calls = 0
        val preparer = CastFilePreparer { _, _ -> calls++; null }
        val missing = File(tmp.root, "ghost.ape")

        val result = preparer.prepare(songId = 3L, path = missing.absolutePath)

        assertTrue(result is CastFilePreparer.Result.Failed)
        assertTrue(
            "错误文案应指向源文件",
            (result as CastFilePreparer.Result.Failed).reason.contains("源文件不存在")
        )
        assertEquals("源文件缺失不该走到转码", 0, calls)
    }

    @Test
    fun `转码返回 null 时给出转码失败提示`() = runTest {
        val src = existingFile("song.ape")
        val preparer = CastFilePreparer { _, _ -> null }

        val result = preparer.prepare(songId = 4L, path = src.absolutePath)

        assertTrue(result is CastFilePreparer.Result.Failed)
        assertTrue(
            (result as CastFilePreparer.Result.Failed).reason.contains("转码失败")
        )
    }

    @Test
    fun `转码回调抛异常被收敛为失败而不是向上抛`() = runTest {
        val src = existingFile("song.ape")
        val preparer = CastFilePreparer { _, _ -> error("ffmpeg 崩了") }

        val result = preparer.prepare(songId = 5L, path = src.absolutePath)

        assertTrue("任何异常都必须收敛为 Failed", result is CastFilePreparer.Result.Failed)
    }

    @Test
    fun `转码产物为空文件时判为不可用`() = runTest {
        val src = existingFile("song.ape")
        val empty = tmp.newFile("empty.wav") // 0 字节
        val preparer = CastFilePreparer { _, _ -> empty }

        val result = preparer.prepare(songId = 6L, path = src.absolutePath)

        assertTrue(result is CastFilePreparer.Result.Failed)
        assertTrue(
            (result as CastFilePreparer.Result.Failed).reason.contains("转码产物不可用")
        )
    }

    @Test
    fun `转码产物路径不存在时判为不可用`() = runTest {
        val src = existingFile("song.ape")
        val ghost = File(tmp.root, "not_created.wav")
        val preparer = CastFilePreparer { _, _ -> ghost }

        val result = preparer.prepare(songId = 7L, path = src.absolutePath)

        assertTrue(result is CastFilePreparer.Result.Failed)
    }
}

package com.puretone.player.playback

import android.net.Uri
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ApePlaybackResolver] 契约测试（P1-1 拆分第三刀）。
 *
 * 这一层守护的是「转码中标记一定会复位」这条不变量：三处调用点原先各写一遍，
 * 只有出错重试那条用了 `try/finally`。另两处一旦中途抛出，UI 就永久停在
 * 「APE 转码中…」——用户看到转圈不动，切歌也没反应。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApePlaybackResolverTest {

    private val wavFile = File("/tmp/ape_1.wav")

    /** 记录 onTranscoding 的调用序列，用于断言「先 true 后 false，且以 false 收尾」 */
    private val marks = mutableListOf<Boolean>()

    private fun resolver(transcode: suspend (Long, String) -> File?) =
        ApePlaybackResolver(transcode = transcode, onTranscoding = { marks.add(it) })

    // ------------------------------------------------------------------ 非 APE

    @Test
    fun `非 APE 直接返回 MediaStore URI 且不触碰转码`() = runBlocking {
        var transcoded = 0
        val r = resolver { _, _ -> transcoded++; null }

        val uri = r.resolveUri(1L, "/sdcard/a.mp3", isApe = false) {
            Uri.parse("content://media/external/audio/media/7")
        }

        assertEquals("content://media/external/audio/media/7", uri.toString())
        assertEquals("非 APE 不应转码", 0, transcoded)
        assertTrue("非 APE 不应点亮转码标记", marks.isEmpty())
    }

    // -------------------------------------------------------------------- APE

    @Test
    fun `APE 转码成功返回文件 URI 且标记先开后关`() = runBlocking {
        val r = resolver { id, path ->
            assertEquals(9L, id)
            assertEquals("/sdcard/x.ape", path)
            wavFile
        }

        val uri = r.resolveUri(9L, "/sdcard/x.ape", isApe = true) {
            error("APE 不应走 MediaStore")
        }

        assertEquals(Uri.fromFile(wavFile).toString(), uri.toString())
        assertEquals(listOf(true, false), marks)
    }

    @Test
    fun `转码返回 null 时判为失败且标记仍复位`() = runBlocking {
        val r = resolver { _, _ -> null }

        val uri = r.resolveUri(1L, "/sdcard/x.ape", isApe = true) { Uri.EMPTY }

        assertNull(uri)
        assertEquals("失败路径同样必须复位", listOf(true, false), marks)
    }

    @Test
    fun `转码抛异常被收敛为失败且标记仍复位`() = runBlocking {
        val r = resolver { _, _ -> error("ffmpeg-kit 原生库缺失") }

        val file = r.transcodeToFile(1L, "/sdcard/x.ape")

        assertNull("异常必须收敛为 null，不能冒泡到调用方", file)
        assertEquals(listOf(true, false), marks)
    }

    @Test
    fun `协程取消必须原样抛出但标记依然复位`() {
        // 取消不是失败：吞掉它会让已取消的任务继续执行后续 setMediaItems / play()
        val r = resolver { _, _ -> throw CancellationException("用户切歌取消了转码") }

        var caught: Throwable? = null
        runBlocking {
            try {
                r.transcodeToFile(1L, "/sdcard/x.ape")
            } catch (t: Throwable) {
                caught = t
            }
        }

        assertTrue("CancellationException 必须透传", caught is CancellationException)
        assertEquals("取消路径同样必须复位标记", listOf(true, false), marks)
    }

    // ---------------------------------------------------------- 仅转码的调用点

    @Test
    fun `transcodeToFile 返回转码产物供调用方替换媒体项`() = runBlocking {
        val r = resolver { _, _ -> wavFile }

        val file = r.transcodeToFile(3L, "/sdcard/y.ape")

        assertEquals(wavFile, file)
        assertEquals(listOf(true, false), marks)
    }

    @Test
    fun `多次转码标记成对出现不残留`() = runBlocking {
        val r = resolver { _, _ -> wavFile }

        r.transcodeToFile(1L, "/sdcard/a.ape")
        r.transcodeToFile(2L, "/sdcard/b.ape")

        assertEquals(listOf(true, false, true, false), marks)
        assertFalse("最后一次必须是关闭", marks.last())
    }
}

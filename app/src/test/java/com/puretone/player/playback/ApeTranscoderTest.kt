package com.puretone.player.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ApeTranscoder] 编排层行为测试。
 *
 * 用 [FakeTranscoder] 替身接管转码，从而**不依赖 ffmpeg-kit 原生库**（Robolectric
 * 下无法运行真实 ffmpeg），专注验证编排层自己该保证的契约：
 * 1. 缓存命中不重复转码（幂等）；
 * 2. 同曲并发只真正转一次（每曲 Mutex 去重，P0-5 / P1-4）；
 * 3. 转码失败返回 null 且不留 `.tmp` 垃圾（不留假缓存）；
 * 4. 超出 [ApeCachePolicy.maxBytes] 时按 LRU 淘汰最久未用的曲目（P2-16 相关）；
 * 5. 引擎不可用时快速失败，不进入转码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApeTranscoderTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.cacheDir, "ape_transcode")
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    // ---------------------------------------------------------------- 缓存与幂等

    @Test
    fun `首次调用触发转码，二次调用命中缓存不再转码`() = runTest {
        val fake = FakeTranscoder()
        val transcoder = ApeTranscoder(context, fake, ApeCachePolicy(maxBytes = 4096))
        val source = newSourceFile(1)

        val first = transcoder.ensureWav(1, source.absolutePath)
        assertNotNull("首次应转码成功", first)
        assertTrue(first!!.exists())

        val second = transcoder.ensureWav(1, source.absolutePath)
        assertNotNull(second)
        assertEquals(first.absolutePath, second!!.absolutePath)
        assertEquals("命中缓存不应重复调用转码器", 1, fake.callCount())
    }

    @Test
    fun `源文件不存在时返回 null 且不调用转码器`() = runTest {
        val fake = FakeTranscoder()
        val transcoder = ApeTranscoder(context, fake, ApeCachePolicy(maxBytes = 4096))

        assertNull(transcoder.ensureWav(99, "/definitely/not/exist.ape"))
        assertEquals(0, fake.callCount())
    }

    // ---------------------------------------------------------------- 并发去重

    @Test
    fun `同曲并发只会真正转码一次`() = runTest {
        // 转码器故意慢一点，放大并发窗口；若 Mutex 失效会观察到多次调用
        val fake = FakeTranscoder(block = { delay(80) })
        val transcoder = ApeTranscoder(context, fake, ApeCachePolicy(maxBytes = 4096))
        val source = newSourceFile(7)

        val results = withContext(Dispatchers.IO) {
            (1..8).map { async { transcoder.ensureWav(7, source.absolutePath) } }.awaitAll()
        }

        assertTrue("并发调用都应拿到结果", results.all { it != null })
        assertEquals("同曲并发必须去重为一次", 1, fake.callCount())
    }

    @Test
    fun `不同曲目并发互不阻塞且各自转码一次`() = runTest {
        val fake = FakeTranscoder(block = { delay(40) })
        val transcoder = ApeTranscoder(context, fake, ApeCachePolicy(maxBytes = 4096))

        withContext(Dispatchers.IO) {
            (1..4).map { id ->
                async { transcoder.ensureWav(id.toLong(), newSourceFile(id).absolutePath) }
            }.awaitAll()
        }

        assertEquals(4, fake.callCount())
        assertTrue(File(cacheDir, "ape_1.wav").exists())
        assertTrue(File(cacheDir, "ape_4.wav").exists())
    }

    // ---------------------------------------------------------------- 失败处理

    @Test
    fun `转码失败返回 null 且不残留目标文件与 tmp`() = runTest {
        val fake = FakeTranscoder(result = false)
        val transcoder = ApeTranscoder(context, fake, ApeCachePolicy(maxBytes = 4096))
        val source = newSourceFile(3)

        assertNull(transcoder.ensureWav(3, source.absolutePath))
        assertFalse("失败后不应留下正式缓存", File(cacheDir, "ape_3.wav").exists())
        assertFalse("失败后不应留下 .tmp 垃圾", File(cacheDir, "ape_3.wav.tmp").exists())
    }

    @Test
    fun `引擎不可用时快速失败且不调用转码`() = runTest {
        val fake = FakeTranscoder(available = false)
        val transcoder = ApeTranscoder(context, fake, ApeCachePolicy(maxBytes = 4096))
        val source = newSourceFile(5)

        assertNull(transcoder.ensureWav(5, source.absolutePath))
        assertEquals("不可用时应短路，不进入转码", 0, fake.callCount())
    }

    // ---------------------------------------------------------------- LRU 淘汰

    @Test
    fun `超出上限时按 LRU 淘汰最久未用的曲目`() = runTest {
        // 每个产物约 60 字节，上限 120 字节：第三个转完必然触发淘汰
        val fake = FakeTranscoder(payloadBytes = 60)
        val transcoder = ApeTranscoder(context, fake, ApeCachePolicy(maxBytes = 120))

        // 时间戳需严格递增，否则 LRU 排序不稳定（文件系统精度差异）
        transcoder.ensureWav(1, newSourceFile(1).absolutePath)
        Thread.sleep(60)
        transcoder.ensureWav(2, newSourceFile(2).absolutePath)
        Thread.sleep(60)
        transcoder.ensureWav(3, newSourceFile(3).absolutePath)

        assertFalse("最久未用的 1 号应被淘汰", File(cacheDir, "ape_1.wav").exists())
        assertTrue(File(cacheDir, "ape_2.wav").exists())
        assertTrue(File(cacheDir, "ape_3.wav").exists())
        assertTrue("淘汰后总量应回到上限内", transcoder.cacheSize() <= 120)
    }

    @Test
    fun `命中缓存会刷新 LRU 位置，避免刚听过的歌被淘汰`() = runTest {
        val fake = FakeTranscoder(payloadBytes = 60)
        val transcoder = ApeTranscoder(context, fake, ApeCachePolicy(maxBytes = 120))
        val source1 = newSourceFile(1)

        transcoder.ensureWav(1, source1.absolutePath)
        Thread.sleep(60)
        transcoder.ensureWav(2, newSourceFile(2).absolutePath)
        Thread.sleep(60)
        // 重新听 1 号（命中缓存 → touch 更新时间戳）
        transcoder.ensureWav(1, source1.absolutePath)
        Thread.sleep(60)
        transcoder.ensureWav(3, newSourceFile(3).absolutePath)

        assertFalse("2 号最久未用，应被淘汰", File(cacheDir, "ape_2.wav").exists())
        assertTrue("刚听过的 1 号应保留", File(cacheDir, "ape_1.wav").exists())
        assertTrue(File(cacheDir, "ape_3.wav").exists())
    }

    // ---------------------------------------------------------------- 缓存管理

    @Test
    fun `clearCache 清空全部缓存`() = runTest {
        val fake = FakeTranscoder()
        val transcoder = ApeTranscoder(context, fake, ApeCachePolicy(maxBytes = 4096))
        transcoder.ensureWav(1, newSourceFile(1).absolutePath)
        assertTrue(transcoder.cacheSize() > 0)

        transcoder.clearCache()
        assertEquals(0L, transcoder.cacheSize())
    }

    // ---------------------------------------------------------------- 测试替身

    /** 建立一个非空源文件（ensureWav 要求源存在） */
    private fun newSourceFile(id: Int): File =
        File(context.cacheDir, "src_$id.ape").apply { writeBytes(ByteArray(32)) }

    /**
     * [Transcoder] 替身：真实写出目标文件（模拟转码产物），并统计调用次数。
     * 用同步容器保证并发测试下计数准确。
     */
    private class FakeTranscoder(
        private val result: Boolean = true,
        private val available: Boolean = true,
        private val payloadBytes: Int = 64,
        private val block: suspend (TranscodeRequest) -> Unit = {},
    ) : Transcoder {

        private val requests = Collections.synchronizedList(mutableListOf<TranscodeRequest>())

        override suspend fun transcode(request: TranscodeRequest): Boolean {
            requests += request
            block(request)
            if (!result) return false
            // 真实转码器会产出目标文件；写点内容让后续 rename / LRU 逻辑成立
            request.destination.writeBytes(ByteArray(payloadBytes))
            return true
        }

        override fun isAvailable(): Boolean = available

        fun callCount(): Int = requests.size
    }
}

package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [CastEntryRegistry] 单测（第三轮审查 P2-1）。
 *
 * 全程用可控时钟、不起 socket：重点锁定「沉寂超时才淘汰」「恰好等于 TTL 保留」
 * 以及「正在被访问的旧 token 不会被换曲误删」——后者是 R7 长连接供流的兼容底线。
 */
class CastEntryRegistryTest {

    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(deltaMs: Long) {
            nowMs += deltaMs
        }
    }

    private fun subject(
        clock: FakeClock,
        ttlMs: Long = CastEntryRegistry.ENTRY_TTL_MS,
    ): CastEntryRegistry = CastEntryRegistry(ttlMs = ttlMs, clock = { clock.nowMs })

    private val flac = File("track.flac")
    private val mp3 = File("next.mp3")

    @Test
    fun `登记后可命中且返回文件与MIME`() {
        val registry = subject(FakeClock())
        assertTrue(registry.register("token-a", flac, "audio/flac").isEmpty())

        val snapshot = registry.find("token-a")
        assertNotNull(snapshot)
        assertEquals(flac, snapshot!!.file)
        assertEquals("audio/flac", snapshot.mimeType)
        assertEquals(1, registry.size())
    }

    @Test
    fun `未超过TTL的旧token换曲后仍可命中`() {
        val clock = FakeClock()
        val registry = subject(clock)
        registry.register("old", flac, "audio/flac")

        clock.advance(CastEntryRegistry.ENTRY_TTL_MS - 1)
        assertTrue(registry.register("new", mp3, "audio/mpeg").isEmpty())

        assertNotNull(registry.find("old"))
        assertNotNull(registry.find("new"))
        assertEquals(2, registry.size())
    }

    @Test
    fun `恰好等于TTL不淘汰`() {
        val clock = FakeClock()
        val registry = subject(clock)
        registry.register("old", flac, "audio/flac")

        clock.advance(CastEntryRegistry.ENTRY_TTL_MS)
        assertTrue(registry.register("new", mp3, "audio/mpeg").isEmpty())
        assertNotNull(registry.find("old"))
    }

    @Test
    fun `超过TTL未访问的旧token在登记新曲时被淘汰`() {
        val clock = FakeClock()
        val registry = subject(clock)
        registry.register("old", flac, "audio/flac")

        clock.advance(CastEntryRegistry.ENTRY_TTL_MS + 1)
        val evicted = registry.register("new", mp3, "audio/mpeg")

        assertEquals(listOf("old"), evicted)
        assertNull(registry.find("old"))
        assertNotNull(registry.find("new"))
        assertEquals(1, registry.size())
    }

    @Test
    fun `命中find会刷新访问时刻使活跃旧token不被淘汰`() {
        val clock = FakeClock()
        val registry = subject(clock)
        registry.register("old", flac, "audio/flac")

        // 模拟 R7 现场：登记后 20 分钟电视仍来重连旧 URL（长连接 / Range 重试）
        clock.advance(20 * 60 * 1000L)
        assertNotNull(registry.find("old"))

        // 再过 20 分钟，距「最近一次访问」仅 20 分钟，不应被淘汰
        clock.advance(20 * 60 * 1000L)
        assertTrue(registry.register("new", mp3, "audio/mpeg").isEmpty())
        assertNotNull(registry.find("old"))
    }

    @Test
    fun `被淘汰的token失效且再次登记同token可用`() {
        val clock = FakeClock()
        val registry = subject(clock)
        registry.register("old", flac, "audio/flac")

        clock.advance(CastEntryRegistry.ENTRY_TTL_MS + 1)
        registry.register("new", mp3, "audio/mpeg")
        assertNull(registry.find("old"))

        registry.register("old", mp3, "audio/mpeg")
        assertEquals("audio/mpeg", registry.find("old")!!.mimeType)
    }

    @Test
    fun `连续换曲只保留未过期条目`() {
        val clock = FakeClock()
        val registry = subject(clock)
        repeat(10) { index ->
            clock.advance(10 * 60 * 1000L)
            registry.register("t$index", flac, "audio/flac")
        }
        // t0..t5 早已沉寂超 30 分钟被淘汰；t6 恰好 30 分钟保留，连同 t7/t8/t9
        assertEquals(4, registry.size())
        assertNull(registry.find("t0"))
        assertNotNull(registry.find("t6"))
        assertNotNull(registry.find("t9"))
    }

    @Test
    fun `prune返回被淘汰token且可独立调用`() {
        val clock = FakeClock()
        val registry = subject(clock, ttlMs = 100L)
        registry.register("a", flac, "audio/flac")
        registry.register("b", mp3, "audio/mpeg")

        clock.advance(101L)
        assertEquals(setOf("a", "b"), registry.prune().toSet())
        assertEquals(0, registry.size())
    }

    @Test
    fun `clear清空全部条目`() {
        val registry = subject(FakeClock())
        registry.register("a", flac, "audio/flac")
        registry.register("b", mp3, "audio/mpeg")

        registry.clear()
        assertEquals(0, registry.size())
        assertNull(registry.find("a"))
    }
}

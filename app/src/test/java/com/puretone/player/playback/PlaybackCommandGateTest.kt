package com.puretone.player.playback

import com.puretone.player.data.local.entity.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackCommandGate] 行为测试（P1-1 第二轮：队列命令闸门）。
 *
 * 这簇用例直接对应拆分前散在 `PlaybackManager` 里的三份隐式状态，以及它们已实际造成的
 * 缺陷。每条断言都写成「用户可感知的后果」，避免以后有人为了通过测试去改语义：
 *
 * | 缺陷 | 表现 | 对应用例 |
 * |---|---|---|
 * | 索引映射变化漏复位 APE 重试标记 | 删掉前一首后，另一个 APE 不被转码，卡在 IDLE | `删除队列项后同索引 APE 重新获得重试资格` |
 * | `clearQueue` 未递增代次 | 点了清空，队列隔一会儿自己冒回来 | `结构变化使在途的旧队列命令过期` |
 * | `pendingPlay`/`pendingRestore` 互斥靠人工维护 | bind 后旧会话盖掉用户刚点的歌 | `显式点歌会清掉已排的续播` |
 */
class PlaybackCommandGateTest {

    private fun song(id: Long = 1L) = SongEntity(
        id = id,
        mediaStoreId = id * 10,
        title = "曲 $id",
        artist = "歌手",
        album = "专辑",
        albumId = 1L,
        duration = 180_000L,
        path = "/sdcard/Music/$id.mp3",
        size = 1024L,
        mimeType = "audio/mpeg",
    )

    private fun req(startIndex: Int = 0) =
        PendingPlay(listOf(song(1), song(2)), startIndex, 0L)

    // ------------------------------------------------------------------ 代次

    @Test
    fun `代次递增且只认最新`() {
        val gate = PlaybackCommandGate()
        val first = gate.nextGeneration()
        assertTrue(gate.isCurrent(first))
        val second = gate.nextGeneration()
        assertEquals(first + 1, second)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `结构变化使在途的旧队列命令过期`() {
        val gate = PlaybackCommandGate()
        val inFlight = gate.nextGeneration()
        // 用户点了清空 / 拖拽重排 / 删除条目
        gate.onQueueStructureChanged()
        assertFalse(gate.isCurrent(inFlight))
    }

    @Test
    fun `resetApeRetry 不递增代次`() {
        // doSetQueueAndPlay 先 nextGeneration 取号、挂起恢复后再复位 APE 重试标记；
        // 若此处误用 onQueueStructureChanged，会把刚取的号冲掉，导致新队列整体不生效。
        val gate = PlaybackCommandGate()
        val g = gate.nextGeneration()
        gate.resetApeRetry()
        assertTrue(gate.isCurrent(g))
    }

    // ------------------------------------------------------------------ 挂起命令

    @Test
    fun `显式点歌会清掉已排的续播`() {
        val gate = PlaybackCommandGate()
        gate.pendRestore()
        gate.pendPlay(req())
        val drained = gate.drain()
        assertFalse(drained.restore)
        assertEquals(0, drained.play?.startIndex)
    }

    @Test
    fun `续播不会覆盖已排的点歌`() {
        val gate = PlaybackCommandGate()
        gate.pendPlay(req(startIndex = 1))
        gate.pendRestore()
        val drained = gate.drain()
        // 点歌在前、续播被 drain 出来但 bind 会先执行点歌；关键是不能把点歌挤掉
        assertEquals(1, drained.play?.startIndex)
    }

    @Test
    fun `追加命令多次累加并保持顺序`() {
        val gate = PlaybackCommandGate()
        gate.pendEnqueue(listOf(song(1), song(2)))
        gate.pendEnqueue(listOf(song(3)))
        assertEquals(listOf(1L, 2L, 3L), gate.drain().enqueue.map { it.id })
    }

    @Test
    fun `追加空列表不改变挂起状态`() {
        val gate = PlaybackCommandGate()
        gate.pendEnqueue(emptyList())
        assertTrue(gate.drain().isEmpty)
    }

    @Test
    fun `drain 一次取走全部并清空`() {
        val gate = PlaybackCommandGate()
        gate.pendPlay(req())
        gate.pendEnqueue(listOf(song(9)))
        val drained = gate.drain()
        assertTrue(drained.play != null)
        assertEquals(1, drained.enqueue.size)
        // 第二次取走必须什么也没有——否则重放会重复执行点歌请求
        assertTrue(gate.drain().isEmpty)
    }

    @Test
    fun `drain 之后新命令仍可正常挂起`() {
        val gate = PlaybackCommandGate()
        gate.pendPlay(req())
        gate.drain()
        gate.pendRestore()
        assertTrue(gate.drain().restore)
    }

    @Test
    fun `clearPending 清掉包括点歌在内的全部命令`() {
        val gate = PlaybackCommandGate()
        gate.pendPlay(req())
        gate.pendEnqueue(listOf(song(9)))
        gate.pendRestore()
        gate.clearPending()
        assertTrue(gate.drain().isEmpty)
    }

    @Test
    fun `解绑只清会话绑定的命令并保留用户点歌`() {
        // unbind 发生在服务销毁时：追加与续播依赖当前会话，应作废；
        // 但用户显式点的歌在服务重建后仍应播放，否则表现为「点了歌没反应」。
        val gate = PlaybackCommandGate()
        gate.pendPlay(req())
        gate.pendEnqueue(listOf(song(9)))
        gate.clearSessionBoundPending()
        val drained = gate.drain()
        assertTrue(drained.play != null)
        assertTrue(drained.enqueue.isEmpty())
        assertFalse(drained.restore)
    }

    // ------------------------------------------------------------------ APE 重试标记

    @Test
    fun `同一索引只允许自动重试一次`() {
        val gate = PlaybackCommandGate()
        assertTrue(gate.tryAcquireApeRetry(2))
        // 第二次同索引失败必须放弃，否则「出错→转码→再出错→再转码」无限循环
        assertFalse(gate.tryAcquireApeRetry(2))
    }

    @Test
    fun `不同索引各自拥有重试资格`() {
        val gate = PlaybackCommandGate()
        assertTrue(gate.tryAcquireApeRetry(0))
        assertTrue(gate.tryAcquireApeRetry(1))
        assertFalse(gate.tryAcquireApeRetry(1))
    }

    @Test
    fun `删除队列项后同索引 APE 重新获得重试资格`() {
        // 真实缺陷：removeFromQueue 让后续项索引左移，而旧实现没有复位重试标记，
        // 于是「原本 index=2 的 APE 重试过」会让新的 index=2（另一首歌）被判定
        // 「已重试过」而跳过转码，直接卡在 IDLE。
        val gate = PlaybackCommandGate()
        assertTrue(gate.tryAcquireApeRetry(2))
        gate.onQueueStructureChanged() // 删除 / 插播 / 重排 / 清空都走这里
        assertTrue(gate.tryAcquireApeRetry(2))
    }

    @Test
    fun `新队列生效前复位后可再次重试`() {
        val gate = PlaybackCommandGate()
        assertTrue(gate.tryAcquireApeRetry(0))
        gate.resetApeRetry()
        assertTrue(gate.tryAcquireApeRetry(0))
    }

    @Test
    fun `未重试过的索引不受其它索引影响`() {
        val gate = PlaybackCommandGate()
        assertTrue(gate.tryAcquireApeRetry(0))
        assertTrue(gate.tryAcquireApeRetry(5))
        assertFalse(gate.tryAcquireApeRetry(5))
    }

    // ------------------------------------------------------------------ 组合场景

    @Test
    fun `挂起的点歌与追加在 drain 后可同时重放`() {
        val gate = PlaybackCommandGate()
        gate.pendPlay(req(startIndex = 1))
        gate.pendEnqueue(listOf(song(7)))
        val drained = gate.drain()
        assertEquals(1, drained.play?.startIndex)
        assertEquals(listOf(7L), drained.enqueue.map { it.id })
        assertFalse(drained.isEmpty)
    }

    @Test
    fun `空闸门 drain 结果为空`() {
        assertTrue(PlaybackCommandGate().drain().isEmpty)
    }

    @Test
    fun `点歌请求原样保留起播位置`() {
        val gate = PlaybackCommandGate()
        gate.pendPlay(PendingPlay(listOf(song(1)), 0, 42_500L))
        val drained = gate.drain()
        assertEquals(42_500L, drained.play?.startPositionMs)
    }

    @Test
    fun `续播挂起后 drain 只返回续播标志`() {
        val gate = PlaybackCommandGate()
        gate.pendRestore()
        val drained = gate.drain()
        assertNull(drained.play)
        assertTrue(drained.restore)
    }
}

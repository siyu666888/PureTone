package com.puretone.player.playback

import androidx.media3.common.MediaItem
import com.puretone.player.data.settings.PureToneSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 交叉淡化状态机的语义测试。
 *
 * 只锁**时序与不变式**，数值部分（触发窗口、渐变步进）已由
 * [CrossfadePolicyTest] / [CrossfadeRampTest] 覆盖。这里关注的是那些「错了不会立刻报错，
 * 而是几十秒后的下一次淡化才听得出来」的行为：
 *
 * - 取消后音量是否真的复位（没复位 → 下一次从错误音量起播，听感上是爆音）；
 * - 收尾是否幂等（渐变协程与主播放器 STATE_ENDED 谁先到都可能）；
 * - 剩余曲目是否补全到新主（没补全 → 队列悄悄变短）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossfadeCoordinatorTest {

    // ---------- 替身 ----------

    private class FakePlayer(
        override var durationMs: Long = 0L,
        override var positionMs: Long = 0L,
        val items: MutableList<MediaItem> = mutableListOf(),
        override var currentIndex: Int = 0,
        /** 与 [FakeHost] 共享的调用顺序日志：锁「先交权、后复位旧主」 */
        val callLog: MutableList<String> ? = null
    ) : CrossfadePlayer {

        override val itemCount: Int get() = items.size
        override val hasNext: Boolean get() = currentIndex + 1 < items.size
        override val currentItemId: Long?
            get() = items.getOrNull(currentIndex)?.mediaId?.toLongOrNull()

        var currentVolume: Float = 1f
        var startCount: Int = 0
        var clearCount: Int = 0
        var removeCount: Int = 0
        var insertCount: Int = 0

        override fun itemsFrom(index: Int): List<MediaItem> =
            items.subList(index.coerceIn(0, items.size), items.size).toList()

        override fun removeItem(index: Int) {
            removeCount++
            if (index in items.indices) items.removeAt(index)
        }

        override fun insertItems(index: Int, inserted: List<MediaItem>) {
            insertCount++
            items.addAll(index.coerceIn(0, items.size), inserted)
        }

        override fun setItem(item: MediaItem) {
            items.clear()
            items.add(item)
            currentIndex = 0
        }

        override fun addItems(added: List<MediaItem>) {
            items.addAll(added)
        }

        override fun setVolume(v: Float) {
            currentVolume = v
        }

        override fun startFromBeginning() {
            startCount++
        }

        override fun pauseAndClear() {
            callLog?.add("pauseAndClear")
            clearCount++
            items.clear()
            currentIndex = 0
        }
    }

    private class FakeHost(
        override var settings: PureToneSettings = PureToneSettings(crossfadeMs = 6_000),
        override var queue: List<QueueEntry> = emptyList(),
        override var isCasting: Boolean = false,
        /** 模拟「播放器只持有剩余尾部、完整队列绝对索引需偏移」的场景（生产中恒为 0） */
        override var queueOffset: Int = 0,
        val callLog: MutableList<String>? = null
    ) : CrossfadeCoordinator.Host {

        val finalized = mutableListOf<Int>()

        override fun onFinalize(newPrimary: CrossfadePlayer, outgoing: CrossfadePlayer, nextIndex: Int) {
            callLog?.add("onFinalize")
            finalized += nextIndex
        }
    }

    private fun item(id: Long) = MediaItem.Builder().setMediaId(id.toString()).build()

    private fun entry(songId: Long, isApe: Boolean = false) = QueueEntry(
        songId = songId,
        title = "t$songId",
        artist = "a",
        coverUri = null,
        durationMs = 30_000L,
        isApe = isApe,
        filePath = "/sdcard/Music/$songId.mp3",
        key = songId
    )

    /** 主播放器播到第 30s 中的 25s，后面还有两首：命中「剩余 5s ≤ 6s+余量」的触发窗口 */
    private fun nearEndPlayer(callLog: MutableList<String>? = null): FakePlayer = FakePlayer(
        durationMs = 30_000L,
        positionMs = 25_000L,
        items = mutableListOf(item(1), item(2), item(3)),
        currentIndex = 0,
        callLog = callLog
    )

    /** 在 [runTest] 的 TestScope 上构造：渐变协程的 delay 走虚拟时间，可被 [advanceUntilIdle] 推进 */
    private fun TestScope.coordinator(
        host: FakeHost = FakeHost(),
        queue: List<QueueEntry> = listOf(entry(1), entry(2), entry(3))
    ): Pair<CrossfadeCoordinator, FakeHost> {
        host.queue = queue
        return CrossfadeCoordinator(scope = this, host = host) to host
    }

    // ---------- 触发守卫 ----------

    @Test
    fun `未注入备援播放器时不触发`() = runTest {
        val (coordinator, host) = coordinator()
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)

        assertFalse(coordinator.isCrossfading)
        assertEquals(0, primary.removeCount)
        assertTrue(host.finalized.isEmpty())
    }

    @Test
    fun `淡化为 0（关闭）时不触发`() = runTest {
        val (coordinator, host) = coordinator(host = FakeHost(settings = PureToneSettings(crossfadeMs = 0)))
        coordinator.attach(FakePlayer(), ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)

        assertFalse(coordinator.isCrossfading)
        assertEquals(0, primary.removeCount)
    }

    @Test
    fun `投屏进行中不触发`() = runTest {
        val (coordinator, _) = coordinator(host = FakeHost(isCasting = true))
        coordinator.attach(FakePlayer(), ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)

        assertFalse(coordinator.isCrossfading)
        assertEquals(0, primary.removeCount)
    }

    @Test
    fun `下一首需转码（APE）时不触发`() = runTest {
        val (coordinator, _) = coordinator(queue = listOf(entry(1), entry(2, isApe = true), entry(3)))
        coordinator.attach(FakePlayer(), ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)

        assertFalse(coordinator.isCrossfading)
        assertEquals(0, primary.removeCount)
    }

    @Test
    fun `远离曲尾时不触发`() = runTest {
        val (coordinator, _) = coordinator()
        coordinator.attach(FakePlayer(), ReplayGainAudioProcessor())
        val primary = FakePlayer(
            durationMs = 30_000L,
            positionMs = 5_000L,
            items = mutableListOf(item(1), item(2)),
            currentIndex = 0
        )

        coordinator.maybeStart(primary)

        assertFalse(coordinator.isCrossfading)
    }

    // ---------- 起播 ----------

    @Test
    fun `命中窗口则备援从 0 音量起播下一首，主播放器让出该曲`() = runTest {
        val (coordinator, _) = coordinator()
        val secondary = FakePlayer()
        coordinator.attach(secondary, ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)

        assertTrue(coordinator.isCrossfading)
        // 主播放器移除「下一首」，避免其自行续播导致重复计完成
        assertEquals(1, primary.removeCount)
        assertEquals(2, primary.itemCount)
        // 备援承载下一首，音量从 0 起步并立即起播
        assertEquals(1, secondary.startCount)
        assertEquals(0f, secondary.currentVolume)
        assertEquals(2L, secondary.currentItemId)
    }

    @Test
    fun `淡化期间重复触发无效`() = runTest {
        val (coordinator, _) = coordinator()
        val secondary = FakePlayer()
        coordinator.attach(secondary, ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)
        coordinator.maybeStart(primary)
        coordinator.maybeStart(primary)

        assertEquals(1, secondary.startCount)
        assertEquals(1, primary.removeCount)
    }

    // ---------- 收尾 ----------

    @Test
    fun `渐变结束后收尾：剩余曲目补全到新主，双方音量复位`() = runTest {
        val (coordinator, host) = coordinator()
        val secondary = FakePlayer()
        coordinator.attach(secondary, ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)
        advanceUntilIdle()

        assertFalse(coordinator.isCrossfading)
        assertEquals(1, host.finalized.size)
        assertEquals(1, host.finalized.first())
        // 剩余曲目必须补全到新主，否则队列会悄悄变短
        assertEquals(2, secondary.itemCount)
        // 淡入方到 1f，淡出方（即将成为备援）复位回 1f 供下次复用
        assertEquals(1f, secondary.currentVolume)
        assertEquals(1f, primary.currentVolume)
        // 旧主被暂停并清空
        assertEquals(1, primary.clearCount)
    }

    @Test
    fun `收尾幂等：渐变协程与 STATE_ENDED 兜底谁先到都只收尾一次`() = runTest {
        val (coordinator, host) = coordinator()
        val secondary = FakePlayer()
        coordinator.attach(secondary, ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)
        advanceUntilIdle()
        coordinator.finalize()
        coordinator.finalize()

        assertEquals(1, host.finalized.size)
        assertEquals(1, primary.clearCount)
    }

    @Test
    fun `未处于淡化时 finalize 直接返回`() = runTest {
        val (coordinator, host) = coordinator()
        coordinator.attach(FakePlayer(), ReplayGainAudioProcessor())

        coordinator.finalize()

        assertTrue(host.finalized.isEmpty())
    }

    // ---------- 取消 ----------

    @Test
    fun `取消后回到可复用状态：双方音量复位、备援清空`() = runTest {
        val (coordinator, host) = coordinator()
        val secondary = FakePlayer()
        coordinator.attach(secondary, ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)
        // 模拟渐变中途被用户切歌打断（渐变协程尚未跑完）
        primary.setVolume(0.4f)
        coordinator.cancel()

        assertFalse(coordinator.isCrossfading)
        assertTrue(host.finalized.isEmpty())
        // 音量必须复位：否则下一次淡化从 0.4f 起播，听感上是爆音
        assertEquals(1f, primary.currentVolume)
        assertEquals(1f, secondary.currentVolume)
        assertEquals(1, secondary.clearCount)
    }

    @Test
    fun `取消后可以再次触发淡化`() = runTest {
        val (coordinator, _) = coordinator()
        val secondary = FakePlayer()
        coordinator.attach(secondary, ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)
        coordinator.cancel()
        coordinator.maybeStart(primary)

        assertTrue(coordinator.isCrossfading)
        assertEquals(2, secondary.startCount)
    }

    @Test
    fun `收尾后宿主把原主重新注入为备援，第二次淡化仍能起播`() = runTest {
        val (coordinator, _) = coordinator()
        val gain = ReplayGainAudioProcessor()
        val secondary = FakePlayer()
        coordinator.attach(secondary, gain)
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)
        advanceUntilIdle()

        // 宿主的角色交换：原主（已暂停并清空）成为新的备援。
        // 少了这一步，第二次淡化会拿主播放器当备援，把它的队列清成单曲。
        coordinator.attach(primary, gain)
        assertEquals(primary, coordinator.currentSecondary)

        // 以新主（原备援）继续播到曲尾，应再次触发淡化
        secondary.durationMs = 30_000L
        secondary.positionMs = 25_000L
        secondary.currentIndex = 0
        coordinator.maybeStart(secondary)

        assertTrue(coordinator.isCrossfading)
        assertEquals(1, primary.startCount)
    }

    @Test
    fun `detach 后不再持有备援，也不再触发`() = runTest {
        val (coordinator, _) = coordinator()
        val secondary = FakePlayer()
        coordinator.attach(secondary, ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)
        coordinator.detach()
        coordinator.maybeStart(primary)

        assertFalse(coordinator.isCrossfading)
        // detach 前已取消：备援被清空一次，之后不再起播
        assertEquals(1, secondary.startCount)
    }

    // ---------- 收尾顺序（交叉淡化导致「一首播完就整体停止」的根因） ----------

    @Test
    fun `收尾必须先交权再复位旧主`() = runTest {
        val log = mutableListOf<String>()
        val host = FakeHost(callLog = log)
        val (coordinator, _) = coordinator(host = host)
        coordinator.attach(FakePlayer(), ReplayGainAudioProcessor())
        val primary = nearEndPlayer(callLog = log)

        coordinator.maybeStart(primary)
        advanceUntilIdle()

        // 顺倒了会怎样：旧主在仍是「宿主眼中的当前播放器」时被 pause+清空，
        // 以「队列空 + 不在播」落进空闲停服判定 → 整个服务被停掉（两个播放器一起释放）。
        assertEquals(listOf("onFinalize", "pauseAndClear"), log)
    }

    // ---------- 取消：把让出的下一首还回去 ----------

    @Test
    fun `取消淡化时把让出的下一首还给主播放器`() = runTest {
        val (coordinator, _) = coordinator()
        val secondary = FakePlayer()
        coordinator.attach(secondary, ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)
        // 淡化窗口内用户按了暂停/切歌
        assertEquals(2, primary.itemCount)
        coordinator.cancel()

        // 不还回去的话，用户中途打断一次就永久少一首歌（下一首凭空消失）
        assertEquals(3, primary.itemCount)
        assertEquals(2L, primary.items[1].mediaId.toLong())
        assertEquals(1, primary.insertCount)
    }

    @Test
    fun `收尾后不归还曲目，新主持有剩余队列`() = runTest {
        val (coordinator, _) = coordinator()
        val secondary = FakePlayer()
        coordinator.attach(secondary, ReplayGainAudioProcessor())
        val primary = nearEndPlayer()

        coordinator.maybeStart(primary)
        advanceUntilIdle()

        // 正常收尾时下一首已经由备援承载，不能再插回旧主（否则重复一首）
        assertEquals(0, primary.insertCount)
        assertEquals(2, secondary.itemCount)
    }

    // ---------- 队列索引对齐（交叉淡化后 UI 选中项与播放项错位的修复） ----------

    @Test
    fun `maybeStart 用 queueOffset 换算绝对下标，收尾回调收到完整队列绝对索引`() = runTest {
        // 模拟第二次淡化：播放器只持有剩余尾部 [s2,s3,s4]（player 空间 0,1,2），
        // 完整 UI 队列为 [s0,s1,s2,s3,s4]，queueOffset=2。当前曲 s2 在 player 空间是 0、
        // 绝对是 2；下一首绝对索引 = (0+1) + 2 = 3（s3）。若错误地用 player 空间 1 去完整
        // 队列取，会取到 s1——这正是淡化后 APE 门控取错歌、UI 选中项错位的来源。
        val queue = listOf(entry(0), entry(1), entry(2), entry(3), entry(4))
        val host = FakeHost(queueOffset = 2, queue = queue)
        val (coordinator, _) = coordinator(host = host, queue = queue)
        coordinator.attach(FakePlayer(), ReplayGainAudioProcessor())
        val primary = FakePlayer(
            durationMs = 30_000L,
            positionMs = 25_000L,
            items = mutableListOf(item(2), item(3), item(4)),
            currentIndex = 0
        )

        coordinator.maybeStart(primary)
        advanceUntilIdle()

        // onFinalize 收到的必须是绝对索引 3，而不是 player 空间索引 1
        assertEquals(listOf(3), host.finalized)
    }
}

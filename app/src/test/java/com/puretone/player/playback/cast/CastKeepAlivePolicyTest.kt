package com.puretone.player.playback.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 划掉任务卡时的服务保活判据（2026-11-05 21:35 真机回归）。
 *
 * 投屏期本地播放器恒暂停；旧判据只看本地 playWhenReady，会把正在电视上放歌的
 * 前台服务停掉，导致手机侧 HTTP 取流与 1Hz 轮询一起消失。
 */
class CastKeepAlivePolicyTest {

    private fun shouldStop(casting: Boolean, playing: Boolean, hasItems: Boolean) =
        CastKeepAlivePolicy.shouldStopServiceOnTaskRemoved(casting, playing, hasItems)

    @Test
    fun `投屏中本地暂停且有队列时不停服务`() {
        assertFalse(shouldStop(casting = true, playing = false, hasItems = true))
    }

    @Test
    fun `投屏中即使本地队列为空也不停服务`() {
        assertFalse(shouldStop(casting = true, playing = false, hasItems = false))
    }

    @Test
    fun `投屏中本地在播也不停服务`() {
        assertFalse(shouldStop(casting = true, playing = true, hasItems = true))
    }

    @Test
    fun `未投屏且本地在播有队列时不停服务`() {
        assertFalse(shouldStop(casting = false, playing = true, hasItems = true))
    }

    @Test
    fun `未投屏且本地暂停时按原语义停服务`() {
        assertTrue(shouldStop(casting = false, playing = false, hasItems = true))
    }

    @Test
    fun `未投屏且队列为空时停服务`() {
        assertTrue(shouldStop(casting = false, playing = true, hasItems = false))
    }

    /**
     * 2026-11-06 06:56 真机回归：设备发现 ownsNetwork=true，投送在 cast-ok 前就
     * 已被会话接管，随后搜索被取消执行 finally。这里必须**不释放**，否则整个
     * 投屏会话没有 WakeLock，息屏进 Doze 后轮询冻结 → 误判设备离线 → 回退本地。
     */
    @Test
    fun `会话已接管时借用方归还不释放保活锁`() {
        assertFalse(
            CastKeepAlivePolicy.shouldReleaseOnBorrowerReturn(
                owned = true,
                sessionActive = true
            )
        )
    }

    @Test
    fun `无会话时借用方归还释放保活锁`() {
        assertTrue(
            CastKeepAlivePolicy.shouldReleaseOnBorrowerReturn(
                owned = true,
                sessionActive = false
            )
        )
    }

    @Test
    fun `复用别人绑定的借用方归还是无操作`() {
        assertFalse(
            CastKeepAlivePolicy.shouldReleaseOnBorrowerReturn(
                owned = false,
                sessionActive = true
            )
        )
        assertFalse(
            CastKeepAlivePolicy.shouldReleaseOnBorrowerReturn(
                owned = false,
                sessionActive = false
            )
        )
    }

    @Test
    fun `真机时序_发现借锁_会话接管_搜索取消归还_锁仍在`() {
        // 1) 设备发现先借到所有权；此刻若搜索正常结束，应当释放
        val discoveryOwned = true
        assertTrue(
            CastKeepAlivePolicy.shouldReleaseOnBorrowerReturn(discoveryOwned, sessionActive = false)
        )
        // 2) 用户点设备投屏，castSong 在 cast-ok 之前就让会话接管
        val sessionActive = true
        // 3) 关弹窗使搜索协程取消，finally 照样执行归还 —— 这一行就是事故分水岭
        assertFalse(
            CastKeepAlivePolicy.shouldReleaseOnBorrowerReturn(discoveryOwned, sessionActive)
        )
        // 4) 停止投屏由会话自己释放；迟到的借用方归还仍是幂等无操作
        assertFalse(
            CastKeepAlivePolicy.shouldReleaseOnBorrowerReturn(
                owned = false,
                sessionActive = false
            )
        )
    }
}

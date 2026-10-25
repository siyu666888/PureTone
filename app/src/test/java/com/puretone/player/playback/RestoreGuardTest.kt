package com.puretone.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续播守卫的回归测试（Bugfix：点迷你条进「正在播放」页后播放被自动暂停）。
 *
 * 背景：每个导航目的地都会新建一份 PlayerViewModel（NavBackStackEntry 作用域），
 * 其 init 各调用一次 PlaybackManager.restoreLastSession()；而恢复是以
 * autoPlay=false 重建队列并 pause()，一旦在「播放器已有内容」时执行就会打断播放。
 * 守卫必须落在进程级单例里，故在此把判据固化下来。
 */
class RestoreGuardTest {

    @Test
    fun `播放器为空且未播放时允许恢复`() {
        assertTrue(canRestoreSession(playerItemCount = 0, isPlaying = false))
    }

    @Test
    fun `正在播放时禁止恢复`() {
        assertFalse(canRestoreSession(playerItemCount = 5, isPlaying = true))
    }

    @Test
    fun `暂停但队列仍在时禁止恢复`() {
        assertFalse(canRestoreSession(playerItemCount = 5, isPlaying = false))
    }

    @Test
    fun `isPlaying 为真但队列为空的异常态也禁止恢复`() {
        assertFalse(canRestoreSession(playerItemCount = 0, isPlaying = true))
    }
}

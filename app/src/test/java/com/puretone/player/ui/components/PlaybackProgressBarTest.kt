package com.puretone.player.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlaybackProgressBar] 纯逻辑单测（Robolectric，无 Compose 渲染宿主）。
 *
 * 覆盖 v1.8.9 进度条重设计的可单测契约：
 *  - [derivePlaybackState] 四态推导（含无时长不下判 Ended）；
 *  - [clampFraction] 进度分数越界钳制；
 *  - [resolvePlayedFraction] Ended 强制满格、其余回退已钳制值。
 *
 * 说明：hover/drag/setProgress 等需要渲染宿主的交互行为，在 JVM Robolectric 下
 * 因 [androidx.compose.ui.test.junit4.createComposeRule] 无法解析 Compose 测试宿主
 * Activity（robolectric/robolectric#4736）而不可跑；它们由 @Preview、HTML 预览与代码评审覆盖，
 * 真机/模拟器端到端验证归入交付文档，不在本单元门禁内。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackProgressBarTest {

    // ---- derivePlaybackState 四态推导 ----

    @Test
    fun `derivePlaybackState 准备中返回 Loading`() {
        assertEquals(
            PlaybackState.Loading,
            derivePlaybackState(isPlaying = false, isPreparing = true, positionMs = 0, durationMs = 1000)
        )
    }

    @Test
    fun `derivePlaybackState 正常播放返回 Playing`() {
        assertEquals(
            PlaybackState.Playing,
            derivePlaybackState(isPlaying = true, isPreparing = false, positionMs = 100, durationMs = 1000)
        )
    }

    @Test
    fun `derivePlaybackState 暂停且未到末尾返回 Paused`() {
        assertEquals(
            PlaybackState.Paused,
            derivePlaybackState(isPlaying = false, isPreparing = false, positionMs = 100, durationMs = 1000)
        )
    }

    @Test
    fun `derivePlaybackState 进度到达末尾返回 Ended`() {
        assertEquals(
            PlaybackState.Ended,
            derivePlaybackState(isPlaying = false, isPreparing = false, positionMs = 1000, durationMs = 1000)
        )
    }

    @Test
    fun `derivePlaybackState 无时长不下判 Ended`() {
        // durationMs<=0 时不应误判 Ended（无时长降级为静态轨道）
        assertEquals(
            PlaybackState.Paused,
            derivePlaybackState(isPlaying = false, isPreparing = false, positionMs = 50, durationMs = 0)
        )
    }

    // ---- clampFraction 钳制 ----

    @Test
    fun `clampFraction 超界返回 1`() {
        assertEquals(1f, clampFraction(1.5f), 0.0001f)
    }

    @Test
    fun `clampFraction 负界返回 0`() {
        assertEquals(0f, clampFraction(-0.5f), 0.0001f)
    }

    @Test
    fun `clampFraction 区间内原值返回`() {
        assertEquals(0.42f, clampFraction(0.42f), 0.0001f)
        assertEquals(0f, clampFraction(0f), 0.0001f)
        assertEquals(1f, clampFraction(1f), 0.0001f)
    }

    // ---- resolvePlayedFraction Ended 强制满格 ----

    @Test
    fun `resolvePlayedFraction Ended 忽略 display 返回 1`() {
        // 即便 display 未到满格，Ended 也强制满格（语义与视觉一致）
        assertEquals(1f, resolvePlayedFraction(PlaybackState.Ended, 0.5f), 0.0001f)
        assertEquals(1f, resolvePlayedFraction(PlaybackState.Ended, 0f), 0.0001f)
    }

    @Test
    fun `resolvePlayedFraction 非 Ended 回退已钳制 display`() {
        assertEquals(0.42f, resolvePlayedFraction(PlaybackState.Playing, 0.42f), 0.0001f)
        assertEquals(1f, resolvePlayedFraction(PlaybackState.Paused, 1.5f), 0.0001f)
        assertEquals(0f, resolvePlayedFraction(PlaybackState.Loading, -0.3f), 0.0001f)
    }
}

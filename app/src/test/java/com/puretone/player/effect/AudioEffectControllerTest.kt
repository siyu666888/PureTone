package com.puretone.player.effect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音效主开关语义的回归测试（P1-5）。
 *
 * 背景：旧实现 `applyBass` / `applyVirtualizer` 只看强度（`strength > 0`），
 * 不含主开关。用户关掉主开关（eqEnabled=false）但低音滑块停在 500 时，
 * DataStore 设置流回 `applySettings` → `ensureEffects()` → `applyBass()`
 * 把 BassBoost 重新置 enabled=true —— **主开关关了重低音仍在响**；
 * 而直接 `setEnabled(false)` 走 `disableAllEffects()` 又全关。
 * 同一语义两个入口行为相反，且设置页操作经 DataStore 流回是主路径。
 *
 * 现统一为「从属于主开关」：主开关关闭或强度为 0，一律不启用。
 * 这里把判据固化为纯函数单测，防止回归。
 */
class AudioEffectControllerTest {

    @Test
    fun `主开关开启且强度大于0时启用`() {
        assertTrue(AudioEffectController.shouldEnableEffect(masterEnabled = true, strength = 500))
    }

    @Test
    fun `主开关关闭时即使强度大于0也不启用`() {
        assertFalse(AudioEffectController.shouldEnableEffect(masterEnabled = false, strength = 500))
    }

    @Test
    fun `主开关开启但强度为0时不启用`() {
        assertFalse(AudioEffectController.shouldEnableEffect(masterEnabled = true, strength = 0))
    }

    @Test
    fun `主开关关闭且强度为0时不启用`() {
        assertFalse(AudioEffectController.shouldEnableEffect(masterEnabled = false, strength = 0))
    }
}

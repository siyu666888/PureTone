package com.puretone.player.effect

import com.puretone.player.data.settings.PureToneSettings
import org.junit.Assert.assertEquals
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

    @Test
    fun `内置预设均为10段`() {
        AudioEffectController.PRESETS.forEach { (name, bands) ->
            assertEquals("预设 $name 应为 10 段", 10, bands.size)
        }
    }

    @Test
    fun `applyPreset 把预设值放大100倍写入频段`() {
        val c = AudioEffectController()
        c.applyPreset("POP")
        assertEquals(
            AudioEffectController.PRESETS["POP"]!!.map { it * 100 },
            c.currentBandLevels()
        )
    }

    @Test
    fun `未知预设名 applyPreset 不改变频段`() {
        val c = AudioEffectController()
        val before = c.currentBandLevels()
        c.applyPreset("不存在")
        assertEquals(before, c.currentBandLevels())
    }

    @Test
    fun `P3-19 applySettings 对等长越界频段也钳制到量程`() {
        // 旧实现 normalizeBands 在长度相等时直接返回，绕过 setBandLevel 的 coerceIn 兜底，
        // DataStore 存了越界 mB 值时原样进入 bandLevels。
        val c = AudioEffectController()
        val over = List(10) { if (it % 2 == 0) 99999 else -99999 }
        c.applySettings(PureToneSettings(eqEnabled = false, eqBandLevels = over))
        c.currentBandLevels().forEachIndexed { i, v ->
            val expected = if (i % 2 == 0) AudioEffectController.MAX_LEVEL_MB else AudioEffectController.MIN_LEVEL_MB
            assertEquals("第 $i 段应被钳制", expected, v)
        }
    }
}

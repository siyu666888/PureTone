package com.puretone.player.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认海报档位判定的纯函数测试。
 *
 * 为什么测纯函数而不是 Compose UI：项目没有 Compose UI 测试基建（无 Robolectric
 * compose 扩展），而档位判定本身是最容易出错的逻辑（尺寸边界、唱片模式偏移），
 * 抽成纯函数后即可用普通 JUnit 覆盖。
 */
class DefaultCoverStyleTest {

    @Test
    fun `小于 64dp 不带黑胶纹`() {
        assertFalse(defaultCoverIsRich(44f))
        assertFalse(defaultCoverIsRich(48f))
        assertFalse(defaultCoverIsRich(63.9f))
    }

    @Test
    fun `大于等于 64dp 带黑胶纹`() {
        assertTrue(defaultCoverIsRich(64f))
        assertTrue(defaultCoverIsRich(72f))
        assertTrue(defaultCoverIsRich(200f))
    }

    @Test
    fun `小格符号占四成二边长`() {
        assertEquals(48f * 0.42f, defaultCoverIconSize(48f, rich = false), 0.01f)
        assertEquals(44f * 0.42f, defaultCoverIconSize(44f, rich = false), 0.01f)
    }

    @Test
    fun `大格带纹时符号占三成四边长`() {
        assertEquals(72f * 0.34f, defaultCoverIconSize(72f, rich = true), 0.01f)
        assertEquals(64f * 0.34f, defaultCoverIconSize(64f, rich = true), 0.01f)
    }
}

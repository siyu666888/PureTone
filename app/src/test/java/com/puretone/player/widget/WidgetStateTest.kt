package com.puretone.player.widget

import androidx.compose.ui.graphics.Color
import com.puretone.player.data.settings.PureToneSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桌面小部件的纯逻辑测试（不碰 Android 框架，纯 JVM）。
 *
 * 两处容易出错、又值得单测的东西：
 * 1. [WidgetStateResolver.pickSongId] 的取值优先级与越界兜底——进程被回收后
 *    桌面还在，回退到上次会话是唯一能显示内容的方式；
 * 2. [resolvePalette] 与 App 主题设置的一致性——小部件不该在 App 全黑时显示白板。
 */
class WidgetStateTest {

    // ---------- 曲目选择 ----------

    @Test
    fun `播放中优先展示当前正在播的歌`() {
        val id = WidgetStateResolver.pickSongId(
            currentSongId = 42L,
            sessionSongIds = listOf(7L, 8L),
            sessionIndex = 0
        )
        assertEquals(42L, id)
    }

    @Test
    fun `无播放状态时回退到会话当前索引`() {
        val id = WidgetStateResolver.pickSongId(
            currentSongId = null,
            sessionSongIds = listOf(7L, 8L, 9L),
            sessionIndex = 2
        )
        assertEquals(9L, id)
    }

    @Test
    fun `会话索引越界时退到末首而非崩溃`() {
        val ids = listOf(7L, 8L, 9L)
        assertEquals(9L, WidgetStateResolver.pickSongId(null, ids, 99))
        // 负索引（脏数据）也不能炸
        assertEquals(7L, WidgetStateResolver.pickSongId(null, ids, -5))
    }

    @Test
    fun `既无播放也无会话时返回空（显示未在播放占位）`() {
        assertNull(WidgetStateResolver.pickSongId(null, emptyList(), 0))
    }

    // ---------- 配色 ----------

    @Test
    fun `纯黑主题始终用纯黑背景`() {
        val palette = resolvePalette(
            PureToneSettings(themeMode = PureToneSettings.THEME_BLACK),
            systemNight = false
        )
        assertSame(WidgetPalette.Black, palette)
    }

    @Test
    fun `深色模式显式设置时压过系统设为浅色`() {
        assertSame(
            WidgetPalette.Dark,
            resolvePalette(PureToneSettings(themeMode = PureToneSettings.THEME_DARK), systemNight = false)
        )
        assertSame(
            WidgetPalette.Light,
            resolvePalette(PureToneSettings(themeMode = PureToneSettings.THEME_LIGHT), systemNight = true)
        )
    }

    @Test
    fun `跟随系统时按夜间模式切换`() {
        val light = resolvePalette(PureToneSettings(), systemNight = false)
        val dark = resolvePalette(PureToneSettings(), systemNight = true)
        assertSame(WidgetPalette.Light, light)
        assertSame(WidgetPalette.Dark, dark)
    }

    @Test
    fun `三种配色的前景色与背景色互不相同`() {
        listOf(WidgetPalette.Light, WidgetPalette.Dark, WidgetPalette.Black).forEach { p ->
            assertTrue(p.background != Color.Transparent)
            assertTrue(p.onBackground != p.background)
            assertTrue(p.muted != p.onBackground)
        }
    }

    @Test
    fun `未在播放占位文案非空`() {
        val state = WidgetState()
        assertNull(state.songId)
        assertFalse(state.isPlaying)
        assertTrue(state.title.isNotBlank())
        assertTrue(state.artist.isNotBlank())
    }
}

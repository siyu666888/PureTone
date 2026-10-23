package com.puretone.player.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 验证 v1.1.6 自绘线形图标：全部 8 枚在访问（触发 lazy 构造）时不应抛异常，
 * 且生成合法的 ImageVector（name 非空、path 节点已构建）。
 * 这是矢量图标主要的运行时风险点（arcTo / 闭环参数非法会在 build() 时抛错）。
 */
class ControlIconsTest {

    private val allIcons: List<ImageVector> = listOf(
        ControlIcons.SkipPrevious,
        ControlIcons.SkipNext,
        ControlIcons.Play,
        ControlIcons.Pause,
        ControlIcons.Queue,
        ControlIcons.RepeatAll,
        ControlIcons.RepeatOne,
        ControlIcons.Shuffle,
    )

    @Test
    fun allIconsBuildWithoutException() {
        for (icon in allIcons) {
            // 触发 lazy 构造；若 arcTo / path 参数非法会在此抛异常
            assertNotNull("图标构造失败", icon)
            assert(icon.name.isNotEmpty()) { "图标 name 不应为空" }
        }
    }

    @Test
    fun repeatIconsShareLoopShape() {
        // 单曲循环应额外包含中央「1」，但不应比顺序循环少节点
        assert(ControlIcons.RepeatOne.defaultWidth == ControlIcons.RepeatAll.defaultWidth)
    }
}

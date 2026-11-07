package com.puretone.player.ui.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 锁死「最近播放」环形箭头的几何契约：三角基准点必须落在环带圆线中心
 * （r=radius），而不是外缘（r=radius+0.9）。2026-11-07 方案 A 效果图确认后落地。
 */
class SmartPlaylistIconsTest {

    private val center = Offset(12f, 12f)

    private fun rad(deg: Float): Float = deg * PI.toFloat() / 180f

    private fun distanceToCenter(p: Offset, c: Offset = center): Double {
        val dx = (p.x - c.x).toDouble()
        val dy = (p.y - c.y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    @Test
    fun `三段箭头的基准点都落在圆线中心半径上`() {
        // drawLoopArrows() 的三段末端角度：250+70 / 10+70 / 130+70
        listOf(320f, 80f, 200f).forEach { deg ->
            val anchor = circularArrowAnchor(center, 8.5f, rad(deg))
            assertEquals(
                "末端 $deg° 的箭尾中心应落在 r=8.5",
                8.5,
                distanceToCenter(anchor),
                0.001
            )
        }
    }

    @Test
    fun `基准点与扇环末端外缘点同方向不引入偏转`() {
        val e = rad(80f)
        val anchor = circularArrowAnchor(center, 8.5f, e)
        val outer = Offset(center.x + 9.4f * cos(e), center.y + 9.4f * sin(e))
        // 圆心→锚点 与 圆心→外缘点 共线：二维叉积为 0
        val cross = (anchor.x - center.x) * (outer.y - center.y) -
            (anchor.y - center.y) * (outer.x - center.x)
        assertEquals(0.0, cross.toDouble(), 0.001)
    }

    @Test
    fun `环带半径变化时基准点仍贴合新的圆线中心`() {
        val e = rad(200f)
        listOf(7f, 8.5f, 10f).forEach { r ->
            assertEquals(
                "半径 $r 应落在同一圆上",
                r.toDouble(),
                distanceToCenter(circularArrowAnchor(center, r, e)),
                0.001
            )
        }
    }

    @Test
    fun `圆心平移后契约依然成立不依赖硬编码坐标`() {
        val shifted = Offset(3.5f, 20.25f)
        val anchor = circularArrowAnchor(shifted, 8.5f, rad(320f))
        assertEquals(8.5, distanceToCenter(anchor, shifted), 0.001)
    }
}

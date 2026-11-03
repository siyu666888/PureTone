package com.puretone.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.puretone.player.ui.theme.PureToneBrand

/**
 * 默认海报：无封面时显示的「设计过的封面」。
 *
 * ## 为什么需要它
 *
 * 此前 11 处封面回退全是「灰色裸图标」（`Icons.Rounded.MusicNote` 等），
 * 观感上有三个问题：
 *
 * 1. **没有底色** —— 与「有封面」的卡片视觉断裂；大尺寸处（专辑卡、歌单详情头）
 *    空白占比过高，看起来像加载失败；
 * 2. **图标随主题变灰** —— 缺品牌辨识，不像「设计如此」；
 * 3. **歌单场景必然依赖它** —— `GroupDetailScreen` 的歌单分支把 `coverUri` 写死为
 *    `null`（歌单本身无封面字段），所以歌单头永远只能显示默认海报。
 *
 * ## 视觉规格（2026-11-02 用户确认：方案 B「黑胶纹 + 音符」+ 固定品牌紫渐变）
 *
 * - 底色：固定品牌紫渐变 `#5E6AD2 → #3A449E`（135°），**不随主题变化**——
 *   默认海报是品牌资产，浅色 / 深色 / 纯黑三套主题下必须长得一样，
 *   否则同一首歌在不同主题下像换了一张封面；
 * - 前景：白色符号；
 * - **尺寸分级**：≥ [RICH_MIN_SIZE_DP] 时额外画两圈同心圆描边（黑胶纹），
 *   小于该值时**只保留渐变 + 符号**——48dp 下 1px 描边会糊成脏边。
 *
 * ## 性能约定
 *
 * 渐变用 `Brush.linearGradient`（不分配位图），圆环用 `Canvas.drawCircle(stroke)`，
 * **禁止** `remember` 缓存 Bitmap（会随尺寸变化失效且吃内存）。
 */
@Composable
fun DefaultCover(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    glyph: DefaultCoverGlyph = DefaultCoverGlyph.MUSIC_NOTE,
    /**
     * 是否为「黑胶唱片」盘面。
     *
     * 播放页的唱片会整体旋转（`discAngle`），因此盘面里**不能**放偏心元素——
     * 偏心元素会跟着转到任意角度，看着像渲染 bug。
     * 所以唱片模式只画「渐变 + 同心圆纹路」（都是旋转对称的），
     * 中心标签由 [VinylCenterLabel] 单独绘制在**不旋转**的图层上。
     */
    vinyl: Boolean = false,
) {
    BoxWithConstraints(
        modifier = modifier.background(
            brush = Brush.linearGradient(
                colors = listOf(PureToneBrand.Primary, PureToneBrand.PrimaryDark),
                start = Offset.Zero,
                end = Offset.Infinite,
            ),
            shape = shape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        // 用 min(宽, 高) 判定档位：方格与圆盘都按内切尺寸算，避免圆形被宽高比拉伸
        val minDp: Dp = minOf(maxWidth, maxHeight)
        val rich = defaultCoverIsRich(minDp.value)

        if (rich) {
            // 黑胶纹：两圈同心圆描边，比例对齐播放页唱片（0.72 / 0.50 直径占比）
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.minDimension
                drawCircle(
                    color = Color.White.copy(alpha = 0.20f),
                    radius = r * 0.36f,
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.13f),
                    radius = r * 0.25f,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        // 唱片模式不画符号：中心标签由 VinylCenterLabel 负责（避免偏心元素随盘旋转）
        if (!vinyl) {
            // 符号尺寸：小格占四成二、大格（带黑胶纹）占三成四
            val iconSize = defaultCoverIconSize(minDp.value, rich).dp
            Icon(
                imageVector = glyph.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * 唱片中心标签：深色圆盘 + 白色描边环 + 轴孔，可选印一个符号。
 *
 * ## 为什么要单独拆一个组件
 *
 * 播放页的唱片在**持续旋转**，所以标签必须画在**不跟随旋转**的图层上——
 * 真实唱片的中心贴纸虽然随盘转，但播放器里让标签固定、只有唱片转，
 * 观感更稳定（也不会出现「音符歪到一边像 bug」）。
 *
 * 顺带修掉一个历史问题：P2-4 的实现把居中的 `Text("♪")` 放在了中心孔**之下**，
 * 而中心孔直径 16% 盘面、`♪` 用 displayMedium 字号（约 45dp），
 * 两者叠加后音符实际被完全盖住——也就是说「播放页无封面时有音符」从来没真正生效过。
 *
 * @param glyph 印在标签上的符号；null 表示不印（真封面走这个分支）
 */
@Composable
fun VinylCenterLabel(
    modifier: Modifier = Modifier,
    glyph: ImageVector? = null,
) {
    BoxWithConstraints(
        modifier = modifier.clip(CircleShape).background(VinylLabelColor),
        contentAlignment = Alignment.Center,
    ) {
        // 全部按标签直径取比例，调用方只需给一个尺寸
        val d = minOf(maxWidth, maxHeight)
        if (glyph != null) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(d * VINYL_GLYPH_RATIO)
                    // 上移让开中心的轴孔（标签不旋转，偏心是安全的）
                    .offset(y = -(d * VINYL_HOLE_RATIO + d * VINYL_GLYPH_RATIO / 2f)),
            )
        }
        // 轴孔：用页面底色模拟「打通」
        Box(
            modifier = Modifier
                .size(d * VINYL_HOLE_RATIO * 2)
                .background(MaterialTheme.colorScheme.background, CircleShape)
        )
    }
}

/**
 * 是否为「带黑胶纹」档位。
 *
 * 48dp 及以下不画同心圆——1px 描边在小尺寸下会糊成脏边，反而更难看。
 */
fun defaultCoverIsRich(minSizeDp: Float): Boolean = minSizeDp >= RICH_MIN_SIZE_DP

/**
 * 前景符号边长（dp）。
 *
 * @param rich 是否带黑胶纹（由 [defaultCoverIsRich] 判定）
 */
fun defaultCoverIconSize(minSizeDp: Float, rich: Boolean): Float =
    if (rich) minSizeDp * 0.34f else minSizeDp * 0.42f

/**
 * 默认海报的前景符号。
 *
 * 歌单用专属符号（列表 + 播放箭头）而非纯音符，以便一眼区分
 * 「这是歌单」与「这是单曲」。
 */
enum class DefaultCoverGlyph(val icon: ImageVector) {
    /** 歌曲 / 专辑 / 通用 */
    MUSIC_NOTE(Icons.Rounded.MusicNote),

    /** 歌手 */
    PERSON(Icons.Rounded.Person),

    /** 歌单 */
    PLAYLIST(Icons.AutoMirrored.Rounded.PlaylistPlay),
}

/** 判定「带黑胶纹」档位的最小边长（dp）。小于此值只画渐变 + 符号。 */
private const val RICH_MIN_SIZE_DP = 64f

/** 唱片中心标签盘颜色（深色，模拟黑胶中心贴纸） */
private val VinylLabelColor = Color(0xFF161616)

/** 唱片中心标签上的符号边长占标签直径的比例 */
private const val VINYL_GLYPH_RATIO = 0.30f

/** 唱片中心轴孔直径占标签直径的比例（真实黑胶中心孔很小） */
private const val VINYL_HOLE_RATIO = 0.12f

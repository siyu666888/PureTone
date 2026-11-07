package com.puretone.player.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * 投屏遥控页专用轨道滑块：进度与音量共用，保证两条轨道视觉完全一致。
 *
 * 为什么不用 Material3 默认样式：M3 1.3 的 Slider 默认 thumb 是 4×20dp 竖条，
 * 且未播放轨道末端自带一个 stopIndicator 小圆点——看起来像光标 / 与手柄语义打架。
 * 这里彻底自定义 thumb 与 track：
 * - 手柄：18dp 圆形（主色内芯 + 2dp 白边 + 轻阴影），按压/拖动放大到 22dp；
 * - 轨道：5dp 高、两端全圆角，已播放主色、未播放主色 10% 透明度，无末端圆点；
 * - 禁用态统一降为 onSurface 的低透明度灰，不给「拖了没反应」的错误暗示。
 *
 * 颜色全部取自 [MaterialTheme.colorScheme]，浅色 / 深色 / 纯黑主题自动适配；
 * 深色下白边换成 surfaceVariant，避免纯白圈在深底上刺眼。
 */
private val TrackHeight = 5.dp
private val ThumbIdleSize = 18.dp
private val ThumbActiveSize = 22.dp
private val ThumbBorderWidth = 2.dp
private val SliderHitHeight = 48.dp

/**
 * 轨道可视端点相对外层 24dp 栅格的内缩量。
 *
 * M3 Slider 为了让 thumb 中心能停在 0 / 1 端点，会把 track 左右各内收约一个
 * thumb 半径；因此整宽 `fillMaxWidth()` 的轨道实际起止点并不贴父容器边界。
 * 进度条左右时间、音量图标与百分比统一按这个值向内缩进，文字/图标边缘才落在
 * 轨道端点上而不是贴在栅格线上（2026-11-06 用户确认的「数值与进度条对齐」）。
 * 只作用于标注行，轨道自身位置不变；真机需要微调时改这一处即可。
 */
internal val CastTrackInset = 12.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastTrackSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    semanticLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    val active = pressed || dragged
    val thumbSize by animateDpAsState(
        targetValue = if (active) ThumbActiveSize else ThumbIdleSize,
        label = "cast-thumb-size"
    )

    val scheme = MaterialTheme.colorScheme
    val surface = scheme.surface
    val isDark = (surface.red * 0.299f + surface.green * 0.587f + surface.blue * 0.114f) < 0.5f
    val ringColor = if (isDark) scheme.surfaceVariant else Color.White
    val activeColor = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.30f)
    val inactiveColor = if (enabled) scheme.primary.copy(alpha = 0.10f) else scheme.onSurface.copy(alpha = 0.08f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        valueRange = 0f..1f,
        interactionSource = interactionSource,
        thumb = {
            Box(
                Modifier
                    .size(thumbSize)
                    .shadow(if (active) 6.dp else 3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(activeColor)
                    .border(ThumbBorderWidth, ringColor, CircleShape)
            )
        },
        track = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TrackHeight)
                    .clip(CircleShape)
                    .background(inactiveColor)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(value.coerceIn(0f, 1f))
                        .height(TrackHeight)
                        .clip(CircleShape)
                        .background(activeColor)
                )
            }
        },
        modifier = modifier
            .height(SliderHitHeight)
            .semantics { stateDescription = semanticLabel }
    )
}

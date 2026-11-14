package com.puretone.player.ui.components

import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.PlayMode
import com.puretone.player.util.UnknownText

/** 「我喜欢」红心着色（歌曲列表收藏标识 / 收藏按钮激活色） */
val FavoriteRed = Color(0xFFE53935)

/** 歌曲列表行 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 多选模式：前置圆形勾选指示
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(
                        if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                        else Modifier.border(
                            1.5.dp,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            CircleShape
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null, // 装饰性，非交互
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        // 封面
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (song.coverUri != null) {
                AsyncImage(
                    model = song.coverUri,
                    contentDescription = null, // 装饰性，非交互
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                // 无封面：显示默认海报（品牌紫渐变 + 音符），不再用灰色裸图标
                DefaultCover(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // P2-9：展示层二次兜底，空/占位符 → 「未知××」
            // （历史数据或其它写入入口可能绕过入库归一）
            Text(
                "${UnknownText.displayArtist(song.artist, "未知艺术家")} · " +
                    UnknownText.displayAlbum(song.album, stringResource(R.string.pt_ui_001)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (song.favorite) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = stringResource(R.string.pt_ui_002),
                tint = FavoriteRed,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(16.dp)
            )
        }
        if (song.isApe) {
            Text(
                "APE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        trailing?.invoke()
    }
}

/**
 * 播放 / 暂停主按钮。
 *
 * 设计要点（对照「控制区优化」建议 2）：
 * - 显著大于其它控制图标，圆形渐变容器 + 阴影，确立核心操作地位；
 * - 按下时整钮缩放（弹性 spring），松手回弹，提供明确按压反馈；
 * - 播放 / 暂停图标切换走 AnimatedContent（淡入淡出 + 缩放），状态切换平滑。
 */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = ControlIconSizes.PlayPause
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "PlayPauseScale"
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (pressed) 4.dp else 10.dp,
                shape = CircleShape,
                clip = false
            )
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        lerpTowardWhite(scheme.primary, 0.16f),
                        scheme.primary
                    )
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.65f)) togetherWith
                    (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.65f))
            },
            label = "PlayPauseIcon"
        ) { playing ->
            Icon(
                imageVector = if (playing) ControlIcons.Pause else ControlIcons.Play,
                contentDescription = if (playing) stringResource(R.string.pt_ui_003) else stringResource(R.string.pt_ui_004),
                tint = scheme.onPrimary,
                modifier = Modifier.size(size * 0.4f)
            )
        }
    }
}

/**
 * 次级控制按钮（上一首 / 下一首 / 随机 / 收藏 / 歌词…）统一风格：
 * - 圆润 Rounded 图标，按压缩放反馈；
 * - 激活态：主色着色 + 淡主色底圆 + 底部小圆点指示（见建议 1、3）；
 * - 图标切换带淡入淡出动画。
 */
@Composable
fun ControlIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    buttonSize: Dp = ControlIconSizes.Button,
    iconSize: Dp = ControlIconSizes.IconStandard,
    showActiveDot: Boolean = false,
    activeTint: Color = MaterialTheme.colorScheme.primary,
    inactiveTint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ControlScale"
    )
    val tint by animateColorAsState(
        targetValue = if (active) activeTint else inactiveTint,
        animationSpec = tween(220),
        label = "ControlTint"
    )
    val background by animateColorAsState(
        targetValue = if (active) activeTint.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(220),
        label = "ControlBg"
    )
    Box(
        modifier = modifier
            .size(buttonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick
            )
            // 无障碍：自定义 Box+clickable 不会自动带 Button role，TalkBack 无法识别为按钮。
            // 显式声明 role，使其与 IconButton 等价的语义（可被「双击激活」读出）。
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.7f)) togetherWith
                    (fadeOut(tween(120)))
            },
            label = "ControlIcon"
        ) { vector ->
            Icon(
                imageVector = vector,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize)
            )
        }
        if (showActiveDot && active) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(activeTint)
            )
        }
    }
}

/**
 * 播放顺序按钮（三态：顺序播放 / 随机播放 / 单曲循环），无「关闭」态。
 *
 * 中性表现：不套用主色高亮 / 淡色底 / 选中圆点，与其它控制按钮同一色阶，
 * 当前顺序完全由图标形状区分（长方形循环 / 交叉曲线 / 长方形循环 + 1）。
 */
@Composable
fun PlayModeIconButton(
    mode: PlayMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = ControlIconSizes.Button,
    iconSize: Dp = ControlIconSizes.IconCompact
) {
    val (icon, desc) = when (mode) {
        PlayMode.SEQUENTIAL -> ControlIcons.RepeatAll to stringResource(R.string.pt_ui_005)
        PlayMode.SHUFFLE -> ControlIcons.Shuffle to stringResource(R.string.pt_ui_006)
        PlayMode.REPEAT_ONE -> ControlIcons.RepeatOne to stringResource(R.string.pt_ui_007)
    }
    ControlIconButton(
        icon = icon,
        contentDescription = desc,
        onClick = onClick,
        modifier = modifier,
        active = false,
        buttonSize = buttonSize,
        iconSize = iconSize
    )
}

/** 收藏按钮：心形，点亮态红心 + 填充图标，切换有弹跳过渡 */
@Composable
fun FavoriteButton(
    favorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = ControlIconSizes.Button,
    iconSize: Dp = ControlIconSizes.IconCompact
) {
    ControlIconButton(
        icon = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        contentDescription = if (favorite) stringResource(R.string.pt_ui_008) else stringResource(R.string.pt_ui_009),
        onClick = onClick,
        modifier = modifier,
        active = favorite,
        activeTint = FavoriteRed,
        buttonSize = buttonSize,
        iconSize = iconSize
    )
}

/** 主色向白色混合 [fraction]，得到柔和的微渐变亮阶（深浅主题均适用） */
private fun lerpTowardWhite(color: Color, fraction: Float): Color {
    return Color(
        red = color.red + (1f - color.red) * fraction,
        green = color.green + (1f - color.green) * fraction,
        blue = color.blue + (1f - color.blue) * fraction,
        alpha = color.alpha
    )
}

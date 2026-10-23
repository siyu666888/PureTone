package com.puretone.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.PlaybackUiState
import com.puretone.player.playback.PlayMode
import kotlin.math.roundToInt

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
                        contentDescription = null,
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
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
            Text(
                "${song.artist} · ${song.album}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (song.favorite) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = "已收藏",
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
    size: Dp = 84.dp
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
                contentDescription = if (playing) "暂停" else "播放",
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
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 28.dp,
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
            ),
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
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 26.dp
) {
    val (icon, desc) = when (mode) {
        PlayMode.SEQUENTIAL -> ControlIcons.RepeatAll to "播放模式：顺序播放"
        PlayMode.SHUFFLE -> ControlIcons.Shuffle to "播放模式：随机播放"
        PlayMode.REPEAT_ONE -> ControlIcons.RepeatOne to "播放模式：单曲循环"
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
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 26.dp
) {
    ControlIconButton(
        icon = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        contentDescription = if (favorite) "取消收藏" else "收藏",
        onClick = onClick,
        modifier = modifier,
        active = favorite,
        activeTint = FavoriteRed,
        buttonSize = buttonSize,
        iconSize = iconSize
    )
}

/**
 * 播放进度条（自定义，替代 M3 Slider —— 建议 4）：
 * - 轨道加高到 6dp，滑块 18dp 带白芯与阴影，整条 24dp 作触控热区，单手好拖；
 * - 拖动时滑块上方弹出时间气泡，实时预览当前拖动位置对应的播放时间；
 * - 单击轨道任意处直接定位（tap-to-seek），拖动松手后统一 seek。
 */
@Composable
fun PlayerProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val safeDuration = durationMs.coerceAtLeast(1L)
    val fraction = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val display = dragFraction ?: fraction
    val dragging = dragFraction != null

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        val maxWidthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val bubbleWidthPx = with(density) { 56.dp.toPx() }
        val thumbPx = with(density) { 18.dp.toPx() }

        // 时间气泡（常驻占位，仅拖动时可见，避免出现/消失引起布局跳动）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    IntOffset(
                        ((display * maxWidthPx) - bubbleWidthPx / 2f)
                            .roundToInt()
                            .coerceIn(0, (maxWidthPx - bubbleWidthPx).roundToInt().coerceAtLeast(0)),
                        0
                    )
                }
                .width(56.dp)
                .height(28.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = dragging,
                enter = fadeIn(tween(90)) + scaleIn(tween(140), initialScale = 0.75f),
                exit = fadeOut(tween(90))
            ) {
                Surface(
                    color = scheme.inverseSurface,
                    contentColor = scheme.inverseOnSurface,
                    shape = RoundedCornerShape(8.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = formatTime((display * safeDuration).toLong()),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // 轨道 + 滑块 + 手势热区
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(safeDuration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // 手指抬起：tap 与 drag 松手都定位到最终位置
                                val f = (change.position.x / size.width.toFloat())
                                    .coerceIn(0f, 1f)
                                onSeek((f * safeDuration).toLong())
                                dragFraction = null
                                break
                            }
                            val dx = change.position.x - change.previousPosition.x
                            if (dx != 0f) {
                                change.consume()
                                dragFraction = (change.position.x / size.width.toFloat())
                                    .coerceIn(0f, 1f)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // 轨道（底 + 已播）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(display)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                )
            }
            // 滑块
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        IntOffset(
                            ((display * maxWidthPx) - thumbPx / 2f)
                                .roundToInt()
                                .coerceIn(0, (maxWidthPx - thumbPx).roundToInt().coerceAtLeast(0)),
                            0
                        )
                    }
                    .size(18.dp)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

/**
 * 迷你播放条（显示在底部导航之上）。
 *
 * 控制按钮仅保留三枚：上一首 / 播放-暂停 / 下一首（对称居中排布）。
 * 播放顺序切换与「喜欢」仅在完整播放页提供，避免迷你条信息过载。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entry = state.currentEntry ?: return
    val scheme = MaterialTheme.colorScheme
    // 长按切换「剩余时长」展示
    var showRemaining by remember { mutableStateOf(false) }

    val duration = if (state.durationMs > 0) state.durationMs else entry.durationMs
    val remaining = (duration - state.positionMs).coerceAtLeast(0L)
    val progress = if (duration > 0) {
        (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = scheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = { showRemaining = !showRemaining }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (entry.coverUri != null) {
                        AsyncImage(
                            model = entry.coverUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 副行：默认艺术家；长按后切换为剩余 / 总时长，平滑过渡
                    AnimatedContent(
                        targetState = showRemaining,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                        },
                        label = "MiniSubtitle"
                    ) { remainingMode ->
                        if (remainingMode) {
                            Text(
                                "剩余 -${formatTime(remaining)} / ${formatTime(duration)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.primary,
                                maxLines = 1
                            )
                        } else {
                            Text(
                                entry.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 仅三枚控制按钮，关于中部播放/暂停对称：上一首（左）/ 播放暂停（中）/ 下一首（右）
                ControlIconButton(
                    icon = ControlIcons.SkipPrevious,
                    contentDescription = "上一首",
                    onClick = onPrev,
                    buttonSize = 42.dp,
                    iconSize = 24.dp
                )
                ControlIconButton(
                    icon = if (state.isPlaying) ControlIcons.Pause else ControlIcons.Play,
                    contentDescription = "播放/暂停",
                    onClick = onToggle,
                    buttonSize = 42.dp,
                    iconSize = 24.dp
                )
                ControlIconButton(
                    icon = ControlIcons.SkipNext,
                    contentDescription = "下一首",
                    onClick = onNext,
                    buttonSize = 42.dp,
                    iconSize = 24.dp
                )
            }

            // 底部细进度线：与主题色一致，直观感知播放进度
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(scheme.surfaceContainerHighest)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(scheme.primary)
                )
            }
        }
    }
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

/** mm:ss 格式化 */
internal fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

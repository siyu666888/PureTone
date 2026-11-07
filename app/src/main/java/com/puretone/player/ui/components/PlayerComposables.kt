package com.puretone.player.ui.components
import androidx.compose.ui.res.stringResource
import com.puretone.player.R

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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventType
import android.animation.ValueAnimator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.PlaybackUiState
import com.puretone.player.playback.PlayMode
import com.puretone.player.util.UnknownText
import kotlinx.coroutines.flow.StateFlow
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
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 26.dp
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
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 26.dp
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

/**
 * 播放进度条状态（本地播放无缓冲层，仅两层进度 + 播放生命周期态）。
 */
sealed interface PlaybackState {
    data object Loading : PlaybackState   // 准备 / 解码中
    data object Playing : PlaybackState   // 正常推进
    data object Paused : PlaybackState    // 暂停（进度静止）
    data object Ended : PlaybackState     // 本曲结束（进度满，等待下一首 / 重播）
}

/** 用户与进度条的交互态，驱动默认 / 悬停 / 拖拽三套视觉反馈。 */
enum class InteractionState { DEFAULT, HOVER, DRAGGING }

/** 由离散播放状态推导进度条应呈现的 [PlaybackState]。 */
fun derivePlaybackState(
    isPlaying: Boolean,
    isPreparing: Boolean,
    positionMs: Long,
    durationMs: Long
): PlaybackState = when {
    isPreparing -> PlaybackState.Loading
    isPlaying -> PlaybackState.Playing
    durationMs > 0 && positionMs >= durationMs -> PlaybackState.Ended
    else -> PlaybackState.Paused
}

/** 进度分数钳制到 [0,1]（UI 层base 计算，单测可覆盖）。 */
fun clampFraction(fraction: Float): Float = fraction.coerceIn(0f, 1f)

/** 已播放比例：Ended 态强制满格 1.0，其余回退到已钳制的 display。 */
fun resolvePlayedFraction(playbackState: PlaybackState, display: Float): Float =
    if (playbackState == PlaybackState.Ended) 1f else clampFraction(display)

/**
 * 播放进度条（自定义，替代 M3 Slider）：
 * - 两层进度（已播 primary / 未播 surfaceContainerHighest），本地播放无缓冲层；
 * - 点击 / 拖拽跳转（tap-to-seek 与 drag-seek 共用提交路径）；
 * - 悬停（鼠标 / 触控笔）与拖拽时间预览气泡；
 * - 适配 Loading / Playing / Paused / Ended 四态与 default / hover / dragging 三态视觉；
 * - 恒定 48dp 触控热区 + TalkBack 进度读出与 setProgress 调节（无障碍）。
 */
@Composable
fun PlaybackProgressBar(
    modifier: Modifier = Modifier,
    playedFraction: Float,
    durationMs: Long,
    playbackState: PlaybackState,
    onSeek: (Long) -> Unit,
    onSeekPreview: ((Long) -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val safeDuration = durationMs.coerceAtLeast(1L)
    val base = clampFraction(playedFraction)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    val interaction = if (dragFraction != null) InteractionState.DRAGGING
                      else if (hoverFraction != null) InteractionState.HOVER
                      else InteractionState.DEFAULT
    val display = dragFraction ?: base
    val hasDuration = durationMs > 0
    val played = resolvePlayedFraction(playbackState, display)

    val contentDesc = stringResource(R.string.pt_progress_000)
    val stateDesc = stringResource(
        when (playbackState) {
            PlaybackState.Loading -> R.string.pt_progress_001
            PlaybackState.Playing -> R.string.pt_progress_002
            PlaybackState.Paused -> R.string.pt_progress_003
            PlaybackState.Ended -> R.string.pt_progress_004
        }
    )

    // 动效守卫：系统开启「减少动效」时跳过脉动/呼吸，仅保留静态态
    val animEnabled = ValueAnimator.areAnimatorsEnabled()
    var pulseTick by remember { mutableStateOf(false) }
    LaunchedEffect(animEnabled) {
        if (!animEnabled) return@LaunchedEffect
        while (true) {
            delay(900)
            pulseTick = !pulseTick
        }
    }
    var playTick by remember { mutableStateOf(false) }
    LaunchedEffect(animEnabled) {
        if (!animEnabled) return@LaunchedEffect
        while (true) {
            delay(5000)
            playTick = !playTick
        }
    }
    val pulseAlpha by animateFloatAsState(
        targetValue = if (pulseTick) 0.35f else 0.15f,
        animationSpec = tween(450)
    )
    val playingPulse by animateFloatAsState(
        targetValue = if (playTick) 1.05f else 1f,
        animationSpec = tween(150)
    )
    val playedAlpha = when {
        !animEnabled -> if (playbackState == PlaybackState.Loading) 0.55f else 1f
        playbackState == PlaybackState.Loading -> pulseAlpha
        else -> 1f
    }
    val thumbScale = if (animEnabled
        && playbackState == PlaybackState.Playing
        && interaction == InteractionState.DEFAULT
    ) playingPulse else 1f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // 触控目标：整体至少 48dp（Google 无障碍规范最小触控尺寸）
            .height(48.dp)
            // 无障碍：暴露进度给 TalkBack（读出百分比）+ 状态描述 + 进度调节动作
            .semantics {
                contentDescription = contentDesc
                progressBarRangeInfo = ProgressBarRangeInfo(display, 0f..1f)
                stateDescription = stateDesc
                setProgress {
                    onSeek((it * safeDuration).toLong())
                    true
                }
            }
    ) {
        val maxWidthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val maxWidthDp = (constraints.maxWidth / density.density).dp

        // 响应式断点：窄（手表/折叠外屏）/ 常规（手机）/ 宽（平板/桌面）
        val (trackBase, thumbBase, bubbleW) = when {
            maxWidthDp <= 280.dp -> Triple(4.dp, 16.dp, 48.dp)
            maxWidthDp >= 600.dp -> Triple(8.dp, 22.dp, 64.dp)
            else -> Triple(6.dp, 18.dp, 56.dp)
        }
        val trackH by animateDpAsState(
            targetValue = trackBase + when (interaction) {
                InteractionState.DRAGGING -> 4.dp
                InteractionState.HOVER -> 2.dp
                else -> 0.dp
            },
            animationSpec = tween(120)
        )
        val thumbS by animateDpAsState(
            targetValue = thumbBase + when (interaction) {
                InteractionState.DRAGGING -> 6.dp
                InteractionState.HOVER -> 2.dp
                else -> 0.dp
            },
            animationSpec = tween(120)
        )
        val bubbleWidthPx = with(density) { bubbleW.toPx() }
        val thumbSizePx = with(density) { thumbS.toPx() }
        val haloSizePx = with(density) { (thumbS + 14.dp).toPx() }

        // 无时长：降级为灰显静态轨道，禁用 seek，但保留 48dp 热区避免布局抖动
        if (!hasDuration) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.fillMaxWidth().height(trackBase)
                        .clip(CircleShape)
                        .background(scheme.surfaceContainerHighest.copy(alpha = 0.5f))
                )
            }
            return@BoxWithConstraints
        }

        // 时间气泡（hover 或 dragging 可见，常驻占位避免布局跳动）
        val bubbleFrac = dragFraction ?: hoverFraction ?: 0f
        val showBubble = interaction != InteractionState.DEFAULT
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    IntOffset(
                        (bubbleFrac * maxWidthPx - bubbleWidthPx / 2f).roundToInt()
                            .coerceIn(0, (maxWidthPx - bubbleWidthPx).roundToInt().coerceAtLeast(0)),
                        0
                    )
                }
                .width(bubbleW).height(28.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = showBubble,
                enter = fadeIn(tween(90)) + scaleIn(tween(140), initialScale = 0.75f),
                exit = fadeOut(tween(90))
            ) {
                Surface(
                    color = scheme.primary,
                    contentColor = scheme.onPrimary,
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = formatTime((bubbleFrac * safeDuration).toLong()),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        // 轨道 + 滑块 + 手势热区（底部 48dp 覆盖整条组件）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(48.dp)
                // 悬停（鼠标/触控笔）预览：不提交、不移动进度；触摸设备不触发 Enter/Exit。
                // Compose 1.7.x 无 Modifier.onPointerEvent，改用 awaitPointerEventScope 观察事件，
                // 且绝不 consume，避免与下方拖拽手势争用。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    if (dragFraction == null) {
                                        val x = event.changes.firstOrNull()?.position?.x ?: continue
                                        hoverFraction = (x / size.width).coerceIn(0f, 1f)
                                    }
                                }
                                PointerEventType.Exit -> hoverFraction = null
                                else -> Unit
                            }
                        }
                    }
                }
                // 点击/拖拽跳转
                .pointerInput(safeDuration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // 手指/指针抬起：tap 与 drag 松手都定位到最终位置
                                val f = (change.position.x / size.width).coerceIn(0f, 1f)
                                onSeek((f * safeDuration).toLong())
                                dragFraction = null
                                break
                            }
                            val dx = change.position.x - change.previousPosition.x
                            if (dx != 0f) {
                                change.consume()
                                dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                onSeekPreview?.invoke((dragFraction!! * safeDuration).toLong())
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
            ) {
                // 轨道（未播底 + 已播）：在 24dp 热区条内垂直居中，使滑块中心与轨道对齐
                Box(
                    Modifier.align(Alignment.CenterStart).fillMaxWidth().height(trackH)
                        .clip(CircleShape)
                        .background(scheme.surfaceContainerHighest)
                ) {
                    Box(
                        Modifier.fillMaxWidth(played).height(trackH)
                            .clip(CircleShape)
                            .background(scheme.primary.copy(alpha = playedAlpha))
                    )
                }
                // 滑块光晕（hover/drag 时 primary 外扩，强化品牌套组反馈；默认态透明不占布局）
                val haloAlpha = when (interaction) {
                    InteractionState.DRAGGING -> 0.20f
                    InteractionState.HOVER -> 0.12f
                    else -> 0f
                }
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .offset {
                            IntOffset(
                                (played * maxWidthPx - haloSizePx / 2f).roundToInt()
                                    .coerceIn(0, (maxWidthPx - haloSizePx).roundToInt().coerceAtLeast(0)),
                                0
                            )
                        }
                        .size(thumbS + 14.dp)
                        .clip(CircleShape)
                        .background(scheme.primary.copy(alpha = haloAlpha))
                )
                // 滑块（品牌实心：primary 填充 + onPrimary 阴影；拖拽时阴影加深；结束态改 surface 底 + 重播字形）
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .offset {
                            IntOffset(
                                (played * maxWidthPx - thumbSizePx / 2f).roundToInt()
                                    .coerceIn(0, (maxWidthPx - thumbSizePx).roundToInt().coerceAtLeast(0)),
                                0
                            )
                        }
                        .size(thumbS)
                        .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                        .shadow(if (interaction == InteractionState.DRAGGING) 6.dp else 3.dp, CircleShape)
                        .clip(CircleShape)
                        .background(if (playbackState == PlaybackState.Ended) scheme.surface else scheme.primary)
                ) {
                    if (playbackState == PlaybackState.Ended) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawReplay(scheme.outline, size.minDimension * 0.14f)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 矢量重播字形（圆形箭头），用于进度条「已结束」态的滑块中心，避免依赖字体字符。
 */
private fun DrawScope.drawReplay(tint: Color, strokeW: Float) {
    val radius = (size.minDimension - strokeW) / 2f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val topLeft = Offset(cx - radius, cy - radius)
    val arcSize = Size(radius * 2f, radius * 2f)
    val startAngle = 50f
    val sweep = 280f
    val rad = (PI / 180.0).toFloat()
    drawArc(
        color = tint,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeW, cap = StrokeCap.Round)
    )
    // 箭头在弧末端，沿顺时针切线方向（暗示循环 / 重播）
    val endAngle = startAngle + sweep
    val ang = endAngle * rad
    val px = cx + radius * cos(ang)
    val py = cy + radius * sin(ang)
    val tang = (endAngle + 90f) * rad
    val tx = cos(tang)
    val ty = sin(tang)
    val arrowLen = strokeW * 2.4f
    val tip = Offset(px + tx * arrowLen, py + ty * arrowLen)
    val perpX = -ty
    val perpY = tx
    val bw = strokeW * 1.2f
    val b1 = Offset(px + perpX * bw, py + perpY * bw)
    val b2 = Offset(px - perpX * bw, py - perpY * bw)
    drawPath(
        path = Path().apply { moveTo(tip.x, tip.y); lineTo(b1.x, b1.y); lineTo(b2.x, b2.y); close() },
        color = tint
    )
}

/**
 * 迷你播放条（显示在底部导航之上）。
 *
 * 控制按钮仅保留三枚：上一首 / 播放-暂停 / 下一首（对称居中排布）。
 * 播放顺序切换与「喜欢」仅在完整播放页提供，避免迷你条信息过载。
 *
 * P1-10：入参为 [StateFlow] 而非状态值——position 每 500ms tick 一次，
 * 收集下沉到本组件内部，使 2Hz 重组只发生在迷你条子树，不波及根部导航树。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    stateFlow: StateFlow<PlaybackUiState>,
    /** 与播放页/遥控页/通知栏同源的展示进度，投屏期为电视镜像 + 250ms 外推 */
    positionFlow: StateFlow<Long>,
    /** 展示总时长的唯一出口，避免迷你条自己写一份 `uiState.durationMs ?: entry.durationMs` */
    durationFlow: StateFlow<Long>,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    // P0-8：Room 实时当前曲的展示字段（编辑标题/封面、联网匹配后立即刷新）。
    // 为 null（尚未查到实体）时回退到队列快照 entry，行为与旧版一致。
    liveTitle: String? = null,
    liveArtist: String? = null,
    liveCoverUri: android.net.Uri? = null
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    val position by positionFlow.collectAsStateWithLifecycle()
    val duration by durationFlow.collectAsStateWithLifecycle()
    val entry = state.currentEntry ?: return
    val title = liveTitle ?: entry.title
    val artist = liveArtist ?: entry.artist
    val coverUri = liveCoverUri ?: entry.coverUri
    val scheme = MaterialTheme.colorScheme
    // 长按切换「剩余时长」展示
    var showRemaining by remember { mutableStateOf(false) }

    val remaining = (duration - position).coerceAtLeast(0L)
    val progress = if (duration > 0) {
        (position.toFloat() / duration).coerceIn(0f, 1f)
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
                    // 无障碍：长按切换「剩余/总时长」是隐藏交互，给可见的 longClick 标签，
                    // 让 TalkBack 用户能在「长按」动作中读到用途，而非无提示的隐藏手势。
                    onLongClickLabel = stringResource(R.string.pt_ui_010),
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
                    if (coverUri != null) {
                        AsyncImage(
                            model = coverUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // 无封面：显示默认海报
                        DefaultCover(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
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
                                stringResource(R.string.pt_ui_011, formatTime(remaining), formatTime(duration)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.primary,
                                maxLines = 1
                            )
                        } else {
                            Text(
                                artist,
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
                    contentDescription = stringResource(R.string.pt_ui_012),
                    onClick = onPrev,
                    buttonSize = 48.dp,
                    iconSize = 24.dp
                )
                ControlIconButton(
                    icon = if (state.isPlaying) ControlIcons.Pause else ControlIcons.Play,
                    contentDescription = stringResource(R.string.pt_ui_013),
                    onClick = onToggle,
                    buttonSize = 48.dp,
                    iconSize = 24.dp
                )
                ControlIconButton(
                    icon = ControlIcons.SkipNext,
                    contentDescription = stringResource(R.string.pt_ui_014),
                    onClick = onNext,
                    buttonSize = 48.dp,
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

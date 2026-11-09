package com.puretone.player.ui.components

import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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

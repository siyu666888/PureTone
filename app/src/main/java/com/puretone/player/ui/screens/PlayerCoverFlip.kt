package com.puretone.player.ui.screens

import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import android.animation.ValueAnimator
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import com.puretone.player.lyrics.ParsedLyrics
import com.puretone.player.playback.QueueEntry
import com.puretone.player.ui.components.ControlIconButton
import com.puretone.player.ui.components.DefaultCover
import com.puretone.player.ui.components.VinylCenterLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 可滑动 + 可翻转封面：
 * - 上滑/下滑切歌：拖动带阻尼（tanh），当前封面随拖动缩小淡出，过阈值切歌；
 *   切歌时新封面从对应方向滑入并放大（AnimatedContent 视差过渡，与拖动方向一致），
 *   松手后容器位移以同时长补间归位，避免瞬移跳变，GPU 硬件加速；
 * - 单击（延迟 300ms 与双击区分）→ 3D 翻转显示背面歌词，再点翻回；
 * - 双击 → 播放 / 暂停；长按 → 唤起歌曲信息弹窗。
 */
@Composable
internal fun SwipeableCover(
    entry: QueueEntry,
    isPlaying: Boolean,
    onSwipePrev: () -> Unit,
    onSwipeNext: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    backContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    // P0-8：Room 实时封面（编辑/匹配后刷新）；null 时用 entry 快照里的封面
    coverOverride: Uri? = null,
    // P2-4：翻转态提升到调用方——播放页需要「歌词」可见按钮与封面翻转共享同一状态
    flipped: Boolean = false,
    onFlippedChange: (Boolean) -> Unit = {}
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val springBack = SpringSpec<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)
    val offsetAnim = remember { Animatable(0f) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    // 切歌方向：>0 下一首（向上滑），<0 上一首（向下滑）；决定 AnimatedContent 过渡方向
    var swipeDir by remember { mutableIntStateOf(1) }

    // CD 旋转角：播放时匀速持续旋转，暂停即停（保留当前角度），切歌不重置。
    // 该 Animatable 位于 SwipeableCover 作用域，跨 entry 变化保持同一实例。
    val discAngle = remember { Animatable(0f) }
    // P1-10：页面退后台（ON_PAUSE）时停转，避免不可见状态下持续产出 frame 耗电；
    // 导航离开本页时组合已被销毁，效果天然取消。
    var foreground by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        foreground = true
        onPauseOrDispose { foreground = false }
    }
    LaunchedEffect(isPlaying, flipped, foreground) {
        // 尊重系统「移除动画」设置（开发者选项「动画程序时长缩放」关闭 /
        // 省电模式可全局关闭动画）：关闭时停转，仅展示静态封面。
        // Compose 动画不受该开关影响，故此处显式判断（切换设置后下次
        // 播放态变化/翻面/前后台切换时生效）。
        if (isPlaying && !flipped && foreground && ValueAnimator.areAnimatorsEnabled()) {
            while (true) {
                discAngle.animateTo(
                    targetValue = discAngle.value + 360f,
                    animationSpec = tween(durationMillis = 14000, easing = LinearEasing)
                )
            }
        }
    }

    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(550),
        label = "CoverFlip"
    )

    val maxDrag = with(density) { 240.dp.toPx() }
    // P2-4：阈值按 dp 声明再换算成像素——旧实现是裸 90f 像素，
    // 在 320dpi 设备上仅约 29dp，与 160dpi 设备手感差异巨大
    val swipeThreshold = with(density) { 90.dp.toPx() }
    // 阻尼函数：越拖位移增量越小，产生"拉橡皮筋"手感
    fun damp(drag: Float): Float = maxDrag * tanh(drag / maxDrag)
    val damped = damp(dragAccum + offsetAnim.value)
    val progress = (damped / maxDrag).coerceIn(-1f, 1f)
    val cardScale = 1f - 0.12f * abs(progress)
    val cardAlpha = 1f - 0.55f * abs(progress)

    fun endDrag(total: Float) {
        // 过阈值切歌：容器位移从当前阻尼位置以与过渡同时长的补间归位，
        // 与 AnimatedContent 的滑入/滑出衔接，避免瞬移跳变
        val settle = {
            scope.launch {
                offsetAnim.snapTo(damp(total))
                offsetAnim.animateTo(0f, tween(380))
            }
        }
        if (total > swipeThreshold) {
            swipeDir = -1
            dragAccum = 0f
            settle()
            onSwipePrev()
        } else if (total < -swipeThreshold) {
            swipeDir = 1
            dragAccum = 0f
            settle()
            onSwipeNext()
        } else {
            // 未过阈值：从阻尼位移弹回中线
            scope.launch {
                dragAccum = 0f
                offsetAnim.snapTo(damp(total))
                offsetAnim.animateTo(0f, springBack)
            }
        }
    }

    AnimatedContent(
        targetState = entry,
        transitionSpec = {
            // 上滑切下一首：新封面自底部滑入、旧封面向上滑出；下滑相反，与手势方向一致
            val dir = if (swipeDir >= 0) AnimatedContentTransitionScope.SlideDirection.Up
            else AnimatedContentTransitionScope.SlideDirection.Down
            (slideIntoContainer(dir, tween(380)) + fadeIn(tween(320)) +
                scaleIn(tween(380), initialScale = 0.82f)) togetherWith
                (slideOutOfContainer(dir, tween(380)) + fadeOut(tween(300)) +
                    scaleOut(tween(380), targetScale = 0.82f))
        },
        label = "CoverParallax",
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                translationY = damped
                scaleX = cardScale
                scaleY = cardScale
                alpha = cardAlpha
                rotationY = rotation
                cameraDistance = 18f * density.density
            }
            .clip(RoundedCornerShape(24.dp))
    ) { e ->
        // 正/背面按翻转态排序：交互面始终置于最上层，避免透明背面抢占手势
        Box(Modifier.fillMaxSize()) {
            if (flipped) {
                // 已翻转：封面退到背后（不再交互），歌词面朝上（可滚动 / 点按翻回）
                CoverFace(
                    entry = e,
                    discAngle = discAngle,
                    coverAlpha = if (rotation <= 90f) 1f else 0f,
                    interactive = false,
                    onSingleTap = {}, onDoubleTap = {}, onLongPress = {},
                    onDragDelta = {}, onDragEnd = {},
                    coverOverride = coverOverride
                )
                LyricsBackFace(
                    backAlpha = if (rotation > 90f) 1f else 0f,
                    onTapFlipBack = { onFlippedChange(false) },
                    content = backContent
                )
            } else {
                // 未翻转：歌词面垫底（透明），封面朝上并接管全部手势
                LyricsBackFace(
                    backAlpha = if (rotation > 90f) 1f else 0f,
                    onTapFlipBack = { onFlippedChange(false) },
                    content = backContent
                )
                CoverFace(
                    entry = e,
                    discAngle = discAngle,
                    coverAlpha = if (rotation <= 90f) 1f else 0f,
                    interactive = true,
                    onSingleTap = { onFlippedChange(true) },
                    onDoubleTap = onDoubleTap,
                    onLongPress = onLongPress,
                    onDragDelta = { total -> dragAccum = total.coerceIn(-maxDrag, maxDrag) },
                    onDragEnd = { total -> endDrag(total) },
                    coverOverride = coverOverride
                )
            }
        }
    }
}

/**
 * 封面正面：展示专辑封面，并在 [interactive] 为 true 时接管
 * 单击(翻转) / 双击(播放暂停) / 长按(歌曲信息) / 垂直拖动(切歌) 手势。
 * 手势合并为单一 awaitEachGesture，避免多个 pointerInput 相互抢占。
 * 拖动方向约定：上滑=下一首，下滑=上一首（位移累加为负=上滑）。
 */
@Composable
private fun CoverFace(
    entry: QueueEntry,
    discAngle: Animatable<Float, AnimationVector1D>,
    coverAlpha: Float,
    interactive: Boolean,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    // P0-8：Room 实时封面（编辑/匹配后刷新）；null 时用 entry 快照封面
    coverOverride: Uri? = null
) {
    val scope = rememberCoroutineScope()
    var lastTapTime by remember { mutableLongStateOf(0L) }
    val tapJob = remember { mutableStateOf<Job?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = coverAlpha }
            .then(
                if (interactive) {
                    Modifier.pointerInput(entry.songId) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var dragY = 0f
                            var dragging = false
                            var longPressed = false
                            val longPressJob = scope.launch {
                                delay(500L)
                                longPressed = true
                                onLongPress()
                            }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    longPressJob.cancel()
                                    when {
                                        dragging -> onDragEnd(dragY)
                                        !longPressed -> {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            if (now - lastTapTime < 300L) {
                                                tapJob.value?.cancel()
                                                lastTapTime = 0L
                                                onDoubleTap()
                                            } else {
                                                lastTapTime = now
                                                tapJob.value?.cancel()
                                                tapJob.value = scope.launch {
                                                    delay(300L)
                                                    onSingleTap()
                                                }
                                            }
                                        }
                                    }
                                    break
                                }
                                val dy = change.position.y - change.previousPosition.y
                                if (!dragging && abs(change.position.y - down.position.y) > slop) {
                                    dragging = true
                                    longPressJob.cancel()
                                }
                                if (dragging) {
                                    dragY += dy
                                    onDragDelta(dragY)
                                    change.consume()
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // CD 唱片：深色盘体外圈 + 圆形封面 + 中心孔；播放时整体随 discAngle 匀速旋转
        val scheme = MaterialTheme.colorScheme
        // 圆形专辑封面（优先 Room 实时封面——P0-8）。
        // 提到旋转层之外，因为下方不旋转的中心标签需要判断「有无封面」来决定是否印音符。
        val coverModel = coverOverride ?: entry.coverUri
        Box(
            modifier = Modifier
                .fillMaxSize(0.96f)
                .graphicsLayer { rotationZ = discAngle.value },
            contentAlignment = Alignment.Center
        ) {
            // 盘体
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0xFF161616))
            )
            // 内圈细高光，模拟唱片纹路
            Box(
                modifier = Modifier
                    .fillMaxSize(0.93f)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            )
            // 圆形专辑封面（优先 Room 实时封面——P0-8）
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.86f).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // P2-4：无封面时旧实现渲染纯灰圆盘 + 小黑点，观感像加载失败。
                // 改为品牌渐变盘体 + 黑胶纹路，任何主题下都像"设计如此"。
                // 2026-11-02：统一改为 DefaultCover 组件（固定品牌紫渐变，与其余
                // 10 处无封面回退完全一致）；vinyl 模式只画旋转对称的盘面，
                // 中心标签（带音符）另画在下方不旋转的图层上。
                DefaultCover(
                    modifier = Modifier.fillMaxSize(0.86f),
                    shape = CircleShape,
                    vinyl = true,
                )
            }
        }

        // 中心标签（深色贴纸 + 轴孔 + 音符）。
        //
        // 必须画在**不随唱片旋转**的图层上：唱片播放时持续旋转，若标签跟着转，
        // 偏心的音符会转到任意角度，看着像渲染 bug。固定标签、只转盘面最稳。
        // 真封面时也用同一套标签（glyph = null），与无封面时观感一致。
        Box(
            modifier = Modifier.fillMaxSize(0.22f),
            contentAlignment = Alignment.Center,
        ) {
            VinylCenterLabel(
                modifier = Modifier.fillMaxSize(),
                glyph = if (coverModel == null) Icons.Rounded.MusicNote else null,
            )
        }
    }
}

/** 封面背面：预旋转 180°，随容器翻转正面朝观众；点按翻回封面（仅朝上时可交互） */
@Composable
private fun LyricsBackFace(
    backAlpha: Float,
    onTapFlipBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .graphicsLayer {
                rotationY = 180f
                alpha = backAlpha
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTapFlipBack() })
            }
    ) {
        content()
    }
}

/**
 * 封面背面歌词页：字号调节 + 高亮当前行并「上下居中悬浮」显示。
 *
 * 居中悬浮方案：歌词区上下各留 (区域高 - 行高)/2 的对称内边距，使当前高亮行始终
 * 停在歌词区的垂直中线；前/后歌词随播放位置从两侧平滑流过（animateScrollToItem
 * 自带过渡），当前行切换时自动滚动到新行的中心。保留原交互：用户手动拖动浏览
 * 歌词后，自动居中避让 4 秒，避免强制拉回打断阅读。
 */
@Composable
internal fun LyricsFace(
    lyrics: ParsedLyrics?,
    positionMs: Long,
    fontSizeSp: Int,
    onFontSize: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlIconButton(
                icon = Icons.Rounded.RemoveCircleOutline,
                contentDescription = stringResource(R.string.pt_player_023),
                onClick = { onFontSize((fontSizeSp - 2).coerceAtLeast(12)) },
                buttonSize = 48.dp,
                iconSize = 18.dp
            )
            Text(stringResource(R.string.pt_player_015), style = MaterialTheme.typography.titleMedium)
            ControlIconButton(
                icon = Icons.Rounded.AddCircleOutline,
                contentDescription = stringResource(R.string.pt_player_024),
                onClick = { onFontSize((fontSizeSp + 2).coerceAtMost(30)) },
                buttonSize = 48.dp,
                iconSize = 18.dp
            )
        }
        if (lyrics == null || lyrics.lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.pt_player_025),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return
        }

        val currentIndex = lyrics.lineIndexAt(positionMs)
        val listState = rememberLazyListState()
        // 歌词区实际高度（px）：用于计算上下对称内边距，把当前行精确居中
        var areaHeightPx by remember { mutableIntStateOf(0) }
        // 估算单行高度（px）= 字号(sp, 含系统字体缩放) + 上下各 6.dp 内边距，
        // 作为居中内边距与行高的近似
        val density = LocalDensity.current
        val lineHeightPx = with(density) { ((fontSizeSp * fontScale + 12).dp).toPx() }
        val halfPadDp = with(density) {
            ((areaHeightPx - lineHeightPx) / 2f).coerceAtLeast(0f).toDp()
        }

        // 用户手动滚动歌词后，自动居中避让几秒（沿用原交互逻辑）
        var userScrollPauseUntil by remember { mutableLongStateOf(0L) }
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    userScrollPauseUntil = android.os.SystemClock.uptimeMillis() + 4_000L
                }
            }
        }
        // 当前行变化时平滑滚动到该行中心；处于手动浏览窗口内则不打断
        LaunchedEffect(currentIndex, areaHeightPx) {
            if (currentIndex < 0) return@LaunchedEffect
            if (android.os.SystemClock.uptimeMillis() < userScrollPauseUntil) return@LaunchedEffect
            listState.animateScrollToItem(currentIndex)
        }

        Box(modifier = Modifier.fillMaxSize().onSizeChanged { areaHeightPx = it.height }) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = halfPadDp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(lyrics.lines.size) { i ->
                    Text(
                        text = lyrics.lines[i].text,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSizeSp.sp),
                        color = if (i == currentIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

package com.puretone.player.ui.screens

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.puretone.player.lyrics.ParsedLyrics
import com.puretone.player.playback.QueueEntry
import com.puretone.player.playback.toPlayMode
import com.puretone.player.ui.MetadataViewModel
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.Routes
import com.puretone.player.ui.components.ControlIconButton
import com.puretone.player.ui.components.ControlIcons
import com.puretone.player.ui.components.FavoriteButton
import com.puretone.player.ui.components.PlayModeIconButton
import com.puretone.player.ui.components.PlayPauseButton
import com.puretone.player.ui.components.PlayerProgressBar
import com.puretone.player.ui.components.SongRow
import com.puretone.player.ui.components.formatTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/** 全屏播放页（Item 1/2/3：布局重构 + 封面翻转歌词 + 视差切歌） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    navController: NavHostController,
    vm: PlayerViewModel = hiltViewModel(),
    metadataVm: MetadataViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val favorite by vm.favorite.collectAsStateWithLifecycle()
    val currentSong by vm.currentSong.collectAsStateWithLifecycle()
    val entry = state.currentEntry
    var showQueue by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var lyricFontSize by remember { mutableIntStateOf(18) }

    // 播放顺序（三态：顺序播放 / 随机播放 / 单曲循环），由播放状态派生
    val playMode = state.toPlayMode()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("正在播放") },
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
                // 右上角三个功能按钮已按建议移除：均衡器→进度条上方左侧，
                // 歌词→封面翻转，播放队列→底部右侧按钮。
            )
        }
    ) { padding ->
        if (entry == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("当前没有正在播放的歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        // 当前歌曲变化时加载其同目录 .lrc 歌词
        LaunchedEffect(entry.songId) {
            vm.loadLyrics(entry.filePath)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 封面：3D 翻转歌词 / 视差切歌 / 双击播放暂停 / 长按歌曲信息
            SwipeableCover(
                entry = entry,
                isPlaying = state.isPlaying,
                onSwipePrev = vm::prev,
                onSwipeNext = vm::next,
                onDoubleTap = vm::togglePlay,
                onLongPress = { showInfo = true },
                modifier = Modifier.fillMaxWidth(0.82f),
                backContent = {
                    LyricsFace(
                        lyrics = lyrics,
                        positionMs = state.positionMs,
                        fontSizeSp = lyricFontSize,
                        onFontSize = { lyricFontSize = it }
                    )
                }
            )

            // 标题 / 艺术家
            Text(
                entry.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                entry.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (state.isTranscoding) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "APE 转码中…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (state.error != null) {
                Text(
                    state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 进度条上方：左=均衡器入口，右=收藏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlIconButton(
                    icon = Icons.Outlined.Equalizer,
                    contentDescription = "均衡器",
                    onClick = { navController.navigate(Routes.EQ) },
                    buttonSize = 40.dp,
                    iconSize = 22.dp
                )
                FavoriteButton(favorite = favorite, onClick = vm::toggleFavorite)
            }

            // 进度（加热区 + 拖动时间气泡）
            val duration = if (state.durationMs > 0) state.durationMs else entry.durationMs
            val position = state.positionMs.coerceAtMost(duration)
            PlayerProgressBar(
                positionMs = position,
                durationMs = duration,
                onSeek = vm::seek,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(position), style = MaterialTheme.typography.labelMedium)
                Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(2.dp))

            // 底部四核心按钮：左=播放模式，中=上一首/播放暂停/下一首（严格对称），右=播放队列
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayModeIconButton(
                    mode = playMode,
                    onClick = vm::cyclePlayMode,
                    buttonSize = 48.dp,
                    iconSize = 26.dp
                )

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlIconButton(
                        icon = ControlIcons.SkipPrevious,
                        contentDescription = "上一首",
                        onClick = vm::prev,
                        buttonSize = 56.dp,
                        iconSize = 40.dp
                    )
                    PlayPauseButton(isPlaying = state.isPlaying, onClick = vm::togglePlay)
                    ControlIconButton(
                        icon = ControlIcons.SkipNext,
                        contentDescription = "下一首",
                        onClick = vm::next,
                        buttonSize = 56.dp,
                        iconSize = 40.dp
                    )
                }

                ControlIconButton(
                    icon = ControlIcons.Queue,
                    contentDescription = "播放队列",
                    onClick = { showQueue = true },
                    buttonSize = 48.dp,
                    iconSize = 28.dp
                )
            }
        }
    }

    // 播放队列浮层（半模态，从底部弹出）
    // 精简：仅保留「上一首 / 播放·暂停 / 下一首」三个按钮，其余按钮全部移除；
    // 队列列表保留（点击即切歌），下滑或点遮罩关闭。
    if (showQueue) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showQueue = false }, sheetState = sheetState) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp)
            ) {
                Text("播放队列（${state.queue.size}）", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                if (state.queue.isEmpty()) {
                    Text(
                        "队列为空",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        // 注意：队列允许同一首歌出现多次，key 不能只用 songId，故沿用位置索引
                        itemsIndexed(state.queue) { index, q ->
                            SongRow(
                                song = com.puretone.player.data.local.entity.SongEntity(
                                    id = q.songId,
                                    mediaStoreId = q.songId,
                                    title = q.title,
                                    artist = q.artist,
                                    album = "",
                                    albumId = 0,
                                    duration = q.durationMs,
                                    path = q.filePath,
                                    size = 0,
                                    coverUri = q.coverUri?.toString(),
                                    mimeType = if (q.isApe) "audio/ape" else "audio/mpeg"
                                ),
                                onClick = { vm.playAtIndex(index) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlIconButton(
                        icon = ControlIcons.SkipPrevious,
                        contentDescription = "上一首",
                        onClick = vm::prev,
                        buttonSize = 56.dp,
                        iconSize = 40.dp
                    )
                    ControlIconButton(
                        icon = if (state.isPlaying) ControlIcons.Pause else ControlIcons.Play,
                        contentDescription = "播放/暂停",
                        onClick = vm::togglePlay,
                        buttonSize = 56.dp,
                        iconSize = 40.dp
                    )
                    ControlIconButton(
                        icon = ControlIcons.SkipNext,
                        contentDescription = "下一首",
                        onClick = vm::next,
                        buttonSize = 56.dp,
                        iconSize = 40.dp
                    )
                }
            }
        }
    }

    // 歌曲信息浮层（长按封面唤起；含查看/编辑 + 联网匹配）
    if (showInfo && currentSong != null) {
        SongInfoSheet(
            song = currentSong!!,
            playerVm = vm,
            metadataVm = metadataVm,
            onDismiss = { showInfo = false }
        )
    }
}

/**
 * 可滑动 + 可翻转封面：
 * - 上滑/下滑切歌：拖动带阻尼（tanh），当前封面随拖动缩小淡出，过阈值切歌；
 *   切歌时新封面从对应方向滑入并放大（AnimatedContent 视差过渡，与拖动方向一致），
 *   松手后容器位移以同时长补间归位，避免瞬移跳变，GPU 硬件加速；
 * - 单击（延迟 300ms 与双击区分）→ 3D 翻转显示背面歌词，再点翻回；
 * - 双击 → 播放 / 暂停；长按 → 唤起歌曲信息弹窗。
 */
@Composable
private fun SwipeableCover(
    entry: QueueEntry,
    isPlaying: Boolean,
    onSwipePrev: () -> Unit,
    onSwipeNext: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    backContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val springBack = SpringSpec<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)
    val offsetAnim = remember { Animatable(0f) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var flipped by remember { mutableStateOf(false) }
    // 切歌方向：>0 下一首（向上滑），<0 上一首（向下滑）；决定 AnimatedContent 过渡方向
    var swipeDir by remember { mutableIntStateOf(1) }

    // CD 旋转角：播放时匀速持续旋转，暂停即停（保留当前角度），切歌不重置。
    // 该 Animatable 位于 SwipeableCover 作用域，跨 entry 变化保持同一实例。
    val discAngle = remember { Animatable(0f) }
    LaunchedEffect(isPlaying, flipped) {
        if (isPlaying && !flipped) {
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

    val maxDrag = 240f
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
        if (total > 90f) {
            swipeDir = -1
            dragAccum = 0f
            settle()
            onSwipePrev()
        } else if (total < -90f) {
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
                    onDragDelta = {}, onDragEnd = {}
                )
                LyricsBackFace(
                    backAlpha = if (rotation > 90f) 1f else 0f,
                    onTapFlipBack = { flipped = false },
                    content = backContent
                )
            } else {
                // 未翻转：歌词面垫底（透明），封面朝上并接管全部手势
                LyricsBackFace(
                    backAlpha = if (rotation > 90f) 1f else 0f,
                    onTapFlipBack = { flipped = false },
                    content = backContent
                )
                CoverFace(
                    entry = e,
                    discAngle = discAngle,
                    coverAlpha = if (rotation <= 90f) 1f else 0f,
                    interactive = true,
                    onSingleTap = { flipped = !flipped },
                    onDoubleTap = onDoubleTap,
                    onLongPress = onLongPress,
                    onDragDelta = { total -> dragAccum = total.coerceIn(-maxDrag, maxDrag) },
                    onDragEnd = { total -> endDrag(total) }
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
    onDragEnd: (Float) -> Unit
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
            // 圆形专辑封面
            if (entry.coverUri != null) {
                AsyncImage(
                    model = entry.coverUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.86f).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.86f)
                        .clip(CircleShape)
                        .background(scheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("♪", style = MaterialTheme.typography.displayMedium, color = scheme.primary)
                }
            }
            // 中心孔（用页面底色模拟"打通"）
            Box(
                modifier = Modifier
                    .fillMaxSize(0.16f)
                    .clip(CircleShape)
                    .background(scheme.background)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize(0.05f)
                    .clip(CircleShape)
                    .background(Color(0xFF161616))
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
 * 封面背面歌词页：字号调节 + 高亮当前行并随播放位置自动滚动。
 */
@Composable
private fun LyricsFace(
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
                contentDescription = "缩小字号",
                onClick = { onFontSize((fontSizeSp - 2).coerceAtLeast(12)) },
                buttonSize = 36.dp,
                iconSize = 18.dp
            )
            Text("歌词", style = MaterialTheme.typography.titleMedium)
            ControlIconButton(
                icon = Icons.Rounded.AddCircleOutline,
                contentDescription = "放大字号",
                onClick = { onFontSize((fontSizeSp + 2).coerceAtMost(30)) },
                buttonSize = 36.dp,
                iconSize = 18.dp
            )
        }
        if (lyrics == null || lyrics.lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无歌词（可将同名 .lrc 放在歌曲同一目录）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return
        }

        val currentIndex = lyrics.lineIndexAt(positionMs)
        val listState = rememberLazyListState()
        LaunchedEffect(currentIndex) {
            if (currentIndex >= 0) listState.animateScrollToItem(currentIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
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

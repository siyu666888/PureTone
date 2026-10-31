package com.puretone.player.ui.screens

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.DragHandle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.puretone.player.lyrics.ParsedLyrics
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.playback.QueueEntry
import com.puretone.player.playback.cast.CastDevice
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
    // P2-10：根部只订阅「离散」状态（positionMs 已抹平），避免整屏 2Hz 重组；
    // 高频进度由 ProgressSection / 歌词面板各自在子树内收集。
    val state by vm.discreteState.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val favorite by vm.favorite.collectAsStateWithLifecycle()
    val currentSong by vm.currentSong.collectAsStateWithLifecycle()
    val castState by vm.castState.collectAsStateWithLifecycle()
    val entry = state.currentEntry
    var showQueue by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showCastPicker by remember { mutableStateOf(false) }
    var lyricFontSize by remember { mutableIntStateOf(18) }
    // P2-4：封面/歌词翻转态提升到此——「歌词」可见按钮与点按封面翻转共享同一状态
    var coverFlipped by remember { mutableStateOf(false) }

    // P0-8：标题/歌手/封面优先取 Room 实时当前曲（编辑信息、联网匹配后立即刷新），
    // 回退到设置队列时构建的 QueueEntry 快照（刚切歌、Room 尚未回发的过渡态）。
    // 旧实现只显示 entry 快照，导致改完歌曲信息播放页/迷你条仍是旧标题旧封面。
    val displayTitle = currentSong?.title ?: entry?.title ?: ""
    val displayArtist = currentSong?.artist ?: entry?.artist ?: ""
    val displayCover = currentSong?.coverUri?.let { Uri.parse(it) } ?: entry?.coverUri

    // 播放顺序（三态：顺序播放 / 随机播放 / 单曲循环），由播放状态派生
    val playMode = state.toPlayMode()

    Scaffold(
        // P1-7：inset 由外层 Scaffold 统一负责（底部导航可见时它是唯一 source），
        // 内层 Scaffold 不再叠加系统栏 padding，否则标题上方多出一圈空白
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("正在播放") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // M1：投屏入口。投屏进行中显示「已连接」图标，点击仍打开设备选择（可换设备/停止）。
                    IconButton(onClick = { showCastPicker = true }) {
                        Icon(
                            if (castState.isCasting) Icons.Filled.CastConnected else Icons.Outlined.Cast,
                            contentDescription = if (castState.isCasting) "投屏中（点按管理）" else "投屏"
                        )
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
                coverOverride = displayCover,
                isPlaying = state.isPlaying,
                onSwipePrev = vm::prev,
                onSwipeNext = vm::next,
                onDoubleTap = vm::togglePlay,
                onLongPress = { showInfo = true },
                modifier = Modifier.fillMaxWidth(0.82f),
                flipped = coverFlipped,
                onFlippedChange = { coverFlipped = it },
                backContent = {
                    // P2-10：进度在这个 lambda 作用域内收集——重组范围被限制在歌词面板，
                    // 不会带着封面/按钮/队列一起每 500ms 重组
                    val lyricPositionMs by vm.positionMs.collectAsStateWithLifecycle()
                    LyricsFace(
                        lyrics = lyrics,
                        positionMs = lyricPositionMs,
                        fontSizeSp = lyricFontSize,
                        onFontSize = { lyricFontSize = it }
                    )
                }
            )

            // 标题 / 艺术家（Room 实时值，编辑后立即刷新——P0-8）
            Text(
                displayTitle,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                displayArtist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // M1：投屏中提示条——显示当前投屏设备名并支持一键停止；设备名/错误随 castState 实时刷新
            if (castState.isCasting) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.CastConnected,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "正在投屏到 ${castState.deviceName ?: "设备"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (castState.error != null) {
                                Text(
                                    castState.error ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        TextButton(onClick = vm::stopCast) { Text("停止投屏") }
                    }
                }
            }

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
                    style = MaterialTheme.typography.bodySmall,
                    // P3-27：可点掉，不必等它自己消失
                    modifier = Modifier.clickable { vm.dismissError() }
                )
            }

            // 进度条上方：左=均衡器入口，中=歌词/封面切换，右=收藏
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
                // P2-4：歌词入口原为「点封面翻转」的隐藏手势，新用户很难发现。
                // 补一个可见切换按钮（与封面翻转共享翻转态），保留点按翻转为快捷方式。
                FilledTonalButton(
                    onClick = { coverFlipped = !coverFlipped },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        if (coverFlipped) "封面" else "歌词",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                FavoriteButton(favorite = favorite, onClick = vm::toggleFavorite)
            }

            // 进度（加热区 + 拖动时间气泡）
            // P2-10：progress 是唯一需要高频刷新的部分，收集下沉到 ProgressSection 内部
            val duration = if (state.durationMs > 0) state.durationMs else entry.durationMs
            ProgressSection(vm = vm, durationMs = duration)

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

    // M1：投屏设备选择对话框（与播放队列浮层同级，覆盖在播放页之上）
    if (showCastPicker) {
        CastPickerDialog(
            vm = vm,
            currentSong = currentSong,
            onDismiss = { showCastPicker = false },
            onSelect = { device ->
                showCastPicker = false
                currentSong?.let { vm.startCast(device, it) }
            }
        )
    }

    // 播放队列浮层（半模态，从底部弹出）
    // 精简：仅保留「上一首 / 播放·暂停 / 下一首」三个按钮，其余按钮全部移除；
    // 队列列表保留（点击即切歌），下滑或点遮罩关闭。
    // P2-4：当前曲高亮、行尾移除、顶部「清空」；行不再构造 album="" 的假 SongEntity。
    if (showQueue) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showQueue = false }, sheetState = sheetState) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "播放队列（${state.queue.size}）",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.queue.isNotEmpty()) {
                        TextButton(onClick = {
                            vm.clearQueue()
                            showQueue = false
                        }) { Text("清空") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (state.queue.isEmpty()) {
                    Text(
                        "队列为空",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    val queueListState = rememberLazyListState()
                    val reorderableState = rememberReorderableLazyListState(queueListState) { from, to ->
                        vm.moveQueueItem(from.index, to.index)
                    }
                    LazyColumn(
                        state = queueListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        // 用 QueueEntry.key 作稳定 key（同一首歌可多次入队，位置索引不稳定）
                        items(state.queue, key = { it.key }) { q ->
                            val idx = state.queue.indexOf(q)
                            ReorderableItem(reorderableState, key = q.key) { _ ->
                                QueueRow(
                                    entry = q,
                                    isCurrent = q.key == state.queue.getOrNull(state.queueIndex)?.key,
                                    onClick = { if (idx >= 0) vm.playAtIndex(idx) },
                                    onRemove = { if (idx >= 0) vm.removeFromQueue(idx) },
                                    dragHandle = Modifier.draggableHandle()
                                )
                            }
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
 * 进度条 + 起止时间标签（P2-10）。
 *
 * positionMs 是 2Hz 高频字段，收集放在本组件内部：重组范围被限制在进度条这一小块，
 * 而旧实现在 PlayerScreen 根部收集整个 state，导致封面/按钮/队列每 500ms 跟着重组一遍。
 */
@Composable
private fun ProgressSection(vm: PlayerViewModel, durationMs: Long) {
    val positionMs by vm.positionMs.collectAsStateWithLifecycle()
    val position = positionMs.coerceAtMost(durationMs)
    PlayerProgressBar(
        positionMs = position,
        durationMs = durationMs,
        onSeek = vm::seek,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatTime(position), style = MaterialTheme.typography.labelMedium)
        Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium)
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
            val coverModel = coverOverride ?: entry.coverUri
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.86f).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                // P2-4：无封面时旧实现渲染纯灰圆盘 + 小黑点，观感像加载失败。
                // 改为品牌渐变盘体 + 黑胶纹路 + 音符，任何主题下都像"设计如此"。
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.86f)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(scheme.primary, scheme.tertiary)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // 黑胶纹路：两圈同心圆描边
                    Box(
                        Modifier
                            .fillMaxSize(0.72f)
                            .clip(CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    )
                    Box(
                        Modifier
                            .fillMaxSize(0.5f)
                            .clip(CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    )
                    Text(
                        "♪",
                        style = MaterialTheme.typography.displayMedium,
                        color = Color.White
                    )
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
 * 封面背面歌词页：字号调节 + 高亮当前行并「上下居中悬浮」显示。
 *
 * 居中悬浮方案：歌词区上下各留 (区域高 - 行高)/2 的对称内边距，使当前高亮行始终
 * 停在歌词区的垂直中线；前/后歌词随播放位置从两侧平滑流过（animateScrollToItem
 * 自带过渡），当前行切换时自动滚动到新行的中心。保留原交互：用户手动拖动浏览
 * 歌词后，自动居中避让 4 秒，避免强制拉回打断阅读。
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
        // 歌词区实际高度（px）：用于计算上下对称内边距，把当前行精确居中
        var areaHeightPx by remember { mutableStateOf(0) }
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

/**
 * 播放队列行（P2-4）。
 *
 * 旧实现为复用 [SongRow] 而构造 `album=""` 的假 SongEntity：副标题出现
 * "artist · " 尾点，媒体类型/封面等字段也都是凑数的；且队列不标当前曲、
 * 不能移除单曲。这里直接基于 [QueueEntry] 渲染——当前曲整行高亮 +
 * 「正在播放」标注，行尾移除按钮。
 */
@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isCurrent) scheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (entry.coverUri != null) {
                AsyncImage(
                    model = entry.coverUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
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
                color = if (isCurrent) scheme.primary else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isCurrent) {
            Text(
                "正在播放",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        if (entry.isApe) {
            Text(
                "APE",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.RemoveCircleOutline,
                contentDescription = "从队列移除",
                tint = scheme.onSurfaceVariant
            )
        }
        IconButton(onClick = {}, modifier = dragHandle) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = "拖拽排序",
                tint = scheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
        }
    }
}

/**
 * 投屏设备选择对话框（M1）。
 *
 * 打开即发起一次 SSDP 设备发现（[PlayerViewModel.searchCastDevices]），展示局域网内
 * 渲染器列表；点击设备即选中并发起当前歌曲投屏（[onSelect]）。空列表/失败给出明确引导，
 * 「重新搜索」按钮可再次发现。设备名/地址来自 [CastDevice]，第三方类型不泄漏到此处以外。
 */
@Composable
private fun CastPickerDialog(
    vm: PlayerViewModel,
    currentSong: SongEntity?,
    onDismiss: () -> Unit,
    onSelect: (CastDevice) -> Unit
) {
    var devices by remember { mutableStateOf<List<CastDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun doSearch() {
        scope.launch {
            loading = true
            error = null
            try {
                devices = vm.searchCastDevices()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { doSearch() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择投屏设备") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                when {
                    loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("正在搜索局域网设备…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    error != null -> {
                        Text(
                            "搜索失败：$error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    devices.isEmpty() -> {
                        Text(
                            "未发现可投屏设备。请确认手机与电视/音箱在同一 Wi-Fi，" +
                                "且设备已开启 DLNA/投屏接收。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(devices) { d ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(d) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        if (d.isTV) Icons.Filled.CastConnected else Icons.Outlined.Cast,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            d.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            d.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { doSearch() }) { Text("重新搜索") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

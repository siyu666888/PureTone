package com.puretone.player.ui.screens

import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Equalizer
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.playback.toPlayMode
import com.puretone.player.ui.MetadataViewModel
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.Routes
import com.puretone.player.ui.components.ControlIconButton
import com.puretone.player.ui.components.ControlIcons
import com.puretone.player.ui.components.FavoriteButton
import com.puretone.player.ui.components.PlayModeIconButton
import com.puretone.player.ui.components.PlayPauseButton

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
    val castActive = castState.isCasting || castState.isPreparing
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
                title = {
                    Text(
                        stringResource(R.string.pt_player_001),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.pt_player_002))
                    }
                },
                actions = {
                    // 投屏态隐藏右上角入口：连接状态已由正文的状态胶囊合并承担，
                    // 换设备改从「设备详情」二级页进入，避免两处图标语义重复。
                    if (!castActive) {
                        IconButton(onClick = { showCastPicker = true }) {
                            Icon(
                                Icons.Outlined.Cast,
                                contentDescription = stringResource(R.string.pt_player_004)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (entry == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pt_player_005), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        // 当前歌曲变化时加载其同目录 .lrc 歌词
        LaunchedEffect(entry.songId) {
            vm.loadLyrics(entry.filePath)
        }

        // 投屏态（isCasting / isPreparing）：正文整体切换为遥控器面板，手机只做远程控制；
        // 否则仍展示本地播放器。两者互斥，避免「本地封面 + 投屏状态卡」叠在一起的混乱。
        if (castActive) {
            // 投屏期只有遥控页需要逐帧进度：在这个分支内收集，别把整屏拖进 2Hz 重组（P2-10）。
            // 它与播放页 / 迷你条 / 通知栏读的是同一份 uiState（已镜像电视轮询 + 外推），
            // 不再各自直读 1Hz 的 castState.progressMs，避免三处进度互相对不上。
            val castPositionMs by vm.positionMs.collectAsStateWithLifecycle()
            val castDurationMs = state.durationMs.takeIf { it > 0L } ?: (entry?.durationMs ?: 0L)
            CastRemoteControlPanel(
                castState = castState,
                positionMs = castPositionMs,
                durationMs = castDurationMs,
                title = displayTitle,
                artist = displayArtist,
                coverUri = displayCover,
                onTogglePlay = vm::togglePlay,
                onSeek = vm::seek,
                onSeekCommitted = vm::notifyCastSeekOptimistic,
                onVolume = vm::setCastVolume,
                onPrev = vm::prev,
                onNext = vm::next,
                onDisconnect = vm::stopCast,
                onPickDevice = { showCastPicker = true },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
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

            if (state.isTranscoding) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.pt_player_012),
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
                    contentDescription = stringResource(R.string.pt_player_013),
                    onClick = { navController.navigate(Routes.EQ) },
                    buttonSize = 48.dp,
                    iconSize = 22.dp
                )
                // P2-4：歌词入口原为「点封面翻转」的隐藏手势，新用户很难发现。
                // 补一个可见切换按钮（与封面翻转共享翻转态），保留点按翻转为快捷方式。
                FilledTonalButton(
                    onClick = { coverFlipped = !coverFlipped },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        if (coverFlipped) stringResource(R.string.pt_player_014) else stringResource(R.string.pt_player_015),
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
                        contentDescription = stringResource(R.string.pt_player_016),
                        onClick = vm::prev,
                        buttonSize = 56.dp,
                        iconSize = 40.dp
                    )
                    PlayPauseButton(isPlaying = state.isPlaying, onClick = vm::togglePlay)
                    ControlIconButton(
                        icon = ControlIcons.SkipNext,
                        contentDescription = stringResource(R.string.pt_player_017),
                        onClick = vm::next,
                        buttonSize = 56.dp,
                        iconSize = 40.dp
                    )
                }

                ControlIconButton(
                    icon = ControlIcons.Queue,
                    contentDescription = stringResource(R.string.pt_player_018),
                    onClick = { showQueue = true },
                    buttonSize = 48.dp,
                    iconSize = 28.dp
                )
            }
        }
    }

    // M1：投屏设备选择对话框（与播放队列浮层同级，覆盖在播放页之上）
    if (showCastPicker) {
        CastDevicePickerDialog(
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
                        stringResource(R.string.pt_player_019, state.queue.size),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.queue.isNotEmpty()) {
                        TextButton(onClick = {
                            vm.clearQueue()
                            showQueue = false
                        }) { Text(stringResource(R.string.pt_player_020)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (state.queue.isEmpty()) {
                    Text(
                        stringResource(R.string.pt_player_021),
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
                        contentDescription = stringResource(R.string.pt_player_016),
                        onClick = vm::prev,
                        buttonSize = 56.dp,
                        iconSize = 40.dp
                    )
                    ControlIconButton(
                        icon = if (state.isPlaying) ControlIcons.Pause else ControlIcons.Play,
                        contentDescription = stringResource(R.string.pt_player_022),
                        onClick = vm::togglePlay,
                        buttonSize = 56.dp,
                        iconSize = 40.dp
                    )
                    ControlIconButton(
                        icon = ControlIcons.SkipNext,
                        contentDescription = stringResource(R.string.pt_player_017),
                        onClick = vm::next,
                        buttonSize = 56.dp,
                        iconSize = 40.dp
                    )
                }
            }
        }
        }  // 关闭播放队列浮层 if (showQueue)
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

package com.puretone.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.puretone.player.data.local.dao.PlaylistWithSongs
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.SongSortOrder
import com.puretone.player.domain.grouping.AlbumSummary
import com.puretone.player.domain.grouping.ArtistSummary
import com.puretone.player.domain.grouping.FolderSummary
import com.puretone.player.ui.LibraryViewModel
import com.puretone.player.ui.MetadataViewModel
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.components.FavoriteRed
import com.puretone.player.ui.components.SongRow
import com.puretone.player.ui.Routes
import kotlinx.coroutines.delay

/** 音乐页页签 */
enum class MusicTab { SONGS, ALBUMS, ARTISTS, FOLDERS }

/**
 * 音乐页（原"浏览"）：搜索 + 全曲库列表 + 曲库扫描入口 + 排序 + 封面补全。
 *
 * - 底部导航/标题统一为「音乐」；
 * - 扫描按钮在顶部动作区；曲库为空且本次进程尚未扫描过时自动扫一次；
 * - 右上角排序菜单支持按标题/添加时间/歌手/专辑/时长/播放次数动态排序；
 * - 右上角「补全封面」一键联网补全本页缺失封面（进度实时展示）；
 * - 顶部内容区提供「播放全部」入口（原顶栏按钮下移，不再显示歌曲数量）；
 * - 歌曲行尾「+」可把该曲加入歌单；右上角「多选」进入批量选择，可整批加入歌单；
 * - 扫描器自动跳过录音 / 铃声 / 提示音等非音乐文件（见 MediaStoreScanner）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    navController: NavHostController,
    libVm: LibraryViewModel = hiltViewModel(),
    playerVm: PlayerViewModel = hiltViewModel(),
    metadataVm: MetadataViewModel = hiltViewModel()
) {
    val library by libVm.library.collectAsStateWithLifecycle()
    val sortOrder by libVm.sortOrder.collectAsStateWithLifecycle()
    val searchQuery by libVm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by libVm.searchResults.collectAsStateWithLifecycle()
    val isScanning by libVm.isScanning.collectAsStateWithLifecycle()
    val backfill by metadataVm.backfill.collectAsStateWithLifecycle()
    val metaMessage by metadataVm.message.collectAsStateWithLifecycle()
    val playlists by libVm.playlists.collectAsStateWithLifecycle()

    var searching by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    // 多选模式 + 选中集合（UI 态，不落库）
    var selecting by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    // 「加入歌单」弹窗目标（null=关闭；非空=待加入的歌曲 id 集合）
    var addDialogSongs by remember { mutableStateOf<List<Long>?>(null) }
    // 歌曲长按操作面板目标（null=关闭）
    var songMenuSong by remember { mutableStateOf<SongEntity?>(null) }
    // 歌曲信息浮层目标（null=关闭）
    var infoSheetSong by remember { mutableStateOf<SongEntity?>(null) }

    // 当前页签（歌曲 / 专辑 / 歌手 / 文件夹）
    var tab by remember { mutableStateOf(MusicTab.SONGS) }

    val list = if (searching && searchQuery.isNotBlank()) searchResults else library

    val tabTitle = when (tab) {
        MusicTab.SONGS -> if (selecting) "已选 ${selectedIds.size} 首" else "音乐"
        MusicTab.ALBUMS -> "专辑"
        MusicTab.ARTISTS -> "歌手"
        MusicTab.FOLDERS -> "文件夹"
    }

    // 曲库为空且本次启动还没扫过：自动扫描一次（授权后首次进入即建库）
    LaunchedEffect(Unit) {
        libVm.autoScanIfEmpty()
    }

    // 补全提示展示片刻后自动消失
    LaunchedEffect(metaMessage) {
        if (metaMessage != null) {
            delay(2600)
            metadataVm.consumeMessage()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    tabTitle,
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            // P1-7：状态栏 inset 由外层 Scaffold 统一负责，内层不再重复叠加
            windowInsets = WindowInsets(0),
            actions = {
                // 扫描：旋转刷新图标；扫描中显示进度圈（全库操作，所有页签可用）
                IconButton(
                    onClick = libVm::scan,
                    enabled = !isScanning
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "扫描曲库")
                    }
                }
                // 补全缺失封面：一键联网补全（所有页签可用）
                IconButton(
                    onClick = { metadataVm.backfillMissingCovers {} },
                    enabled = backfill == null
                ) {
                    if (backfill != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = "补全缺失封面")
                    }
                }
                // 以下仅歌曲页签：多选 / 排序 / 搜索
                if (tab == MusicTab.SONGS) {
                    // 多选模式开关
                    IconButton(onClick = {
                        selecting = !selecting
                        selectedIds.clear()
                    }) {
                        Icon(
                            imageVector = if (selecting) Icons.Rounded.Close else Icons.Rounded.Checklist,
                            contentDescription = if (selecting) "退出多选" else "多选"
                        )
                    }
                    // 排序：弹出排序选项菜单
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.Rounded.SwapVert, contentDescription = "排序")
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false }
                    ) {
                        SortOption("按标题", SongSortOrder.TITLE, sortOrder) { libVm.setSortOrder(it); sortMenuOpen = false }
                        SortOption("按添加时间", SongSortOrder.DATE_ADDED, sortOrder) { libVm.setSortOrder(it); sortMenuOpen = false }
                        SortOption("按歌手", SongSortOrder.ARTIST, sortOrder) { libVm.setSortOrder(it); sortMenuOpen = false }
                        SortOption("按专辑", SongSortOrder.ALBUM, sortOrder) { libVm.setSortOrder(it); sortMenuOpen = false }
                        SortOption("按时长", SongSortOrder.DURATION, sortOrder) { libVm.setSortOrder(it); sortMenuOpen = false }
                        SortOption("按播放次数", SongSortOrder.PLAY_COUNT, sortOrder) { libVm.setSortOrder(it); sortMenuOpen = false }
                    }
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜索")
                    }
                }
            }
        )

        // 页签：歌曲 / 专辑 / 歌手 / 文件夹
        TabRow(selectedTabIndex = tab.ordinal) {
            Tab(selected = tab == MusicTab.SONGS, onClick = { tab = MusicTab.SONGS }, text = { Text("歌曲") })
            Tab(selected = tab == MusicTab.ALBUMS, onClick = { tab = MusicTab.ALBUMS }, text = { Text("专辑") })
            Tab(selected = tab == MusicTab.ARTISTS, onClick = { tab = MusicTab.ARTISTS }, text = { Text("歌手") })
            Tab(selected = tab == MusicTab.FOLDERS, onClick = { tab = MusicTab.FOLDERS }, text = { Text("文件夹") })
        }

        // 封面补全进度 / 结果提示（库级，任何页签下都提示）
        val bf = backfill
        val msg = metaMessage
        if (bf != null || msg != null) {
            Text(
                text = if (bf != null) "正在补全封面 ${bf.first} / ${bf.second} …" else msg.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        when (tab) {
            MusicTab.SONGS -> {
                // 顶部操作区：多选模式=批量动作条；否则=播放全部 + 扫描状态
                if (selecting) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "已选 ${selectedIds.size} / ${list.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            if (selectedIds.size == list.size) selectedIds.clear()
                            else {
                                selectedIds.clear()
                                selectedIds.addAll(list.map { it.id })
                            }
                        }) {
                            Text(if (selectedIds.isNotEmpty() && selectedIds.size == list.size) "取消全选" else "全选")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { addDialogSongs = selectedIds.toList() },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("加入歌单")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { playerVm.playSongs(library, 0) },
                            enabled = library.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("播放全部", style = MaterialTheme.typography.labelLarge)
                        }
                        if (isScanning) {
                            Spacer(Modifier.size(12.dp))
                            Text(
                                "正在扫描本地音乐…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (searching) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = libVm::setSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("搜索歌曲 / 艺术家 / 专辑") },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { libVm.setSearchQuery("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清空")
                                }
                            }
                        }
                    )
                }

                if (list.isEmpty()) {
                    EmptyState(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        text = when {
                            searching -> "未找到匹配的音乐"
                            isScanning -> "正在扫描本地音乐…"
                            else -> "曲库为空，点击右上角扫描按钮建立曲库"
                        }
                    )
                } else {
                    // key = 歌曲 id：批量选择 / 删除 / 换排序时 Compose 才能正确追踪条目，避免选中态串位
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(list, key = { _, song -> song.id }) { index, song ->
                            SongRow(
                                song = song,
                                onClick = {
                                    if (selecting) {
                                        if (song.id in selectedIds) selectedIds.remove(song.id)
                                        else selectedIds.add(song.id)
                                    } else {
                                        playerVm.playSongs(list, index)
                                    }
                                },
                                // 多选态下长按交给勾选语义，不弹操作面板
                                onLongClick = if (selecting) null else { { songMenuSong = song } },
                                selectionMode = selecting,
                                selected = song.id in selectedIds,
                                trailing = if (selecting) null else {
                                    {
                                        IconButton(onClick = { addDialogSongs = listOf(song.id) }) {
                                            Icon(Icons.Rounded.Add, contentDescription = "加入歌单")
                                        }
                                    }
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
            MusicTab.ALBUMS -> AlbumsTabContent(
                vm = libVm,
                onAlbumClick = { album ->
                    navController.navigate(
                        Routes.groupDetail("album", "${album.volumeName}|${album.albumId}|${album.album}")
                    )
                }
            )
            MusicTab.ARTISTS -> ArtistsTabContent(
                vm = libVm,
                onArtistClick = { artist ->
                    navController.navigate(Routes.groupDetail("artist", artist.artist))
                }
            )
            MusicTab.FOLDERS -> FoldersTabContent(
                vm = libVm,
                onFolderClick = { folder ->
                    navController.navigate(Routes.groupDetail("folder", folder.folder))
                }
            )
        }
    }

    // 「加入歌单」弹窗
    val ids = addDialogSongs
    if (ids != null) {
        AddToPlaylistDialog(
            songIds = ids,
            playlists = playlists,
            onAddTo = { playlistId ->
                libVm.addToPlaylist(playlistId, ids)
                addDialogSongs = null
                if (selecting) { selecting = false; selectedIds.clear() }
            },
            onCreateAndAdd = { name ->
                libVm.createPlaylistAndAdd(name, ids)
                addDialogSongs = null
                if (selecting) { selecting = false; selectedIds.clear() }
            },
            onDismiss = { addDialogSongs = null }
        )
    }

    // 歌曲长按操作面板
    val menuSong = songMenuSong
    if (menuSong != null) {
        SongActionSheet(
            song = menuSong,
            favorite = menuSong.favorite,
            onPlayNext = {
                playerVm.playNext(menuSong)
                songMenuSong = null
            },
            onEnqueue = {
                libVm.enqueue(listOf(menuSong))
                songMenuSong = null
            },
            onAddToPlaylist = {
                songMenuSong = null
                addDialogSongs = listOf(menuSong.id)
            },
            onToggleFavorite = {
                libVm.setFavorite(menuSong.id, !menuSong.favorite)
                songMenuSong = null
            },
            onShowInfo = {
                songMenuSong = null
                infoSheetSong = menuSong
            },
            onDismiss = { songMenuSong = null }
        )
    }

    // 歌曲信息浮层（查看 / 编辑 + 联网匹配）
    val infoSong = infoSheetSong
    if (infoSong != null) {
        SongInfoSheet(
            song = infoSong,
            playerVm = playerVm,
            metadataVm = metadataVm,
            onDismiss = { infoSheetSong = null }
        )
    }
}

/**
 * 歌曲长按操作面板：下一首播放 / 加入队列 / 加入歌单 / 收藏 / 歌曲信息。
 *
 * 顶部展示封面缩略与曲名副标题确认目标，下方为等高的图标 + 文案操作行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionSheet(
    song: SongEntity,
    favorite: Boolean,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowInfo: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                            modifier = Modifier.fillMaxSize(),
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
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ActionItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "下一首播放", onPlayNext)
            ActionItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "加入队列", onEnqueue)
            ActionItem(Icons.Rounded.Add, "加入歌单", onAddToPlaylist)
            ActionItem(
                imageVector = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) FavoriteRed else MaterialTheme.colorScheme.onSurface,
                onClick = onToggleFavorite
            )
            ActionItem(Icons.Rounded.Info, "歌曲信息", onShowInfo)
        }
    }
}

/** 操作面板里的一行：图标 + 文案 */
@Composable
private fun ActionItem(
    imageVector: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector,
            contentDescription = null,
            tint = if (tint != Color.Unspecified) tint else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun EmptyState(modifier: Modifier = Modifier, text: String) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 排序菜单项：当前排序项右侧显示勾选 */
@Composable
private fun SortOption(
    label: String,
    order: SongSortOrder,
    current: SongSortOrder,
    onSelect: (SongSortOrder) -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = { onSelect(order) },
        trailingIcon = {
            if (order == current) {
                Icon(Icons.Rounded.Check, contentDescription = "当前排序", tint = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

/**
 * 「加入歌单」弹窗：可新建歌单并加入，或点选已有歌单加入。
 */
@Composable
fun AddToPlaylistDialog(
    songIds: List<Long>,
    playlists: List<PlaylistWithSongs>,
    onAddTo: (Long) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (songIds.size == 1) "加入歌单" else "加入歌单（${songIds.size} 首）") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 新建歌单并加入
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("新建歌单名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onCreateAndAdd(newName) },
                        enabled = newName.isNotBlank()
                    ) { Text("新建") }
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    "选择已有歌单",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(4.dp))
                if (playlists.isEmpty()) {
                    Text(
                        "暂无歌单，先在上面新建一个吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    playlists.forEach { pw ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickableRow { onAddTo(pw.playlist.id) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pw.playlist.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${pw.songs.size} 首",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 行点击（无涟漪依赖的轻量封装） */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

/** 专辑封面网格墙 */
@Composable
private fun AlbumsTabContent(
    vm: LibraryViewModel,
    onAlbumClick: (AlbumSummary) -> Unit
) {
    // P3-14：仅在「专辑」页签被组合时才订阅该流；停留在其它页签（如「歌曲」）时
    // 本 composable 不参与组合，albums 的 StateFlow 无下游订阅 → 上游分组计算不触发，
    // 避免任何曲库变化都白白跑三次全库分组。
    val albums by vm.albums.collectAsStateWithLifecycle()
    if (albums.isEmpty()) {
        EmptyState(modifier = Modifier.fillMaxSize(), text = "暂无专辑")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 118.dp),
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(albums, key = { "${it.volumeName}|${it.albumId}|${it.album}" }) { album ->
            AlbumCard(album = album, onClick = { onAlbumClick(album) })
        }
    }
}

/** 专辑卡片：方形封面 + 专辑名 + 歌手·数量 */
@Composable
private fun AlbumCard(album: AlbumSummary, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (album.coverUri != null) {
                AsyncImage(
                    model = album.coverUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            album.album,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${album.artist} · ${album.songCount} 首",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 歌手列表 */
@Composable
private fun ArtistsTabContent(
    vm: LibraryViewModel,
    onArtistClick: (ArtistSummary) -> Unit
) {
    // P3-14：见 AlbumsTabContent——仅在「歌手」页签组合时订阅。
    val artists by vm.artists.collectAsStateWithLifecycle()
    if (artists.isEmpty()) {
        EmptyState(modifier = Modifier.fillMaxSize(), text = "暂无歌手")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(artists, key = { _, a -> a.artist }) { _, artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtistClick(artist) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (artist.coverUri != null) {
                        AsyncImage(
                            model = artist.coverUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        artist.artist,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${artist.songCount} 首",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

/** 文件夹列表 */
@Composable
private fun FoldersTabContent(
    vm: LibraryViewModel,
    onFolderClick: (FolderSummary) -> Unit
) {
    // P3-14：见 AlbumsTabContent——仅在「文件夹」页签组合时订阅。
    val folders by vm.folders.collectAsStateWithLifecycle()
    if (folders.isEmpty()) {
        EmptyState(modifier = Modifier.fillMaxSize(), text = "暂无文件夹")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(folders, key = { _, f -> f.folder }) { _, folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFolderClick(folder) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${folder.songCount} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

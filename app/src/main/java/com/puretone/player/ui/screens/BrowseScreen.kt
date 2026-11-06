package com.puretone.player.ui.screens
/**
 * 音乐页主框架：搜索 / 排序 / 扫描 / 页签切换 / 歌曲列表。
 * 智能歌单、弹层与页签内容已按职责搬到同包下的
 * [BrowseSections]、[BrowseDialogs]（纯 UI 搬移，零行为改动）。
 */

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.SongSortOrder
import com.puretone.player.ui.LibraryViewModel
import com.puretone.player.ui.MetadataViewModel
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.components.SongRow
import com.puretone.player.ui.Routes
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
    // P1 智能歌单入口数据：三类自动歌单（不落 PlaylistEntity，纯聚合）
    val recentlyAdded by libVm.recentlyAdded.collectAsStateWithLifecycle()
    val topSongs by libVm.topSongs.collectAsStateWithLifecycle()
    val recentlyPlayed by libVm.recentlyPlayed.collectAsStateWithLifecycle()

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
            MusicTab.SONGS -> if (selecting) stringResource(R.string.pt_browse_004, selectedIds.size)
                else stringResource(R.string.pt_browse_005)
        MusicTab.ALBUMS -> stringResource(R.string.pt_browse_006)
        MusicTab.ARTISTS -> stringResource(R.string.pt_browse_007)
        MusicTab.FOLDERS -> stringResource(R.string.pt_browse_008)
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
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.pt_browse_009))
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
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = stringResource(R.string.pt_browse_010))
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
                                contentDescription = if (selecting) stringResource(R.string.pt_browse_011)
                                    else stringResource(R.string.pt_browse_012)
                        )
                    }
                    // 排序：弹出排序选项菜单
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.Rounded.SwapVert, contentDescription = stringResource(R.string.pt_browse_013))
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false }
                    ) {
                            SortOption(
                                stringResource(R.string.pt_browse_014),
                                SongSortOrder.TITLE,
                                sortOrder
                            ) { libVm.setSortOrder(it); sortMenuOpen = false }
                            SortOption(
                                stringResource(R.string.pt_browse_015),
                                SongSortOrder.DATE_ADDED,
                                sortOrder
                            ) { libVm.setSortOrder(it); sortMenuOpen = false }
                            SortOption(
                                stringResource(R.string.pt_browse_016),
                                SongSortOrder.ARTIST,
                                sortOrder
                            ) { libVm.setSortOrder(it); sortMenuOpen = false }
                            SortOption(
                                stringResource(R.string.pt_browse_017),
                                SongSortOrder.ALBUM,
                                sortOrder
                            ) { libVm.setSortOrder(it); sortMenuOpen = false }
                            SortOption(
                                stringResource(R.string.pt_browse_018),
                                SongSortOrder.DURATION,
                                sortOrder
                            ) { libVm.setSortOrder(it); sortMenuOpen = false }
                            SortOption(
                                stringResource(R.string.pt_browse_019),
                                SongSortOrder.PLAY_COUNT,
                                sortOrder
                            ) { libVm.setSortOrder(it); sortMenuOpen = false }
                    }
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.pt_browse_020))
                    }
                }
            }
        )

        // 页签：歌曲 / 专辑 / 歌手 / 文件夹
        TabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == MusicTab.SONGS,
                    onClick = { tab = MusicTab.SONGS },
                    text = { Text(stringResource(R.string.pt_browse_021)) }
                )
                Tab(
                    selected = tab == MusicTab.ALBUMS,
                    onClick = { tab = MusicTab.ALBUMS },
                    text = { Text(stringResource(R.string.pt_browse_006)) }
                )
                Tab(
                    selected = tab == MusicTab.ARTISTS,
                    onClick = { tab = MusicTab.ARTISTS },
                    text = { Text(stringResource(R.string.pt_browse_007)) }
                )
                Tab(
                    selected = tab == MusicTab.FOLDERS,
                    onClick = { tab = MusicTab.FOLDERS },
                    text = { Text(stringResource(R.string.pt_browse_008)) }
                )
        }

        // 封面补全进度 / 结果提示（库级，任何页签下都提示）
        val bf = backfill
        val msg = metaMessage
        if (bf != null || msg != null) {
            Text(
                text = if (bf != null) {
                    stringResource(R.string.pt_browse_022, bf.first, bf.second)
                } else {
                    msg?.resolve(LocalContext.current).orEmpty()
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // P1：智能歌单快捷入口（仅歌曲页签、非搜索态）
        if (tab == MusicTab.SONGS && !searching) {
            SmartPlaylistSectionHeader()
            SmartPlaylistsRow(
                recentlyAdded = recentlyAdded,
                topSongs = topSongs,
                recentlyPlayed = recentlyPlayed,
                onPlay = { playerVm.playSongs(it, 0) }
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
                            stringResource(R.string.pt_browse_023, selectedIds.size, list.size),
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
                                Text(
                                    if (selectedIds.isNotEmpty() && selectedIds.size == list.size)
                                        stringResource(R.string.pt_browse_024)
                                    else stringResource(R.string.pt_browse_025)
                                )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { addDialogSongs = selectedIds.toList() },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.pt_browse_026))
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
                            Text(stringResource(R.string.pt_browse_027), style = MaterialTheme.typography.labelLarge)
                        }
                        if (isScanning) {
                            Spacer(Modifier.size(12.dp))
                            Text(
                                stringResource(R.string.pt_browse_028),
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
                        placeholder = { Text(stringResource(R.string.pt_browse_029)) },
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { libVm.setSearchQuery("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.pt_browse_030))
                                }
                            }
                        }
                    )
                }

                if (list.isEmpty()) {
                    EmptyState(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        text = when {
                            searching -> stringResource(R.string.pt_browse_031)
                            isScanning -> stringResource(R.string.pt_browse_028)
                            else -> stringResource(R.string.pt_browse_032)
                        }
                    )
                } else {
                    // key = 歌曲 id：批量选择 / 删除 / 换排序时 Compose 才能正确追踪条目，避免选中态串位
                    // contentPadding 底部留白：末项与底部 MiniPlayer/导航栏之间留出呼吸空间，避免内容被截断。
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
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
                                            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.pt_browse_026))
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

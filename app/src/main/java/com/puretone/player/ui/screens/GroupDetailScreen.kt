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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.ui.LibraryViewModel
import com.puretone.player.ui.MetadataViewModel
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.components.SongRow

/**
 * 分组二级详情页（专辑 / 歌手 / 文件夹通用）。
 *
 * - type ∈ {album, artist, folder}；key 为分组标识（album 形如 "albumId|album"）；
 * - 展示大封面 / 标题 / 副标题 / 歌曲数量 +「播放全部」；
 * - 下列为可点播、可长按唤出操作面板的歌曲列表，交互与音乐页一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    navController: NavHostController,
    type: String,
    key: String,
    libVm: LibraryViewModel = hiltViewModel(),
    playerVm: PlayerViewModel = hiltViewModel(),
    metadataVm: MetadataViewModel = hiltViewModel()
) {
    val songsFlow = remember(type, key) { libVm.observeGroupSongs(type, key) }
    val songs by songsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by libVm.playlists.collectAsStateWithLifecycle()

    var addDialogSongs by remember { mutableStateOf<List<Long>?>(null) }
    var songMenuSong by remember { mutableStateOf<SongEntity?>(null) }
    var infoSheetSong by remember { mutableStateOf<SongEntity?>(null) }

    val first = songs.firstOrNull()
    val (title, subtitle, coverUri) = when (type) {
        "album" -> {
            val seg = key.split("|", limit = 3)
            val albumName = seg.getOrNull(2) ?: first?.album ?: "专辑"
            Triple(first?.album ?: albumName, first?.artist, songs.firstNotNullOfOrNull { it.coverUri })
        }
        "artist" -> Triple(key, null, songs.firstNotNullOfOrNull { it.coverUri })
        else -> {
            val folderName = key.substringAfterLast('/').ifBlank { key }
            Triple(folderName, key, null)
        }
    }

    Scaffold(
        // P1-7：inset 由外层 Scaffold 统一负责，内层不重复叠加
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            GroupDetailHeader(
                type = type,
                title = title,
                subtitle = subtitle,
                coverUri = coverUri,
                songCount = songs.size,
                onPlayAll = { playerVm.playSongs(songs, 0) }
            )

            if (songs.isEmpty()) {
                EmptyState(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    text = "暂无歌曲"
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        SongRow(
                            song = song,
                            onClick = { playerVm.playSongs(songs, index) },
                            onLongClick = { songMenuSong = song },
                            trailing = {
                                IconButton(onClick = { addDialogSongs = listOf(song.id) }) {
                                    Icon(Icons.Rounded.Add, contentDescription = "加入歌单")
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
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
            },
            onCreateAndAdd = { name ->
                libVm.createPlaylistAndAdd(name, ids)
                addDialogSongs = null
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

/** 详情页头部：封面 + 标题 + 副标题 + 数量 + 播放全部 */
@Composable
private fun GroupDetailHeader(
    type: String,
    title: String,
    subtitle: String?,
    coverUri: String?,
    songCount: Int,
    onPlayAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            when (type) {
                "album" -> {
                    if (coverUri != null) {
                        AsyncImage(
                            model = coverUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                "artist" -> {
                    if (coverUri != null) {
                        AsyncImage(
                            model = coverUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                else -> {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "${songCount} 首",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(12.dp))

        FilledTonalButton(
            onClick = onPlayAll,
            enabled = songCount > 0
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("播放全部", style = MaterialTheme.typography.labelLarge)
        }
    }
}

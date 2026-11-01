package com.puretone.player.ui.screens
import androidx.compose.ui.res.stringResource
import com.puretone.player.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.InsertChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.data.local.dao.PlaylistWithSongs
import com.puretone.player.ui.LibraryViewModel
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.Routes
import com.puretone.player.ui.components.SongRow
import com.puretone.player.ui.theme.PureToneBrand

/** 我的：最近播放、我喜欢、歌单、听歌统计入口、曲库统计（扫描与封面补全已移至音乐页） */
@Composable
fun MeScreen(
    navController: NavHostController,
    libVm: LibraryViewModel = hiltViewModel(),
    playerVm: PlayerViewModel = hiltViewModel()
) {
    val recently by libVm.recentlyPlayed.collectAsStateWithLifecycle()
    val favorites by libVm.favorites.collectAsStateWithLifecycle()
    val playlists by libVm.playlists.collectAsStateWithLifecycle()
    val m3uMessage by libVm.m3uMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }

    // 导入 M3U：SAF 选文件（P2：歌单 M3U 导入导出）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val isM3u = runCatching {
            val name = context.contentResolver.query(uri, null, null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                    } else {
                        ""
                    }
                } ?: ""
            name.endsWith(".m3u", ignoreCase = true) || name.endsWith(".m3u8", ignoreCase = true)
        }.getOrDefault(false)
        if (!isM3u) {
            Toast.makeText(context, context.getString(R.string.pt_me_001), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        libVm.importM3u(uri)
    }

    // M3U 操作结果提示
    LaunchedEffect(m3uMessage) {
        m3uMessage?.let {
            Toast.makeText(context, it.resolve(context), Toast.LENGTH_LONG).show()
            libVm.consumeM3uMessage()
        }
    }
    // P2-3：删除歌单前先确认，避免误触立即删除
    var playlistToDelete by remember { mutableStateOf<PlaylistWithSongs?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.pt_me_002), style = MaterialTheme.typography.headlineSmall)

        // 听歌数据入口（点击进入详情页）
        Card(
            modifier = Modifier.fillMaxWidth(),
            // P3-27：launchSingleTop，避免连点压入多个统计页
            onClick = { navController.navigate(Routes.STATS) { launchSingleTop = true } }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.InsertChart, contentDescription = null, tint = PureToneBrand.Primary)
                Spacer(Modifier.size(12.dp))
                    Text(
                        stringResource(R.string.pt_me_003),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
            }
        }

        // 最近播放（仅展示前 8 首，点击播放也只把前 8 首送进队列，口径统一）
        if (recently.isNotEmpty()) {
            SectionTitle(stringResource(R.string.pt_me_004))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    val recent8 = recently.take(8)
                    recent8.forEachIndexed { index, song ->
                        SongRow(
                            song = song,
                            onClick = { playerVm.playSongs(recent8, index) }
                        )
                    }
                }
            }
        }

        // 我喜欢（收藏）
        if (favorites.isNotEmpty()) {
            SectionTitle(stringResource(R.string.pt_me_005, favorites.size))
            // P3-1：原先内层用 LazyColumn + 外层整页 verticalScroll，两个同向滚动容器
            // 互相抢手势（收藏区拖动时外层滚不动）。整页已用 verticalScroll 滚动，
            // 这里改用普通 Column，消除嵌套滚动冲突；收藏上千首的极端场景性能可接受。
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    favorites.forEachIndexed { index, song ->
                        SongRow(
                            song = song,
                            onClick = { playerVm.playSongs(favorites, index) }
                        )
                    }
                }
            }
        }

        // 歌单
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.pt_me_006, playlists.size), modifier = Modifier.weight(1f))
            TextButton(onClick = {
                importLauncher.launch(arrayOf("*/*"))
            }) {
                Text(stringResource(R.string.pt_me_007))
            }
            TextButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.pt_me_008))
            }
        }
        if (playlists.isEmpty()) {
            Text(stringResource(R.string.pt_me_009), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // P3-2：传 key，歌单增删时只重组受影响项而非整行
                items(playlists, key = { it.playlist.id }) { pw ->
                    // P3-24：LazyRow 给子项的最大宽度即视口宽，fillMaxWidth 会让每张卡片
                    // 占满整屏（一屏只见一张）；固定宽度实现横向多卡预览。
                    Card(
                        modifier = Modifier.width(160.dp),
                        // P3-3：卡片点击进入歌单详情页（保留卡片内播放/导出/删除按钮）
                        onClick = {
                            navController.navigate(Routes.playlistDetail(pw.playlist.id)) {
                                launchSingleTop = true
                            }
                        }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(pw.playlist.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.pt_me_010, pw.songs.size),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { playerVm.playSongs(pw.songs, 0) }) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = stringResource(R.string.pt_me_011),
                                            tint = PureToneBrand.Primary
                                        )
                                }
                                TextButton(onClick = { libVm.exportPlaylist(pw.playlist.id) }) {
                                    Text(stringResource(R.string.pt_me_012))
                                }
                                IconButton(onClick = { playlistToDelete = pw }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.pt_me_013))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onCreate = { name -> libVm.createPlaylist(name); showCreate = false },
            onDismiss = { showCreate = false }
        )
    }

    // P2-3：删除歌单确认弹窗
    // P3-3：先捕获局部 pw 再判空，避免判空后跨 lambda 取用 playlistToDelete!! 时，
    // 若状态被其它重组置 null 触发 NPE（智能转换让下面直接用 pw）。
    val pw = playlistToDelete
    if (pw != null) {
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text(stringResource(R.string.pt_me_013)) },
            text = { Text(stringResource(R.string.pt_me_014, pw.playlist.name)) },
            confirmButton = {
                TextButton(onClick = {
                    libVm.deletePlaylist(pw.playlist.id)
                    playlistToDelete = null
                }) { Text(stringResource(R.string.pt_me_015)) }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) { Text(stringResource(R.string.pt_me_016)) }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

/** 新建歌单命名弹窗 */
@Composable
private fun CreatePlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pt_me_008)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.pt_me_017)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.pt_me_018)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pt_me_016)) }
        }
    )
}

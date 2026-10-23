package com.puretone.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.data.local.entity.SongEntity
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
    var showCreate by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("我的音乐", style = MaterialTheme.typography.headlineSmall)

        // 听歌数据入口（点击进入详情页）
        Card(modifier = Modifier.fillMaxWidth(), onClick = { navController.navigate(Routes.STATS) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.InsertChart, contentDescription = null, tint = PureToneBrand.Primary)
                Spacer(Modifier.size(12.dp))
                Text("听歌数据", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }

        // 最近播放
        if (recently.isNotEmpty()) {
            SectionTitle("最近播放")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    recently.take(8).forEachIndexed { index, song ->
                        SongRow(
                            song = song,
                            onClick = { playerVm.playSongs(recently, index) }
                        )
                    }
                }
            }
        }

        // 我喜欢（收藏）
        if (favorites.isNotEmpty()) {
            SectionTitle("我喜欢（${favorites.size}）")
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
            SectionTitle("歌单（${playlists.size}）", modifier = Modifier.weight(1f))
            TextButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建歌单")
            }
        }
        if (playlists.isEmpty()) {
            Text("暂无歌单，点右上角「新建歌单」创建", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(playlists) { pw ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { playerVm.playSongs(pw.songs, 0) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(pw.playlist.name, style = MaterialTheme.typography.titleMedium)
                            Text("${pw.songs.size} 首", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { playerVm.playSongs(pw.songs, 0) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "播放歌单", tint = PureToneBrand.Primary)
                                }
                                IconButton(onClick = { libVm.deletePlaylist(pw.playlist.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除歌单")
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
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("歌单名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

package com.puretone.player.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.ui.LibraryViewModel
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.components.SongRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import android.widget.Toast

/**
 * 歌单详情页（P3-3 歌单 UI 补完）。
 *
 * 解决「我的」页歌单卡片直接播放、无详情入口的问题：点歌单卡片进入本页，提供
 * - 头部：歌单名 / 歌曲数 / 播放全部；
 * - 溢出菜单：重命名 / 导出 M3U / 删除；
 * - 歌曲列表：点按从当前位置播放；右侧拖动手柄长按排序、移除按钮删除单曲；
 *   拖动排序在内存列表里实时移动（流畅），松手后防抖一次性落库（避免每帧写 DB）。
 *
 * 列表展示以本地 [songList] 为权威：进入时从 Flow 播种一次，之后增删/排序只改本地 +
 * 写库，不再回灌 Flow（避免拖动过程中被重发的流覆盖产生抖动）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    navController: NavHostController,
    playlistId: Long,
    libVm: LibraryViewModel = hiltViewModel(),
    playerVm: PlayerViewModel = hiltViewModel()
) {
    val pwFlow = remember(playlistId) { libVm.observePlaylist(playlistId) }
    val pw by pwFlow.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current

    // 本地展示列表（权威），进入时从流播种一次
    val songList = remember { mutableStateListOf<SongEntity>() }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(pw) {
        val current = pw
        if (!seeded && current != null) {
            songList.clear()
            songList.addAll(current.songs)
            seeded = true
        }
    }

    // M3U 导出结果提示（与「我的」页共用 m3uMessage，消费即清空）
    val m3uMessage by libVm.m3uMessage.collectAsStateWithLifecycle()
    LaunchedEffect(m3uMessage) {
        m3uMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            libVm.consumeM3uMessage()
        }
    }

    // 重排落库：拖动过程中只动内存列表，防抖 400ms 后提交一次最终顺序
    val scope = rememberCoroutineScope()
    var persistJob by remember { mutableStateOf<Job?>(null) }
    fun schedulePersistOrder() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(400)
            if (songList.isNotEmpty()) {
                libVm.reorderPlaylist(playlistId, songList.map { it.id })
            }
        }
    }

    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = from.index
        val toIndex = to.index
        if (fromIndex in songList.indices && toIndex in songList.indices && fromIndex != toIndex) {
            songList.add(toIndex, songList.removeAt(fromIndex))
            schedulePersistOrder()
        }
    }

    // 弹窗状态
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val name = pw?.playlist?.name ?: "歌单"

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        name,
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
                },
                actions = {
                    if (pw != null) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        showRename = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("导出 M3U") },
                                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        libVm.exportPlaylist(playlistId)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除歌单") },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        showDelete = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (pw == null) {
            // 歌单不存在（可能已被其它入口删除）：提示并支持返回
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("歌单不存在", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 头部：歌单数 / 播放全部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${songList.size} 首",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(
                    onClick = { if (songList.isNotEmpty()) playerVm.playSongs(songList.toList(), 0) },
                    enabled = songList.isNotEmpty()
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("播放全部", style = MaterialTheme.typography.labelLarge)
                }
            }

            if (songList.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("歌单还没有歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(songList, key = { it.id }) { song ->
                        val index = songList.indexOf(song)
                        ReorderableItem(reorderableState, key = song.id) { _ ->
                            // 拖动手柄必须在 ReorderableItem 作用域内创建并捕获，
                            // 再传给 SongRow 的 trailing（trailing 是普通 lambda，
                            // 不在该作用域内，无法直接调用 draggableHandle）。
                            val dragHandle = Modifier.draggableHandle()
                            SongRow(
                                song = song,
                                onClick = { playerVm.playSongs(songList.toList(), index) },
                                trailing = {
                                    IconButton(
                                        onClick = {},
                                        modifier = dragHandle
                                    ) {
                                        Icon(
                                            Icons.Rounded.DragHandle,
                                            contentDescription = "拖动排序",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            songList.remove(song)
                                            libVm.removeFromPlaylist(playlistId, song.id)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "从歌单移除",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }

    // 重命名弹窗
    if (showRename) {
        AlertDialogRename(
            initial = name,
            onRename = { libVm.renamePlaylist(playlistId, it); showRename = false },
            onDismiss = { showRename = false }
        )
    }

    // 删除确认弹窗
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除歌单") },
            text = { Text("确定删除歌单「$name」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    libVm.deletePlaylist(playlistId)
                    showDelete = false
                    navController.popBackStack()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            }
        )
    }
}

/** 重命名输入框弹窗 */
@Composable
private fun AlertDialogRename(
    initial: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名歌单") },
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
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

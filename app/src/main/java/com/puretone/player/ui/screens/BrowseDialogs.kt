package com.puretone.player.ui.screens
/**
 * 音乐页弹层与操作面板：歌曲长按操作表、加入歌单对话框、排序项、空态。
 * 从 BrowseScreen.kt 原样搬出（纯 UI 拆分，零行为改动）。
 * SongActionSheet / EmptyState / AddToPlaylistDialog 也被 GroupDetailScreen 复用。
 */

import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.puretone.player.data.local.dao.PlaylistWithSongs
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.SongSortOrder
import com.puretone.player.ui.components.FavoriteRed
import com.puretone.player.ui.components.DefaultCover
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
                        // 无封面：显示默认海报
                        DefaultCover(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(8.dp),
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
            ActionItem(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.pt_browse_033), onPlayNext)
            ActionItem(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.pt_browse_034), onEnqueue)
            ActionItem(Icons.Rounded.Add, stringResource(R.string.pt_browse_026), onAddToPlaylist)
            ActionItem(
                imageVector = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (favorite) stringResource(R.string.pt_browse_035) else stringResource(R.string.pt_browse_036),
                tint = if (favorite) FavoriteRed else MaterialTheme.colorScheme.onSurface,
                onClick = onToggleFavorite
            )
            ActionItem(Icons.Rounded.Info, stringResource(R.string.pt_browse_037), onShowInfo)
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
internal fun SortOption(
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
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.pt_browse_038),
                        tint = MaterialTheme.colorScheme.primary
                    )
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
            title = {
                Text(
                    if (songIds.size == 1) stringResource(R.string.pt_browse_026)
                    else stringResource(R.string.pt_browse_039, songIds.size)
                )
            },
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
                        placeholder = { Text(stringResource(R.string.pt_browse_040)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { onCreateAndAdd(newName) },
                        enabled = newName.isNotBlank()
                    ) { Text(stringResource(R.string.pt_browse_041)) }
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.pt_browse_042),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(4.dp))
                if (playlists.isEmpty()) {
                    Text(
                        stringResource(R.string.pt_browse_043),
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
                                    stringResource(R.string.pt_browse_044, pw.songs.size),
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pt_browse_045)) }
        }
    )
}

/** 行点击（无涟漪依赖的轻量封装） */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

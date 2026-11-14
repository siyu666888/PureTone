package com.puretone.player.ui.screens
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.puretone.player.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.ui.MetadataViewModel
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.components.formatTime
import com.puretone.player.ui.components.DefaultCover
import com.puretone.player.util.ChineseText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * rememberSaveable 专用：把 MutableIntState 存成 Bundle 可承载的 Int。
 * 用 autoSaver 直接保存 state 对象不可靠，显式 Saver 既免装箱又保住进程重建恢复。
 */
private val IntStateSaver: Saver<MutableIntState, Int> = Saver(
    save = { it.intValue },
    restore = { mutableIntStateOf(it) }
)

/**
 * 歌曲信息半模态弹窗：查看 / 编辑信息 + 联网匹配（Item 1 长按封面唤起，Item 5 手动修正）。
 * 一键操作：「自动填充」按当前表单搜索并套用首个候选（信息+封面）；
 * 「下载歌词」从歌词源抓取并存入 App 歌词目录，供播放页高亮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongInfoSheet(
    song: SongEntity,
    playerVm: PlayerViewModel,
    metadataVm: MetadataViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 输入框圆角形状：与「保存修改」按钮（Material3 全圆角）一致的圆弧过渡
    val fieldShape = RoundedCornerShape(50)
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    // P2-9：改用 rememberSaveable——旧实现是 remember，进程被系统回收后重建时
    // 用户正在编辑的标题/歌手/专辑与搜索词全部丢失（manifest 的 configChanges 只挡住了
    // 旋屏重建，挡不住后台杀进程）。这些都是 String，可直接保存。
    var title by rememberSaveable(song.id) { mutableStateOf(ChineseText.toSimplified(song.title) ?: song.title) }
    var artist by rememberSaveable(song.id) { mutableStateOf(ChineseText.toSimplified(song.artist) ?: song.artist) }
    var album by rememberSaveable(song.id) { mutableStateOf(ChineseText.toSimplified(song.album) ?: song.album) }
    // 本地封面预览：套用联网候选后即时刷新（song 参数不会随之更新）
    var coverUri by rememberSaveable(song.id) { mutableStateOf(song.coverUri) }
    // P2-11：年份同样本地缓存——弹窗没有年份输入框，而 autoFill/apply 会把
    // 候选年份直接写库；保存时若仍传 song.year（打开弹窗时的旧值），刚
    // 自动填充的年份会被旧值覆盖（表现为「填了年份，保存后又变回去」）。
    var year by rememberSaveable(song.id, saver = IntStateSaver) { mutableIntStateOf(song.year) }
    // 预填搜索词：跳过「未知艺术家/空」，避免污染搜索；统一转简体
    val artistPart = (ChineseText.toSimplified(song.artist) ?: song.artist)
        .takeIf { it.isNotBlank() && it != "<unknown>" && it != stringResource(R.string.pt_songinfo_001) }
    var metaQuery by rememberSaveable(song.id) {
        mutableStateOf(
            ChineseText.toSimplified(
                listOfNotNull(artistPart, song.title.takeIf { it.isNotBlank() }).joinToString(" ").trim()
            ) ?: ""
        )
    }

    val results by metadataVm.results.collectAsStateWithLifecycle()
    val metaSearching by metadataVm.searching.collectAsStateWithLifecycle()
    val lyricsDownloading by metadataVm.lyricsDownloading.collectAsStateWithLifecycle()
    val message by metadataVm.message.collectAsStateWithLifecycle()
    val canUndo by metadataVm.canUndoAutoFill.collectAsStateWithLifecycle()

    // P2-2：切歌时清空上一首的搜索候选，避免残留
    LaunchedEffect(song.id) {
        metadataVm.clearResults()
    }

    // 提示展示片刻后自动消失；出现提示时同步刷新播放页当前歌曲（应用匹配成功后）
    LaunchedEffect(message) {
        if (message != null) {
            playerVm.refreshCurrentSong()
            delay(2600)
            metadataVm.consumeMessage()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.pt_songinfo_002),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.pt_songinfo_003))
                }
            }

            // 封面 + 只读摘要
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverUri != null) {
                        AsyncImage(
                            model = coverUri,
                            contentDescription = null, // 装饰性，非交互
                            modifier = Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        // 无封面：显示默认海报
                        DefaultCover(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        title.ifBlank { stringResource(R.string.pt_songinfo_004) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${artist.ifBlank { "未知艺术家" }} · ${formatTime(song.duration)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        song.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 编辑字段
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.pt_songinfo_005)) },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = artist,
                onValueChange = { artist = it },
                label = { Text(stringResource(R.string.pt_songinfo_006)) },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = album,
                onValueChange = { album = it },
                label = { Text(stringResource(R.string.pt_songinfo_007)) },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    // P0-1：传「弹窗里这首歌」的 id，保存成功才关闭；
                    // 失败保留弹窗与已填内容，并提示（旧实现用 currentSongId 会写错歌/静默丢失）。
                    scope.launch {
                        saving = true
                        val ok = playerVm.saveSongInfo(song.id, title, artist, album, coverUri, year)
                        saving = false
                        if (ok) onDismiss() else saveFailed = true
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saving) stringResource(R.string.pt_songinfo_008) else stringResource(R.string.pt_songinfo_009)) }
            if (saveFailed) {
                Text(
                    stringResource(R.string.pt_songinfo_010),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // 联网匹配
            Text(stringResource(R.string.pt_songinfo_011, metadataVm.providerName), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = metaQuery,
                    onValueChange = { metaQuery = it; metadataVm.setQuery(it) },
                    label = { Text(stringResource(R.string.pt_songinfo_012)) },
                    singleLine = true,
                    shape = fieldShape,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                if (metaSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { metadataVm.search(metaQuery) }) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.pt_songinfo_013))
                    }
                }
            }

            // 一键操作：自动填充信息与封面 / 下载歌词（存入 App 歌词目录，播放页自动读取）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        metadataVm.autoFill(song.id, artist, title) { c ->
                            title = c.title; artist = c.artist; album = c.album
                            coverUri = c.artworkUrl; year = c.year
                        }
                        playerVm.refreshCurrentSong()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !metaSearching
                ) { Text(stringResource(R.string.pt_songinfo_014)) }
                OutlinedButton(
                    onClick = {
                        metadataVm.downloadLyrics(song.path, artist, title, song.duration) { ok ->
                            if (ok) playerVm.loadLyrics(song.path)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !lyricsDownloading
                ) {
                    if (lyricsDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.pt_songinfo_015))
                    } else {
                        Text(stringResource(R.string.pt_songinfo_016))
                    }
                }
            }

            // P2-2：可撤销最近一次自动填充。撤销成功时把表单一并还原为
            // 原歌曲信息——否则表单里仍留着自动填充的值，用户再点「保存修改」
            // 会把候选值（含年份）又写回库，撤销等于白做。
            if (canUndo) {
                OutlinedButton(
                    onClick = {
                        metadataVm.undoAutoFill { original ->
                            title = ChineseText.toSimplified(original.title) ?: original.title
                            artist = ChineseText.toSimplified(original.artist) ?: original.artist
                            album = ChineseText.toSimplified(original.album) ?: original.album
                            coverUri = original.coverUri
                            year = original.year
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.pt_songinfo_017)) }
            }

            // 搜索结果 / 提示
            val msg = message
            if (msg != null) {
                Text(
                    text = msg.resolve(LocalContext.current),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (results.isNotEmpty()) {
                results.take(8).forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                c.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${c.artist} · ${c.album}".ifBlank { stringResource(R.string.pt_songinfo_018) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = {
                            metadataVm.apply(song.id, c) { c2 ->
                                title = c2.title; artist = c2.artist; album = c2.album
                                coverUri = c2.artworkUrl; year = c2.year
                            }
                            playerVm.refreshCurrentSong()
                        }) { Text(stringResource(R.string.pt_songinfo_019)) }
                    }
                }
                OutlinedButton(onClick = { metadataVm.clearResults() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pt_songinfo_020))
                }
            } else if (!metaSearching) {
                Text(
                    stringResource(R.string.pt_songinfo_021, metadataVm.lyricsProviderName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

package com.puretone.player.ui.screens
/**
 * 音乐页可复用区块：智能歌单快捷入口 + 专辑 / 歌手 / 文件夹页签内容。
 * 从 BrowseScreen.kt 原样搬出（纯 UI 拆分，零行为改动）。
 */

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import com.puretone.player.ui.components.SmartPlaylistBrandIcon
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.domain.grouping.AlbumSummary
import com.puretone.player.domain.grouping.ArtistSummary
import com.puretone.player.domain.grouping.FolderSummary
import com.puretone.player.ui.LibraryViewModel
import com.puretone.player.ui.components.SmartPlaylistGlyph
import com.puretone.player.ui.components.SmartPlaylistKind
import com.puretone.player.ui.components.DefaultCover
import com.puretone.player.ui.components.DefaultCoverGlyph
import androidx.compose.runtime.getValue

/**
 * P1 智能歌单卡片数据（仅 UI 聚合，不落库）。
 */
private data class SmartPlaylist(
    val title: String,
    val kind: SmartPlaylistKind,
    val songs: List<SongEntity>
)

/**
 * P1 智能歌单快捷入口：横向卡片行，点击整卡即把对应自动歌单送入播放队列。
 * 三类自动歌单均由既有数据聚合而来：
 * - 最近添加：按 date_added 倒序；
 * - 我的最爱：按 play_count 倒序（Top N）；
 * - 最近播放：play_history 倒序去重。
 * 列表为空时卡片禁用（暂无数据）。
 */
/**
 * 「智能歌单」分区标题：品牌图标 + 标题文字 + 右侧「自动生成」标签。
 * 仅作视觉分区，不响应点击；图标与文字随深浅主题自动适配。
 */
@Composable
internal fun SmartPlaylistSectionHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SmartPlaylistBrandIcon(modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.pt_smart_playlist_000),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.pt_smart_playlist_001),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(999.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
internal fun SmartPlaylistsRow(
    recentlyAdded: List<SongEntity>,
    topSongs: List<SongEntity>,
    recentlyPlayed: List<SongEntity>,
    onPlay: (List<SongEntity>) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        SmartPlaylist(stringResource(R.string.pt_browse_001), SmartPlaylistKind.RECENTLY_ADDED, recentlyAdded),
        SmartPlaylist(stringResource(R.string.pt_browse_002), SmartPlaylistKind.MY_FAVORITES, topSongs),
        SmartPlaylist(stringResource(R.string.pt_browse_003), SmartPlaylistKind.RECENTLY_PLAYED, recentlyPlayed)
    )
    // 三卡按 weight 均分整屏宽度：固定内边距 + 等分，杜绝横向滚动与卡片溢出（用户诉求）。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { sp ->
            SmartPlaylistCard(sp, onPlay, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SmartPlaylistCard(
    sp: SmartPlaylist,
    onPlay: (List<SongEntity>) -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = sp.songs.isNotEmpty()
    Card(
        modifier = modifier
            .clickable(enabled = enabled) { onPlay(sp.songs) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        // 竖向布局：大图标居中在上方、标题居中在下方；卡片设最小高度与充足内边距，
        // 保证图标完整渲染、标题不被截断，整体排版协调、无横向滚动。
        // 底部「歌曲数量」文字已按需求移除；空歌单仅置灰标题并禁用点击。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 108.dp)
                .padding(vertical = 18.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            SmartPlaylistGlyph(
                kind = sp.kind,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                sp.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
                    .copy(alpha = if (enabled) 1f else 0.5f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 专辑封面网格墙 */
@Composable
internal fun AlbumsTabContent(
    vm: LibraryViewModel,
    onAlbumClick: (AlbumSummary) -> Unit
) {
    // P3-14：仅在「专辑」页签被组合时才订阅该流；停留在其它页签（如「歌曲」）时
    // 本 composable 不参与组合，albums 的 StateFlow 无下游订阅 → 上游分组计算不触发，
    // 避免任何曲库变化都白白跑三次全库分组。
    val albums by vm.albums.collectAsStateWithLifecycle()
    if (albums.isEmpty()) {
        EmptyState(modifier = Modifier.fillMaxSize(), text = stringResource(R.string.pt_browse_046))
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
                // 无封面：显示默认海报（此处为 1:1 大卡，走「带黑胶纹」档位）
                DefaultCover(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(10.dp),
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
            stringResource(R.string.pt_browse_047, album.artist, album.songCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 歌手列表 */
@Composable
internal fun ArtistsTabContent(
    vm: LibraryViewModel,
    onArtistClick: (ArtistSummary) -> Unit
) {
    // P3-14：见 AlbumsTabContent——仅在「歌手」页签组合时订阅。
    val artists by vm.artists.collectAsStateWithLifecycle()
    if (artists.isEmpty()) {
        EmptyState(modifier = Modifier.fillMaxSize(), text = stringResource(R.string.pt_browse_048))
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
                        // 无封面：显示默认海报（歌手用人物符号）
                        DefaultCover(
                            modifier = Modifier.fillMaxSize(),
                            shape = CircleShape,
                            glyph = DefaultCoverGlyph.PERSON,
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
                        stringResource(R.string.pt_browse_049, artist.songCount),
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
internal fun FoldersTabContent(
    vm: LibraryViewModel,
    onFolderClick: (FolderSummary) -> Unit
) {
    // P3-14：见 AlbumsTabContent——仅在「文件夹」页签组合时订阅。
    val folders by vm.folders.collectAsStateWithLifecycle()
    if (folders.isEmpty()) {
        EmptyState(modifier = Modifier.fillMaxSize(), text = stringResource(R.string.pt_browse_050))
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
                        stringResource(R.string.pt_browse_051, folder.songCount),
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

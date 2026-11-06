package com.puretone.player.ui.screens

import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.puretone.player.playback.QueueEntry
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.components.PlaybackProgressBar
import com.puretone.player.ui.components.derivePlaybackState
import com.puretone.player.ui.components.SongRow
import com.puretone.player.ui.components.formatTime
import com.puretone.player.ui.components.DefaultCover

/**
 * 进度条 + 起止时间标签（P2-10）。
 *
 * positionMs 是 2Hz 高频字段，收集放在本组件内部：重组范围被限制在进度条这一小块，
 * 而旧实现在 PlayerScreen 根部收集整个 state，导致封面/按钮/队列每 500ms 跟着重组一遍。
 */
@Composable
internal fun ProgressSection(vm: PlayerViewModel, durationMs: Long) {
    val positionMs by vm.positionMs.collectAsStateWithLifecycle()
    val discrete by vm.discreteState.collectAsStateWithLifecycle()
    val duration = durationMs.coerceAtLeast(1L)
    val position = positionMs.coerceAtMost(durationMs)
    val playbackState = derivePlaybackState(
        isPlaying = discrete.isPlaying,
        isPreparing = discrete.isBuffering || discrete.isTranscoding,
        positionMs = position,
        durationMs = durationMs
    )
    PlaybackProgressBar(
        playedFraction = (position.toFloat() / duration).coerceIn(0f, 1f),
        durationMs = durationMs,
        playbackState = playbackState,
        onSeek = vm::seek,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatTime(position), style = MaterialTheme.typography.labelMedium)
        Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 播放队列行（P2-4）。
 *
 * 旧实现为复用 [SongRow] 而构造 `album=""` 的假 SongEntity：副标题出现
 * "artist · " 尾点，媒体类型/封面等字段也都是凑数的；且队列不标当前曲、
 * 不能移除单曲。这里直接基于 [QueueEntry] 渲染——当前曲整行高亮 +
 * 「正在播放」标注，行尾移除按钮。
 */
@Composable
internal fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isCurrent) scheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (entry.coverUri != null) {
                AsyncImage(
                    model = entry.coverUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
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
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) scheme.primary else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                entry.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isCurrent) {
            Text(
                stringResource(R.string.pt_player_001),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        if (entry.isApe) {
            Text(
                "APE",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.RemoveCircleOutline,
                contentDescription = stringResource(R.string.pt_player_026),
                tint = scheme.onSurfaceVariant
            )
        }
        IconButton(onClick = {}, modifier = dragHandle) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = stringResource(R.string.pt_player_027),
                tint = scheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
        }
    }
}

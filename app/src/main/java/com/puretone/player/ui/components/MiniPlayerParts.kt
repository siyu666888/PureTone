package com.puretone.player.ui.components

import androidx.compose.ui.res.stringResource
import com.puretone.player.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.puretone.player.playback.PlaybackUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * 迷你播放条（显示在底部导航之上）。
 *
 * 控制按钮仅保留三枚：上一首 / 播放-暂停 / 下一首（对称居中排布）。
 * 播放顺序切换与「喜欢」仅在完整播放页提供，避免迷你条信息过载。
 *
 * P1-10：入参为 [StateFlow] 而非状态值——position 每 500ms tick 一次，
 * 收集下沉到本组件内部，使 2Hz 重组只发生在迷你条子树，不波及根部导航树。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    stateFlow: StateFlow<PlaybackUiState>,
    /** 与播放页/遥控页/通知栏同源的展示进度，投屏期为电视镜像 + 250ms 外推 */
    positionFlow: StateFlow<Long>,
    /** 展示总时长的唯一出口，避免迷你条自己写一份 `uiState.durationMs ?: entry.durationMs` */
    durationFlow: StateFlow<Long>,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    // P0-8：Room 实时当前曲的展示字段（编辑标题/封面、联网匹配后立即刷新）。
    // 为 null（尚未查到实体）时回退到队列快照 entry，行为与旧版一致。
    liveTitle: String? = null,
    liveArtist: String? = null,
    liveCoverUri: android.net.Uri? = null
) {
    val state by stateFlow.collectAsStateWithLifecycle()
    val position by positionFlow.collectAsStateWithLifecycle()
    val duration by durationFlow.collectAsStateWithLifecycle()
    val entry = state.currentEntry ?: return
    val title = liveTitle ?: entry.title
    val artist = liveArtist ?: entry.artist
    val coverUri = liveCoverUri ?: entry.coverUri
    val scheme = MaterialTheme.colorScheme
    // 长按切换「剩余时长」展示
    var showRemaining by remember { mutableStateOf(false) }

    val remaining = (duration - position).coerceAtLeast(0L)
    val progress = if (duration > 0) {
        (position.toFloat() / duration).coerceIn(0f, 1f)
    } else 0f

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = scheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    // 无障碍：长按切换「剩余/总时长」是隐藏交互，给可见的 longClick 标签，
                    // 让 TalkBack 用户能在「长按」动作中读到用途，而非无提示的隐藏手势。
                    onLongClickLabel = stringResource(R.string.pt_ui_010),
                    onLongClick = { showRemaining = !showRemaining }
                )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverUri != null) {
                        AsyncImage(
                            model = coverUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
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

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 副行：默认艺术家；长按后切换为剩余 / 总时长，平滑过渡
                    AnimatedContent(
                        targetState = showRemaining,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                        },
                        label = "MiniSubtitle"
                    ) { remainingMode ->
                        if (remainingMode) {
                            Text(
                                stringResource(R.string.pt_ui_011, formatTime(remaining), formatTime(duration)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.primary,
                                maxLines = 1
                            )
                        } else {
                            Text(
                                artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 仅三枚控制按钮，关于中部播放/暂停对称：上一首（左）/ 播放暂停（中）/ 下一首（右）
                ControlIconButton(
                    icon = ControlIcons.SkipPrevious,
                    contentDescription = stringResource(R.string.pt_ui_012),
                    onClick = onPrev,
                    buttonSize = 48.dp,
                    iconSize = 24.dp
                )
                ControlIconButton(
                    icon = if (state.isPlaying) ControlIcons.Pause else ControlIcons.Play,
                    contentDescription = stringResource(R.string.pt_ui_013),
                    onClick = onToggle,
                    buttonSize = 48.dp,
                    iconSize = 24.dp
                )
                ControlIconButton(
                    icon = ControlIcons.SkipNext,
                    contentDescription = stringResource(R.string.pt_ui_014),
                    onClick = onNext,
                    buttonSize = 48.dp,
                    iconSize = 24.dp
                )
            }

            // 底部细进度线：与主题色一致，直观感知播放进度
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(scheme.surfaceContainerHighest)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(scheme.primary)
                )
            }
        }
    }
}

/** mm:ss 格式化 */
internal fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

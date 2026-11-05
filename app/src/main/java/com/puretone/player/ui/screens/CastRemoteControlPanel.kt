package com.puretone.player.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.puretone.player.R
import com.puretone.player.playback.cast.CastState
import com.puretone.player.ui.components.ControlIcons
import com.puretone.player.ui.components.DefaultCover
import com.puretone.player.ui.components.DefaultCoverGlyph
import com.puretone.player.ui.components.formatTime
import kotlin.math.roundToInt

/**
 * 投屏遥控器面板（手机端）。
 *
 * 当用户把音视频投射到大屏后，播放页正文整体替换为该面板——手机只承担远程控制：
 * 设备名 + 连接状态、当前曲目、可拖进度条、上/下一首、播放/暂停、投射音量、断开投屏。
 * 竖屏、控件大、单手可触。
 *
 * 设计要点：
 * - 全部控制入口（播放/暂停/上下首/进度/音量/断开）由外部通过回调注入，
 *   面板本身**不持有**任何业务逻辑——投屏态下这些调用已由 [com.puretone.player.ui.PlayerViewModel]
 *   路由到 DLNA 渲染器，本地播放器在投屏期恒为暂停。
 * - 播放/暂停图标以渲染器态 [CastState.isPlaying] 为准，而非本地播放态（本地恒暂停，
 *   否则「投屏中按暂停」会判断成播放，用户永远停不下来）。
 * - 进度/音量各维护一份本地态，经 `LaunchedEffect` 与 [CastState] 回读双向同步，
 *   拖动期间以本地为准、结束后才下发，避免与 1Hz 轮询互相打架。
 */
@Composable
fun CastRemoteControlPanel(
    castState: CastState,
    title: String,
    artist: String,
    coverUri: Uri?,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekCommitted: () -> Unit,
    onVolume: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val durationMs = castState.durationMs
    val hasProgress = castState.hasProgressInfo && durationMs > 0
    val controlsEnabled = castState.isCasting && !castState.isConnectionLost

    // 进度：本地态与渲染器回读双向同步，拖动期间以本地为准避免抖动
    var seeking by remember { mutableStateOf(false) }
    var progressFrac by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(castState.progressMs, durationMs, hasProgress) {
        if (!seeking && hasProgress) {
            progressFrac = (castState.progressMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
    }

    // 音量：本地态与 castState.castVolume 双向同步
    var castVol by remember { mutableFloatStateOf((castState.castVolume / 100f).coerceIn(0f, 1f)) }
    LaunchedEffect(castState.castVolume) { castVol = (castState.castVolume / 100f).coerceIn(0f, 1f) }

    val currentMs = (progressFrac * durationMs).toLong()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 设备名 + 连接状态（顶部，始终可见）
        CastStatusHeader(castState = castState)

        // 当前曲目（封面 + 标题 + 歌手）
        CastNowPlaying(coverUri = coverUri, title = title, artist = artist)

        // 进度条
        CastProgressRow(
            enabled = controlsEnabled,
            hasProgress = hasProgress,
            progressFrac = progressFrac,
            currentMs = currentMs,
            durationMs = durationMs,
            onProgressChange = { progressFrac = it },
            onSeekStart = { seeking = true },
            onSeekCommit = {
                seeking = false
                onSeek((progressFrac * durationMs).toLong())
                onSeekCommitted()
            }
        )

        // 传输控制：上一首 / 播放·暂停 / 下一首
        CastTransportRow(
            enabled = controlsEnabled,
            isPlaying = castState.isPlaying,
            onPrev = onPrev,
            onTogglePlay = onTogglePlay,
            onNext = onNext
        )

        // 投射音量
        CastVolumeRow(
            enabled = controlsEnabled && castState.volumeSupported != false,
            volumeSupported = castState.volumeSupported,
            castVol = castVol,
            onVolume = { onVolume((it * 100).roundToInt()) }
        )

        // 断开投屏
        CastDisconnectButton(onDisconnect = onDisconnect)

        // 错误提示（如有）
        castState.error?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 顶部：设备名 + 连接状态点。连接中显示 spinner，失联显示红色警告。 */
@Composable
private fun CastStatusHeader(castState: CastState) {
    val deviceName = castState.deviceName ?: stringResource(R.string.pt_player_004)
    val (statusText, statusColor) = when {
        castState.isConnectionLost -> stringResource(R.string.pt_player_045) to MaterialTheme.colorScheme.error
        castState.isPreparing -> stringResource(R.string.pt_player_044) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(R.string.pt_player_043) to MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Filled.CastConnected,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                deviceName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (castState.isPreparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = statusColor
                    )
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
        }
    }
}

/** 当前曲目：方形封面 + 标题 + 歌手。无封面回退统一默认海报。 */
@Composable
private fun CastNowPlaying(coverUri: Uri?, title: String, artist: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            DefaultCover(
                modifier = Modifier.size(96.dp),
                shape = RoundedCornerShape(14.dp),
                glyph = com.puretone.player.ui.components.DefaultCoverGlyph.MUSIC_NOTE
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 可拖进度条 + 当前/总时长；无进度信息时禁用并提示「进度未知」。 */
@Composable
private fun CastProgressRow(
    enabled: Boolean,
    hasProgress: Boolean,
    progressFrac: Float,
    currentMs: Long,
    durationMs: Long,
    onProgressChange: (Float) -> Unit,
    onSeekStart: () -> Unit,
    onSeekCommit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = progressFrac,
            onValueChange = {
                onProgressChange(it)
                onSeekStart()
            },
            onValueChangeFinished = onSeekCommit,
            valueRange = 0f..1f,
            enabled = enabled && hasProgress,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatTime(currentMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasProgress) {
                Text(
                    formatTime(durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    stringResource(R.string.pt_player_047),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 传输控制：上一首 | 播放·暂停(大圆) | 下一首。 */
@Composable
private fun CastTransportRow(
    enabled: Boolean,
    isPlaying: Boolean,
    onPrev: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val playTint = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        // 单手：控制集中在屏幕中下部，间距大、命中区足
    ) {
        IconButton(
            onClick = onPrev,
            enabled = enabled,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                ControlIcons.SkipPrevious,
                contentDescription = stringResource(R.string.pt_player_016),
                modifier = Modifier.size(34.dp),
                tint = tint
            )
        }
        // 播放/暂停主按钮：64dp 实心圆，与设备无关的可靠控制
        IconButton(
            onClick = onTogglePlay,
            enabled = enabled,
            modifier = Modifier
                .size(72.dp)
                .padding(4.dp)
        ) {
            Icon(
                if (isPlaying) ControlIcons.Pause else ControlIcons.Play,
                contentDescription = stringResource(R.string.pt_player_022),
                modifier = Modifier.size(40.dp),
                tint = playTint
            )
        }
        IconButton(
            onClick = onNext,
            enabled = enabled,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                ControlIcons.SkipNext,
                contentDescription = stringResource(R.string.pt_player_017),
                modifier = Modifier.size(34.dp),
                tint = tint
            )
        }
    }
}

/** 投射音量：图标 + 滑块 + 百分比；设备不支持远程音量时禁用并提示用遥控器。 */
@Composable
private fun CastVolumeRow(
    enabled: Boolean,
    volumeSupported: Boolean?,
    castVol: Float,
    onVolume: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val volIcon = if (castVol <= 0f) {
                Icons.AutoMirrored.Filled.VolumeOff
            } else {
                Icons.AutoMirrored.Filled.VolumeUp
            }
            Icon(
                volIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Slider(
                value = castVol,
                onValueChange = onVolume,
                valueRange = 0f..1f,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.pt_player_035, (castVol * 100).roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (volumeSupported == false) {
            Text(
                stringResource(R.string.pt_player_036),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** 断开投屏：全宽描边按钮，醒目但非实心，避免误触即中断。 */
@Composable
private fun CastDisconnectButton(onDisconnect: () -> Unit) {
    Card(
        onClick = onDisconnect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors()
    ) {
        Text(
            stringResource(R.string.pt_player_046),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            textAlign = TextAlign.Center
        )
    }
}

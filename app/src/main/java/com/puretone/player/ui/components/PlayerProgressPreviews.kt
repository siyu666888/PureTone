package com.puretone.player.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 进度条重设计预览（§9）：四播放态 × 三响应式宽度。
 * 直接用固定宽度容器驱动 [PlaybackProgressBar] 内部的 BoxWithConstraints 断点。
 */
private val previewDurationMs = 217_000L

@Composable
private fun BarPreview(width: androidx.compose.ui.unit.Dp, state: PlaybackState, fraction: Float) {
    MaterialTheme {
        Box(Modifier.width(width)) {
            PlaybackProgressBar(
                playedFraction = fraction,
                durationMs = previewDurationMs,
                playbackState = state,
                onSeek = {}
            )
        }
    }
}

@Preview(name = "Loading · 常规", showBackground = true)
@Composable
fun ProgressLoadingPreview() = BarPreview(360.dp, PlaybackState.Loading, 0.18f)

@Preview(name = "Playing · 常规", showBackground = true)
@Composable
fun ProgressPlayingPreview() = BarPreview(360.dp, PlaybackState.Playing, 0.42f)

@Preview(name = "Paused · 常规", showBackground = true)
@Composable
fun ProgressPausedPreview() = BarPreview(360.dp, PlaybackState.Paused, 0.42f)

@Preview(name = "Ended · 常规（重播字形）", showBackground = true)
@Composable
fun ProgressEndedPreview() = BarPreview(360.dp, PlaybackState.Ended, 1f)

@Preview(name = "窄 200dp · Playing", showBackground = true)
@Composable
fun ProgressNarrowPreview() = BarPreview(200.dp, PlaybackState.Playing, 0.55f)

@Preview(name = "宽 600dp · Playing", showBackground = true)
@Composable
fun ProgressWidePreview() = BarPreview(600.dp, PlaybackState.Playing, 0.7f)

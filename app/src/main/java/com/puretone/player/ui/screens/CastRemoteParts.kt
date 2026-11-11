package com.puretone.player.ui.screens

import com.puretone.player.ui.components.ControlIconSizes
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.puretone.player.R
import com.puretone.player.playback.cast.CastState
import com.puretone.player.ui.components.CastTrackInset
import com.puretone.player.ui.components.CastTrackSlider
import com.puretone.player.ui.components.ControlIcons
import com.puretone.player.ui.components.DefaultCover
import com.puretone.player.ui.components.DefaultCoverGlyph
import com.puretone.player.ui.components.formatTime

private val CoverCorner = 20.dp
private val CoverMaxSize = 200.dp
private val DisconnectHeight = 48.dp
private val ConnectedGreen = Color(0xFF2EAF6B)
private val LostOrange = Color(0xFFE8912A)

/**
 * 背景：封面放大高斯模糊铺底 + 顶部 surface 渐变 + 下半屏极淡主色渐变。
 *
 * `Modifier.blur` 仅在 API 31+ 生效，低版本若照样画会显示一张清晰的放大封面，
 * 因此低版本退化为径向柔光。AMOLED 纯黑主题关闭模糊层，避免脏灰底破坏省电效果。
 */
@Composable
internal fun CastBackdrop(coverUri: Uri?) {
    val scheme = MaterialTheme.colorScheme
    val surface = scheme.surface
    val isPureBlack = surface.red < 0.02f && surface.green < 0.02f && surface.blue < 0.02f
    val isDark = (surface.red * 0.299f + surface.green * 0.587f + surface.blue * 0.114f) < 0.5f

    Box(modifier = Modifier.fillMaxSize().background(surface)) {
        if (!isPureBlack && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.25f
                        scaleY = 1.25f
                    }
                    .blur(48.dp)
                    .alpha(if (isDark) 0.08f else 0.12f)
            )
        } else if (!isPureBlack) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.06f), Color.Transparent))
                )
            )
        }

        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to surface.copy(alpha = 0.72f),
                    0.40f to Color.Transparent
                )
            )
        )

        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.50f to Color.Transparent,
                    1.0f to scheme.primary.copy(
                        alpha = if (isPureBlack) 0.03f else if (isDark) 0.07f else 0.05f
                    )
                )
            )
        )
    }
}

/**
 * 居中状态胶囊：绿点 + 「已投屏 · 623」，点击开设备详情。
 *
 * 失联橙点；转码准备期或「已投送但本轮零取流」（170435）时改 spinner +
 * 「连接中…」，避免手机显示「已投屏」却把进度冻结在 00:00。
 */
@Composable
internal fun CastStatusPill(
    shortName: String,
    castState: CastState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    connecting: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val (label, dotColor, spinner) = when {
        castState.isConnectionLost -> Triple(stringResource(R.string.pt_player_045), LostOrange, false)
        castState.isPreparing || connecting ->
            Triple(stringResource(R.string.pt_player_044), scheme.onSurfaceVariant, true)
        else -> Triple(stringResource(R.string.pt_player_043, shortName), ConnectedGreen, false)
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(scheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (spinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = dotColor
            )
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = stringResource(R.string.pt_player_054),
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** 当前曲目：居中大封面（20dp 圆角 + 轻阴影）+ 歌名 22sp + 歌手 14sp。 */
@Composable
internal fun CastNowPlaying(coverUri: Uri?, title: String, artist: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val coverSize = minOf(maxWidth, CoverMaxSize)
            val shape = RoundedCornerShape(CoverCorner)
            Box(
                modifier = Modifier
                    .size(coverSize)
                    .shadow(10.dp, shape)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (coverUri != null) {
                    AsyncImage(
                        model = coverUri,
                        contentDescription = stringResource(R.string.pt_player_014),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    DefaultCover(
                        modifier = Modifier.fillMaxSize(),
                        shape = shape,
                        glyph = DefaultCoverGlyph.MUSIC_NOTE
                    )
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            artist,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 进度：统一轨道 + 左右时间；无进度信息时禁用并显示「--:--／进度未知」。
 * 时间行按 [CastTrackInset] 内缩到轨道可视端点，避免文字贴栅格、轨道却内收。
 */
@Composable
internal fun CastProgressSection(
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
        CastTrackSlider(
            value = progressFrac,
            onValueChange = {
                onProgressChange(it)
                onSeekStart()
            },
            onValueChangeFinished = onSeekCommit,
            enabled = enabled && hasProgress,
            semanticLabel = stringResource(
                R.string.pt_player_053,
                formatTime(currentMs),
                formatTime(durationMs)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CastTrackInset),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (hasProgress) formatTime(currentMs) else stringResource(R.string.pt_player_055),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (hasProgress) formatTime(durationMs) else stringResource(R.string.pt_player_047),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 传输控制：两侧 56dp 浅底圆 + 中间 72dp 主色实心圆，间距统一。 */
@Composable
internal fun CastTransportRow(
    enabled: Boolean,
    isPlaying: Boolean,
    onPrev: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val sideBackground = scheme.surfaceVariant.copy(alpha = 0.65f)
    val sideTint = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f)
    val mainBackground = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.20f)
    val mainTint = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)
    ) {
        CastRoundButton(
            icon = ControlIcons.SkipPrevious,
            description = stringResource(R.string.pt_player_016),
            size = ControlIconSizes.ButtonLarge,
            iconSize = ControlIconSizes.IconRemote,
            containerColor = sideBackground,
            iconTint = sideTint,
            enabled = enabled,
            onClick = onPrev
        )
        CastRoundButton(
            icon = if (isPlaying) ControlIcons.Pause else ControlIcons.Play,
            description = stringResource(R.string.pt_player_022),
            size = ControlIconSizes.ButtonHero,
            iconSize = ControlIconSizes.IconRemoteHero,
            containerColor = mainBackground,
            iconTint = mainTint,
            enabled = enabled,
            onClick = onTogglePlay
        )
        CastRoundButton(
            icon = ControlIcons.SkipNext,
            description = stringResource(R.string.pt_player_017),
            size = ControlIconSizes.ButtonLarge,
            iconSize = ControlIconSizes.IconRemote,
            containerColor = sideBackground,
            iconTint = sideTint,
            enabled = enabled,
            onClick = onNext
        )
    }
}

@Composable
internal fun CastRoundButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    containerColor: Color,
    iconTint: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 投射音量：标题行（左音量图标 / 右 `16%`）+ 整宽统一轨道；图标仅作标识，不占轨道起点。
 * 标题行与进度时间行共用 [CastTrackInset] 内缩、共用 `labelMedium` 字号，两处标注
 * 与轨道端点的对齐关系完全一致（2026-11-06 真机反馈「音量数值与进度数字同大小」）。
 */
@Composable
internal fun CastVolumeSection(
    enabled: Boolean,
    volumeSupported: Boolean?,
    castVol: Float,
    volumePct: Int,
    onVolume: (Float) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // 标题改为图标后，文案保留给图标与滑块做无障碍播报
    val label = stringResource(R.string.pt_player_051)
    val pctText = stringResource(R.string.pt_player_035, volumePct)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CastTrackInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = ControlIcons.Volume,
                contentDescription = label,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                pctText,
                // 与 CastProgressSection 的时间数字同字号，避免两条轨道标注一大一小
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }
        CastTrackSlider(
            value = castVol,
            onValueChange = onVolume,
            enabled = enabled,
            semanticLabel = "$label $pctText",
            modifier = Modifier.fillMaxWidth()
        )
        if (volumeSupported == false) {
            Text(
                stringResource(R.string.pt_player_036),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )
        }
    }
}

/** 结束投屏：贴底的浅描边胶囊，非 error 红、非实心，避免刺眼与误触。 */
@Composable
internal fun CastDisconnectButton(onDisconnect: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DisconnectHeight)
            .clip(CircleShape)
            .border(1.dp, scheme.outlineVariant, CircleShape)
            .clickable(role = Role.Button, onClick = onDisconnect),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.pt_player_046),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

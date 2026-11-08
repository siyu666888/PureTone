package com.puretone.player.ui.screens

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.puretone.player.ui.cast.CastDeviceLabel
import com.puretone.player.ui.cast.CastDeviceSheet
import com.puretone.player.ui.components.CastTrackInset
import com.puretone.player.ui.components.CastTrackSlider
import com.puretone.player.ui.components.ControlIcons
import com.puretone.player.ui.components.DefaultCover
import com.puretone.player.ui.components.DefaultCoverGlyph
import com.puretone.player.ui.components.formatTime
import kotlin.math.roundToInt

/**
 * 投屏遥控器面板（手机端）。
 *
 * 用户把音视频投射到大屏后，播放页正文整体替换为该面板——手机只承担远程控制：
 * 状态胶囊、当前曲目、可拖进度条、上/下一首、播放/暂停、投射音量、结束投屏。
 *
 * 布局约定（v1.9.4 重设计）：
 * - **单一 24dp 栅格**：状态胶囊、封面、歌名、两条轨道、结束投屏按钮的左右边界
 *   落在同一条线上；音量标题以图标呈现，轨道仍整宽，不因图标内缩。
 *   **轨道标注例外**：进度左右时间、音量图标与百分比按 [CastTrackInset] 向内缩进，
 *   落在 M3 Slider 轨道的可视端点上（2026-11-06 真机反馈「数值与进度条对齐」）。
 * - **对称**：封面 / 歌名 / 歌手居中作视觉焦点，其余控件全宽贴栅格。
 * - **弹性竖向节奏**：内容区 `weight(1f)` + 上下弹性留白把剩余高度均分，
 *   结束投屏按钮固定在底部安全区上方，消除「头重脚轻 + 下方 1/3 死白」。
 * - **竖向微调（v1.9.4 真机反馈）**：2026-11-06 二次反馈把内容组整体**下移一行**
 *   回到栅格原位（即撤掉内容组原有的 `offset(y = -LineStep)`），胶囊仍相对内容组
 *   上移一行 [LineStep]，结束投屏按钮**绝对不动**（[EndCastLift] 保持不变）。
 *   按钮之所以必须用真实 bottom padding 而不是 `offset`：后者会把绘制区连同点击区
 *   一起挪进上半内容区，后绘制的按钮会截获「下一首 / 音量」的点击，被当成
 *   「结束投屏」（19:56 真机 `cast-resume source=ui-disconnect`）。代价是按钮测量
 *   高度增加 48dp、`weight(1f)` 的内容区相应少 48dp，且内容下移后与按钮间距收窄
 *   一行——这是「不误触 + 按钮不动」优先于「多留弹性空白」的刻意取舍，小屏需以
 *   真机效果为准。紧凑可滚动屏内容本就贴顶，故该模式胶囊、按钮均不位移。
 * - **统一「圆」语言**：胶囊 999dp、封面 20dp、轨道 5dp 全圆角、三键圆形容器、圆形手柄。
 *
 * 交互逻辑（与旧版一致，重设计只换皮）：
 * - 全部控制入口由外部回调注入，投屏态下这些调用已由
 *   [com.puretone.player.ui.PlayerViewModel] 路由到 DLNA 渲染器；
 * - 播放/暂停图标与取反基准读同一份 uiState 展示态（本地恒暂停），不再直读 1Hz
 *   的渲染器瞬时值，避免状态抖动把「点播放」翻译成 Pause；
 * - 进度/音量各维护一份本地态，经 `LaunchedEffect` 与 [CastState] 双向同步，
 *   拖动期间以本地为准、结束后才下发，避免与 1Hz 轮询打架。
 */
private val GridPadding = 24.dp
/** 竖向微调的「一行」：与左右栅格同宽；现在只用于胶囊的相对位移与按钮上移量 */
private val LineStep = 24.dp
/**
 * 「结束投屏」按钮相对底部安全区的上移两行。内容组 2026-11-06 二次反馈已回正，
 * 按钮本身**不动**（用户明确要求「除结束投屏外整体下移一行」），故这里保持原值。
 * 以真实 bottom padding 实现，不用 Modifier.offset——后者会让按钮绘制区与点击区
 * 一起侵入上半内容区，覆盖音量/传输控件造成误触。
 */
private val EndCastLift = LineStep * 2f
private val CoverCorner = 20.dp
private val CoverMaxSize = 200.dp
private val DisconnectHeight = 48.dp
/** 面板可用高度低于此值时走「紧凑可滚动」，否则走「上下对称撑开」 */
private val CompactThreshold = 660.dp
private val ConnectedGreen = Color(0xFF2EAF6B)
private val LostOrange = Color(0xFFE8912A)

@Composable
fun CastRemoteControlPanel(
    castState: CastState,
    /** 与播放页/迷你条/通知栏同源的播放态（命令即时写入 + 稳定化轮询镜像） */
    isPlaying: Boolean,
    /** 与播放页/迷你条同源的展示进度（已镜像电视轮询并做 250ms 外推） */
    positionMs: Long,
    /** 展示时长：渲染器不报 TrackDuration 时由本地已知时长兜底 */
    durationMs: Long,
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
    onPickDevice: () -> Unit,
    modifier: Modifier = Modifier,
    /** 编排层提示（如「投屏中断，已自动重连」）：复用下方已有的错误展示位，不新增控件 */
    notice: String? = null
) {
    // 进度来源必须是全 App 那一份 uiState，不能直接用 castState.progressMs：
    // 后者是 1Hz 原始轮询值，既没有两次轮询之间的外推，也不受拖动后的乐观窗口保护
    // （Seek 是异步的，头几帧回读仍是旧位置，会把刚拖到的位置拉回去）。
    // 同理 castState.durationMs 在这台真机上恒为 0（只回 RelTime、不回 TrackDuration），
    // 拿它当总时长会让进度条整个禁用、时间恒显「--:--」。
    // 「有无刻度」也只看这份已兜底的 durationMs：castState.hasProgressInfo 在轮询静默
    // 或换曲窗口会变 false，拿它禁用进度条会让遥控页显示「进度未知」，而迷你条、
    // 播放页、通知栏仍拿着同一份 uiState 正常走——三处进度就是这么对不上的。
    val hasProgress = durationMs > 0L
    val controlsEnabled = castState.isCasting && !castState.isConnectionLost

    // 本地态与展示进度双向同步，拖动期间以本地为准避免抖动
    var seeking by remember { mutableStateOf(false) }
    var progressFrac by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(positionMs, durationMs, hasProgress) {
        if (!seeking && hasProgress) {
            progressFrac = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
    }

    // 音量：本地态与 castState.castVolume 双向同步
    var castVol by remember { mutableFloatStateOf((castState.castVolume / 100f).coerceIn(0f, 1f)) }
    LaunchedEffect(castState.castVolume) { castVol = (castState.castVolume / 100f).coerceIn(0f, 1f) }

    var showDeviceSheet by remember { mutableStateOf(false) }
    val currentMs = (progressFrac * durationMs).toLong()
    val volumePct = (castVol * 100).roundToInt()
    val shortName = CastDeviceLabel.short(castState.deviceName, stringResource(R.string.pt_player_004))

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 有界高度时用上下 Spacer 把整组内容对称居中，消除「头重脚轻 + 下方死白」；
        // 紧凑屏（分屏 / 小屏）才退化为可滚动，避免内容被裁剪。
        // verticalScroll 会让子 Column 以无限高度测量，此时 weight / SpaceEvenly
        // 都不生效，因此两种布局模式必须互斥，不能同时挂上。
        val compact = maxHeight < CompactThreshold
        val scrollState = rememberScrollState()
        CastBackdrop(coverUri = coverUri)
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 内容组回正到栅格原位（较上一版整体下移一行）。非紧凑屏不再挂
                    // offset：紧凑屏要保留 verticalScroll，两种模式依旧互斥。
                    .then(if (compact) Modifier.verticalScroll(scrollState) else Modifier)
                    .padding(horizontal = GridPadding, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)
            ) {
                if (!compact) Spacer(Modifier.weight(1f))
                CastStatusPill(
                    shortName = shortName,
                    castState = castState,
                    onClick = { showDeviceSheet = true },
                    // 胶囊相对内容组再上一行；内容组回正后它只比其余内容高一行
                    modifier = if (compact) Modifier else Modifier.offset(y = -LineStep)
                )

                CastNowPlaying(coverUri = coverUri, title = title, artist = artist)

                CastProgressSection(
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

                CastTransportRow(
                    enabled = controlsEnabled,
                    isPlaying = isPlaying,
                    onPrev = onPrev,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext
                )

                CastVolumeSection(
                    enabled = controlsEnabled && castState.volumeSupported != false,
                    volumeSupported = castState.volumeSupported,
                    castVol = castVol,
                    volumePct = volumePct,
                    onVolume = { onVolume((it * 100).roundToInt()) }
                )

                (notice ?: castState.error)?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!compact) Spacer(Modifier.weight(1f))
            }

            // 上移必须用「真实布局间距」而不是 Modifier.offset：offset 只改绘制位置、
            // 布局占位仍留在底部，后绘制的按钮会盖住上半 Column 末尾的音量/传输控件，
            // 点「下一首」时可能被按钮截获成「结束投屏」（2026-11-06 19:56 真机诊断：
            // 切歌后 25s 出现 cast-resume source=ui-disconnect，而链路全程无失联日志）。
            // 用 padding 把按钮连同点击区一起上移，与上半 Column 不重叠，视觉位移不变。
            // 2026-11-06 二次反馈只下移内容组，这里的 EndCastLift 保持原值、按钮绝对不动。
            CastDisconnectButton(
                onDisconnect = onDisconnect,
                modifier = Modifier
                    .padding(horizontal = GridPadding)
                    .padding(bottom = 16.dp + if (compact) 0.dp else EndCastLift)
            )
        }
    }

    if (showDeviceSheet) {
        CastDeviceSheet(
            castState = castState,
            onDismiss = { showDeviceSheet = false },
            onPickDevice = {
                showDeviceSheet = false
                onPickDevice()
            }
        )
    }
}

/**
 * 背景：封面放大高斯模糊铺底 + 顶部 surface 渐变 + 下半屏极淡主色渐变。
 *
 * `Modifier.blur` 仅在 API 31+ 生效，低版本若照样画会显示一张清晰的放大封面，
 * 因此低版本退化为径向柔光。AMOLED 纯黑主题关闭模糊层，避免脏灰底破坏省电效果。
 */
@Composable
private fun CastBackdrop(coverUri: Uri?) {
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

/** 居中状态胶囊：绿点 + 「已投屏 · 623」，点击开设备详情；失联橙点、连接中 spinner。 */
@Composable
private fun CastStatusPill(
    shortName: String,
    castState: CastState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val (label, dotColor, spinner) = when {
        castState.isConnectionLost -> Triple(stringResource(R.string.pt_player_045), LostOrange, false)
        castState.isPreparing -> Triple(stringResource(R.string.pt_player_044), scheme.onSurfaceVariant, true)
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
private fun CastNowPlaying(coverUri: Uri?, title: String, artist: String) {
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
private fun CastProgressSection(
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
private fun CastTransportRow(
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
            size = 56.dp,
            iconSize = 30.dp,
            containerColor = sideBackground,
            iconTint = sideTint,
            enabled = enabled,
            onClick = onPrev
        )
        CastRoundButton(
            icon = if (isPlaying) ControlIcons.Pause else ControlIcons.Play,
            description = stringResource(R.string.pt_player_022),
            size = 72.dp,
            iconSize = 36.dp,
            containerColor = mainBackground,
            iconTint = mainTint,
            enabled = enabled,
            onClick = onTogglePlay
        )
        CastRoundButton(
            icon = ControlIcons.SkipNext,
            description = stringResource(R.string.pt_player_017),
            size = 56.dp,
            iconSize = 30.dp,
            containerColor = sideBackground,
            iconTint = sideTint,
            enabled = enabled,
            onClick = onNext
        )
    }
}

@Composable
private fun CastRoundButton(
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
private fun CastVolumeSection(
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
private fun CastDisconnectButton(onDisconnect: () -> Unit, modifier: Modifier = Modifier) {
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

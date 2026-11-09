package com.puretone.player.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.puretone.player.R
import com.puretone.player.playback.cast.CastEstablishUiPolicy
import com.puretone.player.playback.cast.CastState
import com.puretone.player.ui.cast.CastDeviceLabel
import com.puretone.player.ui.cast.CastDeviceSheet
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
 *   **轨道标注例外**：进度左右时间、音量图标与百分比按 [com.puretone.player.ui.components.CastTrackInset] 向内缩进，
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
/** 面板可用高度低于此值时走「紧凑可滚动」，否则走「上下对称撑开」 */
private val CompactThreshold = 660.dp

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
    val controlsEnabled = castState.isCasting && !castState.isConnectionLost
    // 170435：未确认建立且本轮确实零取流时，编排层停写镜像与外推，positionMs 仍
    // 冻结在 0。此时不能拿它当「电视停在 00:00」，整条进度切成不确定态、胶囊显示
    // 连接中；取流一经确认（含 Play 返回前早到的首次 GET）下一帧自动恢复。
    // 传输键不跟着禁用——054751 真机靠用户手动点播放下发单独 Play 才在 1s 内救回。
    val awaitingEstablishment = CastEstablishUiPolicy.awaitingEstablishment(
        isCasting = castState.isCasting,
        isConnectionLost = castState.isConnectionLost,
        isEstablished = castState.isEstablished,
        lastFetchMs = castState.lastFetchMs
    )
    val hasProgress = durationMs > 0L && !awaitingEstablishment

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
                    connecting = awaitingEstablishment,
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

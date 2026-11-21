package com.puretone.player.ui.screens

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.R
import com.puretone.player.domain.report.PeriodType
import com.puretone.player.data.local.dao.TopSongRow
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.ReportFormat
import com.puretone.player.ui.ReportViewModel
import com.puretone.player.ui.components.BarChart
import com.puretone.player.ui.report.CalendarHeatmap
import com.puretone.player.ui.report.LocalReportPalette
import com.puretone.player.ui.report.PosterContent
import com.puretone.player.ui.report.PosterShare
import com.puretone.player.ui.report.ProvideReportPalette
import com.puretone.player.ui.report.ReportGradients
import com.puretone.player.ui.report.SpectrumAreaChart
import com.puretone.player.ui.report.TimeSegmentBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 听歌报告页（需求4 阶段2 周报 + 阶段3 月报/年报 + V2「声色叙事」改造）。
 *
 * 通过 [ReportFormat.toPosterContent] 一次性产出全部本地化文案，屏幕展示与分享海报
 * 共用同一份 [PosterContent]（趋势柱、时段条、热力格也共用），保证口径与措辞完全一致。
 * V2 改造：周/月切换新图表（面积折线 / 日历热力 / 时段条）与叙事光谱配色，
 * 年报保留 V1 柱状骨架不动。配色由 [ProvideReportPalette] 按海报主题下发，
 * 海报渲染器（PosterRenderer）消费同一份 [com.puretone.player.ui.report.ReportPalette] 令牌。
 *
 * 隐私开关关闭时仍可查看存量，但禁用「生成分享海报」并说明原因。
 * 复用根部下发的 [playerVm]（与 [StatsScreen] 同模式），避免重复恢复播放会话打断播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    navController: NavHostController,
    vm: ReportViewModel = hiltViewModel(),
    playerVm: PlayerViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 报告主题自动跟随 App 实际生效主题（SYSTEM 跟随系统深色 / 纯黑由 AMOLED 或 BLACK 触发），
    // 不再提供手动切换；屏幕与分享海报共用同一派生结果。
    val systemDark = isSystemInDarkTheme()
    val theme = ReportFormat.effectivePosterTheme(state.themeMode, systemDark, state.amoledBlack)

    // 屏幕与海报共用同一份本地化文案
    val content = remember(state.data) {
        state.data?.let { ReportFormat.toPosterContent(context, it) }
    }

    val canShare = state.statsEnabled && content != null && state.data?.isEmpty == false

    ProvideReportPalette(theme = theme) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.pt_report_title)) },
                    windowInsets = WindowInsets(0),
                    navigationIcon = {
                        IconButton(onClick = navController::popBackStack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.pt_stats_002))
                        }
                    },
                    actions = {
                        TextButton(onClick = vm::refresh) { Text(stringResource(R.string.pt_report_refresh)) }
                    }
                )
            }
        ) { padding ->
            when {
                state.loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
                content == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            ReportFormat.formatEmpty(context, state.period),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    val c = content
                    val topSongs = state.data?.topSongs.orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PeriodSelector(selected = state.period, onSelect = vm::setPeriod)
                        if (!state.statsEnabled) {
                            ReportPrivacyBanner()
                        }
                        ReportHero(content = c)
                        ReportTrendCard(content = c)
                        if (c.timeSegments.isNotEmpty()) {
                            ReportTimeCard(content = c)
                        }
                        if (c.calendarCells.isNotEmpty()) {
                            ReportHeatCard(content = c)
                        }
                        ReportCapsules(content = c)
                        if (topSongs.isNotEmpty()) {
                            ReportTopSongs(content = c, songs = topSongs)
                        }
                        if (c.goldenText.isNotBlank()) {
                            ReportGolden(content = c)
                        } else if (c.funTag.isNotBlank()) {
                            ReportFunTag(content = c)
                        }

                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        PosterShare.share(context, c, theme)
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        Toast.makeText(
                                            context,
                                            e.message ?: context.getString(R.string.pt_report_share_title),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            enabled = canShare,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.pt_report_share))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 周期切换：周报 / 月报 / 年报。直接接 [ReportViewModel.setPeriod]。
 */
@Composable
private fun PeriodSelector(selected: PeriodType, onSelect: (PeriodType) -> Unit) {
    val options = listOf(
        PeriodType.WEEK to R.string.pt_report_tab_week,
        PeriodType.MONTH to R.string.pt_report_tab_month,
        PeriodType.YEAR to R.string.pt_report_tab_year
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (type, labelRes) ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(stringResource(labelRes)) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 隐私关闭横幅：仍可见存量，但分享被禁用 */
@Composable
private fun ReportPrivacyBanner() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.pt_report_privacy_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color(LocalReportPalette.current.brand)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.pt_report_privacy_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 主数字区：周期角标 + 引导语 + 渐变听歌时长 + 绝对环比差 + 日均/连续注记 */
@Composable
private fun ReportHero(content: PosterContent) {
    val p = LocalReportPalette.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                content.periodBadge,
                style = MaterialTheme.typography.labelMedium,
                color = Color(p.brand)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                content.leadText,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(p.ink2)
            )
            Spacer(Modifier.height(4.dp))
            // 全卡唯一渐变文字：主数字，承担「惊叹」职责
            Text(
                content.mainNumber,
                style = MaterialTheme.typography.displaySmall.copy(brush = ReportGradients.aurora(p)),
                color = Color.Unspecified
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    content.rangeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(p.ink2)
                )
                if (content.deltaAbsoluteText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        content.deltaAbsoluteText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(p.spectrum4)
                    )
                } else if (content.deltaText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        content.deltaText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(p.spectrum4)
                    )
                }
            }
            if (content.estimateNote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    content.estimateNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(p.ink3)
                )
            }
            if (content.dailyAverageText.isNotBlank() || content.longestStreakText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    listOf(content.dailyAverageText, content.longestStreakText)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(p.ink2)
                )
            }
        }
    }
}

/**
 * 趋势图：周 7 日面积折线 / 月 W1…W5 光谱柱 / 年 12 月柱（V1 保留）。
 * 序列与标签统一取自 [PosterContent.trendBuckets]，与分享海报同口径。
 */
@Composable
private fun ReportTrendCard(content: PosterContent) {
    val p = LocalReportPalette.current
    val buckets = content.trendBuckets
    val peakIdx = buckets.withIndex()
        .filter { it.value.listenMs > 0 }
        .maxByOrNull { it.value.listenMs }?.index ?: -1
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                content.trendTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color(p.brand)
            )
            Spacer(Modifier.height(12.dp))
            if (buckets.all { it.listenMs == 0L }) {
                Text(
                    stringResource(R.string.pt_report_trend_empty),
                    color = Color(p.ink3)
                )
            } else {
                when (content.data.type) {
                    PeriodType.WEEK -> SpectrumAreaChart(
                        buckets = buckets,
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                    PeriodType.MONTH -> BarChart(
                        values = buckets.map { it.listenMs },
                        highlightIndex = peakIdx,
                        barColor = Color(p.spectrum2),
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                    PeriodType.YEAR -> BarChart(
                        values = buckets.map { it.listenMs },
                        highlightIndex = peakIdx,
                        barColor = Color(p.brand),
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    buckets.forEach { bucket ->
                        Text(
                            bucket.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(p.ink3),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 时段分布条（V2 新增）：一条 4 段比例条，宽度即占比，峰值注记在最右 */
@Composable
private fun ReportTimeCard(content: PosterContent) {
    val p = LocalReportPalette.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    content.timeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(p.brand)
                )
                if (content.timePeakText.isNotBlank()) {
                    Text(
                        content.timePeakText,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(p.ink3)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            TimeSegmentBar(segments = content.timeSegments)
        }
    }
}

/** 声音日历（月报专属）：7 列热力网格 + 色阶说明 */
@Composable
private fun ReportHeatCard(content: PosterContent) {
    val p = LocalReportPalette.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    content.heatTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(p.brand)
                )
                Text(
                    content.heatHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(p.ink3)
                )
            }
            Spacer(Modifier.height(12.dp))
            CalendarHeatmap(
                cells = content.calendarCells,
                modifier = Modifier.fillMaxWidth().height(150.dp)
            )
        }
    }
}

/** 三个胶囊：播放次数 / 收听歌曲 / 活跃天数 */
@Composable
private fun ReportCapsules(content: PosterContent) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Capsule(content.capsulePlays, Modifier.weight(1f))
        Capsule(content.capsuleSongs, Modifier.weight(1f))
        Capsule(content.capsuleActiveDays, Modifier.weight(1f))
    }
}

@Composable
private fun Capsule(value: String, modifier: Modifier = Modifier) {
    val p = LocalReportPalette.current
    Card(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = Color(p.spectrum4))
        }
    }
}

/** Top 歌曲列表 */
@Composable
private fun ReportTopSongs(content: PosterContent, songs: List<TopSongRow>) {
    val p = LocalReportPalette.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                content.topSongsTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color(p.brand)
            )
            Spacer(Modifier.height(8.dp))
            songs.forEachIndexed { i, t ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${i + 1}.",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(p.spectrum4),
                        modifier = Modifier.width(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${t.artist} · ${t.playCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(p.ink2),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 行为金句（V2）：指向行为而非数量，洋红左边线 */
@Composable
private fun ReportGolden(content: PosterContent) {
    val p = LocalReportPalette.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(modifier = Modifier.width(3.dp).height(40.dp)) {
                drawRect(Color(p.spectrum3))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                content.goldenText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(p.ink)
            )
        }
    }
}

/** 趣味标签（年报 V1 保留） */
@Composable
private fun ReportFunTag(content: PosterContent) {
    val p = LocalReportPalette.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                content.funTagTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color(p.brand)
            )
            content.funTag.split("\n").forEach { line ->
                if (line.isNotBlank()) Text(line)
            }
        }
    }
}

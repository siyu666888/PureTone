package com.puretone.player.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.R
import com.puretone.player.domain.report.ReportData
import com.puretone.player.data.local.dao.TopSongRow
import com.puretone.player.ui.PlayerViewModel
import com.puretone.player.ui.ReportFormat
import com.puretone.player.ui.ReportViewModel
import com.puretone.player.ui.components.BarChart
import com.puretone.player.ui.report.PosterContent
import com.puretone.player.ui.report.PosterShare
import com.puretone.player.ui.report.PosterTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 听歌报告页（需求4 阶段2）：周报 + 分享海报。
 *
 * 通过 [ReportFormat.toPosterContent] 一次性产出全部本地化文案，屏幕展示与分享海报
 * 共用同一份 [PosterContent]，保证口径与措辞完全一致。隐私开关（[ReportViewModel]
 * 的 [ReportUiState.statsEnabled]）关闭时仍可查看存量，但禁用「生成分享海报」并说明原因。
 *
 * 复用根部下发的 [playerVm]（与 [StatsScreen] 同模式），避免重复恢复播放会话打断播放。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(
    navController: NavHostController,
    vm: ReportViewModel = hiltViewModel(),
    playerVm: PlayerViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 海报主题：默认跟随 App 当前主题（含 BLACK），用户可临时切换
    var theme by remember(state.themeMode) {
        mutableStateOf(ReportFormat.defaultPosterTheme(state.themeMode))
    }

    // 屏幕与海报共用同一份本地化文案
    val content = remember(state.data) {
        state.data?.let { ReportFormat.toPosterContent(context, it) }
    }

    val canShare = state.statsEnabled && content != null && !state.data!!.isEmpty

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
                        stringResource(R.string.pt_report_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                val c = content
                val data = state.data!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (!state.statsEnabled) {
                        ReportPrivacyBanner()
                    }
                    ReportHero(content = c)
                    ReportTrendCard(content = c, data = data)
                    ReportCapsules(content = c)
                    if (data.topSongs.isNotEmpty()) {
                        ReportTopSongs(content = c, songs = data.topSongs)
                    }
                    if (c.funTag.isNotBlank()) {
                        ReportFunTag(content = c)
                    }

                    // 海报主题选择
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.pt_report_theme_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.pt_report_theme_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            ThemeSelector(theme = theme, onTheme = { theme = it })
                        }
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

/** 隐私关闭横幅：仍可见存量，但分享被禁用 */
@Composable
private fun ReportPrivacyBanner() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.pt_report_privacy_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
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

/** 主数字区：周期角标 + 引导语 + 听歌时长 + 范围/环比 + 估算注记 */
@Composable
private fun ReportHero(content: PosterContent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                content.periodBadge,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                content.leadText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                content.mainNumber,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    content.rangeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    content.deltaText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (content.estimateNote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    content.estimateNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 每日听歌趋势（周报即 7 根柱 + 星期标签，高亮峰值） */
@Composable
private fun ReportTrendCard(content: PosterContent, data: ReportData) {
    val peakIdx = data.dailyTrend.withIndex()
        .filter { it.value.listenMs > 0 }
        .maxByOrNull { it.value.listenMs }?.index ?: -1
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                content.trendTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            if (data.dailyTrend.all { it.listenMs == 0L }) {
                Text(
                    stringResource(R.string.pt_report_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                BarChart(
                    values = data.dailyTrend.map { it.listenMs },
                    highlightIndex = peakIdx,
                    barColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    data.dailyTrend.forEach { row ->
                        Text(
                            weekdayLabel(row.dayKey),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
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
    Card(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Top 歌曲列表 */
@Composable
private fun ReportTopSongs(content: PosterContent, songs: List<TopSongRow>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                content.topSongsTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
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
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${t.artist} · ${t.playCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 趣味标签 */
@Composable
private fun ReportFunTag(content: PosterContent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                content.funTagTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content.funTag.split("\n").forEach { line ->
                if (line.isNotBlank()) Text(line)
            }
        }
    }
}

/** 海报主题选择（浅色 / 深色 / 纯黑） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSelector(theme: PosterTheme, onTheme: (PosterTheme) -> Unit) {
    val options = listOf(
        PosterTheme.LIGHT to R.string.pt_report_theme_light,
        PosterTheme.DARK to R.string.pt_report_theme_dark,
        PosterTheme.BLACK to R.string.pt_report_theme_black
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (t, labelRes) ->
            FilterChip(
                selected = theme == t,
                onClick = { onTheme(t) },
                label = { Text(stringResource(labelRes)) }
            )
        }
    }
}

/** day_key(yyyy-MM-dd) → 星期标签（周一..周日） */
private fun weekdayLabel(dayKey: String): String = runCatching {
    LocalDate.parse(dayKey).format(DateTimeFormatter.ofPattern("EEE", Locale.CHINA))
}.getOrDefault("")

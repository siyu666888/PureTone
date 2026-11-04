package com.puretone.player.ui.screens
import androidx.compose.ui.res.stringResource
import com.puretone.player.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.ui.PlayerViewModel
import kotlinx.coroutines.launch
import com.puretone.player.ui.Routes
import com.puretone.player.ui.StatsViewModel
import com.puretone.player.ui.components.BarChart

/**
 * 听歌统计看板（Item 6）：总览卡片 + 每日听歌柱状图 + 时段分布 + 最爱歌曲/歌手 + 趣味标签。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    navController: NavHostController,
    vm: StatsViewModel = hiltViewModel(),
    // 由 PureToneApp 下发根部实例：自己 hiltViewModel() 会拿到目的地作用域的新实例，
    // 其 init 再跑一次「恢复上次会话」→ 以暂停态重建队列，打断正在播放
    playerVm: PlayerViewModel = hiltViewModel()
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showClear by remember { mutableStateOf(false) }

    Scaffold(
        // P1-7：inset 由外层 Scaffold 统一负责，内层不重复叠加
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pt_stats_001)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.pt_stats_002))
                    }
                },
                actions = {
                    TextButton(onClick = { showClear = true }) { Text(stringResource(R.string.pt_stats_003)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 总览卡片
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.pt_stats_004), formatDuration(s.totalListenMs), Modifier.weight(1f))
                    StatCard(
                        stringResource(R.string.pt_stats_005),
                        stringResource(R.string.pt_stats_006, s.totalPlays),
                        Modifier.weight(1f)
                    )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        stringResource(R.string.pt_stats_007),
                        stringResource(R.string.pt_stats_008, s.playedSongs),
                        Modifier.weight(1f)
                    )
                // P2-6：活跃天数口径为近 30 天，标题标注清楚
                    StatCard(
                        stringResource(R.string.pt_stats_009),
                        stringResource(R.string.pt_stats_010, s.activeDays),
                        Modifier.weight(1f)
                    )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // P2-6：日均时长口径为近 30 天，标题标注清楚
                StatCard(stringResource(R.string.pt_stats_011), formatDuration(s.avgListenMsPerActiveDay), Modifier.weight(1f))
                StatCard(
                    stringResource(R.string.pt_stats_012),
                    if (s.peakHour >= 0) "${s.peakHour}:00" else "—",
                    Modifier.weight(1f)
                )
            }

            // 每日听歌趋势（近 30 天柱状图，取有数据的最近 14 天展示）
            val trend = s.dailyTrend.takeLast(14)
            // P2-6：高亮真正峰值（听歌时长最大的一天），而非「最后一个有播放的日期」
            val peakIdx = trend.withIndex()
                .filter { it.value.listenMs > 0 }
                .maxByOrNull { it.value.listenMs }?.index ?: -1
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.pt_stats_013, trend.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    if (trend.all { it.listenMs == 0L }) {
                            Text(
                                stringResource(R.string.pt_stats_014, trend.size),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                    } else {
                        BarChart(
                            values = trend.map { it.listenMs },
                            highlightIndex = peakIdx,
                            barColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                trend.first().dayKey.takeLast(5),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                trend.last().dayKey.takeLast(5),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 时段分布（0-23 时）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.pt_stats_015),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    Spacer(Modifier.height(12.dp))
                    BarChart(
                        values = s.hourPlays.map { it.second },
                        highlightIndex = s.peakHour,
                        barColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.fillMaxWidth().height(90.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(0, 6, 12, 18, 23).forEach { h ->
                            Text(
                                stringResource(R.string.pt_stats_016, h),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 最爱歌手（点击跳转到该歌手分组）
            if (s.topArtists.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.pt_stats_017),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        Spacer(Modifier.height(8.dp))
                        s.topArtists.forEachIndexed { i, a ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navController.navigate(Routes.groupDetail("artist", a.name)) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${i + 1}. ${a.name}",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                    Text(
                                        stringResource(R.string.pt_stats_018, a.playCount),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                Text(" ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 最爱歌曲（点击播放该歌曲并进入播放页）
            if (s.topSongs.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.pt_stats_019),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        Spacer(Modifier.height(8.dp))
                        s.topSongs.forEachIndexed { i, t ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch { playerVm.playSongById(t.id) }
                                        // P3-27：launchSingleTop，避免重复压入播放页
                                        navController.navigate(Routes.PLAYER) { launchSingleTop = true }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${i + 1}. ${t.title}",
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    stringResource(R.string.pt_stats_020, t.artist, t.playCount),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Text(" ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 趣味标签
            if (s.totalPlays > 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(R.string.pt_stats_021),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        val favArtist = s.topArtists.firstOrNull()?.name
                        if (favArtist != null) Text(stringResource(R.string.pt_stats_022, favArtist))
                        if (s.isNightOwl) Text(stringResource(R.string.pt_stats_023, s.peakHour))
                        else if (s.peakHour >= 0) Text(stringResource(R.string.pt_stats_024, s.peakHour))
                        Text(stringResource(R.string.pt_stats_025, s.activeDays))
                    }
                }
            } else {
                Text(
                    stringResource(R.string.pt_stats_026),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // P2-6：清空播放历史入口（删除全部播放记录与统计，不影响曲库与收藏）
    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text(stringResource(R.string.pt_stats_027)) },
            text = { Text(stringResource(R.string.pt_stats_028)) },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    vm.clearHistory()
                }) { Text(stringResource(R.string.pt_stats_029)) }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) { Text(stringResource(R.string.pt_stats_030)) }
            }
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 毫秒 → "X 小时 Y 分钟" / "Y 分钟" / "X 秒" */
@Composable
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return stringResource(R.string.pt_stats_dur_zero)
    val totalMin = ms / 60000
    return when {
        totalMin >= 60 -> stringResource(R.string.pt_stats_dur_hours, totalMin / 60, totalMin % 60)
        totalMin >= 1 -> stringResource(R.string.pt_stats_dur_min, totalMin)
        else -> stringResource(R.string.pt_stats_dur_sec, ms / 1000)
    }
}

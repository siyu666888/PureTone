package com.puretone.player.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.puretone.player.ui.StatsViewModel
import com.puretone.player.ui.components.formatTime

/**
 * 听歌统计看板（Item 6）：总览卡片 + 每日听歌柱状图 + 时段分布 + 最爱歌曲/歌手 + 趣味标签。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    navController: NavHostController,
    vm: StatsViewModel = hiltViewModel()
) {
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("听歌数据") },
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
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
                StatCard("累计时长", formatDuration(s.totalListenMs), Modifier.weight(1f))
                StatCard("总播放", "${s.totalPlays} 次", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("收听歌曲", "${s.playedSongs} 首", Modifier.weight(1f))
                StatCard("活跃天数", "${s.activeDays} 天", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("日均时长", formatDuration(s.avgListenMsPerActiveDay), Modifier.weight(1f))
                StatCard(
                    "最勤时段",
                    if (s.peakHour >= 0) "${s.peakHour}:00" else "—",
                    Modifier.weight(1f)
                )
            }

            // 每日听歌趋势（近 30 天柱状图，取有数据的最近 14 天展示）
            val trend = s.dailyTrend.takeLast(14)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("每日听歌（近 ${trend.size} 天）", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    if (trend.all { it.listenMs == 0L }) {
                        Text("近 ${trend.size} 天还没有听歌记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        BarChart(
                            values = trend.map { it.listenMs },
                            highlightIndex = trend.indexOfLast { it.plays > 0 }.takeIf { it >= 0 } ?: -1,
                            barColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(trend.first().dayKey.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(trend.last().dayKey.takeLast(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 时段分布（0-23 时）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("时段分布", style = MaterialTheme.typography.titleMedium)
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
                            Text("${h}时", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 最爱歌手
            if (s.topArtists.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("最爱歌手", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        s.topArtists.forEachIndexed { i, a ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${i + 1}. ${a.name}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${a.playCount} 次", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // 最爱歌曲
            if (s.topSongs.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("最爱歌曲", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        s.topSongs.forEachIndexed { i, t ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("${i + 1}. ${t.title}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${t.artist} · ${t.playCount} 次", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // 趣味标签
            if (s.totalPlays > 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("趣味标签", style = MaterialTheme.typography.titleMedium)
                        val favArtist = s.topArtists.firstOrNull()?.name
                        if (favArtist != null) Text("🎤 最爱歌手：${favArtist}")
                        if (s.isNightOwl) Text("🌙 深夜听众：最爱在 ${s.peakHour}:00 听歌")
                        else if (s.peakHour >= 0) Text("☀️ 日常听众：高频时段 ${s.peakHour}:00")
                        Text("📅 近 30 天有 ${s.activeDays} 天在听歌")
                    }
                }
            } else {
                Text(
                    "还没有听歌数据，去播放几首歌后再来看看吧～",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 简易柱状图（Canvas，GPU 加速）；[highlightIndex] 高亮当前/峰值柱 */
@Composable
private fun BarChart(
    values: List<Long>,
    barColor: Color,
    modifier: Modifier = Modifier,
    highlightIndex: Int = -1
) {
    if (values.isEmpty()) return
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    Canvas(modifier = modifier) {
        val n = values.size
        val gap = 3.dp.toPx()
        val slot = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        values.forEachIndexed { i, v ->
            val h = (v.toFloat() / max) * (size.height * 0.92f)
            val left = i * (slot + gap)
            drawRect(
                color = if (i == highlightIndex) barColor else barColor.copy(alpha = 0.45f),
                topLeft = Offset(left, size.height - h),
                size = Size(slot, h.coerceAtLeast(if (v > 0) 2.dp.toPx() else 0f))
            )
        }
    }
}

/** 毫秒 → "X 小时 Y 分钟" / "Y 分钟" / "X 秒" */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0 分钟"
    val totalMin = ms / 60000
    return when {
        totalMin >= 60 -> "${totalMin / 60} 小时 ${totalMin % 60} 分"
        totalMin >= 1 -> "$totalMin 分钟"
        else -> "${ms / 1000} 秒"
    }
}

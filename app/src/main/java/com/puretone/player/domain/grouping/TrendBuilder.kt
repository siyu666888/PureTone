package com.puretone.player.domain.grouping

import com.puretone.player.data.local.dao.DailyTrendRow
import java.util.Calendar

/**
 * 统计趋势纯函数（domain 层）。
 *
 * 旧实现把 buildFullTrend / dayKey 写在 [com.puretone.player.ui.StatsViewModel]
 * 的 companion object 里，纯时间算法被绑在 UI 层、无法纯 JVM 单测（纯 Kotlin
 * 逻辑却要跟着 VM 一起跑）。抽到 domain 后：
 * - VM 只负责「observe* → 调本文件 → stateIn」，趋势补全算法不再内含于 VM；
 * - 可纯 JVM 单测（见 TrendBuilderTest），不依赖数据库/上下文。
 *
 * 口径（与历史行为一致，勿随意改动）：
 * - 把稀疏的每日播放记录补全为连续 [TREND_DAYS] 天序列，缺失日 listenMs/plays=0；
 * - 自 sinceMs 当天 0 点开始逐日生成 key，直到今天（含），上限 400 天兜底；
 * - dayKey 格式 "%04d-%02d-%02d"。
 */
const val TREND_DAYS = 30

/** 单日听歌桶（用于趋势图，缺失日补 0） */
data class DayBucket(
    val dayKey: String,
    val listenMs: Long,
    val plays: Long
)

/**
 * 把稀疏的每日记录补全为连续 [TREND_DAYS] 天序列（缺失日 listenMs/plays=0）。
 *
 * @param rows 数据库查出的稀疏每日记录（可能缺天）
 * @param sinceMs 统计窗口起点时间戳（毫秒），从该天 0 点开始
 */
fun buildFullTrend(
    rows: List<DailyTrendRow>,
    sinceMs: Long
): List<DayBucket> {
    val byKey = rows.associateBy { it.dayKey }
    val start = Calendar.getInstance().apply { timeInMillis = sinceMs }
    start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0)
    start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val out = ArrayList<DayBucket>(TREND_DAYS)
    val cursor = start.clone() as Calendar
    while (!cursor.after(today) && out.size < 400) {
        val key = dayKey(cursor)
        val row = byKey[key]
        out += DayBucket(
            dayKey = key,
            listenMs = row?.listenMs ?: 0L,
            plays = row?.plays ?: 0L
        )
        cursor.add(Calendar.DAY_OF_YEAR, 1)
    }
    return out
}

/** 由 Calendar 生成 "YYYY-MM-DD" 形式的日 key */
fun dayKey(cal: Calendar): String {
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    return "%04d-%02d-%02d".format(y, m, d)
}

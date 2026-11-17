package com.puretone.player.ui.report

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.local.dao.DailyTrendRow
import com.puretone.player.data.local.dao.HourCountRow
import com.puretone.player.data.local.dao.NamedCountRow
import com.puretone.player.data.local.dao.TopSongRow
import com.puretone.player.domain.report.PeriodType
import com.puretone.player.domain.report.ReportData
import com.puretone.player.ui.ReportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 海报渲染纯函数测试（需求4 阶段2）。
 *
 * [PosterRenderer.render] 不依赖 Compose / Context，只吃 [PosterContent] + [PosterTheme]，
 * 故可在 Robolectric 下直接断言：尺寸固定 1080×1920、返回非空、三种主题均不抛异常。
 * [PosterContent] 经由 [ReportFormat.toPosterContent] 由真实资源组装，顺带覆盖文案拼接路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PosterRendererTest {

    private fun buildData(): ReportData {
        val trend = listOf(
            DailyTrendRow("2026-11-02", 120_000L, 2L),
            DailyTrendRow("2026-11-03", 30_000L, 1L),
            DailyTrendRow("2026-11-08", 0L, 0L)
        )
        return ReportData(
            type = PeriodType.WEEK,
            rangeStart = 0L,
            rangeEnd = 1L,
            listenMs = 150_000L,
            hasEstimate = true,
            plays = 3L,
            completed = 2L,
            distinctSongs = 2,
            activeDays = 2,
            dailyTrend = trend,
            topSongs = listOf(
                TopSongRow(1L, "SongA", "ArtistX", "Al", 2, null),
                TopSongRow(2L, "SongB", "ArtistY", "Al", 1, null)
            ),
            topArtists = listOf(
                NamedCountRow("ArtistX", 2L),
                NamedCountRow("ArtistY", 1L)
            ),
            hourDistribution = listOf(HourCountRow(12, 3L), HourCountRow(22, 1L)),
            prevListenMs = 10_000L,
            deltaPct = 1400.0
        )
    }

    @Test
    fun `render produces 1080x2016 bitmap for all themes without throwing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = buildData()
        val content = ReportFormat.toPosterContent(context, data)

        for (theme in PosterTheme.values()) {
            val bitmap = PosterRenderer.render(content, theme)
            assertNotNull("theme=$theme 应返回非空 Bitmap", bitmap)
            assertEquals(1080, bitmap.width)
            assertEquals(2016, bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        }
    }

    @Test
    fun `V2 周报内容含时段条与金句但无日历热力`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val content = ReportFormat.toPosterContent(context, buildData())
        assertEquals(4, content.timeSegments.size)
        assertTrue("周报应有行为金句", content.goldenText.isNotBlank())
        assertTrue("周报不铺日历热力", content.calendarCells.isEmpty())
        assertTrue("周报绝对差文案存在", content.deltaAbsoluteText.isNotBlank())
        // 三主题均能渲染（含 V2 新模块）
        PosterTheme.values().forEach { PosterRenderer.render(content, it) }
    }

    @Test
    fun `V2 月报内容含日历热力与日均连续`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val zone = java.time.ZoneId.systemDefault()
        val monthData = buildData().copy(
            type = PeriodType.MONTH,
            rangeStart = java.time.LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        )
        val content = ReportFormat.toPosterContent(context, monthData)
        assertEquals(30, content.calendarCells.size)
        assertTrue("月报应有日均", content.dailyAverageText.isNotBlank())
        assertTrue("月报应有最长连续", content.longestStreakText.isNotBlank())
        PosterTheme.values().forEach { PosterRenderer.render(content, it) }
    }

    @Test
    fun `年报维持V1骨架不渲染V2新模块`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val yearData = buildData().copy(type = PeriodType.YEAR)
        val content = ReportFormat.toPosterContent(context, yearData)
        assertTrue("年报无时段条", content.timeSegments.isEmpty())
        assertTrue("年报无日历热力", content.calendarCells.isEmpty())
        assertTrue("年报无行为金句（保留趣味标签）", content.goldenText.isEmpty())
        assertTrue("年报保留趣味标签", content.funTag.isNotBlank())
        PosterTheme.values().forEach { PosterRenderer.render(content, it) }
    }
}

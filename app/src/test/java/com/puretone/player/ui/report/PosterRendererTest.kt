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
    fun `render produces 1080x1920 bitmap for all themes without throwing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = buildData()
        val content = ReportFormat.toPosterContent(context, data)

        for (theme in PosterTheme.values()) {
            val bitmap = PosterRenderer.render(content, theme)
            assertNotNull("theme=$theme 应返回非空 Bitmap", bitmap)
            assertEquals(1080, bitmap.width)
            assertEquals(1920, bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        }
    }
}

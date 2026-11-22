package com.puretone.player.ui.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import com.puretone.player.domain.report.PeriodType
import com.puretone.player.domain.report.ReportTrend

/**
 * 听歌报告分享海报渲染器（V2「声色叙事」改造）。
 *
 * Android Canvas 直绘（[Bitmap] + [Canvas] + [Paint]），零 Compose 依赖、可在任意线程生成、
 * 可纯 JVM 单测（断言尺寸 / 不抛异常）。配色从 [ReportPalette] 取，与屏幕端同源。
 *
 * 画幅 1080×2016（设计稿 300×560 等比，≈9:16.8），安全区左右 72 / 上 72 / 下 96。
 * 输入 [PosterContent]（文案已本地化）+ [ReportTheme]，纯函数输出 [Bitmap]。
 *
 * V2 模块：主数字渐变、周报面积折线+时段条、月报日历热力+周柱+时段条、
 * 行为金句洋红左边线；年报保留 V1 柱榜骨架。页脚统一「数据仅存本机」。
 *
 * 内容子集（第七轮 B3）：海报是屏幕端 [PosterContent] 的紧凑子集——`dailyAverageText` /
 * `longestStreakText` 仅在屏幕 `ReportHero` 展示，海报不绘制也不预留空间（画幅上限所致，
 * A1 已把满配月报压到 1844/1848，再插块必溢出）。文案口径与屏幕同源，但字段非全渲染。
 */
object PosterRenderer {

    private const val W = 1080
    private const val H = 2016
    private const val MARGIN_X = 72
    private const val MARGIN_TOP = 72
    private const val MARGIN_BOTTOM = 96
    private val CONTENT_W = W - 2 * MARGIN_X

    /** 内容安全底边：页脚首行画在 H-MARGIN_BOTTOM-56，内容须在其上方留 16px 空隙 */
    internal val SAFE_BOTTOM: Int = H - MARGIN_BOTTOM - 56 - 16

    /** 单个布局块：纯整数坐标，可在无 Android 依赖下断言不越界、不重叠 */
    internal data class PosterBlock(val name: String, val top: Int, val bottom: Int)

    /**
     * 布局测量结果：每块位置 + 可压缩尺寸。
     * 全部为纯值，[totalHeight] / [fits] 可直接被单测断言。
     */
    internal data class PosterLayout(
        val blocks: List<PosterBlock>,
        val goldenLines: List<String>,
        val heatCell: Float,
        val heatGap: Float,
        val trendH: Int,
        val timeH: Int,
        val topRowH: Int,
        val topSongCount: Int
    ) {
        val totalHeight: Int get() = blocks.maxOfOrNull { it.bottom } ?: MARGIN_TOP
        val fits: Boolean get() = totalHeight <= SAFE_BOTTOM
    }

    /**
     * 先测量后布局（A1 月报溢出修复）。
     *
     * 旧实现边画边累加 y，月报同时含金句/趋势/时段/热力/胶囊/Top3 时总高约 2290px，
     * 超出 2016 画幅，Top3 被裁、页脚压在胶囊上。这里按固定顺序算每块高度，
     * 超限则依次压缩热力格→趋势卡→时段卡→Top 行高，直到装进 [SAFE_BOTTOM]；
     * **Top 首数恒为最多 3 首、页脚恒锚定底部，不参与压缩**（A1 底部锚定语义）。
     *
     * [goldenLines] 由绘制侧用真实 Paint 折行后传入，测量本身不碰 Android API，可纯 JVM 测。
     */
    internal fun measureLayout(content: PosterContent, goldenLines: List<String>): PosterLayout {
        var heatCell = 92f
        var heatGap = 10f
        var trendH = 300
        var timeH = 200
        var topRowH = 96
        // A1：Top 始终保留最多 3 首，不再因溢出砍首数（底部锚定语义）。
        val topSongCount = content.data.topSongs.take(3).size

        fun blockHeight(): Int {
            var y = MARGIN_TOP
            y += 84 + 30                 // brand
            y += 64 + 16                 // lead
            y += 104 + 12 + 16           // mainNumber
            y += 60                      // rangeDelta
            if (content.estimateNote.isNotBlank()) y += 44 + 16
            if (goldenLines.isNotEmpty()) y += 24 + goldenLines.size * 38 + 24
            y += trendH + 24
            if (content.timeSegments.isNotEmpty()) y += timeH + 24
            if (content.calendarCells.isNotEmpty()) {
                val rows = (content.calendarCells.size + 6) / 7
                y += (92 + rows * (heatCell + heatGap)).toInt() + 24
            }
            y += 120 + 24                // capsules
            val topH = 60 + if (topSongCount == 0) 60 else topSongCount * topRowH + 20
            y += topH + 24
            if (content.funTag.isNotBlank() && content.goldenText.isBlank()) {
                val lines = content.funTag.split("\n").count { it.isNotBlank() }.coerceAtLeast(1)
                y += 50 + lines * 44 + 20
            }
            return y
        }

        // 按优先级压缩，直到装进安全区；每步有下限，避免无限循环。
        // A1：为「极端月报（estimate + 3 行金句 + 满配 3 首 Top）」腾空间，热力格下限 48→40、
        // 趋势卡下限 200→160。该情形原样累加总高 1924、超 SAFE_BOTTOM 76px；两项各多压
        // 40px 后总高 1844 ≤ 1848，从而**永不砍 Top 首数**。Top 卡与页脚锚定底部不漂移。
        // 时段卡(150)与 Top 行高(64)已是下限，再压会让 Top 文字行重叠，故不再下探。
        while (blockHeight() > SAFE_BOTTOM && heatCell > 40f) {
            heatCell -= 4f
        }
        while (blockHeight() > SAFE_BOTTOM && trendH > 160) {
            trendH -= 10
        }
        while (blockHeight() > SAFE_BOTTOM && timeH > 150) {
            timeH -= 10
        }
        while (blockHeight() > SAFE_BOTTOM && topRowH > 64) {
            topRowH -= 4
        }
        // 全部压到底（3 行金句 + 热力 40 + trend 160 + time 150 + topRow 64）总高 1844
        // ≤ SAFE_BOTTOM 1848，故此处必定 fits；若未来新增块导致仍超，交由 fits 断言暴露。

        var y = MARGIN_TOP
        val blocks = mutableListOf<PosterBlock>()
        fun add(name: String, h: Int, trailing: Int = 0) {
            blocks += PosterBlock(name, y, y + h)
            y += h + trailing
        }
        add("brand", 84, 30)
        add("lead", 64, 16)
        add("mainNumber", 104 + 12, 16)
        add("rangeDelta", 60)
        if (content.estimateNote.isNotBlank()) add("estimate", 44, 16)
        if (goldenLines.isNotEmpty()) add("golden", 24 + goldenLines.size * 38, 24)
        add("trend", trendH, 24)
        if (content.timeSegments.isNotEmpty()) add("time", timeH, 24)
        if (content.calendarCells.isNotEmpty()) {
            val rows = (content.calendarCells.size + 6) / 7
            add("heat", (92 + rows * (heatCell + heatGap)).toInt(), 24)
        }
        add("capsules", 120, 24)
        add("topSongs", 60 + if (topSongCount == 0) 60 else topSongCount * topRowH + 20, 24)
        if (content.funTag.isNotBlank() && content.goldenText.isBlank()) {
            val lines = content.funTag.split("\n").count { it.isNotBlank() }.coerceAtLeast(1)
            add("funTag", 50 + lines * 44 + 20)
        }
        return PosterLayout(blocks, goldenLines, heatCell, heatGap, trendH, timeH, topRowH, topSongCount)
    }

    /** 渲染一份海报 */
    fun render(content: PosterContent, theme: PosterTheme): Bitmap {
        val bitmap = createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = ReportPalette.of(theme)
        canvas.drawColor(p.bg)

        val goldenLines = if (content.goldenText.isNotBlank()) {
            wrapText(paint(26f, p.ink), content.goldenText, CONTENT_W - 72f)
        } else {
            emptyList()
        }
        val layout = measureLayout(content, goldenLines)
        // 第七轮 B2：接线 fits。A1 已把极端满配月报压到 1844 ≤ SAFE_BOTTOM 1848，
        // 正常内容必不触发；若未来新增块导致溢出，这里以断言暴露，避免静默压住页脚。
        check(layout.fits) { "海报内容超出安全区: ${layout.totalHeight} > $SAFE_BOTTOM" }
        val at = { name: String -> layout.blocks.first { it.name == name }.top }

        drawBrand(canvas, p, content, at("brand"))
        drawLead(canvas, p, content, at("lead"))
        drawMainNumber(canvas, p, content, at("mainNumber"))
        drawRangeDelta(canvas, p, content, at("rangeDelta"))
        if (content.estimateNote.isNotBlank()) {
            drawEstimate(canvas, p, content, at("estimate"))
        }
        if (goldenLines.isNotEmpty()) {
            drawGolden(canvas, p, goldenLines, at("golden"))
        }
        drawTrendCard(canvas, p, content, at("trend"), layout.trendH)
        if (content.timeSegments.isNotEmpty()) {
            drawTimeCard(canvas, p, content, at("time"), layout.timeH)
        }
        if (content.calendarCells.isNotEmpty()) {
            drawHeatCard(canvas, p, content, at("heat"), layout.heatCell, layout.heatGap)
        }
        drawCapsules(canvas, p, content, at("capsules"))
        drawTopSongs(canvas, p, content, at("topSongs"), layout.topRowH, layout.topSongCount)
        if (content.funTag.isNotBlank() && content.goldenText.isBlank()) {
            drawFunTag(canvas, p, content, at("funTag"))
        }
        drawFooter(canvas, p, content)
        return bitmap
    }

    // ---------- 布局段落（每段按 measureLayout 给定的 top 绘制） ----------

    private fun drawBrand(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        val cx = MARGIN_X + 28f
        val cy = y + 28f
        c.drawCircle(cx, cy, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.brand; style = Paint.Style.FILL
        })
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        c.drawCircle(cx - 6f, cy + 7f, 6f, note)
        c.drawRect(cx - 1f, cy - 14f, cx + 2f, cy + 7f, note)
        c.drawText("PureTone", cx + 46f, y + 44f, paint(44f, p.ink, bold = true))
        val badgeP = paint(26f, p.ink3)
        badgeP.textAlign = Paint.Align.RIGHT
        c.drawText(content.periodBadge, (W - MARGIN_X).toFloat(), y + 38f, badgeP)
        return y + 84
    }

    private fun drawLead(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        c.drawText(content.leadText, MARGIN_X.toFloat(), y + 40f, paint(36f, p.ink2))
        return y + 64
    }

    /** 主数字：全海报唯一渐变文字，承担「惊叹」职责 */
    private fun drawMainNumber(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        var size = 104f
        val mp = paint(size, p.spectrum3, bold = true)
        while (mp.measureText(content.mainNumber) > CONTENT_W && size > 60f) {
            size -= 4f
            mp.textSize = size
        }
        mp.shader = LinearGradient(
            0f, y.toFloat(), CONTENT_W.toFloat(), y.toFloat(),
            intArrayOf(p.spectrum2, p.spectrum3, p.spectrum4), null, Shader.TileMode.CLAMP
        )
        c.drawText(content.mainNumber, MARGIN_X.toFloat(), y + size, mp)
        return y + size.toInt() + 12
    }

    private fun drawRangeDelta(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        c.drawText(content.rangeText, MARGIN_X.toFloat(), y + 34f, paint(30f, p.ink2))
        val delta = content.deltaAbsoluteText.ifBlank { content.deltaText }
        if (delta.isNotBlank()) {
            val dp = paint(30f, p.spectrum4, bold = true)
            dp.textAlign = Paint.Align.RIGHT
            c.drawText(delta, (W - MARGIN_X).toFloat(), y + 34f, dp)
        }
        return y + 60
    }

    private fun drawEstimate(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        c.drawText(content.estimateNote, MARGIN_X.toFloat(), y + 28f, paint(24f, p.ink3))
        return y + 44
    }

    /** 行为金句：洋红左边线 + chip 底（折行由 measureLayout 共享，避免重复测量） */
    private fun drawGolden(c: Canvas, p: ReportPalette, lines: List<String>, y: Int): Int {
        val tp = paint(26f, p.ink, bold = false)
        val h = 24 + lines.size * 38
        val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.card; style = Paint.Style.FILL }
        c.drawRoundRect(RectF(MARGIN_X.toFloat(), y.toFloat(), (W - MARGIN_X).toFloat(), (y + h).toFloat()), 18f, 18f, rp)
        c.drawRect(MARGIN_X.toFloat(), (y + 10).toFloat(), MARGIN_X + 6f, (y + h - 10).toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.spectrum3; style = Paint.Style.FILL })
        lines.forEachIndexed { i, line ->
            c.drawText(line, (MARGIN_X + 28).toFloat(), (y + 38 + i * 38).toFloat(), tp)
        }
        return y + h
    }

    private fun drawTrendCard(
        c: Canvas, p: ReportPalette, content: PosterContent, y: Int, cardH: Int
    ): Int {
        val buckets = content.trendBuckets
        drawCard(c, p, MARGIN_X, y, CONTENT_W, cardH)
        val pad = 36
        val innerX = MARGIN_X + pad
        val innerW = CONTENT_W - 2 * pad
        c.drawText(content.trendTitle, innerX.toFloat(), (y + pad + 30).toFloat(), paint(34f, p.brand, bold = true))
        val barTop = y + pad + 60f
        val plotH = (cardH - pad - 60f - 40f).coerceAtLeast(40f)
        val baseY = barTop + plotH
        if (buckets.all { it.listenMs == 0L }) {
            c.drawText("暂无数据", innerX.toFloat(), (barTop + 60).toFloat(), paint(26f, p.ink3))
            return y + cardH
        }
        when (content.data.type) {
            PeriodType.WEEK -> drawAreaLine(c, p, buckets, innerX, innerW, barTop, plotH)
            // A2：年报保持 V1 品牌纯色柱，与屏幕端 BarChart(brand) 同源，不被 V2 光谱渐变顺带改色
            PeriodType.YEAR -> drawV1Bars(c, p, buckets, innerX, innerW, baseY, plotH)
            else -> drawSpectrumBars(c, p, buckets, innerX, innerW, baseY, plotH)
        }
        val lp = paint(22f, p.ink3)
        val step = innerW / buckets.size.coerceAtLeast(1)
        buckets.forEachIndexed { i, b ->
            val cx = innerX + step * i + step / 2f
            val labelP = Paint(lp).apply { textAlign = Paint.Align.CENTER }
            c.drawText(b.label, cx.toFloat(), baseY + 28f, labelP)
        }
        return y + cardH
    }

    /** 周报：面积折线 + 峰点洋红光晕（7 点读起伏） */
    private fun drawAreaLine(
        c: Canvas, p: ReportPalette, buckets: List<ReportTrend.TrendBucket>,
        x: Int, w: Int, top: Float, h: Float
    ) {
        val max = (buckets.maxOfOrNull { it.listenMs } ?: 0L).coerceAtLeast(1L).toFloat()
        val n = buckets.size
        val step = if (n > 1) w.toFloat() / (n - 1) else 0f
        val pts = buckets.mapIndexed { i, b ->
            val px = x + i * step
            val py = top + h - (b.listenMs / max) * (h - 4f)
            px to py
        }
        val fill = Path().apply {
            moveTo(pts.first().first, top + h)
            pts.forEach { lineTo(it.first, it.second) }
            lineTo(pts.last().first, top + h)
            close()
        }
        c.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, top, 0f, top + h, intArrayOf(
                withAlpha(p.spectrum2, 0.42f), withAlpha(p.spectrum2, 0f)), null, Shader.TileMode.CLAMP)
            style = Paint.Style.FILL
        })
        pts.forEachIndexed { i, pt ->
            if (i < pts.lastIndex) {
                c.drawLine(pt.first, pt.second, pts[i + 1].first, pts[i + 1].second,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.spectrum2; strokeWidth = 4f; style = Paint.Style.STROKE })
            }
        }
        val peak = buckets.withIndex().filter { it.value.listenMs > 0 }
            .maxByOrNull { it.value.listenMs }?.index
        if (peak != null && peak in pts.indices) {
            val (px, py) = pts[peak]
            c.drawCircle(px, py, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(p.spectrum3, 0.22f) })
            c.drawCircle(px, py, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.spectrum3 })
        }
    }

    /** A2 年报专用：V1 品牌纯色柱，峰值 alpha 110 其余 70，与屏幕 BarChart(brand) 同色同源 */
    private fun drawV1Bars(
        c: Canvas, p: ReportPalette, buckets: List<ReportTrend.TrendBucket>,
        x: Int, w: Int, baseY: Float, h: Float
    ) {
        val max = (buckets.maxOfOrNull { it.listenMs } ?: 0L).coerceAtLeast(1L).toFloat()
        val n = buckets.size
        val gap = 12f
        val slot = (w - gap * (n - 1)) / n
        val barW = (slot * 0.6f).coerceAtLeast(8f)
        val peak = buckets.withIndex().filter { it.value.listenMs > 0 }
            .maxByOrNull { it.value.listenMs }?.index ?: -1
        buckets.forEachIndexed { i, b ->
            val left = x + i * (slot + gap) + (slot - barW) / 2
            val bh = if (b.listenMs > 0) (b.listenMs / max) * (h - 4f) + 4f else 2f
            c.drawRect(left, baseY - bh, left + barW, baseY,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(p.brand, if (i == peak) 110f / 255f else 70f / 255f)
                    style = Paint.Style.FILL
                })
        }
    }

    /** 月报：光谱柱，峰值满色其余半透明（渐变更贵所以省着用） */
    private fun drawSpectrumBars(
        c: Canvas, p: ReportPalette, buckets: List<ReportTrend.TrendBucket>,
        x: Int, w: Int, baseY: Float, h: Float
    ) {
        val max = (buckets.maxOfOrNull { it.listenMs } ?: 0L).coerceAtLeast(1L).toFloat()
        val n = buckets.size
        val gap = 12f
        val slot = (w - gap * (n - 1)) / n
        val barW = (slot * 0.6f).coerceAtLeast(8f)
        val peak = buckets.withIndex().filter { it.value.listenMs > 0 }
            .maxByOrNull { it.value.listenMs }?.index ?: -1
        buckets.forEachIndexed { i, b ->
            val left = x + i * (slot + gap) + (slot - barW) / 2
            val bh = if (b.listenMs > 0) (b.listenMs / max) * (h - 4f) + 4f else 2f
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, baseY - bh, 0f, baseY,
                    intArrayOf(p.spectrum2, p.spectrum3), null, Shader.TileMode.CLAMP
                )
                alpha = if (i == peak) 255 else 96
                style = Paint.Style.FILL
            }
            c.drawRect(left, baseY - bh, left + barW, baseY, bp)
        }
    }

    /** 时段分布条：4 段宽度即占比，色相携带「清晨→深夜」信息 */
    private fun drawTimeCard(
        c: Canvas, p: ReportPalette, content: PosterContent, y: Int, cardH: Int
    ): Int {
        drawCard(c, p, MARGIN_X, y, CONTENT_W, cardH)
        val innerX = MARGIN_X + 36f
        c.drawText(content.timeTitle, innerX, (y + 54).toFloat(), paint(34f, p.brand, bold = true))
        if (content.timePeakText.isNotBlank()) {
            val pp = paint(24f, p.ink3)
            pp.textAlign = Paint.Align.RIGHT
            c.drawText(content.timePeakText, (W - MARGIN_X - 36).toFloat(), (y + 54).toFloat(), pp)
        }
        val barY = y + 88f
        val barH = 40f
        var cx = innerX
        val totalW = CONTENT_W - 72f
        content.timeSegments.forEach { seg ->
            val w = (seg.share * totalW).coerceAtLeast(0f)
            if (w > 0f) {
                c.drawRect(cx, barY, cx + w, barY + barH,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.spectrum(seg.level); style = Paint.Style.FILL })
                if (w > 90f) {
                    val lp = paint(22f, Color.WHITE, bold = true).apply { textAlign = Paint.Align.CENTER }
                    c.drawText(seg.label, cx + w / 2, barY + 28f, lp)
                }
                cx += w
            }
        }
        // 空条兜底：全部无数据时画一条中性底
        if (content.timeSegments.all { it.share <= 0f }) {
            c.drawRect(innerX, barY, innerX + totalW, barY + barH,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.line; style = Paint.Style.FILL })
        }
        val labelP = paint(22f, p.ink3).apply { textAlign = Paint.Align.CENTER }
        val step = totalW / content.timeSegments.size.coerceAtLeast(1)
        content.timeSegments.forEachIndexed { i, seg ->
            c.drawText(seg.label, innerX + step * i + step / 2, barY + barH + 30f, labelP)
        }
        return y + cardH
    }

    /** 月报专属：声音日历热力网格（7 列，空日留底不描边；cell/gap 由 measureLayout 压缩） */
    private fun drawHeatCard(
        c: Canvas, p: ReportPalette, content: PosterContent, y: Int, cell: Float, gap: Float
    ): Int {
        val cols = 7
        val rows = (content.calendarCells.size + cols - 1) / cols
        val cardH = (92 + rows * (cell + gap)).toInt()
        drawCard(c, p, MARGIN_X, y, CONTENT_W, cardH)
        val innerX = MARGIN_X + 36f
        c.drawText(content.heatTitle, innerX, (y + 54).toFloat(), paint(34f, p.brand, bold = true))
        val hp = paint(22f, p.ink3)
        hp.textAlign = Paint.Align.RIGHT
        c.drawText(content.heatHint, (W - MARGIN_X - 36).toFloat(), (y + 54).toFloat(), hp)
        val top = y + 76f
        content.calendarCells.forEachIndexed { i, day ->
            val col = i % cols
            val row = i / cols
            val x = innerX + col * (cell + gap)
            val cy = top + row * (cell + gap)
            val color = if (day.level == 0) p.line else p.spectrum(day.level)
            c.drawRoundRect(RectF(x, cy, x + cell, cy + cell), 12f, 12f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL })
        }
        return y + cardH
    }

    private fun drawCapsules(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        val items = listOf(content.capsulePlays, content.capsuleSongs, content.capsuleActiveDays)
        val gap = 16
        val cw = (CONTENT_W - 2 * gap) / 3
        val ch = 120
        items.forEachIndexed { i, text ->
            val x = MARGIN_X + i * (cw + gap)
            c.drawRoundRect(RectF(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat()), 20f, 20f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.card; style = Paint.Style.FILL })
            val parts = text.split(" ")
            val num = parts.firstOrNull() ?: text
            val unit = parts.drop(1).joinToString(" ")
            val np = paint(40f, p.spectrum4, bold = true).apply { textAlign = Paint.Align.CENTER }
            c.drawText(num, x + cw / 2f, y + 54f, np)
            val up = paint(22f, p.ink3).apply { textAlign = Paint.Align.CENTER }
            c.drawText(unit, x + cw / 2f, y + 90f, up)
        }
        return y + ch
    }

    private fun drawTopSongs(
        c: Canvas, p: ReportPalette, content: PosterContent, y: Int, rowH: Int, count: Int
    ): Int {
        val list = content.data.topSongs.take(count)
        val titleH = 60
        val cardH = titleH + if (list.isEmpty()) 60 else list.size * rowH + 20
        drawCard(c, p, MARGIN_X, y, CONTENT_W, cardH)
        val innerX = MARGIN_X + 36
        c.drawText(content.topSongsTitle, innerX.toFloat(), (y + titleH - 6).toFloat(), paint(34f, p.brand, bold = true))
        if (list.isEmpty()) {
            c.drawText("暂无收听记录", innerX.toFloat(), (y + titleH + 44).toFloat(), paint(24f, p.ink3))
            return y + cardH
        }
        var ry = y + titleH + 8
        list.forEachIndexed { i, s ->
            c.drawText("${i + 1}", innerX.toFloat(), ry + 44f, paint(40f, p.spectrum4, bold = true))
            val title = ellipsize(paint(30f, p.ink), s.title, (CONTENT_W - 120).toFloat())
            c.drawText(title, (innerX + 60).toFloat(), ry + 38f, paint(30f, p.ink))
            val sub = ellipsize(paint(22f, p.ink2), "${s.artist} · ${s.playCount} 次", (CONTENT_W - 120).toFloat())
            c.drawText(sub, (innerX + 60).toFloat(), ry + 72f, paint(22f, p.ink2))
            ry += rowH
        }
        return y + cardH
    }

    private fun drawFunTag(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        val lines = content.funTag.split("\n").filter { it.isNotBlank() }
        val titleH = 50
        val lineH = 44
        val cardH = titleH + (if (lines.isEmpty()) 1 else lines.size) * lineH + 20
        drawCard(c, p, MARGIN_X, y, CONTENT_W, cardH)
        val innerX = MARGIN_X + 36
        c.drawText(content.funTagTitle, innerX.toFloat(), (y + titleH - 8).toFloat(), paint(34f, p.brand, bold = true))
        if (lines.isEmpty()) {
            c.drawText("—", innerX.toFloat(), (y + titleH + 40).toFloat(), paint(24f, p.ink3))
        } else {
            lines.forEachIndexed { i, line ->
                c.drawText(line, innerX.toFloat(), (y + titleH + 36 + i * lineH).toFloat(), paint(28f, p.ink))
            }
        }
        return y + cardH
    }

    private fun drawFooter(c: Canvas, p: ReportPalette, content: PosterContent) {
        val fy = H - MARGIN_BOTTOM - 56
        val fp = paint(24f, p.ink2).apply { textAlign = Paint.Align.CENTER }
        c.drawText(content.footer, (W / 2).toFloat(), fy.toFloat(), fp)
        val gp = paint(22f, p.ink3).apply { textAlign = Paint.Align.CENTER }
        c.drawText(content.generatedOn, (W / 2).toFloat(), (fy + 34).toFloat(), gp)
    }

    // ---------- 内部工具 ----------

    private fun drawCard(c: Canvas, p: ReportPalette, x: Int, y: Int, w: Int, h: Int) {
        c.drawRoundRect(RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()), 28f, 28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.card; style = Paint.Style.FILL })
    }

    private fun paint(textSize: Float, color: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.color = color
            this.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            this.style = Paint.Style.FILL
        }

    private fun withAlpha(argb: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    private fun ellipsize(p: Paint, text: String, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var s = text
        while (s.isNotEmpty() && p.measureText("$s…") > maxW) s = s.dropLast(1)
        return "$s…"
    }

    /**
     * 按宽度折行（中文逐字断行），供金句使用；上限 3 行防溢出。
     * 第七轮 B1：前两行贪心切满，第 3 行并入全部剩余并省略号截断。
     * 旧实现推满第 2 行就 break，导致第 3 行只剩溢出的单个字符、其后文字静默丢失且无省略号。
     */
    internal fun wrapText(p: Paint, text: String, maxW: Float): List<String> =
        wrapTextByWidth(text, maxW) { p.measureText(it) }

    /**
     * 折行核心纯函数：[measure] 由调用方注入（生产传 Paint::measureText，测试传确定性函数），
     * 使折行/省略号逻辑可在纯 JVM 下断言，不依赖 Robolectric 的 Paint 文本测量（其 measureText 恒为 0）。
     */
    internal fun wrapTextByWidth(
        text: String,
        maxW: Float,
        measure: (String) -> Float
    ): List<String> {
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty() && lines.size < 2) {
            var end = 0
            while (end < remaining.length &&
                measure(remaining.substring(0, end + 1)) <= maxW
            ) end++
            if (end == 0) end = 1 // 单字超宽也至少保留 1 字，防死循环
            lines += remaining.substring(0, end)
            remaining = remaining.substring(end)
        }
        if (remaining.isNotEmpty()) {
            var s = remaining
            if (measure(s) > maxW) {
                while (s.isNotEmpty() && measure("$s…") > maxW) s = s.dropLast(1)
                s = "$s…"
            }
            lines += s
        }
        return lines
    }
}

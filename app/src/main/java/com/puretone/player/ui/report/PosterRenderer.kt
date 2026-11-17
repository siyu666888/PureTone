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
 */
object PosterRenderer {

    private const val W = 1080
    private const val H = 2016
    private const val MARGIN_X = 72
    private const val MARGIN_TOP = 72
    private const val MARGIN_BOTTOM = 96
    private val CONTENT_W = W - 2 * MARGIN_X

    /** 渲染一份海报 */
    fun render(content: PosterContent, theme: PosterTheme): Bitmap {
        val bitmap = createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = ReportPalette.of(theme)
        canvas.drawColor(p.bg)

        var y = MARGIN_TOP
        y = drawBrand(canvas, p, content, y) + 30
        y = drawLead(canvas, p, content, y) + 16
        y = drawMainNumber(canvas, p, content, y) + 16
        y = drawRangeDelta(canvas, p, content, y)
        if (content.estimateNote.isNotBlank()) {
            y = drawEstimate(canvas, p, content, y) + 16
        }
        if (content.goldenText.isNotBlank()) {
            y = drawGolden(canvas, p, content, y) + 24
        }
        y = drawTrendCard(canvas, p, content, y) + 24
        if (content.timeSegments.isNotEmpty()) {
            y = drawTimeCard(canvas, p, content, y) + 24
        }
        if (content.calendarCells.isNotEmpty()) {
            y = drawHeatCard(canvas, p, content, y) + 24
        }
        y = drawCapsules(canvas, p, content, y) + 24
        y = drawTopSongs(canvas, p, content, y) + 24
        if (content.funTag.isNotBlank() && content.goldenText.isBlank()) {
            y = drawFunTag(canvas, p, content, y)
        }
        drawFooter(canvas, p, content)
        return bitmap
    }

    // ---------- 布局段落（每段返回段落底边 y） ----------

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
            val dp = paint(30f, p.spectrum3, bold = true)
            dp.textAlign = Paint.Align.RIGHT
            c.drawText(delta, (W - MARGIN_X).toFloat(), y + 34f, dp)
        }
        return y + 60
    }

    private fun drawEstimate(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        c.drawText(content.estimateNote, MARGIN_X.toFloat(), y + 28f, paint(24f, p.ink3))
        return y + 44
    }

    /** 行为金句：洋红左边线 + chip 底 */
    private fun drawGolden(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        val text = content.goldenText
        val tp = paint(26f, p.ink, bold = false)
        val lines = wrapText(tp, text, CONTENT_W - 72f)
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

    private fun drawTrendCard(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        val buckets = content.trendBuckets
        val cardH = 300
        drawCard(c, p, MARGIN_X, y, CONTENT_W, cardH)
        val pad = 36
        val innerX = MARGIN_X + pad
        val innerW = CONTENT_W - 2 * pad
        c.drawText(content.trendTitle, innerX.toFloat(), (y + pad + 30).toFloat(), paint(34f, p.brand, bold = true))
        val barTop = y + pad + 60f
        val plotH = 150f
        val baseY = barTop + plotH
        if (buckets.all { it.listenMs == 0L }) {
            c.drawText("暂无数据", innerX.toFloat(), (barTop + 60).toFloat(), paint(26f, p.ink3))
            return y + cardH
        }
        if (content.data.type == PeriodType.WEEK) {
            drawAreaLine(c, p, buckets, innerX, innerW, barTop, plotH)
        } else {
            drawSpectrumBars(c, p, buckets, innerX, innerW, baseY, plotH)
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

    /** 月/年报：光谱柱，峰值满色其余半透明（渐变更贵所以省着用） */
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
    private fun drawTimeCard(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        val cardH = 200
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

    /** 月报专属：声音日历热力网格（7 列，空日留底不描边） */
    private fun drawHeatCard(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        val cols = 7
        val cell = 92f
        val gap = 10f
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

    private fun drawTopSongs(c: Canvas, p: ReportPalette, content: PosterContent, y: Int): Int {
        val list = content.data.topSongs.take(3)
        val rowH = 96
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
            c.drawText("${i + 1}", innerX.toFloat(), ry + 44f, paint(40f, p.spectrum3, bold = true))
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

    /** 按宽度折行（中文逐字断行），供金句使用；上限 3 行防溢出 */
    private fun wrapText(p: Paint, text: String, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (ch in text) {
            val next = line.toString() + ch
            if (p.measureText(next) > maxW && line.isNotEmpty()) {
                out += line.toString()
                line = StringBuilder(ch.toString())
                if (out.size >= 2) break
            } else {
                line.append(ch)
            }
        }
        if (line.isNotEmpty() && out.size < 3) {
            var last = line.toString()
            while (last.isNotEmpty() && p.measureText("$last…") > maxW) last = last.dropLast(1)
            out += if (last.length < line.length) "$last…" else last
        }
        return out
    }
}

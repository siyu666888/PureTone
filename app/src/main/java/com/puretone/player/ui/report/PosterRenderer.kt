package com.puretone.player.ui.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap

/**
 * 听歌报告分享海报渲染器（需求4 阶段2，方案 7 方案A）。
 *
 * 采用 Android Canvas 直绘（[Bitmap] + [Canvas] + [Paint]），零依赖、可在任意线程生成、
 * 易写纯 JVM 单测（断言尺寸/不抛异常）。
 *
 * 设计约束：
 * - 画幅固定 1080×1920（9:16），PNG 无二次压缩；安全区左右 72、上 72、下 96。
 * - 纯函数式：输入 [PosterContent]（文案已本地化）+ [PosterTheme]，输出 [Bitmap]，
 *   **不持有 Context、不查资源、不依赖 Compose**，便于测试与后台生成。
 * - 三主题色板映射自 [com.puretone.player.ui.theme.PureToneColorSchemes] 的 Light/Dark/Black。
 * - 中文换行/截断手写处理（measureText + 末尾省略号），不引入 StaticLayout。
 */
object PosterRenderer {

    private const val W = 1080
    private const val H = 1920
    private const val MARGIN_X = 72
    private const val MARGIN_TOP = 72
    private const val MARGIN_BOTTOM = 96
    private val CONTENT_W = W - 2 * MARGIN_X

    /** 渲染一份海报 */
    fun render(content: PosterContent, theme: PosterTheme): Bitmap {
        val bitmap = createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = palette(theme)
        canvas.drawColor(p.bg)

        var y = MARGIN_TOP
        y = drawBrand(canvas, p, content, y) + 30
        y = drawLead(canvas, p, content, y) + 16
        y = drawMainNumber(canvas, p, content, y) + 16
        y = drawRangeDelta(canvas, p, content, y)
        if (content.estimateNote.isNotBlank()) {
            y = drawEstimate(canvas, p, content, y) + 16
        }
        y = drawTrendCard(canvas, p, content, y) + 24
        y = drawCapsules(canvas, p, content, y) + 24
        y = drawTopSongs(canvas, p, content, y) + 24
        y = drawFunTag(canvas, p, content, y)
        drawFooter(canvas, p, content)
        return bitmap
    }

    // ---------- 布局段落（每段返回段落底边 y） ----------

    private fun drawBrand(canvas: Canvas, p: Palette, content: PosterContent, y: Int): Int {
        val cx = MARGIN_X + 28f
        val cy = y + 28f
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.primary; style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy, 28f, circle)
        // 简单音符字形：白圆点 + 竖杆
        val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawCircle(cx - 6f, cy + 7f, 6f, note)
        canvas.drawRect(cx - 1f, cy - 14f, cx + 2f, cy + 7f, note)
        // 品牌名
        val nameP = paint(44f, p.onSurface, bold = true)
        canvas.drawText("PureTone", cx + 46f, y + 44f, nameP)
        // 周期角标（右对齐）
        val badgeP = paint(26f, p.onSurfaceVariant)
        badgeP.textAlign = Paint.Align.RIGHT
        canvas.drawText(content.periodBadge, (W - MARGIN_X).toFloat(), y + 38f, badgeP)
        return y + 84
    }

    private fun drawLead(canvas: Canvas, p: Palette, content: PosterContent, y: Int): Int {
        val leadP = paint(36f, p.onSurfaceVariant)
        canvas.drawText(content.leadText, MARGIN_X.toFloat(), y + 40f, leadP)
        return y + 64
    }

    private fun drawMainNumber(canvas: Canvas, p: Palette, content: PosterContent, y: Int): Int {
        val mainP = paint(104f, p.heading, bold = true)
        val maxW = CONTENT_W.toFloat()
        var size = 104f
        while (mainP.measureText(content.mainNumber) > maxW && size > 60f) {
            size -= 4f
            mainP.textSize = size
        }
        canvas.drawText(content.mainNumber, MARGIN_X.toFloat(), y + size, mainP)
        return y + size.toInt() + 12
    }

    private fun drawRangeDelta(canvas: Canvas, p: Palette, content: PosterContent, y: Int): Int {
        val rangeP = paint(30f, p.onSurfaceVariant)
        canvas.drawText(content.rangeText, MARGIN_X.toFloat(), y + 34f, rangeP)
        val deltaP = paint(30f, p.primary, bold = true)
        deltaP.textAlign = Paint.Align.RIGHT
        canvas.drawText(content.deltaText, (W - MARGIN_X).toFloat(), y + 34f, deltaP)
        return y + 60
    }

    private fun drawEstimate(canvas: Canvas, p: Palette, content: PosterContent, y: Int): Int {
        val ep = paint(24f, p.onSurfaceVariant)
        canvas.drawText(content.estimateNote, MARGIN_X.toFloat(), y + 28f, ep)
        return y + 44
    }

    private fun drawTrendCard(canvas: Canvas, p: Palette, content: PosterContent, y: Int): Int {
        val bars = buildBars(content)
        val cardH = 300
        drawCard(canvas, p, MARGIN_X, y, CONTENT_W, cardH)
        val pad = 36
        val innerX = MARGIN_X + pad
        val innerW = CONTENT_W - 2 * pad
        val titleP = paint(34f, p.primary, bold = true)
        canvas.drawText(content.trendTitle, innerX.toFloat(), y + pad + 30f, titleP)

        val barTop = y + pad + 60f
        val barH = 150f
        val baseY = barTop + barH
        val max = (bars.maxOfOrNull { it.value } ?: 0L).toFloat().coerceAtLeast(1f)
        val n = bars.size.coerceAtLeast(1)
        val gap = 12f
        val slot = (innerW - gap * (n - 1)) / n
        val barW = (slot * 0.6f).coerceAtLeast(8f)
        val peakIdx = bars.indexOfMaxOrNull()
        bars.forEachIndexed { i, b ->
            val left = innerX + i * (slot + gap) + (slot - barW) / 2
            val h = if (b.value > 0) (b.value.toFloat() / max) * (barH - 4f) + 4f else 2f
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = p.primary
                alpha = if (i == peakIdx && b.value > 0) 255 else 110
                style = Paint.Style.FILL
            }
            canvas.drawRect(left, baseY - h, left + barW, baseY, bp)
            val lp = paint(22f, p.onSurfaceVariant)
            lp.textAlign = Paint.Align.CENTER
            canvas.drawText(b.label, left + barW / 2, baseY + 28f, lp)
        }
        return y + cardH
    }

    private fun drawCapsules(canvas: Canvas, p: Palette, content: PosterContent, y: Int): Int {
        val items = listOf(content.capsulePlays, content.capsuleSongs, content.capsuleActiveDays)
        val gap = 16
        val cw = (CONTENT_W - 2 * gap) / 3
        val ch = 120
        items.forEachIndexed { i, text ->
            val x = MARGIN_X + i * (cw + gap)
            val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.container; style = Paint.Style.FILL }
            canvas.drawRoundRect(
                RectF(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat()),
                20f, 20f, rp
            )
            val parts = text.split(" ")
            val num = parts.firstOrNull() ?: text
            val unit = parts.drop(1).joinToString(" ")
            val np = paint(40f, p.onSurface, bold = true)
            np.textAlign = Paint.Align.CENTER
            canvas.drawText(num, x + cw / 2f, y + 54f, np)
            val up = paint(22f, p.onSurfaceVariant)
            up.textAlign = Paint.Align.CENTER
            canvas.drawText(unit, x + cw / 2f, y + 90f, up)
        }
        return y + ch
    }

    private fun drawTopSongs(canvas: Canvas, p: Palette, content: PosterContent, y: Int): Int {
        val list = content.data.topSongs.take(3)
        val rowH = 96
        val titleH = 60
        val cardH = titleH + if (list.isEmpty()) 60 else list.size * rowH + 20
        drawCard(canvas, p, MARGIN_X, y, CONTENT_W, cardH)
        val innerX = MARGIN_X + 36
        val tp = paint(34f, p.primary, bold = true)
        canvas.drawText(content.topSongsTitle, innerX.toFloat(), y + titleH - 6f, tp)
        if (list.isEmpty()) {
            val ep = paint(24f, p.onSurfaceVariant)
            canvas.drawText("暂无收听记录", innerX.toFloat(), y + titleH + 44f, ep)
            return y + cardH
        }
        var ry = y + titleH + 8
        list.forEachIndexed { i, s ->
            val rankP = paint(40f, p.primary, bold = true)
            canvas.drawText("${i + 1}", innerX.toFloat(), ry + 44f, rankP)
            val title = ellipsize(paint(30f, p.onSurface), s.title, (CONTENT_W - 120).toFloat())
            canvas.drawText(title, (innerX + 60).toFloat(), ry + 38f, paint(30f, p.onSurface))
            val subText = "${s.artist} · ${s.playCount} 次"
            val sub = ellipsize(paint(22f, p.onSurfaceVariant), subText, (CONTENT_W - 120).toFloat())
            canvas.drawText(sub, (innerX + 60).toFloat(), ry + 72f, paint(22f, p.onSurfaceVariant))
            ry += rowH
        }
        return y + cardH
    }

    private fun drawFunTag(canvas: Canvas, p: Palette, content: PosterContent, y: Int): Int {
        val lines = content.funTag.split("\n").filter { it.isNotBlank() }
        val titleH = 50
        val lineH = 44
        val cardH = titleH + (if (lines.isEmpty()) 1 else lines.size) * lineH + 20
        drawCard(canvas, p, MARGIN_X, y, CONTENT_W, cardH)
        val innerX = MARGIN_X + 36
        val tp = paint(34f, p.primary, bold = true)
        canvas.drawText(content.funTagTitle, innerX.toFloat(), y + titleH - 8f, tp)
        if (lines.isEmpty()) {
            val ep = paint(24f, p.onSurfaceVariant)
            canvas.drawText("—", innerX.toFloat(), y + titleH + 40f, ep)
        } else {
            lines.forEachIndexed { i, line ->
                canvas.drawText(line, innerX.toFloat(), y + titleH + 36f + i * lineH, paint(28f, p.onSurface))
            }
        }
        return y + cardH
    }

    private fun drawFooter(canvas: Canvas, p: Palette, content: PosterContent) {
        val fy = H - MARGIN_BOTTOM - 56
        val fp = paint(24f, p.onSurfaceVariant)
        fp.textAlign = Paint.Align.CENTER
        canvas.drawText(content.footer, (W / 2).toFloat(), fy.toFloat(), fp)
        val gp = paint(22f, p.onSurfaceVariant)
        gp.textAlign = Paint.Align.CENTER
        canvas.drawText(content.generatedOn, (W / 2).toFloat(), (fy + 34).toFloat(), gp)
    }

    // ---------- 内部工具 ----------

    private fun drawCard(canvas: Canvas, p: Palette, x: Int, y: Int, w: Int, h: Int) {
        val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.surface; style = Paint.Style.FILL }
        canvas.drawRoundRect(
            RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()),
            28f, 28f, rp
        )
    }

    private fun paint(textSize: Float, color: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.color = color
            this.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            this.style = Paint.Style.FILL
        }

    private fun ellipsize(p: Paint, text: String, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var s = text
        while (s.isNotEmpty() && p.measureText("$s…") > maxW) s = s.dropLast(1)
        return "$s…"
    }

    /**
     * 阶段3：直接消费 [PosterContent.trendBuckets]。
     * 分桶与补齐已在 [com.puretone.player.domain.report.ReportTrend] 完成，
     * 这里不再按周期各自处理日粒度，保证屏幕与海报同图同标签。
     */
    private fun buildBars(content: PosterContent): List<Bar> =
        content.trendBuckets.map { Bar(it.listenMs, it.label) }

    private fun List<Bar>.indexOfMaxOrNull(): Int {
        if (isEmpty()) return -1
        var idx = 0
        for (i in 1 until size) if (this[i].value > this[idx].value) idx = i
        return idx
    }

    private data class Bar(val value: Long, val label: String)

    /** 三主题色板（android.graphics 颜色 int，AARRGGBB） */
    private data class Palette(
        val bg: Int,
        val surface: Int,
        val onSurface: Int,
        val onSurfaceVariant: Int,
        val primary: Int,
        val container: Int,
        val heading: Int
    )

    private fun palette(theme: PosterTheme): Palette = when (theme) {
        PosterTheme.LIGHT -> Palette(
            bg = 0xFFFAF9FF.toInt(),
            surface = 0xFFF4F3FB.toInt(),
            onSurface = 0xFF1B1B21.toInt(),
            onSurfaceVariant = 0xFF47464F.toInt(),
            primary = 0xFF5E6AD2.toInt(),
            container = 0xFFE4E3FA.toInt(),
            heading = 0xFF232965.toInt()
        )
        PosterTheme.DARK -> Palette(
            bg = 0xFF121218.toInt(),
            surface = 0xFF1E1E28.toInt(),
            onSurface = 0xFFE4E1E9.toInt(),
            onSurfaceVariant = 0xFFC9C7D0.toInt(),
            primary = 0xFFBFC2F3.toInt(),
            container = 0xFF292932.toInt(),
            heading = 0xFFE4E3FA.toInt()
        )
        PosterTheme.BLACK -> Palette(
            bg = 0xFF000000.toInt(),
            surface = 0xFF101016.toInt(),
            onSurface = 0xFFE4E1E9.toInt(),
            onSurfaceVariant = 0xFFC9C7D0.toInt(),
            primary = 0xFFBFC2F3.toInt(),
            container = 0xFF18181F.toInt(),
            heading = 0xFFE4E3FA.toInt()
        )
    }
}

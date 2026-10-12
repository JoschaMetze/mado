package jp.co.xenocode.mado

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The home scene: a painting of the day, the time, and the projects agents report on, in four
 * layouts that all take their colours from the painting. Tap the painting's label to read
 * about it; tap anywhere else to move to the next layout.
 *
 * It is still unless something changes: one redraw a minute for the clock.
 */
class DashboardView(context: Context) : View(context) {
    class Project(val name: String, val status: String, val note: String, val progress: Float?)

    class Art(
        val title: String, val artist: String, val date: String, val medium: String, val credit: String,
        val text: String, val image: Bitmap?, val qr: Bitmap?,
        val ink: Int, val paper: Int, val wall: Int, val accent: Int, val swatches: IntArray,
    )

    var onLayoutChosen: ((Int) -> Unit)? = null
    var layout = FULL_BLEED
        set(value) {
            field = value % LAYOUTS
            invalidate()
        }

    private var art = NO_ART
    private var projects: List<Project> = emptyList()
    private var learnOpen = false
    private var learn = 0f
    private var learnTitle: StaticLayout? = null
    private var learnText: StaticLayout? = null

    private val now = Date()
    private val clock = SimpleDateFormat("HH:mm", Locale.US)
    private val hour = SimpleDateFormat("HH", Locale.US)
    private val minute = SimpleDateFormat("mm", Locale.US)
    private val weekday = SimpleDateFormat("EEEE", Locale.US)
    private val dayMonth = SimpleDateFormat("d MMMM", Locale.US)
    private val shortDate = SimpleDateFormat("EEE d MMM", Locale.US)

    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val heavy = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val italic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val scrimTop = Paint()
    private val scrimBottom = Paint()
    private val src = Rect()
    private val dst = RectF()
    private val rect = RectF()
    private val plaque = RectF()
    private val path = Path()

    fun setArt(art: Art?) {
        this.art = art ?: NO_ART
        val a = this.art
        learnTitle = StaticLayout.Builder.obtain(a.title, 0, a.title.length, type(42f, italic, WHITE), LEARN_TEXT_W)
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
        learnText = StaticLayout.Builder.obtain(a.text, 0, a.text.length, type(19f, sans, WHITE), LEARN_TEXT_W)
            .setLineSpacing(0f, 1.42f).setMaxLines(11).setEllipsize(TextUtils.TruncateAt.END).build()
        invalidate()
    }

    fun setProjects(projects: List<Project>) {
        this.projects = projects.take(MAX_PROJECTS)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scrimTop.shader = LinearGradient(0f, 0f, 0f, h * 0.3f, 0x70000000, 0, Shader.TileMode.CLAMP)
        scrimBottom.shader = LinearGradient(0f, h * 0.48f, 0f, h.toFloat(), 0, 0xC0000000.toInt(), Shader.TileMode.CLAMP)
    }

    /** Configure the shared text paint. Every caller sets everything, so nothing leaks between uses. */
    private fun type(
        size: Float, face: Typeface, color: Int, alpha: Float = 1f, spacing: Float = 0f,
        align: Paint.Align = Paint.Align.LEFT, shadow: Boolean = false,
    ): TextPaint = text.apply {
        textSize = size
        typeface = face
        this.color = color
        this.alpha = (Color.alpha(color) * alpha).toInt()
        letterSpacing = spacing
        textAlign = align
        if (shadow) setShadowLayer(14f, 0f, 2f, 0xA0000000.toInt()) else clearShadowLayer()
    }

    private fun mono(color: Int, alpha: Float = 1f, size: Float = 15f, align: Paint.Align = Paint.Align.LEFT, shadow: Boolean = false) =
        type(size, Typeface.MONOSPACE, color, alpha, 0.14f, align, shadow)

    private fun fit(s: String, paint: TextPaint, width: Float) = TextUtils.ellipsize(s, paint, width, TextUtils.TruncateAt.END).toString()

    override fun onDraw(canvas: Canvas) {
        now.time = System.currentTimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        when (layout) {
            MATTED -> drawMatted(canvas, w, h)
            SPLIT -> drawSplit(canvas, w, h)
            PIGMENTS -> drawPigments(canvas, w, h)
            else -> drawFullBleed(canvas, w, h)
        }
        drawLearn(canvas, w, h)

        val target = if (learnOpen) 1f else 0f
        if (abs(target - learn) > 0.002f) {
            learn += (target - learn) * 0.2f
            postInvalidateOnAnimation()
        } else {
            learn = target
            postInvalidateDelayed(60_000 - now.time % 60_000 + 50)
        }
    }

    // --- A: the painting is the whole screen ---------------------------------------------

    private fun drawFullBleed(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(art.wall)
        dst.set(0f, 0f, w, h)
        drawCover(canvas)
        canvas.drawRect(0f, 0f, w, h * 0.3f, scrimTop)
        canvas.drawRect(0f, h * 0.48f, w, h, scrimBottom)

        drawPlaque(canvas, 52f, 44f, 520f, WHITE, shadow = true)

        var y = 62f
        for (p in projects) {
            val head = "${p.name} · ${word(p.status)}"
            canvas.drawText(head, w - 52f, y, type(20f, bold, WHITE, align = Paint.Align.RIGHT, shadow = true))
            drawMark(canvas, p.status, w - 52f - text.measureText(head) - 20f, y - 7f)
            canvas.drawText(fit(p.note, type(17f, sans, WHITE, 0.8f, align = Paint.Align.RIGHT, shadow = true), 420f), w - 52f, y + 24f, text)
            y += 62f
        }

        val time = clock.format(now)
        canvas.drawText(time, 40f, h - 44f, type(186f, thin, WHITE, spacing = -0.02f, shadow = true))
        val x = 40f + text.measureText(time) + 26f
        canvas.drawText(weekday.format(now).uppercase(), x, h - 82f, mono(WHITE, shadow = true, size = 16f))
        canvas.drawText(dayMonth.format(now).uppercase(), x, h - 56f, mono(WHITE, shadow = true, size = 16f))
    }

    // --- B: a matted print on a wall the painting's colour --------------------------------

    private fun drawMatted(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(art.wall)
        for (i in 1..4) {
            fill.color = 0x16000000
            rect.set(70f - 3f * i, 70f, 634f + 3f * i, 634f + 10f + 6f * i)
            canvas.drawRoundRect(rect, 6f, 6f, fill)
        }
        fill.color = 0xFF1C1612.toInt()
        canvas.drawRect(70f, 70f, 634f, 634f, fill)
        fill.color = MAT
        canvas.drawRect(78f, 78f, 626f, 626f, fill)
        dst.set(134f, 134f, 570f, 570f)
        drawFitted(canvas)
        line.color = 0x40000000
        line.strokeWidth = 1f
        canvas.drawRect(dst, line)

        canvas.drawText(fit(art.title, type(26f, italic, art.paper), 400f), 70f, 682f, text)
        canvas.drawText(fit(byline(), type(16f, sans, art.paper, 0.72f), 400f), 70f, 708f, text)
        canvas.drawText("LEARN MORE ›", 634f, 682f, mono(art.paper, 0.85f, 13f, Paint.Align.RIGHT))
        plaque.set(60f, 648f, 644f, 724f)

        val x = 716f
        val right = w - 70f
        canvas.drawText(clock.format(now), x - 8f, 206f, type(176f, thin, art.paper, spacing = -0.02f))
        canvas.drawText("${weekday.format(now)} · ${dayMonth.format(now)}".uppercase(), x, 252f, mono(art.paper, 0.72f, 16f))
        rule(canvas, x, right, 290f)

        val top = 736f - projects.size * 62f
        if (projects.isNotEmpty()) canvas.drawText("IN THE STUDIO", x, top - 30f, mono(art.paper, 0.55f, 14f))
        var y = top
        for (p in projects) {
            drawMark(canvas, p.status, x + 7f, y + 13f)
            canvas.drawText(p.name, x + 30f, y + 20f, type(21f, bold, art.paper))
            val after = x + 30f + text.measureText(p.name)
            canvas.drawText(" · ${word(p.status)}", after, y + 20f, type(21f, sans, art.paper, 0.85f))
            canvas.drawText(fit(p.note, type(17f, sans, art.paper, 0.68f), right - x - 30f), x + 30f, y + 44f, text)
            y += 62f
        }
    }

    // --- C: painting left, a dark panel in its deepest colour right ------------------------

    private fun drawSplit(canvas: Canvas, w: Float, h: Float) {
        val split = w * 0.6f
        canvas.drawColor(art.ink)
        dst.set(0f, 0f, split, h)
        drawCover(canvas)

        val x = split + 42f
        val right = w - 44f
        canvas.drawText(hour.format(now), x - 10f, 206f, type(218f, heavy, art.accent, spacing = -0.05f))
        canvas.drawText(minute.format(now), x - 10f, 392f, type(218f, heavy, art.paper, spacing = -0.05f))
        canvas.drawText(shortDate.format(now).uppercase(), x, 442f, mono(art.paper, 0.7f, 16f))

        var y = h - 40f - projects.size * 58f
        projects.forEachIndexed { i, p ->
            drawMark(canvas, p.status, x + 7f, y + 12f)
            canvas.drawText(p.name, x + 28f, y + 18f, type(18f, bold, art.paper))
            val after = x + 28f + text.measureText(p.name)
            canvas.drawText(fit(" · ${p.note}", type(18f, sans, art.paper, 0.75f), right - after), after, y + 18f, text)
            // Progress as a brush stroke in one of the painting's own pigments.
            line.strokeCap = Paint.Cap.ROUND
            line.strokeWidth = 2f
            line.color = withAlpha(art.paper, 0.14f)
            canvas.drawLine(x, y + 38f, right, y + 38f, line)
            val progress = p.progress ?: if (p.status == "done") 1f else null
            if (progress != null && progress > 0f) {
                val end = x + (right - x) * progress
                val bend = if (i % 2 == 0) -5f else 5f
                path.rewind()
                path.moveTo(x + 4f, y + 38f)
                path.cubicTo(x + (end - x) * 0.3f, y + 38f + bend, x + (end - x) * 0.7f, y + 38f - bend, end - 4f, y + 38f)
                line.strokeWidth = 9f
                line.color = visibleOn(art.ink, art.swatches[i % art.swatches.size])
                canvas.drawPath(path, line)
            }
            y += 58f
        }
        line.strokeCap = Paint.Cap.BUTT

        fill.color = 0x99101010.toInt()
        rect.set(34f, h - 132f, 34f + 440f, h - 34f)
        canvas.drawRoundRect(rect, 6f, 6f, fill)
        drawPlaque(canvas, 56f, h - 118f, 396f, WHITE, shadow = false)
        plaque.set(rect)
    }

    // --- D: the painting reduced to its colours, one band per project ----------------------

    private fun drawPigments(canvas: Canvas, w: Float, h: Float) {
        val edges = floatArrayOf(0f, 0.22f, 0.44f, 0.56f, 0.78f, 1f)
        val slots = intArrayOf(0, 1, -1, 2, 3)
        for (band in 0 until 5) {
            val left = edges[band] * w
            val right = edges[band + 1] * w
            val color = art.swatches[band % art.swatches.size]
            val on = if (luminance(color) > 0.6f) art.ink else WHITE
            fill.color = color
            canvas.drawRect(left, 0f, right, h, fill)

            val p = projects.getOrNull(slots[band])
            if (slots[band] == -1) {
                val cx = (left + right) / 2
                fill.color = MAT
                canvas.drawRect(cx - 57f, h - 250f, cx + 57f, h - 136f, fill)
                dst.set(cx - 52f, h - 245f, cx + 52f, h - 141f)
                drawCover(canvas)
                val title = StaticLayout.Builder.obtain(art.title, 0, art.title.length, type(17f, italic, on), (right - left - 24f).toInt())
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
                canvas.save()
                canvas.translate(left + 12f, h - 122f)
                title.draw(canvas)
                canvas.restore()
                canvas.drawText("LEARN MORE ›", cx, h - 40f, mono(on, 0.85f, 11f, Paint.Align.CENTER))
                plaque.set(left, h - 262f, right, h)
            } else if (p != null) {
                val progress = p.progress ?: if (p.status == "done") 1f else 0f
                if (progress > 0f) {
                    fill.color = withAlpha(on, 0.12f)
                    canvas.drawRect(left, h * (1f - progress), right, h, fill)
                    fill.color = withAlpha(on, 0.4f)
                    canvas.drawRect(left, h * (1f - progress), right, h * (1f - progress) + 1.5f, fill)
                }
                val status = "${word(p.status)} · ${p.note}"
                val note = StaticLayout.Builder.obtain(status, 0, status.length, type(16f, sans, on, 0.82f), (right - left - 52f).toInt())
                    .setLineSpacing(0f, 1.25f).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build()
                val top = h - 36f - note.height
                canvas.save()
                canvas.translate(left + 26f, top)
                note.draw(canvas)
                canvas.restore()
                drawMark(canvas, p.status, left + 33f, top - 22f)
                canvas.drawText(fit(p.name, type(25f, bold, on), right - left - 80f), left + 52f, top - 13f, text)
            }
        }
        canvas.drawText(clock.format(now), w / 2, 320f, type(290f, thin, WHITE, spacing = -0.03f, align = Paint.Align.CENTER, shadow = true))
    }

    // --- shared pieces --------------------------------------------------------------------

    private fun byline() = listOf(art.artist, art.date).filter { it.isNotEmpty() }.joinToString(", ")

    /** The museum label: title, artist, and the way into "learn more". Remembers where it is for taps. */
    private fun drawPlaque(canvas: Canvas, x: Float, y: Float, width: Float, color: Int, shadow: Boolean) {
        canvas.drawText(fit(art.title, type(27f, italic, color, shadow = shadow), width), x, y + 26f, text)
        canvas.drawText(fit(byline(), type(16f, sans, color, 0.8f, shadow = shadow), width), x, y + 52f, text)
        canvas.drawText("LEARN MORE ›", x, y + 80f, mono(color, 0.9f, 13f, shadow = shadow))
        plaque.set(x - 16f, y - 10f, x + width, y + 96f)
    }

    private fun drawCover(canvas: Canvas) {
        val image = art.image ?: return
        val scale = max(dst.width() / image.width, dst.height() / image.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        src.set(((image.width - sw) / 2).toInt(), ((image.height - sh) / 2).toInt(), ((image.width + sw) / 2).toInt(), ((image.height + sh) / 2).toInt())
        canvas.drawBitmap(image, src, dst, picture)
    }

    /** Shrinks `dst` to the painting's own proportions, so nothing is cropped. */
    private fun drawFitted(canvas: Canvas) {
        val image = art.image ?: return
        val scale = min(dst.width() / image.width, dst.height() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        dst.set(dst.centerX() - w / 2, dst.centerY() - h / 2, dst.centerX() + w / 2, dst.centerY() + h / 2)
        canvas.drawBitmap(image, null, dst, picture)
    }

    private fun rule(canvas: Canvas, x0: Float, x1: Float, y: Float) {
        line.color = withAlpha(art.paper, 0.3f)
        line.strokeWidth = 1f
        canvas.drawLine(x0, y, x1, y, line)
    }

    // Shape carries the meaning as well as colour: dot, diamond, square, ring.
    private fun drawMark(canvas: Canvas, status: String, cx: Float, cy: Float) {
        val r = 6.5f
        when (status) {
            "needs_you" -> {
                fill.color = 0xFFFFC35C.toInt()
                canvas.save()
                canvas.rotate(45f, cx, cy)
                canvas.drawRect(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f, fill)
                canvas.restore()
            }
            "blocked" -> {
                fill.color = 0xFFFF6B5E.toInt()
                canvas.drawRect(cx - r, cy - r, cx + r, cy + r, fill)
            }
            "done" -> {
                line.color = 0xFFB9C0C7.toInt()
                line.strokeWidth = 2.2f
                canvas.drawCircle(cx, cy, r - 1f, line)
            }
            else -> {
                fill.color = 0xFF57D39B.toInt()
                canvas.drawCircle(cx, cy, r, fill)
            }
        }
    }

    private fun word(status: String) = status.replace('_', ' ')

    private fun drawLearn(canvas: Canvas, w: Float, h: Float) {
        if (learn <= 0.001f) return
        val x = w - LEARN_W * learn
        fill.color = 0xF40E0E10.toInt()
        canvas.drawRect(x, 0f, w, h, fill)
        val left = x + 52f
        canvas.drawText("ART OF THE DAY", left, 78f, mono(WHITE, 0.6f, 14f))
        var y = 104f
        learnTitle?.let {
            type(42f, italic, WHITE)
            canvas.save()
            canvas.translate(left, y)
            it.draw(canvas)
            canvas.restore()
            y += it.height + 34f
        }
        canvas.drawText(fit(byline(), type(20f, sans, WHITE, 0.85f), LEARN_TEXT_W.toFloat()), left, y, text)
        y += 28f
        canvas.drawText(fit(listOf(art.medium, art.credit).filter { it.isNotEmpty() }.joinToString(" · "), type(16f, sans, WHITE, 0.6f), LEARN_TEXT_W.toFloat()), left, y, text)
        y += 26f
        learnText?.let {
            type(19f, sans, WHITE, 0.92f)
            canvas.save()
            canvas.clipRect(left, y, left + LEARN_TEXT_W, h - 190f)
            canvas.translate(left, y)
            it.draw(canvas)
            canvas.restore()
        }
        art.qr?.let {
            dst.set(left, h - 164f, left + 116f, h - 48f)
            picture.isFilterBitmap = false
            canvas.drawBitmap(it, null, dst, picture)
            picture.isFilterBitmap = true
            canvas.drawText("Scan to open the museum's", left + 140f, h - 98f, type(17f, sans, WHITE, 0.7f))
            canvas.drawText("page on your phone.", left + 140f, h - 74f, text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        when {
            learnOpen -> learnOpen = false
            plaque.contains(event.x, event.y) -> learnOpen = true
            else -> {
                layout += 1
                onLayoutChosen?.invoke(layout)
            }
        }
        invalidate()
        return true
    }

    companion object {
        const val FULL_BLEED = 0
        const val MATTED = 1
        const val SPLIT = 2
        const val PIGMENTS = 3
        private const val LAYOUTS = 4
        private const val MAX_PROJECTS = 4
        private const val LEARN_W = 564f
        private const val LEARN_TEXT_W = 460
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val MAT = 0xFFF5F0E6.toInt()

        /** What the dashboard wears before the first painting arrives. */
        private val NO_ART = Art(
            "Waiting for today's painting", "", "", "", "", "", null, null,
            ink = 0xFF101418.toInt(), paper = 0xFFF1EDE4.toInt(), wall = 0xFF1E2A33.toInt(), accent = 0xFFE8B86A.toInt(),
            swatches = intArrayOf(0xFF27404F.toInt(), 0xFF7A8C8E.toInt(), 0xFFD9C7A3.toInt(), 0xFFB4654A.toInt(), 0xFF1B2730.toInt()),
        )

        fun luminance(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

        private fun withAlpha(c: Int, a: Float) = Color.argb((255 * a).toInt(), Color.red(c), Color.green(c), Color.blue(c))

        /** A pigment too close to the ground it sits on is lifted toward white until it reads. */
        private fun visibleOn(ground: Int, c: Int): Int {
            if (luminance(c) - luminance(ground) > 0.25f) return c
            val t = 0.45f
            return Color.rgb(
                (Color.red(c) + (255 - Color.red(c)) * t).toInt(),
                (Color.green(c) + (255 - Color.green(c)) * t).toInt(),
                (Color.blue(c) + (255 - Color.blue(c)) * t).toInt(),
            )
        }
    }
}

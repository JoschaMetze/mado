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
 * about it, the projects or the weather to open their pages; tap anywhere else to move to the
 * next layout.
 *
 * It is still unless something changes: one redraw a minute for the clock. Before the first
 * painting arrives it says what the frame is waiting for, and a ring turns where the painting
 * will hang.
 */
class DashboardView(context: Context) : View(context) {
    /**
     * `updated` is in seconds since the epoch, 0 when the hub did not say. `iconKey` names the
     * project's picture on the hub ("" when it has none) and `icon` is that picture once it has
     * been fetched; until then, and without one, `initials` are drawn on a disc of `tint`.
     */
    class Project(
        val name: String, val status: String, val note: String, val progress: Float?, val updated: Long = 0,
        val initials: String = "", val tint: Int = Color.GRAY, val iconKey: String = "",
    ) {
        var icon: Bitmap? = null
    }

    class Art(
        val title: String, val artist: String, val date: String, val medium: String, val credit: String,
        val text: String, val image: Bitmap?, val qr: Bitmap?,
        val ink: Int, val paper: Int, val wall: Int, val accent: Int, val swatches: IntArray,
    )

    /** What a player has on, for the line by the date. */
    data class Music(val title: String, val artist: String, val playing: Boolean)

    var onLayoutChosen: ((Int) -> Unit)? = null
    /** The line about the music was tapped. */
    var onMusic: (() -> Unit)? = null
    var onProjects: (() -> Unit)? = null
    /** The line about the next meeting was tapped. */
    var onAgenda: (() -> Unit)? = null
    /** A place's weather was tapped, at this point on the glass. */
    var onWeather: ((Weather, Float, Float) -> Unit)? = null
    var layout = FULL_BLEED
        set(value) {
            field = value % LAYOUTS
            invalidate()
        }

    private var art = NO_ART
    private var hasArt = false
    private var startup = Startup(NO_ART.title, "", false)
    /** False for the first moments after power-up, when the clock reads 1970. */
    private var clockSet = true
    private var projects: List<Project> = emptyList()
    private var weather: Weather? = null
    private class Away(val weather: Weather) {
        val clock = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone(weather.timezone) }
    }

    private var away: List<Away> = emptyList()
    private var music: Music? = null
    private var agenda: Agenda? = null
    /** Where the next-up card is in the current layout, for taps; empty when it is not drawn. */
    private val upcoming = RectF()
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
    private val letters = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val scrimTop = Paint()
    private val scrimBottom = Paint()
    private val src = Rect()
    private val bounds = Rect()
    private val dst = RectF()
    private val rect = RectF()
    private val plaque = RectF()
    /** Where the projects are in the current layout, for taps; the pigments layout has two groups. */
    private val studio = RectF()
    private val studio2 = RectF()
    /** Where the line about the music is in the current layout, for taps. */
    private val tune = RectF()
    /** Where each place's weather is in the current layout, for taps. */
    private val spots = ArrayList<Pair<RectF, Weather>>()
    /** Where the words are in the current layout, so Tama can keep off them. Rebuilt on every draw. */
    val textZones = ArrayList<RectF>()
    private val path = Path()
    private val moon = Path()
    private val bite = Path()
    private val line2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
        strokeCap = Paint.Cap.ROUND
    }

    fun setArt(art: Art?) {
        hasArt = art != null
        show(art ?: waitingArt())
    }

    /** What to say until a painting is up; after that it is not shown. */
    fun setStartup(startup: Startup) {
        if (startup.title == this.startup.title && startup.detail == this.startup.detail && startup.busy == this.startup.busy) return
        this.startup = startup
        if (!hasArt) show(waitingArt())
    }

    /** The status dressed as a painting's label, so every layout shows it where the title goes. */
    private fun waitingArt() = Art(
        startup.title, startup.detail, "", "", "", "", null, null,
        NO_ART.ink, NO_ART.paper, NO_ART.wall, NO_ART.accent, NO_ART.swatches,
    )

    private fun show(art: Art) {
        this.art = art
        val a = art
        learnTitle = StaticLayout.Builder.obtain(a.title, 0, a.title.length, type(42f, italic, WHITE), LEARN_TEXT_W)
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
        learnText = StaticLayout.Builder.obtain(a.text, 0, a.text.length, type(19f, sans, WHITE), LEARN_TEXT_W)
            .setLineSpacing(0f, 1.42f).setMaxLines(11).setEllipsize(TextUtils.TruncateAt.END).build()
        invalidate()
    }

    fun setWeather(weather: Weather?) {
        this.weather = weather
        invalidate()
    }

    /** Weather for other places the owner cares about, each shown with its local time. */
    fun setAway(places: List<Weather>) {
        away = places.map(::Away)
        invalidate()
    }

    /** The track a player has on, or null when none has one; it gets a line by the date. */
    fun setMusic(music: Music?) {
        if (music == this.music) return
        this.music = music
        invalidate()
    }

    /** The owner's calendar; the next meeting gets the music's line while nothing is playing. */
    fun setAgenda(agenda: Agenda?) {
        this.agenda = agenda
        invalidate()
    }

    /** A row of the next-up card: a meeting, or a divider that says the rows below are tomorrow's. */
    private class Up(val event: AgendaEvent?, val label: String = "")

    /** What the next-up card lists: the meeting on now and the ones still to come today, then tomorrow's first ones. */
    private fun upcomingRows(nowMs: Long, most: Int): Pair<String, List<Up>> {
        val a = agenda ?: return "" to emptyList()
        val today = a.on(nowMs).filter { it.counts && it.endMs > nowMs }.take(most)
        val rows = today.map { Up(it) }.toMutableList()
        if (rows.size < most) {
            val tomorrow = a.on(a.dayStart(nowMs) + Agenda.DAY_MS).filter { it.counts }.take(most - rows.size)
            if (tomorrow.isNotEmpty()) {
                if (rows.isNotEmpty()) rows.add(Up(null, "TOMORROW"))
                rows.addAll(tomorrow.map { Up(it) })
            }
        }
        return (if (today.isEmpty()) "TOMORROW" else "NEXT UP") to rows
    }

    /**
     * A small card with the next few meetings: a heading, then a row each with the time (or NOW),
     * the title and where it is. On a painting it gets a dark plaque behind it. Returns its height,
     * 0 when there was nothing to show. Tapping it opens the agenda.
     */
    private fun drawUpcoming(canvas: Canvas, x: Float, y: Float, width: Float, color: Int, most: Int, plaque: Boolean, shadow: Boolean): Float {
        val (heading, rows) = upcomingRows(now.time, most)
        if (rows.isEmpty()) return 0f
        val a = agenda ?: return 0f
        val height = 40f + rows.sumOf { (if (it.event == null) UP_DIVIDER else UP_ROW).toDouble() }.toFloat()
        if (plaque) {
            fill.color = 0x70000000
            rect.set(x - 22f, y - 26f, x + width + 22f, y + height + 4f)
            canvas.drawRoundRect(rect, 14f, 14f, fill)
        }
        canvas.drawText(heading, x, y, mono(color, 0.7f, 14f, shadow = shadow))
        var ry = y + 44f
        for (row in rows) {
            val e = row.event
            if (e == null) {
                canvas.drawText(row.label, x, ry - 8f, mono(color, 0.55f, 13f, shadow = shadow))
                ry += UP_DIVIDER
                continue
            }
            val on = e.isOn(now.time)
            val lead = if (on) "NOW" else a.time(e.startMs)
            canvas.drawText(lead, x, ry, mono(color, if (on) 1f else 0.8f, 17f, shadow = shadow))
            if (on) {
                fill.color = withAlpha(color, 0.9f)
                canvas.drawCircle(x - 11f, ry - 7f, 4f, fill)
            }
            // A narrow column keeps its room for the title.
            val place = if (width < 520f) "" else e.location.uppercase()
            val placePaint = mono(color, 0.6f, 13f, Paint.Align.RIGHT, shadow = shadow)
            val placeW = if (place.isEmpty()) 0f else text.measureText(place) + 18f
            if (place.isNotEmpty()) canvas.drawText(place, x + width, ry, placePaint)
            val titlePaint = type(27f, if (on) bold else sans, color, if (on) 1f else 0.92f, shadow = shadow)
            canvas.drawText(fit(e.title, titlePaint, width - UP_TIME_W - placeW), x + UP_TIME_W, ry, text)
            ry += UP_ROW
        }
        upcoming.set(x - 22f, y - 26f, x + width + 22f, y + height + 4f)
        quiet(upcoming.left, upcoming.top, upcoming.right, upcoming.bottom)
        return height
    }

    /** The track on one line, led by a small note; fainter while it is paused. Tapping it opens now playing. */
    private fun drawMusic(canvas: Canvas, x: Float, y: Float, maxWidth: Float, color: Int, alpha: Float, align: Paint.Align, shadow: Boolean) {
        val on = music ?: return
        val a = if (on.playing) alpha else alpha * 0.7f
        val words = listOf(on.title, on.artist).filter { it.isNotEmpty() }.joinToString(" · ").uppercase()
        val line = fit(words, mono(color, a, 14f, shadow = shadow), maxWidth - NOTE_W)
        val width = text.measureText(line) + NOTE_W
        val left = when (align) {
            Paint.Align.RIGHT -> x - width
            Paint.Align.CENTER -> x - width / 2
            else -> x
        }
        fill.color = withAlpha(color, a)
        line2.color = fill.color
        canvas.drawCircle(left + 5f, y - 2f, 3.4f, fill)
        canvas.drawLine(left + 7.8f, y - 2f, left + 7.8f, y - 13f, line2)
        canvas.drawLine(left + 7.8f, y - 13f, left + 12.5f, y - 10f, line2)
        canvas.drawText(line, left + NOTE_W, y, text)
        // Taller than the words, for a finger.
        tune.set(left - 16f, y - 30f, left + width + 16f, y + 16f)
        quiet(left - 10f, y - 20f, left + width + 10f, y + 10f)
    }

    /** Another place on one line, led by a small sun or moon for whether it is day there. */
    private fun drawAway(
        canvas: Canvas, from: Away, x: Float, y: Float, size: Float, color: Int, alpha: Float, align: Paint.Align, shadow: Boolean, maxWidth: Float,
        /** Left-aligned rows only: name and time stay left, temperature and forecast go to the right edge. */
        spread: Boolean = false,
    ) {
        val place = from.weather
        val where = "${place.place} ${from.clock.format(now)}"
        val what = "${place.tempC}° · ${place.sentence.replaceFirstChar(Char::lowercase)}"
        val line = if (spread) where else fit("$where · $what", type(size, sans, color, alpha, shadow = shadow), maxWidth - SKY_W)
        val width = type(size, sans, color, alpha, shadow = shadow).measureText(line) + SKY_W
        val left = when (align) {
            Paint.Align.RIGHT -> x - width
            Paint.Align.CENTER -> x - width / 2
            else -> x
        }
        val cx = left + 7f
        val cy = y - size * 0.34f
        fill.color = withAlpha(color, alpha)
        if (place.isDay) {
            canvas.drawCircle(cx, cy, 4.2f, fill)
            line2.color = fill.color
            for (i in 0 until 8) {
                canvas.save()
                canvas.rotate(45f * i, cx, cy)
                canvas.drawLine(cx, cy - 6.2f, cx, cy - 8.4f, line2)
                canvas.restore()
            }
        } else {
            moon.rewind()
            moon.addCircle(cx, cy, 6.5f, Path.Direction.CW)
            bite.rewind()
            bite.addCircle(cx + 4f, cy - 2.5f, 5.8f, Path.Direction.CW)
            moon.op(bite, Path.Op.DIFFERENCE)
            canvas.drawPath(moon, fill)
        }
        canvas.drawText(line, left + SKY_W, y, type(size, sans, color, alpha, shadow = shadow))
        spots.add(RectF(left - 12f, y - size - 5f, if (spread) x + maxWidth else left + width + 12f, y + 9f) to place)
        if (spread) {
            val room = maxWidth - width - 24f
            canvas.drawText(fit(what, text, room), x + maxWidth, y, type(size, sans, color, alpha, align = Paint.Align.RIGHT, shadow = shadow))
        }
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
        clockSet = now.time > CLOCK_SET_AFTER_MS
        val w = width.toFloat()
        val h = height.toFloat()
        studio.setEmpty()
        studio2.setEmpty()
        tune.setEmpty()
        upcoming.setEmpty()
        textZones.clear()
        spots.clear()
        when (layout) {
            MATTED -> drawMatted(canvas, w, h)
            SPLIT -> drawSplit(canvas, w, h)
            PIGMENTS -> drawPigments(canvas, w, h)
            else -> drawFullBleed(canvas, w, h)
        }
        drawLearn(canvas, w, h)
        for (r in arrayOf(plaque, studio, studio2)) if (!r.isEmpty) textZones.add(RectF(r))
        if (learn > 0.5f) quiet(w - LEARN_W, 0f, w, h)

        val target = if (learnOpen) 1f else 0f
        if (abs(target - learn) > 0.002f) {
            learn += (target - learn) * 0.2f
            postInvalidateOnAnimation()
        } else {
            learn = target
            if (!hasArt && startup.busy) postInvalidateOnAnimation() else postInvalidateDelayed(60_000 - now.time % 60_000 + 50)
        }
    }

    // Until the network has set the clock there is no time to show, only that there is none.
    private fun time() = if (clockSet) clock.format(now) else "--:--"

    private fun day(format: SimpleDateFormat) = if (clockSet) format.format(now) else ""

    private fun quiet(left: Float, top: Float, right: Float, bottom: Float) {
        textZones.add(RectF(left, top, right, bottom))
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
            val edge = w - 52f - 38f - 14f
            drawIcon(canvas, p, w - 52f - 38f, y - 12f, 38f)
            canvas.drawText(head, edge, y, type(20f, bold, WHITE, align = Paint.Align.RIGHT, shadow = true))
            drawMark(canvas, p.status, edge - text.measureText(head) - 20f, y - 7f)
            canvas.drawText(fit(p.note, type(17f, sans, WHITE, 0.8f, align = Paint.Align.RIGHT, shadow = true), 420f), edge, y + 24f, text)
            y += 62f
        }
        if (projects.isNotEmpty()) studio.set(w - 564f, 24f, w, y - 14f)
        drawUpcoming(canvas, w - 52f - 600f, (if (projects.isEmpty()) 70f else y + 30f), 600f, WHITE, 4, plaque = true, shadow = true)

        val time = time()
        canvas.drawText(time, 40f, h - 44f, type(186f, thin, WHITE, spacing = -0.02f, shadow = true))
        val x = 40f + text.measureText(time) + 26f
        // The date moves up a line to let the music in under it.
        val up = if (music == null) 0f else 26f
        canvas.drawText(day(weekday).uppercase(), x, h - 82f - up, mono(WHITE, shadow = true, size = 16f))
        canvas.drawText(day(dayMonth).uppercase(), x, h - 56f - up, mono(WHITE, shadow = true, size = 16f))
        drawMusic(canvas, x, h - 56f, min(520f, w - x - 420f), WHITE, 0.9f, Paint.Align.LEFT, shadow = true)
        quiet(30f, h - 188f, x + 190f, h - 24f)

        val awayA = away.take(3)
        weather?.let {
            val lift = if (awayA.isEmpty()) 0f else 12f + (awayA.size - 1) * AWAY_STEP
            canvas.drawText("${it.tempC}°", w - 46f, h - 98f - lift, type(78f, thin, WHITE, align = Paint.Align.RIGHT, shadow = true))
            canvas.drawText(it.sentence, w - 52f, h - 54f - lift, type(28f, italic, WHITE, align = Paint.Align.RIGHT, shadow = true))
            // Two zones, so the empty corner beside the temperature stays free.
            quiet(w - 200f, h - 162f - lift, w - 30f, h - 90f - lift)
            quiet(w - 72f - max(text.measureText(it.sentence), 280f), h - 90f - lift, w - 30f, h - 8f)
            spots.add(RectF(w - 72f - max(text.measureText(it.sentence), 280f), h - 170f - lift, w, h - 36f - lift) to it)
        }
        if (awayA.isNotEmpty()) hairline(canvas, w - 52f - 260f, w - 52f, h - 50f - (awayA.size - 1) * AWAY_STEP, WHITE, 0.45f)
        awayA.forEachIndexed { i, place ->
            drawAway(canvas, place, w - 52f, h - 24f - (awayA.size - 1 - i) * AWAY_STEP, 17f, WHITE, 0.88f, Paint.Align.RIGHT, true, 620f)
        }
        if (awayA.isNotEmpty()) quiet(w - 560f, h - 62f - (awayA.size - 1) * AWAY_STEP, w - 30f, h - 8f)
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
        val time = time()
        canvas.drawText(time, x - 8f, 206f, type(176f, thin, art.paper, spacing = -0.02f))
        // The date sits centred under the numerals themselves.
        text.getTextBounds(time, 0, time.length, bounds)
        val middle = x - 8f + (bounds.left + bounds.right) / 2f
        if (clockSet) canvas.drawText("${weekday.format(now)} · ${dayMonth.format(now)}".uppercase(), middle, 252f, mono(art.paper, 0.72f, 16f, Paint.Align.CENTER))
        drawMusic(canvas, x, 277f, right - x, art.paper, 0.72f, Paint.Align.LEFT, shadow = false)
        rule(canvas, x, right, 290f)
        var sectionEnd = 290f
        weather?.let {
            canvas.drawText(fit(it.sentence + ".", type(34f, italic, art.paper), right - x), x, 346f, text)
            canvas.drawText(fit(it.detail, type(17f, sans, art.paper, 0.72f), right - x), x, 378f, text)
            rule(canvas, x, right, 402f)
            sectionEnd = 402f
            spots.add(RectF(x - 16f, 296f, w, 402f) to it)
            // As many other places as fit above the projects; always at least one.
            val room = ((736f - projects.size * 62f - 30f - 34f - 431f) / AWAY_STEP).toInt() + 1
            val shown = away.take(room.coerceIn(1, 4))
            shown.forEachIndexed { i, place ->
                drawAway(canvas, place, x, 431f + i * AWAY_STEP, 17f, art.paper, 0.72f, Paint.Align.LEFT, false, right - x, spread = true)
            }
            if (shown.isNotEmpty()) {
                sectionEnd = 448f + (shown.size - 1) * AWAY_STEP
                rule(canvas, x, right, sectionEnd)
            }
        }

        quiet(x - 16f, 50f, w, sectionEnd + 12f)
        val top = 736f - projects.size * 62f
        run {
            val room = ((top - 30f - 20f - (sectionEnd + 52f)) / UP_ROW).toInt()
            if (room >= 1) drawUpcoming(canvas, x, sectionEnd + 52f, right - x, art.paper, min(room, 4), plaque = false, shadow = false)
        }
        // With four projects and other places above, there is no room left for the heading.
        if (projects.isNotEmpty() && top - 30f - sectionEnd >= 34f) canvas.drawText("IN THE STUDIO", x, top - 30f, mono(art.paper, 0.55f, 14f))
        var y = top
        for (p in projects) {
            drawIcon(canvas, p, x, y + 7f, 38f)
            drawMark(canvas, p.status, x + 59f, y + 26f)
            canvas.drawText(p.name, x + 82f, y + 20f, type(21f, bold, art.paper))
            val after = x + 82f + text.measureText(p.name)
            canvas.drawText(" · ${word(p.status)}", after, y + 20f, type(21f, sans, art.paper, 0.85f))
            canvas.drawText(fit(p.note, type(17f, sans, art.paper, 0.68f), right - x - 82f), x + 82f, y + 44f, text)
            y += 62f
        }
        if (projects.isNotEmpty()) studio.set(x - 16f, top - 16f, w, y + 10f)
    }

    // --- C: painting left, a dark panel in its deepest colour right ------------------------

    private fun drawSplit(canvas: Canvas, w: Float, h: Float) {
        val split = w * 0.6f
        canvas.drawColor(art.ink)
        dst.set(0f, 0f, split, h)
        drawCover(canvas)

        val x = split + 42f
        val right = w - 44f
        canvas.drawText(if (clockSet) hour.format(now) else "--", x - 10f, 206f, type(218f, heavy, art.accent, spacing = -0.05f))
        canvas.drawText(if (clockSet) minute.format(now) else "--", x - 10f, 392f, type(218f, heavy, art.paper, spacing = -0.05f))
        val date = day(shortDate).uppercase()
        canvas.drawText(date, x, 438f, mono(art.paper, 0.7f, 16f))
        drawMusic(canvas, right, 438f, right - x - text.measureText(date) - 24f, art.paper, 0.7f, Paint.Align.RIGHT, shadow = false)
        weather?.let {
            canvas.drawText(fit("${it.tempC}° · ${it.sentence.replaceFirstChar(Char::lowercase)}", type(23f, bold, art.paper), right - x), x, 480f, text)
            spots.add(RectF(split, 450f, w, 492f) to it)
            // Above four projects there is room for one more line; with fewer, for more places.
            val room = ((h - 40f - projects.size * 58f - 16f - 514f) / AWAY_STEP).toInt() + 1
            val shown = away.take(room.coerceIn(1, 4))
            if (shown.isNotEmpty()) {
                hairline(canvas, x, right, 493f, art.paper, 0.2f)
                shown.forEachIndexed { i, place ->
                    drawAway(canvas, place, x, 514f + i * AWAY_STEP, 16f, art.paper, 0.7f, Paint.Align.LEFT, false, right - x, spread = true)
                }
            } else {
                canvas.drawText(fit("${it.highC}° high" + if (it.sunset.isEmpty()) "" else " · sunset ${it.sunset}", type(16f, sans, art.paper, 0.7f), right - x), x, 506f, text)
            }
        }

        // Clock, weather and projects nearly fill the panel, so all of it is off limits.
        quiet(split, 0f, w, h)
        var y = h - 40f - projects.size * 58f
        run {
            val shownAway = if (weather == null) 0 else min(away.size, 4)
            val from = if (weather == null) 470f else if (shownAway > 0) 514f + shownAway * AWAY_STEP + 18f else 540f
            val room = ((y - 36f - from - 30f) / UP_ROW).toInt()
            if (room >= 1) drawUpcoming(canvas, x, from + 20f, right - x, art.paper, min(room, 4), plaque = false, shadow = false)
        }
        projects.forEachIndexed { i, p ->
            drawIcon(canvas, p, x, y - 2f, 28f)
            drawMark(canvas, p.status, x + 45f, y + 12f)
            canvas.drawText(p.name, x + 66f, y + 18f, type(18f, bold, art.paper))
            val after = x + 66f + text.measureText(p.name)
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
        if (projects.isNotEmpty()) studio.set(split, h - 52f - projects.size * 58f, w, h)

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
                drawCover(canvas, art.ink)
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
                drawIcon(canvas, p, left + 26f, top - 92f, 44f)
                drawMark(canvas, p.status, left + 33f, top - 22f)
                canvas.drawText(fit(p.name, type(25f, bold, on), right - left - 80f), left + 52f, top - 13f, text)
            }
        }
        if (projects.isNotEmpty()) studio.set(0f, h - 262f, edges[2] * w, h)
        if (projects.size > 2) studio2.set(edges[3] * w, h - 262f, w, h)
        canvas.drawText(time(), w / 2, 320f, type(290f, thin, WHITE, spacing = -0.03f, align = Paint.Align.CENTER, shadow = true))
        weather?.let {
            canvas.drawText("${it.tempC}° · ${it.sentence.replaceFirstChar(Char::lowercase)}", w / 2, 396f, type(32f, italic, WHITE, align = Paint.Align.CENTER, shadow = true))
            spots.add(RectF(w / 2 - 390f, 352f, w / 2 + 390f, 412f) to it)
        }
        if (away.isNotEmpty()) hairline(canvas, w / 2 - 130f, w / 2 + 130f, 412f, WHITE, 0.5f)
        away.take(3).forEachIndexed { i, place ->
            drawAway(canvas, place, w / 2, 440f + i * 30f, 19f, WHITE, 0.9f, Paint.Align.CENTER, true, 900f)
        }
        quiet(w / 2 - 390f, 80f, w / 2 + 390f, 420f + min(3, away.size) * 30f)
        drawMusic(canvas, w / 2, 444f + min(3, away.size) * 30f, 700f, WHITE, 0.9f, Paint.Align.CENTER, shadow = true)
        run {
            val from = 470f + min(3, away.size) * 30f + (if (music == null) 0f else 30f)
            val room = ((h - 280f - from) / UP_ROW).toInt()
            if (room >= 1) drawUpcoming(canvas, w / 2 - 380f, from + 24f, 760f, WHITE, min(room, 3), plaque = true, shadow = true)
        }
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

    /** `waiting` is the colour of the ring drawn while there is no painting yet. */
    private fun drawCover(canvas: Canvas, waiting: Int = art.paper) {
        val image = art.image ?: return drawWaiting(canvas, waiting)
        val scale = max(dst.width() / image.width, dst.height() / image.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        src.set(((image.width - sw) / 2).toInt(), ((image.height - sh) / 2).toInt(), ((image.width + sw) / 2).toInt(), ((image.height + sh) / 2).toInt())
        canvas.drawBitmap(image, src, dst, picture)
    }

    /** Shrinks `dst` to the painting's own proportions, so nothing is cropped. */
    private fun drawFitted(canvas: Canvas) {
        val image = art.image ?: return drawWaiting(canvas, art.ink)
        val scale = min(dst.width() / image.width, dst.height() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        dst.set(dst.centerX() - w / 2, dst.centerY() - h / 2, dst.centerX() + w / 2, dst.centerY() + h / 2)
        canvas.drawBitmap(image, null, dst, picture)
    }

    /**
     * A ring of dots in the middle of `dst`, the brightest one going round: something is on
     * its way. Circles only: the frame's GPU smudges scaled paths.
     */
    private fun drawWaiting(canvas: Canvas, color: Int) {
        if (hasArt || !startup.busy) return
        val r = min(34f, min(dst.width(), dst.height()) * 0.16f)
        val turn = (android.os.SystemClock.uptimeMillis() % WAITING_TURN_MS) / WAITING_TURN_MS.toFloat()
        for (i in 0 until WAITING_DOTS) {
            val at = i / WAITING_DOTS.toFloat()
            // 1 at the leading dot, falling away behind it.
            val lead = 1f - (turn - at + 1f) % 1f
            fill.color = withAlpha(color, 0.12f + 0.78f * lead * lead)
            val angle = at * 2 * Math.PI - Math.PI / 2
            canvas.drawCircle(
                dst.centerX() + r * Math.cos(angle).toFloat(), dst.centerY() + r * Math.sin(angle).toFloat(),
                r * (0.1f + 0.06f * lead), fill,
            )
        }
    }

    /** A thin separator, as between "here" and the other places. */
    private fun hairline(canvas: Canvas, x0: Float, x1: Float, y: Float, color: Int, alpha: Float) {
        line.color = withAlpha(color, alpha)
        line.strokeWidth = 1f
        canvas.drawLine(x0, y, x1, y, line)
    }

    private fun rule(canvas: Canvas, x0: Float, x1: Float, y: Float) {
        line.color = withAlpha(art.paper, 0.3f)
        line.strokeWidth = 1f
        canvas.drawLine(x0, y, x1, y, line)
    }

    private fun drawMark(canvas: Canvas, status: String, cx: Float, cy: Float) = mark(canvas, status, cx, cy, 6.5f, fill, line)

    private fun drawIcon(canvas: Canvas, p: Project, left: Float, top: Float, size: Float) = icon(canvas, p, left, top, size, fill, letters, picture)

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
        val spot = spots.firstOrNull { it.first.contains(event.x, event.y) }
        when {
            learnOpen -> learnOpen = false
            plaque.contains(event.x, event.y) -> learnOpen = true
            tune.contains(event.x, event.y) -> onMusic?.invoke()
            upcoming.contains(event.x, event.y) -> onAgenda?.invoke()
            spot != null -> onWeather?.invoke(spot.second, event.x, event.y)
            studio.contains(event.x, event.y) || studio2.contains(event.x, event.y) -> onProjects?.invoke()
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
        private const val SKY_W = 22f
        private const val NOTE_W = 22f
        private const val AWAY_STEP = 27f
        private const val UP_ROW = 48f
        private const val UP_DIVIDER = 30f
        private const val UP_TIME_W = 86f
        private const val LEARN_W = 564f
        private const val LEARN_TEXT_W = 460
        private const val WAITING_DOTS = 10
        private const val WAITING_TURN_MS = 1_400L
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val MAT = 0xFFF5F0E6.toInt()

        /** What the dashboard wears before the first painting arrives. */
        val NO_ART = Art(
            "Waiting for today's painting", "", "", "", "", "", null, null,
            ink = 0xFF101418.toInt(), paper = 0xFFF1EDE4.toInt(), wall = 0xFF1E2A33.toInt(), accent = 0xFFE8B86A.toInt(),
            swatches = intArrayOf(0xFF27404F.toInt(), 0xFF7A8C8E.toInt(), 0xFFD9C7A3.toInt(), 0xFFB4654A.toInt(), 0xFF1B2730.toInt()),
        )

        // Shape carries the meaning as well as colour: dot, diamond, square, ring.
        /** `fill` and `line` are the caller's fill and stroke paints; their colours are overwritten. */
        fun mark(canvas: Canvas, status: String, cx: Float, cy: Float, r: Float, fill: Paint, line: Paint) {
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
                    line.strokeWidth = r * 0.34f
                    canvas.drawCircle(cx, cy, r - 1f, line)
                }
                else -> {
                    fill.color = 0xFF57D39B.toInt()
                    canvas.drawCircle(cx, cy, r, fill)
                }
            }
        }

        private val iconBox = RectF()

        /**
         * What a project is recognised by, in a square: its own picture, or its initials on a
         * disc of its colour. `fill`, `letters` and `picture` are the caller's paints for
         * shapes, text and bitmaps; `letters` is overwritten.
         */
        fun icon(canvas: Canvas, p: Project, left: Float, top: Float, size: Float, fill: Paint, letters: TextPaint, picture: Paint) {
            val image = p.icon
            if (image != null) {
                val scale = min(size / image.width, size / image.height)
                val cx = left + size / 2
                val cy = top + size / 2
                iconBox.set(cx - image.width * scale / 2, cy - image.height * scale / 2, cx + image.width * scale / 2, cy + image.height * scale / 2)
                canvas.drawBitmap(image, null, iconBox, picture)
                return
            }
            fill.color = p.tint
            canvas.drawCircle(left + size / 2, top + size / 2, size / 2, fill)
            letters.textSize = size * if (p.initials.length > 1) 0.42f else 0.5f
            letters.typeface = Typeface.DEFAULT_BOLD
            letters.color = WHITE
            letters.textAlign = Paint.Align.CENTER
            canvas.drawText(p.initials, left + size / 2, top + size / 2 - (letters.ascent() + letters.descent()) / 2, letters)
        }

        fun luminance(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

        fun withAlpha(c: Int, a: Float) = Color.argb((255 * a).toInt(), Color.red(c), Color.green(c), Color.blue(c))

        /** A pigment too close to the ground it sits on is lifted toward white until it reads. */
        fun visibleOn(ground: Int, c: Int): Int {
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

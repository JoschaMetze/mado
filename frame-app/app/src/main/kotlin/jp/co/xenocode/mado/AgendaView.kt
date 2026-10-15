package jp.co.xenocode.mado

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * The agenda scene: the time, then today's meetings as a timeline with the one on now marked,
 * and tomorrow beside it when the glass is wide or under it when it is tall. Colours come
 * from the painting, like the other pages. It redraws once a minute; a tap goes home.
 */
class AgendaView(context: Context) : View(context) {
    var onClose: (() -> Unit)? = null

    private var art = DashboardView.NO_ART
    private var agenda = Agenda.EMPTY
    /** Where the words are, so Tama keeps off them. Rebuilt on every draw. */
    val textZones = ArrayList<RectF>()

    private val now = Date()
    private val clock = SimpleDateFormat("HH:mm", Locale.US)
    private val weekday = SimpleDateFormat("EEEE", Locale.US)
    private val dayMonth = SimpleDateFormat("d MMMM", Locale.US)
    private val shortDay = SimpleDateFormat("EEEE d MMM", Locale.US)

    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val italic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    init {
        visibility = GONE
    }

    fun setArt(art: DashboardView.Art) {
        this.art = art
        invalidate()
    }

    fun setAgenda(agenda: Agenda) {
        this.agenda = agenda
        invalidate()
    }

    private fun type(size: Float, face: Typeface, alpha: Float = 1f, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f): TextPaint =
        text.apply {
            textSize = size
            typeface = face
            color = DashboardView.withAlpha(art.paper, alpha)
            textAlign = align
            letterSpacing = spacing
            clearShadowLayer()
        }

    private fun mono(alpha: Float, size: Float, align: Paint.Align = Paint.Align.LEFT) = type(size, Typeface.MONOSPACE, alpha, align, 0.14f)

    private fun fit(s: String, paint: TextPaint, width: Float) = TextUtils.ellipsize(s, paint, width, TextUtils.TruncateAt.END).toString()

    override fun onDraw(canvas: Canvas) {
        now.time = System.currentTimeMillis()
        val nowMs = now.time
        val w = width.toFloat()
        val h = height.toFloat()
        textZones.clear()
        canvas.drawColor(art.ink or 0xFF000000.toInt())

        // The head: time, date, and a sentence about what is next.
        val time = if (nowMs > CLOCK_SET_AFTER_MS) clock.format(now) else "--:--"
        canvas.drawText(time, MARGIN - 6f, 150f, type(150f, thin, spacing = -0.02f))
        val afterClock = MARGIN - 6f + text.measureText(time) + 28f
        canvas.drawText(weekday.format(now).uppercase(), afterClock, 108f, mono(0.7f, 16f))
        canvas.drawText(dayMonth.format(now).uppercase(), afterClock, 134f, mono(0.7f, 16f))
        canvas.drawText("CLOSE ×", w - MARGIN, 78f, mono(0.85f, 13f, Paint.Align.RIGHT))
        canvas.drawText(fit(summary(nowMs), type(26f, italic, 0.9f), w - 2 * MARGIN), MARGIN, 204f, text)
        textZones.add(RectF(0f, 0f, w, 224f))
        rule(canvas, MARGIN, w - MARGIN, 228f, 0.3f)

        // Two columns side by side when there is room, else today over tomorrow.
        val top = 260f
        val gap = 48f
        // Side by side only when lying down; standing up, tomorrow goes under today.
        val columns = if (w > h && w - 2 * MARGIN >= 2 * MIN_COLUMN + gap) 2 else 1
        val colW = if (columns == 2) (w - 2 * MARGIN - gap) / 2 else w - 2 * MARGIN
        val today = agenda.dayStart(nowMs)
        val todayEnd = drawDay(canvas, "TODAY", agenda.on(today), MARGIN, top, colW, h, nowMs, true)
        val tomorrow = today + Agenda.DAY_MS
        if (columns == 2) {
            drawDay(canvas, "TOMORROW · ${shortDay.format(Date(tomorrow)).uppercase()}", agenda.on(tomorrow), MARGIN + colW + gap, top, colW, h, nowMs, false)
        } else if (todayEnd + 120f < h) {
            drawDay(canvas, "TOMORROW · ${shortDay.format(Date(tomorrow)).uppercase()}", agenda.on(tomorrow), MARGIN, todayEnd + 48f, colW, h, nowMs, false)
        }

        postInvalidateDelayed(60_000 - nowMs % 60_000 + 50)
    }

    private fun summary(nowMs: Long): String {
        if (!agenda.configured) return "No calendar yet. Set one up on the hub's page."
        if (!agenda.signedIn) return "The hub is not signed in to the calendar; the hub's page has a button for it."
        agenda.error?.let { return "The calendar could not be read: $it" }
        agenda.current(nowMs)?.let { return "Now: ${it.title}, until ${agenda.time(it.endMs)}." }
        val next = agenda.next(nowMs)
        val left = agenda.on(nowMs).count { it.counts && it.startMs > nowMs }
        return when {
            next == null && agenda.on(nowMs).any { it.counts } -> "Nothing more today."
            next == null -> "Nothing in the calendar today."
            else -> {
                val mins = (next.startMs - nowMs) / 60_000
                val when_ = if (mins < 1) "starting now" else if (mins < 60) "in $mins min" else "at ${agenda.time(next.startMs)}"
                "Next: ${next.title} $when_" + if (left > 1) ", $left still to come." else "."
            }
        }
    }

    /** One day's column. Returns where it ended. */
    private fun drawDay(canvas: Canvas, heading: String, events: List<AgendaEvent>, x: Float, top: Float, colW: Float, h: Float, nowMs: Long, isToday: Boolean): Float {
        canvas.drawText(heading, x, top, mono(0.55f, 14f))
        var y = top + 30f
        if (events.isEmpty()) {
            canvas.drawText("Nothing planned.", x, y + 26f, type(22f, sans, 0.6f))
            textZones.add(RectF(x, top - 20f, x + colW, y + 40f))
            return y + 40f
        }
        val timeW = 118f
        // When the day does not fit, finished meetings go first: what is left matters more than what is over.
        val room = ((h - 24f - y) / ROW_H).toInt().coerceAtLeast(1)
        var shown = events
        if (isToday && shown.size > room) {
            val pastCount = shown.count { !it.allDay && it.endMs <= nowMs }
            // The "n earlier" line takes room of its own, so drop enough for the rest to fit under it.
            val roomUnderLabel = ((h - 24f - y - 34f) / ROW_H).toInt().coerceAtLeast(1)
            val drop = minOf(pastCount, shown.size - roomUnderLabel)
            var left = drop
            shown = shown.filter { e -> if (left > 0 && !e.allDay && e.endMs <= nowMs) { left--; false } else true }
            if (drop > 0) {
                canvas.drawText("$drop EARLIER", x, y + 20f, mono(0.4f, 13f))
                y += 34f
            }
        }
        for ((i, e) in shown.withIndex()) {
            if (y + ROW_H > h - 24f) {
                canvas.drawText("+ ${shown.size - i} MORE", x, y + 20f, mono(0.4f, 13f))
                y += 34f
                break
            }
            val past = isToday && !e.allDay && e.endMs <= nowMs
            val on = e.isOn(nowMs)
            val faint = if (past) 0.38f else if (e.busy == "free" || e.response == "tentative") 0.65f else 1f
            if (e.allDay) {
                fill.color = DashboardView.withAlpha(art.paper, 0.12f)
                rect.set(x, y + 4f, x + colW, y + ROW_H - 10f)
                canvas.drawRoundRect(rect, 10f, 10f, fill)
                canvas.drawText(fit(e.title, type(21f, bold, 0.9f), colW - 40f), x + 18f, y + 36f, text)
                y += ROW_H
                continue
            }
            if (on) {
                fill.color = DashboardView.withAlpha(art.accent, 0.18f)
                rect.set(x - 14f, y, x + colW, y + ROW_H - 6f)
                canvas.drawRoundRect(rect, 12f, 12f, fill)
                fill.color = DashboardView.visibleOn(art.ink, art.accent)
                rect.set(x - 14f, y + 10f, x - 9f, y + ROW_H - 16f)
                canvas.drawRoundRect(rect, 3f, 3f, fill)
            }
            canvas.drawText(agenda.time(e.startMs), x, y + 30f, type(23f, if (on) bold else sans, faint))
            canvas.drawText(agenda.time(e.endMs), x, y + 54f, mono(faint * 0.6f, 13f))
            val title = fit(e.title, type(23f, if (on) bold else sans, faint), colW - timeW - 8f)
            canvas.drawText(title, x + timeW, y + 30f, text)
            val tags = listOfNotNull(
                e.location.takeIf { it.isNotEmpty() },
                "tentative".takeIf { e.response == "tentative" },
                "free".takeIf { e.busy == "free" },
                "mine".takeIf { e.mine },
            )
            if (tags.isNotEmpty()) canvas.drawText(fit(tags.joinToString(" · ").uppercase(), mono(faint * 0.6f, 13f), colW - timeW - 8f), x + timeW, y + 54f, text)
            if (past) {
                line.strokeWidth = 1.5f
                line.color = DashboardView.withAlpha(art.paper, 0.35f)
                val tw = min(text.measureText(title), colW - timeW - 8f)
                canvas.drawLine(x + timeW, y + 23f, x + timeW + type(23f, sans).measureText(title).coerceAtMost(tw), y + 23f, line)
            }
            y += ROW_H
        }
        textZones.add(RectF(x - 16f, top - 20f, x + colW, y))
        return y
    }

    private fun rule(canvas: Canvas, left: Float, right: Float, y: Float, alpha: Float) {
        line.strokeWidth = 1f
        line.color = DashboardView.withAlpha(art.paper, alpha)
        canvas.drawLine(left, y, right, y, line)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onClose?.invoke()
        return true
    }

    companion object {
        private const val MARGIN = 52f
        private const val ROW_H = 66f
        private const val MIN_COLUMN = 480f
    }
}

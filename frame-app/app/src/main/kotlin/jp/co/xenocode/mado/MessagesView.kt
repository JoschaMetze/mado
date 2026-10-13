package jp.co.xenocode.mado

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Every alert the frame has been sent, newest first, opened from the rail: the words, who sent
 * them and when, and whether anyone saw them. It scrolls, a tap closes it, and it closes by
 * itself when left alone.
 */
class MessagesView(context: Context) : View(context) {
    private class Row(val message: MessageLog.Message, val body: StaticLayout, val height: Float)

    private var art = DashboardView.NO_ART
    private var messages: List<MessageLog.Message> = emptyList()
    private var rows: List<Row> = emptyList()
    private var contentHeight = 0f
    private var offset = 0f

    private val italic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    /** The messages' layouts keep hold of their paint, so it is theirs alone. */
    private val bodyText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        typeface = sans
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val clock = SimpleDateFormat("HH:mm", Locale.US)
    private val date = SimpleDateFormat("d MMM", Locale.US)
    private val day = Calendar.getInstance()

    private val scroller = OverScroller(context)
    private val autoClose = Runnable { close() }
    private var closing = false
    val isOpen get() = visibility == VISIBLE && !closing
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollTo(offset + dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, offset.toInt(), 0, -vy.toInt(), 0, 0, 0, maxOffset().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            close()
            return true
        }
    })

    init {
        visibility = GONE
        alpha = 0f
    }

    fun setArt(art: DashboardView.Art) {
        this.art = art
        invalidate()
    }

    /** Oldest first, as the log keeps them. Call again when one arrives or its fate changes. */
    fun setMessages(log: List<MessageLog.Message>) {
        messages = log.asReversed().toList()
        if (messages.isEmpty()) close()
        measureRows()
    }

    fun open() {
        offset = 0f
        scroller.forceFinished(true)
        animate().cancel()
        closing = false
        visibility = VISIBLE
        animate().alpha(1f).setDuration(FADE_MS).withLayer()
        stayOpen()
        invalidate()
    }

    fun close() {
        removeCallbacks(autoClose)
        if (!isOpen) return
        closing = true
        animate().cancel()
        animate().alpha(0f).setDuration(FADE_MS).withLayer().withEndAction {
            visibility = GONE
            closing = false
        }
    }

    private fun stayOpen() {
        removeCallbacks(autoClose)
        postDelayed(autoClose, IDLE_CLOSE_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = measureRows()

    private fun measureRows() {
        val bodyWidth = (width - 2 * MARGIN - INDENT - SIDE_W).toInt()
        if (bodyWidth <= 0) return
        rows = messages.map { m ->
            val body = StaticLayout.Builder.obtain(m.text, 0, m.text.length, bodyText, bodyWidth).setLineSpacing(0f, 1.3f).build()
            Row(m, body, BODY_TOP + body.height + 28f)
        }
        contentHeight = rows.sumOf { it.height.toDouble() }.toFloat() + 24f
        scrollTo(offset)
    }

    private fun maxOffset() = max(0f, contentHeight - (height - LIST_TOP))

    private fun scrollTo(y: Float) {
        offset = y.coerceIn(0f, maxOffset())
        invalidate()
    }

    private fun type(size: Float, face: Typeface, alpha: Float = 1f, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT) = text.apply {
        textSize = size
        typeface = face
        color = DashboardView.withAlpha(art.paper, alpha)
        letterSpacing = spacing
        textAlign = align
    }

    private fun mono(alpha: Float, size: Float, align: Paint.Align = Paint.Align.LEFT) = type(size, Typeface.MONOSPACE, alpha, 0.14f, align)

    private fun unseen(m: MessageLog.Message) = m.fate == MessageLog.UNOPENED || m.fate == MessageLog.LOST || m.fate == MessageLog.HELD

    private fun summary(): String {
        val unseen = messages.count(::unseen)
        return listOfNotNull(if (unseen > 0) "$unseen NOT SEEN" else null, "${messages.size} IN ALL").joinToString(" · ")
    }

    /** "14:32" today, "YESTERDAY 14:32", "9 OCT 14:32"; nothing from before the clock was set. */
    private fun time(at: Long, now: Long): String {
        if (at < CLOCK_SET_MS) return ""
        fun dayOf(ms: Long) = day.apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 400 + it.get(Calendar.DAY_OF_YEAR) }
        val today = dayOf(now)
        val then = dayOf(at)
        val yesterday = dayOf(now - 86_400_000)
        val hour = clock.format(Date(at))
        return when (then) {
            today -> hour
            yesterday -> "YESTERDAY $hour"
            else -> "${date.format(Date(at)).uppercase()} $hour"
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (scroller.computeScrollOffset()) {
            offset = scroller.currY.toFloat().coerceIn(0f, maxOffset())
            postInvalidateOnAnimation()
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val left = MARGIN
        val right = w - MARGIN
        val now = System.currentTimeMillis()
        canvas.drawColor(art.ink or 0xFF000000.toInt())

        canvas.save()
        canvas.clipRect(0f, LIST_TOP, w, h)
        var y = LIST_TOP - offset
        for (row in rows) {
            // Rows off the glass cost nothing.
            if (y + row.height > LIST_TOP && y < h) drawRow(canvas, row, left, right, y, now)
            y += row.height
        }
        canvas.restore()

        canvas.drawText("SENT TO THE FRAME", left, 78f, mono(0.55f, 14f))
        canvas.drawText("Messages", left, 132f, type(46f, italic))
        canvas.drawText("CLOSE ×", right, 78f, mono(0.85f, 13f, Paint.Align.RIGHT))
        canvas.drawText(summary(), right, 132f, mono(0.7f, 14f, Paint.Align.RIGHT))
        line.strokeCap = Paint.Cap.BUTT
        line.strokeWidth = 1f
        line.color = DashboardView.withAlpha(art.paper, 0.3f)
        canvas.drawLine(left, LIST_TOP, right, LIST_TOP, line)

        val range = maxOffset()
        if (range > 0f) {
            val track = h - LIST_TOP - 32f
            val thumb = track * (h - LIST_TOP) / contentHeight
            val top = LIST_TOP + 16f + (track - thumb) * offset / range
            line.strokeCap = Paint.Cap.ROUND
            line.strokeWidth = 4f
            line.color = DashboardView.withAlpha(art.paper, 0.35f)
            canvas.drawLine(w - 40f, top, w - 40f, top + thumb, line)
        }
    }

    private fun drawRow(canvas: Canvas, row: Row, left: Float, right: Float, y: Float, now: Long) {
        val m = row.message
        val x = left + INDENT
        val unseen = unseen(m)

        // The alert's own colour, and a shape with it: a ring for a whisper, a dot, a square for urgent.
        val tint = DashboardView.visibleOn(
            art.ink,
            when (m.level) {
                AlertView.WHISPER -> WHISPER_TINT
                AlertView.URGENT -> URGENT_TINT
                else -> NUDGE_TINT
            }
        )
        val cx = left + 12f
        val cy = y + 30f
        fill.color = tint
        when (m.level) {
            AlertView.WHISPER -> {
                line.strokeWidth = 2.5f
                line.color = tint
                canvas.drawCircle(cx, cy, 8f, line)
            }
            AlertView.URGENT -> canvas.drawRect(cx - 9f, cy - 9f, cx + 9f, cy + 9f, fill)
            else -> canvas.drawCircle(cx, cy, 9f, fill)
        }

        val from = if (m.source != null) " · FROM ${m.source.uppercase()}" else ""
        canvas.drawText(m.level.uppercase() + from, x, y + 35f, mono(0.6f, 13f))
        canvas.drawText(time(m.at, now), right, y + 35f, mono(0.7f, 13f, Paint.Align.RIGHT))
        val fate = when (m.fate) {
            MessageLog.READ -> "READ"
            MessageLog.PASSED -> "LEFT BY ITSELF"
            MessageLog.UNOPENED -> "NEVER OPENED"
            MessageLog.HELD -> "KEPT FOR MORNING"
            MessageLog.LOST -> "NEVER SHOWN"
            else -> "ON THE GLASS"
        }
        canvas.drawText(fate, right, y + 62f, mono(if (unseen) 1f else 0.45f, 13f, Paint.Align.RIGHT))

        bodyText.color = DashboardView.withAlpha(art.paper, if (unseen) 1f else 0.82f)
        canvas.save()
        canvas.translate(x, y + BODY_TOP)
        row.body.draw(canvas)
        canvas.restore()

        line.strokeCap = Paint.Cap.BUTT
        line.strokeWidth = 1f
        line.color = DashboardView.withAlpha(art.paper, 0.14f)
        canvas.drawLine(left, y + row.height, right, y + row.height, line)
    }

    // Everything on the page is this view's, so nothing reaches the dashboard underneath.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        stayOpen()
        gestures.onTouchEvent(event)
        return true
    }

    private companion object {
        const val MARGIN = 96f
        /** Where a row's words start: past the mark for its level. */
        const val INDENT = 44f
        /** Kept clear on the right of each row for its time and what became of it. */
        const val SIDE_W = 260f
        const val LIST_TOP = 158f
        const val BODY_TOP = 46f
        const val FADE_MS = 250L
        const val IDLE_CLOSE_MS = 120_000L
        /** 2024: the frame's clock reads 1970 for a while after boot. */
        const val CLOCK_SET_MS = 1_704_067_200_000L
        const val WHISPER_TINT = 0xFFF4F1FF.toInt()
        const val NUDGE_TINT = 0xFF7BE0D0.toInt()
        const val URGENT_TINT = 0xFFFF6B5E.toInt()
    }
}

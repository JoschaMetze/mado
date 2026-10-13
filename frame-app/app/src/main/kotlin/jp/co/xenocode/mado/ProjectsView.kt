package jp.co.xenocode.mado

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.max

/**
 * Every project on one page, opened by tapping the projects on the dashboard: the whole note,
 * progress, and when each was last reported. It scrolls when the list is longer than the
 * glass. A tap closes it, and it closes by itself when left alone.
 */
class ProjectsView(context: Context) : View(context) {
    private class Row(val project: DashboardView.Project, val note: StaticLayout?, val height: Float)

    private var art = DashboardView.NO_ART
    private var projects: List<DashboardView.Project> = emptyList()
    private var rows: List<Row> = emptyList()
    private var contentHeight = 0f
    private var offset = 0f

    private val italic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    /** The notes' layouts keep hold of their paint, so it is theirs alone. */
    private val noteText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 21f
        typeface = sans
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letters = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    private val scroller = OverScroller(context)
    private val autoClose = Runnable { close() }
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

    fun setProjects(projects: List<DashboardView.Project>) {
        this.projects = projects
        if (projects.isEmpty()) close()
        measureRows()
    }

    fun open() {
        offset = 0f
        scroller.forceFinished(true)
        animate().cancel()
        visibility = VISIBLE
        animate().alpha(1f).setDuration(FADE_MS).withLayer()
        stayOpen()
        invalidate()
    }

    fun close() {
        removeCallbacks(autoClose)
        if (visibility != VISIBLE) return
        animate().cancel()
        animate().alpha(0f).setDuration(FADE_MS).withLayer().withEndAction { visibility = GONE }
    }

    private fun stayOpen() {
        removeCallbacks(autoClose)
        postDelayed(autoClose, IDLE_CLOSE_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = measureRows()

    private fun measureRows() {
        val noteWidth = (width - 2 * MARGIN - INDENT - SIDE_W).toInt()
        if (noteWidth <= 0) return
        rows = projects.map { p ->
            val note = if (p.note.isEmpty()) null else
                StaticLayout.Builder.obtain(p.note, 0, p.note.length, noteText, noteWidth).setLineSpacing(0f, 1.35f).build()
            val body = if (note == null) 0f else note.height + 6f
            Row(p, note, NOTE_TOP + body + (if (progress(p) == null) 4f else 34f) + 26f)
        }
        contentHeight = rows.sumOf { it.height.toDouble() }.toFloat() + 24f
        scrollTo(offset)
    }

    private fun maxOffset() = max(0f, contentHeight - (height - LIST_TOP))

    private fun scrollTo(y: Float) {
        offset = y.coerceIn(0f, maxOffset())
        invalidate()
    }

    private fun progress(p: DashboardView.Project) = p.progress ?: if (p.status == "done") 1f else null

    private fun type(size: Float, face: Typeface, alpha: Float = 1f, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT) = text.apply {
        textSize = size
        typeface = face
        color = DashboardView.withAlpha(art.paper, alpha)
        letterSpacing = spacing
        textAlign = align
    }

    private fun mono(alpha: Float, size: Float, align: Paint.Align = Paint.Align.LEFT) = type(size, Typeface.MONOSPACE, alpha, 0.14f, align)

    private fun summary(): String {
        val waiting = projects.count { it.status == "needs_you" }
        val blocked = projects.count { it.status == "blocked" }
        return listOfNotNull(
            if (waiting > 0) "$waiting ${if (waiting == 1) "NEEDS" else "NEED"} YOU" else null,
            if (blocked > 0) "$blocked BLOCKED" else null,
            "${projects.size} IN ALL",
        ).joinToString(" · ")
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
        val nowS = System.currentTimeMillis() / 1000
        canvas.drawColor(art.ink or 0xFF000000.toInt())

        canvas.save()
        canvas.clipRect(0f, LIST_TOP, w, h)
        var y = LIST_TOP - offset
        rows.forEachIndexed { i, row ->
            // Rows off the glass cost nothing.
            if (y + row.height > LIST_TOP && y < h) drawRow(canvas, row, i, left, right, y, nowS)
            y += row.height
        }
        canvas.restore()

        canvas.drawText("IN THE STUDIO", left, 78f, mono(0.55f, 14f))
        canvas.drawText("Projects", left, 132f, type(46f, italic))
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

    private fun drawRow(canvas: Canvas, row: Row, index: Int, left: Float, right: Float, y: Float, nowS: Long) {
        val p = row.project
        val x = left + INDENT
        DashboardView.icon(canvas, p, left, y + 17f, ICON, fill, letters, picture)
        DashboardView.mark(canvas, p.status, left + ICON + 24f, y + 39f, 9f, fill, line)
        canvas.drawText(p.name, x, y + 50f, type(30f, bold))

        val progress = progress(p)
        val status = p.status.replace('_', ' ').uppercase() + if (p.progress != null) " · ${(p.progress * 100).toInt()}%" else ""
        canvas.drawText(status, right, y + 44f, mono(0.85f, 15f, Paint.Align.RIGHT))
        val age = ago(nowS - p.updated)
        if (p.updated > 0 && age != null) canvas.drawText("Updated $age", right, y + 72f, type(16f, sans, 0.6f, align = Paint.Align.RIGHT))

        var bottom = y + NOTE_TOP
        row.note?.let {
            noteText.color = DashboardView.withAlpha(art.paper, 0.8f)
            canvas.save()
            canvas.translate(x, bottom)
            it.draw(canvas)
            canvas.restore()
            bottom += it.height + 6f
        }
        // Progress as a brush stroke in one of the painting's own pigments, as on the dashboard.
        if (progress != null) {
            val at = bottom + 16f
            line.strokeCap = Paint.Cap.ROUND
            line.strokeWidth = 2f
            line.color = DashboardView.withAlpha(art.paper, 0.14f)
            canvas.drawLine(x, at, right, at, line)
            if (progress > 0f) {
                val end = x + (right - x) * progress
                val bend = if (index % 2 == 0) -5f else 5f
                path.rewind()
                path.moveTo(x + 4f, at)
                path.cubicTo(x + (end - x) * 0.3f, at + bend, x + (end - x) * 0.7f, at - bend, end - 4f, at)
                line.strokeWidth = 9f
                line.color = DashboardView.visibleOn(art.ink, art.swatches[index % art.swatches.size])
                canvas.drawPath(path, line)
            }
        }
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
        const val ICON = 44f
        /** Where a row's words start: past its icon and its status mark. */
        const val INDENT = ICON + 52f
        /** Kept clear on the right of each row for its status and age. */
        const val SIDE_W = 280f
        const val LIST_TOP = 158f
        const val NOTE_TOP = 68f
        const val FADE_MS = 250L
        const val IDLE_CLOSE_MS = 120_000L
    }
}

/**
 * How long ago, in words, or null when the clock makes no sense of it (the frame's clock reads
 * 1970 for a while after boot).
 */
internal fun ago(seconds: Long): String? = when {
    seconds < 0 -> null
    seconds < 90 -> "just now"
    seconds < 3600 -> "${seconds / 60} min ago"
    seconds < 86_400 -> "${seconds / 3600} h ago"
    else -> (seconds / 86_400).let { if (it == 1L) "yesterday" else "$it days ago" }
}

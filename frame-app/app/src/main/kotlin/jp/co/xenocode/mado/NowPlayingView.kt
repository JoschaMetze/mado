package jp.co.xenocode.mado

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The now-playing scene: the cover, big type, and a backdrop made from the cover itself.
 * Tap the cover to let it fill the height of the glass, tap again to bring the text back;
 * tap the text to play or pause.
 * The hub sends the backdrop tiny and already blurred, so all this does is stretch it and let
 * it drift; nothing here is expensive for the Mali-400.
 */
class NowPlayingView(context: Context) : View(context) {
    var onPlayPause: (() -> Unit)? = null
    var onFullCover: ((Boolean) -> Unit)? = null

    /** Just the cover, as large as the glass allows. */
    var fullCover = false
        set(value) {
            field = value
            invalidate()
        }

    private var title = ""
    private var artist = ""
    private var album = ""
    private var source = ""
    private var playing = false
    private var positionS = 0.0
    private var durationS = 0.0
    private var positionAtMs = 0L

    private var cover: Bitmap? = null
    private var backdrop: Bitmap? = null
    private var oldCover: Bitmap? = null
    private var oldBackdrop: Bitmap? = null
    private var artSinceMs = 0L
    /** 0 while playing, 1 when paused; eased every frame. */
    private var rest = 0f
    /** 0 in the normal layout, 1 with the cover full height; eased every frame. */
    private var full = 0f

    private var titleLayout: StaticLayout? = null
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        textSize = 60f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = -0.02f
    }
    private val linePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE }
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WHITE
        typeface = Typeface.MONOSPACE
        textSize = 15f
        letterSpacing = 0.14f
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val coverRect = RectF()
    private val rect = RectF()

    fun setTrack(title: String, artist: String, album: String, source: String) {
        this.artist = artist
        this.album = album
        this.source = source
        if (title != this.title || titleLayout == null) {
            this.title = title
            titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, TEXT_W)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.02f)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
        invalidate()
    }

    fun setPlayback(playing: Boolean, positionS: Double, durationS: Double) {
        this.playing = playing
        this.positionS = positionS
        this.durationS = durationS
        positionAtMs = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Swap in new artwork (or none) with a crossfade. */
    fun setArt(cover: Bitmap?, backdrop: Bitmap?) {
        oldCover = this.cover
        oldBackdrop = this.backdrop
        this.cover = cover
        this.backdrop = backdrop
        artSinceMs = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val fade = min(1f, (now - artSinceMs) / FADE_MS)
        if (fade >= 1f) {
            oldCover = null
            oldBackdrop = null
        }
        rest += ((if (playing) 0f else 1f) - rest) * 0.1f

        canvas.drawColor(0xFF14161B.toInt())
        oldBackdrop?.let { drawBackdrop(canvas, it, w, h, t, 255) }
        backdrop?.let { drawBackdrop(canvas, it, w, h, t, (255 * fade).toInt()) }
        canvas.drawColor(((0x55 + 0x58 * rest).toInt() shl 24))

        full += ((if (fullCover) 1f else 0f) - full) * 0.16f
        if (fullCover && full > 0.999f) full = 1f
        val size = (COVER + (h - COVER) * full) * (1f - 0.07f * rest)
        val homeCx = COVER_LEFT + COVER / 2
        val cx = homeCx + (w / 2 - homeCx) * full
        val radius = RADIUS * (1f - full)
        coverRect.set(cx - size / 2, h / 2 - size / 2, cx + size / 2, h / 2 + size / 2)
        // A soft shadow from three stacked translucent slabs; cheaper than a blur layer.
        for (i in 1..3) {
            fill.color = (0x1E * (1f - full)).toInt() shl 24
            rect.set(coverRect)
            rect.inset(-4f * i, -4f * i)
            rect.offset(0f, 10f + 4f * i)
            canvas.drawRoundRect(rect, radius + 4f * i, radius + 4f * i, fill)
        }
        if (cover == null && (oldCover == null || fade >= 1f)) {
            fill.color = 0x30FFFFFF
            canvas.drawRoundRect(coverRect, radius, radius, fill)
        }
        oldCover?.let { drawCover(canvas, it, radius, 255) }
        cover?.let { drawCover(canvas, it, radius, (255 * fade).toInt()) }

        // Paused: the cover itself goes dim, not only the backdrop.
        fill.color = (0x80 * rest).toInt() shl 24
        canvas.drawRoundRect(coverRect, radius, radius, fill)

        val textAlpha = 1f - min(1f, full * 2.5f)
        if (textAlpha > 0f) drawText(canvas, h, textAlpha, full * 80f)
        if (full > 0.02f && durationS > 0) {
            // With the text gone, progress becomes a hairline along the bottom edge.
            fill.color = (150 * full).toInt() shl 24 or 0xFFFFFF
            canvas.drawRect(0f, h - 3f, w * (position() / durationS).toFloat(), h, fill)
        }
        postInvalidateOnAnimation()
    }

    private fun drawBackdrop(canvas: Canvas, bitmap: Bitmap, w: Float, h: Float, t: Float, alpha: Int) {
        // Oversized so a slow drift never shows an edge.
        val scale = max(w / bitmap.width, h / bitmap.height) * 1.3f
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            (w - bitmap.width * scale) / 2 + sin(t / 13f) * w * 0.1f,
            (h - bitmap.height * scale) / 2 + cos(t / 17f) * h * 0.1f,
        )
        bitmapPaint.alpha = alpha
        canvas.drawBitmap(bitmap, matrix, bitmapPaint)
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap, radius: Float, alpha: Int) {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        matrix.setScale(coverRect.width() / bitmap.width, coverRect.height() / bitmap.height)
        matrix.postTranslate(coverRect.left, coverRect.top)
        shader.setLocalMatrix(matrix)
        coverPaint.shader = shader
        coverPaint.alpha = alpha
        canvas.drawRoundRect(coverRect, radius, radius, coverPaint)
        coverPaint.shader = null
    }

    private fun position(): Double {
        var position = positionS
        if (playing) position += (SystemClock.uptimeMillis() - positionAtMs) / 1000.0
        return min(position, durationS)
    }

    private fun drawText(canvas: Canvas, h: Float, alpha: Float, shift: Float) {
        val layout = titleLayout ?: return
        val left = TEXT_LEFT + shift
        val blockH = 34f + layout.height + 52f + (if (album.isEmpty()) 0f else 34f) + 70f
        var y = h / 2 - blockH / 2

        monoPaint.alpha = (190 * alpha).toInt()
        val where = if (source.isEmpty()) "" else " ON ${source.uppercase()}"
        canvas.drawText((if (playing) "PLAYING" else "PAUSED") + where, left, y + 15f, monoPaint)
        y += 34f

        canvas.save()
        titlePaint.alpha = (255 * alpha).toInt()
        canvas.translate(left, y)
        layout.draw(canvas)
        canvas.restore()
        y += layout.height

        linePaint.textSize = 30f
        linePaint.alpha = (235 * alpha).toInt()
        canvas.drawText(ellipsize(artist), left, y + 40f, linePaint)
        y += 52f
        if (album.isNotEmpty()) {
            linePaint.textSize = 21f
            linePaint.alpha = (150 * alpha).toInt()
            canvas.drawText(ellipsize(album), left, y + 22f, linePaint)
            y += 34f
        }

        if (durationS <= 0) return
        val position = position()
        y += 40f
        fill.color = (0x40 * alpha).toInt() shl 24 or 0xFFFFFF
        rect.set(left, y, left + TEXT_W, y + 4f)
        canvas.drawRoundRect(rect, 2f, 2f, fill)
        fill.color = (255 * alpha).toInt() shl 24 or 0xFFFFFF
        rect.right = left + TEXT_W * (position / durationS).toFloat()
        canvas.drawRoundRect(rect, 2f, 2f, fill)
        monoPaint.alpha = (150 * alpha).toInt()
        canvas.drawText(clock(position), left, y + 30f, monoPaint)
        val total = clock(durationS)
        canvas.drawText(total, left + TEXT_W - monoPaint.measureText(total), y + 30f, monoPaint)
    }

    private fun ellipsize(s: String) = TextUtils.ellipsize(s, linePaint, TEXT_W.toFloat(), TextUtils.TruncateAt.END).toString()

    private fun clock(seconds: Double): String {
        val s = seconds.toInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        when {
            fullCover || coverRect.contains(event.x, event.y) -> {
                fullCover = !fullCover
                onFullCover?.invoke(fullCover)
            }
            event.x >= TEXT_LEFT - 30f && event.y > height * 0.2f && event.y < height * 0.8f -> onPlayPause?.invoke()
        }
        return true
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val COVER = 580f
        const val COVER_LEFT = 64f
        const val RADIUS = 14f
        const val TEXT_LEFT = 712f
        const val TEXT_W = 504
        const val FADE_MS = 900f
    }
}

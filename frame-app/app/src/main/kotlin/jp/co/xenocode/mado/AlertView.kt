package jp.co.xenocode.mado

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.util.ArrayDeque
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Liquid alerts, drawn over whatever scene is on stage. They come in from the right edge:
 * a whisper is a bead (tap it to read), a nudge is a pill with one message, urgent floods
 * the right third and stays until touched.
 */
class AlertView(context: Context) : View(context) {
    class Alert(val id: Long, val level: String, val text: String, val source: String?, val ttlMs: Long)

    /** Called when an alert leaves the glass; reason is "tap" or "expired". */
    var onDismissed: ((Alert, String) -> Unit)? = null

    var linkUp = false
        set(value) {
            field = value
            invalidate()
        }

    private val queue = ArrayDeque<Alert>()
    private var current: Alert? = null
    private var shown = 0f
    private var animator: ValueAnimator? = null
    private var body: StaticLayout? = null
    private val expire = Runnable { dismiss("expired") }

    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linkDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FF7B7B }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 15f
        letterSpacing = 0.12f
    }
    private val pill = RectF()

    fun show(alert: Alert) {
        val now = current
        when {
            now == null -> present(alert)
            alert.level == URGENT && now.level != URGENT -> {
                queue.addFirst(now)
                present(alert)
            }
            else -> queue.addLast(alert)
        }
    }

    private fun present(alert: Alert) {
        current = alert
        val urgent = alert.level == URGENT
        bodyPaint.textSize = if (urgent) 46f else 26f
        bodyPaint.typeface = Typeface.create("sans-serif", if (urgent) Typeface.BOLD else Typeface.NORMAL)
        bodyPaint.color = INK
        smallPaint.color = INK
        val width = if (urgent) URGENT_TEXT_W else NUDGE_TEXT_W
        body = StaticLayout.Builder.obtain(alert.text, 0, alert.text.length, bodyPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, if (urgent) 1.02f else 1.15f)
            .setMaxLines(if (urgent) 7 else 4)
            .build()
        removeCallbacks(expire)
        if (alert.ttlMs > 0) postDelayed(expire, alert.ttlMs)
        animate(1f, 650, OvershootInterpolator(1.3f), null)
    }

    private fun dismiss(reason: String) {
        val leaving = current ?: return
        removeCallbacks(expire)
        animate(0f, 320, AccelerateInterpolator()) {
            if (current === leaving) {
                current = null
                onDismissed?.invoke(leaving, reason)
                queue.pollFirst()?.let(::present)
            }
        }
    }

    private fun animate(to: Float, ms: Long, curve: android.animation.TimeInterpolator, then: (() -> Unit)?) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(if (to == 1f) 0f else shown, to).apply {
            duration = ms
            interpolator = curve
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            if (then != null) addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) then()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!linkUp) canvas.drawCircle(18f, h - 18f, 5f, linkDot)

        val alert = current ?: return
        val t = SystemClock.uptimeMillis() / 1000f
        val textAlpha = (255 * min(1f, max(0f, (shown - 0.6f) / 0.4f))).toInt()
        when (alert.level) {
            WHISPER -> {
                blob.color = 0xFFF4F1FF.toInt()
                canvas.drawCircle(w + BEAD_R - shown * BEAD_R * 1.35f, h / 2 + sin(t * 1.3f) * 7f, BEAD_R, blob)
            }
            URGENT -> {
                blob.color = 0xFFFF6B5E.toInt()
                val r = h * 0.95f
                val cx = w + r * 0.35f + (1f - shown) * r * 1.1f
                canvas.drawCircle(cx, h / 2, r, blob)
                // Droplets riding the leading edge make it read as liquid rather than a panel.
                droplet(canvas, cx, r, h * 0.22f, 64f, sin(t * 0.9f) * 10f)
                droplet(canvas, cx, r, h * 0.60f, 86f, cos(t * 0.7f) * 12f)
                droplet(canvas, cx, r, h * 0.90f, 48f, sin(t * 1.2f + 1f) * 8f)
                drawText(canvas, alert, w - URGENT_TEXT_W - 60f + (1f - shown) * 80f, h * 0.24f, textAlpha, "tap to dismiss")
            }
            else -> {
                blob.color = 0xFF7BE0D0.toInt()
                val layout = body ?: return
                val ph = max(96f, layout.height + (if (alert.source != null) 30f else 0f) + 44f)
                val pw = NUDGE_TEXT_W + 120f
                val left = w - shown * (pw - 44f)
                val top = h * 0.3f
                pill.set(left, top, left + pw, top + ph)
                canvas.drawRoundRect(pill, min(ph / 2, 56f), min(ph / 2, 56f), blob)
                canvas.drawCircle(left + 4f + sin(t) * 4f, top + ph * 0.78f, 24f * shown, blob)
                canvas.drawCircle(left - 34f, top + ph * 0.4f + cos(t * 1.4f) * 5f, 11f * shown, blob)
                drawText(canvas, alert, left + 44f, top + 22f, textAlpha, null)
            }
        }
        postInvalidateOnAnimation()
    }

    private fun droplet(canvas: Canvas, cx: Float, r: Float, y: Float, size: Float, bob: Float) {
        val dy = y - height / 2f
        val edge = cx - sqrt(max(0f, r * r - dy * dy))
        canvas.drawCircle(edge + size * 0.25f + bob, y, size, blob)
    }

    private fun drawText(canvas: Canvas, alert: Alert, x: Float, y: Float, alpha: Int, hint: String?) {
        val layout = body ?: return
        var top = y
        smallPaint.alpha = alpha * 7 / 10
        if (alert.source != null) {
            canvas.drawText("FROM ${alert.source.uppercase()}", x, top + 15f, smallPaint)
            top += 30f
        }
        bodyPaint.alpha = alpha
        canvas.save()
        canvas.translate(x, top)
        layout.draw(canvas)
        canvas.restore()
        if (hint != null) canvas.drawText(hint, x, top + layout.height + 44f, smallPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val alert = current ?: return false
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false
        val hit = when (alert.level) {
            WHISPER -> event.x > width - 140 && event.y > height / 2 - 120 && event.y < height / 2 + 120
            URGENT -> true
            else -> event.x > pill.left - 60 && event.y > pill.top - 40 && event.y < pill.bottom + 40
        }
        if (!hit) return false
        if (alert.level == WHISPER) {
            // Open the bead into a readable nudge; it still counts as the same alert.
            present(Alert(alert.id, NUDGE, alert.text, alert.source, 12_000))
        } else {
            dismiss("tap")
        }
        return true
    }

    private companion object {
        const val WHISPER = "whisper"
        const val NUDGE = "nudge"
        const val URGENT = "urgent"
        const val INK = 0xFF141A1F.toInt()
        const val BEAD_R = 46f
        const val NUDGE_TEXT_W = 420
        const val URGENT_TEXT_W = 380
    }
}

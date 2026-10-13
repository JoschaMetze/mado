package jp.co.xenocode.mado

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The rail: device controls that slide in from the left edge and hide again by themselves.
 * Closed, it only claims touches that start on the left edge (tap or swipe), so everything else falls through
 * to the views underneath.
 */
class RailView(context: Context) : View(context) {
    var onBrightness: ((Float) -> Unit)? = null
    var onHome: (() -> Unit)? = null
    var onScene: ((Int) -> Unit)? = null
    /** About to slide in: the moment to bring `scenes` and `activeScene` up to date. */
    var onOpening: (() -> Unit)? = null
    var onTama: ((Boolean) -> Unit)? = null
    var onTamaSound: ((Boolean) -> Unit)? = null
    var onSleep: ((Boolean) -> Unit)? = null
    /** Night mode is on, by the hours or because someone asked. */
    var asleep = false
        set(value) {
            field = value
            invalidate()
        }
    var tamaShown = true
    var tamaSound = false

    var scenes: List<String> = emptyList()
    var activeScene = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Window brightness, MIN_BRIGHTNESS..1. */
    var brightness = 1f
    var linkUp = false
        set(value) {
            field = value
            invalidate()
        }
    var hubAddress: String? = null

    private var open = 0f
    private var animator: ValueAnimator? = null
    private val autoHide = Runnable { settle(false) }

    private enum class Touch { NONE, DRAG, SLIDER, PRESS, OUTSIDE }
    private var touch = Touch.NONE
    private var downX = 0f
    private var downY = 0f
    private var downOpen = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = FG
    }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FG
        textSize = 22f
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FG
        typeface = Typeface.MONOSPACE
        textSize = 13f
        letterSpacing = 0.14f
    }
    private val rect = RectF()

    private fun settle(toOpen: Boolean) {
        removeCallbacks(autoHide)
        if (toOpen) postDelayed(autoHide, AUTO_HIDE_MS)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(open, if (toOpen) 1f else 0f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                open = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        if (open <= 0f) {
            // A faint grip so the edge is discoverable.
            fill.color = 0x30FFFFFF
            rect.set(-3f, h / 2 - 34f, 3f, h / 2 + 34f)
            canvas.drawRoundRect(rect, 3f, 3f, fill)
            return
        }
        fill.color = (0x59 * open).toInt() shl 24
        canvas.drawRect(0f, 0f, width.toFloat(), h, fill)

        canvas.save()
        canvas.translate((open - 1f) * W, 0f)
        fill.color = PANEL
        canvas.drawRect(0f, 0f, W, h, fill)

        small.alpha = 150
        canvas.drawText("MADO", PAD, 62f, small)

        scenes.forEachIndexed { i, name ->
            val top = SCENE_Y + i * ROW_STEP
            val mid = top + ROW_H / 2
            if (i == activeScene) {
                fill.color = 0x24FFFFFF
                rect.set(14f, top, W - 14f, top + ROW_H)
                canvas.drawRoundRect(rect, 14f, 14f, fill)
                fill.color = FG
                canvas.drawCircle(PAD + 8f, mid, 6f, fill)
            } else {
                canvas.drawCircle(PAD + 8f, mid, 5f, stroke)
            }
            label.alpha = if (i == activeScene) 255 else 170
            canvas.drawText(name, PAD + 32f, mid + 8f, label)
        }
        label.alpha = 255
        val sliderY = sliderY()
        val homeY = sliderY + 34f

        canvas.drawText("BRIGHTNESS", PAD, sliderY - 28f, small)
        val knob = PAD + (W - 2 * PAD) * (brightness - MIN_BRIGHTNESS) / (1f - MIN_BRIGHTNESS)
        fill.color = 0x38FFFFFF
        rect.set(PAD, sliderY - 3f, W - PAD, sliderY + 3f)
        canvas.drawRoundRect(rect, 3f, 3f, fill)
        fill.color = FG
        rect.right = knob
        canvas.drawRoundRect(rect, 3f, 3f, fill)
        canvas.drawCircle(knob, sliderY, 12f, fill)

        rect.set(PAD, homeY + ROW_H / 2 - 8f, PAD + 16f, homeY + ROW_H / 2 + 8f)
        canvas.drawRoundRect(rect, 4f, 4f, stroke)
        canvas.drawText("Home screen", PAD + 32f, homeY + ROW_H / 2 + 8f, label)

        drawSwitch(canvas, "Sleep", homeY + LOW_STEP, asleep, 255)
        drawSwitch(canvas, "Tama", homeY + 2 * LOW_STEP, tamaShown, 255)
        // Sound has nothing to make it while Tama is hidden.
        drawSwitch(canvas, "Tama sounds", homeY + 3 * LOW_STEP, tamaSound && tamaShown, if (tamaShown) 255 else 90)

        fill.color = if (linkUp) 0xFF4FD19C.toInt() else 0xFFFF7B7B.toInt()
        canvas.drawCircle(PAD + 5f, h - 58f, 5f, fill)
        canvas.drawText(if (linkUp) "HUB CONNECTED" else "NO HUB", PAD + 20f, h - 53f, small)
        val address = hubAddress?.removePrefix("ws://")?.removeSuffix("/frame") ?: "not set"
        small.alpha = 110
        canvas.drawText(TextUtils.ellipsize(address, small, W - 2 * PAD, TextUtils.TruncateAt.END).toString(), PAD, h - 30f, small)
        canvas.restore()
    }

    private fun drawSwitch(canvas: Canvas, name: String, top: Float, on: Boolean, alpha: Int) {
        val mid = top + ROW_H / 2
        fill.color = if (on) FG else 0x38FFFFFF
        fill.alpha = fill.alpha * alpha / 255
        rect.set(PAD - 2f, mid - 7f, PAD + 22f, mid + 7f)
        canvas.drawRoundRect(rect, 7f, 7f, fill)
        fill.color = if (on) PANEL else FG
        fill.alpha = alpha
        canvas.drawCircle(if (on) PAD + 15f else PAD + 5f, mid, 5f, fill)
        label.alpha = alpha
        canvas.drawText(name, PAD + 32f, mid + 8f, label)
        label.alpha = 255
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downOpen = open
                touch = when {
                    open < 0.01f -> {
                        if (event.x > EDGE) return false
                        onOpening?.invoke()
                        Touch.DRAG
                    }
                    event.x > W * open -> Touch.OUTSIDE
                    abs(event.y - sliderY()) < 34f -> Touch.SLIDER
                    else -> Touch.PRESS
                }
                animator?.cancel()
                removeCallbacks(autoHide)
                if (touch == Touch.SLIDER) slide(event.x)
            }
            MotionEvent.ACTION_MOVE -> when (touch) {
                Touch.DRAG -> {
                    open = min(1f, max(0f, downOpen + (event.x - downX) / W))
                    invalidate()
                }
                Touch.SLIDER -> slide(event.x)
                // A sideways pull on the panel turns into dragging it shut.
                Touch.PRESS -> if (abs(event.x - downX) > 18f) touch = Touch.DRAG
                else -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = touch
                touch = Touch.NONE
                when (was) {
                    Touch.DRAG -> settle(
                        when {
                            // A plain tap on the left edge opens the rail too.
                            downOpen < 0.01f && abs(event.x - downX) < TAP_SLOP -> true
                            downOpen < 0.5f -> open > 0.3f
                            else -> open > 0.7f
                        }
                    )
                    Touch.OUTSIDE -> settle(false)
                    Touch.PRESS -> press(event.actionMasked == MotionEvent.ACTION_UP)
                    else -> settle(true)
                }
            }
        }
        return true
    }

    private fun sliderY() = SCENE_Y + scenes.size * ROW_STEP + 60f

    private fun press(lifted: Boolean) {
        val homeY = sliderY() + 34f
        val scene = ((downY - SCENE_Y) / ROW_STEP).toInt()
        when {
            !lifted -> settle(true)
            downY in homeY..homeY + LOW_STEP -> {
                settle(false)
                onHome?.invoke()
            }
            downY in homeY + LOW_STEP..homeY + LOW_STEP + LOW_STEP -> {
                settle(false)
                onSleep?.invoke(!asleep)
            }
            downY in homeY + 2 * LOW_STEP..homeY + 2 * LOW_STEP + LOW_STEP -> {
                tamaShown = !tamaShown
                onTama?.invoke(tamaShown)
                settle(true)
                invalidate()
            }
            tamaShown && downY in homeY + 3 * LOW_STEP..homeY + 3 * LOW_STEP + LOW_STEP -> {
                tamaSound = !tamaSound
                onTamaSound?.invoke(tamaSound)
                settle(true)
                invalidate()
            }
            downY >= SCENE_Y && scene < scenes.size -> {
                settle(false)
                onScene?.invoke(scene)
            }
            else -> settle(true)
        }
    }

    private fun slide(x: Float) {
        val v = min(1f, max(0f, (x - PAD) / (W - 2 * PAD)))
        brightness = MIN_BRIGHTNESS + (1f - MIN_BRIGHTNESS) * v
        onBrightness?.invoke(brightness)
        invalidate()
    }

    companion object {
        const val MIN_BRIGHTNESS = 0.03f
        private const val W = 250f
        private const val PAD = 28f
        private const val EDGE = 64f
        private const val TAP_SLOP = 14f
        private const val ROW_H = 54f
        private const val ROW_STEP = ROW_H + 6f
        /** The switches under the slider sit closer together than the scenes, to leave the hub its corner. */
        private const val LOW_STEP = 56f
        private const val SCENE_Y = 96f
        private const val AUTO_HIDE_MS = 6_000L
        private const val FG = 0xFFFFFFFF.toInt()
        private const val PANEL = 0xE6101319.toInt()
    }
}

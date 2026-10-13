package jp.co.xenocode.mado

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * When the frame sleeps. It follows its hours (minutes into the day, and the night may run
 * past midnight) unless someone has said otherwise, and what they said lasts until the hours
 * next change over: "sleep now" in the afternoon ends in the morning, "wake" at night ends
 * the next evening.
 */
class NightClock {
    var from = 23 * 60
    var to = 7 * 60
    var enabled = true
    private var forced: Boolean? = null
    private var wasWithin: Boolean? = null

    fun within(minute: Int) = enabled && from != to && if (from < to) minute in from until to else minute >= from || minute < to

    fun force(asleep: Boolean, minute: Int) {
        forced = if (asleep == within(minute)) null else asleep
    }

    fun asleep(minute: Int): Boolean {
        val within = within(minute)
        if (wasWithin != null && wasWithin != within) forced = null
        wasWithin = within
        return forced ?: within
    }

    companion object {
        /** "23:30" is 1410; anything that is not a time of day is null. */
        fun minutes(time: String): Int? {
            val parts = time.split(':')
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return if (parts.size == 2 && h in 0..23 && m in 0..59) h * 60 + m else null
        }
    }
}

/** The glass is turned down between sunset and sunrise, easing in and out over a while. */
object Evening {
    const val DIM = 0.6f
    private const val EASE_MIN = 40f

    /** What to multiply the owner's brightness by. Times are minutes into the day; -1 is unknown. */
    fun dim(minute: Int, sunrise: Int, sunset: Int): Float {
        if (sunrise < 0 || sunset <= sunrise || minute in sunrise until sunset) return 1f
        val sinceSunset = (minute - sunset + 1440) % 1440
        val untilSunrise = (sunrise - minute + 1440) % 1440
        return 1f - (1f - DIM) * min(1f, min(sinceSunset, untilSunrise) / EASE_MIN)
    }
}

/**
 * What the frame shows while it sleeps: the time in deep amber on black, and nothing else that
 * would light a dark room. Any touch wakes the frame for a while.
 */
class NightView(context: Context) : View(context) {
    var onWake: (() -> Unit)? = null
    /** Alerts that are being kept for the morning. */
    var waiting = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Where the clock and the words are, so Tama can sleep somewhere else. Rebuilt on every draw. */
    val textZones = ArrayList<RectF>()

    private val now = Date()
    private val clock = SimpleDateFormat("HH:mm", Locale.US)
    private val time = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textSize = 290f
        letterSpacing = -0.04f
        textAlign = Paint.Align.CENTER
        color = AMBER
    }
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 15f
        letterSpacing = 0.14f
        textAlign = Paint.Align.CENTER
        color = EMBER
    }

    init {
        visibility = GONE
        alpha = 0f
    }

    override fun onDraw(canvas: Canvas) {
        now.time = System.currentTimeMillis()
        canvas.drawColor(BLACK)
        // The clock moves a little every minute, so no pixel is lit all night.
        val minute = (now.time / 60_000).toInt()
        val x = width / 2f + (minute * 37 % 81 - 40)
        val y = height / 2f + 100f + (minute * 17 % 41 - 20)
        val digits = clock.format(now)
        canvas.drawText(digits, x, y, time)
        val half = time.measureText(digits) / 2f + 30f
        textZones.clear()
        textZones.add(RectF(x - half, y - 250f, x + half, y + 40f))
        textZones.add(RectF(width / 2f - 420f, height - 86f, width / 2f + 420f, height - 36f))
        val held = if (waiting == 0) "" else if (waiting == 1) " · 1 MESSAGE FOR THE MORNING" else " · $waiting MESSAGES FOR THE MORNING"
        canvas.drawText("KNOCK TWICE TO WAKE$held", width / 2f, height - 56f, hint)
        postInvalidateDelayed(60_000 - now.time % 60_000 + 50)
    }

    // While any of it is on the glass, a touch is for waking and not for what is underneath.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onWake?.invoke()
        return true
    }

    private companion object {
        const val BLACK = 0xFF020203.toInt()
        const val AMBER = 0xFF5A2A17.toInt()
        const val EMBER = 0xFF3A1D12.toInt()
    }
}

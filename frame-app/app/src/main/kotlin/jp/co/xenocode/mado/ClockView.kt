package jp.co.xenocode.mado

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Placeholder scene: a clock with dots orbiting it, redrawn every frame. */
class ClockView(context: Context) : View(context) {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("EEEE, MMM d", Locale.US)
    private val now = Date()

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }
    private val datePaint = Paint(timePaint).apply { color = Color.LTGRAY }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = floatArrayOf(0f, 0.7f, 1f)

    override fun onDraw(canvas: Canvas) {
        val t = System.currentTimeMillis()
        now.time = t
        canvas.drawColor(Color.BLACK)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.42f
        val phase = (t % ORBIT_MS) / ORBIT_MS.toFloat()

        for (i in 0 until DOTS) {
            val angle = 2 * PI * (phase + i.toFloat() / DOTS)
            val wobble = 1f + 0.06f * sin(angle * 5 + phase * 2 * PI).toFloat()
            hsv[0] = (360f * i / DOTS + 360f * phase) % 360f
            dotPaint.color = Color.HSVToColor(hsv)
            canvas.drawCircle(
                cx + radius * wobble * cos(angle).toFloat() * (width.toFloat() / height) * 0.9f,
                cy + radius * wobble * sin(angle).toFloat(),
                height / 90f,
                dotPaint,
            )
        }

        timePaint.textSize = height / 4.5f
        datePaint.textSize = height / 18f
        canvas.drawText(timeFormat.format(now), cx, cy + timePaint.textSize / 3, timePaint)
        canvas.drawText(dateFormat.format(now), cx, cy + timePaint.textSize / 3 + datePaint.textSize * 1.6f, datePaint)

        postInvalidateOnAnimation()
    }

    private companion object {
        const val DOTS = 48
        const val ORBIT_MS = 20_000L
    }
}

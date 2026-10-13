package jp.co.xenocode.mado

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tama's face: eyes, brows and mouth chosen separately, so a few shapes of each give many
 * expressions. Drawn in the body's own coordinates, with `r` the body's radius.
 */
internal class TamaFace(private val r: Float) {
    enum class Eyes { OPEN, HAPPY, ASLEEP, SQUEEZED, HALF, SPARKLE, DIZZY }
    enum class Mouth { SMILE, GRIN, OH, FLAT, FROWN, WAVY, CAT, POUT, YAWN, TONGUE, PANT }
    enum class Brows { NONE, WORRIED, ANGRY, RAISED }

    var eyes = Eyes.OPEN
    var mouth = Mouth.SMILE
    var brows = Brows.NONE
    var tear = false

    fun set(eyes: Eyes, mouth: Mouth, brows: Brows = Brows.NONE, tear: Boolean = false) {
        this.eyes = eyes
        this.mouth = mouth
        this.brows = brows
        this.tear = tear
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = INK
    }
    private val box = RectF()
    private val path = Path()

    /**
     * @param ex where the face is looking, as a sideways shift
     * @param ey the height of the eyes
     * @param open how open the eyes are: about 0.1 in a blink, 1 normally, more when startled
     */
    fun draw(canvas: Canvas, ex: Float, ey: Float, open: Float, t: Float) {
        for (side in intArrayOf(-1, 1)) {
            val cx = side * r * 0.36f + ex
            eye(canvas, cx, ey, side, open, t)
            brow(canvas, cx, ey - r * 0.27f, side)
        }
        if (tear) {
            val fall = (t * 0.5f) % 1f
            fill.color = TEAR
            canvas.drawCircle(-r * 0.4f + ex, ey + r * 0.2f + fall * r * 0.3f, r * 0.055f * (1f - fall * 0.4f), fill)
        }
        mouth(canvas, ex, t)
    }

    private fun eye(canvas: Canvas, cx: Float, ey: Float, side: Int, open: Float, t: Float) {
        fill.color = INK
        line.strokeWidth = 3f
        // A blink closes whatever kind of open eye it has.
        val kind = if (open < 0.5f && (eyes == Eyes.SPARKLE || eyes == Eyes.HALF)) Eyes.OPEN else eyes
        when (kind) {
            Eyes.OPEN -> {
                box.set(cx - r * 0.1f, ey - r * 0.15f * open, cx + r * 0.1f, ey + r * 0.15f * open)
                canvas.drawOval(box, fill)
                if (open > 0.6f) glint(canvas, cx - r * 0.03f, ey - r * 0.065f * open, r * 0.036f)
            }
            Eyes.SPARKLE -> {
                box.set(cx - r * 0.135f, ey - r * 0.19f, cx + r * 0.135f, ey + r * 0.19f)
                canvas.drawOval(box, fill)
                glint(canvas, cx - r * 0.04f, ey - r * 0.08f, r * 0.055f)
                glint(canvas, cx + r * 0.05f, ey + r * 0.07f, r * 0.028f)
            }
            Eyes.HAPPY -> {
                box.set(cx - r * 0.12f, ey - r * 0.09f, cx + r * 0.12f, ey + r * 0.13f)
                canvas.drawArc(box, 200f, 140f, false, line)
            }
            Eyes.ASLEEP -> {
                box.set(cx - r * 0.11f, ey - r * 0.1f, cx + r * 0.11f, ey + r * 0.06f)
                canvas.drawArc(box, 15f, 150f, false, line)
            }
            Eyes.SQUEEZED -> {
                path.rewind()
                path.moveTo(cx + side * r * 0.1f, ey - r * 0.09f)
                path.lineTo(cx - side * r * 0.07f, ey)
                path.lineTo(cx + side * r * 0.1f, ey + r * 0.09f)
                canvas.drawPath(path, line)
            }
            Eyes.HALF -> {
                box.set(cx - r * 0.1f, ey - r * 0.13f, cx + r * 0.1f, ey + r * 0.13f)
                canvas.drawArc(box, 0f, 180f, true, fill)
                canvas.drawLine(cx - r * 0.13f, ey, cx + r * 0.13f, ey, line)
            }
            Eyes.DIZZY -> {
                path.rewind()
                for (i in 0..14) {
                    val a = i * 0.75f + t * 7f * side
                    val reach = r * 0.012f * i
                    if (i == 0) path.moveTo(cx, ey) else path.lineTo(cx + reach * cos(a), ey + reach * sin(a))
                }
                line.strokeWidth = 2.2f
                canvas.drawPath(path, line)
            }
        }
    }

    private fun glint(canvas: Canvas, x: Float, y: Float, radius: Float) {
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, y, radius, fill)
        fill.color = INK
    }

    private fun brow(canvas: Canvas, cx: Float, y: Float, side: Int) {
        // "Inner" is the end nearer the middle of the face.
        val inner = cx - side * r * 0.11f
        val outer = cx + side * r * 0.11f
        line.strokeWidth = 2.6f
        when (brows) {
            Brows.WORRIED -> canvas.drawLine(inner, y - r * 0.06f, outer, y + r * 0.03f, line)
            Brows.ANGRY -> canvas.drawLine(inner, y + r * 0.07f, outer, y - r * 0.04f, line)
            Brows.RAISED -> canvas.drawLine(inner, y - r * 0.07f, outer, y - r * 0.07f, line)
            Brows.NONE -> {}
        }
    }

    private fun mouth(canvas: Canvas, ex: Float, t: Float) {
        line.strokeWidth = 3f
        fill.color = INK
        when (mouth) {
            Mouth.SMILE -> {
                box.set(ex - r * 0.12f, r * 0.12f, ex + r * 0.12f, r * 0.34f)
                canvas.drawArc(box, 20f, 140f, false, line)
            }
            Mouth.GRIN, Mouth.PANT -> {
                box.set(ex - r * 0.17f, r * 0.06f, ex + r * 0.17f, r * 0.44f)
                canvas.drawArc(box, 0f, 180f, true, fill)
                fill.color = TONGUE
                val hang = if (mouth == Mouth.PANT) r * 0.12f + r * 0.03f * sin(t * 9f) else 0f
                box.set(ex - r * 0.08f, r * 0.33f, ex + r * 0.08f, r * 0.43f + hang)
                canvas.drawOval(box, fill)
            }
            Mouth.OH -> canvas.drawCircle(ex, r * 0.3f, r * 0.07f, line)
            Mouth.FLAT -> canvas.drawLine(ex - r * 0.08f, r * 0.28f, ex + r * 0.08f, r * 0.28f, line)
            Mouth.FROWN -> {
                box.set(ex - r * 0.12f, r * 0.24f, ex + r * 0.12f, r * 0.46f)
                canvas.drawArc(box, 200f, 140f, false, line)
            }
            Mouth.WAVY -> {
                path.rewind()
                for (i in 0..12) {
                    val px = ex + (i - 6) * r * 0.025f
                    val py = r * 0.28f + r * 0.03f * sin(i * PI.toFloat() / 3f + t * 6f)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                canvas.drawPath(path, line)
            }
            Mouth.CAT -> for (side in intArrayOf(-1, 1)) {
                box.set(ex + side * r * 0.065f - r * 0.065f, r * 0.17f, ex + side * r * 0.065f + r * 0.065f, r * 0.31f)
                canvas.drawArc(box, 10f, 160f, false, line)
            }
            Mouth.POUT -> {
                box.set(ex - r * 0.02f, r * 0.24f, ex + r * 0.12f, r * 0.36f)
                canvas.drawArc(box, 190f, 160f, false, line)
            }
            Mouth.YAWN -> {
                box.set(ex - r * 0.13f, r * 0.1f, ex + r * 0.13f, r * 0.5f)
                canvas.drawOval(box, fill)
                fill.color = TONGUE
                box.set(ex - r * 0.08f, r * 0.36f, ex + r * 0.08f, r * 0.48f)
                canvas.drawOval(box, fill)
            }
            Mouth.TONGUE -> {
                // Concentrating: the tip of its tongue pokes out of the corner of its mouth.
                fill.color = TONGUE
                canvas.drawCircle(ex + r * 0.1f, r * 0.31f, r * 0.06f, fill)
                box.set(ex - r * 0.12f, r * 0.12f, ex + r * 0.12f, r * 0.34f)
                canvas.drawArc(box, 20f, 140f, false, line)
            }
        }
    }

    private companion object {
        const val INK = 0xFF2A1A1A.toInt()
        const val TONGUE = 0xFFFF8C94.toInt()
        const val TEAR = 0xE6A9D8FF.toInt()
    }
}

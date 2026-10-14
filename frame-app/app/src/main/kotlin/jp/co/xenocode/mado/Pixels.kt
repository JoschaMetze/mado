package jp.co.xenocode.mado

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The quest's pixel art: Tama in little squares, hats, creatures made up from a seed, and the
 * realms' scenery. Everything is drawn small, at one pixel per square, and scaled up whole
 * without filtering, which costs the frame's GPU one plain copy.
 */
object Pixels {
    /** Size of the quest's world in squares; the page shows it four times over. */
    const val W = 320
    const val H = 120
    /** Where the ground starts, and where feet stand. */
    const val GROUND = 96
    const val FLOOR = 102

    const val INK = 0xFF2A1A1A.toInt()

    private val HAT_COLORS = mapOf(
        'k' to INK, 'w' to 0xFFF4F4F4.toInt(), 'g' to 0xFF9AA0A6.toInt(), 'd' to 0xFF3A3F48.toInt(),
        'p' to 0xFF7E57C2.toInt(), 'P' to 0xFFB39DDB.toInt(), 'y' to 0xFFFFD54F.toInt(), 'r' to 0xFFE53935.toInt(),
        'b' to 0xFF8D6E63.toInt(), 'B' to 0xFF5D4037.toInt(), 't' to 0xFFD7B98E.toInt(), 'T' to 0xFFE6C26E.toInt(),
        'n' to 0xFF283593.toInt(), 'c' to 0xFF4DD0E1.toInt(), 'm' to 0xFFEC407A.toInt(), 'G' to 0xFF43A047.toInt(),
        'L' to 0xFFA5D6A7.toInt(),
    )

    private val HAT_ART = mapOf(
        Hat.WIZARD to arrayOf(
            "......kk....", ".....kpPk...", ".....kppk...", "....kpypk...", "....kpppk...",
            "...kppppPk..", "...kpppppk..", "..kpppyppPk.", "kkkkkkkkkkkk", "kppppppppppk", ".kkkkkkkkkk.",
        ),
        Hat.VISOR to arrayOf("..kkkkkkkkkk..", ".kGLLLLLLLLGk.", "kGGGGGGGGGGGGk", ".kkkkkkkkkkkk."),
        Hat.ANTENNA to arrayOf(
            ".kk........kk.", "kyyk......kyyk", ".kk........kk.", "..k........k..", "...k......k...",
            "...k......k...", "..kkkkkkkkkk..", ".kggggggggggk.", "..kkkkkkkkkk..",
        ),
        Hat.FEDORA to arrayOf(
            "....kkkkkk....", "...kddkkddk...", "..kddddddddk..", "..kttttttttk..",
            "kkddddddddddkk", "kddddddddddddk", ".kkkkkkkkkkkk.",
        ),
        Hat.TOQUE to arrayOf(
            "..kkk.kkk...", ".kwwwkwwwk..", "kwwwwwwwwwk.", "kwwwwwwwwwk.", ".kwwwwwwwk..",
            "..kwwwwwk...", "..kwwwwwk...", "..kgggggk...", "..kwwwwwk...", "...kkkkk....",
        ),
        Hat.TOPHAT to arrayOf(
            "..kkkkkkkk..", "..kddddddk..", "..kddddddk..", "..kddddddk..", "..kddddddk..",
            "..krrrrrrk..", "..kddddddk..", "kkkkkkkkkkkk", "kddddddddddk", ".kkkkkkkkkk.",
        ),
        Hat.COWBOY to arrayOf(
            ".....kkkkkk.....", "....kbbbbbbk....", "...kbbBbbBbbk...", "k..kBBBBBBBBk..k",
            "kbkkbbbbbbbbkkbk", ".kbbbbbbbbbbbbk.", "..kkkkkkkkkkkk..",
        ),
        Hat.SAILOR to arrayOf(
            "...kkkkkkkk...", "..kwwwwwwwwk..", ".kwwwwwwwwwwk.", ".knnnnnnnnnnk.", "kkkkkkkkkkkkkk", ".kddddddk.....",
        ),
        Hat.PROPELLER to arrayOf(
            "kkkkk...kkkkk", "krrrrkykcccck", ".kkkkkykkkkk.", "....kkkkk....", "..kkryyycck..",
            ".krrryyyccck.", ".krrryyyccck.", "kkkkkkkkkkkkk",
        ),
        Hat.KASA to arrayOf(
            ".......kk.......", ".....kkTTkk.....", "...kkTTTTTTkk...", ".kkTTTTbTTTTTkk.",
            "kTTTTTTTTTTTTTTk", ".kkkkkkkkkkkkkk.",
        ),
        Hat.PARTY to arrayOf(
            "...kyk...", "...kyk...", "....k....", "...kmk...", "...kmk...", "..kymmk..",
            "..kmmyk..", ".kmymmmk.", ".kmmmymk.", "kymmmmmyk", "kkkkkkkkk",
        ),
        Hat.SAFARI to arrayOf(
            "....kkkkkk....", "...kttttttk...", "..kttttttttk..", "..kttttttttk..",
            "..kBBBBBBBBk..", "kkttttttttttkk", ".kkkkkkkkkkkk.",
        ),
        Hat.CROWN to arrayOf(".k...k...k.", "kyk.kyk.kyk", "kyykyyykyyk", "kyyyyryyyyk", "kyyyyyyyyyk", "kkkkkkkkkkk"),
    )

    private val hats = HashMap<Hat, Bitmap>()

    fun hat(hat: Hat): Bitmap = hats.getOrPut(hat) { sprite(HAT_ART.getValue(hat), HAT_COLORS) }

    fun sprite(rows: Array<String>, colors: Map<Char, Int>): Bitmap {
        val w = rows.maxOf { it.length }
        val h = rows.size
        val px = IntArray(w * h)
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, c -> px[y * w + x] = colors[c] ?: 0 } }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun shade(color: Int, by: Float): Int {
        val f = by.coerceIn(0f, 2f)
        fun ch(v: Int) = if (f <= 1f) (v * f).toInt() else (v + (255 - v) * (f - 1f)).toInt()
        return Color.argb(Color.alpha(color), ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
        )
    }

    /** Tama, in squares. Its face is drawn separately, so it can pull faces. */
    class TamaSprite(val bitmap: Bitmap, val eyeY: Int, val leftEye: Int, val rightEye: Int, val mouthX: Int, val mouthY: Int, val headTop: Int)

    private val tamas = HashMap<Long, TamaSprite>()

    /** `squash` above 1 is wide and short, below 1 tall and thin. `sick` tints it green. */
    fun tama(squash: Float, sick: Boolean): TamaSprite {
        val key = (squash * 100).toLong() * 2 + if (sick) 1 else 0
        return tamas.getOrPut(key) { makeTama(squash, sick) }
    }

    private fun makeTama(squash: Float, sick: Boolean): TamaSprite {
        val w = 22
        val h = 18
        val rx = 8.6f * squash
        val ry = 6.2f / squash
        val cx = w / 2f
        val cy = h - 1.5f - ry
        val body = if (sick) 0xFFE2EBC8.toInt() else 0xFFFFE6D2.toInt()
        val shadeC = if (sick) 0xFFB8C98E.toInt() else 0xFFF0BC9A.toInt()
        val outline = if (sick) 0xFF55603A.toInt() else 0xFF6A4434.toInt()
        val px = IntArray(w * h)
        fun inside(x: Int, y: Int): Boolean {
            val dx = (x + 0.5f - cx) / rx
            val dy = (y + 0.5f - cy) / ry
            if (dx * dx + dy * dy <= 1f) return true
            // The tuft, a little to the left of the top.
            val tx = (cx - 1f).toInt()
            val top = (cy - ry).toInt()
            return (x == tx && y in top - 2..top) || (x == tx + 1 && y == top - 3)
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (inside(x, y)) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                px[y * w + x] = if (dx * 0.45f + dy > 0.6f) shadeC else body
            }
        }
        val copy = px.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (copy[y * w + x] != 0) continue
            val near = (-1..1).any { oy -> (-1..1).any { ox ->
                val nx = x + ox
                val ny = y + oy
                (ox == 0 || oy == 0) && nx in 0 until w && ny in 0 until h && copy[ny * w + nx] != 0
            } }
            if (near) px[y * w + x] = outline
        }
        // A highlight, top left.
        val hx = (cx - rx * 0.55f).toInt()
        val hy = (cy - ry * 0.5f).toInt()
        for ((ox, oy) in listOf(0 to 0, 1 to 0, 0 to 1)) px[(hy + oy) * w + hx + ox] = 0xFFFFFFFF.toInt()
        val eyeY = (cy - 0.5f).toInt()
        return TamaSprite(
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888),
            eyeY, (cx - 2.5f).toInt(), (cx + 2.5f).toInt(), cx.toInt(), (cy + 2.2f).toInt(), (cy - ry).toInt(),
        )
    }

    private val creatures = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > 24
    }

    fun foe(foe: Quest.Foe): Bitmap {
        val colors = QuestLore.genres[foe.genre.coerceIn(0, QuestLore.genres.size - 1)].colors
        return creature(foe.seed, if (foe.boss) 30 else 20, colors[abs(foe.seed) % colors.size])
    }

    fun ally(ally: Quest.Ally): Bitmap {
        val colors = QuestLore.genres[ally.genre.coerceIn(0, QuestLore.genres.size - 1)].colors
        return creature(ally.seed, 14, colors[abs(ally.seed / 7) % colors.size])
    }

    /**
     * A creature made up from a seed, mirrored down the middle like the space invaders of old:
     * a body, darker underneath, spots, eyes that look left (towards Tama), and an outline.
     */
    fun creature(seed: Int, size: Int, body: Int): Bitmap = creatures.getOrPut(seed.toLong() * 64 + size) {
        val r = Random(seed)
        val n = size
        val half = (n + 1) / 2
        val cells = Array(n) { BooleanArray(n) }
        val midY = n * (0.45f + r.nextFloat() * 0.15f)
        val reachX = 0.75f + r.nextFloat() * 0.5f
        for (y in 1 until n - 1) for (x in 1 until half) {
            val dx = (half - 0.5f - x) / (half * reachX)
            val dy = (y - midY) / (n * 0.5f)
            val p = 0.95f - 1.25f * (dx * dx + dy * dy)
            cells[y][x] = r.nextFloat() < p
        }
        for (y in 0 until n) for (x in 0 until half) cells[y][n - 1 - x] = cells[y][x]
        keepLargest(cells)
        if (cells.sumOf { row -> row.count { it } } < n * n / 5) {
            for (y in 0 until n) for (x in 0 until n) {
                val dx = (x + 0.5f - n / 2f) / (n * 0.38f)
                val dy = (y + 0.5f - midY) / (n * 0.34f)
                if (dx * dx + dy * dy < 1f) cells[y][x] = true
            }
        }
        // Little legs, sometimes.
        val bottom = (n - 1 downTo 0).first { y -> cells[y].any { it } }
        if (r.nextBoolean() && bottom < n - 2) {
            val leg = 1 + r.nextInt(half / 2)
            for (x in listOf(half - 1 - leg, n - half + leg)) if (x in 1 until n - 1 && cells[bottom][x]) {
                cells[bottom + 1][x] = true
            }
        }
        val px = IntArray(n * n)
        val top = (0 until n).first { y -> cells[y].any { it } }
        val spot = shade(body, if (r.nextBoolean()) 1.45f else 0.6f)
        val spots = HashSet<Int>()
        repeat(r.nextInt(0, 4)) {
            val x = r.nextInt(half)
            val y = r.nextInt(n)
            spots.add(y * n + x)
            spots.add(y * n + n - 1 - x)
        }
        for (y in 0 until n) for (x in 0 until n) if (cells[y][x]) {
            val low = (y - top).toFloat() / max(1, bottom - top)
            px[y * n + x] = when {
                (y * n + x) in spots -> spot
                low > 0.68f -> shade(body, 0.72f)
                low < 0.18f -> shade(body, 1.18f)
                else -> body
            }
        }
        // Eyes: a pair where the body is wide enough, a third of the way down.
        val eyeRow = ((top + (bottom - top) * 0.32f).toInt()..(top + (bottom - top) * 0.55f).toInt())
            .firstOrNull { y -> y in 0 until n && (half - 3 until half).all { cells[y][it] } } ?: (top + (bottom - top) / 2)
        val gap = 1 + r.nextInt(max(1, half / 3))
        val big = n >= 20 || r.nextInt(3) == 0
        for (ex in listOf(half - 1 - gap, n - half + gap)) {
            if (ex !in 0 until n - 1 || eyeRow !in 0 until n - 1) continue
            px[eyeRow * n + ex] = 0xFFFFFFFF.toInt()
            px[eyeRow * n + ex + 1] = 0xFFFFFFFF.toInt()
            if (big) {
                px[(eyeRow + 1) * n + ex] = 0xFFFFFFFF.toInt()
                px[(eyeRow + 1) * n + ex + 1] = INK
            }
            px[eyeRow * n + ex] = INK
        }
        if (r.nextBoolean() && eyeRow + 3 < n) {
            for (mx in half - 2..n - half + 1) if (cells[eyeRow + 3][mx]) px[(eyeRow + 3) * n + mx] = shade(body, 0.35f)
        }
        val out = px.copyOf()
        val edge = shade(body, 0.3f)
        for (y in 0 until n) for (x in 0 until n) {
            if (out[y * n + x] != 0) continue
            val near = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { (ax, ay) -> ax in 0 until n && ay in 0 until n && cells[ay][ax] }
            if (near) px[y * n + x] = edge
        }
        // Cropped to what is there, so it stands on the floor and its health sits just above it.
        var x0 = n
        var y0 = n
        var x1 = 0
        var y1 = 0
        for (y in 0 until n) for (x in 0 until n) if (px[y * n + x] != 0) {
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        val full = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        if (x1 < x0) full else Bitmap.createBitmap(full, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    private fun keepLargest(cells: Array<BooleanArray>) {
        val n = cells.size
        val label = Array(n) { IntArray(n) }
        var best = 0
        var bestSize = 0
        var next = 0
        for (y in 0 until n) for (x in 0 until n) {
            if (!cells[y][x] || label[y][x] != 0) continue
            next++
            var size = 0
            val stack = ArrayDeque<Int>()
            stack.addLast(y * n + x)
            label[y][x] = next
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                val cx = c % n
                val cy = c / n
                size++
                for ((ax, ay) in listOf(cx - 1 to cy, cx + 1 to cy, cx to cy - 1, cx to cy + 1)) {
                    if (ax in 0 until n && ay in 0 until n && cells[ay][ax] && label[ay][ax] == 0) {
                        label[ay][ax] = next
                        stack.addLast(ay * n + ax)
                    }
                }
            }
            if (size > bestSize) {
                bestSize = size
                best = next
            }
        }
        for (y in 0 until n) for (x in 0 until n) if (label[y][x] != best) cells[y][x] = false
    }

    /** A realm's scenery, painted once: the sky, a far layer that scrolls slowly, and the ground. */
    class Backdrop(val sky: Bitmap, val far: Bitmap, val ground: Bitmap, val look: Look)

    private val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    fun backdrop(look: Look, seed: Int): Backdrop {
        val r = Random(seed)
        // The sky in a few bands, dithered into each other the way old consoles did it.
        val sky = IntArray(W * GROUND)
        val steps = 7
        for (y in 0 until GROUND) {
            val t = y.toFloat() / (GROUND - 1) * (steps - 1)
            for (x in 0 until W) {
                val band = (t + bayer[(y and 3) * 4 + (x and 3)] / 16f - 0.5f).toInt().coerceIn(0, steps - 1)
                sky[y * W + x] = mix(look.skyTop, look.skyBottom, band / (steps - 1f))
            }
        }
        val skyBitmap = Bitmap.createBitmap(sky, W, GROUND, Bitmap.Config.ARGB_8888)
        val far = Bitmap.createBitmap(W, GROUND, Bitmap.Config.ARGB_8888)
        paintScenery(Canvas(far), look, r)
        val ground = Bitmap.createBitmap(W, H - GROUND, Bitmap.Config.ARGB_8888)
        paintGround(Canvas(ground), look, r)
        return Backdrop(skyBitmap, far, ground, look)
    }

    private val brush = Paint().apply { isAntiAlias = false }
    private val path = Path()
    private val box = RectF()

    private fun rect(c: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float) {
        brush.color = color
        brush.style = Paint.Style.FILL
        // Drawn twice where it crosses an edge, so the layer tiles without a seam.
        c.drawRect(l, t, r, b, brush)
        if (r > W) c.drawRect(l - W, t, r - W, b, brush)
        if (l < 0) c.drawRect(l + W, t, r + W, b, brush)
    }

    private fun circle(c: Canvas, color: Int, x: Float, y: Float, radius: Float) {
        brush.color = color
        brush.style = Paint.Style.FILL
        c.drawCircle(x, y, radius, brush)
        if (x + radius > W) c.drawCircle(x - W, y, radius, brush)
        if (x - radius < 0) c.drawCircle(x + W, y, radius, brush)
    }

    private fun poly(c: Canvas, color: Int, vararg p: Float) {
        brush.color = color
        brush.style = Paint.Style.FILL
        for (shift in floatArrayOf(0f, -W.toFloat(), W.toFloat())) {
            path.rewind()
            path.moveTo(p[0] + shift, p[1])
            var i = 2
            while (i < p.size) {
                path.lineTo(p[i] + shift, p[i + 1])
                i += 2
            }
            path.close()
            c.drawPath(path, brush)
        }
    }

    private fun hills(c: Canvas, color: Int, base: Float, height: Float, r: Random) {
        brush.color = color
        val a = r.nextFloat() * 6f
        val b = r.nextFloat() * 6f
        for (x in 0 until W) {
            // Whole waves across the width, so the hills tile.
            val t = x * 2 * Math.PI.toFloat() / W
            val top = base - height * (0.55f + 0.3f * sin(t * 2 + a) + 0.15f * sin(t * 5 + b))
            c.drawRect(x.toFloat(), top, x + 1f, GROUND.toFloat(), brush)
        }
    }

    private fun paintScenery(c: Canvas, look: Look, r: Random) {
        val g = GROUND.toFloat()
        val haze = mix(look.far, look.skyBottom, 0.45f)
        when (look.scenery) {
            Scenery.CASTLE -> {
                hills(c, haze, g - 10f, 26f, r)
                val cx = 60f + r.nextFloat() * 200f
                for (i in -2..2) {
                    val tx = cx + i * 13f
                    val top = g - 40f - (if (i == 0) 22f else if (abs(i) == 1) 10f else 0f)
                    rect(c, look.far, tx - 5f, top, tx + 5f, g)
                    for (k in 0..2) rect(c, look.far, tx - 5f + k * 4f, top - 3f, tx - 3f + k * 4f, top)
                    poly(c, look.near, tx - 6f, top - 3f, tx, top - 13f, tx + 6f, top - 3f)
                    rect(c, INK, tx - 1f, top + 8f, tx + 1f, top + 12f)
                }
                hills(c, look.near, g, 9f, r)
                repeat(9) {
                    val x = r.nextFloat() * W
                    rect(c, shade(look.near, 0.6f), x, g - 10f, x + 2f, g)
                    circle(c, look.near, x + 1f, g - 13f, 5f + r.nextFloat() * 3f)
                }
            }
            Scenery.CUBICLES -> {
                for (i in 0 until 6) {
                    val x = i * 54f + 8f
                    rect(c, mix(look.skyTop, 0xFF9FC5E8.toInt(), 0.6f), x, 12f, x + 34f, 40f)
                    rect(c, look.far, x + 16f, 12f, x + 18f, 40f)
                }
                circle(c, 0xFFF4F4F4.toInt(), 290f, 20f, 6f)
                rect(c, INK, 290f, 16f, 291f, 21f)
                var x = 0f
                while (x < W) {
                    val w = 36f + r.nextFloat() * 20f
                    rect(c, look.far, x, g - 26f, x + w - 3f, g)
                    rect(c, shade(look.far, 0.8f), x, g - 26f, x + w - 3f, g - 24f)
                    rect(c, INK, x + 8f, g - 36f, x + 22f, g - 27f)
                    rect(c, 0xFF7FE0F0.toInt(), x + 9f, g - 35f, x + 21f, g - 29f)
                    if (r.nextInt(3) == 0) {
                        rect(c, 0xFF8D6E63.toInt(), x + w - 12f, g - 33f, x + w - 6f, g - 27f)
                        circle(c, 0xFF4CAF50.toInt(), x + w - 9f, g - 36f, 5f)
                    }
                    x += w
                }
            }
            Scenery.PLANETS -> {
                repeat(70) {
                    val sx = r.nextFloat() * W
                    val sy = r.nextFloat() * (g - 10f)
                    rect(c, if (r.nextInt(4) == 0) 0xFFFFF3B0.toInt() else 0xFFFFFFFF.toInt(), sx, sy, sx + 1f, sy + 1f)
                }
                val px = 80f + r.nextFloat() * 160f
                circle(c, look.far, px, 34f, 18f)
                circle(c, shade(look.far, 1.3f), px - 5f, 29f, 7f)
                brush.style = Paint.Style.STROKE
                brush.color = 0xFFE6C26E.toInt()
                brush.strokeWidth = 2f
                box.set(px - 30f, 30f, px + 30f, 38f)
                c.drawOval(box, brush)
                circle(c, look.far, px, 31f, 17f)
                circle(c, 0xFFB0BEC5.toInt(), (px + 120f) % W, 18f, 5f)
                rect(c, look.near, 0f, g - 14f, W.toFloat(), g)
                var x = 0f
                while (x < W) {
                    val h = 6f + r.nextFloat() * 22f
                    rect(c, look.near, x, g - 14f - h, x + 10f + r.nextFloat() * 14f, g)
                    if (r.nextInt(3) == 0) rect(c, 0xFF7FE0F0.toInt(), x + 3f, g - 10f - h, x + 5f, g - 8f - h)
                    x += 16f + r.nextFloat() * 22f
                }
            }
            Scenery.CITY -> {
                circle(c, 0xFFF2EBC8.toInt(), 60f + r.nextFloat() * 200f, 20f, 9f)
                for (layer in 0..1) {
                    var x = 0f
                    val color = if (layer == 0) look.far else look.near
                    while (x < W) {
                        val w = 14f + r.nextFloat() * 22f
                        val h = (if (layer == 0) 40f else 22f) + r.nextFloat() * 30f
                        rect(c, color, x, g - h, x + w, g)
                        if (r.nextInt(4) == 0) rect(c, color, x + w / 2 - 1f, g - h - 8f, x + w / 2 + 1f, g - h)
                        var wy = g - h + 4f
                        while (wy < g - 4f) {
                            var wx = x + 3f
                            while (wx < x + w - 3f) {
                                if (r.nextInt(3) == 0) rect(c, if (layer == 0) 0xFFB8A050.toInt() else 0xFFFFD166.toInt(), wx, wy, wx + 2f, wy + 2f)
                                wx += 5f
                            }
                            wy += 6f
                        }
                        x += w + 2f
                    }
                }
            }
            Scenery.SHELVES -> {
                rect(c, mix(look.skyTop, 0xFFFFFFFF.toInt(), 0.4f), 120f, 14f, 170f, 50f)
                rect(c, 0xFF9FD0F0.toInt(), 124f, 18f, 166f, 46f)
                rect(c, 0xFFE57373.toInt(), 118f, 12f, 128f, 54f)
                rect(c, 0xFFE57373.toInt(), 162f, 12f, 172f, 54f)
                for (shelf in listOf(36f, 62f)) {
                    rect(c, look.near, 0f, shelf, W.toFloat(), shelf + 3f)
                    var x = 4f
                    while (x < W) {
                        if (x < 112f || x > 178f || shelf > 55f) {
                            val h = 6f + r.nextFloat() * 10f
                            val jar = listOf(0xFFE8A87C.toInt(), 0xFFC5E1A5.toInt(), 0xFFFFE082.toInt(), 0xFFEF9A9A.toInt()).random(r)
                            rect(c, jar, x, shelf - h, x + 8f, shelf)
                            rect(c, look.near, x - 1f, shelf - h - 2f, x + 9f, shelf - h)
                        }
                        x += 12f + r.nextFloat() * 8f
                    }
                }
                repeat(3) {
                    val x = 200f + it * 34f
                    rect(c, INK, x, 0f, x + 1f, 74f)
                    circle(c, 0xFF6D6D6D.toInt(), x, 80f, 8f)
                    circle(c, 0xFF8D8D8D.toInt(), x - 2f, 78f, 3f)
                }
            }
            Scenery.GRAVES -> {
                circle(c, 0xFFE8E3C8.toInt(), 230f, 24f, 14f)
                circle(c, look.skyTop, 236f, 20f, 12f)
                hills(c, look.far, g - 6f, 16f, r)
                val hx = 40f + r.nextFloat() * 60f
                rect(c, look.near, hx, g - 46f, hx + 36f, g)
                poly(c, look.near, hx - 4f, g - 46f, hx + 18f, g - 64f, hx + 40f, g - 46f)
                rect(c, 0xFFFFD166.toInt(), hx + 8f, g - 36f, hx + 12f, g - 31f)
                repeat(3) {
                    val tx = 150f + it * 60f + r.nextFloat() * 20f
                    brush.color = look.near
                    rect(c, look.near, tx, g - 34f, tx + 3f, g)
                    rect(c, look.near, tx - 8f, g - 30f, tx, g - 28f)
                    rect(c, look.near, tx + 3f, g - 24f, tx + 11f, g - 22f)
                    rect(c, look.near, tx - 6f, g - 34f, tx - 4f, g - 30f)
                }
                repeat(8) {
                    val x = r.nextFloat() * W
                    val h = 7f + r.nextFloat() * 5f
                    rect(c, 0xFF8A8F9A.toInt(), x, g - h, x + 7f, g)
                    rect(c, 0xFF8A8F9A.toInt(), x + 1f, g - h - 1f, x + 6f, g - h)
                    rect(c, 0xFF5A5F6A.toInt(), x + 3f, g - h + 2f, x + 4f, g - h + 5f)
                }
            }
            Scenery.MESAS -> {
                circle(c, 0xFFFFF0B0.toInt(), 160f + r.nextFloat() * 100f, 60f, 16f)
                repeat(4) {
                    val x = it * 85f + r.nextFloat() * 30f
                    val h = 22f + r.nextFloat() * 26f
                    val w = 34f + r.nextFloat() * 30f
                    poly(c, look.far, x, g, x + 8f, g - h, x + w, g - h, x + w + 8f, g)
                    rect(c, shade(look.far, 0.85f), x + 6f, g - h + 6f, x + w + 2f, g - h + 8f)
                }
                repeat(5) {
                    val x = r.nextFloat() * W
                    val h = 12f + r.nextFloat() * 10f
                    rect(c, look.near, x, g - h, x + 3f, g)
                    rect(c, look.near, x - 4f, g - h + 5f, x - 2f, g - h + 10f)
                    rect(c, look.near, x - 4f, g - h + 9f, x, g - h + 11f)
                    rect(c, look.near, x + 5f, g - h + 3f, x + 7f, g - h + 8f)
                    rect(c, look.near, x + 3f, g - h + 7f, x + 7f, g - h + 9f)
                }
            }
            Scenery.KELP -> {
                repeat(5) {
                    val x = it * 70f + r.nextFloat() * 30f
                    poly(c, 0x18FFFFFF, x, 0f, x + 14f, 0f, x + 44f, g, x + 30f, g)
                }
                val sx = 180f + r.nextFloat() * 60f
                poly(c, look.far, sx, g - 10f, sx + 60f, g - 10f, sx + 50f, g, sx + 10f, g)
                rect(c, look.far, sx + 28f, g - 40f, sx + 31f, g - 10f)
                rect(c, look.far, sx + 18f, g - 34f, sx + 42f, g - 32f)
                repeat(14) {
                    val x = r.nextFloat() * W
                    val h = 20f + r.nextFloat() * 50f
                    var y = g
                    while (y > g - h) {
                        val wob = sin(y * 0.3f + x) * 2f
                        rect(c, look.near, x + wob, y - 3f, x + wob + 2f, y)
                        y -= 3f
                    }
                }
                repeat(6) {
                    val x = r.nextFloat() * W
                    val coral = listOf(0xFFFF7F6E.toInt(), 0xFFFFB3C6.toInt(), 0xFFFFD166.toInt()).random(r)
                    circle(c, coral, x, g - 3f, 4f + r.nextFloat() * 3f)
                    circle(c, coral, x + 4f, g - 6f, 3f)
                }
            }
            Scenery.SYNTH -> {
                val sx = 160f
                for (y in 0 until 34) {
                    val t = y / 34f
                    if (y > 16 && (y / 3) % 2 == 1) continue
                    val half = sqrtF(34f * 34f / 4 - (y - 17f) * (y - 17f)) * 1.0f
                    rect(c, mix(0xFFFFD166.toInt(), look.far, t), sx - half, 30f + y, sx + half, 31f + y)
                }
                brush.style = Paint.Style.STROKE
                brush.strokeWidth = 1f
                repeat(5) {
                    val x = it * 70f + r.nextFloat() * 20f
                    val h = 14f + r.nextFloat() * 18f
                    brush.color = look.near
                    path.rewind()
                    path.moveTo(x, g)
                    path.lineTo(x + 20f, g - h)
                    path.lineTo(x + 40f, g)
                    c.drawPath(path, brush)
                }
                brush.style = Paint.Style.FILL
                repeat(30) {
                    val x = r.nextFloat() * W
                    val y = r.nextFloat() * 30f
                    rect(c, 0xFFFFFFFF.toInt(), x, y, x + 1f, y + 1f)
                }
            }
            Scenery.TORII -> {
                poly(c, look.far, 60f, g, 150f, g - 62f, 160f, g - 62f, 250f, g)
                poly(c, 0xFFF4F4F4.toInt(), 133f, g - 50f, 150f, g - 62f, 160f, g - 62f, 177f, g - 50f, 165f, g - 52f, 155f, g - 48f, 145f, g - 52f)
                hills(c, shade(look.far, 0.8f), g, 10f, r)
                val tx = 40f + r.nextFloat() * 220f
                rect(c, look.near, tx, g - 34f, tx + 3f, g)
                rect(c, look.near, tx + 25f, g - 34f, tx + 28f, g)
                rect(c, look.near, tx - 6f, g - 38f, tx + 34f, g - 34f)
                rect(c, INK, tx - 7f, g - 40f, tx + 35f, g - 38f)
                rect(c, look.near, tx - 2f, g - 28f, tx + 30f, g - 26f)
                repeat(6) {
                    val x = r.nextFloat() * W
                    rect(c, INK, x, 8f, x + 1f, 16f + r.nextFloat() * 10f)
                    circle(c, 0xFFFF8A65.toInt(), x + 0.5f, 22f + r.nextFloat() * 6f, 3f)
                }
            }
            Scenery.DISCO -> {
                repeat(5) {
                    val x = 20f + it * 70f
                    val color = listOf(0x30FF3EA5, 0x302DE2E6, 0x30F1C40F, 0x308A2BE2).random(r)
                    poly(c, color, 160f, 10f, x - 10f, g, x + 14f, g)
                }
                circle(c, look.far, 160f, 14f, 10f)
                for (i in -9..9 step 3) {
                    rect(c, 0xFF8A8FA0.toInt(), 150f, 14f + i * 0.9f, 170f, 14.5f + i * 0.9f)
                    rect(c, 0xFF8A8FA0.toInt(), 160f + i, 4f, 160.5f + i, 24f)
                }
                rect(c, INK, 159.5f, 0f, 160.5f, 4f)
                for (x in listOf(10f, 270f)) {
                    rect(c, INK, x, g - 40f, x + 30f, g)
                    for (k in 0..2) circle(c, 0xFF555566.toInt(), x + 15f, g - 32f + k * 12f, 4f)
                }
            }
            Scenery.VOLCANO -> {
                val vx = 160f + r.nextFloat() * 80f
                poly(c, look.far, vx - 70f, g, vx - 14f, g - 56f, vx + 14f, g - 56f, vx + 70f, g)
                poly(c, 0xFFFF7043.toInt(), vx - 14f, g - 56f, vx + 14f, g - 56f, vx + 8f, g - 50f, vx - 8f, g - 50f)
                repeat(4) { circle(c, 0x80BDBDBD.toInt(), vx - 6f + it * 5f, g - 62f - it * 7f, 4f + it * 1.5f) }
                hills(c, shade(look.near, 1.25f), g, 12f, r)
                repeat(6) {
                    val x = r.nextFloat() * W
                    val h = 22f + r.nextFloat() * 14f
                    for (k in 0..6) rect(c, 0xFF7A5A3A.toInt(), x + sin(k * 0.5f) * 2f, g - k * h / 7f - 4f, x + 2f + sin(k * 0.5f) * 2f, g - k * h / 7f)
                    for (a in 0 until 5) {
                        val ang = -Math.PI.toFloat() / 2 + (a - 2) * 0.6f
                        for (k in 1..9) rect(c, look.near, x + cos(ang) * k * 1.6f, g - h + sin(ang) * k * 1.2f + k * k * 0.12f, x + cos(ang) * k * 1.6f + 2f, g - h + sin(ang) * k * 1.2f + k * k * 0.12f + 2f)
                    }
                }
            }
        }
    }

    private fun sqrtF(v: Float) = kotlin.math.sqrt(max(0f, v))

    private fun paintGround(c: Canvas, look: Look, r: Random) {
        val h = (H - GROUND).toFloat()
        brush.style = Paint.Style.FILL
        brush.color = look.ground
        c.drawRect(0f, 0f, W.toFloat(), h, brush)
        rect(c, shade(look.ground, 1.15f), 0f, 0f, W.toFloat(), 2f)
        when (look.scenery) {
            Scenery.SHELVES, Scenery.DISCO -> {
                var x = 0
                while (x < W) {
                    var y = 2
                    while (y < h) {
                        if (((x / 8) + (y / 8)) % 2 == 0) rect(c, look.groundAccent, x.toFloat(), y.toFloat(), x + 8f, y + 8f)
                        y += 8
                    }
                    x += 8
                }
            }
            Scenery.PLANETS -> {
                for (x in 0 until W step 20) rect(c, look.groundAccent, x.toFloat(), 2f, x + 1f, h)
                for (x in 0 until W step 20) {
                    rect(c, shade(look.ground, 1.4f), x + 4f, 6f, x + 5f, 7f)
                    rect(c, shade(look.ground, 1.4f), x + 15f, 6f, x + 16f, 7f)
                }
                rect(c, 0xFFFFC107.toInt(), 0f, h - 4f, W.toFloat(), h - 3f)
            }
            Scenery.SYNTH -> {
                brush.color = look.groundAccent
                for (y in listOf(3f, 7f, 13f, 21f)) c.drawRect(0f, y, W.toFloat(), y + 1f, brush)
                brush.strokeWidth = 1f
                for (x in 0 until W step 16) c.drawLine(x.toFloat(), 2f, 160f + (x - 160f) * 3f, h, brush)
            }
            Scenery.CITY -> repeat(40) {
                val x = r.nextFloat() * W
                val y = 4f + r.nextFloat() * (h - 6f)
                rect(c, look.groundAccent, x, y, x + 6f + r.nextFloat() * 10f, y + 1f)
            }
            Scenery.TORII -> {
                var x = 0f
                var row = 0
                var y = 3f
                while (y < h) {
                    x = if (row % 2 == 0) 0f else -6f
                    while (x < W) {
                        rect(c, look.groundAccent, x + 1f, y, x + 11f, y + 1f)
                        rect(c, look.groundAccent, x + 11f, y, x + 12f, y + 5f)
                        x += 12f
                    }
                    y += 6f
                    row++
                }
            }
            else -> repeat(70) {
                val x = r.nextFloat() * W
                val y = 3f + r.nextFloat() * (h - 4f)
                rect(c, if (r.nextInt(5) == 0) shade(look.ground, 1.25f) else look.groundAccent, x, y, x + 2f, y + 1f)
            }
        }
    }

    /** A snack, as a tiny round thing coloured by its name. */
    fun snackColor(name: String): Int {
        val palette = intArrayOf(0xFFFFCC80.toInt(), 0xFFF48FB1.toInt(), 0xFFA5D6A7.toInt(), 0xFFFFF59D.toInt(), 0xFFCE93D8.toInt(), 0xFF90CAF9.toInt())
        return palette[abs(name.hashCode()) % palette.size]
    }

    fun distance(ax: Float, ay: Float, bx: Float, by: Float) = hypot(ax - bx, ay - by)

    fun clamp01(v: Float) = min(1f, max(0f, v))
}

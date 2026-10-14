package jp.co.xenocode.mado

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.DecelerateInterpolator
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tama's Quest, opened from the rail: the quest's world in big pixels across the top, and
 * underneath what has happened lately and how Tama is doing. Tapping the scene cheers Tama on;
 * FEED and MEDICINE reach through to it. Tama itself leaves the glass while this is open: it is
 * in here.
 */
class QuestView(context: Context) : View(context) {
    var quest: Quest? = null
    /** Fully covering the scene underneath, or not any more. */
    var onCover: ((Boolean) -> Unit)? = null
    var onClosing: (() -> Unit)? = null

    private val frame = Bitmap.createBitmap(Pixels.W, Pixels.H, Bitmap.Config.ARGB_8888)
    private val pix = Canvas(frame)
    private val pixelPaint = Paint()
    private val dot = Paint().apply { isAntiAlias = false }
    private var backdrop: Pixels.Backdrop? = null
    private var backdropKey = ""
    private var panel: Bitmap? = null
    private var panelKey = ""
    private val src = Rect()
    private val dst = RectF()

    private var scroll = 0f
    private var lastFrame = 0L
    private var openedAt = 0L
    private var closing = false
    private var unveil: Animator? = null
    private val autoClose = Runnable { close() }
    val isOpen get() = visibility == VISIBLE && !closing

    private class Anim(val kind: Quest.Kind, val value: Int, val at: Long)
    private val anims = ArrayList<Anim>()
    private var lastSeq = -1L
    private var nextSlot = 0L

    private class Floater(val text: String, val x: Float, val y: Float, val color: Int, val at: Long, val big: Boolean = false)
    private val floaters = ArrayList<Floater>()

    // Where the scenery of the room in front stands, in squares; it scrolls away as Tama walks on.
    private var prop: Quest.Room? = null
    private var propX = 0f
    private var propSeed = 0
    private var propAt = 0L

    private val motes = 48
    private val moteX = FloatArray(motes)
    private val moteY = FloatArray(motes)
    private val moteV = FloatArray(motes)
    private val random = Random(SystemClock.uptimeMillis())

    private val italic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansBold = Typeface.create("sans-serif", Typeface.BOLD)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val logText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = sans
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()

    private val closeZone = RectF()
    private val feedZone = RectF()
    private val medicineZone = RectF()

    init {
        visibility = GONE
        for (i in 0 until motes) {
            moteX[i] = random.nextFloat() * Pixels.W
            moteY[i] = random.nextFloat() * Pixels.GROUND
            moteV[i] = 0.5f + random.nextFloat()
        }
    }

    /** Opens growing out of where Tama was on the glass. */
    fun open(x: Float, y: Float) {
        val q = quest ?: return
        lastSeq = q.recentEvents.lastOrNull()?.seq ?: 0L
        anims.clear()
        floaters.clear()
        prop = q.rooms.getOrNull(q.roomIndex).takeIf { q.phase == Quest.Phase.ROOM }
        propX = FOE_X
        propSeed = q.title.hashCode() + q.roomIndex
        closing = false
        openedAt = SystemClock.uptimeMillis()
        lastFrame = 0L
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        unveil?.cancel()
        if (isAttachedToWindow && width > 0) {
            val reach = hypot(max(x, width - x), max(y, height - y))
            unveil = ViewAnimationUtils.createCircularReveal(this, x.toInt(), y.toInt(), 0f, reach).apply {
                duration = UNVEIL_MS
                interpolator = DecelerateInterpolator(1.5f)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (unveil === animation && isOpen) onCover?.invoke(true)
                    }
                })
                start()
            }
        } else {
            onCover?.invoke(true)
        }
        stayOpen()
        invalidate()
    }

    fun close() {
        removeCallbacks(autoClose)
        if (!isOpen) return
        closing = true
        unveil?.cancel()
        unveil = null
        onCover?.invoke(false)
        onClosing?.invoke()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        panel = null
        panelKey = ""
    }

    private val scale get() = width / Pixels.W.toFloat()
    private val sceneH get() = Pixels.H * scale

    override fun onDraw(canvas: Canvas) {
        val q = quest ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.2f, (now - lastFrame) / 1000f)
        lastFrame = now
        takeEvents(q, now)

        val genreS = q.genreOf(q.setting)
        val key = "${q.setting}/${q.flavor}/${q.title}"
        if (key != backdropKey || backdrop == null) {
            backdropKey = key
            backdrop = Pixels.backdrop(genreS.look, key.hashCode())
        }
        val back = backdrop!!

        val entering = prop != null && now - propAt < ENTER_MS
        val walking = q.phase == Quest.Phase.TRAVEL || entering
        if (walking) scroll += WALK_SPEED * dt
        if (entering) {
            val t = (now - propAt) / ENTER_MS.toFloat()
            propX = FOE_X + (Pixels.W + 30f - FOE_X) * (1f - t) * (1f - t)
        } else if (prop != null && q.phase != Quest.Phase.ROOM) {
            // Walking on: the room's things go by.
            propX -= WALK_SPEED * dt
            if (propX < -60f) prop = null
        }
        drawScene(q, back, now, walking)

        val s = scale
        src.set(0, 0, Pixels.W, Pixels.H)
        val shake = if (since(Quest.Kind.TAMA_CRIT, now) < 0.25f) (random.nextFloat() - 0.5f) * 8f else 0f
        dst.set(shake, 0f, width + shake, sceneH)
        canvas.drawBitmap(frame, src, dst, pixelPaint)

        val ph = height - sceneH
        val key2 = panelKey(q)
        if (panel == null || key2 != panelKey) {
            panelKey = key2
            val bitmap = panel?.takeIf { it.width == width && it.height == ph.toInt() }
                ?: Bitmap.createBitmap(width, max(1, ph.toInt()), Bitmap.Config.ARGB_8888)
            drawPanel(Canvas(bitmap), q, genreS.look, bitmap.width.toFloat(), bitmap.height.toFloat())
            panel = bitmap
        }
        canvas.drawBitmap(panel!!, 0f, sceneH, null)

        drawOverlay(canvas, q, now)
        postInvalidateDelayed(FRAME_MS)
    }

    /** New events from the quest become animations, one after another rather than all at once. */
    private fun takeEvents(q: Quest, now: Long) {
        for (e in q.recentEvents) {
            if (e.seq <= lastSeq) continue
            lastSeq = e.seq
            val at = max(now, nextSlot)
            nextSlot = at + when (e.kind) {
                Quest.Kind.TAMA_HIT, Quest.Kind.TAMA_CRIT, Quest.Kind.TAMA_MISS, Quest.Kind.ALLY_HIT, Quest.Kind.FOE_HIT, Quest.Kind.FOE_MISS -> 650L
                else -> 250L
            }
            anims.add(Anim(e.kind, e.value, at))
            started(q, e.kind, e.value, at)
        }
        anims.removeAll { now - it.at > 6_000 }
        floaters.removeAll { now - it.at > 2_200 }
    }

    private fun started(q: Quest, kind: Quest.Kind, value: Int, at: Long) {
        val foeTop = FOE_X to (Pixels.FLOOR - 30f)
        val tamaTop = TAMA_X to (Pixels.FLOOR - 24f)
        when (kind) {
            Quest.Kind.TAMA_HIT -> floaters.add(Floater("-$value", foeTop.first, foeTop.second, 0xFFFFFFFF.toInt(), at + 200))
            Quest.Kind.TAMA_CRIT -> floaters.add(Floater("-$value!", foeTop.first, foeTop.second, 0xFFFFD54F.toInt(), at + 200, big = true))
            Quest.Kind.ALLY_HIT -> floaters.add(Floater("-$value", foeTop.first + 8f, foeTop.second + 6f, 0xFFB3E5FC.toInt(), at + 200))
            Quest.Kind.TAMA_MISS -> floaters.add(Floater("MISS", foeTop.first, foeTop.second, 0xFFB0BEC5.toInt(), at + 200))
            Quest.Kind.FOE_HIT -> floaters.add(Floater("-$value", tamaTop.first, tamaTop.second, 0xFFFF8A80.toInt(), at + 200))
            Quest.Kind.FOE_MISS -> floaters.add(Floater("MISS", tamaTop.first, tamaTop.second, 0xFFB0BEC5.toInt(), at + 200))
            Quest.Kind.BEFRIENDED -> floaters.add(Floater("♥", foeTop.first, foeTop.second, 0xFFFF80AB.toInt(), at, big = true))
            Quest.Kind.LEVEL_UP -> floaters.add(Floater("LEVEL $value!", tamaTop.first, tamaTop.second - 6f, 0xFFFFD54F.toInt(), at, big = true))
            Quest.Kind.LOOT -> floaters.add(Floater("LOOT!", tamaTop.first + 30f, tamaTop.second, 0xFFFFE082.toInt(), at))
            Quest.Kind.MIMIC -> floaters.add(Floater("HUG", FOE_X, foeTop.second, 0xFFFF80AB.toInt(), at))
            Quest.Kind.HEAL -> floaters.add(Floater("+HP", tamaTop.first, tamaTop.second, 0xFFA5D6A7.toInt(), at))
            Quest.Kind.EAT -> floaters.add(Floater("nom", tamaTop.first + 14f, tamaTop.second + 4f, 0xFFFFE0B2.toInt(), at))
            Quest.Kind.CHEER -> floaters.add(Floater("!", tamaTop.first, tamaTop.second - 4f, 0xFFFFFFFF.toInt(), at, big = true))
            Quest.Kind.SICK -> floaters.add(Floater("achoo", tamaTop.first, tamaTop.second, 0xFFC5E1A5.toInt(), at))
            Quest.Kind.BETTER -> floaters.add(Floater("better!", tamaTop.first, tamaTop.second, 0xFFA5D6A7.toInt(), at))
            Quest.Kind.ENTER -> {
                prop = Quest.Room.values().getOrNull(value)
                propSeed = q.title.hashCode() + q.roomIndex
                propAt = at
                propX = Pixels.W + 30f
            }
            Quest.Kind.NEW_QUEST -> prop = null
            else -> {}
        }
    }

    private fun since(kind: Quest.Kind, now: Long): Float {
        var best = Float.MAX_VALUE
        for (a in anims) if (a.kind == kind && a.at <= now) best = min(best, (now - a.at) / 1000f)
        return best
    }

    // ---- The scene, in squares ----

    private fun drawScene(q: Quest, back: Pixels.Backdrop, now: Long, walking: Boolean) {
        val t = now / 1000f
        val c = pix
        c.drawBitmap(back.sky, 0f, 0f, null)
        val farX = -(scroll * 0.35f) % Pixels.W
        c.drawBitmap(back.far, farX, 0f, null)
        c.drawBitmap(back.far, farX + Pixels.W, 0f, null)
        val groundX = -scroll % Pixels.W
        c.drawBitmap(back.ground, groundX, Pixels.GROUND.toFloat(), null)
        c.drawBitmap(back.ground, groundX + Pixels.W, Pixels.GROUND.toFloat(), null)
        if (back.look.scenery == Scenery.DISCO) disco(c, back.look, t)

        val resting = q.phase == Quest.Phase.CAMP || q.phase == Quest.Phase.NIGHT
        if (resting) camp(c, t, q.phase == Quest.Phase.NIGHT)
        if (q.phase == Quest.Phase.INN) inn(c)
        prop?.let { drawProp(c, q, it, propX, t, now) }

        q.allies.forEachIndexed { i, ally ->
            val sprite = Pixels.ally(ally)
            val x = TAMA_X - 26f - i * 22f
            val hop = if (walking) abs(sin(t * 7f + i)) * 2f else if (since(Quest.Kind.ALLY_HIT, now) < 0.4f && i == 0) 4f else 0f
            c.drawBitmap(sprite, x - sprite.width / 2f, Pixels.FLOOR - sprite.height - hop + 1f, null)
        }
        drawTama(c, q, t, now, walking)

        if (q.phase == Quest.Phase.NIGHT) {
            dot.color = 0x88081030.toInt()
            c.drawRect(0f, 0f, Pixels.W.toFloat(), Pixels.H.toFloat(), dot)
            dot.color = 0xFFF6F1D3.toInt()
            c.drawCircle(270f, 18f, 7f, dot)
        }
        drift(c, q, back.look, t)
        sparkles(c, now)
    }

    private fun drawTama(c: Canvas, q: Quest, t: Float, now: Long, walking: Boolean) {
        val fainted = q.phase == Quest.Phase.FAINTED
        val asleep = q.phase == Quest.Phase.NIGHT || q.phase == Quest.Phase.DOZING
        val sick = q.needs.sickness != null
        val hit = since(Quest.Kind.TAMA_HIT, now).let { min(it, since(Quest.Kind.TAMA_CRIT, now)) }.let { min(it, since(Quest.Kind.TAMA_MISS, now)) }
        val hurt = since(Quest.Kind.FOE_HIT, now)
        val joy = listOf(Quest.Kind.BEFRIENDED, Quest.Kind.LEVEL_UP, Quest.Kind.LOOT, Quest.Kind.EAT, Quest.Kind.QUEST_DONE, Quest.Kind.CHEER, Quest.Kind.BETTER)
            .minOf { since(it, now) }
        val squash = when {
            fainted -> 1.5f
            asleep -> 1.12f
            hurt < 0.15f -> 1.2f
            joy < 0.25f -> 0.85f
            q.needs.sickness == Sickness.HICCUPS && (now / 700) % 6 == 0L -> 0.88f
            else -> 1f + 0.04f * sin(t * 2.4f)
        }
        val sprite = Pixels.tama((squash * 20).toInt() / 20f, q.needs.sickness == Sickness.TUMMY)
        val bmp = sprite.bitmap
        var x = TAMA_X
        // A lunge towards the foe for its own blows, a knock back for the foe's.
        if (hit < 0.35f) x += 16f * sin(hit / 0.35f * PI.toFloat())
        if (hurt < 0.3f) x -= 5f * sin(hurt / 0.3f * PI.toFloat())
        var lift = 0f
        if (walking) lift = abs(sin(t * 8f)) * 2f
        if (joy < 0.6f) lift = 7f * sin(joy / 0.6f * PI.toFloat())
        if (q.needs.sickness == Sickness.HICCUPS && (now / 700) % 6 == 0L) lift = 3f
        val left = x - bmp.width / 2f
        val top = Pixels.FLOOR - bmp.height + 1f - lift
        if (hurt < 0.3f && (now / 60) % 2 == 0L) {
            // A flash, every other frame.
            c.drawBitmap(bmp, left, top, whiteFlash)
        } else {
            c.drawBitmap(bmp, left, top, null)
        }
        if (q.needs.sickness == Sickness.GLITCH && (now / 120) % 9 == 0L) {
            c.drawBitmap(bmp, left + 2f, top, cyanGhost)
            c.drawBitmap(bmp, left - 2f, top, magentaGhost)
        }

        val ex1 = left + sprite.leftEye
        val ex2 = left + sprite.rightEye
        val ey = top + sprite.eyeY
        dot.color = Pixels.INK
        val blink = (now / 100) % 40 == 0L
        when {
            fainted -> for (ex in floatArrayOf(ex1, ex2)) {
                c.drawRect(ex - 1f, ey - 1f, ex, ey, dot)
                c.drawRect(ex + 1f, ey - 1f, ex + 2f, ey, dot)
                c.drawRect(ex, ey, ex + 1f, ey + 1f, dot)
                c.drawRect(ex - 1f, ey + 1f, ex, ey + 2f, dot)
                c.drawRect(ex + 1f, ey + 1f, ex + 2f, ey + 2f, dot)
            }
            asleep || blink -> for (ex in floatArrayOf(ex1, ex2)) c.drawRect(ex - 1f, ey + 1f, ex + 2f, ey + 2f, dot)
            hurt < 0.4f -> {
                c.drawRect(ex1 - 1f, ey - 1f, ex1, ey, dot); c.drawRect(ex1, ey, ex1 + 1f, ey + 1f, dot); c.drawRect(ex1 - 1f, ey + 1f, ex1, ey + 2f, dot)
                c.drawRect(ex2 + 1f, ey - 1f, ex2 + 2f, ey, dot); c.drawRect(ex2, ey, ex2 + 1f, ey + 1f, dot); c.drawRect(ex2 + 1f, ey + 1f, ex2 + 2f, ey + 2f, dot)
            }
            joy < 1.5f -> for (ex in floatArrayOf(ex1, ex2)) {
                c.drawRect(ex - 1f, ey + 1f, ex, ey + 2f, dot)
                c.drawRect(ex, ey, ex + 1f, ey + 1f, dot)
                c.drawRect(ex + 1f, ey + 1f, ex + 2f, ey + 2f, dot)
            }
            sick -> for (ex in floatArrayOf(ex1, ex2)) c.drawRect(ex, ey + 0.5f, ex + 1f, ey + 2f, dot)
            else -> for (ex in floatArrayOf(ex1 + 1f, ex2 + 1f)) {
                c.drawRect(ex, ey - 1f, ex + 1f, ey + 1f, dot)
            }
        }
        val mx = left + sprite.mouthX + 1f
        val my = top + sprite.mouthY
        when {
            fainted || sick -> { c.drawRect(mx - 2f, my, mx - 1f, my + 1f, dot); c.drawRect(mx - 1f, my - 1f, mx, my, dot); c.drawRect(mx, my, mx + 1f, my + 1f, dot) }
            joy < 1.5f || since(Quest.Kind.EAT, now) < 1.6f -> {
                c.drawRect(mx - 1f, my, mx + 1f, my + 2f, dot)
                dot.color = 0xFFFF8C94.toInt()
                c.drawRect(mx - 1f, my + 1f, mx + 1f, my + 2f, dot)
            }
            asleep -> c.drawRect(mx - 1f, my, mx + 1f, my + 1f, dot)
            else -> { c.drawRect(mx - 1f, my, mx, my + 1f, dot); c.drawRect(mx, my + 1f, mx + 1f, my + 2f, dot); c.drawRect(mx + 1f, my, mx + 2f, my + 1f, dot) }
        }
        dot.color = 0xFFFFA0A0.toInt()
        c.drawRect(ex1 - 3f, ey + 2f, ex1 - 1f, ey + 3f, dot)
        c.drawRect(ex2 + 2f, ey + 2f, ex2 + 4f, ey + 3f, dot)
        if (q.needs.sickness == Sickness.SNIFFLES) {
            dot.color = 0xFFE57373.toInt()
            c.drawRect(mx - 0.5f, my - 2f, mx + 1f, my - 1f, dot)
        }

        // Its hat, of course.
        q.hat?.let { hat ->
            val hb = Pixels.hat(hat)
            val hx = x - hb.width / 2f
            val hy = if (fainted) top + sprite.headTop - hb.height + 4f else top + sprite.headTop - hb.height + 3f
            c.drawBitmap(hb, if (fainted) hx + 10f else hx, hy, null)
        }
        if (fainted) {
            for (i in 0 until 3) {
                val a = t * 4f + i * 2.1f
                dot.color = 0xFFFFE082.toInt()
                c.drawRect(x + cos(a) * 9f, top + 2f + sin(a) * 3f, x + cos(a) * 9f + 1f, top + 3f + sin(a) * 3f, dot)
            }
        }
        if (asleep) {
            dot.color = 0xFFFFFFFF.toInt()
            for (i in 0 until 3) {
                val p = (t * 0.5f + i / 3f) % 1f
                val zx = x + 8f + p * 10f
                val zy = top - 2f - p * 16f
                c.drawRect(zx, zy, zx + 3f, zy + 1f, dot)
                c.drawRect(zx + 1f, zy + 1f, zx + 2f, zy + 2f, dot)
                c.drawRect(zx, zy + 2f, zx + 3f, zy + 3f, dot)
            }
        }
        val eat = since(Quest.Kind.EAT, now)
        if (eat < 1.6f) {
            dot.color = 0xFFFFCC80.toInt()
            val size = 4f * (1f - eat / 1.6f)
            c.drawRect(mx + 3f, my - size / 2, mx + 3f + size, my + size / 2, dot)
        }
    }

    private val whiteFlash = Paint().apply { colorFilter = android.graphics.PorterDuffColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN) }
    private val cyanGhost = Paint().apply { colorFilter = android.graphics.PorterDuffColorFilter(0x9900FFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN) }
    private val magentaGhost = Paint().apply { colorFilter = android.graphics.PorterDuffColorFilter(0x99FF00FF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN) }

    private fun drawProp(c: Canvas, q: Quest, kind: Quest.Room, x: Float, t: Float, now: Long) {
        val floor = Pixels.FLOOR.toFloat()
        when (kind) {
            Quest.Room.FIGHT, Quest.Room.BOSS -> {
                val foe = q.foe
                val gone = since(Quest.Kind.BEFRIENDED, now)
                if (foe == null && gone > 1.2f) return
                val sprite = if (foe != null) Pixels.foe(foe) else lastFoe ?: return
                if (foe != null) {
                    lastFoe = sprite
                    lastFoeBoss = foe.boss
                    lastFoeHat = if (foe.boss) Hat.CROWN else if (foe.seed % 3 == 0) q.genreOf(q.setting).hat else null
                }
                val struck = min(since(Quest.Kind.TAMA_HIT, now), min(since(Quest.Kind.TAMA_CRIT, now), since(Quest.Kind.ALLY_HIT, now))) - 0.2f
                val lunge = since(Quest.Kind.FOE_HIT, now).let { if (it < 0.35f) 14f * sin(it / 0.35f * PI.toFloat()) else 0f } +
                    since(Quest.Kind.FOE_MISS, now).let { if (it < 0.35f) 10f * sin(it / 0.35f * PI.toFloat()) else 0f }
                val bob = abs(sin(t * 3f)) * 2f
                var px = x - sprite.width / 2f - lunge
                if (struck in 0f..0.25f) px += 3f * sin(struck * 60f)
                val py = floor - sprite.height + 1f - bob
                if (foe == null) {
                    // Befriended: it shrinks away into hearts.
                    val k = (1f - gone / 1.2f).coerceIn(0f, 1f)
                    dst.set(x - sprite.width * k / 2f, floor - sprite.height * k, x + sprite.width * k / 2f, floor)
                    c.drawBitmap(sprite, null, dst, null)
                    return
                }
                c.drawBitmap(sprite, px, py, if (struck in 0f..0.2f) whiteFlash else null)
                lastFoeHat?.let { hat ->
                    val hb = Pixels.hat(hat)
                    c.drawBitmap(hb, px + sprite.width / 2f - hb.width / 2f, py - hb.height + 3f, null)
                }
                // Its health, above it.
                val bw = if (foe.boss) 30f else 20f
                val by = py - (lastFoeHat?.let { Pixels.hat(it).height - 1f } ?: 0f) - 5f
                dot.color = Pixels.INK
                c.drawRect(x - bw / 2 - 1f, by - 1f, x + bw / 2 + 1f, by + 2f, dot)
                val f = (foe.hp.toFloat() / foe.maxHp).coerceIn(0f, 1f)
                dot.color = if (f > 0.5f) 0xFF66BB6A.toInt() else if (f > 0.25f) 0xFFFFCA28.toInt() else 0xFFEF5350.toInt()
                c.drawRect(x - bw / 2, by, x - bw / 2 + bw * f, by + 1f, dot)
            }
            Quest.Room.CHEST -> {
                val open = q.phase != Quest.Phase.ROOM
                dot.color = 0xFF6D4C41.toInt(); c.drawRect(x - 9f, floor - 11f, x + 9f, floor, dot)
                dot.color = 0xFF8D6E63.toInt(); c.drawRect(x - 8f, floor - 10f, x + 8f, floor - 1f, dot)
                dot.color = 0xFFFFD54F.toInt(); c.drawRect(x - 9f, floor - 7f, x + 9f, floor - 6f, dot); c.drawRect(x - 1f, floor - 8f, x + 1f, floor - 5f, dot)
                if (open) {
                    dot.color = 0xFF6D4C41.toInt(); c.drawRect(x - 9f, floor - 18f, x + 9f, floor - 15f, dot)
                    dot.color = 0x80FFF59D.toInt(); c.drawRect(x - 7f, floor - 15f, x + 7f, floor - 11f, dot)
                } else {
                    dot.color = 0xFF5D4037.toInt(); c.drawRect(x - 9f, floor - 15f, x + 9f, floor - 11f, dot)
                }
            }
            Quest.Room.NPC, Quest.Room.RIDDLE -> {
                val colors = q.genreOf(q.flavor).colors
                val sprite = Pixels.creature(propSeed, if (kind == Quest.Room.RIDDLE) 24 else 18, colors[abs(propSeed) % colors.size])
                val py = floor - sprite.height + 1f - abs(sin(t * 2f)) * 1f
                c.drawBitmap(sprite, x - sprite.width / 2f, py, null)
                val hb = Pixels.hat(q.genreOf(q.flavor).hat)
                c.drawBitmap(hb, x - hb.width / 2f, py - hb.height + 3f, null)
                if (kind == Quest.Room.RIDDLE) qmark(c, x + 14f, py - 12f)
                else speech(c, x - 10f, py - 6f, t)
            }
            Quest.Room.SHOP -> {
                dot.color = 0xFFE53935.toInt(); c.drawRect(x - 10f, floor - 34f, x + 10f, floor, dot)
                dot.color = 0xFFB71C1C.toInt(); c.drawRect(x - 10f, floor - 2f, x + 10f, floor, dot)
                dot.color = 0xFFB3E5FC.toInt(); c.drawRect(x - 7f, floor - 30f, x + 3f, floor - 12f, dot)
                for (row in 0 until 3) for (col in 0 until 2) {
                    dot.color = listOf(0xFFFFCC80, 0xFFA5D6A7, 0xFFF48FB1, 0xFFFFF59D)[(row + col) % 4].toInt()
                    c.drawRect(x - 6f + col * 4f, floor - 28f + row * 6f, x - 4f + col * 4f, floor - 25f + row * 6f, dot)
                }
                dot.color = 0xFFFFFFFF.toInt(); c.drawRect(x + 5f, floor - 26f, x + 8f, floor - 24f, dot)
                dot.color = if ((t * 2).toInt() % 2 == 0) 0xFF76FF03.toInt() else 0xFF33691E.toInt(); c.drawRect(x + 5f, floor - 20f, x + 8f, floor - 18f, dot)
                dot.color = Pixels.INK; c.drawRect(x - 7f, floor - 8f, x + 3f, floor - 5f, dot)
            }
            Quest.Room.SPRING -> {
                dot.color = 0xFF8D8A80.toInt(); c.drawOval(RectF(x - 22f, floor - 6f, x + 22f, floor + 2f), dot)
                dot.color = 0xFF4FC3F7.toInt(); c.drawOval(RectF(x - 19f, floor - 5f, x + 19f, floor + 1f), dot)
                dot.color = 0x99FFFFFF.toInt()
                for (i in 0 until 6) {
                    val p = (t * 0.4f + i / 6f) % 1f
                    c.drawRect(x - 15f + i * 6f + sin(p * 6f + i) * 2f, floor - 6f - p * 22f, x - 14f + i * 6f + sin(p * 6f + i) * 2f, floor - 4f - p * 22f, dot)
                }
            }
            Quest.Room.FORAGE -> {
                dot.color = 0xFF2E7D32.toInt()
                c.drawCircle(x - 7f, floor - 7f, 8f, dot); c.drawCircle(x + 6f, floor - 8f, 9f, dot)
                dot.color = 0xFF43A047.toInt(); c.drawCircle(x, floor - 13f, 8f, dot)
                dot.color = 0xFFE91E63.toInt()
                for ((bx, by) in listOf(-8f to -10f, 3f to -15f, 8f to -6f, -2f to -5f)) c.drawRect(x + bx, floor + by, x + bx + 2f, floor + by + 2f, dot)
            }
            Quest.Room.ODDITY -> {
                dot.color = 0xFF6D4C41.toInt(); c.drawRect(x - 1f, floor - 22f, x + 1f, floor, dot)
                dot.color = 0xFFD7B98E.toInt(); c.drawRect(x - 10f, floor - 30f, x + 10f, floor - 18f, dot)
                qmark(c, x - 2f, floor - 28f)
            }
        }
    }

    private var lastFoe: Bitmap? = null
    private var lastFoeBoss = false
    private var lastFoeHat: Hat? = null

    private fun qmark(c: Canvas, x: Float, y: Float) {
        dot.color = Pixels.INK
        c.drawRect(x, y, x + 4f, y + 1f, dot); c.drawRect(x + 3f, y + 1f, x + 4f, y + 4f, dot)
        c.drawRect(x + 1f, y + 4f, x + 3f, y + 5f, dot); c.drawRect(x + 1f, y + 5f, x + 2f, y + 7f, dot); c.drawRect(x + 1f, y + 8f, x + 2f, y + 9f, dot)
    }

    private fun speech(c: Canvas, x: Float, y: Float, t: Float) {
        if ((t * 0.7f).toInt() % 3 == 0) return
        dot.color = 0xFFFFFFFF.toInt()
        c.drawRect(x - 8f, y - 7f, x + 2f, y, dot)
        c.drawRect(x - 1f, y, x + 1f, y + 2f, dot)
        dot.color = Pixels.INK
        for (i in 0 until 3) c.drawRect(x - 6f + i * 3f, y - 4f, x - 5f + i * 3f, y - 3f, dot)
    }

    private fun camp(c: Canvas, t: Float, night: Boolean) {
        val x = 150f
        val floor = Pixels.FLOOR.toFloat()
        dot.color = 0xFF5D4037.toInt()
        c.drawRect(x - 7f, floor - 2f, x + 7f, floor, dot)
        for (i in 0 until 5) {
            val h = 5f + 4f * abs(sin(t * 9f + i * 1.7f))
            dot.color = if (i % 2 == 0) 0xFFFF7043.toInt() else 0xFFFFCA28.toInt()
            c.drawRect(x - 5f + i * 2f, floor - 2f - h, x - 3f + i * 2f, floor - 2f, dot)
        }
        dot.color = 0x30FFB74D
        c.drawCircle(x, floor - 6f, 18f + sin(t * 7f), dot)
        val tx = 205f
        dot.color = if (night) 0xFF5C6BC0.toInt() else 0xFF7986CB.toInt()
        val tent = android.graphics.Path().apply { moveTo(tx - 20f, floor); lineTo(tx, floor - 24f); lineTo(tx + 20f, floor); close() }
        c.drawPath(tent, dot)
        dot.color = 0xFF283593.toInt()
        val door = android.graphics.Path().apply { moveTo(tx - 5f, floor); lineTo(tx, floor - 12f); lineTo(tx + 5f, floor); close() }
        c.drawPath(door, dot)
    }

    private fun inn(c: Canvas) {
        val x = 200f
        val floor = Pixels.FLOOR.toFloat()
        dot.color = 0xFFD7B98E.toInt(); c.drawRect(x - 24f, floor - 30f, x + 24f, floor, dot)
        dot.color = 0xFFB5523B.toInt()
        c.drawPath(android.graphics.Path().apply { moveTo(x - 30f, floor - 30f); lineTo(x, floor - 48f); lineTo(x + 30f, floor - 30f); close() }, dot)
        dot.color = 0xFF6D4C41.toInt(); c.drawRect(x - 5f, floor - 16f, x + 5f, floor, dot)
        dot.color = 0xFFFFE082.toInt(); c.drawRect(x - 18f, floor - 22f, x - 10f, floor - 14f, dot); c.drawRect(x + 10f, floor - 22f, x + 18f, floor - 14f, dot)
        dot.color = 0xFFFFFFFF.toInt(); c.drawRect(x - 4f, floor - 42f, x + 4f, floor - 34f, dot)
        dot.color = 0xFFE53935.toInt(); c.drawRect(x - 1f, floor - 41f, x + 1f, floor - 35f, dot); c.drawRect(x - 3f, floor - 39f, x + 3f, floor - 37f, dot)
    }

    private fun disco(c: Canvas, look: Look, t: Float) {
        val beat = (t * 2f).toInt()
        val colors = intArrayOf(0xFFFF3EA5.toInt(), 0xFF2DE2E6.toInt(), 0xFFF1C40F.toInt(), 0xFF8A2BE2.toInt(), 0xFF39FF14.toInt())
        val off = (scroll % 16f)
        var x = -off
        var i = (scroll / 16f).toInt()
        while (x < Pixels.W) {
            for (row in 0 until 3) {
                dot.color = colors[(i * 3 + row * 7 + beat) % colors.size] and 0x00FFFFFF or 0x66000000
                c.drawRect(x, Pixels.GROUND + 2f + row * 8f, x + 15f, Pixels.GROUND + 9f + row * 8f, dot)
            }
            x += 16f
            i++
        }
    }

    private fun drift(c: Canvas, q: Quest, look: Look, t: Float) {
        val weather = q.weather
        val kind = when (weather) {
            Sky.RAIN, Sky.STORM -> Drift.RAIN
            Sky.SNOW -> null
            else -> look.drift
        }
        val snow = weather == Sky.SNOW
        for (i in 0 until motes) {
            var x = moteX[i]
            var y = moteY[i]
            val v = moteV[i]
            when {
                snow -> {
                    y += v * 0.35f; x += sin(t + i) * 0.2f
                    dot.color = 0xFFFFFFFF.toInt(); c.drawRect(x, y, x + 1f, y + 1f, dot)
                }
                kind == Drift.RAIN -> {
                    y += v * 3f; x -= v * 0.8f
                    dot.color = 0x99B3E5FC.toInt(); c.drawRect(x, y, x + 1f, y + 3f, dot)
                }
                kind == Drift.LEAVES || kind == Drift.PETALS -> if (i % 3 == 0) {
                    y += v * 0.25f; x += sin(t * 1.3f + i) * 0.4f - 0.15f
                    dot.color = if (kind == Drift.PETALS) 0xFFF8BBD0.toInt() else 0xFF9CCC65.toInt()
                    c.drawRect(x, y, x + 2f, y + 1f, dot)
                }
                kind == Drift.PAPER -> if (i % 5 == 0) {
                    y += v * 0.3f; x += sin(t + i) * 0.5f
                    dot.color = 0xFFFFFFFF.toInt(); c.drawRect(x, y, x + 3f, y + 2f, dot)
                    dot.color = 0xFF90A4AE.toInt(); c.drawRect(x + 0.5f, y + 1f, x + 2.5f, y + 1.5f, dot)
                }
                kind == Drift.STARS -> if (i % 2 == 0) {
                    dot.color = if (sin(t * 3f + i * 1.7f) > 0.6f) 0xFFFFFFFF.toInt() else 0x66FFFFFF
                    c.drawRect(x, y * 0.7f, x + 1f, y * 0.7f + 1f, dot)
                }
                kind == Drift.BUBBLES -> if (i % 2 == 0) {
                    y -= v * 0.4f; x += sin(t * 2f + i) * 0.3f
                    dot.color = 0x88E0F7FA.toInt(); c.drawRect(x, y, x + 2f, y + 2f, dot)
                }
                kind == Drift.FIREFLIES -> if (i % 3 == 0) {
                    x += sin(t * 0.7f + i) * 0.3f; y += cos(t * 0.9f + i) * 0.2f
                    dot.color = if (sin(t * 2f + i) > 0f) 0xFFFFF59D.toInt() else 0x44FFF59D
                    c.drawRect(x, y, x + 1f, y + 1f, dot)
                }
                kind == Drift.DUST -> if (i % 3 == 0) {
                    x -= v * 0.6f; y += sin(t + i) * 0.2f
                    dot.color = 0x88FFE0B2.toInt(); c.drawRect(x, y, x + 1f, y + 1f, dot)
                }
                kind == Drift.STEAM -> if (i % 4 == 0) {
                    y -= v * 0.2f; x += sin(t + i) * 0.3f
                    dot.color = 0x55FFFFFF; c.drawRect(x, y, x + 2f, y + 2f, dot)
                }
                kind == Drift.SPARKLES -> if (i % 2 == 0) {
                    dot.color = listOf(0xFFFF3EA5, 0xFF2DE2E6, 0xFFF1C40F)[i % 3].toInt()
                    if (sin(t * 5f + i * 2.3f) > 0.7f) c.drawRect(x, y, x + 1f, y + 1f, dot)
                }
                else -> {}
            }
            if (y > Pixels.GROUND + 8f) { y = -2f; x = random.nextFloat() * Pixels.W }
            if (y < -4f) { y = Pixels.GROUND.toFloat(); x = random.nextFloat() * Pixels.W }
            if (x < -4f) x += Pixels.W + 4f
            if (x > Pixels.W + 4f) x -= Pixels.W + 4f
            moteX[i] = x
            moteY[i] = y
        }
    }

    /** Bursts of squares for good news: loot, level-ups, a finished quest, a cheer. */
    private fun sparkles(c: Canvas, now: Long) {
        for (a in anims) {
            val s = (now - a.at) / 1000f
            if (s < 0f) continue
            val (count, life, cx, cy) = when (a.kind) {
                Quest.Kind.LEVEL_UP -> Quad(24, 1.6f, TAMA_X, Pixels.FLOOR - 10f)
                Quest.Kind.QUEST_DONE -> Quad(40, 2.2f, Pixels.W / 2f, 40f)
                Quest.Kind.LOOT, Quest.Kind.MIMIC -> Quad(12, 1f, TAMA_X + 20f, Pixels.FLOOR - 16f)
                Quest.Kind.BEFRIENDED -> Quad(8, 1.2f, FOE_X, Pixels.FLOOR - 14f)
                Quest.Kind.TAMA_CRIT -> Quad(10, 0.5f, FOE_X, Pixels.FLOOR - 12f)
                Quest.Kind.HEAL, Quest.Kind.BETTER -> Quad(8, 1.2f, TAMA_X, Pixels.FLOOR - 6f)
                else -> continue
            }
            if (s > life) continue
            for (i in 0 until count) {
                val ang = i * 2.4f + a.at % 7
                val speed = 18f + (i * 37 % 23)
                val x = cx + cos(ang) * speed * s
                val y = cy + sin(ang) * speed * s - (if (a.kind == Quest.Kind.HEAL || a.kind == Quest.Kind.BETTER) 20f * s else 0f) + 25f * s * s
                dot.color = when (a.kind) {
                    Quest.Kind.HEAL, Quest.Kind.BETTER -> 0xFFA5D6A7.toInt()
                    Quest.Kind.BEFRIENDED -> 0xFFFF80AB.toInt()
                    Quest.Kind.QUEST_DONE -> intArrayOf(0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF69F0AE.toInt(), 0xFF40C4FF.toInt(), 0xFFE040FB.toInt())[i % 5]
                    else -> if (i % 2 == 0) 0xFFFFE082.toInt() else 0xFFFFFFFF.toInt()
                }
                c.drawRect(x, y, x + 1.5f, y + 1.5f, dot)
            }
        }
    }

    private data class Quad(val count: Int, val life: Float, val x: Float, val y: Float)

    // ---- Words, at the glass's own resolution ----

    private fun type(size: Float, face: Typeface, color: Int, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT, shadow: Boolean = false) = text.apply {
        textSize = size
        typeface = face
        this.color = color
        letterSpacing = spacing
        textAlign = align
        if (shadow) setShadowLayer(6f, 0f, 2f, 0xB0000000.toInt()) else clearShadowLayer()
    }

    private fun drawOverlay(canvas: Canvas, q: Quest, now: Long) {
        val w = width.toFloat()
        val s = scale
        val left = 56f
        val right = w - 56f
        val genres = "${q.genreOf(q.flavor).name} × ${q.genreOf(q.setting).name}".uppercase()
        canvas.drawText(genres, left, 60f, type(13f, Typeface.MONOSPACE, 0xD9FFFFFF.toInt(), 0.14f, shadow = true))
        canvas.drawText(q.realm, left, 104f, type(40f, italic, 0xFFFFFFFF.toInt(), shadow = true))
        val title = TextUtils.ellipsize(q.title, type(20f, sans, 0xF0FFFFFF.toInt(), shadow = true), w * 0.58f, TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, left, 136f, text)

        canvas.drawText("CLOSE ×", right, 60f, type(13f, Typeface.MONOSPACE, 0xE6FFFFFF.toInt(), 0.14f, Paint.Align.RIGHT, shadow = true))
        closeZone.set(right - 140f, 20f, w, 80f)
        pill(canvas, "FEED", right, 82f, feedZone, q.needs.hungry && (now / 600) % 2 == 0L)
        if (q.needs.sickness != null) pill(canvas, "MEDICINE", feedZone.left - 14f, 82f, medicineZone, (now / 600) % 2 == 0L)
        else medicineZone.setEmpty()

        // The quest's rooms, done, here and to come; the last is the boss.
        val n = q.rooms.size
        if (n > 0) {
            val gap = 22f
            var x = right - (n - 1) * gap
            for (i in 0 until n) {
                val done = i < q.roomIndex || q.roomIndex >= n
                val here = i == q.roomIndex && q.phase != Quest.Phase.CAMP
                val boss = i == n - 1
                fill.color = if (done) 0xE6FFFFFF.toInt() else 0x59FFFFFF
                line.color = 0xE6FFFFFF.toInt()
                line.strokeWidth = 2f
                if (boss) {
                    box.set(x - 7f, 152f - 7f, x + 7f, 152f + 7f)
                    if (done) canvas.drawRect(box, fill) else canvas.drawRect(box, line)
                } else if (here) {
                    canvas.drawCircle(x, 152f, 8f, line)
                    canvas.drawCircle(x, 152f, 3.5f, fill.apply { color = 0xFFFFFFFF.toInt() })
                } else {
                    canvas.drawCircle(x, 152f, 5f, fill)
                }
                x += gap
            }
        }

        for (f in floaters) {
            val age = (now - f.at) / 1000f
            if (age < 0f || age > 1.8f) continue
            val a = (1f - age / 1.8f).coerceIn(0f, 1f)
            val paint = type(if (f.big) 46f else 30f, sansBold, f.color, align = Paint.Align.CENTER, shadow = true)
            paint.alpha = (255 * a).toInt()
            canvas.drawText(f.text, f.x * s, f.y * s - age * 60f, paint)
        }

        // A title card as a new quest begins.
        val card = since(Quest.Kind.NEW_QUEST, now)
        if (card < CARD_S) {
            val a = min(1f, min(card / 0.4f, (CARD_S - card) / 0.6f))
            fill.color = (0xD0 * a).toInt() shl 24 or 0x0A0812
            canvas.drawRect(0f, sceneH * 0.32f, w, sceneH * 0.72f, fill)
            val c = (255 * a).toInt()
            canvas.drawText("NEW QUEST", w / 2, sceneH * 0.42f, type(14f, Typeface.MONOSPACE, 0xFFFFFF or (c shl 24), 0.3f, Paint.Align.CENTER))
            canvas.drawText(q.realm, w / 2, sceneH * 0.54f, type(56f, italic, 0xFFFFFF or (c shl 24), align = Paint.Align.CENTER))
            val t2 = TextUtils.ellipsize(q.title, type(22f, sans, 0xFFFFFF or (c * 9 / 10 shl 24), align = Paint.Align.CENTER), w * 0.8f, TextUtils.TruncateAt.END)
            canvas.drawText(t2, 0, t2.length, w / 2, sceneH * 0.63f, text)
        }
        val done = since(Quest.Kind.QUEST_DONE, now)
        if (done < 2.4f) {
            val a = min(1f, min(done / 0.3f, (2.4f - done) / 0.5f))
            canvas.drawText("QUEST COMPLETE", w / 2, sceneH * 0.5f, type(52f, sansBold, 0xFFFFD54F.toInt(), 0.08f, Paint.Align.CENTER, shadow = true).apply { alpha = (255 * a).toInt() })
        }
    }

    private fun pill(canvas: Canvas, label: String, right: Float, top: Float, zone: RectF, lit: Boolean) {
        val paint = type(13f, Typeface.MONOSPACE, if (lit) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt(), 0.14f, Paint.Align.CENTER)
        val w = paint.measureText(label) + 40f
        zone.set(right - w, top, right, top + 40f)
        fill.color = if (lit) 0xFFFFE082.toInt() else 0x66000000
        canvas.drawRoundRect(zone, 20f, 20f, fill)
        line.color = if (lit) 0xFFFFE082.toInt() else 0xCCFFFFFF.toInt()
        line.strokeWidth = 2f
        canvas.drawRoundRect(zone, 20f, 20f, line)
        canvas.drawText(label, zone.centerX(), zone.centerY() + 5f, paint)
    }

    private fun panelKey(q: Quest) = buildString {
        append(q.logCount).append('/').append(q.hp).append('/').append(q.xp).append('/').append(q.level)
        append('/').append((q.needs.fullness * 40).toInt()).append('/').append(q.needs.sickness).append('/').append(q.gold)
        append('/').append(q.snacks.size).append('/').append(q.gear.size).append('/').append(q.phase).append('/').append(q.grooving)
        append('/').append(q.needs.wellFed > 0f).append('/').append(q.job)
    }

    private fun drawPanel(c: Canvas, q: Quest, look: Look, w: Float, h: Float) {
        c.drawColor(Pixels.mix(0xFF0E0D12.toInt(), look.near, 0.16f))
        line.color = 0x33FFFFFF
        line.strokeWidth = 1f
        c.drawLine(0f, 0f, w, 0f, line)

        val left = 56f
        val logRight = w * 0.62f
        val col = logRight + 48f
        val right = w - 56f
        c.drawText("TAMA'S QUEST · ${q.questsDone} DONE · ${q.befriended} BEFRIENDED · ${q.faints} NAPS ON THE FLOOR", left, 40f, type(12f, Typeface.MONOSPACE, 0x8CFFFFFF.toInt(), 0.14f))
        if (q.phase == Quest.Phase.ROOM && q.foe != null) {
            c.drawText("TAP THE SCENE TO CHEER", logRight, 40f, type(12f, Typeface.MONOSPACE, 0xCCFFE082.toInt(), 0.14f, Paint.Align.RIGHT))
        }

        // What happened lately, newest at the bottom, older lines fading upwards.
        val lines = q.lines
        var y = h - 26f
        val width = (logRight - left).toInt()
        var age = 0
        for (i in lines.indices.reversed()) {
            val s = lines[i]
            val headline = s.startsWith("LEVEL UP") || s.startsWith("QUEST COMPLETE") || s.startsWith("NEW QUEST")
            logText.typeface = if (headline) sansBold else sans
            logText.color = if (headline) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt()
            logText.alpha = (255 * max(0.28f, 1f - age * 0.16f)).toInt()
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, logText, width).setLineSpacing(0f, 1.12f)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
            val top = y - layout.height
            if (top < 58f) break
            c.save()
            c.translate(left, top)
            layout.draw(c)
            c.restore()
            if (age == 0) {
                fill.color = 0xFFFFE082.toInt()
                c.drawCircle(left - 18f, top + 13f, 4f, fill)
            }
            y = top - 8f
            age++
        }

        c.drawLine(logRight + 24f, 24f, logRight + 24f, h - 24f, line)

        c.drawText("TAMA · LEVEL ${q.level}", col, 40f, type(12f, Typeface.MONOSPACE, 0xB3FFFFFF.toInt(), 0.14f))
        val job = TextUtils.ellipsize(q.job, type(30f, italic, 0xFFFFFFFF.toInt()), right - col, TextUtils.TruncateAt.END)
        c.drawText(job, 0, job.length, col, 78f, text)

        bar(c, "HP", "${q.hp}/${q.maxHp()}", q.hp / q.maxHp().toFloat(), 0xFF66BB6A.toInt(), col, right, 108f)
        bar(c, "XP", "${q.xp}/${q.xpToNext()}", q.xp / q.xpToNext().toFloat(), 0xFF64B5F6.toInt(), col, right, 134f)
        bar(c, "TUMMY", "${(q.needs.fullness * 100).toInt()}%", q.needs.fullness, if (q.needs.hungry) 0xFFFF8A65.toInt() else 0xFFFFCC80.toInt(), col, right, 160f)

        val state = listOfNotNull(
            q.needs.sickness?.let { "${it.title.uppercase()}${if (q.needs.medicated) " · MEDICINE TAKEN" else " · TAP MEDICINE"}" },
            when {
                q.needs.sickness != null -> null
                q.needs.starving -> "STARVING · FORAGING"
                q.needs.hungry -> "HUNGRY · TAP FEED"
                q.needs.wellFed > 0f -> "WELL FED"
                else -> "ALL WELL"
            },
            if (q.grooving) "GROOVING" else null,
        ).joinToString(" · ")
        c.drawText(state, col, 190f, type(12f, Typeface.MONOSPACE, if (q.needs.sickness != null || q.needs.hungry) 0xFFFFAB91.toInt() else 0xFFA5D6A7.toInt(), 0.12f))

        var gy = 220f
        for ((slot, name) in listOf(Quest.Slot.HAT to "HAT", Quest.Slot.WIELD to "WIELD", Quest.Slot.CHARM to "CHARM")) {
            c.drawText(name, col, gy, type(11f, Typeface.MONOSPACE, 0x8CFFFFFF.toInt(), 0.14f))
            val label = q.gear[slot]?.label ?: "—"
            val e = TextUtils.ellipsize(label, type(17f, sans, 0xFFFFFFFF.toInt()), right - col - 70f, TextUtils.TruncateAt.END)
            c.drawText(e, 0, e.length, col + 70f, gy + 1f, text)
            gy += 26f
        }
        val pack = if (q.snacks.isEmpty()) "PACK EMPTY" else "PACK: ${q.snacks.joinToString(", ")}"
        val p = TextUtils.ellipsize("$pack · ${"%,d".format(q.gold)} G", type(12f, Typeface.MONOSPACE, 0xB3FFFFFF.toInt(), 0.1f), right - col, TextUtils.TruncateAt.END)
        c.drawText(p, 0, p.length, col, h - 22f, text)
    }

    private fun bar(c: Canvas, label: String, value: String, f: Float, color: Int, left: Float, right: Float, y: Float) {
        c.drawText(label, left, y + 4f, type(11f, Typeface.MONOSPACE, 0x8CFFFFFF.toInt(), 0.14f))
        c.drawText(value, right, y + 4f, type(12f, Typeface.MONOSPACE, 0xCCFFFFFF.toInt(), 0.06f, Paint.Align.RIGHT))
        val bl = left + 70f
        val br = right - 92f
        fill.color = 0x26FFFFFF
        box.set(bl, y - 3f, br, y + 3f)
        c.drawRoundRect(box, 3f, 3f, fill)
        fill.color = color
        box.right = bl + (br - bl) * f.coerceIn(0f, 1f)
        if (box.right > box.left + 1f) c.drawRoundRect(box, 3f, 3f, fill)
    }

    // ---- Touch ----

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        stayOpen()
        val q = quest ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (hypot(event.x - downX, event.y - downY) > 30f) return true
                val x = event.x
                val y = event.y
                when {
                    closeZone.contains(x, y) -> close()
                    feedZone.contains(x, y) -> feed(q)
                    medicineZone.contains(x, y) -> if (!q.medicine()) floaters.add(Floater("all well!", TAMA_X, Pixels.FLOOR - 26f, 0xFFA5D6A7.toInt(), SystemClock.uptimeMillis()))
                    y < sceneH -> {
                        if (q.cheer()) {
                            // The cheered blow lands now rather than at the next beat.
                            postDelayed({ if (isOpen) q.beat() }, 450)
                        } else {
                            floaters.add(Floater("♪", TAMA_X, Pixels.FLOOR - 26f, 0xFFFFFFFF.toInt(), SystemClock.uptimeMillis()))
                        }
                    }
                }
            }
        }
        return true
    }

    private fun feed(q: Quest) {
        val food = q.needs.nextFood(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), random)
        val now = SystemClock.uptimeMillis()
        when (q.feed(food)) {
            TamaNeeds.Fed.STUFFED -> floaters.add(Floater("stuffed!", TAMA_X, Pixels.FLOOR - 26f, 0xFFFFCC80.toInt(), now))
            TamaNeeds.Fed.TOO_POORLY -> floaters.add(Floater("ugh…", TAMA_X, Pixels.FLOOR - 26f, 0xFFC5E1A5.toInt(), now))
            TamaNeeds.Fed.LOVED -> floaters.add(Floater("♥ ♥", TAMA_X, Pixels.FLOOR - 30f, 0xFFFF80AB.toInt(), now, big = true))
            else -> {}
        }
        invalidate()
    }

    private companion object {
        const val TAMA_X = 92f
        const val FOE_X = 228f
        const val WALK_SPEED = 26f
        const val ENTER_MS = 900L
        const val FRAME_MS = 70L
        const val FADE_MS = 300L
        const val UNVEIL_MS = 650L
        const val CARD_S = 3.6f
        const val IDLE_CLOSE_MS = 30 * 60_000L
    }
}

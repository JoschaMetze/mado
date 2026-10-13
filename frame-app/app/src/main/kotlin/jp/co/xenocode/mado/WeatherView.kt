package jp.co.xenocode.mado

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.DecelerateInterpolator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The weather page, opened by tapping a place's weather on the dashboard: that place's sky as
 * the whole glass (its colour, cloud, rain, snow or stars), the sun or moon on its arc, the
 * next 24 hours as a curve and the week as a strip. Every place has a tab; a tab or a sideways
 * drag moves to another place, and the sky and the curve flow into the new one.
 *
 * A tap anywhere else closes it, and it closes by itself when left alone. It redraws every
 * frame while it is open and not at all while it is closed.
 *
 * The frame runs this code in an interpreter, where every drawing call is dear: a page drawn
 * word by word manages thirty frames a second. So each place's words and pictures are drawn
 * once into a bitmap (its sheet), and a frame is the sky, the sheet, and the few things that
 * move: the curve, the sun, the pill behind the tabs.
 */
class WeatherView(context: Context) : View(context) {
    /** One place, with everything that is worked out once rather than on every frame. */
    private class Page(val weather: Weather, pretendSky: Sky?, pretendNight: Boolean?) {
        val outlook = weather.outlook
        val sky = pretendSky ?: weather.sky
        val light = pretendNight?.not() ?: weather.isDay
        val pretending = pretendSky != null || pretendNight != null
        val code = if (pretendSky == null && outlook != null) outlook.code else when (sky) {
            Sky.CLEAR -> 0
            Sky.CLOUD -> 3
            Sky.FOG -> 45
            Sky.RAIN -> 63
            Sky.SNOW -> 73
            Sky.STORM -> 95
        }
        private val zone = if (weather.timezone.isEmpty()) TimeZone.getDefault() else TimeZone.getTimeZone(weather.timezone)
        private val clock = SimpleDateFormat("EEEE d MMMM · HH:mm", Locale.US).apply { timeZone = zone }
        private val calendar = Calendar.getInstance(zone)
        private val date = Date()
        private var minute = -1L

        /** Everything about this place that stays put, drawn once. Null until it is wanted. */
        var sheet: Bitmap? = null
        val tab = RectF()
        val temp = "${weather.tempC}°"
        val detail = "High ${weather.highC}° · Low ${weather.lowC}°" +
            if (outlook == null) "" else " · Feels ${outlook.feelsC}° · Wind ${outlook.windKmh} km/h · Humidity ${outlook.humidity}%"

        /** How high the curve is at each hour, in pixels down the glass. */
        val hours = min(HOURS, outlook?.temps?.size ?: 0)
        val curve = FloatArray(HOURS) { CURVE_BOTTOM }

        init {
            if (outlook != null) {
                var low = Float.MAX_VALUE
                var high = -Float.MAX_VALUE
                for (i in 0 until hours) {
                    low = min(low, outlook.temps[i])
                    high = max(high, outlook.temps[i])
                }
                // A day that barely changes should look calm, not like a mountain range.
                if (high - low < 5f) {
                    val middle = (high + low) / 2f
                    low = middle - 2.5f
                    high = middle + 2.5f
                }
                // A forecast that stops short runs on level, so two places' curves always line up.
                for (i in 0 until HOURS) {
                    curve[i] = CURVE_BOTTOM - (outlook.temps[min(i, hours - 1)] - low) / (high - low) * (CURVE_BOTTOM - CURVE_TOP)
                }
            }
        }

        val cloud = when {
            sky == Sky.CLEAR -> if (code == 1) 0.2f else 0f
            sky == Sky.CLOUD -> if (code == 2) 0.45f else 0.85f
            sky == Sky.FOG -> 0.6f
            sky == Sky.SNOW -> 0.8f
            sky == Sky.RAIN -> 0.9f
            else -> 1f
        }
        val rain = when {
            sky == Sky.STORM -> 1f
            sky != Sky.RAIN -> 0f
            code in 51..57 -> 0.4f
            code == 65 || code == 67 || code == 82 -> 1f
            else -> 0.7f
        }
        val snow = if (sky == Sky.SNOW) 1f else 0f
        val fog = if (sky == Sky.FOG) 1f else 0f
        val storm = if (sky == Sky.STORM) 1f else 0f
        val stars = if (light) 0f else 1f - cloud

        // What changes with the clock; `tick` keeps these up to date.
        var stamp = ""
        var top = 0
        var bottom = 0
        /** The sun is up, by the page's own clock: which body is on the arc. */
        var sunUp = light
        var along = 0.5f
        var hasArc = false
        var leftLabel = ""
        var leftTime = ""
        var rightLabel = ""
        var rightTime = ""
        var middle = ""

        /** True when what is on the sheet has changed, which is at sunrise and at sunset. */
        fun tick(now: Long): Boolean {
            if (now / 60_000 == minute) return false
            val first = minute < 0
            minute = now / 60_000
            date.time = now
            stamp = clock.format(date).uppercase()
            calendar.timeInMillis = now
            val at = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            val rise = outlook?.sunriseMin ?: -1
            val set = outlook?.sunsetMin ?: -1
            hasArc = rise >= 0 && set > rise
            val was = sunUp
            var golden = 0f
            if (hasArc) {
                val up = Daylight.up(at, rise, set)
                // Asked to pretend the other half of the day, there is no honest place on the arc.
                sunUp = if (pretending) light else up
                along = if (sunUp == up) Daylight.along(at, rise, set) else 0.35f
                val next = outlook?.nextSunriseMin?.takeIf { it >= 0 } ?: rise
                val dawn = if (at >= set) next else rise
                leftLabel = if (sunUp) "SUNRISE" else "SUNSET"
                leftTime = hhmm(if (sunUp) rise else set)
                rightLabel = if (sunUp) "SUNSET" else "SUNRISE"
                rightTime = hhmm(if (sunUp) set else dawn)
                middle = if (sunUp) "Sunset in ${span(Daylight.until(at, set))}" else "Sunrise in ${span(Daylight.until(at, dawn))}"
                if (!pretending) golden = (1f - min(abs(at - rise), abs(at - set)) / 50f).coerceIn(0f, 1f) * (1f - cloud * 0.7f)
            }
            val i = sky.ordinal * 4 + if (light) 0 else 2
            top = mix(SKIES[i], 0xFF45508F.toInt(), golden * 0.35f)
            bottom = mix(SKIES[i + 1], 0xFFD9825A.toInt(), golden * 0.7f)
            return !first && was != sunUp
        }

        private fun hhmm(minutes: Int) = String.format(Locale.US, "%02d:%02d", minutes / 60 % 24, minutes % 60)

        private fun span(minutes: Int) = if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    private var pages: List<Page> = emptyList()
    private var pretendSky: Sky? = null
    private var pretendNight: Boolean? = null
    /** Sheets nobody is using, kept so a refreshed forecast does not allocate a new one. */
    private val spare = ArrayList<Bitmap>()
    /** The tabs' words and the close button, at full strength. */
    private var chrome: Bitmap? = null
    private val warm = Runnable { warmOne() }

    /**
     * The place on show, the one it is turning into (the same one when it is not), and how far
     * along that is. `aim` is where `turn` is heading: 1 to arrive, 0 to go back.
     */
    private var showing = 0
    private var coming = 0
    private var turn = 0f
    private var aim = 0f
    private var velocity = 0f
    private var dragging = false
    private var downX = 0f

    private var openedAt = 0L
    private var last = 0L
    private var seconds = 0f
    private var reveal = 1f
    private var closing = false
    private var unveil: Animator? = null
    private val autoClose = Runnable { close() }

    /**
     * Called with true once the page hides everything beneath it, and with false before it stops
     * doing so. What is underneath would otherwise be redrawn under every one of this page's frames.
     */
    var onCover: ((Boolean) -> Unit)? = null

    val isOpen get() = visibility == VISIBLE && !closing
    /** Where the words are, so Tama can keep off them. */
    val textZones = ArrayList<RectF>()

    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val italic = Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(10f, 0f, 1f, 0x38000000) }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val skyPaint = Paint()
    private var skyTop = 0
    private var skyBottom = 0
    private val skyColors = IntArray(3)
    private val sprite = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sheetPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // Rain and stars are drawn without smoothing: the frame's renderer builds every smoothed
    // line and dot by hand on each frame, and a sky full of them costs it half its time.
    private val specks = Paint()
    private val drops = Paint().apply { strokeWidth = 2f }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        // Warm where the day peaks, cool where it dips.
        shader = LinearGradient(0f, CURVE_TOP, 0f, CURVE_BOTTOM, WARM, COOL, Shader.TileMode.CLAMP)
    }
    private val under = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, CURVE_TOP, 0f, BARS_BASE, 0x50FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }
    private val heat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val dashes = DashPathEffect(floatArrayOf(3f, 9f), 0f)

    private val curvePath = Path()
    private val underPath = Path()
    private var curveA: Page? = null
    private var curveB: Page? = null
    private var curveT = -1f
    private val ys = FloatArray(HOURS)
    private val rect = RectF()
    private val src = Rect()
    private val arcPath = Path()
    // The little pictures as unit shapes. They are drawn through `shape`, at their real size:
    // the frame's renderer rasterises a path before it scales it, so a scaled unit path is a smudge.
    private val cloudPath = Path().apply {
        addCircle(-0.2f, 0.02f, 0.17f, Path.Direction.CW)
        addCircle(0.03f, -0.08f, 0.24f, Path.Direction.CW)
        addCircle(0.26f, 0.04f, 0.15f, Path.Direction.CW)
        addRoundRect(RectF(-0.4f, 0f, 0.43f, 0.2f), 0.1f, 0.1f, Path.Direction.CW)
    }
    private val moonPath = Path().apply {
        addCircle(0f, 0f, 1f, Path.Direction.CW)
        op(Path().apply { addCircle(0.52f, -0.3f, 0.86f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
    }
    private val boltPath = Path().apply {
        moveTo(0.06f, 0f)
        lineTo(-0.1f, 0.2f)
        lineTo(0f, 0.2f)
        lineTo(-0.06f, 0.38f)
        lineTo(0.12f, 0.14f)
        lineTo(0.02f, 0.14f)
        close()
    }
    private val shapes = HashMap<Int, Path>()
    private val scale = Matrix()
    /** The sun's rays about the origin, long and short by turns. */
    private val rays = FloatArray(12 * 4).also {
        for (i in 0 until 12) {
            val angle = Math.PI * i / 6
            val reach = if (i % 2 == 0) 30f else 26f
            it[i * 4] = 22f * cos(angle).toFloat()
            it[i * 4 + 1] = 22f * sin(angle).toFloat()
            it[i * 4 + 2] = reach * cos(angle).toFloat()
            it[i * 4 + 3] = reach * sin(angle).toFloat()
        }
    }

    // The weather in the air. Positions are in pixels; each kind keeps its own arrays.
    private val random = Random(7)
    private val puff: Bitmap = puff()
    private val halo: Bitmap = halo()
    private val ring: Bitmap = ring()
    /**
     * Snow falls as flurries, a few flakes to a small bitmap, because a call per flake is too
     * many for the interpreter and a screen-wide layer of them is too much for the GPU to fill.
     */
    private val flurries = Array(4) { flurry(it) }
    private val flurryX = FloatArray(FLURRIES)
    private val flurryY = FloatArray(FLURRIES)
    private val cloudX = FloatArray(CLOUDS)
    private val cloudY = FloatArray(CLOUDS)
    private val cloudW = FloatArray(CLOUDS)
    private val cloudV = FloatArray(CLOUDS)
    private val cloudA = FloatArray(CLOUDS)
    private val dropV = FloatArray(DROPS)
    /** Each drop as a line: its foot, then its head. */
    private val dropLines = FloatArray(DROPS * 4)
    /** Stars in a few groups that twinkle out of step: x, y pairs. */
    private val stars = Array(STAR_GROUPS) { FloatArray(STARS) }
    private var flash = 0f
    private var nextFlash = 3f

    /** Multiplies the alpha of everything drawn through `type` and `white`. */
    private var veil = 1f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            downX = e.x
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!dragging && pages.size > 1 && abs(e2.x - downX) > 24f) {
                // Whatever turn was under way is finished at once; the finger starts a new one.
                if (turn > 0.5f) showing = coming
                coming = showing
                turn = 0f
                velocity = 0f
                dragging = true
                downX = e2.x
            }
            if (dragging) {
                val pull = (downX - e2.x) / DRAG_W
                val toward = showing + if (pull > 0f) 1 else -1
                if (toward in pages.indices) {
                    coming = toward
                    turn = min(abs(pull), 1f)
                } else {
                    coming = showing
                    turn = 0f
                }
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!dragging) return false
            dragging = false
            if (coming == showing) return true
            // Toward the place being dragged to is a push; the other way is a change of mind.
            val push = if (coming > showing) -vx else vx
            aim = if (push > 500f) 1f else if (push < -500f) 0f else Math.round(turn).toFloat()
            velocity = (push / DRAG_W).coerceIn(-6f, 6f)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val tab = pages.indexOfFirst { it.tab.contains(e.x, e.y) }
            if (tab >= 0) turnTo(tab) else close()
            return true
        }
    })

    init {
        // Invisible rather than gone, so it has its size before it is first opened.
        visibility = INVISIBLE
    }

    private fun turnTo(place: Int) {
        if (coming != showing) {
            if (place == coming) {
                aim = 1f
                return
            }
            if (place == showing) {
                aim = 0f
                return
            }
            // A third place asked for mid-turn: settle on the nearer of the two, then set off.
            if (turn > 0.5f) showing = coming
        }
        coming = place
        turn = 0f
        aim = 1f
        velocity = 0f
    }

    /** Here first, then the other places, in the hub's order. */
    fun setPlaces(places: List<Weather>) {
        val keep = pages.getOrNull(if (turn > 0.5f) coming else showing)?.weather?.place
        val old = pages
        // A place whose forecast has not changed keeps its page, and with it its sheet.
        pages = places.map { place -> old.firstOrNull { it.weather === place } ?: Page(place, pretendSky, pretendNight) }
        for (page in old) if (page !in pages) retire(page)
        curveA = null
        showing = pages.indexOfFirst { it.weather.place == keep }.coerceAtLeast(0)
        coming = showing
        turn = 0f
        velocity = 0f
        dragging = false
        removeCallbacks(warm)
        if (pages.isEmpty()) {
            close()
            return
        }
        var x = 56f
        for (page in pages) {
            val width = 22f + type(18f, bold).measureText(fit(page.weather.place, text, 220f)) + 10f + type(18f, sans).measureText(page.temp) + 22f
            page.tab.set(x, 34f, x + width, 78f)
            x += width + 6f
        }
        chrome?.let(::drawChrome)
        postDelayed(warm, WARM_MS)
    }

    /** Debug builds: look at a sky that is not outside. Null for both goes back to the real one. */
    fun pretend(sky: Sky?, night: Boolean?) {
        pretendSky = sky
        pretendNight = night
        val places = pages.map { it.weather }
        pages.forEach(::retire)
        pages = emptyList()
        setPlaces(places)
    }

    /** Opens on `place` (or here), growing out of the point that was touched. */
    fun open(place: Weather?, x: Float, y: Float) {
        if (pages.isEmpty() || width == 0) return
        showing = pages.indexOfFirst { it.weather === place }.takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.weather.place == place?.place }.coerceAtLeast(0)
        coming = showing
        turn = 0f
        velocity = 0f
        dragging = false
        closing = false
        // Its sheet is normally ready and waiting; if not, the wait is before anything moves.
        sheet(pages[showing])
        openedAt = SystemClock.uptimeMillis()
        last = 0L
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        stopUnveiling()
        if (isAttachedToWindow) {
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

    private fun stopUnveiling() {
        val running = unveil
        unveil = null
        running?.cancel()
    }

    fun close() {
        removeCallbacks(autoClose)
        if (!isOpen) return
        closing = true
        stopUnveiling()
        onCover?.invoke(false)
        animate().cancel()
        animate().alpha(0f).setDuration(FADE_MS).withLayer().withEndAction {
            visibility = INVISIBLE
            closing = false
        }
    }

    private fun stayOpen() {
        removeCallbacks(autoClose)
        postDelayed(autoClose, IDLE_CLOSE_MS)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        for (page in pages) page.sheet = null
        spare.clear()
        chrome = Bitmap.createBitmap(w, CHROME_H, Bitmap.Config.ARGB_8888).also(::drawChrome)
        arcPath.rewind()
        val cx = w - ARC_FROM_RIGHT
        arcPath.addArc(RectF(cx - ARC_RX, ARC_Y - ARC_RY, cx + ARC_RX, ARC_Y + ARC_RY), 180f, 180f)
        for (i in 0 until CLOUDS) {
            // The last few are the wide low bands that only fog brings out.
            val band = i >= CLOUDS - FOG_BANDS
            cloudW[i] = if (band) 1100f + random.nextFloat() * 500f else 380f + random.nextFloat() * 420f
            cloudX[i] = random.nextFloat() * (w + cloudW[i]) - cloudW[i]
            cloudY[i] = if (band) 330f + random.nextFloat() * 260f else -40f + random.nextFloat() * 300f
            cloudV[i] = (if (band) 4f else 7f) + random.nextFloat() * 12f
            cloudA[i] = 0.35f + random.nextFloat() * 0.45f
        }
        for (i in 0 until DROPS) {
            dropLines[i * 4] = random.nextFloat() * (w + 200f)
            dropLines[i * 4 + 1] = random.nextFloat() * h
            dropV[i] = 620f + random.nextFloat() * 520f
        }
        for (i in 0 until FLURRIES) {
            flurryX[i] = random.nextFloat() * w
            flurryY[i] = random.nextFloat() * h
        }
        for (group in stars) for (i in group.indices step 2) {
            group[i] = random.nextFloat() * w
            group[i + 1] = random.nextFloat() * h * 0.62f
        }
        textZones.clear()
        textZones.add(RectF(40f, 26f, w - 40f, 88f))
        textZones.add(RectF(40f, 120f, HERO_W, 444f))
        // The sunrise and sunset times, under the horizon: the line itself is left as a ledge.
        textZones.add(RectF(w - ARC_FROM_RIGHT - ARC_RX - 50f, ARC_Y + 4f, w - 40f, 444f))
        textZones.add(RectF(40f, 444f, w - 40f, h - 30f))
    }

    /** A soft white cloud, drawn once and stretched to every size. */
    private fun puff(): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val soft = Paint(Paint.ANTI_ALIAS_FLAG)
        val blobs = floatArrayOf(70f, 76f, 50f, 128f, 58f, 58f, 186f, 74f, 52f, 104f, 86f, 40f, 156f, 88f, 40f)
        for (i in blobs.indices step 3) {
            soft.shader = RadialGradient(
                blobs[i], blobs[i + 1], blobs[i + 2], intArrayOf(0x90FFFFFF.toInt(), 0x48FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(blobs[i], blobs[i + 1], blobs[i + 2], soft)
        }
        return bitmap
    }

    /** The glow round the sun and the moon. */
    private fun halo(): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val soft = Paint(Paint.ANTI_ALIAS_FLAG)
        soft.shader = RadialGradient(64f, 64f, 64f, intArrayOf(WHITE, 0x55FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        Canvas(bitmap).drawCircle(64f, 64f, 64f, soft)
        return bitmap
    }

    /** The ripple that spreads from "now" on the curve. */
    private fun ring(): Bitmap {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = WHITE
        }
        Canvas(bitmap).drawCircle(32f, 32f, 29f, line)
        return bitmap
    }

    /** Later ones have bigger flakes, for snow that is nearer. */
    private fun flurry(kind: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(FLURRY, FLURRY, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val flake = Paint(Paint.ANTI_ALIAS_FLAG)
        val scatter = Random(11L + kind)
        for (i in 0 until 4) {
            val r = 1.2f + kind * 0.5f + scatter.nextFloat() * 1.4f
            flake.color = DashboardView.withAlpha(WHITE, 0.5f + scatter.nextFloat() * 0.5f)
            canvas.drawCircle(r + scatter.nextFloat() * (FLURRY - 2 * r), r + scatter.nextFloat() * (FLURRY - 2 * r), r, flake)
        }
        return bitmap
    }

    private fun type(size: Float, face: Typeface, alpha: Float = 1f, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT, color: Int = WHITE) =
        text.apply {
            textSize = size
            typeface = face
            this.color = DashboardView.withAlpha(color, (alpha * veil).coerceIn(0f, 1f))
            letterSpacing = spacing
            textAlign = align
        }

    private fun mono(alpha: Float, size: Float, align: Paint.Align = Paint.Align.LEFT, color: Int = WHITE) =
        type(size, Typeface.MONOSPACE, alpha, 0.14f, align, color)

    private fun fit(s: String, paint: TextPaint, width: Float) = TextUtils.ellipsize(s, paint, width, TextUtils.TruncateAt.END).toString()

    private fun white(alpha: Float) = DashboardView.withAlpha(WHITE, (alpha * veil).coerceIn(0f, 1f))

    /** `unit` at `size`, kept so that each size is only rasterised once. */
    private fun shape(unit: Path, kind: Int, size: Float) = shapes.getOrPut(kind * 100_000 + (size * 10f).toInt()) {
        Path().also {
            scale.setScale(size, size)
            unit.transform(scale, it)
        }
    }

    private fun draw(canvas: Canvas, unit: Path, kind: Int, cx: Float, cy: Float, size: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawPath(shape(unit, kind, size), ink)
        canvas.restore()
    }

    /** How far in a part of the page has come, for parts that arrive one after another. */
    private fun appear(delay: Float) = smooth((reveal - delay) / 0.42f)

    // --- sheets --------------------------------------------------------------------------

    /** The page's sheet, drawn now if it has none. */
    private fun sheet(page: Page): Bitmap {
        page.sheet?.let { return it }
        val bitmap = spare.removeLastOrNull() ?: borrowed() ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0)
        page.tick(System.currentTimeMillis())
        val canvas = Canvas(bitmap)
        veil = 1f
        drawHero(canvas, page)
        drawArc(canvas, page)
        drawHours(canvas, page)
        drawWeek(canvas, page)
        page.sheet = bitmap
        return bitmap
    }

    /** Past a handful of places, one that is not on show gives its sheet up. */
    private fun borrowed(): Bitmap? {
        if (pages.count { it.sheet != null } < MAX_SHEETS) return null
        val other = pages.firstOrNull { it.sheet != null && it !== pages[showing] && it !== pages[coming] } ?: return null
        return other.sheet.also { other.sheet = null }
    }

    private fun retire(page: Page) {
        val sheet = page.sheet ?: return
        page.sheet = null
        if (sheet.width == width && sheet.height == height) spare.add(sheet)
    }

    /** Draws one sheet that is missing, while nothing is moving, and comes back for the next. */
    private fun warmOne() {
        if (width == 0) return
        val busy = isOpen && (reveal < 1f || coming != showing || dragging)
        if (!busy) {
            if (pages.count { it.sheet != null } >= MAX_SHEETS) return
            sheet(pages.firstOrNull { it.sheet == null } ?: return)
        }
        postDelayed(warm, WARM_MS)
    }

    private fun drawChrome(bitmap: Bitmap) {
        bitmap.eraseColor(0)
        val canvas = Canvas(bitmap)
        veil = 1f
        for (page in pages) {
            val x = page.tab.left + 22f
            val name = fit(page.weather.place, type(18f, bold), 220f)
            canvas.drawText(name, x, 63f, text)
            canvas.drawText(page.temp, x + text.measureText(name) + 10f, 63f, type(18f, sans, 0.78f))
        }
        canvas.drawText("CLOSE ×", bitmap.width - 64f, 62f, mono(0.8f, 13f, Paint.Align.RIGHT))
    }

    /** A piece of a bitmap, where it was drawn or moved from there, at some strength. */
    private fun piece(canvas: Canvas, bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float, alpha: Float, dx: Float = 0f, dy: Float = 0f) {
        if (alpha <= 0.004f) return
        src.set(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        rect.set(src.left + dx, src.top + dy, src.right + dx, src.bottom + dy)
        sheetPaint.alpha = (255 * min(alpha, 1f)).toInt()
        canvas.drawBitmap(bitmap, src, rect, sheetPaint)
    }

    // --- a frame -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (pages.isEmpty() || width == 0) return
        val frame = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else min((frame - last) / 1000f, 0.05f)
        last = frame
        seconds += dt
        reveal = ((frame - openedAt) / REVEAL_MS).coerceIn(0f, 1f)
        settle(dt)

        val a = pages[showing]
        val b = pages[coming]
        val t = if (a === b) 0f else turn.coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        for (i in pages.indices) if (pages[i].tick(now)) retire(pages[i])

        drawSky(canvas, a, b, t, dt)
        drawTabs(canvas, a, b, t)
        // The place being left slides away ahead of the one arriving.
        val way = if (coming > showing) 1f else -1f
        drawPage(canvas, a, 1f - smooth(t * 1.7f), -way * t * SLIDE)
        if (t > 0f) drawPage(canvas, b, smooth((t - 0.41f) * 1.7f), way * (1f - t) * SLIDE)
        drawCurve(canvas, a, b, t)
        veil = 1f
        postInvalidateOnAnimation()
    }

    /** A spring toward the place asked for, a little under-damped so it arrives with some life. */
    private fun settle(dt: Float) {
        if (dragging || coming == showing) return
        velocity += (-170f * (turn - aim) - 23f * velocity) * dt
        turn += velocity * dt
        if (abs(turn - aim) < 0.001f && abs(velocity) < 0.01f) {
            if (aim == 1f) showing = coming else coming = showing
            turn = 0f
            velocity = 0f
            removeCallbacks(warm)
            postDelayed(warm, WARM_MS)
        }
    }

    /** One place's sheet in its parts, and over it the few things on the page that the clock moves. */
    private fun drawPage(canvas: Canvas, page: Page, alpha: Float, dx: Float) {
        if (alpha <= 0.004f) return
        val sheet = sheet(page)
        val w = width.toFloat()
        val h = height.toFloat()

        val hero = appear(0.06f)
        piece(canvas, sheet, 0f, CHROME_H.toFloat(), HERO_W, MID_Y, alpha * hero, dx, (1f - hero) * 26f)
        veil = alpha * hero
        if (veil > 0.004f) canvas.drawText(page.stamp, 64f + dx, 150f + (1f - hero) * 26f, mono(0.75f, 15f))

        val arc = appear(0.16f)
        piece(canvas, sheet, HERO_W, CHROME_H.toFloat(), w, MID_Y, alpha * arc)
        veil = alpha * arc
        if (veil > 0.004f) drawBody(canvas, page)

        val hours = appear(0.26f)
        piece(canvas, sheet, 0f, MID_Y, w, WEEK_Y, alpha * hours, 0f, (1f - hours) * 26f)
        if (reveal >= 1f) {
            piece(canvas, sheet, 0f, WEEK_Y, w, h, alpha)
        } else {
            // The days of the week come up one after another.
            val column = (w - 128f) / 7f
            for (i in 0 until 7) {
                val day = appear(0.38f + i * 0.03f)
                piece(
                    canvas, sheet, if (i == 0) 0f else 56f + i * column, WEEK_Y, if (i == 6) w else 56f + (i + 1) * column, h,
                    alpha * day, 0f, (1f - day) * 22f,
                )
            }
        }
    }

    // --- the sky -------------------------------------------------------------------------

    private fun drawSky(canvas: Canvas, a: Page, b: Page, t: Float, dt: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = mix(a.top, b.top, t)
        val bottom = mix(a.bottom, b.bottom, t)
        if (top != skyTop || bottom != skyBottom || skyPaint.shader == null) {
            skyTop = top
            skyBottom = bottom
            // Darker toward the foot of the page, where the small print is.
            skyColors[0] = top
            skyColors[1] = mix(top, bottom, 0.5f)
            skyColors[2] = mix(bottom, Color.BLACK, 0.34f)
            skyPaint.shader = LinearGradient(0f, 0f, 0f, h, skyColors, null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, skyPaint)

        val light = lerp(if (a.light) 1f else 0f, if (b.light) 1f else 0f, t)
        val starlight = lerp(a.stars, b.stars, t)
        if (starlight > 0.01f) {
            for (i in 0 until STAR_GROUPS) {
                val twinkle = 0.4f + 0.6f * (0.5f + 0.5f * sin(seconds * (0.7f + i * 0.23f) + i * 1.9f))
                specks.color = DashboardView.withAlpha(WHITE, starlight * twinkle * 0.9f)
                specks.strokeWidth = 2f + i % 2
                canvas.drawPoints(stars[i], specks)
            }
        }

        val cloud = lerp(a.cloud, b.cloud, t)
        val fog = lerp(a.fog, b.fog, t)
        for (i in 0 until CLOUDS) {
            cloudX[i] += cloudV[i] * dt
            if (cloudX[i] > w) cloudX[i] = -cloudW[i]
            val band = i >= CLOUDS - FOG_BANDS
            val strength = if (band) fog * 0.55f else cloud * cloudA[i] * lerp(0.22f, 0.8f, light)
            if (strength < 0.01f) continue
            sprite.alpha = (255 * strength).toInt()
            rect.set(cloudX[i], cloudY[i], cloudX[i] + cloudW[i], cloudY[i] + cloudW[i] * if (band) 0.26f else 0.46f)
            canvas.drawBitmap(puff, null, rect, sprite)
        }

        val rain = lerp(a.rain, b.rain, t)
        if (rain > 0.01f) {
            val count = (DROPS * min(1f, 0.25f + rain * 0.75f)).toInt()
            for (i in 0 until count) {
                val at = i * 4
                val fall = dropV[i] * dt
                var x = dropLines[at] - fall * 0.16f
                var y = dropLines[at + 1] + fall
                if (y > h + 40f) {
                    y = -10f
                    x = random.nextFloat() * (w + 200f)
                }
                val length = dropV[i] * 0.032f
                dropLines[at] = x
                dropLines[at + 1] = y
                dropLines[at + 2] = x + length * 0.16f
                dropLines[at + 3] = y - length
            }
            drops.color = DashboardView.withAlpha(0xFFD6E8FF.toInt(), 0.42f * min(1f, rain * 2.5f))
            canvas.drawLines(dropLines, 0, count * 4, drops)
        }

        val snow = lerp(a.snow, b.snow, t)
        if (snow > 0.01f) {
            sprite.alpha = (255 * snow * 0.9f).toInt()
            for (i in 0 until FLURRIES) {
                val kind = i % flurries.size
                flurryY[i] += (34f + kind * 20f) * dt
                // Neighbours sway against each other, so the fall does not look like a sheet.
                flurryX[i] += sin(seconds * 0.8f + i) * 16f * dt
                if (flurryY[i] > h) {
                    flurryY[i] = -FLURRY.toFloat()
                    flurryX[i] = random.nextFloat() * w
                }
                canvas.drawBitmap(flurries[kind], flurryX[i], flurryY[i], sprite)
            }
        }

        val storm = lerp(a.storm, b.storm, t)
        if (storm > 0.01f) {
            nextFlash -= dt
            if (nextFlash < 0f) {
                flash = 1f
                nextFlash = 5f + random.nextFloat() * 9f
            }
            flash *= 1f - min(1f, dt * 5f)
            // Two beats, like the real thing: bright, a dip, then a fainter afterglow.
            val lit = flash * (0.6f + 0.4f * sin(flash * 19f)) * 0.26f * storm
            if (lit > 0.004f) canvas.drawColor(DashboardView.withAlpha(0xFFE8EEFF.toInt(), lit))
        }
    }

    // --- tabs ----------------------------------------------------------------------------

    private fun drawTabs(canvas: Canvas, a: Page, b: Page, t: Float) {
        val show = appear(0f)
        veil = show
        // The leading edge sets off first and the trailing edge catches up, so the pill stretches
        // as it goes. Past the end of the turn the spring carries it a little further and back.
        val lead = 1f - (1f - t) * (1f - t)
        val trail = t * t
        val right = b.tab.left > a.tab.left
        rect.set(
            lerp(a.tab.left, b.tab.left, if (right) trail else lead), a.tab.top,
            lerp(a.tab.right, b.tab.right, if (right) lead else trail), a.tab.bottom,
        )
        if (a !== b) rect.offset((turn - t) * (b.tab.left - a.tab.left), 0f)
        ink.color = white(0.2f)
        canvas.drawRoundRect(rect, 22f, 22f, ink)
        stroke.pathEffect = null
        stroke.strokeWidth = 1f
        stroke.color = white(0.32f)
        canvas.drawRoundRect(rect, 22f, 22f, stroke)

        val chrome = chrome ?: return
        for (i in pages.indices) {
            val page = pages[i]
            val near = if (page === a) 1f - t else if (page === b) t else 0f
            piece(canvas, chrome, page.tab.left, 0f, page.tab.right, CHROME_H.toFloat(), show * (0.6f + 0.4f * near))
        }
        piece(canvas, chrome, width - 200f, 0f, width.toFloat(), CHROME_H.toFloat(), show)
    }

    // --- one place's words, on its sheet ---------------------------------------------------

    private fun drawHero(canvas: Canvas, page: Page) {
        canvas.drawText(page.temp, 54f, 330f, type(200f, thin, spacing = -0.03f))
        glyph(canvas, page.code, page.light, 54f + text.measureText(page.temp) + 74f, 258f, 104f)
        canvas.drawText(fit(page.weather.sentence, type(40f, italic), 660f), 64f, 392f, text)
        canvas.drawText(fit(page.detail, type(19f, sans, 0.82f), 660f), 64f, 430f, text)
    }

    // --- the sun or the moon on its way across ---------------------------------------------

    /** The sky's road, on the sheet: the horizon, the whole arc dotted, and when it starts and ends. */
    private fun drawArc(canvas: Canvas, page: Page) {
        if (!page.hasArc) return
        val cx = width - ARC_FROM_RIGHT
        stroke.strokeWidth = 1f
        stroke.pathEffect = null
        stroke.color = white(0.34f)
        canvas.drawLine(cx - ARC_RX - 30f, ARC_Y, cx + ARC_RX + 30f, ARC_Y, stroke)
        stroke.strokeWidth = 1.6f
        stroke.pathEffect = dashes
        stroke.color = white(0.42f)
        canvas.drawPath(arcPath, stroke)
        stroke.pathEffect = null
        canvas.drawText(page.leftLabel, cx - ARC_RX, ARC_Y + 24f, mono(0.6f, 12f, Paint.Align.CENTER))
        canvas.drawText(page.leftTime, cx - ARC_RX, ARC_Y + 48f, type(20f, sans, align = Paint.Align.CENTER))
        canvas.drawText(page.rightLabel, cx + ARC_RX, ARC_Y + 24f, mono(0.6f, 12f, Paint.Align.CENTER))
        canvas.drawText(page.rightTime, cx + ARC_RX, ARC_Y + 48f, type(20f, sans, align = Paint.Align.CENTER))
    }

    /** The sun or the moon itself, the part of the arc it has covered, and how long it has left. */
    private fun drawBody(canvas: Canvas, page: Page) {
        if (!page.hasArc) return
        val cx = width - ARC_FROM_RIGHT
        // The body climbs to its place as the page opens.
        val along = page.along * smooth((reveal - 0.16f) / 0.7f)
        val x = cx - ARC_RX * cos(Math.PI * along).toFloat()
        val y = ARC_Y - ARC_RY * sin(Math.PI * along).toFloat()

        stroke.pathEffect = null
        stroke.strokeWidth = 2.2f
        stroke.color = white(0.9f)
        canvas.save()
        canvas.clipRect(cx - ARC_RX - 6f, ARC_Y - ARC_RY - 6f, x, ARC_Y)
        canvas.drawPath(arcPath, stroke)
        canvas.restore()

        val reach = (if (page.sunUp) 92f else 60f) + 7f * sin(seconds * 0.9f)
        sprite.alpha = (255 * veil * (if (page.sunUp) 0.85f else 0.5f) * (1f - page.cloud * 0.55f)).toInt()
        rect.set(x - reach, y - reach, x + reach, y + reach)
        canvas.drawBitmap(halo, null, rect, sprite)
        if (page.sunUp) {
            ink.color = DashboardView.withAlpha(SUN, veil)
            canvas.drawCircle(x, y, 15f, ink)
            stroke.strokeWidth = 2.4f
            stroke.color = DashboardView.withAlpha(SUN, veil * 0.9f)
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(seconds * 7f)
            canvas.drawLines(rays, stroke)
            canvas.restore()
        } else {
            ink.color = DashboardView.withAlpha(MOON, veil)
            draw(canvas, moonPath, MOON_SHAPE, x, y, 15f)
        }
        canvas.drawText(page.middle, cx, ARC_Y + 40f, type(20f, italic, 0.88f, align = Paint.Align.CENTER))
    }

    // --- the next 24 hours -----------------------------------------------------------------

    /** On the sheet: everything about the hours but the curve itself. */
    private fun drawHours(canvas: Canvas, page: Page) {
        val outlook = page.outlook ?: return
        val left = CURVE_INSET
        val step = (width - 2 * CURVE_INSET) / (HOURS - 1)
        canvas.drawText("THE NEXT 24 HOURS", 64f, 466f, mono(0.6f, 13f))
        // Chance of rain or snow, as soft columns standing behind the curve.
        for (i in 0 until page.hours) {
            val chance = outlook.chance[i] / 100f
            if (chance < 0.05f) continue
            ink.color = DashboardView.withAlpha(WET, 0.12f + 0.4f * chance)
            rect.set(left + i * step - 9f, BARS_BASE - 58f * chance, left + i * step + 9f, BARS_BASE)
            canvas.drawRoundRect(rect, 5f, 5f, ink)
        }
        for (i in 0 until page.hours step 3) {
            val x = left + i * step
            canvas.drawText("${outlook.temps[i].roundToInt()}°", x + 3f, page.curve[i] - 15f, type(17f, bold, align = Paint.Align.CENTER))
            glyph(canvas, outlook.codes[i], outlook.light[i], x, 610f, 30f)
            val hour = if (i == 0) "NOW" else String.format(Locale.US, "%02d", (outlook.hour + i) % 24)
            canvas.drawText(hour, x, 644f, mono(0.62f, 12f, Paint.Align.CENTER))
        }
    }

    /** The curve, which is one place's, or on its way from one place's shape to another's. */
    private fun drawCurve(canvas: Canvas, a: Page, b: Page, t: Float) {
        if (a.outlook == null && b.outlook == null) return
        val show = appear(0.26f)
        if (show <= 0f) return
        val left = CURVE_INSET
        val step = (width - 2 * CURVE_INSET) / (HOURS - 1)
        if (curveA !== a || curveB !== b || curveT != t) {
            curveA = a
            curveB = b
            curveT = t
            for (i in 0 until HOURS) ys[i] = lerp(a.curve[i], b.curve[i], t)
            curvePath.rewind()
            curvePath.moveTo(left, ys[0])
            for (i in 0 until HOURS - 1) {
                val before = ys[max(i - 1, 0)]
                val after = ys[min(i + 2, HOURS - 1)]
                curvePath.cubicTo(
                    left + (i + 1f / 3f) * step, ys[i] + (ys[i + 1] - before) / 6f,
                    left + (i + 2f / 3f) * step, ys[i + 1] - (after - ys[i]) / 6f,
                    left + (i + 1) * step, ys[i + 1],
                )
            }
            underPath.set(curvePath)
            underPath.lineTo(left + (HOURS - 1) * step, BARS_BASE)
            underPath.lineTo(left, BARS_BASE)
            underPath.close()
        }

        veil = show
        canvas.save()
        canvas.translate(0f, (1f - show) * 26f)
        // The curve is drawn in from the left as the page opens.
        if (reveal < 1f) canvas.clipRect(0f, CURVE_TOP - 40f, left - 12f + (width - 2 * left + 24f) * smooth((reveal - 0.3f) / 0.6f), BARS_BASE + 2f)
        under.alpha = (255 * show).toInt()
        canvas.drawPath(underPath, under)
        curve.alpha = (255 * show).toInt()
        canvas.drawPath(curvePath, curve)

        val pulse = seconds * 0.55f % 1f
        val wide = 6f + 16f * pulse
        sprite.alpha = (255 * show * 0.7f * (1f - pulse)).toInt()
        rect.set(left - wide, ys[0] - wide, left + wide, ys[0] + wide)
        canvas.drawBitmap(ring, null, rect, sprite)
        ink.color = white(1f)
        canvas.drawCircle(left, ys[0], 5.5f, ink)
        canvas.restore()
    }

    // --- the week ----------------------------------------------------------------------------

    private fun drawWeek(canvas: Canvas, page: Page) {
        val days = page.outlook?.days ?: return
        if (days.isEmpty()) return
        val low = days.minOf { it.lowC }.toFloat()
        val high = max(days.maxOf { it.highC }.toFloat(), low + 1f)
        val column = (width - 128f) / 7f
        val track = column - 34f
        heat.shader = LinearGradient(0f, 0f, track, 0f, COOL, WARM, Shader.TileMode.CLAMP)

        stroke.pathEffect = null
        stroke.strokeWidth = 1f
        stroke.color = white(0.22f)
        canvas.drawLine(64f, 668f, width - 64f, 668f, stroke)
        days.forEachIndexed { i, day ->
            val x = 64f + i * column
            glyph(canvas, day.code, true, x + 20f, 714f, 38f)
            val name = day.name.uppercase()
            canvas.drawText(name, x + 52f, 702f, mono(0.66f, 13f))
            if (day.chance >= 30) canvas.drawText("${day.chance}%", x + 52f + text.measureText(name) + 8f, 702f, mono(0.95f, 12f, color = WET))
            val highs = "${day.highC}°"
            canvas.drawText(highs, x + 52f, 730f, type(22f, bold))
            canvas.drawText("${day.lowC}°", x + 52f + text.measureText(highs) + 8f, 730f, type(22f, sans, 0.6f))

            // Each day's range on the week's own scale, so a warm spell or a cold snap shows as a drift.
            stroke.strokeWidth = 5f
            stroke.color = white(0.16f)
            canvas.drawLine(x + 4f, 756f, x + 4f + track, 756f, stroke)
            val from = track * (day.lowC - low) / (high - low)
            canvas.save()
            canvas.translate(x + 4f, 756f)
            canvas.drawLine(from, 0f, max(from + 1f, track * (day.highC - low) / (high - low)), 0f, heat)
            canvas.restore()
        }
    }

    // --- little pictures of the weather ------------------------------------------------------

    /** `size` is roughly the width of the picture; it is centred on (cx, cy). */
    private fun glyph(canvas: Canvas, code: Int, light: Boolean, cx: Float, cy: Float, size: Float) {
        val sky = Forecast.sky(code)
        stroke.pathEffect = null
        when {
            code == 0 -> orb(canvas, light, cx, cy, size * 0.3f)
            sky == Sky.CLEAR || code == 2 -> {
                orb(canvas, light, cx - size * 0.17f, cy - size * 0.13f, size * 0.2f)
                cloud(canvas, cx + size * 0.07f, cy + size * 0.1f, size * 0.76f)
            }
            sky == Sky.CLOUD -> cloud(canvas, cx, cy + size * 0.05f, size * 0.95f)
            sky == Sky.FOG -> {
                stroke.strokeWidth = size * 0.08f
                stroke.color = white(0.9f)
                for (i in -1..1) {
                    val half = size * (0.4f - 0.08f * abs(i))
                    canvas.drawLine(cx - half, cy + i * size * 0.2f, cx + half, cy + i * size * 0.2f, stroke)
                }
            }
            else -> {
                cloud(canvas, cx, cy - size * 0.1f, size * 0.9f)
                val under = cy + size * 0.17f
                when (sky) {
                    Sky.SNOW -> {
                        ink.color = WHITE
                        for (i in -1..1) canvas.drawCircle(cx + i * size * 0.2f, under + size * (0.07f + 0.05f * abs(i)), size * 0.045f, ink)
                    }
                    Sky.STORM -> {
                        ink.color = SUN
                        draw(canvas, boltPath, BOLT_SHAPE, cx, under - size * 0.06f, size * 0.9f)
                    }
                    else -> {
                        stroke.strokeWidth = size * 0.06f
                        stroke.color = WET
                        for (i in -1..1) {
                            val x = cx + i * size * 0.2f
                            canvas.drawLine(x + size * 0.03f, under, x - size * 0.03f, under + size * 0.15f, stroke)
                        }
                    }
                }
            }
        }
    }

    /** The sun by day, the moon by night. */
    private fun orb(canvas: Canvas, light: Boolean, cx: Float, cy: Float, r: Float) {
        if (light) {
            ink.color = SUN
            canvas.drawCircle(cx, cy, r * 0.6f, ink)
            stroke.strokeWidth = r * 0.17f
            stroke.color = SUN
            canvas.save()
            for (i in 0 until 8) {
                canvas.drawLine(cx, cy - r * 0.86f, cx, cy - r * 1.12f, stroke)
                canvas.rotate(45f, cx, cy)
            }
            canvas.restore()
        } else {
            ink.color = MOON
            draw(canvas, moonPath, MOON_SHAPE, cx, cy, r * 0.85f)
        }
    }

    private fun cloud(canvas: Canvas, cx: Float, cy: Float, width: Float) {
        ink.color = WHITE
        draw(canvas, cloudPath, CLOUD_SHAPE, cx, cy, width)
    }

    // Everything on the page is this view's, so nothing reaches the dashboard underneath.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isOpen) return true
        stayOpen()
        gestures.onTouchEvent(event)
        val action = event.actionMasked
        // A drag let go without a flick settles on whichever place is nearer.
        if (dragging && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            dragging = false
            aim = Math.round(turn).toFloat()
        }
        return true
    }

    private companion object {
        const val CLOUD_SHAPE = 0
        const val MOON_SHAPE = 1
        const val BOLT_SHAPE = 2
        const val HOURS = 25
        const val CLOUDS = 9
        const val FOG_BANDS = 3
        const val DROPS = 120
        const val FLURRIES = 30
        const val FLURRY = 72
        /** Numbers in each group's array: two for every star. */
        const val STARS = 28
        const val STAR_GROUPS = 5
        /** Sheets are four megabytes each; past this many places they are shared out. */
        const val MAX_SHEETS = 4
        const val WARM_MS = 400L

        // The parts of a sheet, which arrive and leave separately.
        const val CHROME_H = 96
        const val HERO_W = 740f
        const val MID_Y = 446f
        const val WEEK_Y = 656f

        const val CURVE_INSET = 96f
        const val CURVE_TOP = 512f
        const val CURVE_BOTTOM = 566f
        const val BARS_BASE = 586f
        const val ARC_FROM_RIGHT = 292f
        const val ARC_RX = 216f
        const val ARC_RY = 196f
        const val ARC_Y = 372f
        /** How far a place's words travel as they leave or arrive. */
        const val SLIDE = 150f
        /** A drag this long turns to the next place. */
        const val DRAG_W = 560f

        const val REVEAL_MS = 1500f
        const val UNVEIL_MS = 560L
        const val FADE_MS = 280L
        const val IDLE_CLOSE_MS = 120_000L

        const val WHITE = 0xFFFFFFFF.toInt()
        val SUN = 0xFFFFDF9A.toInt()
        val MOON = 0xFFF2F0E4.toInt()
        val WET = 0xFFA9D6FF.toInt()
        val WARM = 0xFFFFCB7A.toInt()
        val COOL = 0xFFB5DEFF.toInt()

        /** Top and bottom of the sky by day, then by night, for each `Sky` in order. Dark enough for white words. */
        val SKIES = intArrayOf(
            0xFF16508F.toInt(), 0xFF4C92C6.toInt(), 0xFF060A1C.toInt(), 0xFF1C2553.toInt(), // clear
            0xFF3F5266.toInt(), 0xFF738496.toInt(), 0xFF10141C.toInt(), 0xFF2B3342.toInt(), // cloud
            0xFF525C68.toInt(), 0xFF868E96.toInt(), 0xFF14171B.toInt(), 0xFF373C43.toInt(), // fog
            0xFF27384A.toInt(), 0xFF506676.toInt(), 0xFF0A0F17.toInt(), 0xFF222E3B.toInt(), // rain
            0xFF4E6278.toInt(), 0xFF8C9EB0.toInt(), 0xFF131A27.toInt(), 0xFF3A475B.toInt(), // snow
            0xFF191B2D.toInt(), 0xFF403D59.toInt(), 0xFF07070E.toInt(), 0xFF262439.toInt(), // storm
        )

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        fun smooth(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            if (t <= 0f) return a
            if (t >= 1f) return b
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
            )
        }
    }
}

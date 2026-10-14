package jp.co.xenocode.mado

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import jp.co.xenocode.mado.TamaFace.Brows
import jp.co.xenocode.mado.TamaFace.Eyes
import jp.co.xenocode.mado.TamaFace.Mouth
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tama, the companion: a small soft creature that lives on the far side of the glass, above
 * every scene. It clings wherever it likes, slides when the frame is tilted, startles at a
 * knock and knocks back. Left alone it amuses itself; [TamaDrives] decides with what. It only
 * claims touches that land on it.
 */
class TamaView(context: Context) : View(context), SensorEventListener {
    /** Off by default: the frame makes no sound unless the owner turns this on in the rail. */
    var sound = false
    /** Whether it is night as the frame keeps it; then the only thing to do is sleep. */
    var night: () -> Boolean = { false }
    /** Two knocks close together: a signal to the frame itself, whether or not Tama is about. */
    var onKnockTwice: (() -> Unit)? = null
    private var lastRap = 0L
    private var lastPair = 0L

    /** Where the scene underneath has words. Tama passes over them but does not settle there. */
    var keepOut: () -> List<RectF> = { emptyList() }

    private var x = -1f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var lean = 0f
    // Squash and stretch on one spring: positive is tall and thin.
    private var stretch = 0f
    private var stretchV = 0f

    private var held = false
    private var fingerX = 0f
    private var fingerY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragged = false

    private var targetX = 0f
    private var targetY = 0f
    private var wandering = false
    private var nextWanderAt = 0L

    private var lookX = 0f
    private var lookY = 0f
    private var glanceX = 0f
    private var glanceY = 0f
    private var nextGlanceAt = 0L
    private var watchX = 0f
    private var watchY = 0f
    private var watchUntil = 0L
    private var blinkAt = 0L
    private var wide = 1f

    private val pokes = ArrayDeque<Long>()
    private var sulkUntil = 0L
    private var knockBackAt = 0L
    private var knockStart = 0L

    // Gravity as the glass sees it (x right, y down), in m/s².
    private var gravX = 0f
    private var gravY = 0f
    // How the frame usually sits. Tama clings to that; only a change from it makes it slip.
    private var restX = Float.NaN
    private var restY = 0f
    private val smooth = FloatArray(3)
    private var haveSmooth = false
    private var lastSensor = 0L
    private var lastKnock = 0L
    private var rotation = Surface.ROTATION_0

    // Its pastime: breathe a patch of fog onto the glass and draw in it with a paw.
    private enum class Fog { NONE, GOING, BREATHING, DRAWING, ADMIRING }
    private var fogPhase = Fog.NONE
    private var phaseAt = 0L
    private var fog: Bitmap? = null
    private var fogCanvas: Canvas? = null
    private val fogRect = RectF()
    private var fogAlpha = 0f
    private var fogFadeAt = 0L
    private var fogFadeFrom = 0f
    private var doodle = DOODLES[0]
    private var lastDoodle = -1
    private var strokeI = 0
    private var pointI = 0
    private var penWait = 0f
    private var penBudget = 0f
    private var pawX = 0f
    private var pawY = 0f
    private var pawOut = 0f
    private val fogPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wipe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 9f
        color = 0xEB000000.toInt()
    }

    /** Music is playing somewhere: headphones on, and it bops. */
    var music = false
        set(value) {
            field = value
            invalidate()
        }

    /** Where the left edge of the alert on the glass is at a given height. */
    var alertEdge: (Float) -> Float = { width.toFloat() }
    private var alertLevel: String? = null
    private var alertAt = 0L
    private var alertY = 0f

    private var sky: Sky? = null
    private var tempC = 20
    private var nextFlinchAt = 0L
    private val leaf = Path()

    /** Told whenever what Tama is up to changes, in a word or two, for the hub's state. */
    var onDoing: ((String) -> Unit)? = null
    private var doing = ""

    // Asked for by an agent: a short gesture, a mood that lasts a while, or keeping out of sight.
    private var gesture: String? = null
    private var gestureAt = 0L
    private var hops = 0
    private var worryUntil = 0L
    private var happyUntil = 0L
    private var nextHappyHopAt = 0L
    private var hiding = false
    private var rising = false
    private var hideUntil = 0L
    private var lovesUntil = 0L

    // The hub has gone quiet: it listens for it on a tin-can telephone.
    private var linkDownAt = 0L
    private var tautUntil = 0L
    private var phone = false
    private val string = Path()

    private val face = TamaFace(R)
    private var flinchUntil = 0L
    private var annoyedUntil = 0L
    private var dizzyUntil = 0L
    private var joyUntil = 0L
    private var pinUntil = 0L

    private val memory = TamaMemory()
    private val prefs = context.getSharedPreferences("tama", Context.MODE_PRIVATE)
    private var rest = 0f
    private var savedAt = 0L

    private val drives = TamaDrives()
    /** What it is busy with, other than fog, which keeps its own phases above. */
    private var hobby: Hobby? = null
    /** Still on its way to where the hobby happens. */
    private var going = false
    private var hobbyAt = 0L
    private var hobbyFor = 0L
    private var lastHobby: Hobby? = null
    private var nextHobbyAt = 0L
    private var sadUntil = 0L

    // Pressing its face to the glass to look out.
    private var press = 0f
    private var printX = 0f
    private var printY = 0f
    private var printAt = 0L

    // A dust mote to chase.
    private var moteX = 0f
    private var moteY = 0f
    private var moteVX = 0f
    private var moteVY = 0f
    private var moteShown = false
    private var moteTired = 0L
    private var caughtAt = 0L
    private var sneezed = false

    // A stack of pebbles. It stays standing after Tama has moved on, until something knocks it over.
    private var pebbles = 0
    private var pebbleGoal = 0
    private var pebblesFalling = false
    private var stackUntil = 0L
    private var stackX = 0f
    private var stackY = 0f
    private var stackSide = 1
    private var carry = 0f
    private var carryWait = 0f
    private var wobble = 0f
    private val pebX = FloatArray(MAX_PEBBLES)
    private val pebY = FloatArray(MAX_PEBBLES)
    private val pebVX = FloatArray(MAX_PEBBLES)
    private val pebVY = FloatArray(MAX_PEBBLES)
    private val pebTurn = FloatArray(MAX_PEBBLES)
    private val pebSpin = FloatArray(MAX_PEBBLES)
    private val stackRect = RectF()

    private var lastFrame = 0L

    /** Tama's body and adventures, shared with the quest page: hunger, sickness, its hat, its level. */
    var quest: Quest? = null
    /** While the quest page is open Tama is in there, not on the glass. */
    var away = false
        set(value) {
            if (field && !value) stretchV = 6f
            field = value
            invalidate()
        }
    /** Poked, rather than cared for: the quest hears about it. */
    var onPoked: (() -> Unit)? = null
    // A meal from this side of the glass: it drops into the bowl and is eaten, or turned down.
    private var meal: Food? = null
    private var mealAt = 0L
    private var mealFed: TamaNeeds.Fed? = null
    private var medicineAt = 0L
    private var nextTwitchAt = 0L
    private var nextBubbleAt = 0L
    private var floatText: String? = null
    private var floatAt = 0L
    // A creature from the quest that has wandered out onto the glass.
    private var visitor: android.graphics.Bitmap? = null
    private var visitorName = ""
    private var visitorX = 0f
    private var visitorY = 0f
    private var visitorAt = 0L
    private var scuffleAt = 0L
    private var visitorDone: ((String) -> Unit)? = null
    private val pixels = Paint()
    private val pixelBox = RectF()
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 30f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        setShadowLayer(6f, 0f, 2f, 0xB0000000.toInt())
    }
    private var tap: AudioTrack? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = INK
    }
    private val oval = RectF()
    private val tuft = Path()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        @Suppress("DEPRECATION")
        rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    /** Pick up where it left off: its urges, its place on the glass, and what it has learned. */
    private fun recall() {
        drives.energy = prefs.getFloat("energy", drives.energy)
        drives.boredom = prefs.getFloat("boredom", drives.boredom)
        drives.lonely = prefs.getFloat("lonely", drives.lonely)
        drives.curiosity = prefs.getFloat("curiosity", drives.curiosity)
        lastHobby = Hobby.values().firstOrNull { it.name == prefs.getString("last_hobby", null) }
        memory.restore(prefs.getString("spots", null), prefs.getString("artists", null))
        if (prefs.contains("x")) {
            x = prefs.getFloat("x", x)
            y = prefs.getFloat("y", y)
        }
        // Time passed while the frame was off: it is rested, and has missed you. The clock
        // reads 1970 for a while after boot, so only believe a sensible gap.
        val away = (System.currentTimeMillis() - prefs.getLong("at", 0L)) / 3_600_000f
        if (prefs.contains("at") && away > 0.5f && away < 24 * 365) drives.away(away)
    }

    /** Write down what should outlast a restart. Cheap enough to do every few minutes. */
    fun remember() {
        if (x < 0f) return
        prefs.edit()
            .putFloat("energy", drives.energy).putFloat("boredom", drives.boredom)
            .putFloat("lonely", drives.lonely).putFloat("curiosity", drives.curiosity)
            .putString("last_hobby", lastHobby?.name)
            .putString("spots", memory.spotsText()).putString("artists", memory.artistsText())
            .putFloat("x", x).putFloat("y", y.coerceAtMost(height - R))
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    override fun onDetachedFromWindow() {
        remember()
        (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        tap?.release()
        tap = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (x < 0f) {
            x = w - 150f
            y = h - 130f
            recall()
            x = x.coerceIn(R, w - R)
            y = y.coerceIn(R, h - R)
        }
    }

    /** Every touch on the glass passes by here, so Tama can follow a finger it does not own. */
    fun watch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) drives.company()
        watchX = event.x
        watchY = event.y
        watchUntil = SystemClock.uptimeMillis() + WATCH_MS
    }

    /** Drop whatever it is doing and take up this hobby, if there is room for it. */
    fun perform(what: Hobby) {
        val now = SystemClock.uptimeMillis()
        interrupt(now)
        wandering = false
        begin(what, now)
        invalidate()
    }

    /** The weather here, or null when it is not known. Tama dresses for it. */
    fun setWeather(weather: Weather?) {
        sky = weather?.sky
        tempC = weather?.tempC ?: 20
        invalidate()
    }

    fun trackChanged(artist: String) {
        val favourite = memory.heard(artist)
        if (!asleep()) {
            stretchV = if (favourite) 8f else 5f
            // One of its favourites: it bops harder and glows for the first while.
            if (favourite) lovesUntil = SystemClock.uptimeMillis() + 30_000
        }
        invalidate()
    }

    /** Whether the hub can be reached. Only call this when a hub address is set at all. */
    fun hubLink(up: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (up) {
            // The string pulls tight: someone is there again.
            if (phone) {
                tautUntil = now + 1_800
                stretchV = 6f
            }
            linkDownAt = 0L
        } else if (linkDownAt == 0L) {
            linkDownAt = now
        }
        invalidate()
    }

    /**
     * An agent asks something of Tama: a hobby by name, a gesture (wave, nod, celebrate), a
     * mood for a while (worried, happy), to hide, or calm to drop all of that. False when it
     * does not know the word. `ttlMs` is how long a mood or hiding lasts; 0 for the usual.
     */
    fun act(action: String, ttlMs: Long): Boolean {
        val now = SystemClock.uptimeMillis()
        val hobby = Hobby.values().firstOrNull { it.name.equals(action, ignoreCase = true) }
        when {
            hobby != null -> perform(hobby)
            action in GESTURES -> {
                interrupt(now)
                wandering = false
                gesture = action
                gestureAt = now
                hops = 0
            }
            action == "worried" -> {
                worryUntil = now + if (ttlMs > 0) ttlMs else MOOD_MS
                happyUntil = 0L
            }
            action == "happy" -> {
                happyUntil = now + if (ttlMs > 0) ttlMs else MOOD_MS
                worryUntil = 0L
                stretchV = 6f
                joyUntil = now + 2_500
            }
            action == "hide" -> {
                interrupt(now)
                wandering = false
                hiding = true
                hideUntil = now + if (ttlMs > 0) ttlMs else HIDE_MS
            }
            action == "feed" -> return feed()
            action == "medicine" -> return medicine()
            action == "calm" -> {
                interrupt(now)
                worryUntil = 0L
                happyUntil = 0L
            }
            else -> return false
        }
        invalidate()
        return true
    }

    /**
     * An alert has come onto the glass. A whisper gets a look; for a nudge it goes over and
     * prods it; an urgent one shoves it aside and it frets beside it until someone deals with it.
     */
    fun alert(level: String) {
        val now = SystemClock.uptimeMillis()
        drives.noticed()
        if (level == AlertView.WHISPER) {
            if (asleep()) return
            watchX = width.toFloat()
            watchY = height / 2f
            watchUntil = now + 4_000
            wide = 1.25f
        } else {
            interrupt(now)
            wandering = false
            alertLevel = level
            alertAt = 0L
            alertY = if (level == AlertView.URGENT) y.coerceIn(R + 60f, height - R - 60f) else height * 0.3f + 60f
            wide = 1.45f
            if (level == AlertView.URGENT) {
                stretchV = 7f
                if (x > alertEdge(y) - R) vx = -700f
            }
        }
        invalidate()
    }

    fun alertGone() {
        val now = SystemClock.uptimeMillis()
        // Relief, once the red has drained away.
        if (alertLevel == AlertView.URGENT) {
            stretchV = 6f
            joyUntil = now + 2_500
        }
        if (alertLevel != null) {
            nextWanderAt = now + 4_000
            nextHobbyAt = max(nextHobbyAt, now + 20_000)
        }
        alertLevel = null
        invalidate()
    }

    private fun poking(now: Long) = alertLevel == AlertView.NUDGE && alertAt != 0L && now - alertAt in 300..2_800

    /** Something on the glass changed: a new scene, a new painting, an alert. */
    fun notice() = drives.noticed()

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        val a = event.values
        if (!haveSmooth) {
            a.copyInto(smooth, 0, 0, 3)
            haveSmooth = true
        }
        val jolt = hypot(hypot(a[0] - smooth[0], a[1] - smooth[1]), a[2] - smooth[2])
        for (i in 0..2) smooth[i] += (a[i] - smooth[i]) * 0.2f

        // The accelerometer reports the push holding the frame up, so things fall the other way.
        val (sx, sy) = when (rotation) {
            Surface.ROTATION_90 -> -smooth[1] to smooth[0]
            Surface.ROTATION_180 -> -smooth[0] to -smooth[1]
            Surface.ROTATION_270 -> smooth[1] to -smooth[0]
            else -> smooth[0] to smooth[1]
        }
        gravX = -sx
        gravY = sy
        if (restX.isNaN()) {
            restX = gravX
            restY = gravY
        }
        val dt = if (lastSensor == 0L) 0f else min(0.2f, (now - lastSensor) / 1000f)
        lastSensor = now
        restX += (gravX - restX) * dt / REST_SETTLE_S
        restY += (gravY - restY) * dt / REST_SETTLE_S

        // One knock rings for a moment, so the second only counts after a gap.
        if (jolt > KNOCK && now - lastRap > RAP_GAP_MS && now - lastPair > RAP_REST_MS) {
            if (now - lastRap < RAP_PAIR_MS) {
                lastPair = now
                onKnockTwice?.invoke()
            }
            lastRap = now
        }
        if (jolt > KNOCK && now - lastKnock > KNOCK_COOLDOWN_MS && !held && visibility == VISIBLE) {
            lastKnock = now
            startle()
            if (now > sulkUntil) knockBackAt = now + 700
        }
    }

    private fun crowded(px: Float, py: Float) = keepOut().any {
        px > it.left - CLEAR && px < it.right + CLEAR && py > it.top - CLEAR && py < it.bottom + CLEAR
    }

    /** Aim at a spot clear of words: the nearest one when it has to get off some, any otherwise. */
    private fun pickSpot(nearest: Boolean): Boolean {
        var best = Float.MAX_VALUE
        repeat(40) {
            val px = R + 30f + Random.nextFloat() * (width - 2 * R - 60f)
            val py = R + 30f + Random.nextFloat() * (height - 2 * R - 60f)
            val far = hypot(px - x, py - y)
            if (far < best && !crowded(px, py)) {
                best = far
                targetX = px
                targetY = py
                if (!nearest) return true
            }
        }
        return best < Float.MAX_VALUE
    }

    /** Find somewhere to sit with room for a patch of fog beside it that covers no words. */
    private fun planFog(now: Long) {
        val zones = keepOut()
        repeat(40) {
            val px = R + 30f + Random.nextFloat() * (width - 2 * R - 60f)
            val py = R + 30f + Random.nextFloat() * (height - 2 * R - 60f)
            if (crowded(px, py)) return@repeat
            for (side in if (Random.nextBoolean()) intArrayOf(-1, 1) else intArrayOf(1, -1)) {
                val cx = px + side * (FOG_W / 2 + R + 10f)
                val cy = py - 50f
                fogRect.set(cx - FOG_W / 2, cy - FOG_H / 2, cx + FOG_W / 2, cy + FOG_H / 2)
                if (fogRect.left < 10f || fogRect.top < 10f || fogRect.right > width - 10f || fogRect.bottom > height - 10f) continue
                if (zones.any { RectF.intersects(it, fogRect) }) continue
                targetX = px
                targetY = py
                wandering = true
                fogPhase = Fog.GOING
                // Any older patch goes; there is only one sheet of fog.
                fogAlpha = 0f
                fogFadeAt = 0L
                return
            }
        }
        nextHobbyAt = now + 20_000
    }

    private fun breatheFog(now: Long) {
        val bitmap = fog ?: Bitmap.createBitmap(FOG_W.toInt(), FOG_H.toInt(), Bitmap.Config.ARGB_8888).also {
            fog = it
            fogCanvas = Canvas(it)
        }
        bitmap.eraseColor(0)
        // One wide breath in the middle, then smaller puffs for a ragged edge.
        repeat(14) {
            val r = if (it < 3) 105f else 50f + Random.nextFloat() * 35f
            val cx = if (it < 3) FOG_W / 2 + (it - 1) * 40f else r + Random.nextFloat() * (FOG_W - 2 * r)
            val cy = if (it < 3) FOG_H / 2 else r + Random.nextFloat() * (FOG_H - 2 * r)
            blob.shader = RadialGradient(cx, cy, r, 0x48FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            fogCanvas?.drawCircle(cx, cy, r, blob)
        }
        fogPhase = Fog.BREATHING
        phaseAt = now
        wandering = false
    }

    /** Whatever it was drawing is left to fade as it is. */
    private fun dropFog() {
        if (fogPhase > Fog.GOING) {
            fogFadeAt = SystemClock.uptimeMillis()
            fogFadeFrom = fogAlpha
        }
        fogPhase = Fog.NONE
    }

    /** Is there room at this spot for a hobby that takes up `area` of the glass? */
    private fun roomFor(area: RectF) =
        area.left > 10f && area.top > 10f && area.right < width - 10f && area.bottom < height - 10f &&
            keepOut().none { RectF.intersects(it, area) }

    private fun begin(what: Hobby, now: Long) {
        hobby = what
        going = true
        when (what) {
            Hobby.FOG -> {
                hobby = null
                going = false
                planFog(now)
                return
            }
            // It likes to sleep low down, out of the way.
            Hobby.NAP -> {
                var low = -1f
                var lowX = 0f
                repeat(5) {
                    if (pickSpot(nearest = false) && targetY > low) {
                        low = targetY
                        lowX = targetX
                    }
                }
                targetX = lowX
                targetY = low
                going = low > 0f
            }
            Hobby.PEEK -> going = pickSpot(nearest = false)
            // Sit on top of some words, the clock for preference, without covering them.
            Hobby.PERCH -> {
                val ledge = keepOut().filter { it.width() > 200f && it.top - CLEAR - 2f > R + 10f }
                    .maxByOrNull { it.top + Random.nextFloat() * 200f }
                if (ledge != null) {
                    targetX = (ledge.left + 40f + Random.nextFloat() * (ledge.width() - 80f)).coerceIn(R + 10f, width - R - 10f)
                    targetY = ledge.top - CLEAR - 2f
                }
                going = ledge != null && !crowded(targetX, targetY)
                hobbyFor = Random.nextLong(30_000, 90_000)
            }
            Hobby.PEBBLES -> {
                going = false
                // Stacking needs a down; with the frame lying flat or on its side there is none.
                if (gravY > 6f && pebbles == 0) repeat(40) {
                    if (going) return@repeat
                    val px = R + 30f + Random.nextFloat() * (width - 2 * R - 60f)
                    val py = R + 30f + Random.nextFloat() * (height - 2 * R - 60f)
                    stackSide = if (Random.nextBoolean()) 1 else -1
                    stackX = px + stackSide * 84f
                    stackY = py + R * 0.78f
                    stackRect.set(stackX - 36f, stackY - 110f, stackX + 36f, stackY + 4f)
                    if (!crowded(px, py) && roomFor(stackRect)) {
                        targetX = px
                        targetY = py
                        going = true
                    }
                } else if (pebbles > 0) {
                    topple()
                }
                pebbleGoal = Random.nextInt(3, MAX_PEBBLES + 1)
            }
            // The mote comes to it: in from the nearest side, drifting.
            Hobby.MOTE -> {
                going = false
                hobbyAt = now
                moteX = if (x < width / 2) width + 10f else -10f
                moteY = 80f + Random.nextFloat() * (height - 160f)
                moteVX = if (moteX < 0f) 70f else -70f
                moteVY = 0f
                moteShown = true
                wide = 1.35f
                moteTired = now + Random.nextLong(9_000, 14_000)
                caughtAt = 0L
                sneezed = false
                return
            }
        }
        wandering = going
        if (!going) {
            hobby = null
            nextHobbyAt = now + 20_000
        }
    }

    private fun finish(what: Hobby, now: Long) {
        drives.did(what)
        lastHobby = what
        hobby = null
        nextHobbyAt = now + Random.nextLong(HOBBY_GAP_MIN_MS, HOBBY_GAP_MAX_MS)
        nextWanderAt = max(nextWanderAt, now + 6_000)
    }

    /** Picked up, knocked at or sliding: whatever it was doing stops where it is. */
    private fun interrupt(now: Long) {
        dropFog()
        if (hobby == Hobby.NAP && !going) drives.energy += 0.15f
        hobby = null
        going = false
        moteShown = false
        nextHobbyAt = now + 30_000
        gesture = null
        if (hiding) {
            hiding = false
            rising = true
        }
    }

    /** Where it is on the glass, for the quest page to open out of. */
    fun spot() = x to y

    /** Asleep in the daytime, for the quest, which waits politely. */
    val napping get() = asleep()

    private fun needs() = quest?.needs
    private fun hungry() = needs()?.hungry == true && needs()?.sickness == null
    private fun poorly() = needs()?.sickness

    /** Free to hold out its bowl: hungry, awake and not busy with anything else. */
    private fun holdingBowl(now: Long) = hungry() && meal == null && visitor == null && !asleep() && !night() && !held &&
        alertLevel == null && gesture == null && fogPhase == Fog.NONE && hobby == null && !hiding && now > sulkUntil

    /** Something to eat, from this side of the glass. Tama on the glass shows it; in the quest the page does. */
    fun feed(): Boolean {
        val q = quest ?: return false
        val now = SystemClock.uptimeMillis()
        val food = q.needs.nextFood(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), Random)
        if (away || visibility != VISIBLE) {
            q.feed(food)
            return true
        }
        interrupt(now)
        wandering = false
        meal = food
        mealAt = now
        mealFed = q.feed(food)
        nextWanderAt = max(nextWanderAt, now + 8_000)
        invalidate()
        return true
    }

    /** Medicine, from this side of the glass. False when Tama is well. */
    fun medicine(): Boolean {
        val q = quest ?: return false
        if (!q.medicine()) return false
        if (!away && visibility == VISIBLE) {
            val now = SystemClock.uptimeMillis()
            interrupt(now)
            wandering = false
            medicineAt = now
            nextWanderAt = max(nextWanderAt, now + 6_000)
        }
        invalidate()
        return true
    }

    /** It went up a level on its quest. */
    fun levelUp(level: Int) {
        if (away || visibility != VISIBLE || asleep()) return
        act("celebrate", 0)
        say("LV $level!")
    }

    /** It found a better hat on its quest, and is wearing it. */
    fun newHat() {
        if (away || visibility != VISIBLE) return
        stretchV = 7f
        joyUntil = SystemClock.uptimeMillis() + 2_500
        say("NEW HAT!")
        invalidate()
    }

    private fun say(words: String) {
        floatText = words
        floatAt = SystemClock.uptimeMillis()
        invalidate()
    }

    /**
     * A creature from the quest wants to come out onto the glass. Tama goes to shoo it back in,
     * if it is free; `done` is called with its name once it has. False if Tama is busy.
     */
    fun visit(sprite: android.graphics.Bitmap, name: String, done: (String) -> Unit): Boolean {
        val now = SystemClock.uptimeMillis()
        if (away || visibility != VISIBLE || held || alertLevel != null || asleep() || night() || hiding || gesture != null || meal != null || visitor != null || width == 0) return false
        interrupt(now)
        wandering = false
        visitor = sprite
        visitorName = name
        visitorDone = done
        visitorAt = now
        scuffleAt = 0L
        val fromLeft = x > width / 2f
        visitorX = if (fromLeft) -60f else width + 60f
        visitorY = (y + R * 0.85f).coerceIn(140f, height - 10f)
        wide = 1.4f
        invalidate()
        return true
    }

    private fun stepCare(now: Long, dt: Float) {
        val sick = poorly()
        if (sick != null && now > nextTwitchAt && !asleep() && !held) {
            when (sick) {
                Sickness.SNIFFLES -> {
                    if (nextTwitchAt != 0L) {
                        stretchV = 9f
                        flinchUntil = now + 450
                        say("achoo!")
                    }
                    nextTwitchAt = now + Random.nextLong(25_000, 60_000)
                }
                Sickness.HICCUPS -> {
                    if (nextTwitchAt != 0L) {
                        stretchV = 5.5f
                        say("hic!")
                    }
                    nextTwitchAt = now + Random.nextLong(3_000, 9_000)
                }
                else -> nextTwitchAt = now + 10_000
            }
        }
        meal?.let {
            if (now - mealAt > MEAL_MS) {
                meal = null
                if (mealFed == TamaNeeds.Fed.ATE || mealFed == TamaNeeds.Fed.LOVED) say(if (mealFed == TamaNeeds.Fed.LOVED) "♥ ♥" else "♥")
            } else if (mealFed == TamaNeeds.Fed.ATE || mealFed == TamaNeeds.Fed.LOVED) {
                // Three big bites.
                val bite = ((now - mealAt - 700) / 550).toInt()
                if (now - mealAt > 700 && bite < 3 && (now - mealAt - 700) % 550 < 60) stretchV = -4f
                if (now - mealAt > 2_500 && now - mealAt < 2_600) {
                    stretchV = 6f
                    joyUntil = now + 2_000
                }
            } else if (mealFed == TamaNeeds.Fed.STUFFED) {
                // A shake of the head.
                lean = 0.25f * sin((now - mealAt) / 1000f * 14f) * max(0f, 1f - (now - mealAt) / 1600f)
            }
        }
        if (medicineAt != 0L && now - medicineAt > 3_200) {
            medicineAt = 0L
            stretchV = 6f
            joyUntil = now + 2_000
        }
        val v = visitor ?: return
        val since = now - visitorAt
        if (scuffleAt == 0L) {
            val dx = x - visitorX
            visitorX += (if (dx > 0) 1f else -1f) * VISITOR_SPEED * dt
            if (abs(x - visitorX) < R * 2.1f || since > 20_000) {
                scuffleAt = now
                stretchV = 7f
            }
        } else if (now - scuffleAt > SCUFFLE_MS + POP_MS) {
            visitor = null
            visitorDone?.invoke(visitorName)
            visitorDone = null
            stretchV = 7f
            joyUntil = now + 3_000
            say("shoo!")
            nextWanderAt = now + 6_000
        } else if (now - scuffleAt < SCUFFLE_MS && (now / 140) % 3 == 0L) {
            stretchV = 3f
        }
        if (v.isRecycled) visitor = null
    }

    fun doingNow() = if (visibility == VISIBLE) doing.ifEmpty { "idle" } else "hidden by the owner"

    /** One or two words for what it is up to, for anyone asking the hub. */
    private fun describe(now: Long) = when {
        visibility != VISIBLE -> "hidden by the owner"
        held -> "being held"
        now < sulkUntil -> "sulking"
        alertLevel != null -> "minding an alert"
        hiding -> "hiding"
        gesture != null -> gesture!!
        asleep() -> "asleep"
        fogPhase != Fog.NONE -> "drawing in fog"
        hobby == Hobby.PEBBLES -> "stacking pebbles"
        hobby == Hobby.MOTE -> "chasing a mote"
        hobby == Hobby.PEEK -> "looking out"
        hobby == Hobby.PERCH -> "perched"
        hobby == Hobby.NAP -> "going for a nap"
        visitor != null -> "shooing a quest monster off the glass"
        meal != null -> "eating"
        medicineAt != 0L -> "taking its medicine"
        poorly() != null -> "poorly with ${poorly()!!.label}"
        hungry() -> "holding out its bowl"
        phone -> "listening for the hub"
        wandering -> "wandering"
        now < worryUntil -> "worried"
        now < happyUntil -> "happy"
        else -> "idle"
    }

    private fun topple() {
        if (pebbles == 0 || pebblesFalling) return
        pebblesFalling = true
        for (i in 0 until pebbles) {
            pebVX[i] = (Random.nextFloat() - 0.5f) * 220f
            pebVY[i] = -Random.nextFloat() * 120f
            pebSpin[i] = (Random.nextFloat() - 0.5f) * 500f
        }
    }

    private fun pebbleRestY(i: Int) = stackY - 8f - i * 15f

    private fun stepHobby(now: Long, dt: Float) {
        val since = (now - hobbyAt) / 1000f
        val doing = if (going) null else hobby
        press += ((if (doing == Hobby.PEEK && since < 6.5f) 1f else 0f) - press) * min(1f, dt * 4f)
        when (doing) {
            Hobby.NAP -> if (!night() && drives.energy > 0.9f && since > 120f) finish(Hobby.NAP, now)
            Hobby.PEEK -> if (since > 7.3f) {
                printX = x
                printY = y
                printAt = now
                finish(Hobby.PEEK, now)
            }
            Hobby.PERCH -> if (now - hobbyAt > hobbyFor) finish(Hobby.PERCH, now)
            Hobby.PEBBLES -> {
                val topX = stackX + if (pebbles % 2 == 0) -3f else 4f
                if (carryWait > 0f) {
                    carryWait -= dt
                    pawX = x + stackSide * 30f
                    pawY = y + 14f
                } else {
                    carry = min(1f, carry + dt / 0.9f)
                    val ease = carry * carry * (3f - 2f * carry)
                    pawX = x + stackSide * 30f + (topX - x - stackSide * 30f) * ease
                    pawY = y + 14f + (pebbleRestY(pebbles) - y - 14f) * ease - 46f * sin(PI.toFloat() * carry)
                    if (carry >= 1f) {
                        pebX[pebbles] = topX
                        pebY[pebbles] = pebbleRestY(pebbles)
                        pebTurn[pebbles] = 0f
                        pebbles++
                        carry = 0f
                        carryWait = 0.7f
                        wobble = 1f
                        if (pebbles == pebbleGoal) {
                            stackUntil = now + STACK_STANDS_MS
                            // Now and then the last one is one too many.
                            if (Random.nextInt(10) < 3) {
                                topple()
                                sadUntil = now + 4_000
                            } else {
                                stretchV = 6f
                                joyUntil = now + 3_000
                            }
                            finish(Hobby.PEBBLES, now)
                            // It sits by the pile for a while, pleased or not.
                            nextWanderAt = now + Random.nextLong(20_000, 40_000)
                        }
                    }
                }
            }
            Hobby.MOTE -> {
                val dx = moteX - x
                val dy = moteY - y
                val far = max(1f, hypot(dx, dy))
                if (caughtAt == 0L) {
                    val tired = now > moteTired
                    // It drifts about, and bolts when Tama gets close, until it tires.
                    val a = now / 1000f
                    moteVX += (cos(a * 0.7f) * 60f - moteVX) * dt
                    moteVY += (sin(a * 1.1f) * 50f - moteVY) * dt
                    if (!tired && far < 170f) {
                        moteVX += dx / far * 1400f * (1f - far / 170f) * dt
                        moteVY += dy / far * 1400f * (1f - far / 170f) * dt
                    }
                    if (moteX < 30f) moteVX += 400f * dt else if (moteX > width - 30f) moteVX -= 400f * dt
                    if (moteY < 30f) moteVY += 400f * dt else if (moteY > height - 30f) moteVY -= 400f * dt
                    if (since > 1.2f && far < R * 0.6f) {
                        caughtAt = now
                        moteShown = false
                        stretchV = -6f
                    } else if (now > moteTired + 10_000) {
                        // It got away after all.
                        moteShown = false
                        finish(Hobby.MOTE, now)
                    }
                } else if (!sneezed && now > caughtAt + 1_400) {
                    // The mote tickles: one sneeze and it is out again, off up the glass.
                    sneezed = true
                    stretchV = 10f
                    blinkAt = now
                    moteShown = true
                    moteX = x
                    moteY = y
                    moteVX = (Random.nextFloat() - 0.5f) * 240f
                    moteVY = -330f
                } else if (sneezed && (moteY < -20f || now > caughtAt + 6_000)) {
                    moteShown = false
                    finish(Hobby.MOTE, now)
                }
                moteX += moteVX * dt
                moteY += moteVY * dt
            }
            else -> {}
        }

        wobble *= exp(-dt * 3f)
        if (pebbles > 0 && !pebblesFalling && hobby != Hobby.PEBBLES && now > stackUntil) topple()
        if (pebblesFalling) {
            val pull = hypot(gravX, gravY)
            val gx = if (pull > 3f) gravX / pull else 0f
            val gy = if (pull > 3f) gravY / pull else 1f
            var gone = true
            for (i in 0 until pebbles) {
                pebVX[i] += gx * 1200f * dt
                pebVY[i] += gy * 1200f * dt
                pebX[i] += pebVX[i] * dt
                pebY[i] += pebVY[i] * dt
                pebTurn[i] += pebSpin[i] * dt
                if (pebX[i] > -40f && pebX[i] < width + 40f && pebY[i] > -40f && pebY[i] < height + 40f) gone = false
            }
            if (gone) {
                pebbles = 0
                pebblesFalling = false
            }
        }
    }

    private fun asleep() = hobby == Hobby.NAP && !going

    private fun penX(i: Int) = fogRect.centerX() + (doodle.strokes[strokeI][i * 2] - 50f) * DOODLE_SCALE + sin(i * 0.9f + strokeI * 2f)
    private fun penY(i: Int) = fogRect.centerY() + (doodle.strokes[strokeI][i * 2 + 1] - 50f) * DOODLE_SCALE + cos(i * 0.7f + strokeI)

    private fun stepFog(now: Long, dt: Float) {
        val since = (now - phaseAt) / 1000f
        when (fogPhase) {
            Fog.BREATHING -> {
                fogAlpha = min(1f, since / 2.4f)
                if (since > 2.8f) {
                    var pick = Random.nextInt(DOODLES.size)
                    if (pick == lastDoodle) pick = (pick + 1) % DOODLES.size
                    lastDoodle = pick
                    doodle = DOODLES[pick]
                    strokeI = 0
                    pointI = 0
                    penWait = 0.4f
                    pawX = x
                    pawY = y
                    fogPhase = Fog.DRAWING
                }
            }
            Fog.DRAWING -> {
                val stroke = doodle.strokes[strokeI]
                if (penWait > 0f) {
                    penWait -= dt
                } else {
                    penBudget += PEN_POINTS_PER_S * dt
                    while (penBudget >= 1f && pointI < stroke.size / 2 - 1) {
                        penBudget -= 1f
                        fogCanvas?.drawLine(
                            penX(pointI) - fogRect.left, penY(pointI) - fogRect.top,
                            penX(pointI + 1) - fogRect.left, penY(pointI + 1) - fogRect.top, wipe,
                        )
                        pointI++
                    }
                }
                val reach = min(1f, dt * 16f)
                pawX += (penX(pointI) - pawX) * reach
                pawY += (penY(pointI) - pawY) * reach
                if (pointI >= stroke.size / 2 - 1) {
                    penBudget = 0f
                    pointI = 0
                    penWait = doodle.pause
                    if (++strokeI == doodle.strokes.size) {
                        strokeI = 0
                        fogPhase = Fog.ADMIRING
                        phaseAt = now
                        stretchV = 6f
                        joyUntil = now + 3_200
                    }
                }
            }
            Fog.ADMIRING -> if (since > 3.2f) {
                dropFog()
                finish(Hobby.FOG, now)
                // It stays by its drawing for most of the time the fog lasts.
                nextWanderAt = now + Random.nextLong(20_000, 45_000)
            }
            else -> {}
        }
        if (poking(now)) {
            val prod = 0.5f - 0.5f * cos((now - alertAt) / 1000f * 2f * PI.toFloat() * 1.2f)
            pawX = x + 30f + (alertEdge(y) + 26f - x - 30f) * prod
            pawY = y + 6f
        }
        if (gesture == "wave") {
            val swing = (now - gestureAt) / 1000f * 13f
            pawX = x + R * 1.3f + 9f * sin(swing)
            pawY = y - R * 0.8f - 5f * abs(cos(swing))
        } else if (phone) {
            pawX = x + R * 0.98f
            pawY = y + 12f
        } else if (holdingBowl(now) || meal != null) {
            pawX = x + R * 1.12f
            pawY = y + R * 0.42f
        }
        val reaching = fogPhase == Fog.DRAWING || (hobby == Hobby.PEBBLES && !going) || poking(now) || gesture == "wave" || phone || holdingBowl(now) || meal != null
        pawOut += ((if (reaching) 1f else 0f) - pawOut) * min(1f, dt * 8f)
        if (fogFadeAt != 0L) {
            fogAlpha = fogFadeFrom * (1f - (now - fogFadeAt) / FOG_LIFE_MS.toFloat())
            if (fogAlpha <= 0f) {
                fogAlpha = 0f
                fogFadeAt = 0L
            }
            // Scooting through its own fog rubs it off.
            if (hypot(vx, vy) > 10f && x > fogRect.left - R && x < fogRect.right + R && y > fogRect.top - R && y < fogRect.bottom + R) {
                fogCanvas?.drawCircle(x - fogRect.left, y - fogRect.top, R * 0.9f, wipe)
            }
        }
    }

    private fun startle() {
        interrupt(SystemClock.uptimeMillis())
        topple()
        stretchV = 7f
        wide = 1.45f
        wandering = false
        nextWanderAt = SystemClock.uptimeMillis() + 6_000
        invalidate()
    }

    private fun step(now: Long, dt: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val slipX = gravX - restX
        val slipY = gravY - restY
        val slipping = !held && hypot(slipX, slipY) > SLIP
        if (nextWanderAt == 0L) {
            nextWanderAt = now + 10_000
            nextHobbyAt = now + FIRST_HOBBY_MS
        }
        if (slipping) {
            if (fogPhase != Fog.NONE || hobby != null) interrupt(now)
            topple()
        }
        if (hiding && now > hideUntil) {
            hiding = false
            rising = true
        }
        gesture?.let {
            val since = now - gestureAt
            // Celebrating is three jumps in a row.
            if (it == "celebrate" && hops < 3 && since >= hops * 450L) {
                hops++
                stretchV = 8f
            }
            if (since > (GESTURE_MS[it] ?: 2_000L)) {
                gesture = null
                nextWanderAt = now + 5_000
            }
        }
        stepCare(now, dt)
        val settled = !held && !wandering && !hiding && gesture == null && alertLevel == null && fogPhase == Fog.NONE && hobby == null && meal == null && visitor == null && medicineAt == 0L
        if (now < happyUntil && settled && now > nextHappyHopAt) {
            nextHappyHopAt = now + Random.nextLong(4_000, 9_000)
            stretchV = 4.5f
        }
        phone = now < tautUntil || (linkDownAt != 0L && now - linkDownAt > PHONE_AFTER_MS && settled && now > sulkUntil)
        if (settled || asleep()) {
            // Where it sits still becomes, in time, where it likes to sit.
            rest += dt
            if (rest >= 10f) {
                memory.rested(x / w, y / h, 10)
                rest = 0f
            }
        }
        if (now - savedAt > SAVE_EVERY_MS) {
            savedAt = now
            remember()
        }
        describe(now).let {
            if (it != doing) {
                doing = it
                onDoing?.invoke(it)
            }
        }
        stepFog(now, dt)
        stepHobby(now, dt)
        val chasing = hobby == Hobby.MOTE && caughtAt == 0L && now - hobbyAt > 1_200
        drives.tick(dt, hypot(vx, vy) > 20f, asleep(), fogPhase != Fog.NONE || hobby != null)

        if (held) {
            vx += ((fingerX - x) * 140f - vx * 16f) * dt
            vy += ((fingerY - y) * 140f - vy * 16f) * dt
        } else if (slipping) {
            wandering = false
            wide = max(wide, 1.3f)
            vx += slipX * SLIP_PULL * dt
            vy += slipY * SLIP_PULL * dt
            nextWanderAt = now + 5_000
        } else if (alertLevel != null) {
            val dx = (alertEdge(alertY) - R - 22f).coerceIn(R, w - R) - x
            val dy = alertY - y
            val far = hypot(dx, dy)
            if (far > 14f) {
                vx += (dx / far * SCOOT * 1.6f - vx) * min(1f, dt * 4f)
                vy += (dy / far * SCOOT * 1.6f - vy) * min(1f, dt * 4f)
            } else {
                if (alertAt == 0L) alertAt = now
                vx *= exp(-dt * 8f)
                vy *= exp(-dt * 8f)
            }
        } else if (hiding || rising) {
            // Down behind the bottom edge until only its eyes and tuft show; back up when done.
            val to = if (hiding) h - R * 0.22f else h - R - 30f
            vy += ((to - y) * 5f - vy) * min(1f, dt * 6f)
            vx *= exp(-dt * 4f)
            if (rising && y < h - R) rising = false
        } else if (chasing) {
            val far = max(1f, hypot(moteX - x, moteY - y))
            vx += ((moteX - x) / far * SCOOT * 1.7f - vx) * min(1f, dt * 3f)
            vy += ((moteY - y) / far * SCOOT * 1.7f - vy) * min(1f, dt * 3f)
        } else if (wandering) {
            val dx = targetX - x
            val dy = targetY - y
            val far = hypot(dx, dy)
            if (far < 14f && fogPhase == Fog.GOING) {
                breatheFog(now)
            } else if (far < 14f && going) {
                going = false
                wandering = false
                hobbyAt = now
                carry = 0f
                carryWait = 0.6f
            } else if (far < 14f) {
                wandering = false
                nextWanderAt = now + Random.nextLong(8_000, 30_000)
            } else {
                vx += (dx / far * SCOOT - vx) * min(1f, dt * 3f)
                vy += (dy / far * SCOOT - vy) * min(1f, dt * 3f)
            }
        } else if (fogPhase == Fog.NONE && hobby == null && gesture == null && now > sulkUntil && now > pinUntil && meal == null && visitor == null && medicineAt == 0L) {
            val onWords = crowded(x, y)
            // Left sitting on words (dropped there, or the layout changed under it), it shuffles off.
            if (onWords) nextWanderAt = min(nextWanderAt, now + 1_200)
            val night = night()
            if (now > nextHobbyAt && !onWords && drives.restless(night)) {
                val pick = drives.choose(lastHobby, night) { Random.nextFloat() }
                // Hungry or poorly, it has no heart for games; it will still nap.
                if ((hungry() || poorly() != null) && pick != Hobby.NAP) nextHobbyAt = now + 60_000 else begin(pick, now)
            } else if (now > nextWanderAt) {
                wandering = pickSpot(nearest = onWords)
                // One time in three, when it is free to choose, it heads for its usual spot.
                val usual = memory.favouriteSpot()
                if (!onWords && usual != null && Random.nextInt(3) == 0) {
                    val ux = (usual.first * w + (Random.nextFloat() - 0.5f) * 80f).coerceIn(R + 10f, w - R - 10f)
                    val uy = (usual.second * h + (Random.nextFloat() - 0.5f) * 80f).coerceIn(R + 10f, h - R - 10f)
                    if (!crowded(ux, uy)) {
                        targetX = ux
                        targetY = uy
                        wandering = true
                    }
                }
                if (!wandering) nextWanderAt = now + 10_000
            }
        }
        if (!held && !wandering && !chasing && alertLevel == null && !hiding && !rising) {
            // It clings to the glass, so it never coasts far.
            val grip = exp(-dt * if (slipping) 0.6f else 3f)
            vx *= grip
            vy *= grip
        }
        x += vx * dt
        y += vy * dt
        if (x < R || x > w - R) {
            x = min(w - R, max(R, x))
            stretchV += abs(vx) * 0.004f
            vx *= -0.45f
        }
        if (y < R || (y > h - R && !hiding && !rising)) {
            y = min(h - R, max(R, y))
            stretchV -= abs(vy) * 0.004f
            vy *= -0.45f
        }

        stretchV += (-170f * stretch - 9f * stretchV) * dt
        stretch += stretchV * dt
        wide += (1f - wide) * min(1f, dt * 1.5f)

        // The body sags towards wherever down is, and leans into a move.
        val sag = if (hypot(gravX, gravY) > 3f) atan2(gravX, gravY).coerceIn(-0.5f, 0.5f) else 0f
        val slump = if (asleep()) 0.22f else 0f
        val wantLean = if (held) (vx * 0.0012f).coerceIn(-0.6f, 0.6f) else sag + slump + (vx * 0.0015f).coerceIn(-0.3f, 0.3f)
        lean += (wantLean - lean) * min(1f, dt * 6f)

        val speed = hypot(vx, vy)
        var wantX = glanceX
        var wantY = glanceY
        val reaching = fogPhase == Fog.DRAWING || (hobby == Hobby.PEBBLES && !going)
        if (gesture != null || now < tautUntil) {
            wantX = 0f
            wantY = 0f
        } else if (phone) {
            wantX = 0.6f
            wantY = 0.2f
        } else if (alertLevel != null && !held) {
            // Mostly at the alert; while it is urgent, every so often out at whoever should be reading it.
            val out = alertLevel == AlertView.URGENT && now / 1300 % 3 == 0L
            wantX = if (out) 0f else 0.95f
            wantY = if (out) 0f else -0.15f
        } else if (visitor != null) {
            val far = max(1f, hypot(visitorX - x, visitorY - R * 0.5f - y))
            wantX = (visitorX - x) / far
            wantY = (visitorY - R * 0.5f - y) / far
        } else if (meal != null || holdingBowl(now)) {
            // At the bowl while there is something in it; at you while there is not.
            wantX = if (meal != null) 0.8f else 0f
            wantY = if (meal != null) 0.5f else 0f
        } else if (press > 0.3f) {
            // Looking out into the room, slowly, from side to side.
            wantX = 0.7f * sin(now / 1000f * 1.1f)
            wantY = 0f
        } else if (fogPhase > Fog.GOING || reaching || (moteShown && !held) || (pebblesFalling && now < sadUntil)) {
            val atX = if (reaching) pawX else if (moteShown) moteX else if (pebblesFalling) pebX[0] else fogRect.centerX()
            val atY = if (reaching) pawY else if (moteShown) moteY else if (pebblesFalling) pebY[0] else fogRect.centerY()
            val far = max(1f, hypot(atX - x, atY - y))
            wantX = (atX - x) / far
            wantY = (atY - y) / far
        } else if (now < watchUntil && !held) {
            val far = max(1f, hypot(watchX - x, watchY - y))
            wantX = (watchX - x) / far
            wantY = (watchY - y) / far
        } else if (held) {
            wantX = 0f
            wantY = -0.6f
        } else if (speed > 30f) {
            wantX = vx / speed
            wantY = vy / speed
        } else if (now > nextGlanceAt) {
            nextGlanceAt = now + Random.nextLong(1_500, 6_000)
            val away = Random.nextInt(3) > 0
            glanceX = if (away) Random.nextFloat() * 2f - 1f else 0f
            glanceY = if (away) Random.nextFloat() - 0.5f else 0f
        }
        lookX += (wantX - lookX) * min(1f, dt * 10f)
        lookY += (wantY - lookY) * min(1f, dt * 10f)

        // Thunder makes it jump.
        if (sky == Sky.STORM && now > nextFlinchAt) {
            if (nextFlinchAt != 0L && !asleep()) {
                stretchV = -5f
                wide = 1.4f
            }
            nextFlinchAt = now + Random.nextLong(30_000, 90_000)
        }
        if (now > blinkAt + BLINK_MS) blinkAt = now + Random.nextLong(2_000, 7_000)
        if (knockBackAt != 0L && now >= knockBackAt) {
            knockBackAt = 0L
            knockStart = now
            thump()
            postDelayed(::thump, SECOND_TAP_MS)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        if (away) {
            // Off on its quest; it looks back in every so often.
            postInvalidateDelayed(500)
            return
        }
        step(now, dt)

        val sulking = now < sulkUntil
        val sick = poorly()
        val bodyColor = if (sick == Sickness.TUMMY) SICK_BODY else BODY
        val shadeColor = if (sick == Sickness.TUMMY) SICK_SHADE else SHADE
        val t = now / 1000f
        val knock = (now - knockStart) / 1000f
        var pulse = 1f
        if (knock in 0f..KNOCK_SHOW_S) {
            for (at in floatArrayOf(0f, SECOND_TAP_MS / 1000f)) {
                val since = knock - at
                if (since < 0f) continue
                pulse += 0.12f * exp(-since * 16f)
                // A ring on the glass where it tapped.
                line.strokeWidth = 3f
                line.color = 0xFFFFFF or ((150 * max(0f, 1f - since / 0.5f)).toInt() shl 24)
                canvas.drawCircle(x, y, R * 0.5f + since * 240f, line)
            }
            line.color = INK
        }
        val scoot = when {
            wandering -> 0.05f * sin(t * 10f)
            // Each breath out puffs it up.
            fogPhase == Fog.BREATHING -> 0.06f * sin((now - phaseAt) / 1000f * 7f)
            else -> 0f
        }
        if (fogAlpha > 0f) fog?.let {
            fogPaint.alpha = (255 * fogAlpha).toInt()
            canvas.save()
            if (fogPhase == Fog.BREATHING) {
                // It spreads out from Tama's mouth.
                val grow = 0.5f + 0.5f * fogAlpha
                canvas.scale(grow, grow, if (x < fogRect.left) fogRect.left else fogRect.right, y)
            }
            canvas.drawBitmap(it, null, fogRect, fogPaint)
            canvas.restore()
        }
        val asleep = asleep()
        val breath = if (asleep) 0.045f * sin(t * 0.9f) else 0.022f * sin(t * 1.5f)
        pulse *= 1f + 0.3f * press
        val wet = sky == Sky.RAIN || sky == Sky.STORM
        val cold = sky != null && tempC <= COLD_C
        val hot = sky != null && tempC >= HOT_C
        val worried = alertLevel == AlertView.URGENT
        val bopping = music && !asleep && !held
        val loving = now < lovesUntil
        val bop = if (bopping) (if (loving) 0.075f else 0.045f) * sin(t * BOP) else 0f
        val fret = worried || now < worryUntil
        // A nod is two quick dips.
        val nod = if (gesture == "nod") 0.13f * max(0f, sin((now - gestureAt) / 1000f * 4f * PI.toFloat())) else 0f
        if (gesture == "celebrate") {
            val since = (now - gestureAt) / 1000f
            for (i in 0 until 16) {
                // Confetti: thrown up from where it stands, and falling.
                val a = -PI.toFloat() / 2f + (i - 7.5f) * 0.16f
                val speed = 330f + (i * 53 % 7) * 40f
                paint.color = CONFETTI[i % CONFETTI.size]
                paint.alpha = (255 * max(0f, 1f - since / 2.3f)).toInt()
                canvas.drawCircle(x + cos(a) * speed * since, y - R * 0.5f + sin(a) * speed * since + 420f * since * since, 5f - i % 3, paint)
            }
        }
        // In the heat it sags into a puddle of itself.
        val tall = (1f + stretch + scoot + bop + breath - nod) * pulse * if (hot) 0.88f else 1f
        val wideBody = (1f - (stretch + scoot + bop) * 0.8f) * pulse * if (hot) 1.08f else 1f
        val shiver = if (!asleep && (worried || sky == Sky.SNOW || (sky != null && tempC <= FREEZING_C))) 1.5f * sin(t * 60f) else 0f

        // The mark its face left on the glass.
        val print = 1f - (now - printAt) / PRINT_LIFE_MS.toFloat()
        if (printAt != 0L && print > 0f) {
            paint.color = 0xFFFFFF or ((34 * print).toInt() shl 24)
            oval.set(printX - R * 0.8f, printY - R * 0.55f, printX + R * 0.8f, printY + R * 0.6f)
            canvas.drawOval(oval, paint)
        }
        for (i in 0 until pebbles) drawPebble(canvas, i, pebX[i] + if (pebblesFalling) 0f else wobble * 3f * sin(t * 22f + i) * i, pebY[i], pebTurn[i])
        if (moteShown) {
            paint.color = 0x40FFFFFF
            canvas.drawCircle(moteX, moteY, 7f + sin(t * 9f), paint)
            paint.color = 0xE6FFFFFF.toInt()
            canvas.drawCircle(moteX, moteY, 2.6f, paint)
        }
        if (hobby == Hobby.PERCH && !going) {
            // Sitting on a ledge, it swings its feet.
            paint.color = SHADE
            for (side in intArrayOf(-1, 1)) {
                val fy = y + R * 0.86f + 5f + 4f * sin(t * 3.2f + side)
                oval.set(x + side * 17f - 9f, fy - 7f, x + side * 17f + 9f, fy + 7f)
                canvas.drawOval(oval, paint)
            }
        }

        paint.color = 0x38000000
        oval.set(x - R * 1.05f + 5f, y - R * 0.8f + 12f, x + R * 1.05f + 5f, y + R * 0.9f + 12f)
        canvas.drawOval(oval, paint)

        canvas.save()
        canvas.translate(x + shiver, y)
        canvas.rotate(lean * 180f / PI.toFloat() + if (bopping) 4f * sin(t * BOP / 2f) else 0f)
        canvas.scale(wideBody, tall, 0f, R * 0.85f)

        if (wet) {
            // A leaf for an umbrella, held up on its stalk and dripping at both ends.
            line.color = LEAF_DARK
            line.strokeWidth = 4f
            canvas.drawLine(R * 0.8f, R * 0.2f, R * 0.15f, -R * 1.5f, line)
            leaf.rewind()
            leaf.moveTo(-R * 1.15f, -R * 1.4f)
            leaf.quadTo(0f, -R * 2.25f, R * 1.3f, -R * 1.45f)
            leaf.quadTo(0f, -R * 1.3f, -R * 1.15f, -R * 1.4f)
            paint.color = LEAF
            canvas.drawPath(leaf, paint)
            line.strokeWidth = 2f
            canvas.drawLine(-R * 1.0f, -R * 1.42f, R * 1.15f, -R * 1.47f, line)
            line.color = INK
            paint.color = DROP
            for (side in intArrayOf(-1, 1)) {
                val fall = (t * 0.8f + 0.5f + side * 0.3f) % 1f
                canvas.drawCircle(side * R * 1.2f, -R * 1.4f + fall * fall * 110f, 3.5f * (1f - fall * 0.5f), paint)
            }
        }

        tuft.rewind()
        tuft.moveTo(-8f, -R * 0.8f)
        tuft.quadTo(-12f - lean * 20f, -R * 1.25f, 6f - lean * 30f, -R * 1.3f)
        tuft.quadTo(4f, -R * 1.0f, 8f, -R * 0.8f)
        if (sick == Sickness.GLITCH && (now / 110) % 11 < 2) {
            // Bits of it come apart into colours, the way a screen does.
            paint.color = 0x9900E5FF.toInt()
            oval.set(-R * 1.08f - 7f, -R * 0.88f, R * 1.08f - 7f, R * 0.88f)
            canvas.drawOval(oval, paint)
            paint.color = 0x99FF00C8.toInt()
            oval.set(-R * 1.08f + 7f, -R * 0.88f + 2f, R * 1.08f + 7f, R * 0.88f + 2f)
            canvas.drawOval(oval, paint)
        }
        paint.color = bodyColor
        canvas.drawPath(tuft, paint)
        paint.color = shadeColor
        oval.set(-R * 1.08f, -R * 0.88f, R * 1.08f, R * 0.88f)
        canvas.drawOval(oval, paint)
        paint.color = bodyColor
        oval.set(-R * 1.06f, -R * 0.87f, R * 0.98f, R * 0.72f)
        canvas.drawOval(oval, paint)
        paint.color = 0x99FFFFFF.toInt()
        oval.set(-R * 0.62f, -R * 0.62f, -R * 0.28f, -R * 0.4f)
        canvas.drawOval(oval, paint)

        val hat = quest?.hat
        if (sky == Sky.SNOW && hat == null) {
            // Snow settles on its head.
            paint.color = 0xFFFFFFFF.toInt()
            oval.set(-R * 0.62f, -R * 1.0f, R * 0.56f, -R * 0.68f)
            canvas.drawOval(oval, paint)
            canvas.drawCircle(-R * 0.5f, -R * 0.74f, R * 0.14f, paint)
            canvas.drawCircle(R * 0.44f, -R * 0.75f, R * 0.13f, paint)
        }
        if (cold) {
            line.color = SCARF
            line.strokeWidth = 12f
            oval.set(-R * 0.98f, R * 0.02f, R * 0.98f, R * 0.74f)
            canvas.drawArc(oval, 12f, 156f, false, line)
            paint.color = SCARF
            oval.set(R * 0.42f, R * 0.6f, R * 0.66f, R * 1.08f)
            canvas.drawRoundRect(oval, 5f, 5f, paint)
            line.color = INK
        }
        if (music) {
            line.color = PHONES
            line.strokeWidth = 6f
            oval.set(-R * 1.02f, -R * 1.08f, R * 1.02f, R * 0.9f)
            canvas.drawArc(oval, 200f, 140f, false, line)
            paint.color = PHONES
            for (side in intArrayOf(-1, 1)) {
                oval.set(side * R * 1.02f - 9f, -R * 0.38f, side * R * 1.02f + 9f, R * 0.22f)
                canvas.drawRoundRect(oval, 8f, 8f, paint)
            }
            line.color = INK
        }
        if (hat != null) {
            // Brought home from its quest, and still a little pixelated.
            val bmp = Pixels.hat(hat)
            val k = HAT_PIXEL
            pixelBox.set(-bmp.width * k / 2f, -R * 0.64f - bmp.height * k, bmp.width * k / 2f, -R * 0.64f)
            canvas.drawBitmap(bmp, null, pixelBox, pixels)
        }
        if (hot) {
            val slide = (t * 0.35f) % 1f
            paint.color = DROP
            canvas.drawCircle(R * 0.72f, -R * 0.5f + slide * R * 0.9f, 4.5f * (1f - slide), paint)
        }

        // Sulking, it turns its back: no face until it has got over it.
        if (!sulking) {
            val ex = lookX * R * 0.13f
            val ey = -R * 0.08f + lookY * R * 0.1f
            val shut = now in blinkAt..blinkAt + BLINK_MS
            val open = (if (shut) 0.12f else wide) * (1f + 0.25f * press)
            if (press > 0.02f) {
                // Where the face is flat against the glass.
                paint.color = 0xFFFFFF or ((120 * press).toInt() shl 24)
                oval.set(-R * 0.7f, -R * 0.42f, R * 0.7f, R * 0.5f)
                canvas.drawOval(oval, paint)
            }
            paint.color = if (loving) CHEEK_GLOW else CHEEK
            for (side in intArrayOf(-1, 1)) {
                oval.set(side * R * 0.66f - R * 0.15f + ex * 0.5f, R * 0.2f, side * R * 0.66f + R * 0.15f + ex * 0.5f, R * 0.37f)
                canvas.drawOval(oval, paint)
            }
            val deep = asleep && now - hobbyAt > YAWN_MS
            express(now, deep, worried, hot, shiver != 0f, bopping, loving)
            face.draw(canvas, ex, ey, open, t)
            if (sick == Sickness.SNIFFLES) {
                paint.color = NOSE
                canvas.drawCircle(ex, ey + R * 0.2f, R * 0.09f, paint)
            }
            if (deep) {
                // Fast asleep: a bubble at its nose that swells and shrinks with each breath.
                val r = 9f + 220f * breath
                paint.color = 0x38FFFFFF
                canvas.drawCircle(R * 0.2f + r, R * 0.12f, r, paint)
                line.color = 0xB0FFFFFF.toInt()
                line.strokeWidth = 1.5f
                canvas.drawCircle(R * 0.2f + r, R * 0.12f, r, line)
                line.color = INK
            }
        }
        canvas.restore()

        if (phone) {
            // A tin can to its ear. The string hangs limp until the hub answers, then pulls tight.
            val cx = x + R * 1.02f
            val cy = y - 8f
            line.color = 0xCCFFFFFF.toInt()
            line.strokeWidth = 1.6f
            string.rewind()
            string.moveTo(cx + 12f, cy)
            if (now < tautUntil) string.lineTo(width + 10f, cy - 160f + 3f * sin(t * 70f))
            else string.cubicTo(cx + 40f, cy + 10f, cx + 30f, height - y + cy - 40f, cx + 150f, height + 10f)
            line.style = Paint.Style.STROKE
            canvas.drawPath(string, line)
            line.color = INK
            paint.color = CAN
            oval.set(cx - 11f, cy - 13f, cx + 12f, cy + 13f)
            canvas.drawRoundRect(oval, 4f, 4f, paint)
            paint.color = CAN_RIM
            oval.set(cx + 8f, cy - 13f, cx + 12f, cy + 13f)
            canvas.drawRoundRect(oval, 2f, 2f, paint)
        }
        if (pawOut > 0.02f) {
            val px = x + (pawX - x) * pawOut
            val py = y + (pawY - y) * pawOut
            if (hobby == Hobby.PEBBLES && !going && carryWait <= 0f) drawPebble(canvas, pebbles, px, py + 8f, 0f)
            if (holdingBowl(now) || meal != null) drawBowl(canvas, px, py, now)
            paint.color = shadeColor
            canvas.drawCircle(px + 1f, py + 1.5f, 10f, paint)
            paint.color = bodyColor
            canvas.drawCircle(px, py, 9.5f, paint)
        }
        if (medicineAt != 0L && now - medicineAt < 1_600) {
            // A spoonful, from this side of the glass.
            val k = min(1f, (now - medicineAt) / 800f)
            val sx = x + R * 1.7f + (R * 0.5f - R * 1.7f) * k
            val sy = y - R * 2.2f + (R * 0.2f + R * 2.2f) * k
            line.color = SPOON
            line.strokeWidth = 5f
            canvas.drawLine(sx + 14f, sy - 4f, sx + 64f, sy - 34f, line)
            paint.color = SPOON
            oval.set(sx - 14f, sy - 9f, sx + 16f, sy + 9f)
            canvas.drawOval(oval, paint)
            paint.color = MEDICINE
            oval.set(sx - 10f, sy - 6f, sx + 12f, sy + 5f)
            canvas.drawOval(oval, paint)
            line.color = INK
        }
        val needy = (hungry() || sick != null) && !asleep && meal == null && medicineAt == 0L && visitor == null && !held
        if (needy && now % BUBBLE_EVERY_MS < BUBBLE_MS) {
            // What it is thinking about.
            val bx = x + R * 1.05f
            val by = y - R * 1.75f
            paint.color = BUBBLE
            canvas.drawCircle(x + R * 0.62f, y - R * 1.02f, 5f, paint)
            canvas.drawCircle(x + R * 0.82f, y - R * 1.32f, 8f, paint)
            canvas.drawCircle(bx, by, 32f, paint)
            if (sick != null) drawPill(canvas, bx, by) else drawFood(canvas, needs()?.favourite() ?: Food.ONIGIRI, bx, by, 0.85f)
        }
        visitor?.let { drawVisitor(canvas, it, now) }
        floatText?.let { said ->
            val age = (now - floatAt) / 1000f
            if (age > 1.8f) {
                floatText = null
            } else {
                words.color = 0xFFFFFFFF.toInt()
                words.alpha = (255 * min(1f, (1.8f - age) / 0.5f)).toInt()
                canvas.drawText(said, x, y - R * 1.5f - age * 50f, words)
            }
        }

        val busy = meal != null || medicineAt != 0L || visitor != null || floatText != null || gesture != null || hiding || rising || now < tautUntil || alertLevel != null || bopping || fogPhase != Fog.NONE || (hobby != null && !asleep) || moteShown || pebblesFalling || press > 0.01f || wobble > 0.01f ||
            pawOut > 0.02f || held || wandering || hypot(vx, vy) > 4f || abs(stretch) > 0.004f || abs(stretchV) > 0.05f ||
            knock in 0f..KNOCK_SHOW_S || knockBackAt != 0L || now < watchUntil
        if (busy) postInvalidateOnAnimation() else postInvalidateDelayed(IDLE_FRAME_MS)
    }

    private fun drawPebble(canvas: Canvas, i: Int, px: Float, py: Float, turn: Float) {
        val half = 27f - i * 3f
        canvas.save()
        canvas.rotate(turn, px, py)
        paint.color = 0x30000000
        oval.set(px - half + 2f, py - 6f + 4f, px + half + 2f, py + 9f + 4f)
        canvas.drawOval(oval, paint)
        paint.color = PEBBLE_COLORS[i % PEBBLE_COLORS.size]
        oval.set(px - half, py - 8f, px + half, py + 8f)
        canvas.drawOval(oval, paint)
        paint.color = 0x40FFFFFF
        oval.set(px - half * 0.6f, py - 6f, px + half * 0.2f, py - 1f)
        canvas.drawOval(oval, paint)
        canvas.restore()
    }

    /** The bowl it holds out when it is hungry, and what drops into it. */
    private fun drawBowl(canvas: Canvas, px: Float, py: Float, now: Long) {
        val m = meal
        if (m != null) {
            val since = now - mealAt
            val fall = min(1f, since / 600f)
            val eaten = mealFed == TamaNeeds.Fed.ATE || mealFed == TamaNeeds.Fed.LOVED
            val bites = if (eaten) ((since - 700) / 550f).coerceIn(0f, 3f).toInt() else 0
            val k = 1f - bites / 3f
            if (k > 0f && since < MEAL_MS - 200) drawFood(canvas, m, px + 6f, py - 8f - (1f - fall) * (1f - fall) * 260f, 0.9f * k)
            if (eaten && since in 700..2_400) {
                // Crumbs.
                paint.color = 0xFFFFE0B2.toInt()
                for (i in 0 until 4) {
                    val c = ((since - 700) % 550) / 550f
                    canvas.drawCircle(px - 6f + i * 6f, py - 4f + c * 30f + i * 3f, 2f, paint)
                }
            }
        }
        paint.color = BOWL_SHADE
        oval.set(px - 24f, py - 14f, px + 32f, py + 22f)
        canvas.drawArc(oval, 0f, 180f, true, paint)
        paint.color = BOWL
        oval.set(px - 22f, py - 12f, px + 30f, py + 19f)
        canvas.drawArc(oval, 0f, 180f, true, paint)
        line.color = BOWL_RIM
        line.strokeWidth = 3f
        oval.set(px - 22f, py - 2f, px + 30f, py + 6f)
        canvas.drawArc(oval, 10f, 160f, false, line)
        line.color = INK
    }

    private fun drawPill(canvas: Canvas, cx: Float, cy: Float) {
        canvas.save()
        canvas.rotate(-30f, cx, cy)
        paint.color = 0xFFEF5350.toInt()
        oval.set(cx - 16f, cy - 7f, cx + 2f, cy + 7f)
        canvas.drawRoundRect(oval, 7f, 7f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        oval.set(cx - 2f, cy - 7f, cx + 16f, cy + 7f)
        canvas.drawRoundRect(oval, 7f, 7f, paint)
        line.color = 0x40000000
        line.strokeWidth = 1.5f
        oval.set(cx - 16f, cy - 7f, cx + 16f, cy + 7f)
        canvas.drawRoundRect(oval, 7f, 7f, line)
        line.color = INK
        canvas.restore()
    }

    /** The foods from this side of the glass, at about 30 pixels across when `k` is 1. */
    private fun drawFood(canvas: Canvas, food: Food, cx: Float, cy: Float, k: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(k, k)
        when (food) {
            Food.ONIGIRI -> {
                leaf.rewind()
                leaf.moveTo(0f, -15f)
                leaf.quadTo(4f, -15f, 15f, 8f)
                leaf.quadTo(16f, 13f, 10f, 13f)
                leaf.lineTo(-10f, 13f)
                leaf.quadTo(-16f, 13f, -15f, 8f)
                leaf.quadTo(-4f, -15f, 0f, -15f)
                paint.color = 0xFFFAFAFA.toInt()
                canvas.drawPath(leaf, paint)
                paint.color = 0xFF263238.toInt()
                canvas.drawRect(-6f, 1f, 6f, 13f, paint)
            }
            Food.DANGO -> {
                line.color = 0xFFA1887F.toInt()
                line.strokeWidth = 3f
                canvas.drawLine(-16f, 14f, 14f, -14f, line)
                for ((i, c) in listOf(0xFF9CCC65, 0xFFFFFFFF, 0xFFF48FB1).withIndex()) {
                    paint.color = c.toInt()
                    canvas.drawCircle(-8f + i * 8f, 6f - i * 8f, 7f, paint)
                }
            }
            Food.TAIYAKI -> {
                paint.color = 0xFFD9963F.toInt()
                oval.set(-14f, -9f, 8f, 9f)
                canvas.drawOval(oval, paint)
                leaf.rewind()
                leaf.moveTo(6f, 0f)
                leaf.lineTo(16f, -9f)
                leaf.lineTo(16f, 9f)
                leaf.close()
                canvas.drawPath(leaf, paint)
                paint.color = INK
                canvas.drawCircle(-8f, -2f, 1.8f, paint)
                line.color = 0xFFB8752A.toInt()
                line.strokeWidth = 1.5f
                for (i in 0..2) canvas.drawLine(-3f + i * 4f, -6f, -3f + i * 4f, 6f, line)
            }
            Food.MELONPAN -> {
                paint.color = 0xFFE8D27A.toInt()
                canvas.drawCircle(0f, 0f, 14f, paint)
                line.color = 0xFFC9AE52.toInt()
                line.strokeWidth = 1.5f
                for (i in -1..1) {
                    canvas.drawLine(-12f, i * 6f - 6f, 12f, i * 6f + 6f, line)
                    canvas.drawLine(-12f, i * 6f + 6f, 12f, i * 6f - 6f, line)
                }
            }
            Food.STRAWBERRY -> {
                paint.color = 0xFFE53935.toInt()
                leaf.rewind()
                leaf.moveTo(-13f, -6f)
                leaf.quadTo(0f, -12f, 13f, -6f)
                leaf.quadTo(10f, 10f, 0f, 15f)
                leaf.quadTo(-10f, 10f, -13f, -6f)
                canvas.drawPath(leaf, paint)
                paint.color = 0xFFFFF59D.toInt()
                for ((sx, sy) in listOf(-5f to -2f, 4f to -1f, 0f to 5f, -3f to 9f, 5f to 6f)) canvas.drawCircle(sx, sy, 1.2f, paint)
                paint.color = 0xFF43A047.toInt()
                oval.set(-9f, -13f, 9f, -6f)
                canvas.drawOval(oval, paint)
            }
            Food.PUDDING -> {
                leaf.rewind()
                leaf.moveTo(-9f, -9f)
                leaf.lineTo(9f, -9f)
                leaf.lineTo(14f, 12f)
                leaf.lineTo(-14f, 12f)
                leaf.close()
                paint.color = 0xFFFFE082.toInt()
                canvas.drawPath(leaf, paint)
                paint.color = 0xFF8D5524.toInt()
                oval.set(-10f, -13f, 10f, -5f)
                canvas.drawOval(oval, paint)
                paint.color = 0xFFFAFAFA.toInt()
                oval.set(-17f, 10f, 17f, 15f)
                canvas.drawOval(oval, paint)
            }
        }
        line.color = INK
        canvas.restore()
    }

    /** The quest creature on the glass, the scuffle, and the coins it leaves as it goes back in. */
    private fun drawVisitor(canvas: Canvas, bmp: android.graphics.Bitmap, now: Long) {
        val t = now / 1000f
        val k = VISITOR_PIXEL
        val scuffle = if (scuffleAt == 0L) -1f else (now - scuffleAt) / 1000f
        val pop = scuffle - SCUFFLE_MS / 1000f
        val shrink = if (pop > 0f) max(0f, 1f - pop / (POP_MS / 1000f) * 2f) else 1f
        val bw = bmp.width * k * shrink
        val bh = bmp.height * k * shrink
        val hop = if (scuffleAt == 0L) abs(sin(t * 9f)) * 8f else 0f
        canvas.save()
        if (visitorX < x) canvas.scale(-1f, 1f, visitorX, visitorY)
        pixelBox.set(visitorX - bw / 2f, visitorY - bh - hop, visitorX + bw / 2f, visitorY - hop)
        if (shrink > 0f) canvas.drawBitmap(bmp, null, pixelBox, pixels)
        canvas.restore()
        if (scuffle in 0f..SCUFFLE_MS / 1000f) {
            val mx = (x + visitorX) / 2f
            val my = (y + visitorY - bmp.height * k / 2f) / 2f
            paint.color = DUST
            for (i in 0 until 8) {
                val a = i * 0.8f + t * 7f
                canvas.drawCircle(mx + cos(a) * 34f + sin(t * 23f + i) * 6f, my + sin(a) * 22f, 22f + 5f * sin(t * 17f + i), paint)
            }
            paint.color = 0xFFFFE082.toInt()
            for (i in 0 until 4) {
                val a = t * 5f + i * 1.6f
                canvas.drawCircle(mx + cos(a) * 50f, my - 30f + sin(a) * 14f, 4f, paint)
            }
        }
        if (pop > 0f) {
            for (i in 0 until 7) {
                val a = -PI.toFloat() / 2f + (i - 3) * 0.32f
                val sp = 300f + i % 3 * 60f
                paint.color = if (i % 2 == 0) 0xFFFFD54F.toInt() else 0xFFFFB300.toInt()
                canvas.drawCircle(visitorX + cos(a) * sp * pop, visitorY - bmp.height * k / 2f + sin(a) * sp * pop + 700f * pop * pop, 6f, paint)
            }
        }
    }

    /** For looking at a face while working on it: hold this one for a few seconds and stay put. */
    fun pinFace(eyes: String, mouth: String, brows: String, tear: Boolean) {
        val now = SystemClock.uptimeMillis()
        face.set(
            Eyes.values().firstOrNull { it.name.equals(eyes, true) } ?: Eyes.OPEN,
            Mouth.values().firstOrNull { it.name.equals(mouth, true) } ?: Mouth.SMILE,
            Brows.values().firstOrNull { it.name.equals(brows, true) } ?: Brows.NONE,
            tear,
        )
        interrupt(now)
        wandering = false
        pinUntil = now + 9_000
        nextWanderAt = max(nextWanderAt, now + 10_000)
        nextHobbyAt = max(nextHobbyAt, now + 10_000)
        invalidate()
    }

    /** Pick the face for this moment. The first thing that applies wins, strongest feelings first. */
    private fun express(now: Long, deep: Boolean, worried: Boolean, hot: Boolean, shivering: Boolean, bopping: Boolean, loving: Boolean) {
        if (now < pinUntil) return
        val f = face
        val sneezing = hobby == Hobby.MOTE && caughtAt != 0L
        val busyPaw = fogPhase == Fog.DRAWING || (hobby == Hobby.PEBBLES && !going) || poking(now)
        when {
            deep -> f.set(Eyes.ASLEEP, Mouth.FLAT)
            asleep() -> f.set(Eyes.SQUEEZED, Mouth.YAWN)
            now < flinchUntil -> f.set(Eyes.SQUEEZED, Mouth.WAVY)
            now < dizzyUntil -> f.set(Eyes.DIZZY, Mouth.WAVY)
            held -> f.set(Eyes.OPEN, Mouth.WAVY, Brows.RAISED)
            visitor != null && scuffleAt == 0L -> f.set(Eyes.OPEN, Mouth.OH, Brows.ANGRY)
            visitor != null && now - scuffleAt < SCUFFLE_MS -> f.set(Eyes.SQUEEZED, Mouth.GRIN, Brows.ANGRY)
            meal != null && mealFed == TamaNeeds.Fed.STUFFED -> f.set(Eyes.HALF, Mouth.POUT)
            meal != null && mealFed == TamaNeeds.Fed.TOO_POORLY -> f.set(Eyes.OPEN, Mouth.FROWN, Brows.WORRIED, tear = true)
            meal != null && now - mealAt < 700 -> f.set(Eyes.SPARKLE, Mouth.OH)
            meal != null && now - mealAt < 2_400 -> f.set(Eyes.HAPPY, if ((now - mealAt) % 550 < 270) Mouth.OH else Mouth.GRIN)
            medicineAt != 0L && now - medicineAt < 800 -> f.set(Eyes.OPEN, Mouth.OH, Brows.WORRIED)
            medicineAt != 0L && now - medicineAt < 2_200 -> f.set(Eyes.SQUEEZED, Mouth.WAVY)
            medicineAt != 0L -> f.set(Eyes.SPARKLE, Mouth.GRIN)
            // The mote tickles its nose: a long "ah", then the sneeze.
            sneezing && !sneezed -> f.set(Eyes.HALF, Mouth.OH, Brows.WORRIED)
            sneezing && now < caughtAt + 1_900 -> f.set(Eyes.SQUEEZED, Mouth.GRIN)
            worried -> f.set(Eyes.OPEN, Mouth.WAVY, Brows.WORRIED)
            now < sadUntil -> f.set(Eyes.OPEN, Mouth.FROWN, Brows.WORRIED, tear = true)
            now < worryUntil -> f.set(Eyes.OPEN, Mouth.FROWN, Brows.WORRIED)
            poorly() != null && now >= joyUntil -> f.set(Eyes.HALF, Mouth.WAVY, Brows.WORRIED)
            holdingBowl(now) && now >= joyUntil -> f.set(Eyes.SPARKLE, Mouth.OH, Brows.WORRIED)
            now < annoyedUntil -> f.set(Eyes.HALF, Mouth.POUT, Brows.ANGRY)
            fogPhase == Fog.BREATHING -> f.set(Eyes.HALF, Mouth.OH)
            wide > 1.15f -> f.set(Eyes.OPEN, Mouth.OH, Brows.RAISED)
            now < joyUntil || gesture == "celebrate" || now < tautUntil -> f.set(Eyes.HAPPY, Mouth.GRIN)
            loving -> f.set(Eyes.SPARKLE, Mouth.GRIN)
            busyPaw -> f.set(Eyes.OPEN, Mouth.TONGUE)
            gesture == "wave" -> f.set(Eyes.HAPPY, Mouth.SMILE)
            phone -> f.set(Eyes.OPEN, Mouth.FLAT, Brows.WORRIED)
            press > 0.3f -> f.set(Eyes.SPARKLE, Mouth.CAT)
            hot -> f.set(Eyes.HALF, Mouth.PANT)
            shivering -> f.set(Eyes.OPEN, Mouth.WAVY)
            // Lost in the music for a few bars at a time.
            bopping -> f.set(if (now / 1000 % 8 < 3) Eyes.HAPPY else Eyes.OPEN, Mouth.SMILE)
            now < happyUntil -> f.set(Eyes.OPEN, Mouth.GRIN)
            hobby == Hobby.MOTE -> f.set(Eyes.SPARKLE, Mouth.OH)
            drives.energy < 0.3f -> f.set(Eyes.HALF, Mouth.FLAT)
            drives.boredom > 0.75f -> f.set(Eyes.HALF, Mouth.POUT)
            hobby == Hobby.PERCH && !going -> f.set(Eyes.OPEN, Mouth.CAT)
            // Now and then, content with nothing in particular.
            else -> f.set(Eyes.OPEN, if (now / 15_000 % 4 == 0L) Mouth.CAT else Mouth.SMILE)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (away) return false
                if (hypot(event.x - x, event.y - y) > R * 1.4f && (pawOut < 0.5f || hypot(event.x - pawX, event.y - pawY) > R)) return false
                held = true
                interrupt(now)
                drives.touched()
                dragged = false
                wandering = false
                downX = event.x
                downY = event.y
                downAt = now
                // Hold it where it was grabbed rather than snapping its middle to the finger.
                fingerX = x
                fingerY = y
                stretchV = -5f
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > DRAG_SLOP) dragged = true
                if (dragged) {
                    fingerX = event.x
                    fingerY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                held = false
                nextWanderAt = now + 8_000
                // Hungry or poorly, a tap is care rather than a poke.
                if (!dragged && now - downAt < 400 && !(poorly()?.let { quest?.needs?.medicated == false && medicine() } ?: (hungry() && feed()))) poked(now)
                // Thrown hard enough, its eyes go round and round for a bit.
                if (hypot(vx, vy) > FLING) dizzyUntil = now + 2_600
            }
        }
        return true
    }

    private fun poked(now: Long) {
        onPoked?.invoke()
        pokes.addLast(now)
        flinchUntil = now + 350
        // The third poke in a row is where it stops being funny.
        if (pokes.size >= 3) annoyedUntil = now + 4_000
        while (pokes.first() < now - POKE_WINDOW_MS) pokes.removeFirst()
        if (pokes.size >= POKES_TO_SULK) {
            pokes.clear()
            sulkUntil = now + SULK_MS
        }
    }

    private fun thump() {
        if (!sound) return
        val track = tap ?: run {
            // A soft knuckle on glass: a low tone that dies away in a few hundredths of a second.
            val samples = ShortArray(SAMPLE_RATE * 70 / 1000) { i ->
                val s = i.toFloat() / SAMPLE_RATE
                (sin(2 * PI.toFloat() * 190f * s) * exp(-s * 70f) * 0.3f * Short.MAX_VALUE).toInt().toShort()
            }
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, samples.size * 2, AudioTrack.MODE_STATIC,
            ).also {
                it.write(samples, 0, samples.size)
                tap = it
            }
        }
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        track.reloadStaticData()
        track.play()
    }

    /** A drawing as pen strokes, each a run of x,y pairs in a 100 by 100 box. */
    private class Doodle(val pause: Float, vararg val strokes: FloatArray)

    private companion object {
        const val R = 46f
        /** How close to words it will settle, measured from its middle. */
        const val CLEAR = 38f
        const val FOG_W = 300f
        const val FOG_H = 220f
        const val FOG_LIFE_MS = 50_000L
        const val FIRST_HOBBY_MS = 40_000L
        const val HOBBY_GAP_MIN_MS = 60_000L
        const val HOBBY_GAP_MAX_MS = 180_000L
        const val PRINT_LIFE_MS = 15_000L
        /** A nap starts with a yawn this long. */
        const val YAWN_MS = 1_800L
        /** px/s on release that leaves it dizzy. */
        const val FLING = 900f
        val GESTURES = setOf("wave", "nod", "celebrate")
        val GESTURE_MS = mapOf("wave" to 2_400L, "nod" to 1_000L, "celebrate" to 2_400L)
        const val MOOD_MS = 10 * 60_000L
        const val HIDE_MS = 5 * 60_000L
        /** How long the hub must be gone before the tin can comes out. */
        const val PHONE_AFTER_MS = 30_000L
        const val SAVE_EVERY_MS = 5 * 60_000L
        const val CHEEK_GLOW = 0xE6FF7F9F.toInt()
        const val CAN = 0xFFB8C0C8.toInt()
        const val CAN_RIM = 0xFF7F8891.toInt()
        val CONFETTI = intArrayOf(0xFFFF6B5E.toInt(), 0xFF7BE0D0.toInt(), 0xFFFFD166.toInt(), 0xFFF4F1FF.toInt(), 0xFF8FB8FF.toInt())
        /** At or below these it wears a scarf, and shivers. */
        const val COLD_C = 8
        const val FREEZING_C = 2
        const val HOT_C = 30
        /** Radians a second of bopping: a little under two bounces a second. */
        const val BOP = 11.7f
        const val LEAF = 0xFF74B86B.toInt()
        const val LEAF_DARK = 0xFF3F7F45.toInt()
        const val DROP = 0xD9C4E6FF.toInt()
        const val SCARF = 0xFFD9534F.toInt()
        const val PHONES = 0xFF373C48.toInt()
        const val MAX_PEBBLES = 5
        const val SICK_BODY = 0xFFE4EDC8.toInt()
        const val SICK_SHADE = 0xFFC2D29A.toInt()
        const val NOSE = 0xFFE57373.toInt()
        const val BOWL = 0xFFFAFAFA.toInt()
        const val BOWL_SHADE = 0xFFB0BEC5.toInt()
        const val BOWL_RIM = 0xFF5C8DD6.toInt()
        const val SPOON = 0xFFCFD8DC.toInt()
        const val MEDICINE = 0xFFF06292.toInt()
        const val BUBBLE = 0xE6FFFFFF.toInt()
        const val DUST = 0xE6E0E0E0.toInt()
        const val BUBBLE_EVERY_MS = 18_000L
        const val BUBBLE_MS = 4_000L
        const val MEAL_MS = 3_600L
        /** Screen pixels per square, for things brought back from the quest. */
        const val HAT_PIXEL = 4f
        const val VISITOR_PIXEL = 4f
        const val VISITOR_SPEED = 120f
        const val SCUFFLE_MS = 2_200L
        const val POP_MS = 1_200L
        const val STACK_STANDS_MS = 4 * 60_000L
        val PEBBLE_COLORS = intArrayOf(0xFF8D99A6.toInt(), 0xFFB9A58C.toInt(), 0xFF6F7C85.toInt(), 0xFFC9C3B8.toInt(), 0xFF9AA79A.toInt())
        const val DOODLE_SCALE = 1.3f
        const val PEN_POINTS_PER_S = 26f

        /** A line through the given points, cut into short steps so it can be drawn bit by bit. */
        fun line(vararg p: Float): FloatArray {
            val out = ArrayList<Float>()
            for (i in 0 until p.size / 2 - 1) {
                val steps = max(1, (hypot(p[i * 2 + 2] - p[i * 2], p[i * 2 + 3] - p[i * 2 + 1]) / 3f).toInt())
                for (s in 0 until steps) {
                    out += p[i * 2] + (p[i * 2 + 2] - p[i * 2]) * s / steps
                    out += p[i * 2 + 1] + (p[i * 2 + 3] - p[i * 2 + 1]) * s / steps
                }
            }
            out += p[p.size - 2]
            out += p[p.size - 1]
            return out.toFloatArray()
        }

        fun curve(steps: Int, at: (Float) -> Pair<Float, Float>) = FloatArray((steps + 1) * 2).also {
            for (i in 0..steps) {
                val (px, py) = at(i.toFloat() / steps)
                it[i * 2] = px
                it[i * 2 + 1] = py
            }
        }

        /** Part of an oval, in degrees clockwise from three o'clock. */
        fun arc(cx: Float, cy: Float, rx: Float, ry: Float, from: Float = 0f, to: Float = 360f) =
            curve(max(4, (abs(to - from) / 360f * (rx + ry) * PI.toFloat() / 3f).toInt())) {
                val a = (from + (to - from) * it) * PI.toFloat() / 180f
                cx + rx * cos(a) to cy + ry * sin(a)
            }

        fun cross(cx: Float, cy: Float) = arrayOf(line(cx - 8f, cy - 8f, cx + 8f, cy + 8f), line(cx + 8f, cy - 8f, cx - 8f, cy + 8f))

        val DOODLES = arrayOf(
            // heart
            Doodle(0.25f, curve(60) {
                val a = it * 2 * PI.toFloat()
                val s = sin(a)
                50f + 41f * s * s * s to 44f - 2.6f * (13f * cos(a) - 5f * cos(2 * a) - 2f * cos(3 * a) - cos(4 * a))
            }),
            // spiral
            Doodle(0.25f, curve(90) {
                val a = it * 5f * PI.toFloat()
                50f + (4f + 40f * it) * cos(a) to 50f + (4f + 40f * it) * sin(a)
            }),
            // star
            Doodle(0.25f, line(50f, 8f, 74f, 88f, 10f, 38f, 90f, 38f, 26f, 88f, 50f, 8f)),
            // sun
            Doodle(0.15f, arc(50f, 50f, 20f, 20f), *Array(8) {
                val a = it * PI.toFloat() / 4f
                line(50f + 28f * cos(a), 50f + 28f * sin(a), 50f + 43f * cos(a), 50f + 43f * sin(a))
            }),
            // a face
            Doodle(0.3f, arc(50f, 50f, 40f, 40f), line(36f, 36f, 36f, 46f), line(64f, 36f, 64f, 46f), arc(50f, 54f, 22f, 18f, 20f, 160f)),
            // itself
            Doodle(
                0.3f, arc(50f, 58f, 42f, 32f, -80f, 260f), line(43f, 27f, 42f, 12f, 56f, 9f, 55f, 26f),
                line(37f, 50f, 37f, 58f), line(63f, 50f, 63f, 58f), arc(50f, 64f, 8f, 6f, 20f, 160f),
            ),
            // noughts and crosses against itself; crosses win on the diagonal
            Doodle(
                0.6f, line(37f, 10f, 37f, 90f), line(63f, 10f, 63f, 90f), line(10f, 37f, 90f, 37f), line(10f, 63f, 90f, 63f),
                *cross(50f, 50f), arc(23.5f, 23.5f, 8f, 8f), *cross(76.5f, 23.5f), arc(76.5f, 76.5f, 8f, 8f), *cross(23.5f, 76.5f),
                line(92f, 8f, 8f, 92f),
            ),
            // fish
            Doodle(
                0.3f, arc(45f, 52f, 30f, 18f), line(75f, 52f, 93f, 38f, 93f, 66f, 75f, 52f), arc(29f, 48f, 2f, 2f),
                arc(13f, 28f, 4f, 4f), arc(7f, 14f, 3f, 3f),
            ),
            // house
            Doodle(
                0.3f, line(20f, 90f, 20f, 50f, 50f, 22f, 80f, 50f, 80f, 90f, 20f, 90f), line(44f, 90f, 44f, 68f, 56f, 68f, 56f, 90f),
                line(28f, 56f, 38f, 56f, 38f, 66f, 28f, 66f, 28f, 56f),
            ),
        )
        const val BODY = 0xFFFFE6D2.toInt()
        const val SHADE = 0xFFF0BC9A.toInt()
        const val CHEEK = 0x99FF9F9F.toInt()
        const val INK = 0xFF2A1A1A.toInt()
        const val IDLE_FRAME_MS = 50L
        const val BLINK_MS = 140L
        const val WATCH_MS = 1_500L
        const val DRAG_SLOP = 14f
        const val SCOOT = 130f
        /** m/s² away from how the frame usually sits before Tama loses its grip. */
        const val SLIP = 1.6f
        /** px/s² of slide per m/s² of tilt. */
        const val SLIP_PULL = 260f
        const val REST_SETTLE_S = 20f
        /** m/s² of sudden jolt that counts as a knock. */
        const val KNOCK = 3f
        const val KNOCK_COOLDOWN_MS = 1_500L
        const val RAP_GAP_MS = 220L
        const val RAP_PAIR_MS = 1_300L
        const val RAP_REST_MS = 2_500L
        const val KNOCK_SHOW_S = 0.9f
        const val SECOND_TAP_MS = 230L
        const val POKE_WINDOW_MS = 8_000L
        const val POKES_TO_SULK = 5
        const val SULK_MS = 12_000L
        const val SAMPLE_RATE = 22_050
    }
}

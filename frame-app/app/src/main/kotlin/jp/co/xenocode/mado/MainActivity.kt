package jp.co.xenocode.mado

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), HubLink.Listener {
    private lateinit var root: FrameLayout
    private lateinit var alerts: AlertView
    private lateinit var rail: RailView
    private lateinit var tama: TamaView
    private val tamaZones = ArrayList<android.graphics.RectF>()
    private var trackTitle = ""
    private var hubHearsTama = false
    private lateinit var nowPlaying: NowPlayingView
    private lateinit var dashboard: DashboardView
    private lateinit var projectsPage: ProjectsView
    private var projectList: List<DashboardView.Project> = emptyList()
    /** Project icons by the hub's name for them; a new picture has a new name. */
    private val projectIcons = HashMap<String, Bitmap>()
    private var fetchingIcons = false
    private lateinit var scenes: List<View>
    private var scene = DASHBOARD
    private var artVersion = -1L
    private var paintingVersion = -1L
    private var paintingDay = ""
    private val dayWatch = object : Runnable {
        override fun run() {
            askForPainting()
            root.postDelayed(this, 60_000)
        }
    }
    private var playState = ""
    private val backHome = Runnable { showScene(HOME) }
    private var link: HubLink? = null
    private lateinit var weatherSource: WeatherSource
    private var away = arrayOfNulls<Weather>(0)
    private val weatherWatch = object : Runnable {
        override fun run() {
            refreshWeather()
            root.postDelayed(this, WEATHER_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        alerts = AlertView(this).apply {
            onPresented = { tama.alert(it.level) }
            onDismissed = { alert, reason ->
                tama.alertGone()
                link?.send(JSONObject().put("t", "event").put("kind", "dismissed").put("id", alert.id).put("reason", reason))
            }
        }
        nowPlaying = NowPlayingView(this).apply {
            visibility = View.GONE
            fullCover = prefs().getBoolean("full_cover", false)
            onFullCover = { prefs().edit().putBoolean("full_cover", it).apply() }
            onPlayPause = { link?.send(JSONObject().put("t", "event").put("kind", "media").put("action", "playpause")) }
        }
        dashboard = DashboardView(this).apply {
            layout = prefs().getInt("dashboard_layout", DashboardView.FULL_BLEED)
            onLayoutChosen = { prefs().edit().putInt("dashboard_layout", it).apply() }
            onProjects = { projectsPage.open() }
        }
        projectsPage = ProjectsView(this)
        scenes = listOf(dashboard, nowPlaying)
        tama = TamaView(this).apply {
            visibility = if (prefs().getBoolean("tama", true)) View.VISIBLE else View.GONE
            sound = prefs().getBoolean("tama_sound", false)
            onDoing = ::reportTama
            keepOut = {
                tamaZones.clear()
                tamaZones.addAll(if (scene == DASHBOARD) dashboard.textZones else nowPlaying.textZones)
                if (!alerts.zone.isEmpty) tamaZones.add(alerts.zone)
                tamaZones
            }
            alertEdge = alerts::leftEdge
        }
        rail = RailView(this).apply {
            tamaShown = tama.visibility == View.VISIBLE
            tamaSound = tama.sound
            onTama = {
                tama.visibility = if (it) View.VISIBLE else View.GONE
                // A hidden Tama draws nothing, so it cannot say so itself.
                if (!it) reportTama("hidden by the owner")
                prefs().edit().putBoolean("tama", it).apply()
            }
            onTamaSound = {
                tama.sound = it
                prefs().edit().putBoolean("tama_sound", it).apply()
            }
            scenes = listOf("Dashboard", "Now playing")
            onScene = { index ->
                showScene(index)
                lingerOnNowPlaying()
            }
            brightness = prefs().getFloat("brightness", 1f)
            onBrightness = ::setBrightness
            // The frame has no Home key, so the rail is the way out to the launcher.
            onHome = ::leaveToLauncher
        }
        setBrightness(rail.brightness)
        root = FrameLayout(this).apply {
            scenes.forEach(::addView)
            addView(projectsPage)
            addView(tama)
            addView(alerts)
            addView(rail)
        }
        setContentView(root)
        adoptHubAddress(intent)
        connect()
        root.postDelayed(dayWatch, 60_000)
        weatherSource = WeatherSource(this)
        weatherWatch.run()
    }

    // `am start ... --es hub ws://host:7878/frame` points the frame at a hub; it is remembered.
    // `--es tama pebbles` (or fog, mote, nap, peek, perch) asks Tama for a hobby now.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (adoptHubAddress(intent)) connect()
        val wanted = intent.getStringExtra("tama")
        Hobby.values().firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let(tama::perform)
        // Debug builds only, for looking at how Tama dresses: `--es tama_sky rain --ei tama_temp 3 --ez tama_music true`,
        // and at its faces: `--es tama_face sparkle,grin,raised` (eyes, mouth, brows; add `,tear`).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val sky = Sky.values().firstOrNull { it.name.equals(intent.getStringExtra("tama_sky"), ignoreCase = true) }
            if (sky != null) tama.setWeather(Weather("", intent.getIntExtra("tama_temp", 20), 0, 0, "", "", "", true, sky))
            intent.getStringExtra("tama_face")?.split(',')?.let {
                tama.pinFace(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }, it.getOrNull(3) == "tear")
            }
            if (intent.hasExtra("tama_music")) tama.music = intent.getBooleanExtra("tama_music", false)
        }
    }

    override fun onPause() {
        tama.remember()
        super.onPause()
    }

    override fun onDestroy() {
        link?.stop()
        super.onDestroy()
    }

    private fun reportTama(doing: String) {
        if (hubHearsTama) link?.send(JSONObject().put("t", "event").put("kind", "tama").put("doing", doing))
    }

    private fun prefs() = getSharedPreferences("mado", MODE_PRIVATE)

    private fun adoptHubAddress(intent: Intent?): Boolean {
        val hub = intent?.getStringExtra("hub") ?: return false
        prefs().edit().putString("hub", hub).apply()
        return true
    }

    /**
     * Mado is a home app itself, so "home" would lead straight back here: open the other
     * launcher by name, or Settings when Mado is the only one installed.
     */
    private fun leaveToLauncher() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo }
            .firstOrNull { it.packageName != packageName }
        val target = if (other != null) home.setClassName(other.packageName, other.name)
        else Intent(android.provider.Settings.ACTION_SETTINGS)
        startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
        prefs().edit().putFloat("brightness", value).apply()
    }

    private fun connect() {
        link?.stop()
        onHubLink(false)
        val hub = prefs().getString("hub", null)
        rail.hubAddress = hub
        if (hub == null) return
        val hello = JSONObject()
            .put("t", "hello")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("app", packageManager.getPackageInfo(packageName, 0).versionName)
            .put("api", Build.VERSION.SDK_INT)
            .put("w", resources.displayMetrics.widthPixels)
            .put("h", resources.displayMetrics.heightPixels)
        link = HubLink(hub, hello, this).also { it.start() }
    }

    override fun onHubLink(up: Boolean) {
        alerts.linkUp = up
        // With the hub gone nobody can say whether music is still playing.
        if (!up) tama.music = false
        if (prefs().getString("hub", null) != null) tama.hubLink(up)
        if (!up) hubHearsTama = false
        rail.linkUp = up
        if (up) {
            paintingDay = ""
            askForPainting()
        }
    }

    // The first place in the hub's list is "here"; the rest are other places that matter
    // (family, say). The saved copy keeps weather working when the hub is unreachable.
    private fun refreshWeather() {
        val places = try {
            JSONObject(prefs().getString("config", null) ?: "{}").optJSONArray("places")
        } catch (e: Exception) {
            null
        }
        val here = places?.optJSONObject(0)
        if (here == null) {
            dashboard.setWeather(null)
            tama.setWeather(null)
        }
        // A failed refresh keeps the last forecast on screen rather than blanking it.
        here?.let { p -> weatherSource.fetch(p.optString("name"), p.optDouble("lat"), p.optDouble("lon")) {
            if (it != null) {
                dashboard.setWeather(it)
                tama.setWeather(it)
            }
        } }

        val others = (1 until (places?.length() ?: 0)).mapNotNull { places?.optJSONObject(it) }
        if (others.size != away.size) away = arrayOfNulls(others.size)
        if (others.isEmpty()) dashboard.setAway(emptyList())
        val slots = away
        others.forEachIndexed { i, p ->
            weatherSource.fetch(p.optString("name"), p.optDouble("lat"), p.optDouble("lon")) {
                // The list may have changed while this was in flight; then these slots are stale.
                if (it != null && slots === away) {
                    slots[i] = it
                    dashboard.setAway(slots.filterNotNull())
                }
            }
        }
    }

    /** The frame owns the date: it asks the hub for a painting once per local day. */
    private fun askForPainting() {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        // The clock reads 1970 for a while after boot, until NTP answers.
        if (day == paintingDay || day < "2024") return
        paintingDay = day
        link?.send(JSONObject().put("t", "want_art").put("day", day))
    }

    private fun painting(msg: JSONObject) {
        val version = msg.optLong("version")
        if (version == paintingVersion) return
        paintingVersion = version
        val link = link ?: return
        val palette = msg.optJSONObject("palette") ?: JSONObject()
        // parseColor throws different exceptions for null, empty and malformed strings.
        fun parse(hex: String?, fallback: Int) = try {
            Color.parseColor(hex)
        } catch (e: RuntimeException) {
            fallback
        }
        fun color(name: String, fallback: Int) = parse(palette.optString(name), fallback)
        val swatches = palette.optJSONArray("swatches")
        link.fetch("/daily/$version/image.jpg") { imageBytes ->
            link.fetch("/daily/$version/qr.png") { qrBytes ->
                if (version == paintingVersion) {
                    val art =
                        DashboardView.Art(
                            title = msg.optString("title"), artist = msg.optString("artist"), date = msg.optString("date"),
                            medium = msg.optString("medium"), credit = msg.optString("credit"), text = msg.optString("text"),
                            image = imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
                            qr = qrBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
                            ink = color("ink", Color.BLACK), paper = color("paper", Color.WHITE),
                            wall = color("wall", Color.DKGRAY), accent = color("accent", Color.WHITE),
                            swatches = IntArray(5) { i -> parse(swatches?.optString(i), Color.GRAY) },
                        )
                    dashboard.setArt(art)
                    tama.notice()
                    projectsPage.setArt(art)
                }
            }
        }
    }

    private fun projects(msg: JSONObject) {
        val items = msg.optJSONArray("items") ?: return
        val all =
            (0 until items.length()).map { i ->
                val p = items.getJSONObject(i)
                DashboardView.Project(
                    p.optString("name"), p.optString("status"), p.optString("note"),
                    if (p.isNull("progress")) null else p.optDouble("progress").toFloat(),
                    p.optLong("updated"),
                    // A hub from before icons sends neither initials nor a colour.
                    p.optString("initials").ifEmpty { p.optString("name").trim().take(1).uppercase() },
                    try {
                        Color.parseColor(p.optString("tint"))
                    } catch (e: RuntimeException) {
                        Color.GRAY
                    },
                    if (p.isNull("icon")) "" else p.optString("icon"),
                ).also { it.icon = projectIcons[it.iconKey] }
            }
        projectList = all
        projectIcons.keys.retainAll(all.map { it.iconKey }.toSet())
        dashboard.setProjects(all)
        projectsPage.setProjects(all)
        if (!fetchingIcons) fetchIcons(HashSet())
    }

    /**
     * Fetch the project icons the frame does not have yet, one at a time. `tried` holds the
     * ones already asked for in this run, so an icon the hub cannot give is not asked for twice.
     */
    private fun fetchIcons(tried: HashSet<String>) {
        val key = projectList.map { it.iconKey }.firstOrNull { it.isNotEmpty() && it !in projectIcons && tried.add(it) }
        val link = link
        fetchingIcons = key != null && link != null
        if (key == null || link == null) return
        link.fetch("/icons/$key.png") { bytes ->
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { icon ->
                projectIcons[key] = icon
                projectList.filter { it.iconKey == key }.forEach { it.icon = icon }
                dashboard.invalidate()
                projectsPage.invalidate()
            }
            fetchIcons(tried)
        }
    }

    override fun onHubMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "notify" -> {
                tama.notice()
                alerts.show(
                    AlertView.Alert(
                        id = msg.optLong("id"),
                        level = msg.optString("level", "nudge"),
                        text = msg.optString("text"),
                        source = if (msg.isNull("source")) null else msg.optString("source"),
                        ttlMs = msg.optLong("ttl_s") * 1000,
                    )
                )
            }
            "now_playing" -> nowPlaying(msg)
            // Only a hub that says it follows Tama is told what Tama is up to; an older one
            // would write every change into its log.
            "hub" -> {
                hubHearsTama = msg.optBoolean("tama")
                reportTama(tama.doingNow())
            }
            "companion" -> tama.act(msg.optString("action"), msg.optLong("ttl_s") * 1000)
            "art" -> painting(msg)
            "projects" -> projects(msg)
            "look" -> look(msg.optLong("req"))
            // Kept for the scenes that will use it (weather places); nothing reads it yet.
            "config" -> {
                prefs().edit().putString("config", msg.optJSONObject("config")?.toString()).apply()
                refreshWeather()
            }
        }
    }

    private fun showScene(index: Int) {
        if (index == scene) return
        projectsPage.close()
        val leaving = scenes[scene]
        val arriving = scenes[index]
        scene = index
        rail.activeScene = index
        tama.notice()
        arriving.animate().cancel()
        arriving.alpha = 0f
        arriving.visibility = View.VISIBLE
        arriving.animate().alpha(1f).setDuration(SCENE_FADE_MS).withLayer()
        leaving.animate().cancel()
        leaving.animate().alpha(0f).setDuration(SCENE_FADE_MS).withLayer().withEndAction { leaving.visibility = View.GONE }
    }

    // Music takes the stage when it starts and gives it back a while after it stops.
    private fun nowPlaying(msg: JSONObject) {
        val state = msg.optString("state")
        val playing = state == "playing"
        tama.music = playing
        if (playing && msg.optString("title") != trackTitle) {
            trackTitle = msg.optString("title")
            tama.trackChanged(msg.optString("artist"))
        }
        nowPlaying.setTrack(msg.optString("title"), msg.optString("artist"), msg.optString("album"), msg.optString("source"))
        nowPlaying.setPlayback(playing, msg.optDouble("position_s", 0.0), msg.optDouble("duration_s", 0.0))

        val art = if (msg.isNull("art")) 0L else msg.optLong("art")
        if (art != artVersion) {
            artVersion = art
            if (art == 0L) nowPlaying.setArt(null, null) else fetchArt(art)
        }

        // Only a change of state moves the stage; the helper repeats itself every few seconds
        // and those repeats must not keep pushing the trip home further away.
        if (state != playState) {
            playState = state
            if (playing) showScene(NOW_PLAYING)
            lingerOnNowPlaying()
        }
    }

    /** With no music playing, the now-playing scene only stays for a while, then goes home. */
    private fun lingerOnNowPlaying() {
        root.removeCallbacks(backHome)
        if (scene == NOW_PLAYING && playState != "playing") {
            root.postDelayed(backHome, if (playState == "paused") PAUSED_LINGER_MS else STOPPED_LINGER_MS)
        }
    }

    private fun fetchArt(version: Long) {
        val link = link ?: return
        link.fetch("/art/$version/cover.jpg") { coverBytes ->
            link.fetch("/art/$version/backdrop.png") { backdropBytes ->
                // A newer track may have arrived while these were downloading.
                if (version == artVersion) {
                    nowPlaying.setArt(
                        coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
                        backdropBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
                    )
                }
            }
        }
    }

    /** Send the hub a JPEG of the glass as it is now. */
    private fun look(req: Long) {
        if (root.width == 0) return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        thread {
            val jpeg = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, jpeg)
            bitmap.recycle()
            link?.send(
                JSONObject().put("t", "look").put("req", req)
                    .put("jpeg", Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP))
            )
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        tama.watch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // API 23 has no WindowInsetsController; the legacy flags are the only way to go immersive.
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private companion object {
        const val DASHBOARD = 0
        const val NOW_PLAYING = 1
        /** Where the stage rests when nothing else wants it. */
        const val HOME = DASHBOARD
        const val SCENE_FADE_MS = 700L
        const val WEATHER_EVERY_MS = 20 * 60_000L
        const val PAUSED_LINGER_MS = 120_000L
        const val STOPPED_LINGER_MS = 10_000L
    }
}

package jp.co.xenocode.mado

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

class MainActivity : Activity(), HubLink.Listener {
    private lateinit var root: FrameLayout
    private lateinit var alerts: AlertView
    private lateinit var rail: RailView
    private lateinit var nowPlaying: NowPlayingView
    private lateinit var scenes: List<View>
    private var scene = CLOCK
    private var artVersion = -1L
    private var playState = ""
    private val backHome = Runnable { showScene(HOME) }
    private var link: HubLink? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        alerts = AlertView(this).apply {
            onDismissed = { alert, reason ->
                link?.send(JSONObject().put("t", "event").put("kind", "dismissed").put("id", alert.id).put("reason", reason))
            }
        }
        nowPlaying = NowPlayingView(this).apply {
            visibility = View.GONE
            fullCover = prefs().getBoolean("full_cover", false)
            onFullCover = { prefs().edit().putBoolean("full_cover", it).apply() }
            onPlayPause = { link?.send(JSONObject().put("t", "event").put("kind", "media").put("action", "playpause")) }
        }
        scenes = listOf(ClockView(this), nowPlaying)
        rail = RailView(this).apply {
            scenes = listOf("Clock", "Now playing")
            onScene = { index ->
                showScene(index)
                lingerOnNowPlaying()
            }
            brightness = prefs().getFloat("brightness", 1f)
            onBrightness = ::setBrightness
            // The frame has no Home key, so the rail is the way out to the launcher.
            onHome = {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        setBrightness(rail.brightness)
        root = FrameLayout(this).apply {
            scenes.forEach(::addView)
            addView(alerts)
            addView(rail)
        }
        setContentView(root)
        adoptHubAddress(intent)
        connect()
    }

    // `am start ... --es hub ws://host:7878/frame` points the frame at a hub; it is remembered.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (adoptHubAddress(intent)) connect()
    }

    override fun onDestroy() {
        link?.stop()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("mado", MODE_PRIVATE)

    private fun adoptHubAddress(intent: Intent?): Boolean {
        val hub = intent?.getStringExtra("hub") ?: return false
        prefs().edit().putString("hub", hub).apply()
        return true
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
        rail.linkUp = up
    }

    override fun onHubMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "notify" -> alerts.show(
                AlertView.Alert(
                    id = msg.optLong("id"),
                    level = msg.optString("level", "nudge"),
                    text = msg.optString("text"),
                    source = if (msg.isNull("source")) null else msg.optString("source"),
                    ttlMs = msg.optLong("ttl_s") * 1000,
                )
            )
            "now_playing" -> nowPlaying(msg)
            "look" -> look(msg.optLong("req"))
            // Kept for the scenes that will use it (weather places); nothing reads it yet.
            "config" -> prefs().edit().putString("config", msg.optJSONObject("config")?.toString()).apply()
        }
    }

    private fun showScene(index: Int) {
        if (index == scene) return
        val leaving = scenes[scene]
        val arriving = scenes[index]
        scene = index
        rail.activeScene = index
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
        const val CLOCK = 0
        const val NOW_PLAYING = 1
        /** Where the stage rests when nothing else wants it. The dashboard, once there is one. */
        const val HOME = CLOCK
        const val SCENE_FADE_MS = 700L
        const val PAUSED_LINGER_MS = 120_000L
        const val STOPPED_LINGER_MS = 10_000L
    }
}

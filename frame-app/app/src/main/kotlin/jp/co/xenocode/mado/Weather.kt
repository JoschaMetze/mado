package jp.co.xenocode.mado

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

/** What the sky is doing, in rising order of how wet you get. */
enum class Sky { CLEAR, CLOUD, FOG, RAIN, SNOW, STORM }

/** Today's weather for one place, already put into words. */
class Weather(
    val place: String, val tempC: Int, val highC: Int, val lowC: Int, val sentence: String, val sunset: String,
    /** IANA timezone of the place, for showing its local time. */
    val timezone: String,
    val isDay: Boolean,
    val sky: Sky = Sky.CLOUD,
    /** The hours and days ahead, for the weather page; null when the forecast had none. */
    val outlook: Outlook? = null,
) {
    val detail get() = "$tempC° now · $highC° high" + if (sunset.isEmpty()) "" else " · sunset $sunset"
}

/** One of the days ahead. `chance` is of rain or snow, in percent. */
class Day(val name: String, val code: Int, val highC: Int, val lowC: Int, val chance: Int)

/**
 * What the weather page draws beyond today's sentence. The hourly arrays start at the current
 * hour of the place's own day (`hour`) and run a day ahead; codes are WMO weather codes.
 */
class Outlook(
    val code: Int, val feelsC: Int, val windKmh: Int, val humidity: Int,
    /** Minutes into the place's day, -1 when unknown. `nextSunriseMin` is tomorrow's. */
    val sunriseMin: Int, val sunsetMin: Int, val nextSunriseMin: Int,
    val hour: Int, val temps: FloatArray, val codes: IntArray, val chance: IntArray, val light: BooleanArray,
    val days: List<Day>,
)

/**
 * The frame fetches its own forecast from Open-Meteo, so weather keeps working when the hub
 * is down. Android 6 does not trust Let's Encrypt, which signs Open-Meteo's certificate, so
 * this client carries the two ISRG roots itself (res/raw) and trusts nothing else.
 */
class WeatherSource(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val client: OkHttpClient

    init {
        val certificates = CertificateFactory.getInstance("X.509")
        val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).forEachIndexed { i, id ->
            context.resources.openRawResource(id).use { roots.setCertificateEntry("isrg$i", certificates.generateCertificate(it)) }
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(roots) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        client = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trust)
            // One request at a time. Two forecasts fetched in parallel crashed Android 6's
            // runtime (a race inside ART's interpreter, seen in debug builds).
            .dispatcher(Dispatcher().apply { maxRequests = 1 })
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** `done` runs on the main thread, with null if the forecast could not be had. */
    fun fetch(place: String, lat: Double, lon: Double, done: (Weather?) -> Unit) {
        // Two decimals is about a kilometre: plenty for a forecast, vaguer than an address.
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                "&current=temperature_2m,weather_code,is_day,apparent_temperature,wind_speed_10m,relative_humidity_2m" +
                "&hourly=weather_code,precipitation_probability,temperature_2m,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min,sunset,sunrise,weather_code,precipitation_probability_max" +
                "&timezone=auto&forecast_days=7",
            lat, lon,
        )
        client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { done(null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val weather = try {
                    response.use { if (it.isSuccessful) parse(place, JSONObject(it.body!!.string())) else null }
                } catch (e: Exception) {
                    null
                }
                main.post { done(weather) }
            }
        })
    }

    private fun parse(place: String, json: JSONObject): Weather {
        val current = json.getJSONObject("current")
        val hourly = json.getJSONObject("hourly")
        val daily = json.getJSONObject("daily")
        val codes = hourly.getJSONArray("weather_code")
        val chance = hourly.getJSONArray("precipitation_probability")
        // Hourly data starts at midnight local time, so the index is the hour of the day.
        val hour = current.getString("time").substringAfter('T').substringBefore(':').toInt()
        return Weather(
            place = place,
            tempC = current.getDouble("temperature_2m").roundToInt(),
            highC = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            lowC = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            sentence = Forecast.sentence(
                hour, current.optInt("is_day", 1) == 1, current.getInt("weather_code"),
                IntArray(codes.length()) { codes.optInt(it) },
                IntArray(chance.length()) { chance.optInt(it, 100) },
            ),
            sunset = daily.getJSONArray("sunset").optString(0).substringAfter('T', ""),
            timezone = json.optString("timezone"),
            isDay = current.optInt("is_day", 1) == 1,
            sky = Forecast.sky(current.getInt("weather_code")),
            // The page's extras are not worth losing today's weather over.
            outlook = try {
                outlook(current, hourly, daily, hour)
            } catch (e: Exception) {
                null
            },
        )
    }

    private fun outlook(current: JSONObject, hourly: JSONObject, daily: JSONObject, hour: Int): Outlook? {
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val chance = hourly.getJSONArray("precipitation_probability")
        val light = hourly.getJSONArray("is_day")
        val hours = minOf(25, temps.length() - hour)
        if (hours < 2) return null
        val dates = daily.getJSONArray("time")
        val dayCodes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val wet = daily.getJSONArray("precipitation_probability_max")
        val sunrise = daily.getJSONArray("sunrise")
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val weekday = SimpleDateFormat("EEE", Locale.US)
        return Outlook(
            code = current.getInt("weather_code"),
            feelsC = current.getDouble("apparent_temperature").roundToInt(),
            windKmh = current.getDouble("wind_speed_10m").roundToInt(),
            humidity = current.getInt("relative_humidity_2m"),
            sunriseMin = Daylight.minutes(sunrise.optString(0)),
            sunsetMin = Daylight.minutes(daily.getJSONArray("sunset").optString(0)),
            nextSunriseMin = Daylight.minutes(sunrise.optString(1)),
            hour = hour,
            temps = FloatArray(hours) { temps.getDouble(hour + it).toFloat() },
            codes = IntArray(hours) { codes.optInt(hour + it) },
            chance = IntArray(hours) { chance.optInt(hour + it, 0) },
            light = BooleanArray(hours) { light.optInt(hour + it, 1) == 1 },
            days = List(minOf(7, dates.length())) { i ->
                Day(
                    if (i == 0) "Today" else weekday.format(date.parse(dates.getString(i))!!),
                    dayCodes.optInt(i), highs.getDouble(i).roundToInt(), lows.getDouble(i).roundToInt(), wet.optInt(i, 0),
                )
            },
        )
    }
}

/** Turns hourly weather codes into the one line worth saying about the rest of today. */
object Forecast {
    private val words = arrayOf("twelve", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven")

    // WMO weather interpretation codes, as Open-Meteo reports them.
    fun sky(code: Int) = when (code) {
        0, 1 -> Sky.CLEAR
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLOUD
    }

    private fun wet(code: Int) = sky(code) >= Sky.RAIN

    private fun noun(code: Int) = when {
        code in 51..57 -> "Drizzle"
        code in 80..82 -> "Showers"
        sky(code) == Sky.SNOW -> "Snow"
        sky(code) == Sky.STORM -> "Thunderstorms"
        else -> "Rain"
    }

    private fun hourWord(hour: Int) = when (hour % 24) {
        0 -> "midnight"
        12 -> "noon"
        else -> words[hour % 12]
    }

    /**
     * @param hour the current hour, which is also the index of "now" in the hourly arrays
     * @param codes hourly weather codes from midnight today, ideally 48 of them
     * @param chance hourly chance of precipitation in percent, same indexing
     */
    fun sentence(hour: Int, isDay: Boolean, code: Int, codes: IntArray, chance: IntArray): String {
        // In the evening "the rest of today" is short, so look on through the night.
        val end = minOf(if (hour < 18) 23 else hour + 12, codes.size - 1)
        if (wet(code)) {
            val dry = (hour + 1..end).firstOrNull { !wet(codes[it]) && !wet(codes[minOf(it + 1, codes.size - 1)]) }
            return when {
                dry != null -> "${noun(code)} easing by ${hourWord(dry)}"
                hour < 12 -> "${noun(code)} all day"
                hour < 18 -> "${noun(code)} into the evening"
                else -> "${noun(code)} through the night"
            }
        }
        val rain = (hour + 1..end).firstOrNull { wet(codes[it]) && chance.getOrElse(it) { 100 } >= 40 }
        if (rain != null) {
            return when {
                rain <= 24 -> "${noun(codes[rain])} from ${hourWord(rain)}"
                rain % 24 < 6 -> "${noun(codes[rain])} overnight"
                rain % 24 < 9 -> "${noun(codes[rain])} by morning"
                else -> "${noun(codes[rain])} tomorrow"
            }
        }
        return when (sky(code)) {
            Sky.CLEAR -> if (!isDay) "Clear tonight" else if (hour < 15) "Clear all day" else "A clear evening ahead"
            Sky.FOG -> "Foggy, staying dry"
            else -> if (code == 2) "Some cloud, staying dry" else "Overcast, staying dry"
        }
    }
}

/** Where the sun or the moon is on its way across the sky. Times are minutes into the day. */
object Daylight {
    /** "2026-10-13T05:48" is 348; anything else is -1. */
    fun minutes(time: String): Int {
        val clock = time.substringAfter('T', "").split(':')
        val h = clock.getOrNull(0)?.toIntOrNull() ?: return -1
        val m = clock.getOrNull(1)?.toIntOrNull() ?: return -1
        return h * 60 + m
    }

    fun up(now: Int, sunrise: Int, sunset: Int) = now in sunrise until sunset

    /**
     * How far along its arc the sun is by day, or the moon by night: 0 rising, 1 setting. The
     * night is taken to run from today's sunset to a sunrise at the same time tomorrow.
     */
    fun along(now: Int, sunrise: Int, sunset: Int): Float {
        if (sunset <= sunrise) return 0.5f
        if (up(now, sunrise, sunset)) return (now - sunrise).toFloat() / (sunset - sunrise)
        val night = 1440 - (sunset - sunrise)
        return ((now - sunset + 1440) % 1440).toFloat() / night
    }

    /** Minutes until `then` comes round, today or tomorrow. */
    fun until(now: Int, then: Int) = (then - now + 1440) % 1440
}

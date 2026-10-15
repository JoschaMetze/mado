package jp.co.xenocode.mado

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * One meeting as the hub sends it. Times are instants, made from the hub's wall-clock strings
 * in its zone. The frame never sees attendees or bodies; `title` may already be "Busy".
 */
class AgendaEvent(
    val id: String, val title: String, val startMs: Long, val endMs: Long, val allDay: Boolean,
    /** busy, free, tentative, oof, workingElsewhere */
    val busy: String, val online: Boolean, val location: String,
    /** accepted, tentative, declined, none, organizer */
    val response: String, val mine: Boolean,
) {
    fun isOn(nowMs: Long) = !allDay && nowMs >= startMs && nowMs < endMs
    /** Free time and tentative replies are shown, but do not count as something to be at. */
    val counts get() = !allDay && busy != "free" && response != "declined"
}

/** The owner's calendar for today and the next days, as last heard from the hub. */
class Agenda(
    val events: List<AgendaEvent>, val zone: TimeZone, val titles: Boolean,
    val configured: Boolean, val signedIn: Boolean, val error: String?, val updatedMs: Long,
) {
    private val clock = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }

    fun time(ms: Long): String = clock.format(ms)

    /** Midnight of the day `ms` falls in, in the calendar's zone. */
    fun dayStart(ms: Long): Long {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun sameDay(a: Long, b: Long) = dayStart(a) == dayStart(b)

    /** Everything on the day of `dayMs`: all-day items first, then by start. */
    fun on(dayMs: Long): List<AgendaEvent> {
        val start = dayStart(dayMs)
        val end = start + DAY_MS
        return events.filter { e ->
            if (e.allDay) e.startMs < end && e.endMs > start else e.startMs >= start && e.startMs < end
        }.sortedWith(compareBy({ !it.allDay }, { it.startMs }))
    }

    /** The meeting on now that counts, if any. */
    fun current(nowMs: Long) = events.firstOrNull { it.counts && it.isOn(nowMs) }

    /** The next meeting today that counts and has not started. */
    fun next(nowMs: Long) = events.firstOrNull { it.counts && it.startMs > nowMs && sameDay(it.startMs, nowMs) }

    /** One line for the dashboard, or null when there is nothing left today. */
    fun line(nowMs: Long): String? {
        current(nowMs)?.let { return "NOW · ${it.title} · UNTIL ${time(it.endMs)}" }
        next(nowMs)?.let { e ->
            val mins = (e.startMs - nowMs) / 60_000
            val lead = if (mins < 1) "NOW" else if (mins < 60) "IN $mins MIN" else time(e.startMs)
            val place = if (e.location.isEmpty()) "" else " · ${e.location}"
            return "$lead · ${e.title}$place".uppercase()
        }
        return null
    }

    companion object {
        const val DAY_MS = 86_400_000L
        val EMPTY = Agenda(emptyList(), TimeZone.getDefault(), true, false, false, null, 0)

        fun parse(msg: JSONObject): Agenda {
            val zone = TimeZone.getTimeZone(msg.optString("tz").ifEmpty { TimeZone.getDefault().id })
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = zone }
            val items = msg.optJSONArray("events")
            val events = (0 until (items?.length() ?: 0)).mapNotNull { i ->
                val e = items!!.getJSONObject(i)
                val start = runCatching { fmt.parse(e.optString("start"))?.time }.getOrNull() ?: return@mapNotNull null
                val end = runCatching { fmt.parse(e.optString("end"))?.time }.getOrNull() ?: start
                AgendaEvent(
                    e.optString("id"), e.optString("title"), start, end, e.optBoolean("all_day"),
                    e.optString("busy", "busy"), e.optBoolean("online"), e.optString("location"),
                    e.optString("response", "none"), e.optBoolean("mine"),
                )
            }
            return Agenda(
                events, zone, msg.optBoolean("titles", true), msg.optBoolean("configured"), msg.optBoolean("signed_in"),
                if (msg.isNull("error")) null else msg.optString("error"), msg.optLong("updated") * 1000,
            )
        }

        /** A made-up day around `nowMs`, for looking at the layouts without a hub sign-in. */
        fun demo(nowMs: Long): Agenda {
            val zone = TimeZone.getDefault()
            val h = 3_600_000L
            val base = Agenda(emptyList(), zone, true, true, true, null, nowMs)
            val day = base.dayStart(nowMs)
            fun at(hour: Double, len: Double, title: String, place: String = "Teams", online: Boolean = true, busy: String = "busy", response: String = "accepted") =
                AgendaEvent(title, title, day + (hour * h).toLong(), day + ((hour + len) * h).toLong(), false, busy, online, place, response, false)
            val nowH = ((nowMs - day) / h.toDouble())
            val events = listOf(
                AgendaEvent("hol", "Kerstin away", day - Agenda.DAY_MS, day + 2 * Agenda.DAY_MS, true, "free", false, "", "none", true),
                at(8.75, 0.25, "Abstimmung hf bei enercity", "Zoom"),
                at(nowH - 0.3, 0.6, "Daily MAD"),
                at(nowH + 0.5, 0.5, "Erwartung KI Bewerber"),
                at(nowH + 1.5, 1.0, "Vorstellung Business Case"),
                at(nowH + 3.0, 0.5, "Focus time", "", false, "free"),
                at(nowH + 4.0, 1.0, "Kundentermin", "Room 2", false),
                AgendaEvent("t1", "Sprint review", day + Agenda.DAY_MS + 9 * h, day + Agenda.DAY_MS + 10 * h, false, "busy", true, "Teams", "accepted", false),
                AgendaEvent("t2", "Lunch with Anna", day + Agenda.DAY_MS + 12 * h, day + Agenda.DAY_MS + 13 * h, false, "busy", false, "Café Lotte", "organizer", true),
                AgendaEvent("t3", "1:1", day + Agenda.DAY_MS + 15 * h, day + Agenda.DAY_MS + (15.5 * h).toLong(), false, "tentative", true, "Teams", "tentative", false),
            )
            return Agenda(events, zone, true, true, true, null, nowMs)
        }
    }
}

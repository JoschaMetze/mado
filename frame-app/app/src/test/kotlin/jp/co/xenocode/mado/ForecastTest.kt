package jp.co.xenocode.mado

import org.junit.Assert.assertEquals
import org.junit.Test

class ForecastTest {
    private val clear = 0
    private val partly = 2
    private val overcast = 3
    private val drizzle = 53
    private val rain = 63
    private val showers = 81
    private val snow = 73
    private val storm = 95

    /** 48 hours of one code, with other codes laid over ranges of hours. */
    private fun hours(base: Int, vararg spans: Pair<IntRange, Int>) =
        IntArray(48) { h -> spans.firstOrNull { h in it.first }?.second ?: base }

    private val likely = IntArray(48) { 90 }

    @Test
    fun rainThatStopsNamesTheHour() {
        val codes = hours(clear, 0..14 to rain)
        assertEquals("Rain easing by three", Forecast.sentence(10, true, rain, codes, likely))
    }

    @Test
    fun aSingleDryHourIsNotAnEnd() {
        val codes = hours(rain, 12..12 to overcast, 16..47 to overcast)
        assertEquals("Rain easing by four", Forecast.sentence(10, true, rain, codes, likely))
    }

    @Test
    fun rainWithNoEndDependsOnTheTimeOfDay() {
        val codes = hours(rain)
        assertEquals("Rain all day", Forecast.sentence(9, true, rain, codes, likely))
        assertEquals("Rain into the evening", Forecast.sentence(14, true, rain, codes, likely))
        assertEquals("Rain through the night", Forecast.sentence(20, false, rain, codes, likely))
    }

    @Test
    fun rainLaterTodayNamesTheHourAndTheKind() {
        assertEquals("Showers from five", Forecast.sentence(9, true, partly, hours(partly, 17..19 to showers), likely))
        assertEquals("Snow from noon", Forecast.sentence(9, true, overcast, hours(overcast, 12..20 to snow), likely))
        assertEquals("Thunderstorms from midnight", Forecast.sentence(19, false, clear, hours(clear, 24..26 to storm), likely))
        assertEquals("Drizzle from seven", Forecast.sentence(6, true, overcast, hours(overcast, 7..9 to drizzle), likely))
    }

    @Test
    fun rainAfterMidnightIsOnlySeenFromTheEvening() {
        val codes = hours(clear, 27..30 to rain)
        assertEquals("Clear all day", Forecast.sentence(10, true, clear, codes, likely))
        assertEquals("Rain overnight", Forecast.sentence(19, false, clear, codes, likely))
        assertEquals("Showers by morning", Forecast.sentence(20, false, clear, hours(clear, 31..33 to showers), likely))
    }

    @Test
    fun unlikelyRainIsNotAnnounced() {
        val codes = hours(partly, 15..17 to rain)
        val chance = IntArray(48) { 20 }
        assertEquals("Some cloud, staying dry", Forecast.sentence(9, true, partly, codes, chance))
    }

    @Test
    fun dryDays() {
        assertEquals("Clear all day", Forecast.sentence(9, true, clear, hours(clear), likely))
        assertEquals("A clear evening ahead", Forecast.sentence(16, true, clear, hours(clear), likely))
        assertEquals("Clear tonight", Forecast.sentence(22, false, clear, hours(clear), likely))
        assertEquals("Overcast, staying dry", Forecast.sentence(9, true, overcast, hours(overcast), likely))
        assertEquals("Foggy, staying dry", Forecast.sentence(7, true, 45, hours(45), likely))
    }

    @Test
    fun aShortForecastDoesNotCrash() {
        assertEquals("Rain through the night", Forecast.sentence(22, false, rain, IntArray(24) { rain }, IntArray(24) { 90 }))
    }

    @Test
    fun aLongerForecastSaysTheSameThing() {
        val week = IntArray(168) { h -> if (h in 17..19) showers else partly }
        assertEquals("Showers from five", Forecast.sentence(9, true, partly, week, IntArray(168) { 90 }))
    }

    @Test
    fun clockTimesBecomeMinutes() {
        assertEquals(348, Daylight.minutes("2026-10-13T05:48"))
        assertEquals(-1, Daylight.minutes(""))
        assertEquals(-1, Daylight.minutes("2026-10-13"))
    }

    @Test
    fun theSunCrossesByDayAndTheMoonByNight() {
        val rise = 6 * 60
        val set = 18 * 60
        assertEquals(0f, Daylight.along(rise, rise, set), 0.001f)
        assertEquals(0.5f, Daylight.along(12 * 60, rise, set), 0.001f)
        assertEquals(0f, Daylight.along(set, rise, set), 0.001f)
        assertEquals(0.5f, Daylight.along(0, rise, set), 0.001f)
        assertEquals(0.75f, Daylight.along(3 * 60, rise, set), 0.001f)
        // A forecast with no sun times puts it overhead rather than dividing by zero.
        assertEquals(0.5f, Daylight.along(600, -1, -1), 0.001f)
    }

    @Test
    fun timeUntilSunriseWrapsPastMidnight() {
        assertEquals(30, Daylight.until(17 * 60, 17 * 60 + 30))
        assertEquals(7 * 60, Daylight.until(23 * 60, 6 * 60))
    }
}

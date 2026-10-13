package jp.co.xenocode.mado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightTest {
    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun theNightRunsPastMidnight() {
        val night = NightClock()
        assertFalse(night.asleep(at(22, 59)))
        assertTrue(night.asleep(at(23)))
        assertTrue(night.asleep(at(3)))
        assertFalse(night.asleep(at(7)))
    }

    @Test
    fun aNightInsideOneDayWorksToo() {
        val night = NightClock().apply {
            from = at(1)
            to = at(5)
        }
        assertFalse(night.asleep(at(0, 30)))
        assertTrue(night.asleep(at(2)))
        assertFalse(night.asleep(at(5)))
    }

    @Test
    fun switchedOffItNeverSleepsByItself() {
        val night = NightClock().apply { enabled = false }
        assertFalse(night.asleep(at(3)))
        night.force(true, at(3))
        assertTrue(night.asleep(at(3, 1)))
    }

    @Test
    fun sleepNowLastsUntilMorning() {
        val night = NightClock()
        assertFalse(night.asleep(at(15)))
        night.force(true, at(15))
        assertTrue(night.asleep(at(15, 1)))
        assertTrue(night.asleep(at(23, 30)))
        assertFalse(night.asleep(at(7)))
        assertFalse(night.asleep(at(15)))
    }

    @Test
    fun wakingAtNightLastsUntilTheNextEvening() {
        val night = NightClock()
        assertTrue(night.asleep(at(1)))
        night.force(false, at(1))
        assertFalse(night.asleep(at(1, 1)))
        assertFalse(night.asleep(at(12)))
        assertTrue(night.asleep(at(23)))
    }

    @Test
    fun askingForWhatTheHoursAlreadySayChangesNothing() {
        val night = NightClock()
        assertTrue(night.asleep(at(2)))
        night.force(false, at(2))
        night.force(true, at(2, 5))
        assertTrue(night.asleep(at(2, 6)))
        assertFalse(night.asleep(at(7)))
    }

    @Test
    fun timesOfDayAreRead() {
        assertEquals(1410, NightClock.minutes("23:30"))
        assertEquals(0, NightClock.minutes("00:00"))
        assertNull(NightClock.minutes("24:00"))
        assertNull(NightClock.minutes("7"))
        assertNull(NightClock.minutes(""))
    }

    @Test
    fun theGlassDimsAfterSunsetAndBrightensBySunrise() {
        val rise = at(6)
        val set = at(18)
        assertEquals(1f, Evening.dim(at(12), rise, set), 0.001f)
        assertEquals(1f, Evening.dim(at(18), rise, set), 0.001f)
        assertEquals(0.8f, Evening.dim(at(18, 20), rise, set), 0.001f)
        assertEquals(Evening.DIM, Evening.dim(at(23), rise, set), 0.001f)
        assertEquals(0.8f, Evening.dim(at(5, 40), rise, set), 0.001f)
        assertEquals(1f, Evening.dim(at(6), rise, set), 0.001f)
        // Without sun times there is nothing to go by.
        assertEquals(1f, Evening.dim(at(23), -1, -1), 0.001f)
    }
}

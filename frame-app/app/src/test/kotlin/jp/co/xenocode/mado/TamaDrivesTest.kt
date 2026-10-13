package jp.co.xenocode.mado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaDrivesTest {
    private val noLuck = { _: Hobby -> 0f }

    @Test
    fun tiredMeansNap() {
        val d = TamaDrives().apply { energy = 0.05f }
        assertEquals(Hobby.NAP, d.choose(null, false, noLuck))
    }

    @Test
    fun lonelyMeansPeek() {
        val d = TamaDrives().apply { lonely = 1f }
        assertEquals(Hobby.PEEK, d.choose(null, false, noLuck))
    }

    @Test
    fun doesNotRepeatItselfWithoutGoodReason() {
        val d = TamaDrives().apply { boredom = 0.8f }
        val first = d.choose(null, false, noLuck)
        assertTrue(first != d.choose(first, false, noLuck))
    }

    @Test
    fun nightIsForSleeping() {
        val d = TamaDrives()
        assertTrue(d.restless(night = true))
        assertEquals(Hobby.NAP, d.choose(Hobby.NAP, true, noLuck))
    }

    @Test
    fun aNapRestoresEnergyAndUrgesStayInRange() {
        val d = TamaDrives().apply { energy = 0f }
        d.tick(10_000f, moving = false, asleep = true, occupied = false)
        assertEquals(1f, d.energy, 0f)
        d.tick(100_000f, moving = true, asleep = false, occupied = false)
        assertEquals(0f, d.energy, 0f)
        assertEquals(1f, d.boredom, 0f)
    }

    @Test
    fun aHobbySettlesIt() {
        val d = TamaDrives().apply { boredom = 0.5f; energy = 0.8f; lonely = 0f; curiosity = 0f }
        assertTrue(d.restless(false))
        d.did(Hobby.PEBBLES)
        assertFalse(d.restless(false))
    }
}

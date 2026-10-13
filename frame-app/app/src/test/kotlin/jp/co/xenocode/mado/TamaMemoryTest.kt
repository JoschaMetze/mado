package jp.co.xenocode.mado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaMemoryTest {
    @Test
    fun anArtistBecomesAFavouriteAfterEnoughPlays() {
        val m = TamaMemory()
        repeat(4) { assertFalse(m.heard("Low Tide Radio")) }
        assertTrue(m.heard("Low Tide Radio"))
        assertFalse(m.heard("Someone New"))
        assertFalse(m.heard("  "))
    }

    @Test
    fun onlyTheMostPlayedAreFavourites() {
        val m = TamaMemory()
        for (name in listOf("a", "b", "c")) repeat(10) { m.heard(name) }
        repeat(4) { m.heard("d") }
        assertFalse(m.heard("d"))
        assertTrue(m.heard("a"))
    }

    @Test
    fun aSpotBecomesAHabitOnlyAfterAWhile() {
        val m = TamaMemory()
        m.rested(0.9f, 0.9f, 300)
        assertNull(m.favouriteSpot())
        m.rested(0.9f, 0.9f, 400)
        m.rested(0.1f, 0.1f, 200)
        val (x, y) = m.favouriteSpot()!!
        assertEquals(0.9375f, x, 0.001f)
        assertEquals(0.9f, y, 0.001f)
    }

    @Test
    fun itSurvivesBeingWrittenDownAndReadBack() {
        val m = TamaMemory()
        m.rested(0.5f, 0.5f, 900)
        repeat(6) { m.heard("Tab\tand\nnewline") }
        val back = TamaMemory().apply { restore(m.spotsText(), m.artistsText()) }
        assertEquals(m.favouriteSpot(), back.favouriteSpot())
        assertTrue(back.heard("Tab and newline"))
        TamaMemory().restore("nonsense", "more\nnonsense")
    }
}

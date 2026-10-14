package jp.co.xenocode.mado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TamaNeedsTest {
    private val random = Random(1)

    @Test
    fun hungerEmptiesButNeverBelowZero() {
        val n = TamaNeeds()
        repeat(100) { n.tick(3600f, false, false, random) }
        assertEquals(0f, n.fullness)
        assertTrue(n.hungry)
    }

    @Test
    fun aFullTummySaysNo() {
        val n = TamaNeeds().apply { fullness = 0.95f }
        assertEquals(TamaNeeds.Fed.STUFFED, n.feed(Food.DANGO, random))
    }

    @Test
    fun aTummyAcheCannotFaceFood() {
        val n = TamaNeeds().apply { fullness = 0.2f }
        n.catch(Sickness.TUMMY)
        assertEquals(TamaNeeds.Fed.TOO_POORLY, n.feed(Food.DANGO, random))
    }

    @Test
    fun medicineWorksInMinutesAndRestInHours() {
        val n = TamaNeeds()
        n.catch(Sickness.SNIFFLES)
        n.tick(3600f, false, false, random)
        assertEquals(Sickness.SNIFFLES, n.sickness)
        n.medicine()
        n.tick(100f, false, false, random)
        assertEquals(null, n.sickness)

        n.catch(Sickness.HICCUPS)
        repeat(4) { n.tick(3600f, false, false, random) }
        assertEquals(null, n.sickness)
    }

    @Test
    fun itLearnsAFavourite() {
        val n = TamaNeeds()
        repeat(3) {
            n.fullness = 0f
            n.sinceFed = 1e6f
            n.feed(Food.TAIYAKI, random)
        }
        assertEquals(Food.TAIYAKI, n.favourite())
        n.fullness = 0f
        assertEquals(TamaNeeds.Fed.LOVED, n.feed(Food.TAIYAKI, random))
    }

    @Test
    fun savedNeedsComeBack() {
        val n = TamaNeeds().apply { fullness = 0.4f }
        n.catch(Sickness.GLITCH)
        val out = Saved()
        n.save(out)
        val back = TamaNeeds()
        back.restore(Saved(out.text()))
        assertEquals(0.4f, back.fullness)
        assertEquals(Sickness.GLITCH, back.sickness)
    }
}

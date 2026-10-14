package jp.co.xenocode.mado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuestTest {
    @Test
    fun aLongRunNeverGoesWrong() {
        val q = Quest(Random(7))
        // Two weeks of beats, with nobody watching.
        repeat(20_000) {
            q.tick(Quest.BEAT_S)
            assertTrue(q.hp >= 0)
            assertTrue(q.hp <= q.maxHp())
            assertTrue(q.lines.isNotEmpty())
            assertTrue(q.lines.none { "{" in it })
        }
        println("days ${20_000 * Quest.BEAT_S / 86400} level ${q.level} quests ${q.questsDone} faints ${q.faints} befriended ${q.befriended} gold ${q.gold} gear ${q.gear.values.map { it.label }} allies ${q.allies.map { it.name }}")
        assertTrue(q.level > 5)
        assertTrue(q.questsDone > 50)
        assertTrue("faints should be rare", q.faints < q.questsDone)
    }

    @Test
    fun nightPausesAndMorningResumes() {
        val q = Quest(Random(1))
        repeat(10) { q.beat() }
        val room = q.roomIndex
        q.night = true
        repeat(50) { q.beat() }
        assertEquals(Quest.Phase.NIGHT, q.phase)
        assertEquals(room, q.roomIndex)
        q.night = false
        q.beat()
        assertFalse(q.phase == Quest.Phase.NIGHT)
    }

    @Test
    fun sickTamaRestsAtAnInnUntilBetter() {
        val q = Quest(Random(2))
        repeat(5) { q.beat() }
        q.needs.catch(Sickness.SNIFFLES)
        q.beat()
        assertEquals(Quest.Phase.INN, q.phase)
        assertTrue(q.medicine())
        q.tick(200f)
        assertEquals(null, q.needs.sickness)
        q.beat()
        assertFalse(q.phase == Quest.Phase.INN)
    }

    @Test
    fun starvingTamaFeedsItself() {
        val q = Quest(Random(3))
        q.needs.fullness = 0f
        repeat(3) { q.beat() }
        assertTrue(q.needs.fullness > 0.2f)
    }

    @Test
    fun saveAndRestoreKeepTheStory() {
        val q = Quest(Random(4))
        repeat(500) { q.beat() }
        val r = Quest(Random(5))
        r.restore(q.save())
        assertEquals(q.level, r.level)
        assertEquals(q.xp, r.xp)
        assertEquals(q.title, r.title)
        assertEquals(q.realm, r.realm)
        assertEquals(q.gear.keys, r.gear.keys)
        assertEquals(q.hat, r.hat)
        assertEquals(q.lines, r.lines)
        assertEquals(q.save(), r.save())
        repeat(100) { r.beat() }
    }

    @Test
    fun cheeringOnlyCountsInAFight() {
        val q = Quest(Random(6))
        q.beat()
        assertFalse(q.cheer())
    }
}

package jp.co.xenocode.mado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageLogTest {
    private fun fates(log: MessageLog) = log.all.map { it.fate }

    @Test
    fun eachWayOffTheGlassIsToldApart() {
        val log = MessageLog()
        log.arrived(1, "nudge", "tapped", null, 0, false)
        log.arrived(2, "nudge", "left alone", null, 0, false)
        log.arrived(3, "whisper", "never opened", null, 0, false)
        log.arrived(4, "whisper", "opened, then left alone", null, 0, false)
        log.left(1, "nudge", "tap")
        log.left(2, "nudge", "expired")
        log.left(3, "whisper", "expired")
        log.left(4, "nudge", "expired")
        assertEquals(listOf("read", "passed", "unopened", "read"), fates(log))
    }

    @Test
    fun aNightsMessageWaitsThenIsShown() {
        val log = MessageLog()
        log.arrived(1, "nudge", "in the night", null, 0, true)
        assertEquals(listOf("held"), fates(log))
        log.released(1)
        log.left(1, "nudge", "tap")
        assertEquals(listOf("read"), fates(log))
    }

    @Test
    fun anIdUsedAgainMeansTheNewestMessage() {
        val log = MessageLog()
        log.arrived(1, "nudge", "before the hub restarted", null, 0, false)
        log.left(1, "nudge", "expired")
        log.arrived(1, "nudge", "after", null, 0, false)
        log.left(1, "nudge", "tap")
        assertEquals(listOf("passed", "read"), fates(log))
    }

    @Test
    fun onlyTheNewestAreKept() {
        val log = MessageLog()
        repeat(MessageLog.MAX + 5) { log.arrived(it.toLong(), "nudge", "n$it", null, 0, false) }
        assertEquals(MessageLog.MAX, log.all.size)
        assertEquals("n5", log.all.first().text)
    }

    @Test
    fun itSurvivesBeingWrittenDownAndReadBack() {
        val log = MessageLog()
        log.arrived(7, "urgent", "two\nlines\tand a \\ slash", "ci", 1_760_000_000_000, false)
        log.arrived(8, "nudge", "still on the glass", null, 5, false)
        log.left(7, "urgent", "tap")
        val back = MessageLog().apply { restore(log.text()) }
        val first = back.all[0]
        assertEquals("two\nlines\tand a \\ slash", first.text)
        assertEquals("ci", first.source)
        assertEquals(1_760_000_000_000, first.at)
        assertEquals("read", first.fate)
        assertNull(back.all[1].source)
        // It was waiting when the app stopped, so nobody saw it.
        assertEquals("lost", back.all[1].fate)
        MessageLog().restore("nonsense\nmore\tnonsense")
        MessageLog().restore(null)
    }
}

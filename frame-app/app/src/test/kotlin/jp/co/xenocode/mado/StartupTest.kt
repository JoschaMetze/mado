package jp.co.xenocode.mado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTest {
    private val hub = "ws://frame-hub.example:7878/frame"

    @Test
    fun withoutAHubItLooksForOne() {
        val s = startup(null, linkUp = false, clockSet = true)
        assertEquals("Looking for a hub", s.title)
        assertTrue(s.busy)
    }

    @Test
    fun severalHubsWaitForAPerson() {
        val s = startup(null, linkUp = false, clockSet = true, found = 2)
        assertEquals("Choose a hub", s.title)
        assertFalse(s.busy)
    }

    @Test
    fun itNamesTheHubItIsCalling() {
        val s = startup(hub, linkUp = false, clockSet = false)
        assertEquals("Connecting to the hub", s.title)
        assertEquals("Calling frame-hub.example:7878", s.detail)
        assertTrue(s.busy)
    }

    @Test
    fun theClockComesBeforeThePainting() {
        assertEquals("Setting the clock", startup(hub, linkUp = true, clockSet = false).title)
        assertEquals("Fetching today's painting", startup(hub, linkUp = true, clockSet = true).title)
    }
}

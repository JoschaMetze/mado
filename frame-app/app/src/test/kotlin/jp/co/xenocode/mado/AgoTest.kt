package jp.co.xenocode.mado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgoTest {
    @Test
    fun agesReadAsWords() {
        assertEquals("just now", ago(30))
        assertEquals("5 min ago", ago(5 * 60 + 10))
        assertEquals("3 h ago", ago(3 * 3600 + 59 * 60))
        assertEquals("yesterday", ago(30 * 3600))
        assertEquals("4 days ago", ago(4 * 86_400))
    }

    @Test
    fun aClockBehindTheHubSaysNothing() {
        assertNull(ago(-1_700_000_000))
    }
}

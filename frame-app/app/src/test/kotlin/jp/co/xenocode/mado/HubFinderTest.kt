package jp.co.xenocode.mado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubFinderTest {
    @Test
    fun anAnnouncedHubBecomesAnAddress() {
        assertEquals("ws://192.0.2.7:7879/frame", hubUrl("192.0.2.7", 7879, "/frame"))
        assertEquals("ws://192.0.2.7:7878/frame", hubUrl("192.0.2.7", 7878, null))
        assertEquals("ws://192.0.2.7:7878/elsewhere", hubUrl("192.0.2.7", 7878, "elsewhere"))
    }

    @Test
    fun aTypedAddressIsFilledIn() {
        assertEquals("ws://192.0.2.7:7878/frame", typedHubUrl(" 192.0.2.7 "))
        assertEquals("ws://hub.example:7879/frame", typedHubUrl("hub.example:7879"))
        assertEquals("ws://hub.example:7878/frame", typedHubUrl("http://hub.example/"))
        assertEquals("ws://hub.example:7900/frame", typedHubUrl("ws://hub.example:7900/frame"))
    }

    @Test
    fun nothingTypedIsNothingToDial() {
        assertNull(typedHubUrl("   "))
        assertNull(typedHubUrl("two words"))
        assertNull(typedHubUrl(":7878"))
    }
}

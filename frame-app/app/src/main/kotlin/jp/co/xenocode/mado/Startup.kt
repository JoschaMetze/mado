package jp.co.xenocode.mado

/**
 * What the dashboard says where a painting's label will be, until the first painting is up.
 * `busy` is false when nothing is on its way, so nothing should look as if it were.
 */
class Startup(val title: String, val detail: String, val busy: Boolean)

/** The clock reads 1970 after every power-up until the network sets it: any time before 2024 is that. */
const val CLOCK_SET_AFTER_MS = 1_704_067_200_000L

/**
 * `hub` is the saved address (`ws://host:7878/frame`), null when the frame has none yet; then
 * it is looking for hubs on the network, and `found` is how many it has seen.
 */
fun startup(hub: String?, linkUp: Boolean, clockSet: Boolean, found: Int = 0): Startup {
    val host = hub?.substringAfter("://")?.substringBefore("/")
    return when {
        host == null && found == 0 -> Startup("Looking for a hub", "Searching · or type its address from the rail", true)
        host == null && found == 1 -> Startup("Found a hub", "Connecting", true)
        // Which one is for a person to say.
        host == null -> Startup("Choose a hub", "$found found · open the rail and tap the hub", false)
        !linkUp -> Startup("Connecting to the hub", "Calling $host", true)
        // The frame asks for a painting by date, so it cannot ask before it knows the date.
        !clockSet -> Startup("Setting the clock", "Connected to the hub · waiting for the time", true)
        else -> Startup("Fetching today's painting", "Connected to the hub", true)
    }
}

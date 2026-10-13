package jp.co.xenocode.mado

/** The things Tama does to pass the time. */
enum class Hobby { FOG, PEBBLES, MOTE, NAP, PEEK, PERCH }

/**
 * What Tama feels like doing. Four urges, each 0..1, drift with time and are eased by the
 * hobby that answers them; the strongest urge, with some luck mixed in, picks the next hobby.
 */
class TamaDrives {
    var energy = 0.8f
    var boredom = 0.4f
    var lonely = 0.2f
    var curiosity = 0.5f

    fun tick(dt: Float, moving: Boolean, asleep: Boolean, occupied: Boolean) {
        if (asleep) {
            energy += dt / NAP_S
        } else {
            energy -= dt / AWAKE_S + if (moving) dt / ROAMING_S else 0f
            boredom += if (occupied) -dt / 60f else dt / 420f
            lonely += dt / 2400f
            curiosity += dt / 900f
        }
        clamp()
    }

    /** It was poked, stroked or carried. */
    fun touched() {
        lonely -= 0.5f
        boredom -= 0.3f
        clamp()
    }

    /** Someone touched the glass somewhere else: at least there is company. */
    fun company() {
        lonely -= 0.1f
        clamp()
    }

    /** Something on the glass changed. */
    fun noticed() {
        curiosity += 0.3f
        clamp()
    }

    /** The frame was off for this many hours: it comes back rested, and has missed company. */
    fun away(hours: Float) {
        if (hours >= 1f) energy = 1f
        lonely += hours * 0.2f
        boredom = 0.4f
        clamp()
    }

    fun did(hobby: Hobby) {
        when (hobby) {
            Hobby.FOG -> { boredom -= 0.6f; curiosity -= 0.3f }
            Hobby.PEBBLES -> boredom -= 0.7f
            Hobby.MOTE -> { curiosity -= 0.8f; boredom -= 0.3f; energy -= 0.1f }
            Hobby.PEEK -> lonely -= 0.7f
            Hobby.PERCH -> { boredom -= 0.2f; energy += 0.1f }
            Hobby.NAP -> {}
        }
        clamp()
    }

    /** Whether any urge is strong enough to start something. At night the only thing to do is sleep. */
    fun restless(night: Boolean) = night || boredom > 0.35f || energy < 0.25f || lonely > 0.7f || curiosity > 0.9f

    /** `luck` gives each hobby a number 0..1; the one it did last counts for less. */
    fun choose(last: Hobby?, night: Boolean, luck: (Hobby) -> Float): Hobby {
        if (night) return Hobby.NAP
        return Hobby.values().maxByOrNull { (urge(it) + luck(it) * 0.3f) * if (it == last) 0.4f else 1f }!!
    }

    private fun urge(hobby: Hobby) = when (hobby) {
        Hobby.NAP -> (1f - energy) * 1.5f - 0.3f
        Hobby.FOG -> 0.25f + boredom * 0.5f + curiosity * 0.2f
        Hobby.PEBBLES -> 0.2f + boredom * 0.6f
        Hobby.MOTE -> curiosity * 0.8f + energy * 0.2f - 0.1f
        Hobby.PEEK -> lonely * 1.3f
        Hobby.PERCH -> 0.3f + (1f - energy) * 0.2f
    }

    private fun clamp() {
        energy = energy.coerceIn(0f, 1f)
        boredom = boredom.coerceIn(0f, 1f)
        lonely = lonely.coerceIn(0f, 1f)
        curiosity = curiosity.coerceIn(0f, 1f)
    }

    private companion object {
        /** Seconds for a nap to fill it up, and for a day of sitting or roaming to drain it. */
        const val NAP_S = 600f
        const val AWAKE_S = 7200f
        const val ROAMING_S = 1800f
    }
}

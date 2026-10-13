package jp.co.xenocode.mado

/**
 * What Tama remembers from one day to the next: where on the glass it likes to sit, and whose
 * music it has heard most. Plain data with its own text form, so it can live in preferences.
 */
class TamaMemory {
    private val spots = IntArray(COLS * ROWS)
    private val artists = LinkedHashMap<String, Int>()

    /** It sat still for a while at this point, given as fractions of the glass. */
    fun rested(fx: Float, fy: Float, seconds: Int) {
        val cell = (fy * ROWS).toInt().coerceIn(0, ROWS - 1) * COLS + (fx * COLS).toInt().coerceIn(0, COLS - 1)
        spots[cell] += seconds
        // Old habits fade, so a new favourite can take over.
        if (spots[cell] > FADE_AT_S) for (i in spots.indices) spots[i] /= 2
    }

    /** The middle of the spot it has rested in most, as fractions of the glass; null until it has one. */
    fun favouriteSpot(): Pair<Float, Float>? {
        val best = spots.indices.maxByOrNull { spots[it] } ?: return null
        if (spots[best] < HABIT_S) return null
        return (best % COLS + 0.5f) / COLS to (best / COLS + 0.5f) / ROWS
    }

    /** A track by this artist started. True when the artist is one of its favourites. */
    fun heard(artist: String): Boolean {
        val name = artist.replace('\t', ' ').replace('\n', ' ').trim()
        if (name.isEmpty()) return false
        val plays = (artists[name] ?: 0) + 1
        artists[name] = plays
        if (artists.size > MAX_ARTISTS) artists.remove(artists.minByOrNull { it.value }!!.key)
        return plays >= FAVOURITE_PLAYS && artists.values.count { it > plays } < FAVOURITES
    }

    fun spotsText() = spots.joinToString(",")

    fun artistsText() = artists.entries.joinToString("\n") { "${it.value}\t${it.key}" }

    fun restore(spotsText: String?, artistsText: String?) {
        spotsText?.split(',')?.mapNotNull(String::toIntOrNull)?.takeIf { it.size == spots.size }?.forEachIndexed { i, v -> spots[i] = v }
        artistsText?.lines()?.forEach { line ->
            val plays = line.substringBefore('\t').toIntOrNull() ?: return@forEach
            if ('\t' in line) artists[line.substringAfter('\t')] = plays
        }
    }

    private companion object {
        const val COLS = 8
        const val ROWS = 5
        /** Seconds in one spot before it counts as a habit, and at which all habits are halved. */
        const val HABIT_S = 600
        const val FADE_AT_S = 40_000
        const val MAX_ARTISTS = 60
        const val FAVOURITE_PLAYS = 5
        const val FAVOURITES = 3
    }
}

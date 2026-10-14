package jp.co.xenocode.mado

import kotlin.random.Random

/** Things Tama can catch. None of them is serious, and all of them pass. */
enum class Sickness(val label: String, val title: String) {
    SNIFFLES("the sniffles", "Sniffles"),
    TUMMY("a tummy ache", "Tummy ache"),
    HICCUPS("the hiccups", "Hiccups"),
    /** Caught on quests in the arcade: a little of the pixels came home with it. */
    GLITCH("a glitch", "Glitched"),
}

/** What can be put in Tama's bowl from this side of the glass. */
enum class Food(val label: String) {
    ONIGIRI("an onigiri"),
    DANGO("a stick of dango"),
    TAIYAKI("a taiyaki"),
    MELONPAN("a melon pan"),
    STRAWBERRY("a strawberry"),
    PUDDING("a purin"),
}

/**
 * Tama's body, as a small pet's: how full it is and whether it is poorly. It gets hungry, it
 * can be fed too much, it catches things, and it always gets better. Nothing here can end
 * badly; the worst a neglected Tama does is mope, and on its quests it forages for itself.
 */
class TamaNeeds {
    /** 1 just fed, 0 empty. */
    var fullness = 0.8f
    var sickness: Sickness? = null
    /** Seconds until it is better. Medicine makes this short. */
    var sickLeft = 0f
    var medicated = false
    /** Seconds left of feeling well fed, after a meal from this side of the glass. */
    var wellFed = 0f
    /** Seconds since the last meal from this side of the glass, for telling a snack from a feast. */
    var sinceFed = 1e6f
    /** Its favourite, learned from what it was given most. */
    private val eaten = IntArray(Food.values().size)

    val hungry get() = fullness < HUNGRY
    /** So hungry that, on a quest, it will eat whatever it is carrying. */
    val starving get() = fullness < STARVING

    enum class Fed { ATE, LOVED, STUFFED, TOO_POORLY }

    /**
     * Time passes. `asleep` slows hunger to a third; `chilly` (cold or wet out) and an empty
     * tummy make catching something likelier. Returns the sickness if it caught one just now.
     */
    fun tick(dt: Float, asleep: Boolean, chilly: Boolean, random: Random): Sickness? {
        fullness = (fullness - dt / EMPTY_S * if (asleep) 0.33f else 1f).coerceIn(0f, 1f)
        wellFed = (wellFed - dt).coerceAtLeast(0f)
        sinceFed += dt
        val ill = sickness
        if (ill != null) {
            sickLeft -= dt
            if (sickLeft <= 0f) {
                sickness = null
                medicated = false
            }
            return null
        }
        val perHour = BASE_RISK + (if (fullness < 0.15f) HUNGRY_RISK else 0f) + (if (chilly) CHILLY_RISK else 0f)
        if (random.nextFloat() < perHour * dt / 3600f) {
            val caught = if (chilly && random.nextBoolean()) Sickness.SNIFFLES else listOf(Sickness.SNIFFLES, Sickness.HICCUPS, Sickness.TUMMY).random(random)
            catch(caught)
            return caught
        }
        return null
    }

    fun catch(what: Sickness) {
        if (sickness != null) return
        sickness = what
        sickLeft = NATURAL_RECOVERY_S
        medicated = false
    }

    /**
     * Something to eat from this side of the glass. Full up, it turns it down; poorly with a
     * tummy ache, it cannot face it. A second big meal close behind the first can upset it.
     */
    fun feed(food: Food, random: Random): Fed {
        if (sickness == Sickness.TUMMY) return Fed.TOO_POORLY
        if (fullness > STUFFED) return Fed.STUFFED
        val greedy = sinceFed < GREEDY_S && fullness > 0.55f
        fullness = (fullness + MEAL).coerceAtMost(1f)
        wellFed = WELL_FED_S
        sinceFed = 0f
        val favourite = favourite()
        eaten[food.ordinal]++
        if (greedy && random.nextFloat() < 0.35f) catch(Sickness.TUMMY)
        return if (food == favourite) Fed.LOVED else Fed.ATE
    }

    /** Medicine from this side of the glass: better in a minute or two. False if it is well. */
    fun medicine(): Boolean {
        if (sickness == null) return false
        if (!medicated) sickLeft = minOf(sickLeft, MEDICINE_S)
        medicated = true
        return true
    }

    /** Something it found on a quest. */
    fun snack(amount: Float) {
        fullness = (fullness + amount).coerceAtMost(1f)
    }

    /** The food it has been given most, once it has had a few meals. */
    fun favourite(): Food? {
        val best = eaten.indices.maxByOrNull { eaten[it] } ?: return null
        return if (eaten[best] >= 3) Food.values()[best] else null
    }

    /** What the next meal from this side of the glass will be: breakfast-ish things in the morning. */
    fun nextFood(hour: Int, random: Random): Food {
        val fav = favourite()
        if (fav != null && random.nextInt(3) == 0) return fav
        return when (hour) {
            in 5..10 -> listOf(Food.ONIGIRI, Food.MELONPAN, Food.STRAWBERRY)
            in 14..17 -> listOf(Food.TAIYAKI, Food.PUDDING, Food.DANGO)
            else -> Food.values().toList()
        }.random(random)
    }

    fun save(out: Saved) {
        out.put("full", fullness)
        out.put("sick", sickness?.name ?: "")
        out.put("sick_left", sickLeft)
        out.put("medicated", medicated)
        out.put("well_fed", wellFed)
        out.put("since_fed", sinceFed)
        out.put("eaten", eaten.joinToString(","))
    }

    fun restore(from: Saved) {
        fullness = from.float("full", fullness).coerceIn(0f, 1f)
        sickness = Sickness.values().firstOrNull { it.name == from.string("sick") }
        sickLeft = from.float("sick_left", 0f)
        medicated = from.bool("medicated")
        wellFed = from.float("well_fed", 0f)
        sinceFed = from.float("since_fed", 1e6f)
        from.string("eaten")?.split(',')?.mapNotNull(String::toIntOrNull)?.forEachIndexed { i, n -> if (i < eaten.size) eaten[i] = n }
    }

    companion object {
        const val HUNGRY = 0.3f
        const val STARVING = 0.12f
        const val STUFFED = 0.85f
        const val MEAL = 0.45f
        /** Seconds awake for a full tummy to empty. */
        const val EMPTY_S = 8 * 3600f
        const val WELL_FED_S = 2 * 3600f
        const val GREEDY_S = 20 * 60f
        const val NATURAL_RECOVERY_S = 3 * 3600f
        const val MEDICINE_S = 90f
        /** Chances of catching something, per hour. */
        const val BASE_RISK = 0.004f
        const val HUNGRY_RISK = 0.03f
        const val CHILLY_RISK = 0.012f
    }
}

/**
 * A small key-value text form for preferences, readable without org.json (which is only a stub
 * in unit tests). Values may hold anything but tabs and newlines; lists use [SEP].
 */
class Saved(text: String? = null) {
    private val values = LinkedHashMap<String, String>()

    init {
        text?.lineSequence()?.forEach { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) values[line.substring(0, tab)] = line.substring(tab + 1)
        }
    }

    fun put(key: String, value: Any) {
        values[key] = value.toString().replace('\t', ' ').replace('\n', ' ')
    }

    fun putList(key: String, items: List<String>) = put(key, items.joinToString(SEP.toString()) { it.replace(SEP, ' ') })

    fun string(key: String): String? = values[key]
    fun float(key: String, default: Float) = values[key]?.toFloatOrNull() ?: default
    fun int(key: String, default: Int) = values[key]?.toIntOrNull() ?: default
    fun long(key: String, default: Long) = values[key]?.toLongOrNull() ?: default
    fun bool(key: String) = values[key] == "true"
    fun list(key: String): List<String> = values[key]?.takeIf { it.isNotEmpty() }?.split(SEP) ?: emptyList()

    fun text() = values.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    companion object {
        const val SEP = '\u001F'
    }
}

package jp.co.xenocode.mado

import jp.co.xenocode.mado.QuestLore.pick
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Tama's Quest: an RPG that plays itself. Tama walks through realms made of two genres mashed
 * together, meets things, fights them (they are befriended, sent home or retire; nobody dies,
 * Tama included: it faints and is carried back), finds hats and snacks, and levels up. It moves
 * one beat at a time, slowly when nobody is looking and faster while the page is open.
 *
 * It shares Tama's body with the creature on the glass: [needs] is the same hunger and the same
 * sniffles, and night, naps, weather, music and the painting of the day reach in from outside.
 */
class Quest(private val random: Random = Random.Default) {
    enum class Slot { HAT, WIELD, CHARM }
    enum class Room { FIGHT, CHEST, NPC, SHOP, RIDDLE, SPRING, FORAGE, ODDITY, BOSS }
    enum class Phase { CAMP, TRAVEL, ROOM, FAINTED, NIGHT, INN, DOZING }

    class Item(val slot: Slot, val name: String, val bonus: Int, val hat: Hat? = null) {
        val label get() = "$name +$bonus"
        fun text() = listOf(slot.name, name, bonus.toString(), hat?.name ?: "").joinToString("|")

        companion object {
            fun parse(text: String): Item? {
                val p = text.split('|')
                if (p.size < 4) return null
                val slot = Slot.values().firstOrNull { it.name == p[0] } ?: return null
                return Item(slot, p[1], p[2].toIntOrNull() ?: 0, Hat.values().firstOrNull { it.name == p[3] })
            }
        }
    }

    /** Someone met on the way who came along. `genre` and `seed` pick how they look. */
    class Ally(val name: String, val genre: Int, val seed: Int) {
        fun text() = "$name|$genre|$seed"

        companion object {
            fun parse(text: String): Ally? {
                val p = text.split('|')
                return if (p.size == 3) Ally(p[0], p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0) else null
            }
        }
    }

    class Foe(val name: String, val level: Int, var hp: Int, val maxHp: Int, val boss: Boolean, val genre: Int) {
        val seed get() = name.hashCode()
    }

    /** Something for the page to animate. `seq` only grows, so the page knows what it has shown. */
    class Event(val seq: Long, val kind: Kind, val value: Int = 0)
    enum class Kind { TAMA_HIT, TAMA_CRIT, TAMA_MISS, FOE_HIT, FOE_MISS, ALLY_HIT, BEFRIENDED, FAINT, LOOT, LEVEL_UP, HEAL, EAT, ENTER, QUEST_DONE, NEW_QUEST, CHEER, SICK, BETTER, MIMIC }

    /** What the quest would like the creature on the glass to do. */
    sealed class Crossover {
        object LevelUp : Crossover()
        class NewHat(val hat: Hat) : Crossover()
        /** A foe wants to wander out onto the glass. Answer with [visited] once Tama has shooed it back. */
        class Visitor(val foe: Foe) : Crossover()
    }

    val needs = TamaNeeds()
    var onCrossover: ((Crossover) -> Unit)? = null

    var level = 1
        private set
    var xp = 0
        private set
    var hp = maxHp()
        private set
    var gold = 10
        private set
    val snacks = ArrayList<String>()
    val gear = HashMap<Slot, Item>()
    val allies = ArrayList<Ally>()
    var questsDone = 0
        private set
    var befriended = 0
        private set
    var faints = 0
        private set

    var phase = Phase.CAMP
        private set
    private var resume = Phase.CAMP
    private var phaseBeats = 0
    var setting = 0
        private set
    var flavor = 1
        private set
    var title = ""
        private set
    var boss = ""
        private set
    var realm = ""
        private set
    var job = ""
        private set
    var rooms: List<Room> = emptyList()
        private set
    var roomIndex = 0
        private set
    private var travelLeft = 0
    private var roomBeat = 0
    var foe: Foe? = null
        private set
    private var shopPrice = 0
    private var shopSnack = ""
    private var visiting = false
    private var retries = 0

    /** The outside world, as the frame knows it. */
    var night = false
    var dozing = false
    var watched = false
    private var sky: Sky? = null
    private var tempC = 20
    private var weatherSaid = false
    private var artist = ""
    private var track = ""
    private var grooveBeats = 0
    private var painting: Pair<String, String>? = null
    private var cheer = false
    private var lastBeatAgo = 0f
    private var sinceMessageNote = 1e6f
    private var sincePokeNote = 1e6f

    private val log = ArrayDeque<String>()
    /** How many lines have ever been written, so the page can tell a new one from an old one. */
    var logCount = 0L
        private set
    private val events = ArrayDeque<Event>()
    private var eventSeq = 0L

    val lines: List<String> get() = log.toList()
    val recentEvents: List<Event> get() = events.toList()

    fun maxHp() = 12 + 4 * level
    fun xpToNext() = 40 + 30 * level + 8 * level * level
    fun genreOf(i: Int) = QuestLore.genres[i.coerceIn(0, QuestLore.genres.size - 1)]
    val hat: Hat? get() = gear[Slot.HAT]?.hat

    /** Tama's own part in a fight. Hunger and a meal from this side of the glass both show. */
    private fun attack() = 3 + level + (gear[Slot.WIELD]?.bonus ?: 0) + (if (needs.wellFed > 0f) 1 else 0) - (if (needs.hungry) 1 else 0)
    private fun defence() = (gear[Slot.HAT]?.bonus ?: 0) / 3
    private fun luck() = gear[Slot.CHARM]?.bonus ?: 0

    private fun say(line: String) {
        log.addLast(line)
        logCount++
        while (log.size > MAX_LOG) log.removeFirst()
    }

    private fun event(kind: Kind, value: Int = 0) {
        events.addLast(Event(++eventSeq, kind, value))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    /** The words a line can use: this realm's names for things. */
    private fun words(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val s = genreOf(setting)
        val f = genreOf(flavor)
        return mapOf(
            "foe" to (foe?.name ?: f.foes.pick(random)),
            "foe2" to s.foes.pick(random),
            "boss" to boss,
            "place" to s.places.pick(random),
            "wield" to (gear[Slot.WIELD]?.name ?: f.wields.pick(random)),
            "charm" to f.charms.pick(random),
            "snack" to f.snacks.pick(random),
            "npc" to f.npcs.pick(random).removeArticle(),
            "abstract" to QuestLore.abstracts.pick(random),
            "move" to (f.moves + s.moves).pick(random),
            "ally" to (allies.randomOrNull(random)?.name ?: "Tama's shadow"),
            "genre" to QuestLore.genres.pick(random).name,
        ) + extra
    }

    private fun line(template: String, extra: Map<String, String> = emptyMap()) = QuestLore.fill(template, words(extra))

    /**
     * Time passes, `dt` seconds of it. Hunger and sickness move every time; the story moves one
     * beat whenever a beat is due.
     */
    fun tick(dt: Float) {
        val chilly = sky == Sky.RAIN || sky == Sky.STORM || sky == Sky.SNOW || (sky != null && tempC <= 8)
        val wasSick = needs.sickness
        needs.tick(dt, night || dozing, chilly, random)?.let { caught(it) }
        if (wasSick != null && needs.sickness == null) {
            say("Tama feels better! ${if (phase == Phase.INN) "The party checks out of the inn." else ""}".trim())
            event(Kind.BETTER)
        }
        sinceMessageNote += dt
        sincePokeNote += dt
        lastBeatAgo += dt
        val every = if (watched) WATCHED_BEAT_S else BEAT_S
        if (lastBeatAgo >= every) {
            lastBeatAgo = 0f
            beat()
        }
    }

    private fun caught(what: Sickness) {
        say("Uh oh. Tama has caught ${what.label}.")
        event(Kind.SICK)
    }

    /** The frame was off for this long. The quest went on without anyone watching, a little. */
    fun away(seconds: Float) {
        if (seconds < BEAT_S) return
        var left = min(seconds, 12 * 3600f)
        while (left > 0f) {
            val step = min(left, 600f)
            needs.tick(step, false, false, random)
            left -= step
        }
        say("While the frame was dark, Tama carried on questing.")
        repeat(min(MAX_CATCH_UP, (seconds / BEAT_S).toInt())) { beat() }
    }

    /** Move the story on by one step. Public so tests and the page's cheering can push it along. */
    fun beat() {
        lastBeatAgo = 0f
        if (title.isEmpty()) newQuest()
        if (night && phase != Phase.NIGHT) {
            pause(Phase.NIGHT)
            say(line("Night falls beyond the glass, so the party makes camp. Tama curls up in a {bed}.", mapOf("bed" to QuestLore.beds.pick(random))))
            return
        }
        if (!night && phase == Phase.NIGHT) {
            say("Morning beyond the glass! Tama wakes up and stretches.")
            phase = resume
        }
        if (phase == Phase.NIGHT) {
            heal(0.1f)
            return
        }
        if (needs.sickness != null && phase != Phase.INN) {
            pause(Phase.INN)
            say("Tama has ${needs.sickness!!.label}. The party checks in at the ${QuestLore.inns.pick(random)}.")
            return
        }
        if (needs.sickness == null && phase == Phase.INN) phase = resume
        if (phase == Phase.INN) {
            heal(0.05f)
            if (++phaseBeats % 4 == 0) say(line(QuestLore.innQuips.pick(random)))
            return
        }
        if (dozing && phase != Phase.DOZING) {
            pause(Phase.DOZING)
            say("Tama has dozed off mid-quest. The party waits politely.")
            return
        }
        if (!dozing && phase == Phase.DOZING) {
            say("Tama wakes with a start and carries on.")
            phase = resume
        }
        if (phase == Phase.DOZING) return
        if (needs.starving && phase != Phase.ROOM) forage()
        if (grooveBeats > 0) grooveBeats--

        when (phase) {
            Phase.CAMP -> camp()
            Phase.TRAVEL -> travel()
            Phase.ROOM -> room()
            Phase.FAINTED -> if (++phaseBeats >= 3) {
                hp = maxHp()
                say("Tama dusts itself off and tries again.")
                event(Kind.HEAL)
                phase = Phase.TRAVEL
                travelLeft = 1
            }
            else -> {}
        }
    }

    private fun pause(to: Phase) {
        if (phase != Phase.NIGHT && phase != Phase.INN && phase != Phase.DOZING) resume = phase
        phase = to
        phaseBeats = 0
    }

    private fun heal(fraction: Float) {
        hp = min(maxHp(), hp + max(1, (maxHp() * fraction).toInt()))
    }

    private fun forage() {
        if (snacks.isNotEmpty()) {
            val snack = snacks.removeAt(0)
            needs.snack(SNACK)
            say("Tama can't wait any longer and eats the $snack from its pack.")
        } else {
            needs.snack(SNACK)
            say(line("Tama is starving, so it forages. Found: {snack}! Gone in one bite."))
        }
        event(Kind.EAT)
    }

    private fun camp() {
        phaseBeats++
        when (phaseBeats) {
            1 -> say(if (allies.isEmpty()) "Tama makes camp and hums to itself." else line("The party makes camp. " + QuestLore.stories.pick(random)))
            2 -> {
                hp = maxHp()
                event(Kind.HEAL)
            }
            else -> newQuest()
        }
    }

    private fun newQuest() {
        val count = QuestLore.genres.size
        setting = random.nextInt(count)
        flavor = (setting + 1 + random.nextInt(count - 1)) % count
        val s = genreOf(setting)
        val f = genreOf(flavor)
        realm = "The ${f.adjectives.pick(random)} ${s.places.pick(random)}"
        boss = "${f.bossTitles.pick(random)} ${s.foes.pick(random)}"
        job = "${f.prefixes.pick(random)} ${s.roles.pick(random)}"
        val art = painting
        title = when {
            art != null && random.nextInt(6) == 0 -> line("Rescue '{title}' from the {boss}", mapOf("title" to art.first))
            artist.isNotEmpty() && random.nextInt(8) == 0 -> line("Find the lost B-side of {artist}", mapOf("artist" to artist))
            else -> line(QuestLore.goals.pick(random))
        }
        val n = random.nextInt(5, 9)
        rooms = List(n) { i -> if (i == n - 1) Room.BOSS else roomKind() }.let { r ->
            // At least a couple of fights, or it is not much of an adventure.
            if (r.count { it == Room.FIGHT } >= 2) r else r.mapIndexed { i, k -> if (i == 0 || i == n / 2) Room.FIGHT else k }
        }
        roomIndex = 0
        travelLeft = random.nextInt(1, 3)
        phase = Phase.TRAVEL
        phaseBeats = 0
        foe = null
        weatherSaid = false
        say("NEW QUEST: $title.")
        say("Tama steps into ${realm.replaceFirst("The", "the")} and changes into a $job outfit.")
        if (allies.size > 1 && random.nextInt(3) == 0) {
            val left = allies.removeAt(0)
            say("${left.name.replaceFirstChar { it.uppercase() }} heads home for a rest. They promise to write.")
        }
        event(Kind.NEW_QUEST)
    }

    private fun roomKind(): Room {
        var roll = random.nextInt(100)
        for ((kind, weight) in ROOM_WEIGHTS) {
            roll -= weight
            if (roll < 0) return kind
        }
        return Room.FIGHT
    }

    private fun travel() {
        heal(0.08f)
        when {
            !weatherSaid && sky != null && random.nextInt(3) == 0 -> {
                weatherSaid = true
                weatherLine()?.let { say(line(it)) }
            }
            random.nextInt(2) == 0 -> say(line((genreOf(setting).ambience + genreOf(flavor).ambience).pick(random)))
            needs.hungry && random.nextInt(3) == 0 -> say(line("Tama's tummy rumbles, loud enough to startle a {foe}."))
        }
        if (--travelLeft <= 0) {
            phase = Phase.ROOM
            roomBeat = 0
            room()
        }
    }

    private fun weatherLine(): String? = when {
        sky == Sky.RAIN -> "It's raining beyond the glass too. Even the {foe}s have umbrellas."
        sky == Sky.SNOW -> "Snow falls beyond the glass as well. Tama catches a flake on its tongue."
        sky == Sky.STORM -> "Thunder beyond the glass! Tama hides behind {ally} for a moment."
        tempC >= 30 -> "It's sweltering, here and beyond the glass. Tama finds some shade."
        tempC <= 2 -> "It's freezing beyond the glass. Tama puts on a second, invisible scarf."
        sky == Sky.CLEAR -> "The sun is out beyond the glass. Tama walks with a spring in its step."
        sky == Sky.FOG -> "Fog beyond the glass, and fog here too. Tama holds {ally}'s hand."
        else -> null
    }

    private fun room() {
        val kind = rooms.getOrElse(roomIndex) { Room.BOSS }
        val beat = roomBeat++
        if (beat == 0) event(Kind.ENTER, kind.ordinal)
        when (kind) {
            Room.FIGHT, Room.BOSS -> fight(beat, kind == Room.BOSS)
            Room.CHEST -> if (beat == 0) {
                say("Tama finds a ${QuestLore.chestKinds.pick(random)} chest.")
            } else {
                if (random.nextInt(100) < 15) {
                    say("The chest was a mimic! It only wanted a hug. Tama hugs it.")
                    event(Kind.MIMIC)
                    gainXp(3 + level)
                } else {
                    loot(tier = level)
                }
                roomDone()
            }
            Room.NPC -> if (beat == 0) {
                shopSnack = genreOf(flavor).npcs.pick(random)
                say("Tama meets $shopSnack.")
            } else {
                val who = shopSnack.removeArticle()
                say("${who.replaceFirstChar { it.uppercase() }} says: ${genreOf(flavor).quips.pick(random)}")
                if (allies.size < MAX_ALLIES && random.nextInt(4) == 0 && allies.none { it.name == who }) {
                    allies.add(Ally(who, flavor, random.nextInt()))
                    say("${who.replaceFirstChar { it.uppercase() }} joins the party!")
                }
                roomDone()
            }
            Room.SHOP -> if (beat == 0) {
                shopSnack = genreOf(flavor).snacks.pick(random)
                shopPrice = 4 + level + random.nextInt(6)
                say("${QuestLore.merchants.pick(random)} is selling $shopSnack for ${shopPrice}g.")
            } else {
                if (gold >= shopPrice && snacks.size < MAX_SNACKS) {
                    gold -= shopPrice
                    snacks.add(shopSnack)
                    say("Tama buys one and tucks it in its pack.")
                } else if (snacks.size >= MAX_SNACKS) {
                    say("Tama's pack is full of snacks already. They part as friends.")
                } else {
                    say("Tama can't afford it, but they part as friends.")
                }
                roomDone()
            }
            Room.RIDDLE -> if (beat == 0) {
                say("${QuestLore.riddlers.pick(random)} blocks the way and asks: '${QuestLore.riddles.pick(random)}'")
            } else {
                val right = random.nextInt(100) < 45 + luck() * 5
                say("Tama answers '${QuestLore.answers.pick(random)}' ${(if (right) QuestLore.rightAnswers else QuestLore.wrongAnswers).pick(random)}")
                if (right) gainXp(5 + 2 * level)
                roomDone()
            }
            Room.SPRING -> {
                say("A ${QuestLore.springs.pick(random)}! Tama rests for a while and feels much better.")
                hp = maxHp()
                event(Kind.HEAL)
                roomDone()
            }
            Room.FORAGE -> if (beat == 0) {
                say("Tama rummages in the undergrowth...")
            } else {
                if (random.nextInt(100) < 10) {
                    say("Tama finds a mushroom that tastes purple. Hmm.")
                    if (needs.sickness == null && random.nextInt(100) < 30) {
                        needs.catch(Sickness.TUMMY)
                        caught(Sickness.TUMMY)
                    }
                } else {
                    gotSnack(genreOf(flavor).snacks.pick(random))
                }
                roomDone()
            }
            Room.ODDITY -> {
                val art = painting
                if (art != null && random.nextInt(4) == 0) {
                    say("In a dusty hall hangs '${art.first}' by ${art.second}. Tama looks at it for a long time.")
                } else {
                    say(line(QuestLore.oddities.pick(random)))
                }
                roomDone()
            }
        }
    }

    private fun gotSnack(snack: String) {
        if (snacks.size < MAX_SNACKS) {
            snacks.add(snack)
            say("Found: $snack! Tama tucks it in its pack for later.")
        } else {
            needs.snack(SNACK / 2)
            say("Found: $snack! The pack is full, so Tama eats it on the spot.")
            event(Kind.EAT)
        }
    }

    private fun fight(beat: Int, isBoss: Boolean) {
        if (beat == 0) {
            val s = genreOf(setting)
            val f = genreOf(flavor)
            val lvl = max(1, level + if (isBoss) 1 else random.nextInt(-1, 2))
            val name = if (isBoss) boss else "${s.adjectives.pick(random)} ${f.foes.pick(random)}"
            // Each faint in this room leaves the next foe a little more tired too, so no room lasts for ever.
            val hpMax = (if (isBoss) 15 + 6 * lvl else 5 + 3 * lvl) * 10 / (10 + 4 * retries)
            foe = Foe(name, lvl, hpMax, hpMax, isBoss, flavor)
            if (retries > 0) say("It looks tired from last time.")
            say(if (isBoss) "At last: the $name! It looks surprisingly reasonable." else "A wild $name appears!")
            if (!isBoss && !watched && random.nextInt(VISIT_ODDS) == 0) onCrossover?.invoke(Crossover.Visitor(foe!!))
            return
        }
        val foe = foe ?: return roomDone()
        if (visiting) {
            // Out on the glass with Tama. If nothing ever answers, it wanders back in by itself.
            if (beat > 6) visiting = false
            return
        }
        // Tama's turn, then a friend's, then the foe's.
        val crit = cheer || random.nextInt(100) < 10 + 2 * luck() + if (grooveBeats > 0) 10 else 0
        cheer = false
        val w = words()
        if (!crit && random.nextInt(100) < 8) {
            say(QuestLore.fill(QuestLore.tamaMisses.pick(random), w))
            event(Kind.TAMA_MISS)
        } else {
            val a = attack()
            var dmg = a / 2 + random.nextInt(a / 2 + 1)
            if (crit) dmg = dmg * 2 + 1
            foe.hp -= dmg
            say(QuestLore.fill((if (crit) QuestLore.tamaCrits else QuestLore.tamaHits).pick(random), w + ("n" to dmg.toString())))
            event(if (crit) Kind.TAMA_CRIT else Kind.TAMA_HIT, dmg)
        }
        if (foe.hp > 0 && allies.isNotEmpty() && random.nextInt(100) < 35) {
            val ally = allies.pick(random)
            val dmg = 1 + level / 3 + random.nextInt(2)
            foe.hp -= dmg
            say(QuestLore.fill("{who} helps out and ${QuestLore.assists.pick(random)}. $dmg damage.", w + ("who" to ally.name.replaceFirstChar { it.uppercase() })))
            event(Kind.ALLY_HIT, dmg)
        }
        if (foe.hp <= 0) {
            befriend(foe)
            return
        }
        if (random.nextInt(100) < 22) {
            say(QuestLore.fill(QuestLore.foeMisses.pick(random), w))
            event(Kind.FOE_MISS)
            return
        }
        val power = (2 + foe.level * (if (foe.boss) 13 else 11) / 10) * 10 / (10 + 3 * retries)
        val dmg = max(1, power / 2 + random.nextInt(power / 2 + 1) - defence())
        hp -= dmg
        say(QuestLore.fill(genreOf(foe.genre).attacks.pick(random) + ". $dmg damage.", w))
        event(Kind.FOE_HIT, dmg)
        if (hp <= 0) faint()
    }

    private fun befriend(foe: Foe) {
        say(QuestLore.fill(genreOf(foe.genre).defeats.pick(random), words()))
        befriended++
        event(Kind.BEFRIENDED)
        val wellFed = needs.wellFed > 0f
        gainXp(((4 + 3 * foe.level / 2) * (if (foe.boss) 3 else 1) * (if (wellFed) 1.25f else 1f)).toInt())
        gold += 1 + foe.level + random.nextInt(4) + luck()
        if (foe.boss || random.nextInt(100) < 30) loot(foe.level + if (foe.boss) 2 else 0)
        else if (random.nextInt(100) < 20) gotSnack(genreOf(flavor).snacks.pick(random))
        this.foe = null
        roomDone()
    }

    private fun faint() {
        hp = 0
        faints++
        gold -= gold / 10
        val rescuer = QuestLore.fill(QuestLore.rescuers.pick(random), words())
        say("Tama is all out of puff and flops over. $rescuer carries it back to camp.")
        event(Kind.FAINT)
        phase = Phase.FAINTED
        phaseBeats = 0
        // The same room again afterwards, with a fresh foe.
        roomBeat = 0
        retries++
        foe = null
    }

    private fun roomDone() {
        foe = null
        retries = 0
        if (rooms.getOrNull(roomIndex) == Room.BOSS || roomIndex >= rooms.size - 1) return questDone()
        roomIndex++
        phase = Phase.TRAVEL
        travelLeft = random.nextInt(1, 4)
    }

    private fun questDone() {
        questsDone++
        val reward = 10 + 5 * level
        say("QUEST COMPLETE: $title! +${reward} XP.")
        event(Kind.QUEST_DONE)
        gainXp(reward)
        gold += 5 + level * 2
        phase = Phase.CAMP
        phaseBeats = 0
        roomIndex = rooms.size
    }

    private fun gainXp(amount: Int) {
        xp += amount
        while (xp >= xpToNext()) {
            xp -= xpToNext()
            level++
            hp = maxHp()
            say("LEVEL UP! Tama is now level $level. ${QuestLore.perks.pick(random)}")
            event(Kind.LEVEL_UP, level)
            onCrossover?.invoke(Crossover.LevelUp)
        }
    }

    /** Something found: worn if it is better than what Tama has, sold if not. */
    private fun loot(tier: Int) {
        if (random.nextInt(100) < 25) {
            val coins = 5 + tier * 2 + random.nextInt(10)
            gold += coins
            say("Inside: $coins gold coins, and a note saying 'sorry'.")
            event(Kind.LOOT)
            return
        }
        val slot = Slot.values().pick(random)
        val f = genreOf(flavor)
        val s = genreOf(setting)
        val rare = random.nextInt(100) < 8
        val bonus = 1 + tier / 3 + random.nextInt(3) + if (rare) 3 else 0
        val adjective = (if (random.nextBoolean()) s.adjectives else QuestLore.itemAdjectives).pick(random)
        val item = when (slot) {
            Slot.HAT -> if (rare && random.nextInt(3) == 0) Item(slot, "$adjective Crown of ${QuestLore.itemNouns.pick(random)}", bonus, Hat.CROWN)
            else Item(slot, "$adjective ${f.hatNames.pick(random)}", bonus, f.hat)
            Slot.WIELD -> Item(slot, "$adjective ${f.wields.pick(random)} of ${QuestLore.itemNouns.pick(random)}", bonus)
            Slot.CHARM -> Item(slot, "${f.charms.pick(random)} of ${QuestLore.itemNouns.pick(random)}", bonus)
        }
        event(Kind.LOOT)
        val current = gear[slot]
        if (current == null || item.bonus > current.bonus) {
            gear[slot] = item
            say(when (slot) {
                Slot.HAT -> "Found: ${item.label}! Tama puts it on at once."
                Slot.WIELD -> "Found: ${item.label}! Tama wields it proudly."
                Slot.CHARM -> "Found: ${item.label}! Tama keeps it close."
            })
            if (slot == Slot.HAT && item.hat != null && item.hat != current?.hat) onCrossover?.invoke(Crossover.NewHat(item.hat))
        } else {
            val price = 2 + item.bonus * 2
            gold += price
            say("Found: ${item.label}. Not as good as Tama's, so it sells it for ${price}g.")
        }
    }

    /** The foe that wandered out onto the glass has been shooed back in. */
    fun visited(foeName: String) {
        val f = foe ?: return
        if (f.name != foeName) return
        visiting = false
        say("A ${f.name} slipped out through the glass! Tama shooed it back in.")
        befriend(f)
    }

    /** Tama took up the visitor; the fight waits while it is out there. */
    fun visiting(foeName: String) {
        if (foe?.name == foeName) visiting = true
    }

    /** Someone tapped the page. The next blow is a big one; true if that was news. */
    fun cheer(): Boolean {
        if (cheer || phase != Phase.ROOM || foe == null) return false
        cheer = true
        say("A voice from beyond the glass cheers! Tama feels unstoppable.")
        event(Kind.CHEER)
        return true
    }

    /** A meal put in Tama's bowl, here or on the glass. */
    fun feed(food: Food): TamaNeeds.Fed {
        val fed = needs.feed(food, random)
        val what = food.label.replaceFirstChar { it.uppercase() }
        say(when (fed) {
            TamaNeeds.Fed.ATE -> "$what drops in from beyond the glass! Tama eats it with great ceremony. (Well fed!)"
            TamaNeeds.Fed.LOVED -> "$what drops in from beyond the glass! Tama's favourite! It does a little dance. (Well fed!)"
            TamaNeeds.Fed.STUFFED -> "$what drops in from beyond the glass, but Tama is stuffed and pushes it away."
            TamaNeeds.Fed.TOO_POORLY -> "$what drops in from beyond the glass, but Tama's tummy can't face it."
        })
        if (fed == TamaNeeds.Fed.ATE || fed == TamaNeeds.Fed.LOVED) event(Kind.EAT)
        if (needs.sickness == Sickness.TUMMY && fed != TamaNeeds.Fed.TOO_POORLY) caught(Sickness.TUMMY)
        return fed
    }

    fun medicine(): Boolean {
        if (!needs.medicine()) return false
        say("Medicine arrives from beyond the glass. Tama makes a face, then perks right up.")
        event(Kind.HEAL)
        return true
    }

    fun setWeather(sky: Sky?, tempC: Int) {
        if (sky != this.sky) weatherSaid = false
        this.sky = sky
        this.tempC = tempC
    }

    val weather: Sky? get() = sky

    fun setMusic(artist: String, title: String, playing: Boolean) {
        if (!playing || title.isEmpty()) return
        if (title == track && artist == this.artist) return
        this.artist = artist
        track = title
        grooveBeats = 10
        say("Music drifts in from beyond the glass: $title${if (artist.isNotEmpty()) " by $artist" else ""}. The party grooves.")
    }

    val grooving get() = grooveBeats > 0

    fun setPainting(title: String, artist: String) {
        if (title.isEmpty()) return
        painting = title to artist
    }

    /** An alert came onto the glass outside. Remarked on now and then, not every time. */
    fun messageArrived() {
        if (sinceMessageNote < 600f) return
        sinceMessageNote = 0f
        say("A message rattles the glass from outside. Everyone stops to read it.")
    }

    fun poked() {
        if (sincePokeNote < 900f) return
        sincePokeNote = 0f
        say("Something pokes Tama from beyond the glass. Tama looks around suspiciously.")
    }

    /** A sentence for anyone asking the hub what Tama is up to. */
    fun summary(): String {
        val doing = when (phase) {
            Phase.ROOM -> foe?.let { "befriending the ${it.name}" } ?: "exploring room ${roomIndex + 1} of ${rooms.size}"
            Phase.TRAVEL -> "on the road to room ${roomIndex + 1} of ${rooms.size}"
            Phase.CAMP -> "resting at camp"
            Phase.FAINTED -> "recovering from a faint"
            Phase.NIGHT -> "asleep at camp for the night"
            Phase.INN -> "at an inn with ${needs.sickness?.label ?: "a cold"}"
            Phase.DOZING -> "dozing"
        }
        return "Level $level $job in ${realm.replaceFirst("The", "the")}, $doing. Quest: $title."
    }

    fun save(): String {
        val out = Saved()
        needs.save(out)
        out.put("level", level)
        out.put("xp", xp)
        out.put("hp", hp)
        out.put("gold", gold)
        out.putList("snacks", snacks)
        out.putList("gear", gear.values.map { it.text() })
        out.putList("allies", allies.map { it.text() })
        out.put("quests", questsDone)
        out.put("befriended", befriended)
        out.put("faints", faints)
        out.put("phase", phase.name)
        out.put("resume", resume.name)
        out.put("phase_beats", phaseBeats)
        out.put("setting", setting)
        out.put("flavor", flavor)
        out.put("title", title)
        out.put("boss", boss)
        out.put("realm", realm)
        out.put("job", job)
        out.put("rooms", rooms.joinToString(",") { it.name })
        out.put("room", roomIndex)
        out.put("travel", travelLeft)
        out.put("room_beat", roomBeat)
        out.put("retries", retries)
        foe?.let { out.putList("foe", listOf(it.name, it.level.toString(), it.hp.toString(), it.maxHp.toString(), it.boss.toString(), it.genre.toString())) }
        out.put("shop_snack", shopSnack)
        out.put("shop_price", shopPrice)
        out.put("log_count", logCount)
        out.putList("log", log.toList())
        return out.text()
    }

    fun restore(text: String?) {
        if (text.isNullOrEmpty()) return
        val s = Saved(text)
        needs.restore(s)
        level = max(1, s.int("level", 1))
        xp = s.int("xp", 0)
        hp = s.int("hp", maxHp()).coerceIn(0, maxHp())
        gold = s.int("gold", 10)
        snacks.clear()
        snacks.addAll(s.list("snacks"))
        gear.clear()
        s.list("gear").mapNotNull(Item::parse).forEach { gear[it.slot] = it }
        allies.clear()
        allies.addAll(s.list("allies").mapNotNull(Ally::parse))
        questsDone = s.int("quests", 0)
        befriended = s.int("befriended", 0)
        faints = s.int("faints", 0)
        phase = Phase.values().firstOrNull { it.name == s.string("phase") } ?: Phase.CAMP
        resume = Phase.values().firstOrNull { it.name == s.string("resume") } ?: Phase.CAMP
        phaseBeats = s.int("phase_beats", 0)
        setting = s.int("setting", 0).coerceIn(0, QuestLore.genres.size - 1)
        flavor = s.int("flavor", 1).coerceIn(0, QuestLore.genres.size - 1)
        title = s.string("title") ?: ""
        boss = s.string("boss") ?: ""
        realm = s.string("realm") ?: ""
        job = s.string("job") ?: ""
        rooms = s.string("rooms")?.split(',')?.mapNotNull { r -> Room.values().firstOrNull { it.name == r } } ?: emptyList()
        roomIndex = s.int("room", 0)
        travelLeft = s.int("travel", 1)
        roomBeat = s.int("room_beat", 0)
        retries = s.int("retries", 0)
        foe = s.list("foe").takeIf { it.size == 6 }?.let {
            Foe(it[0], it[1].toIntOrNull() ?: 1, it[2].toIntOrNull() ?: 1, it[3].toIntOrNull() ?: 1, it[4] == "true", it[5].toIntOrNull() ?: 0)
        }
        shopSnack = s.string("shop_snack") ?: ""
        shopPrice = s.int("shop_price", 5)
        log.clear()
        log.addAll(s.list("log"))
        logCount = s.long("log_count", log.size.toLong())
        // A quest saved half-made (or by a different version) starts over cleanly.
        if (rooms.isEmpty() || (phase == Phase.ROOM && rooms.getOrNull(roomIndex) in listOf(Room.FIGHT, Room.BOSS) && foe == null && roomBeat > 0)) {
            title = ""
            phase = Phase.CAMP
        }
        // Paused phases resume by themselves once their reason has gone.
    }

    private fun String.removeArticle() = when {
        startsWith("a ") -> "the " + substring(2)
        startsWith("an ") -> "the " + substring(3)
        else -> this
    }

    companion object {
        /** Seconds between beats, with nobody watching and with the page open. */
        const val BEAT_S = 120f
        const val WATCHED_BEAT_S = 6f
        const val MAX_LOG = 60
        const val MAX_EVENTS = 24
        const val MAX_SNACKS = 3
        const val MAX_ALLIES = 2
        const val MAX_CATCH_UP = 40
        const val SNACK = 0.35f
        /** One fight in this many, unwatched, a foe wanders out onto the glass. */
        const val VISIT_ODDS = 10
        val ROOM_WEIGHTS = listOf(
            Room.FIGHT to 40, Room.CHEST to 10, Room.NPC to 10, Room.SHOP to 7,
            Room.RIDDLE to 7, Room.SPRING to 8, Room.FORAGE to 8, Room.ODDITY to 10,
        )
    }
}

private fun <T> Array<T>.pick(random: Random) = this[random.nextInt(size)]

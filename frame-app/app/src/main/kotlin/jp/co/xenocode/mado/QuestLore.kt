package jp.co.xenocode.mado

import kotlin.random.Random

/** Hats Tama finds on its quests. It wears the best one at home too, still a little pixelated. */
enum class Hat { WIZARD, VISOR, ANTENNA, FEDORA, TOQUE, TOPHAT, COWBOY, SAILOR, PROPELLER, KASA, PARTY, SAFARI, CROWN }

/** The scenery a realm is drawn with. */
enum class Scenery { CASTLE, CUBICLES, PLANETS, CITY, SHELVES, GRAVES, MESAS, KELP, SYNTH, TORII, DISCO, VOLCANO }

/** What drifts through a realm's air. */
enum class Drift { NONE, LEAVES, PAPER, STARS, RAIN, STEAM, FIREFLIES, DUST, BUBBLES, PETALS, SPARKLES }

/** How a genre looks: its sky from top to bottom, far and near scenery, ground and the ground's pattern. */
class Look(
    val skyTop: Int, val skyBottom: Int, val far: Int, val near: Int,
    val ground: Int, val groundAccent: Int, val scenery: Scenery, val drift: Drift,
)

/**
 * One kind of story. A realm is two of them mashed together: one gives the place and how it
 * looks, the other the creatures, the loot and the manners. That is how Tama ends up in a
 * Hardboiled Pantry fighting a Zero-G Soufflé.
 */
class Genre(
    val name: String,
    val places: List<String>,
    val adjectives: List<String>,
    val foes: List<String>,
    val bossTitles: List<String>,
    val attacks: List<String>,
    val defeats: List<String>,
    val wields: List<String>,
    val charms: List<String>,
    val snacks: List<String>,
    val hat: Hat,
    val hatNames: List<String>,
    val npcs: List<String>,
    val quips: List<String>,
    val roles: List<String>,
    val prefixes: List<String>,
    val moves: List<String>,
    val ambience: List<String>,
    val colors: IntArray,
    val look: Look,
)

/** Everything Tama's quests are made of. Lines use {placeholders}, filled by [fill]. */
object QuestLore {
    val genres = listOf(
        Genre(
            "fantasy",
            places = listOf("Wizard's Tower", "Enchanted Forest", "Dragon's Lair", "Castle Keep", "Goblin Market"),
            adjectives = listOf("Enchanted", "Cursed", "Legendary", "Elven", "Arcane"),
            foes = listOf("Goblin", "Slime", "Wyvern", "Lich", "Gelatinous Cube", "Owlbear", "Mimic"),
            bossTitles = listOf("Dread King", "Archmage", "Elder Dragon", "Lich Lord"),
            attacks = listOf("{foe} casts Mild Fireball", "{foe} swings a rusty sword", "{foe} rolls a natural 20 on rudeness", "{foe} summons a smaller {foe}"),
            defeats = listOf("{foe} drops its sword and takes up knitting.", "{foe} vanishes in a puff of glitter.", "{foe} retires to a nice cave by the sea."),
            wields = listOf("Sword", "Staff", "Wand", "Lute", "Mace"),
            charms = listOf("Ring", "Amulet", "Rune", "Lucky D20"),
            snacks = listOf("Elven Waybread", "Healing Mushroom", "Dragonfruit"),
            hat = Hat.WIZARD, hatNames = listOf("Wizard Hat", "Pointy Hat"),
            npcs = listOf("a wizard with a very long beard", "a retired knight", "an elf who is mostly eyebrows", "a bard with only one song"),
            quips = listOf("'It's dangerous to go alone. Take this.' It is a spoon.", "'The prophecy mentions you. Sort of. Page 900.'", "'Never trust a chest with teeth.'"),
            roles = listOf("Paladin", "Wizard", "Ranger", "Bard"),
            prefixes = listOf("Arcane", "Heroic", "Dragon"),
            moves = listOf("Sparkle Bonk", "Arcane Boop", "Heroic Wiggle"),
            ambience = listOf("A distant dragon clears its throat.", "The trees whisper spoilers.", "A signpost points in every direction at once."),
            colors = intArrayOf(0xFF6BBE45.toInt(), 0xFF9B59B6.toInt(), 0xFF7F8FA6.toInt(), 0xFFC0392B.toInt()),
            look = Look(0xFF5FA8E8.toInt(), 0xFFCDE9F7.toInt(), 0xFF8CC08A.toInt(), 0xFF4F8F55.toInt(), 0xFF5E9E4A.toInt(), 0xFF3F7A35.toInt(), Scenery.CASTLE, Drift.LEAVES),
        ),
        Genre(
            "office",
            places = listOf("Open-Plan Office", "Conference Room B", "Break Room", "Server Closet", "Corporate Tower"),
            adjectives = listOf("Corporate", "Quarterly", "Passive-Aggressive", "Synergised", "Laminated"),
            foes = listOf("Middle Manager", "Stapler", "Printer", "Spreadsheet", "Memo", "Reply-All", "Lanyard"),
            bossTitles = listOf("Regional Manager", "Chief Synergy Officer", "VP of Mondays"),
            attacks = listOf("{foe} schedules a meeting that could have been an email", "{foe} cc's Tama on a 40-reply thread", "{foe} jams, aggressively", "{foe} circles back"),
            defeats = listOf("{foe} takes an early lunch and never returns.", "{foe} is reassigned to another realm.", "{foe} sets its status to Away."),
            wields = listOf("Stapler", "Laser Pointer", "Hole Punch", "Clipboard"),
            charms = listOf("Employee of the Month Badge", "Stress Ball", "Sticky Note"),
            snacks = listOf("Break-Room Donut", "Mystery Yogurt", "Birthday Cake Slice"),
            hat = Hat.VISOR, hatNames = listOf("Accountant's Visor", "Green Visor"),
            npcs = listOf("a printer that only says 'PC LOAD LETTER'", "an intern who has been here since 1997", "a sentient coffee machine"),
            quips = listOf("'Per my last email: good luck.'", "'Have you tried turning the dungeon off and on again?'", "'Let's take this offline.'"),
            roles = listOf("Accountant", "Auditor", "Consultant"),
            prefixes = listOf("Corporate", "Quarterly", "Spreadsheet"),
            moves = listOf("Synergy Slam", "Out-of-Office Reply", "Hostile Takeover Hug"),
            ambience = listOf("Somewhere a phone rings forever.", "The fluorescent lights hum in B flat.", "A motivational poster about eagles watches Tama pass."),
            colors = intArrayOf(0xFFD9CBA3.toInt(), 0xFF95A5A6.toInt(), 0xFF5D8AA8.toInt(), 0xFF8B5A2B.toInt()),
            look = Look(0xFFCFC8B8.toInt(), 0xFFEDE8DC.toInt(), 0xFFB4AFA4.toInt(), 0xFF8A877F.toInt(), 0xFF6F7C8C.toInt(), 0xFF5F6B7A.toInt(), Scenery.CUBICLES, Drift.PAPER),
        ),
        Genre(
            "space",
            places = listOf("Space Station", "Moon Base", "Asteroid Belt", "Nebula", "Starship Bridge"),
            adjectives = listOf("Zero-G", "Cosmic", "Interstellar", "Laser", "Quantum"),
            foes = listOf("Space Squid", "Robot", "Moon Slug", "Alien", "Comet", "Drone"),
            bossTitles = listOf("Galactic Emperor", "Space Admiral", "Mothership"),
            attacks = listOf("{foe} fires a polite laser", "{foe} beams Tama up and then back down", "{foe} turns off the gravity", "{foe} plays a theremin at Tama"),
            defeats = listOf("{foe} drifts off into the stars, waving.", "{foe} reboots as a toaster.", "{foe} phones home and leaves."),
            wields = listOf("Ray Gun", "Space Spoon", "Laser Sword", "Tractor Beam"),
            charms = listOf("Moon Rock", "Star Chart", "Tiny Satellite"),
            snacks = listOf("Astronaut Ice Cream", "Space Dango", "Tube of Moon Jelly"),
            hat = Hat.ANTENNA, hatNames = listOf("Antenna Headband", "Space Antennae"),
            npcs = listOf("a robot who wants to be a poet", "a lost astronaut", "a very small alien in a very large mech"),
            quips = listOf("'In space, no one can hear you snack.'", "'The stars are just the sky's freckles.'", "'Beep. Boop. That means good luck.'"),
            roles = listOf("Astronaut", "Starpilot", "Space Ranger"),
            prefixes = listOf("Cosmic", "Galactic", "Rocket"),
            moves = listOf("Meteor Boop", "Zero-G Tumble", "Supernova Sneeze"),
            ambience = listOf("A satellite drifts past, beeping hello.", "The stars twinkle in Morse code.", "Somewhere, a black hole is eating loudly."),
            colors = intArrayOf(0xFF9BE564.toInt(), 0xFFE056FD.toInt(), 0xFF48DBFB.toInt(), 0xFFC8D6E5.toInt()),
            look = Look(0xFF05060F.toInt(), 0xFF1E1748.toInt(), 0xFF7B5FB0.toInt(), 0xFF3A3F5C.toInt(), 0xFF5A6070.toInt(), 0xFF3C414F.toInt(), Scenery.PLANETS, Drift.STARS),
        ),
        Genre(
            "noir",
            places = listOf("Rainy Back Alley", "Jazz Club", "Detective Agency", "Docks at Midnight", "Old Hotel"),
            adjectives = listOf("Noir", "Hardboiled", "Rain-Soaked", "Shadowy", "Smoky"),
            foes = listOf("Informant", "Gangster", "Trench Coat", "Alley Cat", "Saxophone", "Getaway Car"),
            bossTitles = listOf("Crime Boss", "Kingpin", "Big Cheese"),
            attacks = listOf("{foe} monologues at Tama", "{foe} blows smoke rings", "{foe} makes an offer Tama can refuse", "{foe} narrates ominously"),
            defeats = listOf("{foe} confesses everything.", "{foe} walks off into the rain.", "{foe} slips away into the shadows, which is fine."),
            wields = listOf("Magnifying Glass", "Flashlight", "Notebook", "Umbrella"),
            charms = listOf("Lucky Coin", "Matchbook", "Old Photograph"),
            snacks = listOf("Diner Pie", "Cold Coffee", "Hard-Boiled Egg"),
            hat = Hat.FEDORA, hatNames = listOf("Fedora", "Trilby"),
            npcs = listOf("a detective with a tragic past", "a singer from a smoky club", "a pigeon who knows things"),
            quips = listOf("'The city never sleeps. It naps.'", "'Follow the money. Or the snacks.'", "'Everyone's guilty of something, kid. Mostly snacking.'"),
            roles = listOf("Detective", "Private Eye", "Gumshoe"),
            prefixes = listOf("Hardboiled", "Midnight", "Noir"),
            moves = listOf("Dramatic Monologue", "Clue Bonk", "Trench Coat Swirl"),
            ambience = listOf("Rain hits the window like it owes money.", "A saxophone sighs somewhere.", "A neon sign flickers: OPEN, CLOSED, OPEN."),
            colors = intArrayOf(0xFF6B6B80.toInt(), 0xFFB08D57.toInt(), 0xFF9B3D4B.toInt(), 0xFF5C6B7A.toInt()),
            look = Look(0xFF0E1426.toInt(), 0xFF2A3350.toInt(), 0xFF1C2440.toInt(), 0xFF262E48.toInt(), 0xFF222838.toInt(), 0xFF3A4560.toInt(), Scenery.CITY, Drift.RAIN),
        ),
        Genre(
            "kitchen",
            places = listOf("Bakery", "Ramen Shop", "Test Kitchen", "Pantry", "Night Market"),
            adjectives = listOf("Spicy", "Half-Baked", "Deep-Fried", "Glazed", "Undercooked"),
            foes = listOf("Soufflé", "Dumpling", "Rogue Baguette", "Sous-Chef", "Meatball", "Angry Teapot", "Onion"),
            bossTitles = listOf("Head Chef", "Food Critic", "Grand Pâtissier"),
            attacks = listOf("{foe} flambés Tama a little", "{foe} critiques Tama's seasoning", "{foe} rises dramatically", "{foe} throws flour everywhere"),
            defeats = listOf("{foe} is sent back to the kitchen.", "{foe} collapses like a soufflé.", "{foe} gives Tama five stars and leaves happy."),
            wields = listOf("Ladle", "Whisk", "Rolling Pin", "Spatula", "Chopsticks"),
            charms = listOf("Golden Spoon", "Secret Recipe", "Lucky Salt"),
            snacks = listOf("Melon Pan", "Gyoza", "Warm Croissant", "Takoyaki"),
            hat = Hat.TOQUE, hatNames = listOf("Chef's Toque", "Tall Chef's Hat"),
            npcs = listOf("a chef who only speaks in recipes", "a ramen master", "a dumpling who escaped the steamer"),
            quips = listOf("'Taste everything. Except that.'", "'The secret ingredient is more butter.'", "'Never trust a skinny dumpling.'"),
            roles = listOf("Chef", "Pâtissier", "Line Cook"),
            prefixes = listOf("Pastry", "Spicy", "Umami"),
            moves = listOf("Croissant Smite", "Wasabi Surprise", "Whisk Whirlwind"),
            ambience = listOf("Something smells delicious and slightly on fire.", "A pot boils over with enthusiasm.", "Somewhere, bread is rising up."),
            colors = intArrayOf(0xFFF3D19C.toInt(), 0xFFE74C3C.toInt(), 0xFF9CBF5A.toInt(), 0xFF8B5B3A.toInt()),
            look = Look(0xFFF0CB94.toInt(), 0xFFFBEBD0.toInt(), 0xFFD9A066.toInt(), 0xFFB5733E.toInt(), 0xFFEFE9E0.toInt(), 0xFFD94F4F.toInt(), Scenery.SHELVES, Drift.STEAM),
        ),
        Genre(
            "haunted",
            places = listOf("Haunted Mansion", "Moonlit Graveyard", "Creaky Attic", "Ghost Train", "Abandoned Theatre"),
            adjectives = listOf("Haunted", "Spectral", "Spooky", "Ectoplasmic", "Creaky"),
            foes = listOf("Ghost", "Poltergeist", "Skeleton", "Haunted Doll", "Bat", "Pumpkin"),
            bossTitles = listOf("Phantom Duke", "Count", "Banshee Queen"),
            attacks = listOf("{foe} says BOO", "{foe} rattles some chains", "{foe} moves the furniture slightly", "{foe} wails off-key"),
            defeats = listOf("{foe} finally finds peace, and a hobby.", "{foe} fades away, apologising.", "{foe} remembers it left the stove on in 1887 and floats off."),
            wields = listOf("Candlestick", "Broom", "Lantern", "Spirit Bell"),
            charms = listOf("Garlic Clove", "Haunted Locket", "Glow Pebble"),
            snacks = listOf("Candy Corn", "Ghost Marshmallow", "Pumpkin Bun"),
            hat = Hat.TOPHAT, hatNames = listOf("Top Hat", "Victorian Top Hat"),
            npcs = listOf("a ghost who is afraid of the dark", "a vampire who faints at tomato juice", "a talking portrait"),
            quips = listOf("'The house isn't haunted. It's just shy.'", "'Don't go in the basement. It's untidy.'", "'Boo. Sorry. Habit.'"),
            roles = listOf("Ghost Hunter", "Medium", "Exorcist"),
            prefixes = listOf("Spooky", "Spectral", "Midnight"),
            moves = listOf("Boo-merang", "Ectoplasm Squish", "Spooky Hug"),
            ambience = listOf("A door creaks open by itself, then closes, embarrassed.", "An owl hoots twice, then once more for luck.", "The portraits' eyes follow Tama, then lose interest."),
            colors = intArrayOf(0xFFE8E8F4.toInt(), 0xFFF39C12.toInt(), 0xFF8E44AD.toInt(), 0xFFD7CCC8.toInt()),
            look = Look(0xFF1B1030.toInt(), 0xFF4A2F63.toInt(), 0xFF2E1F44.toInt(), 0xFF1E1430.toInt(), 0xFF2E3B2C.toInt(), 0xFF1F2A1E.toInt(), Scenery.GRAVES, Drift.FIREFLIES),
        ),
        Genre(
            "western",
            places = listOf("Dusty Saloon", "Ghost Town", "Canyon Pass", "Frontier Railroad", "Cactus Flats"),
            adjectives = listOf("Dusty", "Rootin'-Tootin'", "Sun-Baked", "Lonesome", "Six-Shooting"),
            foes = listOf("Outlaw", "Tumbleweed", "Rattlesnake", "Cactus", "Bandit", "Vulture"),
            bossTitles = listOf("Sheriff", "Outlaw King", "Cattle Baron"),
            attacks = listOf("{foe} tips its hat menacingly", "{foe} spits and misses everything", "{foe} starts a staring contest", "{foe} kicks up dust"),
            defeats = listOf("{foe} rides off into the sunset.", "{foe} tumbles away on the wind.", "{foe} hangs up its spurs."),
            wields = listOf("Lasso", "Harmonica", "Horseshoe", "Cap Gun"),
            charms = listOf("Sheriff's Star", "Lucky Horseshoe", "Gold Nugget"),
            snacks = listOf("Campfire Beans", "Cactus Fruit", "Sarsaparilla"),
            hat = Hat.COWBOY, hatNames = listOf("Cowboy Hat", "Ten-Gallon Hat"),
            npcs = listOf("a prospector who struck gold and lost it again", "a horse who can count to four", "a sheriff with no town"),
            quips = listOf("'This realm ain't big enough for the two of us. It is, actually.'", "'Never squat with your spurs on.'", "'Yee, and if I may, haw.'"),
            roles = listOf("Cowpoke", "Gunslinger", "Sheriff"),
            prefixes = listOf("Rootin'-Tootin'", "Frontier", "Dusty"),
            moves = listOf("Lasso Loop", "High Noon Boop", "Tumbleweed Roll"),
            ambience = listOf("A tumbleweed rolls past, then comes back for a second look.", "A harmonica plays the same three notes.", "Vultures circle, but only to say hi."),
            colors = intArrayOf(0xFFD2A86E.toInt(), 0xFF5E9C4B.toInt(), 0xFFB5523B.toInt(), 0xFF4F6D8F.toInt()),
            look = Look(0xFFE8743B.toInt(), 0xFFF7C873.toInt(), 0xFFC0603F.toInt(), 0xFF8C3B2A.toInt(), 0xFFE0B070.toInt(), 0xFFC99555.toInt(), Scenery.MESAS, Drift.DUST),
        ),
        Genre(
            "deep sea",
            places = listOf("Sunken Ship", "Coral Reef", "Abyssal Trench", "Underwater Palace", "Kelp Forest"),
            adjectives = listOf("Soggy", "Abyssal", "Barnacled", "Bioluminescent", "Salty"),
            foes = listOf("Jellyfish", "Crab", "Pufferfish", "Kraken", "Anglerfish", "Sea Cucumber"),
            bossTitles = listOf("Kraken Queen", "Admiral", "Leviathan"),
            attacks = listOf("{foe} pinches Tama", "{foe} inflates alarmingly", "{foe} squirts ink everywhere", "{foe} sings a sea shanty, badly"),
            defeats = listOf("{foe} swims off in a huff.", "{foe} drifts away on a warm current.", "{foe} sinks gracefully into the deep."),
            wields = listOf("Trident", "Harpoon", "Conch Shell", "Fishing Rod"),
            charms = listOf("Pearl", "Message in a Bottle", "Sea Glass"),
            snacks = listOf("Seaweed Crackers", "Sea Grapes", "Fish-Shaped Biscuit"),
            hat = Hat.SAILOR, hatNames = listOf("Sailor Cap", "Captain's Cap"),
            npcs = listOf("a mermaid who runs a lighthouse", "a crab with a tiny law degree", "an old turtle who has seen it all"),
            quips = listOf("'Just keep swimming. Or floating. Floating is fine.'", "'The ocean is just a very big soup.'", "'Never pinch a crab. It's rude.'"),
            roles = listOf("Diver", "Sailor", "Pirate"),
            prefixes = listOf("Briny", "Coral", "Tidal"),
            moves = listOf("Bubble Barrage", "Tidal Wiggle", "Anchor Drop"),
            ambience = listOf("A whale sings somewhere far below.", "Bubbles rise like tiny balloons.", "A school of fish spells something rude, then scatters."),
            colors = intArrayOf(0xFFFF7F6E.toInt(), 0xFF2EC4B6.toInt(), 0xFF7F5AF0.toInt(), 0xFFFFD166.toInt()),
            look = Look(0xFF0A3550.toInt(), 0xFF13708A.toInt(), 0xFF0E4A5E.toInt(), 0xFF1E8A6E.toInt(), 0xFFD8C690.toInt(), 0xFFBFAE78.toInt(), Scenery.KELP, Drift.BUBBLES),
        ),
        Genre(
            "arcade",
            places = listOf("Arcade", "Pixel Kingdom", "Bonus Stage", "Neon Highway", "High-Score Table"),
            adjectives = listOf("8-Bit", "Turbo", "Glitched", "Neon", "Retro"),
            foes = listOf("Pixel Ghost", "Space Invader", "Paddle", "Glitch", "Polygon", "Coin-Op"),
            bossTitles = listOf("Final Boss", "High Scorer", "Mainframe"),
            attacks = listOf("{foe} inserts a coin", "{foe} pulls off a combo", "{foe} glitches through the floor", "{foe} plays a chiptune at Tama"),
            defeats = listOf("{foe} enters its initials and leaves.", "{foe} runs out of continues.", "{foe} bursts into a shower of points."),
            wields = listOf("Joystick", "Light Gun", "Floppy Disk", "Keytar"),
            charms = listOf("Extra Life", "Power Pellet", "Golden Token"),
            snacks = listOf("Power Pellet", "Arcade Popcorn", "8-Bit Apple"),
            hat = Hat.PROPELLER, hatNames = listOf("Propeller Beanie", "Turbo Beanie"),
            npcs = listOf("a joystick who has seen too much", "a pixel princess from another castle", "a speedrunner going very fast"),
            quips = listOf("'Up, up, down, down... I forget the rest.'", "'Blow on the cartridge. Trust me.'", "'Your princess is in another realm.'"),
            roles = listOf("Speedrunner", "Pixel Knight", "Gamer"),
            prefixes = listOf("Turbo", "8-Bit", "Neon"),
            moves = listOf("Combo Breaker", "Pixel Punch", "Konami Wiggle"),
            ambience = listOf("A high score flashes somewhere: TMA 999999.", "The floor hums with chiptunes.", "INSERT COIN, says the sky."),
            colors = intArrayOf(0xFFFF2E97.toInt(), 0xFF2DE2E6.toInt(), 0xFFF9F871.toInt(), 0xFF39FF14.toInt()),
            look = Look(0xFF0B0221.toInt(), 0xFF3B0A5C.toInt(), 0xFFFF4FA3.toInt(), 0xFF2DE2E6.toInt(), 0xFF12031F.toInt(), 0xFFFF2E97.toInt(), Scenery.SYNTH, Drift.NONE),
        ),
        Genre(
            "yokai",
            places = listOf("Bamboo Forest", "Mountain Shrine", "Lantern Festival", "Old Onsen Town", "Fox Shrine"),
            adjectives = listOf("Mischievous", "Thousand-Year", "Moonlit", "Shape-Shifting", "Haiku-Writing"),
            foes = listOf("Kappa", "Tanuki", "Tengu", "Kitsune", "Umbrella Yokai", "Lantern Ghost", "Rokurokubi"),
            bossTitles = listOf("Oni Lord", "Nine-Tailed Fox", "Mountain Tengu"),
            attacks = listOf("{foe} plays a prank on Tama", "{foe} turns into a teapot", "{foe} challenges Tama to sumo", "{foe} recites a very long haiku"),
            defeats = listOf("{foe} bows politely and vanishes into the mist.", "{foe} turns back into a leaf.", "{foe} accepts a cucumber as an apology."),
            wields = listOf("Paper Fan", "Bokken", "Bamboo Flute", "Paper Lantern"),
            charms = listOf("Omamori", "Maneki-Neko", "Daruma"),
            snacks = listOf("Dango", "Cucumber", "Taiyaki", "Onigiri"),
            hat = Hat.KASA, hatNames = listOf("Straw Kasa", "Traveller's Kasa"),
            npcs = listOf("a kappa running a cucumber stall", "a fox who is definitely not a person", "a tanuki with a briefcase"),
            quips = listOf("'Even monkeys fall from trees. Especially that one.'", "'Never bow to a kappa first.'", "'Fall down seven times, stand up eight, have a snack.'"),
            roles = listOf("Ronin", "Shrine Keeper", "Ninja"),
            prefixes = listOf("Moonlit", "Bamboo", "Lantern"),
            moves = listOf("Shuriken of Kindness", "Fox Fire Boop", "Sumo Shove"),
            ambience = listOf("Paper lanterns bob in the breeze.", "A shrine bell rings once, very far away.", "Bamboo creaks like an old door."),
            colors = intArrayOf(0xFF6AA84F.toInt(), 0xFF8D6E63.toInt(), 0xFFF57C00.toInt(), 0xFF5C6BC0.toInt()),
            look = Look(0xFF2B2B6B.toInt(), 0xFFE89AAE.toInt(), 0xFF4A3F7A.toInt(), 0xFFC2403B.toInt(), 0xFF8F8A80.toInt(), 0xFF6F6A62.toInt(), Scenery.TORII, Drift.PETALS),
        ),
        Genre(
            "disco",
            places = listOf("Roller Disco", "Nightclub", "Karaoke Box", "Funky Basement", "Dance Floor"),
            adjectives = listOf("Funky", "Groovy", "Glittering", "Boogie", "Platform-Shoed"),
            foes = listOf("Mirror Ball", "Bell-Bottoms", "Roller Skater", "Bass Speaker", "Karaoke Machine", "Lava Lamp"),
            bossTitles = listOf("Disco King", "DJ", "Dance Machine"),
            attacks = listOf("{foe} challenges Tama to a dance-off", "{foe} drops the bass", "{foe} sparkles blindingly", "{foe} does the worm"),
            defeats = listOf("{foe} boogies off the floor.", "{foe} spins away into the night.", "{foe} stays alive, stays alive, but leaves."),
            wields = listOf("Microphone", "Glow Stick", "Vinyl Record", "Tambourine"),
            charms = listOf("Disco Pin", "Mixtape", "Glitter Pouch"),
            snacks = listOf("Neon Soda", "Party Pretzel", "Glitter Gummies"),
            hat = Hat.PARTY, hatNames = listOf("Party Hat", "Glitter Cone"),
            npcs = listOf("a DJ who only plays one song", "a roller skater going in circles", "a karaoke machine with stage fright"),
            quips = listOf("'Dance like nobody's watching. Nobody is.'", "'The night is young and so are the snacks.'", "'Funk is a state of mind.'"),
            roles = listOf("Dancer", "DJ", "Roller Queen"),
            prefixes = listOf("Funky", "Groovy", "Glitter"),
            moves = listOf("Disco Inferno Boop", "Moonwalk Bump", "Glitter Bomb"),
            ambience = listOf("The floor lights up under each step.", "A bassline wanders by.", "Somebody shouts 'One more song!'"),
            colors = intArrayOf(0xFFF1C40F.toInt(), 0xFFFF3EA5.toInt(), 0xFFBDC3C7.toInt(), 0xFFFF7F50.toInt()),
            look = Look(0xFF1A0530.toInt(), 0xFF4B1270.toInt(), 0xFFC0C0D0.toInt(), 0xFF8A2BE2.toInt(), 0xFF222222.toInt(), 0xFFFF3EA5.toInt(), Scenery.DISCO, Drift.SPARKLES),
        ),
        Genre(
            "jungle",
            places = listOf("Lost Jungle", "Volcano Island", "Dinosaur Valley", "Ancient Ruins", "Waterfall Cave"),
            adjectives = listOf("Prehistoric", "Overgrown", "Volcanic", "Ancient", "Wild"),
            foes = listOf("Raptor", "Giant Frog", "Monkey", "Pterodactyl", "Carnivorous Plant", "Stone Idol"),
            bossTitles = listOf("Tyrant Lizard", "Volcano God", "Jungle Queen"),
            attacks = listOf("{foe} roars tremendously", "{foe} throws a coconut", "{foe} stomps the ground", "{foe} swings in on a vine"),
            defeats = listOf("{foe} goes extinct, temporarily.", "{foe} wanders back into the trees.", "{foe} becomes a fossil for a while."),
            wields = listOf("Boomerang", "Bone Club", "Torch", "Giant Leaf"),
            charms = listOf("Amber Bug", "Idol", "Dino Tooth"),
            snacks = listOf("Banana", "Coconut", "Mango", "Roasted Yam"),
            hat = Hat.SAFARI, hatNames = listOf("Pith Helmet", "Explorer's Hat"),
            npcs = listOf("an explorer who has been lost for years and loves it", "a parrot who repeats secrets", "a baby dinosaur"),
            quips = listOf("'Life finds a way. Usually to the snacks.'", "'Don't step on that. Or that.'", "'X marks the spot. Everything is X here.'"),
            roles = listOf("Explorer", "Tomb Raider", "Dino Tamer"),
            prefixes = listOf("Jungle", "Volcanic", "Wild"),
            moves = listOf("Vine Swing", "Volcano Sneeze", "Coconut Clonk"),
            ambience = listOf("A volcano rumbles, hungry.", "Something big moves in the ferns.", "Parrots argue overhead."),
            colors = intArrayOf(0xFF4CAF50.toInt(), 0xFFFF8F00.toInt(), 0xFF8D6E63.toInt(), 0xFF26A69A.toInt()),
            look = Look(0xFF7EC8A0.toInt(), 0xFFE3F2C1.toInt(), 0xFF6C5A4A.toInt(), 0xFF2F7A3A.toInt(), 0xFF7A5A3A.toInt(), 0xFF5E4430.toInt(), Scenery.VOLCANO, Drift.LEAVES),
        ),
    )

    val abstracts = listOf(
        "Weekend", "Left Sock", "Second Breakfast", "Wi-Fi Password", "Sense of Wonder", "Last Slice", "TV Remote",
        "Moon", "Colour Orange", "Sunday Afternoon", "Good Spoon", "Nap Schedule", "Missing Puzzle Piece",
    )

    val goals = listOf(
        "Return the {wield} of the {place} to the {boss}",
        "Find {npc}'s lost {charm}",
        "Deliver one (1) {snack} to the {boss}",
        "Recover the stolen {abstract}",
        "Apologise to the {boss} on behalf of a friend",
        "Settle a feud between the {foe}s and the {foe2}s",
        "Win back the {abstract} from the {boss}",
        "Throw a surprise party for the {boss}",
        "Return an overdue library book to the {boss}",
        "Teach the {boss} to say please",
    )

    val chestKinds = listOf("dusty", "suspiciously shiny", "slightly damp", "humming", "very small", "upside-down", "polite")
    val springs = listOf("hot spring", "warm sunbeam", "suspiciously comfortable beanbag", "puddle of pure moonlight", "heated kotatsu", "hammock")
    val merchants = listOf("A tanuki with a briefcase", "A vending machine with opinions", "A ghost running a pop-up shop", "A crab who takes cards", "A robot selling lemonade")
    val riddlers = listOf("A sphinx in reading glasses", "A door with a mouth", "A very old frog", "A talking vending machine", "A statue who blinks")
    val riddles = listOf(
        "What has keys but opens no doors?", "What gets wetter the more it dries?", "What is the airspeed of an unladen dumpling?",
        "Which came first, the onigiri or the rice?", "What do you call a sad cloud?", "If Tama falls in a forest, does it go boop?",
        "How many naps make a day?",
    )
    val answers = listOf("A piano!", "Onigiri.", "Boop.", "Tuesday?", "A towel!", "Seven, probably.", "Yes.")
    val rightAnswers = listOf("Correct, somehow.", "That's... actually right.", "The riddler weeps with joy.")
    val wrongAnswers = listOf("Wrong, but charmingly. It lets Tama pass anyway.", "Not even close. It lets Tama through out of pity.")

    val oddities = listOf(
        "The room is briefly a musical. Tama sings one note. It is a good note.",
        "Gravity takes a coffee break. Everyone floats politely until it comes back.",
        "A door marked NOT A TRAP is, for once, not a trap.",
        "Tama finds a mirror. The Tama in the mirror waves first.",
        "A sign reads YOU ARE HERE. Tama is, in fact, there.",
        "Everything goes black and white for a moment. Then it remembers its colours.",
        "A narrator clears their throat, then thinks better of it.",
        "Tama steps on a rake. The rake apologises.",
        "It rains upwards for a bit. Nobody mentions it.",
        "A very small dragon asks Tama for directions. Tama points confidently the wrong way.",
        "Tama finds a staircase that only goes sideways.",
        "Two {foe}s are having a tea party. Tama is offered a biscuit.",
        "A wise old {foe} offers Tama a riddle, then forgets it.",
        "The floor is lava. Then it is carpet again. Tama never finds out why.",
        "A pigeon delivers a letter addressed to 'the round one'. It is blank.",
        "Tama stubs its toe on the fourth wall.",
        "GENRE SHIFT! For one whole minute everything here is a {genre} story.",
        "Somewhere far away, someone looks at this frame. Tama waves, just in case.",
        "A cat sits in the middle of the path and will not be moved. Tama goes around.",
        "Tama finds a lost save point and saves, just to feel safe.",
    )

    val assists = listOf(
        "throws a pebble", "cheers loudly", "trips the {foe}", "does a distracting little dance",
        "offers the {foe} a snack", "reads the {foe} its rights", "points at something behind the {foe}",
    )
    val tamaHits = listOf("Tama bonks the {foe} for {n}.", "Tama boops the {foe} for {n}.", "Tama bumps the {foe} with its tummy for {n}.", "Tama uses {move}! {n} damage.")
    val tamaCrits = listOf("CRITICAL! Tama uses {move} on the {foe} for {n}!", "CRITICAL! An enormous boop! {n} damage!")
    val tamaMisses = listOf("Tama trips over its own tuft. Miss!", "Tama winds up, then forgets why. Miss.", "Tama sneezes at the wrong moment. Miss.")
    val foeMisses = listOf("The {foe} misses. Tama didn't even notice.", "The {foe} attacks, but Tama is too round to hit.", "The {foe} hesitates. Tama is just too cute.")
    val rescuers = listOf("A kindly giant snail", "A passing ambulance-bird", "Some helpful frogs", "A very strong butterfly", "The narrator, reluctantly", "A friendly {foe}")
    val stories = listOf(
        "{ally} tells a story about a haunted sock.", "Tama counts the stars and gets to eleven.",
        "Marshmallows are toasted. One is lost to the fire.", "Tama polishes its {wield}.",
        "{ally} snores like a small engine.", "Tama writes in its diary: 'Today I was brave.'",
    )
    val beds = listOf("pile of leaves", "borrowed sleeping bag", "large teacup", "hammock", "nest of socks")
    val inns = listOf("Inn of Lukewarm Soup", "Hotel Eventually", "Sniffle Hut", "Clinic of Dr. Owl", "Hot Water Bottle Lodge")
    val innQuips = listOf(
        "Tama is tucked in with a hot water bottle.", "{ally} reads Tama a very boring story.",
        "Soup is brought. It is lukewarm, as promised.", "Tama sneezes, and a {foe} outside says bless you.",
        "A nurse-owl takes Tama's temperature with a feather.",
    )
    val perks = listOf(
        "Tama learns to whistle.", "Tama's tuft grows slightly more heroic.", "Tama can now count to twelve.",
        "Tama learns a new dance move.", "Tama feels a bit taller. It isn't.", "Tama's boop is now legally a weapon.",
    )
    val itemAdjectives = listOf(
        "Mildly Enchanted", "Second-Hand", "Legendary", "Slightly Sticky", "Unreasonably Shiny", "Artisanal",
        "Limited-Edition", "Very Tiny", "Overdue", "Borrowed", "Gently Used",
    )
    val itemNouns = listOf(
        "Mild Inconvenience", "the Long Lunch", "Tuesdays", "Snacking", "Polite Doom", "Gentle Breezes",
        "Extra Pockets", "the Third Cup", "Dramatic Entrances", "Napping", "Small Victories",
    )

    /** Fill {placeholders}; anything unknown is left as it is. */
    fun fill(template: String, values: Map<String, String>): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val open = template.indexOf('{', i)
            if (open < 0) {
                out.append(template, i, template.length)
                break
            }
            val close = template.indexOf('}', open)
            if (close < 0) {
                out.append(template, i, template.length)
                break
            }
            out.append(template, i, open)
            val key = template.substring(open + 1, close)
            out.append(values[key] ?: template.substring(open, close + 1))
            i = close + 1
        }
        // A line that starts with a name gets a capital, so "the Kappa" reads well either way.
        return out.toString().replaceFirstChar { it.uppercase() }
    }

    fun <T> List<T>.pick(random: Random) = this[random.nextInt(size)]
}

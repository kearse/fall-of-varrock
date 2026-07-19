package org.alter.plugins.content.teleport

import org.alter.game.model.Tile
import org.alter.plugins.content.teleport.DangerTag.*
import org.alter.plugins.content.teleport.DestState.COMING_SOON
import org.alter.plugins.content.teleport.TeleportCategory.*

/**
 * The portal's destination catalog — **adding a teleport = adding a line here**
 * (mirrors the `BossRegistry` / `BotZones` registry pattern).
 *
 * BUILT entries carry a real landing [Tile] (verified against the content plugins:
 * the skilling plaza E of Lumbridge castle, the cellar mine/forge, the PK-bot
 * wilderness corridor, the war fronts, the boss lairs). COMING_SOON entries are
 * roadmap placeholders shown greyed in the UI; they share [PLACEHOLDER] since they
 * never actually teleport (guarded in [TeleportService]). Light them up by giving
 * them a real tile + `BUILT` as each is built.
 *
 * Tiles derived from existing content are marked TUNE where the exact stand-on
 * square should be confirmed in-game.
 */
object TeleportRegistry {

    /** Landing tile for not-yet-built destinations (never used — guarded by isBuilt). */
    private val PLACEHOLDER = Tile(3222, 3218, 0)

    private fun built(
        key: String, name: String, cat: TeleportCategory, tile: Tile,
        danger: DangerTag, wild: Int? = null,
    ) = TeleportDestination(key, name, cat, tile, danger, wild)

    private fun soon(
        key: String, name: String, cat: TeleportCategory,
        danger: DangerTag = SAFE_ZONE, wild: Int? = null,
    ) = TeleportDestination(key, name, cat, PLACEHOLDER, danger, wild, COMING_SOON)

    val all: List<TeleportDestination> = listOf(

        // ── 🏠 Basics ──────────────────────────────────────────────────────────
        built("home", "Home (Lumbridge)", BASICS, Tile(3222, 3218, 0), SAFE_ZONE),
        built("market", "Market / Shops", BASICS, Tile(3221, 3216, 0), SAFE_ZONE),
        built("prayer_altar", "Prayer Altar", BASICS, Tile(3242, 3207, 0), SAFE_ZONE),
        soon("gambling", "Gambling (Flower Poker)", BASICS),
        soon("dice", "Dice Zone", BASICS),
        soon("blackjack", "Blackjack", BASICS),
        soon("party_zone", "Party Zone", BASICS),

        // ── 🛠️ Skilling (full per-skill sub-list) ─────────────────────────────
        // All spots cluster in the Lumbridge skilling plaza (E of the castle) except
        // mining/smithing, which are in the castle cellar. TUNE stand-on tiles in-game.
        // The Mire — the war-supply hub. The WORKING YARD is the Lumbridge graveyard (central, by the
        // castle; gravestones stripped) — bank + processing + stalls + the Quartermaster crypt. The
        // COLLECTION GROUNDS (trees/rocks/fish/herbs/thickets) are the swamp just south. Skilling rows
        // land in the yard (pad @ 3243,3193). Mining/Smithing keep the cellar; old plaza spots = legacy.
        // ONE hub row for the Mire working yard — the old per-skill rows (woodcutting/
        // firemaking/fishing/cooking/crafting/runecraft/farming) all landed on this same
        // pad, which read as a bug from the player's side (UX review). Skills with a
        // genuinely distinct destination keep their own row below.
        built("swamp_hub", "The Mire — Skilling Grounds", SKILLING, Tile(3243, 3193, 0), SAFE_ZONE),
        built("skill_mining", "Mining", SKILLING, Tile(3214, 9617, 0), SAFE_ZONE),       // cellar mine room
        built("skill_smithing", "Smithing", SKILLING, Tile(3210, 9620, 0), SAFE_ZONE),   // cellar furnace + anvils
        built("skill_construction", "Construction", SKILLING, Tile(3239, 3205, 0), SAFE_ZONE), // bench
        built("skill_hunter", "Hunter", SKILLING, Tile(3231, 3172, 0), SAFE_ZONE),       // the Croaking Thickets
        built("skill_agility", "Agility", SKILLING, Tile(3227, 3174, 0), SAFE_ZONE),     // the Mire Run dispenser
        built("skill_herblore", "Herblore", SKILLING, Tile(3236, 3206, 0), SAFE_ZONE),   // TUNE (inventory skill)
        built("skill_fletching", "Fletching", SKILLING, Tile(3229, 3207, 0), SAFE_ZONE), // TUNE (inventory skill)
        built("skill_thieving", "Thieving", SKILLING, Tile(2979, 3394, 0), SAFE_ZONE),   // Falador north square (neutral)

        // ── 🗡️ The War ─────────────────────────────────────────────────────────
        built("varrock_raid", "Varrock Raid", WAR, Tile(3213, 3424, 0), HOSTILE),        // hostile target city (§3C)
        built("north_frontier", "North Frontier", WAR, Tile(3222, 3270, 0), WILD, wild = 5),
        built("goblin_warren", "Goblin Warren", WAR, Tile(3290, 3248, 0), HOSTILE),      // E of Lumbridge horde muster
        built("recruit_trials", "Recruit Trials", WAR, Tile(3219, 3213, 0), SAFE_ZONE),  // Sergeant at the gate
        soon("active_campaign", "Active Campaign", WAR, HOSTILE), // dynamic muster — wire to Campaigns later

        // ── 💀 Bosses ──────────────────────────────────────────────────────────
        built("kbd", "King Black Dragon", BOSSES, Tile(2273, 4685, 0), HOSTILE),         // KBD lair
        built("corp_beast", "Corporeal Beast", BOSSES, Tile(3247, 3319, 0), HOSTILE),    // apex city world boss
        soon("world_boss", "World Boss", BOSSES, HOSTILE), // rotating spawn — wire to WorldBoss later
        soon("zulrah", "Zulrah", BOSSES),
        built("barrows", "Barrows", BOSSES, Tile(3565, 3306, 0), SAFE_ZONE), // Phase B — mounds (dig to enter a crypt)
        built("kraken", "Kraken", BOSSES, Tile(2279, 10012, 0), HOSTILE),    // Phase D — Kraken Cove (Slayer 87)
        // Elite / endgame bosses (see EliteBosses).
        built("nex", "Nex", BOSSES, Tile(2924, 5202, 2), HOSTILE),
        built("vorkath", "Vorkath", BOSSES, Tile(2272, 4052, 0), HOSTILE),
        built("alchemical_hydra", "Alchemical Hydra", BOSSES, Tile(1361, 10231, 0), HOSTILE),
        built("phantom_muspah", "Phantom Muspah", BOSSES, Tile(2117, 5645, 0), HOSTILE),
        built("abyssal_sire", "Abyssal Sire", BOSSES, Tile(2970, 4384, 0), HOSTILE),
        built("grotesque_guardians", "Grotesque Guardians", BOSSES, Tile(3413, 3537, 0), HOSTILE),
        // Phase F — God Wars Dungeon generals (real throne rooms; see GodWarsBosses).
        built("gwd_graardor", "General Graardor", BOSSES, Tile(2870, 5362, 2), HOSTILE),
        built("gwd_kril", "K'ril Tsutsaroth", BOSSES, Tile(2926, 5325, 2), HOSTILE),
        built("gwd_kreearra", "Kree'arra", BOSSES, Tile(2832, 5302, 2), HOSTILE),
        built("gwd_zilyana", "Commander Zilyana", BOSSES, Tile(2897, 5300, 2), HOSTILE),
        // Phase C — wilderness single bosses (real OSRS lairs; see WildernessBosses).
        built("callisto", "Callisto", BOSSES, Tile(3300, 3840, 0), WILD, wild = 40),
        built("vetion", "Vet'ion", BOSSES, Tile(3239, 3779, 0), WILD, wild = 32),
        built("venenatis", "Venenatis", BOSSES, Tile(3315, 3743, 0), WILD, wild = 33),
        built("scorpia", "Scorpia", BOSSES, Tile(3232, 10337, 0), WILD, wild = 54),
        built("chaos_elemental", "Chaos Elemental", BOSSES, Tile(3279, 3916, 0), WILD, wild = 50),
        built("chaos_fanatic", "Chaos Fanatic", BOSSES, Tile(2978, 3851, 0), WILD, wild = 42),
        built("crazy_arch", "Crazy Archaeologist", BOSSES, Tile(2980, 3690, 0), WILD, wild = 24),
        // Phase D — Slayer / PvM bosses (real lairs; see PvmBosses).
        built("demonic_gorillas", "Demonic Gorillas", BOSSES, Tile(2418, 9774, 0), HOSTILE),
        built("skotizo", "Skotizo", BOSSES, Tile(1721, 10090, 0), HOSTILE),
        built("cerberus", "Cerberus", BOSSES, Tile(1311, 1250, 0), HOSTILE),
        built("giant_mole", "Giant Mole", BOSSES, Tile(1760, 5164, 0), HOSTILE),
        built("kalphite_queen", "Kalphite Queen", BOSSES, Tile(3508, 9494, 2), HOSTILE),
        built("sarachnis", "Sarachnis", BOSSES, Tile(1923, 9921, 0), HOSTILE),
        built("smoke_devil", "Thermonuclear Smoke Devil", BOSSES, Tile(2384, 9452, 0), HOSTILE),
        built("dagannoth_kings", "Dagannoth Kings", BOSSES, Tile(2899, 4449, 0), HOSTILE),
        soon("theatre_of_blood", "Theatre of Blood", BOSSES),
        soon("chambers_of_xeric", "Chambers of Xeric", BOSSES),
        built("revenant_caves", "Revenant Caves", BOSSES, Tile(3220, 10140, 0), WILD, wild = 17),

        // ── ⚔️ Wilderness / PvP (the PK-bot corridor, south → north) ───────────
        built("outlaw_camp", "Outlaw Camp", WILDERNESS, Tile(3235, 3345, 0), WILD, wild = 5),
        built("marauder_grounds", "Marauder Grounds", WILDERNESS, Tile(3235, 3382, 0), WILD, wild = 12),
        built("raider_fields", "Raider Fields", WILDERNESS, Tile(3232, 3423, 0), WILD, wild = 20),
        built("warlords_approach", "Warlord's Approach", WILDERNESS, Tile(3229, 3475, 0), WILD, wild = 30),
        built("wilderness_pkers", "Wilderness PKers", WILDERNESS, Tile(3170, 3560, 0), WILD, wild = 40),
        built("deep_wilderness_pkers", "Deep Wilderness PKers", WILDERNESS, Tile(3170, 3700, 0), WILD, wild = 55),
        soon("fun_pk", "Fun-PK Zone", WILDERNESS, SAFE_PVP),
        soon("risk_zone", "Risk Zone", WILDERNESS, SAFE_BANK),
        soon("edge_brid", "Edge PvP (Brid Zone)", WILDERNESS, SAFE_BANK),
        soon("camelot_pvp", "Camelot PvP", WILDERNESS, SAFE_BANK),
        soon("f2p_zone", "F2P Zone", WILDERNESS, SAFE_BANK),
        soon("mage_bank", "Mage Bank", WILDERNESS),
        soon("ferox_enclave", "Ferox Enclave", WILDERNESS),

        // ── 🩸 Slayer ──────────────────────────────────────────────────────────
        built("slayer_master", "Slayer Master", SLAYER, Tile(3219, 3216, 0), SAFE_ZONE), // Vannaka
        soon("slayer_cave", "Slayer Cave", SLAYER),
        soon("resource_contracts", "Resource Contracts", SLAYER),

        // ── 🎮 Mini-Games ──────────────────────────────────────────────────────
        // Lands at the live cave entrance beside TzHaar-Mej-Jal (the game-master) — talk to
        // him to start a run or a Jad practice; the fight itself is a private instance.
        built("fight_cave", "Fight Cave", MINIGAMES, Tile(2413, 5117, 0), SAFE_ZONE),
        // Same landing — TzHaar-Ket-Keh stands beside Mej-Jal; sacrifice a Fire cape to him
        // for permanent access, then he starts runs / Zuk practice (private instance).
        built("inferno", "The Inferno", MINIGAMES, Tile(2413, 5117, 0), SAFE_ZONE),
        // Lands in front of the Void Knight at the mainland end of the tower bridge — talk to
        // him to start a solo or multi game; the instanced fight is elsewhere.
        built("wizard_tower", "Wizard Tower", MINIGAMES, Tile(3113, 3211, 0), SAFE_ZONE),
        soon("castle_wars", "Castle Wars", MINIGAMES),
        soon("last_man_standing", "Last Man Standing", MINIGAMES),
        soon("duel_arena", "Duel Arena", MINIGAMES),

        // ── ❤️ Events (all roadmap — dynamic timers come in Phase 4) ───────────
        soon("hp_event", "HP Event", EVENTS),
        soon("tournament", "Automatic Tournament", EVENTS),
        soon("bloodlust", "Bloodlust", EVENTS),
        soon("treasure_hunt", "Treasure Hunt", EVENTS),
        soon("clan_warfare", "Clan Warfare", EVENTS),
        soon("vote_boss", "Vote Boss", EVENTS),

        // ── 💎 Donator (all roadmap) ───────────────────────────────────────────
        soon("donator_zone", "Donator Zone", DONATOR),
        soon("donator_dungeon", "Donator Dungeon", DONATOR, WILD, wild = 30),
        soon("royal_pvm", "Royal PvM Zone", DONATOR),
        soon("royal_skilling", "Royal Skilling Zone", DONATOR),
        soon("divine_donator", "Divine Donator", DONATOR),
        soon("divine_dungeon", "Divine Monster Dungeon", DONATOR),
        soon("divine_skilling", "Divine Skilling Zone", DONATOR),
        soon("divine_slayer", "Divine Slayer Cave", DONATOR),
    )

    private val byKey: Map<String, TeleportDestination> = all.associateBy { it.key }

    fun byKey(key: String): TeleportDestination? = byKey[key]

    /** Destinations in [category], in catalog order. */
    fun inCategory(category: TeleportCategory): List<TeleportDestination> =
        all.filter { it.category == category }
}

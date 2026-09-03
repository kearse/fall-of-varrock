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
        built("home", "Home (Lumbridge)", BASICS, Tile(3209, 3216, 0), SAFE_ZONE),
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
        built("skill_construction", "Construction", SKILLING, Tile(3244, 3202, 0), SAFE_ZONE), // beside the yard workbench (ConstructionPlugin.benchTile 3245,3202)
        built("skill_hunter", "Hunter", SKILLING, Tile(3231, 3172, 0), SAFE_ZONE),       // the Croaking Thickets
        built("skill_agility", "Agility", SKILLING, Tile(3227, 3174, 0), SAFE_ZONE),     // the Mire Run dispenser
        built("skill_herblore", "Herblore", SKILLING, Tile(3240, 3193, 0), SAFE_ZONE),   // Mire yard, W of the pad (r12849 dump: clear)
        built("skill_fletching", "Fletching", SKILLING, Tile(3246, 3193, 0), SAFE_ZONE), // Mire yard, E of the pad (r12849 dump: clear)
        built("skill_thieving", "Thieving", SKILLING, Tile(3242, 3189, 0), SAFE_ZONE),   // Mire yard stall row (SwampStallSpawnPlugin) — the supply-skilling hub

        // ── 🗡️ The War ─────────────────────────────────────────────────────────
        built("varrock_raid", "Varrock Raid", WAR, Tile(3213, 3424, 0), HOSTILE),        // hostile target city (§3C)
        built("north_frontier", "North Frontier", WAR, Tile(3222, 3270, 0), WILD, wild = 5),
        built("goblin_warren", "Goblin Warren", WAR, Tile(3290, 3248, 0), HOSTILE),      // E of Lumbridge horde muster
        built("recruit_trials", "Recruit Trials", WAR, Tile(3219, 3213, 0), SAFE_ZONE),  // Sergeant at the gate
        soon("active_campaign", "Active Campaign", WAR, HOSTILE), // dynamic muster — wire to Campaigns later

        // ── 💀 Bosses ──────────────────────────────────────────────────────────
        // The hand-built boss roster was removed (see the reboot brief) — bosses return
        // one by one as properly-ported fights. Every entry below is a roadmap placeholder
        // except the war's world-boss event arena, which is live war content.
        built("corp_beast", "Corp Beast (Event)", BOSSES, Tile(3247, 3319, 0), HOSTILE), // Lumbridge world-boss event arena (::worldboss)
        soon("world_boss", "World Boss", BOSSES, HOSTILE), // rotating spawn — wire to WorldBoss later
        // Lands inside the KBD lair island (Kronos port #7, the lair-boss package). TUNE.
        built("kbd", "King Black Dragon", BOSSES, Tile(2271, 4680, 0), HOSTILE),
        soon("corporeal_beast", "Corporeal Beast", BOSSES, HOSTILE),
        // Lands at the Zul-Andra dock — board the sacred-eel boat (or ::zulrah) for a
        // solo instanced shrine fight (Kronos port #2).
        built("zulrah", "Zulrah", BOSSES, Tile(2199, 3056, 0), HOSTILE),
        // Lands just north of the mounds — dig into a mound with a spade to enter its crypt
        // (Kronos port #6: the full crypt run, `content/minigames/barrows/`).
        built("barrows", "Barrows", BOSSES, Tile(3565, 3306, 0), SAFE_ZONE),
        // Lair-boss package (Kronos port #7): shared-world, multi-way lairs. TUNE stand-on tiles.
        built("giant_mole", "Giant Mole", BOSSES, Tile(1760, 5175, 0), HOSTILE),
        built("kalphite_queen", "Kalphite Queen", BOSSES, Tile(3480, 9483, 0), HOSTILE),
        built("dagannoth_kings", "Dagannoth Kings", BOSSES, Tile(2898, 4450, 0), HOSTILE),
        // Lands on Ungael outside the icy spines — climb over them to enter the solo
        // instanced arena and poke the sleeping beast (the Kronos-port pilot).
        built("vorkath", "Vorkath", BOSSES, Tile(2272, 4052, 0), HOSTILE),
        // Lands by the lab rocks — climb them (or ::hydra) for the solo instanced
        // chamber and its chemical vents (Kronos port #3).
        built("alchemical_hydra", "Alchemical Hydra", BOSSES, Tile(1351, 10249, 0), HOSTILE),
        // God Wars throne rooms (Kronos port #5, the package port) — shared-world camps,
        // landing at each room's edge. TUNE stand-on tiles in-game.
        built("gwd_graardor", "General Graardor", BOSSES, Tile(2871, 5352, 2), HOSTILE),
        built("gwd_kril", "K'ril Tsutsaroth", BOSSES, Tile(2926, 5320, 2), HOSTILE),
        built("gwd_kreearra", "Kree'arra", BOSSES, Tile(2838, 5295, 2), HOSTILE),
        built("gwd_zilyana", "Commander Zilyana", BOSSES, Tile(2907, 5263, 0), HOSTILE),
        // Wilderness-boss package (Kronos port #8): the donor's surface lairs, all multi-way.
        // Landing tiles sit a few squares off each spawn — TUNE in-game.
        built("callisto", "Callisto", BOSSES, Tile(3287, 3840, 0), WILD, wild = 41),
        built("vetion", "Vet'ion", BOSSES, Tile(3218, 3782, 0), WILD, wild = 34),
        built("venenatis", "Venenatis", BOSSES, Tile(3332, 3734, 0), WILD, wild = 28),
        built("scorpia", "Scorpia", BOSSES, Tile(3232, 10335, 0), WILD, wild = 54),
        built("chaos_elemental", "Chaos Elemental", BOSSES, Tile(3248, 3918, 0), WILD, wild = 51),
        built("chaos_fanatic", "Chaos Fanatic", BOSSES, Tile(2976, 3840, 0), WILD, wild = 41),
        built("crazy_archaeologist", "Crazy Archaeologist", BOSSES, Tile(2972, 3696, 0), WILD, wild = 23),
        // Slayer-boss package (Kronos port #9). Kraken/Cerberus/Thermy are Slayer-gated (87/91/93);
        // Skotizo needs a dark totem at the catacombs altar. TUNE stand-on tiles in-game.
        built("kraken", "Kraken", BOSSES, Tile(2276, 10030, 0), HOSTILE),
        built("cerberus", "Cerberus", BOSSES, Tile(1240, 1240, 0), HOSTILE),
        built("thermonuclear_smoke_devil", "Thermonuclear Smoke Devil", BOSSES, Tile(2360, 9445, 0), HOSTILE),
        built("skotizo", "Skotizo (Catacombs altar)", BOSSES, Tile(1665, 10046, 0), HOSTILE),
        built("demonic_gorillas", "Demonic Gorillas", BOSSES, Tile(2090, 5660, 0), HOSTILE),
        soon("theatre_of_blood", "Theatre of Blood", BOSSES),
        soon("chambers_of_xeric", "Chambers of Xeric", BOSSES),
        soon("revenant_caves", "Revenant Caves", BOSSES, WILD, wild = 17),

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
        built("slayer_master", "Slayer Master", SLAYER, Tile(3222, 3214, 0), SAFE_ZONE), // in front of Vannaka's GE-hub desk
        soon("slayer_cave", "Slayer Cave", SLAYER),
        soon("resource_contracts", "Resource Contracts", SLAYER),

        // ── 🎮 Mini-Games ──────────────────────────────────────────────────────
        // Lands in front of the Void Knight at the mainland end of the tower bridge — talk to
        // him to start a solo or multi game; the instanced fight is elsewhere.
        built("wizard_tower", "Wizard Tower", MINIGAMES, Tile(3113, 3211, 0), SAFE_ZONE),
        // Lands beside TzHaar-Mej-Jal at the live cave entrance — talk to him (or ::arena)
        // to start a run, ::jad to practice (Kronos port #4: the real 63-wave engine).
        built("fight_cave", "Fight Cave", MINIGAMES, Tile(2413, 5117, 0), SAFE_ZONE),
        soon("inferno", "The Inferno", MINIGAMES),
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

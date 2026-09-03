package org.alter.plugins.content.hostilezones

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.combat.NpcCombatDef
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * The authored hostile zones (docs/hostile-zones.md). PURE DATA — see [HostileZones] for the
 * classload rules (no PvpZones / BotZones / RogueKnights / RSCM references here).
 *
 * **The Wild Bandit Stronghold** (operator decision 2026-09-02: the first zone, LIVE) sits on the
 * existing Wild Bandit Camp — already red, single-combat ground with the ladder's pinned maxer
 * warband and three named knights (Morvane / Halric / Nyx) — so shipping it creates NO new PvP
 * area. It adds the extraction loop on top: loot spots in the tents and the "duty free" armoury,
 * an hourly supply drop, a bandit garrison, and two smugglers' trapdoors that channel a raider out
 * to Edgeville — the design authority's PvP staging town. All coordinates and tables TUNE.
 *
 * Loot is deliberately mid-tier and tradeable (supplies, runes, rune/black d'hide/mystic gear,
 * dragon dagger at the top): tempting on a maxer camp, never BIS or story-gated, no coins, no
 * Blood Money. The rare/supply-drop pool is the recognisable "everyone converges" tier.
 */
object HostileZoneCatalog {

    /** Loot-spot reroll delay (ticks) after a spot is taken — ~4 min, so 16 camped spots cap at
     *  ~240 rolls/hour (Team 2's cash-out band). TUNE. */
    private const val SPOT_RESPAWN_TICKS = 400

    private fun spot(x: Int, z: Int) = LootSpot(x, z, respawnTicks = SPOT_RESPAWN_TICKS)

    /** The stronghold's bandit garrison — a shade above the Bandit Hideout's road bandits. */
    private val STRONGHOLD_BANDIT_DEF = NpcCombatDef.DEFAULT.copy(
        attack = 40, strength = 50, defence = 20, hitpoints = 45,
        attackSpeed = 4, attackAnimation = 422, blockAnimation = 424, deathAnimation = listOf(836),
        aggressiveRadius = 6, aggroTargetDelay = 4, aggressiveTimer = 400,
    )

    /** The tents: the sustain a PKer burns — food, potions, a rare brew. */
    private val TENTS_TABLE = DropTable(
        main = listOf(
            DropEntry("item.shark", 3, 8, weight = 5),
            DropEntry("item.monkfish", 5, 10, weight = 5),
            DropEntry("item.cooked_karambwan", 4, 8, weight = 4),
            DropEntry("item.super_restore4", 1, 1, weight = 3),
            DropEntry("item.prayer_potion4", 1, 2, weight = 4),
            DropEntry("item.super_combat_potion4", 1, 1, weight = 2),
            DropEntry("item.ranging_potion4", 1, 1, weight = 2),
            DropEntry("item.stamina_potion4", 1, 1, weight = 2),
        ),
        rare = listOf(DropEntry("item.saradomin_brew4", 1, 1, oneInN = 20)),
    )

    /** The "duty free": the bandits' stolen armoury — runes, ammo, rune/d'hide/mystic gear.
     *  Stack sizes trimmed per Team 2's cash-out audit (every tradeable liquidates at 70% of
     *  cache cost, so rune stacks dominated the zone's gp/hour) — target band ~600-900k
     *  cache-value/hour for a solo camper at full uptime. */
    private val ARMOURY_TABLE = DropTable(
        main = listOf(
            DropEntry("item.blood_rune", 5, 15, weight = 4),
            DropEntry("item.death_rune", 10, 30, weight = 4),
            DropEntry("item.chaos_rune", 20, 50, weight = 4),
            DropEntry("item.nature_rune", 10, 25, weight = 3),
            DropEntry("item.law_rune", 10, 20, weight = 3),
            DropEntry("item.magic_logs", 5, 12, weight = 2),
            DropEntry("item.rune_arrow", 25, 75, weight = 3),
            DropEntry("item.adamant_scimitar", weight = 2),
            DropEntry("item.rune_scimitar", weight = 2),
            DropEntry("item.rune_full_helm", weight = 2),
            DropEntry("item.rune_platelegs", weight = 2),
            DropEntry("item.rune_kiteshield", weight = 2),
            DropEntry("item.mithril_platebody", weight = 2),
            DropEntry("item.black_dhide_chaps", weight = 2),
            DropEntry("item.black_dhide_vambraces", weight = 2),
            DropEntry("item.mystic_robe_bottom", weight = 1),
        ),
        rare = listOf(
            DropEntry("item.rune_platebody", oneInN = 15),
            DropEntry("item.black_dhide_body", oneInN = 15),
            DropEntry("item.dragon_dagger", oneInN = 25),
        ),
    )

    /** The supply drop: one recognisable prize, broadcast on land and on claim. */
    private val STRONGHOLD_RARE = DropTable(
        main = listOf(
            DropEntry("item.dragon_scimitar", announce = true),
            DropEntry("item.dragon_boots", announce = true),
            DropEntry("item.rune_crossbow", announce = true),
            DropEntry("item.amulet_of_glory4", announce = true),
            DropEntry("item.magic_shortbow", announce = true),
            DropEntry("item.dragon_med_helm", announce = true),
            DropEntry("item.dragon_longsword", announce = true),
            DropEntry("item.granite_maul", announce = true),
            DropEntry("item.helm_of_neitiznot", announce = true),
        ),
    )

    /** What a garrison bandit drops to the player who kills it. */
    private val BANDIT_KILL_TABLE = DropTable(
        main = listOf(
            DropEntry("item.chaos_rune", 5, 15, weight = 3),
            DropEntry("item.lobster", 1, 3, weight = 3),
            DropEntry("item.adamant_arrow", 5, 20, weight = 2),
        ),
        rare = listOf(DropEntry("item.rune_scimitar", oneInN = 40)),
    )

    val WILD_BANDIT_STRONGHOLD = HostileZoneConfig(
        key = "wild_bandit_stronghold",
        display = "the Wild Bandit Stronghold",
        kind = HostileZoneKind.ROGUE_STRONGHOLD,
        area = Area(3020, 3675, 3055, 3705), // = the BotZones / PvpZones SINGLE camp box — TUNE together
        wildLevel = null,                   // inherit the depth level (~52-56): never softer than its surroundings
        districts = listOf(
            LootDistrict(
                key = "tents", display = "the Tents",
                area = Area(3020, 3675, 3037, 3705), table = TENTS_TABLE,
                spots = listOf( // seeded from the camp's ambient bandit tiles (proven walkable); auto-snapped
                    spot(3024, 3696), spot(3025, 3701), spot(3029, 3701), spot(3031, 3685),
                    spot(3032, 3695), spot(3033, 3705), spot(3035, 3703), spot(3037, 3683),
                ),
            ),
            LootDistrict(
                key = "duty_free", display = "the Duty Free",
                area = Area(3038, 3675, 3055, 3705), table = ARMOURY_TABLE,
                spots = listOf(
                    spot(3038, 3691), spot(3039, 3700), spot(3040, 3683), spot(3040, 3703),
                    spot(3044, 3703), spot(3046, 3688), spot(3049, 3691), spot(3049, 3695),
                ),
            ),
        ),
        rareTable = STRONGHOLD_RARE,
        extractionPoints = listOf(
            // Open-trapdoor model with the "Climb-down" verb (1580 is the closed "Open" variant).
            ExtractionPoint(Tile(3037, 3677, 0), "object.trapdoor_1581", "Climb-down", "the smugglers' trapdoor"),
            ExtractionPoint(Tile(3052, 3703, 0), "object.trapdoor_1581", "Climb-down", "the bolt-hole"),
        ),
        exitTile = Tile(3087, 3502, 0), // an Edgeville street — the PvP staging town (safe carve-out). TUNE
        occupiers = listOf(
            OccupierLine(
                npcName = "npc.bandit_737", // rogue-family "Bandit", zero ambient rows, no other owner
                count = 8, combatDef = STRONGHOLD_BANDIT_DEF,
                spacing = 4, walkRadius = 5, singleCombat = true, respawnDelay = 20,
                displayNoun = "bandit", lootTable = BANDIT_KILL_TABLE,
            ),
        ),
        raiders = null,     // the camp's pinned wild_bandit_camp PK-bot colony already prowls it
        supplyDrop = true,
    )

    val all: List<HostileZoneConfig> = listOf(WILD_BANDIT_STRONGHOLD)
}

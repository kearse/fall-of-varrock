package org.alter.plugins.content.npcs.elite

import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * The **elite / endgame boss roster** (the "G-tier" single-NPC bosses): Vorkath, Alchemical
 * Hydra, Phantom Muspah, Abyssal Sire, Nex, and the Grotesque Guardians (Dawn + Dusk). Each is
 * spawned at its real lair by [EliteBossPlugin]; [EliteBossCombatPlugin] runs its **phased**
 * rotation (mechanics keyed by [key]). Raids (ToB/CoX) and the Inferno stay deferred (they
 * need the wave/room framework).
 *
 * **Adding an elite boss = adding an [EliteBoss] here.** Lairs are real coords (TUNE);
 * [org.alter.plugins.content.bosses.BossLairs] force-loads + multi-flags them. Mechanics are
 * faithful-but-simplified (documented per boss in the combat plugin).
 */
object EliteBosses {

    data class EliteBoss(
        val key: String,
        val name: String,
        val lair: Tile,
        val walkRadius: Int,
        val hp: Int,
        val att: Int,
        val str: Int,
        val def: Int,
        val mag: Int,
        val rng: Int,
        val attackSpeed: Int,
        val maxHit: Int,
        val approach: Int,
        val bossPoints: Int,
        val slayerReq: Int,
        val slayerXp: Double,
        val multi: Boolean,
        val loot: DropTable,
    )

    private fun coins(min: Int, max: Int, w: Int) = DropEntry("item.coins_995", min, max, weight = w)

    val all: List<EliteBoss> = listOf(
        EliteBoss(
            key = "npc.vorkath", name = "Vorkath",
            lair = Tile(2272, 4052, 0), walkRadius = 5,
            hp = 750, att = 1, str = 1, def = 214, mag = 150, rng = 308,
            attackSpeed = 4, maxHit = 30, approach = 8, bossPoints = 40, slayerReq = 0, slayerXp = 0.0, multi = false,
            loot = DropTable(
                // 100% — Vorkath's signature superior bones (huge Prayer xp), plus a dragon-bone.
                always = listOf(
                    DropEntry("item.superior_dragon_bones", 2, 2),
                    DropEntry("item.dragon_bones", 1, 1),
                ),
                // One weighted pick — OSRS-flavoured commons: high runes, dragon ammo/gear, blue hide.
                // Coins kept moderate; the value lives in tradeable dragon items, not minted gp.
                main = listOf(
                    coins(30_000, 100_000, 28),
                    DropEntry("item.death_rune", 100, 300, weight = 14),
                    DropEntry("item.blood_rune", 100, 250, weight = 12),
                    DropEntry("item.chaos_rune", 150, 400, weight = 10),
                    DropEntry("item.dragon_arrow", 80, 200, weight = 10),
                    DropEntry("item.dragon_dart", 50, 150, weight = 10),
                    DropEntry("item.dragon_knife", 30, 80, weight = 8),
                    DropEntry("item.blue_dragon_leather", 30, 70, weight = 10),
                    DropEntry("item.dragon_dart_tip", 30, 90, weight = 6),
                    DropEntry("item.dragon_bolts_unf", 100, 250, weight = 8),
                    DropEntry("item.dragon_platelegs", weight = 5),
                    DropEntry("item.dragon_plateskirt", weight = 5),
                    DropEntry("item.dragon_med_helm", weight = 6),
                    DropEntry("item.wine_of_zamorak", 50, 150, weight = 5),
                ),
                // Chase pieces — the dragonfire-ward visage is the headline; head/jar are log fillers.
                // Rates are server-generous vs live OSRS but still meaningful. Announced + logged.
                rare = listOf(
                    DropEntry("item.skeletal_visage", 1, 1, oneInN = 1000, announce = true, log = true),
                    DropEntry("item.draconic_visage", 1, 1, oneInN = 2000, announce = true, log = true),
                    DropEntry("item.dragonbone_necklace", 1, 1, oneInN = 800, announce = true, log = true),
                    DropEntry("item.vorkaths_head", 1, 1, oneInN = 300, log = true),
                    DropEntry("item.jar_of_decay", 1, 1, oneInN = 1500, announce = true, log = true),
                    DropEntry("item.onyx_bolts_e", 10, 30, oneInN = 150),
                ),
            ),
        ),
        EliteBoss(
            key = "npc.alchemical_hydra", name = "Alchemical Hydra",
            lair = Tile(1361, 10231, 0), walkRadius = 5,
            hp = 1100, att = 1, str = 1, def = 200, mag = 250, rng = 250,
            attackSpeed = 4, maxHit = 26, approach = 8, bossPoints = 45, slayerReq = 95, slayerXp = 1100.0, multi = false,
            loot = DropTable(
                always = listOf(DropEntry("item.dragon_bones", 1, 1)),
                main = listOf(
                    coins(30_000, 90_000, 28),
                    DropEntry("item.death_rune", 100, 300, weight = 14),
                    DropEntry("item.chaos_rune", 150, 400, weight = 10),
                    DropEntry("item.dragon_thrownaxe", 20, 60, weight = 10),
                    DropEntry("item.dragon_knife", 20, 60, weight = 10),
                    DropEntry("item.dragon_dart", 40, 120, weight = 8),
                    DropEntry("item.dragon_arrow", 60, 160, weight = 8),
                    DropEntry("item.cannonball", 50, 150, weight = 8),
                ),
                // Signature: Hydra's claw → dragon hunter lance, tail → bonecrusher necklace, leather
                // → ferocious gloves, and the eye/fang/heart that craft the brimstone ring.
                rare = listOf(
                    DropEntry("item.hydras_claw", 1, 1, oneInN = 700, announce = true, log = true),
                    DropEntry("item.hydra_tail", 1, 1, oneInN = 400, log = true),
                    DropEntry("item.hydra_leather", 1, 1, oneInN = 400, announce = true, log = true),
                    DropEntry("item.hydras_eye", 1, 1, oneInN = 180, log = true),
                    DropEntry("item.hydras_fang", 1, 1, oneInN = 180, log = true),
                    DropEntry("item.hydras_heart", 1, 1, oneInN = 180, log = true),
                    DropEntry("item.alchemical_hydra_heads", 1, 1, oneInN = 256, log = true),
                    DropEntry("item.jar_of_chemicals", 1, 1, oneInN = 1500, announce = true, log = true),
                    DropEntry("item.ikkle_hydra", 1, 1, oneInN = 3000, announce = true, log = true),
                ),
            ),
        ),
        EliteBoss(
            key = "npc.phantom_muspah", name = "Phantom Muspah",
            lair = Tile(2117, 5645, 0), walkRadius = 5,
            hp = 850, att = 1, str = 200, def = 150, mag = 250, rng = 250,
            attackSpeed = 4, maxHit = 30, approach = 8, bossPoints = 40, slayerReq = 0, slayerXp = 0.0, multi = false,
            loot = DropTable(
                main = listOf(
                    coins(30_000, 100_000, 26),
                    DropEntry("item.ancient_essence", 100, 300, weight = 20),
                    DropEntry("item.blood_rune", 150, 400, weight = 14),
                    DropEntry("item.death_rune", 150, 400, weight = 12),
                    DropEntry("item.soul_rune", 100, 250, weight = 10),
                    DropEntry("item.nature_rune", 100, 250, weight = 8),
                    DropEntry("item.charged_ice", 20, 50, weight = 8),
                ),
                // Signature: Venator shard (combines into the venator bow) + the ancient icon.
                rare = listOf(
                    DropEntry("item.venator_shard", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.ancient_icon", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.frozen_cache", 1, 1, oneInN = 150, log = true),
                    DropEntry("item.muphin", 1, 1, oneInN = 2500, announce = true, log = true),
                ),
            ),
        ),
        EliteBoss(
            key = "npc.abyssal_sire", name = "Abyssal Sire",
            lair = Tile(2970, 4384, 0), walkRadius = 5,
            hp = 400, att = 180, str = 180, def = 150, mag = 200, rng = 1,
            attackSpeed = 4, maxHit = 22, approach = 2, bossPoints = 35, slayerReq = 85, slayerXp = 400.0, multi = true,
            loot = DropTable(
                always = listOf(DropEntry("item.abyssal_ashes", 1, 1)),
                main = listOf(
                    coins(20_000, 70_000, 26),
                    DropEntry("item.death_rune", 80, 250, weight = 14),
                    DropEntry("item.blood_rune", 80, 200, weight = 12),
                    DropEntry("item.chaos_rune", 100, 300, weight = 10),
                    DropEntry("item.cannonball", 40, 120, weight = 8),
                    DropEntry("item.abyssal_head", 1, 1, weight = 4),
                ),
                // Signature: whip, abyssal dagger, the three bludgeon pieces (axon/spine/claw build the
                // abyssal bludgeon), the Unsired (hand in for a roll), jar, and the Abyssal orphan pet.
                // NOTE: the piece keys are `bludgeon_*`, NOT `abyssal_bludgeon_*` (the old keys never dropped).
                rare = listOf(
                    DropEntry("item.abyssal_whip", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.abyssal_dagger", 1, 1, oneInN = 256, announce = true, log = true),
                    DropEntry("item.bludgeon_axon", 1, 1, oneInN = 258, log = true),
                    DropEntry("item.bludgeon_spine", 1, 1, oneInN = 258, log = true),
                    DropEntry("item.bludgeon_claw", 1, 1, oneInN = 258, log = true),
                    DropEntry("item.unsired", 1, 1, oneInN = 100, announce = true, log = true),
                    DropEntry("item.jar_of_miasma", 1, 1, oneInN = 1500, announce = true, log = true),
                    DropEntry("item.abyssal_orphan", 1, 1, oneInN = 2560, announce = true, log = true),
                ),
            ),
        ),
        EliteBoss(
            key = "npc.nex", name = "Nex",
            lair = Tile(2924, 5202, 2), walkRadius = 6,
            hp = 1000, att = 250, str = 250, def = 250, mag = 300, rng = 300,
            attackSpeed = 4, maxHit = 30, approach = 8, bossPoints = 60, slayerReq = 0, slayerXp = 0.0, multi = true,
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(80_000, 250_000, 28),
                    DropEntry("item.death_rune", 200, 600, weight = 16),
                    DropEntry("item.blood_rune", 200, 500, weight = 14),
                    DropEntry("item.soul_rune", 150, 400, weight = 12),
                    DropEntry("item.nihil_shard", 10, 30, weight = 12), // craft into nihil / ancient blood
                ),
                // Signature: the full Torva AND Virtus sets (Virtus is Nex's mage line — rank-gated
                // in Title.kt, deliberately never sold), Zaryte vambraces, Nihil horn → Zaryte
                // crossbow, Ancient hilt → Ancient godsword, plus the Nexling pet. Apex tier.
                rare = listOf(
                    DropEntry("item.torva_full_helm", 1, 1, oneInN = 450, announce = true, log = true),
                    DropEntry("item.torva_platebody", 1, 1, oneInN = 450, announce = true, log = true),
                    DropEntry("item.torva_platelegs", 1, 1, oneInN = 450, announce = true, log = true),
                    DropEntry("item.virtus_mask", 1, 1, oneInN = 450, announce = true, log = true),
                    DropEntry("item.virtus_robe_top", 1, 1, oneInN = 450, announce = true, log = true),
                    DropEntry("item.virtus_robe_bottom", 1, 1, oneInN = 450, announce = true, log = true),
                    DropEntry("item.zaryte_vambraces", 1, 1, oneInN = 450, announce = true, log = true),
                    DropEntry("item.nihil_horn", 1, 1, oneInN = 450, announce = true, log = true),
                    DropEntry("item.ancient_hilt", 1, 1, oneInN = 516, announce = true, log = true),
                    DropEntry("item.nexling", 1, 1, oneInN = 500, announce = true, log = true),
                ),
            ),
        ),
        // Grotesque Guardians — Dusk (melee) + Dawn (ranged) spawn together; loot on each kill.
        EliteBoss(
            key = "npc.dusk", name = "Dusk",
            lair = Tile(3413, 3537, 0), walkRadius = 5,
            hp = 600, att = 220, str = 280, def = 200, mag = 1, rng = 1,
            attackSpeed = 4, maxHit = 25, approach = 2, bossPoints = 30, slayerReq = 75, slayerXp = 600.0, multi = true,
            loot = DropTable(
                always = listOf(DropEntry("item.granite_dust", 30, 90)),
                main = listOf(
                    coins(20_000, 60_000, 28),
                    DropEntry("item.death_rune", 80, 200, weight = 12),
                    DropEntry("item.blood_rune", 60, 150, weight = 10),
                    DropEntry("item.cannonball", 40, 100, weight = 8),
                ),
                // Signature: Black tourmaline core → guardian boots; granite gloves + hammer; jar.
                rare = listOf(
                    DropEntry("item.black_tourmaline_core", 1, 1, oneInN = 1000, announce = true, log = true),
                    DropEntry("item.granite_gloves", 1, 1, oneInN = 250, log = true),
                    DropEntry("item.granite_hammer", 1, 1, oneInN = 250, log = true),
                    DropEntry("item.jar_of_stone", 1, 1, oneInN = 2000, announce = true, log = true),
                ),
            ),
        ),
        EliteBoss(
            key = "npc.dawn", name = "Dawn",
            lair = Tile(3417, 3537, 0), walkRadius = 5,
            hp = 600, att = 1, str = 1, def = 200, mag = 200, rng = 250,
            attackSpeed = 4, maxHit = 22, approach = 8, bossPoints = 30, slayerReq = 75, slayerXp = 600.0, multi = true,
            loot = DropTable(
                always = listOf(DropEntry("item.granite_dust", 30, 90)),
                main = listOf(
                    coins(20_000, 60_000, 28),
                    DropEntry("item.death_rune", 80, 200, weight = 12),
                    DropEntry("item.nature_rune", 60, 150, weight = 10),
                    DropEntry("item.dragon_arrow", 40, 120, weight = 8),
                ),
                // Signature: Granite ring; shares the tourmaline core; the Noon pet.
                rare = listOf(
                    DropEntry("item.granite_ring", 1, 1, oneInN = 250, log = true),
                    DropEntry("item.black_tourmaline_core", 1, 1, oneInN = 1000, announce = true, log = true),
                    DropEntry("item.jar_of_stone", 1, 1, oneInN = 2000, announce = true, log = true),
                    DropEntry("item.noon", 1, 1, oneInN = 3000, announce = true, log = true),
                ),
            ),
        ),
    )

    fun byKey(key: String): EliteBoss? = all.firstOrNull { it.key == key }
}

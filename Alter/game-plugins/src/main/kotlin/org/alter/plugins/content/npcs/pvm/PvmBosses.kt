package org.alter.plugins.content.npcs.pvm

import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * The **Slayer / PvM boss roster** (teleport portal Bosses tab, Phase D) — single-NPC (or
 * small-group, e.g. the Dagannoth Kings) bosses at their real OSRS lairs. The shared plugins
 * read this registry: [PvmBossPlugin] spawns + defs + death/loot (+ slayer data),
 * [PvmBossCombatPlugin] runs each boss's OSRS rotation + mechanics keyed by [key].
 *
 * **Adding a PvM boss = adding a [PvmBoss] here.** Lairs are real coords (TUNE — verify
 * in-game); [org.alter.plugins.content.bosses.BossLairs] force-loads + multi-flags them.
 * [slayerReq] gates the kill to a Slayer level (0 = none); deeper Slayer integration (task
 * locking, the reward shop) is future work.
 */
object PvmBosses {

    data class PvmBoss(
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
        /** 1 = melee-only, 2 = large/mixed, 8 = ranged/caster. */
        val approach: Int,
        val bossPoints: Int,
        val slayerReq: Int,
        val slayerXp: Double,
        val multi: Boolean,
        val loot: DropTable,
    )

    private fun coins(min: Int, max: Int, w: Int) = DropEntry("item.coins_995", min, max, weight = w)

    val all: List<PvmBoss> = listOf(
        PvmBoss(
            key = "npc.kraken", name = "Kraken",
            lair = Tile(2279, 10012, 0), walkRadius = 0,
            hp = 255, att = 1, str = 1, def = 100, mag = 240, rng = 1,
            attackSpeed = 5, maxHit = 30, approach = 8, bossPoints = 25, slayerReq = 87, slayerXp = 255.0, multi = false,
            loot = DropTable(
                main = listOf(
                    coins(20_000, 60_000, 28),
                    DropEntry("item.death_rune", 100, 300, weight = 16),
                    DropEntry("item.water_rune", 500, 1500, weight = 16),
                    DropEntry("item.raw_shark", 20, 50, weight = 12),
                    DropEntry("item.white_berries", 20, 50, weight = 8),
                    DropEntry("item.nature_rune", 100, 250, weight = 10),
                ),
                // Signature: uncharged trident of the seas + the kraken tentacle (→ abyssal tentacle).
                rare = listOf(
                    DropEntry("item.trident_of_the_seas_full", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.kraken_tentacle", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.jar_of_dirt", 1, 1, oneInN = 1500, announce = true, log = true),
                    DropEntry("item.pet_kraken", 1, 1, oneInN = 3000, announce = true, log = true),
                ),
            ),
        ),
        // NOTE: the Corporeal Beast is intentionally NOT here — it's already a fully-built city
        // world boss (CorpBeastPlugin + war/boss/BossScheduler). Registering it again would
        // duplicate its setCombatDef/onNpcCombat and crash.
        PvmBoss(
            key = "npc.cerberus", name = "Cerberus",
            lair = Tile(1311, 1250, 0), walkRadius = 4,
            hp = 600, att = 220, str = 220, def = 100, mag = 300, rng = 220,
            attackSpeed = 4, maxHit = 23, approach = 2, bossPoints = 35, slayerReq = 91, slayerXp = 600.0, multi = false,
            loot = DropTable(
                always = listOf(DropEntry("item.infernal_ashes", 1, 1)),
                main = listOf(
                    coins(20_000, 60_000, 28),
                    DropEntry("item.rune_platebody", 1, 1, weight = 10),
                    DropEntry("item.unholy_symbol", 1, 1, weight = 8),
                    DropEntry("item.blood_rune", 100, 250, weight = 12),
                    DropEntry("item.soul_rune", 100, 250, weight = 10),
                    DropEntry("item.wrath_rune", 30, 80, weight = 6),
                ),
                // Signature: the three boot crystals (primordial/pegasian/eternal), smouldering stone
                // (→ infernal cape), jar of souls, and the Hellpuppy pet.
                rare = listOf(
                    DropEntry("item.primordial_crystal", 1, 1, oneInN = 512, announce = true, log = true),
                    DropEntry("item.pegasian_crystal", 1, 1, oneInN = 512, announce = true, log = true),
                    DropEntry("item.eternal_crystal", 1, 1, oneInN = 512, announce = true, log = true),
                    DropEntry("item.smouldering_stone", 1, 1, oneInN = 770, announce = true, log = true),
                    DropEntry("item.jar_of_souls", 1, 1, oneInN = 2000, announce = true, log = true),
                    DropEntry("item.hellpuppy", 1, 1, oneInN = 3000, announce = true, log = true),
                ),
            ),
        ),
        PvmBoss(
            key = "npc.giant_mole", name = "Giant Mole",
            lair = Tile(1760, 5164, 0), walkRadius = 6,
            hp = 255, att = 150, str = 200, def = 150, mag = 1, rng = 1,
            attackSpeed = 4, maxHit = 20, approach = 1, bossPoints = 20, slayerReq = 0, slayerXp = 0.0, multi = false,
            loot = DropTable(
                always = listOf(DropEntry("item.mole_claw", 1, 1), DropEntry("item.mole_skin", 1, 3)),
                main = listOf(
                    coins(15_000, 45_000, 28),
                    DropEntry("item.nature_rune", 100, 300, weight = 14),
                    DropEntry("item.law_rune", 80, 200, weight = 10),
                    DropEntry("item.adamant_platebody", 1, 1, weight = 8),
                    DropEntry("item.runite_bar", 1, 3, weight = 8),
                ),
                // Signature: dragon pickaxe + the Baby mole pet.
                rare = listOf(
                    DropEntry("item.dragon_pickaxe", 1, 1, oneInN = 256, announce = true, log = true),
                    DropEntry("item.baby_mole", 1, 1, oneInN = 3000, announce = true, log = true),
                ),
            ),
        ),
        PvmBoss(
            key = "npc.kalphite_queen", name = "Kalphite Queen",
            lair = Tile(3508, 9494, 2), walkRadius = 5,
            hp = 255, att = 200, str = 200, def = 200, mag = 200, rng = 200,
            attackSpeed = 4, maxHit = 31, approach = 2, bossPoints = 30, slayerReq = 0, slayerXp = 0.0, multi = true,
            loot = DropTable(
                always = listOf(DropEntry("item.kalphite_soldier", 1, 1)),
                main = listOf(
                    coins(30_000, 90_000, 28),
                    DropEntry("item.death_rune", 100, 300, weight = 14),
                    DropEntry("item.blood_rune", 100, 250, weight = 12),
                    DropEntry("item.dragon_med_helm", 1, 1, weight = 6),
                    DropEntry("item.dragon_arrow", 100, 250, weight = 10),
                ),
                // Signature: dragon chainbody + dragon 2h + dragon pickaxe, jar of sand, princess pet.
                rare = listOf(
                    DropEntry("item.dragon_chainbody", 1, 1, oneInN = 128, announce = true, log = true),
                    DropEntry("item.dragon_2h_sword", 1, 1, oneInN = 256, announce = true, log = true),
                    DropEntry("item.dragon_pickaxe", 1, 1, oneInN = 400, announce = true, log = true),
                    DropEntry("item.jar_of_sand", 1, 1, oneInN = 2000, announce = true, log = true),
                    DropEntry("item.kalphite_princess", 1, 1, oneInN = 3000, announce = true, log = true),
                ),
            ),
        ),
        PvmBoss(
            key = "npc.sarachnis", name = "Sarachnis",
            lair = Tile(1923, 9921, 0), walkRadius = 5,
            hp = 460, att = 200, str = 200, def = 200, mag = 1, rng = 150,
            attackSpeed = 4, maxHit = 26, approach = 2, bossPoints = 25, slayerReq = 0, slayerXp = 0.0, multi = false,
            loot = DropTable(
                main = listOf(
                    coins(20_000, 60_000, 28),
                    DropEntry("item.death_rune", 80, 220, weight = 14),
                    DropEntry("item.blood_rune", 80, 200, weight = 12),
                    DropEntry("item.nature_rune", 100, 250, weight = 10),
                    DropEntry("item.unholy_symbol", 1, 1, weight = 8),
                    DropEntry("item.runite_bar", 1, 3, weight = 6),
                ),
                // Signature: Sarachnis cudgel — moved OUT of `always` (it was dropping EVERY kill) to
                // a proper ~1/384 rare; plus jar of eyes + the Sraracha pet.
                rare = listOf(
                    DropEntry("item.sarachnis_cudgel", 1, 1, oneInN = 384, announce = true, log = true),
                    DropEntry("item.jar_of_eyes", 1, 1, oneInN = 2000, announce = true, log = true),
                    DropEntry("item.sraracha", 1, 1, oneInN = 3000, announce = true, log = true),
                ),
            ),
        ),
        PvmBoss(
            key = "npc.thermonuclear_smoke_devil", name = "Thermonuclear Smoke Devil",
            lair = Tile(2384, 9452, 0), walkRadius = 4,
            hp = 240, att = 1, str = 1, def = 100, mag = 1, rng = 220,
            attackSpeed = 4, maxHit = 18, approach = 8, bossPoints = 25, slayerReq = 93, slayerXp = 240.0, multi = false,
            loot = DropTable(
                main = listOf(
                    coins(15_000, 45_000, 28),
                    DropEntry("item.chaos_rune", 100, 300, weight = 16),
                    DropEntry("item.air_rune", 500, 1500, weight = 12),
                    DropEntry("item.rune_chainbody", 1, 1, weight = 10),
                    DropEntry("item.death_rune", 80, 200, weight = 10),
                ),
                // Signature: occult necklace (best magic-dmg neck) + the smoke battlestaff + pet.
                rare = listOf(
                    DropEntry("item.occult_necklace", 1, 1, oneInN = 350, announce = true, log = true),
                    DropEntry("item.smoke_battlestaff", 1, 1, oneInN = 512, announce = true, log = true),
                    DropEntry("item.pet_smoke_devil", 1, 1, oneInN = 3000, announce = true, log = true),
                ),
            ),
        ),
        PvmBoss(
            key = "npc.demonic_gorilla", name = "Demonic Gorilla",
            lair = Tile(2418, 9774, 0), walkRadius = 4,
            hp = 187, att = 220, str = 220, def = 165, mag = 220, rng = 220,
            attackSpeed = 4, maxHit = 26, approach = 2, bossPoints = 25, slayerReq = 0, slayerXp = 0.0, multi = true,
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(15_000, 45_000, 28),
                    DropEntry("item.death_rune", 80, 200, weight = 14),
                    DropEntry("item.blood_rune", 80, 200, weight = 12),
                    DropEntry("item.dragon_dart", 30, 60, weight = 12),
                    DropEntry("item.ballista_limbs", 1, 1, weight = 4),
                    DropEntry("item.ballista_spring", 1, 1, weight = 4),
                ),
                // Signature: zenyte shard (→ zenyte jewellery), light + heavy ballista, monkey tail.
                rare = listOf(
                    DropEntry("item.zenyte_shard", 1, 1, oneInN = 350, announce = true, log = true),
                    DropEntry("item.light_ballista", 1, 1, oneInN = 500, announce = true, log = true),
                    DropEntry("item.heavy_ballista", 1, 1, oneInN = 600, announce = true, log = true),
                    DropEntry("item.monkey_tail", 1, 1, oneInN = 800, log = true),
                ),
            ),
        ),
        PvmBoss(
            key = "npc.skotizo", name = "Skotizo",
            lair = Tile(1721, 10090, 0), walkRadius = 5,
            hp = 450, att = 200, str = 200, def = 150, mag = 250, rng = 1,
            attackSpeed = 4, maxHit = 24, approach = 2, bossPoints = 30, slayerReq = 0, slayerXp = 0.0, multi = true,
            loot = DropTable(
                always = listOf(DropEntry("item.ancient_shard", 1, 3)),
                main = listOf(
                    coins(30_000, 80_000, 28),
                    DropEntry("item.death_rune", 100, 300, weight = 14),
                    DropEntry("item.blood_rune", 100, 250, weight = 12),
                    DropEntry("item.dark_totem", 1, 1, weight = 8),
                    DropEntry("item.brimstone_key", 1, 1, weight = 10),
                ),
                // Signature: the Skotos pet + jar of darkness (single-source uniques for Skotizo).
                rare = listOf(
                    DropEntry("item.skotos", 1, 1, oneInN = 500, announce = true, log = true),
                    DropEntry("item.jar_of_darkness", 1, 1, oneInN = 2000, announce = true, log = true),
                ),
            ),
        ),
        // The Dagannoth Kings — three single-style kings sharing one multi-way lair.
        PvmBoss(
            key = "npc.dagannoth_rex", name = "Dagannoth Rex",
            lair = Tile(2901, 4449, 0), walkRadius = 4,
            hp = 255, att = 255, str = 255, def = 255, mag = 1, rng = 1,
            attackSpeed = 4, maxHit = 16, approach = 1, bossPoints = 20, slayerReq = 0, slayerXp = 0.0, multi = true,
            loot = DropTable(
                main = listOf(
                    coins(10_000, 30_000, 28),
                    DropEntry("item.dragon_axe", 1, 1, weight = 8),
                    DropEntry("item.death_rune", 60, 150, weight = 12),
                    DropEntry("item.runite_bar", 1, 2, weight = 8),
                ),
                // Signature (melee king): Berserker ring + Warrior ring.
                rare = listOf(
                    DropEntry("item.berserker_ring", 1, 1, oneInN = 128, announce = true, log = true),
                    DropEntry("item.warrior_ring", 1, 1, oneInN = 128, announce = true, log = true),
                ),
            ),
        ),
        PvmBoss(
            key = "npc.dagannoth_prime", name = "Dagannoth Prime",
            lair = Tile(2899, 4453, 0), walkRadius = 4,
            hp = 255, att = 1, str = 1, def = 255, mag = 255, rng = 1,
            attackSpeed = 4, maxHit = 14, approach = 8, bossPoints = 20, slayerReq = 0, slayerXp = 0.0, multi = true,
            loot = DropTable(
                main = listOf(
                    coins(10_000, 30_000, 28),
                    DropEntry("item.air_rune", 500, 1500, weight = 12),
                    DropEntry("item.death_rune", 60, 150, weight = 12),
                    DropEntry("item.dragon_axe", 1, 1, weight = 6),
                ),
                // Signature (magic king): Seers ring + Mud battlestaff.
                rare = listOf(
                    DropEntry("item.seers_ring", 1, 1, oneInN = 128, announce = true, log = true),
                    DropEntry("item.mud_battlestaff", 1, 1, oneInN = 128, announce = true, log = true),
                ),
            ),
        ),
        PvmBoss(
            key = "npc.dagannoth_supreme", name = "Dagannoth Supreme",
            lair = Tile(2897, 4449, 0), walkRadius = 4,
            hp = 255, att = 1, str = 1, def = 255, mag = 1, rng = 255,
            attackSpeed = 4, maxHit = 14, approach = 8, bossPoints = 20, slayerReq = 0, slayerXp = 0.0, multi = true,
            loot = DropTable(
                main = listOf(
                    coins(10_000, 30_000, 28),
                    DropEntry("item.rune_arrow", 200, 500, weight = 12),
                    DropEntry("item.death_rune", 60, 150, weight = 12),
                    DropEntry("item.dragon_axe", 1, 1, weight = 6),
                ),
                // Signature (ranged king): Archers ring + Seercull bow; the shared Dagannoth pet.
                rare = listOf(
                    DropEntry("item.archers_ring", 1, 1, oneInN = 128, announce = true, log = true),
                    DropEntry("item.seercull", 1, 1, oneInN = 128, announce = true, log = true),
                    DropEntry("item.pet_dagannoth_supreme", 1, 1, oneInN = 5000, announce = true, log = true),
                ),
            ),
        ),
    )

    fun byKey(key: String): PvmBoss? = all.firstOrNull { it.key == key }
}

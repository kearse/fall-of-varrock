package org.alter.plugins.content.npcs.wilderness

import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * The **Wilderness boss roster** (teleport portal Bosses tab, Phase C). Each entry is one
 * single-NPC boss at its real OSRS wilderness lair; the shared plugins read this registry:
 *  - [WildernessBossPlugin] spawns it + registers its combat def + handles its death/loot,
 *  - [WildernessBossCombatPlugin] runs its OSRS attack rotation (mechanics keyed by [key]).
 *
 * **Adding a wilderness boss = adding a [WildBoss] here.** Lairs are real-OSRS coordinates
 * (marked TUNE — verify in-game) and all multi-way; [org.alter.plugins.content.bosses.BossLairs]
 * force-loads their region collision so the boss is pathable before a player arrives.
 */
object WildernessBosses {

    data class WildBoss(
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
        /** How close the boss closes in: 1 = melee-only, 2 = large/mixed, 8 = ranged/caster. */
        val approach: Int,
        val bossPoints: Int,
        val loot: DropTable,
    )

    private fun coins(min: Int, max: Int, w: Int) = DropEntry("item.coins_995", min, max, weight = w)

    val all: List<WildBoss> = listOf(
        WildBoss(
            key = "npc.callisto", name = "Callisto",
            lair = Tile(3300, 3840, 0), walkRadius = 6,
            hp = 510, att = 230, str = 230, def = 240, mag = 1, rng = 1,
            attackSpeed = 4, maxHit = 30, approach = 1, bossPoints = 30,
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(40_000, 120_000, 35),
                    DropEntry("item.dragon_bones", 15, 30, weight = 20),
                    DropEntry("item.death_rune", 100, 300, weight = 15),
                    DropEntry("item.rune_platebody", 1, 1, weight = 10),
                    DropEntry("item.dragon_mace", 1, 1, weight = 6),
                ),
                // Signature: Tyrannical ring, Claws of Callisto (→ Ursine chainmace), Voidwaker hilt,
                // Dragon pickaxe, and the Callisto cub pet.
                rare = listOf(
                    DropEntry("item.tyrannical_ring", 1, 1, oneInN = 350, announce = true, log = true),
                    DropEntry("item.claws_of_callisto", 1, 1, oneInN = 400, announce = true, log = true),
                    DropEntry("item.voidwaker_hilt", 1, 1, oneInN = 1500, announce = true, log = true),
                    DropEntry("item.dragon_pickaxe", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.dragon_2h_sword", 1, 1, oneInN = 200, log = true),
                    DropEntry("item.callisto_cub", 1, 1, oneInN = 2000, announce = true, log = true),
                ),
            ),
        ),
        WildBoss(
            key = "npc.vetion", name = "Vet'ion",
            lair = Tile(3239, 3779, 0), walkRadius = 5,
            hp = 510, att = 280, str = 280, def = 280, mag = 200, rng = 1,
            attackSpeed = 4, maxHit = 28, approach = 2, bossPoints = 30,
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(40_000, 120_000, 35),
                    DropEntry("item.death_rune", 150, 400, weight = 18),
                    DropEntry("item.blood_rune", 100, 250, weight = 14),
                    DropEntry("item.rune_platelegs", 1, 1, weight = 10),
                    DropEntry("item.dragon_longsword", 1, 1, weight = 6),
                ),
                // Signature: Ring of the gods, Skull of Vet'ion (→ Accursed sceptre), Voidwaker blade,
                // Dragon pickaxe, and the Vet'ion jr. pet.
                rare = listOf(
                    DropEntry("item.ring_of_the_gods", 1, 1, oneInN = 350, announce = true, log = true),
                    DropEntry("item.skull_of_vetion", 1, 1, oneInN = 400, announce = true, log = true),
                    DropEntry("item.voidwaker_blade", 1, 1, oneInN = 1500, announce = true, log = true),
                    DropEntry("item.dragon_pickaxe", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.vetion_jr", 1, 1, oneInN = 2000, announce = true, log = true),
                ),
            ),
        ),
        WildBoss(
            key = "npc.venenatis", name = "Venenatis",
            lair = Tile(3315, 3743, 0), walkRadius = 6,
            hp = 510, att = 230, str = 230, def = 220, mag = 180, rng = 180,
            attackSpeed = 4, maxHit = 28, approach = 2, bossPoints = 30,
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(40_000, 120_000, 35),
                    DropEntry("item.death_rune", 150, 400, weight = 18),
                    DropEntry("item.rune_arrow", 200, 500, weight = 14),
                    DropEntry("item.rune_kiteshield", 1, 1, weight = 10),
                    DropEntry("item.dragon_dagger", 1, 1, weight = 6),
                ),
                // Signature: Treasonous ring, Fangs of Venenatis (→ Webweaver bow), Voidwaker gem,
                // Dragon pickaxe, and the Venenatis spiderling pet.
                rare = listOf(
                    DropEntry("item.treasonous_ring", 1, 1, oneInN = 350, announce = true, log = true),
                    DropEntry("item.fangs_of_venenatis", 1, 1, oneInN = 400, announce = true, log = true),
                    DropEntry("item.voidwaker_gem", 1, 1, oneInN = 1500, announce = true, log = true),
                    DropEntry("item.dragon_pickaxe", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.venenatis_spiderling", 1, 1, oneInN = 2000, announce = true, log = true),
                ),
            ),
        ),
        WildBoss(
            key = "npc.scorpia", name = "Scorpia",
            lair = Tile(3232, 10337, 0), walkRadius = 5,
            hp = 200, att = 150, str = 150, def = 150, mag = 1, rng = 1,
            attackSpeed = 4, maxHit = 18, approach = 1, bossPoints = 20,
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(20_000, 60_000, 35),
                    DropEntry("item.death_rune", 80, 200, weight = 18),
                    DropEntry("item.chaos_rune", 100, 300, weight = 16),
                    DropEntry("item.adamant_platebody", 1, 1, weight = 10),
                ),
                // Signature: the tier-3 Odium/Malediction shards (→ ward upgrades) + Scorpia's offspring.
                rare = listOf(
                    DropEntry("item.malediction_shard_3", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.odium_shard_3", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.scorpias_offspring", 1, 1, oneInN = 2000, announce = true, log = true),
                ),
            ),
        ),
        WildBoss(
            key = "npc.chaos_elemental_2054", name = "Chaos Elemental",
            lair = Tile(3279, 3916, 0), walkRadius = 6,
            hp = 200, att = 200, str = 200, def = 200, mag = 200, rng = 200,
            attackSpeed = 4, maxHit = 28, approach = 8, bossPoints = 25,
            loot = DropTable(
                always = listOf(DropEntry("item.dragon_bones", 1, 1)),
                main = listOf(
                    coins(30_000, 90_000, 35),
                    DropEntry("item.death_rune", 120, 300, weight = 18),
                    DropEntry("item.rune_platebody", 1, 1, weight = 10),
                    DropEntry("item.dragon_med_helm", 1, 1, weight = 6),
                ),
                // Signature: dragon pickaxe (its famous source) + the Pet chaos elemental.
                rare = listOf(
                    DropEntry("item.dragon_pickaxe", 1, 1, oneInN = 150, announce = true, log = true),
                    DropEntry("item.dragon_2h_sword", 1, 1, oneInN = 128, announce = true, log = true),
                    DropEntry("item.pet_chaos_elemental", 1, 1, oneInN = 2000, announce = true, log = true),
                ),
            ),
        ),
        WildBoss(
            key = "npc.chaos_fanatic", name = "Chaos Fanatic",
            lair = Tile(2978, 3851, 0), walkRadius = 5,
            hp = 210, att = 1, str = 1, def = 130, mag = 200, rng = 1,
            attackSpeed = 4, maxHit = 26, approach = 8, bossPoints = 20,
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(15_000, 50_000, 35),
                    DropEntry("item.chaos_rune", 100, 300, weight = 18),
                    DropEntry("item.death_rune", 60, 180, weight = 14),
                    DropEntry("item.rune_med_helm", 1, 1, weight = 10),
                ),
                // Signature: the tier-1 Odium/Malediction shards + the Pet chaos elemental.
                rare = listOf(
                    DropEntry("item.odium_shard_1", 1, 1, oneInN = 256, announce = true, log = true),
                    DropEntry("item.malediction_shard_1", 1, 1, oneInN = 256, announce = true, log = true),
                    DropEntry("item.pet_chaos_elemental", 1, 1, oneInN = 3000, announce = true, log = true),
                ),
            ),
        ),
        WildBoss(
            key = "npc.crazy_archaeologist", name = "Crazy Archaeologist",
            lair = Tile(2980, 3690, 0), walkRadius = 5,
            hp = 224, att = 1, str = 1, def = 130, mag = 1, rng = 200,
            attackSpeed = 4, maxHit = 26, approach = 8, bossPoints = 20,
            loot = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    coins(15_000, 50_000, 35),
                    DropEntry("item.death_rune", 80, 220, weight = 18),
                    DropEntry("item.rune_arrow", 150, 400, weight = 14),
                    DropEntry("item.adamant_platebody", 1, 1, weight = 10),
                ),
                // Signature: the tier-2 Odium/Malediction shards + his trademark Fedora.
                rare = listOf(
                    DropEntry("item.odium_shard_2", 1, 1, oneInN = 256, announce = true, log = true),
                    DropEntry("item.malediction_shard_2", 1, 1, oneInN = 256, announce = true, log = true),
                    DropEntry("item.fedora", 1, 1, oneInN = 500, announce = true, log = true),
                ),
            ),
        ),
    )

    fun byKey(key: String): WildBoss? = all.firstOrNull { it.key == key }
}

package org.alter.plugins.content.bosses.godwars

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.BossDeath
import org.alter.plugins.content.bosses.BossKills
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **God Wars Dungeon** — port #5 (the first donor-package port): all four throne
 * rooms from Kronos rev-184 `activities/godwars/`, live in the SHARED world (no
 * instances — GWD rooms are the classic contested camps, exactly like the donor).
 *
 * This plugin owns the world side: region force-loads, the sixteen spawns (donor
 * coordinates from the fight-class comments, walk ranges included), and each
 * general's death economy — donor drop tables translated (godsword shards + hilts +
 * armour rares scaled to our odds convention) paid through the shared
 * [BossDeath.payout] (kill ledger, Collection Log, rare broadcasts, 1/1000 god pet).
 * The fights live in [GodwarsCombatPlugin].
 *
 * v1 scope (guide-documented): the throne rooms themselves. The surrounding dungeon
 * — killcount doors, aviansie/spiritual fodder, altars, the KC HUD — is the donor's
 * `GodwarsEntrance/Obstacle/Altars` layer and can come later; the portal lands you
 * at the room edge directly (a PK server buys the fight, not the walk).
 */
class GodwarsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private class Guard(val key: String, val tile: Tile, val walkRadius: Int)

    private class Room(
        val key: String,
        val name: String,
        val generalKey: String,
        val generalTile: Tile,
        val generalWalkRadius: Int,
        val guards: List<Guard>,
        val drops: DropTable,
        val pet: String,
    )

    // Spawn tiles + walk ranges are the donor's own spawn comments, verbatim.
    // Saradomin's encampment is the one plane-0 room (the donor omits z there).
    private val rooms = listOf(
        Room(
            key = "bandos", name = "General Graardor",
            generalKey = "npc.general_graardor", generalTile = Tile(2870, 5358, 2), generalWalkRadius = 3,
            guards = listOf(
                Guard("npc.sergeant_strongstack", Tile(2871, 5359, 2), 2),
                Guard("npc.sergeant_steelwill", Tile(2872, 5354, 2), 2),
                Guard("npc.sergeant_grimspike", Tile(2868, 5362, 2), 6),
            ),
            drops = DropTable(
                always = listOf(DropEntry("item.big_bones", 1, 1)),
                main = listOf(
                    DropEntry("item.coins_995", 19_581, 21_000, weight = 20),
                    DropEntry("item.rune_2h_sword", 1, 1, weight = 5),
                    DropEntry("item.rune_longsword", 1, 1, weight = 5),
                    DropEntry("item.rune_platebody", 1, 1, weight = 10),
                    DropEntry("item.nature_rune", 60, 70, weight = 15),
                    DropEntry("item.adamantite_ore_noted", 15, 20, weight = 10),
                    DropEntry("item.coal_noted", 115, 120, weight = 10),
                    DropEntry("item.magic_logs_noted", 15, 20, weight = 10),
                    DropEntry("item.snapdragon_seed", 1, 1, weight = 8),
                    DropEntry("item.super_restore4", 3, 3, weight = 7),
                ),
                rare = listOf(
                    DropEntry("item.bandos_chestplate", 1, 1, oneInN = 128, announce = true, log = true),
                    DropEntry("item.bandos_tassets", 1, 1, oneInN = 128, announce = true, log = true),
                    DropEntry("item.bandos_boots", 1, 1, oneInN = 64, log = true),
                    DropEntry("item.bandos_hilt", 1, 1, oneInN = 125, announce = true, log = true),
                    DropEntry("item.godsword_shard_1", 1, 1, oneInN = 100, log = true),
                    DropEntry("item.godsword_shard_2", 1, 1, oneInN = 100, log = true),
                    DropEntry("item.godsword_shard_3", 1, 1, oneInN = 100, log = true),
                ),
            ),
            pet = "item.pet_general_graardor",
        ),
        Room(
            key = "saradomin", name = "Commander Zilyana",
            generalKey = "npc.commander_zilyana", generalTile = Tile(2899, 5267, 0), generalWalkRadius = 9,
            guards = listOf(
                Guard("npc.starlight", Tile(2901, 5264, 0), 9),
                Guard("npc.growler", Tile(2897, 5263, 0), 9),
                Guard("npc.bree", Tile(2895, 5265, 0), 9),
            ),
            drops = DropTable(
                always = listOf(DropEntry("item.bones", 1, 1)),
                main = listOf(
                    DropEntry("item.coins_995", 19_362, 20_300, weight = 20),
                    DropEntry("item.rune_dart", 35, 40, weight = 10),
                    DropEntry("item.law_rune", 95, 105, weight = 10),
                    DropEntry("item.rune_plateskirt", 1, 1, weight = 8),
                    DropEntry("item.rune_kiteshield", 1, 1, weight = 5),
                    DropEntry("item.saradomin_brew3", 3, 3, weight = 12),
                    DropEntry("item.super_restore4", 3, 3, weight = 12),
                    DropEntry("item.prayer_potion4", 3, 3, weight = 10),
                    DropEntry("item.ranarr_seed", 2, 2, weight = 8),
                    DropEntry("item.magic_seed", 1, 1, weight = 5),
                ),
                rare = listOf(
                    DropEntry("item.saradomin_sword", 1, 1, oneInN = 64, announce = true, log = true),
                    DropEntry("item.armadyl_crossbow", 1, 1, oneInN = 200, announce = true, log = true),
                    DropEntry("item.saradomins_light", 1, 1, oneInN = 250, log = true),
                    DropEntry("item.saradomin_hilt", 1, 1, oneInN = 125, announce = true, log = true),
                    DropEntry("item.godsword_shard_1", 1, 1, oneInN = 100, log = true),
                    DropEntry("item.godsword_shard_2", 1, 1, oneInN = 100, log = true),
                    DropEntry("item.godsword_shard_3", 1, 1, oneInN = 100, log = true),
                ),
            ),
            pet = "item.pet_zilyana",
        ),
        Room(
            key = "zamorak", name = "K'ril Tsutsaroth",
            generalKey = "npc.kril_tsutsaroth", generalTile = Tile(2920, 5326, 2), generalWalkRadius = 2,
            guards = listOf(
                Guard("npc.tstanon_karlak", Tile(2929, 5327, 2), 5),
                Guard("npc.balfrug_kreeyath", Tile(2923, 5324, 2), 5),
                Guard("npc.zakln_gritch", Tile(2921, 5327, 2), 3),
            ),
            drops = DropTable(
                always = listOf(DropEntry("item.ashes", 1, 1)),
                main = listOf(
                    DropEntry("item.coins_995", 19_362, 20_073, weight = 20),
                    DropEntry("item.death_rune", 120, 124, weight = 15),
                    DropEntry("item.blood_rune", 80, 90, weight = 12),
                    DropEntry("item.rune_scimitar", 1, 1, weight = 8),
                    DropEntry("item.dragon_daggerp_5698", 1, 1, weight = 5),
                    DropEntry("item.rune_platelegs", 1, 1, weight = 8),
                    DropEntry("item.super_restore3", 3, 3, weight = 10),
                    DropEntry("item.zamorak_brew3", 3, 3, weight = 8),
                    DropEntry("item.grimy_lantadyme_noted", 7, 13, weight = 8),
                    DropEntry("item.lantadyme_seed", 3, 3, weight = 6),
                ),
                rare = listOf(
                    DropEntry("item.zamorakian_spear", 1, 1, oneInN = 100, announce = true, log = true),
                    DropEntry("item.steam_battlestaff", 1, 1, oneInN = 100, log = true),
                    DropEntry("item.staff_of_the_dead", 1, 1, oneInN = 250, announce = true, log = true),
                    DropEntry("item.zamorak_hilt", 1, 1, oneInN = 125, announce = true, log = true),
                    DropEntry("item.godsword_shard_1", 1, 1, oneInN = 100, log = true),
                    DropEntry("item.godsword_shard_2", 1, 1, oneInN = 100, log = true),
                    DropEntry("item.godsword_shard_3", 1, 1, oneInN = 100, log = true),
                ),
            ),
            pet = "item.pet_kril_tsutsaroth",
        ),
        Room(
            key = "armadyl", name = "Kree'arra",
            generalKey = "npc.kreearra_3162", generalTile = Tile(2832, 5301, 2), generalWalkRadius = 8,
            guards = listOf(
                Guard("npc.wingman_skree", Tile(2834, 5297, 2), 8),
                Guard("npc.flockleader_geerin", Tile(2827, 5299, 2), 8),
                Guard("npc.flight_kilisa", Tile(2829, 5300, 2), 8),
            ),
            drops = DropTable(
                always = listOf(
                    DropEntry("item.big_bones", 1, 1),
                    DropEntry("item.feather", 1, 15),
                ),
                main = listOf(
                    DropEntry("item.coins_995", 18_000, 21_000, weight = 20),
                    DropEntry("item.rune_crossbow", 1, 1, weight = 8),
                    DropEntry("item.runite_bolts", 18, 25, weight = 10),
                    DropEntry("item.rune_arrow", 100, 105, weight = 10),
                    DropEntry("item.black_dhide_body", 1, 1, weight = 8),
                    DropEntry("item.grimy_dwarf_weed_noted", 5, 22, weight = 12),
                    DropEntry("item.dwarf_weed_seed", 3, 3, weight = 8),
                    DropEntry("item.ranging_potion3", 3, 3, weight = 8),
                    DropEntry("item.crystal_key", 1, 1, weight = 5),
                    DropEntry("item.yew_seed", 1, 1, weight = 5),
                ),
                rare = listOf(
                    DropEntry("item.armadyl_helmet", 1, 1, oneInN = 64, announce = true, log = true),
                    DropEntry("item.armadyl_chestplate", 1, 1, oneInN = 128, announce = true, log = true),
                    DropEntry("item.armadyl_chainskirt", 1, 1, oneInN = 128, announce = true, log = true),
                    DropEntry("item.armadyl_hilt", 1, 1, oneInN = 125, announce = true, log = true),
                    DropEntry("item.godsword_shard_1", 1, 1, oneInN = 100, log = true),
                    DropEntry("item.godsword_shard_2", 1, 1, oneInN = 100, log = true),
                    DropEntry("item.godsword_shard_3", 1, 1, oneInN = 100, log = true),
                ),
            ),
            pet = "item.pet_kreearra",
        ),
    )

    init {
        // The throne rooms are multi-way (OSRS): bursts/barrages splash the minions and the
        // client shows the crossed swords. Registered at plugin init — MultiwayCombatPlugin
        // reads the registry from its own world-init hook. (Player report 2026-09-03:
        // "barrage does not hit multi" — outside the wilderness only flagged regions do.)
        intArrayOf(11346, 11347, 11602, 11603).forEach { setMultiCombatRegion(it) }

        onWorldInit {
            // The four throne-room regions (Armadyl/Bandos plane 2, Sara plane 0,
            // Zamorak plane 2) — force-loaded so collision exists before any player
            // walks in, and spawns are DIRECT (the spawnNpc queue is consumed before
            // onWorldInit runs — the Wizard Tower lesson).
            runCatching { world.definitions.loadRegions(world, world.chunks, intArrayOf(11346, 11347, 11602, 11603)) }
                .onFailure { logger.error(it) { "godwars: region force-load failed" } }
            rooms.forEach { room ->
                spawnRoomNpc(room.generalKey, room.generalTile, room.generalWalkRadius)
                room.guards.forEach { g -> spawnRoomNpc(g.key, g.tile, g.walkRadius) }
            }
            logger.info { "godwars: spawned 4 generals + 12 bodyguards across the throne rooms." }
        }

        rooms.forEach { room -> BossKills.register("gwd_${room.key}", room.name) }

        rooms.forEach { room ->
            onNpcDeath(room.generalKey) {
                val boss = npc
                val credited = boss.attr[KILLER_ATTR]?.get()
                val killer = credited as? Player
                // A general's death with no player credit is exactly the "I killed Zilyana and got
                // nothing" report (2026-09-03) — leave a trail either way so the next one can be
                // read out of the server log instead of guessed at.
                if (killer == null) {
                    logger.warn { "godwars: ${room.name} died at ${boss.tile} with no player credit (most damage by: $credited)." }
                    return@onNpcDeath
                }
                logger.info { "godwars: ${room.name} slain by ${killer.username} at ${boss.tile}; paying out." }
                // The shared payout (lairs/wilderness/slayer use it too): kill ledger + `::kc`
                // milestones, the table as owned ground items with broadcasts + Collection Log,
                // and the 1/1000 god pet to inventory or bank.
                BossDeath.payout(
                    world, killer, Tile(boss.tile.x, boss.tile.z, boss.tile.height),
                    key = "gwd_${room.key}", name = room.name, drops = room.drops,
                    pet = room.pet, petOneIn = PET_ODDS,
                )
            }
        }
    }

    private fun spawnRoomNpc(key: String, tile: Tile, walkRadius: Int) {
        runCatching {
            val npc = Npc(getRSCM(key), tile, world)
            npc.walkRadius = walkRadius
            world.spawn(npc)
            npc.respawns = true // AFTER world.spawn — setNpcDefaults would clobber it
            npc.setActive(true)
        }.onFailure { logger.warn { "godwars: failed to spawn '$key' at $tile: ${it.message}" } }
    }

    companion object {
        const val PET_ODDS = 1000
    }
}

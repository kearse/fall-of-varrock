package org.alter.plugins.content.bosses.godwars

import dev.openrune.cache.CacheManager.getItem
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.awardTickets
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
 * armour rares scaled to our odds convention), Boss Tickets, Collection Log pages,
 * rare broadcasts, and a 1/1000 god-pet roll. The fights live in
 * [GodwarsCombatPlugin].
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
        val bossPoints: Int,
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
            pet = "item.pet_general_graardor", bossPoints = 25,
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
            pet = "item.pet_zilyana", bossPoints = 25,
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
            pet = "item.pet_kril_tsutsaroth", bossPoints = 25,
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
            pet = "item.pet_kreearra", bossPoints = 25,
        ),
    )

    init {
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

        rooms.forEach { room ->
            onNpcDeath(room.generalKey) {
                val boss = npc
                val killer = boss.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
                killer.awardTickets(PointKind.BOSS, room.bossPoints)
                room.drops.roll(world).forEach { drop ->
                    val id = getRSCM(drop.item)
                    world.spawn(GroundItem(id, drop.amount, boss.tile, killer))
                    val name = getItem(id).name
                    if (drop.announce) {
                        world.players.forEach {
                            it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$name</col> from ${room.name}!</col>")
                        }
                    }
                    if (drop.log && CollectionLog.record(killer, id)) {
                        killer.message("<col=ffae00>New Collection Log slot: $name!</col>")
                    }
                }
                if (world.chance(1, PET_ODDS)) {
                    val pet = getRSCM(room.pet)
                    val add = killer.inventory.add(item = pet, amount = 1, assureFullInsertion = false)
                    if (add.completed == 0) killer.bank.add(pet, 1)
                    world.players.forEach {
                        it.message("<col=ff0000>News: ${killer.username} just received a <col=ffae00>${getItem(pet).name}</col> from ${room.name}!</col>")
                    }
                    if (CollectionLog.record(killer, pet)) {
                        killer.message("<col=ffae00>New Collection Log slot: ${getItem(pet).name}!</col>")
                    }
                }
                killer.message("<col=ff0000>You have slain ${room.name}.</col> (+${room.bossPoints} Boss Tickets)")
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

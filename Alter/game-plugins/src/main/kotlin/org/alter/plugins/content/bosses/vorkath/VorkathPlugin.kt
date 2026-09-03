package org.alter.plugins.content.bosses.vorkath

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.companion.CompanionPolicy
import org.alter.plugins.content.raids.RaidInstance
import org.alter.rscm.RSCM.getRSCM

/**
 * **Vorkath** — the reboot's pilot boss port (Kronos rev-184 `Vorkath.java` → Alter).
 *
 * This plugin owns everything around the fight; the fight itself lives in
 * [VorkathCombatPlugin]:
 *  - **Entry/exit**: climbing the icy spines on Ungael (object 31990 at 2272,4053)
 *    allocates a solo [RaidInstance] copy of the arena (region 9023) and drops the player
 *    inside; the copied spines exit back to the island. The instance tears itself down on
 *    death/logout (allocator attributes), and instanced deaths are already SAFE deaths.
 *  - **Poke to wake**: the boss spawns as the sleeping form (8059); "Poke" plays the
 *    Kronos wake sequence (player 827, wake 7950) and swaps in the fighting form (8061).
 *  - **Death**: drops + Collection Log + rare broadcast (the KBD pattern),
 *    then a fresh sleeping Vorkath rises for chain kills in the same instance.
 */
class VorkathPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val table = DropTable(
        // OSRS-flavoured, scaled to our economy (KBD-pattern odds, not OSRS's 1/5000s).
        always = listOf(
            DropEntry("item.superior_dragon_bones", 2, 2),
            DropEntry("item.blue_dragonhide", 2, 2),
        ),
        main = listOf(
            DropEntry("item.coins_995", 60_000, 140_000, weight = 30),
            DropEntry("item.wrath_rune", 30, 70, weight = 15),
            DropEntry("item.diamond_bolts_e", 30, 100, weight = 12),
            DropEntry("item.dragonstone_bolts_e", 15, 40, weight = 8),
            DropEntry("item.dragon_dart", 20, 60, weight = 8),
            DropEntry("item.adamantite_ore_noted", 10, 30, weight = 8),
            DropEntry("item.battlestaff_noted", 5, 12, weight = 7),
            DropEntry("item.manta_ray_noted", 10, 25, weight = 7),
            DropEntry("item.dragon_bones_noted", 15, 30, weight = 5),
        ),
        rare = listOf(
            DropEntry("item.vorkaths_head_21907", 1, 1, oneInN = 50, announce = true, log = true),
            DropEntry("item.dragonbone_necklace", 1, 1, oneInN = 300, announce = true, log = true),
            DropEntry("item.jar_of_decay", 1, 1, oneInN = 500, log = true),
            DropEntry("item.skeletal_visage", 1, 1, oneInN = 500, announce = true, log = true),
            DropEntry("item.vorki", 1, 1, oneInN = 1000, announce = true, log = true),
        ),
    )

    init {
        // Classic boss, companion-UNAWARE by design: the fight is never edited for companions,
        // they simply stand down inside the arena instance (CompanionPolicy, Block 1).
        CompanionPolicy.denyInstanceOf(ARENA_SOURCE, "Vorkath's arena is a solo fight")

        // ── Entry / exit: the icy spines on Ungael. Outside → allocate + enter; the
        // instance's copied spines → exit back to the island surface.
        onObjOption(obj = "object.ice_chunks_31990", option = "climb-over") {
            if (world.instanceAllocator.getMap(player.tile) != null) {
                player.moveTo(EXIT_TILE)
                player.message("You climb back over the icy spines.")
            } else {
                enter(player)
            }
        }

        // Command entry, like ::zulrah / ::hydra. Player report 2026-09-02 "Vork doesn't work":
        // the icy spines were the ONLY way in, and nothing told players to click them.
        onCommand("vorkath", description = "Climb over the icy spines into Vorkath's crater") {
            if (world.instanceAllocator.getMap(player.tile) != null) {
                player.message("You're already inside an instance — leave it first.")
            } else {
                enter(player)
            }
        }

        // ── Poke the sleeping form awake (Kronos wake sequence).
        // Driven from world.queue, NOT player.queue: the player stepping away would
        // interrupt a player queue mid-wake (VORKATH_WAKING already set), leaving the boss
        // permanently unwakeable in that instance. A world queue survives player movement
        // and the mid-sequence npc removal.
        onNpcOption(npc = "npc.vorkath_8059", option = "poke") {
            val sleeping = npc
            if (sleeping.attr[VORKATH_WAKING] == true) return@onNpcOption
            sleeping.attr[VORKATH_WAKING] = true
            player.animate(827)
            sleeping.animate(7950)
            world.queue {
                wait(5)
                if (sleeping.isDead() || sleeping.index < 0) return@queue
                val spawnTile = sleeping.attr[VORKATH_SPAWN_TILE] ?: sleeping.tile
                world.remove(sleeping)
                val boss = Npc(getRSCM("npc.vorkath_8061"), spawnTile, world)
                boss.respawns = false
                boss.attr[VORKATH_SPAWN_TILE] = spawnTile
                world.spawn(boss)
                boss.setActive(true)
                boss.attack(player)
            }
        }

        // ── Death: economy + retention hooks, then the next sleeping Vorkath rises.
        onNpcDeath("npc.vorkath_8061") {
            val boss = npc
            val killer = boss.attr[KILLER_ATTR]?.get() as? Player

            if (killer != null) {
                // Vorkath rolls the main drop table TWICE (OSRS/donor); the head/visage rares
                // still roll once each (mainRolls only multiplies the main tier).
                table.roll(world, mainRolls = 2).forEach { drop ->
                    val id = getRSCM(drop.item)
                    world.spawn(GroundItem(id, drop.amount, boss.tile, killer))
                    val name = getItem(id).name
                    if (drop.announce) {
                        world.players.forEach {
                            it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$name</col> from Vorkath!</col>")
                        }
                    }
                    if (drop.log && CollectionLog.record(killer, id)) {
                        killer.message("<col=ffae00>New Collection Log slot: $name!</col>")
                    }
                }
                killer.message("<col=ff0000>You have slain Vorkath.</col>")
            }

            // Chain kills: after the death anim settles, the sleeping form returns at the
            // spawn point — but only while its instance is still allocated.
            val spawnTile = boss.attr[VORKATH_SPAWN_TILE] ?: return@onNpcDeath
            world.queue {
                wait(10)
                if (world.instanceAllocator.getMap(spawnTile) == null) return@queue
                spawnSleeping(world, spawnTile)
            }
        }
    }

    private fun enter(p: Player) {
        val instance = RaidInstance.allocate(
            world = world,
            sourceArea = ARENA_SOURCE,
            exitTile = EXIT_TILE,
            owner = p.uid,
        )
        if (instance == null) {
            p.message("The island's magic is exhausted — try again in a moment.")
            return
        }
        spawnSleeping(world, instance.translate(BOSS_SPAWN_SRC))
        p.moveTo(instance.translate(PLAYER_LANDING_SRC))
        p.message("<col=ff0000>You climb over the icy spines. Something huge sleeps in the crater...</col>")
    }

    private fun spawnSleeping(world: World, tile: Tile) {
        val sleeping = Npc(getRSCM("npc.vorkath_8059"), tile, world)
        sleeping.respawns = false
        sleeping.attr[VORKATH_SPAWN_TILE] = tile
        world.spawn(sleeping)
        sleeping.setActive(true)
    }

    companion object {
        /** Ungael arena, region 9023 — SW-tile source coords (Kronos builds region 9023). */
        val ARENA_SOURCE = Area(2240, 4032, 2303, 4095)

        /** Boss SW spawn tile (Kronos SPAWN_POSITION 2269,4062; Vorkath is 7x7). */
        val BOSS_SPAWN_SRC = Tile(2269, 4062, 0)

        /** Player lands just inside the spines (Kronos entry force-moves to ~2272,4055). */
        val PLAYER_LANDING_SRC = Tile(2272, 4055, 0)

        /** Ungael surface, outside the spines. */
        val EXIT_TILE = Tile(2272, 4052, 0)

        val VORKATH_SPAWN_TILE = AttributeKey<Tile>()
        val VORKATH_WAKING = AttributeKey<Boolean>()

    }
}

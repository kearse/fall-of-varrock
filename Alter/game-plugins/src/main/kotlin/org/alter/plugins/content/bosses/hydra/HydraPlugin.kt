package org.alter.plugins.content.bosses.hydra

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.awardTickets
import org.alter.plugins.content.companion.CompanionPolicy
import org.alter.plugins.content.raids.RaidInstance
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Alchemical Hydra** — port #3 from Kronos rev-184 (`bosses/hydra/AlchemicalHydra.java`),
 * on the `docs/kronos-port-guide.md` pattern.
 *
 * This plugin owns the surround; the phase machine, chemical vents and specials live in
 * [HydraCombatPlugin]:
 *  - **Entry**: climb the rocks (object 34548 at 1351,10251 in Karuulm's lab) for a solo
 *    [RaidInstance] of region 5536; the copied rocks climb back out. `::hydra` is the
 *    belt-and-braces route.
 *  - **Death**: drops + Boss Tickets + Collection Log + rare broadcast, then a fresh
 *    green hydra rises at the anchor for chain kills while the instance lives.
 */
class HydraPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val table = DropTable(
        // OSRS-flavoured, scaled to our economy (the KBD/Vorkath odds convention).
        always = listOf(
            DropEntry("item.hydra_bones", 1, 1),
        ),
        main = listOf(
            DropEntry("item.coins_995", 40_000, 110_000, weight = 25),
            DropEntry("item.chaos_rune", 300, 600, weight = 12),
            DropEntry("item.dragon_knife", 50, 150, weight = 10),
            DropEntry("item.dragon_thrownaxe", 50, 150, weight = 10),
            DropEntry("item.super_restore4_noted", 4, 8, weight = 10),
            DropEntry("item.ranging_potion4_noted", 4, 8, weight = 8),
            DropEntry("item.mahogany_logs_noted", 40, 70, weight = 8),
            DropEntry("item.dragon_battleaxe", 1, 1, weight = 5),
        ),
        rare = listOf(
            // The brimstone-ring pieces roll independently and combine at a crafting bench later.
            DropEntry("item.hydras_eye", 1, 1, oneInN = 60, log = true),
            DropEntry("item.hydras_fang", 1, 1, oneInN = 60, log = true),
            DropEntry("item.hydras_heart", 1, 1, oneInN = 60, log = true),
            DropEntry("item.hydra_tail", 1, 1, oneInN = 125, announce = true, log = true),
            DropEntry("item.hydra_leather", 1, 1, oneInN = 125, announce = true, log = true),
            DropEntry("item.hydras_claw", 1, 1, oneInN = 300, announce = true, log = true),
            DropEntry("item.jar_of_chemicals", 1, 1, oneInN = 500, log = true),
            DropEntry("item.ikkle_hydra", 1, 1, oneInN = 1000, announce = true, log = true),
        ),
    )

    init {
        // Classic boss, companion-UNAWARE by design: companions stand down inside the lab
        // instance rather than the fight being edited for them (CompanionPolicy, Block 1).
        CompanionPolicy.denyInstanceOf(LAB_SOURCE, "the Hydra's chamber is a solo fight")

        // ── Entry/exit: the lab rocks. Outside → allocate + enter; inside an instance the
        // copied rocks climb back out (the Vorkath both-ways handler).
        val rocksId = getRSCM("object.rocks_34548")
        val rocksOpt = runCatching { getObject(rocksId).actions?.filterNotNull()?.firstOrNull { it.isNotBlank() } }.getOrNull()
        if (rocksOpt != null) {
            onObjOption(obj = rocksId, option = rocksOpt.lowercase()) {
                if (world.instanceAllocator.getMap(player.tile) != null) {
                    player.moveTo(LAB_ENTRANCE)
                    player.message("You climb back out of the hydra's chamber.")
                } else if (player.tile.isWithinRadius(ROCKS_SRC, 3)) {
                    enter(player)
                }
            }
            logger.info { "Hydra: bound lab rocks $rocksId via cache option '$rocksOpt'." }
        } else {
            logger.warn { "Hydra: rocks object $rocksId has no bindable cache option — ::hydra is the only way in." }
        }

        onCommand("hydra", description = "Enter the Alchemical Hydra's chamber") {
            if (world.instanceAllocator.getMap(player.tile) == null) enter(player)
        }

        // ── Death: economy hooks on every fighting form's id.
        for (key in listOf(
            "npc.alchemical_hydra", "npc.alchemical_hydra_8619",
            "npc.alchemical_hydra_8620", "npc.alchemical_hydra_8621",
        )) {
            onNpcDeath(key) {
                val boss = npc
                HydraCombatPlugin.cleanupEncounter(boss)
                val killer = boss.attr[KILLER_ATTR]?.get() as? Player

                if (killer != null) {
                    killer.awardTickets(PointKind.BOSS, BOSS_POINTS_PER_KILL)
                    table.roll(world).forEach { drop ->
                        val id = getRSCM(drop.item)
                        world.spawn(GroundItem(id, drop.amount, boss.tile, killer))
                        val name = getItem(id).name
                        if (drop.announce) {
                            world.players.forEach {
                                it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$name</col> from the Alchemical Hydra!</col>")
                            }
                        }
                        if (drop.log && CollectionLog.record(killer, id)) {
                            killer.message("<col=ffae00>New Collection Log slot: $name!</col>")
                        }
                    }
                    killer.message("<col=ff0000>You have slain the Alchemical Hydra.</col> (+$BOSS_POINTS_PER_KILL Boss Tickets)")
                }

                // Chain kills: a fresh green hydra rises while the instance lives.
                val anchor = HydraCombatPlugin.anchorOf(boss) ?: return@onNpcDeath
                world.queue {
                    wait(15)
                    if (world.instanceAllocator.getMap(anchor) == null) return@queue
                    HydraCombatPlugin.beginEncounter(world, anchor)
                }
            }
        }
    }

    private fun enter(p: Player) {
        val instance = RaidInstance.allocate(
            world = world,
            sourceArea = LAB_SOURCE,
            exitTile = LAB_ENTRANCE,
            owner = p.uid,
        )
        if (instance == null) {
            p.message("The chamber is sealed — try again in a moment.")
            return
        }
        HydraCombatPlugin.beginEncounter(world, instance.translate(BOSS_ANCHOR_SRC))
        p.moveTo(instance.translate(PLAYER_LANDING_SRC))
        p.message("<col=ff0000>You climb into the hydra's chamber. Chemical vents hiss around you...</col>")
    }

    companion object {
        /** Region 5536 — the Karuulm hydra lab (Kronos builds region 5536). */
        val LAB_SOURCE = Area(1344, 10240, 1407, 10303)

        /** Hydra SW spawn tile (Kronos SPAWN_POSITION 1364,10265). */
        val BOSS_ANCHOR_SRC = Tile(1364, 10265, 0)

        /** Player lands just inside the rocks (Kronos jumps the player 2N of 1351,10251). */
        val PLAYER_LANDING_SRC = Tile(1351, 10253, 0)

        /** The climb rocks in the source map, and where exits land. */
        val ROCKS_SRC = Tile(1351, 10251, 0)
        val LAB_ENTRANCE = Tile(1351, 10249, 0)

        const val BOSS_POINTS_PER_KILL = 25
    }
}

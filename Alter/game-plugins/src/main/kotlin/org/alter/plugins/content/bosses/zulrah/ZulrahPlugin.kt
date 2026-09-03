package org.alter.plugins.content.bosses.zulrah

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
 * **Zulrah** — port #2 from Kronos rev-184 (`bosses/zulrah/Zulrah.java`), on the pattern
 * `docs/kronos-port-guide.md` established with Vorkath.
 *
 * This plugin owns the surround; the rotation state machine lives in [ZulrahCombatPlugin]:
 *  - **Entry**: board the sacred-eel boat at Zul-Andra (object 10068 — bound via its cache
 *    action since the def is nameless; `::zulrah` is the belt-and-braces route). A solo
 *    [RaidInstance] copies the shrine (Kronos SHRINE_BOUNDS: 2268,3074 r16) and the
 *    serpentine form emerges at the CENTER anchor.
 *  - **Exit**: dying teleports you home (instanced deaths are safe); winning spawns the
 *    Zul-Andra return portal (object 11701) beside the shrine.
 *  - **Death**: the drop table rolls TWICE (Kronos `dropItems` calls super twice) +
 *    Boss Tickets + Collection Log + rare broadcast.
 */
class ZulrahPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val table = DropTable(
        // OSRS-flavoured, scaled to our economy; the whole table rolls twice per kill.
        always = listOf(
            DropEntry("item.zulrahs_scales", 100, 299),
        ),
        main = listOf(
            DropEntry("item.coins_995", 30_000, 90_000, weight = 25),
            DropEntry("item.death_rune", 100, 300, weight = 12),
            DropEntry("item.chaos_rune", 200, 500, weight = 10),
            DropEntry("item.snakeskin_noted", 20, 35, weight = 10),
            DropEntry("item.mahogany_logs_noted", 30, 60, weight = 10),
            DropEntry("item.manta_ray_noted", 10, 30, weight = 10),
            DropEntry("item.battlestaff_noted", 8, 15, weight = 8),
            DropEntry("item.dragon_med_helm", 1, 1, weight = 4),
        ),
        rare = listOf(
            DropEntry("item.tanzanite_fang", 1, 1, oneInN = 125, announce = true, log = true),
            DropEntry("item.magic_fang", 1, 1, oneInN = 125, announce = true, log = true),
            DropEntry("item.serpentine_visage", 1, 1, oneInN = 125, announce = true, log = true),
            DropEntry("item.uncut_onyx", 1, 1, oneInN = 200, announce = true, log = true),
            DropEntry("item.jar_of_swamp", 1, 1, oneInN = 500, log = true),
            DropEntry("item.tanzanite_mutagen", 1, 1, oneInN = 1500, announce = true, log = true),
            DropEntry("item.magma_mutagen", 1, 1, oneInN = 1500, announce = true, log = true),
            DropEntry("item.pet_snakeling", 1, 1, oneInN = 1000, announce = true, log = true),
        ),
    )

    init {
        // Classic boss, companion-UNAWARE by design: companions stand down inside the shrine
        // instance rather than the fight being edited for them (CompanionPolicy, Block 1).
        CompanionPolicy.denyInstanceOf(SHRINE_SOURCE, "Zulrah's shrine is a solo fight")

        // ── Entry: the sacred-eel boat. The rev-228 def is nameless (`null_10068`), so bind
        // whatever click action the cache actually carries (the Barrows binding pattern).
        val boatId = getRSCM("object.null_10068")
        val boatOpt = runCatching { getObject(boatId).actions?.filterNotNull()?.firstOrNull { it.isNotBlank() } }.getOrNull()
        if (boatOpt != null) {
            onObjOption(obj = boatId, option = boatOpt.lowercase()) { enter(player) }
            logger.info { "Zulrah: bound boat object $boatId via cache option '$boatOpt'." }
        } else {
            logger.warn { "Zulrah: boat object $boatId has no bindable cache option — ::zulrah is the only way in." }
        }

        onCommand("zulrah", description = "Sail to Zulrah's shrine") {
            if (player.tile.isWithinRadius(ZUL_ANDRA_DOCK, 15) || world.instanceAllocator.getMap(player.tile) == null) {
                enter(player)
            } else {
                player.message("You're already inside an instance — leave it first.")
            }
        }

        // ── Exit: the return portal that appears on a kill.
        val portalId = getRSCM("object.zulandra_teleport_11701")
        val portalOpt = runCatching { getObject(portalId).actions?.filterNotNull()?.firstOrNull { it.isNotBlank() } }.getOrNull()
        if (portalOpt != null) {
            onObjOption(obj = portalId, option = portalOpt.lowercase()) {
                player.animate(3864)
                player.graphic(1039)
                player.moveTo(ZUL_ANDRA_DOCK)
                player.message("You return to Zul-Andra.")
            }
        } else {
            logger.warn { "Zulrah: return portal $portalId has no bindable cache option." }
        }

        // ── Death: double drop roll + tickets + log + broadcast, on every form's id.
        for (key in listOf("npc.zulrah", "npc.zulrah_2043", "npc.zulrah_2044")) {
            onNpcDeath(key) {
                val boss = npc
                ZulrahCombatPlugin.cleanupEncounter(world, boss)
                val killer = boss.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath

                killer.awardTickets(PointKind.BOSS, BOSS_POINTS_PER_KILL)
                repeat(2) {
                    table.roll(world).forEach { drop ->
                        val id = getRSCM(drop.item)
                        world.spawn(GroundItem(id, drop.amount, boss.tile, killer))
                        val name = getItem(id).name
                        if (drop.announce) {
                            world.players.forEach {
                                it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$name</col> from Zulrah!</col>")
                            }
                        }
                        if (drop.log && CollectionLog.record(killer, id)) {
                            killer.message("<col=ffae00>New Collection Log slot: $name!</col>")
                        }
                    }
                }
                killer.message("<col=ff0000>You have slain Zulrah.</col> (+$BOSS_POINTS_PER_KILL Boss Tickets)")
            }
        }
    }

    private fun enter(p: Player) {
        if (world.instanceAllocator.getMap(p.tile) != null) {
            p.message("You're already somewhere you can't sail from.")
            return
        }
        val instance = RaidInstance.allocate(
            world = world,
            sourceArea = SHRINE_SOURCE,
            exitTile = ZUL_ANDRA_DOCK,
            owner = p.uid,
        )
        if (instance == null) {
            p.message("The priestess shakes her head — the shrine will take no more visitors right now.")
            return
        }
        val anchor = instance.translate(BOSS_ANCHOR_SRC)
        p.moveTo(instance.translate(PLAYER_LANDING_SRC))
        p.message("<col=ff0000>The priestess rows you to Zulrah's shrine, then hurriedly paddles away.</col>")
        ZulrahCombatPlugin.beginEncounter(world, anchor)
    }

    companion object {
        /** Kronos SHRINE_BOUNDS: centre 2268,3074, radius 16. */
        val SHRINE_SOURCE = Area(2252, 3058, 2284, 3090)

        /** Zulrah's CENTER anchor (Kronos swBase+18,+16 with base 2250,3058). */
        val BOSS_ANCHOR_SRC = Tile(2268, 3074, 0)

        /** Player lands south-east of the snake (Kronos swBase+20,+12). */
        val PLAYER_LANDING_SRC = Tile(2270, 3070, 0)

        /** Zul-Andra dock — entry boat, exit portal target, and instance exit tile. */
        val ZUL_ANDRA_DOCK = Tile(2199, 3056, 0)

        const val BOSS_POINTS_PER_KILL = 20
    }
}

package org.alter.plugins.content.bosses

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.awardTickets
import org.alter.rscm.RSCM.getRSCM

/**
 * **King Black Dragon — the signature boss** (content faucet, growth roadmap Phase 4).
 *
 * The KBD's spawn, stats and telegraphed multi-attack combat already exist
 * (`npcs/kbd/KbdConfigsPlugin` + `KbdCombatPlugin`, in its lair at region 9033, respawn
 * handled by the combat def). This plugin layers the *economy + retention* hooks on top
 * of its death:
 *
 *  - a weighted [DropTable]: guaranteed bones/hide, a weighted main roll (gp + tradeable
 *    upgrade mats that feed the Forge), and rare "1 in N" uniques + a follower pet,
 *  - **Boss points** awarded to the killer (spent at the boss reward shop),
 *  - **Collection Log** recording of notable drops (first-time slot message),
 *  - a server-wide **rare-drop broadcast** for the uniques/pet.
 */
class KbdBossPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val table = DropTable(
        always = listOf(
            DropEntry("item.dragon_bones", 1, 1),
            DropEntry("item.black_dragon_leather", 2, 2),
        ),
        main = listOf(
            DropEntry("item.coins_995", 50_000, 150_000, weight = 35),
            DropEntry("item.runite_bar", 2, 5, weight = 25), // Forge feedstock
            DropEntry("item.law_rune", 50, 150, weight = 15),
            DropEntry("item.rune_platebody", 1, 1, weight = 10),
            DropEntry("item.dragon_dart_tip", 5, 20, weight = 8),
            DropEntry("item.dragon_med_helm", 1, 1, weight = 5, log = true),
        ),
        rare = listOf(
            DropEntry("item.kbd_heads", 1, 1, oneInN = 50, announce = true, log = true),
            DropEntry("item.draconic_visage", 1, 1, oneInN = 250, announce = true, log = true),
            DropEntry("item.prince_black_dragon", 1, 1, oneInN = 1000, announce = true, log = true),
        ),
    )

    init {
        onNpcDeath("npc.king_black_dragon") {
            val boss = npc
            val killer = boss.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath

            killer.awardTickets(PointKind.BOSS, BOSS_POINTS_PER_KILL)

            table.roll(world).forEach { drop ->
                val id = getRSCM(drop.item)
                world.spawn(GroundItem(id, drop.amount, boss.tile, killer))

                val name = getItem(id).name
                if (drop.announce) {
                    world.players.forEach {
                        it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$name</col> from the King Black Dragon!</col>")
                    }
                }
                if (drop.log && CollectionLog.record(killer, id)) {
                    killer.message("<col=ffae00>New Collection Log slot: $name!</col>")
                }
            }

            killer.message("<col=ff0000>You have slain the King Black Dragon.</col> (+$BOSS_POINTS_PER_KILL Boss Tickets)")
        }

        // The Collection Log view commands live in CollectionLogPlugin (::cl / ::clog).

        onCommand("kbd", description = "Teleport to the King Black Dragon lair") {
            player.moveTo(Tile(2273, 4685, 0))
            player.message("<col=ff0000>You enter the King Black Dragon's lair...</col>")
        }
    }

    private companion object {
        const val BOSS_POINTS_PER_KILL = 10
    }
}

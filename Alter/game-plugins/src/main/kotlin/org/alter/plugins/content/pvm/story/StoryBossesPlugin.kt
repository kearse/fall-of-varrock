package org.alter.plugins.content.pvm.story

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.walkTo
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.BossDeath
import org.alter.plugins.content.bosses.BossKills
import org.alter.plugins.content.companion.CompanionPolicy
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.pvm.story.StoryBossesCombatPlugin.Companion.ALLY
import org.alter.plugins.content.pvm.story.StoryBossesCombatPlugin.Companion.adds
import org.alter.plugins.content.raids.RaidInstance
import org.alter.plugins.content.war.WarNpcNames
import org.alter.plugins.content.war.forge.WarForge
import org.alter.rscm.RSCM.getRSCM
import java.lang.ref.WeakReference

private val logger = KotlinLogging.logger {}

/**
 * Entry, instancing, the Arrav ally and payouts for the two story bosses. Arrav (the freed,
 * talkable model) stands in the fallen palace's west hall; talk to him or `::zemouregal` /
 * `::convergence`. One fight at a time per player; fifteen minutes; the same command inside
 * leaves; death/logout end it.
 */
class StoryBossesPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private enum class Kind { ZEMOUREGAL, CONVERGENCE }

    private class Run(val owner: Player, val kind: Kind, val instance: RaidInstance, val startedAt: Int) {
        var boss: Npc? = null
        var ally: Npc? = null
        var done = false
        var allyLost = false
    }

    private val runs = HashMap<Player, Run>()

    init {
        BossKills.register("zemouregal", StoryBosses.ZEMOUREGAL_NAME)
        BossKills.register("convergence", StoryBosses.CONVERGENCE_NAME)
        CompanionPolicy.denyInstanceOf(StoryBosses.PALACE_SOURCE, "Zemouregal's wards admit only you and Arrav")
        CompanionPolicy.denyInstanceOf(StoryBosses.DUNGEON_SOURCE, "The Fracture admits one living soul")

        onWorldInit {
            runCatching {
                val arrav = Npc(getRSCM(StoryBosses.ARRAV_HUB_KEY), StoryBosses.ARRAV_HUB, world)
                arrav.respawns = true
                arrav.walkRadius = 0
                world.spawn(arrav)
                logger.info { "story-bosses: Arrav posted at ${StoryBosses.ARRAV_HUB}." }
            }.onFailure { logger.error(it) { "story-bosses: failed to post Arrav" } }
        }

        onNpcOption(npc = StoryBosses.ARRAV_HUB_KEY, option = "talk-to") { player.queue { talk(player) } }
        onCommand("zemouregal", description = "Face Zemouregal (or leave the fight)") { toggle(player, Kind.ZEMOUREGAL) }
        onCommand("convergence", description = "Face the Convergence (or leave the fight)") { toggle(player, Kind.CONVERGENCE) }
        onLogout { runs.remove(player)?.let { it.done = true } }

        onNpcDeath(StoryBosses.ZEMOUREGAL_KEY) {
            val dead = npc
            val run = runs.values.firstOrNull { it.boss === dead } ?: return@onNpcDeath
            val p = run.owner
            val killer = dead.attr[KILLER_ATTR]?.get() as? Player ?: p
            run.done = true
            dead.forceChat("This is not the end, child of the Adventurer...")
            BossDeath.payout(world, killer, Tile(dead.tile.x, dead.tile.z, dead.tile.height), key = "zemouregal", name = StoryBosses.ZEMOUREGAL_NAME, drops = StoryBosses.ZEMOUREGAL_DROPS, tickets = StoryBosses.ZEMOUREGAL_TICKETS)
            killer.addPoints(PointKind.WAR_EFFORT, StoryBosses.ZEMOUREGAL_WAR_EFFORT)
            WarForge.awardCommendations(killer, StoryBosses.ZEMOUREGAL_COMMENDATIONS)
            if (world.chance(1, StoryBosses.ZEMOUREGAL_EMBER_ONE_IN)) {
                WarForge.awardEmbers(killer, 1)
                killer.message("<col=801700>A Warden's ember gutters in the Mahjarrat's wake — you take it.</col>")
            }
            val kills = (p.attr[StoryBosses.ZEMOUREGAL_KILLS] ?: 0) + 1
            p.attr[StoryBosses.ZEMOUREGAL_KILLS] = kills
            p.message("<col=801700>Zemouregal withdraws.</col> Times driven from the palace: $kills." + if (run.allyLost) " Arrav did not stand to see it." else " Arrav stands.")
            world.queue { wait(10); leave(p, "The wards fall quiet. You step back into the fallen palace.") }
        }
        onNpcDeath(StoryBosses.CONVERGENCE_KEY) {
            val dead = npc
            val run = runs.values.firstOrNull { it.boss === dead } ?: return@onNpcDeath
            val p = run.owner
            val killer = dead.attr[KILLER_ATTR]?.get() as? Player ?: p
            run.done = true
            BossDeath.payout(world, killer, Tile(dead.tile.x, dead.tile.z, dead.tile.height), key = "convergence", name = StoryBosses.CONVERGENCE_NAME, drops = StoryBosses.CONVERGENCE_DROPS, tickets = StoryBosses.CONVERGENCE_TICKETS)
            killer.addPoints(PointKind.WAR_EFFORT, StoryBosses.CONVERGENCE_WAR_EFFORT)
            WarForge.awardCommendations(killer, StoryBosses.CONVERGENCE_COMMENDATIONS)
            WarForge.awardEmbers(killer, StoryBosses.CONVERGENCE_EMBERS)
            val kills = (p.attr[StoryBosses.CONVERGENCE_KILLS] ?: 0) + 1
            p.attr[StoryBosses.CONVERGENCE_KILLS] = kills
            p.message("<col=801700>The Convergence collapses into the conduits.</col> Fractures sealed: $kills." + if (kills == 1) " The Realm will know you as a Fracture-Sealer." else "")
            world.queue { wait(10); leave(p, "The dungeon exhales. You climb back to the Digsite.") }
        }
    }

    private suspend fun org.alter.game.model.queue.QueueTask.talk(p: Player) {
        val id = getRSCM(StoryBosses.ARRAV_HUB_KEY)
        chatNpc(p, "The heart is mine again. What's left of the city is yours.<br>Zemouregal still haunts this palace in the spaces between<br>— and something worse waits under the Digsite.", npc = id, title = "Arrav")
        when (options(p, "Face Zemouregal", "Descend to the Fracture", "Who are you?", "Nothing for now", title = "Arrav")) {
            1 -> if (StoryBosses.mayFace(p, StoryBosses.ZEMOUREGAL_FLAG)) begin(p, Kind.ZEMOUREGAL)
                 else chatNpc(p, "Not yet. Free me properly first — the Realm's knights<br>will know the way.", npc = id, title = "Arrav")
            2 -> if (StoryBosses.mayFace(p, StoryBosses.FRACTURE_FLAG)) begin(p, Kind.CONVERGENCE)
                 else chatNpc(p, "The Fracture isn't open to you. Sever the surface<br>amplification first.", npc = id, title = "Arrav")
            3 -> {
                chatNpc(p, "Arrav. Once Varrock's champion; for a long time, his.<br>Your parent found the way to my heart before the Fall<br>took them. I owe the Adventurer's child a war.", npc = id, title = "Arrav")
                chatNpc(p, "Fight beside me in the palace and I'll strike with you.<br>Under the Digsite you go alone — the conduits<br>answer to nothing living.", npc = id, title = "Arrav")
            }
            else -> chatNpc(p, "Then rest. The dead are patient; be more so.", npc = id, title = "Arrav")
        }
    }

    private fun toggle(p: Player, kind: Kind) {
        val current = runs[p]
        if (current != null) {
            leave(p, if (current.kind == Kind.ZEMOUREGAL) "You withdraw from the palace wards." else "You climb out of the Fracture.")
            return
        }
        val flag = if (kind == Kind.ZEMOUREGAL) StoryBosses.ZEMOUREGAL_FLAG else StoryBosses.FRACTURE_FLAG
        if (!StoryBosses.mayFace(p, flag)) {
            p.message("That fight isn't open to you yet. Speak to Arrav in the fallen palace.")
            return
        }
        begin(p, kind)
    }

    private fun begin(p: Player, kind: Kind) {
        if (runs.containsKey(p) || world.instanceAllocator.getMap(p.tile) != null) {
            p.message("Finish or leave the fight you're in first.")
            return
        }
        val (source, exit) = if (kind == Kind.ZEMOUREGAL) StoryBosses.PALACE_SOURCE to StoryBosses.HUB_LANDING else StoryBosses.DUNGEON_SOURCE to StoryBosses.DUNGEON_EXIT
        val instance = RaidInstance.allocate(world = world, sourceArea = source, exitTile = exit, owner = p.uid)
        if (instance == null) {
            p.message("The wards shudder and hold — try again in a moment.")
            return
        }
        val run = Run(p, kind, instance, world.currentCycle)
        runs[p] = run
        p.animate(832)
        val entry = if (kind == Kind.ZEMOUREGAL) StoryBosses.PALACE_ENTRY else StoryBosses.DUNGEON_ENTRY
        p.moveTo(instance.translate(entry))
        p.message(if (kind == Kind.ZEMOUREGAL) "<col=801700>The palace folds around you. Somewhere ahead, a Mahjarrat laughs.</col>" else "<col=801700>You drop into the deepest current Senntisten. The dark is not empty.</col>")
        world.queue {
            wait(3)
            if (run.done || runs[p] !== run) return@queue
            if (kind == Kind.ZEMOUREGAL) spawnZemouregal(run) else spawnConvergence(run)
            while (!run.done && runs[p] === run) {
                if (world.currentCycle - run.startedAt >= StoryBosses.RUN_TICKS) {
                    leave(p, "The wards close. The fight is over for now.")
                    return@queue
                }
                wait(10)
            }
        }
    }

    private fun spawnZemouregal(run: Run) {
        val p = run.owner
        runCatching {
            val boss = Npc(getRSCM(StoryBosses.ZEMOUREGAL_KEY), world.snapToWalkable(run.instance.translate(StoryBosses.PALACE_BOSS), maxRadius = 4), world)
            boss.respawns = false
            boss.walkRadius = 0
            world.spawn(boss)
            boss.setActive(true)
            WarNpcNames.rename(boss, StoryBosses.ZEMOUREGAL_NAME)
            run.boss = boss

            val ally = Npc(getRSCM(StoryBosses.ARRAV_ALLY_KEY), world.snapToWalkable(run.instance.translate(StoryBosses.PALACE_ARRAV), maxRadius = 3), world)
            ally.respawns = false
            ally.walkRadius = 0
            world.spawn(ally)
            ally.setActive(true)
            WarNpcNames.rename(ally, "Arrav")
            run.ally = ally
            boss.attr[ALLY] = WeakReference(ally)

            boss.forceChat("You again. And my old champion, unchained. How quaint.")
            ally.forceChat("For Varrock!")
            boss.attack(p)
            p.message("<col=ff0000>Zemouregal turns from the throne. Arrav draws steel beside you.</col>")
            allyLoop(run)
        }.onFailure { logger.warn { "story-bosses: failed to spawn Zemouregal: ${it.message}" } }
    }

    /** Arrav strikes Zemouregal every four ticks while both stand; his fall feeds the Mahjarrat. */
    private fun allyLoop(run: Run) {
        world.queue {
            while (!run.done) {
                val boss = run.boss ?: break
                val ally = run.ally ?: break
                if (boss.isDead() || boss.index < 0) break
                if (ally.isDead() || ally.index < 0) {
                    if (!run.allyLost) {
                        run.allyLost = true
                        boss.forceChat("Your champion falls again. Delicious.")
                        boss.setCurrentHp(minOf(boss.getMaxHp(), boss.getCurrentHp() + 200))
                        boss.graphic(377, 0)
                        run.owner.message("<col=ff0000>Arrav falls — Zemouregal drinks deep of it.</col>")
                    }
                    break
                }
                if (ally.tile.isWithinRadius(boss.tile, boss.getSize())) {
                    ally.facePawn(boss)
                    ally.animate(390)
                    boss.hit(damage = 8 + world.random(14), delay = 1)
                } else {
                    ally.walkTo(boss.tile)
                }
                wait(4)
            }
        }
    }

    private fun spawnConvergence(run: Run) {
        val p = run.owner
        runCatching {
            val boss = Npc(getRSCM(StoryBosses.CONVERGENCE_KEY), world.snapToWalkable(run.instance.translate(StoryBosses.DUNGEON_BOSS), maxRadius = 5), world)
            boss.respawns = false
            boss.walkRadius = 0
            world.spawn(boss)
            boss.setActive(true)
            WarNpcNames.rename(boss, StoryBosses.CONVERGENCE_NAME)
            run.boss = boss
            boss.forceChat("...you came down. They all come down.")
            boss.attack(p)
            p.message("<col=ff0000>The Convergence gathers itself out of the conduits!</col>")
        }.onFailure { logger.warn { "story-bosses: failed to spawn the Convergence: ${it.message}" } }
    }

    private fun leave(p: Player, message: String) {
        val run = runs.remove(p) ?: return
        run.done = true
        run.boss?.let { b ->
            b.adds().forEach { if (!it.isDead() && it.index >= 0) world.remove(it) }
            if (!b.isDead() && b.index >= 0) world.remove(b)
        }
        run.ally?.let { if (!it.isDead() && it.index >= 0) world.remove(it) }
        if (world.instanceAllocator.getMap(p.tile) != null) {
            p.moveTo(if (run.kind == Kind.ZEMOUREGAL) StoryBosses.HUB_LANDING else StoryBosses.DUNGEON_EXIT)
        }
        p.message(message)
    }
}

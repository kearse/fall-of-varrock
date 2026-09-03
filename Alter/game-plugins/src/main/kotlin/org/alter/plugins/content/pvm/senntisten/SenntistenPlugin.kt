package org.alter.plugins.content.pvm.senntisten

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.BossDeath
import org.alter.plugins.content.bosses.BossKills
import org.alter.plugins.content.companion.CompanionPolicy
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.mechanics.Flags
import org.alter.plugins.content.pvm.varrock.VarrockPvm
import org.alter.plugins.content.raids.RaidInstance
import org.alter.plugins.content.war.WarNpcNames
import org.alter.plugins.content.war.forge.WarForge
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Runs an expedition: **Operate** a Digsite winch (or `::expedition`) → a private copy of the
 * temple, three waves from the corridor into the hall, then the Custodian at the altar. Wave
 * clears pay salvage; the Custodian pays the run. Twelve minutes, then the winch hauls you up;
 * `::expedition` inside leaves early; death/logout end it (instanced deaths are safe).
 */
class SenntistenPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private class Run(val owner: Player, val instance: RaidInstance, val startedAt: Int) {
        var wave = 0
        val alive = mutableListOf<Npc>()
        var custodian: Npc? = null
        var done = false
    }

    private val runs = HashMap<Player, Run>()

    init {
        BossKills.register("custodian", Senntisten.CUSTODIAN_NAME)
        CompanionPolicy.denyInstanceOf(Senntisten.SOURCE, "The temple's wards admit one living soul")

        Senntisten.WINCH_KEYS.forEach { key ->
            runCatching { onObjOption(obj = key, option = "operate") { begin(player) } }
                .onFailure { logger.warn { "senntisten: $key has no 'operate' option (${it.message})" } }
        }
        onCommand("expedition", description = "Descend into Senntisten (or leave an expedition)") {
            if (runs.containsKey(player)) leave(player, "You haul yourself back up the winch rope.") else begin(player)
        }
        onLogout { runs.remove(player)?.let { it.done = true } }

        Senntisten.WARDENS.forEach { w ->
            onNpcDeath(w.npcKey) {
                val dead = npc
                val run = runs.values.firstOrNull { dead in it.alive } ?: return@onNpcDeath
                run.alive.remove(dead)
                if (run.alive.isEmpty() && run.custodian == null) waveCleared(run)
            }
        }
        onNpcDeath(Senntisten.CUSTODIAN_KEY) {
            val dead = npc
            val run = runs.values.firstOrNull { it.custodian === dead } ?: return@onNpcDeath
            run.custodian = null
            run.done = true
            val p = run.owner
            val killer = dead.attr[KILLER_ATTR]?.get() as? Player ?: p
            BossDeath.payout(world, killer, Tile(dead.tile.x, dead.tile.z, dead.tile.height), key = "custodian", name = Senntisten.CUSTODIAN_NAME, drops = Senntisten.CUSTODIAN_DROPS, tickets = Senntisten.CUSTODIAN_TICKETS)
            killer.addPoints(PointKind.WAR_EFFORT, Senntisten.CUSTODIAN_WAR_EFFORT)
            WarForge.awardCommendations(killer, Senntisten.CUSTODIAN_COMMENDATIONS)
            val runsDone = (p.attr[Senntisten.RUNS_ATTR] ?: 0) + 1
            p.attr[Senntisten.RUNS_ATTR] = runsDone
            p.message("<col=801700>The Custodian falls and the conduits go quiet.</col> Expeditions completed: $runsDone. The winch rope drops for you.")
            world.queue {
                wait(10)
                leave(p, "You climb the rope back to the Digsite.")
            }
        }
    }

    private fun begin(p: Player) {
        if (runs.containsKey(p) || world.instanceAllocator.getMap(p.tile) != null) {
            p.message("You're already on an expedition — finish it or leave it first.")
            return
        }
        if (Senntisten.ENFORCE_QUEST && !Flags.has(p, Senntisten.QUEST_FLAG)) {
            p.message("The winch is locked. The temple below is not yet yours to enter.")
            return
        }
        val instance = RaidInstance.allocate(world = world, sourceArea = Senntisten.SOURCE, exitTile = Senntisten.SURFACE_EXIT, owner = p.uid)
        if (instance == null) {
            p.message("The winch groans and jams — try again in a moment.")
            return
        }
        val run = Run(p, instance, world.currentCycle)
        runs[p] = run
        p.animate(832)
        p.moveTo(instance.translate(Senntisten.ENTRY_SRC))
        p.message("<col=801700>You are lowered into the dark. Old stone, older wards — Senntisten sleeps below the Digsite. Something has noticed you.</col>")
        world.queue {
            wait(3)
            if (run.done || runs[p] !== run) return@queue
            spawnWave(run)
            // The clock.
            while (!run.done && runs[p] === run) {
                if (world.currentCycle - run.startedAt >= Senntisten.RUN_TICKS) {
                    leave(p, "The winch crew hauls you up — the temple's wards are closing.")
                    return@queue
                }
                wait(10)
            }
        }
    }

    private fun spawnWave(run: Run) {
        val p = run.owner
        val wave = Senntisten.WAVES.getOrNull(run.wave)
        if (wave == null) {
            spawnCustodian(run)
            return
        }
        val anchor = run.instance.translate(wave.anchor)
        wave.roster.forEach { w ->
            val at = world.snapToWalkable(Tile(anchor.x + world.random(6) - 3, anchor.z + world.random(6) - 3, anchor.height), maxRadius = 4)
            runCatching {
                val n = Npc(getRSCM(w.npcKey), at, world)
                n.respawns = false
                world.spawn(n)
                n.setActive(true)
                WarNpcNames.rename(n, w.name)
                n.attack(p)
                run.alive += n
            }.onFailure { logger.warn { "senntisten: failed to spawn ${w.npcKey}: ${it.message}" } }
        }
        p.message("<col=ff0000>Wave ${run.wave + 1} of ${Senntisten.WAVES.size}: ${wave.roster.size} wardens stir${if (run.wave == 0) " in the corridor" else " in the hall"}!</col>")
    }

    private fun waveCleared(run: Run) {
        val p = run.owner
        val n = Senntisten.SALVAGE_PER_WAVE_MIN + world.random(Senntisten.SALVAGE_PER_WAVE_MAX - Senntisten.SALVAGE_PER_WAVE_MIN)
        give(p, VarrockPvm.SALVAGE_KEY, n)
        run.wave++
        p.message("<col=4f9b4f>Wave cleared.</col> You pry $n salvage from the wardens' remains." + if (run.wave < Senntisten.WAVES.size) " Press on." else " The altar hall is quiet... for now.")
        world.queue {
            wait(Senntisten.WAVE_GAP_TICKS)
            if (!run.done && runs[p] === run) spawnWave(run)
        }
    }

    private fun spawnCustodian(run: Run) {
        val p = run.owner
        val at = world.snapToWalkable(run.instance.translate(Senntisten.ALTAR_SRC), maxRadius = 4)
        runCatching {
            val boss = Npc(getRSCM(Senntisten.CUSTODIAN_KEY), at, world)
            boss.respawns = false
            boss.walkRadius = 0
            world.spawn(boss)
            boss.setActive(true)
            WarNpcNames.rename(boss, Senntisten.CUSTODIAN_NAME)
            boss.forceChat("Who disturbs the conduits of Senntisten?")
            boss.attack(p)
            run.custodian = boss
            p.message("<col=ff0000>${Senntisten.CUSTODIAN_NAME} rises from the altar!</col>")
        }.onFailure { logger.warn { "senntisten: failed to spawn the Custodian: ${it.message}" } }
    }

    private fun leave(p: Player, message: String) {
        val run = runs.remove(p) ?: return
        run.done = true
        run.alive.forEach { if (!it.isDead() && it.index >= 0) world.remove(it) }
        run.custodian?.let { if (!it.isDead() && it.index >= 0) world.remove(it) }
        if (world.instanceAllocator.getMap(p.tile) != null) p.moveTo(Senntisten.SURFACE_EXIT)
        p.message(message)
    }

    private fun give(p: Player, key: String, amount: Int) {
        val id = runCatching { getRSCM(key) }.getOrNull() ?: return
        val added = p.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) world.spawn(GroundItem(id, leftover, p.tile, p))
    }
}

package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.announce.Announce

private val logger = KotlinLogging.logger {}

/**
 * **Marches** — the realm's own scheduled warband (story-and-grind-design §2). Every
 * [INTERVAL_TICKS] the Knights of Lumbridge march on the hostile front, and ANY player may
 * fight beside them: the beginner/mid player's entry into the war's offense, below the
 * paid command ladder (March → Raid → Campaign → Conquest).
 *
 * The cycle: a muster call goes out [WARN_TICKS] ahead ("the march sets out in ~5 minutes"),
 * then a [CampaignTier.MARCH] column launches down the standard campaign machinery
 * ([CampaignDirector] does the marching/fighting; [CapturePayout] splits the pooled spoils
 * by participation). Marches are free to players but **consume realm supplies** — a starved
 * realm skips the march and says so, which is the Mire supply loop made visible.
 *
 * `::march` rallies a player to the column (with a second-confirmation warning when the
 * column is already fighting on PvP ground). Marches CAN fail — 10 knights alone will often
 * be driven back; the realm learns to march with them.
 */
class MarchPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private enum class State { IDLE, MUSTERING }
    private var state = State.IDLE
    /** World cycle the current timer leg completes at (drives the "next march in Xm" line). */
    private var nextFireCycle = 0
    /** username -> world cycle until which their `::march` hot-zone confirmation stands. */
    private val hotConfirm = HashMap<String, Int>()

    init {
        val timer = TimerKey()
        onWorldInit {
            schedule(world, timer, INTERVAL_TICKS - WARN_TICKS)
        }
        onTimer(timer) {
            when (state) {
                State.IDLE -> muster(world, timer)
                State.MUSTERING -> {
                    launch(world)
                    state = State.IDLE
                    schedule(world, timer, INTERVAL_TICKS - WARN_TICKS)
                }
            }
        }

        // ::march — rally to the column. Free, for everyone; the march is the public war.
        onCommand("march", description = "Rally to the knights' march on the enemy") {
            val march = CampaignRegistry.activeMarch()
            if (march == null) {
                val mins = ((nextFireCycle - world.currentCycle).coerceAtLeast(0) * 6 / 10 + 59) / 60
                val eta = if (state == State.MUSTERING) "it sets out in ~$mins minute(s)" else "the next musters in ~$mins minute(s)"
                player.message("<col=801700>No march is in the field — $eta. Watch for the muster call.</col>")
                return@onCommand
            }
            val dest = march.rallyTile(world)
            // Rallying into the live battle line is PvP ground — make them say it twice.
            if (march.coversBattle(dest) && (hotConfirm[player.username] ?: 0) < world.currentCycle) {
                hotConfirm[player.username] = world.currentCycle + CONFIRM_WINDOW
                player.message("<col=801700>The column is already fighting in the enemy's territory — that is PvP ground and you can be attacked.</col> Type <col=ffae00>::march</col> again to rally to them anyway.")
                return@onCommand
            }
            hotConfirm.remove(player.username)
            player.moveTo(dest)
            player.message("<col=4f9b4f>You rally to the knights' column. Fight beside them — the realm pays its soldiers from the spoils.</col>")
        }

        // ::marchnow — admin: skip the wait and run the next cycle step immediately.
        onCommand("marchnow", Privilege.ADMIN_POWER, description = "Force the march cycle forward (test)") {
            world.timers[timer] = 1
            player.message("<col=4f9b4f>[test] March cycle advanced (${if (state == State.IDLE) "muster call" else "launch"} next tick).</col>")
        }
    }

    private fun schedule(world: World, timer: TimerKey, ticks: Int) {
        world.timers[timer] = ticks
        nextFireCycle = world.currentCycle + ticks
    }

    /** The muster call, [WARN_TICKS] before launch — or the starved/busy skip. */
    private fun muster(world: World, timer: TimerKey) {
        val op = Campaigns.hostileTarget()
        val skip = when {
            op == null -> true // no hostile front configured yet
            CampaignRegistry.activeMarch() != null -> true // last march still in the field
            CampaignRegistry.isAttacking(op.cityKey) -> true // a commander's campaign holds the front
            world.players.count() == 0 -> true // empty world — don't churn the garrison
            else -> false
        }
        if (skip) {
            schedule(world, timer, INTERVAL_TICKS - WARN_TICKS) // try again next cycle
            return
        }
        if (!RealmSupply.canAfford(CampaignTier.MARCH.supplyCost)) {
            Announce.broadcast(world, "<col=801700>The Knights of Lumbridge cannot march — the realm's war-stores are too low (${RealmSupply.meter()}/${CampaignTier.MARCH.supplyCost} needed). Hand supplies to a Quartermaster!</col>")
            schedule(world, timer, INTERVAL_TICKS - WARN_TICKS)
            return
        }
        Announce.broadcast(world, "<col=4f9b4f>The Knight-Captain musters a march on ${op!!.displayName} — it sets out in ~${WARN_TICKS * 6 / 600} minutes! Any soldier may fight beside the column: answer with <col=ffae00>::march</col><col=4f9b4f>.</col>")
        state = State.MUSTERING
        schedule(world, timer, WARN_TICKS)
    }

    /** Launch the column (re-checking the gates — the world may have changed since the call). */
    private fun launch(world: World) {
        val op = Campaigns.hostileTarget() ?: return
        if (CampaignRegistry.activeMarch() != null || CampaignRegistry.isAttacking(op.cityKey)) return
        if (!RealmSupply.canAfford(CampaignTier.MARCH.supplyCost)) {
            Announce.broadcast(world, "<col=801700>The march is called off — the realm's war-stores ran dry at the gate.</col>")
            return
        }
        if (!CampaignRegistry.start(world, op, CampaignTier.MARCH, sponsor = null)) {
            logger.warn { "[MARCH] scheduled march on ${op.cityKey} failed to start" }
            return
        }
        RealmSupply.consume(world, CampaignTier.MARCH, "The Knights of Lumbridge", op.displayName)
        logger.info { "[MARCH] scheduled march launched on ${op.cityKey} (supplies now ${RealmSupply.meter()})." }
    }

    private companion object {
        /** Full march cycle, muster call to muster call (~30 min at 0.6s ticks). TUNE. */
        const val INTERVAL_TICKS = 3000
        /** Muster warning lead time (~5 min). TUNE. */
        const val WARN_TICKS = 500
        /** How long a `::march` hot-zone confirmation stands (~30s). */
        const val CONFIRM_WINDOW = 50
    }
}

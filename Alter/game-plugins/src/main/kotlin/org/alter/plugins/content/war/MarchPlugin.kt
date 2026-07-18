package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.war.forge.WarForge
import org.alter.rscm.RSCM.getRSCM

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
 * by participation). Marches are **automatic and supply-free**: they fire every cycle no
 * matter what — no player count and no supply level can stop them, and they never spend a
 * single point of the realm's war-stores — so the war keeps moving on its own and the early
 * game never stalls waiting on the realm to be supplied. The supply meter belongs entirely to
 * the paid command ladder above the march (Raid → Campaign → Conquest); marches are the free
 * tier beneath it.
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
    /** The district the mustering march will move on (picked at the muster call). */
    private var target: District? = null
    /** True while the mustering/next march is a GRAND MARCH (every [GRAND_EVERY]th). */
    private var pendingGrand = false
    /** The store patron funding the mustering march (name), if any — covers its supply cost. */
    private var patron: String? = null
    /** The live district Warden of a running Grand March, if any. */
    private var warden: Npc? = null
    private var wardenDistrict: District? = null

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

        // Warden kill: additive death hook, bails instantly unless a Grand March Warden is up.
        onAnyNpcDeath {
            val w = warden ?: return@onAnyNpcDeath
            if (npc === w) onWardenSlain(world, w)
        }

        // ::districts — the reconquest map: every district's pressure/broken state.
        onCommand("districts", description = "Show the reconquest of Falador, district by district") {
            Districts.statusLines().forEach { player.message(it) }
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

    /** The muster call, [WARN_TICKS] before launch — or the busy skip (never a supply skip). */
    private fun muster(world: World, timer: TimerKey) {
        val op = Campaigns.marchTarget() // the scheduled marches move on overrun Falador
        val skip = when {
            CampaignRegistry.activeMarch() != null -> true // last march still in the field
            CampaignRegistry.isAttacking(op.cityKey) -> true // a march already holds the Falador front
            world.players.count() == 0 -> true // empty world — don't churn the garrison
            else -> false
        }
        if (skip) {
            schedule(world, timer, INTERVAL_TICKS - WARN_TICKS) // try again next cycle
            return
        }
        // A store patron's funded march jumps the queue (and pays its own supply cost);
        // otherwise every GRAND_EVERYth launched march is a GRAND MARCH.
        val funded = WarState.popPatronMarch()
        patron = funded?.first
        val grand = funded?.second ?: ((WarState.getMarchCount() + 1) % GRAND_EVERY == 0)
        val tier = if (grand) CampaignTier.GRAND_MARCH else CampaignTier.MARCH
        // The march is unconditional and supply-free — it musters regardless of the realm's
        // supply level and spends nothing, so the war keeps moving without waiting on players.
        val d = Districts.marchTarget(world)
        target = d
        pendingGrand = grand
        val mins = WARN_TICKS * 6 / 600
        when {
            funded != null && grand ->
                Announce.broadcast(world, "<col=ffcc00>${funded.first}, Patron of the Realm, funds a GRAND MARCH against <col=ffae00>the Warden of ${d.display}</col><col=ffcc00> — ${tier.troops} knights set out in ~$mins minutes! <col=ffae00>::march</col><col=ffcc00> to fight!</col>")
            funded != null ->
                Announce.broadcast(world, "<col=4f9b4f>${funded.first}, Patron of the Realm, funds a march on <col=ffae00>${d.display}</col><col=4f9b4f> — it sets out in ~$mins minutes! Fight beside their banner: <col=ffae00>::march</col><col=4f9b4f>.</col>")
            grand ->
                Announce.broadcast(world, "<col=ffcc00>A GRAND MARCH musters against <col=ffae00>the Warden of ${d.display}</col><col=ffcc00> — ${tier.troops} knights set out in ~$mins minutes! The Warden's embers feed the Royal Smith's forge. <col=ffae00>::march</col><col=ffcc00> to fight!</col>")
            else ->
                Announce.broadcast(world, "<col=4f9b4f>The Knight-Captain musters a march on <col=ffae00>${d.display}</col><col=4f9b4f> of Fallen ${op.displayName} — it sets out in ~$mins minutes! Any soldier may fight beside the column: answer with <col=ffae00>::march</col><col=4f9b4f>.</col>")
        }
        state = State.MUSTERING
        schedule(world, timer, WARN_TICKS)
    }

    /** Launch the column (re-checking the gates — the world may have changed since the call). */
    private fun launch(world: World) {
        val fundedBy = patron
        patron = null
        val op = Campaigns.marchTarget() // overrun Falador
        if (CampaignRegistry.activeMarch() != null || CampaignRegistry.isAttacking(op.cityKey)) return requeue(fundedBy)
        val grand = pendingGrand
        pendingGrand = false
        val tier = if (grand) CampaignTier.GRAND_MARCH else CampaignTier.MARCH
        // No supply gate here either — a march that mustered always launches.
        // Point the column at the mustered district: the Falador march route to the city mouth (which is
        // where the route ends), then the district's street approach (waypoints snap to walkable + unstick,
        // so a rough hop heals).
        val d = target ?: Districts.marchTarget(world)
        target = null
        val marchOp = op.copy(
            route = op.route + d.approach,
            objectiveTile = d.rally,
        )
        val started = CampaignRegistry.start(world, marchOp, tier, sponsor = null) { won ->
            if (won) Districts.creditMarchWin(world, d)
            despawnWarden(world, marchWon = won)
        }
        if (!started) {
            logger.warn { "[MARCH] scheduled march on ${op.cityKey}/${d.key} failed to start" }
            return
        }
        WarState.incMarchCount()
        if (grand) spawnWarden(world, d)
        // Marches never touch the realm's war-stores — they are the free, always-on tier. The
        // supply meter is spent only by the paid command ladder (campaigns and conquests).
        if (fundedBy != null) {
            Announce.broadcast(world, "<col=ffae00>$fundedBy's patronage rides with the ${tier.display} to ${d.display} — the column sets out under their banner.</col>")
        }
        logger.info { "[MARCH] scheduled ${tier.display} launched on ${op.cityKey}/${d.key} patron=$fundedBy (supply-free)." }
    }

    /** A funded march that couldn't launch goes back on the queue — a purchase is never lost. */
    private fun requeue(fundedBy: String?) {
        if (fundedBy != null) WarState.queuePatronMarch(fundedBy, pendingGrand)
    }

    /** The Grand March's boss-tier defender: the district's Warden, waiting at the rally point. */
    private fun spawnWarden(world: World, d: District) {
        runCatching {
            val npc = Npc(getRSCM(WARDEN_NPC), d.rally, world)
            npc.walkRadius = 4
            world.spawn(npc)
            WarNpcNames.rename(npc, "The Warden of ${d.display}")
            npc.combatDef = npc.combatDef.copy(
                attack = WARDEN_ATTACK, strength = WARDEN_STRENGTH,
                defence = WARDEN_DEFENCE, hitpoints = WARDEN_HP,
            )
            npc.stats.setMaxLevel(NpcSkills.ATTACK, WARDEN_ATTACK)
            npc.stats.setCurrentLevel(NpcSkills.ATTACK, WARDEN_ATTACK)
            npc.stats.setMaxLevel(NpcSkills.STRENGTH, WARDEN_STRENGTH)
            npc.stats.setCurrentLevel(NpcSkills.STRENGTH, WARDEN_STRENGTH)
            npc.stats.setMaxLevel(NpcSkills.DEFENCE, WARDEN_DEFENCE)
            npc.stats.setCurrentLevel(NpcSkills.DEFENCE, WARDEN_DEFENCE)
            npc.setCurrentHp(WARDEN_HP)
            npc.respawns = false
            npc.setActive(true)
            warden = npc
            wardenDistrict = d
        }.onFailure { logger.error(it) { "[MARCH] failed to spawn the Warden of ${d.key}" } }
    }

    /** Tear down a surviving Warden when its Grand March ends (with a taunt if the march lost). */
    private fun despawnWarden(world: World, marchWon: Boolean) {
        val w = warden ?: return
        val d = wardenDistrict
        warden = null
        wardenDistrict = null
        if (w.index >= 0 && world.npcs.contains(w) && !w.isDead()) {
            world.remove(w)
            if (!marchWon && d != null) {
                Announce.broadcast(world, "<col=801700>The Warden of ${d.display} still stands — his embers stay cold in the enemy's grip.</col>")
            }
        }
    }

    /** Warden slain: embers for the fighters — guaranteed for the MVP, rolled for the rest. */
    private fun onWardenSlain(world: World, w: Npc) {
        val d = wardenDistrict
        warden = null
        wardenDistrict = null
        val fighters = ArrayList<Player>()
        world.players.forEach { p ->
            if (p.index >= 0 && w.damageMap.getDamageFrom(p) > 0) fighters.add(p)
        }
        val mvp = fighters.maxByOrNull { w.damageMap.getDamageFrom(it) }
        fighters.forEach { p ->
            when {
                p === mvp -> WarForge.awardEmbers(p, 1)
                world.random(EMBER_ROLL - 1) == 0 -> WarForge.awardEmbers(p, 1)
                else -> p.message("<col=801700>The Warden's embers scatter — the killing blow's crew claimed them.</col>")
            }
        }
        val where = d?.display ?: "the district"
        Announce.broadcast(world, "<col=ffcc00>The Warden of $where has FALLEN${mvp?.let { " — ${it.username} struck truest" } ?: ""}! His embers feed the Royal Smith's forge.</col>")
    }

    private companion object {
        /** Full march cycle, muster call to muster call (~30 min at 0.6s ticks). TUNE. */
        const val INTERVAL_TICKS = 3000
        /** Muster warning lead time (~5 min). TUNE. */
        const val WARN_TICKS = 500
        /** How long a `::march` hot-zone confirmation stands (~30s). */
        const val CONFIRM_WINDOW = 50
        /** Every Nth launched march is a GRAND MARCH (persisted counter in [WarState]). TUNE. */
        const val GRAND_EVERY = 8
        /** 1-in-N Warden's-ember roll for non-MVP fighters. TUNE. */
        const val EMBER_ROLL = 3
        /** The Warden's base model (renamed + boosted at spawn). */
        const val WARDEN_NPC = "npc.black_knight"
        const val WARDEN_ATTACK = 200
        const val WARDEN_STRENGTH = 180
        const val WARDEN_DEFENCE = 160
        const val WARDEN_HP = 1500
    }
}

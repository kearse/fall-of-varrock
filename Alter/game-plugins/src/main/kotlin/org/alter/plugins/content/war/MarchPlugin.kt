package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.war.events.WarEvents
import org.alter.plugins.content.war.forge.WarForge
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Marches** — the realm's own scheduled public warband (design authority §6). Every
 * [INTERVAL_TICKS] the Knights of Lumbridge march on one target from the [MarchTargets] pool —
 * hostile camps, rogue positions, undead camps, the Varrock outskirts; never the deep city — and
 * ANY player may fight beside them: the beginner/mid player's entry into the war's offense, below
 * the command ladder (March → Lord's operation → Campaign → Conquest).
 *
 * The cycle: a muster call goes out [WARN_TICKS] ahead ("the march sets out in ~5 minutes"),
 * then a [CampaignTier.MARCH] column launches down the standard campaign machinery
 * ([CampaignDirector] does the marching/fighting; [CapturePayout] splits the pooled spoils
 * by participation). Marches are **automatic and supply-free**: they fire every cycle no
 * matter what — no player count and no Realm Supplies level can stop them, and they never
 * spend a single point of the realm's stockpile — so the war keeps moving on its own and the
 * early game never stalls waiting on the realm to be supplied. Realm Supplies belong to the
 * commanders' Campaigns and Conquests.
 *
 * Every [GRAND_EVERY]th march is a **GRAND MARCH**: upsized, on a grand-eligible target, led
 * against its **Warden** — a boss-tier defender whose fall pays the forge's ember components.
 *
 * `::march` rallies a player to the column (with a second-confirmation warning when the column
 * is fighting on wilderness ground — the Varrock outskirts). Marches CAN fail — 10 knights
 * alone will often be driven back; the realm learns to march with them.
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
    /** The target the mustering march will move on (picked at the muster call). */
    private var target: MarchTarget? = null
    /** The target the march in the field is fighting over, if any. */
    private var liveTarget: MarchTarget? = null
    /** True while the mustering/next march is a GRAND MARCH (every [GRAND_EVERY]th). */
    private var pendingGrand = false
    /** Admin pin (`::marchnow <key> [grand]`): the next muster uses this target instead of the pick. */
    private var pinned: MarchTarget? = null
    private var pinnedGrand: Boolean? = null
    /** The store patron funding the mustering march (name), if any. */
    private var patron: String? = null
    /** The live Warden of a running Grand March, if any. */
    private var warden: Npc? = null
    private var wardenTarget: MarchTarget? = null

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

        // ::march — rally to the column. Free, for everyone; the march is the public war. The
        // join itself (with its PvP double-confirm) lives in WarEvents so quests share it.
        onCommand("march", description = "Rally to the knights' march on the enemy") {
            when (val r = WarEvents.join(player, world)) {
                WarEvents.JoinResult.NoMarch -> {
                    val mins = etaMinutes(world)
                    val eta = if (state == State.MUSTERING) "it sets out in ~$mins minute(s)" else "the next musters in ~$mins minute(s)"
                    player.message("<col=801700>No march is in the field — $eta. Watch for the muster call.</col>")
                }
                is WarEvents.JoinResult.NeedsConfirm ->
                    player.message("<col=801700>The column is already fighting — ${r.reason}.</col> Type <col=ffae00>::march</col> again to rally to them anyway.")
                WarEvents.JoinResult.Joined -> {}
            }
        }

        // Warden kill: additive death hook, bails instantly unless a Grand March Warden is up.
        onAnyNpcDeath {
            val w = warden ?: return@onAnyNpcDeath
            if (npc === w) onWardenSlain(world, w)
        }

        // ::marches — the board: every target, what's live/mustering, and the next muster ETA.
        onCommand("marches", description = "Show the realm's march targets and the next muster") {
            MarchTargets.statusLines(etaMinutes(world), target, liveTarget, pendingGrand || nextIsGrand()).forEach { player.message(it) }
        }

        // ::marchnow [key] [grand] — admin: skip the wait and run the next cycle step immediately;
        // an optional target key (and "grand") pins the next muster for a per-target smoke test.
        onCommand("marchnow", Privilege.ADMIN_POWER, description = "Force the march cycle forward (test): ::marchnow [target] [grand]") {
            val args = player.getCommandArgs()
            args.getOrNull(0)?.let { key ->
                val t = MarchTargets.byKey(key)
                if (t == null) {
                    player.message("<col=801700>No such march target. Targets: ${MarchTargets.pool.joinToString { it.key }}.</col>")
                    return@onCommand
                }
                pinned = t
                pinnedGrand = args.getOrNull(1)?.equals("grand", ignoreCase = true)
            }
            world.timers[timer] = 1
            val what = if (state == State.IDLE) "muster call" else "launch"
            player.message("<col=4f9b4f>[test] March cycle advanced ($what next tick${pinned?.let { ", pinned to ${it.key}" } ?: ""}).</col>")
        }
    }

    private fun schedule(world: World, timer: TimerKey, ticks: Int) {
        world.timers[timer] = ticks
        nextFireCycle = world.currentCycle + ticks
    }

    private fun etaMinutes(world: World): Int =
        ((nextFireCycle - world.currentCycle).coerceAtLeast(0) * 6 / 10 + 59) / 60

    /** Whether the NEXT scheduled (unpinned, unfunded) march is the GRAND one. */
    private fun nextIsGrand(): Boolean = (WarState.getMarchCount() + 1) % GRAND_EVERY == 0

    /** The muster call, [WARN_TICKS] before launch — or the busy skip (never a supply skip). */
    private fun muster(world: World, timer: TimerKey) {
        val skip = when {
            CampaignRegistry.activeMarch() != null -> true // last march still in the field
            world.players.count() == 0 -> true // empty world — don't churn the garrison
            else -> false
        }
        if (skip) {
            schedule(world, timer, INTERVAL_TICKS - WARN_TICKS) // try again next cycle
            return
        }
        // A store patron's funded march jumps the queue; otherwise every GRAND_EVERYth launched
        // march is a GRAND MARCH. An admin pin overrides both for a smoke test.
        val funded = WarState.popPatronMarch()
        patron = funded?.first
        val grand = pinnedGrand ?: funded?.second ?: nextIsGrand()
        // The march is unconditional and supply-free — it musters regardless of the realm's
        // supply level and spends nothing, so the war keeps moving without waiting on players.
        val t = pinned?.takeIf { !grand || it.grandEligible } ?: MarchTargets.pick(world, grand)
        pinned = null; pinnedGrand = null
        if (t == null) {
            // Nothing can be fought right now (every target contested/misconfigured) — try next cycle.
            requeue(patron); patron = null
            schedule(world, timer, INTERVAL_TICKS - WARN_TICKS)
            return
        }
        val tier = if (grand) CampaignTier.GRAND_MARCH else CampaignTier.MARCH
        target = t
        pendingGrand = grand
        val mins = WARN_TICKS * 6 / 600
        val wild = if (t.kind == MarchTargetKind.VARROCK_OUTSKIRTS) " (wilderness ground — PvP)" else ""
        when {
            funded != null && grand ->
                Announce.broadcast(world, "<col=ffcc00>${funded.first}, Patron of the Realm, funds a GRAND MARCH against <col=ffae00>${t.warden?.title ?: t.display}</col><col=ffcc00> at ${t.display}$wild — ${tier.troops} knights set out in ~$mins minutes! <col=ffae00>::march</col><col=ffcc00> to fight!</col>")
            funded != null ->
                Announce.broadcast(world, "<col=4f9b4f>${funded.first}, Patron of the Realm, funds a march on <col=ffae00>${t.display}</col><col=4f9b4f>$wild — it sets out in ~$mins minutes! Fight beside their banner: <col=ffae00>::march</col><col=4f9b4f>.</col>")
            grand ->
                Announce.broadcast(world, "<col=ffcc00>A GRAND MARCH musters against <col=ffae00>${t.warden?.title ?: t.display}</col><col=ffcc00> at ${t.display}$wild — ${tier.troops} knights set out in ~$mins minutes! The Warden's embers feed the Royal Smith's forge. <col=ffae00>::march</col><col=ffcc00> to fight!</col>")
            else ->
                Announce.broadcast(world, "<col=4f9b4f>The Knight-Captain musters a march on <col=ffae00>${t.display}</col><col=4f9b4f>$wild — it sets out in ~$mins minutes! Any soldier may fight beside the column: answer with <col=ffae00>::march</col><col=4f9b4f>.</col>")
        }
        state = State.MUSTERING
        schedule(world, timer, WARN_TICKS)
    }

    /** Launch the column (re-checking the gates — the world may have changed since the call). */
    private fun launch(world: World) {
        val fundedBy = patron
        patron = null
        val t = target ?: return requeue(fundedBy)
        target = null
        if (CampaignRegistry.activeMarch() != null || CampaignRegistry.isAttacking(t.key) || CampaignRegistry.overlapsActive(t.op.battleArea)) {
            return requeue(fundedBy)
        }
        val grand = pendingGrand
        pendingGrand = false
        val tier = if (grand) CampaignTier.GRAND_MARCH else CampaignTier.MARCH
        // No supply gate here either — a march that mustered always launches.
        val started = CampaignRegistry.start(world, t.op, tier, sponsor = null) { won ->
            liveTarget = null
            despawnWarden(world, marchWon = won)
        }
        if (!started) {
            logger.warn { "[MARCH] scheduled ${tier.display} on ${t.key} failed to start" }
            return requeue(fundedBy)
        }
        liveTarget = t
        WarState.incMarchCount()
        if (grand) spawnWarden(world, t)
        // Marches never touch Realm Supplies — they are the free, always-on tier. The stockpile is
        // spent only by the commanders' Campaigns and Conquests.
        if (fundedBy != null) {
            Announce.broadcast(world, "<col=ffae00>$fundedBy's patronage rides with the ${tier.display} to ${t.display} — the column sets out under their banner.</col>")
        }
        logger.info { "[MARCH] scheduled ${tier.display} launched on ${t.key} patron=$fundedBy (supply-free)." }
    }

    /** A funded march that couldn't launch goes back on the queue — a purchase is never lost. */
    private fun requeue(fundedBy: String?) {
        if (fundedBy != null) WarState.queuePatronMarch(fundedBy, pendingGrand)
    }

    /** The Grand March's boss-tier defender: the target's Warden, waiting at the rally point. */
    private fun spawnWarden(world: World, t: MarchTarget) {
        val def = t.warden ?: return
        runCatching {
            val rally = t.op.objectiveTile
            val tile = world.findRandomTileAround(rally, radius = 2) ?: rally
            val npc = Npc(getRSCM(def.npc), tile, world)
            npc.walkRadius = 4
            world.spawn(npc)
            WarNpcNames.rename(npc, def.title)
            npc.combatDef = npc.combatDef.copy(
                attack = def.attack, strength = def.strength,
                defence = def.defence, hitpoints = def.hp,
            )
            npc.stats.setMaxLevel(NpcSkills.ATTACK, def.attack)
            npc.stats.setCurrentLevel(NpcSkills.ATTACK, def.attack)
            npc.stats.setMaxLevel(NpcSkills.STRENGTH, def.strength)
            npc.stats.setCurrentLevel(NpcSkills.STRENGTH, def.strength)
            npc.stats.setMaxLevel(NpcSkills.DEFENCE, def.defence)
            npc.stats.setCurrentLevel(NpcSkills.DEFENCE, def.defence)
            npc.setCurrentHp(def.hp)
            npc.respawns = false
            npc.setActive(true)
            warden = npc
            wardenTarget = t
        }.onFailure { logger.error(it) { "[MARCH] failed to spawn ${def.title} at ${t.key}" } }
    }

    /** Tear down a surviving Warden when its Grand March ends (with a taunt if the march lost). */
    private fun despawnWarden(world: World, marchWon: Boolean) {
        val w = warden ?: return
        val t = wardenTarget
        warden = null
        wardenTarget = null
        if (w.index >= 0 && world.npcs.contains(w) && !w.isDead()) {
            world.remove(w)
            if (!marchWon && t != null) {
                Announce.broadcast(world, "<col=801700>${t.warden?.title ?: "The Warden"} still stands at ${t.display} — his embers stay cold in the enemy's grip.</col>")
            }
        }
    }

    /** Warden slain: embers for the fighters — guaranteed for the MVP, rolled for the rest. */
    private fun onWardenSlain(world: World, w: Npc) {
        val t = wardenTarget
        warden = null
        wardenTarget = null
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
        val who = t?.warden?.title ?: "The Warden"
        Announce.broadcast(world, "<col=ffcc00>$who has FALLEN${mvp?.let { " — ${it.username} struck truest" } ?: ""}! His embers feed the Royal Smith's forge.</col>")
    }

    private companion object {
        /** Full march cycle, muster call to muster call (~30 min at 0.6s ticks). TUNE. */
        const val INTERVAL_TICKS = 3000
        /** Muster warning lead time (~5 min). TUNE. */
        const val WARN_TICKS = 500
        /** Every Nth launched march is a GRAND MARCH (persisted counter in [WarState]). TUNE. */
        const val GRAND_EVERY = 8
        /** 1-in-N Warden's-ember roll for non-MVP fighters. TUNE. */
        const val EMBER_ROLL = 3
    }
}

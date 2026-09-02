package org.alter.plugins.content.war.events

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.war.CampaignDirector
import org.alter.plugins.content.war.CampaignRegistry
import org.alter.plugins.content.war.CampaignTier
import org.alter.plugins.content.war.MarchTargets

private val logger = KotlinLogging.logger {}

/**
 * **The war's quest-facing surface** (design authority §10 — "start a Campaign, check meaningful
 * participation, advance the journal"). Quests and other content ask the war three things here
 * and never reach into `MarchPlugin`/`CampaignDirector`:
 *
 *  - [isRunning] / [current] — is the realm's public warband in the field right now?
 *  - [startPublicOperation] — launch a public march on a [MarchTargets] target (a quest beat
 *    that "sends the army", a story event). Free, open to everyone, same machinery as the
 *    scheduled march; does NOT bump the scheduled march counter or field a Warden.
 *  - [join] — rally a player to the live column (the `::march` logic, with its PvP
 *    double-confirm), and [didParticipate] — did they meaningfully fight in a given op
 *    (the [ServiceRecords] ledger, filled by `CampaignDirector.finish`).
 */
object WarEvents {

    /** How long a hot-zone rally confirmation stands (~30s). */
    private const val CONFIRM_WINDOW = 50

    /** username -> world cycle until which their rally confirmation stands. */
    private val hotConfirm = HashMap<String, Int>()

    sealed class StartResult {
        /** Launched; [opKey] is the ledger key [didParticipate] answers for once it ends. */
        data class Started(val opKey: String, val display: String) : StartResult()
        data class NoSuchTarget(val key: String) : StartResult()
        /** A march is already in the field, or the target/ground is contested by another op. */
        data class Busy(val reason: String) : StartResult()
        object Failed : StartResult()
    }

    sealed class JoinResult {
        object NoMarch : JoinResult()
        /** Rallying onto wilderness ground / the live line — call again with `confirmed = true`. */
        data class NeedsConfirm(val reason: String) : JoinResult()
        object Joined : JoinResult()
    }

    fun isRunning(): Boolean = CampaignRegistry.activeMarch() != null

    /** The public warband in the field (MARCH / GRAND_MARCH), if any. */
    fun current(): CampaignDirector? = CampaignRegistry.activeMarch()

    /** The ledger key a public march on [targetKey] files under (see [ServiceRecords.opKey]). */
    fun opKeyFor(targetKey: String, grand: Boolean = false): String? {
        val t = MarchTargets.byKey(targetKey) ?: return null
        return ServiceRecords.opKey(if (grand) CampaignTier.GRAND_MARCH else CampaignTier.MARCH, t.op, sponsored = false)
    }

    /**
     * Launch a public march on [targetKey] now. [onResult] fires once with the outcome
     * (true = victory). Refused while another march is up or the ground is contested.
     */
    fun startPublicOperation(world: World, targetKey: String, grand: Boolean = false, onResult: ((Boolean) -> Unit)? = null): StartResult {
        val t = MarchTargets.byKey(targetKey) ?: return StartResult.NoSuchTarget(targetKey)
        if (CampaignRegistry.activeMarch() != null) return StartResult.Busy("a march is already in the field")
        if (CampaignRegistry.isAttacking(t.key)) return StartResult.Busy("${t.display} is already under attack")
        if (CampaignRegistry.overlapsActive(t.op.battleArea)) return StartResult.Busy("another operation is fighting over that ground")
        val tier = if (grand) CampaignTier.GRAND_MARCH else CampaignTier.MARCH
        val started = CampaignRegistry.start(world, t.op, tier, sponsor = null, onResult = onResult)
        if (!started) {
            logger.warn { "[WAR EVENTS] public ${tier.display} on ${t.key} failed to start" }
            return StartResult.Failed
        }
        logger.info { "[WAR EVENTS] public ${tier.display} launched on ${t.key} (event-started, supply-free)." }
        return StartResult.Started(ServiceRecords.opKey(tier, t.op, sponsored = false), t.display)
    }

    /**
     * Rally [p] to the live column. Rallying into the battle line or onto wilderness ground asks
     * for a second call within [CONFIRM_WINDOW] ticks ([JoinResult.NeedsConfirm]) unless [confirmed].
     */
    fun join(p: Player, world: World, confirmed: Boolean = false): JoinResult {
        val march = CampaignRegistry.activeMarch() ?: return JoinResult.NoMarch
        val dest = march.rallyTile(world)
        val hot = march.coversBattle(dest) || PvpZones.isWilderness(dest)
        val standing = (hotConfirm[p.username] ?: 0) >= world.currentCycle
        if (hot && !confirmed && !standing) {
            hotConfirm[p.username] = world.currentCycle + CONFIRM_WINDOW
            val why = if (PvpZones.isWilderness(dest)) "that is wilderness ground and other players can attack you" else "that is the live battle line"
            return JoinResult.NeedsConfirm(why)
        }
        hotConfirm.remove(p.username)
        p.moveTo(dest)
        p.message("<col=4f9b4f>You rally to the knights' column. Fight beside them — the realm pays its soldiers from the spoils.</col>")
        return JoinResult.Joined
    }

    /** Did [p] meaningfully fight in the op filed under [opKey]? (≥ [minShare]% of the pool; won if [mustWin].) */
    fun didParticipate(p: Player, opKey: String, minShare: Int = 1, mustWin: Boolean = true): Boolean =
        ServiceRecords.didParticipate(p, opKey, minShare, mustWin)
}

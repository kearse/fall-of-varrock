package org.alter.plugins.content.war.events

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.war.CampaignDirector
import org.alter.plugins.content.war.CampaignOp
import org.alter.plugins.content.war.CampaignRegistry
import org.alter.plugins.content.war.Campaigns
import org.alter.plugins.content.war.MarchTargets
import org.alter.plugins.content.war.WarType

private val logger = KotlinLogging.logger {}

/**
 * **The war's quest-facing surface** (design authority §10 — "start a Campaign, check meaningful
 * participation, advance the journal"). Quests and other content ask the war three things here
 * and never reach into `MarchPlugin`/`CampaignDirector`:
 *
 *  - [isRunning] / [current] — is the realm's public warband in the field right now?
 *  - [startPublicOperation] — launch a **public, sponsor-less** op of any [WarType] except a Lord's
 *    operation: a march / Grand March on a [MarchTargets] target, or — the "first major assault as a
 *    generic high-tier public operation" — a campaign / conquest on a hostile city ([Campaigns.HOSTILE]).
 *    Free, open to everyone, same machinery as the scheduled march; spends no coins and no Realm
 *    Supplies (no commander paid), does NOT bump the scheduled march counter or field a Warden.
 *    Player-COMMANDED ops go through [org.alter.plugins.content.war.WarAuthority] instead.
 *  - [join] — rally a player to the live column (the `::march` logic, with its PvP
 *    double-confirm), and [didParticipate] — did they meaningfully fight in a given op
 *    (the [ServiceRecords] ledger, filled by `CampaignDirector.finish`). To REACT when an op ends
 *    without polling, subscribe to [WarHooks.onOperationEnded].
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
        /** [WarType.LORD_OPERATION] needs a sponsor — use `WarAuthority.launch`. */
        data class NotPublic(val type: WarType) : StartResult()
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
    fun opKeyFor(targetKey: String, grand: Boolean = false): String? =
        opKeyFor(if (grand) WarType.GRAND_MARCH else WarType.MARCH, targetKey)

    /** The ledger key a public [type] op on [objective] files under, or null if no such target. */
    fun opKeyFor(type: WarType, objective: String): String? {
        val op = resolve(type, objective) ?: return null
        return ServiceRecords.opKey(type.tier, op, sponsored = false)
    }

    /** The op a public [type] fights over [objective]: a march target for the march-scale types, a
     *  hostile city for a campaign / conquest. */
    private fun resolve(type: WarType, objective: String): CampaignOp? =
        if (type.strikesMarchTargets) MarchTargets.byKey(objective)?.op else Campaigns.hostileByKey(objective)

    /**
     * Launch a public march on [targetKey] now (the original shape; [grand] picks the Grand March
     * tier). [onResult] fires once with the outcome (true = victory).
     */
    fun startPublicOperation(world: World, targetKey: String, grand: Boolean = false, onResult: ((Boolean) -> Unit)? = null): StartResult =
        startPublicOperation(world, if (grand) WarType.GRAND_MARCH else WarType.MARCH, targetKey, onResult)

    /**
     * Launch a public, sponsor-less [type] op on [objective] now — a march-target key for MARCH /
     * GRAND_MARCH, a hostile city key ("varrock") for CAMPAIGN / CONQUEST. Refused while the ground
     * is contested (or, for a march, while the realm's column is already out). [onResult] fires once
     * with the outcome (true = victory); the [WarHooks] result follows with the shares.
     */
    fun startPublicOperation(world: World, type: WarType, objective: String, onResult: ((Boolean) -> Unit)? = null): StartResult {
        if (type == WarType.LORD_OPERATION) return StartResult.NotPublic(type)
        val op = resolve(type, objective) ?: return StartResult.NoSuchTarget(objective)
        if (type.strikesMarchTargets && CampaignRegistry.activeMarch() != null) return StartResult.Busy("a march is already in the field")
        if (CampaignRegistry.isAttacking(op.cityKey)) return StartResult.Busy("${op.displayName} is already under attack")
        if (CampaignRegistry.overlapsActive(op.battleArea)) return StartResult.Busy("another operation is fighting over that ground")
        val started = CampaignRegistry.start(world, op, type.tier, sponsor = null, onResult = onResult)
        if (!started) {
            logger.warn { "[WAR EVENTS] public ${type.display} on ${op.cityKey} failed to start" }
            return StartResult.Failed
        }
        logger.info { "[WAR EVENTS] public ${type.display} launched on ${op.cityKey} (event-started: free, supply-free, no sponsor)." }
        return StartResult.Started(ServiceRecords.opKey(type.tier, op, sponsored = false), op.displayName)
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

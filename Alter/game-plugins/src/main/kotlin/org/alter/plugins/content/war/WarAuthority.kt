package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.events.ServiceRecords
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Who may start a war, and the one path that starts it.** The answer to `canStartCampaign(p)` /
 * `canStartConquest(p)` for every other team, and the launch sequence `::operation`, `::campaign`
 * and `::conquest` all run through — so a quest, a council NPC or a window that lets a player
 * command a war behaves exactly like the command does.
 *
 * [check] lists everything still in the way (empty = go): rank ([WarType.command]), a target that
 * is open for war, no squad of theirs already out, no other op on that ground, the coins, the Realm
 * Supplies. [launch] re-runs the check, charges, starts the op through [CampaignRegistry], refunds
 * on a lost race, drains the stockpile and advances the King quest — the former body of
 * `CampaignCommandPlugin.launch`, now shared.
 *
 * Participation is never gated here — only starting. Public, sponsor-less ops (the scheduled
 * march, a story event) go through `WarEvents.startPublicOperation` instead.
 */
object WarAuthority {

    sealed class Denial {
        /** The player's rank is below [min]. */
        data class NotRank(val min: Title) : Denial()
        /** [WarType.MARCH] / [WarType.GRAND_MARCH]: the realm's own, nobody commands them. */
        object NotCommandable : Denial()
        /** No such target, or none configured; [available] are the keys that ARE open. */
        data class NoTarget(val key: String?, val available: List<String>) : Denial()
        /** The player already has a squad in the field (one op per commander). */
        object SquadOut : Denial()
        /** Another op is on that ground / a march is up. */
        data class Busy(val reason: String) : Denial()
        data class Coins(val need: Int, val have: Int) : Denial()
        data class Supplies(val need: Int, val have: Int) : Denial()
    }

    sealed class LaunchResult {
        /** Launched. [opKey] is the ledger key `WarEvents.didParticipate` answers for once it ends;
         *  [muster] is where the column forms up (null = the men muster at the front). */
        data class Started(val opKey: String, val op: CampaignOp, val muster: Tile?) : LaunchResult()
        data class Refused(val denials: List<Denial>) : LaunchResult()
        /** [check] passed but the registry refused (a race — the coins were refunded). */
        object Failed : LaunchResult()
    }

    /** The op [type] would fight over for [targetKey] (null = the default target), or null. */
    fun resolveTarget(type: WarType, targetKey: String?): CampaignOp? = when {
        type.strikesMarchTargets -> targetKey?.let { MarchTargets.byKey(it) }?.op
        targetKey != null -> Campaigns.hostileByKey(targetKey)
        else -> Campaigns.hostileTarget()
    }

    /** The target keys open to [type] right now (for usage lines). */
    fun availableTargets(type: WarType): List<String> =
        if (type.strikesMarchTargets) MarchTargets.pool.map { it.key } else Campaigns.HOSTILE.map { it.cityKey }

    /**
     * Everything between [p] and starting a [type] op on [targetKey]. Empty = they may launch it now.
     * Ordered as the commands have always reported it: rank, target, squad, ground, coins, supplies.
     */
    fun check(p: Player, type: WarType, targetKey: String? = null): List<Denial> {
        val out = ArrayList<Denial>()
        val gate = type.command ?: return listOf(Denial.NotCommandable)
        if (!p.canCommand(gate)) out += Denial.NotRank(gate.minTitle)
        val op = resolveTarget(type, targetKey)
        if (op == null) {
            out += Denial.NoTarget(targetKey, availableTargets(type))
            return out
        }
        if (CampaignRegistry.hasSquad(p)) out += Denial.SquadOut
        busyReason(type, op)?.let { out += Denial.Busy(it) }
        val have = p.inventory.getItemCount(coinId)
        if (have < type.coinCost) out += Denial.Coins(type.coinCost, have)
        if (type.consumesSupplies && !RealmSupply.canAfford(type.supplyCost)) out += Denial.Supplies(type.supplyCost, RealmSupply.meter())
        return out
    }

    fun canStart(p: Player, type: WarType, targetKey: String? = null): Boolean = check(p, type, targetKey).isEmpty()

    /**
     * Charge and launch. Only succeeds when [check] is empty; a registry race refunds and reports
     * [LaunchResult.Failed]. Prints nothing — the caller narrates.
     */
    fun launch(world: World, p: Player, type: WarType, targetKey: String? = null, onResult: ((Boolean) -> Unit)? = null): LaunchResult {
        val denials = check(p, type, targetKey)
        if (denials.isNotEmpty()) return LaunchResult.Refused(denials)
        val op = resolveTarget(type, targetKey) ?: return LaunchResult.Refused(listOf(Denial.NoTarget(targetKey, availableTargets(type))))
        if (type.coinCost > 0) p.inventory.remove(coinId, type.coinCost)
        if (!CampaignRegistry.start(world, op, type.tier, p, onResult)) {
            if (type.coinCost > 0) p.inventory.add(coinId, type.coinCost) // race lost — refund
            logger.warn { "[WAR] ${p.username}'s ${type.display} on ${op.cityKey} failed to start (registry refused)" }
            return LaunchResult.Failed
        }
        if (type.consumesSupplies) RealmSupply.consume(world, type.tier, p.username, op.displayName) // launching drains the realm stores
        Conquest.onLaunched(p, type.tier) // advances the "King of Lumbridge" quest on a conquest launch
        logger.info { "[WAR] ${p.username} launched a ${type.display} on ${op.cityKey} (coins ${type.coinCost}, supplies ${type.supplyCost})." }
        return LaunchResult.Started(ServiceRecords.opKey(type.tier, op, sponsored = true), op, op.route.firstOrNull())
    }

    /** Why [op] cannot be fought over right now, or null. A march-scale op also waits for the realm's
     *  own column to return (one warband at a time). */
    private fun busyReason(type: WarType, op: CampaignOp): String? = when {
        type.strikesMarchTargets && CampaignRegistry.activeMarch() != null -> "a march is already in the field"
        CampaignRegistry.isAttacking(op.cityKey) -> "${op.displayName} is already under attack"
        CampaignRegistry.overlapsActive(op.battleArea) -> "another operation is fighting over that ground"
        else -> null
    }

    /** The player-facing line for one [Denial] — the same copy the commands have always printed. */
    fun describe(type: WarType, d: Denial): String = when (d) {
        is Denial.NotRank -> when (type) {
            WarType.LORD_OPERATION -> "Only a ${d.min.display} or higher may sponsor an operation. Any soldier may still join one with ::march."
            else -> "Only a ${d.min.display} or higher may command a ${type.display}."
        }
        Denial.NotCommandable -> "A ${type.display} is the realm's own — nobody commands it. Watch for the muster call and ::march."
        is Denial.NoTarget -> {
            val what = if (type.strikesMarchTargets) "march target" else "war target"
            val list = d.available.joinToString(", ").ifEmpty { "none yet" }
            if (d.key == null) "Name a $what: ${list}." else "No such $what '${d.key}'. Open for war: $list."
        }
        Denial.SquadOut -> "You already have a squad in the field."
        is Denial.Busy -> "${d.reason.replaceFirstChar { it.uppercase() }} — wait for it to end."
        is Denial.Coins -> when (type) {
            WarType.LORD_OPERATION -> "Sponsoring an operation costs ${fmt(d.need)} coins; you carry only ${fmt(d.have)}."
            else -> "A ${type.display} costs ${fmt(d.need)} coins; you carry only ${fmt(d.have)}."
        }
        is Denial.Supplies -> "The ${RealmSupply.NAME} are too low to march a ${type.display} — the people must supply the war first (${d.have}/${d.need} needed). Skill the Mire and hand supplies to a Quartermaster."
    }

    fun describeAll(type: WarType, denials: List<Denial>): List<String> = denials.map { describe(type, it) }

    private val coinId: Int by lazy { getRSCM("item.coins_995") }
    private fun fmt(n: Int): String = "%,d".format(n)
}

package org.alter.plugins.content.core

import org.alter.game.model.Area
import org.alter.game.model.entity.Player
import org.alter.plugins.content.companion.Companion as CompanionPawn
import org.alter.plugins.content.companion.CompanionData
import org.alter.plugins.content.companion.CompanionPolicy
import org.alter.plugins.content.companion.CompanionRegistry
import org.alter.plugins.content.war.Title

/**
 * **Companions** (design authority 03 §5, as amended by the operator 2026-09-02/03): persistent
 * named soldiers a player recruits, trains and equips. **Own up to the roster, field them all** —
 * [ACTIVE_MAX] equals the hard roster ceiling (three) for every rank and donor tier; rank (and,
 * later, donor perks) only ever grow the ROSTER ([rosterCap]: Knight 1 / Lord 2 / Minister+ 3),
 * and the steep muster price ladder (10M / 100M / 500M) is what pays for the extra soldiers.
 * Knight unlocks the system. No permadeath.
 *
 * `canDeployCompanion(player)` → [canDeploy]. Content that must keep companions out (a solo boss,
 * an instance, a duel) registers a rule with [deny] / [denyArea] / [denyInstanceOf] — never
 * edits the companion code.
 */
object CompanionApi {

    /** How many may be in the world per owner — the whole roster. See `CompanionRegistry.ACTIVE_MAX`. */
    const val ACTIVE_MAX = CompanionRegistry.ACTIVE_MAX

    sealed class DeployCheck {
        /** A benched companion may be summoned here and now. */
        object Ok : DeployCheck()
        /** Below the first rank that keeps a roster ([min]). */
        data class BelowRank(val min: Title) : DeployCheck()
        /** Ranked, but owns no companion yet (General Zo musters them). */
        object NoRoster : DeployCheck()
        /** Every companion they own is already at their side — nothing left to summon. */
        data class AllFielded(val count: Int) : DeployCheck()
        /** The field is full ([active] of [max]) although someone is still benched. */
        data class FieldFull(val active: Int, val max: Int) : DeployCheck()
        /** Where they stand denies companions (a solo instance, the Fight Cave …). */
        data class Denied(val reason: String) : DeployCheck()
    }

    /** May [p] summon another companion to the field here and now? */
    fun canDeploy(p: Player): DeployCheck {
        if (rosterCap(p) <= 0) return DeployCheck.BelowRank(Title.values().first { it.roster > 0 })
        if (rosterSize(p) <= 0) return DeployCheck.NoRoster
        CompanionPolicy.verdict(p)?.let { return DeployCheck.Denied(it.reason) }
        val active = activeCount(p)
        if (benched(p).isEmpty()) return DeployCheck.AllFielded(active)
        if (active >= ACTIVE_MAX) return DeployCheck.FieldFull(active, ACTIVE_MAX)
        return DeployCheck.Ok
    }

    fun canDeployNow(p: Player): Boolean = canDeploy(p) is DeployCheck.Ok

    /** Companions [p] has in the world right now (0..[ACTIVE_MAX]). */
    fun activeCount(p: Player): Int = CompanionRegistry.count(p)

    /** The companions at [p]'s side, in formation order. */
    fun fielded(p: Player): List<CompanionPawn> = CompanionRegistry.ofOwner(p)

    /** Everyone [p] owns, fielded or benched. */
    fun rosterSize(p: Player): Int = CompanionRegistry.rosterSize(p)

    /** How many [p] may KEEP — their rank's roster (Knight 1 / Lord 2 / Minister+ 3). */
    fun rosterCap(p: Player): Int = CompanionRegistry.rosterCap(p)

    /** Benched roster entries (levels + gear kept). */
    fun benched(p: Player): List<CompanionData> = CompanionRegistry.benchedOf(p)

    /** True if [pawn] is one of [p]'s companions. */
    fun owns(p: Player, pawn: CompanionPawn): Boolean = CompanionRegistry.owns(p, pawn)

    /** The human owner of a companion pawn, if online. */
    fun ownerOf(pawn: CompanionPawn): Player? = CompanionRegistry.ownerOf(pawn.world, pawn)

    /** Stand every fielded companion down (skips one under player attack in the last 10 s). Returns how many. */
    fun dismissAll(p: Player): Int = CompanionRegistry.dismissAll(p)

    // ---- where companions may not stand (consulted on spawn / summon / every tick) ----------------

    /** Deny while the owner is inside [area]. */
    fun denyArea(area: Area, reason: String) = CompanionPolicy.denyArea(area, reason)

    /** Deny inside any instance copied from [source] (solo instanced bosses). */
    fun denyInstanceOf(source: Area, reason: String) = CompanionPolicy.denyInstanceOf(source, reason)

    /** Any rule: non-null verdict = denied. Must not throw. */
    fun deny(rule: CompanionPolicy.Rule) = CompanionPolicy.register(rule)

    /** Why companions must stand down where [p] is, or null if welcome. */
    fun denialReason(p: Player): String? = CompanionPolicy.verdict(p)?.reason

    /** The rank ladder's roster sizes, for UI: Title → roster. */
    fun rosterByRank(): Map<Title, Int> = Title.values().associateWith { it.roster }
}

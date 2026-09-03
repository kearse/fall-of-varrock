package org.alter.plugins.content.core

import org.alter.game.model.Area
import org.alter.game.model.entity.Player
import org.alter.plugins.content.companion.Companion as CompanionPawn
import org.alter.plugins.content.companion.CompanionData
import org.alter.plugins.content.companion.CompanionPolicy
import org.alter.plugins.content.companion.CompanionRegistry
import org.alter.plugins.content.war.Title

/**
 * **Companions** (design authority 03 §5): persistent named soldiers a player recruits, trains
 * and equips. **Own several, deploy one** — [ACTIVE_MAX] is one for every rank and donor tier,
 * locked in the engine; rank (and, later, donor perks) only ever grow the ROSTER ([rosterCap]).
 * Knight unlocks the system. No permadeath.
 *
 * `canDeployCompanion(player)` → [canDeploy]. Content that must keep companions out (a solo boss,
 * an instance, a duel) registers a rule with [deny] / [denyArea] / [denyInstanceOf] — never
 * edits the companion code.
 */
object CompanionApi {

    /** One in the world per owner. Locked — see `CompanionRegistry.ACTIVE_MAX`. */
    const val ACTIVE_MAX = CompanionRegistry.ACTIVE_MAX

    sealed class DeployCheck {
        object Ok : DeployCheck()
        /** Below the first rank that keeps a roster ([min]). */
        data class BelowRank(val min: Title) : DeployCheck()
        /** Ranked, but owns no companion yet (General Zo musters them). */
        object NoRoster : DeployCheck()
        /** Someone already stands with them — a summon would be a swap, not a second soldier. */
        data class AlreadyFielded(val name: String) : DeployCheck()
        /** Where they stand denies companions (a solo instance, the Fight Cave …). */
        data class Denied(val reason: String) : DeployCheck()
    }

    /** May [p] field a companion here and now? */
    fun canDeploy(p: Player): DeployCheck {
        if (rosterCap(p) <= 0) return DeployCheck.BelowRank(Title.values().first { it.roster > 0 })
        if (rosterSize(p) <= 0) return DeployCheck.NoRoster
        CompanionPolicy.verdict(p)?.let { return DeployCheck.Denied(it.reason) }
        fielded(p)?.let { return DeployCheck.AlreadyFielded(it.username) }
        return DeployCheck.Ok
    }

    fun canDeployNow(p: Player): Boolean = canDeploy(p) is DeployCheck.Ok

    /** Companions [p] has in the world right now (0 or 1). */
    fun activeCount(p: Player): Int = CompanionRegistry.count(p)

    /** The companion at [p]'s side, if any. */
    fun fielded(p: Player): CompanionPawn? = CompanionRegistry.ofOwner(p).firstOrNull()

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

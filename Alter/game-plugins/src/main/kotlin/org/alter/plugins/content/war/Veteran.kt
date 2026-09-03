package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.mechanics.Flags

private val logger = KotlinLogging.logger {}

/**
 * **Veteran of Varrock** — the milestone for meaningful participation in a major assault on the
 * fallen city (design authority 03 §1). One flag ([Flags.Known.VETERAN_OF_VARROCK]), one check,
 * one award path.
 *
 * Rules the docs lock: it helps progression (a milestone rank eligibility and quests may read via
 * [has] / `Prerequisite.FlagSet`) but it **never auto-grants Minister**, and exact thresholds are
 * OPEN — so `RankEligibility` does not enforce it yet. **Nothing in Block 1 awards it**: the first
 * major Varrock assault story event (Team story) calls [award] when its brief says so, typically
 * from a `WarHooks.onOperationEnded` listener checking `result.participated(name, minShare)`.
 */
object Veteran {

    const val FLAG = Flags.Known.VETERAN_OF_VARROCK

    fun has(p: Player): Boolean = Flags.has(p, FLAG)

    /**
     * Name [p] a Veteran of Varrock for [reason] (logged; shown to them). Idempotent — returns false
     * if they already held it. Announced realm-wide the first time.
     */
    fun award(p: Player, reason: String): Boolean {
        if (!Flags.set(p, FLAG)) return false
        logger.info { "[VETERAN] ${p.username} named a Veteran of Varrock: $reason" }
        p.message("<col=ffcc00>You are named a Veteran of Varrock — $reason.</col>")
        Announce.broadcast(p.world, "<col=ffcc00>${p.username} is named a Veteran of Varrock!</col>")
        return true
    }

    /** Admin/test: strip the mark. Returns false if they never held it. */
    fun revoke(p: Player): Boolean {
        val had = Flags.clear(p, FLAG)
        if (had) logger.info { "[VETERAN] ${p.username}'s Veteran of Varrock mark revoked" }
        return had
    }
}

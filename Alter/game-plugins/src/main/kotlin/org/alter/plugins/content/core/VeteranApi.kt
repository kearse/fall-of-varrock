package org.alter.plugins.content.core

import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.Veteran

/**
 * **Veteran of Varrock** (design authority 03 §1): meaningful participation in a major assault on
 * the fallen city. Helps progression; **never auto-grants Minister**. Awarded by the story — the
 * first major assault's brief calls [award] (typically from [WarApi.onEnded] with
 * `result.participated(name, minShare)`); nothing else does.
 *
 * `hasVeteranOfVarrock(player)` → [has].
 */
object VeteranApi {
    const val FLAG = Veteran.FLAG

    fun has(p: Player): Boolean = Veteran.has(p)

    /** Name [p] a Veteran for [reason]; announced. False if they already were. */
    fun award(p: Player, reason: String): Boolean = Veteran.award(p, reason)

    /** Admin/test only. */
    fun revoke(p: Player): Boolean = Veteran.revoke(p)
}

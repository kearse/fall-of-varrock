package org.alter.plugins.content.skills.slayer

import org.alter.game.model.attr.SLAYER_TASK_LEFT_ATTR
import org.alter.game.model.attr.SLAYER_TASK_NPC_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Combat-facing view of the player's Slayer assignment, used to gate on-task gear
 * (black mask / slayer helmet) in the combat formulas.
 *
 * Matching mirrors [SlayerPlugin]'s kill crediting: an npc counts as "on task" when its
 * cache display name equals the task monster's name, because many monsters spawn under
 * several npc ids that all share one name.
 */
object SlayerCombat {
    fun isOnTaskAgainst(
        player: Player,
        target: Pawn,
    ): Boolean {
        if (target !is Npc) {
            return false
        }
        val taskKey = player.attr[SLAYER_TASK_NPC_ATTR] ?: return false
        if ((player.attr[SLAYER_TASK_LEFT_ATTR] ?: 0) <= 0) {
            return false
        }
        val taskId = try {
            getRSCM(taskKey)
        } catch (e: Exception) {
            return false
        }
        val taskName = try {
            dev.openrune.cache.CacheManager.getNpc(taskId).name.lowercase().trim()
        } catch (e: Exception) {
            return false
        }
        val targetName = target.def.name.lowercase().trim()
        return taskName.isNotEmpty() && taskName == targetName
    }
}

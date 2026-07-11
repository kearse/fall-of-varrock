package org.alter.plugins.content.war.roguehunt

import dev.openrune.cache.CacheManager.getNpc
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Wiring for [RogueHunt] (story-and-grind-design §4): counts every rogue-family kill on the
 * additive death list (cheap — bails immediately for non-rogue kills, same pattern as the
 * Recruit Trials goblin hook), and serves `::rogues` for a progress check. Milestone payouts
 * live in the Recruiting Sergeant's dialogue ([RecruitTrialsPlugin]).
 */
class RogueHuntPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onAnyNpcDeath {
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onAnyNpcDeath
            if (RogueHunt.isRogue(npcName(npc.id))) RogueHunt.onKill(killer)
        }

        onCommand("rogues", description = "Show your rogue-hunting tally and next bounty") {
            player.message(RogueHunt.statusLine(player))
        }
    }

    private fun npcName(id: Int): String? =
        runCatching { getNpc(id).name }.getOrNull()
}

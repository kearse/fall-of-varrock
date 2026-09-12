package org.alter.plugins.content.quests.asgarnia

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.minigames.blastfurnace.BlastFurnace
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.quests.framework.bindTalk

private val logger = KotlinLogging.logger {}

/**
 * Wiring for [GunsOfAsgarnia] (Asgarnia campaign quest 3): registers the quest, routes the three
 * NPCs' Talk-to through [NpcTalk] (Sir Amik is shared with the rest of the Asgarnia campaign, so
 * only a bottom-priority placeholder line is added here — his everyday lines belong to *At the
 * White Wall*; Nulodion's everyday lines are this quest's; the Blast Furnace Foreman's are the
 * furnace plugin's), subscribes to the furnace's two seams, keeps the road cell alive across
 * logins, credits both fights' kills, and serves `::guns`.
 */
class GunsOfAsgarniaPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        QuestRegistry.register(GunsOfAsgarnia)

        if (bindTalk(GunsOfAsgarnia.AMIK)) {
            NpcTalk.placeholder(GunsOfAsgarnia.AMIK, GunsOfAsgarnia.AMIK_NAME, "Falador has problems enough. If you're here to help with one of them, you'll know which.")
        } else {
            logger.warn { "The Guns of Asgarnia: Sir Amik '${GunsOfAsgarnia.AMIK}' could not be bound; the quest cannot start or finish." }
        }
        if (bindTalk(GunsOfAsgarnia.NULODION)) {
            NpcTalk.register(GunsOfAsgarnia.NULODION, NpcTalk.PRIORITY_DEFAULT) { _ -> { p -> with(GunsOfAsgarnia) { nulodionIdle(p) } } }
        } else {
            logger.warn { "The Guns of Asgarnia: Nulodion '${GunsOfAsgarnia.NULODION}' could not be bound." }
        }
        bindTalk(GunsOfAsgarnia.FOREMAN) // idempotent — the furnace plugin binds him too, in either order

        // The Blast Furnace's seams: Keldagrim pays for the trial order; steel taken counts toward it.
        BlastFurnace.feeWaivers += { p -> QuestEngine.stepId(p, GunsOfAsgarnia) == GunsOfAsgarnia.FURNACE }
        BlastFurnace.barListeners += { p, bar, n -> GunsOfAsgarnia.onBarsTaken(p, bar, n) }

        onLogin {
            // The road cell is a personal spawn: rebuild what the step still owes (dead ones stay dead).
            if (QuestEngine.stepId(player, GunsOfAsgarnia) == GunsOfAsgarnia.ROUTE) KinshraRoute.ensure(player)
        }
        onLogout { KinshraRoute.despawn(player) }

        // Both fights count deaths, not killers — a friend's or a companion's kill clears the cell too.
        onAnyNpcDeath {
            runCatching {
                if (!KinshraRoute.onNpcDeath(npc)) WorkshopDefence.onNpcDeath(npc)
            }.onFailure { logger.error(it) { "The Guns of Asgarnia: kill hook failed" } }
        }

        onCommand("guns", description = "Show your objective in The Guns of Asgarnia and open it in the Quest Journal") {
            player.message(GunsOfAsgarnia.statusLine(player))
            QuestBook.open(player, QuestBook.GUNS_OF_ASGARNIA)
        }
    }
}

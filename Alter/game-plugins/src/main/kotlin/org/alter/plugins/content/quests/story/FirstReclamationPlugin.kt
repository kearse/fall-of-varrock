package org.alter.plugins.content.quests.story

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.war.events.WarHooks
import org.alter.plugins.content.war.outposts.SouthernWatch

private val logger = KotlinLogging.logger {}

/**
 * Wiring for [FirstReclamation]: registers the quest, subscribes the battle step to the war's result
 * hook, and installs the standard's Capture handler on the [SouthernWatch]. General Zo's Talk-to is
 * routed through `NpcTalk` by `GeneralZoPlugin` (bindTalk), so the quest's `talk(...)` branches need
 * no bind here.
 */
class FirstReclamationPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        QuestRegistry.register(FirstReclamation)

        // The battle resolves from the war's own result — after the ledger + payout — never by polling.
        WarHooks.onOperationEnded { result ->
            if (!result.targetKey.equals(SouthernWatch.TARGET_KEY, ignoreCase = true)) return@onOperationEnded
            FirstReclamation.onSouthernRoadResult(world, result)
        }

        // Raising the standard at the circle is the ESTABLISH step.
        SouthernWatch.onCapture = { p -> FirstReclamation.raiseStandard(p) }

        onWorldInit {
            if (QuestRegistry.byKey(FirstReclamation.PREREQUISITE) == null) {
                logger.warn {
                    "[quests] First Reclamation's prerequisite quest '${FirstReclamation.PREREQUISITE}' is not registered — " +
                        "the quest cannot begin until that quest lands (::questdebug begin ${FirstReclamation.key} forces it)."
                }
            }
        }
    }
}

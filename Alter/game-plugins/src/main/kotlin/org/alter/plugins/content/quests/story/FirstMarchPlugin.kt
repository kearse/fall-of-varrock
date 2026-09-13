package org.alter.plugins.content.quests.story

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.war.MarchTargets
import org.alter.plugins.content.war.events.WarHooks

private val logger = KotlinLogging.logger {}

/**
 * Wiring for [FirstMarch] (Main Story Quest 2): registers the quest, subscribes the march step to the
 * war's result hook, and serves `::firstmarch`. General Zo's Talk-to is routed through `NpcTalk` by
 * `GeneralZoPlugin` (bindTalk), so the quest's `talk(...)` branches need no bind here.
 */
class FirstMarchPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        QuestRegistry.register(FirstMarch)

        // The march resolves from the war's own result — after the ledger + payout — never by polling.
        // Any march-tier op counts (Zo's column, the scheduled march, a Lord's operation).
        WarHooks.onOperationEnded { result -> FirstMarch.onMarchResult(world, result) }

        onWorldInit {
            if (MarchTargets.byKey(FirstMarch.TARGET_KEY) == null) {
                logger.warn { "[quests] First March's march target '${FirstMarch.TARGET_KEY}' is not in the march pool — Zo cannot send the column." }
            }
        }

        onCommand("firstmarch", description = "Show your objective in First March and open it in the Quest Journal") {
            player.message(FirstMarch.statusLine(player))
            QuestBook.open(player, QuestBook.FIRST_MARCH)
        }
    }
}

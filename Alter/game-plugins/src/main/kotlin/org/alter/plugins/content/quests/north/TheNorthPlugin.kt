package org.alter.plugins.content.quests.north

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.quests.framework.bindTalk

private val logger = KotlinLogging.logger {}

/**
 * Wiring for [TheNorth] (Main Story Quest 3): registers the quest, routes Oziach's Talk-to through
 * [NpcTalk] (his everyday lines at the default priority — the quest's beats are quest-priority
 * branches registered by the definition itself), binds the Weathered Varrock Dispatch's Read
 * option, and serves `::north`.
 *
 * Oziach is the stock Edgeville resident (`npc.oziach`, 822), spawned where he always stands by
 * the presence-gated world spawns — nothing here spawns or moves him. General Zo's click is bound
 * by `GeneralZoPlugin`.
 */
class TheNorthPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        QuestRegistry.register(TheNorth)

        if (bindTalk(TheNorth.OZIACH)) {
            NpcTalk.register(TheNorth.OZIACH, NpcTalk.PRIORITY_DEFAULT) { _ -> { p -> with(TheNorth) { oziachIdle(p) } } }
        } else {
            logger.warn { "The North: Oziach '${TheNorth.OZIACH}' could not be bound; the quest's Edgeville beats are unreachable." }
        }

        // The dispatch can be read from the pack forever (the lore journal). `onItemOption` throws
        // at construction if the cache def lacks the verb — guarded so a def surprise can never
        // drop this plugin (reading with Oziach still clears the step either way).
        runCatching {
            onItemOption(TheNorth.DISPATCH, "read") {
                player.queue { with(TheNorth) { readFromPack(player) } }
            }
        }.onFailure { logger.warn(it) { "The North: '${TheNorth.DISPATCH}' has no Read option in the cache — the dispatch is read with Oziach only." } }

        onCommand("north", description = "Show your objective in The North and open it in the Quest Journal") {
            player.message(TheNorth.statusLine(player))
            QuestBook.open(player, QuestBook.THE_NORTH)
        }
    }
}

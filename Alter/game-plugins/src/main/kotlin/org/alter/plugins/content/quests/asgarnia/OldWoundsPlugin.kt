package org.alter.plugins.content.quests.asgarnia

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.objects.crates.CrateSearch
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.quests.framework.bindTalk

private val logger = KotlinLogging.logger {}

/**
 * Wiring for [OldWounds] (Asgarnia — BREACH, Quest 4): registers the quest, routes Sir Tiffy's
 * and Lord Daquarius's Talk-to through [NpcTalk] (Tiffy's everyday lines belong to At the White
 * Wall — `bindTalk` dedups, and the placeholder here only keeps him from going mute if this lands
 * first; the quest's beats are quest-priority branches registered by the definition itself),
 * hooks the fortress crate through [CrateSearch], binds the two documents' Read verbs, runs the
 * scene / cache sweep, and serves `::oldwounds`.
 *
 * Sir Tiffy is the presence-gated world spawn on his park bench; nothing here spawns or moves him.
 * Daquarius is spawned per scene, owner-bound, by [LordDaquarius].
 */
class OldWoundsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        QuestRegistry.register(OldWounds)

        if (bindTalk(OldWounds.TIFFY)) {
            NpcTalk.placeholder(
                OldWounds.TIFFY, OldWounds.TIFFY_NAME,
                "Lovely afternoon for the park, what? Do come back if the Kinshra do anything interesting.",
            )
        } else {
            logger.warn { "Old Wounds: Sir Tiffy '${OldWounds.TIFFY}' could not be bound; the quest is unreachable." }
        }

        if (bindTalk(LordDaquarius.NPC)) {
            NpcTalk.register(LordDaquarius.NPC, NpcTalk.PRIORITY_DEFAULT) { _ -> { p -> with(OldWounds) { daquariusIdle(p) } } }
        } else {
            logger.warn { "Old Wounds: '${LordDaquarius.NPC}' could not be bound; the Daquarius scenes run without a Talk-to." }
        }

        // The crate in the fortress hall: SearchCratesPlugin owns the bind, we claim one tile of it.
        CrateSearch.register { p, obj -> OldWounds.searchCrate(p, obj) }

        // Both documents can be read from the pack forever (the lore journal). `onItemOption` throws
        // at construction if the cache def lacks the verb — guarded so a def surprise can never drop
        // this plugin (the crate and the cache read them inline either way).
        runCatching {
            onItemOption(OldWounds.ORDERS, "read") {
                player.queue { with(OldWounds) { readOrdersFromPack(player) } }
            }
        }.onFailure { logger.warn(it) { "Old Wounds: '${OldWounds.ORDERS}' has no Read option in the cache." } }
        runCatching {
            onItemOption(OldWounds.REPORT, "read") {
                player.queue { with(OldWounds) { readReportFromPack(player) } }
            }
        }.onFailure { logger.warn(it) { "Old Wounds: '${OldWounds.REPORT}' has no Read option in the cache." } }

        val timer = TimerKey()
        onWorldInit {
            world.timers[timer] = SWEEP_TICKS
            listOf(OldWounds.TROLLS_KEY, OldWounds.GUNS_KEY).forEach { key ->
                if (QuestRegistry.byKey(key) == null) {
                    logger.warn {
                        "[quests] Old Wounds' prerequisite quest '$key' is not registered — the gate falls back down " +
                            "the chain until that quest lands (::questdebug begin ${OldWounds.key} forces it)."
                    }
                }
            }
        }
        onTimer(timer) {
            runCatching { LordDaquarius.sweep(world) }.onFailure { logger.error(it) { "Daquarius scene sweep failed (skipped)." } }
            runCatching { OldWounds.sweep(world) }.onFailure { logger.error(it) { "Old Wounds cache sweep failed (skipped)." } }
            world.timers[timer] = SWEEP_TICKS
        }

        onCommand("oldwounds", description = "Show your objective in Old Wounds, the Asgarnia campaign board, and open the Quest Journal") {
            player.message(OldWounds.statusLine(player))
            OldWounds.campaignBoard(player).forEach { player.message(it) }
            QuestBook.open(player, QuestBook.OLD_WOUNDS)
        }
    }

    private companion object {
        const val SWEEP_TICKS = 2
    }
}

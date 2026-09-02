package org.alter.plugins.content.quests.framework

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.quests.demo.DemoQuest
import org.alter.plugins.content.war.RankEvents

private val logger = KotlinLogging.logger {}

/**
 * Wiring for the quest framework: the single poll timer (areas/items/predicates + nudges), the
 * per-tick instance sweep, login resume, the additive npc-death hook (kills credit the resolved
 * killer — companions already credit their owner), death/logout instance teardown, the
 * rank-bought auto-begin, the [QuestArrows] provider, and the boot line.
 */
class QuestFrameworkPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        QuestArrows.install()
        QuestRegistry.register(DemoQuest) // the living regression test (admin-only, hidden)

        val poll = TimerKey()
        val sweep = TimerKey()
        onWorldInit {
            world.timers[poll] = POLL_TICKS
            world.timers[sweep] = 1
            logger.info { "[quests] registry: ${QuestRegistry.legacyCount} legacy chains, ${QuestRegistry.frameworkCount} framework quests" }
        }
        onTimer(poll) {
            world.players.forEach { p ->
                if (p.entityType.isHumanControlled) {
                    runCatching { QuestEngine.pollTick(p) }.onFailure { logger.error(it) { "Quest poll failed for ${p.username}" } }
                }
            }
            world.timers[poll] = POLL_TICKS
        }
        onTimer(sweep) {
            runCatching { QuestInstances.tick(world) }.onFailure { logger.error(it) { "Quest instance sweep failed" } }
            world.timers[sweep] = 1
        }

        onLogin { QuestEngine.resumeOnLogin(player) }
        onLogout { QuestInstances.onLogout(player) }
        onPlayerPreDeath { QuestInstances.onDeath(player) }

        onAnyNpcDeath {
            val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onAnyNpcDeath
            if (!killer.entityType.isHumanControlled) return@onAnyNpcDeath
            runCatching { QuestEngine.onNpcKilled(killer, npc) }.onFailure { logger.error(it) { "Quest kill hook failed" } }
        }

        // A rank-up can satisfy a RankAtLeast prerequisite — auto-begin what just became eligible.
        RankEvents.onRankBought(50) { p, _ -> QuestRegistry.frameworkQuests().forEach { QuestEngine.beginIfEligible(p, it) } }
    }

    private companion object {
        const val POLL_TICKS = 3
    }
}

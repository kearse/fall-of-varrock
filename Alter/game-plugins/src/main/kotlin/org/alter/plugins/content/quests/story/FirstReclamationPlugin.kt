package org.alter.plugins.content.quests.story

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.quests.framework.QuestEngine
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

        // ...and if the cache gave the post no clickable banner, walking to where it should stand
        // does it instead, so the quest can never dead-end on a loc we couldn't raise. Gated on the
        // object genuinely being absent: with a banner up there is nothing to poll for, and the
        // Capture click stays the only way to establish the Watch.
        val fallback = TimerKey()
        onWorldInit { world.timers[fallback] = FALLBACK_TICKS }
        onTimer(fallback) {
            runCatching { groundFallback(world) }
                .onFailure { logger.error(it) { "[quests] First Reclamation standard fallback failed (skipped)." } }
            world.timers[fallback] = FALLBACK_TICKS
        }

        onWorldInit {
            if (QuestRegistry.byKey(FirstReclamation.PREREQUISITE) == null) {
                logger.warn {
                    "[quests] First Reclamation's prerequisite quest '${FirstReclamation.PREREQUISITE}' is not registered — " +
                        "the quest cannot begin until that quest lands (::questdebug begin ${FirstReclamation.key} forces it)."
                }
            }
        }
    }

    /**
     * No banner at the circle? Then standing where it belongs raises it. Only runs while the tile is
     * genuinely empty — [SouthernWatch.standardClickable] false, or nothing spawned at the tile — so
     * a working standard is never bypassed.
     */
    private fun groundFallback(world: World) {
        if (SouthernWatch.standardClickable && world.getObject(SouthernWatch.STANDARD_TILE, STANDARD_TYPE) != null) return
        world.players.forEach { p ->
            if (p.index < 0 || !p.entityType.isHumanControlled) return@forEach
            if (QuestEngine.stepId(p, FirstReclamation) != FirstReclamation.ESTABLISH) return@forEach
            if (!p.tile.isWithinRadius(SouthernWatch.STANDARD_TILE, FALLBACK_RADIUS)) return@forEach
            p.message("<col=5d4037>There is no standard here to capture - so you plant one yourself.</col>")
            FirstReclamation.raiseStandard(p)
        }
    }

    private companion object {
        /** Matches `SouthernWatchPlugin`'s spawn type for the banner. */
        const val STANDARD_TYPE = 10
        const val FALLBACK_TICKS = 5 // ~3s, the post's own upkeep cadence
        const val FALLBACK_RADIUS = 1
    }
}

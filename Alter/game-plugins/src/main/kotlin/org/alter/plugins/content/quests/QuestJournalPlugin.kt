package org.alter.plugins.content.quests

import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * **Quest Journal** wiring — keeps the client-feed varps fresh and exposes the free-play toggle.
 *
 * A cheap world poll (same cadence as [org.alter.plugins.content.war.WarProgressPlugin]) re-derives
 * the packed quest varps for every online player; [QuestJournal.sync] only writes on change, so the
 * steady-state cost is a few attribute reads per player. `::questguide` flips the guidance mute —
 * typed by hand or sent by the custom client's Quest Journal panel toggle.
 */
class QuestJournalPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val timer = TimerKey()
        onWorldInit { world.timers[timer] = REFRESH_TICKS }
        onTimer(timer) {
            world.players.forEach { if (it.entityType.isHumanControlled) QuestJournal.sync(it) }
            world.timers[timer] = REFRESH_TICKS
        }
        onLogin { QuestJournal.sync(player) }

        onCommand("questguide", description = "Toggle quest guidance arrows on/off (free play)") {
            QuestJournal.toggleMute(player)
        }
    }

    private companion object {
        const val REFRESH_TICKS = 3 // ~1.8s — matches the war-progress HUD feed
    }
}

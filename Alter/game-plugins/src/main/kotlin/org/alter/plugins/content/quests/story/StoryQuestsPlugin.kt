package org.alter.plugins.content.quests.story

import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestRegistry

/**
 * Registers the main story's framework quests that live in `content/quests/story/` — A Kingdom
 * Alone and the regional phase's four strategic objectives — and owns the phase's two small
 * seams: the one-line login overview and `::strategy`.
 *
 * Talk-to on Duke Horacio and General Zo is routed through `NpcTalk` by their own plugins
 * (`bindTalk`); the quest's branches attach with `QuestDefinition.talk` inside [AKingdomAlone].
 */
class StoryQuestsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        QuestRegistry.register(AKingdomAlone)
        StrategicObjectives.ALL.forEach { QuestRegistry.register(it) }

        // The standing strategic overview — one line, instead of four objective reminders.
        onLogin {
            if (QuestEngine.isComplete(player, AKingdomAlone) && !StrategicObjectives.allSolved(player)) {
                player.message(StrategicObjectives.overviewLine(player))
            }
        }

        // NB: `::campaign` is the Minister's war command (CampaignCommandPlugin) — this is the story overview.
        onCommand("strategy", description = "The war for Varrock: the four strategic problems (after A Kingdom Alone)") {
            if (!QuestEngine.isComplete(player, AKingdomAlone)) {
                if (QuestEngine.started(player, AKingdomAlone)) {
                    player.message("<col=801700>${AKingdomAlone.displayName}:</col> ${QuestEngine.objectiveLine(player, AKingdomAlone)}")
                    QuestBook.open(player, QuestBook.A_KINGDOM_ALONE)
                } else {
                    player.message("The regional campaign phase opens after <col=801700>A Kingdom Alone</col> (Main Story Quest 5), once the Southern Watch stands.")
                }
                return@onCommand
            }
            StrategicObjectives.report(player)
        }
    }
}

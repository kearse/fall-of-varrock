package org.alter.plugins.content.quests.framework

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.getVarp
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.Flags
import org.alter.plugins.content.quests.QuestJournal
import org.alter.plugins.content.quests.demo.DemoQuest

/**
 * `::questdebug` — admin inspection and surgery on quest state (legacy chains read-only; framework
 * quests fully controllable), plus `::demoquest` to run the framework's living regression test.
 */
class QuestDebugPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // The demo quest talks to Father Aereck — routed through NpcTalk (nothing else binds him).
        bindTalk(DemoQuest.PRIEST)

        onCommand("demoquest", Privilege.ADMIN_POWER, description = "Run the quest-framework demo quest (admin)") {
            when (player.getCommandArgs().getOrNull(0)?.lowercase()) {
                "reset" -> { QuestEngine.reset(player, DemoQuest); player.message("Demo quest reset.") }
                else -> if (!QuestEngine.begin(player, DemoQuest, force = true)) {
                    player.message("Demo quest already ${if (QuestEngine.isComplete(player, DemoQuest)) "complete" else "running"} — ::demoquest reset to start over. ${QuestEngine.objectiveLine(player, DemoQuest)}")
                }
            }
        }

        onCommand("questdebug", Privilege.ADMIN_POWER, description = "Quest framework debug: ::questdebug <dump|varps|flags|begin|complete|reset|set|satisfy|instance>") {
            val a = player.getCommandArgs()
            when (a.getOrNull(0)?.lowercase()) {
                "dump" -> dump(player)
                "varps" -> varps(player)
                "flags" -> player.message("Flags: ${Flags.all(player).ifEmpty { setOf("(none)") }.joinToString(", ")}")
                "begin" -> def(player, a.getOrNull(1)) { q -> player.message(if (QuestEngine.begin(player, q, force = true)) "Begun ${q.key}." else "${q.key} already started.") }
                "complete" -> def(player, a.getOrNull(1)) { q -> QuestEngine.complete(player, q) }
                "reset" -> def(player, a.getOrNull(1)) { q -> QuestEngine.reset(player, q); player.message("Reset ${q.key}.") }
                "set" -> def(player, a.getOrNull(1)) { q ->
                    val step = a.getOrNull(2)
                    if (step == null || !QuestEngine.advanceTo(player, q, step)) player.message("Steps: ${q.steps.joinToString(", ") { it.id }}")
                }
                "satisfy" -> def(player, a.getOrNull(1)) { q -> player.message(if (QuestEngine.satisfy(player, q)) "Advanced ${q.key}." else "${q.key} has no live step.") }
                "instance" -> {
                    val qi = QuestInstances.of(player)
                    if (qi == null) player.message("No live quest instance. (${QuestInstances.liveCount()} live world-wide.)")
                    else if (a.getOrNull(1)?.lowercase() == "end") { qi.end(EndReason.ADMIN); player.message("Instance ended.") }
                    else player.message("Instance: ${qi.npcs.size} npc(s), ${qi.ticks} ticks, timeout ${qi.timeoutTicks}, area ${qi.instance.map.area}. ::questdebug instance end")
                }
                else -> player.message("Usage: ::questdebug <dump|varps|flags|begin <key>|complete <key>|reset <key>|set <key> <step>|satisfy <key>|instance [end]>")
            }
        }
    }

    private fun def(p: Player, key: String?, action: (QuestDefinition) -> Unit) {
        val q = key?.let { QuestRegistry.definition(it) }
        if (q == null) p.message("Framework quests: ${QuestRegistry.frameworkQuests().joinToString(", ") { it.key }.ifEmpty { "(none)" }}")
        else action(q)
    }

    private fun dump(p: Player) {
        p.message("<col=801700>Quests</col> (${QuestRegistry.legacyCount} legacy, ${QuestRegistry.frameworkCount} framework); focus slot ${QuestRegistry.activeChainIndex(p)}:")
        QuestRegistry.all().forEach { c ->
            val state = when { c.complete(p) -> "<col=4f9b4f>complete</col>"; c.started(p) -> "<col=ffae00>in progress</col>"; else -> "not started" }
            p.message(" - ${c.key}${c.chainIndex?.let { " [#$it]" } ?: ""}${if (c.optional) " (optional)" else ""}: $state — ${c.objectiveLine(p)}")
        }
        QuestRegistry.frameworkQuests().forEach { q ->
            QuestStates.of(p, q.key)?.let { s -> p.message("   ${q.key}: step='${s.step}' complete=${s.complete} counters=${s.counters}") }
        }
    }

    private fun varps(p: Player) {
        val custom = listOf(
            "recruit" to QuestJournal.RECRUIT_VARP, "warprep" to QuestJournal.WARPREP_VARP, "muted" to QuestJournal.GUIDE_MUTED_VARP,
            "rogue" to QuestJournal.ROGUE_PROBLEM_VARP, "ranged" to QuestJournal.WARPREP_RANGED_VARP,
            "survival" to QuestJournal.WARPREP_SURVIVAL_VARP, "conquest" to QuestJournal.CONQUEST_VARP, "knights" to QuestJournal.KNIGHTS_VARP,
        )
        val native = listOf(
            "recruitRow" to QuestJournal.RECRUIT_QUEST_VARP, "warprepRow" to QuestJournal.WARPREP_QUEST_VARP,
            "rogueRow" to QuestJournal.ROGUE_QUEST_VARP, "ladderRow" to QuestJournal.ROGUE_LADDER_QUEST_VARP,
            "rangedRow" to QuestJournal.WARPREP_RANGED_QUEST_VARP, "survivalRow" to QuestJournal.WARPREP_SURVIVAL_QUEST_VARP,
            "kingRow" to QuestJournal.KING_QUEST_VARP,
        )
        p.message("journal: " + custom.joinToString(" ") { (n, v) -> "$n[$v]=${p.getVarp(v)}" })
        p.message("native: " + native.joinToString(" ") { (n, v) -> "$n[$v]=${p.getVarp(v)}" })
        QuestRegistry.frameworkQuests().forEach { q -> q.journalVarp?.let { p.message("${q.key}[$it]=${p.getVarp(it)}") } }
    }
}

package org.alter.plugins.content.quests.demo

import org.alter.api.ext.chatNpc
import org.alter.api.ext.chatPlayer
import org.alter.api.ext.message
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.Player
import org.alter.plugins.content.quests.framework.EndReason
import org.alter.plugins.content.quests.framework.Objective
import org.alter.plugins.content.quests.framework.QuestDefinition
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestInstances
import org.alter.plugins.content.quests.framework.QuestStep
import org.alter.plugins.content.quests.framework.Reward
import org.alter.rscm.RSCM.getRSCM

/**
 * **Framework Demo** — the quest framework's living regression test (admin-only, hidden from the
 * journal; `::demoquest`). Exercises every objective kind and hook in order:
 * TALK (an [org.alter.plugins.content.quests.framework.NpcTalk] branch) → REACH (area poll) →
 * KILL (npc-death hook) → INSTANCE (a private arena via [QuestInstances] with tagged spawns,
 * benching the companion) → HAVE (consumed items) → step + completion rewards + a flag.
 */
object DemoQuest : QuestDefinition(key = "demo", displayName = "Framework Demo", adminOnly = true) {

    const val PRIEST = "npc.father_aereck"

    /** Tags the arena's spawns so only THEY count for the instance step. */
    val DEMO_NPC = AttributeKey<Boolean>()

    // A private copy of the TzHaar cave floor — a known-good instanced region (the Fight Cave uses it).
    private val ARENA = Area(2368, 5056, 2431, 5119)
    private val LANDING = Tile(2411, 5114, 0)
    private val ARENA_SPAWNS = listOf(Tile(2408, 5111, 0), Tile(2414, 5111, 0))
    private val ARENA_EXIT = Tile(3243, 3210, 0) // the church door

    override val steps: List<QuestStep> = listOf(
        QuestStep(
            "talk", Objective.TalkTo("Speak to Father Aereck in the Lumbridge church.", PRIEST),
            anchor = Tile(3243, 3206, 0), anchorNpc = PRIEST,
        ),
        QuestStep(
            "reach", Objective.ReachArea("Stand at the church altar.", Area(3240, 3205, 3245, 3210)),
            anchor = Tile(3242, 3207, 0),
        ),
        QuestStep(
            "kill", Objective.KillNpcs("Kill 2 goblins in the back woods.", count = 2, nameContains = "goblin"),
            anchor = Tile(3193, 3221, 0),
        ),
        QuestStep(
            "instance", Objective.KillNpcs("Clear the trial ground — slay both goblins in your private arena.", count = 2, filter = { _, npc -> npc.attr[DEMO_NPC] == true }),
            onEnter = { p -> openArena(p) },
            onLeave = { p -> QuestInstances.of(p)?.end(EndReason.COMPLETE) },
            nudge = "Left the arena? ::questdebug set demo instance re-opens it.",
        ),
        QuestStep(
            "have", Objective.HaveItems("Bring a bronze dagger (it is taken).", listOf("item.bronze_dagger" to 1), consume = true),
            anchor = Tile(3238, 3196, 0),
            rewards = listOf(Reward.Coins(1_000)),
        ),
    )

    override val completionRewards: List<Reward> = listOf(Reward.WarEffort(1), Reward.Flag("demo_done"))
    override val completionMessage = "The framework works end to end: talk, reach, kill, instance, items, rewards, flag."

    init {
        talk(PRIEST, "talk") { p ->
            val id = getRSCM(PRIEST)
            chatNpc(p, "Ah — a tester. The framework lives, then. Go and stand at my altar; the rest follows.", npc = id, title = "Father Aereck")
            chatPlayer(p, "Consider it tested.")
            QuestEngine.satisfy(p, DemoQuest, "talk")
        }
    }

    private fun openArena(p: Player) {
        val qi = QuestInstances.enter(
            p, sourceArea = ARENA, exit = ARENA_EXIT, landing = LANDING, timeoutTicks = 500,
            onEnd = { _, reason ->
                if (reason == EndReason.LEFT || reason == EndReason.DEATH || reason == EndReason.TIMEOUT) {
                    p.message("<col=801700>The trial ground closes ($reason).</col> ::questdebug set demo instance re-opens it.")
                }
            },
        ) ?: return
        ARENA_SPAWNS.forEach { src -> qi.spawnNpc("npc.goblin", src, name = "Trial goblin")?.let { it.attr[DEMO_NPC] = true } }
        p.message("<col=801700>A private trial ground opens around you</col> — two goblins, your companion stands down, ~5 minutes.")
    }
}

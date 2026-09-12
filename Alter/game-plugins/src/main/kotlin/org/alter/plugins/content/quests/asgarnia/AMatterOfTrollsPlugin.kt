package org.alter.plugins.content.quests.asgarnia

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.chatNpc
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.Objective
import org.alter.plugins.content.quests.framework.QuestEngine
import org.alter.plugins.content.quests.framework.QuestInstances
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.quests.framework.TalkScript
import org.alter.plugins.content.quests.framework.bindTalk
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Wiring for [AMatterOfTrolls] (Asgarnia — BREACH, quest 2):
 *  - registers the quest;
 *  - hand-places **My Arm** on the Trollheim summit and **Snowflake** in Weiss (both stock defs — the
 *    wiki spawn dump's ids for them are name-drift skips in this cache, so the world has neither), and
 *    two extra Soldiers at the Imperial Guard forward post at the foot of Death Plateau;
 *  - routes Denulth / My Arm / Snowflake / the scout troll / the soldiers through [NpcTalk] with
 *    everyday lines at the default priority (the quest's beats are quest-priority branches registered
 *    by the definition), and binds Sir Amik's click defensively (At the White Wall owns his idle lines);
 *  - **damage-share kill credit**: the framework credits the resolved killer (`KILLER_ATTR`); in a
 *    coalition battle an ally routinely lands the last hit, so any warband troll the player damaged
 *    counts for the patrol and the pass objectives (never double-counted with the framework hook);
 *  - sweeps the quest's temporary open-world spawns; puts a player who logged out mid-battle back on
 *    READY; serves `::trolls`.
 */
class AMatterOfTrollsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        QuestRegistry.register(AMatterOfTrolls)

        // Hand-placed stock npcs (design: "if My Arm is absent from the runtime spawn dump, hand-place
        // his normal existing model" — reuse, not a new character).
        spawnNpc(AMatterOfTrolls.MY_ARM, AMatterOfTrolls.MY_ARM_TILE, walkRadius = 0, direction = Direction.WEST)
        spawnNpc(AMatterOfTrolls.SNOWFLAKE, AMatterOfTrolls.SNOWFLAKE_TILE, walkRadius = 0, direction = Direction.WEST)
        AMatterOfTrolls.FORWARD_POST_GUARDS.forEach { spawnNpc(AMatterOfTrolls.SOLDIER, it, walkRadius = 1, direction = Direction.NORTH) }

        bind(AMatterOfTrolls.DENULTH, "Denulth") { p -> with(AMatterOfTrolls) { denulthIdle(p) } }
        bind(AMatterOfTrolls.MY_ARM, "My Arm") { p -> with(AMatterOfTrolls) { myArmIdle(p) } }
        bind(AMatterOfTrolls.SNOWFLAKE, "Snowflake") { p -> with(AMatterOfTrolls) { snowflakeIdle(p) } }
        bind(AMatterOfTrolls.SCOUT_TROLL, "the troll scout") { p -> with(AMatterOfTrolls) { scoutIdle(p) } }
        bind(AMatterOfTrolls.SOLDIER, "the Imperial Guard soldiers") { p -> soldierIdle(p) }

        // Sir Amik: the quest's branches are quest-priority on its own steps; a bottom-priority line
        // keeps him from going mute if this lands before At the White Wall (which owns his idle lines).
        if (bindTalk(AMatterOfTrolls.SIR_AMIK)) {
            NpcTalk.placeholder(AMatterOfTrolls.SIR_AMIK, "Sir Amik Varze", "The White Knights hold Falador. That is all you need to know today.")
        } else {
            logger.warn { "A Matter of Trolls: Sir Amik '${AMatterOfTrolls.SIR_AMIK}' could not be bound; the debrief is unreachable." }
        }

        // Damage-share kill credit for the patrol and the pass (see the class doc).
        onAnyNpcDeath {
            val n = npc
            val owner = (n.attr[BattleOfThePass.OWNER] ?: n.attr[TempSpawns.OWNER])?.get() ?: return@onAnyNpcDeath
            if (!owner.isOnline) return@onAnyNpcDeath
            if (n.attr[KILLER_ATTR]?.get() === owner) return@onAnyNpcDeath // the framework hook credits this one
            if (!n.damageMap.playerDamage().containsKey(owner)) return@onAnyNpcDeath
            val counts = when (QuestEngine.stepId(owner, AMatterOfTrolls)) {
                AMatterOfTrolls.PATROL -> AMatterOfTrolls.isPatrolOf(owner, n)
                AMatterOfTrolls.BATTLE -> BattleOfThePass.isHostileOf(owner, n)
                else -> false
            }
            if (counts) runCatching { credit(owner) }.onFailure { logger.error(it) { "A Matter of Trolls: kill credit failed for ${owner.username}" } }
        }

        // Temporary open-world spawns (patrol, scout): sweep every 10 ticks.
        val sweep = TimerKey()
        onWorldInit { world.timers[sweep] = SWEEP_TICKS }
        onTimer(sweep) {
            runCatching { TempSpawns.sweep(world) }.onFailure { logger.error(it) { "A Matter of Trolls: temp-spawn sweep failed" } }
            world.timers[sweep] = SWEEP_TICKS
        }

        // Logged out (or crashed) mid-battle: the instance is gone, so the pass must be re-opened by Denulth.
        onLogin {
            val step = QuestEngine.stepId(player, AMatterOfTrolls)
            if ((step == AMatterOfTrolls.BATTLE || step == AMatterOfTrolls.WAR_CHIEF) && QuestInstances.of(player) == null) {
                QuestEngine.advanceTo(player, AMatterOfTrolls, AMatterOfTrolls.READY)
                player.message("<col=801700>The pass was lost when you left it.</col> Denulth will send you back up when you're ready.")
            }
        }

        onCommand("trolls", description = "Show your objective in A Matter of Trolls and open it in the Quest Journal") {
            player.message(AMatterOfTrolls.statusLine(player))
            QuestBook.open(player, QuestBook.A_MATTER_OF_TROLLS)
        }
    }

    /** Bind [npcKey]'s Talk-to and give it everyday lines at the default priority. */
    private fun bind(npcKey: String, who: String, idle: TalkScript) {
        if (bindTalk(npcKey)) {
            NpcTalk.register(npcKey, NpcTalk.PRIORITY_DEFAULT) { _ -> idle }
        } else {
            logger.warn { "A Matter of Trolls: $who ('$npcKey') could not be bound; that part of the quest is unreachable by talking." }
        }
    }

    /** The soldiers' lines (the forward post AND Burthorpe's stock camp share the def). */
    private suspend fun QueueTask.soldierIdle(p: Player) {
        val id = runCatching { getRSCM(AMatterOfTrolls.SOLDIER) }.getOrDefault(-1)
        chatNpc(p, "Burthorpe holds. Commander Denulth's orders.", npc = id, title = "Imperial Guard")
        if (QuestEngine.stepId(p, AMatterOfTrolls) == AMatterOfTrolls.SCOUT) {
            chatNpc(p, "Trolls hit the forward post again last night. Two of ours won't be going home. The tracks lead up onto the plateau — the Commander says you're looking into it.", npc = id, title = "Imperial Guard")
        }
    }

    /** Mirror of `QuestEngine.onNpcKilled`'s counting for a kill the framework did not credit to the player. */
    private fun credit(p: Player) {
        val cur = QuestEngine.step(p, AMatterOfTrolls) ?: return
        val o = cur.objective as? Objective.KillNpcs ?: return
        val n = QuestEngine.addCounter(p, AMatterOfTrolls)
        if (n >= o.count) {
            QuestEngine.advance(p, AMatterOfTrolls)
        } else {
            p.message("<col=801700>${AMatterOfTrolls.displayName}:</col> $n/${o.count}.")
        }
    }

    private companion object {
        const val SWEEP_TICKS = 10
    }
}

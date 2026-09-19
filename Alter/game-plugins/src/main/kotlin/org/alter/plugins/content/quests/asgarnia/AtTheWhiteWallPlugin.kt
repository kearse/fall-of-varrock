package org.alter.plugins.content.quests.asgarnia

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.quests.QuestBook
import org.alter.plugins.content.quests.framework.NpcTalk
import org.alter.plugins.content.quests.framework.QuestRegistry
import org.alter.plugins.content.quests.framework.bindTalk

private val logger = KotlinLogging.logger {}

/**
 * Wiring for [AtTheWhiteWall] (Asgarnia — BREACH, Quest 1): registers the quest, routes the three
 * stock npcs' Talk-to through [NpcTalk] (everyday lines at the default priority — the quest's beats
 * are quest-priority branches registered by the definition itself), runs the [WhiteWallCheckpoint]
 * timer, credits the raid's kills by damage share, and serves `::whitewall`.
 *
 * Sir Amik Varze and Sir Tiffy Cashien are the presence-gated world spawns where OSRS puts them
 * (castle top floor, park bench) — nothing here spawns or moves them. The checkpoint's White
 * Knights are spawned by [WhiteWallCheckpoint] on the same stock id as Falador's castle knights;
 * that id has **Attack and no Talk-to**, so the checkpoint's voice is Sir Rebral, whom the
 * checkpoint posts at the gate (and whose old `npc_spawns.json` row is gone — he was moved).
 */
class AtTheWhiteWallPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        QuestRegistry.register(AtTheWhiteWall)

        bindIdle(AtTheWhiteWall.REBRAL, "Sir Rebral") { p -> with(AtTheWhiteWall) { rebralIdle(p) } }
        bindIdle(AtTheWhiteWall.AMIK, "Sir Amik Varze") { p -> with(AtTheWhiteWall) { amikIdle(p) } }
        bindIdle(AtTheWhiteWall.TIFFY, "Sir Tiffy Cashien") { p -> with(AtTheWhiteWall) { tiffyIdle(p) } }

        // The checkpoint's stretcher case and her nurse: dressing that still answers a click.
        if (bindTalk("npc.nurse_sarah")) {
            NpcTalk.placeholder("npc.nurse_sarah", "Nurse Sarah", "He'll live. That's more than I could say for the last three. Mind the road north — the Kinshra don't stop at the fence for long.")
        }
        if (bindTalk("npc.wounded_soldier")) {
            NpcTalk.placeholder("npc.wounded_soldier", "Wounded soldier", "They hit the gate at dawn. We held. We always hold. That's the problem, isn't it — we're always here, holding.")
        }

        val timer = TimerKey()
        onWorldInit { world.timers[timer] = WhiteWallCheckpoint.TICK }
        onTimer(timer) {
            runCatching { WhiteWallCheckpoint.tick(world) }
                .onFailure { logger.error(it) { "[WHITE WALL] checkpoint tick failed (skipped)." } }
            world.timers[timer] = WhiteWallCheckpoint.TICK
        }

        // DEFEND: damage-share credit. The framework's own hook counts the top-damage killer
        // (KILLER_ATTR); at the gate the White Knights routinely out-damage a player, so "help the
        // knights repel the attack" must count the help — every DEFEND-step player who drew blood
        // on the raider, except the one the engine already credited.
        onAnyNpcDeath {
            if (!WhiteWallCheckpoint.isRaider(npc)) return@onAnyNpcDeath
            val credited = npc.attr[KILLER_ATTR]?.get() as? Player
            npc.damageMap.playerDamage().keys.forEach { p ->
                if (p === credited || !p.isOnline || !p.entityType.isHumanControlled) return@forEach
                runCatching { AtTheWhiteWall.creditRaiderKill(p) }
                    .onFailure { logger.error(it) { "[WHITE WALL] raid kill credit failed for ${p.username}" } }
            }
        }

        onCommand("whitewall", description = "Show your objective in At the White Wall and open it in the Quest Journal") {
            player.message(AtTheWhiteWall.statusLine(player))
            QuestBook.open(player, QuestBook.AT_THE_WHITE_WALL)
        }
    }

    /** Route [npcKey]'s Talk-to through [NpcTalk] with [idle] as its everyday (default-priority) branch. */
    private fun bindIdle(npcKey: String, who: String, idle: suspend org.alter.game.model.queue.QueueTask.(Player) -> Unit) {
        if (bindTalk(npcKey)) {
            NpcTalk.register(npcKey, NpcTalk.PRIORITY_DEFAULT) { _ -> idle }
        } else {
            logger.warn { "At the White Wall: '$npcKey' ($who) could not be bound; that part of the quest is unreachable." }
        }
    }
}

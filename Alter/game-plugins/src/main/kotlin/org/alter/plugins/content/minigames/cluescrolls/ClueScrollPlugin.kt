package org.alter.plugins.content.minigames.cluescrolls

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.api.ext.messageBox
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Binds the Treasure Trails loop described on [ClueScrolls]: reading a scroll, and opening a
 * reward casket. The DIG half is not bound here — the spade's single `dig` handler
 * (`items/spade/SpadePlugin`) calls [ClueScrolls.tryDig] the same way it already calls
 * `Barrows.tryDig`, because only one plugin may own that option.
 */
class ClueScrollPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        ClueScrolls.Tier.values().forEach { tier ->
            bind(tier.scroll, READ_VERBS) { p -> p.queue { readScroll(this, p, tier) } }
            bind(tier.casket, OPEN_VERBS) { p -> openCasket(p, tier) }
        }
    }

    // ───────────────────────────── reading ─────────────────────────────

    private suspend fun readScroll(
        task: org.alter.game.model.queue.QueueTask,
        p: Player,
        tier: ClueScrolls.Tier,
    ) {
        val active = ClueScrolls.activeTier(p)

        // Already on a trail of THIS tier: re-state the step instead of eating another scroll.
        // A player who forgot where they were digging must never have to burn a clue to find out.
        if (active == tier) {
            showStep(task, p, tier)
            return
        }
        if (active != null) {
            p.message(
                "You are already following a <col=801700>${active.display}</col> clue. " +
                    "Finish it before starting another.",
            )
            return
        }

        val scroll = resolveOrNull(tier.scroll) ?: return
        if (tier.pool.isEmpty()) {
            logger.warn { "clues: ${tier.name} has no dig sites; scroll left alone." }
            return
        }
        if (p.inventory.remove(item = scroll, amount = 1).completed == 0) return

        // Distinct sites where the pool allows it, so a trail never sends you to the same square
        // twice running; a short pool just repeats rather than shortening the trail.
        val indices = ArrayList<Int>()
        var guard = 0
        while (indices.size < tier.steps && guard++ < MAX_ROLLS) {
            val pick = world.random(tier.pool.size - 1)
            if (pick !in indices || indices.size >= tier.pool.size) indices += pick
        }
        while (indices.size < tier.steps) indices += world.random(tier.pool.size - 1)

        ClueScrolls.startTrail(p, tier, indices)
        logger.info { "clues: ${p.username} started a ${tier.name} trail (${indices.size} steps)." }
        showStep(task, p, tier)
    }

    private suspend fun showStep(
        task: org.alter.game.model.queue.QueueTask,
        p: Player,
        tier: ClueScrolls.Tier,
    ) {
        val site = ClueScrolls.currentSite(p) ?: run {
            // A trail whose stored indices no longer resolve (a pool edited under a live save) is
            // cleared rather than left jammed — the player keeps the casket-less loss of one scroll.
            p.message("Your clue has crumbled to nothing.")
            ClueScrolls.clearTrail(p)
            return
        }
        val step = ClueScrolls.stepIndex(p) + 1
        task.messageBox(
            p,
            "<col=8f00ff>${tier.display.replaceFirstChar { it.uppercase() }} clue — step $step of ${tier.steps}</col>" +
                "<br><br>${site.hint}<br><br>Bring a spade.",
        )
    }

    // ───────────────────────────── caskets ─────────────────────────────

    private fun openCasket(p: Player, tier: ClueScrolls.Tier) {
        val casket = resolveOrNull(tier.casket) ?: return
        // One free slot is needed beyond the casket's own, so a full pack can't swallow the loot
        // onto the floor of a wilderness dig site.
        if (p.inventory.freeSlotCount < 1) {
            p.message("You need more inventory space to open that.")
            return
        }
        if (p.inventory.remove(item = casket, amount = 1).completed == 0) return

        tier.loot.roll(world).forEach { drop ->
            val id = resolveOrNull(drop.item) ?: return@forEach
            give(p, drop.item, drop.amount)
            if (drop.log && CollectionLog.record(p, id)) {
                p.message("<col=ffae00>New Collection Log slot: ${pretty(drop.item)}!</col>")
            }
            if (drop.announce) {
                Announce.broadcast(
                    world,
                    "<col=ff0000>News: ${p.username} just opened a <col=ffae00>${pretty(drop.item)}</col> " +
                        "from a ${tier.display} reward casket!</col>",
                )
            }
        }
        p.message("<col=8f00ff>You open the ${tier.display} reward casket.</col>")
    }

    // ───────────────────────────── plumbing ─────────────────────────────

    /** Bind the first verb in [verbs] the cache item actually carries; log if it carries none. */
    private fun bind(itemKey: String, verbs: List<String>, logic: (Player) -> Unit) {
        if (runCatching { getRSCM(itemKey) }.isFailure) {
            logger.warn { "clues: item '$itemKey' not in cache; not bound." }
            return
        }
        val bound = verbs.any { verb ->
            runCatching { onItemOption(item = itemKey, option = verb) { logic(player) } }.isSuccess
        }
        if (!bound) logger.warn { "clues: '$itemKey' has none of $verbs in the cache; it stays inert." }
    }

    private fun give(p: Player, key: String, amount: Int) {
        if (amount <= 0) return
        val id = resolveOrNull(key) ?: run { logger.warn { "clues: unknown item key $key" }; return }
        val added = p.inventory.add(item = id, amount = amount, assureFullInsertion = false)
        val leftover = amount - added.completed
        if (leftover > 0) world.spawn(GroundItem(id, leftover, p.tile, p))
    }

    private fun resolveOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    /** "item.ranger_boots" → "Ranger boots" (display only). */
    private fun pretty(key: String): String =
        key.removePrefix("item.").replace('_', ' ').replaceFirstChar { it.uppercase() }

    private companion object {
        const val DIG_ANIM = 830
        /** Bounded so a pool smaller than the step count can't spin the roll loop. */
        const val MAX_ROLLS = 64
        val READ_VERBS = listOf("Read", "Study", "Open")
        val OPEN_VERBS = listOf("Open", "Search", "Loot")
    }
}

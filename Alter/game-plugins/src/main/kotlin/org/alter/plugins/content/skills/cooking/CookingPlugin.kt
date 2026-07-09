package org.alter.plugins.content.skills.cooking

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Cooking** (consumption-loop, Phase 2). Use raw fish on a cooking range to cook it
 * (the Lumbridge kitchen range works, and one is placed by the river fishing spots for
 * convenience). Cooks the whole stack of that fish, burning some until your level beats
 * the fish's no-burn level. Cooked fish is eaten in combat (see EatingPlugin) — the
 * payoff of the fishing → cooking → eat loop.
 */
class CookingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Cookable(
        val raw: String, val cooked: String, val name: String,
        val level: Int, val xp: Double, val noBurn: Int,
    )

    private val range = "object.cooking_range"
    private val rangeTile = Tile(3239, 3246, 0) // by the river fishing spots

    private val cookables: List<Cookable> = listOf(
        Cookable("item.raw_shrimps", "item.shrimps", "shrimps", 1, 30.0, 34),
        Cookable("item.raw_trout", "item.trout", "trout", 15, 70.0, 50),
        Cookable("item.raw_salmon", "item.salmon", "salmon", 25, 90.0, 58),
        Cookable("item.raw_tuna", "item.tuna", "tuna", 30, 100.0, 64),
        Cookable("item.raw_lobster", "item.lobster", "lobster", 40, 120.0, 74),
        Cookable("item.raw_swordfish", "item.swordfish", "swordfish", 45, 140.0, 86),
        Cookable("item.raw_shark", "item.shark", "shark", 80, 210.0, 99),
    ).filter { resolves(it.raw) && resolves(it.cooked) }

    // Range objects that should cook: the river/convenience range (spawned below) + the static Range
    // inside the Mire's house (id 26181, aliased object.kitchen_range) so cooking works there.
    private val ranges = listOf("object.cooking_range", "object.kitchen_range")

    init {
        // Spawn the river/convenience range; the house range is a static map object already in the world.
        if (resolves(range)) {
            onWorldInit { world.spawn(DynamicObject(id = getRSCM(range), type = OBJ_TYPE, rot = 0, tile = rangeTile)) }
        }
        // Bind cooking on every range object that resolves.
        ranges.filter { resolves(it) }.forEach { rangeKey ->
            cookables.forEach { c ->
                onItemOnObj(obj = rangeKey, item = c.raw) { player.queue { cook(this, player, c) } }
            }
        }
    }

    private suspend fun cook(task: QueueTask, player: Player, c: Cookable) {
        if (player.getSkills().getCurrentLevel(Skills.COOKING) < c.level) {
            player.message("You need a Cooking level of ${c.level} to cook ${c.name}.")
            return
        }
        while (player.inventory.contains(getRSCM(c.raw))) {
            player.animate(COOK_ANIM)
            task.wait(COOK_TICKS)
            if (player.inventory.remove(item = getRSCM(c.raw), amount = 1).completed == 0) break
            if (burns(player, c)) {
                player.message("You accidentally burn the ${c.name}.")
            } else {
                player.inventory.add(item = getRSCM(c.cooked), amount = 1)
                player.addXp(Skills.COOKING, c.xp)
                player.message("You cook the ${c.name}.")
            }
        }
    }

    /** Burn chance falls from ~50% at the required level to 0 at the no-burn level. */
    private fun burns(player: Player, c: Cookable): Boolean {
        val lvl = player.getSkills().getCurrentLevel(Skills.COOKING)
        if (lvl >= c.noBurn) return false
        val span = (c.noBurn - c.level).coerceAtLeast(1)
        val chance = ((c.noBurn - lvl).toDouble() / span) * 0.5
        return world.randomDouble() < chance
    }

    private fun resolves(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        const val OBJ_TYPE = 10
        const val COOK_ANIM = 896
        const val COOK_TICKS = 4
    }
}

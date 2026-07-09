package org.alter.plugins.content.bosses

import org.alter.api.Skills
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

/**
 * The **spirit shield crafting chain** — the payoff for the Corporeal Beast's drops
 * ([org.alter.plugins.content.war.boss.BossRegistry]). It mirrors OSRS:
 *
 *  1. `spirit_shield` + `holy_elixir` → `blessed_spirit_shield`  (needs [BLESS_PRAYER] Prayer)
 *  2. `blessed_spirit_shield` + a sigil → the matching sigil shield (needs [ATTACH_PRAYER] Prayer)
 *
 * The base shield + holy elixir drop from the boss's common table, the sigils from its rare
 * unique table — so a player either crafts a sigil shield from parts or gets the blessed shield
 * whole as a rarer direct drop. Built on `onItemOnItem`, which is order-independent (the engine
 * hashes the pair), so either item may be "used on" the other.
 *
 * Recipes whose item ids aren't in the cache are filtered out at load, so a missing id never
 * crashes plugin loading.
 */
class SpiritShieldPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** a + b → result, requiring [level] Prayer. [verb] feeds the success message. */
    private data class Recipe(val a: String, val b: String, val result: String, val level: Int, val verb: String)

    private val recipes = listOf(
        Recipe("item.spirit_shield", "item.holy_elixir", "item.blessed_spirit_shield", BLESS_PRAYER, "bless the spirit shield"),
        Recipe("item.blessed_spirit_shield", "item.arcane_sigil", "item.arcane_spirit_shield", ATTACH_PRAYER, "attach the arcane sigil"),
        Recipe("item.blessed_spirit_shield", "item.spectral_sigil", "item.spectral_spirit_shield", ATTACH_PRAYER, "attach the spectral sigil"),
        Recipe("item.blessed_spirit_shield", "item.elysian_sigil", "item.elysian_spirit_shield", ATTACH_PRAYER, "attach the elysian sigil"),
    ).filter { resolves(it.a) && resolves(it.b) && resolves(it.result) }

    init {
        recipes.forEach { recipe ->
            onItemOnItem(recipe.a, recipe.b) { make(player, recipe) }
        }
    }

    private fun make(player: Player, recipe: Recipe) {
        if (player.getSkills().getCurrentLevel(Skills.PRAYER) < recipe.level) {
            player.message("You need a Prayer level of ${recipe.level} to ${recipe.verb}.")
            return
        }
        val a = getRSCM(recipe.a)
        val b = getRSCM(recipe.b)
        if (!player.inventory.contains(a) || !player.inventory.contains(b)) return
        if (player.inventory.remove(item = a, amount = 1).completed == 0) return
        if (player.inventory.remove(item = b, amount = 1).completed == 0) {
            player.inventory.add(item = a, amount = 1) // refund the first if the second couldn't be taken
            return
        }
        player.inventory.add(item = getRSCM(recipe.result), amount = 1)
        player.message("<col=ffae00>You ${recipe.verb}.</col>")
    }

    private fun resolves(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        private const val BLESS_PRAYER = 85   // OSRS: blessing a spirit shield needs 85 Prayer
        private const val ATTACH_PRAYER = 90  // OSRS: attaching a sigil needs 90 Prayer
    }
}

package org.alter.plugins.content.skills.herblore

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Herblore** (consumption-loop supply, Phase 2). Two-step potion brewing, consumed by
 * [org.alter.plugins.content.items.consumables.potions.PotionsPlugin]:
 *  1. clean herb + vial of water → unfinished potion,
 *  2. unfinished potion + secondary → finished combat/prayer potion.
 * Ingredients come from drops/shops/Slayer; potions feed PvM/PvP demand. Recipes whose
 * items aren't in the cache are skipped at load.
 */
class HerblorePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Recipe(
        val a: String, val b: String, val result: String,
        val level: Int, val xp: Double, val name: String,
    )

    private val recipes: List<Recipe> = listOf(
        // Step 1 — herb + vial of water → unfinished potion (no xp, minimal gate).
        Recipe("item.guam_leaf", "item.vial_of_water", "item.guam_potion_unf", 1, 0.0, "unfinished potion"),
        Recipe("item.tarromin", "item.vial_of_water", "item.tarromin_potion_unf", 1, 0.0, "unfinished potion"),
        Recipe("item.ranarr_weed", "item.vial_of_water", "item.ranarr_potion_unf", 1, 0.0, "unfinished potion"),
        Recipe("item.irit_leaf", "item.vial_of_water", "item.irit_potion_unf", 1, 0.0, "unfinished potion"),
        Recipe("item.kwuarm", "item.vial_of_water", "item.kwuarm_potion_unf", 1, 0.0, "unfinished potion"),
        Recipe("item.cadantine", "item.vial_of_water", "item.cadantine_potion_unf", 1, 0.0, "unfinished potion"),
        // Step 2 — unfinished potion + secondary → finished potion.
        Recipe("item.guam_potion_unf", "item.eye_of_newt", "item.attack_potion3", 3, 25.0, "Attack potion"),
        Recipe("item.tarromin_potion_unf", "item.limpwurt_root", "item.strength_potion3", 12, 50.0, "Strength potion"),
        Recipe("item.ranarr_potion_unf", "item.snape_grass", "item.prayer_potion3", 38, 87.5, "Prayer potion"),
        Recipe("item.irit_potion_unf", "item.eye_of_newt", "item.super_attack3", 45, 100.0, "Super attack"),
        Recipe("item.kwuarm_potion_unf", "item.limpwurt_root", "item.super_strength3", 55, 125.0, "Super strength"),
        Recipe("item.cadantine_potion_unf", "item.white_berries", "item.super_defence3", 66, 150.0, "Super defence"),
    ).filter { resolves(it.a) && resolves(it.b) && resolves(it.result) }

    init {
        recipes.forEach { recipe ->
            onItemOnItem(recipe.a, recipe.b) { make(player, recipe) }
        }
    }

    private fun make(player: Player, recipe: Recipe) {
        if (player.getSkills().getCurrentLevel(Skills.HERBLORE) < recipe.level) {
            player.message("You need a Herblore level of ${recipe.level} to make that.")
            return
        }
        val a = getRSCM(recipe.a)
        val b = getRSCM(recipe.b)
        if (!player.inventory.contains(a) || !player.inventory.contains(b)) return
        if (player.inventory.remove(item = a, amount = 1).completed == 0) return
        if (player.inventory.remove(item = b, amount = 1).completed == 0) {
            player.inventory.add(item = a, amount = 1) // refund the first
            return
        }
        player.inventory.add(item = getRSCM(recipe.result), amount = 1)
        if (recipe.xp > 0) player.addXp(Skills.HERBLORE, recipe.xp)
        player.message("You make a ${recipe.name}.")
    }

    private fun resolves(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }
}

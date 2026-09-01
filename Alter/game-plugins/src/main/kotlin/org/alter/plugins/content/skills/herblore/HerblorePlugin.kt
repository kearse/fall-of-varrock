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
        // Unfinished-potion levels per OSRS (were all level 1).
        Recipe("item.guam_leaf", "item.vial_of_water", "item.guam_potion_unf", 3, 0.0, "unfinished potion"),
        Recipe("item.tarromin", "item.vial_of_water", "item.tarromin_potion_unf", 12, 0.0, "unfinished potion"),
        Recipe("item.ranarr_weed", "item.vial_of_water", "item.ranarr_potion_unf", 30, 0.0, "unfinished potion"),
        Recipe("item.irit_leaf", "item.vial_of_water", "item.irit_potion_unf", 45, 0.0, "unfinished potion"),
        Recipe("item.kwuarm", "item.vial_of_water", "item.kwuarm_potion_unf", 55, 0.0, "unfinished potion"),
        Recipe("item.cadantine", "item.vial_of_water", "item.cadantine_potion_unf", 66, 0.0, "unfinished potion"),
        // Step 2 — unfinished potion + secondary → finished potion.
        Recipe("item.guam_potion_unf", "item.eye_of_newt", "item.attack_potion3", 3, 25.0, "Attack potion"),
        Recipe("item.tarromin_potion_unf", "item.limpwurt_root", "item.strength_potion3", 12, 50.0, "Strength potion"),
        Recipe("item.ranarr_potion_unf", "item.snape_grass", "item.prayer_potion3", 38, 87.5, "Prayer potion"),
        Recipe("item.irit_potion_unf", "item.eye_of_newt", "item.super_attack3", 45, 100.0, "Super attack"),
        Recipe("item.kwuarm_potion_unf", "item.limpwurt_root", "item.super_strength3", 55, 125.0, "Super strength"),
        Recipe("item.cadantine_potion_unf", "item.white_berries", "item.super_defence3", 66, 150.0, "Super defence"),
    ).filter { resolves(it.a) && resolves(it.b) && resolves(it.result) }

    /** grimy herb -> (clean herb, Herblore level, cleaning xp) — OSRS. Grimy herbs drop heavily
     *  but had NO cleaning handler, so they were dead-end items and the cleaning xp was
     *  unobtainable. */
    private data class Clean(val grimy: String, val clean: String, val level: Int, val xp: Double, val name: String)
    private val cleanables = listOf(
        Clean("item.grimy_guam_leaf", "item.guam_leaf", 3, 2.5, "guam leaf"),
        Clean("item.grimy_marrentill", "item.marrentill", 5, 3.8, "marrentill"),
        Clean("item.grimy_tarromin", "item.tarromin", 11, 5.0, "tarromin"),
        Clean("item.grimy_harralander", "item.harralander", 20, 6.3, "harralander"),
        Clean("item.grimy_ranarr_weed", "item.ranarr_weed", 25, 7.5, "ranarr weed"),
        Clean("item.grimy_toadflax", "item.toadflax", 30, 8.0, "toadflax"),
        Clean("item.grimy_irit_leaf", "item.irit_leaf", 40, 8.8, "irit leaf"),
        Clean("item.grimy_avantoe", "item.avantoe", 48, 10.0, "avantoe"),
        Clean("item.grimy_kwuarm", "item.kwuarm", 54, 11.3, "kwuarm"),
        Clean("item.grimy_snapdragon", "item.snapdragon", 59, 11.8, "snapdragon"),
        Clean("item.grimy_cadantine", "item.cadantine", 65, 12.5, "cadantine"),
        Clean("item.grimy_lantadyme", "item.lantadyme", 67, 13.1, "lantadyme"),
        Clean("item.grimy_dwarf_weed", "item.dwarf_weed", 70, 13.9, "dwarf weed"),
        Clean("item.grimy_torstol", "item.torstol", 75, 15.0, "torstol"),
    ).filter { resolves(it.grimy) && resolves(it.clean) }

    init {
        recipes.forEach { recipe ->
            onItemOnItem(recipe.a, recipe.b) { make(player, recipe) }
        }
        cleanables.forEach { c ->
            onItemOption(item = c.grimy, option = "clean") { clean(player, c) }
        }
    }

    private fun clean(player: Player, c: Clean) {
        if (player.getSkills().getCurrentLevel(Skills.HERBLORE) < c.level) {
            player.message("You need a Herblore level of ${c.level} to clean this herb.")
            return
        }
        val slot = player.getInteractingItemSlot()
        if (player.inventory.remove(item = getRSCM(c.grimy), amount = 1, beginSlot = slot).completed == 0) return
        player.inventory.add(item = getRSCM(c.clean), amount = 1)
        player.addXp(Skills.HERBLORE, c.xp)
        player.message("You clean the dirt off the ${c.name}.")
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

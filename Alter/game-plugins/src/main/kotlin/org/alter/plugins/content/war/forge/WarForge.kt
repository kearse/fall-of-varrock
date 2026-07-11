package org.alter.plugins.content.war.forge

import org.alter.api.ext.message
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * **War-forging** (story-and-grind-design §6) — the endgame gear chase. The Royal Smith
 * upgrades an elite base item into its best-in-slot successor for a recipe that touches
 * every pillar: the **base item** (PvM / wilderness loot keys, tradeable), **Commendations**
 * (war service — untradeable, the anti-swipe valve), **runite bars** (skilling / the
 * contract economy, tradeable) and a **coin fee** (the sink).
 *
 * Commendations are an ITEM token (`item.ectotoken` — stackable and cache-untradeable;
 * TUNE: rename/resprite via a cache edit later), paid by [CapturePayout] on won war
 * operations, participation-scaled. Forged OUTPUT is tradeable — a rich player may buy a
 * finished piece off a grinder, but every piece in the economy represents real marches
 * fought by somebody.
 *
 * v1 recipes: the three BIS armour lines (Bandos→Torva, Armadyl→Masori, Ahrim's→Ancestral),
 * two pieces each. Spec-weapon restoration (named captains' broken weapons) and the relic
 * ingredient (salvage runs) land with build items 4/6. Costs are TUNE.
 */
object WarForge {

    /** The commendation token (see class doc). */
    const val COMMENDATION_KEY = "item.ectotoken"

    /** Per-piece forging costs. TUNE (≈25 commendations ⇒ ~10-25 won marches per piece). */
    const val COMMENDATION_COST = 25
    const val RUNITE_BAR_COST = 20
    const val COIN_COST = 250_000

    data class Recipe(val display: String, val style: String, val baseKey: String, val baseDisplay: String, val outKey: String)

    val RECIPES: List<Recipe> = listOf(
        Recipe("Torva platebody", "Melee", "item.bandos_chestplate", "Bandos chestplate", "item.torva_platebody"),
        Recipe("Torva platelegs", "Melee", "item.bandos_tassets", "Bandos tassets", "item.torva_platelegs"),
        Recipe("Masori body", "Ranged", "item.armadyl_chestplate", "Armadyl chestplate", "item.masori_body"),
        Recipe("Masori chaps", "Ranged", "item.armadyl_chainskirt", "Armadyl chainskirt", "item.masori_chaps"),
        Recipe("Ancestral robe top", "Magic", "item.ahrims_robetop", "Ahrim's robetop", "item.ancestral_robe_top"),
        Recipe("Ancestral robe bottom", "Magic", "item.ahrims_robeskirt", "Ahrim's robeskirt", "item.ancestral_robe_bottom"),
    )

    fun recipesFor(style: String): List<Recipe> = RECIPES.filter { it.style == style }

    private fun id(key: String): Int = getRSCM(key)

    /** The player's carried commendation count. */
    fun commendations(p: Player): Int = p.inventory.getItemCount(id(COMMENDATION_KEY))

    /** Pay [n] commendations into the pack (ground overflow — never voided). */
    fun awardCommendations(p: Player, n: Int) {
        if (n <= 0) return
        val cid = id(COMMENDATION_KEY)
        val added = p.inventory.add(cid, n, assureFullInsertion = false)
        val left = n - added.completed
        if (left > 0) p.world.spawn(GroundItem(cid, left, p.tile, p))
        p.message("<col=ffae00>+$n Commendation${if (n == 1) "" else "s"}</col> for your service in the field.")
    }

    /** One-line cost summary for the smith's dialogue. */
    fun costLine(r: Recipe): String =
        "1 ${r.baseDisplay}, $COMMENDATION_COST Commendations, $RUNITE_BAR_COST runite bars and ${"%,d".format(COIN_COST)} coins"

    /** What's missing for [r], or null if the player carries everything. */
    fun missingFor(p: Player, r: Recipe): String? {
        if (p.inventory.getItemCount(id(r.baseKey)) < 1) return "the ${r.baseDisplay} itself"
        if (commendations(p) < COMMENDATION_COST) return "Commendations (${commendations(p)}/$COMMENDATION_COST — serve in the realm's marches)"
        if (p.inventory.getItemCount(id("item.runite_bar")) < RUNITE_BAR_COST) return "runite bars (${p.inventory.getItemCount(id("item.runite_bar"))}/$RUNITE_BAR_COST)"
        if (p.inventory.getItemCount(id("item.coins_995")) < COIN_COST) return "coins (the fee is ${"%,d".format(COIN_COST)})"
        return null
    }

    /**
     * Consume the full recipe from the pack and hand over the forged piece. Caller must have
     * verified [missingFor] is null; returns false if anything changed underneath us.
     */
    fun forge(p: Player, r: Recipe): Boolean {
        if (missingFor(p, r) != null) return false
        p.inventory.remove(id(r.baseKey), 1)
        p.inventory.remove(id(COMMENDATION_KEY), COMMENDATION_COST)
        p.inventory.remove(id("item.runite_bar"), RUNITE_BAR_COST)
        p.inventory.remove(id("item.coins_995"), COIN_COST)
        p.inventory.add(id(r.outKey), 1, assureFullInsertion = false).let { tx ->
            if (tx.completed < 1) p.world.spawn(GroundItem(id(r.outKey), 1, p.tile, p))
        }
        return true
    }
}

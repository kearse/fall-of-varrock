package org.alter.plugins.content.economy

import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * **Quartermaster supply sink** (master design brief §3B — skilling supplies the war). The Quartermaster
 * accepts the *finished* goods that skilling produces — cooked food, finished potions, smithed bars —
 * and pays **War Effort per unit**, consuming the items. This is the economy's deliberate item sink and
 * the end of the "gather → produce → supply the war" loop (the resource contracts are the gather step).
 *
 * Per-unit values are modest and TUNABLE — the point is to give surplus production a war purpose (and
 * feed the daily War Effort bonus), not to be a gp faucet.
 */
object SupplyDepot {

    /** Cooked food (fishing→cooking, farming, etc.). */
    val FOOD: Map<String, Int> = mapOf(
        "item.shrimps" to 1, "item.anchovies" to 1, "item.bread" to 1, "item.cooked_chicken" to 1,
        "item.cooked_meat" to 1, "item.sardine" to 1, "item.herring" to 2, "item.trout" to 2,
        "item.pike" to 3, "item.salmon" to 3, "item.tuna" to 3, "item.lobster" to 4,
        "item.bass" to 5, "item.swordfish" to 5, "item.monkfish" to 6, "item.shark" to 8,
    )

    /** Finished potions (herblore), all doses. */
    val POTIONS: Map<String, Int> = buildMap {
        putAll(doses("item.attack_potion", 3)); putAll(doses("item.strength_potion", 3))
        putAll(doses("item.defence_potion", 3)); putAll(doses("item.super_attack", 5))
        putAll(doses("item.super_strength", 5)); putAll(doses("item.super_defence", 5))
        putAll(doses("item.prayer_potion", 5)); putAll(doses("item.super_combat_potion", 10))
        putAll(doses("item.ranging_potion", 5)); putAll(doses("item.saradomin_brew", 8))
    }

    /** Smithed bars (mining→smithing). */
    val BARS: Map<String, Int> = mapOf(
        "item.bronze_bar" to 1, "item.iron_bar" to 2, "item.steel_bar" to 3,
        "item.mithril_bar" to 5, "item.adamantite_bar" to 8, "item.runite_bar" to 12,
    )

    /** Everything the Quartermaster takes. */
    val ALL: Map<String, Int> = FOOD + POTIONS + BARS

    /** Build dose-1..4 entries for a potion base, scaling the value down with the dose. */
    private fun doses(base: String, v4: Int): Map<String, Int> = mapOf(
        "${base}4" to v4,
        "${base}3" to (v4 * 3 / 4).coerceAtLeast(1),
        "${base}2" to (v4 / 2).coerceAtLeast(1),
        "${base}1" to (v4 / 4).coerceAtLeast(1),
    )

    /** Consume every matching item in the player's inventory and pay War Effort. Returns (items, WE).
     *  Items in the active [SupplyDrive] category pay the drive multiplier (Phase C — high demand). */
    fun deposit(p: Player, wares: Map<String, Int>): Pair<Int, Int> {
        var items = 0
        var we = 0
        var boosted = false
        for ((key, value) in wares) {
            val id = runCatching { getRSCM(key) }.getOrNull() ?: continue
            val count = p.inventory.getItemCount(id)
            if (count <= 0) continue
            p.inventory.remove(id, count)
            val mult = SupplyDrive.multiplierFor(key)
            if (mult > 1) boosted = true
            items += count
            we += count * value * mult
        }
        if (we > 0) {
            p.addPoints(PointKind.WAR_EFFORT, we)
            // Supplies also feed the realm war-supply meter that gates campaigns (the Mire loop).
            org.alter.plugins.content.war.RealmSupply.contribute(p.world, we)
            if (boosted) {
                p.message("<col=ffae00>Supply Drive bonus!</col> Your ${SupplyDrive.active?.display?.lowercase()} paid ${SupplyDrive.MULTIPLIER}x War Effort.")
            }
            // UX: show the supplier what their hand-in did to the realm's stores — the meter
            // only used to speak when it crossed a campaign threshold.
            p.message("The realm's war-stores rise to <col=4f9b4f>${org.alter.plugins.content.war.RealmSupply.meter()}/${org.alter.plugins.content.war.RealmSupply.max()}</col> — marches and campaigns feed on them.")
        }
        return items to we
    }
}

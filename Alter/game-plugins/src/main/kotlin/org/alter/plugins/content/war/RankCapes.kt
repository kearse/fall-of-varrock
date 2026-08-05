package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.game.model.entity.Player
import org.alter.rscm.RSCM.getRSCM

/**
 * Rank capes — the feudal ladder's uniform. Every noble rank (Squire and up, the same
 * ranks whose names show in colour) has a cape, going plain → elaborate up the ladder,
 * each colour echoing the rank's name colour ([Title.nameColor]). The cape is granted
 * by [RankPurchase] when the rank is bought and rank-gated on equip ([RankCapesPlugin]),
 * so a cape on someone's back is proof of standing — the same promise the armour gate
 * makes ("seeing dragon means Lord at minimum").
 *
 * The items are otherwise-unobtainable cache capes renamed + re-statted in
 * `data/cfg/items/itemOverrides/unique/rank_capes.yml` — stats climb with rank but the
 * King's cape stays just under the fire cape so the earned combat capes remain trophies.
 */
object RankCapes {

    /** The cape each rank wears, lowest noble rank first. Peasant/Commoner have none. */
    val CAPES: Map<Title, String> = linkedMapOf(
        Title.SQUIRE to "item.fremennik_green_cloak",     // plain green wool
        Title.SOLDIER to "item.fremennik_blue_cloak",      // plain blue wool
        Title.KNIGHT to "item.saradomin_cloak",            // white, star heraldry
        Title.LORD to "item.victors_cape_1000",            // black, purple wreath
        Title.MINISTER to "item.soul_cape",                // deep crimson
        Title.KING to "item._20th_anniversary_cape",       // gold-embroidered ceremonial
    )

    /** The rank cape's item id for [title], or null for the capeless commoner ranks. */
    fun itemFor(title: Title): Int? = CAPES[title]?.let { getRSCM(it) }

    /** "Squire's cape" etc — matches the override names in rank_capes.yml. */
    fun capeName(title: Title): String = "${title.display}'s cape"

    /** True if [p] already holds [title]'s cape anywhere it could reasonably be. */
    fun owns(p: Player, title: Title): Boolean {
        val id = itemFor(title) ?: return false
        return p.inventory.contains(id) || p.equipment.contains(id) || p.bank.contains(id)
    }

    /**
     * Hand [title]'s cape to [p] — into the pack, overflowing to the bank when full
     * (mirrors the war-content coin/reward pattern). No-op for capeless ranks.
     */
    fun grant(p: Player, title: Title) {
        val id = itemFor(title) ?: return
        val tx = p.inventory.add(id, 1, assureFullInsertion = false)
        if (tx.completed > 0) {
            p.message("You receive the <col=ffae00>${capeName(title)}</col> — wear it to show your station.")
        } else {
            p.bank.add(id, 1)
            p.message("Your <col=ffae00>${capeName(title)}</col> has been sent to your bank.")
        }
    }
}

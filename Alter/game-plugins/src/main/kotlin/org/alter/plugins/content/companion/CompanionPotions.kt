package org.alter.plugins.content.companion

import org.alter.api.Skills
import org.alter.game.model.timer.POTION_DELAY
import org.alter.rscm.RSCM.getRSCM

/**
 * Companions "pot up" — drink their archetype's boosting potion when fighting and the boost has worn
 * off. Mirrors the boost maths in `PotionsPlugin` (`base + pct * baseLevel`, via `alterCurrentLevel`)
 * and the 4→3→2→1→vial dose chain, gated by [POTION_DELAY].
 */
object CompanionPotions {
    private class Pot(val base: String, val boosts: List<Triple<Int, Int, Double>>)

    private fun potFor(a: CompanionStyle): Pot = when (a) {
        CompanionStyle.MELEE -> Pot(
            "super_combat_potion",
            listOf(Triple(Skills.ATTACK, 5, 0.15), Triple(Skills.STRENGTH, 5, 0.15), Triple(Skills.DEFENCE, 5, 0.15)),
        )
        CompanionStyle.RANGE -> Pot("ranging_potion", listOf(Triple(Skills.RANGED, 4, 0.10)))
        CompanionStyle.MAGE -> Pot("magic_potion", listOf(Triple(Skills.MAGIC, 4, 0.0)))
    }

    /** Drink a boosting dose if the companion's boost has lapsed and it isn't on cooldown. */
    fun maybePot(comp: Companion) {
        if (comp.timers.has(POTION_DELAY)) return
        val pot = potFor(comp.archetype)
        val primary = pot.boosts.first().first
        if (comp.getSkills().getCurrentLevel(primary) > comp.getSkills().getBaseLevel(primary)) return // still boosted

        for (n in 4 downTo 1) {
            val id = resolve("item.${pot.base}$n") ?: resolve("item.${pot.base}_$n") ?: continue
            if (comp.inventory.getItemCount(id) <= 0) continue
            if (!comp.inventory.remove(id, 1).hasSucceeded()) return
            val next = if (n > 1) (resolve("item.${pot.base}${n - 1}") ?: resolve("item.${pot.base}_${n - 1}")) else resolve("item.vial")
            next?.let { comp.inventory.add(it, 1) }
            pot.boosts.forEach { (skill, base, pct) ->
                val amt = base + (comp.getSkills().getBaseLevel(skill) * pct).toInt()
                if (amt > 0) comp.getSkills().alterCurrentLevel(skill, amt, amt)
            }
            comp.timers[POTION_DELAY] = 3
            return
        }
    }

    private fun resolve(name: String): Int? = runCatching { getRSCM(name) }.getOrNull()
}

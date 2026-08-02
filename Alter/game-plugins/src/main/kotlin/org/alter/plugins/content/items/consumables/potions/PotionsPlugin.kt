package org.alter.plugins.content.items.consumables.potions

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.ANTIFIRE_POTION_CHARGES_ATTR
import org.alter.game.model.attr.DRAGONFIRE_IMMUNITY_ATTR
import org.alter.game.model.attr.POISON_TICKS_LEFT_ATTR
import org.alter.game.model.attr.VENOM_DAMAGE_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.ANTIFIRE_TIMER
import org.alter.game.model.timer.POISON_TIMER
import org.alter.game.model.timer.POTION_DELAY
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Drinking potions** (consumption-loop). Wires the common OSRS drinkables: combat-stat
 * boosts, restores, brews, run-energy, poison/venom cures, and timed antifire protection.
 * Drink → effect → dose drops (4→3→2→1→empty vial), gated by [POTION_DELAY].
 *
 * Coverage is data-driven via [family]; only doses that resolve in the cache are bound, so
 * adding an item rscm is enough to enable it.
 */
class PotionsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** A single dose: its effect + the item it leaves behind (next dose, or vial). */
    private class Dose(val effect: (Player) -> Unit, val next: String)

    private val doseByKey = HashMap<String, Dose>()

    init {
        // ---- Combat-stat boosts (+base, +pct of the relevant base level) -------------------
        family("attack_potion") { boost(it, Skills.ATTACK, 3, 0.10) }
        family("strength_potion") { boost(it, Skills.STRENGTH, 3, 0.10) }
        family("defence_potion") { boost(it, Skills.DEFENCE, 3, 0.10) }
        family("super_attack") { boost(it, Skills.ATTACK, 5, 0.15) }
        family("super_strength") { boost(it, Skills.STRENGTH, 5, 0.15) }
        family("super_defence") { boost(it, Skills.DEFENCE, 5, 0.15) }
        family("super_combat_potion") {
            boost(it, Skills.ATTACK, 5, 0.15); boost(it, Skills.STRENGTH, 5, 0.15); boost(it, Skills.DEFENCE, 5, 0.15)
        }
        family("combat_potion") { boost(it, Skills.ATTACK, 3, 0.10); boost(it, Skills.STRENGTH, 3, 0.10) }
        family("ranging_potion") { boost(it, Skills.RANGED, 4, 0.10) }
        family("super_ranging") { boost(it, Skills.RANGED, 5, 0.15) }
        family("magic_potion") { boost(it, Skills.MAGIC, 4, 0.0) }
        family("super_magic_potion") { boost(it, Skills.MAGIC, 5, 0.15) }
        family("bastion_potion") { boost(it, Skills.RANGED, 4, 0.10); boost(it, Skills.DEFENCE, 5, 0.15) }
        family("battlemage_potion") { boost(it, Skills.MAGIC, 4, 0.0); boost(it, Skills.DEFENCE, 5, 0.15) }

        // ---- Divine variants — sold in shops (e.g. the vote shop's divine super combat) but were
        // never wired, so drinking one was a silent no-op ("super combat potion does not increase
        // stats"). Simplified: same boosts as their non-divine counterparts; the OSRS re-boost-
        // every-15s (and 10hp sip cost) is not modelled.
        family("divine_super_combat_potion") {
            boost(it, Skills.ATTACK, 5, 0.15); boost(it, Skills.STRENGTH, 5, 0.15); boost(it, Skills.DEFENCE, 5, 0.15)
        }
        family("divine_super_attack_potion") { boost(it, Skills.ATTACK, 5, 0.15) }
        family("divine_super_strength_potion") { boost(it, Skills.STRENGTH, 5, 0.15) }
        family("divine_super_defence_potion") { boost(it, Skills.DEFENCE, 5, 0.15) }
        family("divine_ranging_potion") { boost(it, Skills.RANGED, 5, 0.15) }
        family("divine_magic_potion") { boost(it, Skills.MAGIC, 4, 0.0) }
        family("divine_bastion_potion") { boost(it, Skills.RANGED, 4, 0.10); boost(it, Skills.DEFENCE, 5, 0.15) }
        family("divine_battlemage_potion") { boost(it, Skills.MAGIC, 4, 0.0); boost(it, Skills.DEFENCE, 5, 0.15) }
        family("zamorak_brew") {
            boost(it, Skills.ATTACK, 2, 0.20); boost(it, Skills.STRENGTH, 2, 0.12)
            drainCurrent(it, Skills.DEFENCE, 2, 0.10)
            drainHitpoints(it, 0.12) // floored, never below 1
            restoreSkill(it, Skills.PRAYER, 0, 0.10)
        }

        // ---- Restores ----------------------------------------------------------------------
        family("prayer_potion") { restoreSkill(it, Skills.PRAYER, 7, 0.25) }
        family("super_restore") { restoreAllButHitpoints(it, 8, 0.25) }
        family("sanfew_serum") { restoreAllButHitpoints(it, 4, 0.30); curePoison(it, IMMUNITY_SUPER) }
        family("restore_potion") { restoreCombatStats(it, 10, 0.30) }

        // ---- Heal / brews ------------------------------------------------------------------
        family("saradomin_brew") {
            val hp = 2 + (it.getSkills().getBaseLevel(Skills.HITPOINTS) * 0.15).toInt()
            it.heal(hp, hp) // overheal above max by the boost amount
            boost(it, Skills.DEFENCE, 2, 0.20)
            drainCurrent(it, Skills.ATTACK, 2, 0.10); drainCurrent(it, Skills.STRENGTH, 2, 0.10)
            drainCurrent(it, Skills.MAGIC, 2, 0.10); drainCurrent(it, Skills.RANGED, 2, 0.10)
        }

        // ---- Run energy --------------------------------------------------------------------
        family("energy_potion") { restoreEnergy(it, 10) }
        family("super_energy") { restoreEnergy(it, 20) }
        family("stamina_potion") { restoreEnergy(it, 20) } // OSRS: +20% (reduced-drain effect not modelled)

        // ---- Poison / venom cures (immunity in POISON_TIMER fires, ~15s each) ---------------
        family("antipoison") { curePoison(it, IMMUNITY_ANTIPOISON) }   // 90s
        family("superantipoison") { curePoison(it, IMMUNITY_SUPER) }   // 6 min
        family("antidote") { curePoison(it, IMMUNITY_ANTIDOTE) }       // 9 min (antidote+)
        family("antivenom") { curePoison(it, IMMUNITY_ANTIVENOM) }     // 12 min, also clears venom
        family("relicyms_balm") { it.message("You feel cleansed of any disease.") }

        // ---- Antifire (timed dragonfire protection) ----------------------------------------
        family("antifire_potion") { antifire(it, ANTIFIRE_REGULAR, fullImmunity = false) }       // 6 min partial
        family("extended_antifire") { antifire(it, ANTIFIRE_EXTENDED, fullImmunity = false) }     // 12 min partial
        family("super_antifire_potion") { antifire(it, SUPER_ANTIFIRE, fullImmunity = true) }     // 3 min full
        family("extended_super_antifire") { antifire(it, EXTENDED_SUPER_ANTIFIRE, fullImmunity = true) } // 6 min full

        // Expire antifire protection when its timer runs out.
        onTimer(ANTIFIRE_TIMER) {
            val p = pawn as? Player ?: return@onTimer
            p.attr[ANTIFIRE_POTION_CHARGES_ATTR] = 0
            p.attr[DRAGONFIRE_IMMUNITY_ATTR] = false
            p.message("Your antifire protection has expired.")
        }

        doseByKey.keys.toList().forEach { key ->
            try {
                onItemOption(item = key, option = "drink") { drink(player, key) }
            } catch (e: Exception) {
                // ERROR, not warn: a swallowed bind failure means one potion family is silently
                // dead at boot while every other potion works — near-invisible in a warn stream.
                logger.error(e) { "potions: couldn't bind 'drink' on $key — this potion will do NOTHING in game" }
            }
        }
    }

    /** Register doses 4→1 for a potion family (only those present in cache). Dose names follow
     *  `<base><n>` or `<base>_<n>` (e.g. `super_attack4`, `super_ranging_4`). */
    private fun family(base: String, effect: (Player) -> Unit) {
        val present = (4 downTo 1).mapNotNull { n ->
            val a = "item.$base$n"
            val b = "item.${base}_$n"
            when {
                resolves(a) -> a
                resolves(b) -> b
                else -> null
            }
        }
        if (present.isEmpty()) {
            logger.error { "potions: family '$base' resolved no doses in the cache — nothing bound" }
            return
        }
        present.forEachIndexed { i, key ->
            val next = present.getOrNull(i + 1) ?: "item.vial"
            doseByKey[key] = Dose(effect, next)
        }
    }

    private fun drink(player: Player, key: String) {
        val dose = doseByKey[key] ?: return
        if (org.alter.plugins.content.minigames.duel.DuelArena.rulesOf(player)?.noDrinks == true) {
            player.message("You can't drink potions in this duel.")
            return
        }
        if (player.timers.has(POTION_DELAY)) return
        val slot = player.getInteractingSlot()
        if (player.inventory.remove(item = getRSCM(key), beginSlot = slot).hasSucceeded()) {
            dose.effect(player)
            player.inventory.add(item = getRSCM(dose.next), beginSlot = slot)
            player.animate(DRINK_ANIM)
            player.timers[POTION_DELAY] = POTION_DELAY_TICKS
            player.message("You drink some of your potion.")
        }
    }

    /** Boost [skill] current level by `base + pct * baseLevel`, capped at base+that amount. */
    private fun boost(p: Player, skill: Int, base: Int, pct: Double) {
        val amt = base + (p.getSkills().getBaseLevel(skill) * pct).toInt()
        if (amt > 0) p.getSkills().alterCurrentLevel(skill, amt, amt)
    }

    /** Drain [skill] by `flat + pct * currentLevel` (OSRS uses the *current* level for drains),
     *  cumulative across doses down to 0. */
    private fun drainCurrent(p: Player, skill: Int, flat: Int, pct: Double) {
        val amt = flat + (p.getSkills().getCurrentLevel(skill) * pct).toInt()
        if (amt > 0) p.getSkills().alterCurrentLevel(skill, -amt, -p.getSkills().getBaseLevel(skill))
    }

    /** Zamorak brew Hitpoints drain: `pct * currentHp`, never reducing Hitpoints below 1. */
    private fun drainHitpoints(p: Player, pct: Double) {
        val cur = p.getSkills().getCurrentLevel(Skills.HITPOINTS)
        val amt = Math.min((cur * pct).toInt(), cur - 1)
        if (amt > 0) p.getSkills().alterCurrentLevel(Skills.HITPOINTS, -amt, -p.getSkills().getBaseLevel(Skills.HITPOINTS))
    }

    /** Restore [skill] toward (capped at) its base level by `base + pct * baseLevel`. */
    private fun restoreSkill(p: Player, skill: Int, base: Int, pct: Double) {
        val amt = base + (p.getSkills().getBaseLevel(skill) * pct).toInt()
        if (amt > 0) p.getSkills().alterCurrentLevel(skill, amt, 0) // capValue 0 -> up to base
    }

    private fun restoreCombatStats(p: Player, base: Int, pct: Double) {
        intArrayOf(Skills.ATTACK, Skills.STRENGTH, Skills.DEFENCE, Skills.RANGED, Skills.MAGIC)
            .forEach { restoreSkill(p, it, base, pct) }
    }

    /** Super restore / sanfew serum: restore every skill except Hitpoints. */
    private fun restoreAllButHitpoints(p: Player, base: Int, pct: Double) {
        (0..Skills.CONSTRUCTION).forEach { skill ->
            if (skill != Skills.HITPOINTS) restoreSkill(p, skill, base, pct)
        }
    }

    private fun restoreEnergy(p: Player, pct: Int) {
        p.runEnergy = Math.min(MAX_RUN_ENERGY, p.runEnergy + pct * 100.0)
        p.sendRunEnergy(p.runEnergy.toInt())
    }

    /** Cure existing poison and grant immunity for [immunityTicks] poison ticks
     *  (stored as a negative tick counter that counts back up to zero). Against venom,
     *  only anti-venom clears it — a weaker cure converts the venom to regular poison
     *  at its current damage, exactly as in OSRS. */
    private fun curePoison(p: Player, immunityTicks: Int) {
        if (Poison.isEnvenomed(p) && immunityTicks < IMMUNITY_ANTIVENOM) {
            Poison.convertVenomToPoison(p)
            return
        }
        p.attr.remove(VENOM_DAMAGE_ATTR)
        p.attr[POISON_TICKS_LEFT_ATTR] = -immunityTicks
        p.timers[POISON_TIMER] = 1
        Poison.setHpOrb(p, Poison.OrbState.NONE)
    }

    private fun antifire(p: Player, ticks: Int, fullImmunity: Boolean) {
        p.attr[ANTIFIRE_POTION_CHARGES_ATTR] = 1
        if (fullImmunity) p.attr[DRAGONFIRE_IMMUNITY_ATTR] = true
        p.timers[ANTIFIRE_TIMER] = ticks
    }

    private fun resolves(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        const val DRINK_ANIM = 829
        const val POTION_DELAY_TICKS = 3
        const val MAX_RUN_ENERGY = 10000.0

        // Poison-cure immunity in POISON_TIMER fires (each = 30 ticks = 18s).
        const val IMMUNITY_ANTIPOISON = 5   // 90s
        const val IMMUNITY_SUPER = 20       // 6 min
        const val IMMUNITY_ANTIDOTE = 30    // 9 min
        const val IMMUNITY_ANTIVENOM = 40   // 12 min

        // Antifire protection duration in game ticks (0.6s each).
        const val ANTIFIRE_REGULAR = 600          // 6 min
        const val ANTIFIRE_EXTENDED = 1200        // 12 min
        const val SUPER_ANTIFIRE = 300            // 3 min
        const val EXTENDED_SUPER_ANTIFIRE = 600   // 6 min
    }
}

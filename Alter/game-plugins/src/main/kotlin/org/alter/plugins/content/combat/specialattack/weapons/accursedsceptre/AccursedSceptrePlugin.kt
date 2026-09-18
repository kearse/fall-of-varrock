package org.alter.plugins.content.combat.specialattack.weapons.accursedsceptre

import org.alter.api.Skills
import org.alter.api.ext.message
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Accursed sceptre special attack: **"Accursed Touch"** — 50% energy.
 *
 * OSRS: the cast lands as normal and, on a hit, drains the target's Defence and Magic by 15% of
 * their current level. Against another player the drain is halved, which is the standard OSRS
 * treatment for a stat-draining special in PvP.
 *
 * Player report 2026-09-18: "accursed scepter does not have built in auto cast". The sceptre had no
 * plugin of any kind — no special, and no Wilderness bonus either, despite being the magic member
 * of the revenant family and a 30k Blood Money line in the PK shop. The Wilderness half is now in
 * [org.alter.plugins.content.combat.RevenantWeapons]; this is the special.
 *
 * Autocast itself was already available: both sceptres are cache weapon type 18 (MAGIC_STAFF) and
 * are not powered staves, so `CombatConfigs.canAutocast` permits them and autocast arms by casting
 * once, as it does for every staff here. What they never had was the weapon's own behaviour.
 */
class AccursedSceptrePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        for (sceptre in SCEPTRES) {
            runCatching {
                SpecialAttacks.register(sceptre, ENERGY) {
                    val spell = player.attr[org.alter.plugins.content.combat.Combat.CASTING_SPELL]
                    if (spell == null) {
                        // Nothing to cast: the sceptre fires a spell, so without one there is no
                        // attack to ride. Say so rather than eating the energy silently.
                        player.message("You need to select a spell to autocast before using this special attack.")
                        return@register
                    }
                    val maxHit = MagicCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.0)
                    val accuracy = MagicCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 1.0)
                    val landed = accuracy >= world.randomDouble()
                    player.animate(spell.castAnimation)
                    player.dealHit(target = target, maxHit = maxHit, landHit = landed, delay = 2)
                    if (landed) {
                        // Halved against players, as OSRS halves drain specials in PvP.
                        val fraction = if (target is Player) DRAIN_FRACTION / 2.0 else DRAIN_FRACTION
                        when (val victim = target) {
                            is Player -> {
                                drain(victim, Skills.DEFENCE, fraction)
                                drain(victim, Skills.MAGIC, fraction)
                            }
                            is Npc -> {
                                drain(victim, org.alter.api.NpcSkills.DEFENCE, fraction)
                                drain(victim, org.alter.api.NpcSkills.MAGIC, fraction)
                            }
                        }
                        player.message("<col=8f00ff>Your accursed touch saps your target.</col>")
                    }
                }
            }
        }
    }

    private fun drain(player: Player, skill: Int, fraction: Double) {
        val current = player.getSkills().getCurrentLevel(skill)
        val drained = Math.floor(current * fraction).toInt()
        if (drained <= 0) return
        player.getSkills().decrementCurrentLevel(skill, drained, capped = false)
    }

    private fun drain(npc: Npc, skill: Int, fraction: Double) {
        val current = npc.stats.getCurrentLevel(skill)
        val drained = Math.floor(current * fraction).toInt()
        if (drained <= 0) return
        npc.stats.decrementCurrentLevel(skill, drained, capped = false)
    }

    private companion object {
        const val ENERGY = 50

        /** 15% of the target's CURRENT level, per OSRS. Halved against players. */
        const val DRAIN_FRACTION = 0.15

        /** Both charged forms — the attuned "(a)" sceptre is the same weapon swapped to Ancients. */
        val SCEPTRES = listOf("item.accursed_sceptre", "item.accursed_sceptre_a")
    }
}

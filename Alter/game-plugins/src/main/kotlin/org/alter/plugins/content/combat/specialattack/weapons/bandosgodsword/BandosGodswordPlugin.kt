package org.alter.plugins.content.combat.specialattack.weapons.bandosgodsword

import org.alter.api.NpcSkills
import org.alter.api.Skills
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.currentCombatStat
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.drainCombatStat
import org.alter.plugins.content.combat.formula.MeleeCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks

/**
 * Bandos godsword (11804) special attack: "Warstrike".
 * 50% energy, +100% accuracy, +21% damage. On a successful hit it drains the
 * target's Defence level by the amount of damage dealt.
 */
class BandosGodswordPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        SpecialAttacks.register("item.bandos_godsword", 50) {
            player.animate(id = 7642)
            player.graphic(id = 1210, height = 92)

            val maxHit = MeleeCombatFormula.getMaxHit(player, target, specialAttackMultiplier = 1.21)
            val accuracy = MeleeCombatFormula.getAccuracy(player, target, specialAttackMultiplier = 2.0)
            val landHit = accuracy >= world.randomDouble()
            val victim = target
            val pawnHit = player.dealHit(target = victim, maxHit = maxHit, landHit = landHit, delay = 0)

            if (landHit) {
                // OSRS Warstrike: drains stats equal to the damage dealt, cascading
                // Defence → Strength → Prayer → Attack → Magic → Ranged; works on NPCs
                // (skipping Prayer, which only players have). Runs at hit application so
                // the drained amount matches the damage that actually landed.
                pawnHit.hit.addAction {
                    var remaining = hitmarks.sumOf { it.damage }
                    val order =
                        listOf(
                            Skills.DEFENCE to NpcSkills.DEFENCE,
                            Skills.STRENGTH to NpcSkills.STRENGTH,
                            Skills.PRAYER to -1,
                            Skills.ATTACK to NpcSkills.ATTACK,
                            Skills.MAGIC to NpcSkills.MAGIC,
                            Skills.RANGED to NpcSkills.RANGED,
                        )
                    for ((playerSkill, npcSkill) in order) {
                        if (remaining <= 0) break
                        if (npcSkill == -1 && victim !is Player) continue
                        val current = victim.currentCombatStat(playerSkill, npcSkill)
                        val drain = minOf(remaining, current)
                        if (drain > 0) {
                            victim.drainCombatStat(playerSkill, npcSkill, drain)
                            remaining -= drain
                        }
                    }
                }
            }
        }
    }
}

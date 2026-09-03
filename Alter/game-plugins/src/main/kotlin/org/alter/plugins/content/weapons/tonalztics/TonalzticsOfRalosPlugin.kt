package org.alter.plugins.content.weapons.tonalztics

import org.alter.api.NpcSkills
import org.alter.api.Skills
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.CombatConfigs
import org.alter.plugins.content.combat.TonalzticsOfRalos
import org.alter.plugins.content.combat.currentCombatStat
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.drainCombatStat
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy

/**
 * Tonalztics of ralos special attack "Division" (OSRS Wiki): 50% energy, +50% accuracy on
 * every throw of the attack (two when charged, one uncharged), each landing hit drains the
 * target's Defence level by one eighth of the target's Magic level. Damage per hit stays at
 * the weapon's usual 0-75% of max.
 */
class TonalzticsOfRalosPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        for (item in listOf("item.tonalztics_of_ralos", "item.tonalztics_of_ralos_uncharged")) {
            runCatching {
                SpecialAttacks.register(item, TonalzticsOfRalos.SPECIAL_ENERGY) {
                    player.animate(CombatConfigs.getAttackAnimation(player))
                    val delay = RangedCombatStrategy.getHitDelay(player.getCentreTile(), target.getCentreTile())
                    repeat(TonalzticsOfRalos.hitsPerAttack(player)) {
                        val maxHit = TonalzticsOfRalos.scaleMaxHit(RangedCombatFormula.getMaxHit(player, target))
                        val accuracy = RangedCombatFormula.getAccuracy(
                            player, target, specialAttackMultiplier = TonalzticsOfRalos.SPECIAL_ACCURACY_MULTIPLIER,
                        )
                        val landHit = accuracy >= world.randomDouble()
                        val pawnHit = player.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = delay)
                        if (landHit) {
                            pawnHit.hit.addAction {
                                if (hitmarks.sumOf { it.damage } > 0) {
                                    val magic = target.currentCombatStat(Skills.MAGIC, NpcSkills.MAGIC)
                                    target.drainCombatStat(
                                        Skills.DEFENCE, NpcSkills.DEFENCE,
                                        magic / TonalzticsOfRalos.SPECIAL_DEFENCE_DRAIN_DIVISOR,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

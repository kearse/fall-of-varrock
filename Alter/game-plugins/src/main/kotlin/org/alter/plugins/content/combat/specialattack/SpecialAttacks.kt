package org.alter.plugins.content.combat.specialattack

import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.game.model.World
import org.alter.game.model.attr.LAST_HIT_ATTR
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.ATTACK_DELAY
import org.alter.rscm.RSCM.getRSCM
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.interfaces.attack.AttackTab

/**
 * @author Tom <rspsmods@gmail.com>
 */
object SpecialAttacks {

    fun register(
        item: String,
        energy: Int,
        executeInstantly: Boolean = false,
        attack: CombatContext.() -> Unit,
    ) {
        register(
            getRSCM(item),
            energy,
            executeInstantly,
            attack
        )
    }

    fun register(
        item: Int,
        energy: Int,
        executeInstantly: Boolean = false,
        attack: CombatContext.() -> Unit,
    ) {
        attacks[item] = SpecialAttack(energy, executeInstantly, attack)
    }

    fun executeOnEnable(item: Int): Boolean {
        if (attacks.containsKey(item)) {
            return attacks[item]!!.executeOnSpecBar
        }
        return false
    }

    fun execute(
        player: Player,
        target: Pawn?,
        world: World,
    ): Boolean {
        val weaponItem = player.getEquipment(EquipmentType.WEAPON) ?: return false
        val special = attacks[weaponItem.id] ?: return false

        // Instant (spec-bar) activation arrives with no target: fire at the current or
        // very recent combat target if they're still adjacent and attackable — the OSRS
        // granite-maul style "click the orb, hit instantly" behaviour.
        val resolvedTarget =
            target ?: run {
                val recent = player.getCombatTarget() ?: player.attr[LAST_HIT_ATTR]?.get()
                if (recent == null || recent.isDead() ||
                    player.tile.getChebyshevDistance(recent.tile) > recent.getSize() ||
                    !Combat.canEngage(player, recent)
                ) {
                    return false
                }
                recent
            }

        if (AttackTab.getEnergy(player) < special.energyRequired) {
            return false
        }

        AttackTab.setEnergy(player, AttackTab.getEnergy(player) - special.energyRequired)

        val combatContext = CombatContext(world, player)
        combatContext.target = resolvedTarget
        special.attack(combatContext)

        if (target == null) {
            // Instant specs still count as an attack (skull, PJ timer, last-hit
            // bookkeeping) but must NOT arm the attack cooldown — that is exactly
            // what makes the granite maul spec "0-tick". Preserve whatever cooldown
            // was already running.
            val previousDelay = if (player.timers.has(ATTACK_DELAY)) player.timers[ATTACK_DELAY] else 0
            Combat.postAttack(player, resolvedTarget)
            if (previousDelay > 0) {
                player.timers[ATTACK_DELAY] = previousDelay
            } else {
                player.timers.remove(ATTACK_DELAY)
            }
        }

        return true
    }

    val attacks = mutableMapOf<Int, SpecialAttack>()
}

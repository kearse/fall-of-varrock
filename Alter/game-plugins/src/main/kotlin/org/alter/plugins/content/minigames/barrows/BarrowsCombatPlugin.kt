package org.alter.plugins.content.minigames.barrows

import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.combat.formula.MeleeCombatFormula

/**
 * The six brothers' fights — Kronos `brothers/<Name>.java` translated onto the shared boss
 * primitives. Each brother is one plain attack plus his **set effect**, rolled at the
 * donor's 1-in-4:
 *
 *  - **Ahrim**: magic (gfx 155 cast, 156 bolt, anim 727, max 25, range 8); drains 5 Strength.
 *  - **Dharok**: slash (anim 2067); max hit grows with his missing health — 29 at full, twice
 *    that at the brink (the wiki's "hits harder the lower his health").
 *  - **Guthan**: stab (anim 2080, max 24); heals himself by the damage dealt (gfx 398).
 *  - **Karil**: bolt (anim 2075, max 20, range 8); saps a fifth of your Agility (gfx 401).
 *  - **Torag**: crush (anim 2068, max 24); drains a fifth of your run energy (gfx 399).
 *  - **Verac**: crush (anim 2062, max 23); every fourth swing pierces protection prayers
 *    (donor: max 15 on the piercing hit).
 */
class BarrowsCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        Barrows.Brother.values().forEach { b ->
            onNpcCombat(b.npcKey) {
                npc.queue { npc.brotherCombat(this, b) }
            }
        }
    }

    private suspend fun Npc.brotherCombat(task: QueueTask, b: Barrows.Brother) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = b.range, projectile = b.range > 1) && isAttackDelayReady()) {
                when (b) {
                    Barrows.Brother.AHRIM -> ahrim(target)
                    Barrows.Brother.DHAROK -> dharok(target)
                    Barrows.Brother.GUTHAN -> guthan(target)
                    Barrows.Brother.KARIL -> karil(target)
                    Barrows.Brother.TORAG -> torag(target)
                    Barrows.Brother.VERAC -> verac(target)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.ahrim(target: Pawn) {
        animate(727)
        graphic(155, 92)
        bossProjectile(target, CombatClass.MAGIC, maxHit = 25, gfx = 156)
        if (world.chance(1, 4) && target is Player) {
            target.getSkills().alterCurrentLevel(Skills.STRENGTH, -5)
            target.graphic(400)
            target.message("Ahrim's curse saps your strength!")
        }
    }

    private fun Npc.dharok(target: Pawn) {
        animate(2067)
        val missing = (getMaxHp() - getCurrentHp()).coerceAtLeast(0)
        val maxHit = DHAROK_BASE + DHAROK_BASE * missing / getMaxHp().coerceAtLeast(1)
        bossMelee(target, maxHit = maxHit, style = CombatStyle.SLASH)
    }

    private fun Npc.guthan(target: Pawn) {
        animate(2080)
        prepareAttack(CombatClass.MELEE, CombatStyle.STAB, AttackStyle.AGGRESSIVE)
        val landed = MeleeCombatFormula.getAccuracy(this, target) >= world.randomDouble()
        val self = this
        dealHit(target = target, maxHit = 24, landHit = landed, delay = 0) { ph ->
            val dmg = ph.hit.hitmarks.sumOf { it.damage }
            if (dmg > 0 && world.chance(1, 4) && !self.isDead()) {
                target.graphic(398)
                self.setCurrentHp(minOf(self.getMaxHp(), self.getCurrentHp() + dmg))
            }
        }
    }

    private fun Npc.karil(target: Pawn) {
        animate(2075)
        bossProjectile(target, CombatClass.RANGED, maxHit = 20, gfx = 27)
        if (world.chance(1, 4) && target is Player) {
            val cur = target.getSkills().getCurrentLevel(Skills.AGILITY)
            val drain = cur / 5
            if (drain > 0) target.getSkills().alterCurrentLevel(Skills.AGILITY, -drain)
            target.graphic(401, 100)
            target.message("Karil's tainted bolt saps your agility!")
        }
    }

    private fun Npc.torag(target: Pawn) {
        animate(2068)
        bossMelee(target, maxHit = 24, style = CombatStyle.CRUSH)
        if (world.chance(1, 4) && target is Player) {
            target.graphic(399)
            target.runEnergy = (target.runEnergy - 2000.0).coerceAtLeast(0.0)
            target.sendRunEnergy(target.runEnergy.toInt())
        }
    }

    private fun Npc.verac(target: Pawn) {
        animate(2062)
        if (world.chance(1, 4)) {
            bossMelee(target, maxHit = 15, style = CombatStyle.CRUSH, ignoresPrayer = true)
        } else {
            bossMelee(target, maxHit = 23, style = CombatStyle.CRUSH)
        }
    }

    companion object {
        const val DHAROK_BASE = 29
    }
}

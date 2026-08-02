package org.alter.plugins.content.combat.strategy

import org.alter.api.ProjectileType
import org.alter.api.Skills
import org.alter.api.ext.freeze
import org.alter.api.ext.playSound
import org.alter.game.model.Graphic
import org.alter.game.model.Tile
import org.alter.game.model.combat.XpMode
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.CombatConfigs
import org.alter.plugins.content.combat.WeaponEffects
import org.alter.plugins.content.combat.createProjectile
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import org.alter.plugins.content.magic.MagicSpells

/**
 * @author Tom <rspsmods@gmail.com>
 */
object MagicCombatStrategy : CombatStrategy {
    override fun getAttackRange(pawn: Pawn): Int = 10

    override fun canAttack(
        pawn: Pawn,
        target: Pawn,
    ): Boolean {
        if (pawn is Player) {
            val spell = pawn.attr[Combat.CASTING_SPELL]!!
            val requirements = MagicSpells.getMetadata(spell.id)
            if (requirements != null && !MagicSpells.canCast(pawn, requirements.lvl, requirements.items, requirements.spellbook)) {
                return false
            }
        }
        return true
    }

    override fun attack(
        pawn: Pawn,
        target: Pawn,
    ) {
        val world = pawn.world

        val spell = pawn.attr[Combat.CASTING_SPELL]!!
        val projectile =
            pawn.createProjectile(
                target,
                gfx = spell.projectile,
                type = ProjectileType.MAGIC,
                endHeight = spell.projectilEndHeight,
            )

        pawn.animate(spell.castAnimation)
        spell.castGfx?.let { gfx -> pawn.graphic(gfx) }
        spell.impactGfx?.let { gfx -> target.graphic(Graphic(gfx.id, gfx.height, projectile.lifespan)) }
        if (spell.projectile > 0) {
            world.spawn(projectile)
        }

        if (pawn is Player) {
            if (spell.castSound != -1) {
                pawn.playSound(id = spell.castSound, volume = 1, delay = 0)
            }
            MagicSpells.getMetadata(spell.id)?.let { requirement -> MagicSpells.removeRunes(pawn, requirement.items) }
        }

        val formula = MagicCombatFormula
        val accuracy = formula.getAccuracy(pawn, target)
        val maxHit = formula.getMaxHit(pawn, target)
        val landHit = accuracy >= world.randomDouble()

        val hitDelay = getHitDelay(pawn.getCentreTile(), target.getCentreTile())
        val pawnHit =
            pawn.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = hitDelay) {
                WeaponEffects.applyOnHit(pawn, target, it)
                // Ancient ice spells root the target on a landed hit (freeze() self-guards re-freeze).
                if (landHit && spell.freezeTicks > 0) {
                    target.freeze(spell.freezeTicks)
                }
            }
        // Heal and XP happen when the hit LANDS (the projectile's arrival tick), from the
        // damage actually dealt. Splashes still award the base cast xp.
        pawnHit.hit.addAction {
            val damage = hitmarks.sumOf { it.damage }

            // Ancient blood spells heal the caster for a fraction of the damage dealt (the signature
            // "blood mage" sustain). Caps at the caster's max HP — no overheal in PvP.
            if (landHit && damage > 0 && spell in BLOOD_SPELLS) {
                val heal = (damage * BLOOD_HEAL_RATIO).toInt()
                if (heal > 0) {
                    pawn.setCurrentHp(minOf(pawn.getMaxHp(), pawn.getCurrentHp() + heal))
                }
            }

            if (pawn.entityType.isPlayer) {
                addCombatXp(pawn as Player, target, damage, spell)
            }
        }
    }

    /** Ancient blood spells: heal the caster for 25% of the damage they deal. */
    private const val BLOOD_HEAL_RATIO = 0.25
    private val BLOOD_SPELLS = setOf(
        CombatSpell.BLOOD_RUSH, CombatSpell.BLOOD_BURST, CombatSpell.BLOOD_BLITZ, CombatSpell.BLOOD_BARRAGE,
    )

    fun getHitDelay(
        start: Tile,
        target: Tile,
    ): Int {
        // OSRS magic hit delay: 1 + floor((1 + chebyshev distance) / 3) ticks.
        val distance = start.getChebyshevDistance(target)
        return 1 + Math.floor((1.0 + distance) / 3.0).toInt()
    }

    private fun addCombatXp(
        player: Player,
        target: Pawn,
        damage: Int,
        spell: CombatSpell,
    ) {
        val modDamage = damage
        val mode = CombatConfigs.getXpMode(player)
        val multiplier = if (target is Npc) Combat.getNpcXpMultiplier(target) else 1.0
        val baseXp = spell.baseXp

        // getXpMode() reports the *melee* xp mode for staves (bash/pound/focus), so the
        // defensive-cast decision can't hang off it — the old `mode == MAGIC` gate made
        // defensive casting unreachable for every staff. Check the defensive-cast state
        // directly; tridents on long range (SHARED) award the same split.
        if (mode == XpMode.SHARED || Combat.isCastingDefensively(player)) {
            player.addXp(Skills.MAGIC, (modDamage * 1.33 * multiplier) + baseXp)
            player.addXp(Skills.DEFENCE, modDamage * multiplier)
            player.addXp(Skills.HITPOINTS, modDamage * 1.33 * multiplier)
        } else {
            player.addXp(Skills.MAGIC, (modDamage * 2.0 * multiplier) + baseXp)
            player.addXp(Skills.HITPOINTS, modDamage * 1.33 * multiplier)
        }
    }
}

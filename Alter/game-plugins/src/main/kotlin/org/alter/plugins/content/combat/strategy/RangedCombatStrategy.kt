package org.alter.plugins.content.combat.strategy

import org.alter.api.EquipmentType
import org.alter.api.ProjectileType
import org.alter.api.Skills
import org.alter.api.WeaponType
import org.alter.api.ext.*
import org.alter.game.model.Tile
import org.alter.game.model.combat.AttackStyle
import org.alter.game.model.combat.PawnHit
import org.alter.game.model.combat.XpMode
import org.alter.game.model.entity.*
import org.alter.rscm.RSCM.getRSCM
import org.alter.api.NpcSpecies
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.CombatConfigs
import org.alter.plugins.content.combat.WeaponEffects
import org.alter.plugins.content.combat.createProjectile
import org.alter.plugins.content.combat.dealExactHit
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.formula.RangedCombatFormula
import org.alter.plugins.content.combat.strategy.ranged.BoltEnchantments
import org.alter.plugins.content.combat.strategy.ranged.RangedProjectile
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.plugins.content.combat.strategy.ranged.ammo.Darts
import org.alter.plugins.content.combat.strategy.ranged.ammo.Knives
import org.alter.plugins.content.combat.strategy.ranged.weapon.BowType
import org.alter.plugins.content.combat.strategy.ranged.weapon.Bows
import org.alter.plugins.content.combat.strategy.ranged.weapon.CrossbowType
import org.alter.plugins.content.bots.PkBot

/**
 * @author Tom <rspsmods@gmail.com>
 */
object RangedCombatStrategy : CombatStrategy {
    private const val DEFAULT_ATTACK_RANGE = 7

    private const val MAX_ATTACK_RANGE = 10

    override fun getAttackRange(pawn: Pawn): Int {
        if (pawn is Player) {
            val weapon = pawn.getEquipment(EquipmentType.WEAPON)
            val attackStyle = CombatConfigs.getAttackStyle(pawn)

            var range =
                when (weapon?.id) {
                    getRSCM("item.armadyl_crossbow") -> 8
                    getRSCM("item.craws_bow"), getRSCM("item.craws_bow_u") -> 10
                    getRSCM("item.chinchompa_10033"), getRSCM("item.red_chinchompa_10034"), getRSCM("item.black_chinchompa") -> 9
                    in Bows.LONG_BOWS -> 9
                    in Knives.KNIVES -> 4 // OSRS: throwing knives attack from 4 tiles
                    in Darts.DARTS -> 3
                    in Bows.CRYSTAL_BOWS -> 10
                    else -> DEFAULT_ATTACK_RANGE
                }

            if (attackStyle == AttackStyle.LONG_RANGE) {
                range += 2
            }

            return Math.min(MAX_ATTACK_RANGE, range)
        }
        return DEFAULT_ATTACK_RANGE
    }

    override fun canAttack(
        pawn: Pawn,
        target: Pawn,
    ): Boolean {
        if (pawn is Player) {
            val weapon = pawn.getEquipment(EquipmentType.WEAPON)
            val ammo = pawn.getEquipment(EquipmentType.AMMO)

            val crossbow = CrossbowType.values.firstOrNull { it.item == weapon?.id }
            if (crossbow != null && ammo?.id !in crossbow.ammo) {
                val message = if (ammo != null) "You can't use that ammo with your crossbow." else "There is no ammo left in your quiver."
                pawn.message(message)
                return false
            }

            val bow = BowType.values.firstOrNull { it.item == weapon?.id }
            if (bow != null && bow.ammo.isNotEmpty() && ammo?.id !in bow.ammo) {
                val message = if (ammo != null) "You can't use that ammo with your bow." else "There is no ammo left in your quiver."
                pawn.message(message)
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

        val animation = CombatConfigs.getAttackAnimation(pawn)
        /**
         * @TODO Refactor
         */
        if (target is Player) {
            when (pawn) {
                is Npc -> {
                    CombatConfigs.getCombatDef(pawn)!!.let {
                        if (it.defaultAttackSoundArea) {
                            world.spawn(
                                AreaSound(pawn.tile, it.defaultAttackSound, it.defaultAttackSoundRadius, it.defaultAttackSoundVolume),
                            )
                        } else {
                            target.playSound(pawn.combatDef.defaultAttackSound, pawn.combatDef.defaultAttackSoundVolume)
                        }
                    }
                }
                // @TODO later for player block sound.
            }
        }
        /*
         * A list of actions that will be executed upon this hit dealing damage
         * to the [target].
         */
        var ammoDropAction: ((PawnHit).() -> Unit) = {}

        if (pawn is Player) {
            /*
             * Get the [EquipmentType] for the ranged weapon you're using.
             */
            val ammoSlot =
                when {
                    pawn.hasWeaponType(WeaponType.THROWN) || pawn.hasWeaponType(WeaponType.CHINCHOMPA) -> EquipmentType.WEAPON
                    else -> EquipmentType.AMMO
                }

            val ammo = pawn.getEquipment(ammoSlot)

            /*
             * Create a projectile based on ammo.
             */
            val ammoProjectile = if (ammo != null) RangedProjectile.values.firstOrNull { ammo.id in it.items } else null
            if (ammoProjectile != null) {
                val projectile = pawn.createProjectile(target, ammoProjectile.gfx, ammoProjectile.type)
                ammoProjectile.drawback?.let { drawback -> pawn.graphic(drawback) }
                ammoProjectile.impact?.let { impact -> target.graphic(impact.id, impact.height, projectile.lifespan) }
                world.spawn(projectile)
            }

            /*
             * Remove or drop ammo if applicable. Bots and companions are exempt: their
             * quivers are dressing (bots spawn with a single arrow), and a companion's
             * dropped ammo lands owned by its uid where the owner can never reclaim it.
             */
            if (pawn !is PkBot && ammo != null && (ammoProjectile == null || !ammoProjectile.breakOnImpact())) {
                // Per-cape ammo outcome bands (one roll): Ava's assembler keeps 100% (it
                // previously still broke 20% because the break roll was independent);
                // accumulator keeps 72%, drops 8%, breaks 20%; no device drops 80%/breaks 20%.
                val chance = world.random(99)
                val breakAmmo: Boolean
                val dropAmmo: Boolean
                when {
                    pawn.hasEquipped(EquipmentType.CAPE, "item.avas_assembler") -> {
                        breakAmmo = false
                        dropAmmo = false
                    }
                    pawn.hasEquipped(EquipmentType.CAPE, "item.avas_accumulator") -> {
                        breakAmmo = chance in 0..19
                        dropAmmo = chance in 20..27
                    }
                    else -> {
                        breakAmmo = chance in 0..19
                        dropAmmo = !breakAmmo
                    }
                }

                val amount = 1
                if (breakAmmo || dropAmmo) {
                    pawn.equipment.remove(ammo.id, amount)
                }
                if (dropAmmo) {
                    ammoDropAction = { world.spawn(GroundItem(ammo.id, amount, target.tile, pawn)) }
                }
            }
        }
        // Generic ranged NPCs fire the projectile their combat def declares.
        if (pawn is Npc && pawn.combatDef.projectile != -1) {
            val projectile = pawn.createProjectile(target, gfx = pawn.combatDef.projectile, type = ProjectileType.ARROW)
            world.spawn(projectile)
            if (pawn.combatDef.impactGfx != -1) {
                target.graphic(pawn.combatDef.impactGfx, 92, projectile.lifespan)
            }
        }
        pawn.animate(animation)

        val formula = RangedCombatFormula
        val accuracy = formula.getAccuracy(pawn, target)
        var maxHit = formula.getMaxHit(pawn, target)
        var landHit = accuracy >= world.randomDouble()
        val hitDelay = getHitDelay(pawn.getCentreTile(), target.tile.transform(target.getSize() / 2, target.getSize() / 2))

        // Enchanted-bolt procs, rolled per shot. Ruby and Diamond bypass the accuracy
        // roll; the additive gem effects only matter when the shot lands.
        var rubyProc = false
        var boltEffect: BoltEnchantments.Effect? = null
        if (pawn is Player && pawn.hasWeaponType(WeaponType.CROSSBOW)) {
            val effect = BoltEnchantments.effectFor(pawn)
            if (effect != null && world.random(99) < effect.procPercent) {
                boltEffect = effect
                val rangedLvl = pawn.getSkills().getCurrentLevel(Skills.RANGED)
                when (effect) {
                    BoltEnchantments.Effect.RUBY -> {
                        rubyProc = true
                        landHit = true
                    }
                    BoltEnchantments.Effect.DIAMOND -> {
                        landHit = true
                        maxHit = (maxHit * 1.15).toInt()
                    }
                    BoltEnchantments.Effect.OPAL -> maxHit += rangedLvl / 10
                    BoltEnchantments.Effect.PEARL -> maxHit += rangedLvl / (if (isFiery(target)) 15 else 20)
                    BoltEnchantments.Effect.DRAGONSTONE -> if (!isFiery(target)) maxHit += rangedLvl / 5
                    BoltEnchantments.Effect.ONYX -> maxHit = (maxHit * 1.2).toInt()
                    else -> {}
                }
            }
        }

        val pawnHit =
            if (rubyProc && pawn is Player &&
                // OSRS: Blood Forfeit never procs while the caster is at or below 10% HP —
                // you cannot ruby-bolt yourself to death.
                pawn.getCurrentHp() * 10 > pawn.getMaxHp()
            ) {
                // Ruby "Blood Forfeit": the damage roll is replaced with 20% of the
                // target's current HP (capped at 100), and the caster sacrifices 10%
                // of their own current HP.
                val sacrifice = BoltEnchantments.rubySacrifice(pawn.getCurrentHp())
                if (sacrifice > 0) {
                    pawn.setCurrentHp(pawn.getCurrentHp() - sacrifice)
                }
                pawn.dealExactHit(
                    target = target,
                    damage = BoltEnchantments.rubyDamage(target.getCurrentHp()),
                    delay = hitDelay,
                    onHit = {
                        ammoDropAction(it)
                        WeaponEffects.applyOnHit(pawn, target, it)
                    },
                )
            } else {
                pawn.dealHit(
                    target = target,
                    maxHit = maxHit,
                    landHit = landHit,
                    delay = hitDelay,
                    onHit = {
                        ammoDropAction(it)
                        WeaponEffects.applyOnHit(pawn, target, it)
                    },
                )
            }

        // Bolt effects that trigger on the landing tick.
        if (boltEffect != null && pawn is Player) {
            val effect = boltEffect
            pawnHit.hit.addAction {
                val damage = hitmarks.sumOf { it.damage }
                when (effect) {
                    BoltEnchantments.Effect.EMERALD ->
                        if (damage > 0) {
                            // OSRS: emerald bolts' Magical Poison starts at 5.
                            Poison.poison(target, initialDamage = 5)
                        }
                    BoltEnchantments.Effect.ONYX ->
                        if (damage > 0) {
                            pawn.heal(damage / 4)
                        }
                    BoltEnchantments.Effect.SAPPHIRE ->
                        if (damage > 0 && target is Player) {
                            val drain = target.getSkills().getCurrentLevel(Skills.PRAYER) / 20
                            if (drain > 0) {
                                target.getSkills().decrementCurrentLevel(Skills.PRAYER, drain, capped = false)
                                // OSRS: the caster regains a quarter of what was drained.
                                pawn.getSkills().alterCurrentLevel(Skills.PRAYER, drain / 4)
                            }
                        }
                    else -> {}
                }
            }
        }

        // XP is awarded when the hit lands (the projectile's arrival tick), from the
        // damage actually dealt (hitmarks are clamped to remaining HP at application).
        pawnHit.hit.addAction {
            val damage = hitmarks.sumOf { it.damage }
            if (damage > 0 && pawn.entityType.isPlayer) {
                addCombatXp(pawn as Player, target, damage)
            }
        }
    }

    private fun isFiery(pawn: Pawn): Boolean = pawn is Npc && pawn.isSpecies(NpcSpecies.FIERY)

    fun getHitDelay(
        start: Tile,
        target: Tile,
    ): Int {
        // OSRS ranged hit delay: 1 + floor((3 + chebyshev distance) / 6) ticks.
        val distance = start.getChebyshevDistance(target)
        return 1 + (Math.floor((3.0 + distance) / 6.0)).toInt()
    }

    private fun addCombatXp(
        player: Player,
        target: Pawn,
        damage: Int,
    ) {
        val modDamage = damage
        val mode = CombatConfigs.getXpMode(player)
        val multiplier = Combat.COMBAT_XP_MULTIPLIER * (if (target is Npc) Combat.getNpcXpMultiplier(target) else 1.0)

        if (mode == XpMode.SHARED) {
            player.addXp(Skills.RANGED, modDamage * 2.0 * multiplier)
            player.addXp(Skills.DEFENCE, modDamage * 2.0 * multiplier)
            player.addXp(Skills.HITPOINTS, modDamage * 1.33 * multiplier)
        } else {
            // Any non-longrange style trains Ranged; falling through silently awarded nothing
            // when the weapon's xp-mode table reported a melee mode (e.g. salamanders).
            player.addXp(Skills.RANGED, modDamage * 4.0 * multiplier)
            player.addXp(Skills.HITPOINTS, modDamage * 1.33 * multiplier)
        }
    }
}

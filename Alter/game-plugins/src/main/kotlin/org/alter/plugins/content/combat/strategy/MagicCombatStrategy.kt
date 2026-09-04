package org.alter.plugins.content.combat.strategy

import org.alter.api.NpcSkills
import org.alter.api.PrayerIcon
import org.alter.api.ProjectileType
import org.alter.api.Skills
import org.alter.api.ext.freeze
import org.alter.api.ext.hasPrayerIcon
import org.alter.api.ext.isMulti
import org.alter.api.ext.message
import org.alter.api.ext.playSound
import org.alter.game.model.Graphic
import org.alter.game.model.Tile
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.XpMode
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.entity.isPlayerAttackable
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.CombatConfigs
import org.alter.plugins.content.combat.WeaponEffects
import org.alter.plugins.content.combat.createProjectile
import org.alter.plugins.content.combat.currentCombatStat
import org.alter.plugins.content.combat.dealHit
import org.alter.plugins.content.combat.drainCombatStat
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import org.alter.plugins.content.combat.strategy.magic.PoweredStaves
import org.alter.plugins.content.magic.MagicSpells
import org.alter.plugins.content.mechanics.poison.Poison
import kotlin.math.abs

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
            // Powered staves are PvM-only, as in OSRS ("This staff's spell cannot be used
            // against other players"). They have no spellbook metadata (no runes/level row),
            // so the requirements check below naturally skips them.
            if (spell in PoweredStaves.SPELLS && target is Player) {
                pawn.message("This staff's spell cannot be used against other players.")
                return false
            }
            val requirements = MagicSpells.getMetadata(spell.id)
            if (requirements != null && !MagicSpells.canCast(pawn, requirements.lvl, requirements.items, requirements.spellbook, spellName = requirements.name)) {
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

        val spell = pawn.attr[Combat.CASTING_SPELL]
        if (spell == null) {
            // Generic magic NPCs (combat-def class MAGIC) have no CombatSpell — they cast a
            // plain magic attack: def animation + projectile, formula accuracy, level-derived
            // max hit, full hit-pipeline bookkeeping via dealHit. Players never reach here
            // without a spell (the combat class requires CASTING_SPELL).
            if (pawn is Npc) {
                genericNpcAttack(pawn, target)
            }
            return
        }
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
            MagicSpells.getMetadata(spell.id)?.let { requirement -> MagicSpells.removeRunes(pawn, requirement.items, spellName = requirement.name) }
        }

        val formula = MagicCombatFormula
        val accuracy = formula.getAccuracy(pawn, target)
        val maxHit = formula.getMaxHit(pawn, target)
        val landHit = accuracy >= world.randomDouble()

        val hitDelay = getHitDelay(pawn.getCentreTile(), target.getCentreTile())
        val pawnHit =
            pawn.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = hitDelay) {
                WeaponEffects.applyOnHit(pawn, target, it, combatClass = CombatClass.MAGIC)
                // Ancient ice spells root the target on a landed hit (freeze() self-guards
                // re-freeze and the 5-tick post-thaw immunity). In PvP, Protect from Magic
                // halves the freeze duration — read at hit application, as in OSRS.
                if (landHit && spell.freezeTicks > 0) {
                    var freezeTicks = spell.freezeTicks
                    if (pawn is Player && target is Player && target.hasPrayerIcon(PrayerIcon.PROTECT_FROM_MAGIC)) {
                        freezeTicks /= 2
                    }
                    target.freeze(freezeTicks)
                }
                // Smoke spells: 1-in-5 chance to poison (rush/burst at 2, blitz/barrage at 4).
                if (landHit && spell in SMOKE_SPELLS && pawn.world.chance(1, 5)) {
                    Poison.poison(target, initialDamage = if (spell in SMOKE_STRONG) 4 else 2)
                }
                // Shadow spells: drain the target's Attack (10% rush/burst, 15% blitz/barrage).
                if (landHit && spell in SHADOW_SPELLS) {
                    val pct = if (spell in SHADOW_STRONG) 0.15 else 0.10
                    val current = target.currentCombatStat(Skills.ATTACK, NpcSkills.ATTACK)
                    target.drainCombatStat(Skills.ATTACK, NpcSkills.ATTACK, (current * pct).toInt())
                }
                // Trident of the swamp / toxic staff venom rolls live in WeaponPoisons (via
                // WeaponEffects.applyOnHit above) so the toxic weapons share one rule set.
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

            // Sanguinesti staff: 1-in-6 chance to heal the caster for half the damage dealt.
            if (landHit && damage > 0 && spell == CombatSpell.SANGUINESTI_STAFF && pawn.world.chance(1, 6)) {
                val heal = damage / 2
                if (heal > 0) {
                    pawn.setCurrentHp(minOf(pawn.getMaxHp(), pawn.getCurrentHp() + heal))
                }
            }

            if (pawn.entityType.isPlayer) {
                addCombatXp(pawn as Player, target, damage, spell)
            }
        }

        // Bursts and barrages hit every other target in the 3x3 around the primary target,
        // but only in multi-combat (OSRS). Each secondary gets an independent accuracy roll
        // and carries the same freeze/poison/drain/heal effects.
        if (pawn is Player && spell in AOE_SPELLS &&
            org.alter.plugins.content.combat.PvpZones.isMultiCombat(target.tile, world)
        ) {
            val extras = ArrayList<Pawn>()
            val t = target.tile
            // The 3x3 splash box (SW corner + 3 wide/long). Size-aware overlap: a big npc that
            // merely overlaps the box counts, not only one whose SW tile sits inside it (the old
            // radius-1 test on the SW tile missed 2x2+ monsters standing beside the target).
            val boxX = t.x - 1
            val boxZ = t.z - 1
            world.npcs.forEach { npc ->
                if (npc == null || npc === target || npc.index < 0) return@forEach
                // Cheapest tests first — this is a world-wide scan per cast (the chunk npc index
                // is never populated), so plane + a coarse box reject before any def lookup.
                if (npc.tile.height != t.height) return@forEach
                if (abs(npc.tile.x - t.x) > AOE_SCAN_RADIUS || abs(npc.tile.z - t.z) > AOE_SCAN_RADIUS) return@forEach
                val size = npc.getSize()
                if (!Combat.areOverlapping(boxX, boxZ, 3, 3, npc.tile.x, npc.tile.z, size, size)) return@forEach
                if (npc.isDead() || !npc.isPlayerAttackable() || npc.combatDef.hitpoints == -1) return@forEach
                // quiet: a refusal for a bystander must not print a chat line per cast.
                if (!Combat.canEngage(pawn, npc, quiet = true)) return@forEach
                extras.add(npc)
            }
            world.players.forEach { other ->
                if (other == null || other === pawn || other === target || other.isDead()) return@forEach
                if (other.tile.height != t.height || !other.tile.isWithinRadius(t, 1)) return@forEach
                if (!Combat.canEngage(pawn, other, quiet = true)) return@forEach
                extras.add(other)
            }
            extras.forEach { victim -> castAoeHit(pawn, victim, spell, world) }
        }
    }

    /** An independent burst/barrage splash on a secondary [victim] (multi-combat only). */
    private fun castAoeHit(pawn: Player, victim: Pawn, spell: CombatSpell, world: org.alter.game.model.World) {
        if (spell.projectile > 0) {
            world.spawn(pawn.createProjectile(victim, gfx = spell.projectile, type = ProjectileType.MAGIC, endHeight = spell.projectilEndHeight))
        }
        spell.impactGfx?.let { gfx -> victim.graphic(Graphic(gfx.id, gfx.height)) }
        val accuracy = MagicCombatFormula.getAccuracy(pawn, victim)
        val maxHit = MagicCombatFormula.getMaxHit(pawn, victim)
        val landHit = accuracy >= world.randomDouble()
        val hitDelay = getHitDelay(pawn.getCentreTile(), victim.getCentreTile())
        val pawnHit = pawn.dealHit(target = victim, maxHit = maxHit, landHit = landHit, delay = hitDelay) {
            if (landHit && spell.freezeTicks > 0) {
                var ticks = spell.freezeTicks
                if (victim is Player && victim.hasPrayerIcon(PrayerIcon.PROTECT_FROM_MAGIC)) ticks /= 2
                victim.freeze(ticks)
            }
            if (landHit && spell in SMOKE_SPELLS && world.chance(1, 5)) {
                Poison.poison(victim, initialDamage = if (spell in SMOKE_STRONG) 4 else 2)
            }
            if (landHit && spell in SHADOW_SPELLS) {
                val pct = if (spell in SHADOW_STRONG) 0.15 else 0.10
                val current = victim.currentCombatStat(Skills.ATTACK, NpcSkills.ATTACK)
                victim.drainCombatStat(Skills.ATTACK, NpcSkills.ATTACK, (current * pct).toInt())
            }
        }
        pawnHit.hit.addAction {
            val damage = hitmarks.sumOf { it.damage }
            if (landHit && damage > 0 && spell in BLOOD_SPELLS) {
                val heal = (damage * BLOOD_HEAL_RATIO).toInt()
                if (heal > 0) pawn.setCurrentHp(minOf(pawn.getMaxHp(), pawn.getCurrentHp() + heal))
            }
            addCombatXp(pawn, victim, damage, spell)
        }
    }

    /** Coarse pre-filter for the AoE scan: max npc size (5) so any overlap with the 3x3 survives. */
    private const val AOE_SCAN_RADIUS = 5

    /** Bursts + barrages hit a 3x3 in multi-combat. */
    private val AOE_SPELLS = setOf(
        CombatSpell.ICE_BURST, CombatSpell.ICE_BARRAGE,
        CombatSpell.BLOOD_BURST, CombatSpell.BLOOD_BARRAGE,
        CombatSpell.SMOKE_BURST, CombatSpell.SMOKE_BARRAGE,
        CombatSpell.SHADOW_BURST, CombatSpell.SHADOW_BARRAGE,
    )

    /** Ancient blood spells: heal the caster for 25% of the damage they deal. */
    private const val BLOOD_HEAL_RATIO = 0.25
    private val BLOOD_SPELLS = setOf(
        CombatSpell.BLOOD_RUSH, CombatSpell.BLOOD_BURST, CombatSpell.BLOOD_BLITZ, CombatSpell.BLOOD_BARRAGE,
    )
    private val SMOKE_SPELLS = setOf(
        CombatSpell.SMOKE_RUSH, CombatSpell.SMOKE_BURST, CombatSpell.SMOKE_BLITZ, CombatSpell.SMOKE_BARRAGE,
    )
    private val SMOKE_STRONG = setOf(CombatSpell.SMOKE_BLITZ, CombatSpell.SMOKE_BARRAGE)
    private val SHADOW_SPELLS = setOf(
        CombatSpell.SHADOW_RUSH, CombatSpell.SHADOW_BURST, CombatSpell.SHADOW_BLITZ, CombatSpell.SHADOW_BARRAGE,
    )
    private val SHADOW_STRONG = setOf(CombatSpell.SHADOW_BLITZ, CombatSpell.SHADOW_BARRAGE)

    private fun genericNpcAttack(pawn: Npc, target: Pawn) {
        val world = pawn.world
        pawn.animate(CombatConfigs.getAttackAnimation(pawn))
        var lifespan = 0
        if (pawn.combatDef.projectile != -1) {
            val projectile = pawn.createProjectile(target, gfx = pawn.combatDef.projectile, type = ProjectileType.MAGIC)
            world.spawn(projectile)
            lifespan = projectile.lifespan
        }
        if (pawn.combatDef.impactGfx != -1) {
            target.graphic(Graphic(pawn.combatDef.impactGfx, 92, lifespan))
        }
        val accuracy = MagicCombatFormula.getAccuracy(pawn, target)
        // Level-derived max hit for generic casters: floor(0.5 + (magic + 9) * 64 / 640),
        // i.e. roughly a tenth of the magic level — bespoke bosses override with their own
        // combat plugins when they need exact wiki max hits.
        val maxHit = Math.floor(0.5 + (pawn.stats.getCurrentLevel(NpcSkills.MAGIC) + 9.0) * 64.0 / 640.0).toInt().coerceAtLeast(1)
        val landHit = accuracy >= world.randomDouble()
        pawn.dealHit(target = target, maxHit = maxHit, landHit = landHit, delay = getHitDelay(pawn.getCentreTile(), target.getCentreTile()))
    }

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
        val multiplier = Combat.COMBAT_XP_MULTIPLIER * (if (target is Npc) Combat.getNpcXpMultiplier(target) else 1.0)
        val baseXp = spell.baseXp * Combat.COMBAT_XP_MULTIPLIER

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

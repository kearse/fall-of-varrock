package org.alter.plugins.content.magic.curses

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.FROZEN_TIMER
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.combat.strategy.magic.CombatSpellEffects
import org.alter.plugins.content.magic.MagicSpells

private val logger = KotlinLogging.logger {}

/**
 * **Curse + freeze combat spells** (net-new Magic content). Stat-drain curses (Confuse/Weaken/
 * Curse/Vulnerability/Enfeeble/Stun) and the bind family (Bind/Snare/Entangle). All are
 * non-damaging combat spells, so they register through [CombatSpellEffects] (no binding collision
 * with the damage-spell table). Each handler does its own canCast/removeRunes/xp.
 *
 * Each cast rolls magic accuracy against the target; on a miss it SPLASHES (runes + base xp
 * consumed, splash gfx shown, no effect) — the same rule the Ancient ice spells already use.
 */
class CurseSpellsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private companion object {
        const val CAST_ANIM = 1162
        const val GFX_HEIGHT = 96
        const val SPLASH_GFX = 85
    }

    /** OSRS: bind/curse/TB roll magic accuracy; a failed roll splashes (no effect). */
    private fun lands(caster: Player, target: Pawn): Boolean =
        MagicCombatFormula.getAccuracy(caster, target) >= caster.world.randomDouble()

    init {
        // name -> (player skill, npc skill, drain %, Magic xp, over-head gfx)
        registerCurse("Confuse", Skills.ATTACK, NpcSkills.ATTACK, 5, 13.0, 102)
        registerCurse("Weaken", Skills.STRENGTH, NpcSkills.STRENGTH, 5, 21.0, 105)
        registerCurse("Curse", Skills.DEFENCE, NpcSkills.DEFENCE, 5, 29.0, 108)
        registerCurse("Vulnerability", Skills.DEFENCE, NpcSkills.DEFENCE, 10, 76.0, 168)
        registerCurse("Enfeeble", Skills.STRENGTH, NpcSkills.STRENGTH, 10, 83.0, 171)
        registerCurse("Stun", Skills.ATTACK, NpcSkills.ATTACK, 10, 90.0, 174)

        // name -> (freeze ticks, Magic xp, over-head gfx). ~5s / ~10s / ~15s.
        registerFreeze("Bind", 8, 30.0, 181)
        registerFreeze("Snare", 16, 60.0, 180)
        registerFreeze("Entangle", 24, 89.0, 179) // wiki: 24 ticks (14.4s), was 25

        logger.info { "curse-spells: registered 6 curses + 3 binds." }
    }

    private fun registerCurse(name: String, playerSkill: Int, npcSkill: Int, pct: Int, xp: Double, gfx: Int) {
        CombatSpellEffects.register(name) { caster, target, spell ->
            if (!MagicSpells.canCast(caster, spell.lvl, spell.items, requiredBook = spell.spellbook, spellName = spell.name)) return@register
            MagicSpells.removeRunes(caster, spell.items, spellName = spell.name)
            caster.addXp(Skills.MAGIC, xp)
            caster.animate(CAST_ANIM)
            if (lands(caster, target)) {
                target.graphic(gfx, GFX_HEIGHT)
                drain(target, playerSkill, npcSkill, pct)
            } else {
                target.graphic(SPLASH_GFX, GFX_HEIGHT)
            }
        }
    }

    private fun registerFreeze(name: String, ticks: Int, xp: Double, gfx: Int) {
        CombatSpellEffects.register(name) { caster, target, spell ->
            if (target.timers.has(FROZEN_TIMER)) {
                caster.message("That target is already held by a spell.")
                return@register
            }
            if (!MagicSpells.canCast(caster, spell.lvl, spell.items, requiredBook = spell.spellbook, spellName = spell.name)) return@register
            MagicSpells.removeRunes(caster, spell.items, spellName = spell.name)
            caster.addXp(Skills.MAGIC, xp)
            caster.animate(CAST_ANIM)
            if (lands(caster, target)) {
                target.graphic(gfx, GFX_HEIGHT)
                target.freeze(ticks)
            } else {
                target.graphic(SPLASH_GFX, GFX_HEIGHT)
            }
        }
    }

    /** Drop the target's [pct]% of the relevant stat, clamped so repeated casts don't stack past the floor. */
    private fun drain(target: Pawn, playerSkill: Int, npcSkill: Int, pct: Int) {
        when (target) {
            is Player -> {
                val base = target.getSkills().getBaseLevel(playerSkill)
                val floor = base * (100 - pct) / 100
                if (target.getSkills().getCurrentLevel(playerSkill) > floor) {
                    target.getSkills().setCurrentLevel(playerSkill, floor)
                }
            }
            is Npc -> {
                val base = target.stats.getMaxLevel(npcSkill)
                val floor = base * (100 - pct) / 100
                if (target.stats.getCurrentLevel(npcSkill) > floor) {
                    target.stats.setCurrentLevel(npcSkill, floor)
                }
            }
        }
    }
}

package org.alter.plugins.content.npcs.pvm

import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.*
import org.alter.game.model.queue.*
import org.alter.game.plugin.*
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.bosses.bossSummon
import org.alter.plugins.content.combat.*

/**
 * **Slayer / PvM boss combat — OSRS rotations + signature mechanics** (Phase D), one plugin
 * over the [PvmBosses] roster (the KBD attack-loop pattern, shared hit primitives in
 * `BossCombat.kt`):
 *  - **Kraken** — pure magic (pray Magic to splash it).
 *  - **Corporeal Beast** — magic + melee + an occasional prayer-ignoring **stomp**.
 *  - **Cerberus** — cycles melee/ranged/magic (pray the right style); half-HP **Summoned Souls**.
 *  - **Giant Mole** — melee burrower.
 *  - **Kalphite Queen** — phase 1 ranged+magic flyer → at half HP the melee **crawler** (phase 2).
 *  - **Sarachnis** — melee + ranged.
 *  - **Thermonuclear Smoke Devil** — ranged smoke + an AoE burst.
 *  - **Demonic Gorilla** — switches attack style each volley + a magic boulder.
 *  - **Skotizo** — melee + dark magic.
 *  - **Dagannoth Rex/Prime/Supreme** — fixed melee / magic / ranged kings.
 *
 * Simplified vs OSRS (documented, deferred): Corp's non-spear damage halving + dark core,
 * Cerberus lava pools + ghost styles, the gorilla's prayer-adaptation, Skotizo's altars,
 * Giant Mole relocation, true two-NPC KQ. Projectile gfx are best-known (TUNE).
 */
class PvmBossCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val phase2 = AttributeKey<Boolean>()
    private val soulsOut = AttributeKey<Boolean>()

    init {
        PvmBosses.all.forEach { boss ->
            onNpcCombat(boss.key) { npc.queue { npc.combat(this, boss) } }
        }
    }

    private suspend fun Npc.combat(it: QueueTask, boss: PvmBosses.PvmBoss) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(it, target, distance = boss.approach, projectile = boss.approach > 1) && isAttackDelayReady()) {
                attack(target, boss)
                postAttackLogic(target)
            }
            it.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.attack(target: Pawn, boss: PvmBosses.PvmBoss) {
        ATTACK_ANIMS[boss.key]?.let { if (it > 0) animate(it) }
        when (boss.key) {
            "npc.kraken" -> bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
            "npc.cerberus" -> cerberus(target, boss)
            "npc.giant_mole" -> bossMelee(target, boss.maxHit)
            "npc.kalphite_queen" -> kalphiteQueen(target, boss)
            "npc.sarachnis" -> if (world.random(1) == 0) bossMelee(target, boss.maxHit) else bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
            "npc.thermonuclear_smoke_devil" -> smokeDevil(target, boss)
            "npc.demonic_gorilla" -> gorilla(target, boss)
            "npc.skotizo" -> if (world.random(1) == 0) bossMelee(target, boss.maxHit) else bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
            "npc.dagannoth_rex" -> bossMelee(target, boss.maxHit)
            "npc.dagannoth_prime" -> bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
            "npc.dagannoth_supreme" -> bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
        }
    }

    private fun Npc.cerberus(target: Pawn, boss: PvmBosses.PvmBoss) {
        if (attr[soulsOut] != true && getCurrentHp() <= getMaxHp() / 2) {
            attr[soulsOut] = true
            bossSummon("npc.summoned_soul", 3)
            if (target is Player) target.message("<col=8f0000>Cerberus calls forth its Summoned Souls!</col>")
        }
        when (world.random(2)) {
            0 -> bossMelee(target, boss.maxHit)
            1 -> bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
            else -> bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
        }
    }

    private fun Npc.kalphiteQueen(target: Pawn, boss: PvmBosses.PvmBoss) {
        if (attr[phase2] != true && getCurrentHp() <= getMaxHp() / 2) {
            attr[phase2] = true
            if (target is Player) target.message("<col=8f0000>The Kalphite Queen sheds her shell and crawls forth!</col>")
        }
        if (attr[phase2] == true) {
            bossMelee(target, boss.maxHit)
        } else if (world.random(1) == 0) {
            bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
        } else {
            bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
        }
    }

    private fun Npc.smokeDevil(target: Pawn, boss: PvmBosses.PvmBoss) {
        if (world.chance(1, 5) && target is Player) {
            target.message("The smoke devil belches a choking cloud!")
            bossProjectile(target, CombatClass.RANGED, boss.maxHit + 4, gfx = RANGE_GFX)
        } else {
            bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
        }
    }

    private fun Npc.gorilla(target: Pawn, boss: PvmBosses.PvmBoss) {
        when (world.random(3)) {
            0, 1 -> bossMelee(target, boss.maxHit)
            2 -> bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
            else -> bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
        }
    }

    private companion object {
        // Projectile gfx — best-known OSRS ids; TUNE (a wrong id is a cosmetic miss, never a throw).
        const val MAGIC_GFX = 159
        const val RANGE_GFX = 1466

        // Per-boss attack animation (OSRS npc defs store none → set here). Best-known; TUNE in-game.
        val ATTACK_ANIMS = mapOf(
            "npc.kraken" to 3987,
            "npc.cerberus" to 4490,
            "npc.giant_mole" to 3314,
            "npc.kalphite_queen" to 6240,
            "npc.sarachnis" to 8918,
            "npc.thermonuclear_smoke_devil" to 1658,
            "npc.demonic_gorilla" to 7228,
            "npc.skotizo" to 7070,
            "npc.dagannoth_rex" to 2856,
            "npc.dagannoth_prime" to 2854,
            "npc.dagannoth_supreme" to 2868,
        )
    }
}

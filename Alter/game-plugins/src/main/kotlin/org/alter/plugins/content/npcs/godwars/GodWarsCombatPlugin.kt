package org.alter.plugins.content.npcs.godwars

import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.*
import org.alter.game.model.queue.*
import org.alter.game.plugin.*
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.combat.*

/**
 * **God Wars general combat** (Phase F) — each general alternates between its two OSRS attack
 * styles (the KBD attack-loop + the shared `BossCombat.kt` hit primitives, so protection
 * prayers block the matching style):
 *  - **General Graardor** — melee / ranged
 *  - **K'ril Tsutsaroth** — melee / magic (his magic can't be fully prayed in OSRS; simplified)
 *  - **Kree'arra** — ranged / magic (flying — never melee)
 *  - **Commander Zilyana** — melee / magic
 *
 * Bodyguards fight via the default strategy (see [GodWarsPlugin]). Projectile gfx are TUNE.
 */
class GodWarsCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        GodWarsBosses.all.forEach { boss ->
            onNpcCombat(boss.key) { npc.queue { npc.combat(this, boss) } }
        }
    }

    private suspend fun Npc.combat(it: QueueTask, boss: GodWarsBosses.GwdBoss) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(it, target, distance = boss.approach, projectile = boss.approach > 1) && isAttackDelayReady()) {
                ATTACK_ANIMS[boss.key]?.let { a -> if (a > 0) animate(a) }
                val style = if (world.random(1) == 0) boss.style1 else boss.style2
                when (style) {
                    CombatClass.MELEE -> bossMelee(target, boss.maxHit)
                    CombatClass.RANGED -> bossProjectile(target, CombatClass.RANGED, boss.maxHit, gfx = RANGE_GFX)
                    CombatClass.MAGIC -> bossProjectile(target, CombatClass.MAGIC, boss.maxHit, gfx = MAGIC_GFX)
                }
                postAttackLogic(target)
            }
            it.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private companion object {
        const val MAGIC_GFX = 159
        const val RANGE_GFX = 1466

        // Per-general attack animation (OSRS npc defs store none → set here). Best-known; TUNE in-game.
        val ATTACK_ANIMS = mapOf(
            "npc.general_graardor" to 7018,
            "npc.kril_tsutsaroth" to 6948,
            "npc.kreearra_3162" to 6980,
            "npc.commander_zilyana" to 6967,
        )
    }
}

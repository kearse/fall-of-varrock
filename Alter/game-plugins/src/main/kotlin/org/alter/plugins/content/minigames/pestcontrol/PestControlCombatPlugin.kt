package org.alter.plugins.content.minigames.pestcontrol

import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.move.moveTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.combat.*
import org.alter.rscm.RSCM.getRSCM

/**
 * Pest behaviours from the donor: **torchers** cast from six tiles (magic, gfx 647),
 * **defilers** fling from eight (ranged, gfx 657) or bite up close, **spinners** heal an open
 * portal beside them instead of fighting, **shifters** blink to your side one attack in four.
 * Splatters, ravagers and brawlers use the plain melee handler; the splatter's burst on death
 * lives in [PestControlPlugin].
 */
class PestControlCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val openPortalIds by lazy { PestControl.PORTALS.map { getRSCM(it.open) }.toSet() }

    init {
        PestControl.ALL_TORCHERS.forEach { key -> onNpcCombat(key) { npc.queue { npc.ranged(this, CombatClass.MAGIC, 6, 647) } } }
        PestControl.ALL_DEFILERS.forEach { key -> onNpcCombat(key) { npc.queue { npc.ranged(this, CombatClass.RANGED, 8, 657) } } }
        PestControl.ALL_SPINNERS.forEach { key -> onNpcCombat(key) { npc.queue { npc.spinner(this) } } }
        PestControl.ALL_SHIFTERS.forEach { key -> onNpcCombat(key) { npc.queue { npc.shifter(this) } } }
    }

    private fun Npc.maxHit(): Int = combatDef.hitpoints / 10 + 3

    private suspend fun Npc.ranged(task: QueueTask, cls: CombatClass, range: Int, gfx: Int) {
        var target = getCombatTarget() ?: return
        val anim = combatDef.attackAnimation
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = range, projectile = true) && isAttackDelayReady()) {
                animate(anim)
                if (cls == CombatClass.RANGED && tile.isWithinRadius(target.tile, 1) && world.chance(1, 2)) bossMelee(target, maxHit = maxHit(), style = CombatStyle.STAB)
                else bossProjectile(target, cls, maxHit = maxHit(), gfx = gfx)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private suspend fun Npc.spinner(task: QueueTask) {
        var target = getCombatTarget() ?: return
        val anim = combatDef.attackAnimation
        while (canEngageCombat(target)) {
            val portal = nearbyOpenPortal()
            if (portal != null) {
                facePawn(portal)
                if (isAttackDelayReady()) {
                    animate(anim)
                    portal.setCurrentHp(minOf(portal.getMaxHp(), portal.getCurrentHp() + 5 + world.random(5)))
                    postAttackLogic(portal)
                }
            } else {
                facePawn(target)
                if (moveToAttackRange(task, target, distance = 1, projectile = false) && isAttackDelayReady()) {
                    animate(anim)
                    bossMelee(target, maxHit = maxHit(), style = CombatStyle.CRUSH)
                    postAttackLogic(target)
                }
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.nearbyOpenPortal(): Npc? {
        for (n in world.npcs.entries) {
            if (n == null) continue
            if (n.id in openPortalIds && !n.isDead() && n.tile.height == tile.height && n.tile.isWithinRadius(tile, 2)) return n
        }
        return null
    }

    private suspend fun Npc.shifter(task: QueueTask) {
        var target = getCombatTarget() ?: return
        val anim = combatDef.attackAnimation
        while (canEngageCombat(target)) {
            facePawn(target)
            if (!tile.isWithinRadius(target.tile, 1) && world.chance(1, 4)) {
                blinkTo(target)
            }
            if (moveToAttackRange(task, target, distance = 1, projectile = false) && isAttackDelayReady()) {
                animate(anim)
                bossMelee(target, maxHit = maxHit(), style = CombatStyle.SLASH)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.blinkTo(target: Pawn) {
        val t = target.tile
        val candidates = listOf(Tile(t.x + 1, t.z, t.height), Tile(t.x - 1, t.z, t.height), Tile(t.x, t.z + 1, t.height), Tile(t.x, t.z - 1, t.height))
        val to = candidates[world.random(candidates.size - 1)]
        graphic(1305, 0)
        moveTo(world.snapToWalkable(to, maxRadius = 1))
    }
}

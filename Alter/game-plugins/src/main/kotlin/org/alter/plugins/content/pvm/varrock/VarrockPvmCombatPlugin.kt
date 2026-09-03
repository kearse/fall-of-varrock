package org.alter.plugins.content.pvm.varrock

import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.TileGraphic
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
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
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.rscm.RSCM.getRSCM

/**
 * Fights for the Fallen Varrock layer (FoV-original — companion-aware by omission: nothing here
 * targets companions specially, they simply fight alongside):
 *  - **Elites**: one style each — melee (max per kind) or a magic bolt from range 6; the
 *    Plague Zombie poisons, the Wailing Shade and Banshee sap prayer.
 *  - **Malachai the Hollow**: heavy melee (max 30); every sixth swing a **wail** that drains a
 *    fifth of the prayer of everyone within 5 tiles.
 *  - **The Palace Warden**: melee (max 30) or a **necrotic bolt** (magic, max 24, drains 5
 *    prayer); every eighth attack **raises three dead** beside its target; once below half
 *    health, **Grave Chill** — a three-tick telegraph, then 20 through prayer to anyone within
 *    two tiles.
 */
class VarrockPvmCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        VarrockPvm.ELITES.forEach { e -> onNpcCombat(e.npcKey) { npc.queue { npc.eliteCombat(this, e) } } }
        onNpcCombat(VarrockPvm.HOLLOW_KEY) { npc.queue { npc.hollowCombat(this) } }
        onNpcCombat(VarrockPvm.WARDEN_KEY) { npc.queue { npc.wardenCombat(this) } }
    }

    private fun Npc.playersWithin(radius: Int): List<Player> {
        val out = mutableListOf<Player>()
        world.players.forEach { p -> if (p.tile.height == tile.height && p.tile.isWithinRadius(tile, radius) && !p.isDead() && !p.invisible) out += p }
        return out
    }

    private suspend fun Npc.eliteCombat(task: QueueTask, e: VarrockPvm.Elite) {
        var target = getCombatTarget() ?: return
        val ranged = e.style == VarrockPvm.Style.MAGIC
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = if (ranged) 6 else 1, projectile = ranged) && isAttackDelayReady()) {
                animate(e.attackAnim)
                val landed = if (ranged) bossProjectile(target, CombatClass.MAGIC, maxHit = e.maxHit, gfx = e.projGfx)
                else bossMelee(target, maxHit = e.maxHit, style = CombatStyle.SLASH)
                if (landed) {
                    if (e.poison > 0 && world.chance(1, 3)) Poison.poison(target, e.poison)
                    if (e.prayerDrain > 0 && target is Player) target.getSkills().alterCurrentLevel(Skills.PRAYER, -e.prayerDrain)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private suspend fun Npc.hollowCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        var swings = 0
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false) && isAttackDelayReady()) {
                swings++
                if (swings % 6 == 0) {
                    forceChat("Hear the hollow bells...")
                    animate(823)
                    playersWithin(5).forEach { p ->
                        val cur = p.getSkills().getCurrentLevel(Skills.PRAYER)
                        if (cur > 0) p.getSkills().alterCurrentLevel(Skills.PRAYER, -(cur / 5).coerceAtLeast(1))
                        p.graphic(172, 92)
                        p.message("<col=ff0000>Malachai's wail saps your prayer!</col>")
                    }
                } else {
                    animate(822)
                    bossMelee(target, maxHit = 30, style = CombatStyle.SLASH)
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private suspend fun Npc.wardenCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (attr[WARDEN_BUSY] == true) {
                task.wait(1)
                target = getCombatTarget() ?: break
                continue
            }
            if (getCurrentHp() < getMaxHp() / 2 && attr[WARDEN_CHILLED] != true) {
                attr[WARDEN_CHILLED] = true
                graveChill()
                task.wait(1)
                target = getCombatTarget() ?: break
                continue
            }
            if (moveToAttackRange(task, target, distance = 7, projectile = true) && isAttackDelayReady()) {
                val n = (attr[WARDEN_COUNT] ?: 0) + 1
                attr[WARDEN_COUNT] = n
                if (n % 8 == 0) {
                    raiseTheDead(target)
                } else if (tile.isWithinRadius(target.tile, 1) && world.chance(3, 5)) {
                    animate(5571)
                    bossMelee(target, maxHit = 30, style = CombatStyle.CRUSH)
                } else {
                    animate(5571)
                    val landed = bossProjectile(target, CombatClass.MAGIC, maxHit = 24, gfx = 100)
                    if (landed && target is Player) {
                        target.getSkills().alterCurrentLevel(Skills.PRAYER, -5)
                        target.graphic(172, 92)
                    }
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private fun Npc.raiseTheDead(target: Pawn) {
        forceChat("Rise. RISE!")
        animate(5573)
        val adds = mutableListOf<Npc>()
        repeat(3) {
            val at = world.snapToWalkable(Tile(target.tile.x + world.random(2) - 1, target.tile.z + world.random(2) - 1, tile.height), maxRadius = 3)
            val add = Npc(getRSCM(VarrockPvm.WARDEN_ADD_KEY), at, world)
            add.respawns = false
            add.attr[WARDEN_ADD] = true
            world.spawn(add)
            add.setActive(true)
            add.attack(target)
            adds += add
        }
        val boss = this
        world.queue {
            var left = 120
            while (left > 0 && !boss.isDead() && boss.index >= 0) { wait(5); left -= 5 }
            adds.forEach { if (!it.isDead() && it.index >= 0) world.remove(it) }
        }
    }

    private fun Npc.graveChill() {
        attr[WARDEN_BUSY] = true
        forceChat("The grave takes you all!")
        animate(5573)
        val boss = this
        val ring = mutableListOf<Tile>()
        for (dx in -2..3) for (dz in -2..3) ring += Tile(tile.x + dx, tile.z + dz, tile.height)
        world.queue {
            repeat(3) { ring.forEach { t -> world.spawn(TileGraphic(t, 369, 0)) }; wait(1) }
            if (!boss.isDead() && boss.index >= 0) {
                boss.playersWithin(3).forEach { p ->
                    if (ring.any { it.sameAs(p.tile) }) {
                        p.hit(damage = 20, delay = 0)
                        p.message("<col=ff0000>The grave chill bites through you!</col>")
                    }
                }
            }
            boss.attr[WARDEN_BUSY] = false
        }
    }

    companion object {
        val WARDEN_COUNT = AttributeKey<Int>()
        val WARDEN_BUSY = AttributeKey<Boolean>()
        val WARDEN_CHILLED = AttributeKey<Boolean>()
        val WARDEN_ADD = AttributeKey<Boolean>()
    }
}

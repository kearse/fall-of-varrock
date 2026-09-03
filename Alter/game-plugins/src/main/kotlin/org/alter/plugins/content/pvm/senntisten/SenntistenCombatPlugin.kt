package org.alter.plugins.content.pvm.senntisten

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
import org.alter.rscm.RSCM.getRSCM

/**
 * Fights for the expedition: wardens are single-style (melee or a magic bolt from range 6,
 * the Echo saps prayer); **the Custodian** melees (max 28) or hurls a necrotic bolt (magic,
 * max 26, drains 6 prayer), every seventh attack charges a **conduit surge** (three-tick
 * telegraph, then 25 through prayer to anyone within two tiles) and, once below half health,
 * calls two Echoes from the altar.
 */
class SenntistenCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        Senntisten.WARDENS.forEach { w -> onNpcCombat(w.npcKey) { npc.queue { npc.wardenCombat(this, w) } } }
        onNpcCombat(Senntisten.CUSTODIAN_KEY) { npc.queue { npc.custodianCombat(this) } }
    }

    private fun Npc.playersWithin(radius: Int): List<Player> {
        val out = mutableListOf<Player>()
        world.players.forEach { p -> if (p.tile.height == tile.height && p.tile.isWithinRadius(tile, radius) && !p.isDead() && !p.invisible) out += p }
        return out
    }

    private suspend fun Npc.wardenCombat(task: QueueTask, w: Senntisten.Warden) {
        var target = getCombatTarget() ?: return
        val ranged = w.style == Senntisten.Style.MAGIC
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = if (ranged) 6 else 1, projectile = ranged) && isAttackDelayReady()) {
                animate(w.attackAnim)
                val landed = if (ranged) bossProjectile(target, CombatClass.MAGIC, maxHit = w.maxHit, gfx = w.projGfx)
                else bossMelee(target, maxHit = w.maxHit, style = CombatStyle.SLASH)
                if (landed && w.prayerDrain > 0 && target is Player) target.getSkills().alterCurrentLevel(Skills.PRAYER, -w.prayerDrain)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private suspend fun Npc.custodianCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (attr[BUSY] == true) {
                task.wait(1)
                target = getCombatTarget() ?: break
                continue
            }
            if (getCurrentHp() < getMaxHp() / 2 && attr[CALLED] != true) {
                attr[CALLED] = true
                callEchoes(target)
            }
            if (moveToAttackRange(task, target, distance = 7, projectile = true) && isAttackDelayReady()) {
                val n = (attr[COUNT] ?: 0) + 1
                attr[COUNT] = n
                if (n % 7 == 0) {
                    surge()
                } else if (tile.isWithinRadius(target.tile, 1) && world.chance(3, 5)) {
                    animate(4678)
                    bossMelee(target, maxHit = 28, style = CombatStyle.SLASH)
                } else {
                    animate(4678)
                    val landed = bossProjectile(target, CombatClass.MAGIC, maxHit = 26, gfx = 100)
                    if (landed && target is Player) {
                        target.getSkills().alterCurrentLevel(Skills.PRAYER, -6)
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

    private fun Npc.callEchoes(target: Pawn) {
        forceChat("The conduits answer me!")
        repeat(2) {
            val at = world.snapToWalkable(Tile(tile.x + world.random(4) - 2, tile.z + world.random(4) - 2, tile.height), maxRadius = 3)
            val echo = Npc(getRSCM(Senntisten.ECHO.npcKey), at, world)
            echo.respawns = false
            world.spawn(echo)
            echo.setActive(true)
            echo.attack(target)
        }
    }

    private fun Npc.surge() {
        attr[BUSY] = true
        forceChat("The conduit... SURGES!")
        animate(4678)
        val boss = this
        val ring = mutableListOf<Tile>()
        for (dx in -2..3) for (dz in -2..3) ring += Tile(tile.x + dx, tile.z + dz, tile.height)
        world.queue {
            repeat(3) { ring.forEach { t -> world.spawn(TileGraphic(t, 369, 0)) }; wait(1) }
            if (!boss.isDead() && boss.index >= 0) {
                boss.playersWithin(3).forEach { p ->
                    if (ring.any { it.sameAs(p.tile) }) {
                        p.hit(damage = 25, delay = 0)
                        p.message("<col=ff0000>The surge tears through you!</col>")
                    }
                }
            }
            boss.attr[BUSY] = false
        }
    }

    companion object {
        val COUNT = AttributeKey<Int>()
        val BUSY = AttributeKey<Boolean>()
        val CALLED = AttributeKey<Boolean>()
    }
}

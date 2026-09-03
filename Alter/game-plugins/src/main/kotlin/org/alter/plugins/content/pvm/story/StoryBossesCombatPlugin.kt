package org.alter.plugins.content.pvm.story

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
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

/**
 * **Zemouregal** (Mahjarrat, ancient magicks): melee 30 when adjacent, else an ice bolt
 * (magic 28, holds you two ticks) or a blood bolt (magic 26, heals him half of it). Every
 * sixth attack he raises two of the dead; every tenth he charges a **Mahjarrat flare** —
 * three-tick telegraph, then 32 through prayer to anyone within three tiles. Below a third
 * he wrenches at Arrav's heart, stunning the ally for a spell.
 *
 * **The Convergence** (magic only): necrotic wave 30 with a 5-prayer bite; every fifth attack
 * **grasping conduits** — a three-tick hold and 18; every ninth a **fracture pulse** — three-tick
 * telegraph, 38 through prayer within three tiles. At two-thirds and one-third it births three
 * echoes.
 *
 * **Arrav** (ally): stays on Zemouregal and strikes every four ticks for 8–22; if he falls,
 * Zemouregal drinks 200 from the loss.
 */
class StoryBossesCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onNpcCombat(StoryBosses.ZEMOUREGAL_KEY) { npc.queue { npc.zemouregalCombat(this) } }
        onNpcCombat(StoryBosses.CONVERGENCE_KEY) { npc.queue { npc.convergenceCombat(this) } }
    }

    private fun Npc.playersWithin(radius: Int): List<Player> {
        val out = mutableListOf<Player>()
        world.players.forEach { p -> if (p.tile.height == tile.height && p.tile.isWithinRadius(tile, radius) && !p.isDead() && !p.invisible) out += p }
        return out
    }

    private fun Npc.telegraph(radius: Int, gfx: Int, damage: Int, shout: String, then: () -> Unit = {}) {
        attr[BUSY] = true
        forceChat(shout)
        val boss = this
        val ring = mutableListOf<Tile>()
        for (dx in -radius..radius + boss.getSize() - 1) for (dz in -radius..radius + boss.getSize() - 1) ring += Tile(tile.x + dx, tile.z + dz, tile.height)
        world.queue {
            repeat(3) { ring.filterIndexed { i, _ -> i % 3 == 0 }.forEach { t -> world.spawn(TileGraphic(t, gfx, 0)) }; wait(1) }
            if (!boss.isDead() && boss.index >= 0) {
                boss.playersWithin(radius + boss.getSize()).forEach { p ->
                    if (ring.any { it.sameAs(p.tile) }) {
                        p.hit(damage = damage, delay = 0)
                        p.message("<col=ff0000>You are caught in it!</col>")
                    }
                }
                then()
            }
            boss.attr[BUSY] = false
        }
    }

    // ───────────────────────────── Zemouregal ─────────────────────────────

    private suspend fun Npc.zemouregalCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (attr[BUSY] == true) {
                task.wait(1)
                target = getCombatTarget() ?: break
                continue
            }
            if (getCurrentHp() < getMaxHp() / 3 && attr[WRENCHED] != true) {
                attr[WRENCHED] = true
                wrenchArrav()
            }
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                val n = (attr[COUNT] ?: 0) + 1
                attr[COUNT] = n
                when {
                    n % 10 == 0 -> telegraph(radius = 3, gfx = 369, damage = 32, shout = "Witness a Mahjarrat's fire!")
                    n % 6 == 0 -> raiseTheDead(target)
                    tile.isWithinRadius(target.tile, 1) && world.chance(1, 2) -> {
                        animate(9876)
                        bossMelee(target, maxHit = 30, style = CombatStyle.CRUSH)
                    }
                    world.chance(1, 2) -> {
                        animate(9876)
                        val landed = bossProjectile(target, CombatClass.MAGIC, maxHit = 28, gfx = 368)
                        if (landed && target is Player) {
                            target.graphic(369, 0)
                            target.freeze(2) {}
                        }
                    }
                    else -> {
                        animate(9876)
                        val boss = this
                        dealHit(target, maxHit = 26, landHit = world.chance(3, 4), delay = 2, respectsProtection = true) { ph ->
                            val dealt = ph.hit.hitmarks.sumOf { it.damage }
                            if (dealt > 0 && !boss.isDead() && boss.index >= 0) {
                                boss.setCurrentHp(minOf(boss.getMaxHp(), boss.getCurrentHp() + dealt / 2))
                                target.graphic(377, 0)
                            }
                        }
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
        forceChat("Rise, and serve!")
        animate(9876)
        repeat(2) {
            val at = world.snapToWalkable(Tile(tile.x + world.random(4) - 2, tile.z + world.random(4) - 2, tile.height), maxRadius = 3)
            runCatching {
                val dead = Npc(getRSCM(StoryBosses.RISEN_KEY), at, world)
                dead.respawns = false
                world.spawn(dead)
                dead.setActive(true)
                WarNpcNames.rename(dead, "Zemouregal's risen")
                dead.attack(target)
                adds().add(dead)
            }
        }
    }

    private fun Npc.wrenchArrav() {
        val ally = attr[ALLY]?.get() ?: return
        if (ally.isDead() || ally.index < 0) return
        forceChat("Your heart is still mine, Arrav!")
        ally.forceChat("Aaargh!")
        ally.graphic(377, 0)
        ally.stun(12) {}
    }

    // ───────────────────────────── the Convergence ─────────────────────────────

    private suspend fun Npc.convergenceCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (attr[BUSY] == true) {
                task.wait(1)
                target = getCombatTarget() ?: break
                continue
            }
            val hp = getCurrentHp()
            val births = attr[BIRTHS] ?: 0
            if ((births == 0 && hp < getMaxHp() * 2 / 3) || (births == 1 && hp < getMaxHp() / 3)) {
                attr[BIRTHS] = births + 1
                birthEchoes(target)
            }
            if (moveToAttackRange(task, target, distance = 9, projectile = true) && isAttackDelayReady()) {
                val n = (attr[COUNT] ?: 0) + 1
                attr[COUNT] = n
                when {
                    n % 9 == 0 -> telegraph(radius = 3, gfx = 1274, damage = 38, shout = "The fracture WIDENS.")
                    n % 5 == 0 -> {
                        animate(8634)
                        val landed = bossProjectile(target, CombatClass.MAGIC, maxHit = 18, gfx = 100)
                        if (target is Player) {
                            target.graphic(369, 0)
                            target.freeze(3) {}
                            if (landed) target.message("<col=ff0000>Conduits of shadow grasp at you!</col>")
                        }
                    }
                    else -> {
                        animate(8634)
                        val landed = bossProjectile(target, CombatClass.MAGIC, maxHit = 30, gfx = 100)
                        if (landed && target is Player) {
                            target.getSkills().alterCurrentLevel(Skills.PRAYER, -5)
                            target.graphic(172, 92)
                        }
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

    private fun Npc.birthEchoes(target: Pawn) {
        forceChat("We are many. We are what fell.")
        repeat(3) {
            val at = world.snapToWalkable(Tile(tile.x + world.random(6) - 3, tile.z + world.random(6) - 3, tile.height), maxRadius = 4)
            runCatching {
                val echo = Npc(getRSCM(StoryBosses.ECHO_KEY), at, world)
                echo.respawns = false
                world.spawn(echo)
                echo.setActive(true)
                WarNpcNames.rename(echo, "Echo of the Convergence")
                echo.attack(target)
                adds().add(echo)
            }
        }
    }

    companion object {
        val COUNT = AttributeKey<Int>()
        val BUSY = AttributeKey<Boolean>()
        val WRENCHED = AttributeKey<Boolean>()
        val BIRTHS = AttributeKey<Int>()
        /** Zemouregal → his Arrav ally (set by StoryBossesPlugin). */
        val ALLY = AttributeKey<java.lang.ref.WeakReference<Npc>>()
        /** Everything a boss summoned, so the run can sweep it on exit. */
        val ADDS = AttributeKey<MutableList<Npc>>()

        fun Npc.adds(): MutableList<Npc> = attr[ADDS] ?: mutableListOf<Npc>().also { attr[ADDS] = it }
    }
}

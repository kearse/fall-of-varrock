package org.alter.plugins.content.bosses.godwars

import org.alter.api.HitType
import org.alter.api.PrayerIcon
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.CombatStyle
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.walkTo
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.bossMelee
import org.alter.plugins.content.bosses.bossProjectile
import org.alter.plugins.content.bosses.isProtectedFrom
import org.alter.plugins.content.combat.*
import org.alter.plugins.content.combat.formula.MagicCombatFormula
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.rscm.RSCM.getRSCM

/**
 * The **God Wars throne-room fights** — the four generals and their eight caster/
 * ranger bodyguards from Kronos rev-184 `godwars/combat/`, ids and behaviours
 * verbatim (the four melee bodyguards fight through a shared scripted melee loop so
 * they honour protection prayers, the Fight Cave lesson):
 *
 *  - **Graardor**: adjacent = 65% crush melee, else the ranged BARRAGE — every player
 *    in the room takes a rand-35 boulder (anim 7021, proj 1202). 1/6 war-shouts.
 *  - **Zilyana**: 2-tick melee chaser; 1/7 attacks she roots and BURSTS the whole
 *    room — two instant rand-27 magic hits each (anim 6970, gfx 1221).
 *  - **K'ril**: melee up close — but praying melee risks his PIERCE (rand 49 through
 *    prayer AND defence + half your prayer points drained); magic barrage from range
 *    (anim 6950, proj 1225); every attack has a 1/8 chance to poison(16).
 *  - **Kree'arra**: never melees (flying — his melee immunity lives in his combat
 *    def); 4/10 magic (proj 1200, rand 21) else the ranged blast (proj 1199, rand 71)
 *    which KNOCKS every player back a tile (gfx 245).
 */
class GodwarsCombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onNpcCombat("npc.general_graardor") { npc.queue { npc.graardorCombat(this) } }
        onNpcCombat("npc.commander_zilyana") { npc.queue { npc.zilyanaCombat(this) } }
        onNpcCombat("npc.kril_tsutsaroth") { npc.queue { npc.krilCombat(this) } }
        onNpcCombat("npc.kreearra_3162") { npc.queue { npc.kreearraCombat(this) } }

        // Caster/ranger bodyguards: single-target projectile attackers (donor classes).
        rangedGuard("npc.sergeant_grimspike", maxHit = 21, projGfx = 1220)
        rangedGuard("npc.bree", maxHit = 16, projGfx = 1190, selfGfx = 1185)
        rangedGuard("npc.zakln_gritch", maxHit = 21, projGfx = 1223, selfGfx = 1222)
        rangedGuard("npc.flockleader_geerin", maxHit = 25, projGfx = 1192)
        magicGuard("npc.sergeant_steelwill", maxHit = 35, projGfx = 1217, selfGfx = 1216)
        magicGuard("npc.growler", maxHit = 16, projGfx = 1183, selfGfx = 1182)
        magicGuard("npc.balfrug_kreeyath", maxHit = 16, projGfx = 1227, selfGfx = 1226)
        magicGuard("npc.wingman_skree", maxHit = 16, projGfx = 1201, selfGfx = 1194)

        // Melee bodyguards: scripted so Protect from Melee is honoured.
        meleeGuard("npc.sergeant_strongstack", maxHit = 15)
        meleeGuard("npc.starlight", maxHit = 15)
        meleeGuard("npc.tstanon_karlak", maxHit = 15)
        meleeGuard("npc.flight_kilisa", maxHit = 18)
    }

    /** Every player standing in this npc's room (same plane, radius 15 — the rooms
     *  are regions apart, so this can never leak across camps). PawnList only offers
     *  forEach, so collect by hand. */
    private fun Npc.roomPlayers(): List<Player> {
        val out = mutableListOf<Player>()
        world.players.forEach { p ->
            if (p.tile.height == tile.height && p.tile.isWithinRadius(tile, 15) && !p.isDead() && !p.invisible) {
                out += p
            }
        }
        return out
    }

    // ───────────────────────────── General Graardor ─────────────────────────────

    private suspend fun Npc.graardorCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (world.chance(1, 6)) forceChat(GRAARDOR_SHOUTS[world.random(GRAARDOR_SHOUTS.size - 1)])
                if (tile.isWithinRadius(target.tile, 1) && world.chance(65, 100)) {
                    animate(7018)
                    bossMelee(target, maxHit = 60, style = CombatStyle.CRUSH)
                } else {
                    // The boulder barrage: EVERY player in the room is a target (donor).
                    animate(7021)
                    roomPlayers().forEach { p -> bossProjectile(p, CombatClass.RANGED, maxHit = 35, gfx = 1202) }
                }
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ───────────────────────────── Commander Zilyana ─────────────────────────────

    private suspend fun Npc.zilyanaCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false)) {
                if (isAttackDelayReady()) {
                    if (world.chance(1, 5)) forceChat(ZILYANA_SHOUTS[world.random(ZILYANA_SHOUTS.size - 1)])
                    if (world.chance(1, 7)) {
                        // The lightning burst: instant, no projectile — two rand-27 magic
                        // hits on every player in the room, second a tick later (donor).
                        animate(6970)
                        roomPlayers().forEach { p ->
                            magicBurstHit(p, maxHit = 27, delayTicks = 0)
                            magicBurstHit(p, maxHit = 27, delayTicks = 1)
                            p.graphic(1221, height = 30, delay = 0)
                        }
                    } else {
                        animate(6967)
                        bossMelee(target, maxHit = 31, style = CombatStyle.CRUSH)
                    }
                    postAttackLogic(target)
                }
            } else {
                walkTo(target.tile)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    /** An instant magic hit with real accuracy + overhead protection (no projectile). */
    private fun Npc.magicBurstHit(victim: Player, maxHit: Int, delayTicks: Int) {
        if (victim.isProtectedFrom(CombatClass.MAGIC)) {
            victim.hit(damage = 0, type = HitType.BLOCK, delay = delayTicks)
            return
        }
        val landed = MagicCombatFormula.getAccuracy(this, victim) >= world.randomDouble()
        victim.hit(
            damage = if (landed) world.random(maxHit) else 0,
            type = if (landed) HitType.HIT else HitType.BLOCK,
            delay = delayTicks,
        )
    }

    // ───────────────────────────── K'ril Tsutsaroth ─────────────────────────────

    private suspend fun Npc.krilCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (tile.isWithinRadius(target.tile, 1)) {
                    val victim = target as? Player
                    if (victim != null && victim.hasPrayerIcon(PrayerIcon.PROTECT_FROM_MELEE) && world.chance(2, 10)) {
                        // The prayer-smash: through prayer AND defence, plus half your
                        // prayer points (donor pierceAttack).
                        forceChat("YARRRRRRR!")
                        animate(6948)
                        victim.hit(damage = world.random(49), delay = 0)
                        val prayer = victim.getSkills().getCurrentLevel(Skills.PRAYER)
                        victim.getSkills().alterCurrentLevel(Skills.PRAYER, -(prayer / 2), capValue = 0)
                        victim.message("K'ril Tsutsaroth slams through your protection prayer, leaving you feeling drained.")
                    } else {
                        if (world.chance(1, 6)) forceChat(KRIL_SHOUTS[world.random(KRIL_SHOUTS.size - 1)])
                        animate(6948)
                        bossMelee(target, maxHit = 47, style = CombatStyle.SLASH)
                    }
                } else {
                    if (world.chance(1, 6)) forceChat(KRIL_SHOUTS[world.random(KRIL_SHOUTS.size - 1)])
                    animate(6950)
                    graphic(1224, height = 30, delay = 0)
                    roomPlayers().forEach { p -> bossProjectile(p, CombatClass.MAGIC, maxHit = 30, gfx = 1225) }
                }
                // Every attack carries a 1/8 poison(16) rider (donor).
                if (world.chance(1, 8)) Poison.poison(target, initialDamage = 16)
                postAttackLogic(target)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ───────────────────────────── Kree'arra ─────────────────────────────

    private suspend fun Npc.kreearraCombat(task: QueueTask) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true) && isAttackDelayReady()) {
                if (world.chance(1, 6)) forceChat(KREE_SHOUTS[world.random(KREE_SHOUTS.size - 1)])
                animate(6980)
                if (world.chance(4, 10)) {
                    roomPlayers().forEach { p -> bossProjectile(p, CombatClass.MAGIC, maxHit = 21, gfx = 1200) }
                } else {
                    // The gale blast: rand-71 ranged on the whole room, and everyone is
                    // buffeted one tile away from him (donor knockback, gfx 245).
                    roomPlayers().forEach { p ->
                        bossProjectile(p, CombatClass.RANGED, maxHit = 71, gfx = 1199)
                        knockback(p)
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

    private fun Npc.knockback(p: Player) {
        val dx = Integer.signum(p.tile.x - tile.x)
        val dz = Integer.signum(p.tile.z - tile.z)
        if (dx == 0 && dz == 0) return
        val pushed = Tile(p.tile.x + dx, p.tile.z + dz, p.tile.height)
        // Only push onto genuinely walkable ground — never through walls.
        if (world.snapToWalkable(pushed).sameAs(pushed)) {
            p.moveTo(pushed)
            p.graphic(245, height = 124, delay = 0)
        }
    }

    // ───────────────────────────── bodyguards ─────────────────────────────

    private fun rangedGuard(key: String, maxHit: Int, projGfx: Int, selfGfx: Int = -1) {
        onNpcCombat(key) { npc.queue { npc.guardCombat(this, CombatClass.RANGED, maxHit, projGfx, selfGfx) } }
    }

    private fun magicGuard(key: String, maxHit: Int, projGfx: Int, selfGfx: Int = -1) {
        onNpcCombat(key) { npc.queue { npc.guardCombat(this, CombatClass.MAGIC, maxHit, projGfx, selfGfx) } }
    }

    private fun meleeGuard(key: String, maxHit: Int) {
        onNpcCombat(key) { npc.queue { npc.guardMeleeCombat(this, maxHit) } }
    }

    private suspend fun Npc.guardCombat(task: QueueTask, cls: CombatClass, maxHit: Int, projGfx: Int, selfGfx: Int) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 8, projectile = true)) {
                if (isAttackDelayReady()) {
                    if (selfGfx != -1) graphic(selfGfx, height = 30, delay = 0)
                    animate(CombatConfigs.getAttackAnimation(this))
                    bossProjectile(target, cls, maxHit = maxHit, gfx = projGfx)
                    postAttackLogic(target)
                }
            } else {
                walkTo(target.tile)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    private suspend fun Npc.guardMeleeCombat(task: QueueTask, maxHit: Int) {
        var target = getCombatTarget() ?: return
        while (canEngageCombat(target)) {
            facePawn(target)
            if (moveToAttackRange(task, target, distance = 1, projectile = false)) {
                if (isAttackDelayReady()) {
                    animate(CombatConfigs.getAttackAnimation(this))
                    bossMelee(target, maxHit)
                    postAttackLogic(target)
                }
            } else {
                walkTo(target.tile)
            }
            task.wait(1)
            target = getCombatTarget() ?: break
        }
        resetFacePawn()
        removeCombatTarget()
    }

    // ───────────────────────────── shouts (donor, verbatim) ─────────────────────────────

    private companion object {
        val GRAARDOR_SHOUTS = arrayOf(
            "Death to our enemies!", "Brargh!", "Break their bones!",
            "For the glory of the Big High War God!", "Split their skulls!",
            "We feast on the bones of our enemies tonight!", "CHAAARGE!",
            "Crush them underfoot!", "All glory to Bandos!", "GRAAAAAAAAAR!",
        )
        val ZILYANA_SHOUTS = arrayOf(
            "Death to the enemies of the light!", "Slay the evil ones!",
            "Saradomin lend me strength!", "By the power of Saradomin!",
            "May Saradomin be my sword!", "Good will always triumph!",
            "Forward! Our allies are with us!", "Saradomin is with us!",
            "In the name of Saradomin!", "Attack! Find the Godsword!",
        )
        val KRIL_SHOUTS = arrayOf(
            "Attack them, you dogs!", "Forward!", "Death to Saradomin's dogs!",
            "Kill them, you cowards!", "The Dark One will have their souls!",
            "Zamorak curse them!", "Rend them limb from limb!", "No retreat!",
        )
        val KREE_SHOUTS = arrayOf("Kraaaaw!", "Eeeeek!")
    }
}

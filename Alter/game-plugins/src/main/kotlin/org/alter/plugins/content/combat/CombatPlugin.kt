package org.alter.plugins.content.combat

import org.alter.api.EquipmentType
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.COMBAT_TARGET_FOCUS_ATTR
import org.alter.game.model.attr.FACING_PAWN_ATTR
import org.alter.game.model.attr.INTERACTING_PLAYER_ATTR
import org.alter.game.model.collision.rayCast
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.MovementQueue.StepType
import org.alter.game.model.move.hasMoveDestination
import org.alter.game.model.move.stopMovement
import org.alter.game.model.move.walkRoute
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.combat.strategy.MeleeCombatStrategy
import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import org.alter.plugins.content.interfaces.attack.AttackTab
import java.util.*

class CombatPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {

    init {
        setCombatLogic {
            pawn.attr[COMBAT_TARGET_FOCUS_ATTR]?.get()?.let { target ->
                pawn.facePawn(target)
            }
            pawn.queue {
                while (true) {
                    // @TODO Npc can follow player up to 16 tiles from spawn point, some npc will have exceptional range so property for overwrite should be added.
                    if (!cycle(pawn, this)) {
                        break
                    }
                    wait(1)
                }
            }
        }

        onPlayerOption("Attack") {
            val target = pawn.attr[INTERACTING_PLAYER_ATTR]?.get() ?: return@onPlayerOption
            player.attack(target)
        }
    }

    /**
     * @TODO Bigger creatures seem to have bugged range + their route finding sucks due to conditions given.
     */
    suspend fun cycle(pawn: Pawn, queue: QueueTask): Boolean {
        val target = pawn.getCombatTarget() ?: return false
        // Re-arm autocast BEFORE resolving the strategy: on the first engagement tick the
        // CASTING_SPELL attr is not yet set, so resolving first made the loop pick the
        // melee strategy (range 1) and open every autocast fight with a melee swing.
        if (pawn is Player &&
            !pawn.attr.has(Combat.CASTING_SPELL) &&
            pawn.getVarbit(Combat.SELECTED_AUTOCAST_VARBIT) != 0 &&
            CombatConfigs.canAutocast(pawn)
        ) {
            val spell =
                CombatSpell.values.firstOrNull { it.autoCastId == pawn.getVarbit(Combat.SELECTED_AUTOCAST_VARBIT) }
            if (spell != null) {
                pawn.attr[Combat.CASTING_SPELL] = spell
            }
        }
        val strategy = CombatConfigs.getCombatStrategy(pawn)
        val attackRange = strategy.getAttackRange(pawn)
        var routeLogic = 1
        if (target != pawn.attr[FACING_PAWN_ATTR]?.get()) {
            // Facing broke (another plugin re-faced us): clear the combat focus too, or the
            // stale attr keeps this pawn "in combat" for aggro and single-way checks.
            Combat.reset(pawn)
            return false
        }
        if (pawn.entityType.isNpc) {
            routeLogic = (pawn as Npc).routeLogic
        }
        var reached = world.reachStrategy.reached(
            flags = world.collision,
            level = pawn.tile.height,
            srcX = pawn.tile.x ,
            srcZ = pawn.tile.z,
            destX = target.tile.x,
            destZ = target.tile.z,
            destWidth = target.getSize(),
            destLength = target.getSize(),
            srcSize = pawn.getSize(),
            locShape = -2
        )
        // You can never attack a target you're standing on top of (OSRS: no combat while
        // under a pawn — you must step to an adjacent tile first). reachStrategy already
        // reports "not reached" for overlapping boxes, so we path out from under the target
        // below, and the distance shortcut further down must not cancel that movement.
        val overlapping = Combat.areOverlapping(
            pawn.tile.x, pawn.tile.z, pawn.getSize(), pawn.getSize(),
            target.tile.x, target.tile.z, target.getSize(), target.getSize(),
        )
        if (!reached) {
            when (routeLogic) {
                1 -> {
                    // Chasing a MOVING target must path ONTO the tile they occupy (locShape -1),
                    // not stop at its border (-2): a bordering route to a target 2 tiles away is a
                    // single step, and running only gains ground when two steps are queued in the
                    // same tick (MovementQueue polls the second step only if one exists). With the
                    // one-step route, a running chaser and a walking target stayed exactly one
                    // tile apart forever. The target is vacating their tile this tick, so pathing
                    // onto it is safe — and if they stop, the next cycle re-routes with -2.
                    val fleeing = target.hasMoveDestination()
                    val route = world.smartRouteFinder.findRoute(
                        level = pawn.tile.height,
                        srcX = pawn.tile.x,
                        srcZ = pawn.tile.z,
                        destX = target.tile.x,
                        destZ = target.tile.z,
                        locShape = if (fleeing) -1 else -2,
                        destWidth = if (fleeing) 1 else target.getSize(),
                        destLength = if (fleeing) 1 else target.getSize()
                    )
                    pawn.walkRoute(route, StepType.NORMAL)
                }
                0 -> {
                    val route = LinkedList<Tile>()
                    if (overlapping) {
                        // Standing on top of the target gives naiveDestination a zero delta
                        // (the "step" would be our own tile), so step off to any free
                        // adjacent tile instead.
                        for (dir in Direction.RS_ORDER) {
                            if (world.canTraverse(pawn.tile, dir, pawn, pawn.getSize())) {
                                route.add(pawn.tile.step(dir))
                                break
                            }
                        }
                    } else {
                        val destination = world.dumbRouteFinder.naiveDestination(
                            sourceX = pawn.tile.x,
                            sourceZ = pawn.tile.z,
                            sourceWidth = pawn.getSize(),
                            sourceLength = pawn.getSize(),
                            targetX = target.tile.x,
                            targetZ = target.tile.z,
                            targetWidth = target.getSize(),
                            targetLength = target.getSize()
                        )
                        val dx = destination.x - pawn.tile.x
                        val dz = destination.z - pawn.tile.z
                        // Try diagonal move (both x and z)
                        val diagonalMove = Tile(pawn.tile.x + dx.coerceIn(-1, 1), pawn.tile.z + dz.coerceIn(-1, 1))
                        if (!world.canTraverse(pawn.tile, Direction.between(pawn.tile, diagonalMove), pawn, pawn.getSize())) {
                            // If diagonal blocked, try horizontal (east/west)
                            val horizontalMove = Tile(pawn.tile.x + dx.coerceIn(-1, 1), pawn.tile.z)
                            if (!world.canTraverse(pawn.tile, Direction.between(pawn.tile, horizontalMove), pawn, pawn.getSize())) {
                                // If horizontal blocked, try vertical (north/south)
                                val verticalMove = Tile(pawn.tile.x, pawn.tile.z + dz.coerceIn(-1, 1))
                                if (world.canTraverse(pawn.tile, Direction.between(pawn.tile, verticalMove), pawn, pawn.getSize())) {
                                    route.add(verticalMove)
                                }
                            } else {
                                route.add(horizontalMove)
                            }
                        } else {
                            route.add(diagonalMove)
                        }
                    }
                    if (route.isEmpty()) {
                        return true // no traversable step this tick — keep trying next tick
                    }
                    pawn.walkRoute(route, stepType = StepType.NORMAL)
                }
            }
        }
        if (!overlapping && !reached) {
            // This is the fallback that decides "close enough to swing" when reachStrategy says no.
            // It used to hand melee a free pass: euclidean getDistance() rounds a diagonal step up
            // to 2, the bound was `attackRange + size` (one tile too generous), and the LOS check
            // was skipped outright for anything with range <= 2 — so melee landed from several
            // tiles away and straight through walls and fences.
            //
            // Melee gets NO fallback at all: reachStrategy above is the sole authority on melee
            // reach, and it already enforces true adjacency plus the wall/fence collision flags.
            // Halberds (range 2) reach one tile further, and still can't swing through anything
            // that blocks walking. Only ranged/magic may span the gap, and they must hold
            // projectile line of sight to do it.
            //
            // Range is Chebyshev — tiles, counting a diagonal as one step — which is what OSRS
            // uses and what isWithinRadius (the metric everywhere else in combat) already applies.
            // getDistance() is euclidean, so it both over-reached melee and made ranged attacks
            // shorter on the diagonal than straight on.
            val melee = strategy === MeleeCombatStrategy
            val reach = attackRange + target.getSize() - 1
            val inRange = (!melee || attackRange > 1) &&
                pawn.tile.getChebyshevDistance(target.tile) <= reach
            if (inRange && world.lineValidator.rayCast(pawn.tile, target.tile, projectile = !melee)) {
                reached = true
                pawn.stopMovement()
            }
        }
        if (reached && pawn.hasMoveDestination()) {
            // In range now: drop any leftover chase steps (queued last tick, aimed at where the
            // target used to be) so the guard below doesn't defer the swing — and so we don't
            // walk past a target we could already hit.
            pawn.stopMovement()
        }
        if (pawn.hasMoveDestination() || !reached) {
            // Still chasing: let the OUTER combat loop wait a tick and call cycle() again.
            // (This used to tail-recurse across the suspension point, retaining one
            // continuation frame per tick of pursuit.)
            if (!target.isAlive()) {
                Combat.reset(pawn)
                return false
            }
            return true
        }
        if (!Combat.canEngage(pawn, target)) {
            Combat.reset(pawn)
            pawn.resetFacePawn()
            return false
        }
        if (!pawn.lock.canAttack()) {
            Combat.reset(pawn)
            return false
        }
        if (pawn is Player) {
            pawn.setVarp(Combat.PRIORITY_PID_VARP, target.index)
        }
        if (target != pawn.attr[FACING_PAWN_ATTR]?.get()) {
            Combat.reset(pawn)
            return false
        }
        if (Combat.isAttackDelayReady(pawn)) {
            if (Combat.canAttack(pawn, target, strategy)) {
                if (pawn is Player && AttackTab.isSpecialEnabled(pawn) && pawn.getEquipment(EquipmentType.WEAPON) != null) {
                    AttackTab.disableSpecial(pawn)
                    if (SpecialAttacks.execute(pawn, target, world)) {
                        Combat.postAttack(pawn, target)
                        return true
                    }
                    pawn.message("You don't have enough power left.")
                }
                strategy.attack(pawn, target)
                Combat.postAttack(pawn, target)
            } else {
                Combat.reset(pawn)
                return false
            }
        }
        return true
    }
}
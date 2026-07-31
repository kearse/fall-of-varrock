package org.alter.plugins.content.companion

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.collision.isClipped
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.MovementQueue.StepType
import org.alter.game.model.move.hasMoveDestination
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.walkTo
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.plugins.content.war.Cities
import org.alter.plugins.content.war.boss.BossScheduler
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs
import kotlin.math.max

/**
 * Per-companion, per-tick AI. Combat itself runs through the normal engine path (`Player.attack` →
 * `MeleeCombatStrategy`), so a companion that hits an NPC **gains combat XP automatically** (it's a
 * Player). This brain only decides *what to do* by the companion's [CompanionOrders].
 *
 * Step 2 implements **TRAIN** (the levelling loop) + a basic **FOLLOW**; DEPLOY and the PvP honor
 * targeting land in later steps.
 */
object CompanionBrain {
    private val GOBLIN by lazy { runCatching { getRSCM("npc.goblin_2245") }.getOrDefault(-1) }
    private val HOBGOBLIN by lazy { runCatching { getRSCM("npc.hobgoblin_2241") }.getOrDefault(-1) }

    /** NPCs a hunting companion must NEVER engage even though they carry an Attack option:
     *  General Zo (the war VIP), allied campaign troops / frontier knights, Knights of Lumbridge. */
    private val FRIENDLY by lazy {
        setOf(
            runCatching { getRSCM("npc.melee_combat_tutor") }.getOrDefault(-1), // General Zo
            runCatching { getRSCM("npc.knight_of_saradomin") }.getOrDefault(-1), // allied troops + frontier knights
            2213, 2214, // Knights of Lumbridge (same immune set as the Voidwaker AoE)
        )
    }

    /** Training ground: the north Lumbridge woods goblin band. TUNABLE. */
    private val TRAIN_TILE = Tile(3235, 3270, 0)
    /** Lumbridge town safezone — companions top up food here. TUNABLE. */
    private val TOWN = Area(3200, 3190, 3270, 3260)
    private const val SEEK = 12       // how far a companion looks for a training mob / the owner's foe
    private const val ENGAGE = 12      // how close a defended threat must be for the companion to engage
    private const val FOLLOW_DIST = 3 // how close a follower stays to its owner
    private const val TELEPORT_DIST = 12 // farther than this from its slot → teleport to catch up (pet-style)
    private const val CAMP_RANGE = 60 // TRAIN within this of the goblin camp = the classic camp loop; farther = hunt
    private const val LEASH = 16      // hunting companions only pick targets this close to the OWNER
    private const val HUNT_SNAP = LEASH + 8 // hunting companion this far behind its slot → teleport catch-up

    fun tick(world: World, comp: Companion) {
        if (comp.index < 0 || comp.isDead()) return
        // Owner competing in Last Man Standing → companions HOLD where they are. Every order's
        // catch-up snap (moveTo on height/distance mismatch) would otherwise teleport them onto the
        // island as free bodyguards — a battle royale with three guards isn't one. They stand down
        // and resume the moment the owner's game ends.
        CompanionRegistry.ownerOf(world, comp)?.let { owner ->
            if (org.alter.plugins.content.minigames.lms.LmsGame.inGame(owner)) {
                if (comp.isAttacking()) { comp.removeCombatTarget(); comp.resetFacePawn() }
                return
            }
            // Owner in a staked DUEL → companions stand down, UNLESS the duel's rules allow
            // companions (the 4v4) — and even then a companion knocked out of the duel stays
            // benched (no auto-respawn reinforcements). Standing down also stops the formation
            // snap, so barred companions wait outside the pit instead of spectating inside it.
            org.alter.plugins.content.minigames.duel.DuelArena.duelOf(owner)?.let { duel ->
                if (!duel.rules.allowCompanions || comp in duel.benched) {
                    if (comp.isAttacking()) { comp.removeCombatTarget(); comp.resetFacePawn() }
                    return
                }
            }
            // Owner mid-bout at the PK TRAINING arena → companions hold too: the spar bot only
            // targets the trainee, so a "helping" companion would beat on it for free wins and
            // ruin the lesson. They resume the moment the round ends.
            if (org.alter.plugins.content.minigames.pktraining.TrainingArena.inBout(owner)) {
                if (comp.isAttacking()) { comp.removeCombatTarget(); comp.resetFacePawn() }
                return
            }
            // Owner in the Fight Cave → companions HOLD. A companion follows into the private instance
            // and tanks Jad and the waves (they retarget whoever hits them), so the player is barely
            // attacked — the "Jad not attacking me" report. They resume when the run ends.
            if (org.alter.plugins.content.minigames.fightcave.FightCavePlugin.inCave(owner)) {
                if (comp.isAttacking()) { comp.removeCombatTarget(); comp.resetFacePawn() }
                return
            }
        }
        CompanionLoot.sweep(world, comp) // donor perk: bank nearby loot before doing anything else
        org.alter.plugins.content.bots.BotBrain.maybeEat(comp) // survive fights from its food supply
        if (TOWN.contains(comp.tile)) CompanionGear.restock(comp) // top up food/runes at the safezone
        CompanionRange.ensureAmmo(world, comp) // ranged: restock arrows from the owner's bank when low
        CompanionMagic.ensureSpell(comp) // mage: keep autocast in step with level / owner's chosen spell
        if (comp.isAttacking()) CompanionPotions.maybePot(comp) // boost when fighting
        when (comp.orders) {
            CompanionOrders.TRAIN -> train(world, comp)
            CompanionOrders.FOLLOW -> follow(world, comp)
            CompanionOrders.DEPLOY -> deploy(world, comp)
            CompanionOrders.RETURN -> recall(world, comp)
            CompanionOrders.ATTACK -> hunt(world, comp)
        }
    }

    /**
     * TRAIN is location-aware: near the Lumbridge goblin camp it runs the classic camp loop
     * (farm the goblin band, march to the woods when nothing is in reach). Anywhere else the
     * old behavior was useless (nothing but goblins/hobgoblins ever qualified), so it falls
     * back to [hunt] — fight whatever attackable NPCs are around the owner.
     */
    private fun train(world: World, comp: Companion) {
        if (comp.tile.height != 0 || dist(comp.tile, TRAIN_TILE) > CAMP_RANGE) {
            hunt(world, comp)
            return
        }
        val cur = comp.getCombatTarget() as? Npc
        if (comp.isAttacking() && cur != null && isTrainMob(world, cur)) {
            // Keep swinging only while the foe is still reachable. If it's walled off (wandered
            // somewhere the companion can't path to), drop it and re-pick — otherwise the companion
            // would stand forever trying to reach a target it can't get to.
            if (dist(comp.tile, cur.tile) <= 1 || canPathTo(world, comp, cur)) return
            comp.removeCombatTarget(); comp.resetFacePawn()
        }
        val foe = nearestReachableTrainMob(world, comp)
        if (foe != null) {
            comp.attack(foe)
        } else if (dist(comp.tile, TRAIN_TILE) > 3 && !comp.hasMoveDestination()) {
            comp.walkTo(TRAIN_TILE) // none reachable — march to the woods
        }
    }

    /**
     * ATTACK = aggressive escort: fight the nearest attackable NPC around the **owner** (leashed
     * to [LEASH] so the pack never chains off into the distance), and when nothing is left to
     * kill, form up on the owner like FOLLOW so the hunt travels wherever the owner goes.
     * Friendlies ([FRIENDLY]) and mobs already fighting a player outside the owner's party are
     * never engaged.
     */
    private fun hunt(world: World, comp: Companion) {
        val owner = CompanionRegistry.ownerOf(world, comp) ?: return
        val slot = formationTile(comp, owner)
        // Owner floors/teleports away → break combat and snap back (same rule as FOLLOW, wider
        // margin so a companion legitimately chasing a leashed target isn't yanked mid-fight).
        if (comp.tile.height != owner.tile.height || dist(comp.tile, slot) > HUNT_SNAP) {
            if (comp.isAttacking()) { comp.removeCombatTarget(); comp.resetFacePawn() }
            comp.moveTo(walkableNear(world, slot, owner.tile))
            return
        }
        val cur = comp.getCombatTarget() as? Npc
        if (comp.isAttacking() && cur != null && isHuntMob(world, comp, owner, cur)) {
            if (dist(comp.tile, cur.tile) <= 1 || canPathTo(world, comp, cur)) return
            comp.removeCombatTarget(); comp.resetFacePawn()
        }
        val foe = nearestReachableHuntMob(world, comp, owner)
        if (foe != null) {
            comp.attack(foe)
            return
        }
        // Nothing to fight — trail the owner in formation.
        if (dist(comp.tile, slot) >= 1) {
            if (comp.isAttacking()) { comp.removeCombatTarget(); comp.resetFacePawn() }
            comp.walkTo(slot, StepType.FORCED_RUN)
        }
    }

    /**
     * FOLLOW = guard the owner: fight whatever attacks the owner's party — NPC, PK bot or player —
     * else fight whatever the owner is fighting, else stick to within [FOLLOW_DIST]. A player
     * jumping the owner PREEMPTS an in-progress NPC brawl (PvP defense first). RECALL stays the
     * passive order — it never picks a fight, so it remains the safe way to pull companions out.
     */
    private fun follow(world: World, comp: Companion) {
        val owner = CompanionRegistry.ownerOf(world, comp) ?: return
        val target = formationTile(comp, owner)
        // Owner moved out of range (TELEPORTED away) or onto another FLOOR (stairs/ladders — dist()
        // is x/z-only, so a companion one floor below reads as distance 0 and would stand there
        // forever; the height gap must be checked explicitly) → break combat and snap back like a
        // pet, even mid-fight. Checked BEFORE "keep swinging" so a fighting companion never gets
        // left behind. Snapped to a stand-able tile so it can't land inside an upper-floor wall.
        if (comp.tile.height != owner.tile.height || dist(comp.tile, target) > TELEPORT_DIST) {
            if (comp.isAttacking()) { comp.removeCombatTarget(); comp.resetFacePawn() }
            comp.moveTo(walkableNear(world, target, owner.tile))
            return
        }
        val playerThreat = playerThreatNear(world, comp, owner)
        val cur = comp.getCombatTarget()
        if (comp.isAttacking() && cur != null && isAlivePawn(world, cur)) {
            // Keep swinging — unless a player has jumped the owner while we brawl an NPC:
            // defending the owner against a PKer outranks finishing the mob.
            if (playerThreat == null || cur is Player) return
        }
        val foe: Pawn? = playerThreat ?: combatFoeNear(world, comp, owner)
        if (foe != null) {
            comp.attack(foe)
            return
        }
        // Form up: each companion trails behind the owner in its own slot so they don't STACK —
        // 1 directly behind, 2 side-by-side, 3 a triangle (1 then 2), oriented behind the owner's
        // movement. RUN to keep pace.
        if (dist(comp.tile, target) >= 1) {
            if (comp.isAttacking()) { comp.removeCombatTarget(); comp.resetFacePawn() }
            comp.walkTo(target, StepType.FORCED_RUN)
        }
    }

    /**
     * RECALL (the [CompanionOrders.RETURN] order) = **disengage and stick to the owner**. Unlike
     * FOLLOW it never picks a fight: it breaks any current combat immediately and returns to the
     * owner (teleporting if it's fallen too far behind), then holds in formation. This is what
     * "recall" / "come back to me" does — a safe way to pull companions out of a fight.
     */
    private fun recall(world: World, comp: Companion) {
        val owner = CompanionRegistry.ownerOf(world, comp) ?: return
        if (comp.isAttacking()) { comp.removeCombatTarget(); comp.resetFacePawn() } // always break combat on recall
        val target = formationTile(comp, owner)
        val d = dist(comp.tile, target)
        when {
            // Height mismatch = owner took stairs/a ladder; dist() can't see it (x/z-only).
            comp.tile.height != owner.tile.height || d > TELEPORT_DIST -> comp.moveTo(walkableNear(world, target, owner.tile))
            d >= 1 -> comp.walkTo(target, StepType.FORCED_RUN)
        }
    }

    /** [t] if stand-able, else the nearest clear tile within 3, else [fallback] (the owner's own
     *  tile — always valid, the owner is standing on it). Keeps a formation-slot teleport from
     *  dumping a companion inside a wall on an upper floor / in an instance. */
    private fun walkableNear(world: World, t: Tile, fallback: Tile): Tile {
        if (!world.collision.isClipped(t)) return t
        for (r in 1..3) {
            for (dx in -r..r) for (dz in -r..r) {
                if (max(abs(dx), abs(dz)) != r) continue
                val c = Tile(t.x + dx, t.z + dz, t.height)
                if (!world.collision.isClipped(c)) return c
            }
        }
        return fallback
    }

    /** This companion's formation tile = the owner's tile + its slot offset, rotated behind the
     *  owner's facing. (forward = facing; right = facing rotated 90° CW = (fdz, -fdx).) */
    private fun formationTile(comp: Companion, owner: Player): Tile {
        val (fwd, right) = formationFR(CompanionRegistry.slotOf(comp), CompanionRegistry.countOf(comp))
        val (fdx, fdz) = CompanionRegistry.facingOf(comp)
        val dx = fwd * fdx + right * fdz
        val dz = fwd * fdz - right * fdx
        return Tile(owner.tile.x + dx, owner.tile.z + dz, owner.tile.height)
    }

    /** (forward, right) slot in facing-space — forward negative = behind. */
    private fun formationFR(slot: Int, count: Int): Pair<Int, Int> = when (count) {
        1 -> -1 to 0                                   // directly behind
        2 -> if (slot == 0) -1 to -1 else -1 to 1      // side by side
        else -> when (slot) {                          // triangle: 1 then 2
            0 -> -1 to 0
            1 -> -2 to -1
            else -> -2 to 1
        }
    }

    /** DEPLOY = go fight the boss in the home city on its own (the "send them in" order). */
    private fun deploy(world: World, comp: Companion) {
        val cur = comp.getCombatTarget() as? Npc
        if (comp.isAttacking() && cur != null && isAliveNpc(world, cur)) return
        val boss = BossScheduler.bossesIn(Cities.DEFAULT_CITY_ID).firstOrNull { isAliveNpc(world, it) }
        if (boss == null) {
            // no boss up — hold near the owner
            val owner = CompanionRegistry.ownerOf(world, comp) ?: return
            if (dist(comp.tile, owner.tile) > FOLLOW_DIST && !comp.hasMoveDestination()) comp.walkTo(owner.tile)
            return
        }
        if (dist(comp.tile, boss.tile) <= ENGAGE) comp.attack(boss)
        else if (!comp.hasMoveDestination()) comp.walkTo(boss.tile)
    }

    /** The NPC a follower should engage: what the owner is fighting, or what is attacking owner/companion. */
    private fun combatFoeNear(world: World, comp: Companion, owner: Player): Npc? {
        (owner.getCombatTarget() as? Npc)?.let {
            if (isAliveNpc(world, it) && dist(comp.tile, it.tile) <= SEEK) return it
        }
        var best: Npc? = null
        var bestDist = ENGAGE + 1
        world.npcs.forEach { npc ->
            if (!isAliveNpc(world, npc)) return@forEach
            val t = npc.getCombatTarget()
            if (t !== owner && t !== comp) return@forEach
            val d = dist(comp.tile, npc.tile)
            if (d <= ENGAGE && d < bestDist) { bestDist = d; best = npc }
        }
        return best
    }

    /**
     * FOLLOW's PvP defense: the nearest player (real or PK bot) currently attacking the owner's
     * party — the owner, this companion, or any other companion of the same owner. Party members
     * themselves are never returned. The engine's own combat gates (safe zones, single-combat,
     * can't-attack-own-companions) still apply on the actual attack.
     */
    private fun playerThreatNear(world: World, comp: Companion, owner: Player): Player? {
        var best: Player? = null
        var bestDist = ENGAGE + 1
        world.players.forEach { p ->
            if (p === owner || p === comp || p.index < 0 || p.isDead()) return@forEach
            if (p is Companion && CompanionRegistry.owns(owner, p)) return@forEach
            if (p.tile.height != comp.tile.height) return@forEach
            val t = p.getCombatTarget()
            val onParty = t === owner || t === comp || (t is Companion && CompanionRegistry.owns(owner, t))
            if (!onParty) return@forEach
            val d = dist(comp.tile, p.tile)
            if (d <= ENGAGE && d < bestDist) { bestDist = d; best = p }
        }
        return best
    }

    private fun isAlivePawn(world: World, pawn: Pawn): Boolean = when (pawn) {
        is Npc -> isAliveNpc(world, pawn)
        is Player -> pawn.index >= 0 && !pawn.isDead()
        else -> false
    }

    private fun isAliveNpc(world: World, npc: Npc): Boolean =
        npc.index >= 0 && world.npcs.contains(npc) && !npc.isDead()

    /**
     * Nearest training mob the companion can **actually path to** — closest first, but each
     * candidate is route-checked and walled-off mobs are skipped, so the companion keeps moving
     * down the list to a target it can reach instead of stalling on an unreachable one.
     */
    private fun nearestReachableTrainMob(world: World, comp: Companion): Npc? {
        val candidates = ArrayList<Pair<Npc, Int>>()
        world.npcs.forEach { npc ->
            if (!isTrainMob(world, npc)) return@forEach
            val d = dist(comp.tile, npc.tile)
            if (d <= SEEK) candidates.add(npc to d)
        }
        candidates.sortBy { it.second }
        for ((npc, _) in candidates) {
            if (canPathTo(world, comp, npc)) return npc
        }
        return null
    }

    /** True if a real route exists to a tile adjacent to [npc] (not merely "got somewhere near"). */
    private fun canPathTo(world: World, comp: Companion, npc: Npc): Boolean {
        if (comp.tile.height != npc.tile.height) return false
        val route = world.smartRouteFinder.findRoute(
            level = comp.tile.height,
            srcX = comp.tile.x,
            srcZ = comp.tile.z,
            destX = npc.tile.x,
            destZ = npc.tile.z,
            locShape = -2,
            destWidth = npc.getSize(),
            destLength = npc.getSize(),
        )
        return route.success && !route.alternative
    }

    private fun isTrainMob(world: World, npc: Npc): Boolean =
        npc.index >= 0 && world.npcs.contains(npc) && !npc.isDead() && (npc.id == GOBLIN || npc.id == HOBGOBLIN)

    /**
     * A valid hunt target: alive, on the companion's floor, within [LEASH] of the owner,
     * genuinely player-attackable (same check as [org.alter.plugins.content.combat.Combat]:
     * Attack option + a real combat def), not a [FRIENDLY], and not mid-fight with a player
     * outside the owner's party (no stealing a stranger's kill).
     */
    private fun isHuntMob(world: World, comp: Companion, owner: Player, npc: Npc): Boolean {
        if (npc.index < 0 || !world.npcs.contains(npc) || npc.isDead()) return false
        if (npc.tile.height != comp.tile.height) return false
        if (npc.id in FRIENDLY) return false
        if (!npc.def.isAttackable() || npc.combatDef.hitpoints == -1) return false
        if (dist(owner.tile, npc.tile) > LEASH) return false
        val t = npc.getCombatTarget()
        if (t is Player && t !== owner && t !== comp &&
            !(t is Companion && CompanionRegistry.owns(owner, t))
        ) return false
        return true
    }

    /** Nearest hunt target the companion can actually path to — same route-checked walk down the
     *  candidate list as [nearestReachableTrainMob], with the [isHuntMob] filter. */
    private fun nearestReachableHuntMob(world: World, comp: Companion, owner: Player): Npc? {
        val candidates = ArrayList<Pair<Npc, Int>>()
        world.npcs.forEach { npc ->
            if (!isHuntMob(world, comp, owner, npc)) return@forEach
            candidates.add(npc to dist(comp.tile, npc.tile))
        }
        candidates.sortBy { it.second }
        for ((npc, _) in candidates) {
            if (canPathTo(world, comp, npc)) return npc
        }
        return null
    }

    private fun dist(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.z - b.z))
}

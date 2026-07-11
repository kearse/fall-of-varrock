package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.message
import org.alter.plugins.content.announce.Announce
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.collision.isClipped
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.walkTo
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.war.boss.BossScheduler
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * A squad's orders. [ADVANCE] = the men march on the objective **by themselves** — the donor pays
 * and their troops go fight the boss for them, earning the damage-share credit without the player
 * being present. [FOLLOW] = the men guard the sponsor and travel with them (escort).
 */
enum class SquadMode { ADVANCE, FOLLOW }

/**
 * One running offensive operation: a [CampaignTier]-sized band of allied troops that **escort the
 * sponsor**. They muster on the sponsor in the city and FOLLOW them out to the fight, cutting down
 * any enemy NPC — or PKer — that threatens the player along the way, and piling onto the boss /
 * garrison once the player leads them to it. So the player walks up *with* their troops.
 *
 * The enemy set is the target city's live frontier enemies ([Frontiers]) plus any boss in the city
 * ([BossScheduler]). A campaign/conquest also suppresses the frontier's respawn while it runs, so
 * the ground the army clears actually stays cleared (the kill quota can be met). The offensive
 * analogue of [AttackDirector], written fresh.
 */
class CampaignDirector(
    private val op: CampaignOp,
    val tier: CampaignTier,
    /** The Lord who paid for this squad — or null for a realm-sponsored [CampaignTier.MARCH]. */
    private val sponsor: Player?,
    private val onFinished: (CampaignDirector) -> Unit,
) {
    val cityId: Int get() = op.cityId

    /** The frontier key this op besieges (matches [FrontierConfig.cityKey]) — the signal a
     *  campaign-gated city's frontier reads to know it should muster its garrison. */
    val targetCityKey: String get() = op.cityKey

    private enum class Phase { ACTIVE, DONE }
    private var phase = Phase.ACTIVE
    private val troops = ArrayList<Npc>()
    /** Every troop ever spawned for this squad (NOT pruned on death). Holding the refs keeps the
     *  boss's [org.alter.game.model.combat.DamageMap] entries (weak-keyed on the troop) alive, so a
     *  slain troop's damage still counts toward its Lord's payout. */
    private val allTroops = ArrayList<Npc>()
    private var ticks = 0

    /** The Lord who paid for this squad, or null for a realm-sponsored march. */
    val owner: Player? get() = sponsor

    /** True if [player] is this squad's sponsor (by name, so it survives a relog within the op). */
    fun ownedBy(player: Player): Boolean = sponsor?.username?.equals(player.username, ignoreCase = true) == true

    /** Where a late joiner rallies to (`::march`): the column's head, or the muster if none stand. */
    fun rallyTile(world: World): Tile = troops.firstOrNull { isAlive(world, it) }?.tile ?: op.stagingTile

    /** True if [tile] is inside this campaign's battlefield (where enemy loot pools to [lootPool]). */
    fun coversBattle(tile: Tile): Boolean = op.battleArea.contains(tile)

    /** 0-100 "how far through" this campaign is = enemies cleared toward the kill quota. */
    fun progressPct(world: World): Int {
        val living = Frontiers.zone(op.cityKey)?.livingEnemies(world)?.size ?: 0
        val cleared = (startEnemies - living).coerceAtLeast(0)
        return (cleared * 100 / tier.quota.coerceAtLeast(1)).coerceIn(0, 100)
    }

    /** Label code for the client progress bar: 1 = Conquest, else Campaign. */
    val campaignTierCode: Int get() = if (tier == CampaignTier.CONQUEST) 1 else 0

    /** Divert a slain enemy's loot VALUE into the campaign pool (instead of a floor drop). Only a
     *  full campaign/conquest pools loot — a boss-backing RAID leaves frontier drops on the floor. */
    fun creditLoot(value: Int) {
        if (phase == Phase.ACTIVE && tier != CampaignTier.RAID && value > 0) lootPool += value
    }

    /** TEST ONLY (`::wintest`): seed [seedValue] gp into the war-chest, make sure [participant] counts
     *  as a fighter, and resolve the op as a VICTORY now — so the tithe → auto-bank → donor-points
     *  payout can be verified without grinding the kill quota. */
    fun forceVictory(world: World, seedValue: Int, participant: Player) {
        if (phase != Phase.ACTIVE) return
        creditLoot(seedValue)
        participation.merge(participant, 1, Int::plus)
        finish(world, victory = true)
    }

    /** Total damage this squad's men have dealt to [target] (for the Lord's damage-share cut). */
    fun troopDamageTo(target: Npc): Int = allTroops.sumOf { target.damageMap.getDamageFrom(it) }
    private var startEnemies = 0
    /** The aggregate gp-VALUE of everything this campaign's enemies dropped, pooled instead of hitting
     *  the floor (see [CampaignRegistry.creditCityKill]). Split among participants by contribution at
     *  the end ([CapturePayout]). Long so a long raid's pool can't overflow Int. */
    private var lootPool = 0L
    /** Rough per-player contribution = ticks spent fighting in the battle area. */
    private val participation = HashMap<Player, Int>()
    /** All campaign troops read as the realm's knights (not "<player>'s Soldier"). */
    private val troopName = "Knight of Lumbridge"
    /** Squad orders. Defaults to [SquadMode.ADVANCE] (the donor's men go fight on their own); switch
     *  to [SquadMode.FOLLOW] with `::troops follow`. */
    var mode: SquadMode = SquadMode.ADVANCE

    // --- cross-country march state (campaign/conquest with a [CampaignOp.route]) ---
    /** A marching op: a non-RAID campaign with a hand-authored route. The column spawns in waves and
     *  advances the route waypoint-by-waypoint, vs the boss-raid "muster on the player" behaviour. */
    private val marching = tier != CampaignTier.RAID && op.route.isNotEmpty()
    /** The route waypoints snapped to walkable land (computed in [init] once collision is loaded). */
    private var marchRoute: List<Tile> = emptyList()
    /** Troops still to spawn — staggered over ticks so the army forms a marching column, not a blob. */
    private var pendingSpawns = 0
    /** Per-troop march progress: index of the waypoint it is currently heading toward. */
    private val routeIdx = HashMap<Npc, Int>()
    /** Per-troop stuck detection: last distance to its target waypoint + consecutive no-progress ticks. */
    private val lastDist = HashMap<Npc, Int>()
    private val stuckTicks = HashMap<Npc, Int>()
    /** How many times each troop has been teleport-unstuck (capped; hopeless stragglers are culled). */
    private val unsticks = HashMap<Npc, Int>()
    /** Per-troop ticks spent trying to CLOSE on a combat target without reaching melee (unreachable
     *  enemy, e.g. one stuck behind a fence). */
    private val engageStall = HashMap<Npc, Int>()
    /** Per-troop: enemy index -> tick until which that enemy is ignored (abandoned as unreachable), so
     *  the column doesn't re-lock onto a foe it can't path to and stall the whole march. */
    private val ignoreEnemy = HashMap<Npc, HashMap<Int, Int>>()

    fun init(world: World) {
        val zone = Frontiers.zone(op.cityKey)
        // Only a full campaign/conquest freezes the city's respawn (so clearing the garrison
        // sticks). A small boss-backing RAID party just adds muscle — it must NOT halt the
        // frontier, or summoning a boss would freeze the newbie training line for the fight.
        if (tier != CampaignTier.RAID) {
            // A campaign-gated city (e.g. Varrock) keeps NO garrison until it's attacked — muster it
            // NOW, before we read the start count below, or the kill-quota maths would see 0 enemies.
            // (No-op for an already-populated, presence-gated home frontier.)
            zone?.muster(world)
            zone?.suppressed = true
        }
        startEnemies = zone?.livingEnemies(world)?.size ?: 0
        // A boss-backing RAID musters ON the sponsor (they escort the player to the boss). A
        // campaign/conquest musters at the target city's staging tile — the front — where the
        // commander is teleported on launch; so it doesn't matter where the player ran the command.
        val anchor = if (tier == CampaignTier.RAID && sponsor != null && sponsor.index >= 0 && !sponsor.isDead()) sponsor.tile else op.stagingTile
        if (marching) {
            // Snap each waypoint to walkable land, then spawn the FIRST wave at the muster; the rest
            // trickle in over the next ticks ([spawnWave]) so the army forms up as a marching column.
            marchRoute = op.route.map { walkableNear(world, it) }
            pendingSpawns = tier.troops
            spawnWave(world, marchRoute.first())
        } else {
            repeat(tier.troops) { spawnTroop(world, anchor) }
        }
        logger.info { "Campaign '${op.cityKey}' ${tier.name} [$mode] marching=$marching: ${troops.size}/${tier.troops} '$troopName' mustering at $anchor (start enemies=$startEnemies)." }
        val cry = when {
            sponsor == null ->
                "<col=4f9b4f>The Knights of Lumbridge march on ${op.displayName}! Any soldier of the realm may fight beside them — <col=ffae00>::march</col><col=4f9b4f> to rally to the column.</col>"
            tier == CampaignTier.RAID ->
                "<col=4f9b4f>${sponsor.username}, ${sponsor.title.display}, sends their men to the fight!</col>"
            else ->
                "<col=4f9b4f>${sponsor.username}, ${sponsor.title.display}, marches a ${tier.display} on ${op.displayName}!</col>"
        }
        broadcast(world, cry)
    }

    /** Spawn up to [SPAWN_PER_WAVE] of the still-pending troops at [anchor] (the staggered column fill). */
    private fun spawnWave(world: World, anchor: Tile) {
        var n = 0
        while (pendingSpawns > 0 && n < SPAWN_PER_WAVE) {
            spawnTroop(world, anchor)
            pendingSpawns--; n++
        }
    }

    private fun spawnTroop(world: World, anchor: Tile) {
        val tile = world.findRandomTileAround(anchor, radius = 3) ?: anchor
        val npc = Npc(getRSCM(op.alliedNpc), tile, world)
        npc.walkRadius = 14
        world.spawn(npc)
        WarNpcNames.rename(npc, troopName) // distinct from the frontier "Knight of Lumbridge"
        val d = op.alliedDef
        npc.stats.setMaxLevel(NpcSkills.ATTACK, d.attack); npc.stats.setCurrentLevel(NpcSkills.ATTACK, d.attack)
        npc.stats.setMaxLevel(NpcSkills.STRENGTH, d.strength); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, d.strength)
        npc.stats.setMaxLevel(NpcSkills.DEFENCE, d.defence); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, d.defence)
        npc.setCurrentHp(d.hitpoints)
        npc.combatDef = d
        npc.aggroCheck = { _, _ -> false } // allies never aggro players; steered onto enemies below
        npc.respawns = false
        npc.setActive(true)
        npc.routeLogic = 1
        troops += npc
        allTroops += npc
    }

    fun tick(world: World) {
        if (phase == Phase.DONE) return
        try {
            ticks++
            troops.removeAll { it.index < 0 || !world.npcs.contains(it) || it.isDead() }
            if (marching && pendingSpawns > 0) spawnWave(world, marchRoute.first()) // trickle the column in
            val zone = Frontiers.zone(op.cityKey)
            // Everything the escort may fight: the city's frontier enemies + any boss in the city.
            // No battle-area filter — troops defend the player wherever the player leads them.
            val enemies = ((zone?.livingEnemies(world) ?: emptyList()) + BossScheduler.bossesIn(op.cityId))
                .filter { isAlive(world, it) }
            steer(world, enemies)
            antiStack(world) // spread troops off shared tiles so the battle line fans out
            recordParticipation(world)
            if (debug && marching) {
                logger.info {
                    "MARCHDBG t=$ticks pending=$pendingSpawns troops=${troops.size} " +
                        troops.joinToString(" ") { "${it.tile.x},${it.tile.z}@${routeIdx[it] ?: 0}" }
                }
            }
            resolve(world, zone, enemies)
        } catch (e: Throwable) {
            // Throwable, not Exception — a campaign tick must never kill the game loop.
            logger.error(e) { "Campaign '${op.cityKey}' tick failed (skipped)" }
        }
    }

    private fun steer(world: World, enemies: List<Npc>) {
        when {
            mode == SquadMode.FOLLOW -> steerFollow(world, enemies)
            marching -> steerMarch(world, enemies)
            else -> steerAdvance(world, enemies)
        }
    }

    /**
     * MARCH (campaign/conquest with a [CampaignOp.route]): the column advances the waypoint chain to
     * the enemy city, fighting any enemy within [ENGAGE] along the way, then brawls the garrison on
     * arrival. Reliability comes from short hops + a **teleport-unstick**: a troop that makes no
     * progress toward its waypoint for [STUCK_LIMIT] ticks is teleported to the next one (capped by
     * [MAX_UNSTICKS]; a hopeless straggler is culled — losing a few is acceptable).
     */
    private fun steerMarch(world: World, enemies: List<Npc>) {
        val lastWp = marchRoute.lastIndex
        val now = ticks
        // Single combat for the NPC battle: each enemy is fought by at most ONE knight, so the war
        // reads as clashing lines of duels (this governs only the knights' targeting — players and
        // PKers can still double-team an enemy freely). Seed with enemies already engaged this tick.
        val claimed = HashSet<Int>()
        for (k in troops) { val t = k.getCombatTarget(); if (t is Npc && isAlive(world, t)) claimed.add(t.index) }
        val iter = troops.iterator()
        while (iter.hasNext()) {
            val k = iter.next()
            // The column only "goes hot" once it reaches the engagement line ([aggroFromArea] — the
            // enemy city's doorstep, e.g. the Varrock stone circle). Before that it marches straight
            // through any road skirmish, never stopping to fight even if struck.
            val canFight = op.aggroFromArea?.contains(k.tile) ?: true
            if (canFight) {
                // 1) handle the current combat target. If we're in melee, fight on. If we've been trying
                //    to CLOSE on it for too long, it's unreachable (e.g. behind a fence) — abandon and
                //    ignore it briefly so the column doesn't stall locked onto a foe it can't path to.
                val cur = k.getCombatTarget()
                if (cur is Npc && isAlive(world, cur)) {
                    if (dist(k.tile, cur.tile) <= 1) { engageStall.remove(k); continue } // in melee — fine
                    val s = (engageStall[k] ?: 0) + 1
                    if (s < ENGAGE_STALL) { engageStall[k] = s; continue } // give it a moment to close
                    ignoreEnemy.getOrPut(k) { HashMap() }[cur.index] = now + IGNORE_TICKS
                    engageStall.remove(k); k.removeCombatTarget(); k.resetFacePawn()
                }
                // 2) engage the nearest UNCLAIMED enemy in reach we haven't given up on (1v1 — one
                //    knight per enemy). Extra knights flow past to find their own foe.
                val foe = enemies
                    .filter { dist(k.tile, it.tile) <= ENGAGE && !isIgnored(k, it.index, now) && !claimed.contains(it.index) }
                    .minByOrNull { dist(k.tile, it.tile) }
                if (foe != null) { k.attack(foe); claimed.add(foe.index); engageStall.remove(k); continue }
                if (k.isAttacking()) { k.removeCombatTarget(); k.resetFacePawn() }
            } else if (k.isAttacking()) {
                k.removeCombatTarget(); k.resetFacePawn() // not hot yet — shrug off road fights and march
            }
            // 3) advance the route.
            val idx = routeIdx[k] ?: 0
            if (idx >= lastWp) { // arrived at the city — hold near the objective (enemies handled above)
                if (dist(k.tile, marchRoute[lastWp]) > 2) k.walkTo(walkableNear(world, marchRoute[lastWp]))
                continue
            }
            val target = marchRoute[idx + 1]
            val d = dist(k.tile, target)
            if (d <= WAYPOINT_REACH) { // reached this waypoint — next tick kicks off the next leg
                routeIdx[k] = idx + 1; stuckTicks.remove(k); lastDist.remove(k)
                continue
            }
            // Movement: issue a walk ONLY when starting this leg (prev == null) or when blocked. Re-
            // issuing every tick is what made them wobble back and forth — while a troop is making
            // progress we leave its existing walk untouched.
            val prev = lastDist[k]
            when {
                prev == null -> k.walkTo(walkableNear(world, target)) // kick off the leg
                d >= prev -> { // no progress this tick — blocked
                    val s = (stuckTicks[k] ?: 0) + 1
                    if (s >= STUCK_LIMIT) {
                        val uc = unsticks[k] ?: 0
                        if (uc < MAX_UNSTICKS) { // teleport across the snag to the next waypoint
                            if (debug) logger.info { "MARCHDBG unstick troop@(${k.tile.x},${k.tile.z}) -> (${target.x},${target.z}) uc=${uc + 1}" }
                            k.moveTo(target)
                            unsticks[k] = uc + 1; routeIdx[k] = idx + 1; stuckTicks.remove(k); lastDist.remove(k)
                        } else { // hopeless straggler — cull it
                            if (k.index >= 0 && world.npcs.contains(k)) world.remove(k)
                            iter.remove()
                            routeIdx.remove(k); stuckTicks.remove(k); lastDist.remove(k); unsticks.remove(k)
                            engageStall.remove(k); ignoreEnemy.remove(k)
                        }
                        continue
                    }
                    stuckTicks[k] = s
                    k.walkTo(walkableNear(world, target)) // nudge: re-path around the blockage
                }
                else -> stuckTicks[k] = 0 // making progress — leave the existing walk alone
            }
            lastDist[k] = d
        }
    }

    /**
     * ADVANCE (the donor default): the men march on the objective **by themselves** — the boss if
     * one is up, else the op's push target. While *marching* they only cut down an enemy directly
     * in their path (so they push through to the boss instead of chasing every goblin); once they
     * *arrive* (within [ENGAGE] of the objective) they brawl everything in reach — the boss and its
     * guards. The Lord need not be present and still earns their squad's damage share.
     */
    private fun steerAdvance(world: World, enemies: List<Npc>) {
        val objective = BossScheduler.bossesIn(op.cityId).firstOrNull()?.tile ?: op.objectiveTile
        for (k in troops) {
            val cur = k.getCombatTarget()
            if (k.isAttacking() && cur is Npc && isAlive(world, cur)) continue
            val arrived = dist(k.tile, objective) <= ENGAGE
            val reach = if (arrived) ENGAGE else 1 // brawl on arrival; only hit blockers while marching
            val foe = enemies.filter { dist(k.tile, it.tile) <= reach }.minByOrNull { dist(k.tile, it.tile) }
            if (foe != null) {
                k.attack(foe)
            } else {
                if (k.isAttacking()) { k.removeCombatTarget(); k.resetFacePawn() }
                if (dist(k.tile, objective) > 2) k.walkTo(walkableNear(world, objective))
            }
        }
    }

    /**
     * FOLLOW (escort) behaviour, per troop, each tick:
     *  1. keep fighting a live target;
     *  2. else defend the leader from a **PKer** — any player currently attacking the sponsor;
     *  3. else strike the nearest enemy NPC threatening the troop or the leader (the boss counts);
     *  4. else **follow the leader**, closing to within [FOLLOW_DIST]. If the leader has left the
     *     world, fall back to holding the objective tile.
     */
    private fun steerFollow(world: World, enemies: List<Npc>) {
        val leader = sponsor ?: return steerAdvance(world, enemies) // realm marches have no leader
        val leaderAlive = leader.index >= 0 && !leader.isDead()
        for (k in troops) {
            val cur = k.getCombatTarget()
            if (k.isAttacking() && cur != null && isAlivePawn(world, cur)) continue

            val pker = if (leaderAlive) {
                world.players.firstOrNull { p ->
                    p.index >= 0 && !p.isDead() && p !== leader && p.getCombatTarget() === leader
                }
            } else null

            val foe = enemies
                .filter { dist(k.tile, it.tile) <= ENGAGE || (leaderAlive && dist(leader.tile, it.tile) <= ENGAGE) }
                .minByOrNull { dist(k.tile, it.tile) }

            when {
                pker != null -> k.attack(pker)
                foe != null -> k.attack(foe)
                leaderAlive && dist(k.tile, leader.tile) > FOLLOW_DIST -> {
                    if (k.isAttacking()) { k.removeCombatTarget(); k.resetFacePawn() }
                    k.walkTo(walkableNear(world, leader.tile))
                }
                !leaderAlive && dist(k.tile, op.objectiveTile) > 2 -> {
                    if (k.isAttacking()) { k.removeCombatTarget(); k.resetFacePawn() }
                    k.walkTo(walkableNear(world, op.objectiveTile))
                }
            }
        }
    }

    /**
     * Spread troops off shared tiles (these battle NPCs don't block one another, so they'd otherwise
     * pile onto one tile and a 40-on-40 fight would render as two figures). The troop in melee with
     * its target holds its ground; the rest step to a free neighbour and wait their turn — mirrors the
     * goblins' anti-stack ([HostileZone.enforceSingleCombat]), so the battle line fans out.
     */
    private fun antiStack(world: World) {
        val living = troops.filter { isAlive(world, it) }
        if (living.size < 2) return
        val occ = HashMap<Long, Int>()
        for (k in living) {
            val key = world.npcTileKey(k.tile.x, k.tile.z, k.tile.height)
            occ[key] = (occ[key] ?: 0) + 1
        }
        for (k in living) {
            val here = world.npcTileKey(k.tile.x, k.tile.z, k.tile.height)
            if ((occ[here] ?: 0) <= 1) continue
            val tgt = k.getCombatTarget()
            if (tgt != null && dist(k.tile, tgt.tile) <= 1) continue // mid-melee — hold the line
            for (dir in Direction.RS_ORDER) {
                if (!world.canTraverse(k.tile, dir, k)) continue
                val nbr = k.tile.transform(dir.getDeltaX(), dir.getDeltaZ())
                val nk = world.npcTileKey(nbr.x, nbr.z, nbr.height)
                if ((occ[nk] ?: 0) == 0) {
                    k.walkTo(nbr); occ[here] = occ[here]!! - 1; occ[nk] = 1; break
                }
            }
        }
    }

    /** Credit players who are in the battle area and actually fighting (caps AFK leeching). */
    private fun recordParticipation(world: World) {
        world.players.forEach { p ->
            if (p.index >= 0 && !p.isDead() && op.battleArea.contains(p.tile) && p.isAttacking()) {
                participation.merge(p, 1, Int::plus)
            }
        }
    }

    private fun resolve(world: World, zone: HostileZone?, enemies: List<Npc>) {
        val cleared = (startEnemies - (zone?.livingEnemies(world)?.size ?: 0)).coerceAtLeast(0)
        val bossUp = BossScheduler.bossesIn(op.cityId).isNotEmpty()
        val won = when (tier) {
            CampaignTier.RAID -> ticks > RAID_GRACE && !bossUp       // the summoned boss has fallen
            else -> cleared >= tier.quota                            // the garrison has been broken
        }
        when {
            won -> finish(world, victory = true)
            ticks >= op.timeoutTicks -> finish(world, victory = false)
            troops.isEmpty() && enemies.isNotEmpty() && noPlayersFighting(world) -> finish(world, victory = false)
        }
    }

    private fun noPlayersFighting(world: World): Boolean =
        world.players.none { it.index >= 0 && !it.isDead() && op.battleArea.contains(it.tile) && it.isAttacking() }

    private fun finish(world: World, victory: Boolean) {
        phase = Phase.DONE
        Frontiers.zone(op.cityKey)?.suppressed = false // rings repopulate as normal
        troops.forEach { if (it.index >= 0 && world.npcs.contains(it)) world.remove(it) }
        troops.clear()

        if (victory) {
            if (tier == CampaignTier.RAID) {
                // The boss + its loot already rewarded everyone (BossLoot); the party just disbands.
                broadcast(world, "<col=4f9b4f>The raid party's work in ${op.displayName} is done.</col>")
            } else {
                val whose = sponsor?.let { "${it.username}'s" } ?: "The realm's"
                broadcast(world, "<col=ffcc00>${op.displayName} is taken! $whose ${tier.display} seizes the spoils.</col>")
                CapturePayout.award(world, op, tier, participation, sponsor, lootPool)
                if (sponsor != null && sponsor.index >= 0) sponsor.addPoints(PointKind.PRESTIGE, tier.prestige)
            }
        } else {
            val whose = sponsor?.let { "${it.username}'s" } ?: "The realm's"
            broadcast(world, "<col=801700>$whose ${tier.display} was driven back from ${op.displayName}.</col>")
        }
        onFinished(this)
    }

    // Campaign rallies/captures are headlines → route through the announcement ticker.
    private fun broadcast(world: World, msg: String) = Announce.broadcast(world, msg)

    private fun isAlive(world: World, npc: Npc): Boolean =
        npc.index >= 0 && world.npcs.contains(npc) && !npc.isDead()

    private fun isAlivePawn(world: World, pawn: Pawn): Boolean = when (pawn) {
        is Npc -> isAlive(world, pawn)
        is Player -> pawn.index >= 0 && !pawn.isDead()
        else -> false
    }

    /** Nearest stand-able tile to [t] (the tile itself if clear), out to [SNAP_RADIUS]. */
    private fun walkableNear(world: World, t: Tile): Tile {
        if (!world.collision.isClipped(t)) return t
        for (r in 1..SNAP_RADIUS) {
            for (dx in -r..r) for (dz in -r..r) {
                if (max(abs(dx), abs(dz)) != r) continue
                val c = Tile(t.x + dx, t.z + dz, t.height)
                if (!world.collision.isClipped(c)) return c
            }
        }
        return t
    }

    private fun dist(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.z - b.z))

    /** True if troop [k] is currently ignoring enemy [enemyIndex] (abandoned as unreachable). Expired
     *  entries are purged on read. */
    private fun isIgnored(k: Npc, enemyIndex: Int, now: Int): Boolean {
        val m = ignoreEnemy[k] ?: return false
        val until = m[enemyIndex] ?: return false
        if (now >= until) { m.remove(enemyIndex); return false }
        return true
    }

    companion object {
        /** Admin debug: when on, marching squads log every troop's tile + waypoint index each tick
         *  ("MARCHDBG ...") and every teleport-unstick, so the column's movement can be read from the
         *  server log. Toggled by `::marchdebug`. */
        var debug = false

        const val ENGAGE = 12      // an allied troop engages an enemy within this many tiles
        const val FOLLOW_DIST = 4  // troops close to within this many tiles of the leader
        const val SNAP_RADIUS = 5
        const val RAID_GRACE = 5   // ticks before a raid party may declare the boss "fallen"
        const val SPAWN_PER_WAVE = 2 // troops spawned per tick on the march (the "two-by-two" column)
        const val WAYPOINT_REACH = 3 // advance to the next waypoint within this many tiles
        const val STUCK_LIMIT = 6    // no-progress ticks toward a waypoint before a teleport-unstick
        const val MAX_UNSTICKS = 8   // teleport-unsticks per troop before it's culled as hopeless
        const val ENGAGE_STALL = 5   // ticks trying to close on a target before abandoning it as unreachable
        const val IGNORE_TICKS = 25  // how long a troop ignores an abandoned (unreachable) enemy
    }
}

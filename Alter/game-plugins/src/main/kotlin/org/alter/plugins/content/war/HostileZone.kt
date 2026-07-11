package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.game.model.Area
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.LAST_HIT_BY_ATTR
import org.alter.game.model.collision.isClipped
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.move.walkTo
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.combat.isAttacking
import org.alter.plugins.content.combat.isBeingAttacked
import org.alter.plugins.content.combat.removeCombatTarget
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/** Which side a [MonsterPack] fights for inside a [HostileZone]. */
enum class Faction { ENEMY, FRIENDLY }

/**
 * One kind of monster maintained in a [HostileZone].
 *
 * [respawnDelay] is measured in zone tick-cycles (~3s each) — 0 means instant
 * (trash mobs); bosses use a delay so they aren't an endless coin faucet.
 * [atk]/[str]/[def]/[hp] override the cache combat levels (null = use cache), for
 * turning an ordinary npc into a buffed boss.
 *
 * [faction] makes a pack pick a side in the NPC-vs-NPC skirmish a zone runs when it
 * holds BOTH an [Faction.ENEMY] and a [Faction.FRIENDLY] pack (the city-frontier
 * "good guys vs the enemy lines" case). A zone with only enemies behaves exactly as
 * before (engine aggro on players, no skirmish) — so existing zones are untouched.
 * [objective] is the ordered ring/line a FRIENDLY pack marches toward when it has no
 * foe in reach (e.g. the targeted enemy line's muster tiles); ENEMY packs ignore it
 * and simply hold their ring, fighting back whatever reaches them.
 * [displayName], if set, renames the npc at spawn via [WarNpcNames] (no cache edit).
 */
class MonsterPack(
    val npcName: String,
    val count: Int,
    val staging: List<Tile>,
    val walkRadius: Int = 6,
    val respawnDelay: Int = 0,
    val atk: Int? = null,
    val str: Int? = null,
    val def: Int? = null,
    val hp: Int? = null,
    val faction: Faction = Faction.ENEMY,
    val objective: List<Tile> = emptyList(),
    val displayName: String? = null,
    /**
     * 1v1 against players: an enemy in this pack won't aggro a player who's already being
     * fought by a pack-mate, and a per-tick sweep releases extras so at most ONE of this
     * pack is locked onto each player. For the new-player front line, so it isn't a swarm.
     * (Pack-vs-knight combat and other packs are unaffected.)
     */
    val singleCombat: Boolean = false,
    /** Override the client-displayed combat level at spawn (the cache level otherwise). Use to
     *  make a line read as e.g. level 2 for new players regardless of the npc's cache level. */
    val combatLevelOverride: Int? = null,
    /** Rank-based aggro floor (master design brief §4): this pack ignores players whose feudal rank
     *  ordinal is >= this value. Default = never ignore (everyone is fair game). e.g. set to
     *  [Title.SQUIRE].ordinal so goblins leave Squire+ alone — ranking up *feels* like power. */
    val aggroFloorRank: Int = Int.MAX_VALUE,
    /**
     * Base combat def supplying the unit's ATTACK ANIMATION (and fallback stats) without a
     * global `setCombatDef` registration. Used for friendlies (knights) whose npc id is
     * shared with the war system — registering a global def for that id would clobber it.
     * Enemies leave this null: the owning plugin already registered their def globally, so
     * the cache-resolved `npc.combatDef` is correct.
     */
    val combatDef: org.alter.game.model.combat.NpcCombatDef? = null,
) {
    /**
     * One slot per unit; slot `i` lives at `staging[i % staging.size]` and is refilled IN PLACE
     * when its npc dies — so a cleared patch repopulates where it was emptied, instead of every
     * respawn piling onto one staging point (the old `staging[pool.size]` bug = "they never came
     * back" because all refills landed at the last index).
     */
    internal var slots: Array<Npc?> = arrayOfNulls(0)
    internal var respawnReadyAt = 0

    /** Currently-spawned npcs (liveness is filtered by the caller). */
    internal val pool: List<Npc> get() = slots.filterNotNull()
}

/**
 * A hostile area beyond the cities (The War / open world — `docs/war-system-design.md`:
 * "only cities are safe"). Maintains packs of monsters that roam, aggro, and fight
 * back with real combat stats. Loot is handled by the owning plugin's `onNpcDeath`.
 * Mirrors the robust spawn/maintain pattern from [WarFront].
 *
 * When a zone holds at least one [Faction.FRIENDLY] pack it also runs a lightweight
 * NPC-vs-NPC [skirmish] each tick: enemies fight back whatever friendly (or player)
 * reaches them, and friendlies march toward their [MonsterPack.objective] line,
 * engaging any enemy on the way. Zones with no friendly pack skip the skirmish
 * entirely, so the world-boss / goblin-warren zones are byte-for-byte unchanged.
 */
class HostileZone(
    val name: String,
    private val packs: List<MonsterPack>,
    /** A protected area (e.g. the city) that ENEMY npcs never aggro into and are leashed out
     *  of — so the town stays safe no matter how thin the spawn buffer is. Null = no leash. */
    private val safeArea: Area? = null,
    /** The castle **keep** — the new-player respawn courtyard ([Sieges.LUMBRIDGE_KEEP]). Treated
     *  exactly like [safeArea] (no aggro in, leashed out) but kept as its OWN box so the spawn stays
     *  hard-protected even if [safeArea] is ever shrunk. Usually a sub-area of [safeArea]; null =
     *  none. This is the frontier half of the "goblins never enter the castle" rule (the war half
     *  lives in [WarFront.keep]). */
    private val keep: Area? = null,
    /**
     * Activation gate: each tick the zone is "live" only while this returns true. When it flips to
     * false the whole garrison is **despawned** and nothing is maintained — so a city nobody is in
     * costs ZERO npc-cycle time (the empty-world logs showed 567 idle npcs being processed with 0
     * players online). Home frontiers pass a player-presence check; an enemy city passes a
     * campaign-active check (its garrison exists only while it's under attack). Default always-on
     * preserves the old behaviour for any zone that doesn't opt in (e.g. the goblin warren).
     */
    private val activeWhen: (World) -> Boolean = { true },
) {
    private var ticks = 0
    private val hasFriendlies = packs.any { it.faction == Faction.FRIENDLY }
    private val hasSingleCombat = packs.any { it.faction == Faction.ENEMY && it.singleCombat }

    /** Whether the gate ([activeWhen]) is currently open. Starts closed: a zone spawns nothing until
     *  something (a nearby player, or a campaign) activates it. */
    private var live = false

    /**
     * While true, [maintain] stops refilling dead enemy slots, so the frontier **bleeds out**
     * instead of refilling. An allied campaign sets this on the target city's zone (via
     * [Frontiers]) so clearing the path actually thins it; it clears the flag when the
     * campaign ends and the rings repopulate as normal.
     */
    var suppressed = false

    /** Living ENEMY-faction npcs in this zone — what an allied campaign fights and counts. */
    fun livingEnemies(world: World): List<Npc> = alive(world, Faction.ENEMY)

    fun tick(world: World) {
        try {
            ticks++
            // Gate: open/close on the activation predicate. On close, clear the whole garrison so a
            // dormant city costs nothing; on open, maintain() below fills it next.
            val shouldBeLive = activeWhen(world)
            if (shouldBeLive != live) {
                live = shouldBeLive
                if (!live) despawnAll(world)
            }
            if (!live) return // dormant — no upkeep, skirmish, or leash work while nobody's here
            for (pack in packs) maintain(world, pack)
            if (hasFriendlies) {
                // Build the living enemy/friendly views ONCE per tick (skirmish used to rebuild both
                // itself every call) and hand them to the skirmish.
                val enemies = alive(world, Faction.ENEMY)
                val friendlies = alive(world, Faction.FRIENDLY)
                defendCitizens(world)
                skirmish(world, enemies, friendlies)
            }
            if (safeArea != null) leash(world, safeArea)
            if (keep != null) leash(world, keep) // hard-protect the spawn courtyard (own box)
        } catch (e: Throwable) {
            // Throwable (not just Exception) so even an Error here can never kill the game loop.
            logger.error(e) { "HostileZone '$name' tick failed (skipped this tick)" }
        }
    }

    /**
     * Force the zone live and fill every slot **right now**. Called when a campaign launches against
     * a campaign-gated city so its garrison exists before [CampaignDirector] reads the start count —
     * the zone's own tick is on a separate timer and would otherwise lag a tick behind (the quota
     * maths would see 0 enemies). Idempotent: a no-op if the zone is already populated.
     */
    fun muster(world: World) {
        live = true
        for (pack in packs) maintain(world, pack)
    }

    /** Remove every spawned npc and clear the slots — the zone goes quiet when its gate closes. */
    private fun despawnAll(world: World) {
        for (pack in packs) {
            for (i in pack.slots.indices) {
                val n = pack.slots[i] ?: continue
                if (n.index >= 0 && world.npcs.contains(n)) world.remove(n)
                pack.slots[i] = null
            }
            pack.respawnReadyAt = 0
        }
    }

    /**
     * The 1v1 sweep, run on its OWN fast (every-game-tick) timer by the plugin — far tighter than
     * the ~1.2s zone tick, so a second goblin that aggros is released before it can land a hit.
     * Guarded so it can never propagate into the game loop.
     */
    fun enforceSingleCombatTick(world: World) {
        if (!hasSingleCombat) return
        try {
            enforceSingleCombat(world)
        } catch (e: Throwable) {
            logger.error(e) { "HostileZone '$name' single-combat sweep failed" }
        }
    }

    /** Keep every slot filled: refill a dead slot AT ITS OWN staging point, honouring respawnDelay. */
    private fun maintain(world: World, pack: MonsterPack) {
        if (pack.count <= 0 || pack.staging.isEmpty()) return
        if (pack.slots.size != pack.count) pack.slots = arrayOfNulls(pack.count)

        var died = false
        for (i in pack.slots.indices) {
            val cur = pack.slots[i]
            if (cur != null && (cur.index < 0 || cur.isDead())) { pack.slots[i] = null; died = true }
        }
        if (died) pack.respawnReadyAt = ticks + pack.respawnDelay
        if (suppressed && pack.faction == Faction.ENEMY) return // campaign active: bleed out, don't refill
        if (ticks < pack.respawnReadyAt) return

        for (i in pack.slots.indices) {
            if (pack.slots[i] != null) continue
            val staging = pack.staging[i % pack.staging.size]
            val tile = world.findRandomTileAround(staging, radius = 4) ?: staging
            val npc = Npc(getRSCM(pack.npcName), tile, world)
            npc.walkRadius = pack.walkRadius
            world.spawn(npc)
            if (pack.displayName != null) WarNpcNames.apply(npc, pack.npcName)
            pack.combatLevelOverride?.let { npc.overrideLevel(it) } // display level (e.g. 2 for newbie line)
            npc.respawns = false
            npc.setActive(true)
            npc.routeLogic = 1
            enlist(npc, pack)
            pack.slots[i] = npc
        }
    }

    /**
     * Push combat levels into `npc.stats` (the damage formula's real source — without
     * this a spawned npc hits 0s), using [pack] overrides where set. Enemies are made
     * aggressive (the wilds out here are genuinely hostile); friendlies never aggro
     * players — they only fight the enemy lines, driven by [skirmish].
     */
    private fun enlist(npc: Npc, pack: MonsterPack) {
        val d = pack.combatDef ?: npc.combatDef
        val atk = (pack.atk ?: d.attack).coerceAtLeast(1)
        val str = (pack.str ?: d.strength).coerceAtLeast(1)
        val def = (pack.def ?: d.defence).coerceAtLeast(1)
        val hp = (pack.hp ?: d.hitpoints).coerceAtLeast(1)
        npc.stats.setMaxLevel(NpcSkills.ATTACK, atk); npc.stats.setCurrentLevel(NpcSkills.ATTACK, atk)
        npc.stats.setMaxLevel(NpcSkills.STRENGTH, str); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, str)
        npc.stats.setMaxLevel(NpcSkills.DEFENCE, def); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, def)
        npc.setCurrentHp(hp)
        if (pack.faction == Faction.FRIENDLY) {
            npc.combatDef = d.copy(attack = atk, strength = str, defence = def, hitpoints = hp)
            npc.aggroCheck = AGGRO_NONE // good guys ignore players; the skirmish points them at enemies
        } else {
            npc.combatDef = d.copy(
                attack = atk, strength = str, defence = def, hitpoints = hp,
                aggressiveRadius = maxOf(d.aggressiveRadius, AGGRO_RADIUS),
                aggroTargetDelay = if (d.aggroTargetDelay > 0) d.aggroTargetDelay else 8,
                aggressiveTimer = maxOf(d.aggressiveTimer, 200),
            )
            // Enemies aggro players ANYWHERE except inside the protected town — so standing in
            // Lumbridge never pulls a goblin off the line.
            //
            // MUST stay dead-simple: an aggroCheck runs in the engine's aggro path, which is NOT
            // wrapped in our try/catch — anything it throws propagates into the game-loop task and
            // KILLS it (silently cancelled by the ScheduledThreadPoolExecutor → whole server hangs).
            // That's exactly what an earlier 1v1 version (which scanned pack.pool here) caused. 1v1
            // is now enforced solely by the guarded per-tick [enforceSingleCombat] sweep instead.
            val sa = safeArea
            val kp = keep
            val floor = pack.aggroFloorRank // §4: ignore players who out-rank the line (captured int — no throw)
            npc.aggroCheck = { _, p ->
                (sa == null || !sa.contains(p.tile)) && (kp == null || !kp.contains(p.tile)) && p.title.ordinal < floor
            }
        }
    }

    /**
     * Front-line 1v1 + anti-stack for single-combat enemy packs (run every game tick, guarded):
     *  - **1v1**: per player, KEEP the goblin the player is actually fighting (their own combat
     *    target) — or the closest one if the player isn't targeting any — and release every OTHER
     *    goblin locked onto that player. Keeping the player's own target is what stops a goblin
     *    "walking away mid-fight" (the old version kept an arbitrary one and could release yours).
     *  - **anti-stack**: nudge a goblin sharing a tile with a pack-mate onto a free neighbour,
     *    unless it's the one in melee with its target (so fighters stay put and lined up).
     */
    private fun enforceSingleCombat(world: World) {
        for (pack in packs) {
            if (pack.faction != Faction.ENEMY || !pack.singleCombat) continue
            val living = pack.pool.filter { isAlive(world, it) }
            if (living.isEmpty()) continue

            // 1v1: group attackers by their player target, keep the player's own target, release rest.
            val byPlayer = HashMap<Player, MutableList<Npc>>()
            for (npc in living) {
                val t = npc.getCombatTarget() as? Player ?: continue
                if (isAlivePawn(world, t)) byPlayer.getOrPut(t) { ArrayList() }.add(npc)
            }
            for ((player, attackers) in byPlayer) {
                if (attackers.size <= 1) continue
                val pt = player.getCombatTarget()
                val keep = attackers.firstOrNull { it === pt }
                    ?: attackers.minByOrNull { dist(it.tile, player.tile) }!!
                for (npc in attackers) if (npc !== keep) { npc.removeCombatTarget(); npc.resetFacePawn() }
            }

            // anti-stack: spread goblins off shared tiles, leaving the one in melee where it is.
            val occ = HashMap<Long, Int>()
            for (npc in living) {
                val k = world.npcTileKey(npc.tile.x, npc.tile.z, npc.tile.height)
                occ[k] = (occ[k] ?: 0) + 1
            }
            for (npc in living) {
                val here = world.npcTileKey(npc.tile.x, npc.tile.z, npc.tile.height)
                if ((occ[here] ?: 0) <= 1) continue
                val tgt = npc.getCombatTarget()
                if (tgt != null && dist(npc.tile, tgt.tile) <= 1) continue // mid-melee, leave it
                for (dir in Direction.RS_ORDER) {
                    if (!world.canTraverse(npc.tile, dir, npc)) continue
                    val nbr = npc.tile.transform(dir.getDeltaX(), dir.getDeltaZ())
                    val nk = world.npcTileKey(nbr.x, nbr.z, nbr.height)
                    if ((occ[nk] ?: 0) == 0) {
                        npc.walkTo(nbr)
                        occ[here] = occ[here]!! - 1
                        occ[nk] = 1
                        break
                    }
                }
            }
        }
    }

    /**
     * Keep the town safe: any ENEMY that has crossed into [area] (wandered or chased in) drops
     * its target and is walked back to the nearest muster point on its line. Friendlies are free
     * to enter. Runs after the skirmish so it wins the tick for a stray that just stepped in.
     */
    private fun leash(world: World, area: Area) {
        for (pack in packs) {
            if (pack.faction != Faction.ENEMY) continue
            for (npc in pack.pool) {
                if (!isAlive(world, npc) || !area.contains(npc.tile)) continue
                if (npc.isAttacking()) { npc.removeCombatTarget(); npc.resetFacePawn() }
                val back = pack.staging.minByOrNull { dist(npc.tile, it) } ?: continue
                npc.walkTo(walkableNear(world, back))
            }
        }
    }

    // --- NPC-vs-NPC skirmish (only runs when the zone has a friendly pack) ---

    private fun alive(world: World, faction: Faction): List<Npc> =
        packs.filter { it.faction == faction }
            .flatMap { it.pool }
            .filter { isAlive(world, it) }

    /**
     * Good-vs-evil target acquisition. Enemies HOLD their lines (they roam their own
     * ring via [MonsterPack.walkRadius] and only here pick up a friendly that has
     * closed within range — "fight back whoever reaches us"). Friendlies advance to
     * their [MonsterPack.objective] line, fighting any enemy inside engage range en
     * route; with the objective set to the furthest enemy line they push the whole
     * frontier outward, clearing the nearer lines first because those are closer.
     */
    private fun skirmish(world: World, enemies: List<Npc>, friendlies: List<Npc>) {
        if (enemies.isEmpty() || friendlies.isEmpty()) return

        // Enemies fight back at the nearest friendly that has reached them; if already locked
        // onto a live target (a knight or a player the engine aggro'd) we leave them be.
        for (e in enemies) {
            if (e.isAttacking() && e.getCombatTarget()?.let { isAlivePawn(world, it) } == true) continue
            val foe = nearestWithin(e.tile, friendlies, ENEMY_ENGAGE)
            if (foe != null) e.attack(foe)
        }

        // Friendlies engage the nearest enemy in reach, else march toward the closest tile of
        // their objective line (walkableNear snaps a blocked target tile to dry ground).
        for (pack in packs) {
            if (pack.faction != Faction.FRIENDLY) continue
            for (k in pack.pool) {
                if (!isAlive(world, k)) continue
                if (k.getCombatTarget() is Player) continue // defending a citizen (see defendCitizens) — don't pull off
                val foe = nearestWithin(k.tile, enemies, FRIENDLY_ENGAGE)
                if (foe != null) {
                    if (!(k.isAttacking() && k.getCombatTarget() === foe)) k.attack(foe)
                } else {
                    if (k.isAttacking()) { k.removeCombatTarget(); k.resetFacePawn() }
                    val dest = pack.objective.minByOrNull { dist(k.tile, it) } ?: continue
                    if (dist(k.tile, dest) > 1) k.walkTo(walkableNear(world, dest))
                }
            }
        }
    }

    /**
     * **Guard retaliation** (master design brief §4): the realm's knights defend its citizens. A
     * player being attacked within reach of a friendly knight pulls the nearest knights onto their
     * attacker — goblin, PK bot, or a real PKer. Knights never leave their patrol band: one that
     * strays too far chasing is recalled to its muster. So the frontier is dangerous but guarded;
     * the deep wild is not. Runs inside the guarded tick, so it can never break the loop.
     */
    private fun defendCitizens(world: World) {
        // Recall any knight that strayed too far chasing an attacker — keep them in their zone.
        for (pack in packs) {
            if (pack.faction != Faction.FRIENDLY) continue
            for (k in pack.pool) {
                if (!isAlive(world, k) || k.getCombatTarget() !is Player) continue
                val muster = pack.staging.minByOrNull { dist(k.tile, it) } ?: continue
                if (dist(k.tile, muster) > KNIGHT_LEASH) {
                    k.removeCombatTarget(); k.resetFacePawn(); k.walkTo(walkableNear(world, muster))
                }
            }
        }
        // Sic nearby knights on whoever is attacking a citizen.
        world.players.forEach { p ->
            if (!p.isBeingAttacked()) return@forEach
            val attacker = p.attr[LAST_HIT_BY_ATTR]?.get() ?: return@forEach
            if (attacker === p || !isAlivePawn(world, attacker)) return@forEach
            for (pack in packs) {
                if (pack.faction != Faction.FRIENDLY) continue
                for (k in pack.pool) {
                    if (!isAlive(world, k) || dist(k.tile, p.tile) > DEFEND_RANGE) continue
                    if (!(k.isAttacking() && k.getCombatTarget() === attacker)) k.attack(attacker)
                }
            }
        }
    }

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

    /**
     * Nearest npc in [candidates] within [range] chebyshev tiles of [from] (or null). A single
     * allocation-free pass: the old `candidates.filter { dist <= R }.minByOrNull { dist }` built a
     * throwaway list AND computed the distance twice per candidate — done per-enemy and per-knight,
     * that was hundreds of short-lived lists every tick across a full frontier.
     */
    private fun nearestWithin(from: Tile, candidates: List<Npc>, range: Int): Npc? {
        var best: Npc? = null
        var bestDist = Int.MAX_VALUE
        for (c in candidates) {
            val d = dist(from, c.tile)
            if (d <= range && d < bestDist) { bestDist = d; best = c }
        }
        return best
    }

    private companion object {
        const val AGGRO_RADIUS = 6
        const val ENEMY_ENGAGE = 10   // an enemy fights back a friendly within this many tiles
        const val FRIENDLY_ENGAGE = 12 // a knight engages an enemy within this many tiles
        const val DEFEND_RANGE = 12   // §4: knights retaliate for a citizen attacked within this range
        const val KNIGHT_LEASH = 24   // §4: a defending knight is recalled if it strays this far from its muster
        const val SNAP_RADIUS = 5
        val AGGRO_ANY: (Npc, Player) -> Boolean = { _, _ -> true }
        val AGGRO_NONE: (Npc, Player) -> Boolean = { _, _ -> false }
    }
}

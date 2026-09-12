package org.alter.plugins.content.war.artillery

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.hit
import org.alter.api.ext.message
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.entity.Projectile
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **War artillery — the dwarf multicannon as an emplacement.** A placed multicannon (the stock loc 6,
 * "Dwarf multicannon" — Fire / Pick-up / Empty) that a player LOADS with cannonballs and that then
 * fires by itself at whatever hostile npcs its owner names, one shot a tick, the OSRS cannonball
 * projectile (gfx 53), up to 30 damage a shot. That is the whole of it: enough to be *Asgarnia's
 * artillery* in a scripted fight (The Guns of Asgarnia's workshop defence, the White Wall finale's
 * batteries, a march's gun line) without inventing a deep artillery framework.
 *
 * What this deliberately is NOT (yet): the player-owned OSRS multicannon — carried parts, the
 * four-piece set-up, decay, pick-up, Nulodion's repairs. Those can sit on top of this piece if the
 * economy ever wants cannon parts in players' hands (a Team 2 ruling — parts have to come from
 * somewhere); the firing, loading and targeting here would be reused unchanged.
 *
 * Known limit: the server has no loc-animation packet, so the barrel does not visibly rotate; the
 * projectile, the hitsplats and the dwarf's lines carry the moment.
 */
object DwarfCannon {

    /** Loc 6 "Dwarf multicannon" [Fire, Pick-up, Empty] (3x3; the tile given is its SW corner). */
    const val OBJ_KEY = "object.dwarf_multicannon"
    const val CANNONBALL_KEY = "item.cannonball"

    /** The OSRS cannonball projectile graphic. */
    const val PROJECTILE_GFX = 53
    const val MAX_HIT = 30
    const val RANGE = 8
    /** One Fire click loads up to this many cannonballs (the OSRS magazine). */
    const val MAGAZINE = 30
    /** Flat accuracy — an emplacement has no attacker stats to roll (OSRS uses the loader's ranged accuracy). */
    const val ACCURACY = 0.8

    private val live = ArrayList<CannonEmplacement>()

    /** The emplacement whose 3x3 footprint contains [t] (the clicked loc's tile), if any. */
    fun at(t: Tile): CannonEmplacement? = live.firstOrNull { e ->
        e.placed && t.height == e.tile.height && t.x in e.tile.x..e.tile.x + 2 && t.z in e.tile.z..e.tile.z + 2
    }

    internal fun register(e: CannonEmplacement) { if (e !in live) live += e }
    internal fun unregister(e: CannonEmplacement) { live.remove(e) }

    fun liveCount(): Int = live.size
}

/**
 * One placed gun. [tile] is the loc's SW corner (the gun stands on [centre]); [owner] is credited
 * for its damage (kill credit, drops, quest kill hooks); [targets] returns the npcs it may shoot at
 * — the caller decides what "hostile" means (an instance's raiders, a frontier's enemies).
 *
 * Drive it with [tick] once a game tick (a quest instance's `onTick`, a world timer). It fires only
 * while [ammo] > 0 and a living target is within [DwarfCannon.RANGE] of the gun.
 */
class CannonEmplacement(
    val world: World,
    val tile: Tile,
    val owner: Player?,
    private val targets: () -> List<Npc>,
) {
    val centre: Tile = Tile(tile.x + 1, tile.z + 1, tile.height)

    private var obj: DynamicObject? = null

    var placed = false
        private set

    var ammo = 0
        private set

    var shotsFired = 0
        private set

    /** Fired after every shot that leaves the barrel: (target, damage rolled — 0 for a miss). */
    var onShot: ((Npc, Int) -> Unit)? = null

    /** Spawn the loc. False (and a log line) if the cache lacks it. */
    fun place(): Boolean {
        if (placed) return true
        val id = runCatching { getRSCM(DwarfCannon.OBJ_KEY) }.getOrNull() ?: run {
            logger.warn { "[artillery] '${DwarfCannon.OBJ_KEY}' is not in the cache; no gun placed at $tile." }
            return false
        }
        val o = DynamicObject(id = id, type = 10, rot = 0, tile = tile)
        world.spawn(o)
        obj = o
        placed = true
        DwarfCannon.register(this)
        return true
    }

    /** Remove the loc. Idempotent. Unfired cannonballs stay with the gun (see [ammo]) — the caller decides. */
    fun remove() {
        if (!placed) return
        obj?.let { runCatching { world.remove(it) } }
        obj = null
        placed = false
        DwarfCannon.unregister(this)
    }

    /**
     * Load from [p]'s pack: up to [max] cannonballs, never past a full magazine. Returns the number
     * loaded (0 with a message when the pack has none or the gun is full).
     */
    fun load(p: Player, max: Int = DwarfCannon.MAGAZINE): Int {
        val id = runCatching { getRSCM(DwarfCannon.CANNONBALL_KEY) }.getOrNull() ?: return 0
        val have = p.inventory.getItemCount(id)
        if (have <= 0) { p.message("You have no cannonballs to load."); return 0 }
        val space = DwarfCannon.MAGAZINE - ammo
        if (space <= 0) { p.message("The cannon is fully loaded."); return 0 }
        val n = minOf(have, space, max)
        if (n <= 0) return 0
        val removed = p.inventory.remove(id, n).completed
        if (removed <= 0) return 0
        ammo += removed
        p.message("You load $removed cannonball${if (removed == 1) "" else "s"} into the cannon (${ammo}/${DwarfCannon.MAGAZINE}).")
        return removed
    }

    /** One game tick: fire at the nearest living target in range, if loaded. True if a shot went out. */
    fun tick(): Boolean {
        if (!placed || ammo <= 0) return false
        val target = targets()
            .filter { it.index >= 0 && !it.isDead() && it.tile.height == centre.height && it.tile.getChebyshevDistance(centre) <= DwarfCannon.RANGE }
            .minByOrNull { it.tile.getChebyshevDistance(centre) } ?: return false
        fire(target)
        return true
    }

    private fun fire(target: Npc) {
        ammo--
        shotsFired++
        val distance = centre.getChebyshevDistance(target.tile)
        runCatching {
            world.spawn(
                Projectile.Builder()
                    .setTiles(start = centre, target = target)
                    .setGfx(DwarfCannon.PROJECTILE_GFX)
                    .setHeights(startHeight = 40, endHeight = 36)
                    .setSlope(angle = 15, steepness = 11)
                    .setTimes(delay = 20, lifespan = 20 + maxOf(10, distance * 5))
                    .build(),
            )
        }.onFailure { logger.error(it) { "[artillery] projectile failed at $centre" } }
        val hitDelay = 1 + (3 + distance) / 6 // the ranged hit-delay curve
        val damage = if (world.randomDouble() < DwarfCannon.ACCURACY) world.random(DwarfCannon.MAX_HIT) else 0
        runCatching {
            if (damage > 0 && owner != null) target.damageMap.add(owner, damage)
            target.hit(damage = damage, delay = hitDelay, attackersIndex = owner?.index ?: -1)
        }.onFailure { logger.error(it) { "[artillery] hit failed on ${target.id}" } }
        runCatching { onShot?.invoke(target, damage) }
    }
}

package org.alter.plugins.content.items.pets

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getNpcOrDefault
import dev.openrune.cache.CacheManager.getNpcs
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.ACTIVE_PET_ATTR
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.model.move.MovementQueue.StepType
import org.alter.game.model.move.hasMoveDestination
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.stepTo
import org.alter.game.model.move.stopMovement
import org.alter.rscm.RSCM.getRSCM
import kotlin.math.abs
import kotlin.math.max

private val logger = KotlinLogging.logger {}

/**
 * **Pets** — the boss pets follow you when you drop them, exactly as OSRS (player report
 * 2026-09-17: "all pets don't follow when dropped").
 *
 * Every pet on this server is only an inventory item: the boss tables hand one out
 * ([org.alter.plugins.content.bosses.BossDeath]) and the Collection Log records it, but nothing
 * ever turned it into a follower. Dropping one put a pet on the floor for anyone to take.
 *
 * Here a pet's **Drop** is intercepted: the item leaves the inventory and its follower npc is
 * spawned at your feet, trailing you until you pick it up again (the follower's own **Pick-up**
 * option, or `::pet`). The item is NOT in the inventory while the pet is out, so the pet itself
 * is the ownership record and it has to survive a logout — hence [ACTIVE_PET_ATTR] (persistent):
 * logging out stores the pet and the next login puts it back at your side.
 *
 * ### Resolving the follower npc
 * A pet item and its follower npc are *not* named the same (item "Pet general graardor" → npc
 * "General Graardor Jr."), and several cache npcs share one pet's name — the Kraken **boss** and
 * the Kraken **pet** are both called "Kraken"; Zulrah's combat snakelings and the Snakeling pet
 * are both "Snakeling". So [PETS] maps each pet item to the follower's cache NAME (written as the
 * `npc.rscm` slug spells it) and [resolve] picks, out of every npc with that name, the lowest id
 * the cache flags as a **follower** (`NpcType.isFollower`, or failing that one carrying a
 * "Pick-up" option). A pet whose follower can't be identified that way is left unbound and
 * logged — it keeps its old drop-to-floor behaviour rather than spawning a boss by mistake.
 */
object Pets {

    /** Pet item (rscm key) → the follower npc's cache name, as the `npc.rscm` slug spells it. */
    private val PETS: Map<String, String> = mapOf(
        // Lair bosses
        "item.prince_black_dragon" to "prince_black_dragon",
        "item.baby_mole" to "baby_mole",
        "item.kalphite_princess" to "kalphite_princess",
        "item.pet_dagannoth_rex" to "dagannoth_rex_jr",
        "item.pet_dagannoth_prime" to "dagannoth_prime_jr",
        "item.pet_dagannoth_supreme" to "dagannoth_supreme_jr",
        // God Wars Dungeon
        "item.pet_general_graardor" to "general_graardor_jr",
        "item.pet_zilyana" to "zilyana_jr",
        "item.pet_kril_tsutsaroth" to "kril_tsutsaroth_jr",
        "item.pet_kreearra" to "kreearra_jr",
        // Wilderness bosses
        "item.callisto_cub" to "callisto_cub",
        "item.vetion_jr" to "vetion_jr",
        "item.venenatis_spiderling" to "venenatis_spiderling",
        "item.scorpias_offspring" to "scorpias_offspring",
        "item.pet_chaos_elemental" to "chaos_elemental_jr",
        // Slayer bosses
        "item.pet_kraken" to "kraken",
        "item.hellpuppy" to "hellpuppy",
        "item.pet_smoke_devil" to "smoke_devil",
        "item.skotos" to "skotos",
        "item.ikkle_hydra" to "ikkle_hydra",
        // Solo bosses
        "item.vorki" to "vorki",
        "item.pet_snakeling" to "snakeling",
        "item.pet_corporeal_critter" to "corporeal_critter",
        // Minigames
        "item.tzrekjad" to "tzrekjad",
        "item.phoenix" to "phoenix",
        "item.abyssal_protector" to "abyssal_protector",
    )

    /** The pick-up option every OSRS pet follower carries; also the fallback "this is a pet" signal. */
    private const val PICK_UP = "pick-up"

    /** Farther than this from its owner (or on another floor) and the pet snaps to their side. */
    private const val SNAP_DIST = 12
    /** The pet runs (2 tiles a tick) once it is this far behind — a running owner can't shake it. */
    private const val RUN_DIST = 3
    /** Ticks of no progress while out of position before the pet is snapped back (walled off). */
    private const val STUCK_TICKS = 5

    /** pet item id → follower npc id, and back. Built once at boot from the cache ([resolve]). */
    private val npcByItem = HashMap<Int, Int>()
    private val itemByNpc = HashMap<Int, Int>()

    /** Live followers, by owner. One pet each — OSRS lets you field exactly one. */
    private val live = HashMap<Player, Follower>()

    private class Follower(val npc: Npc, val itemId: Int) {
        var lastTile: Tile = npc.tile
        var stuck: Int = 0
    }

    // ------------------------------------------------------------------ boot

    /**
     * Resolve every pet item to its follower npc from the cache. Called once on world init, when
     * the cache is loaded; safe to call again (idempotent). Returns the number of pets bound.
     */
    fun resolve(): Int {
        if (npcByItem.isNotEmpty()) return npcByItem.size
        // One pass over the npc defs, indexing the FOLLOWERS by normalized name. Anything the cache
        // doesn't flag as a follower but that carries "Pick-up" counts too, so a pet whose def
        // predates the follower flag still resolves; a boss sharing the name never does.
        val followers = HashMap<String, Int>()
        getNpcs().forEach { (id, def) ->
            val isPet = def.isFollower || def.actions.any { it?.equals(PICK_UP, ignoreCase = true) == true }
            if (!isPet) return@forEach
            val key = slug(def.name)
            if (key.isEmpty()) return@forEach
            val cur = followers[key]
            if (cur == null || id < cur) followers[key] = id
        }
        PETS.forEach { (itemKey, npcName) ->
            val itemId = runCatching { getRSCM(itemKey) }.getOrNull()
            if (itemId == null) {
                logger.warn { "[pets] '$itemKey' is not in the item cache — pet unbound." }
                return@forEach
            }
            val npcId = followers[slug(npcName)]
            if (npcId == null) {
                logger.warn { "[pets] no follower npc named '$npcName' in the cache — '$itemKey' keeps its drop-to-floor behaviour." }
                return@forEach
            }
            npcByItem[itemId] = npcId
            itemByNpc[npcId] = itemId
        }
        logger.info { "[pets] ${npcByItem.size}/${PETS.size} pets bound to a follower npc." }
        return npcByItem.size
    }

    /** Cache names and rscm slugs differ only in punctuation/case ("Vet'ion Jr." ↔ `vetion_jr`). */
    private fun slug(name: String): String = name.filter { it.isLetterOrDigit() }.lowercase()

    /** Every bound pet item id — what the drop hook binds. */
    fun itemIds(): Set<Int> = npcByItem.keys

    /** Every bound follower npc id, paired with the option index of its "Pick-up" (or null). */
    fun followerPickUpSlots(): Map<Int, Int> =
        itemByNpc.keys.associateWith { id ->
            getNpcOrDefault(id).actions.indexOfFirst { it?.equals(PICK_UP, ignoreCase = true) == true }
        }.filterValues { it != -1 }

    // ------------------------------------------------------------------ state

    fun petOf(p: Player): Npc? = live[p]?.npc

    /** True if [npc] is [p]'s own follower. Anyone else's pet is not theirs to pick up. */
    fun ownedBy(npc: Npc, p: Player): Boolean = live[p]?.npc === npc

    // ------------------------------------------------------------------ drop / pick up

    /**
     * Drop [itemId] as a follower. Takes the item out of the inventory and spawns the pet at the
     * player's feet. Returns false (leaving the inventory untouched) when the pet can't be fielded,
     * having already said why — the caller then leaves the ordinary drop alone.
     */
    fun drop(world: World, p: Player, itemId: Int, slot: Int): Boolean {
        val npcId = npcByItem[itemId] ?: return false
        val existing = live[p]
        if (existing != null) {
            p.message("You already have a follower. Pick up <col=801700>${nameOf(existing.itemId)}</col> first.")
            return true // handled: refuse rather than dropping the pet on the floor
        }
        val removed = p.inventory.remove(item = itemId, amount = 1, assureFullRemoval = true, beginSlot = slot)
        if (removed.completed == 0) return false
        val pet = spawn(world, p, npcId, itemId)
        if (pet == null) {
            p.inventory.add(item = itemId, amount = 1) // put it back — never eat the pet
            p.message("<col=801700>Your pet can't follow you here.</col>")
            return true
        }
        p.attr[ACTIVE_PET_ATTR] = itemId
        p.message("You have a new follower!")
        return true
    }

    /**
     * Take the pet back into the inventory. Returns false (and says why) when there is no room —
     * the follower stays out, so a full inventory can never lose a pet.
     */
    fun pickUp(world: World, p: Player): Boolean {
        val f = live[p] ?: run {
            p.message("You don't have a follower.")
            return false
        }
        if (p.inventory.add(item = f.itemId, amount = 1, assureFullInsertion = true).completed == 0) {
            p.message("You don't have enough space in your inventory.")
            return false
        }
        despawn(world, p, store = false) // clears ACTIVE_PET_ATTR: the item is the ownership again
        p.message("You pick up <col=801700>${nameOf(f.itemId)}</col>.")
        return true
    }

    // ------------------------------------------------------------------ lifecycle

    /** Login: put the stored pet back at the player's side. */
    fun onLogin(world: World, p: Player) {
        val itemId = p.attr[ACTIVE_PET_ATTR] ?: return
        val npcId = npcByItem[itemId]
        // Either the follower npc no longer resolves (a cache change) or it couldn't be spawned
        // here. Hand the ITEM back rather than clearing the attribute on its own — that would
        // destroy a pet that the player is owed.
        if (npcId == null || spawn(world, p, npcId, itemId) == null) {
            if (p.inventory.add(item = itemId, amount = 1, assureFullInsertion = true).completed == 0) p.bank.add(itemId, 1)
            p.attr.remove(ACTIVE_PET_ATTR)
            p.message("<col=801700>${nameOf(itemId)} couldn't follow you here — it's back in your pack.</col>")
        }
    }

    /** Logout: take the follower out of the world. [ACTIVE_PET_ATTR] keeps the ownership. */
    fun onLogout(world: World, p: Player) = despawn(world, p, store = true)

    private fun spawn(world: World, p: Player, npcId: Int, itemId: Int): Npc? = runCatching {
        val tile = world.snapToWalkable(p.tile, maxRadius = 2)
        val pet = Npc(npcId, tile, world)
        pet.walkRadius = 0
        pet.respawns = false
        pet.aggroCheck = { _, _ -> false } // a pet never picks a fight
        if (!world.spawn(pet)) return@runCatching null
        pet.respawns = false // setNpcDefaults() re-reads the combat def on spawn — a pet never comes back
        pet.setActive(true)
        live[p] = Follower(pet, itemId)
        pet
    }.onFailure { logger.error(it) { "[pets] failed to spawn follower npc $npcId for ${p.username}" } }.getOrNull()

    /** Remove the follower from the world. [store] keeps [ACTIVE_PET_ATTR] (logout); pick-up clears it. */
    private fun despawn(world: World, p: Player, store: Boolean) {
        val f = live.remove(p) ?: return
        if (f.npc.index >= 0 && world.npcs.contains(f.npc)) world.remove(f.npc)
        if (!store) p.attr.remove(ACTIVE_PET_ATTR)
    }

    // ------------------------------------------------------------------ the follow

    /**
     * Per-tick: every pet walks (or runs) after its owner, snapping to their side when it falls too
     * far behind, changes floor, or gets walled off. Also the orphan sweep — a pet whose owner left
     * the world without the logout hook firing is removed here, so no follower is ever left standing.
     *
     * Deliberately NO route-finding: a follower only ever needs the next tile or two toward its
     * owner, and a per-tick path search per pet is exactly the churn the npc wander loop was
     * rewritten to avoid (see `mechanics/npcwalk/NpcRandomWalkPlugin`).
     */
    fun tick(world: World) {
        if (live.isEmpty()) return
        val gone = ArrayList<Player>(0)
        live.forEach { (owner, f) ->
            if (owner.index < 0 || !owner.isOnline || f.npc.index < 0 || !world.npcs.contains(f.npc)) {
                gone += owner
                return@forEach
            }
            val dist = chebyshev(f.npc.tile, owner.tile)
            if (f.npc.tile.height != owner.tile.height || dist > SNAP_DIST || f.stuck >= STUCK_TICKS) {
                f.npc.stopMovement()
                f.npc.moveTo(world.snapToWalkable(owner.tile, maxRadius = 2))
                f.stuck = 0
            } else if (dist >= 2) {
                // Stuck detection: out of position and not actually moving (a wall, a closed door).
                if (f.npc.tile.sameAs(f.lastTile) && !f.npc.hasMoveDestination()) f.stuck++ else f.stuck = 0
                walkAfter(world, f.npc, owner.tile, run = dist >= RUN_DIST)
            } else {
                f.stuck = 0
            }
            f.lastTile = f.npc.tile
        }
        gone.forEach { despawn(world, it, store = true) }
    }

    /** Queue one step (two when running) toward [dest], collision-checked, with no path search. */
    private fun walkAfter(world: World, pet: Npc, dest: Tile, run: Boolean) {
        pet.stopMovement() // re-aim every tick: the owner is a moving target
        val type = if (run) StepType.FORCED_RUN else StepType.NORMAL
        var from = pet.tile
        repeat(if (run) 2 else 1) {
            if (chebyshev(from, dest) <= 1) return
            val next = nextStep(world, pet, from, dest) ?: return
            pet.stepTo(next, type)
            from = next
        }
    }

    /**
     * The next tile from [from] toward [dest]: the direct direction, then its two components when
     * that diagonal is blocked. Null when every candidate is clipped — the stuck counter picks it up.
     */
    private fun nextStep(world: World, pet: Npc, from: Tile, dest: Tile): Tile? {
        val ideal = Direction.between(from, dest)
        val tries = if (ideal.isDiagonal()) arrayOf(ideal, *ideal.getDiagonalComponents()) else arrayOf(ideal)
        for (d in tries) {
            if (d == Direction.NONE) continue
            val next = from.transform(d.getDeltaX(), d.getDeltaZ())
            if (next.sameAs(from)) continue
            if (world.chunks.get(next, createIfNeeded = false) == null) continue
            if (!world.canTraverse(from, d, pet)) continue
            return next
        }
        return null
    }

    private fun chebyshev(a: Tile, b: Tile): Int = max(abs(a.x - b.x), abs(a.z - b.z))

    private fun nameOf(itemId: Int): String = runCatching { getItem(itemId).name }.getOrDefault("your pet")
}

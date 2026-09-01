package org.alter.plugins.content.skills.fishing

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.FISHING_PREFERENCE_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Fishing** — tool/bait-aware with player fish selection (owner's design, 2026-07-04):
 *
 *  - Every fish belongs to a METHOD (net / bait rod / fly rod / cage / harpoon / big net) with a
 *    required tool and, for the rods, a consumable bait. All tools + bait are sold by Gerrant at
 *    the Lumbridge shop hub.
 *  - **Default click** = catch the highest-level fish the player's LEVEL *and CARRIED SUPPLIES*
 *    allow (fly rod + feathers in the pack → the best fly-fishable fish, etc.).
 *  - **Second spot option** opens a CHOOSE-FISH menu of everything currently eligible — players
 *    can deliberately fish something lower (a specific supply, cooking mats, contract items).
 *    The choice is remembered for the session and used while it stays eligible.
 *
 * Spots sit on the River Lum by Lumbridge and on both Mirepools ponds in the swamp collection
 * grounds. Rod bait (fishing bait / feathers) is consumed one per catch; nets/cage/harpoon are not.
 */
class FishingPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** A fishing method: the tool it needs, (for rods) the bait consumed per catch, and the animation
     *  that tool plays. The loop used to hard-play the net cast (621) for everything, so harpooning or
     *  potting a lobster still mimed casting a net — the fishing analog of the gathering-tool anim fix. */
    private data class Method(val label: String, val tool: String, val bait: String? = null, val verb: String, val anim: Int)

    private val net = Method("Small net", "item.small_fishing_net", verb = "You cast out your net...", anim = ANIM_NET)
    private val baitRod = Method("Bait rod", "item.fishing_rod", "item.fishing_bait", "You bait your hook and cast...", anim = ANIM_ROD)
    private val flyRod = Method("Fly rod", "item.fly_fishing_rod", "item.feather", "You whip out your fly rod and cast...", anim = ANIM_ROD)
    private val cage = Method("Lobster cage", "item.lobster_pot", verb = "You lower the cage...", anim = ANIM_CAGE)
    private val harpoon = Method("Harpoon", "item.harpoon", verb = "You ready your harpoon...", anim = ANIM_HARPOON)
    private val bigNet = Method("Big net", "item.big_fishing_net", verb = "You cast out the big net...", anim = ANIM_BIG_NET)

    private data class Fish(val raw: String, val name: String, val level: Int, val xp: Double, val method: Method)

    /** The full ladder — highest ELIGIBLE (level + carried tool/bait) is the default catch. */
    private val ladder: List<Fish> = listOf(
        Fish("item.raw_shrimps", "shrimps", 1, 10.0, net),
        Fish("item.raw_sardine", "sardine", 5, 20.0, baitRod),
        Fish("item.raw_herring", "herring", 10, 30.0, baitRod),
        Fish("item.raw_anchovies", "anchovies", 15, 40.0, net),
        Fish("item.raw_trout", "trout", 20, 50.0, flyRod),
        Fish("item.raw_pike", "pike", 25, 60.0, baitRod),
        Fish("item.raw_salmon", "salmon", 30, 70.0, flyRod),
        Fish("item.raw_tuna", "tuna", 35, 80.0, harpoon),
        Fish("item.raw_lobster", "lobster", 40, 90.0, cage),
        Fish("item.raw_bass", "bass", 46, 100.0, bigNet),
        Fish("item.raw_swordfish", "swordfish", 50, 100.0, harpoon),
        Fish("item.raw_monkfish", "monkfish", 62, 120.0, net), // wiki: monkfish are caught with a small net
        Fish("item.raw_shark", "shark", 76, 110.0, harpoon),
    ).filter { resolves(it.raw) && resolves(it.method.tool) && (it.method.bait == null || resolves(it.method.bait)) }

    /** Preferred catch (raw item key), set via the choose-fish menu. Persists across logout so a
     *  deliberate pick isn't silently lost half-way through a contract. */
    private val fishPref = FISHING_PREFERENCE_ATTR

    private val spot = "npc.fishing_spot"
    private val spotTiles = listOf(
        // River Lum by Lumbridge (stand on the west bank).
        Tile(3242, 3247, 0), Tile(3240, 3251, 0), Tile(3243, 3243, 0),
        // The Mire — river bank east of the collection grounds (stand on x3245).
        Tile(3246, 3175, 0), Tile(3246, 3174, 0), Tile(3246, 3173, 0), Tile(3246, 3172, 0),
        // The Mirepools — NORTH pond edges (spot on the water, fish from the bank).
        Tile(3218, 3195, 0), Tile(3217, 3193, 0), Tile(3216, 3190, 0),
        // The Mirepools — SOUTH pond edges (by the Mire Run).
        Tile(3216, 3171, 0), Tile(3218, 3172, 0), Tile(3225, 3171, 0),
    )

    init {
        spotTiles.forEach { spawnNpc(spot, x = it.x, z = it.z, height = it.height, walkRadius = 0, direction = Direction.WEST) }

        // First spot option = fish (session pref, else best eligible). Any further options = the
        // choose-fish menu. Cache actions vary, so bind whatever the def actually carries.
        val actions = try {
            getNpc(getRSCM(spot)).actions.filterNotNull().filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
        val opts = (actions.ifEmpty { listOf("Net") }).distinct()
        opts.forEachIndexed { idx, opt ->
            try {
                if (idx == 0) {
                    onNpcOption(spot, option = opt) { player.queue { fish(this, player) } }
                } else {
                    onNpcOption(spot, option = opt) { player.queue { chooseFish(this, player) } }
                }
            } catch (e: Exception) {
                logger.warn { "fishing: couldn't bind spot option '$opt'" }
            }
        }
        if (opts.size < 2) logger.info { "fishing: spot has a single option; choose-fish menu is via ::fish." }

        // The spot npc carries a single cache action in this cache, so the choose-fish menu needs
        // its own entry point (guaranteed-reachable command, same pattern as ::market / ::bonds).
        // The command only SETS the preference — the loop starts when a spot is clicked (else the
        // command would let players "fish" on dry land).
        onCommand("fish", description = "Choose which fish to catch") {
            player.queue { chooseFish(this, player, autoFish = false) }
        }
    }

    /** All fish the player can catch RIGHT NOW: level + tool in pack (+ bait for the rods). */
    private fun eligible(player: Player): List<Fish> {
        val lvl = player.getSkills().getCurrentLevel(Skills.FISHING)
        return ladder.filter { f ->
            lvl >= f.level &&
                hasMethodTool(player, f.method) &&
                (f.method.bait == null || player.inventory.getItemCount(getRSCM(f.method.bait)) > 0)
        }
    }

    /** Harpoon tiers (best first) with their fishing anims. Any of these — in the inventory OR
     *  the weapon slot — counts as a harpoon; the old code demanded exactly item.harpoon in the
     *  inventory, so a player wielding a dragon/infernal/crystal harpoon couldn't fish at all. */
    private val HARPOONS: List<Pair<String, Int>> = listOf(
        "item.crystal_harpoon" to 8336,
        "item.infernal_harpoon" to 7402, "item.infernal_harpoon_or" to 7402,
        "item.dragon_harpoon" to 7401, "item.dragon_harpoon_or" to 7401,
        "item.barbtail_harpoon" to 618,
        "item.harpoon" to 618,
    ).filter { resolves(it.first) }

    private fun hasHarpoon(player: Player, key: String): Boolean {
        val id = getRSCM(key)
        return player.inventory.getItemCount(id) > 0 ||
            player.getEquipment(org.alter.api.EquipmentType.WEAPON)?.id == id
    }

    /** True when the player carries the method's tool (harpoon = any tier, inv or wielded). */
    private fun hasMethodTool(player: Player, method: Method): Boolean =
        if (method === harpoon) HARPOONS.any { hasHarpoon(player, it.first) }
        else player.inventory.getItemCount(getRSCM(method.tool)) > 0

    /** The animation for a method's catch — for the harpoon, the best tier the player holds. */
    private fun methodAnim(player: Player, method: Method): Int =
        if (method === harpoon) HARPOONS.firstOrNull { hasHarpoon(player, it.first) }?.second ?: ANIM_HARPOON
        else method.anim

    /** The catch to go for, in order: the player's explicit pick, then the fish Vannaka has them
     *  contracted to gather, then the best eligible. Without the contract step a player at level
     *  15+ with a net always lands anchovies and never credits a "gather raw shrimps" contract. */
    private fun target(player: Player): Fish? {
        val can = eligible(player)
        if (can.isEmpty()) return null
        val pref = player.attr[fishPref]
        can.firstOrNull { it.raw == pref }?.let { return it }
        val contracted = org.alter.plugins.content.skills.slayer.ResourceContracts.contractedKey(player)
        can.firstOrNull { it.raw == contracted }?.let { return it }
        return can.maxByOrNull { it.level }
    }

    /** The choose-fish menu. Pages in fours so the WHOLE eligible ladder stays reachable — the old
     *  flat `take(4)` hid everything below the top four, which is how a shrimps contract became
     *  unfishable once anchovies/sardine/herring/trout were unlocked. */
    private suspend fun chooseFish(task: QueueTask, player: Player, autoFish: Boolean = true) {
        val can = eligible(player).sortedByDescending { it.level }
        if (can.isEmpty()) { noGear(player); return }
        // The chatbox option list holds 5 rows. One page fits 4 + Nevermind; if the ladder is longer
        // we spend a row on "More fish..." and page 3 at a time.
        val perPage = if (can.size > 4) 3 else 4
        val pages = (can.size + perPage - 1) / perPage
        var page = 0
        while (true) {
            val picks = can.drop(page * perPage).take(perPage)
            val labels = picks.map { "${it.name.replaceFirstChar { c -> c.uppercase() }} (lvl ${it.level}, ${it.method.label})" } +
                (if (pages > 1) listOf("More fish...") else emptyList()) + "Nevermind"
            val sel = task.options(player, *labels.toTypedArray(), title = "What would you like to fish?")
            if (pages > 1 && sel == picks.size + 1) { page = (page + 1) % pages; continue } // More...
            val fishPicked = picks.getOrNull(sel - 1) ?: return // Nevermind / closed
            player.attr[fishPref] = fishPicked.raw
            player.message("You focus on catching <col=801700>${fishPicked.name}</col>. (Click a fishing spot to begin; the best catch resumes if this becomes unavailable.)")
            if (autoFish) fish(task, player)
            return
        }
    }

    private fun noGear(player: Player) {
        player.message("You don't have the gear to fish anything here — Gerrant at the Lumbridge market sells nets, rods, bait and feathers.")
    }

    private suspend fun fish(task: QueueTask, player: Player) {
        if (ladder.isEmpty()) return
        val standing = player.tile
        while (player.tile == standing) {
            if (player.inventory.isFull) {
                player.message("Your inventory is too full to hold any more fish.")
                break
            }
            val catch = target(player) ?: run { noGear(player); return }
            player.animate(methodAnim(player, catch.method))
            player.message(catch.method.verb)
            task.wait(CATCH_TICKS)
            if (player.tile != standing) break
            if (player.inventory.isFull) break
            // Re-check at catch time (bait may have run out mid-wait).
            val landed = target(player) ?: run { noGear(player); return }
            val bait = landed.method.bait
            if (bait != null && player.inventory.remove(item = getRSCM(bait), amount = 1).completed == 0) continue
            player.inventory.add(item = getRSCM(landed.raw), amount = 1)
            player.addXp(Skills.FISHING, landed.xp)
            player.message("You catch some ${landed.name}.")
            org.alter.plugins.content.skills.slayer.ResourceContracts.onGather(player, getRSCM(landed.raw)) // Vannaka resource contract
        }
    }

    private fun resolves(key: String): Boolean = try { getRSCM(key); true } catch (e: Exception) { false }

    private companion object {
        // Per-tool fishing animations (were all hard-played as the net cast, 621).
        const val ANIM_NET = 621     // small net
        const val ANIM_BIG_NET = 620 // big net
        const val ANIM_ROD = 623     // bait & fly rods (622 is the OILY rod cast)
        const val ANIM_CAGE = 619    // lobster pot
        const val ANIM_HARPOON = 618 // harpoon
        const val CATCH_TICKS = 4
    }
}

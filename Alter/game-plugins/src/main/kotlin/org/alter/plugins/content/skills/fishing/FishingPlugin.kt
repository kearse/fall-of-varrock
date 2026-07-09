package org.alter.plugins.content.skills.fishing

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
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

    /** A fishing method: the tool it needs and (for rods) the bait consumed per catch. */
    private data class Method(val label: String, val tool: String, val bait: String? = null, val verb: String)

    private val net = Method("Small net", "item.small_fishing_net", verb = "You cast out your net...")
    private val baitRod = Method("Bait rod", "item.fishing_rod", "item.fishing_bait", "You bait your hook and cast...")
    private val flyRod = Method("Fly rod", "item.fly_fishing_rod", "item.feather", "You whip out your fly rod and cast...")
    private val cage = Method("Lobster cage", "item.lobster_pot", verb = "You lower the cage...")
    private val harpoon = Method("Harpoon", "item.harpoon", verb = "You ready your harpoon...")
    private val bigNet = Method("Big net", "item.big_fishing_net", verb = "You cast out the big net...")

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
        Fish("item.raw_monkfish", "monkfish", 62, 120.0, bigNet),
        Fish("item.raw_shark", "shark", 76, 110.0, harpoon),
    ).filter { resolves(it.raw) && resolves(it.method.tool) && (it.method.bait == null || resolves(it.method.bait)) }

    /** Session-only preferred catch (raw item key), set via the choose-fish menu. */
    private val fishPref = AttributeKey<String>()

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
        if (opts.size < 2) logger.warn { "fishing: spot has a single option; choose-fish menu is via ::fish." }

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
                player.inventory.getItemCount(getRSCM(f.method.tool)) > 0 &&
                (f.method.bait == null || player.inventory.getItemCount(getRSCM(f.method.bait)) > 0)
        }
    }

    /** The catch to go for: the session pick while it stays eligible, else the best eligible. */
    private fun target(player: Player): Fish? {
        val can = eligible(player)
        if (can.isEmpty()) return null
        val pref = player.attr[fishPref]
        return can.firstOrNull { it.raw == pref } ?: can.maxByOrNull { it.level }
    }

    private suspend fun chooseFish(task: QueueTask, player: Player, autoFish: Boolean = true) {
        val can = eligible(player)
        if (can.isEmpty()) { noGear(player); return }
        // Highest first; cap the menu at 4 picks + Nevermind (dialog option limit).
        val picks = can.sortedByDescending { it.level }.take(4)
        val labels = picks.map { "${it.name.replaceFirstChar { c -> c.uppercase() }} (lvl ${it.level}, ${it.method.label})" } + "Nevermind"
        val sel = task.options(player, *labels.toTypedArray(), title = "What would you like to fish?")
        val fishPicked = picks.getOrNull(sel - 1) ?: return
        player.attr[fishPref] = fishPicked.raw
        player.message("You focus on catching <col=801700>${fishPicked.name}</col>. (Click a fishing spot to begin; the best catch resumes if this becomes unavailable.)")
        if (autoFish) fish(task, player)
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
            player.animate(FISH_ANIM)
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
        const val FISH_ANIM = 621 // net cast / fishing motion
        const val CATCH_TICKS = 4
    }
}

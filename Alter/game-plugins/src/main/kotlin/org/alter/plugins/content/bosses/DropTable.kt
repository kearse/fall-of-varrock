package org.alter.plugins.content.bosses

import org.alter.game.model.World

/**
 * A reusable, weighted **drop table** for PvM content (the content faucet — see the
 * growth roadmap, Phase 4). Three tiers mirror OSRS loot:
 *
 *  - [always]  — dropped on every kill (bones, base mats).
 *  - [main]    — a single weighted pick from the table (one entry per kill, picked by [DropEntry.weight]).
 *  - [rare]    — independent "1 in N" rolls ([DropEntry.oneInN]); each can drop on top of the main roll.
 *
 * Pure data + a [roll] that resolves amounts via the world RNG; item ids stay as RSCM
 * keys so the table is declarative and the owning plugin resolves + spawns the loot.
 */
data class DropEntry(
    /** RSCM item key, e.g. `item.runite_bar`. */
    val item: String,
    val min: Int = 1,
    val max: Int = 1,
    /** Relative weight within the [main] tier (ignored for always/rare tiers). */
    val weight: Int = 1,
    /** "1 in N" chance for the [rare] tier (ignored for always/main tiers). */
    val oneInN: Int = 0,
    /** Server-wide "rare drop" broadcast when this is dropped. */
    val announce: Boolean = false,
    /** Record this drop in the player's Collection Log. */
    val log: Boolean = false,
)

/** A resolved drop ready to spawn: concrete item key + amount + its flags. */
data class RolledDrop(val item: String, val amount: Int, val announce: Boolean, val log: Boolean)

class DropTable(
    private val always: List<DropEntry> = emptyList(),
    private val main: List<DropEntry> = emptyList(),
    private val rare: List<DropEntry> = emptyList(),
) {
    /** Roll the whole table for one kill. */
    fun roll(world: World): List<RolledDrop> {
        val out = ArrayList<RolledDrop>()

        always.forEach { out += it.resolve(world) }

        if (main.isNotEmpty()) {
            val total = main.sumOf { it.weight }
            var pick = world.random(total - 1) // 0..total-1
            for (e in main) {
                if (pick < e.weight) {
                    out += e.resolve(world)
                    break
                }
                pick -= e.weight
            }
        }

        rare.forEach { e ->
            if (e.oneInN > 0 && world.chance(1, e.oneInN)) {
                out += e.resolve(world)
            }
        }

        return out
    }

    private fun DropEntry.resolve(world: World): RolledDrop {
        val amount = if (max <= min) min else min + world.random(max - min)
        return RolledDrop(item, amount, announce, log)
    }
}

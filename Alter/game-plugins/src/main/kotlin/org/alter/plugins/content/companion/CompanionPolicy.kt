package org.alter.plugins.content.companion

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.entity.Player
import org.alter.plugins.content.raids.RaidInstance

private val logger = KotlinLogging.logger {}

/**
 * **Where companions may take the field.** Content registers rules ("not in the Fight Cave",
 * "not inside a Vorkath instance"); [CompanionRegistry] asks [verdict] before it spawns, summons,
 * recruits-to-the-field, or drives a companion's brain, and benches the active companion in
 * place while its owner stands somewhere denied (resuming when they leave).
 *
 * This is the Block-1 "boss compatibility" seam: classic OSRS bosses stay companion-unaware —
 * their mechanics are never edited for companions — and instead simply deny them here.
 * Companion-AWARE encounters are FoV-original content only. Nothing is denied by default;
 * shared-world content (GWD, marches, the Wizard Tower instance) stays allowed until it opts out.
 */
object CompanionPolicy {

    /** Why a companion may not take the field here — shown to the owner as "Sir X stands down — <reason>". */
    data class Verdict(val reason: String)

    fun interface Rule {
        /** Non-null = denied. [tile] is the owner's tile (the companion follows it). Must not throw. */
        fun check(owner: Player, tile: Tile): Verdict?
    }

    private val rules = ArrayList<Rule>()

    fun register(rule: Rule) { rules += rule }

    /** Deny while the owner stands inside [area] (live-map coordinates). */
    fun denyArea(area: Area, reason: String) =
        register { _, tile -> if (area.contains(tile)) Verdict(reason) else null }

    /**
     * Deny while the owner stands inside ANY instance copied from [source] — the rule for solo
     * instanced bosses (Vorkath/Zulrah/Hydra allocate a fresh [RaidInstance] copy of their arena
     * per fight, so a fixed [Area] can't describe it).
     */
    fun denyInstanceOf(source: Area, reason: String) =
        register { owner, tile ->
            val src = RaidInstance.sourceOf(owner.world, tile)
            if (src != null && sameBounds(src, source)) Verdict(reason) else null
        }

    /** The first rule that denies [owner] where they stand, or null if companions are welcome. */
    fun verdict(owner: Player): Verdict? {
        val tile = owner.tile
        for (r in rules) {
            val v = runCatching { r.check(owner, tile) }
                .onFailure { logger.error(it) { "CompanionPolicy rule threw for ${owner.username} (ignored)" } }
                .getOrNull()
            if (v != null) return v
        }
        return null
    }

    fun denied(owner: Player): Boolean = verdict(owner) != null

    /** Tell the owner why their companion stands down — the standard line, one voice everywhere. */
    fun notify(owner: Player, name: String, v: Verdict) =
        owner.message("<col=801700>Sir $name stands down — ${v.reason}.</col>")

    private fun sameBounds(a: Area, b: Area): Boolean =
        a.bottomLeftX == b.bottomLeftX && a.bottomLeftY == b.bottomLeftY &&
            a.topRightX == b.topRightX && a.topRightY == b.topRightY
}

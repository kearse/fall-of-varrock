package org.alter.plugins.content.skills.agility

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.ForcedMovement
import org.alter.game.model.World
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.skills.agility.ObstacleCrossing.Kind

private val logger = KotlinLogging.logger {}

/**
 * **Agility shortcuts** — the world-traversal obstacles gated by an Agility level: climb a stile,
 * squeeze through a crevice, hop the stepping stones, walk a log balance, etc. These objects
 * already exist in the map cache at their real OSRS locations; this plugin binds behaviour to
 * them so clicking actually carries the player across (animated, like the ditch crossing — not
 * an instant teleport).
 *
 * Each binding is `onObjOption(id, action)`, which fires for **every** placement of that object
 * in the world, so one entry covers the shortcut everywhere it appears. The object ids/actions
 * were enumerated from the cache by [org.alter.tools.shortcutscan.ShortcutScanTool] (gradle
 * `shortcutScan`); see `data/shortcutscan/shortcut-locs.tsv` for the full backlog of placements.
 *
 * The level requirement and crossing direction are NOT in the cache (they're OSRS design data),
 * so they're encoded here. Adding another shortcut = one [Entry] row. The destination is computed
 * from the clicked object + the player's side, so no per-location tile tables are needed.
 *
 * Deliberately scoped to **standalone** shortcuts. Rooftop-course pieces (Gap/Ledge/Tightrope/
 * Hand-holds/Balancing-ledge/Monkeybars) and quest/grapple obstacles are left unbound — those
 * need bespoke handling and shouldn't be a generic free cross.
 */
class AgilityShortcutPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Entry(
        val id: Int,
        val action: String,
        val level: Int,
        val xp: Double,
        val anim: Int,
        val kind: Kind = Kind.CROSS,
    )

    // ---- shortcut table -----------------------------------------------------------------------
    // Each line: list of object ids (paired with the EXACT cache action) → Agility level gate, xp,
    // animation, crossing kind. Levels are a tuned difficulty ladder (stiles trivial → log balances
    // hardest) so Agility actually gates access; adjust the numbers freely. Animations are the
    // canonical OSRS agility ids, all verified present in the rev-228 cache.

    /** Add the same params for several (id, action) pairs. */
    private fun MutableList<Entry>.bind(
        action: String, level: Int, xp: Double, anim: Int, kind: Kind = Kind.CROSS, vararg ids: Int,
    ) = ids.forEach { add(Entry(it, action, level, xp, anim, kind)) }

    private val entries: List<Entry> = buildList {
        // Stiles — climb over a fence. Trivial.
        bind("Climb-over", level = 1, xp = 3.0, anim = ANIM_CLIMBOVER, ids = intArrayOf(993, 3730, 7527, 12982, 22302, 19222))
        // Broken fences — trivial.
        bind("Jump", level = 1, xp = 3.0, anim = ANIM_CLIMBOVER, ids = intArrayOf(544))
        bind("Climb-over", level = 1, xp = 3.0, anim = ANIM_CLIMBOVER, ids = intArrayOf(2618, 18411))
        // Rocks — easy climb. (2962 culled by audit: void landing at its lone instanced placement.)
        bind("Climb-over", level = 5, xp = 5.0, anim = ANIM_CLIMB, ids = intArrayOf(2963, 2964))
        // Rockslides — easy climb.
        bind("Climb-over", level = 10, xp = 8.0, anim = ANIM_CLIMB, ids = intArrayOf(3309, 5847))
        // NOTE: Crevices (15186/15187/15194-15197/16539/16543) and Obstacle pipes (16509/16511/
        // 20210/23136-23140) are intentionally UNBOUND. The audit (shortcutScan audit) showed every
        // crevice placement is a one-sided tunnel mouth underground, and obstacle pipes teleport you
        // THROUGH to a distant exit — neither fits the generic one-tile cross. They need explicit
        // src→dest endpoint pairs; left as future work rather than ship a broken cross.
        // Jutting walls — moderate squeeze (incl. the deep-wilderness shortcut).
        bind("Squeeze-past", level = 20, xp = 10.0, anim = ANIM_SQUEEZE, ids = intArrayOf(16532, 17002, 22552))
        // Stepping stones — hop onto the stone. The clickable action differs per stone id.
        // (4411 is owned by the Mire Run course [AgilityPlugin], which tile-dispatches: course
        // placements run the lap, remote placements get the same generic hop as here. Binding it
        // in both plugins kills this whole plugin at load — "already bound to a plugin".)
        bind("Jump-to", level = 15, xp = 6.0, anim = ANIM_JUMP, kind = Kind.ONTO, ids = intArrayOf(11643, 15412, 16513, 19042))
        bind("Jump-across", level = 15, xp = 6.0, anim = ANIM_JUMP, kind = Kind.ONTO, ids = intArrayOf(5948, 5949))
        bind("Cross", level = 15, xp = 6.0, anim = ANIM_JUMP, kind = Kind.ONTO, ids = intArrayOf(10663, 11768, 13504, 16466, 19040, 23556))
        bind("Jump-onto", level = 15, xp = 6.0, anim = ANIM_JUMP, kind = Kind.ONTO, ids = intArrayOf(16533))
        bind("Jump", level = 15, xp = 6.0, anim = ANIM_JUMP, kind = Kind.ONTO, ids = intArrayOf(21126, 21127, 21128, 21129, 21130, 21131, 21132, 21133))
        // Log balances — walk across. Hardest tier. (3553/3555/3557 culled: one-sided underground.)
        bind("Cross", level = 30, xp = 12.0, anim = ANIM_BALANCE, ids = intArrayOf(3931, 3932, 3933))
        bind("Walk-across", level = 30, xp = 12.0, anim = ANIM_BALANCE, ids = intArrayOf(16540, 16542, 16546, 16548, 20882, 20884, 23144, 23145, 23274, 23542))
        // Rope bridges — walk across. (21316-21319 culled: one-sided underground bridges.)
        bind("Cross", level = 25, xp = 10.0, anim = ANIM_BALANCE, ids = intArrayOf(5002))
        bind("Walk-across", level = 25, xp = 10.0, anim = ANIM_BALANCE, ids = intArrayOf(21306, 21307, 21308, 21309, 21315))
    }

    init {
        entries.forEach { e ->
            onObjOption(obj = e.id, option = e.action) {
                val obj = player.getInteractingGameObj()
                use(player, obj, e)
            }
        }
        logger.info { "agility shortcuts bound: ${entries.size} object/action pairs" }
    }

    private fun use(player: Player, obj: GameObject, e: Entry) {
        val level = player.getSkills().getCurrentLevel(Skills.AGILITY)
        if (level < e.level) {
            player.message("You need an Agility level of ${e.level} to use this shortcut.")
            return
        }

        val dir = ObstacleCrossing.cardinalDir(player.tile, obj)
        val dest = ObstacleCrossing.crossTile(world, player.tile, obj, dir, e.kind)

        player.faceTile(obj.tile)
        player.queue {
            if (e.anim > 0) player.animate(e.anim)
            val dist = ObstacleCrossing.chebyshev(player.tile, dest).coerceAtLeast(1)
            val dur2 = (30 * dist).coerceIn(30, 180)
            val movement = ForcedMovement.of(player.tile, dest, dur2 / 2, dur2, dir.angle)
            player.forceMove(this, movement)
            player.addXp(Skills.AGILITY, e.xp)
        }
    }

    private companion object {
        // Canonical OSRS agility-obstacle animations (all verified present in the rev-228 cache).
        const val ANIM_CLIMBOVER = 839 // climb over a stile / low fence
        const val ANIM_CLIMB = 828 // climb up rocks / rockslide
        const val ANIM_SQUEEZE = 844 // squeeze through a gap / past a wall
        const val ANIM_JUMP = 769 // leap onto a stepping stone
        const val ANIM_BALANCE = 762 // balance-walk across a log / rope bridge
    }
}

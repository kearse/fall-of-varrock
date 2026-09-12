package org.alter.plugins.content.war.outposts

import org.alter.game.model.Area
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.entity.Player
import org.alter.plugins.content.mechanics.Flags
import org.alter.plugins.content.teleport.TransportRoutes

/**
 * **The Southern Watch** — the realm's first forward post, on the stone circle south of Fallen
 * Varrock's gate (Main Story Quest 4, *First Reclamation*; spec `docs/quests/first-reclamation.md`).
 *
 * Pure data + the per-player unlock reads. The post is **shared-world** ([SouthernWatchPlugin]
 * spawns the standard, a small Knights-of-Lumbridge garrison and a Field Quartermaster for
 * everyone, always); what a player *earns* by finishing the quest is personal: the fast-travel
 * route ([ROUTE] — the portal row, General Zo's "Send me to the Southern Watch", `::southernwatch`)
 * and the [FLAG] milestone. The surrounding road stays the `varrock_outskirts` march target — the
 * enemy keeps contesting it, the post is simply where the realm meets them now. No per-player world
 * state, no territory meter, no map edit (design authority: the shared-world rule).
 *
 * Geometry (region 12852 collision dump `data/mapdump/r12852_50_52.txt`): the ring's "Stone circle
 * wall" locs span 3222-3233 × 3364-3375 around a solid 2x2 centre stone at 3227,3369 (loc 17454).
 * The march's own rally tile (3213,3376) is the road just west of the ring.
 */
object SouthernWatch {

    /** Player-facing name. */
    const val NAME = "the Southern Watch"

    /** [TransportRoutes] key (also the portal row key `TeleportRegistry`) — locked until First Reclamation. */
    const val ROUTE = "southern_watch"

    /** [Flags] milestone: this player raised the standard (First Reclamation complete). */
    const val FLAG = "southern_watch"

    /** The march target whose ground the post sits on (`MarchTargets.VARROCK_OUTSKIRTS`). */
    const val TARGET_KEY = "varrock_outskirts"

    /** The ring's centre stone (solid 2x2, 3227-3228 × 3369-3370). */
    val CENTRE = Tile(3227, 3369, 0)

    /** The stone circle itself — walls included — the ground the post holds. */
    val RING = Area(3221, 3363, 3234, 3376)

    /** Where the route lands a player: inside the ring, clear of the stones. */
    val LANDING = Tile(3225, 3371, 0)

    /**
     * The standard at the circle's heart — the Castle Wars **Saradomin Standard** (loc 4902, a banner
     * on its stand, option **Capture**), the realm's Saradomin iconography reused unchanged: no new
     * model, no cache rename (locs cannot be renamed at runtime). North of the centre stone.
     */
    const val STANDARD_OBJ = "object.saradomin_standard_4902"
    const val STANDARD_OPTION = "Capture"
    val STANDARD_TILE = Tile(3227, 3372, 0)

    /** The Field Quartermaster — `npc.quartermaster` renamed at spawn, a third SupplyDepot post. */
    const val QUARTERMASTER_NPC = "npc.quartermaster"
    const val QUARTERMASTER_NAME = "Field Quartermaster"
    val QUARTERMASTER_TILE = Tile(3230, 3369, 0)

    /** The garrison: three Knights of Lumbridge on the ring's inner edge, facing out. */
    const val KNIGHT_NPC = "npc.knight_of_saradomin" // shown as "Knight of Lumbridge" (WarNpcNames)
    val KNIGHT_POSTS: List<Pair<Tile, Direction>> = listOf(
        Tile(3224, 3372, 0) to Direction.WEST,
        Tile(3231, 3372, 0) to Direction.EAST,
        Tile(3227, 3366, 0) to Direction.SOUTH,
    )

    /** "At the post" radius around [CENTRE] — the Quartermaster hand-in + the Field Quartermaster title. */
    const val POST_RADIUS = 10

    fun isAtPost(p: Player): Boolean = p.tile.isWithinRadius(CENTRE, POST_RADIUS)

    /** Has [p] earned the post (First Reclamation complete)? */
    fun isUnlocked(p: Player): Boolean = TransportRoutes.isUnlocked(p, ROUTE) || Flags.has(p, FLAG)

    /**
     * Grant the post to [p]: the route and the milestone flag. The quest's completion reward calls
     * this; admins can too. Idempotent.
     */
    fun unlock(p: Player) {
        TransportRoutes.unlock(p, ROUTE)
        Flags.set(p, FLAG)
    }

    /**
     * What a **Capture** on the standard does for [p] beyond the post's own flavour — the quest
     * plugin installs its hook here (one-way dependency: the quest knows the outpost, never the
     * reverse). Return true when the click was consumed.
     */
    @Volatile
    var onCapture: (Player) -> Boolean = { false }
}

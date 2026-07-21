package org.alter.plugins.content.npcs.elite

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.ForcedMovement
import org.alter.game.model.World
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.skills.agility.ObstacleCrossing

private val logger = KotlinLogging.logger {}

/**
 * **Vorkath access — the Ungael ice chunks.** The Vorkath teleport lands the player next to the
 * lair, but the fight platform is fenced off by "ice chunks" you climb over. Those objects exist
 * in the map cache but had no interaction bound, so clicking *Climb-over* did nothing and the
 * player was stranded short of the boss (the reported bug).
 *
 * This binds the three lateral ice-chunk objects to an animated forced-movement cross, reusing
 * [ObstacleCrossing]'s landing math (same as the Agility shortcuts). Unlike an Agility shortcut
 * this is **free** — no level requirement and no XP — because it's boss access, not a skill. The
 * crossing direction/landing is derived from the clicked object + the player's side, so the same
 * binding carries the player in **both** directions (in to fight, back out to leave).
 *
 * Only the lateral "I can climb over these" chunks are bound (31990/31992/31994). The decorative
 * chunks (31991/31993/31995) and the climb-up/down chunks (31996/31998 — level changes) are left
 * alone.
 */
class VorkathAccessPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        for (id in ICE_CHUNK_IDS) {
            if (!bindCross(id)) {
                logger.warn { "VorkathAccessPlugin: no crossing action found on ice-chunk object $id — not bound" }
            }
        }
    }

    /**
     * Bind the first crossing verb that actually exists on the object's cache definition.
     * [org.alter.game.plugin.KotlinPlugin.onObjOption] throws if the verb isn't a real action on
     * the object (which would otherwise fail-load the whole plugin), and the exact verb lives only
     * in the binary cache — so we try the plausible candidates and stop at the one that binds.
     */
    private fun bindCross(id: Int): Boolean {
        for (verb in CROSS_VERBS) {
            try {
                onObjOption(obj = id, option = verb) {
                    val obj = player.getInteractingGameObj()
                    cross(player, obj)
                }
                return true
            } catch (ex: Exception) {
                // Verb not present on this object's def (or already bound elsewhere) — try the next.
                logger.debug { "ice-chunk $id: '$verb' not bindable (${ex.message})" }
            }
        }
        return false
    }

    private fun cross(player: Player, obj: GameObject) {
        val dir = ObstacleCrossing.cardinalDir(player.tile, obj)
        val dest = ObstacleCrossing.crossTile(world, player.tile, obj, dir, ObstacleCrossing.Kind.CROSS)

        player.faceTile(obj.tile)
        player.queue {
            player.animate(ANIM_CLIMBOVER)
            val dist = ObstacleCrossing.chebyshev(player.tile, dest).coerceAtLeast(1)
            val dur = (30 * dist).coerceIn(30, 180)
            val movement = ForcedMovement.of(player.tile, dest, dur / 2, dur, dir.angle)
            player.forceMove(this, movement)
        }
    }

    private companion object {
        /** The lateral, crossable Ungael ice chunks ("I can climb over these."). */
        val ICE_CHUNK_IDS = intArrayOf(31990, 31992, 31994)

        /** Candidate cache action verbs for the crossing (first one present on the def wins). */
        val CROSS_VERBS = listOf("Climb-over", "Cross")

        /** Climb-over animation (shared with the Agility stiles). */
        const val ANIM_CLIMBOVER = 839
    }
}

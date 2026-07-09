package org.alter.plugins.content.weapons.custom

import org.alter.game.Server
import org.alter.game.model.EntityType
import org.alter.game.model.Graphic
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.WeaponEffects
import org.alter.plugins.content.combat.dealExactHit

/**
 * Staff of the Storm - chain-lightning magic weapon. When a cast lands, the bolt
 * arcs to nearby enemies for reduced damage. Implemented as a passive on-hit
 * effect via [WeaponEffects], so it triggers on the staff's normal casts.
 *
 * Chaining is restricted to NPCs only: arcing to other players would need
 * multi-combat-area validation to be PvP-fair, so player chaining is left for a
 * later PvP pass. The arc range is one tile around the primary target.
 *
 *  - PK-safe (`tumekens_shadow`): arcs to 1 extra target for 35% damage.
 *  - PvM/donor (`corrupted_tumekens_shadow`): arcs to 2 extra targets for 50%.
 */
class StaffOfTheStormPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private companion object {
        const val LIGHTNING_GFX = 161
        const val LIGHTNING_GFX_HEIGHT = 124
    }

    init {
        registerChain("item.tumekens_shadow", maxChains = 1, splashPercent = 35)
        registerChain("item.corrupted_tumekens_shadow", maxChains = 2, splashPercent = 50)
    }

    private fun registerChain(
        item: String,
        maxChains: Int,
        splashPercent: Int,
    ) {
        WeaponEffects.registerOnHit(item) {
            if (!landed || damage <= 0) return@registerOnHit

            val splash = maxOf(1, damage * splashPercent / 100)
            nearbyNpcs(world, target.tile, exclude = target)
                .take(maxChains)
                .forEach { npc ->
                    npc.graphic(Graphic(LIGHTNING_GFX, LIGHTNING_GFX_HEIGHT, 0))
                    player.dealExactHit(target = npc, damage = splash, delay = 1)
                }
        }
    }

    /**
     * Living NPCs within one tile of [centre], excluding [exclude]. Scans the
     * 3x3 block of tiles around the primary target via the chunk entity index.
     */
    private fun nearbyNpcs(
        world: World,
        centre: org.alter.game.model.Tile,
        exclude: org.alter.game.model.entity.Pawn,
    ): List<Npc> {
        val found = mutableListOf<Npc>()
        for (dx in -1..1) {
            for (dz in -1..1) {
                val tile = centre.transform(dx, dz)
                val chunk = world.chunks.get(tile) ?: continue
                chunk.getEntities<Npc>(tile, EntityType.NPC).forEach { npc ->
                    if (npc != exclude && !npc.isDead()) {
                        found.add(npc)
                    }
                }
            }
        }
        return found
    }
}

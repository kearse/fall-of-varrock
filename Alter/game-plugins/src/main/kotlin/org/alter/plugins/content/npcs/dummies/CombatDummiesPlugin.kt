package org.alter.plugins.content.npcs.dummies

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.dsl.*
import org.alter.api.ext.npc
import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.attr.NO_LOOT_ATTR
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.war.WarNpcNames
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Combat dummies at home** (community suggestion 2026-09-13: "add max hit dummies and undead
 * dummies at home").
 *
 * Two dummies stand at the east end of the Lumbridge market aisle, beside the combat vendors:
 *  - a **max hit dummy** — the plain combat dummy, for reading your real max off the hitsplats;
 *  - an **undead dummy** — the same, but undead, so salve amulet / crumble-undead style bonuses
 *    can be checked against something that actually counts as undead.
 *
 * What makes a dummy a *dummy* rather than a monster is the def below, and every field in it is
 * load-bearing:
 *  - **Zero defence and zero defensive bonuses** — every swing lands. A dummy that could block
 *    would be useless for reading a max hit, because a splat you didn't see might have been the
 *    big one.
 *  - **Zero attack, strength, magic and ranged, and no aggression** — it never fights back and
 *    never starts anything. You can stand here in a bank tab's worth of gear with no risk.
 *  - **[DUMMY_HP] hitpoints** — not literal invulnerability (the engine has no such flag) but
 *    enough that no realistic session kills one; a fast respawn covers the case where somebody
 *    manages it anyway.
 *  - **[NO_LOOT_ATTR]** — the generic OSRS drop plugin would otherwise pay a drop table for
 *    whatever the cache thinks a dummy is worth. Training equipment pays nothing.
 *
 * Both npcs are renamed through [WarNpcNames] so the right-click reads as what they are rather
 * than the cache's bare "Combat dummy" / "Undead combat dummy" — no cache edit needed.
 */
class CombatDummiesPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Dummy(val key: String, val label: String, val x: Int, val z: Int)

    /**
     * East end of the market aisle (z=3227), just past the vendor row that runs x3207–3215 at
     * z=3228 facing south — so the aisle players already walk to reach Horvik and Lowe carries the
     * dummies at its far end, out of the shop traffic. **TUNE the two tiles in-game.**
     */
    private val dummies = listOf(
        Dummy("npc.combat_dummy_2668", "Max hit dummy", 3216, 3227),
        Dummy("npc.undead_combat_dummy_7413", "Undead dummy", 3218, 3227),
    )

    init {
        dummies.forEach { d ->
            if (runCatching { getRSCM(d.key) }.isFailure) {
                logger.warn { "dummies: '${d.key}' not in cache; ${d.label} not spawned." }
                return@forEach
            }
            setCombatDef(d.key) {
                immunities { poison = true; venom = true }
                // respawnDelay is short rather than 0: 0 would mean "never respawns", and a dummy
                // somebody grinds down over an afternoon must come back.
                configs { attackSpeed = 4; respawnDelay = 25 }
                aggro { radius = 0; searchDelay = 1 }
                stats {
                    hitpoints = DUMMY_HP
                    attack = 1; strength = 1; defence = 0; magic = 1; ranged = 1
                }
                bonuses {
                    defenceStab = 0; defenceSlash = 0; defenceCrush = 0
                    defenceMagic = 0; defenceRanged = 0
                }
            }
            spawnNpc(d.key, x = d.x, z = d.z, height = 0, walkRadius = 0, direction = Direction.SOUTH)
            // Fires on the boot spawn AND on every respawn, so the flags survive a kill.
            onNpcSpawn(d.key) {
                npc.attr[NO_LOOT_ATTR] = true
                WarNpcNames.rename(npc, d.label)
            }
        }
        logger.info { "dummies: ${dummies.size} training dummies ready at the Lumbridge market aisle." }
    }

    private companion object {
        /** High enough that no realistic session fells one; not a magic invulnerability flag. */
        const val DUMMY_HP = 30_000
    }
}

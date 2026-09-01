package org.alter.plugins.content.drops

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.npc
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.attr.NO_LOOT_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * **Generic real-OSRS NPC drops.** Previously every non-boss monster dropped nothing; this binds a
 * single [onAnyNpcDeath] handler that looks the dead npc up in [NpcDropTables] (real OSRS tables +
 * rates, generated from the osrsbox dataset) and spawns its loot for the killer.
 *
 * Boss loot stays with the bespoke boss plugins: any npc that already has its own death handler is
 * skipped ([hasNpcDeathHandler]) so we never double-drop on top of e.g. the KBD table. Only
 * player kills drop (matching OSRS), which also keeps war/AI mob deaths from littering the ground.
 *
 * Economy dials (coin faucet width, exclusions) live in `data/cfg/drops/config.yml` — see
 * [NpcDropConfig].
 */
class NpcDropPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val logger = KotlinLogging.logger {}

    init {
        NpcDropConfig.load()
        NpcDropTables.load()

        onAnyNpcDeath {
            if (!NpcDropConfig.enabled || !NpcDropTables.loaded) return@onAnyNpcDeath

            val dead = npc
            val npcId = dead.id

            // Minigame-spawned mobs (Fight Cave waves, etc.) are reward-gated — no per-kill loot.
            if (dead.attr[NO_LOOT_ATTR] == true) return@onAnyNpcDeath

            // Skip npcs whose loot is owned by a bespoke boss/content plugin, or explicitly excluded.
            if (npcId in NpcDropConfig.excludeIds) return@onAnyNpcDeath
            if (hasNpcDeathHandler(npcId)) return@onAnyNpcDeath

            // OSRS drops go to the killer; no human killer (AI/war kills) => no loot.
            val killer = dead.attr[KILLER_ATTR]?.get() as? Player ?: return@onAnyNpcDeath

            GenericDrops.rollAndDrop(world, dead, killer)
        }

        logger.info { "Generic NPC drops active (${NpcDropTables.size} monster tables)." }
    }
}

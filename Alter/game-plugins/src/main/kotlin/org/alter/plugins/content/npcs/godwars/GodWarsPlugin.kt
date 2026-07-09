package org.alter.plugins.content.npcs.godwars

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.dsl.setCombatDef
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.economy.PointKind
import org.alter.plugins.content.economy.addPoints
import org.alter.plugins.content.economy.awardTickets
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Spawns each [GodWarsBosses] general + its three bodyguards in their throne room, registers
 * everyone's combat def, and handles the **general's** death → loot + Boss points + Collection
 * Log + broadcast (the KBD shape). Bodyguards use simple melee defs + the default combat
 * strategy; the general's dual-style rotation lives in [GodWarsCombatPlugin].
 *
 * **Deferred:** the killcount door (you currently spawn straight into the room), and the
 * bodyguards' true individual styles (Steelwill mage / Grimspike ranged, etc.).
 */
class GodWarsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    // Declared BEFORE init: Kotlin runs property initializers and init blocks in source order,
    // and init calls registerBodyguardDef() which reads this set.
    private val definedGuards = HashSet<String>()

    init {
        for (boss in GodWarsBosses.all) {
            spawnNpc(boss.key, boss.lair.x, boss.lair.z, boss.lair.height, walkRadius = 6)

            setCombatDef(boss.key) {
                configs {
                    attackSpeed = boss.attackSpeed
                    respawnDelay = RESPAWN_DELAY
                }
                aggro {
                    radius = 12
                    searchDelay = 1
                }
                stats {
                    hitpoints = boss.hp
                    attack = boss.att
                    strength = boss.str
                    defence = boss.def
                    magic = boss.mag
                    ranged = boss.rng
                }
                bonuses {
                    attackBonus = 150
                    strengthBonus = 130
                    defenceStab = 150
                    defenceSlash = 150
                    defenceCrush = 150
                    defenceMagic = 130
                    defenceRanged = 150
                }
                anims {
                    // The combat-def DSL requires a death animation, but OSRS npc defs carry none.
                    death = deathAnimFor(boss.key)
                }
            }

            // Bodyguards: spread around the general; one shared simple melee def each.
            boss.bodyguards.forEachIndexed { i, guard ->
                val off = BODYGUARD_OFFSETS[i % BODYGUARD_OFFSETS.size]
                spawnNpc(guard, boss.lair.x + off.first, boss.lair.z + off.second, boss.lair.height, walkRadius = 5)
                registerBodyguardDef(guard)
            }

            onNpcDeath(boss.key) {
                val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
                dropLoot(npc, killer, boss)
            }
        }
    }

    private fun registerBodyguardDef(guard: String) {
        if (!definedGuards.add(guard)) return // one def per npc id
        runCatching {
            setCombatDef(guard) {
                configs {
                    attackSpeed = 4
                    respawnDelay = RESPAWN_DELAY
                }
                aggro {
                    radius = 12
                    searchDelay = 1
                }
                stats {
                    hitpoints = 150
                    attack = 150
                    strength = 150
                    defence = 130
                }
                bonuses {
                    attackBonus = 100
                    strengthBonus = 90
                    defenceStab = 100
                    defenceSlash = 100
                    defenceCrush = 100
                }
                anims {
                    death = deathAnimFor(guard)
                }
            }
        }.onFailure { logger.warn { "GWD: bodyguard def for $guard already registered or failed" } }
    }

    private fun dropLoot(npc: Npc, killer: Player, boss: GodWarsBosses.GwdBoss) {
        killer.awardTickets(PointKind.BOSS, boss.bossPoints)
        boss.loot.roll(world).forEach { drop ->
            val id = runCatching { getRSCM(drop.item) }.getOrNull() ?: return@forEach
            world.spawn(GroundItem(id, drop.amount, npc.tile, killer))
            val name = runCatching { getItem(id).name }.getOrNull() ?: return@forEach
            if (drop.announce) {
                world.players.forEach {
                    it.message("<col=ff0000>News: ${killer.username} just received <col=ffae00>$name</col> from ${boss.name}!</col>")
                }
            }
            if (drop.log && CollectionLog.record(killer, id)) {
                killer.message("<col=ffae00>New Collection Log slot: $name!</col>")
            }
        }
        killer.message("<col=ff0000>You have slain ${boss.name}.</col> (+${boss.bossPoints} Boss Tickets)")
    }

    /**
     * The combat-def DSL requires a death animation, but OSRS npc definitions don't store one.
     * Fall back to the npc's own idle/stand animation — model-appropriate and guaranteed to
     * exist — so the boss/bodyguard loads; bespoke death anims can be added later.
     */
    private fun deathAnimFor(npcKey: String): Int {
        val id = runCatching { getRSCM(npcKey) }.getOrNull() ?: return FALLBACK_DEATH
        val stand = runCatching { getNpc(id).standAnim }.getOrNull() ?: -1
        return if (stand > 0) stand else FALLBACK_DEATH
    }

    private companion object {
        const val RESPAWN_DELAY = 100
        const val FALLBACK_DEATH = 836 // generic death animation
        val BODYGUARD_OFFSETS = listOf(2 to 2, -2 to 2, 2 to -2)
    }
}

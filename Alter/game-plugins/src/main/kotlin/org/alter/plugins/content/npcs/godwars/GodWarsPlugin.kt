package org.alter.plugins.content.npcs.godwars

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.dsl.setCombatDef
import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.game.Server
import org.alter.game.model.Tile
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
 * **Bodyguards respawn *with* the general, not on their own timer.** They're placed once at boot,
 * but their combat def has `respawnDelay = 0`, so a killed bodyguard is removed and stays gone —
 * the engine never resurrects it independently. Instead [onNpcSpawn] on the general fires whenever
 * the general (re)spawns and re-spawns its three bodyguards ([spawnBodyguards]). This stops the
 * minions reappearing before the general's ~90s respawn (the client's top-left Boss-timer estimate).
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

    // General ids whose *first* spawn (boot) has been seen. The boot spawn is already covered by
    // the static bodyguard placement below, so only later spawns (respawns) trigger a re-spawn.
    private val bootedGenerals = HashSet<Int>()

    init {
        for (boss in GodWarsBosses.all) {
            spawnCentred(boss.key, boss.lair.x, boss.lair.z, boss.lair.height, walkRadius = 6)
            warnIfUnattackable(boss.key)

            setCombatDef(boss.key) {
                configs {
                    attackSpeed = boss.attackSpeed
                    respawnDelay = RESPAWN_DELAY
                }
                aggro {
                    radius = 12
                    searchDelay = 1
                    // GWD generals are permanently aggressive in OSRS — no 10-minute tolerance and
                    // no combat-level cap. Without this the def falls back to the default 1000-cycle
                    // timer, so NpcAggroPlugin.defaultAggressiveness stops the general aggroing after
                    // ~10 min and skips higher-level players entirely. MAX_VALUE = always hostile.
                    aggroTimer = Int.MAX_VALUE
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

            // Bodyguards: spread around the general; one shared simple melee def each. Placed once
            // at boot; their def carries respawnDelay = 0 so a kill removes them for good — they
            // only return when the general does (see the onNpcSpawn hook below).
            boss.bodyguards.forEachIndexed { i, guard ->
                val off = BODYGUARD_OFFSETS[i % BODYGUARD_OFFSETS.size]
                spawnCentred(guard, boss.lair.x + off.first, boss.lair.z + off.second, boss.lair.height, walkRadius = 5)
                warnIfUnattackable(guard)
                registerBodyguardDef(guard)
            }

            // Tie the bodyguards' lifecycle to the general: this fires at boot (skipped — the static
            // placement above already covers it) and on every general respawn (brings them back).
            val generalId = getRSCM(boss.key)
            onNpcSpawn(boss.key) {
                if (bootedGenerals.add(generalId)) return@onNpcSpawn
                spawnBodyguards(boss)
            }

            onNpcDeath(boss.key) {
                val killer = npc.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
                dropLoot(npc, killer, boss)
            }
        }
    }

    /**
     * A player can only attack an npc whose **cache** definition carries an "Attack" action and a
     * combat level — `setCombatDef` gives it stats and aggression but does NOT add the right-click
     * "Attack" option (the client gates that on the cache def, see [OpNpcHandler]). If a GWD general
     * or bodyguard id is missing it, the npc spawns permanently unkillable and the only symptom is a
     * silent "nothing happens" in-game. Flag it loudly at boot so a bad id is caught here instead.
     *
     * The bodyguards ship without it — apply `gradlew :game-server:npcDef -PnpcArgs="krilbodyguards"`
     * against the cache (see [org.alter.tools.npcdef]) and restart to silence this for them.
     */
    private fun warnIfUnattackable(npcKey: String) {
        val def = runCatching { getNpc(getRSCM(npcKey)) }.getOrNull() ?: return
        if (def.combatLevel <= 0 || def.actions.none { it == "Attack" }) {
            logger.error {
                "GWD: '$npcKey' (id ${def.id}) is NOT player-attackable " +
                    "(combatLevel=${def.combatLevel}, actions=${def.actions.filterNotNull()}) — " +
                    "the cache def is missing an 'Attack' option, so players can't fight it."
            }
        }
    }

    /**
     * Re-spawn a general's three bodyguards at their throne-room offsets. Any survivors from the
     * previous set (e.g. if the general was somehow killed before its guards) are cleared first, so
     * exactly three return. Spawned with `respawns = false`: they never self-respawn — a fresh trio
     * appears only on the next general respawn. Placement mirrors [spawnCentred] (footprint centred
     * on the offset tile) so a respawned bodyguard lands exactly where the boot spawn put it.
     */
    private fun spawnBodyguards(boss: GodWarsBosses.GwdBoss) {
        val guardIds = boss.bodyguards.mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()
        val survivors = ArrayList<Npc>()
        world.npcs.forEach { n ->
            if (n.id in guardIds && n.tile.height == boss.lair.height &&
                Math.abs(n.tile.x - boss.lair.x) <= GUARD_RESET_RADIUS &&
                Math.abs(n.tile.z - boss.lair.z) <= GUARD_RESET_RADIUS
            ) {
                survivors += n
            }
        }
        survivors.forEach { world.remove(it) }

        boss.bodyguards.forEachIndexed { i, guard ->
            val off = BODYGUARD_OFFSETS[i % BODYGUARD_OFFSETS.size]
            val id = runCatching { getRSCM(guard) }.getOrNull() ?: return@forEachIndexed
            val half = (runCatching { getNpc(id).size }.getOrNull()?.takeIf { it > 0 } ?: 1) shr 1
            val cx = boss.lair.x + off.first
            val cz = boss.lair.z + off.second
            val n = Npc(id, Tile(cx - half, cz - half, boss.lair.height), world)
            n.respawns = false
            n.walkRadius = 5
            world.spawn(n)
            n.setActive(true)
        }
    }

    private fun registerBodyguardDef(guard: String) {
        if (!definedGuards.add(guard)) return // one def per npc id
        runCatching {
            setCombatDef(guard) {
                configs {
                    attackSpeed = 4
                    // 0 = no engine respawn: a killed bodyguard is removed and only returns when its
                    // general respawns (via the onNpcSpawn hook), never on its own faster timer.
                    respawnDelay = 0
                }
                aggro {
                    radius = 12
                    searchDelay = 1
                    // Bodyguards share the general's permanent aggression (see the general def above).
                    aggroTimer = Int.MAX_VALUE
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
     * Spawn [npcKey] so its **footprint** is centred on ([cx], [cz]).
     *
     * An npc's spawn tile is its **south-west corner**; the body extends north-east by its cache
     * tile size (mirrors the engine's `Pawn.getCentreTile`, `tile + size / 2`).
     * The [GodWarsBosses] lairs are the throne-room *centres*, so placing a size-4 general's base
     * directly on the centre tile shoves its 4×4 body 2 tiles north-east — into the room's wall
     * (General Graardor was spawning stuck in the Bandos wall this way). Offsetting the base
     * south-west by `size / 2` keeps the "lair = centre" contract for a body of any size, so the
     * fix scales to every general and bodyguard without per-npc tuning.
     */
    private fun spawnCentred(npcKey: String, cx: Int, cz: Int, height: Int, walkRadius: Int) {
        val size = runCatching { getNpc(getRSCM(npcKey)).size }.getOrNull()?.takeIf { it > 0 } ?: 1
        val half = size shr 1
        spawnNpc(npcKey, cx - half, cz - half, height, walkRadius = walkRadius)
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
        // 150 ticks = 90s, matching OSRS and the client's top-left Boss-timer estimate for the GWD
        // generals (RuneLite bosstimer/Boss.java). The bodyguards inherit this cadence because they
        // respawn with the general rather than on their own timer.
        const val RESPAWN_DELAY = 150
        const val FALLBACK_DEATH = 836 // generic death animation
        val BODYGUARD_OFFSETS = listOf(2 to 2, -2 to 2, 2 to -2)
        // Radius around the lair within which stale bodyguards are cleared before a fresh trio spawns.
        const val GUARD_RESET_RADIUS = 12
    }
}

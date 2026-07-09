package org.alter.plugins.content.war.boss

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.NpcSkills
import org.alter.api.ext.message
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Player
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.war.Cities
import org.alter.plugins.content.war.WarNpcNames
import org.alter.plugins.content.war.title
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/** A live boss the server is tracking, with who (if anyone) summoned it. */
class ActiveRaid(
    val npc: Npc,
    val def: BossDef,
    val cityId: Int,
    val sponsor: Player?,
    val isSummon: Boolean,
    val spawnedAt: Int,
)

/**
 * Drives **city bosses**: a rotating passive spawn (announced server-wide) plus the spawn
 * helper the on-demand [BossSummon] reuses, so passive and summoned bosses are identical
 * npcs handled by one death funnel.
 *
 * Single-world singleton; all state is in-memory (open-world ephemeral content — a boss
 * doesn't survive a restart). Ticked from [org.alter.plugins.content.war.WorldBossPlugin]'s
 * world timer; [ticks] counts those upkeep cycles (~3s each).
 */
object BossScheduler {
    /** Keyed by npc index so the death funnel can map a corpse back to its raid. */
    private val activeBosses = HashMap<Int, ActiveRaid>()
    private var ticks = 0
    private var rotation = 0
    /** No passive spawn until this upkeep tick. Starts at [INITIAL_PASSIVE_DELAY] so a fresh boot
     *  leaves cities EMPTY for a while — a Lord can summon into a free city, and passive content
     *  only fills in if no one does. */
    private var nextPassiveSpawnAt = INITIAL_PASSIVE_DELAY

    /** Upkeep cycles (~3s each). */
    private const val INITIAL_PASSIVE_DELAY = 100 // ~5 min after boot before the first passive boss
    private const val SPAWN_COOLDOWN = 100        // ~5 min after a kill before the next passive boss
    private const val GRACE = 200                 // ~10 min: an untouched boss withdraws (frees the slot)

    /** One upkeep cycle: reap dead/stale bosses, then passively spawn if a city is free. */
    fun tick(world: World) {
        try {
            ticks++
            reap(world)
            passiveSpawn(world)
        } catch (e: Throwable) {
            // Throwable, not Exception — never let boss upkeep kill the game loop.
            logger.error(e) { "BossScheduler tick failed (skipped)" }
        }
    }

    /**
     * Drop entries whose npc is gone, and withdraw bosses left unchallenged past [GRACE].
     * A *kill* is owned by [onBossDeath] (the death funnel), so we only remove here when the
     * npc has fully left the world — never on `isDead()` alone, which could race the death
     * handler and lose the loot.
     */
    private fun reap(world: World) {
        val it = activeBosses.iterator()
        while (it.hasNext()) {
            val raid = it.next().value
            val npc = raid.npc
            if (npc.index < 0 || !world.npcs.contains(npc)) { it.remove(); continue }
            // Unchallenged withdrawal: nobody has dealt damage and the grace window elapsed.
            if (ticks - raid.spawnedAt >= GRACE && npc.damageMap.playerDamage().isEmpty()) {
                if (raid.isSummon) {
                    raid.sponsor?.takeIf { it.index >= 0 }
                        ?.message("<col=801700>Your summoned ${raid.def.displayName} went unchallenged and withdrew.</col>")
                }
                world.remove(npc)
                it.remove()
            }
        }
    }

    /** If a rotation city has no active boss and the cooldown elapsed, spawn the next boss. */
    private fun passiveSpawn(world: World) {
        if (ticks < nextPassiveSpawnAt) return
        val roster = BossRegistry.all
        if (roster.isEmpty()) return
        // Walk the rotation once looking for a (boss, city) whose city is currently free.
        for (n in roster.indices) {
            val def = roster[(rotation + n) % roster.size]
            val city = def.cities.firstOrNull { cityId -> def.tileIn(cityId) != null && !cityHasActiveBoss(cityId) }
                ?: continue
            rotation = (rotation + n + 1) % roster.size
            spawnBoss(world, def, city, sponsor = null, isSummon = false)
            return
        }
    }

    /** True if [cityId] already has a tracked boss alive (passive or summoned). */
    fun cityHasActiveBoss(cityId: Int): Boolean = activeBosses.values.any { it.cityId == cityId }

    /** HP-depleted % (0-100) of the nearest living boss within [radius] of [tile], or null if none.
     *  Drives the client boss-raid progress bar. */
    fun bossProgressNear(tile: Tile, radius: Int): Int? {
        var best: Int? = null
        var bestDist = Int.MAX_VALUE
        for (raid in activeBosses.values) {
            val npc = raid.npc
            if (npc.index < 0 || npc.isDead()) continue
            val d = npc.tile.getChebyshevDistance(tile)
            if (d <= radius && d < bestDist) {
                val max = npc.getMaxHp().coerceAtLeast(1)
                val cur = npc.getCurrentHp().coerceIn(0, max)
                bestDist = d
                best = ((max - cur) * 100 / max).coerceIn(0, 100)
            }
        }
        return best
    }

    /** Living boss npcs currently in [cityId] (what an allied raid party converges on). */
    fun bossesIn(cityId: Int): List<Npc> =
        activeBosses.values
            .filter { it.cityId == cityId && it.npc.index >= 0 && !it.npc.isDead() }
            .map { it.npc }

    /**
     * Spawn [def] in [cityId], apply its stat/level/name overrides, register it as an
     * active raid and announce it. Returns the npc (null if the boss has no tile there).
     * Shared by the passive rotation and [BossSummon].
     */
    fun spawnBoss(world: World, def: BossDef, cityId: Int, sponsor: Player?, isSummon: Boolean): Npc? {
        val tile = def.tileIn(cityId) ?: return null
        val spawn = world.findRandomTileAround(tile, radius = 2) ?: tile
        val npc = Npc(getRSCM(def.npcName), spawn, world)
        npc.walkRadius = 6
        world.spawn(npc)
        WarNpcNames.rename(npc, def.displayName)
        def.combatLevel?.let { npc.overrideLevel(it) }
        npc.respawns = false
        npc.setActive(true)
        applyStats(npc, def)
        activeBosses[npc.index] = ActiveRaid(npc, def, cityId, sponsor, isSummon, ticks)

        val city = Cities.byId(cityId)?.displayName ?: "the realm"
        logger.info { "Boss '${def.key}' (${if (isSummon) "summoned by ${sponsor?.username}" else "passive"}) spawned in $city at $spawn (npc index=${npc.index})." }
        if (isSummon && sponsor != null) {
            announce(world, "<col=ffae00>${sponsor.username}, ${sponsor.title.display}, has summoned a ${def.displayName} to $city!</col>")
        } else {
            announce(world, "<col=ffae00>A ${def.displayName} has appeared in $city! Rally and slay it for a share of the spoils.</col>")
        }
        return npc
    }

    /** Boss death entry point (called from the plugin's onNpcDeath). No-op for untracked npcs. */
    fun onBossDeath(world: World, npc: Npc) {
        val raid = activeBosses.remove(npc.index) ?: return
        BossLoot.award(world, npc, raid)
        nextPassiveSpawnAt = ticks + SPAWN_COOLDOWN
    }

    /** Push the boss's combat levels into `npc.stats` (the damage formula's real source) +
     *  its live combatDef, mirroring HostileZone.enlist. Never mutates the global def. */
    private fun applyStats(npc: Npc, def: BossDef) {
        val d = npc.combatDef
        val atk = (def.atk ?: d.attack).coerceAtLeast(1)
        val str = (def.str ?: d.strength).coerceAtLeast(1)
        val dfc = (def.def ?: d.defence).coerceAtLeast(1)
        val hp = (def.hp ?: d.hitpoints).coerceAtLeast(1)
        npc.stats.setMaxLevel(NpcSkills.ATTACK, atk); npc.stats.setCurrentLevel(NpcSkills.ATTACK, atk)
        npc.stats.setMaxLevel(NpcSkills.STRENGTH, str); npc.stats.setCurrentLevel(NpcSkills.STRENGTH, str)
        npc.stats.setMaxLevel(NpcSkills.DEFENCE, dfc); npc.stats.setCurrentLevel(NpcSkills.DEFENCE, dfc)
        npc.setCurrentHp(hp)
        npc.combatDef = d.copy(attack = atk, strength = str, defence = dfc, hitpoints = hp)
    }

    // Boss spawns are headlines → route through the announcement ticker (BROADCAST channel).
    private fun announce(world: World, msg: String) = Announce.broadcast(world, msg)
}

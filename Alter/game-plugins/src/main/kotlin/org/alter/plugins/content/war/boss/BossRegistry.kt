package org.alter.plugins.content.war.boss

import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropTable
import org.alter.rscm.RSCM.getRSCM

/**
 * One city boss. A boss spawns in a city (passively on a rotation, or summoned by a
 * Lord+ — see [BossScheduler]/[BossSummon]); players travel there, fight it, and split
 * its loot by damage contribution ([BossLoot]).
 *
 * Loot is kept as the proven, low-risk shape — a coin range plus a
 * curated rare table of rscm keys, resolved lazily and guarded so an id not yet in the
 * cache is skipped rather than crashing a kill. (We deliberately avoid the loot-table DSL
 * here: its builder is wired to push into an `NpcCombatBuilder`, which is awkward for a
 * stand-alone contribution split.)
 *
 * @param npcName stock RSCM npc the boss is repurposed from (must be player-ATTACKABLE).
 * @param atk/str/def/hp combat overrides pushed onto the live npc at spawn (null = cache).
 * @param combatLevel client-displayed level override (null = cache level).
 * @param cities ids the boss may rotate into; [spawnTiles] gives its open-world tile per city.
 * @param summonCost coins a sponsor pays to summon this boss on demand.
 * @param titheCut fraction of the coin pool skimmed to the sponsor (0.0..1.0).
 * @param bonusRollOneIn sponsor's extra rare roll is 1-in-N (0 = no bonus roll).
 * @param prestigeAward prestige points the sponsor earns when their summoned boss dies.
 * @param dropTable the tiered, HIGH-VALUE loot every contributor rolls (always supplies + one
 *        weighted gear/resource pick + independent rare chase rolls). City/war bosses are rare,
 *        gated events, so each kill is a real payday — the value sits in items, not minted coins.
 *        The MVP rolls the table TWICE. See [org.alter.plugins.content.bosses.DropTable].
 * @param uniqueTable the mega-rares (sigils/pet/visage-tier). Each rolls INDEPENDENTLY per
 *        eligible contributor at true 1-in-N odds — NOT guaranteed. This is where an "avatar"
 *        drop becomes a server event. See [BossUnique].
 */
data class BossDef(
    val key: String,
    val npcName: String,
    val displayName: String,
    val tier: Int,
    val cities: List<Int>,
    val spawnTiles: Map<Int, Tile>,
    val atk: Int? = null,
    val str: Int? = null,
    val def: Int? = null,
    val hp: Int? = null,
    val combatLevel: Int? = null,
    val coinDrop: IntRange,
    val dropTable: DropTable,
    val summonCost: Int,
    val titheCut: Double = 0.10,
    val bonusRollOneIn: Int = 3,
    val prestigeAward: Int = 25,
    val bossPointsPerKill: Int = 10,
    val uniqueTable: List<BossUnique> = emptyList(),
    /** The world-boss (event) spawn rolls its [uniqueTable] at this multiple of the base odds —
     *  e.g. 2 = twice as likely — to make rallying the public event worthwhile. The always-on
     *  lair version (if the boss has one) keeps base odds. Applied in [BossLoot]. */
    val eventUniqueMultiplier: Int = 1,
) {
    /** This boss's spawn tile in [cityId], if it rotates there. */
    fun tileIn(cityId: Int): Tile? = spawnTiles[cityId]
}

/**
 * A mega-rare drop rolled INDEPENDENTLY per eligible contributor (see [BossLoot]). Each
 * roll is a true 1-in-[oneInN] chance — so multiple sigils never share one slot the way
 * [BossDef.rareTable] entries do. Server-broadcast + collection-logged when it lands.
 *
 * @param item rscm item key (skipped safely if not yet in the cache).
 * @param oneInN drop chance is 1-in-N per eligible contributor, per kill.
 * @param announce broadcast the drop to the whole server (the "News:" headline).
 */
data class BossUnique(
    val item: String,
    val oneInN: Int,
    val announce: Boolean = true,
)

/**
 * The boss roster. **Adding a boss = adding a [BossDef] here.** For v1 only Lumbridge
 * (city id 1) exists, so every boss lists a Lumbridge spawn tile.
 *
 * The roster is currently EMPTY. The Corporeal Beast used to be the Lumbridge event boss
 * (spawned on the open ground NE of the city at 3247,3319), but it now lives only at its
 * real lair: the cache spawns in `npc_spawns.json` (2993,4382,2 / 2993,4254,2) with its
 * full stats and OSRS drop table from `npc_combat.json` / `npc_drops.json`. Registering it
 * here again would re-bind a death handler on `npc.corporeal_beast`, which makes the
 * world-spawn loader prune those lair spawns (see `WorldSpawnsPlugin.finalizeSpawnData`).
 *
 * Every consumer ([BossScheduler], [BossSummon], `WorldBossPlugin`) tolerates an empty
 * roster: no passive rotation, `::summonboss` says nothing can be summoned, and
 * `::worldboss` still teleports to the (empty) arena. Add the next event boss as a
 * [BossDef] below — nothing else needs touching.
 */
object BossRegistry {
    val all: List<BossDef> = emptyList()

    fun byKey(key: String): BossDef? = all.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /** The boss def whose npc resolves to [npcId], or null (guards unknown rscm keys). */
    fun byNpcId(npcId: Int): BossDef? =
        all.firstOrNull { runCatching { getRSCM(it.npcName) }.getOrNull() == npcId }

    /** Distinct stock npc keys to bind death handlers for. */
    fun npcNames(): List<String> = all.map { it.npcName }.distinct()
}

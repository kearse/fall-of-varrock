package org.alter.plugins.content.war.boss

import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.war.Cities
import org.alter.rscm.RSCM.getRSCM

/**
 * One city boss. A boss spawns in a city (passively on a rotation, or summoned by a
 * Lord+ — see [BossScheduler]/[BossSummon]); players travel there, fight it, and split
 * its loot by damage contribution ([BossLoot]).
 *
 * Loot is kept as the proven, low-risk `RaidRewards`-style shape — a coin range plus a
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
 * v1 ships a single APEX world boss: the **Corporeal Beast** — a genuine cache boss model
 * (npc 319), one of the largest and most menacing in the game, and the canonical source of
 * "avatar"-tier mega-rares (the spirit-shield sigils + its pet). Tuned hard-but-soloable so
 * a strong solo can fell it, with the sigils gated behind the independent [uniqueTable].
 * Lighter warm-up bosses can be added back here later without touching any other code.
 */
object BossRegistry {
    private const val LUM = Cities.DEFAULT_CITY_ID // 1

    val all: List<BossDef> = listOf(
        BossDef(
            key = "corporeal_beast",
            npcName = "npc.corporeal_beast",
            displayName = "Corporeal Beast",
            tier = 3,
            cities = listOf(LUM),
            // Open ground NE of Lumbridge by the river — outside the safe keep. TUNE.
            // The Corp Beast is a 5x5 model, so give it clear ground.
            spawnTiles = mapOf(LUM to Tile(3247, 3319, 0)),
            // Hard-but-soloable: ~1200 HP, tough stats a strong solo in good gear/prayer can beat.
            atk = 180, str = 200, def = 160, hp = 1_200,
            combatLevel = 785, // its real level — looks terrifying on the client
            coinDrop = 20_000..45_000,
            // HIGH-VALUE tiered table — each contributor rolls it (MVP twice). Value lives in
            // gear/resources/chase items (tradeable, sinkable) rather than minted coins, so an
            // apex kill is a real payday without flooding gp. TUNE freely.
            dropTable = DropTable(
                // Every kill: a chunk of PK supplies + a resource bundle (always useful).
                always = listOf(
                    DropEntry("item.blood_rune", 80, 160),
                    DropEntry("item.death_rune", 80, 160),
                    DropEntry("item.super_restore4", 2, 4),
                    DropEntry("item.shark", 5, 10),
                ),
                // One weighted pick: solid rune gear + resources common, dragon-tier the uncommon reward.
                main = listOf(
                    DropEntry("item.rune_platebody", weight = 28),
                    DropEntry("item.rune_platelegs", weight = 28),
                    DropEntry("item.rune_kiteshield", weight = 24),
                    DropEntry("item.rune_scimitar", weight = 24),
                    DropEntry("item.runite_ore", 2, 4, weight = 22),
                    DropEntry("item.dragon_bones", 10, 20, weight = 22),
                    DropEntry("item.magic_logs", 20, 40, weight = 18),
                    DropEntry("item.battlestaff", 5, 10, weight = 16),
                    DropEntry("item.dragon_dagger", weight = 10),
                    DropEntry("item.dragon_med_helm", weight = 10),
                    DropEntry("item.dragon_longsword", weight = 8),
                    DropEntry("item.dragon_mace", weight = 8),
                    DropEntry("item.dragon_platelegs", weight = 4),
                    DropEntry("item.dragon_chainbody", weight = 3),
                ),
                // Independent chase rolls — the headline "nice drop" pieces (avatar-tier = uniqueTable).
                rare = listOf(
                    DropEntry("item.dragon_boots", oneInN = 25),
                    DropEntry("item.dragon_2h_sword", oneInN = 45),
                    DropEntry("item.dragon_full_helm", oneInN = 70, announce = true, log = true),
                    DropEntry("item.dragon_pickaxe", oneInN = 90, announce = true, log = true),
                ),
            ),
            summonCost = 3_000_000,
            titheCut = 0.10,
            prestigeAward = 40,
            bossPointsPerKill = 25,
            // Event bonus: the Lumbridge event spawn drops its avatar-tier uniques at 2× the base
            // (lair) odds — the payoff for rallying the public boss over farming the cave solo.
            eventUniqueMultiplier = 2,
            // The avatar-tier mega-rares — true independent rolls, server-announced + logged.
            uniqueTable = listOf(
                BossUnique("item.arcane_sigil", oneInN = 150),
                BossUnique("item.spectral_sigil", oneInN = 150),
                BossUnique("item.elysian_sigil", oneInN = 150),
                BossUnique("item.blessed_spirit_shield", oneInN = 120),
                BossUnique("item.draconic_visage", oneInN = 200),
                BossUnique("item.pet_corporeal_critter", oneInN = 700),
            ),
        ),
    )

    fun byKey(key: String): BossDef? = all.firstOrNull { it.key.equals(key, ignoreCase = true) }

    /** The boss def whose npc resolves to [npcId], or null (guards unknown rscm keys). */
    fun byNpcId(npcId: Int): BossDef? =
        all.firstOrNull { runCatching { getRSCM(it.npcName) }.getOrNull() == npcId }

    /** Distinct stock npc keys to bind death handlers for. */
    fun npcNames(): List<String> = all.map { it.npcName }.distinct()
}

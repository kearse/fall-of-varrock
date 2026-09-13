package org.alter.plugins.content.bots

import org.alter.api.ext.message
import org.alter.game.model.World
import org.alter.game.model.attr.KNIGHT_KEY_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.plugins.content.announce.Announce
import org.alter.plugins.content.bosses.CollectionLog
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.bots.knights.RogueKnights
import org.alter.rscm.RSCM.getRSCM

/**
 * **PK-set loot pools** — the ONLY items a slain Rogue Knight yields. The bot's worn kit never
 * drops (2026-09-12: full-kit drops flooded the gear economy); a kill pays a Blood Money bounty
 * ([RogueBounty]) and rolls these pools, nothing else.
 *
 * Each pool is built around a real PK build archetype (pure kit → zerker kit → hybrid → maxer →
 * NH tribrid → the Ancient Warrior wilderness sets → revenant weapons), so grinding a camp
 * assembles a complete, recognisable PKing set — the chase escalates to dragon claws / AGS /
 * voidwaker-class uniques at the top. Ambient bots roll the pool for their loadout's tier;
 * NAMED rogue knights (bots/knights/) roll their own def's table at much better odds instead.
 *
 * Every entry lives in the [DropTable] RARE tier (independent 1-in-N rolls) — never the main
 * tier, which would guarantee an item every kill and turn the pools back into a gear farm; most
 * kills pay only the bounty. Rolled drops go back to [BotCombatPlugin] (`rewardKiller`), which
 * seals them into the killer's loot key for ANY real-player kill — wilderness or safe camp — and
 * ground-drops them killer-owned only when no key can be minted.
 *
 * **No untradeables (community suggestion, 2026-09-13).** Every entry here is a TRADEABLE item.
 * A bot that drops account-bound gear hands out, at random and in bulk, the exact rewards other
 * content exists to sell — and the holder can never pass it on, so the supply only ever grows.
 * Removed on 2026-09-13 and what replaced each:
 *  - **void** (5 pieces, HYBRID) — it is a Pest Control reward; that shelf is the source
 *    (`minigames/pestcontrol`). Replaced by the remaining tradeable Barrows shells.
 *  - **rune / dragon defender, fighter torso, barrows gloves** — no minigame here sells them, so
 *    they moved to the PK Rewards shop's **Untradeables** wing (`economy/pk/PkRewardsPlugin`):
 *    bought at a known Blood Money price instead of farmed at random off a bot.
 *  - **avernic defender** → its **hilt**, which IS tradeable and is what the shop's dragon
 *    defender is for (hilt + defender = avernic, the OSRS recipe).
 *  - **imbued rings** (berserker/archer/seers (i)) — the plain rings already roll in the budget
 *    pool; the imbue is a straight upgrade, not a set hole. Replaced by the ring of suffering and
 *    the amulet of blood fury.
 * Keep it that way: if a new entry is untradeable, it belongs on a shelf, not in this file.
 *
 * RATES ARE LAUNCH VALUES — TUNE. Anchored against the Blood Money shop (AGS 45k / claws 36k /
 * VLS 75k BM after the 2026-09-13 ×3 reprice) so drops complement the BM sink rather than undercut
 * it, and kept modest so the named knights stay the efficient chase over ambient farming. (They
 * were tuned as a bonus on top of the old kit drop and are now the whole item side of the reward —
 * revisit with kill data.)
 */
object PkLootPools {

    // ---- the archetype pools (rare-tier only: every line is an independent 1/N roll) ----

    /** Metal-tier fodder (wild 1–10 + the Bandit Hideout): starter upgrade trickle. */
    private val STARTER = DropTable(
        rare = listOf(
            DropEntry("item.adamant_scimitar", oneInN = 20),
            DropEntry("item.rune_scimitar", oneInN = 40),
            DropEntry("item.amulet_of_strength", oneInN = 25),
            DropEntry("item.climbing_boots", oneInN = 25),
            DropEntry("item.dragon_dagger", oneInN = 60),
            DropEntry("item.dragon_scimitar", oneInN = 150),
        ),
    )

    /** Budget tier (wild 11–20): the PURE + ZERKER kit chase. */
    private val PURE_ZERKER_KIT = DropTable(
        rare = listOf(
            // pure kit
            DropEntry("item.rune_scimitar", oneInN = 25),
            DropEntry("item.magic_shortbow", oneInN = 25),
            DropEntry("item.black_dhide_body", oneInN = 30),
            DropEntry("item.black_dhide_chaps", oneInN = 30),
            DropEntry("item.mystic_robe_top", oneInN = 35),
            DropEntry("item.mystic_robe_bottom", oneInN = 35),
            DropEntry("item.ancient_staff", oneInN = 40),
            DropEntry("item.amulet_of_glory", oneInN = 30),
            DropEntry("item.dragon_dagger", oneInN = 30),
            DropEntry("item.granite_maul", oneInN = 50),
            // zerker kit
            DropEntry("item.berserker_helm", oneInN = 40),
            DropEntry("item.obsidian_cape", oneInN = 40),
            DropEntry("item.dragon_boots", oneInN = 60),
            DropEntry("item.warrior_ring", oneInN = 60),
            DropEntry("item.berserker_ring", oneInN = 80),
            DropEntry("item.archers_ring", oneInN = 80),
            // gimmick chase
            DropEntry("item.tzhaarketom", oneInN = 50),
            DropEntry("item.berserker_necklace", oneInN = 50),
            DropEntry("item.dragon_scimitar", oneInN = 40),
        ),
    )

    /** Mid tier (wild 21–30 + Fallen Varrock): the HYBRID kit — whip-and-neitiznot country. */
    private val HYBRID_KIT = DropTable(
        rare = listOf(
            DropEntry("item.helm_of_neitiznot", oneInN = 30),
            DropEntry("item.amulet_of_fury", oneInN = 40),
            DropEntry("item.karils_leathertop", oneInN = 40),
            DropEntry("item.karils_leatherskirt", oneInN = 40),
            DropEntry("item.dragon_boots", oneInN = 35),
            DropEntry("item.karils_coif", oneInN = 40),
            DropEntry("item.ahrims_robetop", oneInN = 45),
            DropEntry("item.ahrims_robeskirt", oneInN = 45),
            DropEntry("item.dharoks_helm", oneInN = 45),
            DropEntry("item.dharoks_platebody", oneInN = 45),
            DropEntry("item.dharoks_platelegs", oneInN = 45),
            DropEntry("item.occult_necklace", oneInN = 50),
            DropEntry("item.infinity_boots", oneInN = 50),
            DropEntry("item.rune_crossbow", oneInN = 35),
            DropEntry("item.seers_ring", oneInN = 60),
            DropEntry("item.abyssal_whip", oneInN = 80, announce = true),
            DropEntry("item.dharoks_greataxe", oneInN = 80),
            DropEntry("item.staff_of_the_dead", oneInN = 150, announce = true),
            DropEntry("item.dark_bow", oneInN = 100),
            DropEntry("item.serpentine_helm", oneInN = 150),
            DropEntry("item.dragon_crossbow", oneInN = 150),
            // (Void used to roll here. It's a Pest Control reward — see minigames/pestcontrol —
            // and untradeables no longer drop from bots at all; see the class doc.)
            DropEntry("item.torags_platelegs", oneInN = 45),
            DropEntry("item.veracs_plateskirt", oneInN = 45),
            DropEntry("item.ahrims_staff", oneInN = 60),
        ),
    )

    /** High tier (wild 31–40 + the Wild Bandit Camp): the MAXER kit + first spec-weapon uniques. */
    private val MAXER_KIT = DropTable(
        rare = listOf(
            DropEntry("item.abyssal_whip", oneInN = 40),
            DropEntry("item.abyssal_tentacle", oneInN = 90, announce = true),
            DropEntry("item.neitiznot_faceguard", oneInN = 80),
            DropEntry("item.amulet_of_torture", oneInN = 70),
            DropEntry("item.necklace_of_anguish", oneInN = 70),
            DropEntry("item.ferocious_gloves", oneInN = 90),
            DropEntry("item.primordial_boots", oneInN = 90),
            DropEntry("item.pegasian_boots", oneInN = 90),
            DropEntry("item.eternal_boots", oneInN = 90),
            DropEntry("item.ring_of_suffering", oneInN = 70),
            DropEntry("item.amulet_of_blood_fury", oneInN = 90),
            DropEntry("item.bandos_chestplate", oneInN = 110, announce = true),
            DropEntry("item.bandos_tassets", oneInN = 110, announce = true),
            DropEntry("item.dragon_knife", min = 25, max = 75, oneInN = 40),
            DropEntry("item.heavy_ballista", oneInN = 90),
            DropEntry("item.dragon_javelin", min = 25, max = 75, oneInN = 40),
            DropEntry("item.dragon_warhammer", oneInN = 120, announce = true),
            DropEntry("item.dragon_claws", oneInN = 150, announce = true, log = true),
            DropEntry("item.elder_maul", oneInN = 150, announce = true),
        ),
    )

    /** Elite tier (wild 41+ + the Redoubt): the NH TRIBRID kit — the end-game PK chase. */
    private val NH_ELITE_KIT = DropTable(
        rare = listOf(
            DropEntry("item.ancestral_hat", oneInN = 90, announce = true),
            DropEntry("item.ancestral_robe_top", oneInN = 110, announce = true),
            DropEntry("item.ancestral_robe_bottom", oneInN = 110, announce = true),
            DropEntry("item.kodai_wand", oneInN = 130, announce = true),
            DropEntry("item.masori_mask_f", oneInN = 110, announce = true),
            DropEntry("item.masori_body_f", oneInN = 130, announce = true),
            DropEntry("item.masori_chaps_f", oneInN = 130, announce = true),
            DropEntry("item.elidinis_ward_f", oneInN = 130, announce = true),
            DropEntry("item.avernic_defender_hilt", oneInN = 110, announce = true),
            DropEntry("item.tormented_bracelet", oneInN = 90),
            DropEntry("item.occult_necklace", oneInN = 40),
            DropEntry("item.dragon_arrow", min = 50, max = 150, oneInN = 25),
            DropEntry("item.granite_maul", oneInN = 30),
            DropEntry("item.armadyl_godsword", oneInN = 100, announce = true, log = true),
            DropEntry("item.ancient_godsword", oneInN = 150, announce = true, log = true),
            DropEntry("item.burning_claws", oneInN = 120, announce = true),
            DropEntry("item.dragon_claws", oneInN = 120, announce = true, log = true),
            DropEntry("item.voidwaker", oneInN = 500, announce = true, log = true),
        ),
    )

    /** Revenant-weapon trickle, mixed into the two deep-wild pools' rolls via [tierTables]. */
    private val REV_CACHE = DropTable(
        rare = listOf(
            DropEntry("item.craws_bow", oneInN = 120, announce = true),
            DropEntry("item.viggoras_chainmace", oneInN = 120, announce = true),
            DropEntry("item.thammarons_sceptre", oneInN = 120, announce = true),
            DropEntry("item.amulet_of_avarice", oneInN = 90),
            DropEntry("item.webweaver_bow", oneInN = 400, announce = true, log = true),
            DropEntry("item.ursine_chainmace", oneInN = 400, announce = true, log = true),
            DropEntry("item.accursed_sceptre", oneInN = 400, announce = true, log = true),
        ),
    )

    /** Loadout tier → the pool(s) an ambient bot of that tier rolls on death. */
    private val TIER_TABLES: Map<String, List<DropTable>> = mapOf(
        "metal" to listOf(STARTER),
        "budget" to listOf(PURE_ZERKER_KIT),
        "mid" to listOf(HYBRID_KIT),
        "high" to listOf(MAXER_KIT, REV_CACHE),
        "elite" to listOf(NH_ELITE_KIT, REV_CACHE),
    )

    /**
     * Roll the PK-set drops for a slain bot. Returns resolved [Item]s for the caller to deliver
     * (`BotCombatPlugin.rewardKiller`: loot key, else killer-owned ground drop). Empty when there's
     * no real killer — bot-on-bot and environmental deaths never mint loot.
     *
     * A NAMED rogue knight ([KNIGHT_KEY_ATTR]) rolls its ladder def's table (signature rares at
     * far better odds); ambient bots roll their loadout tier's pool(s). Announce/collection-log
     * flags are handled here, mirroring the `BossLoot.grantDrop` pattern.
     */
    fun bonusDrops(world: World, bot: PkBot, killer: Player?): List<Item> {
        if (killer == null || killer is PkBot || killer.index < 0) return emptyList()
        val tables = bot.attr[KNIGHT_KEY_ATTR]?.let { key ->
            RogueKnights.byKey(key)?.rareTable?.let { listOf(it) }
        } ?: TIER_TABLES[bot.loadout.tier] ?: return emptyList()

        val out = ArrayList<Item>()
        for (table in tables) {
            for (drop in table.roll(world)) {
                val id = runCatching { getRSCM(drop.item) }.getOrNull() ?: continue // unknown key — skip
                out += Item(id, drop.amount)
                if (drop.log && CollectionLog.record(killer, id)) {
                    killer.message("<col=ffae00>New Collection Log slot: ${pretty(drop.item)}!</col>")
                }
                if (drop.announce) {
                    Announce.broadcast(
                        world,
                        "<col=ff0000>News: ${killer.username} just looted a <col=ffae00>${pretty(drop.item)}</col> from a Rogue Knight!</col>",
                    )
                }
            }
        }
        return out
    }

    /** "item.armadyl_godsword" → "Armadyl godsword" (display-only). */
    private fun pretty(key: String): String =
        key.removePrefix("item.").replace('_', ' ').replaceFirstChar { it.uppercase() }
}

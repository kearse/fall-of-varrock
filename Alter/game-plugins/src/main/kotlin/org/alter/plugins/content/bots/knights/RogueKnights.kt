package org.alter.plugins.content.bots.knights

import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.plugins.content.bots.BotItem

/**
 * **The Rogue Knight ladder** — the data registry behind the organized rogue-knight assignment
 * track (the "keep giving you harder and harder knights" loop). Every entry is a NAMED boss-tier
 * PKer bot stationed at one of the organized camps; the Recruiting Sergeant assigns them in rank
 * order, weakest → strongest. Killing your assigned knight:
 *   - advances your rank ([RogueKnightLadder]),
 *   - pays that knight's **first-kill unlock** — deliberately the CORE of the next build up the
 *     ladder, so beating a knight literally gears you for the next one,
 *   - and every kill (first or farmed) rolls the knight's **signature rare table** on top of its
 *     full worn-kit drop.
 *
 * Camps escalate: the SAFE Bandit Hideout (reclaimable deaths — learn cheaply) → the SAFE Siege of
 * Port Sarim (the rogues raid Lumbridge's lifeline port — `areas/portsarim/PortSiegePlugin` runs
 * the battle scene; lose the port and the realm is cut off) → shallow-wild Fallen Varrock → the
 * deep-wild camps where dying costs you everything you carry. Top knights are TUNED TO BE
 * GENUINELY HARD (hp overrides + near-perfect prayer reactions + fast specs) — several attempts is
 * the intended experience.
 *
 * **Scaling:** adding knight #15+ is ONE list entry (keep ranks contiguous). Everything else —
 * spawning, tracking arrows, dialogue, drops — is data-driven off this list.
 * All numbers (hp, reactions, rates, tiles) are TUNE.
 */

/** One of the organized camps a knight is stationed at. [safe] camps sit on reclaim-death ground. */
data class KnightCamp(
    val key: String,
    val display: String,
    /** Where the tracking arrow points while the knight isn't spawned/in scene, and the anchor
     *  the camp engine spawns instances around. Must be open, walkable ground. */
    val center: Tile,
    val safe: Boolean,
    /** One-line directions the Sergeant (and ::knights) gives for finding the camp. */
    val directions: String,
)

data class RogueKnightDef(
    /** Ladder position, 0-based; MUST equal this def's index in [RogueKnights.LADDER]. */
    val rank: Int,
    /** Stable key (attrs, zone keys, logs). */
    val key: String,
    /** Display name, shown over the bot's head (via TITLED_NAME_ATTR — the username stays
     *  "Rogue Knight" so kill-crediting and loot-key labels keep working). */
    val name: String,
    val camp: KnightCamp,
    /** Base [org.alter.plugins.content.bots.BotLoadout] key the knight fights in. */
    val loadoutKey: String,
    /** Boss max-HP override (null = the loadout's normal pool). Applied via PkBot.maxHpOverride +
     *  setCurrentLevel — NEVER setBaseLevel above 99 (crashes the XP table). */
    val maxHp: Int? = null,
    /** Prayer-reaction override (ticks); 1..1 = near frame-perfect. Null = human default. */
    val reactionTicks: IntRange? = null,
    /** Spec-regen override (ticks per +10%); lower = more specs. Null = default. */
    val specRegen: Int? = null,
    /** Extra sustain appended to the loadout's inventory at spawn (top knights carry more brews). */
    val extraInventory: List<BotItem> = emptyList(),
    /** Paid ONCE, on the kill that advances the player past this knight: (RSCM key, amount).
     *  Inventory-first, overflow to bank — never ground loot (deep camps are PvP ground). */
    val firstKillRewards: List<Pair<String, Int>> = emptyList(),
    /** Signature rares rolled on EVERY kill of this knight (rare tier, independent 1/N). */
    val rareTable: DropTable? = null,
    /** The Sergeant's assignment brief for this knight. */
    val briefLine: String,
)

object RogueKnights {

    // ---- the camps (tiles TUNE — centers must be open ground; verify with ::zone) ----

    val BANDIT_HIDEOUT = KnightCamp(
        key = "bandit_hideout",
        display = "the Bandit Hideout",
        center = Tile(3110, 3230),
        safe = true,
        directions = "west out of Lumbridge, past the mill — the hideout squats on the road to Draynor.",
    )
    val PORT_SARIM = KnightCamp(
        key = "port_sarim",
        display = "Port Sarim",
        center = Tile(3041, 3202), // the docks — matches PortSiegePlugin.PORT_CENTRE (TUNE together)
        safe = true, // outside the red: reclaim deaths — the ladder's second learning ground
        directions = "the docks south-west of Lumbridge — our only port. The rogues raid it in waves to cut the realm off; help the dock knights hold it while you hunt.",
    )
    val FALLEN_VARROCK = KnightCamp(
        key = "fallen_varrock",
        display = "Fallen Varrock",
        center = Tile(3212, 3422),
        safe = false,
        directions = "the old city square, north through the wilderness line — single combat, but it IS the wild.",
    )
    val WILD_BANDIT_CAMP = KnightCamp(
        key = "wild_bandit_camp",
        display = "the Wild Bandit Camp",
        center = Tile(3037, 3690),
        safe = false,
        directions = "deep wilderness, far north-west — the bandits' tents. Bring nothing you won't risk.",
    )
    val ROGUE_REDOUBT = KnightCamp(
        key = "rogue_redoubt",
        display = "the Rogue Commander's Redoubt",
        center = Tile(3015, 3882),
        safe = false,
        directions = "the deepest wilderness, near the old mage arena — the Commander's elite hold the walls.",
    )

    val CAMPS = listOf(BANDIT_HIDEOUT, PORT_SARIM, FALLEN_VARROCK, WILD_BANDIT_CAMP, ROGUE_REDOUBT)

    // ---- the ladder (rank == index; ~14 now, add more freely — numbers/items TUNE) ----

    val LADDER: List<RogueKnightDef> = listOf(
        RogueKnightDef(
            rank = 0, key = "brack", name = "Sir Brack the Bronze",
            camp = BANDIT_HIDEOUT, loadoutKey = "iron_pker",
            firstKillRewards = listOf("item.coins_995" to 10_000, "item.steel_scimitar" to 1),
            briefLine = "Sir Brack the Bronze — a deserter playing knight at the Bandit Hideout. Cut him down and take his post apart.",
        ),
        RogueKnightDef(
            rank = 1, key = "oswin", name = "Sir Oswin the Steel",
            camp = BANDIT_HIDEOUT, loadoutKey = "steel_pker", maxHp = 40,
            firstKillRewards = listOf("item.black_scimitar" to 1, "item.amulet_of_strength" to 1),
            briefLine = "Sir Oswin the Steel runs the hideout now. Tougher than Brack — watch his dagger.",
        ),
        RogueKnightDef(
            rank = 2, key = "malrik", name = "Sir Malrik the Black",
            camp = PORT_SARIM, loadoutKey = "black_pker", maxHp = 60,
            firstKillRewards = listOf("item.mithril_scimitar" to 1, "item.climbing_boots" to 1, "item.coins_995" to 20_000),
            rareTable = DropTable(rare = listOf(DropEntry("item.rune_scimitar", oneInN = 8))),
            briefLine = "Sir Malrik the Black leads the raids on Port Sarim's docks. Lose that port and Lumbridge is cut off from the world — cut HIM down instead. Help the dock knights hold the line while you're there.",
        ),
        RogueKnightDef(
            rank = 3, key = "vora", name = "Dame Vora the Swift",
            camp = PORT_SARIM, loadoutKey = "budget_pure",
            firstKillRewards = listOf("item.rune_scimitar" to 1, "item.magic_shortbow" to 1, "item.amulet_of_glory" to 1),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.black_dhide_body", oneInN = 8),
                DropEntry("item.black_dhide_chaps", oneInN = 8),
            )),
            briefLine = "Dame Vora the Swift runs the port raids' skirmish line — a pure, all blade and no armour. She'll pray your style and out-damage you. Learn to switch.",
        ),
        RogueKnightDef(
            rank = 4, key = "grom", name = "Grom the Mauler",
            camp = PORT_SARIM, loadoutKey = "obby_mauler", maxHp = 90,
            firstKillRewards = listOf("item.obsidian_cape" to 1, "item.granite_maul" to 1),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.berserker_necklace", oneInN = 10),
                DropEntry("item.tzhaarketom", oneInN = 16),
            )),
            briefLine = "Grom the Mauler — no knight at all, just an obsidian maul and bad intentions, breaking heads on the port road. Don't stand still.",
        ),
        RogueKnightDef(
            rank = 5, key = "edran", name = "Sir Edran Ironclad",
            camp = PORT_SARIM, loadoutKey = "budget_zerker", maxHp = 100,
            firstKillRewards = listOf("item.berserker_helm" to 1, "item.rune_defender" to 1),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.fighter_torso", oneInN = 10),
                DropEntry("item.warrior_ring", oneInN = 16),
            )),
            briefLine = "Sir Edran Ironclad commands the port assault — a zerker of the old school: whip, torso, and a maul combo that ends fights. Break him and the raids on Port Sarim break with him.",
        ),
        RogueKnightDef(
            rank = 6, key = "caldus", name = "Sir Caldus the Red",
            camp = FALLEN_VARROCK, loadoutKey = "budget_main",
            firstKillRewards = listOf(
                "item.rune_full_helm" to 1, "item.rune_platebody" to 1, "item.rune_platelegs" to 1,
                "item.rune_kiteshield" to 1, "item.dragon_dagger" to 1,
            ),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.dragon_scimitar", oneInN = 12),
                DropEntry("item.dragon_defender", oneInN = 16),
            )),
            briefLine = "Sir Caldus the Red holds Varrock square — the true wilderness now. What you carry, you risk.",
        ),
        RogueKnightDef(
            rank = 7, key = "isolde", name = "Dame Isolde the Blade",
            camp = FALLEN_VARROCK, loadoutKey = "classic_hybrid", maxHp = 110,
            firstKillRewards = listOf("item.helm_of_neitiznot" to 1, "item.amulet_of_glory" to 1),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.abyssal_whip", oneInN = 16, announce = true),
                DropEntry("item.amulet_of_fury", oneInN = 20),
            )),
            briefLine = "Dame Isolde the Blade — a true hybrid, three books deep. She freezes, she switches, she finishes. So should you.",
        ),
        RogueKnightDef(
            rank = 8, key = "guthric", name = "Sir Guthric Longshot",
            camp = FALLEN_VARROCK, loadoutKey = "void_ranger",
            firstKillRewards = listOf(
                "item.rune_crossbow" to 1, "item.black_dhide_body" to 1, "item.black_dhide_chaps" to 1,
            ),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.void_knight_top", oneInN = 10),
                DropEntry("item.void_knight_robe", oneInN = 10),
                DropEntry("item.void_knight_gloves", oneInN = 10),
                DropEntry("item.void_ranger_helm", oneInN = 12),
                DropEntry("item.dark_bow", oneInN = 20),
            )),
            briefLine = "Sir Guthric Longshot, in stolen Void plate. He'll pick you apart at range — close the gap or pray it away.",
        ),
        RogueKnightDef(
            rank = 9, key = "morvane", name = "Sir Morvane the Grave",
            camp = WILD_BANDIT_CAMP, loadoutKey = "dharok_mid", maxHp = 120,
            firstKillRewards = listOf("item.granite_maul" to 1, "item.dragon_boots" to 1),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.dharoks_greataxe", oneInN = 20, announce = true),
                DropEntry("item.dharoks_helm", oneInN = 15),
                DropEntry("item.dharoks_platebody", oneInN = 15),
                DropEntry("item.dharoks_platelegs", oneInN = 15),
            )),
            briefLine = "Sir Morvane the Grave fights at death's door in full Dharok's — the lower he gets, the harder he hits. Never let him sit low.",
        ),
        RogueKnightDef(
            rank = 10, key = "halric", name = "Sir Halric Iron-Hand",
            camp = WILD_BANDIT_CAMP, loadoutKey = "statius_bruiser", maxHp = 130, reactionTicks = 1..2,
            firstKillRewards = listOf("item.barrows_gloves" to 1, "item.amulet_of_fury" to 1),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.statiuss_warhammer", oneInN = 15, announce = true),
                DropEntry("item.statiuss_full_helm", oneInN = 15),
                DropEntry("item.statiuss_platebody", oneInN = 15),
                DropEntry("item.statiuss_platelegs", oneInN = 15),
                DropEntry("item.dragon_warhammer", oneInN = 40, announce = true),
            )),
            briefLine = "Sir Halric Iron-Hand, in Statius's own plate. His warhammer caves defences in — yours included.",
        ),
        RogueKnightDef(
            rank = 11, key = "nyx", name = "Dame Nyx the Huntress",
            camp = WILD_BANDIT_CAMP, loadoutKey = "morrigan_skirmisher", maxHp = 130, reactionTicks = 1..2,
            firstKillRewards = listOf("item.heavy_ballista" to 1, "item.dragon_javelin" to 50),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.morrigans_coif", oneInN = 15, announce = true),
                DropEntry("item.morrigans_leather_body", oneInN = 15),
                DropEntry("item.morrigans_leather_chaps", oneInN = 15),
                DropEntry("item.dragon_claws", oneInN = 50, announce = true, log = true),
            )),
            briefLine = "Dame Nyx the Huntress, Morrigan's leathers and a ballista that hits like a siege engine. Pray missiles or die tired.",
        ),
        RogueKnightDef(
            rank = 12, key = "dathen", name = "Sir Dathen the Claw",
            camp = ROGUE_REDOUBT, loadoutKey = "vesta_duelist", maxHp = 140, reactionTicks = 1..2, specRegen = 4,
            extraInventory = listOf(BotItem("item.saradomin_brew4", 2)),
            firstKillRewards = listOf("item.dragon_defender" to 1, "item.berserker_ring_i" to 1),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.vestas_longsword", oneInN = 18, announce = true, log = true),
                DropEntry("item.vestas_chainbody", oneInN = 18, announce = true),
                DropEntry("item.vestas_plateskirt", oneInN = 18),
                DropEntry("item.dragon_claws", oneInN = 30, announce = true, log = true),
            )),
            briefLine = "Sir Dathen the Claw, Vesta's longsword in hand at the Redoubt walls. Most who challenge him feed the crows.",
        ),
        RogueKnightDef(
            rank = 13, key = "vexmar", name = "Lord Vexmar, Rogue Commander",
            camp = ROGUE_REDOUBT, loadoutKey = "elite_nh", maxHp = 160, reactionTicks = 1..1, specRegen = 3,
            extraInventory = listOf(BotItem("item.saradomin_brew4", 4), BotItem("item.super_restore4", 2)),
            firstKillRewards = listOf("item.coins_995" to 250_000, "item.bandos_tassets" to 1),
            rareTable = DropTable(rare = listOf(
                DropEntry("item.armadyl_godsword", oneInN = 25, announce = true, log = true),
                DropEntry("item.zuriels_staff", oneInN = 18, announce = true),
                DropEntry("item.zuriels_hood", oneInN = 18),
                DropEntry("item.zuriels_robe_top", oneInN = 18, announce = true),
                DropEntry("item.zuriels_robe_bottom", oneInN = 18),
                DropEntry("item.ancestral_hat", oneInN = 40, announce = true, log = true),
                DropEntry("item.elder_maul", oneInN = 40, announce = true),
            )),
            briefLine = "Lord Vexmar — the Rogue Commander himself. The full NH kit, frame-perfect prayers, and no mercy. Beating him makes you one of the deadliest blades in the realm.",
        ),
    )

    init {
        LADDER.forEachIndexed { i, def ->
            require(def.rank == i) { "Rogue Knight ladder ranks must be contiguous: '${def.key}' has rank ${def.rank} at index $i" }
        }
        require(LADDER.map { it.key }.toSet().size == LADDER.size) { "Rogue Knight ladder keys must be unique" }
    }

    fun byRank(rank: Int): RogueKnightDef? = LADDER.getOrNull(rank)

    fun byKey(key: String): RogueKnightDef? = LADDER.firstOrNull { it.key == key }
}

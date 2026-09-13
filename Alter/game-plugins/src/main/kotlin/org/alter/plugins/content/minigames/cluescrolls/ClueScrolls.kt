package org.alter.plugins.content.minigames.cluescrolls

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.message
import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Treasure Trails** — the minimal working clue loop (player report 2026-09-13: "clue scrolls and
 * rewards chest doesnt work").
 *
 * Before this there was no clue system at all. Scrolls dropped from roughly twenty curated boss
 * tables and were inert; the generic drop handler had already had to blanket-veto every clue and
 * casket id (`data/cfg/drops/config.yml`, "no Treasure Trails system exists") after the osrsbox
 * import handed out elite caskets at 100% on 27 monsters. So a player's only experience of clues
 * was an item they could not use and a chest they could not open.
 *
 * **What this ships.** One trail at a time, per account:
 *  1. **Read** a scroll — it is consumed and a trail of [Tier.steps] dig sites is rolled from that
 *     tier's [Tier.pool].
 *  2. **Dig** at each site with a spade. Reading the scroll again re-states the current step, so a
 *     clue is never lost.
 *  3. The last dig yields that tier's **reward casket**, which opens into [Tier.loot].
 *
 * **What it deliberately does not ship.** Every step is a *coordinate* clue — the dig. OSRS's
 * anagrams, cryptics, emotes, maps and puzzle boxes are the follow-up, and they are left out for a
 * concrete reason rather than for effort: an anagram or emote step has to bind an option on an
 * existing npc or object, and this codebase binds one handler per (id, option) pair. Claiming
 * `Talk-to` on an npc that another plugin already owns would silently replace that plugin's
 * handler. Coordinate clues need none of that — the spade's `dig` is a single funnel this system
 * hooks through [tryDig], the same way Barrows already does.
 *
 * **Tiers.** All five exist here, but only HARD and ELITE currently have sources (the curated boss
 * tables). The generic drop veto stays in place: it was a data-quality fix for a broken import, not
 * a placeholder for this system, and re-opening it would put elite caskets back on 27 monsters.
 * EASY/MEDIUM/MASTER are wired and waiting for a source to be pointed at them.
 */
object ClueScrolls {

    // ───────────────────────────── state ─────────────────────────────

    /** The tier name of the active trail, or absent when the player has none. */
    val TIER_ATTR = AttributeKey<String>(persistenceKey = "clue_tier")

    /** How many steps of the active trail are already done. */
    val STEP_ATTR = AttributeKey<Int>(persistenceKey = "clue_step")

    /**
     * The rolled trail: indices into the tier's [Tier.pool], comma-joined. Stored rather than
     * re-rolled so a trail survives a relog and a player can't re-roll an awkward site by logging
     * out. (A plain string because the attribute store persists primitives.)
     */
    val TRAIL_ATTR = AttributeKey<String>(persistenceKey = "clue_trail")

    // ───────────────────────────── the trail ─────────────────────────────

    /** One dig site: where to dig, and the riddle-ish hint the scroll shows. */
    data class Site(val tile: Tile, val hint: String)

    enum class Tier(
        val scroll: String,
        val casket: String,
        val display: String,
        val steps: Int,
        val pool: List<Site>,
        val loot: DropTable,
    ) {
        EASY("item.clue_scroll_easy", "item.reward_casket_easy", "easy", 1, EASY_SITES, EASY_LOOT),
        MEDIUM("item.clue_scroll_medium", "item.reward_casket_medium", "medium", 2, MEDIUM_SITES, MEDIUM_LOOT),
        HARD("item.clue_scroll_hard", "item.reward_casket_hard", "hard", 3, HARD_SITES, HARD_LOOT),
        ELITE("item.clue_scroll_elite", "item.reward_casket_elite", "elite", 4, ELITE_SITES, ELITE_LOOT),
        MASTER("item.clue_scroll_master", "item.reward_casket_master", "master", 5, ELITE_SITES, MASTER_LOOT),
        ;

        companion object {
            fun byName(name: String?): Tier? = values().firstOrNull { it.name == name }
            fun byScroll(key: String): Tier? = values().firstOrNull { it.scroll == key }
            fun byCasket(key: String): Tier? = values().firstOrNull { it.casket == key }
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    fun activeTier(p: Player): Tier? = Tier.byName(p.attr[TIER_ATTR])

    /** The rolled pool indices of the active trail, or empty when there is none. */
    fun trail(p: Player): List<Int> =
        p.attr[TRAIL_ATTR]?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

    fun stepIndex(p: Player): Int = p.attr[STEP_ATTR] ?: 0

    /** The site the player is currently looking for, or null when no trail is active. */
    fun currentSite(p: Player): Site? {
        val tier = activeTier(p) ?: return null
        val idx = trail(p).getOrNull(stepIndex(p)) ?: return null
        return tier.pool.getOrNull(idx)
    }

    fun clearTrail(p: Player) {
        p.attr.remove(TIER_ATTR)
        p.attr.remove(STEP_ATTR)
        p.attr.remove(TRAIL_ATTR)
    }

    fun startTrail(p: Player, tier: Tier, indices: List<Int>) {
        p.attr[TIER_ATTR] = tier.name
        p.attr[STEP_ATTR] = 0
        p.attr[TRAIL_ATTR] = indices.joinToString(",")
    }

    /**
     * The dig step. Called from the spade's single `dig` handler (`items/spade/SpadePlugin`), the
     * same way `Barrows.tryDig` is — only one plugin may own that item option, so this lives here as
     * a plain function rather than on [ClueScrollPlugin].
     *
     * Returns true when the dig WAS a clue step, so the spade handler stops; false when the player
     * is standing anywhere else and the spade should carry on with its own cases.
     */
    fun tryDig(p: Player): Boolean {
        val tier = activeTier(p) ?: return false
        val site = currentSite(p) ?: return false
        val here = p.tile
        if (here.x != site.tile.x || here.z != site.tile.z || here.height != site.tile.height) return false

        p.animate(DIG_ANIM)
        val next = stepIndex(p) + 1
        if (next < tier.steps) {
            p.attr[STEP_ATTR] = next
            p.message("<col=8f00ff>You dig up the next clue.</col>")
            currentSite(p)?.let { p.message(it.hint) }
            return true
        }

        // Last step: pay the casket and end the trail.
        clearTrail(p)
        val casket = runCatching { getRSCM(tier.casket) }.getOrNull()
        if (casket == null) {
            logger.warn { "clues: '${tier.casket}' not in cache; ${p.username}'s trail paid nothing." }
            p.message("You dig up nothing but earth. Report this to staff.")
            return true
        }
        val added = p.inventory.add(item = casket, amount = 1, assureFullInsertion = false)
        if (added.completed == 0) p.world.spawn(GroundItem(casket, 1, p.tile, p))
        p.message("<col=8f00ff>You dig up a reward casket!</col>")
        return true
    }

    /** The standard OSRS spade-dig animation, shared with `SpadePlugin`. */
    private const val DIG_ANIM = 830
}

// ─────────────────────────────────────────────────────────────────────────────
// Dig sites. Tiles are all on ground already used by live content, so every one of
// them is reachable; the tier decides how dangerous getting there is. TUNE in-game.
// ─────────────────────────────────────────────────────────────────────────────

/** Around Lumbridge and the Mire — a starter trail you can walk in a couple of minutes. */
private val EASY_SITES = listOf(
    ClueScrolls.Site(Tile(3230, 3209, 0), "Dig near the well where the road out of Lumbridge bends."),
    ClueScrolls.Site(Tile(3247, 3244, 0), "Dig in the field east of the castle, where the goblins muster."),
    ClueScrolls.Site(Tile(3243, 3186, 0), "Dig on the road south of the yard, where the miners walk."),
    ClueScrolls.Site(Tile(3251, 3201, 0), "Dig by the tree line at the east edge of the working yard."),
    ClueScrolls.Site(Tile(3178, 3316, 0), "Dig in the cow field, north-west of the river."),
    ClueScrolls.Site(Tile(3172, 3293, 0), "Dig in the farm pen where the chickens are kept."),
)

/** The wider safe mainland — Falador, Al Kharid, the crafting road. */
private val MEDIUM_SITES = listOf(
    ClueScrolls.Site(Tile(2979, 3394, 0), "Dig in the square north of Falador, beside the stalls."),
    ClueScrolls.Site(Tile(3375, 3148, 0), "Dig at the giants' camp, east of the desert gate."),
    ClueScrolls.Site(Tile(2910, 3287, 0), "Dig on the peninsula south of the crafting guild."),
    ClueScrolls.Site(Tile(2907, 3335, 0), "Dig inside the stone circle where the wizards gather."),
    ClueScrolls.Site(Tile(3025, 3514, 0), "Dig on the grounds the black knights hold, north of Falador."),
    ClueScrolls.Site(Tile(3113, 3211, 0), "Dig at the foot of the tower that stands alone in the water."),
)

/** The frontier — the Varrock pocket and the near Wilderness. You can be attacked at these. */
private val HARD_SITES = listOf(
    ClueScrolls.Site(Tile(3213, 3424, 0), "Dig in the fallen city, where the raiders come over the wall."),
    ClueScrolls.Site(Tile(3235, 3345, 0), "Dig at the outlaws' camp, just past the ditch."),
    ClueScrolls.Site(Tile(3235, 3382, 0), "Dig where the marauders make their ground."),
    ClueScrolls.Site(Tile(3232, 3423, 0), "Dig in the raider fields, twenty deep."),
    ClueScrolls.Site(Tile(3290, 3248, 0), "Dig at the warren east of Lumbridge, where the horde gathers."),
    ClueScrolls.Site(Tile(3229, 3475, 0), "Dig on the warlord's approach, thirty deep."),
)

/** Deep Wilderness. Every one of these is multi-combat ground with bots hunting it. */
private val ELITE_SITES = listOf(
    ClueScrolls.Site(Tile(3170, 3560, 0), "Dig forty deep, where the PKers wait."),
    ClueScrolls.Site(Tile(3166, 3679, 0), "Dig among the shadows' graves."),
    ClueScrolls.Site(Tile(3036, 3629, 0), "Dig beneath the dark warriors' walls."),
    ClueScrolls.Site(Tile(3295, 3885, 0), "Dig where the demons' ruins still smoulder."),
    ClueScrolls.Site(Tile(2976, 3840, 0), "Dig where the fanatic raves, forty-one deep."),
    ClueScrolls.Site(Tile(3170, 3700, 0), "Dig fifty-five deep, past every safe road."),
)

// ─────────────────────────────────────────────────────────────────────────────
// Reward tables. Every tier ALWAYS pays coins and a supply line so a casket is never a
// disappointment, then rolls one weighted main pick and independent rare chances at the
// cosmetics that make clues worth doing. Deliberately gear-light: clue rewards here are
// prestige and profit, not a combat shortcut past the PK and boss ladders.
// ─────────────────────────────────────────────────────────────────────────────

private val EASY_LOOT = DropTable(
    always = listOf(DropEntry("item.coins_995", 2_000, 6_000)),
    main = listOf(
        DropEntry("item.uncut_ruby", 2, 5, weight = 10),
        DropEntry("item.uncut_diamond", 1, 3, weight = 6),
        DropEntry("item.yew_logs_noted", 20, 50, weight = 10),
        DropEntry("item.blue_dhide_body", 1, 1, weight = 6),
        DropEntry("item.blue_dhide_chaps", 1, 1, weight = 6),
        DropEntry("item.amulet_of_glory4", 1, 1, weight = 5),
        DropEntry("item.law_rune", 20, 50, weight = 8),
    ),
    rare = listOf(
        DropEntry("item.wizard_boots", oneInN = 60),
        DropEntry("item.enchanted_hat", oneInN = 60),
        DropEntry("item.musketeer_hat", oneInN = 80),
    ),
)

private val MEDIUM_LOOT = DropTable(
    always = listOf(DropEntry("item.coins_995", 6_000, 15_000)),
    main = listOf(
        DropEntry("item.uncut_diamond", 3, 8, weight = 10),
        DropEntry("item.uncut_dragonstone", 1, 2, weight = 5),
        DropEntry("item.yew_logs_noted", 50, 120, weight = 8),
        DropEntry("item.amulet_of_glory4", 1, 2, weight = 8),
        DropEntry("item.saradomin_full_helm", 1, 1, weight = 4),
        DropEntry("item.zamorak_full_helm", 1, 1, weight = 4),
        DropEntry("item.death_rune", 50, 120, weight = 8),
    ),
    rare = listOf(
        DropEntry("item.holy_sandals", oneInN = 70),
        DropEntry("item.bronze_dragon_mask", oneInN = 90),
        DropEntry("item.black_dhide_body_t", oneInN = 90),
    ),
)

private val HARD_LOOT = DropTable(
    always = listOf(DropEntry("item.coins_995", 20_000, 50_000)),
    main = listOf(
        DropEntry("item.uncut_dragonstone_noted", 3, 8, weight = 10),
        DropEntry("item.runite_bar_noted", 5, 12, weight = 8),
        DropEntry("item.saradomin_dhide_body", 1, 1, weight = 6),
        DropEntry("item.guthix_dhide_body", 1, 1, weight = 6),
        DropEntry("item.zamorak_dhide_body", 1, 1, weight = 6),
        DropEntry("item.saradomin_platebody", 1, 1, weight = 5),
        DropEntry("item.blood_rune", 100, 250, weight = 8),
    ),
    rare = listOf(
        DropEntry("item.rune_platebody_g", oneInN = 50),
        DropEntry("item.rune_platelegs_g", oneInN = 50),
        DropEntry("item.robin_hood_hat", oneInN = 80, announce = true),
        DropEntry("item.zamorak_page_1", oneInN = 60),
    ),
)

private val ELITE_LOOT = DropTable(
    always = listOf(
        DropEntry("item.coins_995", 60_000, 150_000),
        DropEntry("item.blood_money", 200, 600),
    ),
    main = listOf(
        DropEntry("item.uncut_dragonstone_noted", 10, 25, weight = 10),
        DropEntry("item.runite_bar_noted", 15, 35, weight = 8),
        DropEntry("item.dragon_arrow", 150, 400, weight = 8),
        DropEntry("item.bandos_platebody", 1, 1, weight = 5),
        DropEntry("item.blood_rune", 300, 700, weight = 8),
        DropEntry("item.death_rune", 300, 700, weight = 8),
    ),
    rare = listOf(
        DropEntry("item.gilded_platebody", oneInN = 70, announce = true),
        DropEntry("item.gilded_platelegs", oneInN = 70, announce = true),
        DropEntry("item.gilded_full_helm", oneInN = 80, announce = true),
        DropEntry("item.gilded_kiteshield", oneInN = 80, announce = true),
        DropEntry("item.gilded_med_helm", oneInN = 90),
        DropEntry("item.gilded_scimitar", oneInN = 90, announce = true),
        DropEntry("item.ranger_boots", oneInN = 120, announce = true, log = true),
    ),
)

private val MASTER_LOOT = DropTable(
    always = listOf(
        DropEntry("item.coins_995", 120_000, 300_000),
        DropEntry("item.blood_money", 500, 1_500),
    ),
    main = listOf(
        DropEntry("item.uncut_dragonstone_noted", 25, 60, weight = 10),
        DropEntry("item.runite_bar_noted", 30, 70, weight = 8),
        DropEntry("item.dragon_javelin", 300, 700, weight = 8),
        DropEntry("item.blood_rune", 700, 1_500, weight = 8),
        DropEntry("item.onyx_bolt_tips", 30, 80, weight = 6),
    ),
    rare = listOf(
        DropEntry("item.gilded_platebody", oneInN = 40, announce = true),
        DropEntry("item.gilded_platelegs", oneInN = 40, announce = true),
        DropEntry("item.gilded_full_helm", oneInN = 50, announce = true),
        DropEntry("item.gilded_kiteshield", oneInN = 50, announce = true),
        DropEntry("item.dragon_full_helm_ornament_kit", oneInN = 90, announce = true),
        DropEntry("item.ranger_boots", oneInN = 70, announce = true, log = true),
        DropEntry("item.robin_hood_hat", oneInN = 70, announce = true),
    ),
)

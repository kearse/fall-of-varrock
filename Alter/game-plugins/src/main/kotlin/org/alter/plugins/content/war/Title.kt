package org.alter.plugins.content.war

import org.alter.api.EquipmentType
import org.alter.game.model.attr.PLAYER_TITLE_ATTR
import org.alter.game.model.attr.TITLED_NAME_ATTR
import org.alter.game.model.entity.Player

/**
 * Metal-armour tiers, ascending. A player's [Title] caps which tier they may wear.
 */
enum class ArmourTier(val display: String) {
    BRONZE("bronze"),
    IRON("iron"),
    STEEL("steel"),
    BLACK("black"),
    MITHRIL("mithril"),
    ADAMANT("adamant"),
    RUNE("rune"),
    DRAGON("dragon"),
}

/**
 * The feudal rank ladder — **standing and authority** (design authority §5). Ranks are raised
 * by Duke Horacio once [RankEligibility] is met: the coin price below is one requirement, a
 * floor of lifetime War Effort (service) is another, and milestones such as Veteran of Varrock
 * will join them. Never automatic from a quest, never bought with cash.
 *
 * THE INVARIANT: rank gates what you may WEAR and which wars you may START
 * ([CommandTier] / [Player.canCommand]); it never gates JOINING a war that is under way —
 * `::march` and every rally are open to every citizen.
 *
 * Rank is an ADDITIONAL armour requirement on top of the classic skill-level
 * requirements (loaded for all gear in ItemMetadataService — 40 Defence for rune
 * etc.). Each rank unlocks a higher [maxTier] of armour, and the ladder carries
 * the other perks too:
 *
 *  - Peasant (free): bronze & iron armour
 *  - Commoner (10k): steel armour
 *  - Squire (50k): black armour + coloured name (Squire and up) + rank cape ([RankCapes], Squire and up)
 *  - Soldier (150k): mithril & adamant armour
 *  - Knight (500k): rune armour + a companion soldier ([roster] 1)
 *  - Lord (2M): ALL armour + field troops & summon bosses ([CommandTier.RAID]) + roster of 2
 *  - Minister (10M): launch war campaigns ([CommandTier.CAMPAIGN]) + roster of 3
 *  - King (50M): launch conquests, lead the realm ([CommandTier.CONQUEST])
 *
 * [cost] is the coin price of that rank (the sink half of eligibility). [PEASANT] is the free
 * starting rank. [roster] is how many companion soldiers the rank may keep on its banner (General
 * Zo) and swap between — never how many stand beside the player: that is ONE for every rank
 * (`CompanionRegistry.ACTIVE_MAX`, locked). The tiered muster price (`RecruitMenu.RECRUIT_COSTS`:
 * 10M, 100M, 500M) prices the roster.
 */
enum class Title(
    val display: String,
    val cost: Int,
    val maxTier: ArmourTier,
    val nameColor: String? = null,
    val roster: Int = 0,
) {
    PEASANT("Peasant", 0, ArmourTier.IRON),
    COMMONER("Commoner", 10_000, ArmourTier.STEEL),
    SQUIRE("Squire", 50_000, ArmourTier.BLACK, "9acd32"),    // yellow-green
    SOLDIER("Soldier", 150_000, ArmourTier.ADAMANT, "1e90ff"), // blue - mithril & adamant
    KNIGHT("Knight", 500_000, ArmourTier.RUNE, "c0c0c0", roster = 1),      // silver - rune
    LORD("Lord", 2_000_000, ArmourTier.DRAGON, "a020f0", roster = 2),      // purple
    MINISTER("Minister", 10_000_000, ArmourTier.DRAGON, "dc143c", roster = 3), // crimson
    KING("King", 50_000_000, ArmourTier.DRAGON, "ffd700", roster = 3),     // gold
    ;

    companion object {
        fun byOrdinal(o: Int): Title = values().getOrElse(o) { PEASANT }

        /** The lowest rank allowed to wear [tier] armour. */
        fun forTier(tier: ArmourTier): Title = values().first { it.maxTier.ordinal >= tier.ordinal }
    }
}

/**
 * The tiers of **war command** a [Title] unlocks (boss-raid & campaign content — the
 * political privilege that finally gives Lord/Minister/King a purpose beyond an armour
 * cap). Distinct from [ArmourTier] gating. A title grants its own tier and every tier
 * below it ([Player.canCommand]).
 */
enum class CommandTier(val minTitle: Title) {
    /** Summon a boss and send a small raid party to back the players fighting it. */
    RAID(Title.LORD),
    /** Launch a city campaign that pushes allied troops through the frontier lines. */
    CAMPAIGN(Title.MINISTER),
    /** Send a full army to conquer a city and seize its resources. */
    CONQUEST(Title.KING),
}

/** The player's current bought rank (Peasant by default). */
val Player.title: Title get() = Title.byOrdinal(attr[PLAYER_TITLE_ATTR] ?: 0)

/**
 * The name as it should appear over the head / on right-click: a color-coded "<Rank> Name" for the
 * noble ranks (Squire and up — an EARNED mark), or the plain username for Peasant/Commoner. The
 * `<col>` tag rides along the appearance name so the colour carries without protocol work.
 *
 * TRADE-OFF: a col-tagged name breaks the client engine's name→player lookups (a chatbox
 * "Accept trade"/"Accept challenge" click resolves the requester by cleaned name, and a tagged
 * name cleans to null — "Unable to find <player>"). The shipped client compensates: the
 * `lofchatrequests` RuneLite plugin rewrites those clicks into index-based player ops. Keep it
 * in mind before adding other name-lookup-dependent features (see docs/custom-client.md §4).
 */
fun titledName(p: Player): String {
    val color = p.title.nameColor ?: return p.username
    return "<col=$color>${p.title.display} ${p.username}</col>"
}

/** Recompute [TITLED_NAME_ATTR] from the current rank and push it to the appearance (call on login
 *  and whenever the rank changes). */
fun Player.refreshTitledName() {
    attr[TITLED_NAME_ATTR] = titledName(this)
    org.alter.game.info.PlayerInfo(this).syncAppearance()
}

/** True if the player's rank is high enough to issue [tier] war commands. */
fun Player.canCommand(tier: CommandTier): Boolean = title.ordinal >= tier.minTitle.ordinal

/** How an NPC deferentially addresses a player of this rank (master design brief §4 — NPC deference). */
val Title.address: String
    get() = when (this) {
        Title.PEASANT, Title.COMMONER -> "citizen"
        Title.SQUIRE -> "Squire"
        Title.SOLDIER -> "Soldier"
        Title.KNIGHT -> "Sir"
        Title.LORD -> "my lord"
        Title.MINISTER -> "Minister"
        Title.KING -> "your Majesty"
    }

/** The deferential form of address for this player's current rank. */
val Player.address: String get() = title.address

/** The next rank up, or null if already King. */
val Player.nextTitle: Title?
    get() = title.ordinal.let { if (it < Title.values().lastIndex) Title.values()[it + 1] else null }

/** True if the player's rank allows wearing [tier] armour. */
fun Player.canWear(tier: ArmourTier): Boolean = title.maxTier.ordinal >= tier.ordinal

/**
 * The worn equipment slots the feudal armour gate covers. Weapons are deliberately NOT here —
 * they are gated by skill levels alone, never by rank; armour needs BOTH its classic skill
 * levels and the rank. Shared by [TitlePlugin] (the player's own equip gate) and the companion
 * gear paths (a companion's armour ceiling is its owner's rank).
 */
val RANK_GATED_ARMOUR_SLOTS = setOf(
    EquipmentType.HEAD.id, EquipmentType.CHEST.id, EquipmentType.SHIELD.id,
    EquipmentType.LEGS.id, EquipmentType.GLOVES.id, EquipmentType.BOOTS.id,
)

/**
 * Ranged/magic armour families that sit on the KNIGHT rung (rune-equivalent) — the mid
 * prestige tier: mystic-class robes, the fremennik/granite line, void. TUNE freely.
 */
private val KNIGHT_TIER_ARMOUR = listOf(
    // magic
    "mystic", "splitbark", "enchanted", "infinity", "farseer", "lunar",
    // melee/hybrid mid-prestige
    "granite", "rock-shell", "spined", "skeletal", "gilded", "void",
    "berserker helm", "warrior helm", "archer helm", "neitiznot",
)

/**
 * Endgame sets (any combat style) that sit on the LORD rung (dragon-equivalent — "all
 * armour"): barrows, godwars, raid/wildy prestige gear. TUNE freely.
 */
private val LORD_TIER_ARMOUR = listOf(
    // barrows
    "ahrim", "dharok", "guthan", "torag", "verac", "karil",
    // magic endgame
    "ancestral", "dagon'hai", "zuriel", "virtus",
    // ranged endgame
    "armadyl", "pernix", "masori", "morrigan",
    // melee/hybrid endgame
    "bandos", "justiciar", "inquisitor", "obsidian", "torva", "statius", "vesta",
    "serpentine", "magma", "tanzanite", "3rd age", "crystal",
    "primordial", "pegasian", "eternal",
)

/**
 * The armour tier an item name implies, or null if it isn't rank-gated. Covers all three
 * combat styles: the metal melee ladder plus ranged (leather→studded→snakeskin→d'hide
 * colours) and magic (mystic-class → endgame robes) equivalents mapped onto the same
 * rank rungs. Basic robes, monk/vestment gear and other untiered pieces stay free.
 * Only consulted for [RANK_GATED_ARMOUR_SLOTS] items, so weapon/jewellery names never hit it.
 */
fun armourTier(name: String?): ArmourTier? {
    val n = name?.lowercase() ?: return null

    // Dragonhide first — the colour decides the rung, and the bare "dragon"/"black"
    // keywords below would misfire on it ("black d'hide", "green dragonhide", ...).
    if ("d'hide" in n || "dragonhide" in n) {
        return when {
            "green" in n -> ArmourTier.ADAMANT // Soldier
            "blue" in n -> ArmourTier.RUNE     // Knight
            "red" in n -> ArmourTier.RUNE      // Knight
            "black" in n -> ArmourTier.DRAGON  // Lord
            else -> ArmourTier.RUNE            // god-blessed d'hide — Knight
        }
    }

    // Endgame sets before the generic families — "karil's leathertop" must not fall
    // through to the leather rung.
    if (LORD_TIER_ARMOUR.any { it in n }) return ArmourTier.DRAGON
    if (KNIGHT_TIER_ARMOUR.any { it in n }) return ArmourTier.RUNE

    return when {
        // ranged families
        "snakeskin" in n || "frog-leather" in n -> ArmourTier.BLACK // Squire
        "studded" in n -> ArmourTier.STEEL                          // Commoner
        "leather" in n || "yak-hide" in n -> ArmourTier.IRON        // Peasant (leather + hardleather)
        // the metal melee ladder
        "dragon" in n -> ArmourTier.DRAGON
        "rune" in n -> ArmourTier.RUNE
        "adamant" in n -> ArmourTier.ADAMANT
        "mithril" in n -> ArmourTier.MITHRIL
        "black" in n -> ArmourTier.BLACK
        "steel" in n -> ArmourTier.STEEL
        "iron" in n -> ArmourTier.IRON
        "bronze" in n -> ArmourTier.BRONZE
        else -> null
    }
}

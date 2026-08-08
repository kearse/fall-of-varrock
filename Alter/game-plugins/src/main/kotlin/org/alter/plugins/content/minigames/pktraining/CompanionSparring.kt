package org.alter.plugins.content.minigames.pktraining

import org.alter.game.model.Tile
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.plugins.content.companion.CompItem
import org.alter.plugins.content.companion.CompanionOrders
import org.alter.plugins.content.kits.KitSetup
import org.alter.plugins.content.raids.RaidInstance
// MANDATORY alias: files in this feature routinely sit next to `companion object`s, and a bare
// `import ...companion.Companion` is silently shadowed by them (`is Companion` always-false — a
// real production bug). Alias everywhere, uniformly.
import org.alter.plugins.content.companion.Companion as CompanionPawn

/** How the sparring companion fights: the full NH kitchen sink, or locked to one school. */
enum class SparStyle(val label: String) {
    FULL_NH("Full NH"), MELEE("Melee only"), RANGED("Ranged only"), MAGIC("Magic only")
}

/** Difficulty band — maps onto the bot brain's real knobs (reaction ticks, eat threshold, prayer). */
enum class SparDifficulty(
    val label: String,
    /** Prayer-switch reaction window (null = the brain's human default, mostly 1-2 / sometimes 3-6). */
    val reaction: IntRange?,
    /** Eat threshold override (fraction of max HP; null = loadout default). */
    val eatAt: Double?,
    val usesPrayer: Boolean,
    /** Bait gear-flash frequency (1-in-N between swings; null = never baits). */
    val baitOneIn: Int?,
    val specOnPidSwap: Boolean,
) {
    EASY("Easy", reaction = 3..6, eatAt = 0.7, usesPrayer = false, baitOneIn = null, specOnPidSwap = false),
    MEDIUM("Medium", reaction = null, eatAt = null, usesPrayer = true, baitOneIn = 10, specOnPidSwap = false),
    HARD("Hard", reaction = 1..2, eatAt = 0.5, usesPrayer = true, baitOneIn = 6, specOnPidSwap = true),
}

/** Where the companion's bout loadout comes from. */
enum class CompanionKitMode(val label: String) {
    /** Its own current gear + its standard restock consumables (the default). */
    CURRENT("Current gear"),
    /** A loaner copy of the owner's worn gear + carried inventory — fight yourself. */
    MIRROR("Mirror my loadout"),
    /** A kit built in the kit locker (loaner armoury). */
    CUSTOM("Custom kit"),
}

/** One bout's full configuration — remembered per owner for one-click rematches. */
class SparSettings {
    var fightStyle: SparStyle = SparStyle.FULL_NH
    var difficulty: SparDifficulty = SparDifficulty.MEDIUM
    var rules: TrainingRules = TrainingRules()
    var companionKitMode: CompanionKitMode = CompanionKitMode.CURRENT
    /** The built kit for [CompanionKitMode.CUSTOM]. */
    var companionKit: KitSetup? = null
    /** The owner's loaner kit, or null = they fight in their own gear (the default). */
    var playerKit: KitSetup? = null
}

/**
 * Live **companion sparring** bouts — your own companion as your NH training partner, fought in the
 * PK Training Arena's private instanced pits. This object is the cross-plugin registry (sessions +
 * the static queries other systems consult); the lifecycle lives in [CompanionSparringPlugin].
 *
 * Static queries and who consults them:
 *  - [sanctionsEngagement] — `Combat.canEngage` (owner↔companion attacks are sanctioned mid-bout);
 *  - [isSparring] — `CompanionBrain.tick` (BotBrain owns a sparring companion), companion
 *    dismiss/gear guards (its containers hold loaner gear mid-bout);
 *  - [stashedContainersOf] — `CompanionRegistry.snapshot` (every persist mid-bout writes the
 *    companion's REAL gear, never the loaner kit — the crash-safety keystone);
 *  - [rulesOf] — the duel-rules enforcement sites (eat/potion/prayer/style/movement/spec).
 */
object CompanionSparring {

    /** One live bout. The `real*` fields are the companion's pre-bout state, restored at bout end. */
    class SparSession(
        val owner: Player,
        val comp: CompanionPawn,
        val instance: RaidInstance,
        val settings: SparSettings,
    ) {
        var fighting = false // false during the countdown; true once "FIGHT!" is called
        var countdown = 0

        // Companion's REAL state, captured BEFORE the loaner kit goes on. The container maps are
        // also what CompanionRegistry.snapshot persists mid-bout (see stashedContainersOf).
        var realEquip: Map<Int, CompItem> = emptyMap()
        var realInv: Map<Int, CompItem> = emptyMap()
        var realOrders: CompanionOrders = CompanionOrders.FOLLOW
        var realHuntAnchor: Tile? = null
        var realAttackStyle: Int = 0
        var realHomeTile: Tile = Tile(0, 0)
        var realLeash: Int = 0
        var realRoam: Int = 0

        // The owner's own respawn override, saved for own-gear fighters (loaner kits stash theirs).
        var respawnSaved = false
        var prevRespawn: Int? = null

        /** Names of kit items the companion couldn't wear (level-gated) — reported once at start. */
        var skipped: List<String> = emptyList()
    }

    private val sessions = ArrayList<SparSession>()

    /** Remembered per-owner settings (owner key = lowercased uid), for one-click "same again". */
    private val lastSettings = HashMap<Any, SparSettings>()

    /** Installed by [CompanionSparringPlugin] at init: opens the setup flow (dialogue/overlay). */
    var setupOpener: ((Player, CompanionPawn) -> Unit)? = null

    /** Route a Challenge on an owned companion into the sparring setup flow. */
    fun requestSetup(owner: Player, comp: CompanionPawn) {
        setupOpener?.invoke(owner, comp)
    }

    fun register(s: SparSession) { sessions += s }
    fun unregister(s: SparSession) { sessions.remove(s) }
    fun all(): List<SparSession> = sessions.toList()

    fun sessionOf(p: Player): SparSession? = sessions.firstOrNull { it.owner === p }
    fun sessionOfComp(c: CompanionPawn): SparSession? = sessions.firstOrNull { it.comp === c }

    /** True while [c] is the OPPONENT of a live sparring bout (its gear is a loaner kit). */
    fun isSparring(c: CompanionPawn): Boolean = sessionOfComp(c) != null

    /**
     * The companion's REAL containers while it's mid-bout, or null when it isn't. Consulted by
     * `CompanionRegistry.snapshot` so every persist path (periodic save, logout, panel) writes the
     * real gear — the loaner kit lives only on the transient entity and can never reach the blob.
     */
    fun stashedContainersOf(c: CompanionPawn): Pair<Map<Int, CompItem>, Map<Int, CompItem>>? =
        sessionOfComp(c)?.let { it.realEquip to it.realInv }

    /**
     * True iff [pawn] attacking [target] is this bout's own sanctioned pairing — the owner and
     * their sparring companion, either direction, once the countdown has ended. Nobody else, ever.
     */
    fun sanctionsEngagement(pawn: Pawn, target: Pawn): Boolean {
        val s = when {
            pawn is Player && pawn !is CompanionPawn -> sessionOf(pawn)
            pawn is CompanionPawn -> sessionOfComp(pawn)
            else -> null
        } ?: return false
        if (!s.fighting) return false
        return (pawn === s.owner && target === s.comp) || (pawn === s.comp && target === s.owner)
    }

    /** The bout rules binding [p] (owner or sparring companion), or null outside a bout. */
    fun rulesOf(p: Player): TrainingRules? = when {
        p is CompanionPawn -> sessionOfComp(p)?.settings?.rules
        else -> sessionOf(p)?.settings?.rules
    }

    /** Remember [settings] as [owner]'s last-used setup (one-click rematch / "Load Last"). */
    fun rememberSettings(owner: Player, settings: SparSettings) {
        lastSettings[keyOf(owner)] = settings
    }

    fun lastSettingsOf(owner: Player): SparSettings? = lastSettings[keyOf(owner)]

    private fun keyOf(p: Player): Any = (p.uid.value as? String)?.lowercase() ?: p.uid.value
}

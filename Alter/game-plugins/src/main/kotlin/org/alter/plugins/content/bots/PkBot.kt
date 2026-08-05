package org.alter.plugins.content.bots

import org.alter.game.model.PlayerUID
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.Player

/**
 * A server-side, clientless **fake-player** PKer bot.
 *
 * It is a real [Player] (NOT an [org.alter.game.model.entity.Npc]) with
 * [org.alter.game.model.EntityType.PLAYER], so:
 *  - it renders to real clients with full equipped gear, prayer overhead icons and player
 *    animations (broadcast by the GPI protocol in SequentialSynchronizationTask), and
 *  - `write()` is a no-op (only [org.alter.game.model.entity.Client] overrides it), so its
 *    per-tick `cycle()` / pre-sync run harmlessly with no socket attached.
 *
 * This class only holds AI/roaming STATE. Spawning, gear and combat live in [BotManager] and the
 * (separate) NH combat brain so this stays a thin data carrier.
 */
open class PkBot(world: World, val loadout: BotLoadout) : Player(world) {

    /** Where the bot was spawned; its anchor for roaming and idle return. */
    var homeTile: Tile = Tile(0, 0)

    /** How far the bot may wander from [homeTile] (set by the roaming system; 0 = stationary). */
    var roamRadius: Int = 0

    /** How far from [homeTile] the bot will chase before giving up (0 = never leashes). */
    var leashRadius: Int = 0

    /** The spawn zone that owns this bot, if any (null = hand-spawned via ::spawnbot). */
    var zoneKey: String? = null

    /**
     * Relaxes the [BotBrain]'s wilderness-only guards for THIS bot: when set, it will aggro/chase and
     * roam even on safe (non-wilderness) tiles. Off by default so the standard wilderness PKers keep
     * their PvP-wild-only behaviour; opted into only by dedicated ambusher spawns (e.g. the lone PKer
     * at the Lumbridge goblin camp). Real player-vs-player is still gated normally — only bot aggro
     * is unlocked, via [org.alter.plugins.content.combat.Combat.canEngage]'s bot bypass.
     */
    var ambushEverywhere: Boolean = false

    /**
     * NAMED-KNIGHT hunter lock: when set, this bot only ever aggro/fights the player with this uid
     * (see `BotBrain.eligible`). Used by the Rogue Knight camp engine, which spawns one instance of
     * an assigned knight PER hunter — the lock keeps duplicate knights from cross-aggroing other
     * hunters' fights (the "two Sir Kades thrash the multi cap" failure). Null = fights anyone.
     */
    var boundHunter: PlayerUID? = null

    /**
     * PROVOCATION latch: the uid of the player who last started a fight with this bot. Set by
     * [BotBrain] the moment it sees a player targeting this bot, cleared when the bot disengages.
     * It is the carve-out that lets a bot the brain would otherwise never aggro (a passive named
     * knight, a stood-down camp rogue facing a cleared hunter) keep fighting back for the whole
     * fight — `BotBrain.eligible` re-runs every tick, so without the latch the bot would drop a
     * player-started fight the instant the player's own target flickered.
     */
    var provokedBy: PlayerUID? = null

    /**
     * Boss-knight max-HP override. MUST be applied via this + `setCurrentLevel(3, hp)` — never
     * `setBaseLevel` above 99, which indexes off the end of the 99-entry XP table. Overriding
     * [getMaxHp] here makes the brain's eat ratios, heal caps and the head-bar all use the boosted
     * pool for free. Null = the loadout's normal (≤99) hitpoints.
     */
    var maxHpOverride: Int? = null

    override fun getMaxHp(): Int = maxHpOverride ?: super.getMaxHp()

    /** Boss-knight prayer-reaction override (ticks range) — `1..1` = near frame-perfect flicking.
     *  Null = the brain's human default (mostly 1-2, sometimes 3-6). */
    var reactionTicksRange: IntRange? = null

    /** Boss-knight spec-regen override (ticks per +10% energy; lower = more specs). Null = default. */
    var specRegenPeriod: Int? = null

    /** Per-INSTANCE eat-threshold override (fraction of max HP). Null = the loadout's [BotLoadout.eatAt]. */
    var eatAtOverride: Double? = null

    /** World cycle of the last roam step, so idle wandering is throttled (amble, not sprint). */
    var lastRoamCycle: Int = 0

    /** Tile the brain saw this bot on last tick — feeds the no-progress unstick counter below. */
    var lastBrainTile: Tile? = null

    /** Consecutive brain ticks the bot has been trying to move without changing tile. When it hits
     *  [BotBrain]'s stuck threshold the bot is teleported home (see the unstick in `BotBrain.tick`). */
    var noProgressTicks: Int = 0

    /** Index into the loadout's melee spec rotation (AGS -> maul -> ...), advanced on each spec. */
    var nextMeleeSpec: Int = 0

    /** The school the bot is currently dressed for — drives gear-swap decisions. */
    var currentStyle: BotStyle = loadout.baseStyle

    /** Guard so the brain doesn't issue a second gear swap before the first resolves. */
    var swapping: Boolean = false

    /** World cycle of the last AI decision, so the brain can throttle its think rate. */
    var lastThinkCycle: Int = 0

    /** The combat class the bot's overhead is CURRENTLY protecting against (lags the opponent's real
     *  style by a human reaction delay — see [BotBrain.updatePrayers]). Null = not set yet. */
    var prayedAgainst: CombatClass? = null

    /** The opponent style the bot is mid-reaction to (waiting out its reaction timer before switching
     *  its overhead to it). Null = no pending switch. */
    var pendingPray: CombatClass? = null

    // --- fight-style state (docs/pk-bot-fight-styles.md — PID model, baits, combos) ---

    /** The opponent the fight's PID coin is currently flipped against (fight-scoped). */
    var pidOpponent: PlayerUID? = null

    /** True while the bot holds PID advantage this phase (see [BotBrain.updatePid]). */
    var hasPid: Boolean = false

    /** World cycle at which the fight's PID reshuffles (every 100-150 ticks, like OSRS). */
    var pidShuffleAt: Int = 0

    /** World cycle the bot entered KO range holding its burst for the PID swap (fallback timer). */
    var koWaitSince: Int? = null

    /** The true style hidden behind a bait gear-flash (restored before the next swing). */
    var baitedFrom: BotStyle? = null

    /** Armed instant spec follow-up (e.g. the granite maul after AGS) — RSCM name, fired by
     *  [BotBrain] as soon as the leading spec has swung. */
    var comboFollowup: String? = null
}

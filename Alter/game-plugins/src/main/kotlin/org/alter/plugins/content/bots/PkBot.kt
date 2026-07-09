package org.alter.plugins.content.bots

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

    /** World cycle of the last roam step, so idle wandering is throttled (amble, not sprint). */
    var lastRoamCycle: Int = 0

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
}

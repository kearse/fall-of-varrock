package org.alter.plugins.content.bots

import org.alter.api.EquipmentType
import org.alter.api.PrayerIcon
import org.alter.api.ext.getVarp
import org.alter.api.ext.setVarp
import org.alter.game.model.Tile
import org.alter.game.model.attr.KNIGHT_KEY_ATTR
import org.alter.game.model.World
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.move.hasMoveDestination
import org.alter.game.model.move.moveTo
import org.alter.game.model.move.walkTo
import org.alter.game.model.timer.ATTACK_DELAY
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.CombatConfigs
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.combat.getCombatTarget
import org.alter.plugins.content.bots.knights.CampClearance
import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.mechanics.prayer.Prayer
import org.alter.plugins.content.mechanics.prayer.Prayers
import org.alter.plugins.content.war.StaticTerrain
import org.alter.rscm.RSCM.getRSCM

/**
 * The NH "brain" for a [PkBot] — the per-tick decision layer that sits on top of the normal player
 * combat loop (which already handles pathing, attacking, and special-attack execution).
 *
 * Each tick, for an engaged bot, it:
 *  1. **eats/combos** when low (brew + karambwan), with a realistic food delay,
 *  2. **prays** protect-from-[opponent's current style] (the scary part — live prayer switching),
 *  3. picks an offence style that the opponent is **NOT** overhead-praying and swaps the whole gear
 *     set to it (true tribrid gear switching), autocasting ice barrage on mage,
 *  4. fires a **spec** with the equipped spec weapon (Voidwaker / magic shortbow) when it has energy.
 *
 * Reads the opponent purely from public state: their equipped-weapon class ([CombatConfigs]) and
 * their overhead prayer icon. No client packets are involved — everything is server-side.
 */
object BotBrain {

    private val EAT_DELAY = TimerKey()

    /** Ticks the bot must wait after the opponent switches style before flicking its overhead. */
    private val PRAYER_REACT = TimerKey()

    // HP thresholds (fraction of max) for eating and comboing.
    private const val EAT_AT = 0.55
    private const val COMBO_AT = 0.40
    private const val FOOD_DELAY_TICKS = 3

    // Special attack: spec when energy is at/above this and the bot is on a spec-capable style.
    private const val SPEC_THRESHOLD = 50

    private const val SPEC_REGEN_PERIOD = 5 // ticks between +10% energy regen (no client to do it)

    // --- fight-style constants (docs/pk-bot-fight-styles.md) ---
    /** OSRS reshuffles PIDs at random ~1-minute intervals; the fight coin follows suit. */
    private val PID_SHUFFLE_TICKS = 100..150
    /** A specOnPidSwap boss holds its KO burst for the swap at most this long, then goes anyway. */
    private const val KO_WAIT_MAX_TICKS = 15
    /** Spec weapons with no swing timer of their own — a follow-up with one fires instantly
     *  (the real granite-maul behaviour that makes ags+maul a stack, not two swings). */
    private val INSTANT_SPEC_WEAPONS = setOf("item.granite_maul")

    private const val EAT_ANIM = 829

    // Roaming cadence: at most one wander attempt per ROAM_PERIOD cycles, and only ~1-in-ROAM_ONE_IN
    // of those actually steps — so idle bots amble rather than pace.
    private const val ROAM_PERIOD = 4
    private const val ROAM_ONE_IN = 3

    /** Brain ticks of trying-to-move-without-moving before a wedged bot is teleported home (~30s). */
    private const val STUCK_TICKS = 50

    // Eaten in priority order when low — brews first, then any carried food (so metal-tier bots
    // with cheaper food still heal).
    private val BREW_PRIORITY = listOf(
        "item.saradomin_brew4", "item.anglerfish", "item.manta_ray", "item.shark",
        "item.swordfish", "item.lobster", "item.salmon", "item.trout",
    )
    private val COMBO_FOOD = listOf("item.cooked_karambwan")

    private val HEAL = mapOf(
        "item.saradomin_brew4" to 16,
        "item.cooked_karambwan" to 18,
        "item.anglerfish" to 22,
        "item.manta_ray" to 22,
        "item.shark" to 20,
        "item.swordfish" to 14,
        "item.lobster" to 12,
        "item.salmon" to 9,
        "item.trout" to 7,
    )

    fun tick(world: World, bot: PkBot) {
        if (!bot.isAlive() || !bot.lock.canAttack()) return

        // Top up special-attack energy on a slow cadence (bots have no client SPEC_RESTORE timer).
        // Boss knights may override the period ([PkBot.specRegenPeriod]) — lower = more specs.
        if (world.currentCycle % (bot.specRegenPeriod ?: SPEC_REGEN_PERIOD) == 0 && AttackTab.getEnergy(bot) < 100) {
            AttackTab.restoreEnergy(bot)
        }

        // No-progress unstick: a bot that keeps trying to move (a live walk destination, or a
        // leash-return it can't complete) without ever changing tile is wedged — clipped pockets,
        // teller corridors, pathing dead ends. After ~30s of that, teleport it home rather than
        // leave an unreachable knight taunting the bank (companions will whale on it forever).
        val tryingToMove = bot.hasMoveDestination() ||
            (bot.leashRadius > 0 && !bot.tile.isWithinRadius(bot.homeTile, bot.leashRadius))
        if (tryingToMove && bot.lastBrainTile == bot.tile) {
            if (++bot.noProgressTicks >= STUCK_TICKS) {
                disengage(bot)
                bot.moveTo(bot.homeTile)
                bot.noProgressTicks = 0
            }
        } else {
            bot.noProgressTicks = 0
        }
        bot.lastBrainTile = bot.tile

        // Tether + give up: a zone bot only chases within its leash of home.
        if (bot.leashRadius > 0) {
            val focus = bot.getCombatTarget() as? Player
            if (focus != null && !focus.tile.isWithinRadius(bot.homeTile, bot.leashRadius)) {
                disengage(bot) // target ran past the leash — give up
            }
            if (!bot.tile.isWithinRadius(bot.homeTile, bot.leashRadius)) {
                // Dragged out of its turf: abandon the fight and head home, don't fight on the way.
                disengage(bot)
                if (!bot.hasMoveDestination()) bot.walkTo(bot.homeTile)
                return
            }
        }

        maybeEat(bot)

        val target = (bot.getCombatTarget() as? Player)?.takeIf { eligible(bot, it) }
            ?: acquire(world, bot)
        if (target == null) {
            roam(world, bot) // nobody to fight — wander the turf
            return
        }

        updatePid(world, bot, target)
        updatePrayers(bot, target)

        // Only re-evaluate offence/spec when the bot is ready to swing — avoids gear-swap thrashing.
        // Between swings: finish an in-flight spec combo, else maybe throw a bait gear-flash.
        if (!bot.timers.has(ATTACK_DELAY)) {
            restoreBait(bot)
            val desired = chooseOffence(bot, target)
            if (desired != bot.currentStyle) applyStyle(bot, desired)
            maybeSpec(bot, target)
        } else if (!comboFollowup(bot)) {
            maybeBait(bot)
        }

        if (bot.getCombatTarget() != target) bot.attack(target)
    }

    // --- roaming / leash ---

    private fun disengage(bot: PkBot) {
        if (bot.getCombatTarget() != null) {
            Combat.reset(bot)
            bot.resetFacePawn()
        }
        bot.provokedBy = null // the fight is over — a passive/stood-down bot goes back to ignoring
        // Fight-scoped style state dies with the fight.
        bot.pidOpponent = null
        bot.koWaitSince = null
        bot.comboFollowup = null
        restoreBait(bot)
    }

    // --- the PID coin (docs/pk-bot-fight-styles.md) ---

    /**
     * The fight's PID coin. OSRS processes players in PID order and reshuffles PIDs at random
     * ~1-minute intervals; whoever holds the lower PID wins same-tick races. We model exactly that
     * much — a per-fight coin reshuffled every [PID_SHUFFLE_TICKS]:
     *  - PLAYER holds it: the bot's minimum reaction rolls lose the race tick (+1 — see
     *    [updatePrayers]). Your sharpest switch beats its sharpest flick.
     *  - BOT holds it: its instant combo follow-ups stack with no gap, and a specOnPidSwap
     *    archetype launches its held KO burst (the real "spec on the swap").
     * PID never makes a bot react FASTER than its tuned range — it settles ties, like the real
     * thing, so the windows the ladder teaches stay honest. Invisible by design, also like the
     * real thing: you feel the momentum shift, you never see it.
     */
    private fun updatePid(world: World, bot: PkBot, target: Player) {
        if (bot.pidOpponent != target.uid || world.currentCycle >= bot.pidShuffleAt) {
            bot.pidOpponent = target.uid
            bot.hasPid = (0 until 2).random() == 0
            bot.pidShuffleAt = world.currentCycle + PID_SHUFFLE_TICKS.random()
        }
    }

    /** Idle wander: occasionally walk to a nearby walkable tile within [PkBot.roamRadius] of home. */
    private fun roam(world: World, bot: PkBot) {
        if (bot.roamRadius <= 0 || bot.hasMoveDestination()) return
        if (world.currentCycle - bot.lastRoamCycle < ROAM_PERIOD) return
        bot.lastRoamCycle = world.currentCycle
        if ((0 until ROAM_ONE_IN).random() != 0) return // stand around most of the time
        val raw = world.findRandomTileAround(bot.homeTile, bot.roamRadius) ?: return
        val dest = if (StaticTerrain.isWalkable(raw.x, raw.z)) raw.x to raw.z
            else StaticTerrain.nearestWalkable(raw.x, raw.z) ?: return
        val destTile = Tile(dest.first, dest.second, bot.tile.height)
        // Never wander off the PvP wild onto a safe tile — unless this is a dedicated ambusher, which
        // is allowed to patrol its safe-tile post (e.g. the goblin-camp PKer).
        if (!bot.ambushEverywhere && !PvpZones.isWilderness(destTile)) return
        bot.walkTo(destTile)
    }

    // --- target acquisition ---

    private fun acquire(world: World, bot: PkBot): Player? {
        // ::botduel: a duelling bot only ever fights its partner (see [PkBot.duelPartner]).
        bot.duelPartner?.let { partner ->
            if (!eligible(bot, partner)) return null
            bot.attack(partner)
            return partner
        }
        var best: Player? = null
        var bestDist = Int.MAX_VALUE
        world.players.forEach { p ->
            if (!eligible(bot, p)) return@forEach
            // Anti-gang: don't join a player who already has their share of PKers on them. Only
            // gates NEW acquisitions — a bot already fighting keeps its target, so no thrash.
            if (attackersOn(world, bot, p) >= attackerCap(bot, p)) return@forEach
            val dist = bot.tile.getDistance(p.tile)
            if (dist < bestDist) {
                bestDist = dist
                best = p
            }
        }
        best?.let { bot.attack(it) }
        return best
    }

    /**
     * How many bots may fight one player at once. Single-combat ground (which now includes every
     * deep Rogue Knight camp — see [PvpZones]) is a strict 1v1. The SAFE learning camps (Bandit
     * Hideout, Port Sarim) sit outside the wilderness where singles rules can't apply, so their
     * colony rogues enforce the 1v1 themselves — fresh players learning to fight PKers should
     * never be piled. Open multi wilderness has NO artificial cap (OSRS-style); the spawn-side
     * density cap ([BotColony]'s MAX_BOTS_NEAR_PLAYER) is what keeps pile-ons bounded there.
     */
    private fun attackerCap(bot: PkBot, p: Player): Int = when {
        PvpZones.isSingle(p.tile) -> 1
        CampClearance.campOf(bot)?.safe == true -> 1
        else -> Int.MAX_VALUE
    }

    /** Count of OTHER live PKer bots currently targeting [p]. */
    private fun attackersOn(world: World, self: PkBot, p: Player): Int {
        var n = 0
        world.players.forEach { o ->
            if (o is PkBot && o !== self && o.getCombatTarget() === p) n++
        }
        return n
    }

    private fun eligible(bot: PkBot, p: Player): Boolean {
        // ::botduel: the duel partner is the ONE bot this bot may fight, anywhere — every other
        // rule (no bot targets, wilderness only, leash, provocation) is bypassed for it.
        bot.duelPartner?.let { partner ->
            return p === partner && partner.index >= 0 && !partner.isDead() && Combat.canEngage(bot, partner)
        }
        if (p is PkBot || !p.isOnline || p.invisible) return false
        // A named-knight instance is bound to ONE hunter: it never aggros anyone else, so the
        // per-hunter duplicates at a busy camp each fight their own duel (see [PkBot.boundHunter]).
        if (bot.boundHunter != null && p.uid != bot.boundHunter) return false
        if (!p.tile.isWithinRadius(bot.tile, AGGRO_RANGE)) return false
        if (bot.leashRadius > 0 && !p.tile.isWithinRadius(bot.homeTile, bot.leashRadius)) return false
        // PKers only fight in the PvP wild: never aggro (or chase) a player standing on a safe tile,
        // including the safe carve-outs inside the red (banks / GE / town cores). In the wild itself
        // rank gives no cover — everyone there is fair game. A dedicated ambusher ([PkBot.ambushEverywhere])
        // overrides this to hunt on its safe-tile post (e.g. the goblin-camp PKer).
        if (!bot.ambushEverywhere && !PvpZones.isWilderness(p.tile)) return false

        if (!provoked(bot, p)) {
            // Named knights NEVER open a fight — a boss waits at its camp until its hunter strikes
            // first (the provocation latch above then keeps it swinging for the whole duel).
            if (bot.attr[KNIGHT_KEY_ATTR] != null) return false
            // A camp's tier rogues hunt only players who haven't thinned that camp yet: clear the
            // gate ([CampClearance]) and they stand down for good — though they still answer a
            // fight the cleared player starts, via the same provocation latch.
            val camp = CampClearance.campOf(bot)
            if (camp != null && CampClearance.cleared(p, camp)) return false
        }
        return Combat.canEngage(bot, p)
    }

    /**
     * True while [p] owns the fight with [bot] — they're targeting it now, or did earlier this
     * engagement ([PkBot.provokedBy], cleared on [disengage]). A dead provoker drops the latch so
     * a passive boss doesn't jump its returning hunter after a death — walking back is a fresh,
     * player-initiated engagement.
     */
    private fun provoked(bot: PkBot, p: Player): Boolean {
        if (p.isDead()) {
            if (bot.provokedBy == p.uid) bot.provokedBy = null
            return false
        }
        if (p.getCombatTarget() === bot) bot.provokedBy = p.uid
        return bot.provokedBy == p.uid
    }

    // --- prayer (defence + offence) ---

    private fun updatePrayers(bot: PkBot, target: Player) {
        if (!bot.fightLoadout.usesPrayer) return // a few loadouts opt out of prayer entirely

        // HUMAN prayer reaction: don't flick the overhead the instant the opponent changes style. When
        // their combat class changes, wait a randomized few ticks (usually a sharp 1-2, sometimes a slow
        // 3-6) before switching protection. That lag is the window a real pker uses to switch + spec the
        // bot down in one tick — without it the bot is frame-perfect and near-unkillable.
        val cls = CombatConfigs.getCombatClass(target)
        if (cls != bot.prayedAgainst) {
            if (bot.pendingPray != cls) { // opponent just switched to a new style — start reacting
                bot.pendingPray = cls
                // Boss knights may pin the reaction window ([PkBot.reactionTicksRange]) for a
                // consistent, learnable flick. This delay IS the player's damage window, so never
                // pin 1..1 (frame-perfect = unbeatable switches); the ladder's sharpest is 1..2.
                var react = bot.reactionTicksRange?.random() ?: prayerReactionTicks()
                // PID tiebreak (see updatePid): when the PLAYER holds the fight's PID, their switch
                // wins the race tick — the bot's SHARPEST roll arrives one tick later. Never the
                // other way round: PID cannot make the bot faster than its tuned range.
                if (!bot.hasPid && react == (bot.reactionTicksRange?.first ?: 1)) react++
                bot.timers[PRAYER_REACT] = react
            }
            if (!bot.timers.has(PRAYER_REACT)) { // reaction time elapsed — commit the overhead switch
                bot.prayedAgainst = cls
                bot.pendingPray = null
                bot.statPraySwaps++
            }
        } else {
            bot.pendingPray = null // already protecting their style; nothing pending
        }
        val protect = when (bot.prayedAgainst) {
            CombatClass.MELEE -> Prayer.PROTECT_FROM_MELEE
            CombatClass.RANGED -> Prayer.PROTECT_FROM_MISSILES
            CombatClass.MAGIC -> Prayer.PROTECT_FROM_MAGIC
            else -> null
        }
        // Offence prayer scales with the bot's prayer level, so a low-bracket PKer uses Ultimate
        // Strength / Eagle Eye rather than Piety / Rigour — authentic for the kit, not a maxed main.
        val pray = bot.fightLoadout.stats.prayer
        val offence = when (bot.currentStyle) {
            BotStyle.MELEE -> meleeOffence(pray)
            BotStyle.RANGED -> rangeOffence(pray)
            BotStyle.MAGIC -> mageOffence(pray)
        }
        // Offence first, protect last — so the defensive overhead is always the one that "wins".
        if (!Prayers.isActive(bot, offence)) Prayers.activate(bot, offence)
        if (protect != null && !Prayers.isActive(bot, protect)) Prayers.activate(bot, protect)
    }

    /** Human reaction time (ticks) to an opponent's style switch: mostly a sharp 1-2, sometimes a slow
     *  3-6 — so the bot flicks fast some fights and lags others, never frame-perfect. */
    private fun prayerReactionTicks(): Int =
        if ((0 until 10).random() < 6) (1..2).random() else (3..6).random()

    // Level-gated offence prayers (OSRS unlock levels) — picked from the loadout's prayer stat.
    private fun meleeOffence(prayer: Int): Prayer = when {
        prayer >= 70 -> Prayer.PIETY
        prayer >= 31 -> Prayer.ULTIMATE_STRENGTH
        prayer >= 13 -> Prayer.SUPERHUMAN_STRENGTH
        else -> Prayer.BURST_OF_STRENGTH
    }
    private fun rangeOffence(prayer: Int): Prayer = when {
        prayer >= 74 -> Prayer.RIGOUR
        prayer >= 44 -> Prayer.EAGLE_EYE
        prayer >= 26 -> Prayer.HAWK_EYE
        else -> Prayer.SHARP_EYE
    }
    private fun mageOffence(prayer: Int): Prayer = when {
        prayer >= 77 -> Prayer.AUGURY
        else -> Prayer.MYSTIC_MIGHT
    }

    // --- the bait gear-flash (the prayer trick real NHers throw) ---

    /**
     * Between swings, a baiting archetype ([FightProfile.baitOneIn]) briefly dresses another
     * style — the gear-flash real NHers use to pull your overhead — then re-dresses its true
     * style before it swings ([restoreBait] runs on every ready tick). Pure theatre: it never
     * attacks in the bait kit, so the human counter (read the weapon at SWING time, not
     * mid-cycle) is exactly the counter that works on people.
     */
    private fun maybeBait(bot: PkBot) {
        val oneIn = bot.fightLoadout.profile.baitOneIn ?: return
        if (bot.baitedFrom != null || bot.fightLoadout.gear.size < 2) return
        if (bot.getVarp(AttackTab.SPECIAL_ATTACK_VARP) == 1) return // queued spec must keep its weapon
        if (bot.timers[ATTACK_DELAY] < 2) return // no time to re-dress before the swing
        if ((0 until oneIn).random() != 0) return
        val flash = bot.fightLoadout.gear.keys.filter { it != bot.currentStyle }.randomOrNull() ?: return
        bot.baitedFrom = bot.currentStyle
        bot.statBaits++
        BotManager.equipStyle(bot, flash)
    }

    /** Undo a bait flash: re-dress the true style (swing decisions always run after this). */
    private fun restoreBait(bot: PkBot) {
        val real = bot.baitedFrom ?: return
        bot.baitedFrom = null
        BotManager.equipStyle(bot, real)
        configureStyle(bot)
    }

    // --- offence style (attack off the opponent's overhead prayer) ---

    private fun chooseOffence(bot: PkBot, target: Player): BotStyle {
        val blocked = when (target.prayerIcon) {
            PrayerIcon.PROTECT_FROM_MELEE.id -> BotStyle.MELEE
            PrayerIcon.PROTECT_FROM_MISSILES.id -> BotStyle.RANGED
            PrayerIcon.PROTECT_FROM_MAGIC.id -> BotStyle.MAGIC
            else -> null
        }
        // If they're not protecting our current style, keep hammering it.
        if (bot.currentStyle != blocked) return bot.currentStyle
        // They just prayed our style — switch to one they're not blocking (prefer mage > range > melee).
        return PREFERRED_OFFENCE.firstOrNull { it != blocked && bot.fightLoadout.gear.containsKey(it) }
            ?: bot.currentStyle
    }

    private fun applyStyle(bot: PkBot, style: BotStyle) {
        bot.statGearSwaps++
        BotManager.equipStyle(bot, style)
        configureStyle(bot)
    }

    /**
     * Set the casting-spell + attack-style varp to match [PkBot.currentStyle]. Called on every gear
     * swap AND once at spawn (via [BotManager]) — so a bot whose BASE style is MAGIC actually
     * autocasts from the first tick (the brain only swaps styles reactively, and a dedicated mage may
     * never swap, so without this it would stand there holding a wand doing nothing).
     */
    fun configureStyle(bot: PkBot) {
        if (bot.currentStyle == BotStyle.MAGIC) {
            bot.attr[Combat.CASTING_SPELL] = resolveSpell(bot.fightLoadout.spell[BotStyle.MAGIC])
            bot.setVarp(AttackTab.ATTACK_STYLE_VARP, 0)
        } else {
            bot.attr.remove(Combat.CASTING_SPELL)
            bot.setVarp(AttackTab.ATTACK_STYLE_VARP, 1) // aggressive for max melee/ranged damage
        }
    }

    private fun resolveSpell(key: String?): CombatSpell = when (key) {
        "ice_barrage" -> CombatSpell.ICE_BARRAGE
        "ice_blitz" -> CombatSpell.ICE_BLITZ
        "ice_burst" -> CombatSpell.ICE_BURST
        "blood_barrage" -> CombatSpell.BLOOD_BARRAGE
        else -> CombatSpell.ICE_BARRAGE
    }

    // --- special attacks (fires with the equipped spec weapon: Voidwaker / msb(i)) ---

    private fun maybeSpec(bot: PkBot, target: Player) {
        when (bot.currentStyle) {
            BotStyle.MAGIC -> return // staff has no spec
            BotStyle.RANGED -> {
                if (AttackTab.getEnergy(bot) >= SPEC_THRESHOLD && bot.getVarp(AttackTab.SPECIAL_ATTACK_VARP) != 1) {
                    bot.setVarp(AttackTab.SPECIAL_ATTACK_VARP, 1)
                    bot.statSpecs++
                }
            }
            BotStyle.MELEE -> meleeSpecOrRestore(bot, target)
        }
    }

    /**
     * Whip is the sustained melee main; the spec rotation are KO FINISHERS, swapped in only once
     * the target is at/below the profile's [FightProfile.koAtHp]. Restores the whip (+ shield)
     * otherwise. A [FightProfile.specOnPidSwap] archetype holds the burst until the fight's PID
     * flips its way (the classic "spec on the swap"), capped at [KO_WAIT_MAX_TICKS] so a held
     * spec always goes eventually. Firing a step whose NEXT rotation entry is an instant weapon
     * (granite maul) arms [comboFollowup] — the real ags+maul stack, not two slow swings.
     */
    private fun meleeSpecOrRestore(bot: PkBot, target: Player) {
        val profile = bot.fightLoadout.profile
        val rotation = bot.fightLoadout.meleeSpecRotation
        val finishing = target.getCurrentHp() <= profile.koAtHp
        if (rotation.isNotEmpty() && finishing && AttackTab.getEnergy(bot) >= SPEC_THRESHOLD) {
            if (profile.specOnPidSwap && !bot.hasPid) {
                // KO is loaded but momentum isn't — keep main-weapon pressure until the swap.
                val since = bot.koWaitSince ?: bot.world.currentCycle.also { bot.koWaitSince = it }
                if (bot.world.currentCycle - since < KO_WAIT_MAX_TICKS) {
                    restoreMeleeMain(bot)
                    return
                }
            }
            bot.koWaitSince = null
            val name = rotation[bot.nextMeleeSpec % rotation.size]
            bot.equipment[EquipmentType.WEAPON.id] = Item(getRSCM(name))
            bot.equipment[EquipmentType.SHIELD.id] = null // AGS / maul are two-handed
            bot.calculateBonuses()
            bot.setVarp(AttackTab.SPECIAL_ATTACK_VARP, 1) // combat loop runs the weapon's special
            bot.statSpecs++
            bot.nextMeleeSpec++
            // If the next rotation step is an instant spec weapon, arm the stack: it fires the
            // moment this spec has swung, without waiting out the swing timer.
            val follow = if (rotation.size > 1) rotation[bot.nextMeleeSpec % rotation.size] else null
            if (follow != null && follow != name && follow in INSTANT_SPEC_WEAPONS) {
                bot.comboFollowup = follow
            }
        } else {
            restoreMeleeMain(bot)
            if (!finishing) {
                bot.nextMeleeSpec = 0 // reset the combo once the target is healthy again
                bot.koWaitSince = null
            }
        }
    }

    /**
     * Fire an armed instant follow-up ([PkBot.comboFollowup]) as soon as the leading spec has
     * swung: equip the instant weapon (granite maul — no swing timer on spec, exactly like the
     * real item), queue its special, and cut the remaining attack delay. PID sets the stack gap:
     * holding it, the maul lands with no daylight (the true ags+maul tick-stack); without it,
     * one tick — the window a player with PID gets to eat or pray. Returns true while a combo
     * is in flight (suppresses bait flashes).
     */
    private fun comboFollowup(bot: PkBot): Boolean {
        val next = bot.comboFollowup ?: return false
        if (bot.getVarp(AttackTab.SPECIAL_ATTACK_VARP) == 1) return true // leading spec not out yet
        bot.comboFollowup = null
        if (AttackTab.getEnergy(bot) < SPEC_THRESHOLD) return false // tank's dry — no stack
        bot.equipment[EquipmentType.WEAPON.id] = Item(getRSCM(next))
        bot.equipment[EquipmentType.SHIELD.id] = null
        bot.calculateBonuses()
        bot.setVarp(AttackTab.SPECIAL_ATTACK_VARP, 1)
        bot.statSpecs++
        if (bot.hasPid) bot.timers.remove(ATTACK_DELAY) else bot.timers[ATTACK_DELAY] = 1
        bot.nextMeleeSpec++
        return true
    }

    /** Put the whip (+ shield) back if the bot is currently holding a spec weapon. */
    private fun restoreMeleeMain(bot: PkBot) {
        val main = bot.fightLoadout.gear[BotStyle.MELEE] ?: return
        val mainWeaponId = main[EquipmentType.WEAPON]?.let { getRSCM(it) } ?: return
        if (bot.equipment[EquipmentType.WEAPON.id]?.id == mainWeaponId) return // already on the main
        bot.equipment[EquipmentType.WEAPON.id] = Item(mainWeaponId)
        main[EquipmentType.SHIELD]?.let { bot.equipment[EquipmentType.SHIELD.id] = Item(getRSCM(it)) }
        bot.calculateBonuses()
    }

    // --- survival (eating) ---

    /** Eat from the carried food when low on HP. Public so the companion brain reuses it. */
    fun maybeEat(bot: PkBot) {
        val hp = bot.getCurrentHp()
        val max = bot.getMaxHp()
        if (hp <= 0 || max <= 0) return
        val ratio = hp.toDouble() / max

        // DHers override eatAt LOW so they sit in the high-damage band; everyone else uses EAT_AT.
        // A named-knight INSTANCE may override its loadout's threshold ([PkBot.eatAtOverride]).
        val eatAt = bot.eatAtOverride ?: bot.fightLoadout.eatAt ?: EAT_AT
        // Combo-eat no higher than the eat threshold, so a low-eatAt DHer doesn't karambwan itself
        // back out of its damage band.
        val comboAt = minOf(COMBO_AT, eatAt)

        if (ratio <= eatAt && !bot.timers.has(EAT_DELAY)) {
            if (consume(bot, BREW_PRIORITY)) {
                bot.timers[EAT_DELAY] = FOOD_DELAY_TICKS
                // Eating delays the next attack like a real player — but must never SHORTEN the swing
                // timer of a slow weapon (a 7-tick DH axe was being reset to 3 by every brew sip).
                val pending = if (bot.timers.has(ATTACK_DELAY)) bot.timers[ATTACK_DELAY] else 0
                bot.timers[ATTACK_DELAY] = maxOf(pending, FOOD_DELAY_TICKS)
            }
        }
        // Karambwan is combo food — can be eaten the same tick, no food-timer gate.
        if (ratio <= comboAt) {
            consume(bot, COMBO_FOOD)
        }
    }

    /** Consume the first matching item from [candidates] (priority order), heal, and animate. */
    private fun consume(bot: PkBot, candidates: List<String>): Boolean {
        for (name in candidates) {
            val id = getRSCM(name)
            for (i in 0 until bot.inventory.capacity) {
                val item = bot.inventory[i] ?: continue
                if (item.id != id) continue
                bot.inventory[i] = if (item.amount > 1) Item(item.id, item.amount - 1) else null
                val heal = HEAL[name] ?: 0
                if (heal > 0) bot.setCurrentHp(minOf(bot.getMaxHp(), bot.getCurrentHp() + heal))
                bot.animate(EAT_ANIM)
                bot.statFood++
                return true
            }
        }
        return false
    }

    /** How far (tiles) a bot will engage — matches the engine's 15-tile view/engage cap. */
    private const val AGGRO_RANGE = 15

    private val PREFERRED_OFFENCE = listOf(BotStyle.MAGIC, BotStyle.RANGED, BotStyle.MELEE)
}

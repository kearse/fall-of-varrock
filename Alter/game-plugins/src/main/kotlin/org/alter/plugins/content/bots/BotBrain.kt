package org.alter.plugins.content.bots

import org.alter.api.EquipmentType
import org.alter.api.PrayerIcon
import org.alter.api.ext.setVarp
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.move.hasMoveDestination
import org.alter.game.model.move.walkTo
import org.alter.game.model.timer.ATTACK_DELAY
import org.alter.game.model.timer.TimerKey
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.CombatConfigs
import org.alter.plugins.content.combat.PvpZones
import org.alter.plugins.content.combat.getCombatTarget
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

    // Melee finisher: only swap to AGS/maul specs once the target's HP is at/below this.
    private const val FINISH_HP = 55
    private const val SPEC_REGEN_PERIOD = 5 // ticks between +10% energy regen (no client to do it)

    private const val EAT_ANIM = 829

    // Roaming cadence: at most one wander attempt per ROAM_PERIOD cycles, and only ~1-in-ROAM_ONE_IN
    // of those actually steps — so idle bots amble rather than pace.
    private const val ROAM_PERIOD = 4
    private const val ROAM_ONE_IN = 3

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

        updatePrayers(bot, target)

        // Only re-evaluate offence/spec when the bot is ready to swing — avoids gear-swap thrashing.
        if (!bot.timers.has(ATTACK_DELAY)) {
            val desired = chooseOffence(bot, target)
            if (desired != bot.currentStyle) applyStyle(bot, desired)
            maybeSpec(bot, target)
        }

        if (bot.getCombatTarget() != target) bot.attack(target)
    }

    // --- roaming / leash ---

    private fun disengage(bot: PkBot) {
        if (bot.getCombatTarget() != null) {
            Combat.reset(bot)
            bot.resetFacePawn()
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
        var best: Player? = null
        var bestDist = Int.MAX_VALUE
        world.players.forEach { p ->
            if (!eligible(bot, p)) return@forEach
            // Anti-gang: don't join if the player already has their share of PKers on them (single
            // combat = a lone 1v1 duel; multi = at most [MAX_MULTI_ATTACKERS]). Only gates NEW
            // acquisitions — a bot already fighting keeps its target, so no thrash.
            if (attackersOn(world, bot, p) >= attackerCap(p)) return@forEach
            val dist = bot.tile.getDistance(p.tile)
            if (dist < bestDist) {
                bestDist = dist
                best = p
            }
        }
        best?.let { bot.attack(it) }
        return best
    }

    /** How many bots may fight one player at once: single-combat is a strict 1v1; multi allows a few. */
    private fun attackerCap(p: Player): Int = if (PvpZones.isSingle(p.tile)) 1 else MAX_MULTI_ATTACKERS

    /** Count of OTHER live PKer bots currently targeting [p]. */
    private fun attackersOn(world: World, self: PkBot, p: Player): Int {
        var n = 0
        world.players.forEach { o ->
            if (o is PkBot && o !== self && o.getCombatTarget() === p) n++
        }
        return n
    }

    private fun eligible(bot: PkBot, p: Player): Boolean =
        p !is PkBot && p.isOnline && !p.invisible &&
            // A named-knight instance is bound to ONE hunter: it never aggros anyone else, so the
            // per-hunter duplicates at a busy camp each fight their own duel (see [PkBot.boundHunter]).
            (bot.boundHunter == null || p.uid == bot.boundHunter) &&
            p.tile.isWithinRadius(bot.tile, AGGRO_RANGE) &&
            (bot.leashRadius <= 0 || p.tile.isWithinRadius(bot.homeTile, bot.leashRadius)) &&
            // PKers only fight in the PvP wild: never aggro (or chase) a player standing on a safe tile,
            // including the safe carve-outs inside the red (banks / GE / town cores). In the wild itself
            // rank gives no cover — everyone there is fair game. A dedicated ambusher ([PkBot.ambushEverywhere])
            // overrides this to hunt on its safe-tile post (e.g. the goblin-camp PKer).
            (bot.ambushEverywhere || PvpZones.isWilderness(p.tile)) &&
            Combat.canEngage(bot, p)

    // --- prayer (defence + offence) ---

    private fun updatePrayers(bot: PkBot, target: Player) {
        if (!bot.loadout.usesPrayer) return // a few loadouts opt out of prayer entirely

        // HUMAN prayer reaction: don't flick the overhead the instant the opponent changes style. When
        // their combat class changes, wait a randomized few ticks (usually a sharp 1-2, sometimes a slow
        // 3-6) before switching protection. That lag is the window a real pker uses to switch + spec the
        // bot down in one tick — without it the bot is frame-perfect and near-unkillable.
        val cls = CombatConfigs.getCombatClass(target)
        if (cls != bot.prayedAgainst) {
            if (bot.pendingPray != cls) { // opponent just switched to a new style — start reacting
                bot.pendingPray = cls
                // Boss knights may pin the reaction window ([PkBot.reactionTicksRange], e.g. 1..1 for
                // a near frame-perfect flicker) — the top of the ladder is MEANT to take several tries.
                bot.timers[PRAYER_REACT] = bot.reactionTicksRange?.random() ?: prayerReactionTicks()
            }
            if (!bot.timers.has(PRAYER_REACT)) { // reaction time elapsed — commit the overhead switch
                bot.prayedAgainst = cls
                bot.pendingPray = null
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
        val pray = bot.loadout.stats.prayer
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
        return PREFERRED_OFFENCE.firstOrNull { it != blocked && bot.loadout.gear.containsKey(it) }
            ?: bot.currentStyle
    }

    private fun applyStyle(bot: PkBot, style: BotStyle) {
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
            bot.attr[Combat.CASTING_SPELL] = resolveSpell(bot.loadout.spell[BotStyle.MAGIC])
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
                if (AttackTab.getEnergy(bot) >= SPEC_THRESHOLD) bot.setVarp(AttackTab.SPECIAL_ATTACK_VARP, 1)
            }
            BotStyle.MELEE -> meleeSpecOrRestore(bot, target)
        }
    }

    /**
     * Whip is the sustained melee main; the AGS -> granite-maul rotation are spec FINISHERS, swapped
     * in only once the target is low enough to burst down. Restores the whip (+ shield) otherwise.
     */
    private fun meleeSpecOrRestore(bot: PkBot, target: Player) {
        val rotation = bot.loadout.meleeSpecRotation
        val finishing = target.getCurrentHp() <= FINISH_HP
        if (rotation.isNotEmpty() && finishing && AttackTab.getEnergy(bot) >= SPEC_THRESHOLD) {
            val name = rotation[bot.nextMeleeSpec % rotation.size]
            bot.equipment[EquipmentType.WEAPON.id] = Item(getRSCM(name))
            bot.equipment[EquipmentType.SHIELD.id] = null // AGS / maul are two-handed
            bot.calculateBonuses()
            bot.setVarp(AttackTab.SPECIAL_ATTACK_VARP, 1) // combat loop runs the weapon's special
            bot.nextMeleeSpec++
        } else {
            restoreMeleeMain(bot)
            if (!finishing) bot.nextMeleeSpec = 0 // reset the combo once the target is healthy again
        }
    }

    /** Put the whip (+ shield) back if the bot is currently holding a spec weapon. */
    private fun restoreMeleeMain(bot: PkBot) {
        val main = bot.loadout.gear[BotStyle.MELEE] ?: return
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
        val eatAt = bot.eatAtOverride ?: bot.loadout.eatAt ?: EAT_AT
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
                return true
            }
        }
        return false
    }

    /** How far (tiles) a bot will engage — matches the engine's 15-tile view/engage cap. */
    private const val AGGRO_RANGE = 15

    /** Max PKers that may pile on one player in MULTI-combat (single-combat is always a strict 1v1). */
    private const val MAX_MULTI_ATTACKERS = 2

    private val PREFERRED_OFFENCE = listOf(BotStyle.MAGIC, BotStyle.RANGED, BotStyle.MELEE)
}

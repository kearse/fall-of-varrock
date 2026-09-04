package org.alter.plugins.content.combat

import org.alter.api.*
import org.alter.api.ext.*
import org.alter.game.model.Tile
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.COMBAT_TARGET_FOCUS_ATTR
import org.alter.game.model.attr.LAST_HIT_ATTR
import org.alter.game.model.attr.LAST_HIT_BY_ATTR
import org.alter.game.model.collision.rayCast
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.entity.AreaSound
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.entity.isPlayerAttackable
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.ACTIVE_COMBAT_TIMER
import org.alter.game.model.timer.ATTACK_DELAY
import org.alter.game.model.timer.TimerKey
import org.alter.api.cfg.Varbit
import org.alter.plugins.content.combat.strategy.CombatStrategy
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.combat.strategy.MagicCombatStrategy
import org.alter.plugins.content.combat.strategy.MeleeCombatStrategy
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import org.alter.plugins.content.combat.strategy.magic.PoweredStaves
import java.lang.ref.WeakReference

/**
 * @author Tom <rspsmods@gmail.com>
 */
object Combat {
    val CASTING_SPELL = AttributeKey<CombatSpell>()
    val DAMAGE_DEAL_MULTIPLIER = AttributeKey<Double>()
    val DAMAGE_TAKE_MULTIPLIER = AttributeKey<Double>()
    const val PRIORITY_PID_VARP = 1075
    const val SELECTED_AUTOCAST_VARBIT = 276
    const val DEFENSIVE_MAGIC_CAST_VARBIT = 2668

    /**
     * OSRS PJ timer: for [PJ_TICKS] (12 seconds) after a combat exchange, a combatant in
     * single-way combat can only be attacked by their current opponent. Refreshed on every
     * attack for both participants, so an active fight can't be interrupted.
     */
    val PJ_TIMER = TimerKey()
    const val PJ_TICKS = 20

    /** Throttle for the "can't attack another player's companion" veto ([canEngage] re-fires every cycle). */
    private val COMPANION_VETO_MSG_CYCLE_ATTR = AttributeKey<Int>()
    private const val COMPANION_VETO_MSG_COOLDOWN = 8

    fun reset(pawn: Pawn) {
        pawn.attr.remove(COMBAT_TARGET_FOCUS_ATTR)
    }

    /**
     * Whether spell damage should award the defensive split (Magic + Defence).
     * Two ways in: the defensive-autocast box on the combat tab (varbit 2668),
     * or having the fourth combat style (Focus/Block) selected while casting —
     * the latter guarantees mages a defence-training path even on client builds
     * where the autocast boxes aren't clickable.
     */
    fun isCastingDefensively(player: Player): Boolean =
        player.getVarbit(DEFENSIVE_MAGIC_CAST_VARBIT) != 0 || player.getAttackStyle() == 3

    fun canAttack(
        pawn: Pawn,
        target: Pawn,
        combatClass: CombatClass,
    ): Boolean {
        if (styleBanned(pawn, combatClass)) return false
        return canEngage(pawn, target) && getStrategy(combatClass).canAttack(pawn, target)
    }

    fun canAttack(
        pawn: Pawn,
        target: Pawn,
        strategy: CombatStrategy,
    ): Boolean {
        // The combat loop attacks through THIS overload, so the style ban must cover it too.
        val combatClass = when (strategy) {
            is MagicCombatStrategy -> CombatClass.MAGIC
            is RangedCombatStrategy -> CombatClass.RANGED
            else -> CombatClass.MELEE
        }
        if (styleBanned(pawn, combatClass)) return false
        return canEngage(pawn, target) && strategy.canAttack(pawn, target)
    }

    /**
     * Rule-bound bouts (a duel) can ban whole combat styles — the [CombatRestrictions] view — and
     * the duel's Fun Weapons rule additionally demands a whitelisted joke weapon in hand (bare fists
     * are NOT allowed, the classic rule; the equip point already reverts non-fun weapons, so in
     * practice this catches punching). Messages the pawn and returns true when the swing is banned.
     */
    private fun styleBanned(pawn: Pawn, combatClass: CombatClass): Boolean {
        if (pawn !is Player) return false
        val restrictions = CombatRestrictions.of(pawn)
        if (restrictions != null &&
            ((combatClass == CombatClass.MELEE && restrictions.noMelee) ||
                (combatClass == CombatClass.RANGED && restrictions.noRanged) ||
                (combatClass == CombatClass.MAGIC && restrictions.noMagic))
        ) {
            pawn.message("That combat style isn't allowed in this ${restrictions.context}.")
            return true
        }
        val duelRules = org.alter.plugins.content.minigames.duel.DuelArena.rulesOf(pawn)
        if (duelRules?.funWeapons == true &&
            pawn.equipment[EquipmentType.WEAPON.id]?.id !in
            org.alter.plugins.content.minigames.duel.DuelRules.FUN_WEAPONS
        ) {
            pawn.message("You can only attack with fun weapons in this duel.")
            return true
        }
        return false
    }

    fun isAttackDelayReady(pawn: Pawn): Boolean = !pawn.timers.has(ATTACK_DELAY)

    fun postAttack(
        pawn: Pawn,
        target: Pawn,
    ) {
        pawn.timers[ATTACK_DELAY] = CombatConfigs.getAttackDelay(pawn)
        // BOTH combatants are combat-locked (10.2s logout block) — with only the target
        // timed, an aggressor whose victim didn't retaliate could x-log instantly mid-fight.
        pawn.timers[ACTIVE_COMBAT_TIMER] = 17
        target.timers[ACTIVE_COMBAT_TIMER] = 17 // 10,2 seconds

        pawn.attr[LAST_HIT_ATTR] = WeakReference(target)
        target.attr[LAST_HIT_BY_ATTR] = WeakReference(pawn)

        // Remember who attacked whom for the skull exemption: the victim's counterattack
        // must not skull them even after the aggressor switches target (the old check only
        // compared the aggressor's CURRENT combat target — instantaneous and wrong).
        if (pawn is Player && target is Player) {
            val map = target.attr[ATTACKED_BY_ATTR] ?: HashMap<Int, Int>().also { target.attr[ATTACKED_BY_ATTR] = it }
            map[pawn.index] = pawn.world.currentCycle + ATTACKED_BY_WINDOW
            map.values.removeIf { it < pawn.world.currentCycle } // keep the map tiny
        }

        // Both participants get PJ protection while the exchange is live.
        pawn.timers[PJ_TIMER] = PJ_TICKS
        target.timers[PJ_TIMER] = PJ_TICKS

        // Manual (non-autocast) casts are one-shot: clear the spell so combat breaks off.
        // Powered-staff built-in spells are exempt — the staff keeps attacking (the combat
        // loop re-arms/clears them by wielded weapon each cycle anyway).
        if (pawn.attr.has(CASTING_SPELL) && pawn is Player && pawn.getVarbit(SELECTED_AUTOCAST_VARBIT) == 0 &&
            pawn.attr[CASTING_SPELL] !in PoweredStaves.SPELLS
        ) {
            reset(pawn)
            pawn.attr.remove(CASTING_SPELL)
        }

        if (target is Player && target.interfaces.getModal() != -1) {
            target.closeInterface(target.interfaces.getModal())
            target.interfaces.setModal(-1)
        }

        applySkull(pawn, target)
    }

    /** White-skull the aggressor when they attack a HUMAN player unprovoked (the keep-3 → keep-0
     *  risk hinges on this). Attacking bots does NOT skull (fighting wild PKer bots stays keep-3);
     *  bots are pre-skulled at spawn (see BotManager), so combat never needs to skull them.
     *  Honours the PK_PREVENT_SKULL opt-out. */
    private fun applySkull(pawn: Pawn, target: Pawn) {
        if (pawn !is Player || target !is Player) return
        if (pawn is org.alter.plugins.content.bots.PkBot || target is org.alter.plugins.content.bots.PkBot) return
        if (!PvpZones.isWilderness(pawn.tile)) return // skulling only happens in the wild
        if (target.getCombatTarget() == pawn) return // they were already attacking us → retaliation, not aggression
        // Sliding-window memory of who attacked us: retaliating against a recent aggressor
        // never skulls, even after they switched target or stopped attacking.
        val attackedBy = pawn.attr[ATTACKED_BY_ATTR]
        if (attackedBy != null && (attackedBy[target.index] ?: 0) >= pawn.world.currentCycle) return
        if (pawn.getVarbit(Varbit.PK_PREVENT_SKULL) != 0) return // player opted out
        pawn.skull(SkullIcon.WHITE, SKULL_TICKS)
        // A key-carrier's skull is the keyed variant — re-apply it over the plain white one.
        org.alter.plugins.content.economy.pk.LootKeys.syncOverhead(pawn)
    }

    private const val SKULL_TICKS = 2000 // ~20 minutes at 0.6s/tick

    /** attacker index → world cycle until which retaliating against them is skull-exempt. */
    private val ATTACKED_BY_ATTR = AttributeKey<HashMap<Int, Int>>(resetOnDeath = true)

    /** 1 minute sliding window per aggressor hit — refreshed on every attack they land. */
    private const val ATTACKED_BY_WINDOW = 100

    fun postDamage(
        pawn: Pawn,
        target: Pawn,
    ) {
        if (target.isDead()) {
            return
        }

        /*
         * Don't override the animation if one is already set. @Z-Kris
         */
        val hasBlock = target.previouslySetAnim != -1

        if (!hasBlock) {
            target.animate(CombatConfigs.getBlockAnimation(target))
            if (target is Npc) {
                val npcDefs = target.combatDef
                if (npcDefs.defaultBlockSoundArea) {
                    target.world.spawn(
                        AreaSound(target.tile, npcDefs.defaultBlockSound, npcDefs.defaultBlockSoundRadius, npcDefs.defaultBlockSoundVolume),
                    )
                } else {
                    // Attacker may be an Npc (NPC-vs-NPC war combat) — only players hear the block sound.
                    (pawn as? Player)?.playSound(npcDefs.defaultBlockSound, npcDefs.defaultBlockSoundVolume)
                }
            }
        }

        // A hit can land after its attacker died (projectiles are not cancelled by the
        // attacker's death) — the block animation above is right, but nobody should
        // auto-retaliate against a corpse.
        if (pawn.isDead()) {
            return
        }

        if (target.lock.canAttack()) {
            // Only auto-acquire the attacker as a target when idle (no living target) —
            // re-targeting on every incoming hit made multi-combat thrash between attackers,
            // restarting the combat loop (and its pathing) each time.
            val current = target.getCombatTarget()
            val idle = current == null || current.isDead()
            if (target.entityType.isNpc) {
                if (idle) {
                    target.attack(pawn)
                }
            } else if (target is Player) {
                // Auto-retaliate: honour the combat-tab toggle (varp 172, 1 = disabled), and don't
                // require being already in range — attack() spins up the combat loop which paths into
                // range itself. (Previously this only fired point-blank and the toggle was ignored.)
                if (target.getVarp(AttackTab.DISABLE_AUTO_RETALIATE_VARP) == 0 && idle) {
                    target.attack(pawn)
                }
            }
        }
    }

    /**
     * OSRS overhead-protection rule: the matching protect prayer blocks 100% of the damage
     * dealt by an NPC attacker but only 40% of the damage dealt by a player attacker
     * (accuracy is unaffected in both cases). Bespoke prayer-piercing bosses bypass the
     * formulas entirely via BossCombat, so no pierce flag is needed here.
     */
    fun protectionDamageMultiplier(
        attacker: Pawn,
        target: Pawn,
        combatClass: CombatClass,
    ): Double {
        val icon =
            when (combatClass) {
                CombatClass.MELEE -> PrayerIcon.PROTECT_FROM_MELEE
                CombatClass.RANGED -> PrayerIcon.PROTECT_FROM_MISSILES
                CombatClass.MAGIC -> PrayerIcon.PROTECT_FROM_MAGIC
            }
        if (!target.hasPrayerIcon(icon)) {
            return 1.0
        }
        return if (attacker.entityType.isPlayer) 0.6 else 0.0
    }

    /**
     * Server-wide multiplier on all damage-based combat XP (melee, ranged and magic,
     * including spell base XP). Applied on top of the per-NPC bonus from
     * [getNpcXpMultiplier] and the global [Player.xpRate], so this is the single dial
     * for combat training speed without touching skilling rates.
     */
    const val COMBAT_XP_MULTIPLIER = 2.0

    fun getNpcXpMultiplier(npc: Npc): Double {
        val attackLvl = npc.stats.getMaxLevel(NpcSkills.ATTACK)
        val strengthLvl = npc.stats.getMaxLevel(NpcSkills.STRENGTH)
        val defenceLvl = npc.stats.getMaxLevel(NpcSkills.DEFENCE)
        val hitpoints = npc.getMaxHp()

        val averageLvl = Math.floor((attackLvl + strengthLvl + defenceLvl + hitpoints) / 4.0)
        val averageDefBonus =
            Math.floor(
                (
                    npc.getBonus(
                        BonusSlot.DEFENCE_STAB,
                    ) + npc.getBonus(BonusSlot.DEFENCE_SLASH) + npc.getBonus(BonusSlot.DEFENCE_CRUSH)
                ) / 3.0,
            )
        return 1.0 + Math.floor(averageLvl * (averageDefBonus + npc.getStrengthBonus() + npc.getAttackBonus()) / 5120.0) / 40.0
    }

    fun raycast(
        pawn: Pawn,
        target: Pawn,
        distance: Int,
        projectile: Boolean,
    ): Boolean {
        val world = pawn.world
        val start = pawn.tile
        val end = target.tile

        return start.isWithinRadius(end, distance) && world.lineValidator.rayCast(start, end, projectile = projectile)
    }

    suspend fun moveToAttackRange(
        it: QueueTask,
        pawn: Pawn,
        target: Pawn,
        distance: Int,
        projectile: Boolean,
    ): Boolean {
        val world = pawn.world
        val start = pawn.tile
        val end = target.tile

        val srcSize = pawn.getSize()
        val dstSize = Math.max(distance, target.getSize())

        val touching =
            if (distance > 1) {
                areOverlapping(start.x, start.z, srcSize, srcSize, end.x, end.z, dstSize, dstSize)
            } else {
                areBordering(start.x, start.z, srcSize, srcSize, end.x, end.z, dstSize, dstSize)
            }
        val withinRange = touching && world.lineValidator.rayCast(start, end, projectile = projectile)
        return withinRange //|| pawn.walkToInteract(it, target, lineOfSightRange = distance)
    }

    fun getProjectileLifespan(
        source: Pawn,
        target: Tile,
        type: ProjectileType,
    ): Int =
        when (type) {
            ProjectileType.MAGIC -> {
                val fastRoute = source.tile.getChebyshevDistance(target)
                5 + (fastRoute * 10)
            }
            else -> {
                val distance = source.tile.getDistance(target)
                type.calculateLife(distance)
            }
        }

    fun canEngage(
        pawn: Pawn,
        target: Pawn,
    ): Boolean {
        if (pawn.isDead() || target.isDead()) {
            return false
        }

        // You cannot raise a blade against your own companions. Ownership is matched through the
        // registry's normalized owner key, NOT a raw `uid.value` compare — a display name that comes
        // back in a different case would otherwise read as someone else's companion, and the guard
        // would let a player attack their own knights.
        if (pawn is Player && target is org.alter.plugins.content.companion.Companion &&
            org.alter.plugins.content.companion.CompanionRegistry.owns(pawn, target)
        ) {
            pawn.message("That's your own companion.")
            return false
        }

        // Companions are PvE-ONLY in human PvP (operator decision, 2026-09-02): a real player can
        // never attack someone else's companion, and a companion never attacks a real player (the
        // companion branch further down). Human fights stay human — no 2v1 with a free-respawning
        // bodyguard, and no free-XP punchbag either. Bots/NPCs vs companions are unaffected.
        if (pawn is Player && pawn !is org.alter.plugins.content.bots.PkBot &&
            target is org.alter.plugins.content.companion.Companion
        ) {
            val now = pawn.world.currentCycle
            val last = pawn.attr[COMPANION_VETO_MSG_CYCLE_ATTR] ?: -COMPANION_VETO_MSG_COOLDOWN
            if (now - last >= COMPANION_VETO_MSG_COOLDOWN) {
                pawn.attr[COMPANION_VETO_MSG_CYCLE_ATTR] = now
                pawn.message("You can't attack another player's companion.")
            }
            return false
        }

        val maxDistance =
            when {
                pawn is Player && pawn.hasLargeViewport() -> Player.LARGE_VIEW_DISTANCE
                else -> Player.NORMAL_VIEW_DISTANCE
            }
        if (!pawn.tile.isWithinRadius(target.tile, maxDistance)) {
            return false
        }

        val pvp = pawn.entityType.isPlayer && target.entityType.isPlayer

        if (pawn is Player) {
            if (!pawn.isOnline) {
                return false
            }

            if (pawn.hasWeaponType(WeaponType.BULWARK) && pawn.getAttackStyle() == 3) {
                pawn.message("Your bulwark is in its defensive state and can't be used to attack.")
                return false
            }

            if (pawn.invisible && pvp) {
                pawn.message("You can't attack while invisible.")
                return false
            }
        } else if (pawn is Npc) {
            if (!pawn.isSpawned()) {
                return false
            }
        }

        if (target is Npc) {
            if (!target.isSpawned()) {
                return false
            }
            if (!target.isPlayerAttackable() || target.combatDef.hitpoints == -1) {
                (pawn as? Player)?.message("You can't attack this npc.")
                return false
            }
            if (pawn is Player && target.combatDef.slayerReq > pawn.getSkills().getBaseLevel(Skills.SLAYER)) {
                pawn.message("You need a higher Slayer level to know how to wound this monster.")
                return false
            }
            // Single-way: an npc already fighting ANOTHER player can't be piled — the
            // mirror of the player-target rule further down. NPC targets had no check at
            // all, so single-combat zones only protected players, not their kills.
            // Bots and companions are Players too, but they must never hold the single-way lock
            // on a monster: a player's OWN companion attacks whatever its owner attacks, the boss
            // retaliates onto it, and the owner was locked out of their own fight ("Someone else
            // is fighting that" on every campaign boss — player report 2026-09-02).
            if (pawn is Player && PvpZones.isSingle(target.tile)) {
                val npcTarget = target.getCombatTarget()
                if (npcTarget is Player && npcTarget != pawn &&
                    npcTarget !is org.alter.plugins.content.bots.PkBot &&
                    npcTarget.index >= 0 && !npcTarget.isDead()
                ) {
                    pawn.message("Someone else is fighting that.")
                    return false
                }
            }
        } else if (target is Player) {
            if (!target.isOnline || target.invisible) {
                return false
            }

            if (!target.lock.canBeAttacked()) {
                return false
            }

            // Single-way combat + PJ timer apply to aggressive NPCs too: a monster can't
            // pile a player who is mid-fight with someone else.
            if (pawn is Npc && PvpZones.isSingle(target.tile) && pjBlocked(pawn, target)) {
                return false
            }

            // Duel isolation: an active staked duel is a sealed bubble — outsiders can't hit the
            // duelists (nor they outsiders), nobody swings during the countdown, and a companion
            // never joins (every duel is a pure 1v1 — DuelArena.companionsOpen). This must run
            // BEFORE the bot bypass below, or any PkBot (companions included) could pierce the duel.
            // The refusal wording is per-case classic ("The duel hasn't started yet!" during the
            // countdown, "That is not your opponent." for friendly fire, …).
            if (pvp && pawn is Player) {
                org.alter.plugins.content.minigames.duel.DuelArena.engagementBlock(pawn, target)?.let { refusal ->
                    pawn.message(refusal)
                    return false
                }
            }

            // Rogue Knight camp gate: a named knight refuses any real player who hasn't thinned
            // its camp's tier rogues yet ([CampClearance]). Must run BEFORE the bot bypass below,
            // which would otherwise wave the attack straight through. Because canEngage re-runs
            // every combat cycle, the gate also ends an in-progress fight the moment it applies;
            // the veto message is throttled inside the check.
            if (pawn is Player &&
                org.alter.plugins.content.bots.knights.CampClearance.blocksBossEngagement(pawn, target)
            ) {
                return false
            }

            // PKer bots are attackable anywhere (no wilderness gate, no level range), and they
            // may attack players anywhere. Real player-vs-player keeps the normal rules.
            //
            // A COMPANION is the exception on BOTH sides: it's a player's property, not a
            // free-for-all PK bot. A real player attacking someone else's companion was already
            // refused above; a companion swinging at a REAL player is refused in the
            // companionVsPlayer branch below (PvE-only). Companion vs bots/NPCs keeps the full
            // bypass, and a bot may still attack a companion.
            val companionVsPlayer = pawn is org.alter.plugins.content.companion.Companion &&
                target !is org.alter.plugins.content.bots.PkBot
            val botCombat = (pawn is org.alter.plugins.content.bots.PkBot && !companionVsPlayer) ||
                (target is org.alter.plugins.content.bots.PkBot &&
                    target !is org.alter.plugins.content.companion.Companion)

            // Two players in an active staked duel may hit each other anywhere (the fight is in a
            // private, safe instance). Only that duel's two principals are unlocked — the isolation
            // check above already rejected everything else. Deliberately NOT applied to the
            // companionVsPlayer branch below: companions stay PvE-only, duel or no duel.
            val duelCombat = pawn is Player &&
                org.alter.plugins.content.minigames.duel.DuelArena.sanctionsEngagement(pawn, target)

            if (pvp && !botCombat && !companionVsPlayer && !duelCombat) {
                pawn as Player

                // PvP is only allowed in the wilderness (the red zone); everywhere else is safe.
                if (!PvpZones.isWilderness(pawn.tile) || !PvpZones.isWilderness(target.tile)) {
                    pawn.message("You can't attack players here.")
                    return false
                }

                // Combat-level attack range widens the deeper into the wild you are.
                val wildLvl = PvpZones.wildernessLevel(pawn.tile)
                val minLvl = Math.max(Skills.MIN_COMBAT_LVL, pawn.combatLevel - wildLvl)
                val maxLvl = Math.min(Skills.MAX_COMBAT_LVL, pawn.combatLevel + wildLvl)
                if (target.combatLevel !in minLvl..maxLvl) {
                    pawn.message("Your level difference is too great to attack ${target.username} here.")
                    return false
                }

                // Single-combat: you can't pile a target who's already fighting another PLAYER,
                // and the PJ timer keeps them protected for 20 ticks after their last exchange.
                // NPC fights don't count here — a player killing green dragons in the single
                // zone is exactly who single-way PvP exists to let you attack. PK bots and
                // companions are Players in the engine but NPCs in spirit: fighting a Rogue Knight
                // (or having your own companion beside you) never shields you from a real PKer.
                if (PvpZones.isSingle(target.tile)) {
                    val theirTarget = target.getCombatTarget()
                        ?.takeIf { it is Player && it !is org.alter.plugins.content.bots.PkBot }
                    if (theirTarget != null && theirTarget != pawn) {
                        pawn.message("${target.username} is already in combat.")
                        return false
                    }
                    if (pjBlocked(pawn, target, playersOnly = true)) {
                        pawn.message("${target.username} is already in combat.")
                        return false
                    }
                }
            } else if (pvp && companionVsPlayer) {
                // PvE-only: a companion never raises a blade against a REAL player — not even in
                // its owner's defence. The brain makes it stand back instead
                // (CompanionBrain.holdForOwnersFight); the fight is the owner's alone.
                return false
            }
        }
        return true
    }

    /**
     * True when [target] is under PJ protection against [aggressor]: their PJ timer is
     * running and [aggressor] is not the opponent they are currently exchanging with.
     * With [playersOnly] (the PvP branch), only PLAYER opponents grant protection —
     * being mid-fight with an NPC must not make you unattackable to PKers.
     */
    private fun pjBlocked(
        aggressor: Pawn,
        target: Pawn,
        playersOnly: Boolean = false,
    ): Boolean {
        if (!target.timers.has(PJ_TIMER)) {
            return false
        }
        val partners =
            listOfNotNull(target.attr[LAST_HIT_BY_ATTR]?.get(), target.attr[LAST_HIT_ATTR]?.get())
                .filter { !playersOnly || it is Player }
        if (partners.isEmpty()) {
            return false
        }
        return aggressor !in partners
    }

    private fun getStrategy(combatClass: CombatClass): CombatStrategy =
        when (combatClass) {
            CombatClass.MELEE -> MeleeCombatStrategy
            CombatClass.RANGED -> RangedCombatStrategy
            CombatClass.MAGIC -> MagicCombatStrategy
        }

    /**
     * Checks to see if two AABB overlap (share at least one tile).
     */
    fun areOverlapping(
        x1: Int,
        z1: Int,
        width1: Int,
        length1: Int,
        x2: Int,
        z2: Int,
        width2: Int,
        length2: Int,
    ): Boolean {
        val a = Box(x1, z1, width1 - 1, length1 - 1)
        val b = Box(x2, z2, width2 - 1, length2 - 1)

        if (a.x1 > b.x2 || b.x1 > a.x2) {
            return false
        }

        if (a.y1 > b.y2 || b.y1 > a.y2) {
            return false
        }

        return true
    }

    /**
     * Checks to see if two AABB are bordering, but not overlapping.
     */
    fun areBordering(
        x1: Int,
        z1: Int,
        width1: Int,
        length1: Int,
        x2: Int,
        z2: Int,
        width2: Int,
        length2: Int,
    ): Boolean {
        val a = Box(x1, z1, width1 - 1, length1 - 1)
        val b = Box(x2, z2, width2 - 1, length2 - 1)

        if (b.x1 in a.x1..a.x2 && b.y1 in a.y1..a.y2 || b.x2 in a.x1..a.x2 && b.y2 in a.y1..a.y2) {
            return false
        }

        if (b.x1 > a.x2 + 1) {
            return false
        }

        if (b.x2 < a.x1 - 1) {
            return false
        }

        if (b.y1 > a.y2 + 1) {
            return false
        }

        if (b.y2 < a.y1 - 1) {
            return false
        }
        return true
    }

    data class Box(val x: Int, val y: Int, val width: Int, val length: Int) {
        val x1: Int get() = x

        val x2: Int get() = x + width

        val y1: Int get() = y

        val y2: Int get() = y + length
    }
}

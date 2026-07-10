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
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.ACTIVE_COMBAT_TIMER
import org.alter.game.model.timer.ATTACK_DELAY
import org.alter.api.cfg.Varbit
import org.alter.plugins.content.combat.strategy.CombatStrategy
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.combat.strategy.MagicCombatStrategy
import org.alter.plugins.content.combat.strategy.MeleeCombatStrategy
import org.alter.plugins.content.combat.strategy.RangedCombatStrategy
import org.alter.plugins.content.combat.strategy.magic.CombatSpell
import java.lang.ref.WeakReference

/**
 * @author Tom <rspsmods@gmail.com>
 */
object Combat {
    val CASTING_SPELL = AttributeKey<CombatSpell>()
    val DAMAGE_DEAL_MULTIPLIER = AttributeKey<Double>()
    val DAMAGE_TAKE_MULTIPLIER = AttributeKey<Double>()
    val BOLT_ENCHANTMENT_EFFECT = AttributeKey<Boolean>()
    const val PRIORITY_PID_VARP = 1075
    const val SELECTED_AUTOCAST_VARBIT = 276
    const val DEFENSIVE_MAGIC_CAST_VARBIT = 2668

    fun reset(pawn: Pawn) {
        pawn.attr.remove(COMBAT_TARGET_FOCUS_ATTR)
    }

    fun canAttack(
        pawn: Pawn,
        target: Pawn,
        combatClass: CombatClass,
    ): Boolean {
        // Duel style rules: block a combat class this duel disallows (No Melee / No Ranged / No Magic).
        if (pawn is Player) {
            val rules = org.alter.plugins.content.minigames.duel.DuelArena.rulesOf(pawn)
            if (rules != null &&
                ((combatClass == CombatClass.MELEE && rules.noMelee) ||
                    (combatClass == CombatClass.RANGED && rules.noRanged) ||
                    (combatClass == CombatClass.MAGIC && rules.noMagic))
            ) {
                pawn.message("That combat style isn't allowed in this duel.")
                return false
            }
        }
        return canEngage(pawn, target) && getStrategy(combatClass).canAttack(pawn, target)
    }

    fun canAttack(
        pawn: Pawn,
        target: Pawn,
        strategy: CombatStrategy,
    ): Boolean = canEngage(pawn, target) && strategy.canAttack(pawn, target)

    fun isAttackDelayReady(pawn: Pawn): Boolean = !pawn.timers.has(ATTACK_DELAY)

    fun postAttack(
        pawn: Pawn,
        target: Pawn,
    ) {
        pawn.timers[ATTACK_DELAY] = CombatConfigs.getAttackDelay(pawn)
        target.timers[ACTIVE_COMBAT_TIMER] = 17 // 10,2 seconds
        pawn.attr[BOLT_ENCHANTMENT_EFFECT] = false

        pawn.attr[LAST_HIT_ATTR] = WeakReference(target)
        target.attr[LAST_HIT_BY_ATTR] = WeakReference(pawn)

        if (pawn.attr.has(CASTING_SPELL) && pawn is Player && pawn.getVarbit(SELECTED_AUTOCAST_VARBIT) == 0) {
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
        if (pawn.getVarbit(Varbit.PK_PREVENT_SKULL) != 0) return // player opted out
        pawn.skull(SkullIcon.WHITE, SKULL_TICKS)
        // A key-carrier's skull is the keyed variant — re-apply it over the plain white one.
        org.alter.plugins.content.economy.pk.LootKeys.syncOverhead(pawn)
    }

    private const val SKULL_TICKS = 2000 // ~20 minutes at 0.6s/tick

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

        if (target.lock.canAttack()) {
            if (target.entityType.isNpc) {
                if (!target.attr.has(COMBAT_TARGET_FOCUS_ATTR) || target.attr[COMBAT_TARGET_FOCUS_ATTR]!!.get() != pawn) {
                    target.attack(pawn)
                }
            } else if (target is Player) {
                // Auto-retaliate: honour the combat-tab toggle (varp 172, 1 = disabled), and don't
                // require being already in range — attack() spins up the combat loop which paths into
                // range itself. (Previously this only fired point-blank and the toggle was ignored.)
                if (target.getVarp(AttackTab.DISABLE_AUTO_RETALIATE_VARP) == 0 && target.getCombatTarget() != pawn) {
                    target.attack(pawn)
                }
            }
        }
    }

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

        // You cannot raise a blade against your own companions.
        if (pawn is Player && target is org.alter.plugins.content.companion.Companion &&
            target.ownerUid.value == pawn.uid.value
        ) {
            pawn.message("That's your own companion.")
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
            if (!target.def.isAttackable() || target.combatDef.hitpoints == -1) {
                (pawn as? Player)?.message("You can't attack this npc.")
                return false
            }
            if (pawn is Player && target.combatDef.slayerReq > pawn.getSkills().getBaseLevel(Skills.SLAYER)) {
                pawn.message("You need a higher Slayer level to know how to wound this monster.")
                return false
            }
        } else if (target is Player) {
            if (!target.isOnline || target.invisible) {
                return false
            }

            if (!target.lock.canBeAttacked()) {
                return false
            }

            // Castle Wars outranks every other rule (including the bot bypass below): opposite-team
            // pairs in the SAME game may fight anywhere in the arena, but a teammate may NEVER be
            // attacked — not even by a filler bot — and outsiders can't touch participants at all.
            run {
                val cw = org.alter.plugins.content.minigames.castlewars.CastleWars
                val pawnIn = pawn is Player && cw.inGame(pawn)
                val targetIn = cw.inGame(target)
                if (pawnIn || targetIn) {
                    if (!pawnIn || !targetIn || cw.sameTeam(pawn as Player, target)) {
                        (pawn as? Player)?.message("You can't attack them here.")
                        return false
                    }
                    return true
                }
            }

            // Duel isolation: an active staked duel is a sealed bubble — outsiders can't hit the
            // duelists (nor they outsiders), nobody swings during the countdown, and COMPANIONS only
            // join when the duel's rules allow them (KO'd companions stay benched). This must run
            // BEFORE the bot bypass below, or any PkBot (companions included) could pierce the duel.
            if (pvp && pawn is Player && target is Player &&
                org.alter.plugins.content.minigames.duel.DuelArena.blocksEngagement(pawn, target)
            ) {
                pawn.message("You can't interfere with that fight.")
                return false
            }

            // PKer bots are attackable anywhere (no wilderness gate, no level range), and they
            // may attack players anywhere. Real player-vs-player keeps the normal rules.
            val botCombat = pawn is org.alter.plugins.content.bots.PkBot ||
                target is org.alter.plugins.content.bots.PkBot

            // Two players in an active staked duel may hit each other anywhere (the Duel Arena is a
            // safe, non-wilderness zone). Only this exact pair is unlocked — everyone else stays gated.
            // (Companions in a companions-allowed duel pass via the bot bypass above.)
            val duelCombat = pawn is Player && target is Player &&
                org.alter.plugins.content.minigames.duel.DuelArena.areDueling(pawn, target)

            // Two players in the SAME Last Man Standing game may fight anywhere on the island instance
            // (LMS is free-for-all PvP inside a safe instance). Only same-game pairs are unlocked.
            val lmsCombat = pawn is Player && target is Player &&
                org.alter.plugins.content.minigames.lms.LmsGame.sameGame(pawn, target)

            if (pvp && !botCombat && !duelCombat && !lmsCombat) {
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

                // Single-combat: you can't pile a target who's already fighting another player.
                if (PvpZones.isSingle(target.tile)) {
                    val theirTarget = target.getCombatTarget()
                    if (theirTarget != null && theirTarget != pawn) {
                        pawn.message("${target.username} is already in combat.")
                        return false
                    }
                }
            }
        }
        return true
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

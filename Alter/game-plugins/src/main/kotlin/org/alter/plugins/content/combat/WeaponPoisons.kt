package org.alter.plugins.content.combat

import dev.openrune.cache.CacheManager
import org.alter.api.EquipmentType
import org.alter.api.ext.getEquipment
import org.alter.api.ext.hasEquipped
import org.alter.game.model.combat.CombatClass
import org.alter.game.model.combat.PawnHit
import org.alter.game.model.entity.Npc
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.rscm.RSCM.getRSCM

/**
 * The player-side on-hit poison / venom layer (OSRS Wiki: Poison, Venom, Weapon poison,
 * Serpentine helm). Player report 2026-09-02: nothing a player wielded ever poisoned or
 * envenomed anything — the (p)/(p+)/(p++) ids only existed for spec registration, and the
 * toxic blowpipe / toxic staff had no venom roll at all.
 *
 * Rules, applied to a LANDED hit that dealt damage:
 *  - Toxic weapons (toxic staff of the dead — melee or cast — toxic blowpipe, trident of the
 *    swamp): 1-in-4 venom.
 *  - Poisoned weapons, detected by the `(p)`/`(p+)`/`(p++)` name suffix so every dagger/spear/
 *    hasta/dart/knife/arrow/bolt/javelin variant is covered without an id table:
 *    melee 1-in-4 at 4/5/6, ranged 1-in-8 at 2/3/4 (the fired AMMO carries the poison).
 *  - Serpentine/tanzanite/magma helm, monsters only: 100% with a toxic weapon, 1-in-2 with
 *    a poisoned melee weapon, 1-in-6 with any other melee weapon. Never triggers on players.
 *
 * Wired from [WeaponEffects.applyOnHit], which every combat strategy already calls.
 */
object WeaponPoisons {

    enum class Strength(val suffix: String, val meleeDamage: Int, val rangedDamage: Int) {
        P("(p)", 4, 2),
        PP("(p+)", 5, 3),
        PPP("(p++)", 6, 4),
        ;

        companion object {
            /** Every poisoned-item name ends with exactly one of these; longest suffix first. */
            val BY_SUFFIX_LENGTH = values().sortedByDescending { it.suffix.length }
        }
    }

    private val strengthCache = HashMap<Int, Strength?>()

    /** The poison strength carried by a (p)/(p+)/(p++) item, or null for an unpoisoned one. */
    fun strengthOf(itemId: Int): Strength? {
        if (itemId <= 0) return null
        return strengthCache.getOrPut(itemId) {
            val name = runCatching { CacheManager.getItem(itemId).name }.getOrNull() ?: return@getOrPut null
            Strength.BY_SUFFIX_LENGTH.firstOrNull { name.endsWith(it.suffix) }
        }
    }

    private val TOXIC_WEAPONS: Set<Int> by lazy {
        listOf(
            "item.toxic_staff_of_the_dead",
            "item.toxic_blowpipe",
            "item.trident_of_the_swamp",
            "item.trident_of_the_swamp_e",
        ).mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()
    }

    fun isToxicWeapon(itemId: Int): Boolean = itemId in TOXIC_WEAPONS

    private fun wearsSerpentineHelm(player: Player): Boolean =
        player.hasEquipped(EquipmentType.HEAD, "item.serpentine_helm", "item.tanzanite_helm", "item.magma_helm")

    private fun oneIn(player: Player, n: Int): Boolean = n <= 1 || player.world.random(n - 1) == 0

    /**
     * @param combatClass the class of the attack that produced [pawnHit] (read at fire time by
     *   the strategy — the weapon may have been switched by the time the hit lands).
     * @param ammoId the ammo fired from the quiver, when the attack was a bow/crossbow shot.
     *   Thrown weapons ARE their ammo, so the wielded item is used when this is null.
     */
    fun onHit(
        attacker: Player,
        target: Pawn,
        pawnHit: PawnHit,
        combatClass: CombatClass,
        ammoId: Int? = null,
    ) {
        if (!pawnHit.landed) return
        if (pawnHit.hit.hitmarks.sumOf { it.damage } <= 0) return
        if (target.isDead()) return

        val weapon = attacker.getEquipment(EquipmentType.WEAPON)?.id ?: -1
        val toxic = isToxicWeapon(weapon)
        val poisonSource =
            when (combatClass) {
                CombatClass.MELEE -> weapon
                CombatClass.RANGED -> ammoId ?: weapon
                else -> -1
            }
        val strength = strengthOf(poisonSource)

        // Serpentine helm passive (monsters only — OSRS Wiki, Serpentine helm).
        if (target is Npc && wearsSerpentineHelm(attacker)) {
            val denominator =
                when {
                    toxic -> 1
                    combatClass == CombatClass.MELEE && strength != null -> 2
                    combatClass == CombatClass.MELEE -> 6
                    else -> 0
                }
            if (denominator > 0 && oneIn(attacker, denominator)) {
                Poison.venom(target)
                return
            }
        }

        if (toxic && oneIn(attacker, 4)) {
            Poison.venom(target)
            return
        }

        if (strength != null) {
            val ranged = combatClass == CombatClass.RANGED
            if (oneIn(attacker, if (ranged) 8 else 4)) {
                Poison.poison(target, initialDamage = if (ranged) strength.rangedDamage else strength.meleeDamage)
            }
        }
    }
}

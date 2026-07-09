package org.alter.game.model.combat

import org.alter.game.model.EntityType
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import java.util.*

/**
 * Represents a map of hits from different [Pawn]s and their information.
 *
 * @author Tom <rspsmods@gmail.com>
 */
class DamageMap {
    private val map = WeakHashMap<Pawn, DamageStack>(0)

    operator fun get(pawn: Pawn): DamageStack? = map[pawn]

    fun add(
        pawn: Pawn,
        damage: Int,
    ) {
        val total = (map[pawn]?.totalDamage ?: 0) + damage
        map[pawn] = DamageStack(total, System.currentTimeMillis())
    }

    /**
     * Get all [DamageStack]s dealt by [Pawn]s whom meets the criteria
     * [Pawn.entityType] == [type].
     */
    fun getAll(
        type: EntityType,
        timeFrameMs: Long? = null,
    ): Collection<DamageStack> =
        map.filter {
            it.key.entityType == type && (timeFrameMs == null || System.currentTimeMillis() - it.value.lastHit < timeFrameMs)
        }.values

    /**
     * Get the total damage from a [pawn].
     *
     * @return
     * 0 if [pawn] has not dealt any damage.
     */
    fun getDamageFrom(pawn: Pawn): Int = map[pawn]?.totalDamage ?: 0

    /**
     * Every [Player] who has dealt damage, mapped to their total. Unlike [getAll]
     * (which exposes only the [DamageStack] values) this keeps the player keys, so
     * callers can split rewards by per-player contribution. Only entries still held
     * by the backing [WeakHashMap] are returned, so logged-out/GC'd players naturally
     * drop out — callers should still guard delivery against dead/offline players.
     */
    fun playerDamage(): Map<Player, Int> =
        map.entries
            .filter { it.key.entityType == EntityType.PLAYER }
            .associate { it.key as Player to it.value.totalDamage }

    /**
     * Gets the [Pawn] that has dealt the most damage in this map.
     */
    fun getMostDamage(): Pawn? = map.maxByOrNull { it.value.totalDamage }?.key

    /**
     * Gets the most damage dealt by a [Pawn] in our map whom meets the criteria
     * [Pawn.entityType] == [type].
     */
    fun getMostDamage(
        type: EntityType,
        timeFrameMs: Long? = null,
    ): Pawn? =
        map.filter {
            it.key.entityType == type && (timeFrameMs == null || System.currentTimeMillis() - it.value.lastHit < timeFrameMs)
        }.maxByOrNull { it.value.totalDamage }?.key

    data class DamageStack(val totalDamage: Int, val lastHit: Long)
}

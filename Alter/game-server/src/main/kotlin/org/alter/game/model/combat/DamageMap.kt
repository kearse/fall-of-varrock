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
     * Whether [pawn] satisfies a [type] filter. Asking for [EntityType.PLAYER] matches
     * any pawn whose [EntityType.isPlayer] is true — a real logged-in account is a
     * [org.alter.game.model.entity.Client] with [EntityType.CLIENT], and only
     * bots/companions are bare `PLAYER`, so an exact `==` comparison would silently
     * skip every human attacker. Every other type is matched exactly.
     */
    private fun matches(pawn: Pawn, type: EntityType): Boolean =
        if (type == EntityType.PLAYER) pawn.entityType.isPlayer else pawn.entityType == type

    private fun withinTimeFrame(stack: DamageStack, timeFrameMs: Long?): Boolean =
        timeFrameMs == null || System.currentTimeMillis() - stack.lastHit < timeFrameMs

    /**
     * Get all [DamageStack]s dealt by [Pawn]s whom meets the criteria [type].
     *
     * [EntityType.PLAYER] matches every pawn with [EntityType.isPlayer] (real clients
     * AND bots/companions); other types are matched exactly. Callers that really mean
     * "bots only" must check [Pawn.entityType] themselves.
     */
    fun getAll(
        type: EntityType,
        timeFrameMs: Long? = null,
    ): Collection<DamageStack> =
        map.filter { matches(it.key, type) && withinTimeFrame(it.value, timeFrameMs) }.values

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
     *
     * Matches on [EntityType.isPlayer], NOT `== EntityType.PLAYER`: a real logged-in
     * account is a [org.alter.game.model.entity.Client] whose entity type is
     * [EntityType.CLIENT] — only bots/companions are bare `PLAYER`. The exact-match
     * version silently returned an empty map for every human, so nothing built on it
     * (The Last Free City's FIGHT kill credit, war-boss loot shares, the Asgarnia quest
     * kill counters) ever credited a real player.
     */
    fun playerDamage(): Map<Player, Int> =
        map.entries
            .filter { it.key.entityType.isPlayer }
            .associate { it.key as Player to it.value.totalDamage }

    /**
     * Gets the [Pawn] that has dealt the most damage in this map.
     */
    fun getMostDamage(): Pawn? = map.maxByOrNull { it.value.totalDamage }?.key

    /**
     * Gets the most damage dealt by a [Pawn] in our map whom meets the criteria [type].
     *
     * [EntityType.PLAYER] matches every pawn with [EntityType.isPlayer] (real clients
     * AND bots/companions); other types are matched exactly — see [getAll].
     */
    fun getMostDamage(
        type: EntityType,
        timeFrameMs: Long? = null,
    ): Pawn? =
        map.filter { matches(it.key, type) && withinTimeFrame(it.value, timeFrameMs) }
            .maxByOrNull { it.value.totalDamage }?.key

    data class DamageStack(val totalDamage: Int, val lastHit: Long)
}

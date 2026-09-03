package org.alter.plugins.content.core

import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.RealmSupply

/**
 * **Realm Supplies** — the kingdom's shared, consumable stockpile (design authority 03 §4). Players
 * donate/produce into it; only the commanders' high-tier wars consume it. Not a player wallet: it
 * has no per-player balance, only the one realm-wide meter (persisted in `war_state.json`).
 *
 * `addRealmSupplies(amount)` → [add]; `consumeRealmSupplies(amount)` → [consume].
 * If the stockpile reaches zero, ordinary gameplay continues; only campaigns and conquests wait.
 */
object RealmSuppliesApi {

    const val NAME = RealmSupply.NAME

    fun get(): Int = RealmSupply.meter()
    fun max(): Int = RealmSupply.max()
    fun canAfford(amount: Int): Boolean = RealmSupply.canAfford(amount)

    /**
     * Raise the stockpile by [amount]. Pass the [contributor] when a player produced it — the
     * hand-in is then filed in their service ledger (`::service`). Announces when a campaign
     * becomes affordable. Does NOT award War Effort — pair with [WarEffortApi.add] if the act
     * is personal service too (the depot does both).
     */
    fun add(world: World, amount: Int, contributor: Player? = null) = RealmSupply.contribute(world, amount, contributor)

    /**
     * Drain [amount] because [who] did [what] (past tense, e.g. `"marched a campaign on Varrock"`,
     * `"provisioned the convoy"`). Broadcast realm-wide. Does not go below zero.
     */
    fun consume(world: World, amount: Int, who: String, what: String) = RealmSupply.consume(world, amount, who, what)

    /** The `::supply` status line. */
    fun status(): String = RealmSupply.status()
}

package org.alter.plugins.content.core

import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.plugins.content.war.CampaignDirector
import org.alter.plugins.content.war.Campaigns
import org.alter.plugins.content.war.MarchTarget
import org.alter.plugins.content.war.MarchTargets
import org.alter.plugins.content.war.WarAuthority
import org.alter.plugins.content.war.WarState
import org.alter.plugins.content.war.WarType
import org.alter.plugins.content.war.events.WarEvents
import org.alter.plugins.content.war.events.WarHooks

/**
 * **The war** — start, join, and react (design authority 03 §2). Five public tiers ([WarType]):
 * March and Grand March (the realm's own, automatic, free), Lord operation (Lord+), Campaign
 * (Minister+), Conquest (King). **Starting is rank-gated; participating never is.** Every victory
 * is temporary — nothing is ever captured or owned.
 *
 * - `canStartCampaign(p)` / `canStartConquest(p)` → [canStartCampaign] / [canStartConquest]
 *   (or [check] for the reasons).
 * - `startPublicWar(type, objective)` → [startPublicWar]: a story event's sponsor-less op —
 *   free, supply-free, open to all — on a march target or a hostile city.
 * - A player COMMANDING a war (their coins, their supplies) → [start].
 * - React when any op ends → [onEnded]; ask after the fact → [didParticipate].
 */
object WarApi {

    // ---- may they start it? ---------------------------------------------------------------------

    /** Everything between [p] and starting [type] on [targetKey] (empty = yes). */
    fun check(p: Player, type: WarType, targetKey: String? = null): List<WarAuthority.Denial> = WarAuthority.check(p, type, targetKey)

    fun canStart(p: Player, type: WarType, targetKey: String? = null): Boolean = WarAuthority.canStart(p, type, targetKey)

    /** Player-facing reasons they cannot start [type] right now (empty = they can). */
    fun whyNot(p: Player, type: WarType, targetKey: String? = null): List<String> = WarAuthority.describeAll(type, check(p, type, targetKey))

    fun canStartOperation(p: Player, targetKey: String): Boolean = canStart(p, WarType.LORD_OPERATION, targetKey)
    fun canStartCampaign(p: Player, city: String? = null): Boolean = canStart(p, WarType.CAMPAIGN, city)
    fun canStartConquest(p: Player, city: String? = null): Boolean = canStart(p, WarType.CONQUEST, city)

    // ---- start one --------------------------------------------------------------------------------

    /**
     * [p] COMMANDS a [type] op (charged their coins / the realm's supplies exactly like the
     * `::operation` / `::campaign` / `::conquest` commands). Prints nothing — narrate from the result.
     */
    fun start(world: World, p: Player, type: WarType, targetKey: String? = null, onResult: ((Boolean) -> Unit)? = null): WarAuthority.LaunchResult =
        WarAuthority.launch(world, p, type, targetKey, onResult)

    /**
     * A **public, sponsor-less** [type] op on [objective] — what a story event or a quest step
     * launches. Free, spends no Realm Supplies, anyone joins. MARCH / GRAND_MARCH strike a march
     * target key ([marchTargets]); CAMPAIGN / CONQUEST strike a hostile city key ([hostileCities]).
     * [onResult] fires once with the outcome; the [onEnded] result follows with the shares.
     */
    fun startPublicWar(world: World, type: WarType, objective: String, onResult: ((Boolean) -> Unit)? = null): WarEvents.StartResult =
        WarEvents.startPublicOperation(world, type, objective, onResult)

    // ---- what's live, join, participation -----------------------------------------------------------

    /** Is the realm's public warband (a march / Grand March) in the field? */
    fun isRunning(): Boolean = WarEvents.isRunning()
    fun current(): CampaignDirector? = WarEvents.current()

    /** Rally [p] to the live column (`::march`); a hot rally asks for a second call with `confirmed = true`. */
    fun join(p: Player, world: World, confirmed: Boolean = false): WarEvents.JoinResult = WarEvents.join(p, world, confirmed)

    /** The ledger key a public [type] op on [objective] files under (for [didParticipate]). */
    fun opKeyFor(type: WarType, objective: String): String? = WarEvents.opKeyFor(type, objective)

    /** Did [p] meaningfully fight in the op filed under [opKey] (≥ [minShare]%, won if [mustWin])? */
    fun didParticipate(p: Player, opKey: String, minShare: Int = 1, mustWin: Boolean = true): Boolean =
        WarEvents.didParticipate(p, opKey, minShare, mustWin)

    /** React to every march / operation / campaign / conquest ending, with the share table. */
    fun onEnded(priority: Int = 0, listener: (WarHooks.WarResult) -> Unit) = WarHooks.onOperationEnded(priority, listener)

    // ---- targets and the realm's queue -------------------------------------------------------------

    fun marchTargets(): List<String> = MarchTargets.pool.map { it.key }
    fun hostileCities(): List<String> = Campaigns.HOSTILE.map { it.cityKey }

    /** Add a march target from your own plugin `init` (see [MarchTargets.register] for the rules). */
    fun registerMarchTarget(target: MarchTarget): MarchTarget = MarchTargets.register(target)

    /** Queue a patron-funded march (the store's purchase) for the next muster call; persisted. */
    fun queuePatronMarch(patronName: String, grand: Boolean) = WarState.queuePatronMarch(patronName, grand)
}

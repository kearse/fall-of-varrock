package org.alter.plugins.content.mechanics.shops

import dev.openrune.cache.CacheManager.getNpc
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.Plugin
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * Click options that should ALL route to the same shop / vendor menu.
 *
 * In the OSRS cache a store NPC usually carries more than one of these (e.g. both "Talk-to" AND
 * "Trade"). The old per-plugin binders picked a single preferred option and bound only that, which
 * left the other option(s) dead on the right-click menu — the classic "clicking Talk-to on a
 * shopkeeper does nothing" bug. Binding every entry option the npc actually has fixes it: whichever
 * one the player clicks, they land in the same store / options menu.
 */
private val VENDOR_ENTRY_OPTIONS = listOf(
    "trade", "talk-to", "talk", "exchange", "shop", "store", "buy", "buy-dye",
    "gamble", "rewards", "view-offers", "collect",
)

/**
 * Bind [logic] to EVERY vendor-entry option ([VENDOR_ENTRY_OPTIONS]) the npc actually has in this
 * cache. Resolved defensively: a missing cache key or an npc with none of these options is skipped
 * (and logged) rather than crashing plugin init. If the npc has none of the known entry options,
 * falls back to its first available action so the vendor is still reachable.
 *
 * @return true if at least one option was bound.
 */
fun KotlinPlugin.bindVendorOptions(npc: String, logic: Plugin.() -> Unit): Boolean {
    val acts = try {
        getNpc(getRSCM(npc)).actions.filterNotNull().filter { it.isNotBlank() }
    } catch (e: Exception) { emptyList() }

    val toBind = acts.filter { a -> VENDOR_ENTRY_OPTIONS.any { it.equals(a, true) } }
        .ifEmpty { acts.take(1) } // fall back to the first action so the vendor stays usable

    toBind.forEach { opt -> onNpcOption(npc, option = opt, logic = logic) }

    if (toBind.isEmpty()) logger.warn { "vendor '$npc' has no click options to bind a shop/menu to." }
    return toBind.isNotEmpty()
}

/**
 * Split binding for vendors that have something to SAY as well as something to SELL:
 * "Talk-to"/"Talk" runs [talk] (dialogue), every other vendor-entry option — Trade, Shop,
 * Rewards, … — runs [trade] (which should open the store DIRECTLY, no dialogue hop).
 *
 * If the npc has no talk option, [talk] is simply unreachable; if it has ONLY talk options,
 * they run [trade] instead so the store is never dead — a shopkeeper you can only talk to
 * still sells.
 *
 * @return true if at least one option was bound.
 */
fun KotlinPlugin.bindVendorTalkAndTrade(
    npc: String,
    talk: Plugin.() -> Unit,
    trade: Plugin.() -> Unit,
): Boolean {
    val acts = try {
        getNpc(getRSCM(npc)).actions.filterNotNull().filter { it.isNotBlank() }
    } catch (e: Exception) { emptyList() }

    val talkActs = acts.filter { it.equals("talk-to", true) || it.equals("talk", true) }
    val tradeActs = acts.filter { a ->
        a !in talkActs && VENDOR_ENTRY_OPTIONS.any { it.equals(a, true) }
    }.ifEmpty { if (talkActs.isEmpty()) acts.take(1) else emptyList() }

    if (tradeActs.isEmpty()) {
        // No dedicated trade option — the talk option(s) must carry the store.
        talkActs.forEach { opt -> onNpcOption(npc, option = opt, logic = trade) }
    } else {
        talkActs.forEach { opt -> onNpcOption(npc, option = opt, logic = talk) }
        tradeActs.forEach { opt -> onNpcOption(npc, option = opt, logic = trade) }
    }

    val bound = talkActs.isNotEmpty() || tradeActs.isNotEmpty()
    if (!bound) logger.warn { "vendor '$npc' has no click options to bind talk/trade to." }
    return bound
}

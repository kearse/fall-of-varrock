package org.alter.plugins.content.minigames.duel

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ChatMessageType
import org.alter.api.EquipmentType
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.action.EquipAction
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.DUEL_ESCROW_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.model.item.Item
import org.alter.game.model.move.moveTo
import org.alter.game.model.priv.Privilege
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.timer.FROZEN_TIMER
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.bots.PkBot
import org.alter.plugins.content.combat.PvpZones
// Aliased: this class has its own `companion object`, which would shadow a bare Companion import
// (the always-false `is Companion` bug from BotCombatPlugin). The alias is unambiguous.
import org.alter.plugins.content.companion.Companion as CompanionPawn
import org.alter.plugins.content.companion.CompanionRegistry
import org.alter.plugins.content.mechanics.trading.TRADE_SESSION_ATTR
import org.alter.plugins.content.mechanics.trading.getTradeSession
import org.alter.plugins.content.mechanics.trading.impl.StakeHook
import org.alter.plugins.content.mechanics.trading.impl.TradeSession
import org.alter.plugins.content.minigames.castlewars.CastleWars
import org.alter.plugins.content.raids.RaidInstance
import org.alter.rscm.RSCM.getRSCM
import org.bson.Document

private val logger = KotlinLogging.logger {}

/** Pending duel challenges sent TO this player (by identity), mirroring the trade-request pattern. */
val DUEL_REQUESTS_ATTR = AttributeKey<MutableSet<Player>>()

/**
 * **Duelling — classic staking, challenge anywhere.** The old-school "risk it all" duel: right-click
 * **Challenge** a player in any safe zone, both put up items + coins on the stake screen (the trade
 * UI, re-labelled), confirm, then fight to the death — **the winner takes both stakes**.
 *
 * The fight itself happens in a **private instanced copy of the Duel-Arena pit** (the Wizard-Tower
 * pattern): each duel allocates its own [RaidInstance] of [ARENA_SOURCE], so any number of duels can
 * run at once without ever sharing a pit. When the duel resolves, both fighters are returned to the
 * exact tiles they were challenged on. The real arena grounds still host the
 * [org.alter.plugins.content.minigames.pktraining.PkTrainingArenaPlugin] training pits.
 *
 * Safe by construction (no scam surface):
 *  - The instant both confirm, the stakes move into **escrow** (a [Duel] object), NOT to the opponent.
 *    Nothing is exchanged by hand, so the classic "remove-item-at-the-last-second" scam can't happen —
 *    the confirm screen shows both locked stakes + their coin value.
 *  - Death here drops **no personal items** (every instanced map is a safe death); only the escrow moves.
 *  - **Crash-safe:** each player's own stake is persisted to [DUEL_ESCROW_ATTR]; if the JVM dies
 *    mid-duel the duel is voided and every stake is refunded on next login. Staked wealth can't vanish.
 *  - Forfeit / logout mid-fight = you lose the stake to your opponent.
 *
 * Rules (no-prayer, no-food, whip-only, obstacles, …) are a later phase; v1 is a straight fight.
 */
class DuelArenaPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val duelTimer = TimerKey()

    init {
        // Duel deaths are safe by construction: fights happen inside an instanced map, and
        // SafeDeaths treats every instanced-map tile as a safe death (personal items kept).

        onLogin {
            player.attr[DUEL_REQUESTS_ATTR] = HashSet()
            player.sendOption("Challenge", CHALLENGE_OPTION_SLOT)
            // Clear any stale rules-overlay state (transient signal must never persist across login).
            player.setVarp(DuelRulesClientMenu.STATE_VARP, 0)
            // Crash recovery: a leftover escrow blob means a duel never resolved — refund the stake.
            player.attr[DUEL_ESCROW_ATTR]?.let { blob ->
                val refunded = itemsFromBlob(blob)
                if (refunded.isNotEmpty()) {
                    award(player, refunded)
                    player.message("<col=801700>A duel was interrupted — your stake has been returned.</col>")
                }
                player.attr.remove(DUEL_ESCROW_ATTR)
            }
        }

        onPlayerOption("Challenge") {
            val target = player.getInteractingPlayer()
            challenge(player, target)
        }

        // Enforce equipment rules: reverting any equip a duel disallows (disabled slot / non-allowed
        // weapon). onEquipToSlot is multi-bind, so this stacks cleanly with the other equip handlers.
        EquipmentType.values.forEach { type ->
            onEquipToSlot(type.id) {
                val rules = DuelArena.rulesOf(player) ?: return@onEquipToSlot
                if (slotDisallowed(rules, type.id, player.equipment[type.id]?.id)) {
                    EquipAction.unequip(player, type.id)
                    player.message("You can't wear that in this duel.")
                }
            }
        }

        onWorldInit { world.timers[duelTimer] = 1 }
        onTimer(duelTimer) {
            DuelArena.active.toList().forEach { d ->
                runCatching { tick(d) }.onFailure { e -> logger.error(e) { "duel tick failed" } }
            }
            world.timers[duelTimer] = 1
        }

        // Dying in a fight = you lose; opponent takes the escrow. (Personal items are safe here.)
        // A COMPANION KO'd during a companions-allowed duel is benched for the rest of it — the
        // companion system auto-respawns it at home, and benching stops it snapping back into the
        // pit as an immortal reinforcement.
        onPlayerDeath {
            (player as? CompanionPawn)?.let { comp ->
                CompanionRegistry.ownerOf(world, comp)?.let { owner ->
                    DuelArena.duelOf(owner)?.let { d -> if (d.fighting) d.benched += comp }
                }
            }
            // A principal's death resolves the duel in ANY phase — a countdown death (lingering
            // poison etc.) must not leave the duel hanging with one fighter gone.
            DuelArena.duelOf(player)?.let { d -> resolve(d, loser = player) }
        }

        // Logging out mid-duel forfeits the stake to the opponent (a pre-fight stake screen is
        // declined by the trade plugin's own onLogout, which safely returns the un-committed items).
        onLogout {
            if (DuelRulesScreen.isOpen(player)) DuelRulesScreen.cancel(player)
            if (DuelRulesClientMenu.isOpen(player)) DuelRulesClientMenu.cancel(player)
            DuelArena.duelOf(player)?.let { d -> resolve(d, loser = player) }
        }

        // ── rules-grid interface routing (cache group DuelRulesScreen.IFACE) ──
        onButton(DuelRulesScreen.IFACE, DuelRulesScreen.CLOSE_HIT) { DuelRulesScreen.cancel(player) }
        onButton(DuelRulesScreen.IFACE, DuelRulesScreen.DECLINE_HIT) { DuelRulesScreen.cancel(player) }
        onButton(DuelRulesScreen.IFACE, DuelRulesScreen.ACCEPT_HIT) { DuelRulesScreen.accept(player) }
        for (i in 0 until DuelRulesScreen.TOGGLES) {
            onButton(DuelRulesScreen.IFACE, DuelRulesScreen.RULE_HIT_BASE + i) { DuelRulesScreen.toggle(player, i) }
        }
        onInterfaceClose(DuelRulesScreen.IFACE) { DuelRulesScreen.onClosed(player) }

        // Live-verify controls for the grid (see docs/duel-rules-grid.md): flip the flow to the
        // grid once the cache group is authored, and open a solo layout test (self-vs-self; toggles
        // work, accept can't complete — Decline to exit).
        onCommand("dueloverlay", Privilege.ADMIN_POWER, description = "Toggle the themed client-overlay duel rules screen") {
            DuelArena.useRulesOverlay = !DuelArena.useRulesOverlay
            player.message("Duel rules overlay: ${if (DuelArena.useRulesOverlay) "ON" else "OFF"}.")
        }
        onCommand("duelgrid", Privilege.ADMIN_POWER, description = "Toggle the duel rules-grid interface flow") {
            DuelArena.useRulesGrid = !DuelArena.useRulesGrid
            player.message("Duel rules grid: ${if (DuelArena.useRulesGrid) "ON" else "OFF (chatbox menus)"}.")
        }
        onCommand("duelgridtest", Privilege.ADMIN_POWER, description = "Open the duel rules grid solo (layout test)") {
            DuelRulesScreen.open(player, player) { }
            player.message("Rules grid opened (solo test) — toggles should tick; Decline to exit.")
        }
    }

    // ───────────────────────────── challenge / stake ─────────────────────────────

    private fun challenge(player: Player, target: Player) {
        if (target === player) return
        if (target is PkBot) { player.message("You can't stake against a training bot."); return }
        // Challenges are a safe-zone thing: in the wilderness you just attack them.
        if (!PvpZones.isSafe(player.tile) || !PvpZones.isSafe(target.tile)) {
            player.message("You can't arrange a duel in the wilderness.")
            return
        }
        // No arranging duels from inside other content's instances (Wizard Tower, LMS, raids...) —
        // the accept teleport would rip a player out of that content mid-run.
        if (player.world.instanceAllocator.getMap(player.tile) != null ||
            player.world.instanceAllocator.getMap(target.tile) != null
        ) {
            player.message("You can't arrange a duel from in here.")
            return
        }
        if (busy(player)) { player.message("You're busy at the moment."); return }
        if (busy(target)) { player.message("${target.username} is busy at the moment."); return }

        val myRequests = player.attr[DUEL_REQUESTS_ATTR] ?: HashSet<Player>().also { player.attr[DUEL_REQUESTS_ATTR] = it }
        if (target in myRequests) {
            // They already challenged us → both agreed. Rules come next: the clickable rules-grid
            // interface for BOTH players when enabled (::duelgrid, after cache authoring), otherwise
            // the challenger picks from the chatbox menus.
            myRequests.remove(target)
            (target.attr[DUEL_REQUESTS_ATTR])?.remove(player)
            val onRules: (DuelRules) -> Unit = { rules ->
                target.message("<col=801700>Duel rules agreed: ${rules.summary()}.</col>")
                player.message("<col=801700>Duel rules agreed: ${rules.summary()}.</col>")
                openStake(player, target, rules)
            }
            when {
                // Themed client overlay (safe, default) → cache grid (if verified) → chatbox menus.
                DuelArena.useRulesOverlay -> DuelRulesClientMenu.open(player, target, onRules)
                DuelArena.useRulesGrid -> DuelRulesScreen.open(player, target, onRules)
                else -> player.queue { pickRulesThenStake(player, target) }
            }
        } else {
            (target.attr[DUEL_REQUESTS_ATTR] ?: HashSet<Player>().also { target.attr[DUEL_REQUESTS_ATTR] = it }).add(player)
            player.message("Sending duel challenge...")
            target.message("${player.username}:duelreq:", ChatMessageType.TRADE_REQ, player.username)
        }
    }

    private fun busy(p: Player): Boolean =
        p.getTradeSession() != null || p.isLocked() || DuelArena.duelOf(p) != null ||
            DuelRulesScreen.isOpen(p) || DuelRulesClientMenu.isOpen(p) ||
            org.alter.plugins.content.kits.KitEditor.isOpen(p) ||
            CastleWars.inGame(p) ||
            // Loaner-kit seal: a PK-training kit can't be STAKED (win it back after the restore = smuggled out).
            org.alter.plugins.content.minigames.pktraining.TrainingArena.kitted(p)

    /** Let the challenger pick the rules over two quick menus, then open the stake screen under them.
     *  (Phase 3 replaces these menus with the faithful clickable rules grid.) */
    private suspend fun QueueTask.pickRulesThenStake(player: Player, target: Player) {
        if (busy(target) || !PvpZones.isSafe(target.tile)) { player.message("${target.username} is no longer available."); return }

        // Menu 1 — supplies & movement.
        val supplies = options(player,
            "Anything goes",
            "No prayer",
            "No food & drinks",
            "No prayer, food & drinks",
            "No movement",
        )
        val noPrayer = supplies == 2 || supplies == 4
        val noConsumables = supplies == 3 || supplies == 4
        val noMovement = supplies == 5
        if (busy(target)) { player.message("${target.username} is busy at the moment."); return }

        // Menu 2 — combat & gear.
        val gear = options(player,
            "Any gear",
            "Melee only",
            "Boxing (no gear)",
            "Whip only",
            "DDS only",
            "Fun weapons only",
        )
        var noRanged = false; var noMagic = false
        var disabledSlots = emptySet<Int>(); var allowedWeapons: Set<Int>? = null; var gearLabel: String? = null
        when (gear) {
            2 -> { noRanged = true; noMagic = true; gearLabel = "Melee only" }
            3 -> { disabledSlots = EquipmentType.values.map { it.id }.toSet(); gearLabel = "Boxing" }
            4 -> { allowedWeapons = weaponIds("item.abyssal_whip"); gearLabel = "Whip only" }
            5 -> { allowedWeapons = weaponIds("item.dragon_dagger", "item.dragon_dagger_p", "item.dragon_dagger_p+", "item.dragon_dagger_p++"); gearLabel = "DDS only" }
            6 -> { allowedWeapons = weaponIds("item.rubber_chicken", "item.stale_baguette", "item.giant_frog_legs", "item.mole_slippers", "item.frozen_whip_mix"); gearLabel = "Fun weapons only" }
        }

        // Menu 3 — companions (default: barred; allowed = both parties' companions fight too).
        val allowCompanions = options(player,
            "No companions — a pure 1v1.",
            "Allow companions — bring the party (up to 4v4).",
        ) == 2

        val rules = DuelRules(
            noRanged = noRanged, noMagic = noMagic,
            noPrayer = noPrayer, noFood = noConsumables, noDrinks = noConsumables, noMovement = noMovement,
            allowCompanions = allowCompanions,
            disabledSlots = disabledSlots, allowedWeapons = allowedWeapons, gearLabel = gearLabel,
        )
        if (busy(target)) { player.message("${target.username} is busy at the moment."); return }
        target.message("<col=801700>${player.username} set the duel rules: ${rules.summary()}.</col>")
        openStake(player, target, rules)
    }

    /** Resolve a set of RSCM item names to ids, skipping any that don't exist in the cache. */
    private fun weaponIds(vararg names: String): Set<Int> =
        names.mapNotNull { runCatching { getRSCM(it) }.getOrNull() }.toSet()

    /** True if [slotId] (holding [itemId]) is barred by [rules]: a disabled slot, or a non-allowed weapon. */
    private fun slotDisallowed(rules: DuelRules, slotId: Int, itemId: Int?): Boolean {
        if (slotId in rules.disabledSlots) return true
        if (slotId == EquipmentType.WEAPON.id && rules.allowedWeapons != null && itemId != null && itemId !in rules.allowedWeapons!!) return true
        return false
    }

    /** Force off any worn item a duel's gear rules disallow (so a fight can't START with it). */
    private fun stripDisallowed(p: Player, rules: DuelRules) {
        EquipmentType.values.forEach { type ->
            val item = p.equipment[type.id] ?: return@forEach
            if (slotDisallowed(rules, type.id, item.id)) EquipAction.unequip(p, type.id)
        }
    }

    /** Open the stake screen for both players — a stake-mode [TradeSession] whose completion launches the duel. */
    private fun openStake(a: Player, b: Player, rules: DuelRules) {
        val hook = StakeHook { p, pStake, q, qStake -> begin(p, pStake, q, qStake, rules) }
        val sa = TradeSession(a, b, hook)
        val sb = TradeSession(b, a, hook)
        a.attr[TRADE_SESSION_ATTR] = sa
        b.attr[TRADE_SESSION_ATTR] = sb
        sa.open()
        sb.open()
    }

    // ───────────────────────────── duel lifecycle ─────────────────────────────

    /** Both stakes are locked in (escrow) — allocate a private arena copy and start the duel. */
    private fun begin(a: Player, stakeA: List<Item>, b: Player, stakeB: List<Item>, rules: DuelRules) {
        // One instanced pit PER DUEL (the Wizard-Tower pattern) — the whole server can duel at
        // once without ever sharing an arena. autoDeallocate is OFF because the "owner" is just
        // one of the fighters: their death/logout must not evict the opponent mid-payout. resolve()
        // empties the pit, and the allocator's idle scan then tears it down.
        val instance = RaidInstance.allocate(world, ARENA_SOURCE, exitTile = FALLBACK_EXIT, owner = a.uid, autoDeallocate = false)
        if (instance == null) {
            // Instance space exhausted — hand both stakes straight back; nothing was risked.
            award(a, stakeA)
            award(b, stakeB)
            listOf(a, b).forEach { it.message("<col=801700>Every arena is in use right now — your stake has been returned.</col>") }
            return
        }

        val duel = Duel(a, b, stakeA, stakeB, rules, instance = instance, returnA = a.tile, returnB = b.tile)
        duel.countdown = COUNTDOWN_TICKS
        DuelArena.active += duel

        // Crash-safe: persist each fighter's OWN stake, so a crash refunds it (duel voided) on login.
        a.attr[DUEL_ESCROW_ATTR] = escrowBlob(stakeA)
        b.attr[DUEL_ESCROW_ATTR] = escrowBlob(stakeB)

        a.moveTo(instance.translate(RED_SPAWN))
        b.moveTo(instance.translate(BLUE_SPAWN))
        // Enforce gear rules up front — force off anything the rules disallow before the fight.
        stripDisallowed(a, rules)
        stripDisallowed(b, rules)
        a.setCurrentHp(a.getMaxHp())
        b.setCurrentHp(b.getMaxHp())
        listOf(a, b).forEach {
            it.message("<col=801700>The stakes are set. Rules: ${rules.summary()}. Winner takes all — no forfeit.</col>")
        }
    }

    private fun tick(d: Duel) {
        if (d.a.index < 0 || d.b.index < 0) return // logout handled by onLogout
        if (!d.fighting) {
            d.countdown--
            when (d.countdown) {
                2, 1 -> broadcast(d, "<col=ff0000>${d.countdown}...</col>")
                0 -> {
                    d.fighting = true
                    broadcast(d, "<col=ff0000>FIGHT!</col>")
                    d.a.attack(d.b)
                    d.b.attack(d.a)
                }
            }
        } else if (d.rules.noMovement) {
            // "No movement" — keep both rooted by refreshing the freeze timer every tick (it wears off).
            d.a.timers[FROZEN_TIMER] = 4
            d.b.timers[FROZEN_TIMER] = 4
        }
    }

    /** [loser] died / forfeited — [winner] takes both stakes; tidy up and void the escrow refund. */
    private fun resolve(d: Duel, loser: Player) {
        if (!DuelArena.active.remove(d)) return // already resolved (guard double-fire)
        val winner = d.opponentOf(loser)

        // Clear both escrow backstops FIRST so neither gets a crash-refund on top of the payout.
        d.a.attr.remove(DUEL_ESCROW_ATTR)
        d.b.attr.remove(DUEL_ESCROW_ATTR)

        award(winner, d.stakes)
        winner.setCurrentHp(winner.getMaxHp())
        // Both fighters go back to the tiles they were standing on when the stake locked in.
        // The loser's death already dropped them at the instance's fallback exit (or the logout
        // path moved them there) — this override lands them home; emptying the pit also lets the
        // allocator's idle scan reclaim the instance.
        if (winner.index >= 0) winner.moveTo(d.returnTileOf(winner))
        if (loser.index >= 0) loser.moveTo(d.returnTileOf(loser))
        winner.message("<col=007f00>You won the duel and claimed the stake!</col>")
        if (loser.index >= 0) loser.message("<col=ff0000>You lost the duel — your stake is gone.</col>")
        logger.info { "DUEL winner=${winner.username} loser=${loser.username} pot=${d.stakes.sumOf { it.amount.toLong() }} items" }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Give [items] to [p], overflowing to the bank when the inventory is full. */
    private fun award(p: Player, items: List<Item>) {
        items.forEach { item ->
            val added = p.inventory.add(item = item.id, amount = item.amount, assureFullInsertion = false).completed
            if (added < item.amount) p.bank.add(item.id, item.amount - added)
        }
    }

    private fun broadcast(d: Duel, msg: String) {
        if (d.a.index >= 0) d.a.message(msg)
        if (d.b.index >= 0) d.b.message(msg)
    }

    private fun escrowBlob(items: List<Item>): String {
        val doc = Document()
        items.forEachIndexed { i, it -> doc.append(i.toString(), Document("id", it.id).append("amount", it.amount)) }
        return doc.toJson()
    }

    private fun itemsFromBlob(blob: String): List<Item> = runCatching {
        Document.parse(blob).values.mapNotNull { v ->
            (v as? Document)?.let { Item(it.getInteger("id"), it.getInteger("amount", 1)) }
        }
    }.getOrDefault(emptyList())

    private companion object {
        /** Player right-click slot for "Challenge" (2..5 = Attack/Follow/Trade/Report; 6 is free). */
        const val CHALLENGE_OPTION_SLOT = 6

        const val COUNTDOWN_TICKS = 3

        // Source of each duel's private arena copy: the NORTH-EAST Duel-Arena pit (mapdump-verified:
        // region 13362, pit interior x3370..3389 z3246..3258, row z3251 fully clear). Chunk-aligned
        // 3x3-chunk box so the pit's enclosing walls are copied with it — inside the instance the
        // walls (and the void beyond them) keep the fighters in the pit.
        val ARENA_SOURCE = Area(3368, 3240, 3391, 3263)
        // Spawn tiles in SOURCE coordinates — translated into the allocated instance per duel.
        val RED_SPAWN = Tile(3374, 3251, 0)
        val BLUE_SPAWN = Tile(3382, 3251, 0)
        // The instance's exit tile: the old arena lobby, near the trainer (mapdump-verified
        // walkable). Only a fallback — resolve() sends both fighters back to their return tiles;
        // this catches the odd path (death/logout placement, allocator teardown sweep).
        val FALLBACK_EXIT = Tile(3367, 3274, 0)
    }
}

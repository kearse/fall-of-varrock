package org.alter.plugins.content.minigames.duel

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ChatMessageType
import org.alter.api.EquipmentType
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.action.EquipAction
import org.alter.game.info.PlayerInfo
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.DUEL_ESCROW_ATTR
import org.alter.game.model.attr.POISON_TICKS_LEFT_ATTR
import org.alter.game.model.container.ContainerStackType
import org.alter.game.model.container.ItemContainer
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
import org.alter.plugins.content.combat.Vengeance
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.items.consumables.potions.PotionsPlugin
import org.alter.plugins.content.mechanics.poison.Poison
import org.alter.plugins.content.mechanics.prayer.Prayers
// Aliased: this class has its own `companion object`, which would shadow a bare Companion import
// (the always-false `is Companion` bug from BotCombatPlugin). The alias is unambiguous.
import org.alter.plugins.content.companion.Companion as CompanionPawn
import org.alter.plugins.content.companion.CompanionRegistry
import org.alter.plugins.content.mechanics.trading.TRADE_SESSION_ATTR
import org.alter.plugins.content.mechanics.trading.getTradeSession
import org.alter.plugins.service.marketvalue.ItemMarketValueService
import org.alter.plugins.content.mechanics.trading.impl.StakeHook
import org.alter.plugins.content.mechanics.trading.impl.TradeSession
import org.alter.plugins.content.minigames.castlewars.CastleWars
import org.alter.plugins.content.raids.RaidInstance
import org.bson.Document

private val logger = KotlinLogging.logger {}

/** Pending duel challenges sent TO this player (by identity), mirroring the trade-request pattern. */
val DUEL_REQUESTS_ATTR = AttributeKey<MutableSet<Player>>()

// Per-player duel record (persisted → surfaces on the website profile beside the PK stats,
// which read the same details-blob attribute path). Staked duels only — exhibition/tournament
// matches never touch these.
val DUEL_WINS_ATTR = AttributeKey<Int>(persistenceKey = "duel_wins")
val DUEL_LOSSES_ATTR = AttributeKey<Int>(persistenceKey = "duel_losses")
val DUEL_DRAWS_ATTR = AttributeKey<Int>(persistenceKey = "duel_draws")
val DUEL_BIGGEST_POT_ATTR = AttributeKey<Long>(persistenceKey = "duel_biggest_pot")

/**
 * Return tile owed to a fighter whose duel resolved while their own death sequence was still in
 * flight (the double-KO second faller): their respawn lands them on the instance's fallback exit
 * AFTER our resolve teleport, so resolve parks the tile here and the post-death hook applies it.
 */
val DUEL_RETURN_ATTR = AttributeKey<Tile>()

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
            // Clear any stale rules/confirm-overlay state (transient signals must never persist
            // across login).
            player.setVarp(DuelRulesClientMenu.STATE_VARP, 0)
            player.setVarp(DuelConfirmScreen.STATE_VARP, 0)
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
        // weapon / 2H under a weapon-or-shield restriction), and holding the weapon slot to its
        // FIGHT!-moment contents under No Weapon Switch. onEquipToSlot is multi-bind, so this
        // stacks cleanly with the other equip handlers. The handler runs synchronously inside
        // EquipAction.equip, so a reverted equip can never land a same-tick hit (the one-tick
        // DDS-spec switch this rule exists to kill).
        EquipmentType.values.forEach { type ->
            onEquipToSlot(type.id) {
                val d = DuelArena.duelOf(player) ?: return@onEquipToSlot
                val item = player.equipment[type.id]
                if (item != null && d.rules.barsWorn(type.id, item.id)) {
                    EquipAction.unequip(player, type.id)
                    player.message("You can't wear that in this duel.")
                    return@onEquipToSlot
                }
                // No Weapon Switch: the weapon slot must keep its FIGHT! contents. A shield equip
                // can break the lock too — it silently unequips a locked 2H.
                if (type.id == EquipmentType.WEAPON.id || type.id == EquipmentType.SHIELD.id) {
                    enforceWeaponLock(player, d, revertSlot = type.id)
                }
            }
        }

        onWorldInit { world.timers[duelTimer] = 1 }
        onTimer(duelTimer) {
            DuelArena.active.toList().forEach { d ->
                runCatching { tick(d) }.onFailure { e -> logger.error(e) { "duel tick failed" } }
            }
            // Confirmation-screen "Wait…" bits expire here (the same every-tick heartbeat).
            runCatching { DuelConfirmScreen.tick(world) }.onFailure { e -> logger.error(e) { "duel confirm tick failed" } }
            world.timers[duelTimer] = 1
        }

        // Double-KO detection. The pre-death hook fires at the START of the death sequence, while
        // both fallers are still at 0 HP — the only window where "we died together" is observable
        // order-independently. The post-death hook (below) fires AFTER the death animation and
        // respawn, by which point the other faller may already be respawned and healed.
        onPlayerPreDeath {
            if (player is CompanionPawn) return@onPlayerPreDeath
            DuelArena.duelOf(player)?.let { d ->
                if (!d.exhibition && d.opponentOf(player).isDead()) d.drawPending = true
            }
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
                return@onPlayerDeath
            }
            // A duel that resolved while this death was mid-animation parked our return tile —
            // apply it now that the respawn (which lands on the instance fallback exit) is done.
            player.attr[DUEL_RETURN_ATTR]?.let { tile ->
                player.attr.remove(DUEL_RETURN_ATTR)
                player.moveTo(tile)
            }
            // A principal's death resolves the duel in ANY phase — a countdown death (lingering
            // poison etc.) must not leave the duel hanging with one fighter gone. A double KO
            // (both principals' deaths overlapped) is a draw, not a processing-order coin flip.
            DuelArena.duelOf(player)?.let { d ->
                if (d.drawPending) resolveDraw(d, "Both fighters fell", DuelLog.DRAW_DOUBLE_KO)
                else resolve(d, loser = player)
            }
        }

        // Logging out mid-duel forfeits the stake to the opponent (a pre-fight stake screen is
        // declined by the trade plugin's own onLogout, which safely returns the un-committed items).
        onLogout {
            if (DuelRulesScreen.isOpen(player)) DuelRulesScreen.cancel(player)
            if (DuelRulesClientMenu.isOpen(player)) DuelRulesClientMenu.cancel(player)
            DuelArena.duelOf(player)?.let { d -> resolve(d, loser = player, end = DuelEnd.LOGOUT) }
        }

        // The scoreboard, read from anywhere: recent staked results + your own record — the
        // classic arena wall boards, virtualized (DuelLog; the site's /duels page is the
        // durable version).
        onCommand("duels", description = "Recent duel results and your record") {
            player.message("<col=801700>-- Duel Arena scoreboard --</col>")
            val rows = DuelLog.recent(SCOREBOARD_ROWS)
            if (rows.isEmpty()) {
                player.message("No staked duels since the last restart. See the website for the full history.")
            }
            rows.forEach { e ->
                val line = when (e.end) {
                    DuelLog.DRAW_TIMEOUT, DuelLog.DRAW_DOUBLE_KO -> "${e.winner} drew with ${e.loser}"
                    DuelLog.END_FORFEIT -> "${e.winner} beat ${e.loser} (forfeit)"
                    DuelLog.END_LOGOUT -> "${e.winner} beat ${e.loser} (fled)"
                    else -> "${e.winner} beat ${e.loser}"
                }
                val pot = if (e.potValue > 0) " — ${"%,d".format(e.potValue)} coins" else ""
                player.message("$line$pot <col=7f7f7f>(${ago(e.at)})</col>")
            }
            val w = player.attr[DUEL_WINS_ATTR] ?: 0
            val l = player.attr[DUEL_LOSSES_ATTR] ?: 0
            val dr = player.attr[DUEL_DRAWS_ATTR] ?: 0
            val big = player.attr[DUEL_BIGGEST_POT_ATTR] ?: 0L
            val bigText = if (big > 0) " Biggest pot won: ${"%,d".format(big)} coins." else ""
            player.message("<col=801700>Your record:</col> $w-$l-$dr (W-L-D).$bigText")
        }

        // The classic forfeit (the arena trapdoors, as a command until the duel overlay grows a
        // button): concede the fight and hand the whole stake to your opponent. Denied by the
        // No Forfeit rule and during the countdown, exactly like the original.
        onCommand("forfeit", description = "Forfeit your duel — your opponent takes the stake") {
            val d = DuelArena.duelOf(player)
            when {
                d == null -> player.message("You're not in a duel.")
                d.exhibition -> player.message("You can't forfeit a tournament match.")
                !d.fighting -> player.message("The duel hasn't started yet.")
                d.rules.noForfeit -> player.message("The No Forfeit rule is set — this duel is to the death.")
                else -> resolve(d, loser = player, end = DuelEnd.FORFEIT)
            }
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

        // Obstacle-pit live verification (the source area can't be mapdump-checked from the build
        // environment): walk the pit with ::duelpittest — confirm walls enclose it, both spawn
        // tiles are clear, and obstacles actually block pathing/sight — then open the rule with
        // ::duelobstacles. See DuelArena.obstaclesOpen.
        onCommand("duelobstacles", Privilege.ADMIN_POWER, description = "Open/close the Obstacles duel rule (after verifying the pit)") {
            DuelArena.obstaclesOpen = !DuelArena.obstaclesOpen
            player.message("Obstacle duels: ${if (DuelArena.obstaclesOpen) "OPEN" else "CLOSED"}.")
        }
        onCommand("duelpittest", Privilege.ADMIN_POWER, description = "Teleport into a test copy of the obstacle pit") {
            val instance = RaidInstance.allocate(world, OBSTACLE_SOURCE, exitTile = FALLBACK_EXIT, owner = player.uid, autoDeallocate = true)
            if (instance == null) {
                player.message("No instance space free right now.")
            } else {
                player.moveTo(instance.translate(OB_RED_SPAWN))
                player.message("Obstacle pit test copy. Spawns: ${OB_RED_SPAWN.x},${OB_RED_SPAWN.z} and ${OB_BLUE_SPAWN.x},${OB_BLUE_SPAWN.z} (source coords). ::home to leave.")
            }
        }
    }

    // ───────────────────────────── challenge / stake ─────────────────────────────

    private fun challenge(player: Player, target: Player) {
        if (target === player) return
        // Challenging YOUR OWN companion isn't a stake — it opens COMPANION SPARRING (the PK
        // training arena's settings flow: NH style, difficulty, rules, loadouts, then a private
        // pit). Other people's companions and generic bots keep the rejection below.
        if (target is CompanionPawn &&
            org.alter.plugins.content.companion.CompanionRegistry.owns(player, target)
        ) {
            org.alter.plugins.content.minigames.pktraining.CompanionSparring.requestSetup(player, target)
            return
        }
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
            // Type 101 (TRADE_REQ) renders the message verbatim, so send readable text — the
            // shipped client routes "wishes to duel" lines to the Challenge op (lofchatrequests
            // plugin) instead of the Trade op the engine would otherwise send for this type.
            target.message("${player.username} wishes to duel with you.", ChatMessageType.TRADE_REQ, player.username)
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
        var noRanged = false; var noMagic = false; var funWeapons = false
        var disabledSlots = emptySet<Int>(); var allowedWeapons: Set<Int>? = null; var gearLabel: String? = null
        when (gear) {
            2 -> { noRanged = true; noMagic = true; gearLabel = "Melee only" }
            3 -> { disabledSlots = EquipmentType.values.map { it.id }.toSet(); gearLabel = "Boxing" }
            4 -> { allowedWeapons = DuelRules.WHIP_WEAPONS; gearLabel = "Whip only" }
            5 -> { allowedWeapons = DuelRules.DDS_WEAPONS; gearLabel = "DDS only" }
            6 -> { funWeapons = true; allowedWeapons = DuelRules.FUN_WEAPONS; gearLabel = "Fun weapons" }
        }
        if (busy(target)) { player.message("${target.username} is busy at the moment."); return }

        // Menu 3 — the 2016-era fairness toggles.
        val extras = options(player,
            "No extra restrictions",
            "No special attacks",
            "No weapon switching",
            "No specials & no switching",
        )
        val noSpec = extras == 2 || extras == 4
        val noWeaponSwitch = extras == 3 || extras == 4

        // Menu 4 — companions (default: barred; allowed = both parties' companions fight too).
        val allowCompanions = options(player,
            "No companions — a pure 1v1.",
            "Allow companions — bring the party (up to 4v4).",
        ) == 2

        val rules = DuelRules(
            noRanged = noRanged, noMagic = noMagic,
            noPrayer = noPrayer, noFood = noConsumables, noDrinks = noConsumables, noMovement = noMovement,
            noWeaponSwitch = noWeaponSwitch, noSpec = noSpec, funWeapons = funWeapons,
            allowCompanions = allowCompanions,
            disabledSlots = disabledSlots, allowedWeapons = allowedWeapons, gearLabel = gearLabel,
        )
        // The menus only offer coherent packages, but the single winnability gate runs anyway —
        // every rules entry path goes through DuelRules.validate() (see docs plan, Phase 1).
        rules.validate()?.let { err -> player.message(err); return }
        if (busy(target)) { player.message("${target.username} is busy at the moment."); return }
        target.message("<col=801700>${player.username} set the duel rules: ${rules.summary()}.</col>")
        openStake(player, target, rules)
    }

    /** The worn items [rules] would strip from [p] at duel start — the count the stake screen's
     *  inventory-space check verifies against free backpack slots. */
    private fun strippedGear(p: Player, rules: DuelRules): List<Int> =
        EquipmentType.values.mapNotNull { type ->
            p.equipment[type.id]?.takeIf { rules.barsWorn(type.id, it.id) }?.let { type.id }
        }

    /**
     * Force off every worn item a duel's gear rules disallow, so a fight can never START with it.
     * The stake screen already verified backpack space at accept; if the player somehow filled up
     * since (or a path skipped the vet), the item goes to the BANK rather than staying worn —
     * a disallowed piece remaining equipped would let gear rules be dodged outright.
     */
    private fun stripDisallowed(p: Player, rules: DuelRules) {
        strippedGear(p, rules).forEach { slotId ->
            if (EquipAction.unequip(p, slotId) != EquipAction.Result.SUCCESS) {
                p.equipment[slotId]?.let { item ->
                    p.equipment[slotId] = null
                    p.calculateBonuses()
                    PlayerInfo(p).syncAppearance()
                    EquipAction.onItemUnequip(p, item.id, slotId)
                    p.bank.add(item.id, item.amount)
                    p.message("<col=801700>Your ${item.getName()} didn't fit in your backpack — it has been banked.</col>")
                }
            }
        }
    }

    /**
     * No Weapon Switch: hold [p]'s weapon slot to the weapon captured at FIGHT!. Runs inside the
     * equip handlers (synchronously — see the onEquipToSlot binding) with [revertSlot] naming the
     * slot whose fresh equip broke the lock (the weapon itself, or a shield that displaced a
     * locked 2H), and from the duel tick as a backstop with no slot to revert (a plain unequip).
     */
    private fun enforceWeaponLock(p: Player, d: Duel, revertSlot: Int? = null) {
        if (!d.fighting || !d.rules.noWeaponSwitch) return
        val locked = d.lockedWeaponOf(p)
        val weaponSlot = EquipmentType.WEAPON.id
        if (p.equipment[weaponSlot]?.id == locked) return
        revertSlot?.let {
            EquipAction.unequip(p, it)
            p.message("You can't switch weapons in this duel.")
        }
        // Put the locked weapon back in hand if it's sitting in the backpack (it is, whenever a
        // swap or unequip displaced it — a thrown weapon running out is the one way it can't be).
        if (locked != null && p.equipment[weaponSlot]?.id != locked) {
            val index = p.inventory.getItemIndex(locked, skipAttrItems = false)
            if (index != -1) p.inventory[index]?.let { EquipAction.equip(p, it, index) }
        }
    }

    /** Open the stake screen for both players — a stake-mode [TradeSession] whose completion launches the duel. */
    private fun openStake(a: Player, b: Player, rules: DuelRules) {
        val hook = StakeHook { p, pStake, q, qStake -> begin(p, pStake, q, qStake, rules) }
        // The classic accept-time space check: gear these rules strip at duel start must fit the
        // backpack (as it'll be once the stake is committed), or the stake can't be confirmed.
        val vet: (Player, Int) -> String? = { p, freeSlots ->
            val stripped = strippedGear(p, rules).size
            if (stripped > freeSlots) {
                "This duel's rules would remove $stripped worn item${if (stripped == 1) "" else "s"} " +
                    "but you only have $freeSlots free backpack space${if (freeSlots == 1) "" else "s"} — make room first."
            } else null
        }
        // Confirm-screen edges: raise/lower the themed confirmation overlay (varp + opponent
        // stats), refreshing the Show-Inventories gear snapshot on the way up.
        val confirm: (Player, Boolean) -> Unit = { p, openNow ->
            val opponent = if (p === a) b else a
            if (openNow) {
                DuelConfirmScreen.open(p, opponent, rules)
                if (rules.showInventories) pushOpponentGear(p, opponent)
            } else {
                DuelConfirmScreen.close(p)
                clearOpponentGear(p)
            }
        }
        val sa = TradeSession(a, b, hook, vet, confirm)
        val sb = TradeSession(b, a, hook, vet, confirm)
        a.attr[TRADE_SESSION_ATTR] = sa
        b.attr[TRADE_SESSION_ATTR] = sb
        sa.open()
        sb.open()
        // Show Inventories: each side sees the other's backpack + worn gear from the FIRST stake
        // screen on (identities, not quantities — the masked containers).
        if (rules.showInventories) {
            pushOpponentGear(a, b)
            pushOpponentGear(b, a)
        }
    }

    /** Send [opponent]'s backpack + worn gear to [viewer] under the Show-Inventories container
     *  keys, stack quantities masked to 1 (the classic "items visible, amounts hidden"). */
    private fun pushOpponentGear(viewer: Player, opponent: Player) {
        viewer.sendItemContainer(DuelConfirmScreen.OPP_BACKPACK_KEY, masked(opponent.inventory))
        viewer.sendItemContainer(DuelConfirmScreen.OPP_WORN_KEY, masked(opponent.equipment))
    }

    /** Blank both Show-Inventories containers for [viewer] (the client hides its panel on empty). */
    private fun clearOpponentGear(viewer: Player) {
        if (viewer.index < 0) return
        viewer.sendItemContainer(DuelConfirmScreen.OPP_BACKPACK_KEY, ItemContainer(viewer.inventory.capacity, ContainerStackType.NORMAL))
        viewer.sendItemContainer(DuelConfirmScreen.OPP_WORN_KEY, ItemContainer(viewer.equipment.capacity, ContainerStackType.NORMAL))
    }

    private fun masked(source: ItemContainer): ItemContainer {
        val copy = ItemContainer(source.capacity, ContainerStackType.NORMAL)
        for (i in 0 until source.capacity) {
            source[i]?.let { copy[i] = Item(it.id, 1) }
        }
        return copy
    }

    // ───────────────────────────── duel lifecycle ─────────────────────────────

    /** Both stakes are locked in (escrow) — allocate a private arena copy and start the duel. */
    private fun begin(a: Player, stakeA: List<Item>, b: Player, stakeB: List<Item>, rules: DuelRules) {
        // The validation belt: every rules entry path calls validate() at accept time, but the
        // stakes lock in HERE — an unfightable combo slipping through would trap two stakes in a
        // stalemate, so refuse and refund rather than trust the screens.
        rules.validate()?.let { err ->
            award(a, stakeA)
            award(b, stakeB)
            listOf(a, b).forEach { it.message("<col=801700>The duel can't start: $err Your stake has been returned.</col>") }
            return
        }
        // One instanced pit PER DUEL (the Wizard-Tower pattern) — the whole server can duel at
        // once without ever sharing an arena. The Obstacles rule swaps in the obstacle pit's
        // source area (classic: which pit you get is decided solely by that rule). autoDeallocate
        // is OFF because the "owner" is just one of the fighters: their death/logout must not
        // evict the opponent mid-payout. resolve() empties the pit, and the allocator's idle scan
        // then tears it down.
        val source = if (rules.obstacles) OBSTACLE_SOURCE else ARENA_SOURCE
        val instance = RaidInstance.allocate(world, source, exitTile = FALLBACK_EXIT, owner = a.uid, autoDeallocate = false)
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

        // Classic spawn shape: No-Movement duels start on ADJACENT tiles (so melee always
        // connects — the original's fix for the rooted-stalemate scam); obstacle duels use the
        // obstacle pit's own spawns (No Movement + Obstacles can't combine — validate()); and
        // everything else starts the fighters apart.
        val (spawnA, spawnB) = when {
            rules.noMovement -> NM_RED_SPAWN to NM_BLUE_SPAWN
            rules.obstacles -> OB_RED_SPAWN to OB_BLUE_SPAWN
            else -> RED_SPAWN to BLUE_SPAWN
        }
        a.moveTo(instance.translate(spawnA))
        b.moveTo(instance.translate(spawnB))
        a.facePawn(b)
        b.facePawn(a)
        // Enforce gear rules up front — force off anything the rules disallow before the fight.
        stripDisallowed(a, rules)
        stripDisallowed(b, rules)
        // The classic level playing field: both fighters enter fully normalized — no pre-potted
        // boosts, pre-charged specs, loaded Vengeance, ticking poison or divine re-boost timers.
        normalizeCombatState(a)
        normalizeCombatState(b)
        val forfeitLine = if (rules.noForfeit) "No forfeit — this one's to the death!"
        else "Type ::forfeit to concede (your opponent takes the stake)."
        listOf(a, b).forEach {
            it.message("<col=801700>The stakes are set. Rules: ${rules.summary()}. Winner takes all. $forfeitLine</col>")
        }
    }

    /**
     * The classic duel-start/duel-end reset — both fighters on equal footing, nothing carried in
     * or out: boosted AND drained stats to base (includes full HP), prayers stopped, special
     * attack to 100%, run energy full, poison/venom cured, a loaded Vengeance cleared, a queued
     * spec un-armed, and a divine potion's recurring re-boost cancelled (a plain stat reset would
     * silently re-boost 15s later). Also the "hospital treatment" at duel end. In the companion
     * so the tournament's exhibition matches apply the same reset at match start (it previously
     * reset only HP + spec energy — lobby prayers/poison/Vengeance could ride into round one).
     */
    private fun normalizeCombatState(p: Player) = Companion.normalizeCombatState(p)

    private fun tick(d: Duel) {
        if (d.a.index < 0 || d.b.index < 0) return // logout handled by onLogout
        if (d.rules.noMovement) {
            // "No movement" — rooted from the moment they land on the adjacent spawns, countdown
            // included (a step apart during the count would re-create the melee stalemate), by
            // refreshing the freeze timer every tick (it wears off).
            d.a.timers[FROZEN_TIMER] = 4
            d.b.timers[FROZEN_TIMER] = 4
        }
        if (d.fighting && d.rules.noWeaponSwitch) {
            // Weapon-lock backstop for the one path the equip handlers can't revert: a plain
            // unequip-to-fists (re-equipping mid-unequip would corrupt EquipAction's swap flow,
            // so the lock is restored here, on the next tick instead).
            enforceWeaponLock(d.a, d)
            enforceWeaponLock(d.b, d)
        }
        if (!d.fighting) {
            d.countdown--
            when (d.countdown) {
                3, 2, 1 -> {
                    // Classic presentation: the count is called overhead by both fighters.
                    overhead(d, "${d.countdown}")
                    broadcast(d, "<col=ff0000>${d.countdown}...</col>")
                }
                0 -> {
                    d.fighting = true
                    // No Weapon Switch: what's in hand at FIGHT! is the weapon for the whole duel.
                    if (d.rules.noWeaponSwitch) d.lockWeapons(EquipmentType.WEAPON.id)
                    // Classic presentation: a hint arrow over your opponent for the whole fight.
                    if (d.a.index >= 0) d.a.setPlayerHintArrow(d.b.index)
                    if (d.b.index >= 0) d.b.setPlayerHintArrow(d.a.index)
                    overhead(d, "FIGHT!")
                    broadcast(d, "<col=ff0000>FIGHT!</col>")
                    // Fair opener: who swings first is a coin flip, not challenger-always-first.
                    // (Within the fight, the engine's OSRS-style PID shuffle owns tick order.)
                    val (first, second) = if (world.random(1) == 0) d.a to d.b else d.b to d.a
                    first.attack(d.opponentOf(first))
                    second.attack(d.opponentOf(second))
                }
            }
            return
        }
        // Classic 15-minute limit: a staked duel nobody can finish is called a draw, both stakes
        // refunded — the escape valve for turtled fights. Exhibition (tournament) matches keep
        // their own lifecycle.
        if (!d.exhibition) {
            d.fightTicks++
            if (d.fightTicks == DRAW_WARN_TICK) {
                broadcast(d, "<col=ff0000>One minute remains — if nobody falls, the duel is a draw.</col>")
            }
            if (d.fightTicks >= DRAW_TICK) {
                resolveDraw(d, "Time is up", DuelLog.DRAW_TIMEOUT)
                return
            }
        }
    }

    /** [loser] died / forfeited / fled — [winner] takes both stakes; tidy up and void the escrow refund. */
    private fun resolve(d: Duel, loser: Player, end: DuelEnd = DuelEnd.DEATH) {
        if (!DuelArena.active.remove(d)) return // already resolved (guard double-fire)
        val winner = d.opponentOf(loser)

        // Clear both escrow backstops FIRST so neither gets a crash-refund on top of the payout.
        d.a.attr.remove(DUEL_ESCROW_ATTR)
        d.b.attr.remove(DUEL_ESCROW_ATTR)

        if (!d.exhibition) award(winner, d.stakes)
        // Send home FIRST, restore second: sendHome() keys off isDead() to spot a death sequence
        // still in flight, and the restore below heals — reversing the order would erase that
        // signal and strand the second faller of a double-KO on the fallback exit.
        // Both fighters go back to the tiles they were standing on when the stake locked in;
        // emptying the pit also lets the allocator's idle scan reclaim the instance.
        sendHome(winner, d)
        sendHome(loser, d)
        // The hospital treatment: both fighters leave fully restored (stats, prayer, spec, run
        // energy, poison) — classic duel-end behaviour, and it doubles as buff cleanup.
        listOf(winner, loser).forEach { p ->
            if (p.index >= 0) {
                normalizeCombatState(p)
                p.resetFacePawn()
                p.clearHintArrow()
            }
        }
        if (!d.exhibition) {
            when (end) {
                DuelEnd.DEATH -> {
                    // The classic victory line, then the spoils summary below.
                    winner.message("<col=007f00>Well done! You have defeated ${loser.username}!</col>")
                    if (loser.index >= 0) loser.message("<col=ff0000>You lost the duel — your stake is gone.</col>")
                }
                DuelEnd.FORFEIT -> {
                    winner.message("<col=007f00>${loser.username} forfeits — you claim the stake!</col>")
                    if (loser.index >= 0) loser.message("<col=ff0000>You forfeit the duel — the stake goes to ${winner.username}.</col>")
                }
                DuelEnd.LOGOUT ->
                    winner.message("<col=007f00>${loser.username} fled the duel — you claim the stake!</col>")
            }
            val pot = potValue(d.stakes)
            sendSpoils(winner, loser, d.stakes, pot)
            // Scoreboard + per-player record. The loser's attrs still persist on a LOGOUT end —
            // logout handlers (which is where that resolve comes from) run before the save.
            DuelLog.record(
                winner, loser,
                when (end) {
                    DuelEnd.DEATH -> DuelLog.END_DEATH
                    DuelEnd.FORFEIT -> DuelLog.END_FORFEIT
                    DuelEnd.LOGOUT -> DuelLog.END_LOGOUT
                },
                d.rules, pot,
            )
            winner.attr[DUEL_WINS_ATTR] = (winner.attr[DUEL_WINS_ATTR] ?: 0) + 1
            if (pot > (winner.attr[DUEL_BIGGEST_POT_ATTR] ?: 0L)) winner.attr[DUEL_BIGGEST_POT_ATTR] = pot
            loser.attr[DUEL_LOSSES_ATTR] = (loser.attr[DUEL_LOSSES_ATTR] ?: 0) + 1
        }
        logger.info { "DUEL winner=${winner.username} loser=${loser.username} end=$end exhibition=${d.exhibition} pot=${d.stakes.sumOf { it.amount.toLong() }} items" }
        d.onResolved?.invoke(winner, loser)
        d.onResolved = null
    }

    /**
     * The duel produced no winner — a double KO (both principals' deaths overlapped) or the
     * 15-minute limit. Each side gets their OWN stake back; nothing changes hands. A side that
     * is offline keeps their [DUEL_ESCROW_ATTR] so the login crash-refund path pays them instead.
     * Never called for exhibition duels ([Duel.drawPending] and the timer both guard on it).
     */
    private fun resolveDraw(d: Duel, why: String, endKind: String) {
        if (!DuelArena.active.remove(d)) return // already resolved (guard double-fire)
        listOf(d.a to d.stakeA, d.b to d.stakeB).forEach { (p, ownStake) ->
            if (p.index >= 0) {
                p.attr.remove(DUEL_ESCROW_ATTR)
                award(p, ownStake)
                sendHome(p, d) // BEFORE the restore — it keys off isDead() (see resolve())
                normalizeCombatState(p)
                p.resetFacePawn()
                p.clearHintArrow()
                p.message("<col=801700>$why — the duel is a draw. Your stake has been returned.</col>")
                p.attr[DUEL_DRAWS_ATTR] = (p.attr[DUEL_DRAWS_ATTR] ?: 0) + 1
            }
            // Offline side: escrow attr stays put → their stake refunds on next login.
        }
        DuelLog.recordDraw(d.a, d.b, endKind, d.rules, potValue(d.stakes))
        logger.info { "DUEL DRAW a=${d.a.username} b=${d.b.username} why=\"$why\" pot=${d.stakes.sumOf { it.amount.toLong() }} items" }
        d.onResolved = null
    }

    /**
     * Return [p] to their pre-duel tile. A fighter whose death sequence is still in flight can't
     * be moved now — their respawn (which runs after us) would override the teleport with the
     * instance's fallback exit — so the tile is parked on [DUEL_RETURN_ATTR] and the post-death
     * hook applies it once the respawn is done.
     */
    private fun sendHome(p: Player, d: Duel) {
        if (p.index < 0) return
        if (p.isDead()) p.attr[DUEL_RETURN_ATTR] = d.returnTileOf(p)
        else p.moveTo(d.returnTileOf(p))
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Market value of a stake pot (falls back to cache cost when no price service is up). */
    private fun potValue(spoils: List<Item>): Long {
        val prices = world.getService(ItemMarketValueService::class.java)
        return spoils.sumOf { ((prices?.get(it.id) ?: it.getDef().cost ?: 0).toLong()) * it.amount }
    }

    /**
     * The classic winnings presentation (interface 372's spoils list, as a chat block until an
     * overlay panel exists): who fell, what was won, and what it's worth. Long stakes list the
     * first few items and summarize the rest — the full pot is already in the winner's backpack.
     */
    private fun sendSpoils(winner: Player, loser: Player, spoils: List<Item>, total: Long) {
        if (winner.index < 0) return
        if (spoils.isEmpty()) {
            winner.message("Nothing was staked — honour only.")
            return
        }
        val named = spoils.take(SPOILS_LISTED).joinToString(", ") { item ->
            if (item.amount == 1) item.getName() else "${item.getName()} x${"%,d".format(item.amount)}"
        }
        val more = spoils.size - SPOILS_LISTED
        val suffix = if (more > 0) " +$more more" else ""
        winner.message(
            "<col=007f00>Winnings from ${loser.username} (level ${loser.combatLevel}): " +
                "$named$suffix — value ${"%,d".format(total)} coins.</col>",
        )
    }

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

    /** Both fighters call [text] overhead (the classic countdown presentation). */
    private fun overhead(d: Duel, text: String) {
        if (d.a.index >= 0) d.a.forceChat(text)
        if (d.b.index >= 0) d.b.forceChat(text)
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

    // Public: the tournament runs its matches in copies of the same pit (ARENA_SOURCE/spawns).
    companion object {
        /** Player right-click slot for "Challenge" (2..5 = Attack/Follow/Trade/Report; 6 is free). */
        const val CHALLENGE_OPTION_SLOT = 6

        /** 4 ticks → the full classic "3", "2", "1", "FIGHT!" call (one step per tick). */
        const val COUNTDOWN_TICKS = 4

        private const val MAX_RUN_ENERGY = 10000.0

        // Classic 15-minute draw limit on staked duels (1,500 ticks), with a one-minute warning.
        const val DRAW_TICK = 1500
        const val DRAW_WARN_TICK = DRAW_TICK - 100

        /** Items named individually in the winnings chat block before "+n more". */
        const val SPOILS_LISTED = 5

        /** Rows the ::duels chat scoreboard shows. */
        const val SCOREBOARD_ROWS = 8

        /** See the instance-side doc — shared with the tournament's match starter. */
        fun normalizeCombatState(p: Player) {
            p.getSkills().restoreAll()
            Prayers.deactivateAll(p)
            AttackTab.setEnergy(p, 100)
            p.setVarp(AttackTab.SPECIAL_ATTACK_VARP, 0)
            PotionsPlugin.cancelDivineReboost(p)
            p.runEnergy = MAX_RUN_ENERGY
            p.sendRunEnergy(p.runEnergy.toInt())
            if ((p.attr[POISON_TICKS_LEFT_ATTR] ?: 0) > 0) {
                p.attr[POISON_TICKS_LEFT_ATTR] = 0
                Poison.setHpOrb(p, Poison.OrbState.NONE)
            }
            p.attr.remove(Vengeance.ACTIVE)
        }

        /** Rough "how long ago" for scoreboard rows. */
        private fun ago(at: Long): String {
            val mins = (System.currentTimeMillis() - at) / 60_000
            return when {
                mins < 1 -> "just now"
                mins < 60 -> "${mins}m ago"
                mins < 60 * 24 -> "${mins / 60}h ago"
                else -> "${mins / (60 * 24)}d ago"
            }
        }

        // Source of each duel's private arena copy: the NORTH-EAST Duel-Arena pit (mapdump-verified:
        // region 13362, pit interior x3370..3389 z3246..3258, row z3251 fully clear). Chunk-aligned
        // 3x3-chunk box so the pit's enclosing walls are copied with it — inside the instance the
        // walls (and the void beyond them) keep the fighters in the pit.
        val ARENA_SOURCE = Area(3368, 3240, 3391, 3263)
        // Spawn tiles in SOURCE coordinates — translated into the allocated instance per duel.
        val RED_SPAWN = Tile(3374, 3251, 0)
        val BLUE_SPAWN = Tile(3382, 3251, 0)
        // No-Movement duels start ADJACENT (centre of the same clear row) so melee always
        // connects — spread spawns + rooted fighters would be an unwinnable stalemate.
        val NM_RED_SPAWN = Tile(3377, 3251, 0)
        val NM_BLUE_SPAWN = Tile(3378, 3251, 0)
        // The instance's exit tile: the old arena lobby, near the trainer (mapdump-verified
        // walkable). Only a fallback — resolve() sends both fighters back to their return tiles;
        // this catches the odd path (death/logout placement, allocator teardown sweep).
        val FALLBACK_EXIT = Tile(3367, 3274, 0)

        // Source of the OBSTACLE pit (the Obstacles rule): the classic map's south-east obstacle
        // arena, interior (3367,3208)-(3386,3218), boxed chunk-aligned so the enclosing walls copy
        // with it. NOT YET MAPDUMP-VERIFIED — the cache lives only on the live host, so this is
        // the classic-map candidate from the research doc: the Obstacles rule ships CLOSED
        // (DuelArena.obstaclesOpen) until an admin walks the pit with ::duelpittest and opens it
        // with ::duelobstacles. Spawn tiles are candidates too — TUNE during that walk.
        val OBSTACLE_SOURCE = Area(3360, 3200, 3391, 3223)
        val OB_RED_SPAWN = Tile(3372, 3213, 0)
        val OB_BLUE_SPAWN = Tile(3381, 3213, 0)
    }
}

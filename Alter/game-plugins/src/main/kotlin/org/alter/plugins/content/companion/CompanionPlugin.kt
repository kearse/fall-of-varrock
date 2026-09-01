package org.alter.plugins.content.companion

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.action.NpcDeathAction
import org.alter.game.action.PlayerDeathAction
import org.alter.game.model.priv.Privilege
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
// MANDATORY alias: this class has its own `companion object`, so a bare `is Companion` would
// resolve to it and silently never match (see the warning at BotCombatPlugin.kt).
import org.alter.plugins.content.companion.Companion as CompanionPawn

/**
 * Host plugin for the **companion** system: spawns a player's roster on login, stores + despawns it
 * on logout, ticks the companion brains, and (for now) exposes test commands. The polished recruit
 * flow lives at General Zo and the management UI is Phase 2 (custom client).
 */
class CompanionPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        // A companion's npc kills credit its OWNER: KILLER_ATTR consumers (bounties, drops, slayer,
        // war points, …) all pay/attribute whoever this resolves to, and paying a companion writes
        // into transient containers that evaporate on despawn — the "my companion got the bounty
        // reward" report. Installed here, at the engine's single kill-credit choke point.
        NpcDeathAction.killCreditResolver = { pawn ->
            if (pawn is CompanionPawn) CompanionRegistry.ownerOf(world, pawn) ?: pawn else pawn
        }
        // Same remap on the PLAYER-death path — so a companion that lands the killing blow on a
        // PkBot (a Rogue Knight boss, a tier rogue) credits its OWNER, not the companion. Without
        // this the bot-death consumers that reject `killer is PkBot` silently drop the kill (the
        // "killed the bronze boss but it still shows hunting" report).
        PlayerDeathAction.killCreditResolver = { pawn ->
            if (pawn is CompanionPawn) CompanionRegistry.ownerOf(world, pawn) ?: pawn else pawn
        }

        onLogin { CompanionRegistry.spawnFor(world, player) }
        onLogout { CompanionRegistry.storeAndDespawn(world, player) }

        // NOTE: gear management is handled by the "Fall of Varrock Companions" RuneLite plugin (panel), NOT
        // right-click player-options — those `sendOption`s are global and leaked onto every player
        // and bot. The panel will drive gear via a per-companion channel instead.

        val timer = TimerKey()
        onWorldInit { world.timers[timer] = TICK }
        onTimer(timer) {
            CompanionRegistry.tick(world)
            world.timers[timer] = TICK
        }

        // ::recruit <melee|range|mage> — TEST recruit (the General Zo dialogue comes in a later step).
        onCommand("recruit", Privilege.ADMIN_POWER, description = "Recruit a companion (test): ::recruit <melee|range|mage>") {
            val archetype = when (player.getCommandArgs().getOrNull(0)?.lowercase()) {
                "melee", null -> CompanionStyle.MELEE
                "range", "ranged" -> CompanionStyle.RANGE
                "mage", "magic" -> CompanionStyle.MAGE
                else -> { player.message("Usage: ::recruit <melee|range|mage>"); return@onCommand }
            }
            val cap = CompanionRegistry.companionCap(player)
            // The cap counts the whole roster, dismissed companions included — benching one doesn't
            // free a slot to recruit another.
            if (CompanionRegistry.rosterSize(player) >= cap) {
                player.message("Your rank allows $cap companion(s) — you already command ${CompanionRegistry.rosterSize(player)}."); return@onCommand
            }
            val comp = CompanionRegistry.recruit(world, player, archetype)
            if (comp == null) player.message("<col=801700>Could not recruit a companion.</col>")
            else player.message("<col=4f9b4f>Recruited a ${archetype.display} companion (${CompanionRegistry.rosterSize(player)}/$cap).</col>")
        }

        // ::companion <train|attack|follow|deploy|return|dismiss|summon|bones|archetype> [slot] — order all your
        // companions, or just the companion in [slot] (1-based). The RuneLite panel sends the slot form.
        onCommand("companion", description = "Order companions: ::companion <train|attack|follow|deploy|return|dismiss|summon|bones|archetype> [slot]") {
            val list = CompanionRegistry.ofOwner(player)
            val args = player.getCommandArgs()

            // Dismiss / summon come FIRST: they're the only actions that make sense with nobody
            // fielded (the whole point of summon is that your companions are off duty).
            //   ::companion dismiss [slot]  — send one (or all) off duty; they leave the world entirely
            //   ::companion summon  [slot]  — call one (or all) back to your side
            // [slot] is 1-based over the roster the panel was sent: live companions first, then the bench.
            when (args.getOrNull(0)?.lowercase()) {
                "dismiss" -> {
                    val slot = args.getOrNull(1)?.toIntOrNull()
                    if (slot != null) {
                        if (!CompanionRegistry.dismiss(player, slot - 1)) player.message("<col=801700>No companion in slot $slot.</col>")
                    } else if (CompanionRegistry.dismissAll(player) == 0) {
                        player.message("You have no companions to dismiss.")
                    }
                    return@onCommand
                }
                "summon" -> {
                    val slot = args.getOrNull(1)?.toIntOrNull()
                    if (slot != null) {
                        CompanionRegistry.summon(player, slot - 1)
                    } else if (CompanionRegistry.summonAll(player) == 0) {
                        player.message("You have no dismissed companions to summon.")
                    }
                    return@onCommand
                }
            }

            if (list.isEmpty()) {
                val benched = CompanionRegistry.benchedOf(player)
                player.message(
                    if (benched.isEmpty()) "You have no companions."
                    else "All your companions are off duty — summon them from the companion panel.",
                )
                return@onCommand
            }

            // Gear management (panel-driven; gear from/to the owner's BANK):
            //   ::companion equip   <compSlot> <itemId>     — add a bank item onto a companion
            //   ::companion unequip <compSlot> <equipSlot>  — return a worn item to the bank
            when (args.getOrNull(0)?.lowercase()) {
                "equip" -> {
                    val comp = list.getOrNull((args.getOrNull(1)?.toIntOrNull() ?: 0) - 1)
                    val itemId = args.getOrNull(2)?.toIntOrNull()
                    if (comp == null || itemId == null) { player.message("Usage: ::companion equip <slot> <itemId>"); return@onCommand }
                    CompanionGear.equipFromBank(player, comp, itemId)
                    CompanionRegistry.persist(player); CompanionRegistry.forcePush(player)
                    return@onCommand
                }
                "unequip" -> {
                    val comp = list.getOrNull((args.getOrNull(1)?.toIntOrNull() ?: 0) - 1)
                    val equipSlot = args.getOrNull(2)?.toIntOrNull()
                    if (comp == null || equipSlot == null) { player.message("Usage: ::companion unequip <slot> <equipSlot>"); return@onCommand }
                    CompanionGear.unequipToBank(player, comp, equipSlot)
                    CompanionRegistry.persist(player); CompanionRegistry.forcePush(player)
                    return@onCommand
                }
                // ::companion style <slot> <0-3> — set the companion's attack style (panel buttons).
                "style" -> {
                    val slot = (args.getOrNull(1)?.toIntOrNull() ?: 0) - 1
                    val style = args.getOrNull(2)?.toIntOrNull()
                    if (slot < 0 || style == null) { player.message("Usage: ::companion style <slot> <0-3>"); return@onCommand }
                    CompanionRegistry.setStyle(player, slot, style)
                    return@onCommand
                }
                // ::companion spell <slot> <SPELL_NAME|auto> — set/clear a mage's autocast (panel selector).
                "spell" -> {
                    val slot = (args.getOrNull(1)?.toIntOrNull() ?: 0) - 1
                    val name = args.getOrNull(2)
                    if (slot < 0 || name == null) { player.message("Usage: ::companion spell <slot> <spell|auto>"); return@onCommand }
                    CompanionRegistry.setSpell(player, slot, if (name.equals("auto", true)) null else name.uppercase())
                    return@onCommand
                }
                // ::companion retaliate <slot> — toggle auto-retaliate (panel toggle).
                "retaliate" -> {
                    val slot = (args.getOrNull(1)?.toIntOrNull() ?: 0) - 1
                    if (slot < 0) { player.message("Usage: ::companion retaliate <slot>"); return@onCommand }
                    CompanionRegistry.toggleRetaliate(player, slot)
                    return@onCommand
                }
                // ::companion rename <slot> <name> — rename a companion (panel dialog).
                "rename" -> {
                    val slot = (args.getOrNull(1)?.toIntOrNull() ?: 0) - 1
                    val name = args.drop(2).joinToString(" ")
                    if (slot < 0 || name.isBlank()) { player.message("Usage: ::companion rename <slot> <name>"); return@onCommand }
                    CompanionRegistry.rename(player, slot, name)
                    return@onCommand
                }
                // ::companion gear <slot> <equipSlot> — request the bank items that fit a slot (panel picker).
                "gear" -> {
                    val slot = (args.getOrNull(1)?.toIntOrNull() ?: 0) - 1
                    val equipSlot = args.getOrNull(2)?.toIntOrNull()
                    if (slot < 0 || equipSlot == null) { player.message("Usage: ::companion gear <slot> <equipSlot>"); return@onCommand }
                    CompanionRegistry.pushGearList(player, slot, equipSlot)
                    return@onCommand
                }
                // ::companion bones [slot] — feed every bone in YOUR inventory to a companion
                // (default slot 1): he buries them and gains the Prayer xp. The only Prayer
                // training path a clientless companion can have.
                "bones" -> {
                    val comp = list.getOrNull((args.getOrNull(1)?.toIntOrNull() ?: 1) - 1)
                    if (comp == null) { player.message("<col=801700>No companion in that slot.</col>"); return@onCommand }
                    if (CompanionPrayers.feedBones(player, comp) == 0) {
                        player.message("You have no bones to give him.")
                    } else {
                        CompanionRegistry.persist(player); CompanionRegistry.forcePush(player)
                    }
                    return@onCommand
                }
                // ::companion archetype <slot> <melee|range|mage> — re-school a companion so it can
                // train a different combat style (skills + gear carry over).
                "archetype", "school" -> {
                    val slot = (args.getOrNull(1)?.toIntOrNull() ?: 0) - 1
                    val style = when (args.getOrNull(2)?.lowercase()) {
                        "melee" -> CompanionStyle.MELEE
                        "range", "ranged" -> CompanionStyle.RANGE
                        "mage", "magic" -> CompanionStyle.MAGE
                        else -> null
                    }
                    if (slot < 0 || style == null) { player.message("Usage: ::companion archetype <slot> <melee|range|mage>"); return@onCommand }
                    if (!CompanionRegistry.setArchetype(player, slot, style)) {
                        player.message("<col=801700>No companion in slot ${slot + 1}.</col>")
                    }
                    return@onCommand
                }
                // ::companion loot [slot] — toggle the donor auto-loot-to-bank perk (all, or one slot).
                "loot" -> {
                    val slot = args.getOrNull(1)?.toIntOrNull()
                    val targets = if (slot != null) listOfNotNull(list.getOrNull(slot - 1)) else list
                    if (targets.isEmpty()) { player.message("<col=801700>No such companion.</col>"); return@onCommand }
                    val on = !targets.first().autoLoot // mirror the first target's new state across the selection
                    targets.forEach { it.autoLoot = on }
                    CompanionRegistry.persist(player); CompanionRegistry.forcePush(player)
                    val who = if (slot != null) "Sir ${targets.first().username}" else "Your companions"
                    player.message("<col=4f9b4f>$who will ${if (on) "now auto-bank loot" else "no longer auto-bank loot"}.</col>")
                    return@onCommand
                }
            }

            val order = when (args.getOrNull(0)?.lowercase()) {
                "train" -> CompanionOrders.TRAIN
                "attack", "hunt" -> CompanionOrders.ATTACK
                "follow" -> CompanionOrders.FOLLOW
                "deploy" -> CompanionOrders.DEPLOY
                "return" -> CompanionOrders.RETURN
                else -> { player.message("Usage: ::companion <train|attack|follow|deploy|return|dismiss|summon> [slot]"); return@onCommand }
            }
            val slot = args.getOrNull(1)?.toIntOrNull()
            if (slot != null) {
                val comp = list.getOrNull(slot - 1)
                if (comp == null) { player.message("<col=801700>No companion in slot $slot.</col>"); return@onCommand }
                giveOrder(comp, order)
                player.message("<col=4f9b4f>Sir ${comp.username}: ${order.name.lowercase()}.</col>")
                if (order == CompanionOrders.ATTACK) {
                    player.message("<col=4f9b4f>He will hold this ground and fight until slain or given new orders.</col>")
                }
            } else {
                list.forEach { giveOrder(it, order) }
                player.message("<col=4f9b4f>All companions: ${order.name.lowercase()}.</col>")
                if (order == CompanionOrders.ATTACK) {
                    player.message("<col=4f9b4f>They will hold this ground and fight until slain or given new orders.</col>")
                }
            }
        }

        // ::companions — list your roster (fielded first, then anyone off duty or slain).
        onCommand("companions", description = "List your companions") {
            val list = CompanionRegistry.ofOwner(player)
            val benched = CompanionRegistry.benchedOf(player)
            if (list.isEmpty() && benched.isEmpty()) {
                player.message("You have no companions. Recruit one from General Zo."); return@onCommand
            }
            player.message("Your companions (${CompanionRegistry.rosterSize(player)}/${CompanionRegistry.MAX}):")
            list.forEach { player.message(" - Sir ${it.username} (${it.archetype.display}), combat ${it.combatLevel} [${it.orders.name.lowercase()}]") }
            benched.forEachIndexed { i, d ->
                val state = if (d.dead) "slain" else "off duty — ::companion summon ${list.size + i + 1}"
                player.message(" - Sir ${d.name} (${d.archetype.display}) [$state]")
            }
        }
    }

    /** Assign [order], anchoring an ATTACK grind on the companion's current tile (see
     *  [Companion.huntAnchor]) and clearing any old anchor on every other order — RETURN/FOLLOW is
     *  exactly how a grinding companion is recalled. */
    private fun giveOrder(comp: CompanionPawn, order: CompanionOrders) {
        comp.orders = order
        comp.huntAnchor = if (order == CompanionOrders.ATTACK) comp.tile else null
    }

    private companion object {
        const val TICK = 2 // ~1.2s brain cadence
    }
}

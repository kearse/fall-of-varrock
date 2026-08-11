package org.alter.plugins.content.interfaces.gameframe.tabs.combat_options

import org.alter.api.*
import org.alter.api.cfg.*
import org.alter.api.dsl.*
import org.alter.api.ext.*
import org.alter.game.*
import org.alter.game.model.*
import org.alter.game.model.attr.*
import org.alter.game.model.attr.NEW_ACCOUNT_ATTR
import org.alter.game.model.container.*
import org.alter.game.model.container.key.*
import org.alter.game.model.entity.*
import org.alter.game.model.item.*
import org.alter.game.model.queue.*
import org.alter.game.model.shop.*
import org.alter.game.model.timer.*
import org.alter.game.plugin.*
import org.alter.plugins.content.combat.Combat
import org.alter.plugins.content.combat.specialattack.SpecialAttacks
import org.alter.plugins.content.interfaces.attack.AttackTab
import org.alter.plugins.content.interfaces.attack.AttackTab.ATTACK_STYLE_VARP
import org.alter.plugins.content.interfaces.attack.AttackTab.ATTACK_TAB_INTERFACE_ID
import org.alter.plugins.content.interfaces.attack.AttackTab.DISABLE_AUTO_RETALIATE_VARP
import org.alter.plugins.content.interfaces.attack.AttackTab.SPECIAL_ATTACK_VARP
import org.alter.plugins.content.interfaces.attack.AttackTab.setEnergy

class AttackTabPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
        
    init {
        /**
         * First log-in logic (when accounts have just been made).
         */
        onLogin {
            /*
             * Spawn/PK server: always grant full special-attack energy on login
             * (rather than only for brand-new accounts), so specials are usable
             * immediately instead of waiting for the slow restore timer.
             */
            setEnergy(player, 100)
            AttackTab.resetRestorationTimer(player)
            clearStaleAutocast(player)
        }

        onTimer(AttackTab.SPEC_RESTORE) {
            AttackTab.restoreEnergy(player)
            AttackTab.resetRestorationTimer(player)
        }

        /**
         * Attack style buttons
         */
        onButton(interfaceId = ATTACK_TAB_INTERFACE_ID, component = 5) {
            player.setVarp(ATTACK_STYLE_VARP, 0)
        }

        onButton(interfaceId = ATTACK_TAB_INTERFACE_ID, component = 9) {
            player.setVarp(ATTACK_STYLE_VARP, 1)
        }

        onButton(interfaceId = ATTACK_TAB_INTERFACE_ID, component = 13) {
            player.setVarp(ATTACK_STYLE_VARP, 2)
        }

        onButton(interfaceId = ATTACK_TAB_INTERFACE_ID, component = 17) {
            player.setVarp(ATTACK_STYLE_VARP, 3)
        }

        /*
         * Autocast mode boxes, shown on the combat tab when a staff is wielded:
         * the left "Spell" box (shield icon) selects defensive casting, the right
         * one standard casting. On this server they toggle the casting mode
         * directly (varbit 2668) rather than opening the choose-spell interface,
         * which isn't wired — autocast arms by casting a spell once (see
         * CombatSpellsPlugin). Child ids inside interface 593 drift between cache
         * revisions, so bind every child of each box: 21-25 make up the defensive
         * box (container/icon/shield/text), 26-29 the standard one. The styles end
         * at 17 and auto-retaliate starts at 30, so neither range can collide.
         */
        for (component in 21..25) {
            onButton(interfaceId = ATTACK_TAB_INTERFACE_ID, component = component) {
                setDefensiveCasting(player, true)
            }
        }
        for (component in 26..29) {
            onButton(interfaceId = ATTACK_TAB_INTERFACE_ID, component = component) {
                setDefensiveCasting(player, false)
            }
        }

        /**
         * Toggle auto-retaliate button.
         */
        onButton(interfaceId = ATTACK_TAB_INTERFACE_ID, component = 31) {
            player.toggleVarp(DISABLE_AUTO_RETALIATE_VARP)
        }

        /*
         * Special-attack toggle. The client sends one of two buttons:
         *   - 593:38  -> the spec bar at the bottom of the combat/attack tab
         *   - 160:35  -> the special-attack orb next to the minimap
         *
         * ALL minimap orbs live on the shared "orbs" interface 160 (regardless of
         * display mode), following an 8-component stride:
         *   prayer 160:19, run 160:27, spec 160:35.
         * (xp 160:5 / world-map 160:53 confirm interface 160 is the orb pane.)
         *
         * NOTE: the orb was previously (mis)bound to the toplevel gameframe
         * interfaces 548:57 / 161:60 / 164:59 -- those are NOT the orb, so the
         * minimap spec orb silently did nothing while the combat-tab bar worked.
         */
        val onSpecialToggle: org.alter.game.plugin.Plugin.() -> Unit = {
            if (org.alter.plugins.content.minigames.pktraining.CompanionSparring.rulesOf(player)?.noSpec == true) {
                player.message("Special attacks are disabled in this sparring bout.")
            } else {
                val weaponId = player.equipment[EquipmentType.WEAPON.id]?.id ?: -1
                if (SpecialAttacks.executeOnEnable(weaponId)) {
                    if (!SpecialAttacks.execute(player, null, world)) {
                        player.message("You don't have enough power left.")
                    }
                } else {
                    player.toggleVarp(SPECIAL_ATTACK_VARP)
                }
            }
        }
        onButton(interfaceId = 593, component = 38, logic = onSpecialToggle)
        onButton(interfaceId = 160, component = 35, logic = onSpecialToggle)

        /**
         * Disable special attack when switching weapons.
         */
        onEquipToSlot(EquipmentType.WEAPON.id) {
            player.setVarp(SPECIAL_ATTACK_VARP, 0)
            clearStaleAutocast(player)
        }

        /**
         * Unarming a staff has to clear auto-cast too -- otherwise the selection
         * survives into bare-handed combat, where nothing else would clear it.
         */
        onUnequipFromSlot(EquipmentType.WEAPON.id) {
            clearStaleAutocast(player)
        }

        /**
         * Disable special attack on log-out.
         */
        onLogout {
            player.setVarp(SPECIAL_ATTACK_VARP, 0)
        }
    }

    /**
     * Clears any lingering auto-cast selection / casting-spell flag when the
     * player is not wielding a magic weapon. A stale auto-cast varbit forces the
     * combat style to MAGIC (CombatConfigs), which throws for melee/ranged
     * weapons and silently prevents them from landing hits.
     */
    private fun clearStaleAutocast(player: Player) {
        if (!player.hasWeaponType(WeaponType.MAGIC_STAFF, WeaponType.STAFF, WeaponType.TRIDENT)) {
            player.setVarbit(Combat.SELECTED_AUTOCAST_VARBIT, 0)
            player.setVarbit(Combat.DEFENSIVE_MAGIC_CAST_VARBIT, 0)
            player.attr.remove(Combat.CASTING_SPELL)
        }
    }

    /**
     * Flips the casting mode the magic xp split keys off (defensive = Magic +
     * Defence, standard = full Magic). Kept as a plain varbit toggle so the mode
     * survives staff-to-staff swaps; [clearStaleAutocast] resets it whenever the
     * player leaves magic weapons entirely.
     */
    private fun setDefensiveCasting(player: Player, defensive: Boolean) {
        val current = player.getVarbit(Combat.DEFENSIVE_MAGIC_CAST_VARBIT) != 0
        if (current == defensive) {
            return
        }
        player.setVarbit(Combat.DEFENSIVE_MAGIC_CAST_VARBIT, if (defensive) 1 else 0)
        if (defensive) {
            player.message("You will now cast spells defensively, splitting the experience between Magic and Defence.")
        } else {
            player.message("You will no longer cast spells defensively.")
        }
    }
}

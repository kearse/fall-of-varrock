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
            val weaponId = player.equipment[EquipmentType.WEAPON.id]?.id ?: -1
            if (SpecialAttacks.executeOnEnable(weaponId)) {
                if (!SpecialAttacks.execute(player, null, world)) {
                    player.message("You don't have enough power left.")
                }
            } else {
                player.toggleVarp(SPECIAL_ATTACK_VARP)
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
            player.attr.remove(Combat.CASTING_SPELL)
        }
    }
}

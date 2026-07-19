package org.alter.plugins.content.mechanics.appearance

import org.alter.api.InterfaceDestination
import org.alter.api.ext.closeInterface
import org.alter.api.ext.openInterface
import org.alter.api.ext.player
import org.alter.api.ext.setVarbit
import org.alter.game.Server
import org.alter.game.info.PlayerInfo
import org.alter.game.model.World
import org.alter.game.model.appearance.Appearance
import org.alter.game.model.appearance.Colours
import org.alter.game.model.appearance.Gender
import org.alter.game.model.appearance.Looks
import org.alter.game.model.attr.APPEARANCE_SET_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * The native OSRS **character-design interface** (679) — the screen every OSRS account sees at
 * creation, showing the player's LIVE engine-rendered 3D model. Re-enabled for Fall of Varrock:
 * every arrow press mutates [Player.appearance] and re-syncs it immediately, so the preview *is*
 * the real look (design brief: "use the real one for character modifications").
 *
 * Opened by Aurelia the Stylist's "Restyle my appearance" ([AppearanceDesign.open]) and the
 * `::appearance` command (the safe first-test path — this interface was disabled in this build,
 * and while STOCK cache interfaces render fine on our client, verify it opens before wiring
 * anything player-facing to it; Aurelia keeps her classic dialogue restyle as the fallback).
 *
 * Component map (stock 679): 65/66 gender buttons, 68 confirm; body-part rows base at 10
 * (head, jaw, torso, arms, hands, legs, feet — 4 apart), colour rows base at 41 (hair, torso,
 * legs, feet, skin — 4 apart); each row's `+2` child is Previous, `+3` is Next.
 */
object AppearanceDesign {
    const val INTERFACE_ID = 679

    /** Stock varbit the interface uses to highlight the selected gender button. */
    const val GENDER_VARBIT = 11697

    fun open(p: Player) {
        p.setVarbit(GENDER_VARBIT, if (p.appearance.gender == Gender.FEMALE) 1 else 0)
        p.openInterface(INTERFACE_ID, InterfaceDestination.MAIN_SCREEN)
    }
}

class AppearanceInterfacePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** One editable row of the interface: either a body-part look or a colour slot. */
    private sealed class Row(val component: Int) {
        /** [option] is the [Appearance.getLook] option code (0=HEAD … 6=FEET). */
        class Look(component: Int, val option: Int) : Row(component)

        /** [slot] indexes [Appearance].colors: [hair, torso, legs, feet, skin]. */
        class Colour(component: Int, val slot: Int) : Row(component)
    }

    private val rows: List<Row> = listOf(
        Row.Look(component = 10, option = 0), // head
        Row.Look(component = 14, option = 1), // jaw (male only)
        Row.Look(component = 18, option = 2), // torso
        Row.Look(component = 22, option = 3), // arms
        Row.Look(component = 26, option = 4), // hands
        Row.Look(component = 30, option = 5), // legs
        Row.Look(component = 34, option = 6), // feet
        Row.Colour(component = 41, slot = 0), // hair colour
        Row.Colour(component = 45, slot = 1), // torso colour
        Row.Colour(component = 49, slot = 2), // legs colour
        Row.Colour(component = 53, slot = 3), // feet colour
        Row.Colour(component = 57, slot = 4), // skin colour
    )

    init {
        onCommand("appearance", description = "Open the character-design screen") {
            AppearanceDesign.open(player)
        }

        onButton(AppearanceDesign.INTERFACE_ID, 65) { setGender(player, Gender.MALE) }
        onButton(AppearanceDesign.INTERFACE_ID, 66) { setGender(player, Gender.FEMALE) }

        onButton(AppearanceDesign.INTERFACE_ID, 68) {
            player.attr[APPEARANCE_SET_ATTR] = true
            player.closeInterface(AppearanceDesign.INTERFACE_ID)
            player.unlock() // no-op unless a (future) first-login flow locked the player
        }

        rows.forEach { row ->
            onButton(AppearanceDesign.INTERFACE_ID, row.component + 2) { step(player, row, delta = -1) }
            onButton(AppearanceDesign.INTERFACE_ID, row.component + 3) { step(player, row, delta = +1) }
        }
    }

    private fun step(p: Player, row: Row, delta: Int) {
        ensureOwnedAppearance(p)
        when (row) {
            is Row.Look -> stepLook(p, row.option, delta)
            is Row.Colour -> stepColour(p, row.slot, delta)
        }
    }

    // ---------------------------------- appearance ops ----------------------------------
    // Mirrors the Lumbridge Stylist's dialogue restyle: looks[] holds INDICES into the
    // per-gender pools in [Looks]; the female array omits JAW so later slots shift by one.

    /**
     * A fresh player's [Appearance] aliases the shared `DEFAULT_*` arrays; mutating those in
     * place would restyle every default-look player at once. Copy-on-first-write.
     */
    private fun ensureOwnedAppearance(p: Player) {
        val a = p.appearance
        p.appearance = Appearance(a.looks.copyOf(), a.colors.copyOf(), a.gender)
    }

    private fun poolFor(option: Int, gender: Gender): Array<Int> = when (option) {
        0 -> Looks.getHeads(gender)
        1 -> Looks.getJaws(gender)
        2 -> Looks.getTorsos(gender)
        3 -> Looks.getArms(gender)
        4 -> Looks.getHands(gender)
        5 -> Looks.getLegs(gender)
        6 -> Looks.getFeets(gender)
        else -> arrayOf()
    }

    /** looks[] index for a body-part option, or null when the part doesn't exist (female jaw). */
    private fun slotFor(option: Int, gender: Gender): Int? = when (gender) {
        Gender.MALE -> option
        Gender.FEMALE -> when (option) {
            0 -> 0
            1 -> null
            else -> option - 1
        }
    }

    private fun stepLook(p: Player, option: Int, delta: Int) {
        val gender = p.appearance.gender
        val slot = slotFor(option, gender) ?: return
        val size = poolFor(option, gender).size
        if (size <= 0 || slot !in p.appearance.looks.indices) return
        p.appearance.looks[slot] = wrap(p.appearance.looks[slot] + delta, size)
        PlayerInfo(p).syncAppearance()
    }

    private fun stepColour(p: Player, slot: Int, delta: Int) {
        val size = when (slot) {
            0 -> Colours.HAIR_COLOURS.size
            1 -> Colours.TORSO_COLOURS.size
            2 -> Colours.LEG_COLOURS.size
            3 -> Colours.FEET_COLOURS.size
            4 -> Colours.SKIN_COLOURS.size
            else -> 0
        }
        if (size <= 0 || slot !in p.appearance.colors.indices) return
        p.appearance.colors[slot] = wrap(p.appearance.colors[slot] + delta, size)
        PlayerInfo(p).syncAppearance()
    }

    private fun setGender(p: Player, gender: Gender) {
        if (p.appearance.gender == gender) return
        val defaults = if (gender == Gender.MALE) Appearance.DEFAULT_MALE else Appearance.DEFAULT_FEMALE
        // Keep the player's colours; reset looks to a valid default for the new gender.
        p.appearance = Appearance(defaults.looks.copyOf(), p.appearance.colors.copyOf(), gender)
        p.setVarbit(AppearanceDesign.GENDER_VARBIT, if (gender == Gender.FEMALE) 1 else 0)
        PlayerInfo(p).syncAppearance()
    }

    /** Wrap [value] into 0 until [size] (handles negatives). */
    private fun wrap(value: Int, size: Int): Int = ((value % size) + size) % size
}

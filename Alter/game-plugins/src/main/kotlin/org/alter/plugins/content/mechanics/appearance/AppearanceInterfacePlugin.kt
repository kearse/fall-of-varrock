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
import org.alter.game.model.attr.AttributeKey
import org.alter.game.model.attr.STYLE_PREVIEW_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**
 * Session-only callback fired the moment the player clicks **Confirm** (button 74) on the
 * character-design screen. The first-login onboarding sets it so that confirming the design hands
 * control straight back to the Recruiting Sergeant flow (mustering the recruit into the Trials).
 * No persistenceKey — a logout mid-design just drops it, and the flow re-opens the screen when the
 * recruit re-hails the Sergeant, so it can never soft-lock.
 */
val APPEARANCE_CONFIRM_CALLBACK_ATTR = AttributeKey<() -> Unit>()

/**
 * The native OSRS **character-design interface** (679) — the screen every OSRS account sees at
 * creation, showing the player's LIVE engine-rendered 3D model *inside* the panel (the same way
 * the "Equip Your Character" screen, interface 84, draws the player). This is the makeover the
 * server ships: Aurelia the Stylist's "Restyle my appearance" and `::makeover` both open it, and
 * `::appearance` opens it directly. Every arrow press mutates [Player.appearance] and re-syncs it
 * immediately, so the preview *is* the real look.
 *
 * The model rendering needs no server code — 679 is a stock cache interface with a player-model
 * component; opening it plus [PlayerInfo.syncAppearance] is all it takes. While the window is open
 * the player is broadcast WITHOUT worn gear ([STYLE_PREVIEW_ATTR]) so the base skins being edited
 * are visible; closing the interface restores the gear (see the `onInterfaceClose` hook below).
 *
 * Component map (group 679 = `player_design`, taken from this build's decoded cache and confirmed by
 * in-game testing). Each editable row exposes two ADJACENT arrow buttons — LEFT (Previous) then RIGHT
 * (Next), i.e. `left`, `left+1`. Body-part rows start at head 15/16 and step by 4 (jaw 19/20, torso
 * 23/24, arms 27/28, hands 31/32, legs 35/36, feet 39/40); colour rows start at hair 46/47 and step by
 * 4 (torso 50/51, legs 54/55, feet 58/59, skin 62/63). Body-type buttons: 68 (Type A) / 69 (Type B);
 * Confirm: 74. The pronoun controls (dropdown 72, button container 78) are purely cosmetic and have no
 * counterpart in the engine's [Appearance] (which models gender only), so they're intentionally left
 * unhandled.
 *
 * NOTE — the original ids here were a pre-rev-228 assumption (rows at base 10/41, arrows at `+2`/`+3`,
 * gender 65/66, confirm 68) and did NOT match this build's cache ("buttons misaligned and dead", commit
 * 2e3e962): every left arrow hit an unbound id (dead) while every right arrow fell through onto the NEXT
 * row's handler (e.g. the head row's Next changed facial hair), and the old "confirm" id 68 was really
 * the Type-A body button, so switching body type just closed the window. If a future cache re-jigs the
 * group, re-verify with:
 *     ./gradlew :game-server:teleportIface -PteleArgs="inspect 679"   # server stopped
 */
object AppearanceDesign {
    const val INTERFACE_ID = 679

    /** Stock varbit the interface uses to highlight the selected body-type button (A/B). */
    const val GENDER_VARBIT = 11697

    /**
     * Open the character-design screen. [onConfirm], if given, runs once when the player clicks
     * Confirm (button 74) — used by the first-login onboarding to resume the Sergeant flow. Passing
     * null (the default, e.g. `::appearance` or the Stylist) clears any stale pending callback.
     */
    fun open(p: Player, onConfirm: (() -> Unit)? = null) {
        if (onConfirm != null) p.attr[APPEARANCE_CONFIRM_CALLBACK_ATTR] = onConfirm
        else p.attr.remove(APPEARANCE_CONFIRM_CALLBACK_ATTR)
        p.setVarbit(GENDER_VARBIT, if (p.appearance.gender == Gender.FEMALE) 1 else 0)
        // Hide worn gear so the preview shows the default skins being edited; restored on close.
        p.attr[STYLE_PREVIEW_ATTR] = true
        PlayerInfo(p).syncAppearance()
        p.openInterface(INTERFACE_ID, InterfaceDestination.MAIN_SCREEN)
    }

    /** End the bare-skins preview and re-broadcast the player in their gear. */
    fun endPreview(p: Player) {
        if (p.attr[STYLE_PREVIEW_ATTR] == true) {
            p.attr.remove(STYLE_PREVIEW_ATTR)
            PlayerInfo(p).syncAppearance()
        }
    }
}

class AppearanceInterfacePlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /**
     * One editable row of the interface: either a body-part look or a colour slot. [leftArrow] is the
     * component id of the row's LEFT (Previous) arrow; the RIGHT (Next) arrow is always [leftArrow] + 1.
     */
    private sealed class Row(val leftArrow: Int) {
        /** [option] is the [Appearance.getLook] option code (0=HEAD … 6=FEET). */
        class Look(leftArrow: Int, val option: Int) : Row(leftArrow)

        /** [slot] indexes [Appearance].colors: [hair, torso, legs, feet, skin]. */
        class Colour(leftArrow: Int, val slot: Int) : Row(leftArrow)
    }

    private val rows: List<Row> = listOf(
        Row.Look(leftArrow = 15, option = 0), // head / hairstyle
        Row.Look(leftArrow = 19, option = 1), // jaw / facial hair (Type A only)
        Row.Look(leftArrow = 23, option = 2), // torso / shirt
        Row.Look(leftArrow = 27, option = 3), // arms
        Row.Look(leftArrow = 31, option = 4), // hands
        Row.Look(leftArrow = 35, option = 5), // legs
        Row.Look(leftArrow = 39, option = 6), // feet
        Row.Colour(leftArrow = 46, slot = 0), // hair colour
        Row.Colour(leftArrow = 50, slot = 1), // torso colour
        Row.Colour(leftArrow = 54, slot = 2), // legs colour
        Row.Colour(leftArrow = 58, slot = 3), // feet colour
        Row.Colour(leftArrow = 62, slot = 4), // skin colour
    )

    init {
        onCommand("appearance", description = "Open the character-design screen") {
            AppearanceDesign.open(player)
        }

        // Body-type buttons (Type A ≈ male model, Type B ≈ female model).
        onButton(AppearanceDesign.INTERFACE_ID, 68) { setGender(player, Gender.MALE) }
        onButton(AppearanceDesign.INTERFACE_ID, 69) { setGender(player, Gender.FEMALE) }

        onButton(AppearanceDesign.INTERFACE_ID, 74) {
            player.attr[APPEARANCE_SET_ATTR] = true
            player.closeInterface(AppearanceDesign.INTERFACE_ID)
            player.unlock() // no-op unless a (future) first-login flow locked the player
            // First-login onboarding hands control back here: run the pending confirm callback once
            // (e.g. muster the recruit into the Trials), then clear it so it never re-fires.
            val onConfirm = player.attr[APPEARANCE_CONFIRM_CALLBACK_ATTR]
            if (onConfirm != null) {
                player.attr.remove(APPEARANCE_CONFIRM_CALLBACK_ATTR)
                onConfirm()
            }
        }

        rows.forEach { row ->
            onButton(AppearanceDesign.INTERFACE_ID, row.leftArrow) { step(player, row, delta = -1) }
            onButton(AppearanceDesign.INTERFACE_ID, row.leftArrow + 1) { step(player, row, delta = +1) }
        }

        // Any close (Confirm, the X, or another interface replacing 679) puts the gear back on.
        onInterfaceClose(AppearanceDesign.INTERFACE_ID) { AppearanceDesign.endPreview(player) }
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

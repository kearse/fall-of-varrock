package org.alter.plugins.content.mechanics.appearance

import org.alter.api.ext.getCommandArgs
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.api.ext.setVarp
import org.alter.game.Server
import org.alter.game.info.PlayerInfo
import org.alter.game.model.World
import org.alter.game.model.appearance.Appearance
import org.alter.game.model.appearance.Colours
import org.alter.game.model.appearance.Gender
import org.alter.game.model.appearance.Looks
import org.alter.game.model.attr.APPEARANCE_SET_ATTR
import org.alter.game.model.attr.STYLE_PREVIEW_ATTR
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.onboarding.FirstLoginFlow

/**
 * Server half of the client-drawn **Character Style** window (`lofstyle`) — the on-brand
 * replacement for the native character-design interface, whose rev-228 layout doesn't match
 * the component ids this build assumed (misaligned, dead buttons). The window anchors to the
 * LEFT of the viewport so the player's OWN in-world model — updated live by every click via
 * [PlayerInfo.syncAppearance] — is the real 3D preview, exactly the "show the real look"
 * requirement the native screen was meant to satisfy.
 *
 * Open: varp 4632 pulse, payload bit 0 = open, bit 1 = female (re-pulsed after a gender
 * switch so the window can dim the facial-hair row). Actions: `::lofstyle ...` → `styleclick`
 * with `look <option> <delta>` / `colour <slot> <delta>` / `gender <male|female>` / `done` /
 * `close`. No proximity gate — restyling is cosmetic and self-only, and `::makeover` has
 * always worked anywhere.
 *
 * While the window is open the player is broadcast WITHOUT worn equipment
 * ([STYLE_PREVIEW_ATTR]) — you edit your default skins, so the preview must show them.
 * `done` and `close` both restore the gear; the attr is transient so a logout mid-makeover
 * restores it too.
 */
object StyleMenu {
    /** Overlay-open varp (docs/overlay-design-system.md §8) — pulsed to 0, never persisted. */
    const val OPEN_VARP = 4632

    /**
     * @param mandatory first-login mode — the client hides the ✕ and relabels DONE ("ENTER THE
     *   WAR"), so the window can't be dismissed without confirming. Carried in payload bit 2.
     */
    fun open(p: Player, mandatory: Boolean = false) {
        if (p.attr[STYLE_PREVIEW_ATTR] != true) {
            p.attr[STYLE_PREVIEW_ATTR] = true
            PlayerInfo(p).syncAppearance()
        }
        var v = 1 or (if (p.appearance.gender == Gender.FEMALE) 2 else 0)
        if (mandatory) v = v or 4
        p.setVarp(OPEN_VARP, v)
        p.queue { wait(2); p.setVarp(OPEN_VARP, 0) }
    }

    /** End the bare-skins preview and broadcast the player back in their gear. */
    fun endPreview(p: Player) {
        if (p.attr[STYLE_PREVIEW_ATTR] == true) {
            p.attr.remove(STYLE_PREVIEW_ATTR)
            PlayerInfo(p).syncAppearance()
        }
    }
}

class StyleMenuPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("styleclick", description = "Character Style window action (client overlay channel)") {
            val a = player.getCommandArgs()
            when (a.getOrNull(0)?.lowercase()) {
                "look" -> step(player, isColour = false, a.getOrNull(1)?.toIntOrNull(), a.getOrNull(2)?.toIntOrNull())
                "colour" -> step(player, isColour = true, a.getOrNull(1)?.toIntOrNull(), a.getOrNull(2)?.toIntOrNull())
                "gender" -> when (a.getOrNull(1)?.lowercase()) {
                    "male" -> setGender(player, Gender.MALE)
                    "female" -> setGender(player, Gender.FEMALE)
                }
                "done" -> {
                    player.attr[APPEARANCE_SET_ATTR] = true
                    StyleMenu.endPreview(player)
                    // First-login mode: DONE ("ENTER THE WAR") confirms the look and advances the
                    // onboarding flow (unlock → Sergeant). Otherwise it's an ordinary makeover close.
                    if (FirstLoginFlow.isOnboarding(player)) {
                        FirstLoginFlow.onStyleConfirmed(player)
                    } else {
                        player.message("Looking sharp — the realm will know you by it.")
                    }
                }
                // The window closed without confirming (✕, or another window replaced it) —
                // the style edits already applied live; just put the gear back on.
                "close" -> StyleMenu.endPreview(player)
            }
        }
    }

    // ------------------------------- appearance ops -------------------------------
    // Mirrors the Lumbridge Stylist's dialogue restyle: looks[] holds INDICES into the
    // per-gender pools in [Looks]; the female array omits JAW so later slots shift by one.

    private fun step(p: Player, isColour: Boolean, index: Int?, deltaRaw: Int?) {
        if (index == null || deltaRaw == null) return
        val delta = if (deltaRaw >= 0) 1 else -1
        ensureOwnedAppearance(p)
        if (isColour) stepColour(p, index, delta) else stepLook(p, index, delta)
    }

    /** Copy-on-first-write: never mutate the shared DEFAULT_* appearance arrays. */
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
        Gender.MALE -> option.takeIf { it in 0..6 }
        Gender.FEMALE -> when {
            option == 0 -> 0
            option == 1 -> null
            option in 2..6 -> option - 1
            else -> null
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
        PlayerInfo(p).syncAppearance()
        // Re-pulse with the new gender bit so the window updates its rows — preserve the mandatory
        // bit while onboarding, or the ✕ would reappear after a gender switch.
        StyleMenu.open(p, mandatory = FirstLoginFlow.isOnboarding(p))
    }

    private fun wrap(value: Int, size: Int): Int = ((value % size) + size) % size
}

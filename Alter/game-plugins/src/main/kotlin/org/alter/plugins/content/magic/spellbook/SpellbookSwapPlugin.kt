package org.alter.plugins.content.magic.spellbook

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Spellbook
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.Player
import org.alter.game.model.priv.Privilege
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.combat.Combat
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Spellbook switching** (net-new Magic content). Lets any player change between the four
 * spellbooks, which unlocks the already-coded Ancient combat spells + teleports (gated by
 * [org.alter.plugins.content.magic.MagicSpells.canCast]'s `requiredBook` check) and the
 * Lunar/Arceuus books as their spells get wired.
 *
 * Player-facing via the Altar of the Occult in the Lumbridge market courtyard (Venerate for
 * the menu, or the direct Ancient/Lunar/Arceuus right-clicks), plus `::spellbook` (menu) or
 * `::spellbook <normal|ancient|lunar|arceuus>`. Uses the proper [setSpellbook] API (varbit
 * 4070). Switching clears any active autocast so a spell selected on the old book can't
 * linger on the new one.
 */
class SpellbookSwapPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onCommand("spellbook", description = "Switch spellbook: ::spellbook <normal|ancient|lunar|arceuus>") {
            val args = player.getCommandArgs()
            if (args.isEmpty()) {
                openMenu(player)
            } else {
                val book = parse(args[0])
                if (book == null) {
                    player.message("Unknown spellbook '${args[0]}'. Use: normal, ancient, lunar or arceuus.")
                } else {
                    switchTo(player, book)
                }
            }
        }

        // Admin test helpers for the Mage Tower reward (mirrors ::settitle / ::givecoins).
        onCommand("unlockbooks", Privilege.ADMIN_POWER, description = "Grant the Mage Tower spellbook unlock") {
            if (player.unlockMageBooks()) {
                player.message("Mage Tower cleared (test): Ancient, Lunar and Arceuus spellbooks unlocked.")
            } else {
                player.message("Spellbooks are already unlocked.")
            }
        }
        onCommand("lockbooks", Privilege.ADMIN_POWER, description = "Re-lock the special spellbooks (test)") {
            player.lockMageBooks()
            if (player.getSpellbook() != Spellbook.NORMAL) {
                switchTo(player, Spellbook.NORMAL)
            }
            player.message("Special spellbooks re-locked. You are back on the Standard book.")
        }

        // ---- Altar of the Occult (Lumbridge market courtyard) ----
        // A world copy of the POH occult altar (2x2), standing at the west end of the
        // south-wall services line, facing NORTH. The old south fountain tile (3221,3210)
        // now hosts the Grand Exchange stand (GrandExchangeClickPlugin), and the NORTH
        // fountain (3221,3226) is the teleport portal (TeleportPortalObjectPlugin) — do NOT
        // target either. Done in onWorldInit so the region/collision is loaded and the
        // (defensive) removal of any map-baked loc runs *before* the altar is placed —
        // otherwise the altar (same tile+slot) would shadow it and world.remove would clear
        // the altar instead (AlkharidGate/MiningPlugin boot pattern). The cache def carries
        // real switch verbs (Venerate/Ancient/Lunar/Arceuus), so books swap on a click
        // without ::spellbook. Same Mage Tower gate via [switchTo].
        onWorldInit {
            world.getObject(Tile(ALTAR_X, ALTAR_Z, 0), type = ALTAR_TYPE)?.let { world.remove(it) }
            world.spawn(DynamicObject(getRSCM(OCCULT_ALTAR), type = ALTAR_TYPE, rot = NORTH_ROT, Tile(ALTAR_X, ALTAR_Z, 0)))
        }
        onObjOption(obj = OCCULT_ALTAR, option = "Venerate") { openMenu(player) }
        onObjOption(obj = OCCULT_ALTAR, option = "Ancient") { switchTo(player, Spellbook.ANCIENTS) }
        onObjOption(obj = OCCULT_ALTAR, option = "Lunar") { switchTo(player, Spellbook.LUNAR) }
        onObjOption(obj = OCCULT_ALTAR, option = "Arceuus") { switchTo(player, Spellbook.ARCEUUS) }
        // "Remove" is a POH leftover baked into the cache def; answer it rather than dead-click.
        onObjOption(obj = OCCULT_ALTAR, option = "Remove") { player.message("The altar is fixed firmly in place.") }
    }

    private fun openMenu(player: Player) {
        val locked = !player.mageBooksUnlocked
        fun entry(book: Spellbook): String =
            if (locked && book.requiresMageTower()) "${label(book)} (locked)" else label(book)
        player.queue {
            val choice = options(
                player,
                entry(Spellbook.NORMAL),
                entry(Spellbook.ANCIENTS),
                entry(Spellbook.LUNAR),
                entry(Spellbook.ARCEUUS),
                title = "Choose your spellbook",
            )
            when (choice) {
                1 -> switchTo(player, Spellbook.NORMAL)
                2 -> switchTo(player, Spellbook.ANCIENTS)
                3 -> switchTo(player, Spellbook.LUNAR)
                4 -> switchTo(player, Spellbook.ARCEUUS)
            }
        }
    }

    private fun parse(arg: String): Spellbook? = when (arg.lowercase()) {
        "normal", "standard", "modern" -> Spellbook.NORMAL
        "ancient", "ancients" -> Spellbook.ANCIENTS
        "lunar", "lunars" -> Spellbook.LUNAR
        "arceuus" -> Spellbook.ARCEUUS
        else -> null
    }

    private fun switchTo(player: Player, book: Spellbook) {
        if (book.requiresMageTower() && !player.mageBooksUnlocked) {
            player.message("The ${label(book)} spellbook is sealed. Clear the Mage Tower to unlock the mage books.")
            return
        }
        if (player.getSpellbook() == book) {
            player.message("You are already using the ${label(book)} spellbook.")
            return
        }
        player.setSpellbook(book)
        // Clear any active autocast so a spell chosen on the old book doesn't carry over.
        player.setVarbit(Combat.SELECTED_AUTOCAST_VARBIT, 0)
        player.attr.remove(Combat.CASTING_SPELL)
        player.animate(645)
        player.graphic(112)
        player.message("You change to the ${label(book)} spellbook.")
    }

    private fun label(book: Spellbook): String = when (book) {
        Spellbook.NORMAL -> "Standard"
        Spellbook.ANCIENTS -> "Ancient"
        Spellbook.LUNAR -> "Lunar"
        Spellbook.ARCEUUS -> "Arceuus"
    }

    private companion object {
        /** Object 31858 — the variant whose cache actions are Venerate/Ancient/Lunar/Arceuus/Remove. */
        const val OCCULT_ALTAR = "object.altar_of_the_occult"

        // West end of the courtyard's south-wall services line, one tile east of the Royal
        // Smith (3213,3211) — 2x2 footprint 3215-3216 x 3211-3212. (The old south fountain
        // tile @ 3221,3210 is now the GE stand; the north fountain @ 3221,3226 is the
        // teleport portal — leave both alone.)
        const val ALTAR_X = 3215
        const val ALTAR_Z = 3211
        const val ALTAR_TYPE = 10   // interactable-scenery loc slot (matches the fountain's type)
        const val NORTH_ROT = 0     // model-verified in-game: rot 1 rendered EAST, so north = rot 0
                                    // (the occult-altar model's base orientation is offset one step
                                    // from the Direction.NORTH -> rot 1 convention)
    }
}

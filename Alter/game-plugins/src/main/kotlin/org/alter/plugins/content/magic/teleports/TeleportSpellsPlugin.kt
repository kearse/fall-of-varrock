package org.alter.plugins.content.magic.teleports

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.Skills
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Player
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.magic.MagicSpells
import org.alter.plugins.content.magic.SpellMetadata
import org.alter.plugins.content.magic.TeleportType
import org.alter.plugins.content.magic.canTeleport
import org.alter.plugins.content.magic.teleport

private val logger = KotlinLogging.logger {}

/**
 * **Magic teleports** (the non-combat Magic gap). The teleport machinery already exists
 * ([Player.teleport], [MagicSpells] metadata loaded from the cache); this binds the
 * standard spellbook teleport buttons to it. Casting checks level + runes, consumes runes,
 * teleports, and grants Magic xp. Lumbridge is the home teleport — central to the server.
 *
 * Spells are matched by a keyword in their cache name, so exact spell-name strings aren't
 * needed; unmatched teleports are simply left unbound (logged once).
 */
class TeleportSpellsPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private data class Dest(val keyword: String, val tile: Tile, val xp: Double, val type: TeleportType = TeleportType.MODERN)

    // All four spellbooks' teleports. Tiles/xp mirror TeleportSpell.kt; spell names confirmed
    // from the boot "teleport-spell unbound" log. canCast() gates by level + runes + spellbook,
    // so a keyword match a player can't cast is harmless. More specific keywords precede their
    // substrings (e.g. "west ardougne" before "ardougne") so firstOrNull short-circuits correctly.
    /** The free Home Teleport (every spellbook has one). Level 0, no runes, and crucially **no xp** —
     *  it must be matched before the "lumbridge" keyword below, otherwise "Lumbridge Home Teleport"
     *  binds to the 41-xp Teleport to Lumbridge and players farm Magic xp by teleporting home. */
    private val home = Dest("home", Tile(3222, 3218, 0), 0.0)

    private val dests = listOf(
        // Standard / modern.
        Dest("lumbridge", Tile(3222, 3218, 0), 41.0), // home
        Dest("varrock", Tile(3212, 3424, 0), 35.0),
        Dest("falador", Tile(2964, 3378, 0), 48.0),
        Dest("camelot", Tile(2757, 3477, 0), 55.5),
        Dest("seers", Tile(2757, 3477, 0), 55.5),
        Dest("west ardougne", Tile(2531, 3306, 0), 68.0),
        Dest("ardougne", Tile(2661, 3300, 0), 61.0),
        Dest("watchtower", Tile(2552, 3114, 0), 68.0),
        Dest("trollheim", Tile(2889, 3676, 0), 68.0),
        Dest("ape atoll", Tile(2761, 2782, 0), 74.0),
        Dest("kourend", Tile(1636, 3667, 0), 82.0),
        // Ancient (Carrallanger spelling matches the cache name).
        Dest("paddewwa", Tile(3097, 9882, 0), 64.0, TeleportType.ANCIENT),
        Dest("senntisten", Tile(3348, 3344, 0), 70.0, TeleportType.ANCIENT),
        Dest("kharyrll", Tile(3492, 3477, 0), 76.0, TeleportType.ANCIENT),
        Dest("lassar", Tile(3005, 3474, 0), 82.0, TeleportType.ANCIENT),
        Dest("dareeyak", Tile(2967, 3695, 0), 88.0, TeleportType.ANCIENT),
        Dest("carrallanger", Tile(3147, 3669, 0), 94.0, TeleportType.ANCIENT),
        Dest("annakarl", Tile(3295, 3886, 0), 100.0, TeleportType.ANCIENT),
        Dest("ghorrock", Tile(2969, 3875, 0), 106.0, TeleportType.ANCIENT),
        // Lunar.
        Dest("moonclan", Tile(2097, 3913, 0), 66.0, TeleportType.LUNAR),
        Dest("ourania", Tile(2454, 3232, 0), 69.0, TeleportType.LUNAR),
        Dest("waterbirth", Tile(2546, 3757, 0), 71.0, TeleportType.LUNAR),
        Dest("barbarian", Tile(2548, 3568, 0), 76.0, TeleportType.LUNAR),
        Dest("khazard", Tile(2656, 3157, 0), 80.0, TeleportType.LUNAR),
        Dest("catherby", Tile(2804, 3433, 0), 92.0, TeleportType.LUNAR),
        Dest("ice plateau", Tile(2981, 3943, 0), 96.0, TeleportType.LUNAR),
        // Arceuus.
        Dest("arceuus library", Tile(1633, 3836, 0), 9.0, TeleportType.ARCEUUS),
        Dest("draynor manor", Tile(3110, 3328, 0), 16.0, TeleportType.ARCEUUS),
        Dest("battlefront", Tile(1344, 3683, 0), 19.0, TeleportType.ARCEUUS),
        Dest("mind altar", Tile(2979, 3510, 0), 22.0, TeleportType.ARCEUUS),
        Dest("salve graveyard", Tile(3439, 3469, 0), 30.0, TeleportType.ARCEUUS),
        Dest("fenkenstrain", Tile(3547, 3528, 0), 50.0, TeleportType.ARCEUUS),
        Dest("cemetery", Tile(2966, 3763, 0), 82.0, TeleportType.ARCEUUS),
        Dest("barrows", Tile(3564, 3313, 0), 90.0, TeleportType.ARCEUUS),
        Dest("harmony", Tile(3797, 2860, 0), 74.0, TeleportType.ARCEUUS),
    )

    init {
        if (!MagicSpells.isLoaded()) MagicSpells.loadSpellRequirements(world)

        MagicSpells.getTeleportSpells().values.forEach { spell ->
            val name = spell.name.lowercase()
            // Home Teleport ("Lumbridge Home Teleport", "Home Teleport", …) is free and grants no xp —
            // catch it before the keyword table so it never binds to the paid Teleport to Lumbridge.
            val dest = if (name.contains("home")) home else dests.firstOrNull { name.contains(it.keyword) }
            if (dest != null) {
                onButton(spell.interfaceId, spell.component) { castTeleport(player, spell, dest) }
            } else {
                logger.info { "teleport-spell unbound (no destination): '${spell.name}'" }
            }
        }
    }

    private fun castTeleport(player: Player, spell: SpellMetadata, dest: Dest) {
        if (!MagicSpells.canCast(player, spell.lvl, spell.items, requiredBook = spell.spellbook)) return
        if (!player.canTeleport(dest.type)) return
        MagicSpells.removeRunes(player, spell.items)
        player.addXp(Skills.MAGIC, dest.xp)
        player.teleport(dest.tile, dest.type)
    }
}

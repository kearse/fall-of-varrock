package org.alter.plugins.content.magic.spellbook

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.cfg.Varbit
import org.alter.api.cfg.Varp
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

private val logger = KotlinLogging.logger {}

/**
 * **Quest-gated spells, unlocked.** Player report 2026-09-18: "spell book still lock behind a quest
 * so spells don't light up".
 *
 * This server has no OSRS quests, but the stock spellbook clientscript does not know that: it greys
 * a spell out by reading the progress varp/varbit of the quest that unlocks it, and on an account
 * that can never do those quests those values sit at 0 forever. The SERVER never checks any of them
 * (every spell here is level-gated only) â€” the spells are castable, they simply render dead, which
 * from the player's side is indistinguishable from "not implemented".
 *
 * This is exactly the treatment `PrayersPlugin` already gives Chivalry and Piety, which read
 * King's Ransom the same way. Same rule, same seam, applied to Magic.
 *
 * **Provenance of the numbers.** The completion values are not from memory â€” each one is column 19
 * of that quest's row in the cache's own quest DBTable, read with
 * `gradlew :game-server:questTable -PquestArgs="dump"`. That column was verified against all eight
 * quests this server already reuses for its native quest-tab rows (Cook's Assistant 2, Ernest 3,
 * Restless Ghost 5, Romeo & Juliet 100, Tree Gnome Village 9, Death Plateau 80, Wanted! 11, Dwarf
 * Cannon 11) before being trusted for these.
 *
 * **What is deliberately NOT here.** The Mage Arena god spells (Saradomin Strike / Claws of Guthix /
 * Flames of Zamorak) stay locked: Mage Arena I and II are real, shipped content on this server
 * (`minigames/magearena`), so those spells are earned rather than granted. Nothing here touches a
 * varp that one of our own quests drives â€” the reused OSRS quest varps are 0, 29, 31, 32, 63, 67,
 * 71, 107, 111, 122, 130, 144, 160, 178, 179, 273, 314, 657 and 1051, and the varbits below are
 * backed by varps 440, 823, 1003, 678 and 1566.
 *
 * Only ever RAISES a value, so a save that somehow holds a higher one is left alone.
 */
class SpellUnlocksPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        onLogin {
            var raised = 0
            for ((varp, complete, what) in QUEST_VARPS) {
                if (player.getVarp(varp) < complete) {
                    player.setVarp(varp, complete)
                    raised++
                    logger.debug { "spell-unlocks: ${player.username} varp $varp -> $complete ($what)" }
                }
            }
            for ((varbit, complete, what) in QUEST_VARBITS) {
                if (player.getVarbit(varbit) < complete) {
                    player.setVarbit(varbit, complete)
                    raised++
                    logger.debug { "spell-unlocks: ${player.username} varbit $varbit -> $complete ($what)" }
                }
            }
            if (raised > 0) {
                logger.info { "spell-unlocks: pinned $raised quest flag(s) for ${player.username}." }
            }
        }
    }

    private companion object {
        /** (varp, completed value, the spells it lights). Older quests store progress in a varp. */
        val QUEST_VARPS = listOf(
            Triple(Varp.PLAGUE_CITY, 29, "Ardougne Teleport"),
            Triple(Varp.WATCHTOWER, 13, "Watchtower Teleport"),
            Triple(Varp.EADGARS_RUSE, 110, "Trollheim Teleport"),
            Triple(Varp.UNDERGROUND_PASS, 11, "Iban Blast"),
            Triple(Varp.THE_TOURIST_TRAP, 30, "Magic Dart"),
        )

        /** (varbit, completed value, the spells it lights). Newer quests store progress in a varbit. */
        val QUEST_VARBITS = listOf(
            Triple(Varbit.DESERT_TREASURE, 15, "Ancient Magicks + Lvl-6 Enchant"),
            Triple(Varbit.LUNAR_DIPLOMACY, 190, "Lunar spellbook + Lvl-7 Enchant"),
            Triple(Varbit.DREAM_MENTOR, 28, "the rest of the Lunar book"),
            Triple(Varbit.RECIPE_FOR_DISASTER, 5, "Ape Atoll Teleport"),
            Triple(Varbit.CLIENT_OF_KOUREND, 7, "Kourend Castle Teleport"),
        )
    }
}

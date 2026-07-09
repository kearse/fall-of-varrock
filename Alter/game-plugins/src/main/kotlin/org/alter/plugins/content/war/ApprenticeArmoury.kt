package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.api.ext.openShop
import org.alter.game.model.entity.Player

/**
 * Rank-gated metal armoury (bronze → rune) selling only the LESSER pieces — med helm,
 * chainbody, platelegs, square shield. No full helm / platebody / kiteshield (players smith
 * those themselves).
 *
 * The per-tier shops are REGISTERED once by SmithingApprenticePlugin; this shared opener lets
 * any vendor (the cellar apprentice, or Horvik in the Lumbridge hub) show the tier befitting
 * the player's feudal [title] — a Peasant sees bronze/iron, a Lord sees up to rune.
 */
object ApprenticeArmoury {

    /** A shop per tier cap; the player opens the one matching their rank. */
    val TIER_SHOPS = linkedMapOf(
        ArmourTier.IRON to "Apprentice's Armoury (Iron)",
        ArmourTier.STEEL to "Apprentice's Armoury (Steel)",
        ArmourTier.BLACK to "Apprentice's Armoury (Black)",
        ArmourTier.MITHRIL to "Apprentice's Armoury (Mithril)",
        ArmourTier.ADAMANT to "Apprentice's Armoury (Adamant)",
        ArmourTier.RUNE to "Apprentice's Armoury (Rune)",
    )

    /** Clamp a rank's armour tier to the smith's range (iron..rune). */
    fun capFor(maxTier: ArmourTier): ArmourTier = when {
        maxTier.ordinal >= ArmourTier.RUNE.ordinal -> ArmourTier.RUNE
        maxTier.ordinal < ArmourTier.IRON.ordinal -> ArmourTier.IRON
        else -> maxTier
    }

    /** Open the armoury tier befitting [player]'s feudal rank. */
    fun open(player: Player) {
        val cap = capFor(player.title.maxTier)
        player.openShop(TIER_SHOPS.getValue(cap))
        player.message("The smith lays out wares befitting a ${player.title.display}.")
    }
}

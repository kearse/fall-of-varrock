package org.alter.plugins.content.war

import org.alter.game.model.Area
import org.alter.game.model.Tile

/**
 * The districts of **Fallen Varrock** — location identity ONLY (navigation, encounter placement,
 * captains, loot themes, art direction). There is deliberately no pressure meter, no "broken" or
 * "liberated" state and no persistent control: Varrock stays fallen (design authority, Sept 2026).
 * The old Falador reconquest meter that carried these labels is retired (git tag
 * `pre-block1-siege-engine` holds it).
 *
 * The four boxes partition [org.alter.plugins.content.npcs.worldspawns.WorldSpawnsPlugin.FALLEN_VARROCK]
 * (3155,3376 → 3300,3520) without overlap; [center] is a reachable street tile outside the bank
 * safe pockets (drawn from the hand-walked Varrock streets in [CityFrontiers]). TUNE in-game.
 */
enum class VarrockDistrict(
    val key: String,
    val display: String,
    val area: Area,
    val center: Tile,
) {
    /** The west of the city — the poor streets around the west bank. */
    SLUMS("slums", "the Slums", Area(3155, 3376, 3205, 3520), Tile(3198, 3429, 0)),

    /** The south approach and the central square. */
    OLD_MARKET("old-market", "the Old Market", Area(3206, 3376, 3235, 3441), Tile(3220, 3432, 0)),

    /** Everything east of the square — the east bank block and the eastern streets. */
    EAST_QUARTER("east-quarter", "the East Quarter", Area(3236, 3376, 3300, 3520), Tile(3245, 3429, 0)),

    /** North of the square: the museum, the palace approach and the palace itself. */
    MUSEUM_QUARTER("museum-quarter", "the Museum Quarter", Area(3206, 3442, 3235, 3520), Tile(3222, 3447, 0)),
    ;

    companion object {
        val all: List<VarrockDistrict> = values().toList()

        /** The district [tile] falls in, or null outside the fallen city. */
        fun at(tile: Tile): VarrockDistrict? = all.firstOrNull { it.area.contains(tile) }

        fun byKey(key: String): VarrockDistrict? = all.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

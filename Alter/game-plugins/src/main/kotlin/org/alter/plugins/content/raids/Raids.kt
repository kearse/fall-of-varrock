package org.alter.plugins.content.raids

import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.plugins.content.bosses.DropEntry
import org.alter.plugins.content.bosses.DropTable

/**
 * The raid registry (R0). Each [RaidDefinition] is entered with `::<key>` and run by
 * [RaidsPlugin] in its own instance. Real raids (Inferno R1, Nightmare R2, ToB R3, CoX R4)
 * get added here as their stages/mechanics are built; [SMOKE_TEST] validates the framework
 * end-to-end (allocate instance → teleport in → waves → reward → teardown).
 */
object Raids {

    /** A 3-wave instanced smoke test over the TzHaar cave floor (source coords TUNE). */
    private val SMOKE_TEST = RaidDefinition(
        key = "testraid",
        name = "Test Raid",
        sourceArea = Area(2368, 5056, 2432, 5120),
        entryTile = Tile(2400, 5085, 0),
        exitTile = Tile(3209, 3216, 0), // Lumbridge home
        levels = 1,
        stages = listOf(
            RaidStage("<col=ff0000>Wave 1...</col>", listOf(RaidSpawn("npc.goblin", Tile(2400, 5090, 0), 2))),
            RaidStage("<col=ff0000>Wave 2...</col>", listOf(RaidSpawn("npc.goblin", Tile(2400, 5090, 0), 3))),
            RaidStage("<col=ff0000>Final wave!</col>", listOf(RaidSpawn("npc.hill_giant", Tile(2400, 5090, 0), 1))),
        ),
        reward = DropTable(
            main = listOf(
                DropEntry("item.coins_995", 10_000, 30_000, weight = 3),
                DropEntry("item.death_rune", 50, 150, weight = 1),
            ),
        ),
        points = 10,
    )

    val all: List<RaidDefinition> = listOf(
        SMOKE_TEST,
    )
}

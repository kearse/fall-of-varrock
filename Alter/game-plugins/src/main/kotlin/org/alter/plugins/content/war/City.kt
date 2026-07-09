package org.alter.plugins.content.war

import org.alter.game.model.Tile

/**
 * A city in The War — one of the last safe havens players are citizens of.
 *
 * A city owns the [fronts] it is defended by (their [WarState] siege pressure
 * drives this city's open-world state) and the [respawnTile] its citizens return
 * to on death. The city is also where a citizen's bank lives; banking is spatial
 * (a bank within the city) so it needs no special state beyond respawning here.
 *
 * @param id stable numeric id persisted on the player ([org.alter.game.model.attr.CITY_ID_ATTR]).
 * @param key stable string handle (logs, config, debug commands).
 */
data class City(
    val id: Int,
    val key: String,
    val displayName: String,
    val respawnTile: Tile,
    val fronts: List<String>,
)

/**
 * Registry of the cities players can be citizens of. A city is simply named after the
 * real town it sits in (Lumbridge, Varrock, …). Add new cities here as the world grows —
 * each pairs with a [SiegeConfig] of the same `cityId` in [Sieges].
 */
object Cities {
    /** Default citizenship for brand-new / unassigned players. */
    const val DEFAULT_CITY_ID = 1

    val LUMBRIDGE = City(
        id = 1,
        key = "lumbridge",
        displayName = "Lumbridge",
        respawnTile = Tile(x = 3222, z = 3218, height = 0), // Lumbridge castle courtyard
        fronts = listOf(WarStatePlugin.FRONTIER_NORTH),
    )

    /** Varrock — the first HOSTILE target city (§3C). Not a citizenship home (no fronts of its own
     *  yet); registered so its campaign [cityId]=2 resolves to a name. */
    val VARROCK = City(
        id = 2,
        key = "varrock",
        displayName = "Varrock",
        respawnTile = Tile(x = 3213, z = 3424, height = 0), // Varrock square
        fronts = emptyList(),
    )

    private val byId: Map<Int, City> = listOf(LUMBRIDGE, VARROCK).associateBy { it.id }

    val all: Collection<City> get() = byId.values

    fun byId(id: Int): City? = byId[id]

    /** The city that owns [frontId] as one of its fronts, if any. */
    fun byFront(frontId: String): City? = byId.values.firstOrNull { frontId in it.fronts }

    fun default(): City = byId.getValue(DEFAULT_CITY_ID)
}

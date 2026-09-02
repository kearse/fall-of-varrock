package org.alter.plugins.content.war

import org.alter.game.model.Tile

/**
 * A city in The War — a place players can be citizens of, or that the war names as a target.
 *
 * A city owns the [respawnTile] its citizens return to on death. The city is also where a
 * citizen's bank lives; banking is spatial (a bank within the city) so it needs no special
 * state beyond respawning here.
 *
 * @param id stable numeric id persisted on the player ([org.alter.game.model.attr.CITY_ID_ATTR]).
 * @param key stable string handle (logs, config, debug commands; matches a [CampaignOp.cityKey]).
 */
data class City(
    val id: Int,
    val key: String,
    val displayName: String,
    val respawnTile: Tile,
)

/**
 * Registry of the cities players can be citizens of, plus the cities the war names. A city is
 * simply named after the real town it sits in (Lumbridge, Varrock, …). Add new cities here as
 * the world grows.
 */
object Cities {
    /** Default citizenship for brand-new / unassigned players. */
    const val DEFAULT_CITY_ID = 1

    /** Lumbridge — the Last Free City of Misthalin, everyone's home and the war's muster point. */
    val LUMBRIDGE = City(
        id = 1,
        key = "lumbridge",
        displayName = "Lumbridge",
        respawnTile = Tile(x = 3222, z = 3218, height = 0), // Lumbridge castle courtyard
    )

    /** Varrock — the FALLEN city, the commanders' hostile target ([Campaigns.VARROCK]). Not a
     *  citizenship home; registered so its campaign [cityId]=2 resolves to a name. */
    val VARROCK = City(
        id = 2,
        key = "varrock",
        displayName = "Varrock",
        respawnTile = Tile(x = 3213, z = 3424, height = 0), // Varrock square
    )

    /** Falador — a fortified surviving power and a safe hub (design authority, Sept 2026). Not a
     *  war target and not a citizenship home; registered so [cityId]=3 resolves to a name. */
    val FALADOR = City(
        id = 3,
        key = "falador",
        displayName = "Falador",
        respawnTile = Tile(x = 3013, z = 3353, height = 0), // Falador centre
    )

    private val byId: Map<Int, City> = listOf(LUMBRIDGE, VARROCK, FALADOR).associateBy { it.id }

    val all: Collection<City> get() = byId.values

    fun byId(id: Int): City? = byId[id]

    fun byKey(key: String): City? = byId.values.firstOrNull { it.key.equals(key, ignoreCase = true) }

    fun default(): City = byId.getValue(DEFAULT_CITY_ID)
}

package org.alter.plugins.content.war

/**
 * Shared handle on each city's live frontier [HostileZone], so the **campaign engine** can
 * read/suppress a city's enemy rings without holding a direct reference to [CityFrontierPlugin]
 * (the two run on separate timers). [CityFrontierPlugin] registers its zones here at boot;
 * [CampaignDirector] looks one up by city key to suppress respawns and count the garrison.
 */
object Frontiers {
    private val byCity = HashMap<String, HostileZone>()

    fun register(cityKey: String, zone: HostileZone) { byCity[cityKey] = zone }

    /** The frontier zone for [cityKey], or null if that city has none. */
    fun zone(cityKey: String): HostileZone? = byCity[cityKey]
}

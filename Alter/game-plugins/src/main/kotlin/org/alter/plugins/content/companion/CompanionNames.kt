package org.alter.plugins.content.companion

/**
 * Real-sounding names for companions so they don't read as "BizzyZ's melee". Every companion is
 * shown as **"Sir <Name>"** (via TITLED_NAME_ATTR, the "Sir" in OSRS title-orange) — the orange
 * "Sir" rank is the server's tell that a "player" is actually a bot companion, so no one mistakes
 * one for a real account.
 */
object CompanionNames {
    private val POOL = listOf(
        "Garrick", "Thorne", "Kael", "Bryn", "Aldric", "Roderick", "Gareth", "Cedric", "Tristan",
        "Magnus", "Darius", "Lucan", "Rowan", "Edmund", "Percival", "Hadrian", "Lysander", "Caspian",
        "Orin", "Doran", "Fenwick", "Alaric", "Brennan", "Corwin", "Dunstan", "Eamon", "Galen",
        "Hollis", "Idris", "Joran", "Kellan", "Lorne", "Merrick", "Nolan", "Osric", "Perrin", "Ronan",
        "Soren", "Talon", "Ulric", "Varian", "Wystan", "Yorick", "Drake", "Bram", "Cael", "Devin",
        "Emmett", "Gideon",
    )

    /** A random name, preferring one not already in [taken] (a player's other companions). */
    fun pick(taken: Collection<String> = emptyList()): String =
        POOL.shuffled().firstOrNull { it !in taken } ?: POOL.random()

    /** Pool index of [name] (so it can be pushed to the client as a small int instead of a string).
     *  The "Fall of Varrock Companions" RuneLite plugin holds a copy of this same POOL, keyed by this index. */
    fun indexOf(name: String): Int = POOL.indexOf(name)
}

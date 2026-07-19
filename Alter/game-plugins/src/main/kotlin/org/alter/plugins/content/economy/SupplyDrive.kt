package org.alter.plugins.content.economy

/**
 * **Supply Drive** state (the Mire's rotating demand — Phase C of the war-supply loop).
 *
 * Every drive window one category of Quartermaster supplies (food / potions / bars) is in HIGH
 * DEMAND and pays [MULTIPLIER]× War Effort + realm supply meter on deposit. Rotated + broadcast
 * by [SupplyDrivePlugin]; read by [SupplyDepot.deposit]. Gives grinding sessions a rhythm — chase
 * the hot category, or bank ahead for the window you know is coming.
 */
object SupplyDrive {

    // NB: keep this enum's order aligned with [SupplyDepot.CATEGORIES] — the client window maps
    // the active drive to a tab by ordinal.
    enum class Category(val display: String, val wares: () -> Map<String, Int>) {
        FOOD("Cooked food", { SupplyDepot.FOOD }),
        POTIONS("Finished potions", { SupplyDepot.POTIONS }),
        BARS("Smithed bars", { SupplyDepot.BARS }),
        ORES("Mined ores", { SupplyDepot.ORES }),
        LOGS("Felled logs", { SupplyDepot.LOGS }),
        RAW_FISH("Raw fish", { SupplyDepot.RAW_FISH }),
    }

    const val MULTIPLIER = 2

    @Volatile
    var active: Category? = null

    /** The War-Effort multiplier for depositing [key] right now. */
    fun multiplierFor(key: String): Int {
        val cat = active ?: return 1
        return if (cat.wares().containsKey(key)) MULTIPLIER else 1
    }
}

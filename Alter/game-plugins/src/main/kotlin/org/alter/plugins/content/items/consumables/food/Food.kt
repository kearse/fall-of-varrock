package org.alter.plugins.content.items.food


enum class Food(
    val item: String,
    val heal: Int = 0,
    val overheal: Boolean = false,
    val replacement: Int = -1,
    val tickDelay: Int = 3,
    val comboFood: Boolean = false,
    /** Blighted consumables can only be eaten inside the Wilderness. */
    val wildernessOnly: Boolean = false,
) {
    /**
     * Sea food.
     */
    SHRIMP(item = "item.shrimps", heal = 3),
    SARDINE(item = "item.sardine", heal = 4),
    HERRING(item = "item.herring", heal = 5),
    MACKEREL(item = "item.mackerel", heal = 6),
    TROUT(item = "item.trout", heal = 7),
    COD(item = "item.cod", heal = 7),
    PIKE(item = "item.pike", heal = 8),
    SALMON(item = "item.salmon", heal = 9),
    TUNA(item = "item.tuna", heal = 10),
    RAINBOW(item = "item.rainbow_fish", heal = 11),
    CAVEEEL(item = "item.cave_eel", heal = 9),
    LOBSTER(item = "item.lobster", heal = 12),
    BASS(item = "item.bass", heal = 13),
    SWORDFISH(item = "item.swordfish", heal = 14),
    MONKFISH(item = "item.monkfish", heal = 16),
    KARAMBWAN(item = "item.cooked_karambwan", heal = 18, comboFood = true),
    SHARK(item = "item.shark", heal = 20),
    SEATURTLE(item = "item.sea_turtle", heal = 21),
    MANTA_RAY(item = "item.manta_ray", heal = 22),
    DARK_CRAB(item = "item.dark_crab", heal = 22),
    ANGLERFISH(item = "item.anglerfish", overheal = true),
    // Neypotzli (Moons of Peril) camp food — heal values per the OSRS wiki (TUNE if disputed).
    COOKED_BREAM(item = "item.cooked_bream", heal = 12),
    COOKED_MOONLIGHT_ANTELOPE(item = "item.cooked_moonlight_antelope", heal = 26),

    /**
     * Blighted food — Wilderness-only copies of the PvP staples (OSRS). Gated in
     * [org.alter.plugins.content.items.consumables.food.Foods.canEat]. Player report
     * 2026-09-02: these dropped from the zombie-pirate table but had no eat handler.
     */
    BLIGHTED_MANTA_RAY(item = "item.blighted_manta_ray", heal = 22, wildernessOnly = true),
    BLIGHTED_ANGLERFISH(item = "item.blighted_anglerfish", overheal = true, wildernessOnly = true),
    BLIGHTED_KARAMBWAN(item = "item.blighted_karambwan", heal = 18, comboFood = true, wildernessOnly = true),

    /**
     * Meat.
     */
    CHICKEN(item = "item.cooked_chicken", heal = 3),
    MEAT(item = "item.cooked_meat", heal = 3),
    ROASTBEASTMEAT(item = "item.roast_beast_meat", heal = 8),
    KEBAB(item = "item.ugthanki_kebab", heal = 19),

    /**
     * Pastries.
     */
    BREAD(item = "item.bread", heal = 5),

    /**
     * Other.
     */
    ONION(item = "item.onion", heal = 1),
    ;

    companion object {
        val values = enumValues<Food>()
    }
}

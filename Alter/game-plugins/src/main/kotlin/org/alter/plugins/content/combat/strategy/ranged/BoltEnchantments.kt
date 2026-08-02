package org.alter.plugins.content.combat.strategy.ranged

import org.alter.api.EquipmentType
import org.alter.api.ext.hasEquipped
import org.alter.game.model.entity.Player

/**
 * Enchanted bolt special effects ("procs"), rolled per shot at attack time.
 *
 * Proc chances are the OSRS PvM base rates (Ruby "Blood Forfeit" 6%, Diamond
 * "Armour Piercing" 10%, Dragonstone "Dragon's Breath" 6%, Onyx "Life Leech" 11%,
 * Emerald "Magical Poison" 55%, Opal "Lucky Lightning" 5%, Pearl "Sea Curse" 6%,
 * Sapphire "Clear Mind" 5% — OSRS Wiki, Enchanted bolts). Ruby and Diamond procs
 * bypass the accuracy roll entirely; the rest only matter on a landed hit.
 */
object BoltEnchantments {
    enum class Effect(val procPercent: Int) {
        OPAL(5),
        SAPPHIRE(5),
        PEARL(6),
        EMERALD(55),
        RUBY(6),
        DIAMOND(10),
        DRAGONSTONE(6),
        ONYX(11),
    }

    private val AMMO: List<Pair<Effect, Array<String>>> =
        listOf(
            Effect.OPAL to arrayOf("item.opal_bolts_e", "item.opal_dragon_bolts_e"),
            Effect.SAPPHIRE to arrayOf("item.sapphire_bolts_e", "item.sapphire_dragon_bolts_e"),
            Effect.PEARL to arrayOf("item.pearl_bolts_e", "item.pearl_dragon_bolts_e"),
            Effect.EMERALD to arrayOf("item.emerald_bolts_e", "item.emerald_dragon_bolts_e"),
            Effect.RUBY to arrayOf("item.ruby_bolts_e", "item.ruby_dragon_bolts_e"),
            Effect.DIAMOND to arrayOf("item.diamond_bolts_e", "item.diamond_dragon_bolts_e"),
            Effect.DRAGONSTONE to arrayOf("item.dragonstone_bolts_e", "item.dragonstone_dragon_bolts_e"),
            Effect.ONYX to arrayOf("item.onyx_bolts_e", "item.onyx_dragon_bolts_e"),
        )

    /** The enchanted-bolt effect matching the player's equipped ammo, if any. */
    fun effectFor(player: Player): Effect? =
        AMMO.firstOrNull { (_, items) ->
            items.any { runCatching { player.hasEquipped(EquipmentType.AMMO, it) }.getOrDefault(false) }
        }?.first

    /** Damage a Ruby proc deals: 20% of the target's current HP, capped at 100. */
    fun rubyDamage(targetCurrentHp: Int): Int = minOf(100, targetCurrentHp / 5)

    /** HP the caster sacrifices on a Ruby proc: 10% of their current HP. */
    fun rubySacrifice(casterCurrentHp: Int): Int = casterCurrentHp / 10
}

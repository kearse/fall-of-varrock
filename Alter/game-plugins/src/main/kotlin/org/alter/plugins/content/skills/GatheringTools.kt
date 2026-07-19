package org.alter.plugins.content.skills

import dev.openrune.cache.CacheManager.getItem
import org.alter.api.EquipmentType
import org.alter.api.Skills
import org.alter.game.model.entity.Player

/**
 * Axe/pickaxe tier lookup for the gathering skills.
 *
 * Woodcutting and Mining used to play a single hardcoded animation (bronze axe 879 /
 * bronze pickaxe 625) no matter what tool you held, so an adamant axe still swung a
 * bronze one. This resolves the BEST tool the player actually carries (inventory or
 * equipped) and hands back its real chop/swing animation plus the level it needs.
 */
object GatheringTools {

    /** A tool tier: [key] is matched against the cache item name. */
    data class Tool(val key: String, val level: Int, val anim: Int)

    /** Result of a lookup: [tool] is null when the player holds tools but can't use any of them. */
    data class Held(val tool: Tool?, val anyHeld: Boolean)

    // Best -> worst. Level is the Woodcutting requirement to *use* the axe.
    private val AXES = listOf(
        Tool("crystal", 71, 8324),
        Tool("3rd age", 61, 7264),
        Tool("infernal", 61, 2117),
        Tool("dragon", 61, 2846),
        Tool("gilded", 41, 8303),
        Tool("rune", 41, 867),
        Tool("adamant", 31, 869),
        Tool("mithril", 21, 871),
        Tool("black", 11, 873),
        Tool("steel", 6, 875),
        Tool("iron", 1, 877),
        Tool("bronze", 1, 879),
    )

    // Best -> worst. Level is the Mining requirement to *use* the pickaxe.
    private val PICKAXES = listOf(
        Tool("crystal", 71, 8347),
        Tool("3rd age", 61, 7283),
        Tool("infernal", 61, 4482),
        Tool("dragon", 61, 7139),
        Tool("gilded", 41, 8313),
        Tool("rune", 41, 624),
        Tool("adamant", 31, 628),
        Tool("mithril", 21, 629),
        Tool("black", 11, 3873),
        Tool("steel", 6, 627),
        Tool("iron", 1, 626),
        Tool("bronze", 1, 625),
    )

    fun bestAxe(player: Player): Held = best(player, AXES, Skills.WOODCUTTING, ::isAxe)

    fun bestPickaxe(player: Player): Held = best(player, PICKAXES, Skills.MINING, ::isPickaxe)

    private fun best(
        player: Player,
        tiers: List<Tool>,
        skill: Int,
        isTool: (String) -> Boolean,
    ): Held {
        val level = player.getSkills().getCurrentLevel(skill)
        var anyHeld = false
        var best: Tool? = null
        for (name in heldItemNames(player)) {
            if (!isTool(name)) continue
            anyHeld = true
            val tier = tierOf(name, tiers) ?: continue
            if (level < tier.level) continue
            if (best == null || tiers.indexOf(tier) < tiers.indexOf(best)) best = tier
        }
        return Held(best, anyHeld)
    }

    private fun heldItemNames(player: Player): List<String> {
        val names = ArrayList<String>()
        for (i in 0 until player.inventory.capacity) {
            val item = player.inventory[i] ?: continue
            names += getItem(item.id).name?.lowercase() ?: continue
        }
        player.equipment[EquipmentType.WEAPON.id]?.let { weapon ->
            getItem(weapon.id).name?.lowercase()?.let { names += it }
        }
        return names
    }

    private fun tierOf(name: String, tiers: List<Tool>): Tool? {
        // An uncharged infernal tool swings as its dragon base, not the infernal variant.
        if (name.contains("infernal") && name.contains("uncharged")) return tiers.first { it.key == "dragon" }
        return tiers.firstOrNull { name.contains(it.key) }
    }

    /**
     * A choppable axe: excludes pickaxes, combat axes (battleaxe / throwing axe) and the
     * smithing parts (axe heads, broken axes) that merely have "axe" in the name.
     */
    private fun isAxe(name: String): Boolean =
        name.contains("axe") &&
            !name.contains("pickaxe") &&
            !name.contains("battleaxe") &&
            !name.contains("throwing axe") &&
            !name.contains("thrownaxe") &&
            !name.contains("axe head") &&
            !name.contains("broken")

    private fun isPickaxe(name: String): Boolean =
        name.contains("pickaxe") &&
            !name.contains("broken") &&
            !name.contains("handle") &&
            !name.contains("upgrade kit")
}

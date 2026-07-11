package org.alter.plugins.content.war

import org.alter.api.ext.message
import org.alter.api.ext.npc
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.attr.KILLER_ATTR
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.timer.TimerKey
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

/**
 * The **Goblin Warren** (The War / "venture out" content - `docs/war-system-design.md`):
 * a hostile area east of Lumbridge where the besieging horde musters. Tougher
 * monsters than the city goblins - hill giant brutes, ogres, and the goblin generals
 * Bentnoze & Wartface as bosses - that fight back and drop escalating coin piles, so
 * players have a dangerous place to earn the coins that buy ranks & armour.
 *
 * Population is held by [HostileZone]; coin loot is dropped here on a player kill.
 */
class GoblinWarrenPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val warren = HostileZone(
            name = "Goblin Warren",
            packs = listOf(
                MonsterPack("npc.hill_giant", count = 6, walkRadius = 8, staging = listOf(
                    Tile(3288, 3248, 0), Tile(3296, 3255, 0), Tile(3300, 3242, 0),
                )),
                MonsterPack("npc.ogre", count = 4, walkRadius = 6, staging = listOf(
                    Tile(3308, 3250, 0), Tile(3312, 3258, 0),
                )),
                MonsterPack("npc.general_bentnoze", count = 1, walkRadius = 4, staging = listOf(Tile(3318, 3250, 0))),
                MonsterPack("npc.general_wartface", count = 1, walkRadius = 4, staging = listOf(Tile(3320, 3252, 0))),
            ),
        )

        val warrenTimer = TimerKey()
        onWorldInit { world.timers[warrenTimer] = TICK }
        onTimer(warrenTimer) {
            warren.tick(world)
            world.timers[warrenTimer] = TICK
        }

        // Coin loot, scaling with the monster's danger (only a player kill loots).
        coinLoot("npc.hill_giant", 100, 250)
        coinLoot("npc.ogre", 250, 600)
        coinLoot("npc.general_bentnoze", 2000, 4000)
        coinLoot("npc.general_wartface", 2000, 4000)
    }

    private fun coinLoot(npcName: String, min: Int, max: Int) {
        onNpcDeath(npcName) {
            val monster = npc
            val killer = monster.attr[KILLER_ATTR]?.get() as? Player ?: return@onNpcDeath
            val amount = (min..max).random()
            world.spawn(GroundItem(getRSCM("item.coins_995"), amount, monster.tile))
            killer.message("<col=ffcf48>The ${monster.def.name?.lowercase() ?: "creature"} drops ${"%,d".format(amount)} coins.</col>")
        }
    }

    private companion object {
        const val TICK = 5 // ~3s population upkeep
    }
}

package org.alter.plugins.content.areas.lumbridge.spawns

import org.alter.game.Server
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository

/**Example
 *spawnNpc(npc = "npc.ID", x = xxxx, y = zzzz, height = 0, walk = 0, direction = Direction.NORTH)
 */


class SpawnPlugin(
    r: PluginRepository,
    world: World,
    server: Server
) : KotlinPlugin(r, world, server) {
    init {
        // Home-hub declutter: the default NPCs that stood on the OPEN castle ground floor
        // (three men, two rats and the imp, all level 0 inside the building) were removed so the
        // floor reads as a social hub. Kept: the upstairs men/women (height 1) and the courtyard
        // fauna by the south steps (a woman + four rats). The matching config duplicates were also
        // dropped from data/cfg/spawns/npc_spawns.json.
        spawnNpc(npc = "npc.man_3106", x = 3206, z = 3219, walkRadius = 20, height = 1, direction = Direction.SOUTH)
        spawnNpc(npc = "npc.man_3108", x = 3209, z = 3215, walkRadius = 20, height = 1, direction = Direction.SOUTH)
        spawnNpc(npc = "npc.woman_3111", x = 3211, z = 3213, walkRadius = 20, height = 1, direction = Direction.SOUTH)
        spawnNpc(npc = "npc.woman_3111", x = 3217, z = 3205, walkRadius = 20, direction = Direction.NORTH)
        spawnNpc(npc = "npc.rat_2854", x = 3207, z = 3202, walkRadius = 10, direction = Direction.NORTH)
        spawnNpc(npc = "npc.rat_2854", x = 3205, z = 3204, walkRadius = 10, direction = Direction.NORTH)
        spawnNpc(npc = "npc.rat_2854", x = 3206, z = 3202, walkRadius = 10, direction = Direction.NORTH)
        spawnNpc(npc = "npc.rat_2854", x = 3207, z = 3203, walkRadius = 10, direction = Direction.NORTH)
        spawnNpc(npc = "npc.sheep_2789", x = 3196, z = 3263, walkRadius = 10, direction = Direction.NORTH)
        spawnNpc(npc = "npc.sheep_2789", x = 3199, z = 3261, walkRadius = 10, direction = Direction.EAST)
        spawnNpc(npc = "npc.sheep_2789", x = 3201, z = 3272, walkRadius = 10, direction = Direction.SOUTH)
        spawnNpc(npc = "npc.sheep_2789", x = 3202, z = 3268, walkRadius = 10, direction = Direction.NORTH)
        spawnNpc(npc = "npc.sheep_2789", x = 3206, z = 3266, walkRadius = 10, direction = Direction.WEST)
        spawnNpc(npc = "npc.ram_1265", x = 3201, z = 3263, walkRadius = 10, direction = Direction.NORTH)
        spawnNpc(npc = "npc.ram_1265", x = 3207, z = 3271, walkRadius = 10, direction = Direction.SOUTH)
        spawnNpc(npc = "npc.ram_1265", x = 3195, z = 3271, walkRadius = 10, direction = Direction.EAST)
        spawnNpc(npc = "npc.huge_spider_134", x = 3168, z = 3243, walkRadius = 7, direction = Direction.SOUTH)
        spawnNpc(npc = "npc.giant_spider", x = 3165, z = 3251, walkRadius = 7, direction = Direction.EAST)
        spawnNpc(npc = "npc.giant_spider_3018", x = 3246, z = 3248, walkRadius = 7, direction = Direction.EAST)
        spawnNpc(npc = "npc.giant_spider_3017", x = 3241, z = 3245, walkRadius = 7, direction = Direction.SOUTH)
        spawnNpc(npc = "npc.giant_spider_3017", x = 3253, z = 3243, walkRadius = 7, direction = Direction.WEST)
        spawnNpc(npc = "npc.giant_spider_3017", x = 3245, z = 3235, walkRadius = 7, direction = Direction.NORTH)
        spawnNpc(npc = "npc.giant_spider_3017", x = 3253, z = 3234, walkRadius = 7, direction = Direction.WEST)
        spawnNpc(npc = "npc.goblin_3028", x = 3264, z = 3232, walkRadius = 8, direction = Direction.WEST)
        spawnNpc(npc = "npc.goblin_2246", x = 3247, z = 3244, walkRadius = 8, direction = Direction.NORTH)
        spawnNpc(npc = "npc.goblin_2248", x = 3244, z = 3244, walkRadius = 8, direction = Direction.NORTH)
        spawnNpc(npc = "npc.goblin_2484", x = 3241, z = 3242, walkRadius = 8, direction = Direction.SOUTH)
        spawnNpc(npc = "npc.goblin_3028", x = 3253, z = 3245, walkRadius = 8, direction = Direction.WEST)
        spawnNpc(npc = "npc.goblin_3039", x = 3255, z = 3236, walkRadius = 8, direction = Direction.WEST)
        spawnNpc(npc = "npc.goblin_3054", x = 3256, z = 3230, walkRadius = 8, direction = Direction.WEST)
        spawnNpc(npc = "npc.goblin_3028", x = 3221, z = 3271, walkRadius = 8, direction = Direction.WEST)
        // TODO: Add more goblin spawns
        spawnNpc(npc = "npc.drunken_man", x = 3230, z = 3241, walkRadius = 3, direction = Direction.EAST)
        spawnNpc(npc = "npc.man_3109", x = 3228, z = 3239, walkRadius = 3, direction = Direction.WEST)
        spawnNpc(npc = "npc.woman_3112", x = 3229, z = 3238, walkRadius = 3, direction = Direction.SOUTH)
        spawnNpc(npc = "npc.man_3014", x = 3231, z = 3236, walkRadius = 3, direction = Direction.WEST)
        spawnNpc(npc = "npc.zombie_rat_3970", x = 3246, z = 3198, walkRadius = 5, direction = Direction.WEST)
        spawnNpc(npc = "npc.zombie_rat", x = 3239, z = 3198, walkRadius = 5, direction = Direction.WEST)

        // Item spawns
        //
        // Home-hub declutter: every ground-item spawn that sat inside the castle footprint
        // (outer shell x=3204..3227, z=3207..3230) was removed so the opened ground floor,
        // the first floor and the bank roof read as clean social/service space instead of a
        // litter of one-gp junk under the players' feet. Dropped spawns were:
        //   level 0 - mind rune (3206,3208), bronze arrow (3205,3227), knife (3205,3212),
        //             pot (3209,3214), bowl (3208,3214), jug (3211,3212)
        //   level 1 - bronze dagger (3213,3216)
        //   level 2 - 4x logs (3205,3224 / 3205,3226 / 3208,3225 / 3209,3224)
        // The knife south of the castle stays: it is outside the walls, on the courtyard
        // ground by the south steps where the rats and the woman spawn.
        spawnItem(item = "item.knife", amount = 1, x = 3224, z = 3202)

        // Stray altar (object.altar_409 @ 3222,3215) removed from the courtyard.
    }
}

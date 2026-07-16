package org.alter.plugins.content.skills.mining

import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.EquipmentType
import org.alter.api.Skills
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * A basic **Mining** skill (the server had none). Click a rock with a pickaxe to
 * mine ore for XP; the rock depletes and respawns. Rocks are spawned where we want
 * mining to be available — first batch is in/around Lumbridge Castle so players have
 * a safe, in-city way to earn (per the gated-economy plan, `docs/economy.md`).
 *
 * Mirrors the thieving-stall pattern (interact → check tool/level → reward → deplete
 * → respawn). Rock object ids were found by scanning the cache for a "Mine" action.
 */
class MiningPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** A kind of rock: its object id, the ore it gives, level req, XP, and the ticks
     *  it takes to mine one ore ([mineTicks]) — the rock never depletes. */
    private data class Rock(val objId: Int, val ore: String, val oreName: String, val level: Int, val xp: Double, val mineTicks: Int)

    /** Where each rock is placed in the world. */
    private data class RockSpawn(val rock: Rock, val tile: Tile)

    private val copper = Rock(11161, "item.copper_ore", "copper ore", 1, 17.5, 2)
    private val tin = Rock(11361, "item.tin_ore", "tin ore", 1, 17.5, 2)
    private val clay = Rock(11362, "item.clay", "clay", 1, 5.0, 2)
    private val iron = Rock(11364, "item.iron_ore", "iron ore", 15, 35.0, 3)
    private val silver = Rock(11368, "item.silver_ore", "silver ore", 20, 40.0, 3)
    private val coal = Rock(11366, "item.coal", "coal", 30, 50.0, 4)
    private val gold = Rock(11370, "item.gold_ore", "gold ore", 40, 65.0, 5)
    private val mithril = Rock(11372, "item.mithril_ore", "mithril ore", 55, 80.0, 6)
    private val adamant = Rock(11374, "item.adamantite_ore", "adamantite ore", 70, 95.0, 8)
    private val rune = Rock(11376, "item.runite_ore", "runite ore", 85, 125.0, 11)
    private val amethyst = Rock(11388, "item.amethyst", "amethyst", 92, 240.0, 4)
    // Rune essence — the Runecraft supply. Level 1, mined in the Mire skilling column so it feeds
    // the fire altar a few tiles north. Object 16687 = the 1x1 "Rune essence" rock (has a Mine action).
    private val essence = Rock(16687, "item.rune_essence", "rune essence", 1, 5.0, 2)

    // Lumbridge Castle cellar (underground), laid out per the user's plan. The room is
    // opened up at boot by openMineRoom() into one chamber. Outer-wall tiles are dead (edge
    // collision), so each rock sits on the INNER-RING tile against its wall and is mined
    // from the open core beside it. (Stand tiles verified walkable in-game via the probe.)
    //   EAST wall (x3219): high tier — Mithril, Adamant, Rune, Amethyst   (stand on x3218)
    //   SOUTH wall (z9615): Clay, Silver, Gold, Copper, Tin, Iron, Coal   (stand on z9616)
    //   WEST = furnace, NORTH = anvils (see SmithingPlugin).
    private val spawns = listOf(
        // East wall — high tier
        RockSpawn(mithril, Tile(3219, 9620, 0)),
        RockSpawn(adamant, Tile(3219, 9621, 0)),
        RockSpawn(rune, Tile(3219, 9622, 0)),
        // Requested (3219,9623) is the NE corner — unreachable (no walkable neighbour), so
        // placed one tile west at (3218,9623), mined from the south (3218,9622).
        RockSpawn(amethyst, Tile(3218, 9623, 0)),
        // South wall — low/mid tier, west-to-east
        RockSpawn(clay, Tile(3211, 9615, 0)),
        RockSpawn(silver, Tile(3212, 9615, 0)),
        RockSpawn(gold, Tile(3213, 9615, 0)),
        RockSpawn(copper, Tile(3214, 9615, 0)),
        RockSpawn(tin, Tile(3215, 9615, 0)),
        RockSpawn(iron, Tile(3216, 9615, 0)),
        RockSpawn(coal, Tile(3217, 9615, 0)),
        // The Mire — collection grounds rock field (swamp, south of the graveyard working yard). The
        // cellar-only openMineRoom()/clearCellarFurniture() operate on CELLAR only, so these are safe.
        // The Mire — mining co-located with smithing: a 2-wide column in the open ground just south of
        // the furnace (x3237-3238, z3189 down to z3184). Mine here, smelt at the furnace, smith at the
        // anvil — one tight loop. Mined from the x3236 / x3239 sides.
        RockSpawn(copper, Tile(3237, 3189, 0)),
        RockSpawn(clay, Tile(3238, 3189, 0)),
        RockSpawn(tin, Tile(3237, 3188, 0)),
        RockSpawn(iron, Tile(3238, 3188, 0)),
        RockSpawn(iron, Tile(3237, 3187, 0)),
        RockSpawn(silver, Tile(3238, 3187, 0)),
        RockSpawn(coal, Tile(3237, 3186, 0)),
        RockSpawn(coal, Tile(3238, 3186, 0)),
        RockSpawn(gold, Tile(3237, 3185, 0)),
        RockSpawn(mithril, Tile(3238, 3185, 0)),
        RockSpawn(adamant, Tile(3237, 3184, 0)),
        RockSpawn(rune, Tile(3238, 3184, 0)),
        RockSpawn(amethyst, Tile(3237, 3183, 0)), // endgame AFK tier for the Bog Quarry (swamp buildout 2026-07-04)
        // Rune essence — two rocks extending the column one tile north (walkable ground verified in
        // the cache collision dump; mined from x3236 / x3239), feeding the Mire fire altar.
        RockSpawn(essence, Tile(3237, 3190, 0)),
        RockSpawn(essence, Tile(3238, 3190, 0)),
    )

    private val rockTypes = listOf(copper, tin, clay, iron, silver, coal, gold, mithril, adamant, rune, amethyst, essence)

    init {
        onWorldInit {
            clearCellarFurniture()
            openMineRoom()
            spawns.forEach { world.spawn(DynamicObject(id = it.rock.objId, type = OBJ_TYPE, rot = 0, tile = it.tile)) }
            // A respawning bronze pickaxe so new players can always grab a starter tool.
            // On a tile that was ALWAYS plain floor (3213,9622 had a cleared object, which
            // left the item hovering) — by the ladder so it's grabbed on the way in.
            val pick = GroundItem(getRSCM("item.bronze_pickaxe"), 1, Tile(3210, 9617, 0))
            pick.respawnCycles = PICKAXE_RESPAWN
            world.spawn(pick)
        }

        rockTypes.distinctBy { it.objId }.forEach { rock ->
            // Guard the bind: onObjOption("Mine") THROWS if the cache def lacks that exact
            // action, which would drop the WHOLE plugin (every rock). Skip+log instead so a
            // bad/uncertain id (e.g. the newly added clay/gold) can never kill the mine.
            val hasMine = try {
                getObject(rock.objId).actions?.filterNotNull()?.any { it.equals("Mine", true) } == true
            } catch (e: Exception) { false }
            if (!hasMine) {
                logger.warn { "mining: object ${rock.objId} (${rock.oreName}) has no 'Mine' action — skipping bind." }
                return@forEach
            }
            onObjOption(obj = rock.objId, option = "Mine") {
                val obj = player.getInteractingGameObj()
                player.queue { mine(this, player, obj, rock) }
            }
        }
    }

    private suspend fun mine(task: QueueTask, player: Player, obj: GameObject, rock: Rock) {
        if (!player.hasPickaxe()) {
            player.message("You need a pickaxe to mine this rock.")
            return
        }
        if (player.getSkills().getCurrentLevel(Skills.MINING) < rock.level) {
            player.message("You need a Mining level of ${rock.level} to mine this rock.")
            return
        }
        if (player.inventory.isFull) {
            player.message("Your inventory is too full to hold any more ${rock.oreName}.")
            return
        }

        player.faceTile(obj.tile)
        player.message("You swing your pickaxe at the rock...")
        // Auto-mine: the rock never depletes, so keep mining one ore every
        // [Rock.mineTicks] until the inventory is full. NOT locked — walking or
        // clicking elsewhere cancels this task (interruptQueues), and the tile guard
        // stops it if the player moves, so they're never frozen in place.
        val spot = player.tile
        while (player.tile == spot) {
            player.animate(MINE_ANIM)
            task.wait(rock.mineTicks)
            if (player.tile != spot) break
            if (player.inventory.isFull) {
                player.message("Your inventory is too full to hold any more ${rock.oreName}.")
                break
            }
            player.inventory.add(item = getRSCM(rock.ore), amount = 1)
            player.addXp(Skills.MINING, rock.xp)
            player.message("You manage to mine some ${rock.oreName}.")
            // The Recruit Trials SUPPLY step detects the mined ore from its own poll (no hook needed here).
            org.alter.plugins.content.skills.slayer.ResourceContracts.onGather(player, getRSCM(rock.ore)) // Vannaka resource contract
        }
    }

    /**
     * Open the cellar interior into one chamber so ores can line the four walls with a
     * clear middle. Removes the internal partition walls + cave scenery inside [MINE_ROOM]
     * (the outer walls, which are outside this box, are kept). Protects the exit [LADDER_ID]
     * and never touches our own [DynamicObject] spawns (rocks/furnace/anvil), so it's safe
     * regardless of plugin load order. Re-runs each boot (static map objects reload).
     */
    private fun openMineRoom() {
        var removed = 0
        for (x in MINE_ROOM.bottomLeftX..MINE_ROOM.topRightX) {
            for (z in MINE_ROOM.bottomLeftY..MINE_ROOM.topRightY) {
                for (type in 0..22) {
                    val obj = world.getObject(Tile(x, z, 0), type) ?: continue
                    if (obj is DynamicObject) continue // our rocks/furnace/anvil
                    if (obj.id == LADDER_ID) continue // keep the way out
                    world.remove(obj)
                    removed++
                }
            }
        }
        logger.info { "cellar-mine: opened room ($MINE_ROOM), removed $removed static wall/scenery object(s)." }
    }

    /** Strip the decorative furniture out of the cellar so it reads as a mine. */
    private fun clearCellarFurniture() {
        var removed = 0
        for (x in CELLAR.bottomLeftX..CELLAR.topRightX) {
            for (z in CELLAR.bottomLeftY..CELLAR.topRightY) {
                for (type in 0..22) {
                    val obj = world.getObject(Tile(x, z, 0), type) ?: continue
                    val name = getObject(obj.id).name?.lowercase() ?: continue
                    if (FURNITURE.any { name.contains(it) }) {
                        logger.info { "cellar-mine: removing '$name' (id=${obj.id}, type=$type) at $x,$z" }
                        world.remove(obj)
                        removed++
                    }
                }
            }
        }
        logger.info { "cellar-mine: cleared $removed furniture object(s) from the cellar." }
    }

    private fun Player.hasPickaxe(): Boolean {
        for (i in 0 until inventory.capacity) {
            val item = inventory[i] ?: continue
            if (getItem(item.id).name?.contains("pickaxe", ignoreCase = true) == true) return true
        }
        val weapon = equipment[EquipmentType.WEAPON.id]
        return weapon != null && getItem(weapon.id).name?.contains("pickaxe", ignoreCase = true) == true
    }

    private companion object {
        const val OBJ_TYPE = 10 // standard interactable scenery
        const val MINE_ANIM = 625 // pickaxe swing
        const val PICKAXE_RESPAWN = 50 // ~30s before a taken bronze pickaxe respawns
        const val LADDER_ID = 17385 // cellar exit ladder @(3209,9616) — never remove

        /** The Lumbridge Castle cellar room (underground). */
        val CELLAR = Area(3205, 9612, 3224, 9626)

        /** The interior to open up (inside the four outer walls); ores line the walls. */
        val MINE_ROOM = Area(3208, 9615, 3219, 9623)
        val FURNITURE = listOf(
            "table", "chair", "shelf", "shelv", "sink", "stool", "bench", "cupboard", "counter", "dining",
            "barrel", "crate", "box", "boxes", "sack",
        )
    }
}

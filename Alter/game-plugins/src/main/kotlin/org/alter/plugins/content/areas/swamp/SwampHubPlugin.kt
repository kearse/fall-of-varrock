package org.alter.plugins.content.areas.swamp

import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.Area
import org.alter.game.model.Direction
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.DynamicObject
import org.alter.game.model.entity.GroundItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.interfaces.bank.openBank
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **The Mire — the realm's war-supply hub**, in two halves:
 *  - **The Working Yard** — the Lumbridge graveyard, cleared of its gravestones (kept the coffin
 *    crypt). Holds the bank, the five processing stations (cookfire/furnace/anvil/spinning wheel/
 *    altar), the thieving stalls, and the **Supply Officer** (a Quartermaster) inside the crypt. This
 *    is where you PROCESS gathers and hand finished goods to the war. Sited here so it's central
 *    (steps from the castle), not out in the deep marsh.
 *  - **The Collection Grounds** — the swamp just south: trees/rocks/fish/herbs/thickets, spawned by
 *    their own skill plugins' swamp lists. This is where you GATHER.
 *
 * This plugin owns the yard. All tiles verified walkable against the cache collision dump (region
 * 12849). The Supply Officer reuses `npc.quartermaster` with NO new bind — `WarlordsArmouryPlugin`
 * binds that npc's options (deposit via `SupplyDepot` -> War Effort + the realm supply meter) globally.
 */
class SwampHubPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    private val officer = "npc.quartermaster"

    init {
        // Objects spawn in onWorldInit so the region/collision is loaded first.
        onWorldInit {
            stripYard() // clear the graveyard's grave-clutter so it reads as a clean war-supply workshop
            openCrypt() // open the crypt's WEST side: remove the west wall + its vegetation (kept N/E/S)
            removeFurnaceGate() // remove the railing/gate behind the furnace (house-edge fence, outside the strip)
            // Per the owner's layout: ONE bank stand (east) + a bank chest by the house range (west) for
            // the cook loop; furnace south of the house (east-facing) + anvil on the east side; spinning
            // wheel + altar against the house side; cooking is done at the Range INSIDE the house (no
            // outside range — see CookingPlugin's kitchen_range bind). Trees + tools are by their plugins.
            spawnObj("object.bank_booth", 3238, 3198, rot = 1) // east side; rotated one step right
            spawnObj("object.bank_chest", 3230, 3198, rot = 1) // east-facing; bound in bindBankChest()
            spawnObj("object.furnace", 3237, 3192)             // one tile south of the house (default facing)
            spawnObj("object.anvil", 3238, 3196)               // east side of the house
            spawnObj("object.spinning_wheel", 3241, 3202)      // moved per owner
            spawnObj("object.fire_altar", 3238, 3200)          // moved per owner
            // Starter tools: the axe by the tree line, the pickaxe on the road south toward the mines.
            spawnTool("item.bronze_axe", 3251, 3201)
            spawnTool("item.bronze_pickaxe", 3243, 3186)
            logger.info { "swamp-hub: working yard ready (bank + bank-chest + smithing + craft; cooking in the house)." }
        }
        bindBankChest()

        // The Supply Officer (a frontier Quartermaster) inside the coffin crypt. No bind — the existing
        // global npc.quartermaster bind opens the deposit/armoury dialog on click.
        if (runCatching { getRSCM(officer) }.isSuccess) {
            spawnNpc(officer, x = 3248, z = 3193, height = 0, walkRadius = 0, direction = Direction.WEST)
        } else {
            logger.warn { "swamp-hub: '$officer' not in cache; Supply Officer not spawned." }
        }
        spawnUndeadCorner()
    }

    /**
     * The Mire is a cleared graveyard, so the dead don't rest easy: a small cluster of zombies rises
     * on the open grass south-west of the yard house. Reachable through the safe route with
     * everything else in the Mire. This fills a real content gap — the Slayer roster assigns zombies,
     * but the OSRS spawn dump has none within reach of home, so a "kill zombies" contract had nowhere
     * to be filled. `npc.zombie` (id 26) is in the world dump, so WorldSpawnsPlugin already registers
     * its real combat def (22 hp, aggressive within [WorldSpawns.AGGRO_RADIUS]=4); this bespoke spawn
     * inherits it. Kills credit the Slayer task by name (see SlayerPlugin.onKill).
     *
     * TUNABLE: these tiles sit on the open grass SW of the house, clear of the skilling stations and
     * the fishing spots so aggression never bleeds into a skiller — nudge them if any land on scenery.
     */
    private fun spawnUndeadCorner() {
        if (!runCatching { getRSCM(ZOMBIE) }.isSuccess) {
            logger.warn { "swamp-hub: '$ZOMBIE' not in cache; undead corner not spawned." }
            return
        }
        UNDEAD_TILES.forEach { t ->
            spawnNpc(ZOMBIE, x = t.x, z = t.z, height = t.height, walkRadius = 4, direction = Direction.WEST)
        }
        logger.info { "swamp-hub: undead corner ready (${UNDEAD_TILES.size} zombies on the grass SW of the yard house)." }
    }

    /** Clear the graveyard's grave-clutter so the yard reads as a clean war-supply workshop: removes ALL
     *  interactable scenery (type 10 — gravestones, crosses, plants, the dead tree, "Death's Domain") and
     *  the graveyard railing (id 3457), while KEEPING the coffin crypt (2145), the building walls, the
     *  ground decoration, and our own DynamicObject spawns. (The earlier name-only "Gravestone" filter
     *  missed the crosses/plants/fence — hence the yard still looked like a graveyard.) */
    private fun stripYard() {
        var removed = 0
        for (x in YARD.bottomLeftX..YARD.topRightX) for (z in YARD.bottomLeftY..YARD.topRightY) {
            for (type in 0..22) {
                val obj = world.getObject(Tile(x, z, 0), type) ?: continue
                if (obj is DynamicObject) continue   // never our own spawns
                if (type == OBJ_TYPE || obj.id == FENCE_ID) { world.remove(obj); removed++ } // incl. the coffin now
            }
        }
        logger.info { "swamp-hub: cleared $removed grave-clutter object(s) from the working yard." }
    }

    /** Open the crypt's WEST side (toward the yard/stations): remove the west wall + the door + the
     *  west-side wall decorations/vegetation — everything on x3246-3247, z3190-3195 — while keeping the
     *  NORTH, EAST and SOUTH outer walls. The coffin (type-10) is cleared by stripYard. */
    private fun openCrypt() {
        var removed = 0
        for (x in 3246..3247) for (z in 3190..3195) {
            for (type in 0..22) {
                val obj = world.getObject(Tile(x, z, 0), type) ?: continue
                if (obj is DynamicObject) continue
                world.remove(obj); removed++
            }
        }
        logger.info { "swamp-hub: opened the crypt's west side — removed $removed wall/vegetation object(s) (kept N/E/S)." }
    }

    /** Remove the railing/gate behind the furnace (x3237, z3191-3194). It's the house-edge fence just
     *  outside the YARD strip box (which starts at x3238), so stripYard never reaches it. */
    private fun removeFurnaceGate() {
        var removed = 0
        for (z in 3191..3194) for (type in 0..22) {
            val obj = world.getObject(Tile(3237, z, 0), type) ?: continue
            if (obj !is DynamicObject && obj.id == FENCE_ID) { world.remove(obj); removed++ }
        }
        logger.info { "swamp-hub: removed $removed gate/railing object(s) behind the furnace." }
    }

    private fun spawnObj(key: String, x: Int, z: Int, rot: Int = 0) {
        val id = runCatching { getRSCM(key) }.getOrNull() ?: run {
            logger.warn { "swamp-hub: object '$key' not in cache; skipped." }; return
        }
        world.spawn(DynamicObject(id = id, type = OBJ_TYPE, rot = rot, tile = Tile(x, z, 0)))
    }

    /** BankBoothsPlugin only binds bank BOOTHS, so bind the cooking-loop bank chest here (its own option). */
    private fun bindBankChest() {
        val key = "object.bank_chest"
        val acts = runCatching { getObject(getRSCM(key)).actions.filterNotNull().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        val opt = acts.firstOrNull { it.equals("bank", true) || it.equals("use", true) } ?: acts.firstOrNull()
        if (opt != null) onObjOption(key, option = opt) { player.openBank() }
        else logger.warn { "swamp-hub: bank chest has no usable option; not bound." }
    }

    private fun spawnTool(key: String, x: Int, z: Int) {
        val id = runCatching { getRSCM(key) }.getOrNull() ?: return
        val gi = GroundItem(id, 1, Tile(x, z, 0))
        gi.respawnCycles = TOOL_RESPAWN
        world.spawn(gi)
    }

    private companion object {
        const val OBJ_TYPE = 10
        const val TOOL_RESPAWN = 50
        const val FENCE_ID = 3457   // the graveyard railing (type 3 wall) — removed
        const val GROUND_DECO = 22  // ground-decoration object type — kept when opening the crypt
        /** The graveyard working yard (grave-clutter stripped within this box). Starts at x3238 to leave
         *  the house (x<=3237) intact; extends to z3202 + x3254 to clear the gravestones and the dead
         *  tree (id 1282 @ 3253,3197) on the east tree line. */
        val YARD = Area(3238, 3188, 3254, 3203)

        /** Slayer-roster zombie (id 26); in the world dump, so its combat def is already registered. */
        const val ZOMBIE = "npc.zombie"

        /** Where the undead rise — the open grass just SOUTH-WEST of the yard house, around (3231,3191).
         *  (They used to rise on the SE fringe by the mines road, but that pocket isn't walkable from the
         *  yard — players could see the zombies and never reach them.) Kept between the rock line (x3237+)
         *  east and the fishing shore (x<=3218) west, so a walkRadius-4 wander never bleeds aggression
         *  into a skilling station. TUNABLE. */
        val UNDEAD_TILES = listOf(
            Tile(3231, 3191, 0), Tile(3229, 3190, 0), Tile(3232, 3192, 0),
            Tile(3230, 3188, 0), Tile(3232, 3189, 0),
        )
    }
}

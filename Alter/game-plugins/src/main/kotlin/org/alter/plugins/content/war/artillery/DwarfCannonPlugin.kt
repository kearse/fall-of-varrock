package org.alter.plugins.content.war.artillery

import dev.openrune.cache.CacheManager.getObject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.getInteractingGameObj
import org.alter.api.ext.message
import org.alter.api.ext.player
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * The multicannon loc's clicks, routed to whichever [CannonEmplacement] stands there: **Fire**
 * loads it from the player's pack (the gun then fires by itself — see [CannonEmplacement.tick]);
 * Pick-up and Empty are refused — an emplacement belongs to whoever placed it, never to a pack.
 * Bound defensively: a cache def without the verb logs instead of dropping the plugin.
 */
class DwarfCannonPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    init {
        val id = runCatching { getRSCM(DwarfCannon.OBJ_KEY) }.getOrNull()
        if (id == null) {
            logger.warn { "[artillery] '${DwarfCannon.OBJ_KEY}' does not resolve; multicannon clicks are unbound." }
        } else {
            val actions = runCatching { getObject(id).actions.filterNotNull().map { it.lowercase() } }.getOrDefault(emptyList())
            if ("fire" in actions) {
                onObjOption(id, "fire") {
                    val p = player
                    val obj = runCatching { p.getInteractingGameObj() }.getOrNull()
                    val gun = obj?.let { DwarfCannon.at(it.tile) }
                    if (gun == null) {
                        p.message("This gun isn't manned — nobody has laid it.")
                        return@onObjOption
                    }
                    if (gun.ammo >= DwarfCannon.MAGAZINE) {
                        p.message("The cannon is fully loaded and firing.")
                        return@onObjOption
                    }
                    if (gun.load(p) > 0 && gun.ammo > 0) p.message("The cannon swings toward the enemy.")
                }
            } else {
                logger.warn { "[artillery] loc $id has no Fire option ($actions); loading is unbound." }
            }
            listOf("pick-up", "empty").filter { it in actions }.forEach { verb ->
                onObjOption(id, verb) {
                    val gun = runCatching { player.getInteractingGameObj() }.getOrNull()?.let { DwarfCannon.at(it.tile) }
                    player.message(if (gun != null) "It's mounted and manned. Leave it be." else "It's bolted down.")
                }
            }
        }
    }
}

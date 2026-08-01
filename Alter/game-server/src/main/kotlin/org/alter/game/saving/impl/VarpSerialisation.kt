package org.alter.game.saving.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.entity.Client
import org.alter.game.model.varp.Varp
import org.alter.game.saving.DocumentHandler
import org.bson.Document

private val logger = KotlinLogging.logger {}

/**
 * Marker key identifying the current document format. The legacy format contained only
 * numeric-string keys, so a non-numeric reserved key is the only reliable discriminator
 * between the two — both are flat string-keyed maps of ints.
 */
internal const val VARP_VERSION_KEY = "_v"
internal const val VARP_CURRENT_VERSION = 2

/**
 * Encode varps keyed by **id** with the state as the value.
 *
 * The legacy format had this inverted — `{ state: id }` — which meant every varp sharing a
 * state value collapsed into a single map entry on save: all but one of them were silently
 * discarded, every save. Keying by id is collision-free by construction.
 */
internal fun encodeVarps(varps: List<Varp>): Document =
    Document().apply {
        put(VARP_VERSION_KEY, VARP_CURRENT_VERSION)
        varps
            .filter { it.state != 0 }
            .forEach { put(it.id.toString(), it.state) }
    }

/**
 * Decode a varp document into (id, state) pairs, handling both formats:
 * v2 (has [VARP_VERSION_KEY]) is keyed by id; the legacy format is keyed by state with the
 * id as the value. Non-numeric keys/values are skipped.
 */
internal fun decodeVarps(doc: Document): List<Pair<Int, Int>> {
    val v2 = doc.containsKey(VARP_VERSION_KEY)
    val decoded = mutableListOf<Pair<Int, Int>>()
    doc.forEach { key, value ->
        if (key == VARP_VERSION_KEY) return@forEach
        val k = key.toIntOrNull() ?: return@forEach
        val v = value?.toString()?.toIntOrNull() ?: return@forEach
        decoded.add(if (v2) k to v else v to k)
    }
    return decoded
}

class VarpSerialisation(override val name: String = "varps") : DocumentHandler {

    override fun fromDocument(client: Client, doc: Document) {
        decodeVarps(doc).forEach { (id, state) ->
            /*
             * Legacy documents were keyed by state, so a large state value parsed as an "id"
             * can exceed the varp array — guard instead of letting the whole login fail as
             * MALFORMED.
             */
            if (id in 0 until client.varps.maxVarps) {
                client.varps.setState(id, state)
            } else {
                logger.error { "Skipping out-of-range varp on load: player=${client.loginUsername} id=$id state=$state" }
            }
        }
    }

    override fun asDocument(client: Client): Document = encodeVarps(client.varps.getAll())

}

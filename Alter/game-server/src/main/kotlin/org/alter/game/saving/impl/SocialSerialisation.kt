package org.alter.game.saving.impl

import org.alter.game.model.entity.Client
import org.alter.game.saving.DocumentHandler
import org.bson.Document

/**
 * Persists the friends and ignore lists (login keys). Optional: saves written before this
 * section existed simply load with empty lists.
 */
class SocialSerialisation(override val name: String = "social") : DocumentHandler {

    override val optional: Boolean = true

    override fun fromDocument(client: Client, doc: Document) {
        client.social.friends.clear()
        client.social.ignores.clear()
        doc.getList("friends", String::class.java)?.let { client.social.friends.addAll(it) }
        doc.getList("ignores", String::class.java)?.let { client.social.ignores.addAll(it) }
    }

    override fun asDocument(client: Client): Document {
        return Document().apply {
            append("friends", client.social.friends.toList())
            append("ignores", client.social.ignores.toList())
        }
    }
}

package org.alter.game.saving

import org.alter.game.model.entity.Client
import org.bson.Document

interface DocumentHandler {

    val name: String

    /**
     * Whether a save may lack this section. Sections added after players already had saves
     * must be optional, otherwise every older save fails to load as MALFORMED.
     */
    val optional: Boolean
        get() = false

    fun asDocument(client: Client) : Document

    fun fromDocument(client: Client, doc: Document)

}

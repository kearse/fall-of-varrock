package org.alter.game.saving.formats

import org.alter.game.model.entity.Client
import org.bson.Document

abstract class FormatHandler(open val collectionName : String) {

    open fun init() {}

    abstract fun saveDocument(client : Client, document : Document)

    /**
     * Upserts a whole document keyed by login username, for collections that are
     * not tied to an online [Client] (e.g. moderation records for offline players).
     */
    abstract fun saveDocument(loginUsername : String, document : Document)

    abstract fun playerExists(client : Client) : Boolean

    abstract fun parseDocument(client : Client) : Document

    abstract fun loadAll() : Map<String,Document>


}
package org.alter.game.saving.formats.impl

import com.mongodb.client.model.Filters.regex
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates.set
import org.alter.game.model.entity.Client
import org.alter.game.saving.formats.FormatHandler
import org.bson.Document
import org.bson.conversions.Bson

class Mongo(override val collectionName: String) : FormatHandler(collectionName) {

    override fun init() {
        DatabaseManager.connect()
    }

    override fun saveDocument(client: Client, document: Document) {
        if (!playerExists(client)) {
            DatabaseManager.getCollection(collectionName).insertOne(document)
        } else {
            val caseInsensitiveFilter = createCaseInsensitiveFilter(client)
            val attrs = document.get("attributes", Document::class.java)
            DatabaseManager.getCollection(collectionName).updateOne(caseInsensitiveFilter, set("attributes", attrs))
        }
    }

    override fun saveDocument(loginUsername: String, document: Document) {
        DatabaseManager.getCollection(collectionName).replaceOne(
            regex("loginUsername", "^${Regex.escape(loginUsername)}$", "i"),
            document,
            ReplaceOptions().upsert(true),
        )
    }

    override fun parseDocument(client : Client): Document {
        val caseInsensitiveFilter = createCaseInsensitiveFilter(client)
        return DatabaseManager.getCollection(collectionName).find(caseInsensitiveFilter).first()!!
    }

    override fun loadAll(): Map<String, Document> {
        // PlayerDetails.init() calls loadAll() before init()/connect(), so connect
        // defensively here (connect() is idempotent — returns early if already open).
        DatabaseManager.connect()
        val result = mutableMapOf<String, Document>()
        DatabaseManager.getCollection(collectionName).find().forEach { doc ->
            val key = doc.getString("loginUsername")
            if (!key.isNullOrEmpty()) {
                result[key.lowercase()] = doc
            }
        }
        return result
    }

    override fun playerExists(client: Client): Boolean {
        val caseInsensitiveFilter = createCaseInsensitiveFilter(client)
        return DatabaseManager.getCollection(collectionName)
            .find(caseInsensitiveFilter)
            .toList()
            .isNotEmpty()
    }

    private fun createCaseInsensitiveFilter(client: Client): Bson {
        return regex("loginUsername", "^${client.loginUsername}$", "i")
    }
}
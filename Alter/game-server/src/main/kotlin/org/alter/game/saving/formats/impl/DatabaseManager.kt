package org.alter.game.saving.formats.impl

import com.mongodb.MongoException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document

/**
 * Mongo connection settings, resolved from the environment so the game server and
 * the website can point at the **same** database.
 *
 * Precedence:
 *   1. MONGODB_URI            — a full connection string (mongodb:// or mongodb+srv://)
 *   2. MONGO_HOST/MONGO_DB/…  — individual parts (builds a connection string)
 *   3. defaults               — mongodb://localhost:27017 / db "lumbridge"
 *
 * Reads from system properties first (so they can be set via -D or game.yml plumbing),
 * then falls back to OS environment variables of the same name.
 */
data class Environment(
    val database: String,
    val uri: String,
) {
    companion object {
        private fun cfg(name: String): String? =
            System.getProperty(name)?.takeIf { it.isNotBlank() }
                ?: System.getenv(name)?.takeIf { it.isNotBlank() }

        fun fromEnv(): Environment {
            val database = cfg("MONGODB_DB") ?: cfg("MONGO_DB") ?: "lumbridge"

            val explicitUri = cfg("MONGODB_URI") ?: cfg("MONGO_URI")
            if (explicitUri != null) {
                return Environment(database, explicitUri)
            }

            val host = cfg("MONGO_HOST") ?: "localhost:27017"
            val user = cfg("MONGO_USER")
            val pass = cfg("MONGO_PASS")
            val srv = cfg("MONGO_SRV").toBoolean() // true => mongodb+srv://
            val options = cfg("MONGO_OPTIONS")
            val scheme = if (srv) "mongodb+srv" else "mongodb"

            val auth = if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) "$user:$pass@" else ""
            val query = if (!options.isNullOrEmpty()) "?$options" else ""
            return Environment(database, "$scheme://$auth$host/$database$query")
        }
    }
}

object DatabaseManager {

    val logger = KotlinLogging.logger {}

    private val env: Environment by lazy { Environment.fromEnv() }

    private var client: MongoClient? = null
    private var database: MongoDatabase? = null

    fun connect() {
        if (client != null) {
            return
        }
        // Redact credentials before logging the connection target.
        val safe = env.uri.replace(Regex("//[^@/]+@"), "//***@")
        logger.info { "Connecting to MongoDB ($safe, db=${env.database})" }

        try {
            client = MongoClients.create(env.uri)
            database = client?.getDatabase(env.database)
                ?: throw MongoException("Failed to connect to MongoDB database ${env.database}.")
        } catch (e: MongoException) {
            throw MongoException("Failed to connect to MongoDB using $safe.", e)
        }
    }

    /**
     * Gets a collection by name from the database.
     */
    fun getCollection(name: String): MongoCollection<Document> {
        return database?.getCollection(name)
            ?: throw MongoException("Database not connected. Cannot get collection.")
    }
}

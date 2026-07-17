package org.alter.game.saving.formats.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.game.model.entity.Client
import org.alter.game.saving.formats.FormatHandler
import org.bson.Document
import org.bson.json.JsonWriterSettings
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

class Json(override val collectionName: String) : FormatHandler(collectionName) {

    private var path: Path = Path("")

    private var prettyPrintSettings: JsonWriterSettings = JsonWriterSettings.builder().indent(true).build()

    init {
        path = Paths.get("../data/saves/${collectionName}/")
        if (!Files.exists(path)) {
            Files.createDirectories(path)
        }
    }

    override fun saveDocument(client: Client, document: Document) {
        writeAtomically(client.loginUsername, document)
    }

    override fun saveDocument(loginUsername: String, document: Document) {
        writeAtomically(loginUsername, document)
    }

    /**
     * Persist [document] without ever leaving a truncated/empty save behind.
     *
     * The old approach wrote the JSON straight onto the save file, which truncates the target to
     * zero BEFORE writing the new bytes. A crash or a full disk mid-write then left a 0-byte file
     * that [Document.parse] can never read again — the player is permanently locked out with a
     * MALFORMED login (see PlayerSaving.loadPlayer). This was the "logged in fine, then suddenly
     * can't log in" corruption.
     *
     * Instead: render the JSON first (and refuse to write it if it came out blank), stage it in a
     * sibling temp file, snapshot the previous good save as `<name>.bak`, then atomically rename the
     * temp file over the target. An atomic rename guarantees the live save is only ever the complete
     * old content or the complete new content, and the `.bak` gives [parseDocument] a known-good
     * copy to fall back to if the target is ever corrupt.
     */
    private fun writeAtomically(loginUsername: String, document: Document) {
        val json = document.toJson(prettyPrintSettings)
        // Never overwrite a real save with nothing — a blank render means something upstream failed.
        require(json.isNotBlank()) { "Refusing to write a blank save for '$loginUsername'." }

        val target = path.resolve(loginUsername)
        val tmp = path.resolve("$loginUsername.tmp")
        Files.writeString(tmp, json)

        // Preserve the last known-good save so a future corruption can self-heal via parseDocument.
        if (Files.exists(target) && Files.size(target) > 0) {
            Files.copy(target, path.resolve("$loginUsername.bak"), StandardCopyOption.REPLACE_EXISTING)
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            // Some filesystems (rare) can't do an atomic rename — fall back to a plain replace.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun parseDocument(client: Client): Document {
        val save = path.resolve(client.loginUsername)
        parseIfValid(save)?.let { return it }

        // Primary save is missing/blank/corrupt — recover from the last known-good backup so a
        // half-written save (crash or full disk) doesn't lock the player out. Restore it in place so
        // the recovered state becomes the new primary.
        val backup = path.resolve("${client.loginUsername}.bak")
        parseIfValid(backup)?.let { recovered ->
            logger.warn { "Primary save for '${client.loginUsername}' was unreadable; recovered from backup." }
            runCatching { Files.copy(backup, save, StandardCopyOption.REPLACE_EXISTING) }
            return recovered
        }

        // Nothing usable: surface the original parse failure to the caller (-> MALFORMED) rather
        // than silently discarding a save that may still be restorable from an offline backup.
        return Document.parse(save.readText())
    }

    override fun loadAll(): Map<String, Document> {
        val doc = mutableMapOf<String, Document>()
        Files.list(path).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                // Skip staging/backup siblings so they never masquerade as their own account.
                .filter { file ->
                    val name = file.fileName.toString()
                    !name.endsWith(".tmp") && !name.endsWith(".bak")
                }
                .forEach { file ->
                    val parsed = parseIfValid(file)
                    if (parsed != null) {
                        doc[file.fileName.toString().substringBeforeLast(".")] = parsed
                    } else {
                        // One corrupt save must not abort the whole scan (e.g. the Elo-board rebuild).
                        logger.warn { "Skipping unreadable save file '${file.fileName}'." }
                    }
                }
        }
        return doc
    }

    override fun playerExists(client: Client): Boolean {
        val save = path.resolve(client.loginUsername)
        return Files.exists(save)
    }

    /** Parse [file] into a [Document], or null if it is missing, blank, or not valid JSON. */
    private fun parseIfValid(file: Path): Document? {
        if (!Files.exists(file)) return null
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return runCatching { Document.parse(text) }.getOrNull()
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}

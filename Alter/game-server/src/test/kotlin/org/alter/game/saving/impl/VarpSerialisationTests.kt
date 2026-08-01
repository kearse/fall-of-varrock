package org.alter.game.saving.impl

import org.alter.game.model.varp.Varp
import org.bson.Document
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VarpSerialisationTests {
    @Test
    fun roundTripPreservesVarpsSharingAState() {
        // The legacy format keyed the document by STATE, so these two collapsed to one entry.
        val varps = listOf(Varp(id = 173, state = 1), Varp(id = 281, state = 1), Varp(id = 1050, state = 77))

        val decoded = decodeVarps(encodeVarps(varps)).toMap()

        assertEquals(3, decoded.size)
        assertEquals(1, decoded[173])
        assertEquals(1, decoded[281])
        assertEquals(77, decoded[1050])
    }

    @Test
    fun zeroStatesAreNotWritten() {
        val doc = encodeVarps(listOf(Varp(id = 1, state = 0), Varp(id = 2, state = 5)))
        assertTrue(!doc.containsKey("1"))
        assertEquals(5, doc.getInteger("2"))
    }

    @Test
    fun versionMarkerIsWrittenAndNotDecodedAsAVarp() {
        val doc = encodeVarps(listOf(Varp(id = 2, state = 5)))
        assertEquals(VARP_CURRENT_VERSION, doc.getInteger(VARP_VERSION_KEY))
        assertEquals(listOf(2 to 5), decodeVarps(doc))
    }

    @Test
    fun legacyDocumentsDecodeInverted() {
        // Legacy format: key = state, value = id, no version marker.
        val legacy = Document().append("77", "1050").append("3", "173")

        val decoded = decodeVarps(legacy).toMap()

        assertEquals(77, decoded[1050])
        assertEquals(3, decoded[173])
    }

    @Test
    fun nonNumericKeysAreIgnored() {
        val doc = Document().append(VARP_VERSION_KEY, VARP_CURRENT_VERSION).append("abc", 5).append("7", 9)
        assertEquals(listOf(7 to 9), decodeVarps(doc))
    }

    @Test
    fun stringAndIntValuesBothDecode() {
        // The JSON save path can round-trip values as either type.
        val doc = Document().append(VARP_VERSION_KEY, VARP_CURRENT_VERSION).append("7", "9").append("8", 10)
        val decoded = decodeVarps(doc).toMap()
        assertEquals(9, decoded[7])
        assertEquals(10, decoded[8])
    }

    @Test
    fun jsonParsedDocumentDecodes() {
        val doc = Document.parse("""{"$VARP_VERSION_KEY": 2, "173": 1, "281": 1}""")
        val decoded = decodeVarps(doc).toMap()
        assertEquals(1, decoded[173])
        assertEquals(1, decoded[281])
    }
}

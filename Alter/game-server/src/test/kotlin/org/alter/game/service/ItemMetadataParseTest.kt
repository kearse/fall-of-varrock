package org.alter.game.service

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.alter.game.service.game.ItemMetadataService
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The item override YAML corpus has three distinct document styles, and [ItemMetadataService.load]
 * keys its full-vs-partial override semantics off which fields deserialize as null. These tests pin
 * the deserialization contract for each style with the same mapper setup loadAll() uses.
 */
class ItemMetadataParseTest {
    private val mapper = YAMLMapper()

    private fun parse(doc: String): ItemMetadataService.Metadata =
        mapper.readValue(doc, ItemMetadataService.Metadata::class.java)

    @Test
    fun `barrows style skillReqs-only doc leaves stat fields null`() {
        val meta = parse(
            """
            id: 4718
            name: "Dharok's greataxe"

            equipment:
              skillReqs:
                - skill: "attack"
                  level: 70
                - skill: "strength"
                  level: 70
            """.trimIndent(),
        )
        val eq = meta.equipment
        assertNotNull(eq)
        assertNull(eq.equipSlot, "no equipSlot → partial override")
        assertNull(eq.attackSpeed, "absent attack speed must stay null, not -1")
        assertNull(eq.weaponType, "absent weapon type must stay null, not -1")
        assertNull(eq.attackStab)
        assertNull(eq.meleeStrength)
        assertEquals(2, eq.skillReqs?.size)
        assertEquals("attack", eq.skillReqs?.first()?.skill)
        assertEquals(70, eq.skillReqs?.first()?.level)
    }

    @Test
    fun `full doc with equipSlot parses every provided stat`() {
        val meta = parse(
            """
            id: 35
            name: "Excalibur"
            weight: 2.267

            equipment:
              equipSlot: "weapon"
              weaponType: 9
              attackSpeed: 5
              attackStab: 9999
              meleeStrength: 25
              prayer: 0
            """.trimIndent(),
        )
        val eq = meta.equipment
        assertNotNull(eq)
        assertEquals("weapon", eq.equipSlot)
        assertEquals(9, eq.weaponType)
        assertEquals(5, eq.attackSpeed)
        assertEquals(9999, eq.attackStab)
        assertEquals(25, eq.meleeStrength)
        assertEquals(0, eq.prayer)
        assertEquals(2.267, meta.weight)
    }

    @Test
    fun `cosmetic doc with explicit zero bonuses keeps them non-null`() {
        val meta = parse(
            """
            id: 28254
            name: "Warlord's warhelm"

            equipment:
              attackStab: 0
              defenceStab: 0
              prayer: 0
            """.trimIndent(),
        )
        val eq = meta.equipment
        assertNotNull(eq)
        assertNull(eq.equipSlot)
        assertEquals(0, eq.attackStab, "explicit zero must survive as a value, not null")
        assertEquals(0, eq.defenceStab)
        assertEquals(0, eq.prayer)
        assertNull(eq.attackSlash, "unlisted bonus stays null so the cache value is kept")
    }

    @Test
    fun `doc without weight leaves it null`() {
        val meta = parse(
            """
            id: 4718
            name: "Dharok's greataxe"
            """.trimIndent(),
        )
        assertNull(meta.weight)
    }
}

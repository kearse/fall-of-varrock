package org.alter.game.saving.impl

import org.alter.game.model.entity.Client
import org.alter.game.model.skill.Skill
import org.alter.game.model.skill.SkillSet
import org.alter.game.saving.DocumentHandler
import org.bson.Document

class SkillSerialisation(override val name: String = "skills") : DocumentHandler {

    override fun fromDocument(client: Client, doc: Document) = doc.forEach { _, skillDoc ->
        client.getSkills().setSkill(Skill.fromDocument(skillDoc as Document))
    }

    override fun asDocument(client: Client): Document {
        return Document().apply {
            (0 until client.getSkills().maxSkills).forEach { skillID ->
                val skill = client.getSkills()[skillID]
                val name = SkillSet.getSkillName(skillID).lowercase()
                // Enum 680 names only the 23 real skills; the extra SkillSet slots (23, 24)
                // all stringify to the default "Skill" and collide on ONE junk map key that
                // polluted saves and hiscores totals. Skip them — loading tolerates the
                // key's absence (old saves' junk entry decodes to slot 24 and is ignored).
                if (name == "skill") return@forEach
                put(name, skill.asDocument())
            }
        }
    }

}
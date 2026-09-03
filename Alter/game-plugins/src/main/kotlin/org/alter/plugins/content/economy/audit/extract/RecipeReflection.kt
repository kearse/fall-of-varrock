package org.alter.plugins.content.economy.audit.extract

import org.alter.plugins.content.economy.audit.engine.ActionTimeModel
import org.alter.plugins.content.economy.audit.model.Edge
import org.alter.plugins.content.economy.audit.model.EdgeKind
import org.alter.plugins.content.economy.audit.model.ItemInfo
import org.alter.plugins.content.economy.audit.model.NodeId
import org.alter.plugins.content.economy.audit.model.RecipeCategory
import org.alter.plugins.content.economy.audit.model.Stack
import org.alter.plugins.content.magic.MagicSpells
import org.alter.plugins.content.war.forge.WarForge
import org.alter.rscm.RSCM.getRSCM

/**
 * Reads every item-conversion recipe table straight out of the constructed plugin instances by
 * reflection (the tables are `private val` literals inside each plugin — see the `// File:line`
 * pointers). Quantity / failure semantics that live in the plugins' loop bodies are encoded here
 * next to the pointer that justifies them. A missing field throws and is reported as
 * `RECIPE_ADAPTER_BROKEN`, never swallowed.
 */
object RecipeReflection {

    class Result(
        val edges: List<Edge>,
        /** `(max shl 16) or min` item-on-item hashes the recipes explain (PluginRepository.bindItemOnItem). */
        val explainedItemPairs: Set<Int>,
        /** `(item shl 32) or obj` item-on-object pairs the recipes explain. */
        val explainedObjPairs: Set<Long>,
        val broken: List<Pair<String, String>>,
    )

    private fun id(key: String): Int? = runCatching { getRSCM(key) }.getOrNull()
    private fun node(key: String): NodeId? = id(key)?.let { NodeId.ItemNode(it) }
    fun itemPair(a: Int, b: Int): Int = (maxOf(a, b) shl 16) or minOf(a, b)
    fun objPair(item: Int, obj: Int): Long = (item.toLong() shl 32) or obj.toLong()

    private class Ctx(val items: Map<Int, ItemInfo>, val coins: Int) {
        val edges = ArrayList<Edge>()
        val itemPairs = HashSet<Int>()
        val objPairs = HashSet<Long>()
        val broken = ArrayList<Pair<String, String>>()

        fun explainItems(a: String, b: String) {
            val x = id(a) ?: return
            val y = id(b) ?: return
            itemPairs += itemPair(x, y)
        }

        fun explainObj(obj: String, item: String) {
            val o = id(obj) ?: return
            val i = id(item) ?: return
            objPairs += objPair(i, o)
        }

        fun recipe(
            id: String, source: String, inputs: List<Pair<String, Double>>, outputs: List<Pair<String, Double>>,
            ticks: Double, category: RecipeCategory, evMin: Double = 1.0, evMax: Double = 1.0, note: String = "",
            extraInputs: List<Stack> = emptyList(), kind: EdgeKind = EdgeKind.RECIPE,
        ): Boolean {
            val ins = inputs.map { (k, q) -> node(k)?.let { Stack(it, q) } ?: return false } + extraInputs
            val outs = outputs.map { (k, q) -> node(k)?.let { Stack(it, q) } ?: return false }
            edges += Edge(
                id = id, kind = kind, source = source, inputs = ins, outputs = outs, ticksPerUnit = ticks,
                evAtMinLevel = evMin, evAtMaxLevel = evMax, levelNote = note, category = category,
            )
            return true
        }
    }

    fun extract(plugins: Map<String, Any>, items: Map<Int, ItemInfo>, coins: Int): Result {
        val ctx = Ctx(items, coins)
        fun adapter(name: String, block: (Any) -> Unit) {
            val plugin = plugins.entries.firstOrNull { it.key.endsWith(".$name") }?.value
            if (plugin == null) {
                ctx.broken += name to "plugin not constructed offline"
                return
            }
            try {
                block(plugin)
            } catch (e: Throwable) {
                ctx.broken += name to "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        adapter("SmithingPlugin") { smithing(it, ctx) }
        adapter("CraftingPlugin") { crafting(it, ctx) }
        adapter("FletchingPlugin") { fletching(it, ctx) }
        adapter("HerblorePlugin") { herblore(it, ctx) }
        adapter("CookingPlugin") { cooking(it, ctx) }
        adapter("RunecraftPlugin") { runecraft(it, ctx) }
        adapter("FarmingPlugin") { farming(it, ctx) }
        adapter("HunterPlugin") { hunter(it, ctx) }
        adapter("UtilitySpellsPlugin") { utilitySpells(it, ctx) }
        adapter("EnchantPlugin") { enchant(it, ctx) }
        adapter("SpiritShieldPlugin") { spiritShields(it, ctx) }
        adapter("ForgePlugin") { forge(it, ctx) }
        adapter("CosmeticDyePlugin") { dyes(it, ctx) }
        adapter("PotionsPlugin") { potions(it, ctx) }
        try {
            warForge(ctx)
        } catch (e: Throwable) {
            ctx.broken += "WarForge" to "${e.javaClass.simpleName}: ${e.message}"
        }
        return Result(ctx.edges, ctx.itemPairs, ctx.objPairs, ctx.broken)
    }

    // ---- SmithingPlugin.kt: bars (:56-63), pieces (:68-76), metalByBar (:82-89); iron 50% fail (:292) ----
    private fun smithing(p: Any, c: Ctx) {
        val furnace = Reflect.str(p, "furnace")
        val anvil = Reflect.str(p, "anvil")
        for (bar in Reflect.list(p, "bars")) {
            val barKey = Reflect.str(bar, "bar")
            val ores = Reflect.map<String, Int>(bar, "ores")
            val coal = Reflect.int(bar, "coal")
            val level = Reflect.int(bar, "level")
            val inputs = ores.map { (k, n) -> k to n.toDouble() } + (if (coal > 0) listOf("item.coal" to coal.toDouble()) else emptyList())
            val iron = barKey == "item.iron_bar"
            c.recipe(
                id = "recipe:smithing.smelt.${barKey.removePrefix("item.")}", source = "SmithingPlugin.smelt",
                inputs = inputs, outputs = listOf(barKey to 1.0), ticks = ActionTimeModel.SMELT_TICKS,
                category = RecipeCategory.CRAFT, evMin = if (iron) 0.5 else 1.0, evMax = if (iron) 0.5 else 1.0,
                note = "Smithing $level" + if (iron) "; iron fails 50% (SmithingPlugin.kt:292)" else "",
            )
            ores.keys.forEach { c.explainObj(furnace, it) }
        }
        val pieces = Reflect.list(p, "pieces")
        for ((barKey, metal) in Reflect.map<String, Any>(p, "metalByBar")) {
            val prefix = Reflect.str(metal, "prefix")
            val base = Reflect.int(metal, "baseLevel")
            c.explainObj(anvil, barKey)
            for (piece in pieces) {
                val key = Reflect.str(piece, "key")
                val bars = Reflect.int(piece, "bars")
                val offset = Reflect.int(piece, "levelOffset")
                val result = "item.${prefix}_$key"
                if (id(result) == null) continue
                c.recipe(
                    id = "recipe:smithing.smith.${prefix}_$key", source = "SmithingPlugin.smith",
                    inputs = listOf(barKey to bars.toDouble()), outputs = listOf(result to 1.0),
                    ticks = ActionTimeModel.SMITH_TICKS, category = RecipeCategory.CRAFT,
                    note = "Smithing ${minOf(99, base + offset)}; hammer not consumed",
                )
            }
        }
    }

    // ---- CraftingPlugin.kt: gems (:36-41), leatherPieces (:44-51), spinnables (:54-57) ----
    private fun crafting(p: Any, c: Ctx) {
        for (g in Reflect.list(p, "gems")) {
            val uncut = Reflect.str(g, "uncut"); val cut = Reflect.str(g, "cut")
            c.recipe("recipe:crafting.cut.${cut.removePrefix("item.")}", "CraftingPlugin.cutGems",
                listOf(uncut to 1.0), listOf(cut to 1.0), ActionTimeModel.CRAFT_TICKS, RecipeCategory.CRAFT,
                note = "Crafting ${Reflect.int(g, "level")}; chisel not consumed")
            c.explainItems("item.chisel", uncut)
        }
        for (h in Reflect.list(p, "leatherPieces")) {
            val item = Reflect.str(h, "item")
            c.recipe("recipe:crafting.leather.${item.removePrefix("item.")}", "CraftingPlugin.makeLeather",
                listOf("item.leather" to 1.0, "item.thread" to 1.0), listOf(item to 1.0), ActionTimeModel.CRAFT_TICKS,
                RecipeCategory.CRAFT, note = "Crafting ${Reflect.int(h, "level")}; needle not consumed")
        }
        c.explainItems("item.needle", "item.leather")
        val wheel = Reflect.str(p, "wheel")
        for (s in Reflect.list(p, "spinnables")) {
            val from = Reflect.str(s, "from"); val to = Reflect.str(s, "to")
            c.recipe("recipe:crafting.spin.${to.removePrefix("item.")}", "CraftingPlugin.spin",
                listOf(from to 1.0), listOf(to to 1.0), ActionTimeModel.CRAFT_TICKS, RecipeCategory.CRAFT,
                note = "Crafting ${Reflect.int(s, "level")}")
            c.explainObj(wheel, from)
        }
    }

    // ---- FletchingPlugin.kt: cuts (:34-41), arrow shafts 15/log (:71,:86), stringings (:46-59) ----
    private fun fletching(p: Any, c: Ctx) {
        for (cut in Reflect.list(p, "cuts")) {
            val log = Reflect.str(cut, "log")
            val shortU = Reflect.str(cut, "shortU"); val longU = Reflect.str(cut, "longU")
            c.recipe("recipe:fletching.cut.${shortU.removePrefix("item.")}", "FletchingPlugin.makeLoop",
                listOf(log to 1.0), listOf(shortU to 1.0), ActionTimeModel.FLETCH_TICKS, RecipeCategory.CRAFT,
                note = "Fletching ${Reflect.int(cut, "shortLevel")}; knife not consumed")
            c.recipe("recipe:fletching.cut.${longU.removePrefix("item.")}", "FletchingPlugin.makeLoop",
                listOf(log to 1.0), listOf(longU to 1.0), ActionTimeModel.FLETCH_TICKS, RecipeCategory.CRAFT,
                note = "Fletching ${Reflect.int(cut, "longLevel")}; knife not consumed")
            if (log == "item.logs") {
                c.recipe("recipe:fletching.cut.arrow_shaft", "FletchingPlugin.makeLoop",
                    listOf(log to 1.0), listOf("item.arrow_shaft" to 15.0), ActionTimeModel.FLETCH_TICKS, RecipeCategory.CRAFT,
                    note = "Fletching 1; 15 shafts per log (FletchingPlugin.kt:86)")
            }
            c.explainItems("item.knife", log)
        }
        for ((unstrung, s) in Reflect.map<String, Any>(p, "stringings")) {
            val strung = Reflect.str(s, "strung")
            c.recipe("recipe:fletching.string.${strung.removePrefix("item.")}", "FletchingPlugin.stringLoop",
                listOf(unstrung to 1.0, "item.bow_string" to 1.0), listOf(strung to 1.0), ActionTimeModel.FLETCH_TICKS,
                RecipeCategory.CRAFT, note = "Fletching ${Reflect.int(s, "level")}")
            c.explainItems("item.bow_string", unstrung)
        }
    }

    // ---- HerblorePlugin.kt: recipes (:34-50), cleanables (:56-71) ----
    private fun herblore(p: Any, c: Ctx) {
        for (r in Reflect.list(p, "recipes")) {
            val a = Reflect.str(r, "a"); val b = Reflect.str(r, "b"); val result = Reflect.str(r, "result")
            c.recipe("recipe:herblore.make.${result.removePrefix("item.")}", "HerblorePlugin.make",
                listOf(a to 1.0, b to 1.0), listOf(result to 1.0), ActionTimeModel.CLICK_TICKS, RecipeCategory.CRAFT,
                note = "Herblore ${Reflect.int(r, "level")}")
            c.explainItems(a, b)
        }
        for (cl in Reflect.list(p, "cleanables")) {
            val grimy = Reflect.str(cl, "grimy"); val clean = Reflect.str(cl, "clean")
            c.recipe("recipe:herblore.clean.${clean.removePrefix("item.")}", "HerblorePlugin.clean",
                listOf(grimy to 1.0), listOf(clean to 1.0), ActionTimeModel.CLICK_TICKS, RecipeCategory.CRAFT,
                note = "Herblore ${Reflect.int(cl, "level")}")
        }
    }

    // ---- CookingPlugin.kt: cookables (:39-47), burn chance (:88-94) ----
    private fun cooking(p: Any, c: Ctx) {
        @Suppress("UNCHECKED_CAST")
        val ranges = Reflect.field(p, "ranges") as List<String>
        for (k in Reflect.list(p, "cookables")) {
            val raw = Reflect.str(k, "raw"); val cooked = Reflect.str(k, "cooked")
            val level = Reflect.int(k, "level"); val noBurn = Reflect.int(k, "noBurn")
            val span = (noBurn - level).coerceAtLeast(1).toDouble()
            val burnAt = { lvl: Int -> if (lvl >= noBurn) 0.0 else ((noBurn - lvl) / span) * 0.5 }
            c.recipe("recipe:cooking.${cooked.removePrefix("item.")}", "CookingPlugin.cook",
                listOf(raw to 1.0), listOf(cooked to 1.0), ActionTimeModel.COOK_TICKS, RecipeCategory.CRAFT,
                evMin = 1.0 - burnAt(level), evMax = 1.0 - burnAt(99),
                note = "Cooking $level (burns ${(burnAt(level) * 100).toInt()}%) / $noBurn no-burn; burnt fish destroyed")
            ranges.forEach { c.explainObj(it, raw) }
        }
    }

    // ---- RunecraftPlugin.kt: ladder (:37-46), count = 1 + lvl/multiStep, highest rune only (:61,:65) ----
    private fun runecraft(p: Any, c: Ctx) {
        val altar = Reflect.str(p, "altar")
        @Suppress("UNCHECKED_CAST")
        val essences = Reflect.field(p, "essences") as List<String>
        val ladder = Reflect.list(p, "ladder")
        for ((i, r) in ladder.withIndex()) {
            val rune = Reflect.str(r, "rune"); val level = Reflect.int(r, "level"); val step = Reflect.int(r, "multiStep")
            val hi = ladder.getOrNull(i + 1)?.let { Reflect.int(it, "level") - 1 } ?: 99
            val countLo = 1 + level / step; val countHi = 1 + hi / step
            for (ess in essences) {
                c.recipe("recipe:runecraft.${rune.removePrefix("item.")}.${ess.removePrefix("item.")}", "RunecraftPlugin.craft",
                    listOf(ess to 1.0), listOf(rune to 1.0), ActionTimeModel.RUNECRAFT_TICKS, RecipeCategory.CRAFT,
                    evMin = countLo.toDouble(), evMax = countHi.toDouble(),
                    note = "Runecraft $level..$hi only (highest rune forced): x$countLo..x$countHi per essence")
                c.explainObj(altar, ess)
            }
        }
    }

    // ---- FarmingPlugin.kt: crops (:46-55), GROW_TICKS 17 (:88), fixed yield, no failure ----
    private fun farming(p: Any, c: Ctx) {
        val patch = Reflect.str(p, "patch")
        for (crop in Reflect.list(p, "crops")) {
            val seed = Reflect.str(crop, "seed"); val produce = Reflect.str(crop, "produce")
            val yield = Reflect.int(crop, "yield")
            c.recipe("recipe:farming.${produce.removePrefix("item.")}", "FarmingPlugin.grow",
                listOf(seed to 1.0), listOf(produce to yield.toDouble()), ActionTimeModel.FARM_TICKS, RecipeCategory.CRAFT,
                note = "Farming ${Reflect.int(crop, "level")}; $yield per seed, no disease, ~10 s (FarmingPlugin.kt:88)")
            c.explainObj(patch, seed)
        }
    }

    // ---- HunterPlugin.kt: snare yields (:66-68), box ladder (:40-43), successChance (:100) ----
    private fun hunter(p: Any, c: Ctx) {
        val thicket = Reflect.str(p, "thicket")
        val outs = ArrayList<Pair<String, Double>>()
        if (id("item.feather") != null) outs += "item.feather" to 7.5   // random(3..12)
        if (id("item.raw_bird_meat") != null) outs += "item.raw_bird_meat" to 0.5
        if (id("item.bones") != null) outs += "item.bones" to 1.0
        if (outs.isNotEmpty()) {
            c.recipe("recipe:hunter.snare", "HunterPlugin.snare", emptyList(), outs, ActionTimeModel.HUNTER_TICKS,
                RecipeCategory.CRAFT, note = "Hunter 1; bird snare NOT consumed, never fails (HunterPlugin.kt:56-72)")
        }
        c.explainObj(thicket, "item.bird_snare")
        for (chin in Reflect.list(p, "boxLadder")) {
            val item = Reflect.str(chin, "item"); val level = Reflect.int(chin, "level")
            val chance = { lvl: Int -> (0.5 + (lvl - level) * 0.01).coerceIn(0.4, 0.95) }
            c.recipe("recipe:hunter.box.${item.removePrefix("item.")}", "HunterPlugin.box", emptyList(), listOf(item to 1.0),
                ActionTimeModel.HUNTER_TICKS, RecipeCategory.CRAFT, evMin = chance(level), evMax = chance(99),
                note = "Hunter $level; box trap NOT consumed")
        }
        c.explainObj(thicket, "item.box_trap")
    }

    private fun spellRunes(nameContains: String): List<Stack>? {
        val spell = MagicSpells.getMiscSpells().values.firstOrNull { it.name.lowercase().contains(nameContains) } ?: return null
        val elemental = setOf("item.air_rune", "item.water_rune", "item.earth_rune", "item.fire_rune").mapNotNull { id(it) }.toSet()
        return spell.items.filter { it.id !in elemental }.map { Stack(NodeId.ItemNode(it.id), it.amount.toDouble()) }
    }

    // ---- UtilitySpellsPlugin.kt: superheat bars (:35-43) + steel (:47-49); bones→fruit whole stack (:129-144) ----
    private fun utilitySpells(p: Any, c: Ctx) {
        val runes = spellRunes("superheat") ?: emptyList()
        val bars = Reflect.list(p, "bars").toMutableList()
        Reflect.nullable(p, "steelBar")?.let { bars += it }
        for (bar in bars) {
            val barKey = Reflect.str(bar, "bar")
            val ores = Reflect.map<String, Int>(bar, "ores")
            val coal = Reflect.int(bar, "coal")
            val inputs = ores.map { (k, n) -> k to n.toDouble() } + (if (coal > 0) listOf("item.coal" to coal.toDouble()) else emptyList())
            c.recipe("recipe:superheat.${barKey.removePrefix("item.")}", "UtilitySpellsPlugin.superheat", inputs,
                listOf(barKey to 1.0), ActionTimeModel.CLICK_TICKS, RecipeCategory.CONVERT,
                note = "Smithing ${Reflect.int(bar, "smithLevel")}; no iron failure (unlike the furnace)", extraInputs = runes)
        }
        for ((spellName, fruit) in listOf("bones to bananas" to "item.banana", "bones to peaches" to "item.peach")) {
            val fruitRunes = spellRunes(spellName) ?: continue
            // One cast converts the WHOLE bones stack; bones don't stack, so <= 28 per cast.
            c.recipe("recipe:${spellName.replace(' ', '_')}", "UtilitySpellsPlugin.bonesToFruit", listOf("item.bones" to 1.0),
                listOf(fruit to 1.0), ActionTimeModel.CLICK_TICKS / 28.0, RecipeCategory.CONVERT,
                note = "whole inventory per cast (UtilitySpellsPlugin.kt:129-144)",
                extraInputs = fruitRunes.map { Stack(it.node, it.qty / 28.0) })
        }
    }

    // ---- EnchantPlugin.kt: recipes (:38-49), tierRunes (:52-58) ----
    private fun enchant(p: Any, c: Ctx) {
        val tierRunes = Reflect.map<Int, List<Pair<String, Int>>>(p, "tierRunes")
        for ((from, res) in Reflect.map<String, Any>(p, "recipes")) {
            val to = Reflect.str(res, "to"); val tier = Reflect.int(res, "tier")
            val runes = tierRunes[tier]?.mapNotNull { (k, n) -> node(k)?.let { Stack(it, n.toDouble()) } } ?: emptyList()
            c.recipe("recipe:enchant.${to.removePrefix("item.")}", "EnchantPlugin", listOf(from to 1.0), listOf(to to 1.0),
                ActionTimeModel.CLICK_TICKS, RecipeCategory.CONVERT, note = "Magic ${Reflect.int(res, "level")}", extraInputs = runes)
        }
    }

    // ---- SpiritShieldPlugin.kt: recipes (:37-42) ----
    private fun spiritShields(p: Any, c: Ctx) {
        for (r in Reflect.list(p, "recipes")) {
            val a = Reflect.str(r, "a"); val b = Reflect.str(r, "b"); val result = Reflect.str(r, "result")
            c.recipe("recipe:spiritshield.${result.removePrefix("item.")}", "SpiritShieldPlugin", listOf(a to 1.0, b to 1.0),
                listOf(result to 1.0), ActionTimeModel.CLICK_TICKS, RecipeCategory.CONVERT, note = "Prayer ${Reflect.int(r, "level")}")
            c.explainItems(a, b)
        }
    }

    // ---- economy/forge/ForgePlugin.kt: upgrades (:39-46), runite bars + gp fee ----
    private fun forge(p: Any, c: Ctx) {
        val bar = Reflect.str(p, "bar"); val coins = Reflect.str(p, "coins"); val forge = Reflect.str(p, "forge")
        for (u in Reflect.list(p, "upgrades")) {
            val base = Reflect.str(u, "base"); val result = Reflect.str(u, "result")
            c.recipe("recipe:forge.${result.removePrefix("item.")}", "ForgePlugin",
                listOf(base to 1.0, bar to Reflect.int(u, "bars").toDouble(), coins to Reflect.int(u, "gp").toDouble()),
                listOf(result to 1.0), ActionTimeModel.CLICK_TICKS, RecipeCategory.CONVERT,
                note = "Smithing ${Reflect.int(u, "level")}; hammer not consumed")
            c.explainObj(forge, base)
        }
    }

    // ---- war/forge/WarForge.kt RECIPES (public object) ----
    private fun warForge(c: Ctx) {
        for (r in WarForge.RECIPES) {
            val inputs = mutableListOf(r.baseKey to 1.0, "item.coins_995" to r.coins.toDouble())
            if (r.commendations > 0) inputs += WarForge.COMMENDATION_KEY to r.commendations.toDouble()
            if (r.embers > 0) inputs += WarForge.EMBER_KEY to r.embers.toDouble()
            if (r.bars > 0) inputs += "item.runite_bar" to r.bars.toDouble()
            c.recipe("recipe:warforge.${r.outKey.removePrefix("item.")}", "WarForge", inputs, listOf(r.outKey to 1.0),
                ActionTimeModel.CLICK_TICKS, RecipeCategory.CONVERT, note = "Royal Smith; Commendations are untradeable")
        }
    }

    // ---- CosmeticDyePlugin.kt: dyes (:42-47), BASE, source cape destroyed (:84) ----
    private fun dyes(p: Any, c: Ctx) {
        val base = runCatching { Reflect.str(p, "BASE") }.getOrDefault("item.champions_cape")
        val dyes = Reflect.list(p, "dyes")
        val sources = listOf(base) + dyes.map { Reflect.str(it, "variantKey") }
        for (d in dyes) {
            val dye = Reflect.str(d, "dyeKey"); val variant = Reflect.str(d, "variantKey")
            for (src in sources) {
                if (src == variant) continue
                c.recipe("recipe:dye.${variant.removePrefix("item.")}.from.${src.removePrefix("item.")}", "CosmeticDyePlugin",
                    listOf(dye to 1.0, src to 1.0), listOf(variant to 1.0), ActionTimeModel.CLICK_TICKS, RecipeCategory.CONVERT,
                    note = "source cape destroyed")
                c.explainItems(dye, src)
            }
        }
    }

    // ---- PotionsPlugin.kt: doseByKey (generated by family()/familyChain(), :190-224) ----
    private fun potions(p: Any, c: Ctx) {
        for ((key, dose) in Reflect.map<String, Any>(p, "doseByKey")) {
            val next = Reflect.str(dose, "next")
            c.recipe("dose:${key.removePrefix("item.")}", "PotionsPlugin.drink", listOf(key to 1.0), listOf(next to 1.0),
                ActionTimeModel.CLICK_TICKS, RecipeCategory.CONVERT, kind = EdgeKind.DOSE, note = "drinking a dose")
        }
    }
}

package org.alter.plugins.content.war

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document
import org.bson.json.JsonWriterSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Persistent, server-wide state for **The War** (the AI-commanded raid system).
 *
 * The alter engine persists nothing globally — [org.alter.game.model.World.attr]
 * is runtime-only and there is no shutdown hook — so this object owns the war's
 * server-wide state and its own JSON persistence.
 *
 * ### What it stores, per *front*
 * - **Persisted:** the castle's surviving **knight pool** (battle losses must
 *   survive a restart) and any **city-fallen** recovery ticks (the lasting penalty
 *   when General Zo's castle is overrun).
 * - **Runtime only:** the live [RaidStatus] (phase / tier / goblins alive), written
 *   by the [AttackDirector] each tick and read by the HUD, General Zo, citizenship,
 *   etc. A restart simply resets this to peace, which is fine.
 *
 * The old 0–100 *siege pressure* / four-band model has been retired in favour of
 * this discrete, event-driven status.
 *
 * @see WarStatePlugin for the lifecycle wiring.
 */
object WarState {
    private val logger = KotlinLogging.logger {}

    /** Bumped when the on-disk schema changes (was 1 for the old pressure model). */
    private const val SCHEMA_VERSION = 2

    /** Realm-wide war-supply meter cap — skilling the Mire fills it, campaigns drain it. TUNABLE. */
    private const val SUPPLY_METER_MAX = 3000

    private val saveFile: Path = Paths.get("../data/saves/world/war_state.json")
    private val prettyPrint: JsonWriterSettings = JsonWriterSettings.builder().indent(true).build()

    /** The three discrete states a front can be in. */
    enum class Phase { PEACE, UNDER_RAID, CITY_FALLEN }

    /**
     * The live, player-facing snapshot of a front's raid (runtime only). Written by
     * the [AttackDirector]; read by the HUD / General Zo / `::war` etc.
     */
    data class RaidStatus(
        val phase: Phase = Phase.PEACE,
        val tierName: String = "",
        val goblinsAlive: Int = 0,
        /** The raid's starting roster, so the HUD bar can show a remaining-fraction. */
        val peakRoster: Int = 0,
    )

    // --- persisted ---
    private val knightPoolByFront = HashMap<String, Int>()
    private val knightPoolMaxByFront = HashMap<String, Int>()
    private val cityFallenTicksByFront = HashMap<String, Int>()

    // --- runtime only ---
    private val raidStatusByFront = HashMap<String, RaidStatus>()

    @Volatile
    private var dirty = false

    /**
     * Ensure [frontId] exists. Idempotent — call on world init for every front so a
     * freshly-added front appears with a full garrison without a save-file edit.
     */
    fun registerFront(frontId: String, poolMax: Int = 100) {
        knightPoolMaxByFront[frontId] = poolMax
        if (!knightPoolByFront.containsKey(frontId)) {
            knightPoolByFront[frontId] = poolMax
            dirty = true
        }
    }

    fun knightPoolMax(frontId: String): Int = knightPoolMaxByFront[frontId] ?: 100

    /** The castle's surviving knight count for [frontId] (clamped to its max). */
    fun getKnightPool(frontId: String): Int =
        knightPoolByFront.getOrDefault(frontId, knightPoolMax(frontId)).coerceIn(0, knightPoolMax(frontId))

    /** Set the surviving knight count (clamped). Marks state dirty. */
    fun setKnightPool(frontId: String, value: Int) {
        val clamped = value.coerceIn(0, knightPoolMax(frontId))
        if (knightPoolByFront.put(frontId, clamped) != clamped) dirty = true
    }

    /** Add [delta] (may be negative) to the knight pool; returns the new value. */
    fun addKnightPool(frontId: String, delta: Int): Int {
        setKnightPool(frontId, getKnightPool(frontId) + delta)
        return getKnightPool(frontId)
    }

    // --- realm war-supply meter (single realm-wide value; the Mire fills it, campaigns drain it) ---
    private var supplyMeter = 0

    fun supplyMeterMax(): Int = SUPPLY_METER_MAX
    fun getSupplyMeter(): Int = supplyMeter.coerceIn(0, SUPPLY_METER_MAX)
    fun setSupplyMeter(value: Int) {
        val clamped = value.coerceIn(0, SUPPLY_METER_MAX)
        if (clamped != supplyMeter) { supplyMeter = clamped; dirty = true }
    }
    /** Add [delta] (may be negative) to the realm supply meter; returns the new clamped value. */
    fun addSupplyMeter(delta: Int): Int { setSupplyMeter(supplyMeter + delta); return getSupplyMeter() }

    // --- march counter (every Nth launched march is a GRAND MARCH — see MarchPlugin) ---
    private var marchCount = 0

    fun getMarchCount(): Int = marchCount

    fun incMarchCount(): Int {
        marchCount++
        dirty = true
        return marchCount
    }

    // --- patron-funded marches (store — story-and-grind-design §7): queued by the store's
    //     RewardDeliveryPlugin on purchase, consumed by MarchPlugin at the next muster call.
    //     Entries are "name|1" (grand) / "name|0". Persisted so a purchase survives a restart.
    private val patronQueue = ArrayList<String>()

    fun queuePatronMarch(name: String, grand: Boolean) {
        patronQueue.add("$name|${if (grand) 1 else 0}")
        dirty = true
    }

    /** Pop the next funded march as (patronName, isGrand), or null if none waits. */
    fun popPatronMarch(): Pair<String, Boolean>? {
        if (patronQueue.isEmpty()) return null
        val e = patronQueue.removeAt(0)
        dirty = true
        return e.substringBeforeLast('|') to e.endsWith("|1")
    }

    // --- district pressure (the reconquest of Varrock — story-and-grind-design §5) ---
    private val districtPressureByKey = HashMap<String, Int>()

    fun getDistrictPressure(key: String): Int = districtPressureByKey[key] ?: 0

    fun setDistrictPressure(key: String, value: Int) {
        val v = value.coerceAtLeast(0)
        if (districtPressureByKey.put(key, v) != v) dirty = true
    }

    /** Add [delta] (may be negative) to a district's pressure; returns the new value. */
    fun addDistrictPressure(key: String, delta: Int): Int {
        setDistrictPressure(key, getDistrictPressure(key) + delta)
        return getDistrictPressure(key)
    }

    // --- city-fallen (lasting penalty after General Zo's castle is overrun) ---

    fun isCityFallen(frontId: String): Boolean = (cityFallenTicksByFront[frontId] ?: 0) > 0
    fun cityFallenTicksLeft(frontId: String): Int = cityFallenTicksByFront[frontId] ?: 0

    fun setCityFallen(frontId: String, ticks: Int) {
        cityFallenTicksByFront[frontId] = ticks.coerceAtLeast(0)
        dirty = true
    }

    /** Count the fallen timer down by [ticks]; returns the ticks remaining. */
    fun decayCityFallen(frontId: String, ticks: Int): Int {
        val left = ((cityFallenTicksByFront[frontId] ?: 0) - ticks).coerceAtLeast(0)
        cityFallenTicksByFront[frontId] = left
        dirty = true
        return left
    }

    fun clearCityFallen(frontId: String) {
        if ((cityFallenTicksByFront[frontId] ?: 0) != 0) {
            cityFallenTicksByFront[frontId] = 0
            dirty = true
        }
    }

    // --- live raid status (runtime) ---

    fun raidStatus(frontId: String): RaidStatus = raidStatusByFront[frontId] ?: RaidStatus()

    fun setRaidStatus(frontId: String, status: RaidStatus) {
        raidStatusByFront[frontId] = status
    }

    /** The effective phase: city-fallen overrides the live raid status. */
    fun phaseOf(frontId: String): Phase =
        if (isCityFallen(frontId)) Phase.CITY_FALLEN else raidStatus(frontId).phase

    fun isAtPeace(frontId: String): Boolean = phaseOf(frontId) == Phase.PEACE

    fun knownFronts(): Set<String> = knightPoolByFront.keys.toSet()

    /**
     * Load war state from disk. Safe when no file exists (starts fresh) and never
     * throws — a corrupt file is logged and treated as "start fresh". Older v1
     * (pressure) saves are simply ignored: the new fields are absent, so every
     * front begins at a full garrison and at peace.
     */
    fun load() {
        try {
            if (!Files.exists(saveFile)) {
                logger.info { "No war state file at $saveFile; starting fresh." }
                return
            }
            val doc = Document.parse(saveFile.readText().trimStart('﻿'))
            val fronts = doc.get("fronts", Document::class.java) ?: Document()
            knightPoolByFront.clear()
            cityFallenTicksByFront.clear()
            for ((frontId, value) in fronts) {
                val sub = value as? Document ?: continue
                (sub.get("knightPool") as? Number)?.let { knightPoolByFront[frontId] = it.toInt().coerceAtLeast(0) }
                (sub.get("cityFallen") as? Number)?.let { cityFallenTicksByFront[frontId] = it.toInt().coerceAtLeast(0) }
            }
            (doc.get("supplyMeter") as? Number)?.let { supplyMeter = it.toInt().coerceIn(0, SUPPLY_METER_MAX) }
            districtPressureByKey.clear()
            (doc.get("districts", Document::class.java))?.forEach { (key, value) ->
                (value as? Number)?.let { districtPressureByKey[key] = it.toInt().coerceAtLeast(0) }
            }
            (doc.get("marchCount") as? Number)?.let { marchCount = it.toInt().coerceAtLeast(0) }
            patronQueue.clear()
            (doc.get("patronMarches") as? List<*>)?.forEach { (it as? String)?.let(patronQueue::add) }
            dirty = false
            logger.info { "Loaded war state: ${knightPoolByFront.size} front(s) from $saveFile." }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load war state from $saveFile; starting fresh." }
        }
    }

    /** Write war state to disk. No-op when nothing changed unless [force]. Never throws. */
    fun save(force: Boolean = false) {
        if (!dirty && !force) return
        try {
            Files.createDirectories(saveFile.parent)
            val fronts = Document()
            val keys = knightPoolByFront.keys + cityFallenTicksByFront.keys
            keys.forEach { frontId ->
                fronts.append(
                    frontId,
                    Document()
                        .append("knightPool", knightPoolByFront[frontId] ?: knightPoolMax(frontId))
                        .append("cityFallen", cityFallenTicksByFront[frontId] ?: 0),
                )
            }
            val districts = Document()
            districtPressureByKey.forEach { (key, value) -> districts.append(key, value) }
            val doc = Document()
                .append("version", SCHEMA_VERSION)
                .append("supplyMeter", supplyMeter)
                .append("fronts", fronts)
                .append("districts", districts)
                .append("marchCount", marchCount)
                .append("patronMarches", patronQueue.toList())
            saveFile.writeText(doc.toJson(prettyPrint))
            dirty = false
            logger.info { "Saved war state: ${knightPoolByFront.size} front(s) to $saveFile." }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save war state to $saveFile; will retry." }
        }
    }
}

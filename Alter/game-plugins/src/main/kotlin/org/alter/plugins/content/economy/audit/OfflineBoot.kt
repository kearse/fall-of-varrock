package org.alter.plugins.content.economy.audit

import dev.openrune.cache.CacheManager
import dev.openrune.cache.filestore.Cache
import org.alter.game.DevContext
import org.alter.game.GameContext
import org.alter.game.Server
import org.alter.game.model.Tile
import org.alter.game.model.World
import org.alter.game.model.entity.Npc
import org.alter.game.model.skill.SkillSet
import org.alter.game.plugin.PluginRepository
import org.alter.game.saving.formats.SaveFormatType
import org.alter.game.service.game.ItemMetadataService
import org.alter.plugins.content.magic.MagicSpells
import org.alter.rscm.RSCM
import java.nio.file.Path

/**
 * Boots enough of the game **offline** for the economy auditor: the cache, the RSCM key table,
 * the item overrides (the real [ItemMetadataService.loadAll], so `cost`/alch overrides apply
 * exactly as at server boot), a bare [World] (no network, no services, no map), and then an
 * explicit allow-list of shop / recipe plugins constructed through the same
 * `(PluginRepository, World, Server)` constructor the live loader uses. Their `createShop` calls
 * land in `world.plugins.shops`, their `onItemOnItem/onItemOnObj` binds land in the repository,
 * which is what the extractors read. Nothing here touches `world.network`.
 *
 * Working directory must be the `game-plugins` module dir (like `metaReqCheck`): the overrides and
 * RSCM loaders read `../data/cfg` relative to it.
 */
object OfflineBoot {

    const val REVISION = 228

    /** Plugins whose absence would make the audit blind to a whole vendor: abort if any fails. */
    val REQUIRED: List<String> = listOf(
        "org.alter.plugins.content.areas.lumbridge.npcs.stores.LumbridgeShopHubPlugin",
        "org.alter.plugins.content.areas.lumbridge.npcs.stores.LumbridgeGeneralStorePlugin",
        "org.alter.plugins.content.areas.lumbridge.npcs.stores.LumbridgeStylistPlugin",
        "org.alter.plugins.content.areas.lumbridge.npcs.SmithingApprenticePlugin",
        "org.alter.plugins.content.weapons.custom.WarlordsArmouryPlugin",
        "org.alter.plugins.content.economy.pk.PkRewardsPlugin",
        "org.alter.plugins.content.war.PrestigeShopPlugin",
        "org.alter.plugins.content.economy.store.DonorStorePlugin",
        "org.alter.plugins.content.economy.cosmetics.CosmeticDyePlugin",
        "org.alter.plugins.content.economy.SupplyDepotPlugin",
        "org.alter.plugins.content.economy.tradingpost.TradingPostPlugin",
    )

    /** Recipe / converter plugins (read by reflection): a failure here is reported, not fatal. */
    val RECIPE_PLUGINS: List<String> = listOf(
        "org.alter.plugins.content.skills.smithing.SmithingPlugin",
        "org.alter.plugins.content.skills.crafting.CraftingPlugin",
        "org.alter.plugins.content.skills.fletching.FletchingPlugin",
        "org.alter.plugins.content.skills.herblore.HerblorePlugin",
        "org.alter.plugins.content.skills.cooking.CookingPlugin",
        "org.alter.plugins.content.skills.runecraft.RunecraftPlugin",
        "org.alter.plugins.content.skills.farming.FarmingPlugin",
        "org.alter.plugins.content.skills.hunter.HunterPlugin",
        "org.alter.plugins.content.magic.utility.UtilitySpellsPlugin",
        "org.alter.plugins.content.magic.enchant.EnchantPlugin",
        "org.alter.plugins.content.bosses.SpiritShieldPlugin",
        "org.alter.plugins.content.economy.forge.ForgePlugin",
        "org.alter.plugins.content.items.consumables.potions.PotionsPlugin",
    )

    /** Constructed only so their item-on-item / item-on-object binds show up in the coverage check. */
    val COVERAGE_PLUGINS: List<String> = listOf(
        "org.alter.plugins.content.skills.firemaking.FiremakingPlugin",
        "org.alter.plugins.content.skills.construction.ConstructionPlugin",
        "org.alter.plugins.content.mechanics.prayer.PrayerAltarPlugin",
        "org.alter.plugins.content.mechanics.water.WaterPlugin",
        // AmuletOfGloryPlugin needs an equipment def the bare offline world lacks (NPE); it is a
        // teleport charge ladder, not an NPC value converter, so it is left out.
        "org.alter.plugins.content.items.lootingbag.LootingBagPlugin",
        "org.alter.plugins.content.items.spade.SpadePlugin",
        "org.alter.plugins.content.areas.lumbridge.npcs.BartenderPlugin",
        "org.alter.plugins.content.items.ancient_wyvern_shield.AncientWyvernShieldPlugin",
        "org.alter.plugins.content.interfaces.itemsets.ItemsetsPlugin",
        "org.alter.plugins.content.items.mystery_box.MysteryBoxPlugin",
        "org.alter.plugins.content.magic.alchemy.AlchemyPlugin",
    )

    class Booted(
        val world: World,
        val plugins: Map<String, Any>,
        val failed: List<Pair<String, String>>,
    )

    fun boot(cachePath: Path): Booted {
        CacheManager.init(Cache.load(cachePath, false), REVISION)
        RSCM.init()
        ItemMetadataService().loadAll()

        val context = GameContext(
            initialLaunch = false,
            name = "economy-audit",
            revision = REVISION,
            saveFormat = SaveFormatType.JSON,
            cycleTime = 600,
            playerLimit = 1,
            home = Tile(3222, 3218, 0),
            skillCount = SkillSet.DEFAULT_SKILL_COUNT,
            npcStatCount = Npc.Stats.DEFAULT_NPC_STAT_COUNT,
            runEnergy = true,
            gItemPublicDelay = 100,
            gItemDespawnDelay = 300,
            preloadMaps = false,
        )
        val world = World(context, DevContext(false, false, false, false, false, false))
        val server = Server()

        if (!MagicSpells.isLoaded()) MagicSpells.loadSpellRequirements(world)

        val constructed = LinkedHashMap<String, Any>()
        val failed = ArrayList<Pair<String, String>>()
        for (cls in REQUIRED + RECIPE_PLUGINS + COVERAGE_PLUGINS) {
            try {
                val pluginClass = Class.forName(cls)
                val ctor = pluginClass.getConstructor(PluginRepository::class.java, World::class.java, Server::class.java)
                constructed[cls] = ctor.newInstance(world.plugins, world, server)
            } catch (e: Throwable) {
                val root = generateSequence<Throwable>(e) { it.cause }.last()
                failed += cls to "${root.javaClass.simpleName}: ${root.message}"
            }
        }
        val fatal = failed.filter { it.first in REQUIRED }
        check(fatal.isEmpty()) {
            "Required shop plugin(s) failed to construct offline; the audit would be blind:\n" +
                fatal.joinToString("\n") { "  ${it.first}: ${it.second}" }
        }
        return Booted(world, constructed, failed)
    }
}

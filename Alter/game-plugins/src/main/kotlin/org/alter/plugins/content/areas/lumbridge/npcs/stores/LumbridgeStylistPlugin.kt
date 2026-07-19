package org.alter.plugins.content.areas.lumbridge.npcs.stores

import io.github.oshai.kotlinlogging.KotlinLogging
import org.alter.api.ext.*
import org.alter.game.Server
import org.alter.game.info.NpcInfo
import org.alter.game.info.PlayerInfo
import org.alter.game.model.Direction
import org.alter.game.model.World
import org.alter.game.model.appearance.Appearance
import org.alter.game.model.appearance.Colours
import org.alter.game.model.appearance.Gender
import org.alter.game.model.appearance.Looks
import org.alter.game.model.entity.Player
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.shop.PurchasePolicy
import org.alter.game.model.shop.ShopItem
import org.alter.game.plugin.KotlinPlugin
import org.alter.game.plugin.PluginRepository
import org.alter.plugins.content.mechanics.appearance.AppearanceDesign
import org.alter.plugins.content.mechanics.shops.CoinCurrency
import org.alter.plugins.content.mechanics.shops.ShopTabs
import org.alter.plugins.content.mechanics.shops.bindVendorOptions
import org.alter.rscm.RSCM.getRSCM

private val logger = KotlinLogging.logger {}

/**
 * **Lumbridge Stylist** — the courtyard appearance shop (Aurelia). One NPC in the Lumbridge
 * castle courtyard that does two jobs:
 *
 *  - **Talk-to → makeover.** A free, live-preview restyle driven entirely through dialogue
 *    ([restyleMenu]): switch gender, cycle hairstyle / facial hair, recolour hair / skin /
 *    shirt / legs, and re-style the *default clothes* (the torso/arms/legs identkit body parts
 *    shown when nothing is equipped). Every step mutates [Player.appearance] and re-syncs with
 *    [PlayerInfo.syncAppearance] so the change shows on the player model immediately, and it
 *    persists automatically (the save layer serialises `player.appearance`).
 *
 *  - **Trade → clothes shop.** A coins-only cosmetic storefront ([ShopTabs]) selling a variety
 *    of aesthetic clothes (shirts, robes, legwear, hats, gloves, boots) and a full rack of
 *    capes. `BUY_STOCK` so players can sell the pieces back. Pure cosmetic — no stats, no power.
 *
 * The primary makeover path is now the native character-design interface (679, live 3D model —
 * [AppearanceDesign]); the step-by-step dialogue restyle remains as a menu option and fallback
 * until the interface is confirmed rendering on the custom client. Stock and appearance pools
 * are resolved defensively — a missing cache key is skipped rather than crashing plugin init.
 */
class LumbridgeStylistPlugin(
    r: PluginRepository,
    world: World,
    server: Server,
) : KotlinPlugin(r, world, server) {

    /** One item line in a shop. `sell` is the coins price; null falls back to cache value. */
    private data class Ware(val key: String, val amount: Int, val sell: Int? = null)

    /** Resolved stylist npc id (or -1). Passed to [chatNpc] so its head shows even when the
     *  makeover is opened by command (no interacting npc set). */
    private val npcId: Int = resolveOrNull(NPC) ?: -1

    /** NPC dialogue line in the stylist's voice (head + name), independent of interaction state. */
    private suspend fun QueueTask.say(player: Player, message: String) =
        chatNpc(player, message, npc = npcId, title = STYLIST_NAME)

    // ------------------------------------ shop stock ------------------------------------

    /** Shirts, robes and other tops — the aesthetic "clothes" rack. */
    private val topStock = listOf(
        Ware("item.shirt", 100, 250), Ware("item.woven_top", 100, 250),
        Ware("item.desert_shirt", 100, 250), Ware("item.desert_robe", 100, 400),
        Ware("item.priest_gown", 100, 300), Ware("item.mime_top", 100, 500),
        Ware("item.pink_robe_top", 100, 350), Ware("item.green_robe_top", 100, 350),
        Ware("item.blue_robe_top", 100, 350), Ware("item.cream_robe_top", 100, 350),
        Ware("item.turquoise_robe_top", 100, 350), Ware("item.grey_robe_top", 100, 350),
        Ware("item.red_robe_top", 100, 350), Ware("item.yellow_robe_top", 100, 350),
        Ware("item.teal_robe_top", 100, 350), Ware("item.purple_robe_top", 100, 350),
    )

    /** Legwear — skirts, trousers, shorts. */
    private val legStock = listOf(
        Ware("item.trousers", 100, 250), Ware("item.skirt", 100, 250),
        Ware("item.shorts", 100, 250), Ware("item.flared_trousers", 100, 400),
        Ware("item.blue_skirt", 100, 300), Ware("item.pink_skirt", 100, 300),
        Ware("item.black_skirt", 100, 300), Ware("item.mime_legs", 100, 500),
    )

    /** Hats, gloves and boots — accessories to finish an outfit. */
    private val accessoryStock = listOf(
        // Hats
        Ware("item.pink_hat", 100, 250), Ware("item.green_hat", 100, 250),
        Ware("item.blue_hat", 100, 250), Ware("item.cream_hat", 100, 250),
        Ware("item.turquoise_hat", 100, 250), Ware("item.grey_hat", 100, 250),
        Ware("item.red_hat", 100, 250), Ware("item.yellow_hat", 100, 250),
        Ware("item.teal_hat", 100, 250), Ware("item.purple_hat", 100, 250),
        Ware("item.wizard_hat", 100, 300), Ware("item.blue_wizard_hat", 100, 300),
        Ware("item.blue_beret", 100, 400), Ware("item.red_beret", 100, 400),
        Ware("item.highwayman_mask", 100, 500), Ware("item.sleeping_cap", 100, 300),
        Ware("item.chefs_hat", 100, 250),
        // Gloves
        Ware("item.leather_gloves", 100, 200), Ware("item.grey_gloves", 100, 250),
        Ware("item.red_gloves", 100, 250), Ware("item.yellow_gloves", 100, 250),
        Ware("item.teal_gloves", 100, 250), Ware("item.purple_gloves", 100, 250),
        // Boots
        Ware("item.leather_boots", 100, 200), Ware("item.pink_boots", 100, 250),
        Ware("item.green_boots", 100, 250), Ware("item.blue_boots", 100, 250),
        Ware("item.cream_boots", 100, 250), Ware("item.turquoise_boots", 100, 250),
        Ware("item.grey_boots", 100, 250), Ware("item.red_boots", 100, 250),
        Ware("item.yellow_boots", 100, 250), Ware("item.teal_boots", 100, 250),
        Ware("item.purple_boots", 100, 250), Ware("item.mime_boots", 100, 500),
    )

    /** Capes — the full colour rack plus a few themed ones. */
    private val capeStock = listOf(
        Ware("item.red_cape", 100, 500), Ware("item.black_cape", 100, 500),
        Ware("item.blue_cape", 100, 500), Ware("item.yellow_cape", 100, 500),
        Ware("item.green_cape", 100, 500), Ware("item.purple_cape", 100, 500),
        Ware("item.orange_cape", 100, 500),
        Ware("item.saradomin_cape", 50, 2000), Ware("item.guthix_cape", 50, 2000),
        Ware("item.zamorak_cape", 50, 2000), Ware("item.cape_of_legends", 50, 5000),
    )

    private val tabs by lazy {
        listOf(
            ShopTabs.Tab("Shirts & robes", SHOP_TOPS, icon = "item.blue_robe_top"),
            ShopTabs.Tab("Legwear", SHOP_LEGS, icon = "item.skirt"),
            ShopTabs.Tab("Hats, gloves & boots", SHOP_ACCESSORIES, icon = "item.blue_hat"),
            ShopTabs.Tab("Capes", SHOP_CAPES, icon = "item.blue_cape"),
        )
    }

    init {
        // ---- cosmetic clothes shop (coins, sell-and-buy-back its own stock) ----
        coinShop(SHOP_TOPS, topStock)
        coinShop(SHOP_LEGS, legStock)
        coinShop(SHOP_ACCESSORIES, accessoryStock)
        coinShop(SHOP_CAPES, capeStock)

        // ---- the stylist, in the open courtyard aisle north of the market rows ----
        spawnNpc(NPC, x = 3221, z = 3229, height = 0, walkRadius = 0, direction = Direction.SOUTH)
        // Client-facing name (no cache edit), re-applied on every (re)spawn.
        onNpcSpawn(NPC) { NpcInfo(npc).setTempName(STYLIST_NAME) }

        // Every click option the npc has routes to the main menu (Talk-to leads to both the
        // makeover and the shop, so neither is a dead click even if the def lacks a Trade verb).
        if (!bindVendorOptions(NPC) { player.queue { mainMenu(player) } }) {
            logger.warn { "stylist: '$NPC' has no click options; use ::stylist / ::makeover." }
        }

        // Convenience openers (and a safety net if the npc can't be reached).
        onCommand("stylist", description = "Open the Lumbridge Stylist clothes shop") {
            openClothes(player)
        }
        onCommand("makeover", description = "Restyle your appearance") {
            AppearanceDesign.open(player)
        }
    }

    // ------------------------------------ dialogue ------------------------------------

    private suspend fun QueueTask.mainMenu(player: Player) {
        say(player, "Welcome to my salon! I can restyle your look or sell<br>you something fashionable to wear. What will it be?")
        when (options(player,
            "Restyle my appearance.",
            "Show me your clothes and capes.",
            "Restyle me step by step, the old way.",
            "Who are you?",
            "Nothing, thanks.",
            title = STYLIST_NAME)) {
            // The mirror: the native character-design screen (interface 679) with the live
            // 3D player model. The step-by-step dialogue below stays as the fallback until
            // the interface is confirmed rendering on the custom client (see AppearanceDesign).
            1 -> AppearanceDesign.open(player)
            2 -> {
                say(player, "Take your time - everything's pure fashion, no combat<br>bonuses here.")
                openClothes(player)
            }
            3 -> restyleMenu(player)
            4 -> {
                say(player, "I'm $STYLIST_NAME, Lumbridge's finest stylist. Hair, skin,<br>a whole new wardrobe - I do it all, and the restyle is<br>on the house.")
                mainMenu(player)
            }
            5 -> chatPlayer(player, "Nothing, thanks.")
        }
    }

    private fun openClothes(player: Player) {
        ShopTabs.open(player, tabs)
    }

    /** Top-level restyle menu. Recurses so the player can make several changes in one sitting. */
    private suspend fun QueueTask.restyleMenu(player: Player) {
        // Never mutate the shared DEFAULT_* appearance arrays: give the player their own copy first.
        ensureOwnedAppearance(player)
        when (options(player,
            "Hairstyle & facial hair.",
            "Colours (hair, skin, clothes).",
            "Default clothes (body style).",
            "Switch gender.",
            "I'm happy as I am.",
            title = "Restyle")) {
            1 -> hairMenu(player)
            2 -> coloursMenu(player)
            3 -> clothesMenu(player)
            4 -> genderMenu(player)
            5 -> say(player, "Looking good! Come back any time.")
        }
    }

    private suspend fun QueueTask.hairMenu(player: Player) {
        when (options(player,
            "Change hairstyle.",
            if (player.appearance.gender == Gender.MALE) "Change facial hair." else "Change facial hair. (male only)",
            "Back.",
            title = "Hair")) {
            1 -> { cycleLook(player, HEAD, "hairstyle"); hairMenu(player) }
            2 -> {
                if (player.appearance.gender == Gender.MALE) cycleLook(player, JAW, "facial hair")
                else say(player, "Facial hair only suits the gents, I'm afraid.")
                hairMenu(player)
            }
            3 -> restyleMenu(player)
        }
    }

    private suspend fun QueueTask.coloursMenu(player: Player) {
        when (options(player,
            "Hair colour.",
            "Skin colour.",
            "Shirt colour.",
            "Leg colour.",
            "Back.",
            title = "Colours")) {
            1 -> { cycleColour(player, COL_HAIR, Colours.HAIR_COLOURS.size, "hair colour"); coloursMenu(player) }
            2 -> { cycleColour(player, COL_SKIN, Colours.SKIN_COLOURS.size, "skin tone"); coloursMenu(player) }
            3 -> { cycleColour(player, COL_TORSO, Colours.TORSO_COLOURS.size, "shirt colour"); coloursMenu(player) }
            4 -> { cycleColour(player, COL_LEGS, Colours.LEG_COLOURS.size, "leg colour"); coloursMenu(player) }
            5 -> restyleMenu(player)
        }
    }

    private suspend fun QueueTask.clothesMenu(player: Player) {
        say(player, "These are your 'default' clothes - the outfit shown<br>when you've nothing equipped in that slot.")
        when (options(player,
            "Shirt style.",
            "Sleeve style.",
            "Trouser style.",
            "Back.",
            title = "Default clothes")) {
            1 -> { cycleLook(player, TORSO, "shirt"); clothesMenu(player) }
            2 -> { cycleLook(player, ARMS, "sleeves"); clothesMenu(player) }
            3 -> { cycleLook(player, LEGS, "trousers"); clothesMenu(player) }
            4 -> restyleMenu(player)
        }
    }

    private suspend fun QueueTask.genderMenu(player: Player) {
        val current = if (player.appearance.gender == Gender.MALE) "male" else "female"
        say(player, "You're currently $current. Switching resets your body<br>style and hair to a fresh default - your colours stay.")
        when (options(player, "Become male.", "Become female.", "Cancel.", title = "Gender")) {
            1 -> { setGender(player, Gender.MALE); say(player, "There you go - a fine gentleman."); restyleMenu(player) }
            2 -> { setGender(player, Gender.FEMALE); say(player, "Lovely - a fine lady."); restyleMenu(player) }
            3 -> restyleMenu(player)
        }
    }

    // ------------------------------------ appearance ops ------------------------------------

    /** Body-part option codes as used by [Appearance.getLook]. */
    private val HEAD = 0; private val JAW = 1; private val TORSO = 2
    private val ARMS = 3; private val LEGS = 5
    // Colour slot indices in [Appearance.colors]: [hair, torso, legs, feet, skin].
    private val COL_HAIR = 0; private val COL_TORSO = 1; private val COL_LEGS = 2; private val COL_SKIN = 4

    /**
     * A fresh player's [Appearance] aliases the shared `DEFAULT_*` arrays in [Appearance]; mutating
     * them in place would corrupt every other default-look player. Replace with private copies once.
     */
    private fun ensureOwnedAppearance(player: Player) {
        val a = player.appearance
        player.appearance = Appearance(a.looks.copyOf(), a.colors.copyOf(), a.gender)
    }

    /** The style pool for a body-part option, for the given gender. */
    private fun poolFor(option: Int, gender: Gender): Array<Int> = when (option) {
        HEAD -> Looks.getHeads(gender)
        JAW -> Looks.getJaws(gender)
        TORSO -> Looks.getTorsos(gender)
        ARMS -> Looks.getArms(gender)
        4 -> Looks.getHands(gender)
        LEGS -> Looks.getLegs(gender)
        6 -> Looks.getFeets(gender)
        else -> arrayOf()
    }

    /**
     * The index into [Appearance.looks] for a body-part [option] and [gender]. The female array
     * omits JAW, so slots after HEAD shift down by one (mirrors [Appearance.getLook]). Returns
     * null when the part doesn't exist for the gender (female JAW).
     */
    private fun slotFor(option: Int, gender: Gender): Int? = when (gender) {
        Gender.MALE -> option // 0..6 map straight through
        Gender.FEMALE -> when (option) {
            HEAD -> 0
            JAW -> null
            else -> option - 1
        }
    }

    private suspend fun QueueTask.cycleLook(player: Player, option: Int, label: String) {
        val pool = poolFor(option, player.appearance.gender)
        if (slotFor(option, player.appearance.gender) == null || pool.size <= 1) {
            say(player, "There's nothing to change there.")
            return
        }
        cycle(player, label) { delta -> stepLook(player, option, delta) }
    }

    private suspend fun QueueTask.cycleColour(player: Player, slot: Int, size: Int, label: String) {
        if (size <= 1) { say(player, "There's nothing to change there."); return }
        cycle(player, label) { delta -> stepColour(player, slot, size, delta) }
    }

    /** Shared "Next / Previous / keep it" loop with live preview. */
    private suspend fun QueueTask.cycle(player: Player, label: String, step: (Int) -> Unit) {
        while (true) {
            when (options(player, "Next $label.", "Previous $label.", "This one's perfect.", title = "Choose your $label")) {
                1 -> step(1)
                2 -> step(-1)
                else -> return
            }
        }
    }

    private fun stepLook(player: Player, option: Int, delta: Int) {
        val gender = player.appearance.gender
        val slot = slotFor(option, gender) ?: return
        val size = poolFor(option, gender).size
        if (size <= 0 || slot !in player.appearance.looks.indices) return
        player.appearance.looks[slot] = wrap(player.appearance.looks[slot] + delta, size)
        PlayerInfo(player).syncAppearance()
    }

    private fun stepColour(player: Player, slot: Int, size: Int, delta: Int) {
        if (slot !in player.appearance.colors.indices) return
        player.appearance.colors[slot] = wrap(player.appearance.colors[slot] + delta, size)
        PlayerInfo(player).syncAppearance()
    }

    private fun setGender(player: Player, gender: Gender) {
        if (player.appearance.gender == gender) return
        val defaults = if (gender == Gender.MALE) Appearance.DEFAULT_MALE else Appearance.DEFAULT_FEMALE
        // Keep the player's current colours; reset looks to a valid default for the new gender.
        player.appearance = Appearance(defaults.looks.copyOf(), player.appearance.colors.copyOf(), gender)
        PlayerInfo(player).syncAppearance()
    }

    /** Wrap [value] into 0 until [size] (handles negatives). */
    private fun wrap(value: Int, size: Int): Int = ((value % size) + size) % size

    // ------------------------------------ shop builder ------------------------------------

    private fun coinShop(name: String, wares: List<Ware>) {
        val stock = wares.mapNotNull { w -> resolveOrNull(w.key)?.let { ShopItem(it, w.amount, w.sell) } }
        createShop(name, CoinCurrency(), purchasePolicy = PurchasePolicy.BUY_STOCK, stockSize = maxOf(stock.size, 1)) {
            stock.forEachIndexed { i, item -> items[i] = item }
        }
    }

    private fun resolveOrNull(key: String): Int? = try { getRSCM(key) } catch (e: Exception) { null }

    private companion object {
        /** Stock make-over mage id, repurposed as the courtyard stylist (renamed at runtime). */
        const val NPC = "npc.makeover_mage"
        const val STYLIST_NAME = "Aurelia the Stylist"

        const val SHOP_TOPS = "Lumbridge Stylist - Shirts & Robes"
        const val SHOP_LEGS = "Lumbridge Stylist - Legwear"
        const val SHOP_ACCESSORIES = "Lumbridge Stylist - Accessories"
        const val SHOP_CAPES = "Lumbridge Stylist - Capes"
    }
}

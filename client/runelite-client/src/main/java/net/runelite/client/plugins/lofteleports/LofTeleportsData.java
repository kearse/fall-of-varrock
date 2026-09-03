/*
 * Fall of Varrock — teleport portal: client-side mirror of the server destination catalog.
 *
 * MUST stay in sync with the server's org.alter.plugins.content.teleport.TeleportRegistry +
 * TeleportCategory (same category order, same per-category row order) — the overlay sends
 * "::tp <catIndex> <rowIndex>" and the server resolves it by those indices. Same discipline as
 * WildernessZones mirroring the server PvpZones.
 *
 * EVERY TeleportRegistry change ships with a sync of this file + a client deploy — the drift
 * after the boss-roster purge left the whole Bosses tab dead and the Mini-Games rows firing the
 * wrong destinations (the "portal teleports are broken" player reports).
 *
 * Since 2026-09-03 the click also carries the row's display name and the server resolves by
 * name before index (TeleportClickPlugin), so a stale client build lands on the boss it shows
 * rather than the row now at that index. Names must therefore match the server's displayName
 * exactly; keeping the ORDER in sync is still required for the greyed "Soon" rows to line up.
 *
 * The `icon` is a representative OSRS item id drawn via ItemManager.getImage (purely cosmetic;
 * an unknown id just renders blank).
 */
package net.runelite.client.plugins.lofteleports;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

final class LofTeleportsData
{
	// danger colours
	static final Color SAFE = new Color(0, 192, 0);
	static final Color WILD = new Color(255, 64, 64);
	static final Color HOSTILE = new Color(255, 128, 0);
	static final Color SOON = new Color(127, 127, 127);

	static final class Dest
	{
		final String name;
		final String danger;
		final Color colour;
		final boolean built;
		final int icon;

		Dest(String name, String danger, Color colour, boolean built, int icon)
		{
			this.name = name;
			this.danger = danger;
			this.colour = colour;
			this.built = built;
			this.icon = icon;
		}
	}

	static final class Category
	{
		final String name;
		final List<Dest> dests;

		Category(String name, List<Dest> dests)
		{
			this.name = name;
			this.dests = dests;
		}
	}

	static final List<Category> CATEGORIES = new ArrayList<>();

	private static Dest b(String n, String d, Color c, int icon) { return new Dest(n, d, c, true, icon); }
	private static Dest s(String n, String d, Color c, int icon) { return new Dest(n, d, c, false, icon); }

	private static void cat(String name, Dest... dests)
	{
		List<Dest> list = new ArrayList<>();
		for (Dest d : dests) list.add(d);
		CATEGORIES.add(new Category(name, list));
	}

	static
	{
		cat("Basics",
			b("Home (Lumbridge)", "Safe Zone", SAFE, 8008),     // Lumbridge teleport tab
			b("Market / Shops", "Safe Zone", SAFE, 995),        // coins
			b("Prayer Altar", "Safe Zone", SAFE, 2434),         // prayer potion
			s("Gambling (Flower Poker)", "Soon", SOON, 1038),   // red partyhat
			s("Dice Zone", "Soon", SOON, 995),
			s("Blackjack", "Soon", SOON, 6408),                 // blackjack
			s("Party Zone", "Soon", SOON, 1038));

		// Mirrors TeleportRegistry SKILLING: the seven production skills consolidated into the
		// single Mire hub row (UX review), then the skills with a distinct destination.
		cat("Skilling",
			b("The Mire — Skilling Grounds", "Safe Zone", SAFE, 5341), // rake (general skilling hub)
			b("Mining", "Safe Zone", SAFE, 1275),               // rune pickaxe
			b("Smithing", "Safe Zone", SAFE, 2347),             // hammer
			b("Construction", "Safe Zone", SAFE, 8794),         // saw
			b("Hunter", "Safe Zone", SAFE, 10010),              // butterfly net
			b("Agility", "Safe Zone", SAFE, 3105),              // climbing boots
			b("Herblore", "Safe Zone", SAFE, 233),              // pestle and mortar
			b("Fletching", "Safe Zone", SAFE, 946),             // knife
			b("Thieving", "Safe Zone", SAFE, 1523));            // lockpick

		cat("The War",
			b("Varrock Raid", "Hostile", HOSTILE, 1333),        // rune scimitar
			b("North Frontier", "Wild Lvl 5", WILD, 1325),      // iron scimitar
			b("Goblin Warren", "Hostile", HOSTILE, 288),        // goblin mail
			b("Recruit Trials", "Safe Zone", SAFE, 1277),       // bronze sword
			s("Active Campaign", "Soon", SOON, 1201));          // rune kiteshield

		// Mirrors TeleportRegistry BOSSES row-for-row (32 rows): the hand-built roster was purged;
		// bosses come back one at a time as Kronos ports and flip to b(...) here as they land.
		cat("Bosses",
			b("Corp Beast (Event)", "Hostile", HOSTILE, 13734), // spirit shield
			s("World Boss", "Soon", SOON, 11864),               // slayer helmet
			b("King Black Dragon", "Hostile", HOSTILE, 1149),   // dragon med helm (lair-boss package port)
			s("Corporeal Beast", "Soon", SOON, 13734),          // spirit shield
			b("Zulrah", "Hostile", HOSTILE, 12934),             // zulrah's scales
			b("Barrows", "Safe Zone", SAFE, 4716),              // dharok's helm (crypt run port)
			b("Giant Mole", "Hostile", HOSTILE, 7416),          // mole claw (lair-boss package port)
			b("Kalphite Queen", "Hostile", HOSTILE, 3140),      // dragon chainbody
			b("Dagannoth Kings", "Hostile", HOSTILE, 6737),     // berserker ring
			b("Vorkath", "Hostile", HOSTILE, 21634),            // vorkath's head
			b("Alchemical Hydra", "Hostile", HOSTILE, 22988),   // hydra leather
			b("General Graardor", "Hostile", HOSTILE, 11832),   // bandos chestplate (GWD package port)
			b("K'ril Tsutsaroth", "Hostile", HOSTILE, 11806),   // zamorak godsword
			b("Kree'arra", "Hostile", HOSTILE, 11826),          // armadyl helmet
			b("Commander Zilyana", "Hostile", HOSTILE, 11808),  // saradomin godsword
			b("Callisto", "Wild Lvl 41", WILD, 12603),          // tyrannical ring (wilderness-boss package port)
			b("Vet'ion", "Wild Lvl 34", WILD, 12601),           // ring of the gods
			b("Venenatis", "Wild Lvl 28", WILD, 12605),         // treasonous ring
			b("Scorpia", "Wild Lvl 54", WILD, 11930),           // odium shard 3
			b("Chaos Elemental", "Wild Lvl 51", WILD, 11995),   // pet chaos elemental
			b("Chaos Fanatic", "Wild Lvl 41", WILD, 11928),     // odium shard 1
			b("Crazy Archaeologist", "Wild Lvl 23", WILD, 11990), // fedora
			b("Kraken", "Hostile", HOSTILE, 12004),             // kraken tentacle (slayer-boss package port)
			b("Cerberus", "Hostile", HOSTILE, 13231),           // primordial crystal
			b("Thermonuclear Smoke Devil", "Hostile", HOSTILE, 12002), // occult necklace
			b("Skotizo (Catacombs altar)", "Hostile", HOSTILE, 21275), // dark claw
			b("Demonic Gorillas", "Hostile", HOSTILE, 19529),   // zenyte shard
			s("Theatre of Blood", "Soon", SOON, 22326),         // justiciar faceguard
			s("Chambers of Xeric", "Soon", SOON, 20997),        // twisted bow
			s("Revenant Caves", "Soon", SOON, 22557),           // amulet of avarice
			b("Zemouregal (Fallen Palace)", "Hostile", HOSTILE, 30320),      // arrav's axe (story boss, Arrav hub)
			b("The Convergence (Deep Senntisten)", "Hostile", HOSTILE, 2373)); // relic of old Varrock (story boss, Arrav hub)

		cat("Wilderness",
			b("Outlaw Camp", "Wild Lvl 5", WILD, 1333),         // rune scimitar
			b("Marauder Grounds", "Wild Lvl 12", WILD, 1215),   // dragon dagger
			b("Raider Fields", "Wild Lvl 20", WILD, 4153),      // granite maul
			b("Warlord's Approach", "Wild Lvl 30", WILD, 4151), // abyssal whip
			b("Wilderness PKers", "Wild Lvl 40", WILD, 11802),  // armadyl godsword
			b("Deep Wilderness PKers", "Wild Lvl 55", WILD, 13652), // dragon claws
			s("Fun-PK Zone", "Soon", SOON, 4587),               // dragon scimitar
			s("Risk Zone", "Soon", SOON, 995),                  // coins
			s("Edge PvP (Brid Zone)", "Soon", SOON, 1333),      // rune scimitar
			s("Camelot PvP", "Soon", SOON, 1319),               // rune 2h
			s("F2P Zone", "Soon", SOON, 1303),                  // rune longsword
			s("Mage Bank", "Soon", SOON, 6914),                 // master wand
			s("Ferox Enclave", "Soon", SOON, 6685));            // saradomin brew

		cat("Slayer",
			b("Slayer Master", "Safe Zone", SAFE, 4155),        // enchanted gem
			s("Slayer Cave", "Soon", SOON, 8901),               // black mask
			s("Resource Contracts", "Soon", SOON, 995));

		cat("Mini-Games",
			b("Wizard Tower", "Safe Zone", SAFE, 579),          // blue wizard hat
			b("Fight Cave", "Safe Zone", SAFE, 6570),           // fire cape
			b("Moons of Peril", "Hostile", HOSTILE, 29028),     // blood moon helm (Neypotzli)
			b("Senntisten Expedition", "Hostile", HOSTILE, 2373), // relic of old Varrock (the Digsite winch)
			b("Pest Control", "Safe Zone", SAFE, 8839),         // void knight top (the Outpost landers)
			b("Wintertodt", "Safe Zone", SAFE, 20720),          // bruma torch (the Doors of Dinh)
			b("Guardians of the Rift", "Safe Zone", SAFE, 26792), // abyssal pearls (Temple of the Eye lobby)
			b("Blast Furnace", "Safe Zone", SAFE, 2363),        // runite bar (Keldagrim furnace room)
			s("The Inferno", "Soon", SOON, 21295),              // infernal cape
			s("Castle Wars", "Soon", SOON, 4037),               // castle wars decor
			s("Last Man Standing", "Soon", SOON, 11941),        // looting bag
			s("Duel Arena", "Soon", SOON, 2552));               // ring of dueling

		cat("Events",
			s("HP Event", "Soon", SOON, 13441),                 // anglerfish
			s("Automatic Tournament", "Soon", SOON, 1333),      // rune scimitar
			s("Bloodlust", "Soon", SOON, 565),                  // blood rune
			s("Treasure Hunt", "Soon", SOON, 405),              // casket
			s("Clan Warfare", "Soon", SOON, 4039),              // saradomin banner
			s("Vote Boss", "Soon", SOON, 995));

		cat("Donator",
			s("Donator Zone", "Soon", SOON, 13190),             // old school bond
			s("Donator Dungeon", "Soon", SOON, 13190),
			s("Royal PvM Zone", "Soon", SOON, 13652),           // dragon claws
			s("Royal Skilling Zone", "Soon", SOON, 11920),      // dragon pickaxe
			s("Divine Donator", "Soon", SOON, 13190),
			s("Divine Monster Dungeon", "Soon", SOON, 13576),   // dragon warhammer
			s("Divine Skilling Zone", "Soon", SOON, 23673),     // crystal axe
			s("Divine Slayer Cave", "Soon", SOON, 11865));      // slayer helmet (i)
	}

	private LofTeleportsData()
	{
	}
}

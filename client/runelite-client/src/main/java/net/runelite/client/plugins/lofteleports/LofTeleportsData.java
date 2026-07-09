/*
 * Kingdom of Lumbridge — teleport portal: client-side mirror of the server destination catalog.
 *
 * MUST stay in sync with the server's org.alter.plugins.content.teleport.TeleportRegistry +
 * TeleportCategory (same category order, same per-category row order) — the overlay sends
 * "::tp <catIndex> <rowIndex>" and the server resolves it by those indices. Same discipline as
 * WildernessZones mirroring the server PvpZones.
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
			b("Home (Lumbridge)", "Safe Zone", SAFE, 8008),   // Lumbridge teleport tab
			b("Market / Shops", "Safe Zone", SAFE, 995),       // coins
			b("Prayer Altar", "Safe Zone", SAFE, 2434),        // prayer potion
			s("Gambling", "Soon", SOON, 1038),                 // red partyhat
			s("Dice Zone", "Soon", SOON, 995),
			s("Blackjack", "Soon", SOON, 6408),                // blackjack
			s("Party Zone", "Soon", SOON, 1038));

		cat("Skilling",
			b("Mining", "Safe Zone", SAFE, 1275),              // rune pickaxe
			b("Smithing", "Safe Zone", SAFE, 2347),            // hammer
			b("Woodcutting", "Safe Zone", SAFE, 1359),         // rune axe
			b("Firemaking", "Safe Zone", SAFE, 590),           // tinderbox
			b("Fishing", "Safe Zone", SAFE, 307),              // fishing rod
			b("Cooking", "Safe Zone", SAFE, 315),              // shrimps
			b("Crafting", "Safe Zone", SAFE, 1755),            // chisel
			b("Construction", "Safe Zone", SAFE, 8794),        // saw
			b("Runecraft", "Safe Zone", SAFE, 1438),           // air talisman
			b("Farming", "Safe Zone", SAFE, 952),              // spade
			b("Hunter", "Safe Zone", SAFE, 10010),             // butterfly net
			b("Agility", "Safe Zone", SAFE, 3105),             // climbing boots
			b("Herblore", "Safe Zone", SAFE, 233),             // pestle and mortar
			b("Fletching", "Safe Zone", SAFE, 946),            // knife
			b("Thieving", "Safe Zone", SAFE, 1523));           // lockpick

		cat("The War",
			b("Varrock Raid", "Hostile", HOSTILE, 1333),       // rune scimitar
			b("North Frontier", "Wild Lvl 5", WILD, 1325),     // iron scimitar
			b("Goblin Warren", "Hostile", HOSTILE, 288),       // goblin mail
			b("Recruit Trials", "Safe Zone", SAFE, 1277),      // bronze sword
			s("Active Campaign", "Soon", SOON, 1201));         // rune kiteshield

		cat("Bosses",
			b("King Black Dragon", "Hostile", HOSTILE, 1149),  // dragon med helm
			b("Ice Dragon", "Wild Lvl 15", WILD, 1751),        // blue dragonhide
			b("Rat King", "Wild Lvl 8", WILD, 1985),           // cheese
			s("World Boss", "Soon", SOON, 11864),              // slayer helmet
			s("Zulrah", "Soon", SOON, 12934),                  // zulrah's scales
			s("Barrows", "Soon", SOON, 4716),                  // dharok's helm
			s("Kraken", "Soon", SOON, 12004),                  // kraken tentacle
			s("Corporeal Beast", "Soon", SOON, 13734),         // spirit shield
			s("Nex", "Soon", SOON, 11791),                     // staff of the dead
			s("Callisto", "Soon", SOON, 12603),                // tyrannical ring
			s("Vet'ion", "Soon", SOON, 12601),                 // ring of the gods
			s("Venenatis", "Soon", SOON, 12605),               // treasonous ring
			s("Scorpia", "Soon", SOON, 12806),                 // malediction ward
			s("Chaos Elemental", "Soon", SOON, 7158),          // dragon 2h
			s("Chaos Fanatic", "Soon", SOON, 12808),           // odium shard
			s("Crazy Archaeologist", "Soon", SOON, 11924),     // fedora
			s("Demonic Gorillas", "Soon", SOON, 19481),        // heavy ballista
			s("Skotizo", "Soon", SOON, 19685),                 // dark totem
			s("Theatre of Blood", "Soon", SOON, 22326),        // justiciar faceguard
			s("Chambers of Xeric", "Soon", SOON, 20997),       // twisted bow
			s("Revenant Caves", "Soon", SOON, 22557));         // amulet of avarice

		cat("Wilderness",
			b("Outlaw Camp", "Wild Lvl 5", WILD, 1333),        // rune scimitar
			b("Marauder Grounds", "Wild Lvl 12", WILD, 1215),  // dragon dagger
			b("Raider Fields", "Wild Lvl 20", WILD, 4153),     // granite maul
			b("Warlord's Approach", "Wild Lvl 30", WILD, 4151),// abyssal whip
			b("Wilderness PKers", "Wild Lvl 40", WILD, 11802), // armadyl godsword
			b("Deep Wilderness PKers", "Wild Lvl 55", WILD, 13652), // dragon claws
			s("Fun-PK Zone", "Soon", SOON, 4587),              // dragon scimitar
			s("Risk Zone", "Soon", SOON, 995),                 // coins
			s("Edge PvP (Brid)", "Soon", SOON, 1333),          // rune scimitar
			s("Camelot PvP", "Soon", SOON, 1319),              // rune 2h
			s("F2P Zone", "Soon", SOON, 1303),                 // rune longsword
			s("Mage Bank", "Soon", SOON, 6914),                // master wand
			s("Ferox Enclave", "Soon", SOON, 6685));           // saradomin brew

		cat("Slayer",
			b("Slayer Master", "Safe Zone", SAFE, 4155),       // enchanted gem
			s("Slayer Cave", "Soon", SOON, 8901),              // black mask
			s("Resource Contracts", "Soon", SOON, 995));

		cat("Mini-Games",
			b("Fight Cave", "Safe Zone", SAFE, 6570),          // fire cape
			s("Castle Wars", "Soon", SOON, 4037),              // castle wars decor
			s("Last Man Standing", "Soon", SOON, 11941),       // looting bag
			s("Duel Arena", "Soon", SOON, 2552));              // ring of dueling

		cat("Events",
			s("HP Event", "Soon", SOON, 13441),                // anglerfish
			s("Automatic Tournament", "Soon", SOON, 1333),     // rune scimitar
			s("Bloodlust", "Soon", SOON, 565),                 // blood rune
			s("Treasure Hunt", "Soon", SOON, 405),             // casket
			s("Clan Warfare", "Soon", SOON, 4039),             // saradomin banner
			s("Vote Boss", "Soon", SOON, 995));

		cat("Donator",
			s("Donator Zone", "Soon", SOON, 13190),            // old school bond
			s("Donator Dungeon", "Soon", SOON, 13190),
			s("Royal PvM Zone", "Soon", SOON, 13652),          // dragon claws
			s("Royal Skilling Zone", "Soon", SOON, 11920),     // dragon pickaxe
			s("Divine Donator", "Soon", SOON, 13190),
			s("Divine Monster Dungeon", "Soon", SOON, 13576),  // dragon warhammer
			s("Divine Skilling Zone", "Soon", SOON, 23673),    // crystal axe
			s("Divine Slayer Cave", "Soon", SOON, 11865));     // slayer helmet (i)
	}

	private LofTeleportsData()
	{
	}
}

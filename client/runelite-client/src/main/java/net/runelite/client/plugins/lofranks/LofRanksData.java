/*
 * Fall of Varrock — Feudal Ranks window (static rank table).
 *
 * Mirror of the server's Title enum (Alter war/Title.kt): names, coin costs, name colours and
 * the unlock lines shown on the progression screen. Keep the ORDER identical to the enum —
 * the varp payload and "::rank buy <ordinal>" both speak in Title ordinals.
 */
package net.runelite.client.plugins.lofranks;

import java.awt.Color;

final class LofRanksData
{
	static final class Rank
	{
		final String name;
		final int cost;
		final String costShort;
		final Color color;
		final String perks;
		final String[] unlocks;

		Rank(String name, int cost, String costShort, Color color, String perks, String... unlocks)
		{
			this.name = name;
			this.cost = cost;
			this.costShort = costShort;
			this.color = color;
			this.perks = perks;
			this.unlocks = unlocks;
		}
	}

	/** Ordinal-indexed, PEASANT(0) … KING(7) — must match the server Title enum. */
	static final Rank[] RANKS = {
		new Rank("PEASANT", 0, "FREE", null, "bronze & iron armour",
			"Bronze & iron armour"),
		new Rank("COMMONER", 10_000, "10K", null, "steel armour",
			"Steel armour"),
		new Rank("SQUIRE", 50_000, "50K", new Color(0x9acd32), "black armour · coloured name",
			"Black armour", "Coloured name"),
		new Rank("SOLDIER", 150_000, "150K", new Color(0x1e90ff), "mithril & adamant",
			"Mithril & adamant armour"),
		new Rank("KNIGHT", 500_000, "500K", new Color(0xc0c0c0), "rune armour · 1 companion",
			"Rune armour", "+1 Companion soldier", "Silver name"),
		new Rank("LORD", 2_000_000, "2M", new Color(0xa020f0), "ALL armour · troops & bosses",
			"ALL armour (dragon+)", "+1 Companion (2 total)", "Field troops · summon bosses"),
		new Rank("MINISTER", 10_000_000, "10M", new Color(0xdc143c), "war campaigns · 3 companions",
			"Launch war campaigns", "+1 Companion (3 total)"),
		new Rank("KING", 50_000_000, "50M", new Color(0xffd700), "conquests · lead the realm",
			"Launch conquests", "Lead the realm"),
	};

	private LofRanksData()
	{
	}
}

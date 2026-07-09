/*
 * Kingdom of Lumbridge — PvP zone geometry, MIRRORED from the server.
 *
 * This is a hand-kept copy of org.alter.plugins.content.combat.PvpZones (server module
 * game-plugins). The client has no live feed of the zone boxes, so they are duplicated
 * here. **If PvpZones.kt changes, change this too** (same pattern as the map-dump tool
 * mirroring CityFrontiers). Coordinates are world (x = east, y = north); a "z" in the
 * server's Tile(x, z, height) is this y.
 */
package net.runelite.client.plugins.wildernesslines;

import net.runelite.api.coords.WorldPoint;

final class WildernessZones
{
	private WildernessZones()
	{
	}

	private static final int TILES_PER_LEVEL = 8;
	private static final int MAX_WILD_LEVEL = 56;
	private static final int WILD_SOUTH_EDGE_Y = 3300;

	// {x0, y0, x1, y1} inclusive rectangles.
	private static final int[][] WILDERNESS = {
		{2944, 3300, 3450, 3968}, // classic wild + southward extension through Misthalin
		{3245, 3214, 3267, 3256}, // east bank of the Lum (Lumbridge <-> Al Kharid) PK pocket
	};

	private static final int[][] SINGLE = {
		{3100, 3350, 3320, 3550}, // shallow core of the main wild
		{3245, 3214, 3267, 3256}, // east-Lum PK pocket is single combat
	};

	private static final int[][] SAFE_INSIDE_RED = {
		{3140, 3470, 3185, 3515}, // Grand Exchange
		{3178, 3432, 3196, 3453}, // Varrock west bank
		{3250, 3416, 3257, 3424}, // Varrock east bank
		{3091, 3488, 3098, 3499}, // Edgeville bank
	};

	private static boolean inAny(int[][] boxes, int x, int y)
	{
		for (int[] b : boxes)
		{
			if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3])
			{
				return true;
			}
		}
		return false;
	}

	/** True PvP-enabled wilderness tile (inside red, not a safe carve-out). */
	static boolean isWilderness(int x, int y)
	{
		return inAny(WILDERNESS, x, y) && !inAny(SAFE_INSIDE_RED, x, y);
	}

	static boolean isSingle(int x, int y)
	{
		return isWilderness(x, y) && inAny(SINGLE, x, y);
	}

	static boolean isMulti(int x, int y)
	{
		return isWilderness(x, y) && !inAny(SINGLE, x, y);
	}

	/** Wilderness level by depth pushed north of the safe edge (0 if not in the wild). */
	static int level(int x, int y)
	{
		if (!isWilderness(x, y))
		{
			return 0;
		}
		final int depth = y - WILD_SOUTH_EDGE_Y;
		if (depth < 0)
		{
			return MAX_WILD_LEVEL; // isolated PK pockets south of the main wild = open PvP
		}
		final int lvl = depth / TILES_PER_LEVEL + 1;
		return Math.max(1, Math.min(MAX_WILD_LEVEL, lvl));
	}

	static boolean isWilderness(WorldPoint p)
	{
		return isWilderness(p.getX(), p.getY());
	}

	/** Banner subtitle: "Level N" in the main wild, or "PK Zone" for the isolated southern pockets. */
	static String subtitle(int x, int y)
	{
		if (!isWilderness(x, y))
		{
			return "";
		}
		if (y < WILD_SOUTH_EDGE_Y)
		{
			return "PK Zone";
		}
		return "Wilderness Lvl " + level(x, y);
	}
}

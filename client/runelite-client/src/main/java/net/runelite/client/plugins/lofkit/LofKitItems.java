/*
 * Fall of Varrock — Kit editor item classification.
 *
 * The kit editor's smart defaults (left-click equips gear but deposits food) and its bank-browser
 * category tabs need to know what KIND of thing an item is. The client has no item metadata service,
 * but the cache's inventory actions are a reliable tell: wearable gear carries Wear/Wield/Equip,
 * food carries Eat, potions carry Drink. CLIENT THREAD ONLY — ItemComposition lookups go through
 * the client's definition cache.
 */
package net.runelite.client.plugins.lofkit;

import net.runelite.api.ItemComposition;

final class LofKitItems
{
	// Bank-browser category tabs, in rail order.
	static final int CAT_ALL = 0;
	static final int CAT_GEAR = 1;
	static final int CAT_FOOD = 2;
	static final int CAT_POTION = 3;
	static final int CAT_OTHER = 4;

	private LofKitItems()
	{
	}

	/** Weapons and armour — anything with a Wear/Wield/Equip inventory action. */
	static boolean equipable(ItemComposition c)
	{
		return hasAction(c, "Wear") || hasAction(c, "Wield") || hasAction(c, "Equip");
	}

	static int category(ItemComposition c)
	{
		if (equipable(c))
		{
			return CAT_GEAR;
		}
		if (hasAction(c, "Eat"))
		{
			return CAT_FOOD;
		}
		if (hasAction(c, "Drink"))
		{
			return CAT_POTION;
		}
		return CAT_OTHER;
	}

	private static boolean hasAction(ItemComposition c, String action)
	{
		final String[] actions = c.getInventoryActions();
		if (actions == null)
		{
			return false;
		}
		for (String a : actions)
		{
			if (action.equalsIgnoreCase(a))
			{
				return true;
			}
		}
		return false;
	}
}

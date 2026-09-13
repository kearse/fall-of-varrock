/*
 * Fall of Varrock — the stock quest tab, showing only OUR quests.
 *
 * The OSRS quest tab (interface 399) is rendered client-side from the cache's quest DBTable. The
 * server can't add rows to it, so Fall of Varrock RELABELS a handful of existing OSRS quest rows
 * to its own quests (Alter: QuestTablePatch.PLAN — Cook's Assistant becomes "The Last Free City",
 * Doric's Quest becomes "War-Prep I - Magic", …) and drives each reused row's progress varp so the
 * stock tab colours them red / yellow / green for free.
 *
 * What the cache edit CAN'T do cleanly is hide the ~180 other OSRS quests: the rev-228 quest-list
 * script enumerates rows in ways a pruned master index doesn't reliably control (the live tab
 * ended up listing two rows). This class does the hiding where it belongs — in the client we own:
 * the list-builder script calls RuneLite's QuestFilter (script 3238) once per row, which raises
 * the "questFilter" callback with the row's DBROW id on the int stack; we answer "hide" for every
 * row that isn't one of ours. The same mechanism powers the stock Quest List search box.
 *
 * Clicking a row is also handled here: the stock click sends the server only the row's list
 * position, which no longer maps cleanly to a quest once rows are filtered client-side — so the
 * click is consumed and the Quest Journal window is opened directly on the clicked quest, which
 * we resolve from the row's cache name recorded during the filter pass.
 */
package net.runelite.client.plugins.lofquests;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.dbtable.DBTableID;
import net.runelite.client.util.Text;

final class LofQuestTab
{
	/** The callback name RuneLite's QuestFilter script (3238) raises once per quest row. */
	static final String FILTER_CALLBACK = "questFilter";

	/**
	 * The reused OSRS quest rows (DBROW ids) that carry Fall of Varrock quests → the quest each
	 * shows as. MUST mirror the server's QuestTablePatch.PLAN (docs/quest-tab-handoff.md §0): add
	 * a line here whenever a quest gets a tab row there, or the new row stays hidden.
	 */
	private static final Map<Integer, LofQuest> ROWS;
	static
	{
		final Map<Integer, LofQuest> m = new HashMap<>();
		m.put(17, LofQuest.LAST_FREE_CITY);       // Cook's Assistant
		m.put(30, LofQuest.WARPREP_MAGIC);        // Doric's Quest
		m.put(120, LofQuest.ROGUE_HUNTING_I);     // The Restless Ghost
		m.put(83, LofQuest.ROGUE_HUNTING_II);     // The Knight's Sword
		m.put(76, LofQuest.WARPREP_RANGED);       // Imp Catcher
		m.put(131, LofQuest.WARPREP_SURVIVAL);    // Sheep Shearer
		m.put(161, LofQuest.KING_OF_LUMBRIDGE);   // Witch's Potion
		m.put(44, LofQuest.THE_NORTH);            // Ernest the Chicken
		m.put(121, LofQuest.FIRST_RECLAMATION);   // Romeo & Juliet
		m.put(125, LofQuest.A_KINGDOM_ALONE);     // Rune Mysteries
		m.put(10, LofQuest.BREACH);               // Black Knights' Fortress
		m.put(112, LofQuest.SECURE);              // Prince Ali Rescue
		m.put(155, LofQuest.UNDERSTAND);          // Vampyre Slayer
		m.put(108, LofQuest.SUSTAIN);             // Pirate's Treasure
		m.put(118, LofQuest.AT_THE_WHITE_WALL);   // Recruitment Drive
		m.put(23, LofQuest.A_MATTER_OF_TROLLS);   // Death Plateau
		m.put(35, LofQuest.GUNS_OF_ASGARNIA);     // Dwarf Cannon
		m.put(156, LofQuest.OLD_WOUNDS);          // Wanted!
		m.put(150, LofQuest.FIRST_MARCH);         // Tree Gnome Village
		ROWS = Collections.unmodifiableMap(m);
	}

	/** Row name as the cache spells it (after relabel) → quest, learned during the filter pass so a
	 *  click on a row can be resolved exactly, whatever punctuation the cache row uses. */
	private final Map<String, LofQuest> namesSeen = new HashMap<>();

	/** Rows answered during the current list build — lets the plugin notice a build where the
	 *  filter script never called back (a script-override mismatch), instead of failing silently. */
	private int rowsFiltered;

	static boolean isOurRow(int dbrow)
	{
		return ROWS.containsKey(dbrow);
	}

	static LofQuest questForRow(int dbrow)
	{
		return ROWS.get(dbrow);
	}

	/**
	 * Answer one "questFilter" callback. Stack layout (see QuestFilter.rs2asm / QuestListPlugin):
	 * top = the quest DBROW id, below it the result slot (-1 = let the stock filters decide,
	 * 0 = show, 1 = hide). Only ever forces HIDE for foreign rows — our own rows are left to the
	 * stock filters (the "hide completed" toggles and the search box keep working).
	 */
	void filter(Client client)
	{
		final int[] intStack = client.getIntStack();
		final int size = client.getIntStackSize();
		if (size < 2)
		{
			return;
		}
		final int row = intStack[size - 1];
		rowsFiltered++;
		final LofQuest quest = ROWS.get(row);
		if (quest == null)
		{
			intStack[size - 2] = 1;
			return;
		}
		try
		{
			final Object[] name = client.getDBTableField(row, DBTableID.Quest.NAME, 0);
			if (name != null && name.length > 0 && name[0] instanceof String)
			{
				namesSeen.put(key((String) name[0]), quest);
			}
		}
		catch (RuntimeException ignored)
		{
			// a row without a name column — the click falls back to the journal's own quest names
		}
	}

	/** Start of a list build: reset the per-build counter. Returns how many rows the previous
	 *  build filtered (0 = the filter script never ran). */
	int beginBuild()
	{
		final int previous = rowsFiltered;
		rowsFiltered = 0;
		return previous;
	}

	int rowsFiltered()
	{
		return rowsFiltered;
	}

	/** Resolve a clicked row's display text to one of our quests: the cache's own relabelled name
	 *  first, then the journal's name with dash/space punctuation normalised. Null if it's not ours. */
	LofQuest questForRowText(String text)
	{
		if (text == null)
		{
			return null;
		}
		final String k = key(text);
		if (k.isEmpty())
		{
			return null;
		}
		final LofQuest seen = namesSeen.get(k);
		if (seen != null)
		{
			return seen;
		}
		for (LofQuest q : ROWS.values())
		{
			if (key(q.getQuestName()).equals(k))
			{
				return q;
			}
		}
		return null;
	}

	/** Tag-stripped, dash-normalised, whitespace-collapsed lower-case form of a quest name. */
	static String key(String s)
	{
		return Text.removeTags(s)
			.replace('—', '-').replace('–', '-')
			.replace(' ', ' ')
			.replaceAll("\\s+", " ")
			.trim()
			.toLowerCase(Locale.ROOT);
	}
}

/*
 * Fall of Varrock — quest journal varp contract.
 *
 * The server's QuestJournalPlugin publishes each custom quest chain's live state into these varps
 * (see Alter: org.alter.plugins.content.quests.QuestJournal — the layouts must match exactly).
 */
package net.runelite.client.plugins.lofquests;

import net.runelite.api.Client;

final class LofQuestVarps
{
	/** Packed "The Last Free City" (the Recruit Trials chain) state: bits 0-5 step ordinal in STORY
	 *  order (0 alarm … 10 return, 11 debrief, 12 done — the server remaps its enum for the wire),
	 *  bits 6-9 goblins defeated, bit 10 contract taken. */
	static final int RECRUIT = 4610;

	/** War-Prep chain step ordinal (0 = not started … 6 = done). */
	static final int WARPREP = 4611;

	/** 1 while the player has muted the server's guidance arrows (free play), else 0. */
	static final int GUIDE_MUTED = 4612;

	/** Packed rogue-quests state (Rogue Hunting I + II share one server chain): bits 0-5 step
	 *  ordinal, bits 6-11 rogues felled on the HUNT step. */
	static final int ROGUE_PROBLEM = 4617;

	/** Packed Rogue Knight ladder state: bits 0-7 knights beaten, bits 8-15 active hunt index + 1,
	 *  bits 16-19 rogue camps cleared, bits 20-23 total knight-hosting camps (the last two feed the
	 *  rogue quest window's left-side dial). */
	static final int KNIGHTS = 4682;

	/** "Open the Quest Journal window" pulse: value = focused quest's chain index + 1 (0 = no signal).
	 *  Must match server QuestBook.OPEN_VARP. Was 4645 — inside the kit editor's 4640-4679 slot
	 *  block, so publishing a kit with a filled chest slot popped the quest journal. */
	static final int QUEST_BOOK_OPEN = 4683;

	/** Packed "War-Prep II — Ranged" state: bits 0-5 step ordinal, bits 6-11 enemies felled with a ranged weapon on FIELD. */
	static final int RANGED = 4624;

	// --- framework quests (server QuestDefinition.journalVarp, generic packing via QuestEngine.publish):
	//     bits 0-7 = current step index + 1 (0 = unstarted / finished), bits 8-19 = the step's progress
	//     counter, bits 20-21 = state (0 none, 1 in progress, 2 complete). Reserved block 4686-4699
	//     (docs/overlay-design-system.md §8) — one id per quest, in chain order.

	/** Main Story Quest 3, "The North". */
	static final int NORTH = 4686;

	/** Main Story Quest 4, "First Reclamation". */
	static final int FIRST_RECLAMATION = 4687;

	/** Main Story Quest 5, "A Kingdom Alone". */
	static final int A_KINGDOM_ALONE = 4688;

	/** The four strategic objectives of the regional campaign phase (opened by A Kingdom Alone). */
	static final int BREACH = 4689;
	static final int SECURE = 4690;
	static final int UNDERSTAND = 4691;
	static final int SUSTAIN = 4692;

	/** Asgarnia — BREACH, quest 2: "A Matter of Trolls" (4693 is At the White Wall's, in its own PR). */
	static final int A_MATTER_OF_TROLLS = 4694;

	/** Generic-packing step ordinal: the current step index + 1, 0 when unstarted or finished. */
	static int genericStep(Client client, int varp)
	{
		return client.getVarpValue(varp) & 0xFF;
	}

	/** Generic-packing progress counter (kills etc.) of the current step. */
	static int genericProgress(Client client, int varp)
	{
		return (client.getVarpValue(varp) >> 8) & 0xFFF;
	}

	/** Generic-packing state: 0 none (locked / not begun), 1 in progress, 2 complete. */
	static int genericState(Client client, int varp)
	{
		return (client.getVarpValue(varp) >> 20) & 0x3;
	}

	/** "War-Prep III — Survival" step ordinal, bits 0-5. (Was 4643 — kit editor's block.) */
	static final int SURVIVAL = 4681;

	/** "King of Lumbridge" (endgame conquest) step ordinal, bits 0-5 (0 = not started / not yet King). */
	static final int CONQUEST = 4633;

	static int recruitStep(Client client)
	{
		return client.getVarpValue(RECRUIT) & 0x3F;
	}

	static int recruitGoblinKills(Client client)
	{
		return (client.getVarpValue(RECRUIT) >> 6) & 0xF;
	}

	static boolean recruitContractTaken(Client client)
	{
		return ((client.getVarpValue(RECRUIT) >> 10) & 0x1) == 1;
	}

	static int warprepStep(Client client)
	{
		return client.getVarpValue(WARPREP) & 0x3F;
	}

	static int rogueProblemStep(Client client)
	{
		return client.getVarpValue(ROGUE_PROBLEM) & 0x3F;
	}

	static int warprepRangedStep(Client client)
	{
		return client.getVarpValue(RANGED) & 0x3F;
	}

	static int warprepRangedKills(Client client)
	{
		return (client.getVarpValue(RANGED) >> 6) & 0x3F;
	}

	static int warprepSurvivalStep(Client client)
	{
		return client.getVarpValue(SURVIVAL) & 0x3F;
	}

	static int conquestStep(Client client)
	{
		return client.getVarpValue(CONQUEST) & 0x3F;
	}

	static int rogueProblemKills(Client client)
	{
		return (client.getVarpValue(ROGUE_PROBLEM) >> 6) & 0x3F;
	}

	static int knightsBeaten(Client client)
	{
		return client.getVarpValue(KNIGHTS) & 0xFF;
	}

	static int knightCampsCleared(Client client)
	{
		return (client.getVarpValue(KNIGHTS) >> 16) & 0xF;
	}

	static int knightCampsTotal(Client client)
	{
		return (client.getVarpValue(KNIGHTS) >> 20) & 0xF;
	}

	static int questBookOpen(Client client)
	{
		return client.getVarpValue(QUEST_BOOK_OPEN);
	}

	static boolean guideMuted(Client client)
	{
		return client.getVarpValue(GUIDE_MUTED) == 1;
	}

	private LofQuestVarps()
	{
	}
}

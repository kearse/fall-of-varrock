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
	/** Packed Recruit Trials state: bits 0-5 step ordinal, bits 6-9 goblin kills, bit 10 contract taken. */
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

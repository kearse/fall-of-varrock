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

	/** Packed "The Rogue Problem" state: bits 0-5 step ordinal, bits 6-11 rogues felled on the HUNT step. */
	static final int ROGUE_PROBLEM = 4617;

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

	static int conquestStep(Client client)
	{
		return client.getVarpValue(CONQUEST) & 0x3F;
	}

	static int rogueProblemKills(Client client)
	{
		return (client.getVarpValue(ROGUE_PROBLEM) >> 6) & 0x3F;
	}

	static boolean guideMuted(Client client)
	{
		return client.getVarpValue(GUIDE_MUTED) == 1;
	}

	private LofQuestVarps()
	{
	}
}

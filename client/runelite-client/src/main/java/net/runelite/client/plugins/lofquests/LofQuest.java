/*
 * Fall of Varrock — the custom quest registry.
 *
 * One entry per Fall of Varrock quest, mirroring the server-side chains step-for-step (the step
 * ordinals MUST match the server enums — RecruitTrials.Step and WarPrepChain.Step in Alter).
 * Entries with no varp are FUTURE teasers: they render dimmed in the journal so players can see
 * where the quest line is heading (the "what's ahead" view) before the content exists.
 *
 * To add a quest: give its server chain a varp in QuestJournal (Alter), add the entry here with
 * the same ordinals, its "why" blurb and its unlock list — the panel and overlays pick it up.
 */
package net.runelite.client.plugins.lofquests;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

@Getter
enum LofQuest
{
	RECRUIT_TRIALS(
		"Recruit Trials",
		"Lumbridge is at war and every citizen serves. The Trials are your enlistment: learn the "
			+ "three pillars of the war effort — fighting the goblin front, climbing the feudal "
			+ "ladder, and supplying the war machine — and leave a kitted citizen-soldier.",
		11, // DONE ordinal
		Arrays.asList(
			new LofQuestStep(0, "Speak to the Recruiting Sergeant", "By the Lumbridge gate.", new WorldPoint(3219, 3214, 0)),
			new LofQuestStep(1, "Clear the back woods", "Kill 5 goblins that slipped the defences.", new WorldPoint(3193, 3221, 0)),
			new LofQuestStep(2, "Report back to the Sergeant", new WorldPoint(3219, 3214, 0)),
			new LofQuestStep(3, "Buy your first rank from Duke Horacio", "He's in the market, by the Slayer Master.", new WorldPoint(3222, 3218, 0)),
			new LofQuestStep(4, "Complete Vannaka's war-contract", "Take the contract, then slay the castle rats.", new WorldPoint(3219, 3215, 0)),
			new LofQuestStep(5, "Report back to Vannaka", new WorldPoint(3219, 3215, 0)),
			new LofQuestStep(6, "Mine copper and tin in The Mire", "The skilling grounds south-east of the castle.", new WorldPoint(3237, 3189, 0)),
			new LofQuestStep(7, "Smelt a bronze bar", "At The Mire's furnace.", new WorldPoint(3237, 3192, 0)),
			new LofQuestStep(8, "Smith a bronze dagger", "At The Mire's anvil.", new WorldPoint(3238, 3196, 0)),
			new LofQuestStep(9, "Deliver the dagger to the Quartermaster", "The Supply Officer by the crypt in The Mire.", new WorldPoint(3248, 3193, 0)),
			new LofQuestStep(10, "Report back to Vannaka for your reward", new WorldPoint(3219, 3215, 0))
		),
		Arrays.asList(
			"Citizen-soldier status (the war's contracts open up)",
			"20,000 coins — enough for your first rank",
			"A full steel armour set, piece by piece",
			"50 War Effort and the Book of Commands",
			"The War-Prep chain (raid training)"
		)),

	WARPREP_MAGIC(
		"War-Prep I — Magic",
		"The front's mages will eat an unprepared soldier alive. Vannaka drills your Prayer to 37 "
			+ "for Protect from Magic, arms you in mystic gear, and sends you to clear the Wizard "
			+ "Tower with the Void Knight — the grimoire inside unlocks the old magics.",
		6, // DONE ordinal (RANK was inserted at 5 — see WarPrepChain.Step)
		Arrays.asList(
			new LofQuestStep(1, "Train Prayer to 37", "Use the dragon bones on the Lumbridge church altar.", new WorldPoint(3242, 3207, 0)),
			new LofQuestStep(2, "Return to Vannaka to be armed", "He kits you out for the tower.", new WorldPoint(3219, 3215, 0)),
			new LofQuestStep(3, "Clear the Wizard Tower", "Speak to the Void Knight at the bridge; take the grimoire from the Archmage.", new WorldPoint(3113, 3208, 0)),
			new LofQuestStep(4, "Return to Vannaka with word of the grimoire", new WorldPoint(3219, 3215, 0)),
			new LofQuestStep(5, "Buy your next rank from Duke Horacio", "Vannaka's purse covers it — heavier armour awaits.", new WorldPoint(3222, 3218, 0))
		),
		Arrays.asList(
			"Protect from Magic (Prayer 37)",
			"The Ancient, Lunar and Arceuus spellbooks (::spellbook)",
			"Mystic gear, runes and prayer potions",
			"A purse that covers your next feudal rank",
			"Access to the war's raids"
		)),

	THE_ROGUE_PROBLEM(
		"The Rogue Problem",
		"Lumbridge holds, but Fallen Varrock — the old capital — has fallen to rogues, muggers and "
			+ "highwaymen. The Recruiting Sergeant sends the new Squire into its lawless streets to "
			+ "thin the cutthroats and cut down one of their captains. Break them and the purse earns "
			+ "your Knighthood — your first companion, and the right to hunt the wilderness.",
		6, // DONE ordinal (RogueProblem.Step)
		// World anchors are best-effort Fallen Varrock tiles — TUNE against the live map.
		Arrays.asList(
			new LofQuestStep(1, "Speak to the Recruiting Sergeant", "He has harder work now you're a Squire — by the Lumbridge gate.", new WorldPoint(3219, 3214, 0)),
			new LofQuestStep(2, "Thin out the rogues of Fallen Varrock", "Cut down 30 cutthroats in the streets. Lawless PvP ground — risk little.", new WorldPoint(3212, 3428, 0)),
			new LofQuestStep(3, "Hunt down a district captain", "Prowling deep in the city — see the board with ::bounties.", new WorldPoint(3216, 3421, 0)),
			new LofQuestStep(4, "Return to the Recruiting Sergeant", "Report the captain's fall and claim your purse.", new WorldPoint(3219, 3214, 0)),
			new LofQuestStep(5, "Rise to Knight at Duke Horacio", "The Sergeant's purse covers the climb — Soldier, then Knight.", new WorldPoint(3222, 3218, 0))
		),
		Arrays.asList(
			"Knighthood — rune armour",
			"Your first companion (General Zo musters them)",
			"The wilderness / PK loop (start at the PK Training Arena)",
			"A purse that covers the climb to Knight"
		)),

	WARPREP_RANGED(
		"War-Prep II — Ranged",
		"The next drill in the War-Prep chain: mastering the bow for the front's skirmish lines.",
		Arrays.asList(
			"Ranged mastery for the war",
			"Marksman's kit"
		)),

	WARPREP_SURVIVAL(
		"War-Prep III — Survival",
		"Field survival training — staying alive when the front collapses around you.",
		Collections.singletonList(
			"Survivability perks for raids and sieges"
		)),

	KING_OF_LUMBRIDGE(
		"King of Lumbridge",
		"The top of the feudal ladder. A questline that ends with you ruling the city — commanding "
			+ "its armies, reshaping its laws, and marching on the other kingdoms.",
		Arrays.asList(
			"Rule Lumbridge",
			"Command the city's NPC armies",
			"City-vs-city conquest"
		));

	private final String questName;
	private final String why;
	/** Step ordinal that means "completed" on the server (-1 for FUTURE teaser entries). */
	private final int doneOrdinal;
	private final List<LofQuestStep> steps;
	private final List<String> unlocks;

	LofQuest(String questName, String why, int doneOrdinal, List<LofQuestStep> steps, List<String> unlocks)
	{
		this.questName = questName;
		this.why = why;
		this.doneOrdinal = doneOrdinal;
		this.steps = steps;
		this.unlocks = unlocks;
	}

	/** FUTURE teaser entry — no server chain behind it yet. */
	LofQuest(String questName, String why, List<String> unlocks)
	{
		this(questName, why, -1, Collections.emptyList(), unlocks);
	}

	boolean isFuture()
	{
		return doneOrdinal < 0;
	}

	/** The server chain's current step ordinal for this quest (0 for FUTURE entries). */
	int stepOrdinal(Client client)
	{
		switch (this)
		{
			case RECRUIT_TRIALS:
				return LofQuestVarps.recruitStep(client);
			case WARPREP_MAGIC:
				return LofQuestVarps.warprepStep(client);
			case THE_ROGUE_PROBLEM:
				return LofQuestVarps.rogueProblemStep(client);
			default:
				return 0;
		}
	}

	LofQuestState state(Client client)
	{
		if (isFuture())
		{
			return LofQuestState.FUTURE;
		}
		int ord = stepOrdinal(client);
		switch (this)
		{
			case RECRUIT_TRIALS:
				// TALK (0) is handed to every fresh citizen — count it as not-yet-started.
				return ord >= doneOrdinal ? LofQuestState.FINISHED
					: ord == 0 ? LofQuestState.NOT_STARTED : LofQuestState.IN_PROGRESS;
			case WARPREP_MAGIC:
				// The chain auto-begins when the Recruit Trials finish; ordinal 0 = still locked.
				return ord >= doneOrdinal ? LofQuestState.FINISHED
					: ord == 0 ? LofQuestState.LOCKED : LofQuestState.IN_PROGRESS;
			case THE_ROGUE_PROBLEM:
				// Act II — locked until War-Prep I (Magic) finishes (its DONE ordinal is 6); after
				// that the chain auto-begins at step 1, so ordinal 0 still reads as locked.
				if (LofQuestVarps.warprepStep(client) < 6)
				{
					return LofQuestState.LOCKED;
				}
				return ord >= doneOrdinal ? LofQuestState.FINISHED
					: ord == 0 ? LofQuestState.LOCKED : LofQuestState.IN_PROGRESS;
			default:
				return LofQuestState.LOCKED;
		}
	}

	/** Short lock explanation for LOCKED entries (null otherwise). */
	String lockReason(Client client)
	{
		if (this == WARPREP_MAGIC && state(client) == LofQuestState.LOCKED)
		{
			return "Complete the Recruit Trials first.";
		}
		if (this == THE_ROGUE_PROBLEM && state(client) == LofQuestState.LOCKED)
		{
			return "Finish War-Prep I — Magic first.";
		}
		return null;
	}

	/** How many checklist steps are already behind the player. */
	int completedSteps(Client client)
	{
		int ord = stepOrdinal(client);
		int done = 0;
		for (LofQuestStep step : steps)
		{
			if (ord > step.getOrdinal())
			{
				done++;
			}
		}
		return done;
	}

	/** The active checklist step, or null when unstarted/locked/finished. */
	LofQuestStep currentStep(Client client)
	{
		int ord = stepOrdinal(client);
		for (LofQuestStep step : steps)
		{
			if (step.getOrdinal() == ord)
			{
				return step;
			}
		}
		return null;
	}

	/**
	 * Where the guidance arrow should point right now — usually the current step's anchor, but a
	 * step can redirect mid-flight (the war-contract step moves from Vannaka to the rats once the
	 * contract is taken).
	 */
	WorldPoint currentTarget(Client client)
	{
		LofQuestStep step = currentStep(client);
		if (step == null)
		{
			return null;
		}
		if (this == RECRUIT_TRIALS && step.getOrdinal() == 4 && LofQuestVarps.recruitContractTaken(client))
		{
			return new WorldPoint(3206, 3205, 0); // contract taken — the castle rats' corner
		}
		return step.getTarget();
	}

	/** Live progress suffix for a step row, e.g. " (3/5)" goblins or " (23/37)" Prayer. */
	String stepProgress(Client client, LofQuestStep step)
	{
		if (this == RECRUIT_TRIALS && step.getOrdinal() == 1 && stepOrdinal(client) == 1)
		{
			return " (" + LofQuestVarps.recruitGoblinKills(client) + "/5)";
		}
		if (this == WARPREP_MAGIC && step.getOrdinal() == 1 && stepOrdinal(client) == 1)
		{
			return " (" + client.getRealSkillLevel(Skill.PRAYER) + "/37)";
		}
		if (this == THE_ROGUE_PROBLEM && step.getOrdinal() == 2 && stepOrdinal(client) == 2)
		{
			return " (" + LofQuestVarps.rogueProblemKills(client) + "/30)";
		}
		return "";
	}
}

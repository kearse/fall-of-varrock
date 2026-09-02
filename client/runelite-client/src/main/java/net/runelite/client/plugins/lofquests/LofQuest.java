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
			new LofQuestStep(0, "Speak to the Recruiting Sergeant", "By the Lumbridge gate.", new WorldPoint(3217, 3220, 0)),
			new LofQuestStep(1, "Clear the back woods", "Kill 5 goblins that slipped the defences.", new WorldPoint(3193, 3221, 0)),
			new LofQuestStep(2, "Report back to the Sergeant", new WorldPoint(3217, 3220, 0)),
			new LofQuestStep(3, "Buy your first rank from Duke Horacio", "He's in the market, by the Slayer Master.", new WorldPoint(3220, 3211, 0)),
			new LofQuestStep(4, "Complete Vannaka's war-contract", "Take the contract, then slay the castle rats.", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(5, "Report back to Vannaka", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(6, "Mine copper and tin in The Mire", "The skilling grounds south-east of the castle.", new WorldPoint(3237, 3189, 0)),
			new LofQuestStep(7, "Smelt a bronze bar", "At The Mire's furnace.", new WorldPoint(3237, 3192, 0)),
			new LofQuestStep(8, "Smith a bronze dagger", "At The Mire's anvil.", new WorldPoint(3238, 3196, 0)),
			new LofQuestStep(9, "Deliver the dagger to the Quartermaster", "The Supply Officer by the crypt in The Mire.", new WorldPoint(3248, 3193, 0)),
			new LofQuestStep(10, "Report back to Vannaka for your reward", new WorldPoint(3222, 3212, 0))
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
			new LofQuestStep(2, "Return to Vannaka to be armed", "He kits you out for the tower.", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(3, "Clear the Wizard Tower", "Speak to the Void Knight at the bridge; take the grimoire from the Archmage.", new WorldPoint(3113, 3208, 0)),
			new LofQuestStep(4, "Return to Vannaka with word of the grimoire", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(5, "Buy your next rank from Duke Horacio", "Vannaka's purse covers it — heavier armour awaits.", new WorldPoint(3220, 3211, 0))
		),
		Arrays.asList(
			"Protect from Magic (Prayer 37)",
			"The Ancient, Lunar and Arceuus spellbooks (::spellbook)",
			"Mystic gear, runes and prayer potions",
			"A purse that covers your next feudal rank",
			"Access to the war's raids"
		)),

	ROGUE_HUNTING_I(
		"Rogue Hunting I",
		"Lumbridge holds, but when Varrock fell its rogues, muggers and highwaymen — led by "
			+ "deserters who style themselves Rogue Knights — scattered onto the roads west and "
			+ "into the ruins of the fallen city. The Recruiting Sergeant sets the new Squire on "
			+ "their rank and file: thin the cutthroats on the safe road camps west of Lumbridge, "
			+ "or in Fallen Varrock's wilderness streets for the bold. Clearing the hunt pays a "
			+ "soldier's purse — and opens the Rogue Knight ladder.",
		3, // complete once the hunt clears (RogueProblem.Step.KNIGHT ordinal)
		Arrays.asList(
			new LofQuestStep(1, "Speak to the Recruiting Sergeant", "He has harder work now you're a Squire — by the Lumbridge gate.", new WorldPoint(3217, 3220, 0)),
			// No fixed anchor: the server's hint arrow leads this step — to the nearest safe road
			// camp from afar, locking onto live rogues once they're in reach (like the knight hunt).
			new LofQuestStep(2, "Thin out the rogue rank and file", "Cut down 30 of the rogue family — kills count anywhere; the arrow leads to the nearest safe road camp and locks onto rogues in reach. Fallen Varrock is denser but it is the wilderness — only the bank pockets are safe.", null)
		),
		Arrays.asList(
			"A soldier's purse (150,000) — buys the Soldier rank at Duke Horacio",
			"The Rogue Knight ladder — Rogue Hunting II begins",
			"The Sergeant's rogue bounties (::rogues)"
		)),

	ROGUE_HUNTING_II(
		"Rogue Hunting II",
		"The rank and file are thinned — now for the deserters who lead them. The Sergeant assigns "
			+ "the Rogue Knights in order, weakest to strongest, camp by camp from the Lumbridge "
			+ "road to the deepest wilderness. Each knight guards the coin and gear that beat the "
			+ "next; their spoils earn your Knighthood along the way. The quest ends only when "
			+ "every camp is broken and the Rogue Commander falls. This is how you learn to PK.",
		6, // DONE ordinal (RogueProblem.Step)
		Arrays.asList(
			new LofQuestStep(3, "Kill your first assigned Rogue Knight", "Buy Soldier with your hunt purse first. The Sergeant's marker leads to the camp; ::knights tracks the ladder.", null),
			new LofQuestStep(4, "Return to the Recruiting Sergeant", "Report the knight's fall.", new WorldPoint(3217, 3220, 0)),
			// No fixed anchor: the ladder's own marker leads the climb, camp to camp.
			new LofQuestStep(5, "Break every camp on the ladder", "All 14 knights, weakest to strongest — the Commander last. Buy Soldier and Knight from Duke Horacio as the spoils come in; ::knights tracks the climb.", null)
		),
		Arrays.asList(
			"Knighthood — earned from the ladder's spoils: rune armour",
			"Your first companion (General Zo musters them)",
			"The wilderness / PK loop (start at the PK Training Arena)",
			"Every beaten knight stays farmable for its signature gear (::knights)",
			"The realm's PK schooling: switches, baits, spec combos — learned camp by camp",
			"War-Prep II — Ranged"
		)),

	WARPREP_RANGED(
		"War-Prep II — Ranged",
		"The front's skirmish lines are won with the bow. The rogues' ladder made a fighter of you — "
			+ "now Vannaka arms you with a marksman's kit and sends you to prove you can hold a "
			+ "line at distance. He pays a skirmish bounty; the Lordship itself you earn — knight "
			+ "farming, city raids, loot keys — and buy when your purse allows.",
		6, // DONE ordinal (WarPrepRanged.Step)
		// World anchors are best-effort — TUNE against the live map.
		Arrays.asList(
			new LofQuestStep(2, "Return to Vannaka for the marksman's kit", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(3, "Fell 20 enemies with a ranged weapon", "Bow, crossbow or thrown — Fallen Varrock's rogues will do.", new WorldPoint(3212, 3428, 0)),
			new LofQuestStep(4, "Report back to Vannaka", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(5, "Earn your Lordship at Duke Horacio", "The realm's loops pay the 2,000,000 — farm the Rogue Knights for kits and rares, raid the fallen cities' loot spots, hunt the wild for loot keys.", new WorldPoint(3220, 3211, 0))
		),
		Arrays.asList(
			"The rank of Lord — dragon armour",
			"Command of the knights (General Zo)",
			"War-Prep III — Survival (General Zo's next lesson)",
			"A 250,000 skirmish bounty — the Lordship is earned"
		)),

	WARPREP_SURVIVAL(
		"War-Prep III — Survival",
		"Field survival training — staying alive when the front collapses around you. General Zo toughens "
			+ "you up, kits you out, and sends you into the Fight Cave to prove you can endure. He pays "
			+ "a survival bounty; the Ministry itself you earn in command — marches, raids, the "
			+ "ladder's elite rares.",
		6, // DONE ordinal (WarPrepSurvival.Step)
		Arrays.asList(
			new LofQuestStep(1, "Raise your Hitpoints to 60", "Hitpoints climbs as you fight — toughen up.", new WorldPoint(3220, 3210, 0)),
			new LofQuestStep(2, "Return to General Zo for a survival kit", new WorldPoint(3220, 3210, 0)),
			new LofQuestStep(3, "Endure the Fight Cave to wave 6", "Enter with ::arena — manage your health and outlast it.", null),
			new LofQuestStep(4, "Report back to General Zo", new WorldPoint(3220, 3210, 0)),
			new LofQuestStep(5, "Earn your Ministry at Duke Horacio", "Command pays the 10,000,000 — lead marches and raids, farm the ladder's elite for their rares, hunt the deep wild.", new WorldPoint(3220, 3211, 0))
		),
		Arrays.asList(
			"The rank of Minister — within reach of the crown",
			"Survivability for raids and sieges",
			"The road to King and the endgame conquest",
			"A purse that covers your rank to Minister"
		)),

	KING_OF_LUMBRIDGE(
		"King of Lumbridge",
		"The top of the feudal ladder, and the endgame. Crowned King, you finally put the crown to "
			+ "use: stock the realm for war, muster a full army, and march on Fallen Varrock to retake "
			+ "the old capital. Win the conquest and the realm's armies march at your word.",
		4, // DONE ordinal (Conquest.Step)
		Arrays.asList(
			new LofQuestStep(1, "Stock the realm's war-stores", "Skill the Mire and hand supplies to a Quartermaster (::supply to check).", new WorldPoint(3248, 3193, 0)),
			new LofQuestStep(2, "Launch the conquest of Fallen Varrock", "Gather your war-chest, then command ::conquest varrock.", new WorldPoint(3231, 3219, 0)),
			new LofQuestStep(3, "Win the conquest", "Break Fallen Varrock's garrison — lead your army to victory.", new WorldPoint(3213, 3424, 0))
		),
		Arrays.asList(
			"Fallen Varrock retaken",
			"Command of the realm's armies (::conquest)",
			"A commander's spoils (::claim) and Prestige",
			"City-vs-city conquest"
		));

	/** npc.rat_2854 — the small rats around Lumbridge castle; the Recruit Trials' intro contract
	 *  target. They're tiny, scurry all over the courtyard and never show on the minimap, so the
	 *  quest highlights the rats themselves (see LofQuestsPlugin) rather than trusting the
	 *  fixed-tile arrow alone, which just lands on empty ground once they wander off it. */
	static final int CASTLE_RAT_ID = 2854;

	private static final int[] CASTLE_RATS = {CASTLE_RAT_ID};
	private static final int[] NO_NPCS = new int[0];

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

	/** The main quest chain in order — the real, built quests (FUTURE teasers excluded). The index
	 *  into this list is the "chain index" the server's QuestBook varp and the node track speak. */
	static final List<LofQuest> CHAIN;
	static
	{
		final java.util.List<LofQuest> c = new java.util.ArrayList<>();
		for (LofQuest q : values())
		{
			if (!q.isFuture())
			{
				c.add(q);
			}
		}
		CHAIN = Collections.unmodifiableList(c);
	}

	/** This quest's 0-based position in {@link #CHAIN}, or -1 if it's a FUTURE teaser. */
	int chainIndex()
	{
		return CHAIN.indexOf(this);
	}

	/** The chain quest at [i], or null if out of range. */
	static LofQuest byChainIndex(int i)
	{
		return i >= 0 && i < CHAIN.size() ? CHAIN.get(i) : null;
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
			case ROGUE_HUNTING_I:
			case ROGUE_HUNTING_II:
				// Both rogue quests window the same server chain (RogueProblem.Step).
				return LofQuestVarps.rogueProblemStep(client);
			case WARPREP_RANGED:
				return LofQuestVarps.warprepRangedStep(client);
			case WARPREP_SURVIVAL:
				return LofQuestVarps.warprepSurvivalStep(client);
			case KING_OF_LUMBRIDGE:
				return LofQuestVarps.conquestStep(client);
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
			case ROGUE_HUNTING_I:
				// Act II opener — locked until War-Prep I (Magic) finishes (its DONE ordinal is 6);
				// after that the chain auto-begins at step 1, so ordinal 0 still reads as locked.
				// Complete the moment the hunt clears (the chain reaches its KNIGHT beat, ordinal 3).
				if (LofQuestVarps.warprepStep(client) < 6)
				{
					return LofQuestState.LOCKED;
				}
				return ord >= doneOrdinal ? LofQuestState.FINISHED
					: ord == 0 ? LofQuestState.LOCKED : LofQuestState.IN_PROGRESS;
			case ROGUE_HUNTING_II:
				// The ladder — locked until Rogue Hunting I clears (the shared chain reaches
				// ordinal 3); complete when every camp is broken (DONE, ordinal 6).
				if (ord < 3)
				{
					return LofQuestState.LOCKED;
				}
				return ord >= doneOrdinal ? LofQuestState.FINISHED : LofQuestState.IN_PROGRESS;
			case WARPREP_RANGED:
				// Locked until Rogue Hunting II finishes (the rogue chain's DONE ordinal is 6);
				// then auto-begins, so ordinal 0 still reads as locked.
				if (LofQuestVarps.rogueProblemStep(client) < 6)
				{
					return LofQuestState.LOCKED;
				}
				return ord >= doneOrdinal ? LofQuestState.FINISHED
					: ord == 0 ? LofQuestState.LOCKED : LofQuestState.IN_PROGRESS;
			case WARPREP_SURVIVAL:
				// Locked until War-Prep II (Ranged) finishes (its DONE ordinal is 6); then auto-begins at
				// the Lord rank-up, so ordinal 0 still reads as locked.
				if (LofQuestVarps.warprepRangedStep(client) < 6)
				{
					return LofQuestState.LOCKED;
				}
				return ord >= doneOrdinal ? LofQuestState.FINISHED
					: ord == 0 ? LofQuestState.LOCKED : LofQuestState.IN_PROGRESS;
			case KING_OF_LUMBRIDGE:
				// The endgame quest auto-begins on the King rank-up; ordinal 0 = not yet King (locked).
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
		if (this == ROGUE_HUNTING_I && state(client) == LofQuestState.LOCKED)
		{
			return "Finish War-Prep I — Magic first.";
		}
		if (this == ROGUE_HUNTING_II && state(client) == LofQuestState.LOCKED)
		{
			return "Finish Rogue Hunting I first.";
		}
		if (this == WARPREP_RANGED && state(client) == LofQuestState.LOCKED)
		{
			return "Finish Rogue Hunting II first.";
		}
		if (this == WARPREP_SURVIVAL && state(client) == LofQuestState.LOCKED)
		{
			return "Finish War-Prep II — Ranged first.";
		}
		if (this == KING_OF_LUMBRIDGE && state(client) == LofQuestState.LOCKED)
		{
			return "Reach the rank of King (buy it from Duke Horacio) first.";
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

	/**
	 * NPC ids to highlight in the scene (tile marker) and dot on the minimap for the active step
	 * — small, mobile targets a fixed-tile arrow can't pin down. Empty for steps with no such target.
	 */
	int[] currentHighlightNpcIds(Client client)
	{
		LofQuestStep step = currentStep(client);
		if (step == null)
		{
			return NO_NPCS;
		}
		// War-contract step: once the contract is taken the objective is "slay the castle rats". They
		// scurry all over the courtyard and never show on the minimap, so the fixed-tile arrow lands on
		// empty ground — highlight the rats themselves so a new recruit can't miss them.
		if (this == RECRUIT_TRIALS && step.getOrdinal() == 4 && LofQuestVarps.recruitContractTaken(client))
		{
			return CASTLE_RATS;
		}
		return NO_NPCS;
	}

	/** True if any quest can ever flag this npc id as an objective target — the stable membership gate
	 *  for the NPC-overlay highlighter (the live show/hide is the render predicate's job). */
	static boolean isObjectiveNpc(int npcId)
	{
		return npcId == CASTLE_RAT_ID;
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
		if (this == ROGUE_HUNTING_I && step.getOrdinal() == 2 && stepOrdinal(client) == 2)
		{
			return " (" + LofQuestVarps.rogueProblemKills(client) + "/30)";
		}
		if (this == ROGUE_HUNTING_II && step.getOrdinal() == 5 && stepOrdinal(client) == 5)
		{
			return " (" + LofQuestVarps.knightsBeaten(client) + "/14)";
		}
		if (this == WARPREP_RANGED && step.getOrdinal() == 3 && stepOrdinal(client) == 3)
		{
			return " (" + LofQuestVarps.warprepRangedKills(client) + "/20)";
		}
		if (this == WARPREP_SURVIVAL && step.getOrdinal() == 1 && stepOrdinal(client) == 1)
		{
			return " (" + client.getRealSkillLevel(Skill.HITPOINTS) + "/60)";
		}
		return "";
	}
}

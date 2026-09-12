/*
 * Fall of Varrock — the custom quest registry.
 *
 * One entry per Fall of Varrock quest, mirroring the server-side chains step-for-step (the step
 * ordinals MUST match the server enums — RecruitTrials.Step (as published by
 * RecruitTrials.clientOrdinal) and WarPrepChain.Step in Alter).
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
	/**
	 * Main Story Quest 1. The server chain is still RecruitTrials (varp 4610); the wire ordinals are
	 * story order — the server remaps DEBRIEF/DONE (RecruitTrials.clientOrdinal) so 11 = debrief,
	 * 12 = done.
	 */
	LAST_FREE_CITY(
		"The Last Free City",
		"Lumbridge is the last free city of Misthalin, and today it is being probed. Goblins have "
			+ "pushed into the old camp east of the castle; the Knights of Lumbridge are holding, and "
			+ "Sergeant Damien needs every pair of hands. Stand with them, earn your first rank for "
			+ "it, hunt down the attackers who scattered, replace what the army lost — and learn why "
			+ "the war lies north.",
		12, // DONE (wire ordinal)
		Arrays.asList(
			new LofQuestStep(0, "Answer Sergeant Damien's alarm", "Lumbridge is under attack. He's by the castle gate.", new WorldPoint(3217, 3220, 0)),
			new LofQuestStep(1, "Help the Knights of Lumbridge hold the east camp", "Defeat 5 goblins at the goblin camp east of the castle, across the river.", new WorldPoint(3254, 3234, 0)),
			new LofQuestStep(2, "Report to Sergeant Damien", "The attack has been pushed back.", new WorldPoint(3217, 3220, 0)),
			new LofQuestStep(3, "Claim your first rank from Duke Horacio", "You stood for Lumbridge. He's in the market, by the Slayer Master.", new WorldPoint(3220, 3211, 0)),
			new LofQuestStep(4, "Complete Vannaka's cleanup contract", "Take the contract, then hunt the goblins that scattered into the fields east of the castle.", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(5, "Report back to Vannaka", "The stragglers are dealt with.", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(6, "Mine copper and tin in The Mire", "Replace what the defence consumed — the skilling grounds south-east of the castle.", new WorldPoint(3237, 3189, 0)),
			new LofQuestStep(7, "Smelt a bronze bar", "At The Mire's furnace.", new WorldPoint(3237, 3192, 0)),
			new LofQuestStep(8, "Smith a bronze dagger", "At The Mire's anvil.", new WorldPoint(3238, 3196, 0)),
			new LofQuestStep(9, "Deliver the dagger to the Quartermaster", "For the War Effort — the Supply Officer by the crypt in The Mire.", new WorldPoint(3248, 3193, 0)),
			new LofQuestStep(10, "Report back to Vannaka", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(11, "Report to Sergeant Damien", "The immediate danger has passed.", new WorldPoint(3217, 3220, 0))
		),
		Arrays.asList(
			"Your first feudal rank — Peasant to Commoner",
			"10,000 coins and a full steel armour set, piece by piece",
			"War contracts (Vannaka) and the supply loop (The Mire → Quartermaster)",
			"50 War Effort and the Book of Commands",
			"War-Prep I — Magic (Vannaka's drills), and the road north to the first March"
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
			"War-Prep II — Ranged, and the Sergeant's optional Rogue Problem assignment"
		)),

	ROGUE_HUNTING_I(
		"Rogue Hunting I",
		"OPTIONAL. Lumbridge holds, but when Varrock fell its rogues, muggers and highwaymen — led by "
			+ "deserters who style themselves Rogue Knights — scattered onto the roads west and "
			+ "into the ruins of the fallen city. Ask Sergeant Damien for the assignment: "
			+ "thin the cutthroats on the safe road camps west of Lumbridge, or in Fallen Varrock's "
			+ "wilderness streets for the bold. Clearing the hunt pays a soldier's purse — and opens "
			+ "the Rogue Knight ladder, the realm's PK schooling. Nothing else waits on it.",
		3, // complete once the hunt clears (RogueProblem.Step.KNIGHT ordinal)
		Arrays.asList(
			new LofQuestStep(1, "Ask Sergeant Damien for the assignment", "Optional — by the Lumbridge gate, once War-Prep I is done.", new WorldPoint(3217, 3220, 0)),
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
			new LofQuestStep(4, "Return to Sergeant Damien", "Report the knight's fall.", new WorldPoint(3217, 3220, 0)),
			// No fixed anchor: the ladder's own marker leads the climb, camp to camp.
			new LofQuestStep(5, "Break every camp on the ladder", "All 14 knights, weakest to strongest — the Commander last. Buy Soldier and Knight from Duke Horacio as the spoils come in; ::knights tracks the climb.", null)
		),
		Arrays.asList(
			"The ladder's spoils — coin and kits toward your Knighthood: rune armour",
			"Your first companion (General Zo musters them)",
			"The wilderness / PK loop",
			"Every beaten knight stays farmable for its signature gear (::knights)",
			"The realm's PK schooling: switches, baits, spec combos — learned camp by camp"
		)),

	WARPREP_RANGED(
		"War-Prep II — Ranged",
		"The front's skirmish lines are won with the bow. The tower made a mage of you — "
			+ "now Vannaka arms you with a marksman's kit and sends you to prove you can hold a "
			+ "line at distance. He pays a skirmish bounty; the Lordship itself you earn — marches, "
			+ "knight farming, loot keys — and buy when your purse and service allow.",
		6, // DONE ordinal (WarPrepRanged.Step)
		// World anchors are best-effort — TUNE against the live map.
		Arrays.asList(
			new LofQuestStep(2, "Return to Vannaka for the marksman's kit", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(3, "Fell 20 enemies with a ranged weapon", "Bow, crossbow or thrown — Fallen Varrock's rogues will do.", new WorldPoint(3212, 3428, 0)),
			new LofQuestStep(4, "Report back to Vannaka", new WorldPoint(3222, 3212, 0)),
			new LofQuestStep(5, "Earn your Lordship at Duke Horacio", "The realm's loops pay the 2,000,000 and the War Effort — fight the marches, farm the Rogue Knights for kits, hunt the wild for loot keys.", new WorldPoint(3220, 3211, 0))
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
		)),

	/**
	 * Main Story Quest 3 — the first FRAMEWORK quest (server `quests/north/TheNorth`, generic
	 * packing on LofQuestVarps.NORTH). Step ordinals are the 1-based server step index; the state
	 * bits are authoritative (it auto-begins the moment The Last Free City is done).
	 */
	THE_NORTH(
		"The North",
		"You have seen Lumbridge attacked and stood with its Knights. General Zo wants you to see "
			+ "what the Fall of Varrock did to the rest of the kingdom: go north to Edgeville, find "
			+ "someone who remembers the day Varrock fell, stand at the Wilderness line — where the "
			+ "Rogue Knights give way to other adventurers — and bring back the last dispatch Varrock "
			+ "ever sent. No fight is required; the road north may test you anyway.",
		LofQuestVarps.NORTH,
		"Complete The Last Free City first.",
		Arrays.asList(
			new LofQuestStep(1, "Speak to General Zo about the north", "In the castle courtyard, beside Duke Horacio.", new WorldPoint(3220, 3210, 0)),
			new LofQuestStep(2, "Travel to Edgeville", "Any road or teleport — an amulet of glory lands you there. Rogue Knights may cross your path; you need not fight them.", new WorldPoint(3087, 3496, 0)),
			new LofQuestStep(3, "Find someone who remembers the Fall", "Oziach, in his hut at the north-west edge of Edgeville.", new WorldPoint(3069, 3517, 0)),
			new LofQuestStep(4, "Inspect the Wilderness boundary", "Walk to the ditch at the top of town. You do not have to cross it.", new WorldPoint(3088, 3519, 0)),
			new LofQuestStep(5, "Return to Oziach", "Tell him what you saw.", new WorldPoint(3069, 3517, 0)),
			new LofQuestStep(6, "Read the Weathered Varrock Dispatch", "Read it from your pack, or with Oziach.", new WorldPoint(3069, 3517, 0)),
			new LofQuestStep(7, "Take the dispatch to General Zo", "He wants to read the original — and Oziach wants it back.", new WorldPoint(3220, 3210, 0))
		),
		Arrays.asList(
			"15 War Effort",
			"The Weathered Varrock Dispatch — yours to keep and re-read from your pack",
			"Edgeville and the Wilderness line: where the Rogue Knights end and real PvP begins",
			"First Reclamation — the next main story quest"
		)),

	/**
	 * Main Story Quest 4 — a framework quest (server `quests/story/FirstReclamation`, generic journal
	 * varp 4687). Rows are the 1-based server step indices. The server's `retry` step (8) has no row
	 * of its own: it renders as the battle row (7) with a "driven back" suffix, arrow on General Zo.
	 * Chain slot: after The North (7), before A Kingdom Alone (9) — declaration order IS the slot.
	 */
	FIRST_RECLAMATION(
		"First Reclamation",
		"General Zo says surviving is no longer enough. Every march before this one hit the enemy and "
			+ "went home; this time the realm clears the southern approach to Fallen Varrock and HOLDS it. "
			+ "Scout the old stone circle on foot, fight beside the Reclamation Column's Grand March until "
			+ "the line breaks, then raise Lumbridge's standard and establish the Southern Watch - the "
			+ "first forward post in the shadow of the city walls. And hear the limit: Misthalin cannot "
			+ "take Varrock alone.",
		LofQuestVarps.FIRST_RECLAMATION,
		"Finish The North first.",
		Arrays.asList(
			new LofQuestStep(1, "Report to General Zo", "He believes Lumbridge is ready to reclaim its first northern position.", new WorldPoint(3220, 3210, 0)),
			new LofQuestStep(2, "Survey the southern road", "Walk north toward Varrock - the reconnaissance is yours. The road reaches the lower end of the outskirts battlefield.", new WorldPoint(3228, 3344, 0)),
			new LofQuestStep(3, "Inspect the stone circle", "Step inside the ring east of the road.", new WorldPoint(3225, 3371, 0)),
			new LofQuestStep(4, "Look north toward Fallen Varrock", "The road beyond the circle, in front of the south gate.", new WorldPoint(3212, 3381, 0)),
			new LofQuestStep(5, "Report your findings to General Zo", new WorldPoint(3220, 3210, 0)),
			new LofQuestStep(6, "Give General Zo the word", "He launches the Reclamation Column - a public Grand March on the Varrock outskirts.", new WorldPoint(3220, 3210, 0)),
			new LofQuestStep(7, "Fight beside the Reclamation Column", "::march rallies you to it. The column must WIN and you need a real share of the fighting. Driven back? General Zo sends it again.", new WorldPoint(3213, 3376, 0)),
			new LofQuestStep(9, "Raise the standard at the stone circle", "Capture the standard at the ring's heart to establish the Southern Watch.", new WorldPoint(3227, 3372, 0)),
			new LofQuestStep(10, "Report to General Zo", "The Southern Watch is holding.", new WorldPoint(3220, 3210, 0))
		),
		Arrays.asList(
			"The Southern Watch - fast travel to the forward post (portal, General Zo, ::southernwatch)",
			"A Field Quartermaster and a garrison of Knights of Lumbridge at the circle",
			"Varrock march staging on the doorstep of the fallen city",
			"50 War Effort and the spoils of the won Grand March",
			"A Kingdom Alone - the next main quest"
		)),

	/**
	 * Main Story Quest 5. Short and dialogue-only: Duke Horacio and General Zo lay out why Lumbridge
	 * cannot retake Varrock alone, and the four strategic problems the surviving kingdoms must solve.
	 * Completing it opens the four objective entries below at once.
	 */
	A_KINGDOM_ALONE(
		"A Kingdom Alone",
		"The standard flies at the Southern Watch — Lumbridge has taken ground back for the first "
			+ "time in twelve years. Now comes the uncomfortable conclusion. General Zo has worked "
			+ "through what an assault on Varrock would actually require, and Lumbridge cannot "
			+ "retake the city alone. Report to Duke Horacio, hear Zo's assessment, and learn what "
			+ "the surviving kingdoms of Gielinor must solve before anyone marches on Varrock.",
		LofQuestVarps.A_KINGDOM_ALONE,
		"Complete First Reclamation first.",
		Arrays.asList(
			new LofQuestStep(1, "Report to Duke Horacio", "He wants a report on the kingdom's position now the Southern Watch stands. Lumbridge command area, by the market.", new WorldPoint(3220, 3211, 0)),
			new LofQuestStep(2, "Ask General Zo what retaking Varrock would require", "He stands beside the Duke. You won't enjoy his answer.", new WorldPoint(3220, 3210, 0)),
			new LofQuestStep(3, "Report General Zo's assessment to Duke Horacio", new WorldPoint(3220, 3211, 0)),
			new LofQuestStep(4, "Discuss the surviving kingdoms and the four problems", "Falador, the River Salve, the Wilderness, Kandarin — and why nobody is coming yet.", new WorldPoint(3220, 3211, 0))
		),
		Arrays.asList(
			"The Regional Campaign Phase — work across Gielinor in any order",
			"BREACH — Asgarnia: a way through Varrock's defences",
			"SECURE — Morytania: the Salve Accord",
			"UNDERSTAND — the Wilderness, then the Desert: what really happened during the Fall",
			"SUSTAIN — Kandarin and the War Effort: supply for a sustained assault",
			"2 Quest Points; the Council of Gielinor convenes once all four are solved"
		)),

	/** Regional objective — Asgarnia. Completed by the Asgarnia campaign's payoff. */
	BREACH(
		"BREACH — Asgarnia",
		"How do we get an army into Fallen Varrock? The city won't fall because Lumbridge brings "
			+ "more swords — someone has to get those swords through its defences. Falador has "
			+ "soldiers, engineers and weapons Misthalin does not, but the White Knights are at war "
			+ "with the Kinshra and cannot simply march east. Start at the White Wall.",
		LofQuestVarps.BREACH,
		"Complete A Kingdom Alone first.",
		Arrays.asList(
			new LofQuestStep(1, "Find a way for coalition forces to break through Varrock's defences", "Lead: Falador / Asgarnia. First quest: At the White Wall.", null)
		),
		Arrays.asList(
			"White Knight support and Asgarnian manpower",
			"Restored dwarven artillery and multicannon capability",
			"Temple Knight intelligence"
		)),

	/** Regional objective — Morytania. Completed by the Salve Accord. */
	SECURE(
		"SECURE — Morytania",
		"What protects Misthalin while its army is fighting at Varrock? If the army marches north, "
			+ "something else notices — Morytania among others. The objective is narrow: the Salve "
			+ "Accord. No crossing, mutual action against violators, and a stable eastern frontier. "
			+ "An accord, not an alliance. Start at the River Salve.",
		LofQuestVarps.SECURE,
		"Complete A Kingdom Alone first.",
		Arrays.asList(
			new LofQuestStep(1, "Ensure Misthalin will remain secure while its army fights in the north", "Lead: River Salve / Morytania. First quest: Across the Salve.", null)
		),
		Arrays.asList(
			"The Salve Accord — a secured eastern border",
			"Misthalin free to commit its army north"
		)),

	/** Regional objective — the one with an internal order: Wilderness, then Desert, then Senntisten. */
	UNDERSTAND(
		"UNDERSTAND — Wilderness / Desert",
		"What actually happened to Varrock? Everyone knows what people saw: Zemouregal attacked, "
			+ "Arrav led the dead, the city fell. The planners will not send an army into Varrock "
			+ "without knowing why the catastrophe was so abnormal — or whether the same danger "
			+ "remains beneath the city. The investigation has an order: the Wilderness first, then "
			+ "the Kharidian Desert, then whatever Senntisten still remembers.",
		LofQuestVarps.UNDERSTAND,
		"Complete A Kingdom Alone first.",
		Arrays.asList(
			new LofQuestStep(1, "Investigate the First Scar — the Wilderness as an older catastrophe", "Lead: the Wilderness. First quest: The First Scar. No PvP kill is ever required.", null),
			new LofQuestStep(2, "Follow the evidence into the Kharidian Desert", "Azzanadra and Mahjarrat history lead toward Sliske and the Elder Horn.", null),
			new LofQuestStep(3, "Uncover what Senntisten holds beneath Varrock", "The convergence that preceded the Fall.", null)
		),
		Arrays.asList(
			"The truth beneath the accepted story of the Fall",
			"Whether the same danger still waits beneath Varrock"
		)),

	/** Regional objective — Kandarin + the War Effort. Completed by restored logistics. */
	SUSTAIN(
		"SUSTAIN — Kandarin / War Effort",
		"How do we keep an army alive once it reaches Varrock? One battle empties the stores; now "
			+ "imagine feeding thousands — arrows, food, medicine, replacement armour, horses, "
			+ "transport, for weeks. Kandarin still trades. Restore the transport and trade the Fall "
			+ "broke, and combine it with the realm's own War Effort.",
		LofQuestVarps.SUSTAIN,
		"Complete A Kingdom Alone first.",
		Arrays.asList(
			new LofQuestStep(1, "Create the supply and transportation network required to maintain a major offensive", "Lead: Kandarin + the War Effort. Major quest: The Long Road East.", null)
		),
		Arrays.asList(
			"Restored Spirit Tree, glider and Fairy Ring transport",
			"An eastern convoy and the trade to feed a coalition army"
		)),

	// ---- Regional campaigns: framework quests (generic varp packing) ----
	// Chain order here MUST match the server's QuestBook constants: the regional campaign quests
	// follow the four objectives (AT_THE_WHITE_WALL = 14, directly after SUSTAIN = 13), campaign
	// by campaign. Step ordinals are the server's 1-based step indices.

	/**
	 * Asgarnia — BREACH, Quest 1. The regional opener: why Falador does not simply send its army to
	 * Varrock. A checkpoint fight at Falador's north gate, Sir Amik Varze's "I have an army. I do not
	 * have an army to spare.", Sir Tiffy Cashien's first questions, and a walk along the front.
	 */
	AT_THE_WHITE_WALL(
		"At the White Wall",
		"Falador survived the Fall — and became a fortified military state locked in a war with the "
			+ "Kinshra. It has exactly the army Misthalin needs for Varrock, and none of it to spare: "
			+ "the Kinshra do not need to take the city, only to keep its knights busy, while the "
			+ "trolls pin the Imperial Guard at Burthorpe and the guns wear out faster than the dwarves "
			+ "can replace them. Reach the north gate, hold it with the White Knights, hear Sir Amik "
			+ "out, meet Sir Tiffy, and read the ground yourself.",
		LofQuestVarps.AT_THE_WHITE_WALL,
		"Complete A Kingdom Alone first.",
		Arrays.asList(
			new LofQuestStep(1, "Travel to Asgarnia", "Falador's NORTH gate — the one facing the Kinshra. Any road or teleport into Falador, then out to the north gate.", new WorldPoint(2965, 3398, 0)),
			new LofQuestStep(2, "Speak with the White Knights at the checkpoint", "The garrison holds the road just outside the north gate.", new WorldPoint(2965, 3398, 0)),
			new LofQuestStep(3, "Help the White Knights repel the Kinshra attack", "Defeat 5 Kinshra raiders at the checkpoint. Any raider you draw blood on counts, even if a knight finishes it.", new WorldPoint(2965, 3400, 0), 5),
			new LofQuestStep(4, "Speak with Sir Amik Varze", "Top floor of the White Knights' Castle, in the middle of Falador.", new WorldPoint(2960, 3336, 2)),
			new LofQuestStep(5, "Find Sir Tiffy Cashien", "His bench in Falador Park, east of the castle.", new WorldPoint(2997, 3373, 0)),
			new LofQuestStep(6, "Inspect the front", "Three places, any order: the White Knight line at the checkpoint, the supply road just inside the north gate, and the ground north of the fence beyond the checkpoint.", new WorldPoint(2965, 3398, 0)),
			new LofQuestStep(7, "Report to Sir Amik Varze", "Top floor of the White Knights' Castle.", new WorldPoint(2960, 3336, 2))
		),
		Arrays.asList(
			"1 Quest Point and 25 War Effort",
			"The Asgarnia campaign (BREACH) formally begun — A Matter of Trolls unlocked",
			"The Asgarnian Front: the White Knight checkpoint at Falador's north gate"
		)),

	/** FUTURE teaser: the strategic phase's payoff (excluded from the chain track until built). */
	COUNCIL_OF_GIELINOR(
		"Council of Gielinor",
		"Once BREACH, SECURE, UNDERSTAND and SUSTAIN are all solved, a small council of the "
			+ "surviving kingdoms' representatives reviews the strategic work and authorises "
			+ "sustained assaults on Fallen Varrock. The first major assault on the city follows.",
		Arrays.asList(
			"The First Major Assault on Varrock",
			"Veteran of Varrock"
		));

	/** First Reclamation's battle row / the server's retry step (see the entry's note). */
	private static final int FIRST_RECLAMATION_BATTLE = 7;
	private static final int FIRST_RECLAMATION_RETRY = 8;
	/** General Zo's post in the castle hub. */
	private static final WorldPoint GENERAL_ZO = new WorldPoint(3220, 3210, 0);

	/**
	 * The Kinshra raiders of At the White Wall — stock Black Knights (the checkpoint raid spawns
	 * 516; 517 is the fortress twin). Highlighted during the checkpoint fight so the player can pick
	 * the raiders out of the melee with the White Knights.
	 */
	private static final int[] BLACK_KNIGHTS = {516, 517};

	/**
	 * The goblins of the Lumbridge fields — every plain "Goblin" npc id the camp and the surrounding
	 * countryside spawn (the stock 655-668/674/677/678 family, plus the frontier's 2245-2249 line).
	 * The Last Free City highlights them during the east-camp fight and, once Vannaka's cleanup
	 * contract is taken, during the hunt — so a recruit can pick the goblins out of the brawl with
	 * the Knights of Lumbridge (the server credits kills by cache NAME, so any of these count).
	 */
	private static final int[] GOBLINS = {
		655, 656, 657, 658, 659, 660, 661, 662, 663, 664, 665, 666, 667, 668, 674, 677, 678,
		2245, 2246, 2247, 2248, 2249,
	};
	private static final int[] NO_NPCS = new int[0];

	/** Where Vannaka's cleanup contract sends the recruit: the goblin field east of the castle, the
	 *  Slayer hunting ground for goblins (matches the server's `::slayertele` destination). */
	private static final WorldPoint GOBLIN_FIELD = new WorldPoint(3247, 3244, 0);

	private final String questName;
	private final String why;
	/** Step ordinal that means "completed" on the server (-1 for FUTURE teaser entries). */
	private final int doneOrdinal;
	private final List<LofQuestStep> steps;
	private final List<String> unlocks;
	/** Framework quests: the server's generic journal varp (QuestDefinition.journalVarp); 0 = a
	 *  legacy chain (own varp layout, switched on below) or a FUTURE teaser. */
	private final int genericVarp;
	/** Framework quests: the "Locked — …" line while the prerequisites are unmet (nullable). */
	private final String lockReasonText;

	LofQuest(String questName, String why, int doneOrdinal, List<LofQuestStep> steps, List<String> unlocks)
	{
		this(questName, why, doneOrdinal, 0, null, steps, unlocks);
	}

	/**
	 * Framework quest entry (server `QuestDefinition` with a `journalVarp`): generic packing —
	 * bits 0-7 current step index + 1, bits 8-19 progress, bits 20-21 state (0 locked / not begun,
	 * 1 in progress, 2 complete). Step ordinals are the server's 1-based step indices.
	 */
	LofQuest(String questName, String why, int genericVarp, String lockReason, List<LofQuestStep> steps, List<String> unlocks)
	{
		this(questName, why, Integer.MAX_VALUE, genericVarp, lockReason, steps, unlocks);
	}

	/** FUTURE teaser entry — no server chain behind it yet. */
	LofQuest(String questName, String why, List<String> unlocks)
	{
		this(questName, why, -1, 0, null, Collections.emptyList(), unlocks);
	}

	LofQuest(String questName, String why, int doneOrdinal, int genericVarp, String lockReason, List<LofQuestStep> steps, List<String> unlocks)
	{
		this.questName = questName;
		this.why = why;
		this.doneOrdinal = doneOrdinal;
		this.genericVarp = genericVarp;
		this.lockReasonText = lockReason;
		this.steps = steps;
		this.unlocks = unlocks;
	}

	boolean isFuture()
	{
		return doneOrdinal < 0;
	}

	/** A framework (generic-varp) quest, as opposed to a legacy chain or a FUTURE teaser. */
	boolean isGeneric()
	{
		return genericVarp > 0;
	}

	/** True if [varp] is any framework quest's journal varp — a change to it must refresh the journal. */
	static boolean isJournalVarp(int varp)
	{
		for (LofQuest q : values())
		{
			if (q.genericVarp == varp)
			{
				return true;
			}
		}
		return false;
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
		if (isGeneric())
		{
			if (this == FIRST_RECLAMATION)
			{
				// The server's retry step (8) is the battle row (7) again — driven back, see General Zo.
				final int raw = LofQuestVarps.genericStep(client, genericVarp);
				return raw == FIRST_RECLAMATION_RETRY ? FIRST_RECLAMATION_BATTLE : raw;
			}
			return LofQuestVarps.genericStep(client, genericVarp);
		}
		switch (this)
		{
			case LAST_FREE_CITY:
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
		if (isGeneric())
		{
			// Framework quests begin on their own once the prerequisites are met (or from the
			// quest before them), so "not begun" reads as locked.
			switch (LofQuestVarps.genericState(client, genericVarp))
			{
				case 2:
					return LofQuestState.FINISHED;
				case 1:
					return LofQuestState.IN_PROGRESS;
				default:
					return LofQuestState.LOCKED;
			}
		}
		int ord = stepOrdinal(client);
		switch (this)
		{
			case LAST_FREE_CITY:
				// The alarm step (0) is handed to every fresh citizen — count it as not-yet-started.
				return ord >= doneOrdinal ? LofQuestState.FINISHED
					: ord == 0 ? LofQuestState.NOT_STARTED : LofQuestState.IN_PROGRESS;
			case WARPREP_MAGIC:
				// The chain begins once The Last Free City is done; ordinal 0 = still locked.
				return ord >= doneOrdinal ? LofQuestState.FINISHED
					: ord == 0 ? LofQuestState.LOCKED : LofQuestState.IN_PROGRESS;
			case ROGUE_HUNTING_I:
				// OPTIONAL assignment — locked until War-Prep I (Magic) finishes (its DONE ordinal
				// is 6); after that the Sergeant offers it, so ordinal 0 reads as NOT STARTED.
				// Complete the moment the hunt clears (the chain reaches its KNIGHT beat, ordinal 3).
				if (LofQuestVarps.warprepStep(client) < 6)
				{
					return LofQuestState.LOCKED;
				}
				return ord >= doneOrdinal ? LofQuestState.FINISHED
					: ord == 0 ? LofQuestState.NOT_STARTED : LofQuestState.IN_PROGRESS;
			case ROGUE_HUNTING_II:
				// The ladder — locked until Rogue Hunting I clears (the shared chain reaches
				// ordinal 3); complete when every camp is broken (DONE, ordinal 6).
				if (ord < 3)
				{
					return LofQuestState.LOCKED;
				}
				return ord >= doneOrdinal ? LofQuestState.FINISHED : LofQuestState.IN_PROGRESS;
			case WARPREP_RANGED:
				// Locked until War-Prep I (Magic) finishes (its DONE ordinal is 6); then
				// auto-begins, so ordinal 0 still reads as locked. The Rogue Problem is optional
				// and never gates it.
				if (LofQuestVarps.warprepStep(client) < 6)
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
		if (isGeneric())
		{
			return state(client) == LofQuestState.LOCKED ? lockReasonText : null;
		}
		if (this == WARPREP_MAGIC && state(client) == LofQuestState.LOCKED)
		{
			return "Complete The Last Free City first.";
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
			return "Finish War-Prep I — Magic first.";
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
		if (state(client) == LofQuestState.FINISHED)
		{
			return steps.size(); // a finished framework quest publishes step 0 — every row is behind
		}
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
	 * step can redirect mid-flight (the cleanup-contract step moves from Vannaka to the goblin
	 * field once the contract is taken).
	 */
	WorldPoint currentTarget(Client client)
	{
		LofQuestStep step = currentStep(client);
		if (step == null)
		{
			return null;
		}
		if (this == FIRST_RECLAMATION && LofQuestVarps.genericStep(client, genericVarp) == FIRST_RECLAMATION_RETRY)
		{
			return GENERAL_ZO; // driven back — regroup with General Zo before the next push
		}
		if (this == LAST_FREE_CITY && step.getOrdinal() == 4 && LofQuestVarps.recruitContractTaken(client))
		{
			return GOBLIN_FIELD; // contract taken — hunt the goblins loose east of the castle
		}
		return step.getTarget();
	}

	/**
	 * NPC ids to highlight in the scene (tile marker) and dot on the minimap for the active step
	 * — the targets a fixed-tile arrow can't pin down. Empty for steps with no such target.
	 */
	int[] currentHighlightNpcIds(Client client)
	{
		LofQuestStep step = currentStep(client);
		if (step == null)
		{
			return NO_NPCS;
		}
		if (this == LAST_FREE_CITY)
		{
			// The east-camp fight: pick the goblins out of the brawl with the Knights of Lumbridge.
			if (step.getOrdinal() == 1)
			{
				return GOBLINS;
			}
			// The cleanup contract, once taken: the goblins that scattered into the countryside.
			if (step.getOrdinal() == 4 && LofQuestVarps.recruitContractTaken(client))
			{
				return GOBLINS;
			}
		}
		if (this == AT_THE_WHITE_WALL && step.getOrdinal() == 3)
		{
			// The checkpoint raid: pick the Kinshra raiders out of the melee with the White Knights.
			return BLACK_KNIGHTS;
		}
		return NO_NPCS;
	}

	/** True if any quest can ever flag this npc id as an objective target — the stable membership gate
	 *  for the NPC-overlay highlighter (the live show/hide is the render predicate's job). */
	static boolean isObjectiveNpc(int npcId)
	{
		for (int id : GOBLINS)
		{
			if (id == npcId)
			{
				return true;
			}
		}
		for (int id : BLACK_KNIGHTS)
		{
			if (id == npcId)
			{
				return true;
			}
		}
		return false;
	}

	/** Live progress suffix for a step row, e.g. " (3/5)" goblins or " (23/37)" Prayer. */
	String stepProgress(Client client, LofQuestStep step)
	{
		if (isGeneric())
		{
			if (this == FIRST_RECLAMATION && step.getOrdinal() == FIRST_RECLAMATION_BATTLE
				&& LofQuestVarps.genericStep(client, genericVarp) == FIRST_RECLAMATION_RETRY)
			{
				return " (driven back - see General Zo)";
			}
			// Counted steps of a framework quest: the generic progress bits against the step's goal.
			if (step.getGoal() > 0 && stepOrdinal(client) == step.getOrdinal())
			{
				final int n = Math.min(LofQuestVarps.genericProgress(client, genericVarp), step.getGoal());
				return " (" + n + "/" + step.getGoal() + ")";
			}
			return "";
		}
		if (this == LAST_FREE_CITY && step.getOrdinal() == 1 && stepOrdinal(client) == 1)
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

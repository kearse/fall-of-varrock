package org.alter.game.model.attr

import org.alter.game.model.container.ItemTransaction
import org.alter.game.model.entity.*
import org.alter.game.model.item.Item
import org.alter.game.model.shop.Shop
import java.lang.ref.WeakReference

/**
 * A decoupled file that holds AttributeKeys that require read-access from our
 * game module. Any attributes that can be stored on the plugin classes themselves,
 * should do so. When storing them in a class, remember the AttributeKey must be
 * a singleton, meaning it should only have a single state.
 *
 * @author Tom <rspsmods@gmail.com>
 */

/**
 * Indicates the last [Date.time] the player logged in
 *   Note| due to GSON/JSON limitation on types, storing [Long] as [String] instead
 *   despite imposed costs
 */
val LAST_LOGIN_ATTR = AttributeKey<String>("last_login")

/**
 * Indicates the amount of time the player has membership
 *   Note| due to GSON/JSON limitation on types, storing [Long] as [String] instead
 *   despite imposed costs
 */
val MEMBERS_EXPIRES_ATTR = AttributeKey<String>("members_expires")

/**
 * A flag which indicates if the player's account was just created/logged in for
 * the first time.
 */
val NEW_ACCOUNT_ATTR = AttributeKey<Boolean>()

/**
 * Indicates the last [Date.time] the player claimed a free bond (tradeable)
 *   Note| due to GSON/JSON limitation on types, storing [Long] as [String] instead
 *   despite imposed costs
 */
val FREE_BOND_CLAIMED_ATTR = AttributeKey<String>("bond_claimed")

/**
 * A flag which indicates if the player's appearance has been set by the player.
 * Opting for persistence and modifying on_login behavior this will allow OSRS-like
 * behavior such that player can logout and will still be allowed to set appearance
 * at next login. Additionally this flag can be set by admins to allow change on next login.
 */
val APPEARANCE_SET_ATTR = AttributeKey<Boolean>("appearance_set")

/**
 * First-login onboarding step (see FirstLoginFlow): 0 = intro VIDEO, 1 = character
 * STYLE window, 2 = DONE. Persisted so the flow survives a disconnect mid-sequence
 * (resume where you left off). An ABSENT value means DONE — every existing account
 * predates onboarding and must never be re-onboarded. Seeded to 0 for brand-new
 * accounts in PlayerSaving.configureNewPlayer.
 */
val ONBOARD_STEP_ATTR = AttributeKey<Int>("onboard_step")

/**
 * While set, appearance syncs broadcast the player WITHOUT worn equipment — the identikit
 * body only. Set for the duration of the Character Style window (you edit your default
 * skins, so the preview must show them); cleared when the window closes. Transient on
 * purpose: a logout mid-makeover must never leave a player stuck looking undressed.
 */
val STYLE_PREVIEW_ATTR = AttributeKey<Boolean>()

/**
 * The id of the city the player is a citizen of (The War / siege content).
 * Persistent. Resolves the player's home: respawn point and bank city.
 */
val CITY_ID_ATTR = AttributeKey<Int>("city_id")

/**
 * An optional custom respawn location, stored as a [org.alter.game.model.Tile]'s
 * packed coordinate. When set, [org.alter.game.action.PlayerDeathAction] sends the
 * player here on (non-instance) death instead of the global home tile. Persistent.
 * Populated by citizenship content from [CITY_ID_ATTR]; kept generic so any
 * feature can override a player's respawn.
 */
val RESPAWN_TILE_ATTR = AttributeKey<Int>("respawn_tile")

/**
 * The player's purchased feudal **rank**, stored as the title's ordinal (0 =
 * Peasant). Bought with coins from Duke Horacio; caps the armour tier the player
 * may wear (The War / progression — see `docs/war-system-design.md`). Persistent.
 */
val PLAYER_TITLE_ATTR = AttributeKey<Int>("player_title")

/**
 * The player's display name as it should appear over their head / on right-click: a color-coded
 * "<Rank> Name" for noble ranks (Squire+), or the plain username below that. Computed from
 * [PLAYER_TITLE_ATTR] by the title content and read by [org.alter.game.info.PlayerInfo]. Session-only
 * (re-derived on login and whenever the rank changes).
 */
val TITLED_NAME_ATTR = AttributeKey<String>()

/**
 * Reward-economy point currencies (content/economy). Persistent counters spent at
 * point reward shops (see PointsCurrency). Slayer points from slayer tasks, boss
 * points from PvM, vote points from voting.
 */
val SLAYER_POINTS_ATTR = AttributeKey<Int>("slayer_points")
val BOSS_POINTS_ATTR = AttributeKey<Int>("boss_points")
val VOTE_POINTS_ATTR = AttributeKey<Int>("vote_points")

/**
 * **War Effort** — the war economy's currency (master design brief §3A). Earned from
 * the three war pillars: frontier/raid kills, Slayer war-contracts, and supply
 * contributions to the Quartermaster. Spent on the war supply shop, rank-up discounts,
 * war cosmetics and city benefits. A persistent counter like the other point kinds; it
 * is **earned, never purchasable** (kept off the monetization ladder by design). Persistent.
 */
val WAR_EFFORT_POINTS_ATTR = AttributeKey<Int>("war_effort_points")

/**
 * **Daily War Effort bonus** (master design brief §3A). [WAR_EFFORT_TODAY_ATTR] is the War Effort
 * EARNED so far today (reset each day — separate from the spendable [WAR_EFFORT_POINTS_ATTR] balance);
 * [WAR_EFFORT_BONUS_DAY_ATTR] is the epoch-day that total belongs to. Crossing daily thresholds grants
 * tiered skilling-XP and monster-drop bonuses for the rest of the day (see
 * [org.alter.plugins.content.economy.WarEffortBonus]). [DROP_BONUS_PERCENT_ATTR] is the resulting
 * monster-drop bonus percent, read by the engine loot roll. Persistent.
 */
val WAR_EFFORT_TODAY_ATTR = AttributeKey<Int>("war_effort_today")
val WAR_EFFORT_BONUS_DAY_ATTR = AttributeKey<Int>("war_effort_bonus_day")
val DROP_BONUS_PERCENT_ATTR = AttributeKey<Int>("drop_bonus_percent")

/**
 * **Prestige** — the political/feudal progression currency (boss-raid & campaign content,
 * see `docs`). Earned by sponsoring operations: a Lord/Minister/King who pays to summon a
 * boss or command a campaign is awarded prestige on its completion. A persistent counter
 * like the other point kinds, kept **orthogonal to [PLAYER_TITLE_ATTR]** (Title is a bought
 * armour gate; prestige is earned and spent at prestige reward shops). Persistent.
 */
val PRESTIGE_POINTS_ATTR = AttributeKey<Int>("prestige_points")

/**
 * The player's **companion** roster, stored as a JSON blob (the companion plugin owns the
 * encode/decode — the engine save layer can't reference plugin classes). Holds each owned
 * companion's archetype, per-skill XP, gear, supplies and dead/alive state, so companions are
 * permanent and levelable across logins. Written on logout (before save) and periodically; read
 * on login to respawn the roster. Persistent. See content/companion.
 */
val COMPANIONS_ATTR = AttributeKey<String>("companions")

/**
 * The player's **wilderness loot key** contents, stored as a JSON blob (the loot-key plugin owns
 * the encode/decode — same pattern as [COMPANIONS_ATTR]). Each entry maps one of the 5 loot key
 * items in the player's possession to the sealed victim loot it represents. Persistent. See
 * content/economy/pk/LootKeyPlugin.
 */
val LOOT_KEYS_ATTR = AttributeKey<String>("loot_keys")

/**
 * A Minister/King's **unclaimed war spoils** — the commander's cut of a won campaign/conquest
 * (war-stake return + tithe, plus a rare 3rd age relic) held pending an explicit `::claim` instead
 * of auto-banking. Stored as a JSON item blob (same pattern as [LOOT_KEYS_ATTR]; the war-spoils
 * system owns the encode/decode). Persistent, so a commander can claim after a relog. See
 * content/war/WarSpoils.
 */
val WAR_SPOILS_ATTR = AttributeKey<String>("war_spoils")

/**
 * **Donor points** — the monetization-side currency (master design brief §6: "money buys time and
 * flair"). Earned by donating and as the donor's campaign perk (a 1% cut of a won campaign's loot,
 * MINTED in donor points so it never inflates gp or skims the participants' earned pool — see
 * [org.alter.plugins.content.war.CapturePayout]). Spent on cosmetics/convenience, NEVER on the
 * earned ladder. Persistent counter like the other point kinds.
 */
val DONOR_POINTS_ATTR = AttributeKey<Int>("donor_points")

/**
 * **Recruit Trials** onboarding state (master design brief §1). [RECRUIT_TRIAL_STEP_ATTR]
 * is the current step ordinal of [org.alter.plugins.content.war.recruit.RecruitTrials.Step]
 * (0 = talk to the Recruiting Sergeant, rising to DONE). [RECRUIT_GOBLIN_KILLS_ATTR] counts
 * goblins killed during the FIGHT trial; [RECRUIT_SUPPLY_MINED_ATTR] counts ore mined during the
 * SUPPLY trial. All persistent so the chain survives a relog and never re-fires once DONE.
 */
val RECRUIT_TRIAL_STEP_ATTR = AttributeKey<Int>("recruit_trial_step")
val RECRUIT_GOBLIN_KILLS_ATTR = AttributeKey<Int>("recruit_goblin_kills")
val RECRUIT_SUPPLY_MINED_ATTR = AttributeKey<Int>("recruit_supply_mined")

/**
 * **War-Prep quest chain** state (the post-Recruit-Trials onboarding that readies a citizen for
 * raids). [WARPREP_STEP_ATTR] is the current step ordinal of
 * [org.alter.plugins.content.war.warprep.WarPrepChain.Step] (0 = not started). The chain begins
 * when the Recruit Trials finish and gates raid access on its completion. Persistent, so it
 * survives a relog and never re-fires once complete.
 */
val WARPREP_STEP_ATTR = AttributeKey<Int>("warprep_step")

/**
 * How many dragon-bone top-ups Vannaka has already given this player on the War-Prep PRAYER step.
 * Bones are tradeable, so an uncapped top-up is an infinite-bones faucet (drop/trade the stack and
 * ask again); the cap bounds the exploit, and past it Vannaka grants the remaining Prayer XP
 * directly instead (nothing farmable, still no soft-lock). Persistent.
 */
val WARPREP_BONE_TOPUPS_ATTR = AttributeKey<Int>("warprep_bone_topups")

/**
 * Whether the player has muted quest guidance ("free play" mode): while true, the quest chains
 * (Recruit Trials, War-Prep) stop drawing their hint arrows so the player can roam untracked.
 * Progress still advances silently — muting pauses the nagging, not the quest. Toggled by
 * `::questguide` (which the custom client's Quest Journal panel sends). Persistent.
 */
val QUEST_GUIDE_MUTED_ATTR = AttributeKey<Boolean>("quest_guide_muted")

/**
 * One-time "we've met" flags so key NPCs give a full first-meeting introduction (who they are +
 * how their system works) the first time, then fall through to their concise everyday dialogue.
 * Part of the intro-quest polish (master design brief §1). Persistent.
 */
val DUKE_INTRO_DONE_ATTR = AttributeKey<Boolean>("duke_intro_done")
val SLAYER_INTRO_DONE_ATTR = AttributeKey<Boolean>("slayer_intro_done")
val VOID_KNIGHT_INTRO_DONE_ATTR = AttributeKey<Boolean>("void_knight_intro_done")

/**
 * Whether the player has cleared the **Mage Tower** raid, which permanently unlocks the three
 * special spellbooks (Ancient/Lunar/Arceuus) for free switching via `::spellbook`. Once earned
 * it stays set across logins, so the player can swap books whenever. Until then only the Standard
 * book is switchable. The raid grants it on completion (see magic/spellbook/MageBookUnlock). Persistent.
 */
val MAGE_BOOKS_UNLOCKED_ATTR = AttributeKey<Boolean>("mage_books_unlocked")

/**
 * The player's Collection Log: a comma-separated list of collected item ids (notable
 * boss/rare drops). Persistent. See content/bosses/CollectionLog.
 */
val COLLECTION_LOG_ATTR = AttributeKey<String>("collection_log")

/**
 * Daily-reward + vote retention state (content/economy/daily). Day-stamps are epoch-day
 * numbers (millis / 86_400_000); streaks count consecutive claim days. Persistent.
 */
val DAILY_LAST_DAY_ATTR = AttributeKey<Int>("daily_last_day")
val DAILY_STREAK_ATTR = AttributeKey<Int>("daily_streak")
val VOTE_LAST_DAY_ATTR = AttributeKey<Int>("vote_last_day")
val VOTE_STREAK_ATTR = AttributeKey<Int>("vote_streak")

/**
 * The furthest wave the player has reached in the Fight Cave wave arena
 * (content/minigames/fightcave) — a personal hiscore + the fire-cape unlock gate. Persistent.
 */
val ARENA_BEST_WAVE_ATTR = AttributeKey<Int>("arena_best_wave")

/**
 * The Inferno (content/minigames/inferno): [INFERNO_UNLOCKED_ATTR] is set once the player has
 * sacrificed a Fire cape to TzHaar-Ket-Keh (permanent access); [INFERNO_BEST_WAVE_ATTR] is the
 * furthest wave reached — the personal hiscore. Persistent.
 */
val INFERNO_UNLOCKED_ATTR = AttributeKey<Boolean>("inferno_unlocked")
val INFERNO_BEST_WAVE_ATTR = AttributeKey<Int>("inferno_best_wave")

/**
 * True once the player has paid the Mire Run agility course's one-time dispenser attunement fee
 * (content/skills/agility — the wilderness-agility-style loot dispenser). Persistent.
 */
val MIRE_RUN_PAID_ATTR = AttributeKey<Boolean>("mire_run_paid")

/**
 * Marks an npc as **loot-suppressed**: the generic real-OSRS drop handler
 * (content/drops/NpcDropPlugin) skips it on death. Set by minigames that spawn ordinary monster
 * ids as reward-gated waves (e.g. the Fight Cave — cape only, no per-kill loot). Transient.
 */
val NO_LOOT_ATTR = AttributeKey<Boolean>()

/**
 * The player's active Slayer assignment (content/skills/slayer). [SLAYER_TASK_NPC_ATTR]
 * is the RSCM npc key being hunted; [SLAYER_TASK_LEFT_ATTR] the remaining kill count;
 * [SLAYER_TASK_TOTAL_ATTR] the count originally assigned (so the client dial can show how far
 * through the task the player is); [SLAYER_STREAK_ATTR] the completed-task streak (scales point
 * rewards). Persistent.
 */
val SLAYER_TASK_NPC_ATTR = AttributeKey<String>("slayer_task_npc")
val SLAYER_TASK_LEFT_ATTR = AttributeKey<Int>("slayer_task_left")
val SLAYER_TASK_TOTAL_ATTR = AttributeKey<Int>("slayer_task_total")
val SLAYER_STREAK_ATTR = AttributeKey<Int>("slayer_streak")

/**
 * The npc key of the combat contract the player was *last* assigned, so the next assignment
 * can avoid handing out the same monster twice in a row (mirrors
 * [RESOURCE_CONTRACT_LAST_ATTR] on the gathering side). Persistent.
 */
val SLAYER_TASK_LAST_ATTR = AttributeKey<String>("slayer_task_last")

/**
 * The player's active **Resource contract** from Vannaka (master design brief §2 — Slayer becomes
 * War Contracts). [RESOURCE_CONTRACT_ITEM_ATTR] is the RSCM item key to gather (ore/log/fish);
 * [RESOURCE_CONTRACT_LEFT_ATTR] the remaining count. Rewards coin + War Effort on completion. Held
 * independently of the Slayer (combat) contract, so a player may run one of each. Persistent.
 */
val RESOURCE_CONTRACT_ITEM_ATTR = AttributeKey<String>("resource_contract_item")
val RESOURCE_CONTRACT_LEFT_ATTR = AttributeKey<Int>("resource_contract_left")

/**
 * The item key of the resource contract the player *last* finished, so the next assignment can
 * avoid handing out the same work twice in a row. Persistent.
 */
val RESOURCE_CONTRACT_LAST_ATTR = AttributeKey<String>("resource_contract_last")

/**
 * The fish the player has chosen to catch (raw item RSCM key, set from the choose-fish menu or
 * `::fish`). Kept across logout so a deliberate pick isn't lost mid-contract. Persistent.
 */
val FISHING_PREFERENCE_ATTR = AttributeKey<String>("fishing_preference")

/**
 * **Rogue hunting** (content/war/roguehunt — story-and-grind-design §4): [ROGUE_KILLS_ATTR] is the
 * lifetime count of rogue-family kills (the human cutthroats occupying Fallen Varrock); the
 * Recruiting Sergeant pays escalating milestone bounties on it. [ROGUE_MILESTONE_PAID_ATTR] is how
 * many milestones he has already paid. Death never resets the counter. Persistent.
 */
val ROGUE_KILLS_ATTR = AttributeKey<Int>("rogue_kills")
val ROGUE_MILESTONE_PAID_ATTR = AttributeKey<Int>("rogue_milestone_paid")

/**
 * **The Rogue Problem** — the Act II quest (story-and-grind-design §4) that picks up where the
 * War-Prep chain leaves off and bridges the Squire→Knight climb.
 * [ROGUE_PROBLEM_STEP_ATTR] is the current step ordinal of
 * [org.alter.plugins.content.war.roguehunt.RogueProblem.Step] (0 = not started).
 * [ROGUE_PROBLEM_KILLS_ATTR] is a **quest-scoped** hunt counter for the HUNT step (kept separate
 * from the lifetime [ROGUE_KILLS_ATTR] milestone tally so the quest goal is measured fresh, not
 * satisfied by kills banked before the quest began). Both persistent; the chain never re-fires
 * once DONE.
 */
val ROGUE_PROBLEM_STEP_ATTR = AttributeKey<Int>("rogue_problem_step")
val ROGUE_PROBLEM_KILLS_ATTR = AttributeKey<Int>("rogue_problem_kills")

/**
 * **The Rogue Knight ladder** (content/bots/knights — the organized named-knight assignment track
 * the Recruiting Sergeant runs after The Rogue Problem's briefing). [ROGUE_KNIGHT_RANK_ATTR] is how
 * many named Rogue Knights the player has beaten — their assigned target is always the knight at
 * this index of the ladder. [ROGUE_KNIGHT_TARGET_ATTR] is an optional FARM target: a beaten
 * knight's index the player has asked to hunt again (clamped to <= rank on read; absent = hunt the
 * assigned knight). Death never resets either — dying to a knight is the expected loop. Persistent.
 */
val ROGUE_KNIGHT_RANK_ATTR = AttributeKey<Int>("rogue_knight_rank")
val ROGUE_KNIGHT_TARGET_ATTR = AttributeKey<Int>("rogue_knight_target")

/**
 * Marks a live [PkBot] as a NAMED Rogue Knight instance: the ladder key of the knight it embodies
 * (see `content/bots/knights/RogueKnights`). Set at spawn by the camp engine; bots are never
 * persisted, so this is session-only by nature. Transient.
 */
val KNIGHT_KEY_ATTR = AttributeKey<String>()

/**
 * **King of Lumbridge** — the endgame conquest quest (see
 * [org.alter.plugins.content.war.Conquest]). Opens the moment a player reaches [King][
 * org.alter.plugins.content.war.Title.KING] and guides them through supplying the realm, mustering
 * an army and winning a conquest of Fallen Varrock. [CONQUEST_STEP_ATTR] is the current step ordinal
 * of [org.alter.plugins.content.war.Conquest.Step] (0 = not started / not yet King); persistent, and
 * the chain never re-fires once DONE.
 */
val CONQUEST_STEP_ATTR = AttributeKey<Int>("conquest_step")

/**
 * **War-Prep II — Ranged** — the Act II prep quest (story-and-grind-design §1) that follows
 * The Rogue Problem and carries the new Knight up to Lord, teaching the bow for the front's
 * skirmish lines. [WARPREP_RANGED_STEP_ATTR] is the current step ordinal of
 * [org.alter.plugins.content.war.warprep.WarPrepRanged.Step] (0 = not started).
 * [WARPREP_RANGED_KILLS_ATTR] is a **quest-scoped** counter for the FIELD step's ranged kills
 * (kept fresh, not satisfied by earlier kills). [WARPREP_RANGED_AMMO_TOPUPS_ATTR] bounds the
 * anti-soft-lock ammo top-up on the DRILL step (arrows are tradeable, so an uncapped top-up is a
 * faucet; past the cap the remaining Ranged XP is drilled in directly). All persistent; the chain
 * never re-fires once DONE. Persisted BY ORDINAL — never reorder the Step enum without a migration.
 */
val WARPREP_RANGED_STEP_ATTR = AttributeKey<Int>("warprep_ranged_step")
val WARPREP_RANGED_KILLS_ATTR = AttributeKey<Int>("warprep_ranged_kills")
val WARPREP_RANGED_AMMO_TOPUPS_ATTR = AttributeKey<Int>("warprep_ranged_ammo_topups")

/**
 * **War-Prep III — Survival** — the Act III prep quest that follows War-Prep II and carries the
 * Lord up to Minister, teaching field survival for when the front collapses.
 * [WARPREP_SURVIVAL_STEP_ATTR] is the current step ordinal of
 * [org.alter.plugins.content.war.warprep.WarPrepSurvival.Step] (0 = not started).
 * [WARPREP_SURVIVAL_DRILL_TOPUPS_ATTR] bounds the anti-soft-lock food top-up on the DRILL step
 * (same rationale as the ranged ammo top-up). Persistent; never re-fires once DONE. Persisted BY
 * ORDINAL — never reorder the Step enum without a migration.
 */
val WARPREP_SURVIVAL_STEP_ATTR = AttributeKey<Int>("warprep_survival_step")
val WARPREP_SURVIVAL_DRILL_TOPUPS_ATTR = AttributeKey<Int>("warprep_survival_drill_topups")

/**
 * A flag which indicates that the player will not take collision into account
 * when walking.
 */
val NO_CLIP_ATTR = AttributeKey<Boolean>()

/**
 * A flag that indicates whether or not this player has protect-item
 * prayer active.
 */
val PROTECT_ITEM_ATTR = AttributeKey<Boolean>()

/**
 * Marks the player's deaths as designed-SAFE (no item drop) while set — for minigame/boss
 * content that teleports players in and out (set on entry, clear on exit). Fixed-world safe
 * arenas should register a zone in the plugins' SafeDeaths allowlist instead.
 */
val SAFE_DEATH_ATTR = AttributeKey<Boolean>()

/**
 * The display mode that the player has submitted as a message.
 */
val DISPLAY_MODE_CHANGE_ATTR = AttributeKey<Int>()

/**
 * The distance a [Pawm] keeps facing their [FACING_PAWN_ATTR].
 */
val RESET_FACING_PAWN_DISTANCE_ATTR = AttributeKey<Int>()

/**
 * The [Pawn] which another pawn is facing.
 */
val FACING_PAWN_ATTR = AttributeKey<WeakReference<Pawn>>()

/**
 * An [Npc] that has us as their [FACING_PAWN_ATTR].
 */
val NPC_FACING_US_ATTR = AttributeKey<WeakReference<Npc>>()

/**
 * The current viewed shop.
 */
val CURRENT_SHOP_ATTR = AttributeKey<Shop>()

/**
 * The [Pawn] which another pawn wants to initiate combat with, whether they meet
 * the criteria to attack or not (including being in attack range).
 */
val COMBAT_TARGET_FOCUS_ATTR = AttributeKey<WeakReference<Pawn>>()

/**
 * The [Pawn] that killed another pawn.
 */
val KILLER_ATTR = AttributeKey<WeakReference<Pawn>>()

/**
 * The last [Pawn] that the owner of this attribute has hit.
 */
val LAST_HIT_ATTR = AttributeKey<WeakReference<Pawn>>()

/**
 * The last [Pawn] who has hit the owner of this attribute.
 */
val LAST_HIT_BY_ATTR = AttributeKey<WeakReference<Pawn>>()

/**
 * The amount of "poison ticks" left before the poison wears off.
 */
val POISON_TICKS_LEFT_ATTR = AttributeKey<Int>(persistenceKey = "poison_ticks_left", resetOnDeath = true)

/**
 * The damage venom will deal on its next proc (6 at onset, +2 per proc, capped at 20).
 * A value > 0 means the pawn is envenomed; venom shares the poison timer channel.
 */
val VENOM_DAMAGE_ATTR = AttributeKey<Int>(persistenceKey = "venom_damage", resetOnDeath = true)

/**
 * Reflected-damage points left on the currently worn ring of recoil (40 when fresh).
 * Cleared when the ring shatters. Persists across sessions like the ring itself.
 */
val RECOIL_DAMAGE_LEFT_ATTR = AttributeKey<Int>(persistenceKey = "recoil_damage_left")

/**
 * The amount of antifire potion charges left.
 */
val ANTIFIRE_POTION_CHARGES_ATTR = AttributeKey<Int>(persistenceKey = "antifire_potion_charges", resetOnDeath = true)

/**
 * If full dragonfire immunity is enabled.
 */
val DRAGONFIRE_IMMUNITY_ATTR = AttributeKey<Boolean>(persistenceKey = "dragonfire_immunity", resetOnDeath = true)

/**
 * The command that the player has submitted to the server using the '::' prefix.
 */
val COMMAND_ATTR = AttributeKey<String>()

/**
 * The arguments to the last command that was submitted by the player. This does
 * not include the command itself, if you want the command itself, use the
 * [COMMAND_ATTR] attribute.
 */
val COMMAND_ARGS_ATTR = AttributeKey<Array<String>>()

/**
 * The option that was last selected on any entity message.
 * For example: object action one will set this attribute to [1].
 */
val INTERACTING_OPT_ATTR = AttributeKey<Int>()

/**
 * The slot that was last selected on any entity message.
 */
val INTERACTING_SLOT_ATTR = AttributeKey<Int>()

/**
 * The [GroundItem] that was last clicked on.
 */
val INTERACTING_GROUNDITEM_ATTR = AttributeKey<WeakReference<GroundItem>>()

/**
 * The last [ItemTransaction] to occur when a ground item is picked up
 * from the ground.
 */
val GROUNDITEM_PICKUP_TRANSACTION = AttributeKey<WeakReference<ItemTransaction>>()

/**
 * The [GameObject] that was last clicked on.
 */
val INTERACTING_OBJ_ATTR = AttributeKey<WeakReference<out GameObject>>()

/**
 * The [Npc] that was last clicked on.
 */
val INTERACTING_NPC_ATTR = AttributeKey<WeakReference<Npc>>()

/**
 * The [Player] that was last clicked on.
 */
val INTERACTING_PLAYER_ATTR = AttributeKey<WeakReference<Player>>()

/**
 * The slot of the interacting item in its item container.
 */
val INTERACTING_ITEM_SLOT = AttributeKey<Int>()

/**
 * The id of the interacting item.
 */
val INTERACTING_ITEM_ID = AttributeKey<Int>()

/**
 * The item pointer of the interacting item.
 */
val INTERACTING_ITEM = AttributeKey<WeakReference<Item>>()

/**
 * The slot index of any 'secondary' item being interacted with.
 */
val OTHER_ITEM_SLOT_ATTR = AttributeKey<Int>()

/**
 * The item id of any 'secondary' item being interacted with.
 */
val OTHER_ITEM_ID_ATTR = AttributeKey<Int>()

/**
 * The item pointer of any 'secondary' item being interacted with.
 */
val OTHER_ITEM_ATTR = AttributeKey<WeakReference<Item>>()

/**
 * Interacting interface parent id.
 */
val INTERACTING_COMPONENT_PARENT = AttributeKey<Int>()

/**
 * Interacting interface child id.
 */
val INTERACTING_COMPONENT_CHILD = AttributeKey<Int>()

/**
 * The skill id of the latest level up.
 */
val LEVEL_UP_SKILL_ID = AttributeKey<Int>()

/**
 * The amount of levels that have incremented in a skill level up.
 */
val LEVEL_UP_INCREMENT = AttributeKey<Int>()

/**
 * The previous skill XP of the latest level up.
 */
val LEVEL_UP_OLD_XP = AttributeKey<Double>()

val CHANGE_LOGGING = AttributeKey<Boolean>()

/**
 * Per-player opt-in for the developer **chatbox debug diagnostics** — the "Unhandled item/button/
 * object action", "Undefined spell", examine-id suffixes and similar messages that used to be
 * gated only by the server-wide `dev-settings` flags. Those diagnostics are now OFF for everyone by
 * default; an admin flips this on for their own session with `::debug` when they need to identify an
 * unwired component/item/spell. Session-only (no persistenceKey) so it never sticks past a relog and
 * can never leak to ordinary players. See [org.alter.game.model.entity.showChatboxDebug].
 */
val DEBUG_CHATBOX_ATTR = AttributeKey<Boolean>()

/**
 * Instead of running tp
 */
val CLIENT_KEY_COMBINATION = AttributeKey<Int>()

/**
 * A JSON blob holding the player's REAL inventory + equipment + spellbook + boosted-skill state,
 * stashed when they take a loaner kit in the PK Training Arena (content/minigames/pktraining).
 * Persistent by design: if the JVM dies while a player is kitted in loaner gear, their real gear is
 * still on disk and is restored on the next login (the plugin's onLogin recovery). Cleared the
 * moment the loaner kit is handed back. Present == "this player currently owes a real-gear restore".
 */
val PK_ARENA_STASH_ATTR = AttributeKey<String>("pk_arena_stash")

/**
 * A JSON blob of the player's pre-boost combat CURRENT levels, written when a companion-sparring
 * bout's **Max Stats** option boosts them to 99 while they fight in their OWN gear (a loaner-kit
 * fighter's levels ride [PK_ARENA_STASH_ATTR] instead — this key exists precisely for the
 * no-stash path). Persistent by design: a JVM death mid-bout leaves the snapshot on disk and the
 * levels are restored on next login. Cleared the moment the bout ends. Boosts are current-level
 * only — base levels/XP are never touched.
 */
val SPAR_BOOST_ATTR = AttributeKey<String>("spar_boost")

/**
 * A JSON blob holding the items a player has escrowed into an **active Duel Arena stake** (their own
 * stake). Written when a duel begins (items already moved out of the inventory into escrow), cleared
 * when the duel resolves. Persistent by design: if the JVM dies mid-duel the stake is on disk and is
 * refunded to its owner on next login (the duel is voided) — a crash can never eat staked wealth.
 */
val DUEL_ESCROW_ATTR = AttributeKey<String>("duel_escrow")

/**
 * A JSON blob holding the player's saved **kit loadouts** (content/kits) — up to three named setups
 * of worn gear + inventory + spellbook, built in the kit editor. Loaded as loaner gear in the PK
 * Training Arena, or withdrawn from the player's own bank anywhere else. Persistent: kits survive
 * logout like bank contents do.
 */
val KIT_LOADOUTS_ATTR = AttributeKey<String>("kit_loadouts")

/**
 * A JSON blob holding the player's REAL inventory + equipment + spellbook + Magic level, stashed
 * while they fight a match in the **Automatic Tournament** (content/minigames/tournament) — matches
 * are fought in a standardized loaner kit. Persistent by design (mirrors [PK_ARENA_STASH_ATTR]):
 * a crash mid-match restores the real gear on next login. Present == "a restore is owed".
 */
val TOURNEY_STASH_ATTR = AttributeKey<String>("tourney_stash")

/**
 * Marks a [org.alter.game.model.entity.Player] bot as a **sparring partner** owned by the PK
 * Training Arena. The bot-combat plugin skips its normal "drop the full kit to the killer + despawn"
 * death handling for these — the arena owns their lifecycle and they must never faucet gear into the
 * economy (a trainee kills them for free, repeatedly). Transient (bots are never persisted).
 */
val SPAR_BOT_ATTR = AttributeKey<Boolean>()

/**
 * A JSON blob holding the player's REAL inventory + equipment + spellbook, stashed when they enter a
 * **Last Man Standing** game (content/minigames/lms) — LMS strips your own gear and hands you a starter
 * kit + chest loot for the round. Persistent by design (mirrors [PK_ARENA_STASH_ATTR] but is a SEPARATE
 * key so LMS and the PK Training Arena never clobber each other): a JVM death mid-game leaves the real
 * gear on disk, restored on the next login. Cleared the moment the player leaves the game.
 */
val LMS_STASH_ATTR = AttributeKey<String>("lms_stash")

/**
 * Marks a [org.alter.game.model.entity.Player] bot as a **Last Man Standing competitor**. The bot-combat
 * plugin skips its normal "drop the full kit to the killer + despawn" death handling for these — the LMS
 * engine owns their lifecycle and their round loot. Transient (bots are never persisted). Same exemption
 * shape as [SPAR_BOT_ATTR].
 */
val LMS_BOT_ATTR = AttributeKey<Boolean>()

/**
 * Persistent per-player **Last Man Standing points** counter, earned for kills and wins and spent at the
 * LMS reward shop (via [org.alter.plugins.content.economy.PointsCurrency]). An untappable counter (not a
 * tradeable item), matching how OSRS LMS points work.
 */
val LMS_POINTS_ATTR = AttributeKey<Int>("lms_points")

/**
 * A JSON blob holding a **Castle Wars** player's real cape-slot item + prior respawn tile, stashed when
 * they enter a game (the arena force-equips a team Hooded cloak and re-points the death respawn at their
 * castle's spawn room). Persistent by design (the [LMS_STASH_ATTR] crash-safety pattern, own key): a JVM
 * death mid-game restores the cape + respawn on the next login. Cleared the moment the player leaves.
 */
val CW_STASH_ATTR = AttributeKey<String>("cw_stash")

/**
 * Marks a [org.alter.game.model.entity.Player] bot as a **Castle Wars filler competitor**. The bot-combat
 * plugin skips its "drop the full kit to the killer + despawn" death handling for these — Castle Wars
 * bots respawn endlessly during a game, so the normal death faucet would print PK kits on repeat. Same
 * exemption shape as [SPAR_BOT_ATTR] / [LMS_BOT_ATTR]. Transient (bots are never persisted).
 */
val CW_BOT_ATTR = AttributeKey<Boolean>()

/** Persistent per-player Castle Wars career **wins** counter (shown at the lobby scoreboard). */
val CW_WINS_ATTR = AttributeKey<Int>("cw_wins")

/** Persistent per-player Castle Wars career **flag captures** counter (shown at the lobby scoreboard). */
val CW_CAPTURES_ATTR = AttributeKey<Int>("cw_captures")

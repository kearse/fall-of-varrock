# PvP & Combat Activities — the Team 5 charter doc

> Current as of 2026-09-03. Design authority = the six `0N_*.docx` of 2026-09-02 (05 §5-8 for PvP,
> 04 §10-11 for Blood Money, 03 §3-5 for the war / companion rules). Where this doc and the
> code disagree, the code is the inventory and the docx is the direction.

**Mission:** make PvP approachable enough that someone can learn it, profitable enough that someone
can main it, and deep enough that experienced PKers stay.

## 1. Ownership map

| Team | Owns (files) | Seams the others call |
|---|---|---|
| **Team 5 — PvP** | `content/combat/**` (PvpZones, Combat.canEngage / applySkull, PvpDeathDropPlugin, DeathRisk, SafeDeaths, WildernessOverlayPlugin), `content/bots/**` (PK bots, knights/, BotDuel), companion **PvP behaviour** (`CompanionBrain` FOLLOW/hunt rules, the companion branches of `canEngage`), `content/economy/pk/{PkKillGuard,PkGuardPlugin,PkStatsPlugin,LootKeyPlugin,LootChestInterface}` + the Blood Money **kill formula** in `PkRewardsPlugin`, `content/war/roguehunt/**`, `content/hostilezones/**`, `content/areas/portsarim/PortSiegePlugin` | `PkKillGuard.verdictFor(world, victim)` (any consumer paying for a player kill), `HostileZones.all` (zoning), `RogueRewards` (War Effort amounts), `BotDuel` |
| **Team 1 — core / war** | `content/war/**` (Frontiers, CityFrontiers, CityFrontierPlugin, MarchTargets, CampaignDirector, events/), `CompanionRegistry` / `CompanionPolicy` / `RecruitMenu`, quests framework, `economy/Currencies.kt`, `content/core/*Api` | `p.addPoints(WAR_EFFORT, n)` / `WarEffortApi.add`, `MarchTargets.register`, `WarHooks.onOperationEnded`, `CompanionPolicy.register / denyArea`, `CompanionRegistry.ACTIVE_MAX` (read it, never assume) |
| **Team 2 — economy** | Blood Money **prices / stock** (`PkRewardStock`), shops / GE / Trading Post / alch, `ItemMarketValueService` (the ONE item-value source), drop config multipliers | any new reward VALUE goes to them as a proposal first |

Boundary that matters most: **Team 2 owns what Blood Money buys; Team 5 owns how it is earned.**

## 2. Systems inventory

| System | Where | One line |
|---|---|---|
| PvP zoning | `combat/PvpZones.kt` | Red = the custom wilderness (`mainWilderness` south edge at the top of Lumbridge) + pockets + hostile zones, minus safe carve-outs; single vs multi boxes; level = depth/8 (+1), pockets south of the line = any level, frontier bands capped at 10, hostile zones fixed-or-depth. `::zone` reads a tile. |
| Engagement | `combat/Combat.kt` `canEngage` | Wilderness both sides, level bracket `cb ± level`, single-combat + 20-tick PJ timer (PK bots / companions never shield you), Rogue camp gate, bots attackable anywhere, companions PvE-only (§4). |
| Skull | `Combat.applySkull` | White skull 2000 ticks on an unprovoked attack on a human in the wild; retaliation window 100 ticks; varbit 13131 opt-out; bots never skull. |
| Death | `combat/PvpDeathDropPlugin` + `combat/DeathRisk` | Keep-N everywhere (3 / 4 with Protect Item / 0 skulled / 1), untradeables kept free, loot keys (`economy/pk/LootKeyPlugin`) for any real-player kill, safe-zone reclaim piles, `SafeDeaths` for arenas. `DeathRisk.plan` is the ONE keep-N computation. |
| Blood Money + Elo | `economy/pk/PkRewardsPlugin` (25 + 3×cb, `BM_BASE` / `BM_PER_LEVEL`), `PkStatsPlugin` (Elo K=32, varps 4602-4605, hiscores) | Both gated by `PkKillGuard` (§3). Bots never mint or pay. |
| PK bots | `bots/` — `BotZones` (grid over the wild + pinned camps), `BotColony`, `BotBrain` (NH brain: eat, pray-react, switch off the overhead, spec combos, baits, PID model — `docs/pk-bot-fight-styles.md`), `Loadouts` (29), `PkLootPools` | Real `Player`s, wilderness-only aggro, named "Rogue Knight", full kit into the killer's loot key. |
| Rogue Knights | `bots/knights/` (RogueKnights, RogueKnightLadder, CampClearance, RogueKnightCampPlugin, RogueRewards), `war/roguehunt/` | 7 camps, 14 named bosses, per-hunter instances, camp clearance gate; OPTIONAL — quest path or direct challenge (`::knights challenge`); War Effort per gate kill / camp clear / first kill / capped repeats. |
| Hostile Zones | `hostilezones/` — `docs/hostile-zones.md` | The extraction loop as data: zoning, loot spots, supply drop, occupier garrison, raider colony, channelled trapdoor extraction. First zone live: the Wild Bandit Stronghold. |
| Port Sarim siege | `areas/portsarim/PortSiegePlugin` | Rogue raiders (`npc.bandit_737`) vs dock knights; raider kills count for the rogue tally, the quest hunt and the port camp gate. |
| Test harness | `bots/BotDuel` + `::botduel` | Bot-vs-bot bouts and the round-robin win matrix (§6). |

## 3. Anti-farm rules (how Blood Money is earned)

`economy/pk/PkKillGuard` decides ONCE per death whether a player kill pays Blood Money and moves
Elo. Rules in order, all thresholds `TUNE` consts:

1. `SELF` / `BOT_VICTIM` / `NOT_HUMAN` / `SAFE_ZONE` — only a human killing a human in the wild
   (always on; a companion's killing blow already resolves to its owner).
2. `SAME_IP` — both accounts on one login address (`Client.remoteIp`).
3. `REPEAT_VICTIM` — same victim within 30 min. `PAIR_CAP` — 3 paying kills per pair per 24 h,
   counted in both directions (kill trading is one pair).
4. `VICTIM_CASHOUT_CAP` — a victim funds ≤ 10 payouts/day. `KILLER_DAILY_CAP` — a killer mints
   from ≤ 20 kills/day (≈ 8k BM/day at level-126 victims — Team 2 prices the shelves off this).
5. `LOW_RISK` — the victim must have risked ≥ 20,000 gp of tradeables (`DeathRisk.riskedValue`,
   kept items excluded, unclaimed loot-key contents included, snapshot BEFORE the drop strips them).
6. `FRESH_ACCOUNT` — victim account younger than 24 h (`ACCOUNT_CREATED_AT_ATTR`).

A denial only zeroes the payout — the fight, the skull, the death drop and the loot key are
untouched. Every human-vs-human death writes a `pk-audit` log line; `::pkaudit <name>` (admin)
shows a player's ledger, `::pkguard` toggles rules at runtime, `::pktest <name>` dry-runs.

Other no-mint rules: PK bots never mint or pay; killing a companion pays nothing; Rogue Knight
War Effort is capped per knight per day and in total; extraction mints nothing.

## 4. Companions in PvP (operator decision 2026-09-02)

**PvE-only.** A companion never attacks a real player (not even in its owner's defence) and a
real player can never attack one; while the owner exchanges blows with a human, every companion
stands back in formation (`CompanionBrain.holdForOwnersFight`, held for the PJ window after the
last exchange) and does not count for single-combat. Companions still fight PK bots, Rogue
Knights and NPCs everywhere in the wild. Rule is count-agnostic — read
`CompanionRegistry.ACTIVE_MAX`, never assume how many a player fields.

## 5. Hostile Zones — summary

See `docs/hostile-zones.md`. Kinds: wilderness fort, frontier, rogue stronghold, fallen
settlement, Varrock pocket. A zone = one data entry; the framework gives it zoning, loot spots,
the supply drop, a garrison, an optional raider colony and channelled extraction points. Economy
band (Team 2): ~600–900k cache-value/hour for a solo camper at full uptime.

## 6. PvP combat testing — `::botduel`

- `::botduel <loadoutA> <loadoutB> [rounds]` — spawn the pair beside you (safe ground is fine;
  bot-vs-bot is allowed anywhere and no colony musters there) and fight to the death with the full
  NH brain. Each bout logs `[BOTDUEL] … winner ticks dmg food specs prayswaps swaps baits` and
  messages you.
- `::botduel all [rounds]` — round-robin every loadout pair (406 pairs), 6 lanes at once, then a
  `[BOTDUEL-MATRIX]` W/L/D + win-rate table in the server log (≈ 100 min for one round each).
- `::botduel status` / `::botduel stop`; `::clearbots` also ends a run.
- Bouts over 600 ticks are draws. Duelists drop nothing and credit nothing.

**Regression expectations:** the metal ladder is monotonic (bronze < iron < steel < black <
mithril < adamant < rune < dragon), `elite_nh` in the top three, no loadout draws every bout
(that means it cannot hit — a broken strategy). Re-run after any formula / strategy / brain
change and diff the matrix against the last one.

## 7. Future — NOT to be built yet (design authority 06 §5)

- **PvP Academy / Training Arena** (05 §6, handoff §9 — "the arena teaches, Rogue Knights test,
  real players prove it"): an Edgeville trainer / portal that teleports the player into controlled
  drills, one lesson per skill (eating, protection prayers, offensive prayers, switching, specs;
  then combo eating, freezes / movement, multi-way switching, NH fundamentals; then tick timing,
  fakies / spec prediction, PID, advanced NH), each recommending a Rogue Knight that uses that skill
  in real combat. Seams already in the code: `RogueKnights.LADDER` rank order + per-hunter
  instances (`RogueKnightCampPlugin` "PVP-TRAINING SEAM"), `PkBot.loadoutOverride`,
  `BotDuel` for scripted opponents, `FightProfile` per loadout. Veterans skip lessons — already
  true for the ladder (`::knights challenge`).
- **Bounty Hunter** (06 §9): `TargetMarker.PRIORITY_BOUNTY` is reserved; the kill guard's pair
  ledger and the `pk-audit` line are the data a bounty system would read.
- Deferred with Team 1: March targets for the Wild Bandit Camp and the Rogue Commander's Redoubt
  (`MarchTargets.register`, ids `npc.bandit_734` / `npc.bandit_12663` reserved).
- Edgeville as a `FALLEN_SETTLEMENT` hostile zone — delta in `docs/hostile-zones.md`; operator
  decision required (it conflicts with the Edgeville academy hub).

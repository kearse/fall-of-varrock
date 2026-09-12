# PvP & Combat Activities — the Team 5 charter doc

> Current as of 2026-09-12. Design authority = the six `0N_*.docx` of 2026-09-02 (05 §5-8 for PvP,
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
| PvP zoning | `combat/PvpZones.kt` (+ `Wilderness` in game-api `TileExt.kt`) | **Where HUMANS may fight.** Red = the real OSRS wilderness (`Wilderness.SURFACE`, north of the Edgeville ditch) + the boss lairs + the Fallen Varrock pocket (`VARROCK_POCKET`, flat level 20, single) + hostile zones, minus carve-outs (GE / Varrock banks / Ferox / auto bank radii). Level = OSRS `((z-3520)/8)+1`; lairs, pockets and hostile zones are fixed. Single by default, `MULTI` boxes are the exception. `canTeleport` reads the same level. Everything else is safe FROM PLAYERS only — see Rogue Knights. `::zone` reads a tile. |
| Engagement | `combat/Combat.kt` `canEngage` | Wilderness both sides, level bracket `cb ± level`, single-combat + 20-tick PJ timer (PK bots / companions never shield you), Rogue camp gate, bots attackable anywhere and attacking anywhere except a `RogueTerritory.sanctuary` player (onboarding / locked / instanced / sanctuary tile — checked every cycle), companions PvE-only (§4). |
| Skull | `Combat.applySkull` | White skull 2000 ticks on an unprovoked attack on a human in the wild; retaliation window 100 ticks; varbit 13131 opt-out; bots never skull. |
| Death | `combat/PvpDeathDropPlugin` + `combat/DeathRisk` | Keep-N everywhere (3 / 4 with Protect Item / 0 skulled / 1), untradeables kept free, loot keys (`economy/pk/LootKeyPlugin`) for any real-player kill, safe-zone reclaim piles, `SafeDeaths` for arenas. `DeathRisk.plan` is the ONE keep-N computation. |
| Blood Money + Elo | `economy/pk/PkRewardsPlugin` (25 + 3×cb, `BM_BASE` / `BM_PER_LEVEL`, `bloodMoneyFor`), `PkStatsPlugin` (Elo K=32, varps 4602-4605, hiscores) | Both gated by `PkKillGuard` (§3) for human kills. A slain BOT is paid separately by `bots/RogueBounty` (half the formula, named knights ×2, no cap, no guard — operator 2026-09-12); bots never earn. |
| PK bots | `bots/` — `RogueTerritory` (the knights' own law: muster / hunt / danger), `BotZones` (two grids: `wild_*` over the OSRS box, `land_*` over `RogueTerritory.MAINLAND`, + pinned camps), `BotColony`, `BotBrain` (NH brain: eat, pray-react, switch off the overhead, spec combos, baits, PID model — `docs/pk-bot-fight-styles.md`), `Loadouts` (29), `PkLootPools`, `RogueBounty` | Real `Player`s named "Rogue Knight" that **hunt the whole mainland, cities included**: grid knights muster outside the `CITY_CORES` and chase you in; tier = OSRS depth in the wild, distance from the nearest safe city on the mainland (`dangerLevel`, elites deep-wild only); 1v1 outside the wild; never a sanctuary player, never across floors, no unprovoked aggro during the post-death (100t) / post-login (50t) truce or inside a bank radius. Death of a knight = Blood Money bounty (`RogueBounty`) to the killer's inventory + `PkLootPools` rare rolls into the killer's loot key; the worn kit NEVER drops (2026-09-12). Death to a knight off the wild = the normal reclaim pile. |
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

Other rules: PK bots never EARN Blood Money (a bot's kill of a human pays nothing), but a human's
kill of a bot IS paid — outside this guard, by `bots/RogueBounty`: half the player formula, named
ladder knights double, uncapped (operator decision 2026-09-12; the worn kit no longer drops at
all). That includes a hostile zone's `hz_` raider bots. Killing a companion pays nothing; Rogue
Knight War Effort is capped per knight per day and in total; extraction itself mints nothing.

## 4. Companions in PvP (operator decision 2026-09-02)

**PvE-only.** A companion never attacks a real player (not even in its owner's defence) and a
real player can never attack one; while the owner exchanges blows with a human, every companion
stands back in formation (`CompanionBrain.holdForOwnersFight`, held for the PJ window after the
last exchange) and does not count for single-combat. Companions still fight PK bots, Rogue
Knights and NPCs everywhere — wild or mainland (they will brawl a knight at the city gates). Rule is count-agnostic — read
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
- Edgeville as a `FALLEN_SETTLEMENT` hostile zone — delta in `docs/hostile-zones.md`; superseded
  2026-09-12: with the OSRS line at the ditch, Edgeville is the PvP staging town OUTSIDE the red by
  geometry (and a `RogueTerritory` city core — no knight muster). The academy-hub direction stands.
- **Edgeville portal row + corridor relabel** (`content/teleport/TeleportRegistry.kt`): there is no
  Edgeville row, and the six "Wilderness / PvP" corridor rows (Outlaw Camp … ) now land on safe
  mainland with stale "Wild Lvl N" labels. Any registry change must mirror
  `client/.../lofteleports/LofTeleportsData.java` + a client deploy → separate PR. Until then: the
  Amulet of Glory (Edgeville 3087,3496), walking, and the stronghold extraction exit.
- **City-interior guards vs Rogue Knights**: generic NPC aggro ignores clientless players
  (`lastMapBuildTime` never set), so Falador / Al Kharid guards don't react to a knight. Would need
  a `HostileZone.defendCitizens`-style sweep. Follow-up.

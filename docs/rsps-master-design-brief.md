# Kingdom of Lumbridge — Master Design Brief
### War-led identity: First 10 Minutes → War Effort loop → Feudal endgame → Monetization

**What this is.** An architecture/design spec authored as the "mastermind" brief. A separate
implementation agent will build from it. It is self-contained — you should not need the
conversation that produced it.

**How to read it.**
- **Locked** = a decision the owner has made; do not change it without checking in.
- **Open** = a genuinely deferred tuning choice; flag it, pick a sensible default, note it.
- Code paths (e.g. `content/war/WarState.kt`) are **verified starting points** — confirm the
  exact symbol before editing; the codebase moves.
- Core thesis that everything must preserve: **play buys power and standing; money buys time
  and flair.** Never let cash touch the earned ladder.

---

## Table of Contents
0. World model & core identity (the frame)
1. The First 10 Minutes ("Recruit Trials")
2. Slayer becomes War Contracts
3. Skilling supplies the war (War Effort currency + item sink)
4. Rank/title wired into everything
5. Feudal command & the throne
6. Monetization
7. Build order
8. Critical files & hooks
9. Verification
10. Open tuning questions

---

## 0. World model & core identity (Locked)

- **Lumbridge = the one player nation.** ALL players are citizens of Lumbridge. It holds the
  King, the court, and the election. **One crown for the whole realm** — no rival player
  kingdoms (keeps a small population unified and co-operative rather than fragmented).
- **Falador = a second SAFE hub, but a NEUTRAL country** — neutral NPCs, not a raid target,
  not player-owned. (Room later for neutral-faction trade/content.)
- **NPC enemy nations = the raid/conquest targets:** Varrock, Al Kharid, Ardougne, Yanille,
  Gnome Stronghold, Burthorpe, Rimmington, Draynor, etc. NPC-held; the Lumbridge army marches
  on them and players loot the NPCs they kill + items that spawn on the ground. The spread of
  distance/strength is a natural **campaign difficulty curve** (Draynor/Al Kharid close & easy
  → Ardougne/Yanille/Gnome far & hard; bigger quota, bigger payout).
- The "war" is **co-op PvE conquest** (Lumbridge vs NPC nations), NOT city-vs-city player war.
  PvP lives in the **wilderness and the contested roads** (PK bots + real players).

**The current battle system** (`content/war/` has TWO systems — both relevant):
- **Always-on frontier ring** (`CityFrontiers.kt` / `CityFrontierPlugin.kt`) — **LIVE**. A
  persistent goblin/knight brawl right outside the Lumbridge gate (~220 goblins at the edge,
  hobgoblins/ogres deeper, knights overlapping). This is the **on-demand combat** a new player
  can always fight.
- **The raid/siege** (`Sieges.kt` / `AttackDirector.kt`) — **intentionally simplified to ONE
  prong**: a goblin camp east of the river (3256,3249) marches west over the south bridge
  (deck z3225–3226) into the castle to reach General Zo. Small rosters (Probe 10 / Raid 18 /
  Siege 25 + Warlord), 8-knight garrison, fires every ~18–54 min.
- **Locked: lean now, expand post-launch.** Build around the single south-bridge prong; keep
  everything **front-agnostic** (data-driven — a new front = another `SiegeConfig` in
  `Sieges.all`, no code change). Re-expanding to multiple fronts / conquest targets is a
  post-launch content drop + dev-blog reveal.

---

## 1. The First 10 Minutes ("Recruit Trials")  — *highest priority; the #1 goal*

Problem today: a fresh account spawns at Lumbridge (3218,3218) with a skilling-oriented
starter kit and **no tutorial, welcome, or guidance** (`StarterKitPlugin.kt`;
`LoginAppearancePlugin.kt` has its intro commented out). The war is 20 tiles away and nothing
surfaces it. Land the hook fast: *"There's a war. You're Lumbridge's newest citizen.
Everything you do feeds it, and your rank rises as you help."*

**1A. Combat-ready starter kit** — `content/mechanics/starter/StarterKitPlugin.kt` (already
gated on `NEW_ACCOUNT_ATTR`). Keep the tools but add and **auto-equip a basic weapon** (bronze
scimitar/sword) + ~10 food so the player can immediately fight frontier goblins. The first kit
should say "fighter," not "lumberjack."

**1B. Welcome framing on first login** *(Locked: dialogue first, custom if3 later)* — hook the
login path (`world.plugins.executeLogin`, fired from `Player.login()`), gated on
`NEW_ACCOUNT_ATTR`. Ship a short NPC **dialogue** welcome at launch (zero cache work);
upgrade to a polished **custom if3** panel (crest, war framing, rank ladder — via the existing
siege-bar if3 pipeline) post-launch. Content: the war, your citizenship (`CitizenshipPlugin`
already assigns Lumbridge), your goal (rise from Peasant).
> if3 = the modern RS interface format; "custom if3" = a bespoke UI authored into the cache
> (displee + if3 codec), driven by the server via interface + varp/varbit packets — the same
> pipeline used for the existing War HUD / siege bar (`WarHudPlugin`).

**1C. "Recruiting Sergeant" NPC + objective chain** — new NPC near 3218,3218 that hails the
player and drives a 4-step **Recruit Trials** chain (reuse existing dialogue/quest patterns).
Each trial funnels into one live system and plants the three pillars:
1. **FIGHT** — "Cull 5 goblins at the frontier." → the always-on `CityFrontierPlugin` ring
   (close, low-level, knights fighting alongside = great first impression). Combat is always
   available on demand; does NOT depend on a raid firing.
2. **RANK** — "Report to Duke Horacio." → teaches the ladder (`DukeHoracioPlugin`, `Title.kt`):
   coins → Commoner (10k) → … → Knight. Can't afford yet → motivation.
3. **SLAY** — "Take a war-contract from Vannaka." → Slayer as war objectives (§2).
4. **SUPPLY** — "Bring one supply to the Quartermaster" (or train one skill at the swamp hub).
   → the supply loop (§3).
Completing the chain grants a starter reward + the player's first **War Effort** points, so
they leave onboarding already on the ladder.

**1D. Objective tracker (lightweight)** — a small on-screen checklist for the trials
(varp-driven HUD or quest-style interface). Seeds the future **achievement-diary** system. Add
a minimap hint/flag to the frontier for Trial 1.

---

## 2. Slayer becomes War Contracts (bidirectional)

Reframe Slayer from generic grinding into war objectives, and make the war need what you slay.
- **Reframe + retarget tasks** (`SlayerTasks.kt`, 11 starter tasks, data-only): war-flavored
  display strings ("Bandits block the north supply road — clear 30"); targets biased to
  frontier/war NPCs (goblins, hobgoblins, ogres) + nearby beasts, so tasks route the player
  through live war content (a Slayer-contract-hub feel). Add difficulty tiers (Turael/Mazchna/
  Nieve masters are stubbed; or keep Vannaka with easy/medium/hard tiers first to avoid cache
  NPC-option work).
- **Dual reward** *(Locked)*: completion grants Slayer points (as today) **plus War Effort**.
  Hook the completion path in `SlayerPlugin.kt` (`onAnyNpcDeath` → decrement → reward block).
- **Rank-gated contracts** *(Locked)*: the hard "clear the supply road" tiers require higher
  feudal rank — interlocks Slayer + rank + war.
- **Optional (higher effort):** tasks that reference live war state and whose completion nudges
  the relevant frontier line / `WarState`.

---

## 3. Skilling supplies the war (item sink) + War Effort currency  *(Locked: full loop at launch)*

Makes "skilling serves the war" literal and fixes the economy's missing sink (`docs/economy.md`
flags *"combat consumables burn — partial, no supply skills yet"*).

**3A. New currency: War Effort** — add `WAR_EFFORT` to `enum class PointKind`
(`content/economy/Currencies.kt`) + persistent `WAR_EFFORT_POINTS_ATTR` (`Attributes.kt`); the
Slayer/Boss/Vote model is the pattern. **Earned** by all three pillars: frontier/raid kills
(extend `WarEffortPlugin` / `WarParticipation`), Slayer war-contracts (§2), supply contributions
(below). **Spent on** a war supply shop, rank-up discounts, war cosmetics, and city-level
benefits (reinforcements / faster recovery after a fall).

**3B. Quartermaster supply sink** — new Quartermaster NPC that **accepts crafted goods** (cooked
food, finished potions, ammo, bars) and grants War Effort per unit — a true **item sink**. The
**Swamp Skilling Hub** (launch plan) produces the inputs end-to-end: woodcutting→fletching→
arrows, farming→herblore→potions, fishing→cooking→food, mining→smithing→bars. (Shop potions are
already sold UNFINISHED behind a Herblore gate, so the chain is half-wired.)

**3C. The payoff — the war consumes supplies** *(Locked: stakes meaningful but recoverable)* —
give each city a **supply/readiness** metric (reuse/sit alongside `WarState`'s persisted knight
pool). At launch it drives the **south-bridge garrison** (`Sieges.LUMBRIDGE.knightPoolMax`,
currently 8 — deliberately too small to hold 25 goblins): supply raises the garrison ceiling/
replenish; neglect leaves the 8 to lose and goblins leak to Zo. A well-supplied city defends;
a neglected one falls more often. **On a fall**, shops close and the bank seals (`WarServices`
already does this), then it **recovers** — real urgency without permanently punishing casuals
(tune fall/recovery windows short). Keep the metric **per-front/per-city** so it scales when
fronts expand. Loop closed: *skilling → supplies → city survives → players keep hub/bank → keep
playing.*

---

## 4. Rank/title wired into everything ("a Lord should FEEL like a Lord")

**Design principle:** feudal titles are **earned political/military power and status, NOT a
pay-gear vendor** (coins + War Effort). A **separate ladder from donor ranks** (§6). Never
pay-to-win.

**Already wired (reinforce, don't rebuild):** armour-tier equip gate (`TitlePlugin`), war loot
class-gated by rank (`WarDrops`), rank-gated vendor tiers (`ApprenticeArmoury`).

### Launch set (high value, low cost)
1. **Rank-based frontier aggro** *(Locked)* — outrank an enemy line and it ignores you. Goblins
   (line 1) ignore **Squire+**, hobgoblins (line 2) **Soldier+**, ogres (line 3) **Knight+**
   (tunable). Hook: add `aggroFloorRank` to `EnemyLine` and make `HostileZone`'s `aggroCheck`
   lambda rank-aware (`CityFrontiers.kt` / `HostileZone.kt`, ~lines 140–151). **The wilderness
   is unaffected for free** — it uses the engine's standard level-based aggro (different code
   path). Makes ranking up *feel* like power even before gear.
2. **Title name prefix** *(Locked: noble ranks only, color-coded)* — "Lord BizzyZ" over the
   head, in chat, on right-click. **Squire and above** get a prefix; Peasant/Commoner show the
   plain name (so it's an *earned* mark). **Color-coded by rank** (King gold, Lord purple, …).
   Map `Title` enum → `(prefix, color)`; inject at the single encoder hook
   `PlayerInfo.syncAppearance()` line 63 (`info.setName(player.username)` →
   `info.setName(titledName(player))`). Encode the rank color into the name string (RSPS
   name-color convention) so it carries to overhead/chat/right-click without protocol work.
3. **NPC deference by rank** — knights/guards/vendors address you by title; Duke Horacio's
   dialogue shifts as you rise. Cheap (dialogue reads `PLAYER_TITLE_ATTR`), big immersion.
4. **PK-bot aggro by rank/level** *(Locked: the wild ignores rank)* — same "standing" rule, but
   bots are **real Players**, not NPCs, so they don't use the NPC `aggroCheck`. Gate it in the
   bot's **target-acquisition scan** (`BotCombatPlugin` `botAggroTimer` → `BotBrain`): a bot
   skips a candidate who **outranks its threshold AND/OR is outside its combat-level band**
   (loadouts carry `combatLevel`). **Outside the wilderness, rank protects** (a Lord is left
   alone); **inside the wilderness, bots aggro on level alone** — the wild stays the equalizer.

### Post-launch (feudal payoff — also dev-blog hype beats)
5. **War command by rank** — Lord+ gain authority over the war (see §5). Rank as real power.
6. **Wilderness bounty by rank** — killing a higher-ranked player drops more Blood Money (a
   Lord's head is worth more): ties rank to PK risk/reward and gives high rank a real *cost*.
   Hook: PK rewards plugin reads victim title.
7. **Rank insignia / cape cosmetic** — a visible rank cape/banner; monetization-safe status flex
   (could be a launch cosmetic if cheap).

---

## 5. Feudal command & the throne

### Command authority scales with rank (a delegated chain into the AI command layer)
Plugs into existing `Commander` budgets / `WarState` / `AttackDirector`:
- **Knight/soldier** — fights; no command.
- **Lord** — *tactical* command of the home defense: rally knights to a field, set posture
  (hold bridge / push camp / guard Zo), spend War Effort for a reinforcement wave.
- **Minister** — *operational*: city-wide standing orders, pre-position the garrison, set
  defensive policy; acts as **regent** for the King.
- **King** — *strategic*: declare campaigns, set war policy, allocate the War Effort treasury,
  appoint/promote Ministers.

### Offense — two tiers *(Locked)*
- **Lord-led skirmish** — a Lord may launch a small raid party **without the King**, funded by
  **his own War Effort** with a **capped troop budget**. Real agency; the cap + self-funding
  means it can't strip the home garrison.
- **Full campaign** — committing a real army to conquer an NPC nation requires the **King (or
  regent) to declare it**; Lords/Ministers **lead**, citizens **fight AND supply**.
- Rule: **Lords skirmish small; the King declares war; the city supplies it.**

### Campaign economy — declare → price → muster → march → conquer  *(the marquee post-launch system)*
A server-wide collaborative objective gated by a supply quota the whole nation fills — where
"skilling serves the war" becomes literal:
1. **Declare** — the King names a **target NPC city** and **army size** (e.g. "100 troops to
   Varrock").
2. **Price** — the system computes a **per-soldier cost** in materials (food, potions, ammo,
   bars) × troop count = the total **war quota**. Bigger army = bigger quota.
3. **Muster** — citizens gather materials and deposit them to a muster/quartermaster NPC. Fed by
   skilling + the §3 supply loop; a **huge, deliberate resource sink** — the nation *produces*
   its army.
4. **March** — quota met → the **army spawns and marches**, reusing the two-sided war engine
   (`Commander` allocation, waypoints, flow-field pathing), roles flipped: the Lumbridge army is
   the ATTACKER; the **target city's NPC garrison defends**.
5. **Raid & conquer** — en route the army kills monsters and PK bots; at the target it **wipes
   the city's attackable NPCs**; players following loot the kills. (Persistent control/occupation
   — flipping the target's state so it stays "conquered" for a window — is an **Open** later
   layer; the core loop is raid-and-loot.)
6. **Payoff — raids pay big.** Loot from **NPCs killed** (population + defenders + road bots) and
   **ground spawns** through the target. Reward scales with distance/strength — the haul is what
   pulls the whole nation into the gather.

**Economy balance (important):** quota (sink) vs haul (faucet) must be tuned so a campaign is
*net rewarding but not inflationary*. The haul is naturally bounded (finite NPC roster + finite
ground spawns per target), so price the **per-soldier quota** against the **expected haul** per
target — far/strong cities cost more AND pay more. Log every campaign's quota-in vs haul-out to
`docs/economy.md`.

### The throne at a few-hundred-player scale — one realm, a court, rotation
- **A court, not a seat** — King (1) → Ministers (few) → Lords (many) → Knights (most). The
  throne is the singular apex, but **dozens hold real authority** below it, so hundreds have a
  meaningful position to earn. This (not federation) is the scale mechanism.
- **The conquest campaigns ARE the shared endgame** — every citizen participates (gather, supply,
  march, loot), so the war engages the whole population, not just the court.
- **Earned, then ELECTED, not bought** *(Locked)* — Peasant→Lord is the earned ladder;
  King/Minister are limited **seats decided each season by an ELECTION of the nobility** (Lords+
  vote). Most political/social option — alliances, campaigning, a recurring server event.
  - **Candidacy gated by commitment** *(Locked)*: only Lord+ who also meet a **minimum account
    age** AND a **minimum total playtime** may stand. Rewards invested players; blocks
    throwaway/alt candidacies; and because both gates are real-time-based, **cash cannot buy
    eligibility**. (Needs a created-at timestamp at registration + a cumulative playtime counter
    — add if not tracked; playtime can tick on the autosave cadence.)
  - **No turnout quorum** — elections always resolve.
  - **Term limits / re-election each season** so the throne rotates and no one entrenches.
  - **Open:** voting weight — one-noble-one-vote vs weighted by rank (King-makers). Defer.
- **Regency** — King offline → command delegates to the highest-ranking online noble; the war
  never stalls on one person.
- **Bounded, term-limited power** — royal powers are real but survivable: a bad/AFK King is
  routed around by regency and replaced next election.

Net: one realm, one crown, a **rotating elected political layer** that scales via the court and
the shared campaigns. All of §5 is **post-launch** (needs the conquest targets + season/election
system); at launch only the rank ladder + Lord-tier defensive command exist.

---

## 6. Monetization (cosmetic + convenience; NEVER the earned ladder)

Based on `rsps-monetization-plan.md`, adapted to the server-specific risk: we now have an earned
political ladder (feudal rank, War Effort, the throne). If money buys any of it, the identity
collapses into pay-to-win.

### The cardinal rule — two ladders, never crossed
- **Feudal rank** (`PLAYER_TITLE_ATTR`, Peasant→King) = EARNED. Gates armour tiers, war loot,
  aggro-standing, war command, throne eligibility. **Never purchasable.**
- **Donor rank** (`Privilege.DONOR_POWER`, cumulative spend) = PAID cosmetic + convenience.
  **Never** grants feudal authority, armour gates, aggro protection, war command, supplies, War
  Effort, or throne eligibility. A whale who buys top donor still spawns a feudal **Peasant** and
  gets jumped by goblins until they EARN Squire+.
- Render the two **visually distinct** (color/icon/slot) so a Dragon donor never looks like he
  outranks a King. (Reinforcing property: throne candidacy is gated by account age + playtime —
  unpurchasable — so **cash literally cannot buy political eligibility**.)
- **The store never sells** War Effort, supplies, campaign-quota contribution, feudal rank, or
  BIS gear. (Indirect bonds→gp→materials via the player market is fine — normal eco liquidity.)

### Already scaffolded (build on, don't recreate)
- Bonds: `BOND_POUCH_KEY` container + `FREE_BOND_CLAIMED_ATTR` (`Player.kt`, `ContainerKeys.kt`).
- Membership: `MEMBERS_EXPIRES_ATTR` + login `member` flag (`Attributes.kt`, `LoginWorker.kt`).
- Donor: `Privilege.DONOR_POWER` (`Privilege.kt`).

### Revenue stack (priority order)
1. **Bonds first** *(Locked: store credit + sellable for gp)* — buy with cash → bond lands in the
   `bonds` pouch → **redeem for store credit** (cosmetics/membership/donor) OR **sell to players
   for gp**. Lets F2P reach perks through gameplay while you capture revenue. Hook: container
   exists; add the bond item, a redemption → store-credit currency, and the tradeable path.
2. **Membership** (~$7.50/mo or ~$75/yr) — gate QoL on `MEMBERS_EXPIRES_ATTR`: extra bank space
   (`Bank.kt`), more presets, teleport access, optional cosmetic aura, **plus an XP/drop boost up
   to ~10%** *(Locked)*. **Caps *(Locked)*:** (a) **combined** with any donor drop boost the total
   is capped at ~10% — they **do not compound**; (b) the boost applies to **skilling XP + monster
   drops only — never War Effort or campaign-supply contribution**, so paid players grind
   PvM/skilling faster but never out-climb the war/political economy with cash. Recurring income
   covers hosting + DDoS.
3. **Donor ranks** — a 6-tier cumulative-spend ladder (modeled on Roat Pkz, but stripped of its
   pay-to-win). Track a new `DONOR_SPEND_ATTR`, derive the tier, set `DONOR_POWER`. **The
   "Donator" suffix + a distinct icon/color keep them visually separate from feudal titles**
   (these are NOT political power). Ladder (Roat Pkz names; prices tunable): **Normal Donator $10
   → Super Donator $30 → Extreme Donator $90 → Legendary Donator $190 → Royal Donator $500 →
   Divine Donator $1000**. Each tier adds **donor points** (a cosmetic-shop currency) + all lower
   perks.

   **Perks are convenience / access / cosmetic only — three buckets:**
   - **Cosmetic (every tier):** donor icon + name color (distinct from the noble title color),
     donor-point cosmetic shop (pets, overrides, particles, titles), higher tiers = flashier.
   - **Convenience commands (laddered):** `::yell` (Normal) → `::bank` safe-zone (Super) →
     `::teles` teleport wizard + extra presets/kits (Extreme) → `::settele` + action keybinds
     (Legendary) → more bank + trading-post slots (Royal) → max QoL (Divine).
   - **Access zones:** donor zone (Normal+) = the fair skilling-convenience hub (rune/coal,
     yew/magic, exclusive *cosmetic/pet* thieving stalls); higher tiers unlock nicer AFK/skilling
     lounges + a donor home/castle. **Rule:** these zones give *comfort and speed of access*,
     never exclusive power or BIS — anything earned there must be earnable elsewhere too.

   **The two power perks Roat sells — handled to stay non-P2W:**
   - **`::spec` / `::hp` restore** *(Locked: safe-zone only)* — include as a **convenience**, but
     **safe-zone only, not usable in/near combat or the wilderness**, with a tier-scaling cooldown
     (Roat: 90→15s). Saves a recharge trip; can't win a fight you're in. (Roat allows it in
     combat — that's the P2W we're declining.)
   - **Drop-rate boost** *(Locked: small, ≤10%, combined-capped)* — donor tiers add a modest drop
     boost laddering up toward ~10%. **Critical guard:** Membership and donor boosts **do NOT
     compound** — the **combined total is capped at ~10%** (honors the doc's "no stacking boosts
     that compound into power"). So a 10% member gains nothing extra from donor's boost; donor's
     boost mainly helps non-members / fills toward the cap. Same **carve-out** as membership: the
     boost applies to **PvM drops only — never War Effort or campaign supply.**
   - **`::unskull`** — **exclude.** On a PK server, removing your skull on demand dodges the death
     penalty = a real PvP advantage.

   As always: donor **never** grants feudal authority, War Effort, supplies, armour gates,
   aggro-standing, or throne eligibility.
4. **Cosmetic store** (highest margin, infinite ceiling) — pets, **item overrides** (reuse the
   `itemOverride`/`WeaponEffects` cosmetic-override framework), particles, **cosmetic titles**
   (visually distinct from feudal titles), home customization. Lean in here over time.

### Engagement (non-cash) — drives the population that makes the cash work
- Wire `::vote` (currently a stub) → vote points (`PointKind.VOTE` exists) → cosmetic boxes /
  small boosts. Discord-boost rewards. Toplist votes are how a new server surfaces.

### Delivery pipeline (infra, mostly outside game code)
- Store website + payment processor (PayPal flags RSPS patterns — choose carefully; **taxable**
  income). Purchase → in-game claim: a pending-rewards queue keyed by username, claimed on login
  (needs a small web↔server bridge).

### Scope (matches the launch roadmap phasing)
- **Launch**: bonds + membership + a starter cosmetic set + the fair donor zone. **Publish the
  monetization promise** (players screenshot it — honor it).
- **Post-launch**: expand cosmetics, add perks, run the first promos. No FOMO/limited drops at
  launch; don't ship the store maxed.

---

## 7. Build order

**Launch spine**
1. **First 10 Minutes** (§1) — highest priority; cheapest big win. Starter kit + Sergeant +
   Recruit Trials + tracker.
2. **War Effort currency + Slayer war-contracts** (§2, §3A) — mostly data + reward hooks.
3. **Quartermaster supply sink** (§3B) — skilling's purpose + the item sink.
4. **Rank tie-ins, launch set** (§4 items 1–4) — title prefix + frontier aggro + bot aggro +
   deference. High-value/low-cost; make rank *felt*.
5. **War-consumes-supplies payoff** (§3C) — recoverable-stakes mechanic; makes the loop click.
6. **Monetization launch set** (§6) — bonds + membership + starter cosmetics + donor zone;
   publish the promise.

**Post-launch**
7. Feudal payoff (§4 items 5–7, §5) — war command, wilderness bounty, insignia.
8. Conquest campaigns + multi-target rollout (§0, §5) — the marquee endgame + dev-blog reveals.
9. Elected throne + season system (§5).

---

## 8. Critical files & hooks
- **Onboarding:** `content/mechanics/starter/StarterKitPlugin.kt`; `Player.login()`
  (`game-server/.../entity/Player.kt`); new `RecruitTrialsPlugin` + Sergeant NPC;
  `CitizenshipPlugin.kt`.
- **Slayer:** `content/skills/slayer/SlayerTasks.kt`, `SlayerPlugin.kt`.
- **Currency/economy:** `content/economy/Currencies.kt`; `game-server/.../attr/Attributes.kt`;
  new Quartermaster vendor (pattern: `LumbridgeShopHubPlugin.kt` `createShop()` DSL).
- **War:** `content/war/WarEffortPlugin.kt`, `WarParticipation.kt`, `WarState.kt`,
  `WarServices.kt`, `Sieges.kt`, `Commander.kt`, `AttackDirector.kt`, `CityFrontiers.kt`.
- **Rank tie-ins:** `content/war/Title.kt`, `TitlePlugin.kt`, `DukeHoracioPlugin.kt`;
  rank-aware aggro in `HostileZone.kt` + `CityFrontiers.kt` (`EnemyLine.aggroFloorRank`); title
  name prefix at `game-server/.../info/PlayerInfo.kt:63` (`setName`).
- **PK-bot aggro gate:** `content/bots/BotCombatPlugin.kt` (`botAggroTimer` scan) + `BotBrain.kt`
  (+ `Loadouts.kt` for `combatLevel`).
- **Monetization:** `BOND_POUCH_KEY`/`Player.bonds` (`ContainerKeys.kt`, `Player.kt`);
  `MEMBERS_EXPIRES_ATTR` + `FREE_BOND_CLAIMED_ATTR` (`Attributes.kt`); `Privilege.DONOR_POWER`
  (`Privilege.kt`); `content/economy/Currencies.kt` (store-credit); `Bank.kt` (member space);
  `itemOverride`/`WeaponEffects` framework (cosmetic overrides); `::vote` stub.

---

## 9. Verification
- **New-account run:** create a fresh account; confirm the welcome + Sergeant fire once (gated on
  `NEW_ACCOUNT_ATTR`), the trial chain tracks and completes, and War Effort is granted. Re-login →
  onboarding does NOT re-fire.
- **Slayer:** take a contract; confirm dual reward (Slayer + War Effort) and war-flavored text;
  confirm hard tiers are rank-gated.
- **Supply:** hand goods to the Quartermaster → items consumed, War Effort granted.
- **Stakes:** `::warraid siege` to force a raid; confirm low supply → weaker defense and that
  contributing supplies measurably helps; confirm `WarServices` shop/bank seal on fall + recovery.
- **Rank:** `::settitle` across ranks; confirm frontier aggro drops at the right tiers, the
  colored noble prefix shows Squire+, bots ignore high rank outside the wild but not inside it.
- **Monetization:** redeem a bond → store credit and player-sale path; toggle membership →
  perks + ≤10% boost on XP/drops only (NOT War Effort); donor tier sets `DONOR_POWER` without any
  feudal effect.
- Inspect with existing `::war`, `::warmap`, `::points`, `::settitle`, `::givecoins`.

---

## 10. Open tuning questions (deferred, pick sensible defaults)
1. Persistent conquest/occupation layer (does a conquered NPC city stay "taken" for a window?).
2. Election voting weight — one-noble-one-vote vs rank-weighted.
3. Exact numbers: War Effort earn/spend rates; per-soldier campaign costs per target; membership
   boost % within the ≤10% cap; donor price points; min account-age / playtime for candidacy;
   frontier aggro rank floors; bot level-band width.
4. Whether the rank insignia cape ships at launch (cosmetic) or post-launch.

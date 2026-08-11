# OSRS Duel Arena — full history, mechanics, failure modes (research dossier)

Reference for building our custom duel/staking system at a high level: everything the real
Duel Arena did, every way it broke, and everything Jagex changed in response — mapped at the
end against what we've already built (`Alter/game-plugins/.../minigames/duel/`).

Compiled Aug 2026 from the OSRS Wiki update pages, Jagex news posts, RuneHQ, community
records, **and primary game data**: the decompiled OSRS clientscripts (RuneStar cs2-scripts)
and a current cache dump containing the Emir's Arena "legacy duel" interfaces, which are
near-verbatim clones of the original screens. Facts marked **[cache]** are exact
strings/values from the OSRS client cache. Unverifiable folklore is flagged where it appears.

---

## 1. Timeline — the whole arc in one table

| Date | Event |
| --- | --- |
| RS Classic (2001–03) | No arena. "Duel anywhere" existed and was abused (dueling-to-death as a free Lumbridge teleport) — a stated motivation for building a fenced arena. |
| **25 Mar 2004** | RS2 "Duelling and Extras" update: Duel Arena launches NE of Al Kharid. Six walled pits, spectator walkways, hospital with nurses, Fadli item storage, rules incl. No Forfeit / No Movement / Obstacles / per-category combat bans. |
| 20 Nov 2007 | Anti-RWT era: stakes capped at 3,000 gp per 15 minutes; 64-player tournament mode added as compensation. |
| 10 Dec 2007 | Arena opened to F2P. |
| 1 Feb 2011 | Free Trade referendum (91% of 1.2M votes): unrestricted staking returns; ~10 trillion gp staked/dropped within 3 days; tournaments removed. |
| Aug 2012 (RS3) | **Money-pouch dupe**: withdrawing coins from a full money pouch inside the Duel Arena/Crucible duplicated money. Fixed in days; abusers banned/rolled back. |
| **22 Feb 2013** | OSRS launches from a 2007 archive **with free trade on** → unlimited item+coin staking from day one (bounded only by the 2,147,483,647 int cap). |
| 25 Jun 2015 | Platinum tokens introduced — stakeable currency. |
| **16 Jul 2015** | **Duel Arena Rework** (first big anti-scam pass): challenge screens rebuilt; Accept button locks for ~3s after *any* rule or stake change; accepts reset on change; Save/Load preset + "Load last duel" buttons. |
| 24 Aug 2016 | Anti-scam dev blog — response so positive it shipped **unpolled**. |
| **29 Sep 2016** | **Duel Arena Improvements**: opponent's combat level **and individual combat stats** shown before accepting; new **"Show inventories"** rule (see opponent's backpack/worn — items but not stack sizes); new **"No weapon switch"** rule; a **third screen** added (rules → stake → final confirm); flow deliberately made longer. |
| **16 Aug 2018** | **Duel Arena Changes** — the staking-economy overhaul: **item staking removed** (coins + platinum tokens only; F2P coins only; Ironmen can't stake); **stakes must be within 10,000 gp of each other** (kills odds staking); **tiered tax on the combined pot**: 0.25% (up to 10M), 0.50% (10M–100M), 1.00% (100M+). |
| **13 Oct 2021** | Game Integrity newspost: the arena is the source of **38% of all RWT bans** (thousands/month). Cap announced for November, full phase-out announced for 2022; buyer-side bans + wealth removal. |
| **17 Nov 2021** | Final-form changes: **10M gp stake cap per player per duel** (members + F2P); two official fixed presets — **"Whip"** and **"Boxing"** — so the community-standard rulesets can't be subtly mis-set. *(The "1% tax" often remembered from this era is the Grand Exchange tax, Dec 2021 — the arena's own tax stayed the 2018 tiers.)* |
| **Jan 2022** | Arena **permanently closed and demolished** in both games (RS3: 4 Jan, earthquake → Het's Oasis; OSRS: first update week of Jan, left as rubble for six months). Staking removed from the game forever. |
| 15 Feb – Jun 2022 | PvP Arena design blog; rewards poll **fails** (voting gated to PvP participants — controversial), rewards redesigned, v2 passes. |
| **6 / 13 Jul 2022** | **Emir's Arena (PvP Arena)** soft launch / full launch: no stakes, matchmade cross-world 1v1s and 4–64 player tournaments on separate save worlds, rank system (start 2500, cap 15,000), points-only reward shop. Points earned **only** in matchmade fights — nothing for manually arranged ones. |
| 15 Aug 2024 | Post-arena gambling had migrated to "**deathmatching**"; Jagex bans it under Games of Chance rules (3-day ban + wealth removal, then permaban). The lesson: the gambling incentive outlives any venue. |

---

## 2. Complete feature inventory (what "faithful" means)

### 2.1 Challenge flow — three screens

1. **Challenge**: inside the arena activity zone, players gain a right-click **Challenge**
   option (trade-request pattern: chatbox clickable, mutual challenge opens the screens).
   Busy/in-duel players can't be challenged.
2. **Screen 1 — Duel Options** (interface 482): opponent name + combat level (colour-coded)
   + full combat stats (post-2016). Left: 13 rule checkboxes. Right: **your worn equipment
   rendered slot-by-slot** — clicking a slot toggles that slot's restriction (red cross
   overlay). Preset buttons: Save / Load / Load-last-duel, later "Whip" and "Boxing".
3. **Screen 2 — Stake** (interface 481): both stakes side by side. Post-2018: coins +
   platinum panels with quick-add buttons (+100k/+1m/+10m; +10/+100/+1k) and exact-quantity
   entry. Pre-2018: 28-slot item stake containers. Tabs to view opponent's backpack/worn
   (only if "Show inventories" agreed). **[cache]** "Tax is deducted from the combined stake."
4. **Screen 3 — Confirmation** (interface 476): scrollable plain-text summary generated
   server-side from the agreed rule bits — sections **[cache]**:
   - *Opponent details:* name + level.
   - *Before the duel starts:* "Some worn items will be taken off." / "Boosted stats will be
     restored." (if No Drinks or No Special Attacks) / "Existing prayers will be stopped."
     (if No Prayer) / "No options apply."
   - *During the duel:* one line per rule (exact strings below), plus "You can't use 2H
     weapons such as bows." if weapon and/or shield slot is disabled.
   - Green footer when options match a preset: "Options match 'Whip'" etc.
   - Stake totals for both players, with the tax breakdown.

**Accept-state machine**: WAITING → one accepts → both accept → confirm screen → both accept
→ duel starts. **Any rule or stake mutation resets both players' accept flags and locks the
Accept button for ~3 seconds** ("Wait...", orange; screen-switches show "Check...") with a
**flashing exclamation mark next to the exact row/slot that changed** **[cache:
`duel_options_changed`, `duel_wait_button`]**, plus a red banner: "An option or stake has
changed - check before accepting!" This is the single most important anti-scam mechanism the
arena ever got (Jul 2015).

### 2.2 The rule bits — canonical layout (varp 286 `dueloptions`) **[cache]**

| Bit | Rule | Effect (confirm-screen wording) |
| --- | --- | --- |
| 0 | No Forfeit | "You cannot forfeit the duel." — trapdoors unusable, strictly to the death |
| 1 | No Movement | "You cannot move." — players **start on adjacent tiles** (so melee still works); also bans binding/hold spells |
| 2 | No Weapon Switch | "You cannot switch weapons." — locked to weapon equipped at start (added 2016; kills the hasta scam and mid-duel DDS-spec finishers) |
| 3 | Show Inventories | informational — unlocks opponent backpack/worn tabs (items visible, **stack quantities hidden**) |
| 4 | No Ranged | "You cannot use Ranged attacks **or salamanders**." |
| 5 | No Melee | "You cannot use Melee attacks or salamanders." |
| 6 | No Magic | "You cannot use Magic attacks or salamanders." (salamanders attack in all three styles, so banning any style bans them) |
| 7 | No Drinks | "You cannot use potions or drinks." — **boosted stats restored to base at duel start** |
| 8 | No Food | "You cannot use food." (includes combo food) |
| 9 | No Prayer | "You cannot use Prayer." — active prayers deactivated at start |
| 10 | Obstacles | "There will be obstacles in the arena." — fight assigned to an obstacle pit (walls/pillars blocking movement + line of sight) |
| 12 | Fun Weapons | "You can only attack with 'fun' weapons." — negative-bonus joke weapons (rubber chicken, flowers, birthday cake…); **bare fists NOT allowed** |
| 13 | No Special Attacks | "You cannot use special attacks." — also restores boosted stats at start; force-on for F2P preset comparison |
| 14–27 | Equipment slot restrictions (varbit 642) | bit = 14 + slot id; restricted worn items **auto-unequipped to backpack at start** (inventory space must be verified at accept) and can't be re-equipped. Disabling weapon **or** shield also bans all 2H weapons |

**Official preset bitmasks [cache, decoded]:**
- **Whip** = `229499867` — No Forfeit, No Movement, Show Inventories, No Ranged, No Magic,
  No Drinks, No Food, No Prayer, No Special Attacks + every equipment slot disabled **except
  weapon**. (Note: the whip preset actually permits *any one-handed weapon*.)
- **Boxing** = `229630939` — same plus weapon slot disabled → bare fists.

### 2.3 Rule-dependency matrix (combinations the game rejects)

Enforced **at toggle time**, not at duel start — every one of these exists because someone
weaponised the gap:

- **No Ranged + No Melee + No Magic** — nobody can fight.
- **No Forfeit + No Movement** — stalemate with no escape hatch.
- **No Forfeit + No Melee** — a ranger/mage can run out of ammo/runes and never end the duel.
- **No Movement + Obstacles** — obstacle pits assume pathing.
- **Fun Weapons + No Melee** — all fun weapons are melee → zero-damage forever-duel (the
  "snowball fight" stall scam).
- (RS3 also blocked familiars + Obstacles — familiars wedged on the scenery.)

The design rule: **validate every configuration for winnability and escapability before it
can be accepted.**

### 2.4 Arena logistics

- **Six pits: 3 flat, 3 obstacle** — which you get is decided solely by the Obstacles rule.
  Spectator walkways above; **Fadli** at the entrance (1 gp rotten tomatoes to pelt duellers;
  bank access); **Mubariz** the information clerk; **two scoreboards showing the last 50
  duels** on that world (winner, loser — forfeits omitted).
- **Hospital** beside the arena: Surgeon General Tafani, Jaraah "The Butcher", nurses Sabreen
  and A'abla — free full-HP heals (but can't cure poison); Saradomin altar nearby for prayer.
- **Duel start**: both teleported into a free pit (adjacent tiles if No Movement, otherwise
  apart), hint arrow over the opponent, **overhead countdown "3", "2", "1", "FIGHT!"** (~3s;
  attacks blocked during it — "The duel hasn't started yet!").
- **During**: no teleports, no trading, attack only your opponent (option becomes left-click
  Attack), outsiders can't interfere, no skulls, **safe death** — you lose only the stake.
- **Duel end**: both teleported out beside the hospital and **fully restored** — HP, boosted
  *and drained* stats, prayer points, special attack to 100%, run energy, poison cleared.
  Winner gets "Well done! You have defeated <name>!" + a winnings interface (interface 372)
  listing the spoils.
- **Forfeit**: trapdoors on east and west of every pit; forfeiter loses the whole stake,
  and the forfeit is not recorded on the scoreboard.
- **Draw**: a duel that lasted **15 minutes ended in a draw — both stakes refunded**.
  (Simultaneous-death handling isn't clearly documented; refund-both is the behavior
  consistent with the draw principle.)
- **Disconnect/x-log mid-fight**: the avatar stays in the pit under normal combat-logout
  rules — an x-logger keeps taking hits and generally dies, losing the stake. Disconnect
  during the setup screens simply declines the duel with everything returned.

### 2.5 Staking mechanics summary

- Originally any tradeables + coins (28-slot container each); platinum tokens from 2015;
  **coins/platinum only from Aug 2018**; F2P coins only; Ironmen never.
- Zero-stake "fun duels" always allowed.
- Limits: int-cap era → within-10k-of-each-other (2018) → 10M/player cap (Nov 2021).
- Tax from the **combined pot** before payout: 0.25% / 0.50% / 1.00% tiers (2018+) — both a
  gold sink and an odds-staking killer.
- Stake amounts rendered with `K (exact)` / `M (exact)` expansions so "3 coins vs 3,000,000
  coins" lookalike scams are visible on the confirm screen.

---

## 3. Failure-mode dossier — every way it broke

### 3.1 Interface/social scams and their fixes

| Scam | Mechanism | Jagex fix |
| --- | --- | --- |
| **Rule flick** (the canonical one) | Toggle a rule (classically un-ticking No Food when only the scammer brought food) the instant before the victim clicks Accept | Jul 2015: accept-flag reset + ~3s Accept lockout on ANY change, flashing marker on the changed row, red "an option or stake has changed" banner; Sep 2016: longer 3-screen flow |
| **Stake flick / lookalike swap** | Same trick on the stake: pull coins or swap an item for a cheap lookalike during a screen transition | Same lockout applies to stake changes; confirm screen shows full stake + totals; 2018 removed items entirely |
| **Decline-and-rechallenge spam** | Decline late, re-open with worse terms, bet on the victim spam-clicking Accept through the fresh screens | Each re-challenge is a fresh agreement with fresh lockouts; flow lengthened |
| **Hasta scam** | "Whip duel" convention = any 1h weapon, no armour; a Zamorakian hasta's defensive bonuses dominate whips in no-armour fights | 2016 "No weapon switch" rule; Nov 2021 official immutable Whip/Boxing presets |
| **Hidden-build stat baiting** | 1-def/1-prayer max-melee builds have deceptively low combat levels; victims judged fairness by level | Sep 2016: opponent's **individual stats** shown pre-accept |
| **Odds staking / host & middleman theft** | Unequal stakes negotiated in chat, overage held by "trusted" hosts who absconded; the main RMT laundering rail (~$100k/day wagered at peak) | Aug 2018: symmetric stakes forced (within 10k), items unstakeable — anything the game can't escrow, scammers will |
| **Snowball-fight stall** | Fun Weapons + No Melee = zero-damage forever-duel; victim eventually forfeits/logs and loses the stake | Rule-dependency validation (see 2.3) |
| **Arrows/movement one-sided & stalemate combos** | e.g. Ranged-only + No Movement + arrows off: scammer uses knives, victim's bow is dead and they can't close distance | Rule-dependency validation + accept lockout killing the last-second flick |
| **Obstacle kiting** | Melee-only opponents kited around obstacle scenery forever, worse under No Forfeit | Dependency rules (Obstacles ⟂ No Movement, No Forfeit ⟂ No Melee) + trapdoors as escape hatch |
| **Doubling money / trust cons around the arena** | Classic build-trust-then-vanish, "test my max hit on you", inventory-casing | Never fixable by interface — one reason the venue itself was removed |

### 3.2 Actual bugs and technical exploits

- **Money-pouch dupe (RS3, Aug 2012)** — the big real one: a secondary currency container
  interacting with the minigame's restricted-inventory state machine duplicated coins.
  *Lesson: lock or snapshot-reconcile every alternate container (pouches, bags) inside the
  staking state machine.*
- **"2015 1B stake dupes"** — folklore; no real-game evidence found. Almost certainly
  conflates the 2012 pouch dupe and rampant **private-server** duel dupes.
- **X-log/disconnect** — real game: small surface (avatar stays and dies). **Private
  servers: the classic dupe** — accept final screen, hard-disconnect; winner gets paid AND
  the disconnect handler refunds the loser's already-committed stake on relog →
  duplication. *Lesson: single server-authoritative escrow, settled exactly once,
  idempotently; refund logic lives in the settlement path, never the disconnect handler.*
- **Interface dupes (RSPS)** — after settlement, stale stake containers withdrawable via
  another interface (sometimes with forced interface IDs). *Lesson: clear stake containers
  on death/settlement; never trust client-reported container state.*
- **Client-value manipulation (RSPS)** — Cheat Engine freezing item slots/values so client
  and server disagree about what's staked. *Lesson: server inventory is the only truth;
  validate every stake mutation against it.*
- **Simultaneous death** — both hit 0 on the same tick: naive implementations pay both,
  refund neither, or let list-processing order pick the winner. *Lesson: explicit
  deterministic draw (refund both) or coin-flip — decided in one settlement path.*
- **Boosted-stat / effect carry-in** — real game resets boosted stats at duel start (under
  No Drinks/No Spec) and normalizes fully at duel end. Divine potions re-apply their boost
  on a timer, so a reset must also cancel recurring boost timers. No confirmed Vengeance
  carry-in bug, but the hardening rule is: **duel start = full combat-state normalization**
  (stats, buffs/debuffs incl. Vengeance, spec energy, prayer, poison/venom, pending hits).
- **PID advantage — the structural fairness bug.** The server-assigned processing order
  decides who lands first when both act on the same tick. In whip/boxing stakes that end
  with both players in single digits, the lower-PID player wins disproportionately, and PID
  also wins defensive-style-switch races. OSRS mitigates by **reshuffling PID randomly every
  ~100–150 ticks**; within a window the edge is real and was a famous source of
  "rigged-feeling" losses. *Arguably the single most important fairness decision in a
  reimplementation: randomize processing order per duel/tick, or resolve same-tick kills as
  an explicit draw or coin-flip rather than by list position.*

### 3.3 Why Jagex ultimately deleted it (the strategic lesson)

Ten years of patches (2015, 2016, 2018, 2021) fixed the *interface* but never the
*incentive*: staking is a legitimate-looking, game-native way to move huge value between
accounts, which made the arena the game's #1 RMT laundering rail (38% of all RWT bans),
a gambling machine normalized by streamer content, and a scam magnet at many players' first
PvP touchpoint. The replacement (Emir's Arena) kept the legitimate use — accessible 1v1s and
tournaments — and removed value transfer entirely: no stakes, supplied standardized setups,
matchmade-only rewards, isolated save worlds. When gambling migrated to unregulated
"deathmatching", Jagex banned that too (2024). **If a server allows value staking, it is
operating a gambling rail — the design choices are caps, taxes/sinks, escrow, logging, and
enforcement, not whether abuse will be attempted.**

---

## 4. Gap analysis — our implementation vs. the research

What we already have (`DuelArenaPlugin.kt`, `DuelArena.kt`, `TradeSession` stake screen,
wiki article `duel-arena-staking.md`):

**Already ahead of the original:**
- **True escrow at accept** — stakes move into a server-side `Duel` object, never through
  the opponent's hands. The entire remove-at-the-last-second scam family is dead by
  construction (the real arena only ever mitigated it with lockouts).
- **Crash-safe settlement** — `DUEL_ESCROW_ATTR` persistence voids + refunds on JVM death.
  The classic RSPS hard-disconnect dupe is closed *if* settlement stays idempotent (it is:
  `resolve()` guards double-fire and clears escrow before payout).
- **Private instanced pit per duel** — no shared-arena interference, no obstacle-wedge
  surface, unlimited concurrency; challenge-anywhere beats walking to Al Kharid.
- **Curated rule menus** — illegal combinations are unrepresentable by construction (menus
  offer only coherent packages), which is stronger than a toggle grid + validation matrix.
- Safe deaths, sealed bubble vs outsiders, gear stripping at start + re-equip enforcement,
  bank-overflow payout, per-duel logging.

**Gaps to close (ordered by how much they matter):**

1. **No Movement stalemate bug** — real arena spawns No-Movement duels on **adjacent
   tiles**; we spawn at `RED_SPAWN`/`BLUE_SPAWN` 8 tiles apart and root both players. A
   No-Movement duel where either side lacks ranged/magic (e.g. "Melee only" + "No movement",
   or whip-only) is an **unwinnable stalemate whose only exit is logout = lose your stake** —
   exactly the snowball-fight scam the real game had to patch. Fix: adjacent spawn tiles
   when `noMovement`, and/or a rule-dependency check (our menu structure allows this combo).
2. **No draw path** — no 15-minute draw timer, and simultaneous same-tick death resolves by
   whichever `onPlayerDeath` fires first (processing-order = PID bug). Add: draw timer →
   refund both; explicit same-tick rule (draw/refund is simplest).
3. **Combat-state normalization** — we reset HP only. At duel start: reset boosted/drained
   stats, spec energy, prayer points, clear Vengeance/poison/pending effects, cancel divine
   re-boost timers. At duel end: full restore for both (real arena restored everything).
   Without spec reset, entering pre-charged for an instant DDS double-spec is a real edge.
4. **Accept-lockout + change flash on the stake screen** — verify `TradeSession` resets
   both accepts on any stake mutation and consider the ~3s "Wait..." lockout + "an option
   or stake has changed" banner; it's the arena's most battle-tested anti-scam UX and cheap
   to add to the overlay/grid rules screens too.
5. **Missing classic rules** — No Weapon Switch (locks the hasta-scam surface our
   "whip-only = specific item" rule already narrows), No Special Attacks, Show Inventories,
   Obstacles (needs an obstacle pit variant of `ARENA_SOURCE`), forfeit as a mechanic
   (trapdoor/option; currently `noForfeit` is messaging-only and every duel is effectively
   no-forfeit — decide and make it a real toggle with the No-Forfeit dependency rules).
6. **Confirm-screen completeness** — show opponent combat level + stats and the full
   rule-consequence text ("Boosted stats will be restored", "Some worn items will be taken
   off", 2H warning) on the final confirm; the 2016 lesson is that the longer, more explicit
   flow measurably cut scams.
7. **PID fairness** — check Alter's player-processing order; if fixed, one side owns every
   close duel. Randomize per duel (swap who's processed first) or per shuffle window.
8. **Economy levers** (only if/when staking scale grows): stake cap, winnings tax as a gold
   sink, scoreboard (last-50 results — also a nice social feature), duel-history logging for
   RMT pattern detection (rigged win-trading between the same pair is the laundering
   signature: same two accounts, one-directional net flow).
9. **Alternate containers** — audit that looting-bag/pouch-like containers (and anything
   with a "withdraw" path usable mid-duel) are locked inside the duel state machine
   (the 2012 pouch-dupe shape).

### Rules-grid tie-in

The pending clickable rules grid (`docs/duel-rules-grid.md`, 69 components) maps cleanly
onto the canonical layout: 13 rule toggles (varp-286 bits 0–13) + 11 equipment-slot toggles
(bits 14–27) + preset buttons. If we want faithful semantics, the cache-exact tooltip and
confirm strings in §2.2 are the source of truth, and Whip/Boxing preset masks give us
one-click community-standard setups.

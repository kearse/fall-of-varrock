# Duel Arena implementation plan — classic-faithful, RMT-nerfs excluded

The build plan for taking our duel system (`Alter/game-plugins/.../minigames/duel/`) to a
high-quality classic Duel Arena. Companion to `duel-arena-research.md` (section references
below point there).

## Design stance

- **Classic rules, classic feel** — the full 13-rule + 11-slot grid, presets, countdown,
  forfeit, obstacles, fun weapons, the three-screen agreement flow with its exact wording.
- **Our delivery model** — challenge anywhere in a safe zone → private instanced pit →
  returned where you stood. No physical-hub investment; the Al Kharid grounds stay PK
  training.
- **Pre-2018 staking economics** — RMT isn't a threat here, so none of the nerfs that
  existed only to fight it:

| | Classic era (keep) | RMT-era nerf (drop) |
| --- | --- | --- |
| What's stakeable | **Any tradeable items + coins** (28-slot stake each side) | Coins/platinum only (2018) |
| Stake size | **Unlimited** (int-cap bounded) | Within-10k matching (2018), 10M cap (2021) |
| Tax | **None** | 0.25%/0.5%/1% winnings tax (2018) |
| Odds staking | **Allowed** — unequal stakes are a feature between consenting players | Forced-symmetric stakes |

- **Keep the entire anti-scam layer** — it was never about RMT and it's what made late-era
  dueling trustworthy: accept-lockout on change, change flashes, the explicit confirm
  screen, opponent stats display. Our escrow already goes further than Jagex ever did
  (research §4): stake-removal scams are impossible by construction, so we get pre-2018
  freedom with post-2016 safety.
- Custom additions that stay: companions rule (4v4), exhibition duels for the tournament,
  crash-safe escrow, sparring hand-off for own companions.

---

## Phase 1 — correctness & fairness fixes (before any new features) — ✅ SHIPPED

These are live bugs or unfairness in what's built (research §4 gaps 1–3, 7).

1. **No-Movement adjacent spawns** — classic spawns No-Movement duels on adjacent tiles so
   melee still works; we spawn 8 tiles apart, making "Melee only + No Movement" an
   unwinnable stalemate whose only exit is a stake-losing logout. Fix in
   `DuelArenaPlugin.begin()`: when `rules.noMovement`, use adjacent spawn tiles (e.g.
   `3377/3378, 3251`); keep the spread spawns otherwise.
2. **Rule-dependency validation** — one enforcement point (`DuelRules.validate()`)
   rejecting the research §2.3 matrix: all-three-styles-banned; No Forfeit + No Movement;
   No Forfeit + No Melee; No Movement + Obstacles; Fun Weapons + No Melee. Wire into every
   rules entry path (overlay, grid, chatbox menus) so an illegal combo can't reach the
   stake screen.
3. **Draw handling** —
   - **Simultaneous same-tick death = draw**: both stakes refunded, no winner message.
     Today the first-processed `onPlayerDeath` wins — processing-order luck (the PID bug in
     miniature). Add a `resolveDraw(d)` path beside `resolve()`; in the death handler,
     detect the opponent also dying this tick (both HP ≤ 0) and route to draw.
   - **15-minute duel timer** (1,500 ticks, counted from FIGHT!): expiry = draw, both
     refunded. Tick it in `tick(d)`.
4. **Combat-state normalization** (research §3.2 "carry-in") —
   - **At duel start** (after teleport, before countdown): snapshot then reset for both —
     boosted/drained stats to base, special attack to 100%, prayer points to full, active
     prayers off, poison/venom cured, Vengeance and pending effects cleared, divine-potion
     re-boost timers cancelled. Kills the pre-charged DDS double-spec opener and every
     "walked in buffed" edge.
   - **At duel end**: full restore for both fighters (HP, stats, prayer, spec, run energy,
     status) — the classic hospital experience, minus the walk.
5. **Real forfeit** — classic default is forfeit-allowed; today every duel is effectively
   no-forfeit. Make `noForfeit` a real toggle: when off, expose a **Forfeit** action
   (trapdoor objects if the instanced pit's map has them, otherwise an option on the duel
   status overlay/`::forfeit`). Forfeiter loses the whole stake (existing `resolve()` path,
   distinct message). When `noForfeit` on, hide/deny it. Update wiki article wording.
6. **PID fairness audit** — **RESOLVED: already faithful.** Alter's `PlayerCycleTask` cycles
   players by a `processingPriority` that `World.shufflePidsIfDue()` re-deals randomly every
   100–150 ticks — the OSRS PID shuffle, exactly. Phase 1 adds two things on top: the
   FIGHT! opening swing order is a per-duel coin flip (not challenger-first), and the
   double-KO draw rule (fix 3) removes the one place PID could still pick a stake winner.

## Phase 2 — complete the classic rule set — ✅ SHIPPED (Show Inv → P3, Obstacles → P4)

Extend `DuelRules` + enforcement (research §2.2 is the source of truth for semantics):

- ✅ **No Weapon Switch** (bit 2): the weapon worn at FIGHT! is locked. Enforced synchronously
  in `onEquipToSlot` (weapon AND shield slots — a shield equip silently displaces a locked 2H)
  so a one-tick DDS-spec switch can't land, plus a duel-tick backstop that re-equips the locked
  weapon after a plain unequip (re-equipping inside the unequip hook would corrupt
  `EquipAction`'s swap flow).
- ✅ **No Special Attacks** (bit 13): arming the spec bar (combat-tab bar + minimap orb, the
  single gate every spec path goes through) is denied; pairs with the Phase-1 spec reset.
- ✅ **Fun Weapons** (bit 12) as a proper rule: `funWeapons` flag + whitelist; **bare fists not
  allowed** — attacks without a whitelisted fun weapon equipped are denied in
  `Combat.canAttack`. The whitelists (fun/whip/DDS) now live once, in `DuelRules.Companion`.
- **Obstacles** (bit 10): see Phase 4.
- **Show Inventories** (bit 3): informational — opponent backpack/worn visible on stake +
  confirm screens, item identities but not stack quantities. Needs interface support;
  schedule with Phase 3.
- ✅ **Equipment-slot restrictions**: `DuelRules.barsWorn()` is the one wearability answer
  (equip revert, duel-start strip, space check). Auto-unequip at start **with
  inventory-space verification at accept** (a `TradeSession` stake vet denies the confirm
  screen when the stripped gear can't fit), a bank-overflow belt if a path skips the vet
  (a disallowed item must never stay worn), and the "no 2H when weapon or shield disabled"
  implication.
- ✅ **Presets**: Save / Load / Load-last-duel per player (persisted attrs `duel_saved_rules`
  / `duel_last_rules`), plus the two official ones — **Whip** = No Forfeit/No Movement/No
  Ranged/No Magic/No Drinks/No Food/No Prayer/No Spec + all slots off except weapon (any
  one-handed weapon — the forbidden shield slot is what bans 2H, exactly like the real
  preset); **Boxing** = same + weapon slot off. (Show Inv joins both presets in Phase 3.)
  The stricter "whip-only/DDS-only" item whitelists stay as house rule toggles.
  **`validate()` change**: the Phase-1 `No Forfeit + No Movement` ban is LIFTED — the decoded
  official Whip preset sets both bits (research §2.3 correction); melee availability is
  guaranteed by the `No Forfeit + No Melee` ban and the 15-minute draw timer ends stalls.
- ✅ **Overlay protocol v2**: `STATE_VARP` repacked with **16 reserved rule bits** (rules
  1–16, accepts 17/18, slots 19–29) so Phase 3/4 additions won't reshuffle the layout again;
  preset button column (Whip/Boxing/Save/Load/Last) added to `LofDuelOverlay`.
- ✅ **Sparring parity** (per the review below): the shared `CombatRestrictions.of(player)`
  merged view now backs every duel+spar enforcement site (styles, prayer, food, drinks,
  spec). Mirror-or-diverge decisions: `noSpec` mirrored (spar already had it);
  `noWeaponSwitch`/`funWeapons`/gear slots deliberately duel-only — sparring loadouts are
  kit-defined, so mid-bout gear games aren't a thing there.

## Phase 3 — interface fidelity (the three-screen agreement)

Ship on the client overlay (`DuelRulesClientMenu`, default-on) first; the cache grid
(`docs/duel-rules-grid.md`) follows the same contract once live-verified. Cache-exact
strings in research §2.1–2.2.

1. **Rules screen**: 13 rule rows + 11 equipment slots + preset buttons; toggles sync live
   to both players (already the overlay's model).
2. **Anti-scam UX everywhere an agreement can change** (rules screen AND stake screen):
   any mutation resets BOTH accepts, locks Accept for ~3 s ("Wait..."), flashes a marker on
   the changed row/slot, and shows "An option or stake has changed - check before
   accepting!". **Audit `TradeSession`** to confirm stake edits reset both accepts today;
   add the lockout timer to it.
3. **Confirmation screen** (the third screen) before escrow locks: opponent name, combat
   level, **individual combat stats**; "Before the duel starts:" consequence lines (worn
   items taken off / boosted stats restored / prayers stopped); "During the duel:" one line
   per rule; both stake lists with values and `K/M (exact)` expansions; Accept/Decline with
   the same change-lockout. This is where the 2016 lesson lands: longer, explicit flow =
   fewer regrets.

## Phase 4 — arena content & atmosphere

- **Obstacle pits**: mapdump-verify an obstacle-arena source area (candidates from the
  classic map: `(3367,3208)-(3386,3218)`, `(3336,3227)-(3355,3237)`) and a flat one;
  re-verify the current `ARENA_SOURCE` pit is actually obstacle-free in our rev-228 cache.
  Obstacles rule → allocate the obstacle source; enforce the No-Movement dependency.
- **Countdown polish**: overhead text "3", "2", "1", "FIGHT!" above both fighters (classic)
  instead of chat-only; hint arrow over the opponent.
- **Messages**: classic strings — "The duel hasn't started yet!", "That is not your
  opponent.", "Well done! You have defeated <name>!", forfeit/decline lines.
- **Winnings presentation**: on win, a spoils summary (opponent name, combat level, items
  won) — chat block first, overlay panel later.

## Phase 5 — social & meta layer

- **Scoreboard, virtualized**: log every resolved duel (winner, loser, rule summary, pot
  value, timestamp — forfeits marked, draws logged) to Mongo; surface as `::duels` recent
  results in-game and a page/section on the community site. Classic scoreboard feel without
  a physical hub.
- **Per-player duel stats**: wins/losses/draws, biggest pot won — website profile fodder.
- **RMT-pattern logging we keep anyway**: the duel log already records pairs and pots;
  cheap insurance even though RMT isn't a concern.
- Tournament (`exhibition` duels) picks up all new rules automatically — verify presets and
  normalization behave under `onResolved`.

---

## Companion sparring — parity & reuse (reviewed pre-build)

Sparring (`pktraining/CompanionSparring*`) is the duel challenge's no-risk sibling: a
deliberate **parallel** (`TrainingRules` ≠ `DuelRules`, no stakes/escrow/handshake) sharing
the same machinery (instanced pit, countdown, FROZEN_TIMER rooting, overlay-varp transport,
kit-editor handoffs) and mirrored one-for-one at every enforcement site (eat / potion /
prayer / spec / style bans / teleport seal). Findings that bind this plan:

- **Steal from sparring:** its two-phase overlay (SETTINGS → kit step → read-only CONFIRM
  with Accept/Back via a phase bit) is the proven pattern for Phase 3's confirmation
  screen; its overhead `forceChat` countdown ("3…2…1…FIGHT!") is Phase 4's countdown
  polish; its remembered-settings rematch is the model for richer duel presets.
- **Phase 1 applies to every rules entry path:** `DuelRulesClientMenu.accept()` already
  validates all-styles-banned and No Forfeit + No Melee, but not No Forfeit + No Movement,
  and nothing blocks No Movement + melee-restricted — with the 8-tile spawns that's the
  stalemate duel. The `DuelRules.validate()` helper must be the single gate the overlay,
  grid, and chatbox menus all call. (Sparring itself is immune: nothing staked, `::sparend`
  always exits.)
- **Phase 2 rule additions must be a mirror-or-diverge decision per rule** for
  `TrainingRules` (sparring already has `noSpec`; duels don't yet). To stop the per-site
  duplication doubling, add one shared helper — a merged `combatRestrictionsOf(player)`
  view over duel + spar rules — so each enforcement site checks once. Keeps the
  parallel-types design, kills the copy-paste.
- **Known quirk (cosmetic):** `SparringClientMenu.loadLast` aliases the remembered
  `SparSettings` by reference — live edits mutate the "last used" copy. Fine today; snapshot
  it if that ever matters.
- **Future option:** sparring's `maxStats` (all-99s, LMS-style, with crash-safe
  boost/restore blobs) would make a good house duel rule for mismatched accounts — the
  machinery already exists.

## Test checklist (the failure-mode suite, from research §3)

- Stake mutation after opponent accepts → both accepts reset + lockout (rules AND stake).
- Illegal rule combos unreachable from every entry path (overlay, grid, menus, presets).
- No Movement duels: adjacent spawn, melee connects, binding spells denied.
- Simultaneous death (tick-synced DoT or recoil) → draw, both refunded, no double-payout.
- 15-minute expiry → draw refund.
- Hard-disconnect at every phase: rules screen (declined, nothing lost), stake screen
  (trade plugin returns items), countdown and fight (forfeit-loss), after resolve (no
  stale escrow). Re-verify the crash-refund path can never double-pay alongside `resolve()`.
- Spec/boost/prayer/Vengeance carry-in: enter duel buffed → all normalized at FIGHT!;
  divine timer doesn't re-boost mid-duel.
- Equipment: disallowed gear stripped at start, re-equip denied, 2H denied under
  weapon/shield-slot restriction, accept denied when stripped gear can't fit inventory.
- Fun weapons: bare-fist attack denied; only whitelist connects.
- No Weapon Switch: weapon locked from FIGHT!, switch attempts denied.
- Forfeit: allowed-case pays opponent and marks scoreboard entry as forfeit; denied under
  No Forfeit.
- Escrow: winner inventory-full overflow to bank; huge item stakes (28 slots both sides)
  resolve correctly.
- Companions rule unchanged: benching, 4v4 seal, no companion stake leakage.
- PID: alternating/randomized processing verified (log which duelist processes first).

## Sequencing

Phases are ordered by dependency and risk: **1 → 2 → 3** are the substance (correctness →
rules → agreement UX) and each is shippable alone; 4 and 5 are parallelizable polish. The
Phase-2 rule additions should land behind the existing overlay so the grid work
(`duel-rules-grid.md`) doesn't block anything.

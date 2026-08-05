# OSRS-Accurate Death Everywhere — design + build plan (IMPLEMENTED)

**Status:** BUILT 2026-07-03 (v1: ground-pile reclaim). Compiled + live; in-game verification via
`::testdeath` (DEV_POWER) still pending across zones (Lumbridge, a road, deep wild, Fight Cave).

**What was built (decisions locked in):**
- `PvpDeathDropPlugin` un-gated: keep-N runs on EVERY player death (PkBot/Companion victims still skipped).
- New `SafeDeaths.kt` (combat package): a death is safe when the victim has the new `SAFE_DEATH_ATTR`,
  is inside an instanced map, or dies inside a registered arena (Fight Cave + Zulrah shrine pre-registered).
  Checked before any drop math.
- Reclaim = option **(a) ground pile**: safe-zone deaths spawn VICTIM-owned drops on the death tile,
  private for 1500 cycles (~15 min), then public for 300 more, then gone. Wilderness deaths unchanged
  (killer-owned private window, bot/no killer → public).
- Engine support: `GroundItem.publicDelayOverride` / `despawnDelayOverride` (-1 = world defaults) +
  `World` ground-item cycle honors them.
- Untradeables NEVER drop and don't consume a keep slot (per-item `isTradeable` from item defs).
- `::testdeath` (DEV_POWER) self-kill harness added in `PvpDeathDropPlugin`.
- Out of scope, unchanged: ironman rules, gravestone object/UI (option b), Death's Office (option c).

**Added 2026-07-04 — wilderness LOOT KEYS** (`content/economy/pk/LootKeyPlugin.kt`):
- A real player who kills a player OR a PKer bot gets the victim's lost items sealed into an
  OSRS **loot key** (cache items 26651-26655, untradeable) instead of ground drops. With a
  full inventory → normal killer-owned ground drops.
  *(2026-08-05: minting was wilderness-only at first; now EVERY player kill mints a key — safe
  zones included. The 5-key cap is gone too: the 5 key item ids are reused round-robin as
  handles, so inventory space is the only limit; the overhead icon still caps at five keys.)*
- **Claiming = the REAL OSRS Loot Chest popup** (stock cache interface **742** "Wilderness Loot
  Key", driven by `LootChestInterface.kt`): a Loot Chest object (`object.loot_chest` 43468, native
  "Loot" action) sits at the Lumbridge market's economy corner (3224,3219, south of the Bond
  Merchant). "Loot" pushes each key's contents into the five Deadman loot containers (invs
  **558-562**, tab i = key i — RuneLite gameval) and opens iface 742; the interface's own cs2
  (onLoad 5914) draws the key tabs + item grid. Server-wired buttons (comp ids from cache dump):
  9=Destroy(confirm), 20=Withdraw-all→inventory, 34=Withdraw-all→bank (whole chest), 25/26=
  Item/Note mode. Varp 1299 = total sealed value (comp 24 text, best guess). Unwired comps hit a
  chat debug logger for live discovery. A key seals max **28 stacks** (OSRS); overflow ground-drops
  at the kill. `::lootchest`/`::testkey` (DEV) = test harness. The key's "Check" lists contents;
  `::lootkeys` re-issues lost handles. Contents persist on `LOOT_KEYS_ATTR` (bson blob).
- **Dying loses unclaimed keys** (OSRS): on any non-safe death the handles are destroyed
  (inventory AND bank) and the sealed contents join the lost loot — wilderness killer gets them in
  THEIR key; safe-zone deaths put them on the victim's reclaim pile. No keep slot, no Protect Item.
- Bots killed OUTSIDE the wilderness (road highwaymen) also mint keys since 2026-08-05 (no key
  possible → kit drops on the ground as before).
- New audit tool: `gradlew :game-server:objCheck -PobjArgs="<ids>"` prints loc name/size/actions.
**Origin:** Spun off from the road-ambusher ("highwayman") work. That change added roaming PKer
bots that hunt players on the overworld roads and hit **anyone** (rank no longer protects — see
`BotBrain.eligible`). For those ambushes to carry real stakes, death must cost items **outside** the
wilderness too. The user's call: make death **the same as OSRS everywhere**, not just for bot kills.

> Quote that scoped this: *"any death should be the same as osrs. you only keep 3 unskulled unless
> you do protect item to get a 4th. if you are skulled you risk everything unless you pray to protect
> item."*

---

## The rule we're implementing (OSRS)

On death, sort all worn + inventory items by value and **keep the N most valuable**; everything else
is lost:

| State                     | Items kept |
|---------------------------|:----------:|
| Unskulled                 | 3          |
| Unskulled + Protect Item  | 4          |
| Skulled                   | 0          |
| Skulled + Protect Item    | 1          |

This is **already implemented** and correct — the only problem is it's gated to the wilderness.

---

## Current state of the code (verified)

Everything below already exists and works; we are **reusing**, not rebuilding.

- **Keep-N + skull + Protect Item math** — `PvpDeathDropPlugin.kt` (combat package). Hooks
  `onPlayerPreDeath` (runs *before* respawn), computes `keep` from skull/protect state exactly per the
  table above, sorts items by market value (`ItemMarketValueService`, falls back to cache cost), keeps
  the top N, drops the rest.
  - **THE GATE (the thing to change):** the whole plugin early-returns unless
    `PvpZones.isWilderness(victim.tile)`. So a death anywhere else drops nothing today.
  - Also early-returns when the victim is a `PkBot` (bot deaths are handled separately).
- **Skull system** — `Combat.applySkull()` + `SkullRemovalPlugin` + `SkullIcon`. Unprovoked player
  attacks in the wild set a WHITE skull for ~2000 ticks; a timer clears it; retaliation is exempt;
  `PK_PREVENT_SKULL` opt-out respected. **No change needed** — and note it's wild-only *by design*
  (OSRS only skulls you for attacking players, which only happens in the wild here).
- **Protect Item prayer** — `Prayers.kt` (`PROTECT_ITEM`, lvl 25). Activation sets
  `PROTECT_ITEM_ATTR = true`; `PvpDeathDropPlugin` reads it. **Wired and working.**
- **Killer attribution + loot ownership** — `GroundItem` has an owner UID + a private window
  (~100 cycles) before items go public. `PvpDeathDropPlugin` reads `KILLER_ATTR`; if the killer is a
  real player they get the private window, otherwise items are public immediately.
- **Respawn** — `PlayerDeathAction.kt` (game-server). Restores all skills, respawns at the
  respawn/home tile, "Oh dear, you are dead!". Runs *after* the pre-death drop hook.
- **Bot deaths** — `BotManager` / bot combat handle bot loot separately; bots are pre-skulled WHITE
  for their whole life and skipped by `PvpDeathDropPlugin`.

---

## What "full OSRS death everywhere" actually requires

The naive version — *delete the wilderness gate* — makes every PvE death (a cow, fall damage, a boss)
drop your items on the spot with **no way to get them back**. Real OSRS softens this with a
**gravestone / item-reclaim** mechanic for non-PvP deaths. So "full" = two pieces:

### 1. Un-gate the keep-N drop (small)
In `PvpDeathDropPlugin`, replace the `isWilderness(victim.tile)` gate so the keep-N calculation runs
on **every** player death. Keep the `PkBot`-victim skip. The skull/protect math is unchanged (on a
road you're unskulled → keep 3/4; in the wild the existing skull path still applies).

### 2. Add a gravestone / reclaim path for SAFE (non-wilderness) deaths (the real work)
So PvE/road deaths aren't a brutal instant loss of everything-but-3:

- On a **safe-zone** death, the lost items spawn at the death tile owned **by the victim** (not the
  killer) with a longer reclaim window (OSRS: ~15 min visible to you, then public/gone). This is the
  key difference from a wilderness death, where the **killer** owns the drops.
- On a **wilderness** death, keep today's behavior exactly: killer owns the private window (or public
  if a bot/none). PvP loot stays PvP loot.
- Decision fork — pick one:
  - **(a) Ground-pile reclaim (simplest):** lost items just sit on the death tile, victim-owned, long
    timer. No new UI. Reuses `GroundItem` ownership + timers. **Recommended for v1.**
  - **(b) Gravestone object + timer UI:** spawn a gravestone the player walks back to; optional
    blessing to extend. More faithful, much more work (object, timer overlay, decay states).
  - **(c) Death's Office / item-retrieval NPC:** items bankable at an NPC for a fee. Most modern-OSRS,
    most work. Probably overkill here.

### 3. Wire the killer/attribution for road-bot deaths
When a road highwayman kills you on a safe road, you're unskulled → keep 3. The lost items should be
**victim-owned** (reclaim path above), since a bot can't loot. Confirm `KILLER_ATTR` = the bot and
that the safe-zone branch routes ownership to the victim, not the (bot) killer.

---

## Edge cases to handle

- **Instances / minigames / already-"safe" deaths** (e.g. tutorial, certain bosses that should be
  safe): must be exempt or they'll grief. Add an explicit `SAFE_DEATH` attribute/zone allowlist and
  check it *before* the keep-N drop. **Do not** let un-gating silently turn a designed-safe death into
  a lootable one.
- **Ironman / HCIM** death rules (item loss, HCIM status loss) — decide if in scope.
- **PvE-drop ownership vs. other players griefing your gravestone** — victim-owned window must be long
  enough to walk back from respawn.
- **Companions** (`Companion : PkBot`) must never drop the owner's items or be affected.
- **Value source** — confirm `ItemMarketValueService` returns sane values for custom items so the
  "keep most valuable" sort doesn't protect junk over gear.
- **Untradeables** — OSRS keeps untradeables on death (they don't count against the 3). Verify the
  sort/keep respects tradeability, or untradeables could be "dropped" and vanish.

---

## Build order (suggested)

1. Add a `SAFE_DEATH` exemption check (attr + zone allowlist) — **before** touching the gate, so
   un-gating can't grief designed-safe deaths.
2. Un-gate `PvpDeathDropPlugin` (run keep-N on all deaths); branch ownership: wilderness → killer,
   safe → victim.
3. Implement reclaim path (a) ground-pile, victim-owned, long timer for safe deaths.
4. Verify untradeables + Protect Item + skull interactions with a `::testdeath`-style harness in
   several zones (Lumbridge, a road, deep wild).
5. (Optional) upgrade reclaim to (b) gravestone UI once (a) feels right.

## Files to touch

- `Alter/game-plugins/.../content/combat/PvpDeathDropPlugin.kt` — un-gate, ownership branch, safe-death
  exemption, untradeable handling.
- `Alter/game-server/.../action/PlayerDeathAction.kt` — only if respawn/ordering needs adjusting.
- `Alter/game-plugins/.../content/combat/PvpZones.kt` — `isSafe()` already exists; reuse for the branch.
- New: gravestone/reclaim helper (if going past ground-pile).

## Related / do-NOT-duplicate (already done)

- Skull system, Protect Item prayer, keep-N math, ground-item ownership, respawn — all built. Reuse.
- Road ambushers that make this matter — shipped in the `bots` package (`BotZones` road_* zones,
  `BotBrain` patrol + everyone-fair-game). See memory `rsps-pk-bots`.

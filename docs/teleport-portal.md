# Teleport Portal — Design Plan

A single **portal object** at the Lumbridge home opens a custom, tabbed teleport
interface (Roak-style) giving players one-click travel to monsters, the war,
skilling, PvP zones, minigames and events. Right-clicking the portal re-uses your
last teleport.

This is the marquee "travel hub" feature. v1 ships the **full custom tabbed cache
interface**, with real destinations clickable and roadmap destinations shown
greyed as **Coming Soon**, plus the three dynamic features (Previous Teleports,
Popular Now, live event timers).

Reference: modelled on Roak Pkz's "Teleports!" interface (left tab column, right
list with Location / Price / Danger columns, "Previous Teleports" history, a
dynamic "Popular Now" tab, and live "Starts in / Spawns in / Alive Now" timers).

---

## 1. Where the portal lives

- **One portal** in the **Lumbridge home courtyard**, next to the home spawn and
  the [shop hub](../Alter/game-plugins/src/main/kotlin/org/alter/plugins/content/areas/lumbridge/npcs/stores/LumbridgeShopHubPlugin.kt)
  market aisle (shops sit on rows x3219 / x3224, aisle x3220–3223; home spawn
  ≈ `3222,3217`). Proposed portal tile ≈ `Tile(3221, 3216, 0)` at the south end of
  the market aisle — **TUNE in-game** so it doesn't block the aisle or the shop NPCs.
- **Object id:** pick a portal / magic-portal object from the cache (cache-scan
  TODO — same `::aboutobj` / object-scan trick used for other content).
- **Left-click** → open the teleport interface. **Right-click → "Previous Teleport"**
  → instantly repeat your most recent destination (no interface).
- Standard guards before any teleport: not in combat / teleblocked
  ([TeleBlockPlugin](../Alter/game-plugins/src/main/kotlin/org/alter/plugins/content/magic/teleblock/TeleBlockPlugin.kt)),
  not already busy; play the teleport animation/gfx, then `moveTo`.

---

## 2. Tab structure (mapped to OUR content)

Roak is a pure PK server; we re-theme its skeleton around our PK/spawn **+ the
dystopia war**. Nine tabs:

| Tab | Theme |
|-----|-------|
| 🏠 Basics | Home, skilling, shops, safe utility |
| 🗡️ The War | Our signature tab (replaces Roak "Events") — campaigns, fronts, raids |
| 💀 Bosses | City bosses + classic bosses |
| ⚔️ Wilderness / PvP | The PK-bot corridor + future PvP zones |
| 🩸 Slayer | Slayer master + task areas |
| 🎮 Mini-Games | Fight Cave + future minigames |
| ❤️ Events | Timed server events (all roadmap for now) |
| 💎 Donator | Donor-ranked destinations (roadmap) |
| 🔥 Popular / Recent | Dynamic: top-5 used + your last 3 |

---

## 3. Destination catalog (BUILT = clickable, ☆ = Coming Soon greyed)

### 🏠 Basics
- **Home (Lumbridge)** — `gameContext.home` ≈ `3222,3217` — *Safe Zone*
- **Market / Shops** — shop-hub aisle ≈ `3221,3216` — *Safe Zone*
- **Prayer Altar** — `3242,3207` — *Safe Zone*
- **Agility Course** — by the home (see AgilityPlugin) — *Safe Zone*
- **Mine (Lumbridge cellar)** — cellar mine ≈ `3209,9620` area — *Safe Zone*
- **Forge / Anvil (cellar)** — `3209,9620` corner — *Safe Zone*
- ☆ Gambling (Flower Poker) · ☆ Dice · ☆ Blackjack · ☆ Party Zone

> Skilling note: our skills (mining, smithing, woodcutting, fishing, cooking,
> firemaking, fletching, herblore, crafting, construction, farming, hunter,
> runecraft, agility, thieving, slayer) are **all built** but spread across
> Lumbridge as world objects rather than one "Skilling Zone". v1 ships the spots
> with a clear destination (Mine, Forge, Agility); a fuller per-skill skilling
> sub-list is an easy follow-up.

### 🗡️ The War
- **Active Campaign** — current target city muster (resolves via Campaigns) — *Danger varies*
- **Varrock Raid** — hostile target city, Varrock square `3213,3424` — *Hostile*
- **North Frontier** — `WarStatePlugin.FRONTIER_NORTH` — *Wilderness*
- **Goblin Warren** — see GoblinWarrenPlugin — *Hostile*
- **Recruit Trials** — see RecruitTrialsPlugin — *Safe Zone*
- ☆ further cities as the war expands

### 💀 Bosses
- **King Black Dragon** — KbdBossPlugin lair — *Dangerous*
- **Ice Dragon** (city boss) — Lumbridge spawn `3247,3319` — *Wilderness edge*
- **Rat King** (city boss) — Lumbridge spawn `3235,3280` — *Wilderness edge*
- **World Boss** — rotating, WorldBossPlugin — *Dangerous*
- ☆ Zulrah · ☆ Barrows · ☆ Kraken · ☆ Corp Beast · ☆ Nex · ☆ Callisto · ☆ Vet'ion
  · ☆ Venenatis · ☆ Scorpia · ☆ Chaos Elemental · ☆ Chaos Fanatic · ☆ Crazy
  Archaeologist · ☆ Demonic Gorillas · ☆ Skotizo · ☆ Theatre of Blood · ☆ Chambers
  of Xeric · ☆ Revenant Caves

### ⚔️ Wilderness / PvP (the [PK-bot corridor](../Alter/game-plugins/src/main/kotlin/org/alter/plugins/content/bots/BotZones.kt))
- **Outlaw Camp** — `3212–3258, 3328–3362` — *low Wild*
- **Marauder Grounds** — *Wild*
- **Raider Fields** — *Wild*
- **Warlord's Approach** — *Wild*
- **Wilderness PKers** — `3140–3200, 3528–3640` — *deep Wild*
- **Deep Wilderness PKers** — `3140–3200, 3660–3760` — *deep Wild*
- ☆ Fun-PK Zone · ☆ Risk Zone · ☆ Edge/Brid Zone · ☆ Camelot PvP · ☆ F2P Zone
  · ☆ Mage Bank · ☆ Ferox Enclave

### 🩸 Slayer
- **Slayer Master** — SlayerPlugin master location — *Safe Zone*
- ☆ Slayer Cave · ☆ Resource Contracts hub

### 🎮 Mini-Games
- **Fight Cave** (our TzTok-Jad / Fire cape) — entry via FightCavePlugin (`::arena`) — *Safe Zone*
- ☆ Castle Wars · ☆ Last Man Standing · ☆ Duel Arena

### ❤️ Events (all roadmap)
- ☆ HP Event · ☆ Automatic Tournament · ☆ Bloodlust · ☆ Treasure Hunt
  · ☆ Clan Warfare · ☆ Vote Boss

### 💎 Donator (all roadmap)
- ☆ Donator Zone · ☆ Donator Dungeon · ☆ Royal PvM/Skilling Zone · ☆ Divine tier

### 🔥 Popular / Recent (dynamic — see §5)

---

## 4. Pricing & danger model

- **v1: everything FREE** (matches Roak's default; simplest, encourages use).
- We have point currencies (`PointKind`: BOSS, VOTE, WAR_EFFORT, PRESTIGE, DONOR)
  and **Blood Money** as an item. The Roak "500 PKP" paid-teleport pattern maps to
  Blood Money or Boss points — held in reserve for a few premium teleports later.
- **Danger tags** per destination, colour-coded like Roak:
  *Safe Zone* / *Safe Bank* / *Safe PvP* (green) and *Wilderness Lvl N* / *Hostile*
  (red). Stored on each destination so the row renders it directly.

---

## 5. Dynamic features (all three in v1)

- **Previous Teleports** — persist each player's last 3 destination keys in a
  player attribute; render bottom-left of the interface AND drive the portal's
  right-click "Previous Teleport".
- **Popular Now** — a server-wide usage counter per destination (in-memory, with
  optional persistence); the Popular tab shows the top 5.
- **Live event timers** — event rows show "Starts in / Spawns in / Alive Now"
  countdowns, fed by the existing schedulers
  ([BossScheduler](../Alter/game-plugins/src/main/kotlin/org/alter/plugins/content/war/boss/BossScheduler.kt),
  campaign director). Text refreshes on a timer tick while the interface is open.

---

## 6. Implementation phases

**Phase 0 — Data model + teleport engine (pure Kotlin, no cache).**
Mirror the proven `BossRegistry` / `BotZones` registry pattern:
- `TeleportDestination(key, displayName, category, iconSprite, dest: Tile,
  danger: DangerTag, cost: Cost?, state: Built|ComingSoon, dynamicTimer?)`.
- `TeleportRegistry` — the catalog above, one entry per destination.
- `TeleportService` — executes a teleport (combat/teleblock guards, lock, anim/gfx,
  `moveTo`), records Previous + Popular usage.
- Temporary **option-menu** entry point so destinations are testable before the
  cache UI exists.

**Phase 1 — Portal object.**
Spawn the portal at the home tile; `onObjOption` left-click → open; right-click →
previous teleport. (Object id + tile from cache scan.)

**Phase 2 — Custom cache interface (if3).**
Author the interface in the cache (displee + if3 codec, per
[custom-interfaces](../) workflow): root window + title bar + close button; left
tab column (9 tabs); right scrollable list of rows (icon sprite · name · price ·
danger columns); bottom-left Previous-Teleports list. Register sprites/icons.
Watch the known onLogin / stale-cache gotchas.

**Phase 3 — Plugin binding.**
Open interface on portal click; populate rows for the selected tab from the
registry (set text/sprite per component); row click → `TeleportService`; tab click
→ repopulate; greyed Coming-Soon rows are unclickable ("Coming soon" message).

**Phase 4 — Dynamics.**
Previous Teleports (attr) → Popular Now (usage counter + ordering) → event timers
(hook schedulers, refresh text on tick).

**Phase 5 — Polish.**
Pricing hooks (if any premium teleports), icon pass, danger-colour pass, tune
tiles/object placement in-game.

---

## 7. Locked v1 decisions

- **Menu:** full custom tabbed cache interface.
- **Scope:** built destinations clickable + greyed Coming-Soon placeholders.
- **Dynamics:** all three (Previous Teleports, Popular Now, live event timers).
- **Placement:** one portal at Lumbridge home.
- **Pricing:** everything FREE (point currencies reserved for future premium teleports).
- **Skilling:** full **per-skill** sub-list in v1 (its own `SKILLING` category — 15 skills).

## 8. Status

- **Phase 0 — DONE** (compiles clean). Pure-Kotlin core under
  `game-plugins/.../content/teleport/`:
  - `TeleportDestination.kt` — model (category, danger, state).
  - `TeleportRegistry.kt` — the full catalog (built + coming-soon).
  - `TeleportService.kt` — teleport execution + Previous (persistent attr) + Popular (server-wide).
  - `TeleportPortalPlugin.kt` — temporary `::portal` / `::portalto <key>` / `::portalback` driver.
- **Phase 1 — DONE** (compiles). The physical portal:
  - `TeleportPortalObjectPlugin.kt` — replaces the Lumbridge courtyard **Fountain**
    (cache obj 879, type 10, 2×2, base tile **3221,3226**) by spawning a portal
    (`object.portal_of_champions`, id 31618 — the large ornate "Portal of Champions")
    on the same tile+type-10 slot, which overrides the static map loc (no cache edit).
  - Click → `TeleportMenu.open`; a second model option (if any) → repeat last teleport.
  - Options bound **defensively** (only actions the model exposes) so a wrong id
    can't throw/drop the plugin.
  - `TeleportMenu.kt` — shared chat-text view used by the object + the `::portal` cmd.
  - **Boot-verify:** confirm the portal model has a click option and a sane footprint
    (`::aboutobj` on it); if the boot log warns "no click options", swap the obj id.
- **Next (Phase 2):** author the if3 cache interface (tabs + rows) and a custom
  right-click "Previous Teleport" object option.

## TODO carried into later phases

1. **Portal object id + final tile** — cache scan + in-game tune.
2. **Interface id** — claim a free if3 interface id for the teleport window (Phase 2).
3. **Tile tuning** — skilling stand-on tiles, wilderness wild-levels, war fronts (marked TUNE in the registry).
4. **Special bindings** — Fight Cave must route through its session entry (not raw teleport);
   Active Campaign / World Boss resolve dynamic spawn tiles.

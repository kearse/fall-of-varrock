# Hostile Zones — the extraction loop

> **STATUS (Team 5, September 2026): LIVE framework, one zone live.** The dormant "raid city"
> system (Falador / Al Kharid, removed in Block 1 PR #298 — both are safe towns) was rebuilt as the
> generic **Hostile Zone** framework in `content/hostilezones/`. Design authority 05 §8 / handoff
> §15: keep the loop, replace the old map assumptions, make the content placeable in the
> Wilderness, the Edgeville frontier, Rogue strongholds, fallen settlements and optional Varrock
> pockets. The first zone — **the Wild Bandit Stronghold** — ships live (operator decision
> 2026-09-02). Zones are gated by `HostileZones.LIVE`.

**The loop:** enter dangerous ground → gather loot (authored loot spots + the warned supply drop)
→ survive the garrison, the PK bots and everyone else raiding → extract (a channelled trapdoor to
a safe exit, or walk out). Death follows wilderness rules: the killer takes your loot key. Rewards
are tempting, tradeable, and never BIS or story-gated; nothing is minted (no coins, no Blood
Money) — the reward is the loot already in your pack.

## Zone kinds

| Kind | Default level | Default combat | Meant for |
|---|---|---|---|
| `WILDERNESS_FORT` | inherit depth | multi | fortified wilderness positions |
| `FRONTIER` | 10 | multi | contested frontier ground (the Edgeville frontier) |
| `ROGUE_STRONGHOLD` | inherit depth | single | rogue-held camps (the Wild Bandit Stronghold) |
| `FALLEN_SETTLEMENT` | 30 | multi | a truly fallen town (Edgeville, later) |
| `VARROCK_POCKET` | 20 | single | optional high-risk pockets of the fallen city |

A kind is flavour + defaults only (the entry banner, the level, single/multi); every config can
override them. There are no behaviour branches on the kind.

## What a zone gets, from data alone

| Piece | Owner | What it does |
|---|---|---|
| PvP zoning | `combat/PvpZones` (reads `HostileZones.all`) | The box is red; `wildLevel` fixes the PvP attack range regardless of latitude (null = the depth level, so a deep-wild box is never softer than its surroundings); `singleCombat` makes it 1v1. Ground outside `mainWilderness` becomes red through this. |
| Loot spots | `HostileLootPlugin` | Each `LootDistrict` keeps one public ground item per `LootSpot`, rolled from the district's `DropTable`; taken → rerolled `respawnTicks` later. Spots snap to walkable at boot, are audited against safe carve-outs, and only tick while a real player (never a PK bot) is within 32 tiles of the box. |
| Supply drop | `SupplyDropPlugin` | Hourly (±10 min) warned drop: broadcast names the zone + district, 5 minutes later ONE item from `rareTable` lands on a random walkable, non-safe tile there; land + claim are broadcast; unclaimed drops sit ~1 h. `supplyDrop = false` opts a zone out. |
| Occupiers | `HostileOccupierPlugin` | Each `OccupierLine` becomes a `MonsterPack` in ONE `war.HostileZone` per zone (slot-based in-place respawn, aggro, 1v1 sweep), presence-gated like the loot, regions force-loaded at world init, kill loot from the line's table spawned killer-owned. Independent of `Frontiers` / the campaign engine — a zone is not a March target. |
| Raiders | `bots/BotZones` | A `RaiderColony` appends a pinned PK-bot colony `hz_<key>` over the box. |
| Extraction points | `HostileExtractionPlugin` | Dynamic objects spawned at `extractionPoints`; the configured verb starts a 10-tick channel (cancelled by moving / damage / death, refused while teleblocked); completion moves the raider to `exitTile`, reports the haul (carried value minus the value carried on entry), broadcasts ≥ 10k gp, and books `ExtractionRecords` (`HOSTILE_EXTRACTIONS_ATTR`). |
| Banner | `HostileZoneBannerPlugin` | "You enter … — <kind>. Extract or die." / "You leave …", plus the entry-value snapshot. |

## Configuring a zone

One `HostileZoneConfig` in `HostileZoneCatalog.all` — nothing else changes:

```kotlin
HostileZoneConfig(
    key = "wild_bandit_stronghold", display = "the Wild Bandit Stronghold",
    kind = HostileZoneKind.ROGUE_STRONGHOLD,
    area = Area(3020, 3675, 3055, 3705),
    wildLevel = null,                       // inherit depth; or a fixed level
    districts = listOf(LootDistrict(key, display, area, table, spots)),
    rareTable = STRONGHOLD_RARE,
    extractionPoints = listOf(ExtractionPoint(Tile(3037, 3677, 0), "object.trapdoor_1581", "Climb-down", "the smugglers' trapdoor")),
    exitTile = Tile(3087, 3502, 0),         // MUST be outside the box (the registry requires it)
    occupiers = listOf(OccupierLine("npc.bandit_737", count = 8, combatDef = …, lootTable = …)),
    raiders = null,                         // or RaiderColony(listOf("max_main" to 2, …), target = 2)
    supplyDrop = true,
)
```

Rules that bite:

- **The registry and catalog are pure data.** Never reference `PvpZones` (it reads the registry
  lazily — a reference back is a classload cycle), `BotZones` (its object init reads the
  registry), `RogueKnights`, or `getRSCM`. Item / npc / object names stay strings.
- **One owner per npc id, server-wide.** An occupier id gets a global combat def + death handler;
  pick ids with zero rows in `data/cfg/spawns/npc_spawns.json` that no frontier line, march target,
  world-spawn pool or boss uses (the plugin skips a claimed id with a loud ERROR). The cache must
  also agree the npc is player-attackable ("Attack" option + combat level).
- **Extraction objects need the verb in the cache.** `objHasOption` is checked at boot — a bad
  id logs and leaves the points unclickable, never drops the plugin. `::hostile probe
  object.<name>` prints an object's verbs live; `gradlew :game-server:objCheck -PobjArgs="<ids>"`
  offline. In the 228 cache `trapdoor_1581` = open trapdoor with **Climb-down**; `1580` is the
  closed one with only **Open**; `tunnel_entrance` (3828) also has Climb-down.
- **Teleports are NOT gated by a zone's level.** `canTeleport` uses the vanilla wilderness level
  (x 2941–3392 / z 3524–3968), so a zone's `wildLevel` doesn't block teleports; deep-wild boxes are
  blocked by their real latitude anyway (the Wild Bandit Camp is vanilla level 19–23: standard
  teleports blocked above z 3684, glory-class still works in its south half).
- Exit tiles land on safe ground by design — that IS the reward loop. If it proves too generous,
  lengthen the channel or move the exit to a shallower red tile.
- **Economy band (Team 2):** every tradeable currently liquidates at 70% of cache cost, so a zone's
  gp/hour ≈ 0.7 × Σ(cache cost of drops/hour). Target ≈ 600–900k cache-value/hour for a solo
  camper at full uptime (under mid-tier PvM). Levers: stack sizes in the tables, the spot reroll
  delay (`SPOT_RESPAWN_TICKS`, 4 min → ≤ 240 rolls/hour over 16 spots), the number of spots.

## The supply-drop map marker

Machine lines ride the BROADCAST channel with prefix `FOV_RAID:` (hidden from chat by the client's
ticker filter) — **frozen**, the custom client's `lofsupplydrop` plugin hard-codes it:

- `WARN:<zone>:<district>:<x>:<z>:<seconds>` — district centre, at the 5-minute warning
- `DROP:<zone>:<district>:<x>:<z>` — the exact landing tile
- `CLEAR` — claimed

Late log-ins get the current phase's line replayed by the server's `onLogin` hook.

## The Wild Bandit Stronghold (live)

On the existing Wild Bandit Camp box — already red + single with the ladder's pinned maxer colony
and the knights Morvane / Halric / Nyx, so no new PvP area was created. Two districts (the Tents:
sustain; the Duty Free: the stolen armoury), 16 loot spots seeded from the camp's ambient bandit
tiles (proven walkable, auto-snapped), an 8-bandit garrison (`npc.bandit_737`, stats a shade above
the road bandits), the hourly supply drop (dragon scim / boots / med / long, rune c'bow, glory, msb,
gmaul, neitiznot), and two smugglers' trapdoors (SW corner, NE corner) exiting to an Edgeville
street — the design authority's PvP staging town. All tiles and tables TUNE.

## Edgeville as a FALLEN_SETTLEMENT — the delta (not built)

Beyond the framework: remove the whole-town Edgeville carve-out at `PvpZones.SAFE_INSIDE_RED`
(the bank stays safe via `BankSafezonePlugin`'s auto-carve — the region band covers it), an
ambient takeover pass in `WorldSpawnsPlugin` (`repopulateFallenCity` is reusable), a
`TeleportRegistry` entry (+ client mirror), and a decision against the handoff's Edgeville
PvP-academy hub (§8). Operator decision required before it is anything but a config.

## Dev commands

`::hostile` (dev) — `list` (zoning / loot / garrison / extraction status per zone), `spots [key]`,
`reset [key]` (force-refill), `muster <key>`, `extract` (complete at your tile, no channel),
`go <key>`, `drop` (= `::rarespawn`, advance the supply-drop machine), `probe <object.name>`.

## Verification

Boot: `[HOSTILE LOOT] 16 loot spot(s) armed across 1 hostile zone(s), 0 skipped`,
`[HOSTILE OCCUPIERS] 'the Wild Bandit Stronghold' npc.bandit_737: 8 muster point(s)` + force-loaded
regions, `[HOSTILE EXTRACTION] 'the Wild Bandit Stronghold': 2 point(s) spawned; bound
object.trapdoor_1581/Climb-down`, `[SUPPLY DROP] cadence armed across 1 zone(s)`, no "already has a
combat def" / "NOT player-attackable" / "has no … verb" errors. In-game: `::hostile go
wild_bandit_stronghold` → banner, `::zone` red/single, bandits muster, kill one → loot, take a
spot item → refill ~2 min, `::rarespawn` ×2 → WARN star / DROP star / claim → CLEAR, trapdoor
Climb-down → 6 s → Edgeville + haul line; hit or step mid-channel → cancelled; teleblocked → refused.

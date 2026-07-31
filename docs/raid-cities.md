# Raid Cities — the Tarkov loop

The post-apocalyptic frame the world already has — demon-held Varrock, PK bots prowling the
custom wilderness, overrun Falador — extended into an extraction-raid game loop: **raid
cities** are open-world PvP loot grounds. Gear spawns at authored street spots, a warned
supply drop lands roughly hourly, and the only safety inside the walls is the banks. Get in,
grab what you can, extract — or feed your haul to whoever kills you.

Varrock is deliberately **not** a raid city: the war owns it. Raids happen in the other
cities, each with its own gear identity:

| City | Zone | Gear theme | Wild level |
|---|---|---|---|
| Falador | `raidzones/FaladorRaid.kt` | knight armoury: white/steel/mithril → adamant/rune | 30 |
| Al Kharid | `raidzones/AlKharidRaid.kt` | scimitar ladder, gems, silks, runes | 25 |

## The rules on the ground

- **Full PvP streets.** A raid city is red ground at a fixed wilderness level
  (`RaidCityConfig.raidWildLevel`) regardless of its latitude — `PvpZones` treats the city
  boxes as wilderness even outside `mainWilderness` (Al Kharid is south of the line). Death
  on the streets follows wilderness rules: the killer takes your loot (loot-key path in
  `PvpDeathDropPlugin`, unchanged).
- **Banks are the safe pockets.** `BankSafezonePlugin` auto-carves an 8-tile safe radius
  around every bank object in its scan band (extended to cover Al Kharid's bank). Bank in
  your haul or lose it. Extra pockets can be authored per city via
  `RaidCityConfig.extractions`.
- **The occupiers hunt raiders.** City NPC populations come from the fallen-city takeover in
  `WorldSpawnsPlugin` (Falador's bandit pool; Al Kharid's new scorpion/gang pool), and every
  aggro-flagged occupier inside a raid city gets boosted per-instance aggression
  (`RaidAggro`: wider radius, faster re-target, longer interest, no level tolerance).
- **PK bots camp the loot.** Each raid city gets a dedicated `BotZones` colony pinned to the
  `T_RAIDER` tier (mithril→rune + budget PK sets — dangerous, not elite). Falador's
  wilderness grid cells also self-populate now the city is red.

## Loot spots (`RaidLootPlugin`)

Every district (`RaidDistrict`) has 8–15 authored `LootSpot`s and a themed `DropTable`
(`bosses/DropTable.kt`). Each spot keeps one public ground item rolled from the table; when
it's taken, the spot rerolls a fresh pick ~2 minutes later. Spots are snapped to walkable at
boot, audited against the safe carve-outs (loot inside a bank pocket would be risk-free),
and presence-gated — an empty city's spots don't tick.

Dev command: `::raidloot` (spot states per city), `::raidloot reset` (force refill).

## The supply drop (`RareDropPlugin`)

Roughly every hour (±10 min jitter), a server-wide broadcast names a city and district —
**"a supply drop falls on Falador — the Old Market — in ~5 minutes"** — and at zero one
high-value item from the city's `rareTable` lands on a random street tile there. Landing and
claim are both broadcast, so the whole server converges and the claimant has to run it out.
Unclaimed drops despawn after ~an hour.

Admin command: `::rarespawn` (advance warn/land immediately).

### World-map marker

Alongside the human-facing headlines, `RareDropPlugin` sends machine lines on the same
BROADCAST channel (the `FOV_INTRO:` pattern), prefix `FOV_RAID:`:

- `WARN:<city>:<district>:<x>:<z>:<seconds>` — district centre, at the 5-minute warning
- `DROP:<city>:<district>:<x>:<z>` — the exact landing tile
- `CLEAR` — claimed

The custom client's `lofsupplydrop` plugin renders these as a star on the world map
(snap-to-edge + click-to-jump): the district centre while the drop is incoming, the exact
tile once it's down. Late log-ins get the current phase's line replayed by the server's
`onLogin` hook, markers self-expire client-side if a `CLEAR` is missed, and the raw machine
lines are blanked from the chatbox (`lofannouncements` also excludes the prefix from the
ticker). Client dev command: `::supplydroptest` cycles WARN → DROP → CLEAR locally.

## Adding a city

One config entry: write a `<City>Raid.kt` (area, districts + spots + tables, aggro, rare
table — see `AlKharidRaid.kt`), add it to `RaidCities.all`, add a takeover pool +
`applyFallenX()` in `WorldSpawnsPlugin` if the city isn't already overrun, and check
`BankSafezonePlugin`'s region band covers its banks. PvP zoning, bots, loot spots and the
supply-drop rotation all pick the city up from the registry.

## Tuning notes

- District boxes and loot spots are authored approximations — verify with `::zone` /
  `::raidloot` in-game and adjust.
- `raidWildLevel` sets the PvP attack range inside the city; it may also interact with
  wilderness teleport restrictions (arguably desirable — extraction should mean walking out).
- Rare-table contents are launch-conservative (rune/dragon tier); tune value up once the
  loop is proven.

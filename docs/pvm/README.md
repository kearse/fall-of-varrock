# Team 4 — PvM & Endgame: program tracker

> Mission: make Fall of Varrock worth playing for hundreds of hours after the campaign.
> Design authority: `05_PvM_PvP_Minigames_and_Endgame_Activities.docx` and
> `02_Main_Story_Regional_Campaigns_and_Quests.docx` (September 2026) — see
> `docs/design/`. Where this file and those docs disagree, the docs win.

## Rules we build under

1. **Classic boss rule (non-negotiable).** GWD, Barrows, Zulrah, Vorkath, Jad, KBD, DKS,
   the Wilderness and Slayer bosses stay mechanically faithful to OSRS — mechanics, loot
   identity, access. Port from the Kronos rev-184 donor where one exists
   (`docs/kronos-port-guide.md`), OSRS-wiki spec otherwise. No FoV re-skins.
2. **Moons of Peril is LOCKED PLANNED preservation content**, not a redesign.
3. **FoV-original content is the innovation zone**: war bosses, Wardens, captains,
   Fallen Varrock exploration bosses, Senntisten, Zemouregal, the Convergence.
4. **Rewards**: no raw-GP piles. Common = supplies/runes/materials; uncommon = salvage /
   resources; rare = War-Forging materials; very rare = chase uniques. Classic bosses keep
   classic loot. **Every rate lands in `docs/pvm/reward-rates.md` for Team 2 review.**
5. **Fallen Varrock is never reclaimed.** Content added there is persistent, always-on,
   and independent of campaign state (the captains are the precedent).
6. Every player-facing feature ships a wiki article in the same PR (standing rule).
7. One PR per commit, stacked; each PR boot-verified headless before it is opened.

## Engine facts every PR relies on

- Three-file boss shape: `<Boss>ConfigsPlugin` (`setCombatDef` — `hitpoints`,
  `attackSpeed`, `respawnDelay`, `anims{death}` are REQUIRED or the plugin silently
  fails to load), `<Boss>Plugin` (entry, `onNpcDeath` → `DropTable` + `awardTickets` +
  `CollectionLog` + broadcast), `<Boss>CombatPlugin` (`onNpcCombat` suspend loop).
- `onNpcDeath(key)` marks the id handler-owned world-wide: generic `npc_drops.json`
  loot stops for it and ambient `WorldSpawnsPlugin` spawns of that id are pruned. Use
  fresh variant ids for any Varrock enemy.
- `CollectionLogRegistry.categories` must mirror every `log = true` drop by hand.
- `TeleportRegistry` row order == `LofTeleportsData.java` row order per category. Any
  row added/flipped needs the client mirror synced in the same PR.
- Instanced deaths are safe automatically; shared-world lairs need
  `world.definitions.loadRegions(...)` at `onWorldInit` or npcs freeze.
- Boot check: `.claude/launch.json` → `pvm-server` (port 43597), green line is
  `All N plugins loaded with no failures`.

## PR queue (status: ⬜ todo · 🔨 in progress · ✅ open/merged)

| # | Branch | Scope | Donor | Status |
|---|--------|-------|-------|--------|
| 1 | `pvm/01-barrows` | Barrows minigame: dig → crypt → sarcophagus fight (six set effects) → random tunnel crypt → chest (reward potential) → sixth-brother ambush; prayer drain; kill ledger; clog page; portal row | Kronos `activities/barrows/*` + pre-purge coords | ✅ PR open (boot-verified 341 plugins; client mirror row → deploy on merge) |
| 2 | `pvm/02-lair-bosses` | KBD, Giant Mole, Kalphite Queen (2 forms), Dagannoth Kings (+spinolyps) at real lairs, multi flags, force-load | Kronos | ✅ PR open (stacked on 1; boot-verified 344 plugins, 6 bosses + 12 spinolyps spawned) |
| 3 | `pvm/03-wilderness-bosses` | Callisto, Vet'ion (2 phases + hellhounds), Venenatis, Scorpia (+guardians), Chaos Elemental, Chaos Fanatic, Crazy Archaeologist | Kronos | ✅ PR open (stacked on 2; boot-verified 347 plugins, 7 bosses / 9 regions) |
| 4 | `pvm/04-slayer-bosses` | Kraken (+tentacles), Cerberus (+souls), Thermonuclear Smoke Devil, Skotizo (+altars), Demonic Gorillas; Abyssal Sire deferred | Kronos | ✅ PR open (stacked on 3; boot-verified 350 plugins, 7 regions, all spawns) |
| 5 | `pvm/05-moons-of-peril` | Neypotzli loop: supplies → Blood/Blue/Eclipse Moon encounters → Lunar Chest; enraged variants; sets + weapons | OSRS wiki (rev-228 cache has npcs/items/objects) | ⬜ |
| 6 | `pvm/06-fallen-varrock-pvm` | Elite undead tier, salvage piles → materials/relics, rare encounters, Arrav Intelligence assignment board, Palace exploration boss, captain kill ledger | FoV-original | ⬜ |
| 7 | `pvm/07-senntisten-expeditions` | Instanced expedition (Digsite entry): escalating rooms, materials, relics/logs, end boss; Deep Senntisten tier behind flag | FoV-original | ⬜ |
| 8 | `pvm/08-story-bosses` | Repeatable Zemouregal (after Defender of Varrock) and Convergence manifestation (after The Fracture), flag-gated | FoV-original | ⬜ |
| 9 | `pvm/09-minigames-a` | Pest Control (Void), Wintertodt | Kronos | ⬜ |
| 10 | `pvm/10-minigames-b` | Tempoross, Guardians of the Rift, Giants' Foundry, Blast Furnace, Barbarian Assault | OSRS wiki | ⬜ |

Cross-cutting, shipped with PR 1: `BossKills` per-boss kill ledger (`::kc`), the
reward-rate ledger, this tracker.

## Cross-team seams (do not edit; request)

| Owner | Files | What we need from them |
|-------|-------|------------------------|
| Core / war | `war/**` (MarchTargets, CityFrontiers, CampaignDirector, WarForge, ServiceRecord, Flags) | War Effort / Commendation credit APIs (`addPoints(WAR_EFFORT)`, `WarForge.awardCommendations`) — call, never reimplement. Salvage relic → WarForge recipe ingredient is theirs to wire once we drop the item. |
| Team 3 (story) | `quests/**` | Flag keys `quest.cursed_hero`, `quest.last_adventurer`, `quest.beneath_the_fallen_empire`, `quest.defender_of_varrock`, `quest.the_fracture` gate Arrav assignments / expeditions / deep tier / story bosses. Until set, PvM content gates on rank or is open. |
| Team 2 (economy) | drops config, shops, currencies | Reviews `docs/pvm/reward-rates.md`; owns GP-value baselines. |
| Team 5 (PvP/combat) | combat core, PvP zones, bots | Boss ports lean on `DAMAGE_TAKE_MULTIPLIER`, `BossProtection`, `bossMelee/bossProjectile`; changes there need a heads-up. |

## Deferred / explicitly not in this block

Theatre of Blood, Chambers of Xeric, Nightmare, Nex, Muspah, Grotesque Guardians,
Sarachnis (no donor, wiki builds later); Barrows tunnel-door maze (v2 of PR 1);
Castle Wars / LMS / Duel Arena (Team 5 territory); PvP Training Arena.

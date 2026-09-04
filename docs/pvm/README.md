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
  `All N plugins loaded with no failures`. **World-init hooks run ~45 s AFTER that line**
  (a Mongo-waiting hook earlier in the chain blocks the rest) — poll for your plugin's
  world-init log late, and the game-loop `Entities:` line later still.
- `PluginRepository.executeWorldInit` now isolates each hook (PR 6): a throwing hook logs
  `World-init hook #i threw` instead of silently skipping every hook registered after it.

## PR queue (status: ⬜ todo · 🔨 in progress · ✅ open/merged)

| # | Branch | Scope | Donor | Status |
|---|--------|-------|-------|--------|
| 1 | `pvm/01-barrows` | Barrows minigame: dig → crypt → sarcophagus fight (six set effects) → random tunnel crypt → chest (reward potential) → sixth-brother ambush; prayer drain; kill ledger; clog page; portal row | Kronos `activities/barrows/*` + pre-purge coords | ✅ PR open (boot-verified 341 plugins; client mirror row → deploy on merge) |
| 2 | `pvm/02-lair-bosses` | KBD, Giant Mole, Kalphite Queen (2 forms), Dagannoth Kings (+spinolyps) at real lairs, multi flags, force-load | Kronos | ✅ PR open (stacked on 1; boot-verified 344 plugins, 6 bosses + 12 spinolyps spawned) |
| 3 | `pvm/03-wilderness-bosses` | Callisto, Vet'ion (2 phases + hellhounds), Venenatis, Scorpia (+guardians), Chaos Elemental, Chaos Fanatic, Crazy Archaeologist | Kronos | ✅ PR open (stacked on 2; boot-verified 347 plugins, 7 bosses / 9 regions) |
| 4 | `pvm/04-slayer-bosses` | Kraken (+tentacles), Cerberus (+souls), Thermonuclear Smoke Devil, Skotizo (+altars), Demonic Gorillas; Abyssal Sire deferred | Kronos | ✅ PR open (stacked on 3; boot-verified 350 plugins, 7 regions, all spawns) |
| 5 | `pvm/05-moons-of-peril` | Neypotzli loop: supplies → Blood/Blue/Eclipse Moon encounters → Lunar Chest; enraged variants; sets + weapons | OSRS wiki (rev-228 cache has npcs/items/objects) | ✅ PR open (stacked on 4; boot-verified 353 plugins; v1 = fights + chest + enraged, gathering loop v2) |
| 6 | `pvm/06-fallen-varrock-pvm` | Elite undead tier, salvage piles → materials/relics, rare encounters, Arrav Intelligence assignment board, Palace exploration boss, captain kill ledger | FoV-original | ✅ PR open (stacked on 5; boot-verified 357 plugins, 11 elite posts / 27 piles / Warden / Rovin). Captain KC = one-line request to Team 1's NamedCaptainsPlugin |
| 7 | `pvm/07-senntisten-expeditions` | Instanced expedition (Digsite entry): escalating rooms, materials, relics/logs, end boss; Deep Senntisten tier behind flag | FoV-original | ✅ PR open (stacked on 6; boot-verified 360 plugins; v1 = temple region 13722: 3 waves + the Custodian, Digsite winch entry; Deep Senntisten = regions 13466/13721, v2 behind Beneath the Fallen Empire) |
| 8 | `pvm/08-story-bosses` | Repeatable Zemouregal (after Defender of Varrock) and Convergence manifestation (after The Fracture), flag-gated | FoV-original | ✅ PR open (stacked on 7; boot-verified 363 plugins, Arrav hub posted; Zemouregal 12614 + Arrav ally 12612 in an instanced palace hall, region 12854; the Convergence = Nightmare form 9425 in the instanced Digsite dungeon, region 13465; Bosses tab now 32 rows → client deploy on merge) |
| 9 | `pvm/09-minigames-a` | Pest Control (Void), Wintertodt | Kronos | ✅ PR open (stacked on 8; boot-verified 368 plugins, braziers/pyromancers + landers posted). Wintertodt region 6462 is BRIDGE-flagged: plane-1 objects register at plane 0, donor coords work as-is; PC = RaidInstance of 10536 per game, party min 1 |
| 10 | `pvm/10-minigames-b` | Tempoross, Guardians of the Rift, Giants' Foundry, Blast Furnace, Barbarian Assault | OSRS wiki | ⬜ |

Cross-cutting, shipped with PR 1: `BossKills` per-boss kill ledger (`::kc`), the
reward-rate ledger, this tracker.

**Boss encounter bug batch 2026-09-03** (`claude/boss-encounter-bugs-e52b59`, from the operator's
first play-through of the merged stack):

- **Multi-loc clicks dispatch on the CHILD id.** `ObjectPathAction` resolves
  `obj.getTransform(player)` (the varbit child) before `executeObject`, so a binding on the
  base loc never fires. Barrows chest 20973 → bind `chest_20723` [Open] / `chest_20724`
  [Search, Close]; Zulrah's boat 10068 → `sacrificial_boat` 46241 [Board] / 46242 [Board,
  Quick-Board]. `objCheck` prints the transform table; the old "bind the base by slot" note in
  the PR-1 memory is wrong.
- **Barrows tunnel gate (operator rule):** the tunnel crypt only opens once the other five
  brothers are dead; the sixth still ambushes at the chest.
- **Dagannoth Rex attack anim = 2853** (2851 is not in rev 228; `npcDef anims 2267`).
- **Npc-option clicks close modals** (`OpNpcHandler`, like `OpLocHandler`) — a modal the server
  still thinks is open parks every STANDARD queue task, so "Poke" looked dead while Attack (no
  queue) worked. Vorkath's poke also retries after 12 ticks if a wake never completed.
- **Hydra portal landing / exit → (1351,10241)** — the open floor south of the rocks (was the
  passage under them); in-instance landing snaps to walkable.
- **Callisto lives in Callisto's Den**: surface `Cave Entrance` 47140 (3291,3849) [Enter, Peek,
  Check-Fee] → region 13215 **plane 1** (17×15 chamber, `Cave` 46925 [Exit] at 3294,10190); npc
  id switched to 6503 (classic model — the skeleton the donor anims 4925/4927/4929 belong to; the
  rework's 6609 has skeletal anims the server can't verify).
- **GWD generals pay through `BossDeath.payout`** (kill ledger + `::kc`) and log every payout /
  no-credit death, so the "no drops from Zilyana" report can be read from the log next time.
  Prime suspect: companion auto-bank loot sweeps the owner's own drops when the owner is > 6
  tiles away (her 2-tick melee pushes players back).
- Tools: `npcDef seq <animId…>` (frames / delays / `cycleLength`), `objCheck` now prints
  `solid/impen/obstr/clipType` (a shape-22 floor decoration only clips when `solid == 1`).

**Boss Tickets retired 2026-09-03** (economy #336 + our `pvm/10-retire-boss-tickets`, stacked on 9):
`BossDeath.payout` has no ticket count and no boss or minigame awards tickets; `BossKills` pays the
Champion's cape / Divine halo at 100 / 500 total boss kills. Team 2 approved every reward table
as-is on the same day (`docs/pvm/cache-value-expectation.md`).

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

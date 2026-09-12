# Quest-tab replacement — handoff runbook (for a session with cache access)

> **Mission:** make the OSRS quest tab (interface 399) list *Fall of Varrock's* quests instead of
> the ~200 OSRS quests, by rewriting the quest DBTable in the game cache. This file is
> self-contained: everything a local Claude Code session (or a human) needs — context, formats,
> pitfalls, runbook — was researched in the repo and is written down here. The cloud session that
> wrote this could not touch the cache (it isn't in git; it lives on the dev machine at
> `Alter/data/cache` and on the VPS at `/opt/kol/data/cache`).
>
> Companion doc: `docs/custom-quests.md` (what the Quest Journal already does).
> **Recon is DONE (dump analysed). Phase 1 (relabel proof) is tooled and ready — start at §0.**
>
> **Current state (2026-09-12): run `sync`.** The cache half is `relabel` (our names on the reused
> rows); HIDING the other OSRS rows moved to the custom client (`lofquests` / `LofQuestTab`) — see
> Phase 2 below. The live tab was last seen listing two rows ("The Last Free City", "Demon Slayer")
> after the legacy cache-side `hide`; `sync` (= `unhide` + `relabel` + `free`) plus the client
> build in the same PR puts all 18 quests in the tab.

---

## 0. Recon findings + Phase 1 relabel (do this first)

The quest table was dumped from the live rev-228 cache (`quest-table-dump.json`) and analysed. Key
findings that shape everything below:

- **The quest table (DBTable 0) has 198 rows, one per OSRS quest.** Column meanings decoded:
  `col0` = the quest id (unique 1-198, this is what `QUEST_STATUS_GET` keys on), `col1` = hidden
  sort name, `col2` = displayed name, `col6` = category, `col7` = difficulty (0-4), `col8` =
  quest points, `col9` = release date, `col13` = start coord, `col14` = start NPC, `col16` = map
  element, `col19` ≈ completion value, `col21` = parent quest (RFD sub-quests only), `col23` =
  skill reqs, `col25` = prerequisite quests, `col37` = reward blurb.
- **There is NO varbit/varp column.** Row colour (not-started / in-progress / finished) is resolved
  by the compiled clientscript `QUEST_STATUS_GET(col0)`, which maps the quest id → that quest's
  progress varp internally. We can't repoint that to our own varp without editing cs2.
- **Therefore: reuse existing quest ids.** Relabel a simple OSRS quest's row to our quest name and
  drive that quest's varp from the server — the stock tab then colours it for us. Verified varps
  for good reuse candidates (all classic F2P, single linear varp, unused by our content):

  | Our quest | Reused OSRS quest | DBROW id | quest id (col0) | progress varp | complete val |
  |---|---|---|---|---|---|
  | The Last Free City (was "Recruit Trials" — renamed 2026-09-11; re-run `relabel`) | Cook's Assistant | 17 | 1 | **29** | 2 |
  | War-Prep I — Magic | Doric's Quest | 30 | 11 | **31** | 100 |
  | Rogue Hunting I (the 30-rogue hunt) | The Restless Ghost | 120 | 3 | 107 | 5 |
  | Rogue Hunting II (the Rogue Knight ladder) | The Knight's Sword | 83 | 14 | 122 | 7 |
  | War-Prep II — Ranged | Imp Catcher | 76 | 9 | 160 | 2 |
  | War-Prep III — Survival | Sheep Shearer | 131 | 5 | 179 | 21 |
  | King of Lumbridge | Witch's Potion | 161 | 13 | 67 | 3 |
  | The North (Main Story Quest 3 — framework quest; driven by `QuestEngine.publish` from `TheNorth.nativeTabVarp`) | Ernest the Chicken | 44 | 7 | 32 | 3 |
  | First Reclamation (Main Story Quest 4, framework quest — `QuestDefinition.nativeTabVarp`) | Romeo & Juliet | 121 | 4 | 144 | 100 |
  | A Kingdom Alone (MSQ5, framework `a_kingdom_alone`) | Rune Mysteries | 125 | 53 | 63 | 6 |
  | BREACH - Asgarnia (objective `breach`) | Black Knights' Fortress | 10 | 12 | 130 | 4 |
  | SECURE - Morytania (objective `secure`) | Prince Ali Rescue | 112 | 10 | 273 | 110 |
  | UNDERSTAND - Wilderness / Desert (objective `understand`) | Vampyre Slayer | 155 | 8 | 178 | 3 |
  | SUSTAIN - Kandarin / War Effort (objective `sustain`) | Pirate's Treasure | 108 | 16 | 71 | 4 |
  | At the White Wall (Asgarnia — BREACH, Quest 1; framework quest — `nativeTabVarp`) | Recruitment Drive (its stock start NPC is Sir Amik) | 118 | 86 | **657** | 2 |
  | A Matter of Trolls (Asgarnia — BREACH quest 2, framework `a_matter_of_trolls`; sort key "16 …") | Death Plateau | 23 | 58 | 314 | 80 |
  | The Guns of Asgarnia (Asgarnia quest 3, framework `guns_of_asgarnia`; sort "17 …") | Dwarf Cannon | 35 | 47 | 0 | 11 |
  | Old Wounds (Asgarnia — BREACH, Quest 4, framework `old_wounds`; sort "18 Old Wounds") | Wanted! (its start NPC is Sir Tiffy) | 156 | 92 | 1051 | 11 |

  Framework quests (`quests/framework/`) drive their reused varp through `QuestDefinition.nativeTabVarp`
  / `nativeTabComplete` (written by `QuestEngine.publish`, 0 / 1 / complete) — no per-quest
  `QuestJournal` code. **Sort-string gotcha:** the tab orders rows by the col1 sort STRING, so a
  "10 …" prefix sorts before "2 …" — quests past sort digit 9 need a scheme that compares correctly
  (e.g. "9a", "9b") unless every row is renumbered zero-padded — which is what `PLAN` now does
  ("01" … "07" legacy hallway, "08" The North, "09" First Reclamation, "10" A Kingdom Alone,
  "11"-"14" the four strategic objectives, "15" At the White Wall and the regional campaign quests
  after it — Trolls "16", Guns "17", Old Wounds "18"). Not usable for rows: Goblin Diplomacy, Demon
  Slayer, Misthalin Mystery, X Marks the Spot, The Corsair Curse (varbit-driven), Shield of Arrav
  (two varps).

  Rogue Hunting I & II are TWO rows off ONE server chain (`RogueProblem.Step`, varp 4617):
  `QuestJournal.syncNativeTab` completes row I the moment the hunt clears (KNIGHT step) and holds
  row II at not-started until then, completing it at DONE (the whole ladder broken). After changing
  `PLAN`, re-run `sync` (the workflow) so the tab picks up the new/renamed rows.

> **Note (2026-07):** The Rogue Problem took The Restless Ghost (varp 107) — the slot originally
> penciled in for War-Prep II — Ranged — so Ranged was re-mapped to a fresh spare quest, **Imp
> Catcher** (DBROW 76, varp 160, complete 2). All six quests now have live server chains driving
> these varps.

**The server drives all six varps** (`QuestJournal.syncNativeTab` writes each to
not-started/in-progress/complete from real quest state). So the proof is just the cache half:

> **All six FoV quests are ✅ wired** (server + `PLAN`). `RogueProblem`, `WarPrepRanged` and
> `WarPrepSurvival` each drive their reused varp (107 / 160 / 179) via `QuestJournal.syncNativeTab`
> (not-started when `NONE`, complete at `DONE`, in-progress otherwise), and each has a `Relabel` row
> in `PLAN` — so `KEPT` already lists DBROWs 17, 30, 120, 76, 131, 161. Run `sync` on the
> cache to surface them.

```powershell
# from the repo root, JDK 17. Point at the cache YOUR SERVER READS — a fresh git clone's
# data/cache is empty, so pass the install path (spaces are fine; the tool rejoins them):
$c = "C:/Program Files (x86)/Kearse RSPS/Alter/data/cache"
.\Alter\gradlew.bat -p .\Alter :game-server:questTable -PquestArgs="inspect $c"   # see current names
.\Alter\gradlew.bat -p .\Alter :game-server:questTable -PquestArgs="relabel $c"   # back up + rename
# ...restart the server (the one that serves that cache), then log in.
.\Alter\gradlew.bat -p .\Alter :game-server:questTable -PquestArgs="restore $c"   # roll back if needed
```

(Omit the path to use the default `data/cache` if you run the server from the clone with the cache
copied in. Each row's pristine bytes are backed up to `Alter/data/cache-backups/dbrow_<id>.bin`
before the first rewrite; `restore` reads them back, so run it from the same repo root.)

`relabel` backs up each row to `Alter/data/cache-backups/dbrow_<id>.bin` first, rewrites only the
two name columns (no indexed column touched → **no index rebuild, minimal risk**), and verifies by
re-decoding. It renames only the two LIVE quests (Cook's Assistant → "Recruit Trials", Doric's →
"War-Prep I — Magic"); the other OSRS quests still list unchanged — that's expected in Phase 1.

**Proof to confirm the whole mechanism works** (do before Phase 2): after `relabel` + restart, make
a fresh account. The quest tab should show "Recruit Trials" in **red**; as you talk to the Sergeant
and progress it should go **yellow**, and **green** on completion. If it colours correctly, the
col0→varp→colour chain is confirmed and Phase 2 is safe to build. If it doesn't, the status
resolution differs from the assumption above and needs a clientscript/enum dump — stop and report.

## Phase 2 — list ONLY FoV quests ✅ (the client filters; the cache keeps every row)

Confirmed on the live server (2026-07): relabelled rows show with the right names and colours.

**What changed (2026-09-12).** The first Phase 2 was the cache-side **`hide`** action — prune the
quest table's master row index (js5 index 21, archive 0, file 0) down to our rows, on the theory that
the list enumerates that index. It didn't hold up: the live tab ended up listing exactly two rows
("The Last Free City" and the never-touched "Demon Slayer"), i.e. the rev-228 list script does not
simply walk the pruned master. Rather than reverse-engineer the cs2, hiding moved to the layer that
*is* deterministic — the custom client:

- RuneLite's list builder calls its own **`QuestFilter` script (3238)** once per quest row, which
  raises the `questFilter` callback with the row's DBROW id on the int stack (this is what the stock
  Quest List search box uses). **`lofquests` / `LofQuestTab`** answers "hide" for every row that is
  not in its `ROWS` map — the same 18 DBROW ids as `PLAN`. Our rows are left to the stock filters, so
  the "hide completed/unstarted" toggles and the search box keep working. Config toggle: *Lof Quest
  Journal → Only our quests in the quest tab* (default on).
- **Row clicks** are handled client-side too: the stock op sends the server only the row's list
  position, which is meaningless once rows are filtered client-side, so the click is consumed and the
  Quest Journal window opens directly on the quest (resolved from the row's cache name, recorded
  during the filter pass). The server's `onButton(399, 7)` in `QuestBookPlugin` stays as the fallback
  for a client with the window switched off.
- `unhide` regenerates the full master row list from the rows themselves (the pristine master is
  exactly "every table-0 row, ascending" — verified against the dump), so it needs no backup file
  and also repairs a partial prune. `hide` is kept in the tool as LEGACY (prints a warning) and is
  no longer in the workflow.
- `free` clears the BOOLEAN members flag (**column 5**, indexed — DBTABLEINDEX file 6) on the four
  reused rows that were members' quests (Recruitment Drive, Death Plateau, Dwarf Cannon, Wanted!)
  and moves them from key 1 to key 0 in that index, so all 18 list under one **Free Quests** header.
  Rows are backed up like `relabel`'s; `restore` puts the index back too.

**Run it:**

```
Actions -> Quest cache relabel -> Run workflow -> sync     (= unhide + relabel + free, one restart)
```

then ship the client build from the same PR (`LofQuestTab` must know the 18 rows). Verify in-game:
the tab lists exactly the 18 quests, red/yellow/green from a fresh account onward, one "Free Quests"
header; clicking a row opens the Quest Journal on that quest. If the client log warns
`quest tab: the list was built without a single questFilter callback`, RuneLite's script-3238
override isn't applying to this cache (hash mismatch) — the OSRS rows will show; report it.

**Still open (polish, not blockers):**
- `restore` in the workflow swaps back the *whole* `runtime/cache.prerelabel` (2026-07) — every cache
  edit made since by the other workflows (item/npc/loc/terrain) goes with it. Use the tool's row-level
  `restore` (the `dbrow_*.bin` backups) instead when only the quest rows need rolling back.

**Adding a quest to the tab later:** add a `Relabel` row to `PLAN` in `QuestTablePatch.kt` (reusing
an OSRS quest's varp — §0), mirror its varp in `QuestJournal`/`nativeTabVarp`, add the same DBROW id
to `LofQuestTab.ROWS` in the client, then run `sync` and ship the client.

---

## 1. Context you need

- **Server:** Alter fork (Kotlin), OSRS rev 228, cache loaded from `Alter/data/cache`, served to
  the client over js5. The custom client is a RuneLite 1.10.51 fork in `client/`.
- The quest tab is **not server-driven**. Rev 228 clientscripts render interface 399 from a cache
  database: quest metadata in **DBTable 0** — the server only supplies per-quest progress varps
  and the summary varbits.
- Our custom quests already have server state + a client Journal (PR #9 + follow-ups): varps
  **4610** (Recruit Trials, packed), **4611** (War-Prep step), **4612** (guidance mute). The quest
  tab work must reuse these varps so tab colours track real progress.
- The quest root pane is already wired server-side: `CharacterSummaryPlugin.kt` opens root 629
  with 399 mounted, seeds placeholder count varbits (lines ~28-31, marked `@TODO`), and arms
  clicks on 399's component 7 (`setInterfaceEvents(399, 7, 0..198)`) with **no onButton handler
  yet**.

## 2. Cache facts (verified against the repo's codecs)

All in js5 terms; the repo's own libraries read/write these:

| Thing | Where | Codec in repo |
|---|---|---|
| Table schema | index 2 (CONFIGS), archive **39** (DBTABLE), file = tableId (**0** = quests) | `Alter/plugins/filestore/.../decoder/DBTableDecoder.kt`, `encoder/DBTableEncoder.kt` |
| One row per quest | index 2, archive **38** (DBROW), file = rowId; row carries `tableId` (opcode 4) + typed column values (opcode 3) | `DBRowDecoder.kt` / `DBRowEncoder.kt` |
| Row indexes | index **21** (DBTABLEINDEX), archive = tableId, **file 0 = master index** (all rows), file N = index on column N-1 | none in openrune — format ported into `QuestTableDump.kt`; write support must mirror it |

- **DBTableIndex file format** (from RuneLite's `DBTableIndexLoader`, also implemented in the dump
  tool): `varint tupleCount`, then per tuple: `byte BaseVarType` (0 int / 1 long / 2 string),
  `varint valueCount`, per value: typed key, `varint rowCount`, rowIds as varints.
  **The clientscripts iterate quests via the master index — if you change the row set and don't
  rewrite index 21 archive 0, the tab will still show/miss the old rows.**
- Known column ids (from `client/runelite-api/.../dbtable/DBTableID.java`): **2 = quest name
  (string), 16 = map element, 21 = main-quest category.** The rest (difficulty, quest points,
  members, progress varp/varbit binding, sort name…) exist in the schema but their ids must be
  read from the dump — do not guess them.
- Quest-list colouring: the row's progress var (bound in one of the columns) vs the "in
  progress"/"complete" text-colour varps 3409-3411 (`game-api/cfg/Varp.kt`).

## 3. Runbook

### Phase 1 — recon (READ-ONLY, tooled, run this first)

```powershell
# from the repo root, JDK 17 (see root README)
.\Alter\gradlew.bat -p .\Alter :plugins:tools:dumpQuestTable
# cache elsewhere? add -Pcache=D:\path\to\cache   output: Alter/quest-table-dump.json
```

The tool (`Alter/plugins/tools/.../QuestTableDump.kt`) prints and JSON-dumps: the full table-0
schema (every column id, types, defaults), every quest row's values, row counts for every other
table in the cache, and the decoded master/column indexes for table 0. **Read the dump before
writing any patch code.** Answer from it:
1. Which column binds the progress varp/varbit? (Look for int columns whose values look like
   varbit/varp ids across rows.)
2. Which columns are indexed (which `columnN` index files exist), and what the master index's
   tuple shape is — the patch must reproduce exactly that shape.
3. Which columns are mandatory vs defaulted (schema defaults).

### Phase 2 — patch design (do after reading the dump)

Write a `patchQuestTable` sibling task that, **operating on a copy**:
1. Backs up `data/cache` (zip) before anything.
2. Removes all DBROW files whose `tableId == 0` (keep a printed manifest).
3. Adds one row per FoV quest — reuse two EXISTING row ids for Recruit Trials / War-Prep so any
   id-based script assumptions stay valid; bind their progress column to varps 4610/4611 (or
   dedicated new varbits if the schema wants varbit ids — the dump will tell).
4. Rewrites index 21 archive 0: master (file 0) listing exactly the new row ids, plus each
   column index file present in the dump, same tuple shapes.
5. Commits via the CacheTool flow (`CacheTool.buildCache`: temp copy → task → `library.update()`
   → `rebuild` → copy back), or replicate it — displee needs `update()` + `rebuild` or the
   .idx files won't match.
6. Server side: replace the `@TODO` counts in `CharacterSummaryPlugin.kt` (total quests = our
   quest count, completed = derived from varps 4610/4611) and add `onButton(399, component 7)`
   → message/journal for the clicked quest.

**Codec pitfall:** `DefinitionDecoder.loadSingle` NPEs (reads into an empty map) and
`DefinitionDecoder.files()` touches the global `CacheManager`. Do what `QuestTableDump.kt` does:
subclass the decoder and drive the protected `read(map, id, reader)` with a pre-seeded map.

### Phase 3 — verify (in game, before touching the VPS)

1. Boot the server on the patched cache; log in with the custom client.
2. Quest tab: only FoV quests listed; colours red/yellow/green must track a fresh account
   advancing (varp 4610 moves as the trials progress).
3. Regression: item/npc/object configs untouched (only archives 38/39 in index 2 + index 21
   changed); bank, shops, world map all fine; a vanilla-ish RuneLite client should also still log
   in (cache CRCs changed → client redownloads those archives; login failure = a bad rebuild).
4. Only then copy the cache to the VPS (`/opt/kol/data/cache`) alongside a dated backup, restart.

### Fallbacks if the tab fights back

- The stock filter varbits can *hide* OSRS entries today as a stopgap (`HIDE_QUESTS=13774`,
  `HIDE_COMPLETED_QUESTS=13777`, `HIDE_UNSTARTED_QUESTS=13776`, headers `13889`) but cannot
  rename/add rows.
- Worst case (clientscript hostile to a tiny table): keep the OSRS rows out of sight via filters
  and lean on the client-side Quest Journal sidebar (already shipped) as the quest UI.
- Caution echo from `lofteleports`: *brand-new* cache interfaces didn't render on this client;
  editing rows an existing script already renders is a different, safer operation — but verify
  with one renamed row before deleting everything.

## 4. Success criteria

- Quest tab lists exactly: Recruit Trials, War-Prep I — Magic, The Rogue Problem, War-Prep II —
  Ranged, War-Prep III — Survival, King of Lumbridge, with correct state colours from a fresh account
  through completion.
- Quest points / count varbits in the summary tab show FoV numbers, not placeholders.
- No other content regressions; both FoV and stock clients still log in.
- The patch task is re-runnable (idempotent) so future quests are one config entry + re-run.

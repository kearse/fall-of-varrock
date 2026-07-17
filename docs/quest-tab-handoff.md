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
  | Recruit Trials | Cook's Assistant | 17 | 1 | **29** | 2 |
  | War-Prep I — Magic | Doric's Quest | 30 | 11 | **31** | 100 |
  | War-Prep II — Ranged | The Restless Ghost | 120 | 3 | 107 | 5 |
  | War-Prep III — Survival | Sheep Shearer | 131 | 5 | 179 | 21 |
  | King of Lumbridge | Witch's Potion | 161 | 13 | 67 | 3 |

**The server already drives the first two varps** (`QuestJournal.syncNativeTab` writes varp 29/31
to not-started/in-progress/complete from real quest state). So the proof is just the cache half:

> **Follow-up — "The Rogue Problem" (Act II) native-tab row.** The Act II quest
> `RogueProblem.kt` is live and fully drives the **custom client's Quest Journal** (its own lofquests
> varp 4617 — arrows + panel work today). It does **not** yet have a native quest-tab row: pick a
> spare reuse quest below (the **War-Prep II — Ranged** slot, quest id 3 / varp 107, is a natural fit
> since Ranged is still unbuilt, or reserve a fresh one), relabel it to "The Rogue Problem" here, and
> add the matching `QuestJournal.syncNativeTab` write from `RogueProblem.step` (not-started when
> `NONE`, complete at `DONE`). Server + cache halves both needed for the stock tab to colour it.

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

## Phase 2 — list ONLY FoV quests ✅ (tooled: the `hide` action)

Confirmed on the live server (2026-07): relabelled rows show with the right names and colours. Phase
2 is the **`hide`** action — run it after `relabel`:

```
Actions -> Quest cache relabel -> Run workflow -> hide
```

**How it works (simpler than a full rebuild).** The rev-228 quest list enumerates rows via the quest
table's **master index** (js5 index 21, archive 0, file 0) — one key mapping to every row id. `hide`
rewrites just that row list down to our kept rows (17, 30). The other ~196 rows' *data is left
intact* (not deleted), so the per-column indexes stay valid and it's fully reversible — they're just
no longer reachable from the list. `QuestTablePatch.decodeIndex`/`encodeIndex` handle the index byte
format (§2); it backs the master index up and verifies by re-decode. Rollback is the `restore` action
(swaps the whole pristine `runtime/cache.prerelabel` back).

**Still open (polish, not blockers):**
- The summary-tab counts in `CharacterSummaryPlugin.kt` (`@TODO` ~line 28-31) still show placeholder
  totals — set them to our quest count for a tidy "quests completed" line.
- An `onButton(399, 7)` handler could open the sidebar Journal when a quest row is clicked.
- To add more quests to the tab later: add rows to `PLAN` in `QuestTablePatch.kt` (each reusing an
  OSRS quest's varp — the §0 table has three more mapped), mirror their varps in `QuestJournal`, then
  re-run `relabel` + `hide`.

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

- Quest tab lists exactly: Recruit Trials, War-Prep I — Magic (+ future teasers if desired as
  greyed rows), with correct state colours from a fresh account through completion.
- Quest points / count varbits in the summary tab show FoV numbers, not placeholders.
- No other content regressions; both FoV and stock clients still log in.
- The patch task is re-runnable (idempotent) so future quests are one config entry + re-run.

# Quest-tab replacement — handoff runbook (for a session with cache access)

> **Mission:** make the OSRS quest tab (interface 399) list *Fall of Varrock's* quests instead of
> the ~200 OSRS quests, by rewriting the quest DBTable in the game cache. This file is
> self-contained: everything a local Claude Code session (or a human) needs — context, formats,
> pitfalls, runbook — was researched in the repo and is written down here. The cloud session that
> wrote this could not touch the cache (it isn't in git; it lives on the dev machine at
> `Alter/data/cache` and on the VPS at `/opt/kol/data/cache`).
>
> Companion doc: `docs/custom-quests.md` (what the Quest Journal already does).
> **Phase 1 (recon) is tooled and ready to run — start at §3.**

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

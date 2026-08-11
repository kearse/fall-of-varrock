# Custom Quests — the Quest Journal & the road to a real quest tab

> How Fall of Varrock's custom quest line is surfaced to players: what's built (the client
> **Quest Journal** + server state feed), and the researched plan for the two bigger steps —
> replacing the OSRS quest tab's contents with our quests, and growing the Journal toward full
> Quest-Helper-grade guidance. Status tags: ✅ built · 🔶 partial · ⬜ planned.

---

## 1. The problem

The quest line (Recruit Trials → War-Prep → … → King of Lumbridge) is custom, but nothing in
the game *shows* it: the quest tab still lists ~200 OSRS quests we don't have, and a player
mid-chain has only a hint arrow and chat messages — no overview of why they're doing this, what
they've done, what's ahead, or what each quest unlocks. There's also no way to switch the
guidance off and just free-play.

## 2. What's built ✅

### 2a. Server → client quest feed (`QuestJournal` / `QuestJournalPlugin`, Alter)

`org.alter.plugins.content.quests.QuestJournal` publishes each chain's live state into varps the
custom client reads (same transport as the war HUD — no custom packets):

| Varp | Contents |
|------|----------|
| **4610** | Recruit Trials, packed: bits 0-5 step ordinal, bits 6-9 goblin kills, bit 10 contract taken |
| **4611** | War-Prep chain step ordinal |
| **4612** | 1 while quest guidance is muted (free play), else 0 |
| **4617** | Rogue Hunting I + II (one shared chain), packed: bits 0-5 step ordinal, bits 6-11 rogues felled on HUNT |
| **4624** | War-Prep II — Ranged, packed: bits 0-5 step ordinal, bits 6-11 enemies felled with a ranged weapon on FIELD |
| **4681** | War-Prep III — Survival step ordinal, bits 0-5 (was 4643 — collided with the kit editor's 4640-4679 block) |
| **4633** | King of Lumbridge (endgame conquest) step ordinal, bits 0-5 |

A 3-tick world poll re-derives these from the persistent attributes (which stay the source of
truth) and only writes on change. **Custom-varp registry so far:** 4600 siege alert · 4601 war
progress · 4602-4605 PK stats · 4606 wilderness level · 4607 teleport menu · 4608 LMS HUD ·
**4610-4612 quests** · 4617 quests (rogue) · 4620-4623 CW timer · **4624 quests (War-Prep II)** ·
**4633 quests (King of Lumbridge)** · 4640-4679 kit editor · 4680 companion sparring ·
**4681 quests (War-Prep III)** · **4682 quests (Rogue Knight ladder)** · **4683 Quest Journal
window open-pulse** (`QuestBook.OPEN_VARP`) — the quest trio moved off 4643-4645, which sat inside
the kit editor's block and made `::kits` pop the quest journal. Claim the next one here when you
add a system, and cross-check the master map in docs/overlay-design-system.md §8 FIRST.

### 2b. Free-play toggle (`::questguide`)

`QUEST_GUIDE_MUTED_ATTR` (persistent). While muted, `RecruitTrials.updateHintArrow` and
`WarPrepChain.updateHintArrow` draw nothing, so no server arrow follows the player around;
progress still advances silently. Toggled by `::questguide` or the Journal's header button.

### 2c. The client Quest Journal (`lofquests` plugin, custom RuneLite client)

A first-party sidebar plugin (book icon) modelled on the RuneLite **Quest Helper** plugin
(BSD-2, ported arrow rendering credits in `LofArrow.java`):

- **Quest list** coloured like the OSRS quest tab (red / yellow / green, grey for locked), with
  per-quest progress (`6/11`). All seven chain quests (Recruit Trials, War-Prep I — Magic, Rogue
  Hunting I, Rogue Hunting II, War-Prep II — Ranged, War-Prep III — Survival, King of Lumbridge)
  are fully wired — the two Rogue Hunting quests window one server chain (varp 4617); the
  FUTURE-teaser render path (dimmed "coming soon" rows) stays available for the next unbuilt quest.
- **Per-quest card**: the "why" blurb, the step checklist (✓ done / ➤ current+detail / ○ ahead,
  live counters like goblin kills and Prayer level), and **what it unlocks**.
- **Track / Stop tracking**: the player chooses which quest (if any) draws client-side guidance
  — a scene arrow + tile outline over the objective and a minimap arrow (rim-edge arrow when the
  target is far), Quest-Helper style. Auto-tracks the active quest until the player untracks.
- **Guidance arrows ON/OFF** header button = the server mute (sends `::questguide`, mirrors varp
  4612) — one click to free-play.

**Adding a quest** (e.g. War-Prep II when it lands): give the chain a varp in `QuestJournal`
(server), then add a `LofQuest` entry with the same step ordinals, targets, why-text and unlock
list. The panel and overlays pick it up automatically.

## 3. Replacing the OSRS quest tab contents 🔶 (recon tooled — see the handoff)

> **Execution runbook:** [`quest-tab-handoff.md`](quest-tab-handoff.md) — self-contained for a
> session/dev with cache access. Recon is done (dump analysed); the **Phase 1 relabel proof** is
> tooled: `gradlew :game-server:questTable -PquestArgs="relabel"` renames the reused OSRS quest
> rows to FoV quests, and the server already drives their colour varps. Read the handoff §0.

**Can we?** Yes. The quest tab (interface 399, mounted in quest root 629 — see
`CharacterSummaryPlugin.kt`) is rendered **client-side from a cache DBTable**, not sent by the
server. Rev 228 stores quest metadata in DBTable 0 (`DBTableID.Quest` in runelite-api: column 2
name, 16 map element, 21 category); the tab's clientscripts iterate its rows and colour each by
the quest's progress varp/varbit.

**Plan:**
1. Use the in-repo openrune filestore encoders (`Alter/plugins/filestore/.../encoder/
   DBTableEncoder.kt`, `DBRowEncoder.kt`) to rewrite the quest DBTable in the game cache: delete
   the OSRS rows, add one row per custom quest pointing each at one of our varps (4610/4611…).
2. Repack + re-CRC the cache; the client picks it up via js5.
3. Server: replace the hardcoded quest-count varbits in `CharacterSummaryPlugin.kt:28-31`
   (marked `@TODO`) with real counts. Row clicks are now handled: `QuestBookPlugin`'s
   `onButton(399, 7)` maps the clicked row to a quest and pulses `QuestBook.OPEN_VARP` (4683),
   which the `lofquests` client opens as the custom **Quest Journal window**
   (`LofQuestBookOverlay`, styled after the Feudal Ranks screen). The same window is opened by
   `::quests`, the per-quest status commands (`::rogueproblem` / `::warpranged` / `::warpsurvival`),
   and a sidebar toolbar button.
4. The stock quest-list *filter* varbits (13774/13776/13777/13889) can hide categories today as
   a stopgap, but can't rename or add entries — the DBTable edit is the real fix.

**Blockers / cautions:** the cache isn't in this repo (owner-held, Jagex-derived); do the edit
against a copy and keep a backup — a bad config archive CRC bricks login. Test on one quest row
first. Note `lofteleports`' experience that *brand-new* cache interfaces didn't render on our
client — editing rows an existing clientscript already renders is a different, safer operation,
but verify in-game before deleting all the OSRS rows.

## 4. Growing toward full Quest-Helper guidance ⬜

Research findings from the real plugin (github.com/Zoinkwiz/quest-helper, BSD-2 — legal to
clone/modify with attribution): ~694 files, of which ~446 are OSRS quest *data* and a ~150-200
file core framework (step tree with `ConditionalStep` branching, 76 requirement classes, 8
overlays, sidebar). Its yellow arrow is **drawn by the plugin, not `setHintArrow`**; paths are
**hand-authored `WorldPoint` lists** per step (`WorldLines`), with true pathfinding delegated to
the separate Shortest Path plugin via `PluginMessage` (whose collision data is baked from the
OSRS map — custom regions would need regenerated data).

Upgrade steps for our Journal, in rough order of value:
1. **Hand-authored walking paths** per step (port `WorldLines` — scene + minimap polylines).
2. **NPC/object highlighting** (outline the Sergeant/Vannaka/altar via `ModelOutlineRenderer`).
3. **Item requirements** per step with inventory ticks (port `ItemRequirement` essentials).
4. **Branching steps** (port `ConditionalStep`) once quests get non-linear.
5. **Shortest Path integration** for real click-to-walk routing (needs its map data regenerated
   for our custom areas, or bounded to vanilla terrain).

## 5. File map

| Piece | Path |
|-------|------|
| Server feed + mute | `Alter/game-plugins/.../content/quests/QuestJournal.kt`, `QuestJournalPlugin.kt` |
| Mute attribute | `Alter/game-server/.../model/attr/Attributes.kt` (`QUEST_GUIDE_MUTED_ATTR`) |
| Arrow gating | `RecruitTrials.updateHintArrow`, `WarPrepChain.updateHintArrow` |
| Client plugin | `client/runelite-client/.../plugins/lofquests/` (9 files + panel icon) |
| Varp contract | `LofQuestVarps.java` ↔ `QuestJournal.kt` (must stay in sync) |
| Quest registry | `LofQuest.java` (step ordinals must match the server enums) |

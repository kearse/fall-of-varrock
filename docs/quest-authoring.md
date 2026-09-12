# Quest authoring — turning a Block-2 brief into code

> The quest framework (`Alter/game-plugins/.../content/quests/framework/`, Block 1 PR-8/9) and
> the hooks around it. Read `docs/custom-quests.md` §6 for the file map; this page is the
> **how**. Design authority: `docs/design/` (the September-2026 handoff docs).

---

## 0. The sentence every brief reduces to

> *"Start a Campaign, check meaningful participation, advance the journal, award Veteran, unlock
> the next objective."*

In code:

```kotlin
// (Illustrative: "First March" is the next opening quest — see docs/quests/README.md. The Last Free
// City itself is NOT a framework quest; it runs on the legacy RecruitTrials chain, key `recruit_trials`.)
object FirstMarch : QuestDefinition(key = "first_march", displayName = "First March", chainIndex = 7) {

    override val prerequisites = listOf(
        Prerequisite.QuestComplete("recruit_trials"),         // legacy chain keys work — The Last Free City
        Prerequisite.RankAtLeast(Title.SOLDIER),
        Prerequisite.WarEffortAtLeast(50),
    )

    private var opKey: String? = null // per-launch ledger key (see step "march")

    override val steps = listOf(
        QuestStep("brief", Objective.TalkTo("Report to General Zo.", "npc.melee_combat_tutor"),
            anchor = GeneralZoPlugin.ZO_TILE, anchorNpc = "npc.melee_combat_tutor"),

        // "Start a Campaign": the step OPENS a public march when entered…
        QuestStep("march", Objective.Predicate("Fight in the march on the goblin camp — it sets out now.") { p ->
            opKey?.let { WarEvents.didParticipate(p, it, minShare = 5) } == true   // …"meaningful participation"
        }, anchor = Tile(3254, 3234, 0),
            onEnter = { p ->
                when (val r = WarEvents.startPublicOperation(p.world, "goblin_camp")) {
                    is WarEvents.StartResult.Started -> opKey = r.opKey
                    is WarEvents.StartResult.Busy -> p.message("The knights are already in the field — ::march and fight with them.")
                    else -> p.message("The march could not set out; try again shortly.")
                }
            },
            nudge = "::march rallies you to the column; you need a real share of the fighting."),

        QuestStep("report", Objective.TalkTo("Report the victory to General Zo.", "npc.melee_combat_tutor"),
            anchorNpc = "npc.melee_combat_tutor",
            rewards = listOf(Reward.WarEffort(25))),
    )

    // "award Veteran, unlock the next objective"
    override val completionRewards = listOf(
        Reward.Flag(Flags.Known.VETERAN_OF_VARROCK),   // NOT in Block 1 — the first major assault awards it
        Reward.UnlockRoute("varrock_sewers"),          // TransportRoutes flag `route.varrock_sewers`
        Reward.Coins(50_000),
    )

    init {
        talk("npc.melee_combat_tutor", "brief") { p ->
            chatNpc(p, "…", npc = getRSCM("npc.melee_combat_tutor"), title = "General Zo")
            QuestEngine.satisfy(p, this@FirstMarch, "brief")
        }
        talk("npc.melee_combat_tutor", "report") { p -> /* … */ QuestEngine.satisfy(p, this@FirstMarch, "report") }
    }
}
```

Register it once, in any plugin's `init`: `QuestRegistry.register(FirstMarch)`, and make sure
the NPC's click is routed: `bindTalk("npc.melee_combat_tutor")` (idempotent — General Zo still
has his own `onNpcOption` today; migrate him to `bindTalk` + an `NpcTalk` default branch first,
exactly as the Recruiting Sergeant was in PR-9).

## 1. The seams, one line each

| Need | Call | Where it lives |
|---|---|---|
| "Has the player done X?" (any quest, legacy or new) | `QuestRegistry.isComplete(p, "warprep_magic")` | `framework/QuestRegistry.kt` |
| Gate a quest on rank / service / milestone | `Prerequisite.RankAtLeast`, `WarEffortAtLeast`, `FlagSet(Flags.Known.VETERAN_OF_VARROCK)` | `framework/Objective.kt`, `mechanics/Flags.kt` |
| "Is this player eligible for Lord yet?" | `RankEligibility.check(p, Title.LORD)` (empty list = yes) | `war/RankEligibility.kt` |
| React to a rank-up | `RankEvents.onRankBought(priority) { p, title -> … }` (framework quests auto-begin on rank-up already) | `war/RankEvents.kt` |
| Start a public war op / join it / check participation | `WarEvents.startPublicOperation`, `WarEvents.join`, `WarEvents.didParticipate` | `war/events/WarEvents.kt` |
| Read a player's lifetime service | `ServiceRecords.of(p)` (`::service`) | `war/events/ServiceRecord.kt` |
| A private map copy for a scripted fight | `QuestInstances.enter(p, sourceArea, exit, landing, …)`, `instance.spawnNpc(...)` | `framework/QuestInstances.kt` |
| Bench the companion somewhere | `CompanionPolicy.denyArea` / `denyInstanceOf` / `register { owner, tile -> … }` (quest instances already deny) | `companion/CompanionPolicy.kt` |
| Lock/unlock a route | `TransportRoutes.register("key", "locked line", gate?)`, `Reward.UnlockRoute("key")`, `TeleportDestination(routeKey = "key")` | `teleport/TransportRoutes.kt` |
| Dialogue on a shared NPC | `talk(npcKey, stepId) { … }` (quest priority) / `NpcTalk.register(npcKey, PRIORITY_DEFAULT)` / `NpcTalk.placeholder` | `framework/NpcTalk.kt` |
| Guidance arrow | `QuestStep.anchor` / `anchorNpc` (mutes honoured) | `framework/QuestArrows.kt` |
| Journal row in the client | `chainIndex` (+ a `LofQuest` entry, same step order) and optionally `journalVarp` from the reserved block 4686-4699 | `quests/QuestBook.kt`, `docs/overlay-design-system.md` §8 |
| Row in the stock quest tab | `nativeTabVarp` / `nativeTabComplete` = a relabelled OSRS quest's varp (`QuestTablePatch.PLAN` row + `QuestJournal` constants); `QuestEngine.publish` drives it 0 / 1 / complete | `docs/quest-tab-handoff.md` §0 |
| Start a public war and react to its result | `WarEvents.startPublicOperation(world, WarType.GRAND_MARCH, "varrock_outskirts")` on the step, then `WarHooks.onOperationEnded { r -> … r.participated(username, 1) … }` → `QuestEngine.advanceTo` (never poll `didParticipate` — a stale ledger entry from an earlier march on the same ground would pass the step) | `quests/story/FirstReclamation.kt` (the worked example) |
| A shared-world outpost with a personal unlock | the outpost plugin spawns for everyone; the quest pays `TransportRoutes.unlock` + a `Flags` flag; a `TeleportRegistry` row carries the `routeKey` | `war/outposts/SouthernWatch*.kt` |

## 2. Rules that keep the world consistent

1. **Mutate before you narrate.** State changes (`QuestEngine.satisfy/advance`, flags, rewards)
   go BEFORE the last `chatNpc` — a `p.queue{}` dialogue dies on death, logout, attack or an
   object click; the state must not.
2. **Never `onNpcOption` a shared NPC.** It throws at construction if the cache npc lacks the
   verb and drops the whole plugin; a second bind on the same npc throws too. `bindTalk` +
   `NpcTalk` branches only.
3. **Kills**: framework quests count on the additive `onAnyNpcDeath` list via `KILLER_ATTR`
   (companion kills already credit the owner). Never `onNpcDeath(id)` — it is one-owner.
4. **Instances**: `QuestInstances.enter` ends any previous instance of the owner's; `end()` is
   idempotent; death/logout teardown never double-teleports (the engine already moved them).
   Tag your spawns (an `AttributeKey<Boolean>`) and filter `KillNpcs` on the tag so only the
   arena's npcs count.
5. **Participation is never rank-gated.** A quest may START a war (`WarEvents`); it never asks
   for a rank to JOIN one. Rank gates come from `RankEligibility`/`Prerequisite.RankAtLeast`
   on the quest itself, not on the war.
6. **Optional content** sets `optional = true` — `::quests` never points a player at it while a
   main-road quest is unstarted.
7. **Every player-facing quest ships its wiki article in the same PR** (`Alter/web/content/wiki/`).
8. **Verify** with `::questdebug begin <key>` → walk it → `::questdebug dump`; `::questdebug varps`
   before/after any change near the legacy chains must be identical; boot must print
   `[quests] registry: 7 legacy chains, N framework quests` with N incremented.

## 3. Quest keys (prerequisites)

Framework: `the_north` (**The North**, Main Story Quest 3 — `docs/quests/the-north.md`; the first
built framework quest, and the reference for the journal varp + native-tab row path:
`journalVarp` from the 4686 block + `nativeTabVarp`/`nativeTabComplete` on the definition, both
written by `QuestEngine.publish`). Its gate is `Prerequisite.Custom`: `first_march` once that key
is registered, else `recruit_trials` — copy the pattern when the quest before yours is not built yet.

Legacy: `recruit_trials` (**The Last Free City**, Main Story Quest 1 — `docs/quests/the-last-free-city.md`)
· `warprep_magic` · `rogue_hunting_1` (optional) · `rogue_hunting_2` (optional) · `warprep_ranged` ·
`warprep_survival` · `king_of_lumbridge`. Framework story quests: `the_north` (Main Story Quest 3),
`first_reclamation` (Main Story Quest 4 — `docs/quests/first-reclamation.md`), `a_kingdom_alone`
(Main Story Quest 5).

Every new quest spec starts from the integration-first template in `docs/quests/README.md`.

## 4. Not yet built (Block 2 adds as needed)

Branching steps (a `ConditionalStep` — First Reclamation fakes its battle ⇄ retry loop with
`QuestEngine.advanceTo`), party instances, the Veteran-of-Varrock award (the first major
assault story event), the `NpcTalk` migration for Vannaka (still on his own `onNpcOption` bind).

Built since (The North + First Reclamation, 2026-09-12): General Zo routes through `bindTalk` + a
default `NpcTalk` branch; framework quests publish to the client journal through the generic
`LofQuest` entry (varp `& 0xFF` = 1-based step, bits 20-21 = state; `LofQuestVarps.NORTH` = 4686,
`FIRST_RECLAMATION` = 4687) and to the native tab through `QuestDefinition.nativeTabVarp`; the
first locked route (`southern_watch`, unlocked by First Reclamation); and `QuestEngine.pollTick`
auto-begins an `autoBegin` quest the moment its gate opens mid-session, so the next quest starts
without a relog.

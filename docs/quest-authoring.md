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
object TheLastFreeCity : QuestDefinition(key = "last_free_city", displayName = "The Last Free City", chainIndex = 7) {

    override val prerequisites = listOf(
        Prerequisite.QuestComplete("warprep_magic"),          // legacy chain keys work
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
            QuestEngine.satisfy(p, this@TheLastFreeCity, "brief")
        }
        talk("npc.melee_combat_tutor", "report") { p -> /* … */ QuestEngine.satisfy(p, this@TheLastFreeCity, "report") }
    }
}
```

Register it once, in any plugin's `init`: `QuestRegistry.register(TheLastFreeCity)`, and make sure
the NPC's click is routed: `bindTalk("npc.melee_combat_tutor")` (idempotent — General Zo still
has his own `onNpcOption` today; migrate him to `bindTalk` + an `NpcTalk` default branch first,
exactly as the Recruiting Sergeant was in PR-9).

## 1. The seams, one line each

> **Prefer the facade**: `content/core/` (`WarApi`, `QuestApi`, `RankApi`, `WarEffortApi`,
> `RealmSuppliesApi`, `VeteranApi`, `CompanionApi`, `StateApi`) wraps every row below with a stable
> signature — see `docs/core-api.md`. The internals are listed so you can read them, not so you
> import them.

| Need | Call | Where it lives |
|---|---|---|
| Record a story beat / quest state by key (framework, or a raw beat before its quest exists) | `QuestApi.record(p, "story.arrav", "met")`, `QuestApi.state(p, key)` | `core/QuestApi.kt` |
| Only the followed quest drives the arrow | `QuestApi.follow(p, key)` / `::quests follow <key>` | `framework/QuestFollow.kt` |
| Start a **public** campaign/conquest on Varrock from a story event (free, no sponsor) | `WarApi.startPublicWar(world, WarType.CAMPAIGN, "varrock")` | `war/events/WarEvents.kt` |
| React when any war ends (shares included) | `WarApi.onEnded { r -> … r.participated(name, 5) }` | `war/events/WarHooks.kt` |
| Award / check Veteran of Varrock | `VeteranApi.award(p, reason)` / `VeteranApi.has(p)` | `war/Veteran.kt` |
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

## 3. Legacy quest keys (prerequisites)

`recruit_trials` · `warprep_magic` · `rogue_hunting_1` (optional) · `rogue_hunting_2` (optional) ·
`warprep_ranged` · `warprep_survival` · `king_of_lumbridge`.

## 4. Not yet built (Block 2 adds as needed)

Branching steps (a `ConditionalStep`), party instances, client journal entries for framework
quests (the additive `LofQuest` constructor), the Veteran-of-Varrock award (the first major
assault story event), any locked route (none registered), `NpcTalk` migrations for Vannaka and
General Zo (still on their own `onNpcOption` binds).

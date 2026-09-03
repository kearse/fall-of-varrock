# Core API — the seams the other teams call

> **Team 1 (Core Systems & War) contract, 2026-09-03.** One documented entry point per system,
> in `Alter/game-plugins/src/main/kotlin/org/alter/plugins/content/core/`. Import from `content.core`;
> everything behind it (`war/`, `companion/`, `quests/framework/`, `economy/Currencies`,
> `mechanics/Flags`) may keep moving. Design authority: `docs/design/` + the September-2026
> docs *03 Ranks, War & Core Systems* and *06 Development Authority*.

## 0. The invariants (read these before the tables)

| # | Rule | Enforced where |
|---|---|---|
| 1 | **War Effort is never spent, traded or lowered by gameplay.** It is a lifetime record. | `PointKind.WAR_EFFORT.spendable = false`; `addPoints` ignores negatives; `spendPoints` refuses; only `WarEffortApi.adminSet` / `::setpoints` can lower it (WARN-logged). |
| 2 | **Realm Supplies are the one shared consumable.** No per-player balance. Only Campaign and Conquest drain it; marches, Grand Marches, Lord operations and story-called public wars never do. Zero supplies never stops ordinary play. | `RealmSupply`, `WarType.supplyCost`, `WarAuthority.launch`. |
| 3 | **Starting a war is rank-gated; joining never is.** Lord → operation, Minister → campaign, King → conquest. `::march` / `WarApi.join` are open to every citizen. | `WarAuthority.check` (start), nothing on join. |
| 4 | **Every victory is temporary.** No territory capture, no liberation, no district control, no permanent Varrock. | No such state exists; copy says "the field is won". |
| 5 | **A player fields their whole roster at once (up to three), at every rank and donor tier.** Rank/donor only scale the roster; the muster price ladder (10M / 100M / 500M) pays for the extra soldiers. Operator decision 2026-09-02, re-confirmed 2026-09-03 — it overrides docs 03 §5 / 06 §3's "one active". | `CompanionRegistry.ACTIVE_MAX = MAX` (3), `Title.roster`, `RecruitMenu.RECRUIT_COSTS`. |
| 6 | **Quests accelerate ranks; they never gate or grant them.** A quest may pay War Effort; promotion always runs `RankEligibility`. | `RankApi.promote` == Duke Horacio. |
| 7 | **Veteran of Varrock is awarded by the story only**, never auto-grants Minister, thresholds OPEN. | `Veteran.award` has no content caller; `RankEligibility` Minister slot unenforced. |
| 8 | **Companions' fighting credits their owner** (one share, one ledger entry). PK bots never share. | `CampaignDirector.recordParticipation`, `BossLoot`, `MarchPlugin`. |
| 9 | **Don't reach past the facade.** If a facade lacks something you need, ask Team 1 to add it there rather than importing `war/`… directly. New persistent facts go through `StateApi` flags or a facade, never a new `AttributeKey` per feature. | code review. |

## 1. Conceptual call → real call

| Brief said | Call | Notes |
|---|---|---|
| `addWarEffort(player, amount)` | `WarEffortApi.add(p, amount, source)` | `source` is a short log tag (`"rogue_camp"`). Fires `onEarned`. |
| `getWarEffort(player)` | `WarEffortApi.get(p)` / `atLeast(p, n)` | |
| react to War Effort earned anywhere | `WarEffortApi.onEarned(priority) { p, amount -> }` | Covers all nine existing earn sites too. |
| `addRealmSupplies(amount)` | `RealmSuppliesApi.add(world, amount, contributor?)` | With a contributor the hand-in is filed in `::service`. |
| `consumeRealmSupplies(amount)` | `RealmSuppliesApi.consume(world, amount, who, what)` | Broadcast; never below zero. |
| `canStartCampaign(player)` / `canStartConquest(player)` | `WarApi.canStartCampaign(p)` / `canStartConquest(p)` / `canStartOperation(p, target)` | Reasons: `WarApi.whyNot(p, type)` → player-facing lines; `WarApi.check` → typed `WarAuthority.Denial`s. |
| a player commands a war | `WarApi.start(world, p, type, target?, onResult?)` | Same charge + launch as the commands. |
| `startPublicWar(type, objective)` | `WarApi.startPublicWar(world, WarType, objective, onResult?)` | Sponsor-less: free, supply-free, anyone joins. `MARCH`/`GRAND_MARCH` take a march-target key, `CAMPAIGN`/`CONQUEST` a hostile city key (`"varrock"`). `LORD_OPERATION` is not public. |
| join the live war | `WarApi.join(p, world, confirmed)` | Returns `NeedsConfirm` on hot ground — call again with `confirmed = true`. |
| did they meaningfully fight? | `WarApi.didParticipate(p, opKey, minShare, mustWin)` | `opKey` from `WarApi.opKeyFor(type, objective)` or the `Started` result. |
| react when any war ends | `WarApi.onEnded(priority) { r: WarHooks.WarResult -> }` | `r.type`, `r.targetKey`, `r.opKey`, `r.won`, `r.sponsor`, `r.shares` (username → %), `r.participated(name, minShare)`, `r.lootPool`. Fires after the ledger and payout. |
| `recordQuestState(player, quest, state)` | `QuestApi.record(p, questKey, state)` | `state` = a step id, `QuestApi.COMPLETE` or `QuestApi.RESET`. Framework quest → the engine (rewards, journal, arrow). Unregistered key → a raw beat in the same blob. Legacy chains are read-only. |
| read quest state | `QuestApi.state(p, key)` / `isComplete` / `isStarted` / `objectiveLine` | Works for legacy, framework and raw keys. |
| drive a framework quest | `QuestApi.begin` / `satisfy(p, key, stepId?)` / `complete` / `addCounter` | |
| the followed quest (arrow) | `QuestApi.follow(p, key)` / `followed(p)` / `unfollow(p)` | Players: `::quests follow <key>`. Only the followed quest drives the server arrow. |
| `hasVeteranOfVarrock(player)` | `VeteranApi.has(p)` | Award from the story: `VeteranApi.award(p, reason)`; test: `::veteran grant`. |
| `canDeployCompanion(player)` | `CompanionApi.canDeploy(p): DeployCheck` (`Ok / BelowRank / NoRoster / AllFielded / FieldFull / Denied`) | `CompanionApi.ACTIVE_MAX` is the roster ceiling (3). Keep companions out of your content with `denyArea` / `denyInstanceOf` / `deny(rule)`. |
| rank / authority | `RankApi.rank(p)`, `atLeast`, `canCommand(p, CommandTier)`, `eligibility(p, title)`, `promote(p, title)`, `onRankChanged(priority) { p, title -> }` | |
| shared unlock / state | `StateApi.flag / setFlag / clearFlag`, `registerRoute`, `routeOpen`, `unlockRoute` | Prefix your keys (`story.`, `region.kandarin.`, `pvp.`). Reserved: `quest.<key>.done`, `route.<key>`, `veteran_of_varrock`. |
| add a march target from your plugin | `WarApi.registerMarchTarget(MarchTarget(...))` | From your plugin `init`; order-free; keys unique; ids with zero rows in `npc_spawns.json`. |

## 2. The war, end to end (what a story brief reduces to)

```kotlin
// A quest step opens a public campaign on Varrock, waits for a real share, then awards Veteran.
QuestStep("assault", Objective.Predicate("Fight in the assault on Fallen Varrock — it begins now.") { p ->
    opKey?.let { WarApi.didParticipate(p, it, minShare = 5) } == true
}, onEnter = { p ->
    when (val r = WarApi.startPublicWar(p.world, WarType.CAMPAIGN, "varrock")) {
        is WarEvents.StartResult.Started -> opKey = r.opKey
        is WarEvents.StartResult.Busy -> p.message("The realm is already in the field — ::march and fight with them.")
        else -> p.message("The assault could not set out; try again shortly.")
    }
})

// Somewhere in a plugin init — award Veteran to everyone who mattered in ANY won campaign/conquest on Varrock.
WarApi.onEnded { r ->
    if (r.won && r.targetKey == "varrock" && (r.type == WarType.CAMPAIGN || r.type == WarType.CONQUEST)) {
        r.shares.filterValues { it >= 5 }.keys.mapNotNull { world.getPlayerForName(it) }
            .forEach { VeteranApi.award(it, "the assault on Fallen Varrock") }
    }
}
```

Tiers at a glance (`WarType`):

| type | who starts | coins | supplies | target key |
|---|---|---|---|---|
| `MARCH` / `GRAND_MARCH` | the realm (scheduled) or a story event | free | none | a march target (`WarApi.marchTargets()`) |
| `LORD_OPERATION` | Lord+ | 500k, not refunded | none | a march target |
| `CAMPAIGN` | Minister+ | 3M stake, back on a win | 1,500 | a hostile city (`WarApi.hostileCities()`) |
| `CONQUEST` | King | 15M stake, back on a win | 2,800 | a hostile city |

Exact costs and rank thresholds are OPEN balance questions (Team 2 owns reward values).

## 3. Persistence — where each thing lives

| Thing | Store | Key |
|---|---|---|
| War Effort | player attr | `war_effort_points` |
| Realm Supplies, march counter, patron queue | `data/saves/world/war_state.json` (v3; timer flush + shutdown flush) | — |
| Rank | player attr | `player_title` (ordinal) |
| Service ledger (`::service`) | player attr, bson blob | `service_record` |
| Quest states (framework + raw beats) | player attr, bson blob | `quest_states` |
| Followed quest | player attr | `quest_followed` |
| Flags (veteran, routes, quest done, your prefixed facts) | player attr, sorted CSV | `player_flags` |
| Companions (roster, gear, xp) | player attr, bson blob | `companions` |

Adding a persistent field: prefer a `StateApi` flag (boolean) or a field in an existing blob; a new
`AttributeKey<Int/String>("stable_key")` also persists (declared anywhere; the save layer matches
the string). Never `Double`.

## 4. Admin / test surface

`::coreapi` (every facade's read-side for you) · `::publicwar <march|grand|campaign|conquest> <target>` ·
`::veteran [grant|revoke] [name]` · `::setpoints war_effort <n>` (admin override, logged) · `::setsupply <n>` ·
`::wintest [pool]` · `::marchnow [target] [grand]` · `::questdebug …` · `::demoquest` · `::quests follow <key>`.

## 5. Not built here (by design)

Exact Minister/King thresholds; Senntisten / the Fracture; quest dialogue; district systems; any
defensive war; Commendation / Boss Ticket removal (Team 2); Veteran awarding from content; client
journal categories (server field only); donor roster slots (`CompanionApi.rosterCap` is the seam).

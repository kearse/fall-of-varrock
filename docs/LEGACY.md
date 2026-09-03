# Design docs — what is authoritative and what is legacy

> Kept current from Block 1 (2026-09-02). When two documents disagree, the **design authority**
> wins; a legacy doc is history and implementation notes, not a spec.

## The design authority (September 2026)

Converted from the owner's `.docx` handoff (the docx files are canonical; the markdown under
`docs/design/` is a plain OpenXML conversion for grep-ability):

| Doc | What it decides |
|---|---|
| `docs/design/design-authority-dev-handoff.md` | The current world model: offensive-only war (public Marches / Grand Marches, Lord operations, Campaigns, Conquests), Varrock permanently fallen (no district pressure or liberation), War Effort = personal lifetime service vs Realm Supplies = shared stockpile, the rank ladder as earned standing (participation open, only starting wars rank-gated), one active companion, Rogue Knights optional, war-forging untied from city state, Falador / Al Kharid as ordinary towns, classic bosses untouched. |
| `docs/design/development-block-1-scope.md` | Block 1 — Systems & Foundation: the reconciliation this repo shipped as PRs #296–#305 (`block1/*` branches). |
| `docs/design/master-story-and-quest-plan.md` | The story spine and the Block-2/3 quest briefs (The Last Free City, Senntisten, Arrav …) — **not yet built**; the quest framework (`docs/quest-authoring.md`) is what they are authored against. |

## Legacy docs (banners inside each)

| Doc | Status | Why |
|---|---|---|
| `docs/rsps-master-design-brief.md` | LEGACY | The pre-authority "mastermind" brief. Falador/Al Kharid framing and the defensive command are gone; ranks are earned standing. Its onboarding / War-Contracts / supply-loop sections still describe live systems. |
| `docs/war-system-design.md` | LEGACY | The defensive siege engine (AttackDirector, WarFront, Strategist …) was deleted in PR #296 — git tag `pre-block1-siege-engine` keeps it recoverable. The offensive war lives in `war/MarchPlugin`, `war/MarchTargets`, `war/CampaignCommandPlugin`, `war/CampaignDirector`. |
| `docs/hostile-zones.md` | LIVE | The extraction loop rebuilt as the generic Hostile Zone framework (`content/hostilezones/`, Team 5 PR-D, 2026-09-03); first zone live at the Wild Bandit Stronghold. Falador and Al Kharid stay safe towns. |
| `docs/story-and-grind-design.md` | PARTLY RETIRED | District pressure / liberation (§2, §5) are retired — Varrock stays fallen; districts are location identity only (`war/VarrockDistricts.kt`). The Rogue Problem (§4) is now optional. War-forging (§6) still stands. |
| `docs/raids-framework.md` | CORRECTED | Only `RaidInstance` ever existed of the R0 core it described; `QuestInstances` is the per-player instance lifecycle now. |
| `docs/custom-quests.md` §1–§5 | LIVE (journal contract) + §6 the framework | The six legacy step-machine chains it documents are the pre-Block-2 onboarding hallway, wrapped by `quests/framework/LegacyChains.kt`. |

## Legacy code (tagged in KDoc)

| Code | Status |
|---|---|
| `war/recruit/RecruitTrials`, `war/warprep/WarPrepChain`, `WarPrepRanged`, `WarPrepSurvival`, `war/roguehunt/RogueProblem`, `war/Conquest` | LEGACY quest chains — live onboarding until Block-2 quests replace them; presented through `QuestRegistry` by `LegacyChains`; rank hooks via `LegacyRankHooks`. |
| `hostilezones/` (was `raidzones/`) | LIVE framework; zones are data in `HostileZoneCatalog`, gated by `HostileZones.LIVE`. |
| `war/VarrockDistricts.kt` | Location identity only (captain posts, flavour) — no pressure. |

## Where the new seams are

`docs/quest-authoring.md` (the brief-to-code walkthrough) · `docs/custom-quests.md` §6 (framework map) ·
`war/RankEligibility.kt` · `war/RankEvents.kt` · `war/events/WarEvents.kt` · `war/events/ServiceRecord.kt` ·
`companion/CompanionPolicy.kt` · `teleport/TransportRoutes.kt` · `mechanics/Flags.kt` ·
`docs/overlay-design-system.md` §8 (varps; 4686-4699 reserved for framework quest journals).

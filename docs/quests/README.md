# Quest specs — the integration-first template

> The build is assumed finished. A quest is a **progression layer over what already exists**:
> dialogue, quest state, objective tracking, existing-system checks, rewards. It is not allowed
> to invent replacement gameplay for something the game can already do, and it does not get a
> new NPC, location, map, object or mechanic merely because it would make the script prettier.
> Design authority: `docs/design/master-story-and-quest-plan.md`; framework: `docs/quest-authoring.md`.

## The reuse hierarchy (cheapest first)

1. Existing NPC, in their current location
2. Existing map, unchanged
3. Existing enemy
4. Existing item / object where reasonable
5. Existing gameplay system / activity (War, rank, War Effort, supply, Slayer, transport, bosses…)
6. New dialogue + quest state
7. Temporary spawn or tiny scripted interaction
8. Existing map used as a quest instance
9. New NPC / item **only if necessary**
10. Custom map / mechanic **only when the payoff justifies the development**

The custom-development budget is reserved for the moments that define Fall of Varrock (Battle
for Varrock, the Arrav encounters, The Cursed Hero, Deep Senntisten, The Fracture). Everything
else — the opening chain, the regional campaigns, the Council — is written *against the build*.

## How a spec is written

1. **Integration audit first.** Before any dialogue: for each beat, name the existing NPC → its
   current tile → the existing activity/system → the code seam the quest attaches to (file +
   function). If a beat has no seam, the beat changes, not the game.
2. Write only dialogue and objectives that sit on those seams.
3. Fill in the development section below. Every quest spec carries it, in this order.
4. Ship the wiki article and the client journal entry in the same PR (`docs/quest-authoring.md` §2).

## The development section (every quest)

| Field | What we document |
|---|---|
| Start NPC | Existing NPC and current location |
| NPCs Used | Existing whenever possible |
| Locations | Existing unchanged locations |
| Gameplay Used | Existing FoV/OSRS systems |
| Dialogue | New dialogue required |
| Quest State | Progression variables / triggers |
| Journal | Objective text per state |
| System Hooks | War, rank, War Effort, supply, boss, transport, etc. |
| Temporary Content | Only temporary spawns/objects if required |
| New NPCs | NONE by default |
| New Maps | NONE by default |
| World Changes | NONE by default |
| New Mechanics | NONE by default |
| Development Cost | Reuse / Script / Light Custom / Custom |

## Specs

| # | Quest | Status | Spec |
|---|---|---|---|
| 1 | The Last Free City | **BUILT** (2026-09-11) | [the-last-free-city.md](the-last-free-city.md) |
| 2 | First March | proposed | — |
| 3 | The North | proposed | — |
| 4 | First Reclamation | proposed | — |
| 5 | A Kingdom Alone | **BUILT** (2026-09-12) | [a-kingdom-alone.md](a-kingdom-alone.md) — opens the regional phase (BREACH / SECURE / UNDERSTAND / SUSTAIN) |

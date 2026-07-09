# Duel Rules Grid — live-verify runbook

The faithful clickable Duel Arena rules screen: a custom if3 cache interface (group **1020**)
authored by `DuelRulesCacheTool`, driven by `DuelRulesScreen`, routed by `DuelArenaPlugin`.
Everything is **code-complete and compiled**; what remains is the cache-author + live-client
verify loop, which needs a human at the client (this path has crashed clients before — see
the custom-interfaces memory/gotchas).

The flow ships **OFF** (`DuelArena.useRulesGrid = false`): challenges use the chatbox rule
menus until the grid is verified. Nothing breaks if the cache was never authored.

## One-time author (server STOPPED)

1. Stop the game server (cache file lock). Close the client too.
2. From the `Alter` dir:
   ```
   gradlew :game-server:duelRulesIface -PduelRulesArgs="build"
   ```
   Expect `>>> AUTHORED OK — 69/69 components verified.`
3. Optional geometry dump: `gradlew :game-server:duelRulesIface -PduelRulesArgs="inspect"`
   (or `panelIface -PpanelArgs="inspect data/cache 1020"` for the richer dump).

## Verify at the client

4. Start the server; fully close + relaunch RSProx (the client must fetch the new JS5 group).
5. Log in on an admin account and run **`::duelgridtest`** — opens the grid solo (self-vs-self).
   - **Crash/logout on open?** Read `C:\Users\zakea\.runelite\logs\client.log` FIRST:
     - `ArrayIndexOutOfBoundsException: Index 1020` → client interface array too small (shouldn't
       happen; 1020 < 1102) — pick a lower free gid, re-author, bump `DuelRulesScreen.IFACE`.
     - `NullPointerException` in the draw → a component-id gap or a bad component; re-run the
       tool's `inspect` and compare against `DuelRulesCacheTool`'s id map.
   - **Opens but blank/misrendered?** Stale JS5: bump the group id (e.g. 1021), re-author (step
     1–2), update `DuelRulesScreen.IFACE` + `DUEL_RULES_IFACE`, rebuild, full client restart.
6. In the test screen: click several **rule rows** (green tick should toggle), click **Accept**
   (status text should update; it can't complete solo), then **Decline** (screen closes).
7. Two-account test: `::duelgrid` (turns the grid flow ON), then mutual **Challenge** → the grid
   opens for BOTH → toggles sync live to both → any toggle resets accepts → both Accept → stake
   screen opens with the chosen rules → duel enforces them.

## Ship it

8. Once verified, make it permanent: in `DuelArena.kt` set `var useRulesGrid = true`
   (the `::duelgrid` command stays as a kill-switch), rebuild, and update the wiki article
   (`duel-arena-staking.md` — replace the "quick rules menus" wording + drop the coming-soon note).

## File map

| Piece | File |
| --- | --- |
| Cache tool (authors group 1020) | `game-server/src/main/kotlin/org/alter/tools/duelrules/DuelRulesCacheTool.kt` |
| Gradle task | `game-server/build.gradle.kts` → `duelRulesIface` |
| Server driver (sessions/toggles/accept) | `game-plugins/.../content/minigames/duel/DuelRulesScreen.kt` |
| Routing + flag + test commands | `game-plugins/.../content/minigames/duel/DuelArenaPlugin.kt` |
| Feature flag | `DuelArena.useRulesGrid` (default false) |

Component-id contract: the constants in `DuelRulesScreen` MUST match `DuelRulesCacheTool`
(69 components, contiguous 0..68; hit layers 54–68 are the only clickable shapes).

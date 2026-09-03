# Economy arbitrage audit — findings and fix plan (Team 2, 2026-09-03)

> Team 2 (Economy & Markets) first deliverable, per design docs 04 §6/§17 and 06 §7: audit every
> NPC value loop **before** any price floor is set. The numbers below come from the re-runnable
> auditor (`gradlew :game-plugins:economyAudit`, see §7) against the live cache at commit
> `a0d4eb45` + this branch; the generated tables are in `economy-arbitrage-audit.md` / `.json`
> next to this file. Re-run after every reprice — the JSON diff is the proof.

## 1. Headline

The economy has **one structural hole**, and it is the whole skilling economy:

> NPC systems price every item from the cache `cost` field, on both sides of a recipe. For most
> mid/high-tier Smithing, Fletching, gem-cutting, Herblore and Runecrafting recipes the *output's*
> cache cost is far more than the summed cache cost of its NPC-sold *inputs*. Because an NPC will
> buy any tradeable output back at 70% of its cost — the General Store for anything under 5,000 gp,
> the Trading Post for anything at all — and because the GE commodity backstop sells unlimited
> bars, ores, logs, gems and essence at 100% of cost, every one of those skills is a coin printer
> bounded only by click rate.

Eight loops clear 1,000,000 gp/hour sustained on the crude tick model; sixteen more clear 100,000.
None of them needs a drop, a boss or another player.

| Sev | Loop (all inputs from NPCs) | Buy for | Sell for | Profit/unit | gp/h (sustained) |
|---|---|---|---|---|---|
| S0 | GE bars → smith **adamant platebody** → Trading Post | 3,200 | 11,648 | 8,448 | ~11.9M |
| S0 | GE bars → **mithril platebody** → Trading Post | 1,500 | 3,639 | 2,139 | ~3.0M |
| S0 | GE bars → **adamant kiteshield** → Trading Post | 1,920 | 3,807 | 1,887 | ~2.7M |
| S0 | GE magic logs + bow string → **magic longbow** → General Store | 325 | 1,792 | 1,467 | ~2.1M |
| S0 | GE bars → **adamant full helm** → General Store | 1,280 | 2,464 | 1,184 | ~1.7M |
| S0 | GE bars → **steel platebody** → General Store | 500 | 1,400 | 900 | ~1.3M |
| S0 | GE magic logs → **magic shortbow** → General Store | 325 | 1,120 | 795 | ~1.1M |
| S0 | GE yew logs → **yew longbow** → General Store | 165 | 896 | 731 | ~1.0M |
| S1 | GE essence (4 gp) → Runecraft 99 → **2 law runes** → General Store | 2 | 168 | 166 | ~0.9M |
| S1 | shop uncut diamond → cut → **diamond** → General Store | 200 | 1,400 | 1,200 | ~0.3M (stock-capped) |
| S1 | Stylist **sleeping cap** (300) → General Store | 300 | 1,400 | 1,100 | ~0.26M (stock-capped) |
| S1 | shop irit seed → farm (10 s) → herblore → **super attack(3)** → General Store | 8 | 125 | 117 | ~28k |

The full list (91 loops: 8 S0, 16 S1, 40 S2, 16 S3, 11 INFO) is in the generated report; the
`related` column folds in the same loop seen from earlier inputs (ore → bar → platebody is one loop).

**The second, smaller hole** is the Stylist: 20 clothing lines are shelf-priced at 250–400 gp with
cache costs of 450–2,000, so the General Store buys them straight back for more (the only pure
`Shop A → Shop B` loops found).

## 2. Loop classes — what was found, by the operator's list

| Class | Live loops | Verdict |
|---|---|---|
| NPC buy → craft → NPC sell | **69** | The hole. Every skill with an NPC-sold input and a 70% NPC sink. |
| NPC buy → convert → NPC sell | 2 | Superheat gold/silver ore (GE) → bars → Crafting shop buyback; small. |
| Shop A → Shop B | **20** | All Stylist → General Store (explicit prices below 70% of cost). |
| Shop → high alch | 0 | Alch at 60% never beats a 70% buyback and never beats a shelf price; safe **while** every shelf ≥ cost. |
| GE NPC floor → shop | 0 | The GE *floor* (70%) equals every shop's buyback, so nothing arbitrages between them. The GE *ceiling* (NPC sells at 100%) is the raw-material tap behind the craft loops above. |
| Trading Post → shop | 0 (n/a) | The Trading Post has no NPC stock; it only buys (at 70% of anything). It is the cash-out, not a source. |
| currency → item → GP | 0 live, **6 prevented** | Justiciar (1,200 tickets = 1.2M gp → 4.2M at 70%), blade of Saeldor, DFS, wyvern shield: all blocked, but see §3. |
| item → currency → item | 0 live | Relics wing buyback (tickets) only loops via the guarded Justiciar route. |

**Hypotheses from the pre-audit reading that the numbers refute** (good news):

- Vote-ticket wares (gilded set, potions) are **not** loopable: 60 tickets × 2,000 gp = 120,000 gp buys a gilded platebody whose best NPC liquidation is 45,500 gp.
- Blood-Money wares are **not** loopable: Vesta's chainbody costs 15,000 BM ≈ 12,000,000 gp and liquidates for 350,000.
- Bird snare (25 gp) and box trap (75 gp) are priced **above** cache (5 / 32), not below.
- No unexplained converter binds, all 15 recipe adapters read cleanly, the 765-ware snapshot reconciles 1:1 with the live `ItemCurrency` pricing expression.

## 3. Guards that are load-bearing today (regression list)

Six ticket-priced wares would be 0.5M–3M gp loops if a single guard moved. The guard that stops the
biggest ones is **not** `SpecialShopGuard` — it is the General Store's `cost > 5,000` refusal,
because the store pays 70% while alch pays 60%:

| Item | Ticket price → gp | Unguarded liquidation | Blocked by |
|---|---|---|---|
| Justiciar chestguard | 1,200 tickets = 1,200,000 | 4,200,000 (General Store 70%) | `GeneralStore.cost>5000` |
| Justiciar legguards | 1,100,000 | 3,150,000 | `GeneralStore.cost>5000` |
| Justiciar faceguard | 900,000 | 1,400,000 | `GeneralStore.cost>5000` |
| Blade of Saeldor | 1,500,000 | 3,000,000 (alch) | cache-untradeable |
| Dragonfire shield | 500,000 | 1,200,000 (alch) | cache-untradeable |
| Ancient wyvern shield | 600,000 | 1,200,000 (alch) | cache-untradeable |

Implication: `GeneralStoreCurrency` must consult `SpecialShopGuard` (it does not today), and the
Boss-Ticket price of these four items sits **below** 70% of their cache cost — the ticket removal PR
must not leave them purchasable for gp at those numbers.

## 4. Root causes

1. **Cache `cost` is not a value system across a recipe.** It is Jagex's shop-price field. In OSRS
   nothing buys an adamant platebody for 70% of it; the GE market does. Here three NPCs do.
2. **Universal 70% NPC buyback with no ceiling.** `TradingPostCurrency` buys any tradeable; the
   General Store buys anything under 5,000 gp cost; every themed `BUY_STOCK` shop buys its own wares
   back. The 70% rate was designed as a *floor for the player market*, but with no cap it is a
   *price for the NPC market*.
3. **The GE backstop's ceiling side is an unlimited raw-material tap.** `GrandExchangeCommodities`
   lets the NPC *sell* ~90 items at 100% of cost, including every bar, ore, log, uncut gem and rune
   essence. The floor side (NPC buys at 70%) is the intended skiller protection; the ceiling side is
   what feeds the S0 loops. Notably, essence at 4 gp makes Runecrafting a 2 gp → 168 gp converter.

The Stylist loops are simpler: explicit shelf prices were chosen for feel and never checked
against `cost × 0.7`.

## 5. Fix plan (PR queue) and the rule the auditor will enforce

**The closing rule** (proposed as Team 2 policy, enforced by the auditor's exit code): for every set
of NPC-sold inputs, the best NPC liquidation of the outputs must be **≤ 90% of the input cost**.
Ten percent of headroom keeps skilling for the *market* profitable while NPC round-trips lose money.

| PR | Change | Closes |
|---|---|---|
| **2a** | **Trading Post buys only the GE commodity allowlist** at 70% (it becomes the same floor the GE gives, in shop form). Nothing else is NPC-cashable there. | all S0 Trading-Post sinks (adamant/mithril gear) |
| **2b** | **General Store cap 5,000 → 500 gp**, and it consults `SpecialShopGuard`. Junk sink stays a junk sink (doc 04 §15). | 60+ craft loops; the Justiciar regression |
| **2c** | **Split the GE backstop allowlist**: *floor-only* for raw materials (bars, ores, logs, gems, essence, herbs — the NPC buys at 70% but never sells) vs *floor + ceiling* for boring necessities the shops already sell (runes, food, planks). | the unlimited 100% tap behind every S0 |
| **2d** | Stylist: shelf price = max(explicit, cost); explicit buyback 50% of shelf. | the 20 Shop A → Shop B loops |
| **2e** | Register Vote/BM/Donor/Prestige wares with `SpecialShopGuard`; boot-time `ShopPriceSanity` warning for any coin ware with `sell < cost × 0.7`. | future regressions |
| **3** | Explicit FoV prices (`itemOverrides/economy/*.yml` `cost:`) for the economically important Lumbridge items, so the corridor is intentional rather than inherited (doc 04 §6, §7). Re-run the auditor; the JSON diff must be empty of S0/S1. | design intent |
| **4+** | Boss Ticket removal, BM/Vote shelf repricing, War-Forge inputs, Prestige removal, reward-value sheet — unchanged from the plan. | — |

After 2a–2c the craft loops collapse because their sink disappears (no NPC buys a platebody) and
their tap dries up (no NPC sells a bar); Smithing/Fletching output then floats on the player market
between the shop ceiling and nothing — which is exactly doc 04's "premium optimisation remains an
economy" model.

### Decisions needed from the operator

1. **Trading Post**: restrict its buyback to the commodity allowlist (recommended) or remove NPC
   buyback from it entirely and let the GE floor do the job.
2. **GE backstop ceiling**: agree the floor-only list (raw materials never NPC-sold). Runes and food
   stay two-sided so magic ammo and PvP food are never blocked by an empty GE.
3. **General Store cap**: 500 gp (recommended) or stock-only buyback.
4. **Boss-Ticket catalogue disposition** when tickets go: retire the PvM shelf outright (doc 04 §13 —
   bosses drop the gear) vs keep a gp-priced pity shelf at ≥ cost (the four items in §3 need explicit
   prices either way).

## 6. Reward-value bands (provisional, for Teams 1/4/5)

Until the closing rule lands, realised gp/hour of any drop table is `0.7 × Σ cache cost per hour`,
because everything cashes out at an NPC. Design against **cache-value per hour at full uptime**:

| Activity tier | Band (cache value / hour) | Notes |
|---|---|---|
| Skilling (gathering) | 150k–400k | plus the Supply Depot's War Effort, which is not gp |
| Mid PvM / hostile zones | 600k–1.2M | deep-wild risk justifies the top of the band |
| Endgame PvM | 1.5M–3M | uniques dominate; the per-kill coin pool must stay under 20% of it |
| PvP (BM) | ≤ 8,000 BM/day/killer under Team 5's gate | BM's gp value is player-set; shelf prices key on kills-to-regear |

Proposals that exceed a band get trimmed before merge; proposals inside it are approved same day.

## 7. Re-running the audit

```powershell
cd Alter
.\gradlew.bat :game-plugins:economyAudit "-PeconCache=C:\Program Files (x86)\Kearse RSPS\Alter\data\cache" --console=plain
.\gradlew.bat :game-plugins:economyAudit -PeconMode=selftest "-PeconCache=..."     # 12 spot checks, must print ALL PASS
.\gradlew.bat :game-plugins:test --tests "org.alter.plugins.content.economy.audit.*"
```

Exit code 1 while any S0/S1 loop, unexplained converter bind or broken recipe adapter exists.
`-PeconFlags=no-guards` shows what a guard removal would expose; `-PeconPegs=boss_ticket=1000,...`
overrides the assumed gp value of a currency once the "buy tickets" tabs are gone (PR #313).

How it works: `OfflineBoot` loads the cache and the real item overrides, constructs the eleven shop
plugins and fifteen recipe/converter plugins offline through the same constructor the server uses,
snapshots every `Shop` exactly as `ItemCurrency` would price it, reads the recipe tables by
reflection (a renamed field fails loudly), and relaxes a value graph in both directions (cheapest NPC
acquisition vs best NPC liquidation) under four modes: no-fail level vs first-usable level, guards on
vs off. Set-box pack/unpack is judged once, not recursively. Time costs are a documented tick table —
gp/hour is a ranking, not a measurement.

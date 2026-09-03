# Fall of Varrock — Economy Ledger (faucet ↔ sink)

> **Rule:** every gp/item **faucet** (source) must be paired with a **sink** (drain), and
> overall **sinks ≥ faucets**. Uncontrolled inflation + dupes are the #1 cause of RSPS
> death. Keep this file current as content lands. Localhost-only project (no monetization).

## Currencies
- **gp** (`item.coins_995`) — main currency (inventory item).
- **Tradeable special currencies** (inventory items — can be traded, dropped, and sold for coins
  on the Grand Exchange, player-listed):
  - **Blood Money** (`item.blood_money`) — PK currency.
  - **Boss Tickets** (`item.boss_ticket`) — PvM currency (migrated from a counter to a ticket item).
  - **Vote Tickets** (`item.vote_ticket`) — vote currency (likewise a ticket item).
  - Each has a **coin ceiling** set by an NPC "buy for coins" tab (Quartermaster / Emblem Trader /
    Valaine) — see the sink table + `economy/grandexchange/CurrencyExchange`. Peg: Boss Ticket
    ≈ 1,000 gp, Blood Money ≈ 800 gp, Vote Ticket ≈ 2,000 gp (raise as the economy grows).
- **Reward points** (persistent counters, NOT items — can't be dropped/traded/duped/GE'd):
  - Prestige, Donor, LMS — spent at sell-only reward shops.
  - **War Effort** — a **lifetime service record**, `PointKind.spendable = false`: it only ever
    climbs, no shop or sink may debit it (Block 1 PR-4). Read by rank eligibility and the daily
    War Effort bonus; `::service` shows the per-player ledger (`war/events/ServiceRecord.kt`).
  - **Realm Supplies** — the SHARED consumable stockpile (`war/RealmSupply.kt`, persisted in
    `WarState`): every Quartermaster hand-in raises it by the item's War Effort value; only
    Campaigns (1,500) and Conquests (2,800) spend it. Marches and Lord operations are free.
  - See `content/economy/Currencies.kt` (`PointKind`) + `PointsCurrency.kt`.

## Faucets (sources) — current
| Source | Output | Notes |
|---|---|---|
| Frontier kills (Lumbridge goblin/hobgoblin/ogre rings) | gp + occasional gear piece | `war/CityFrontierPlugin.registerLoot` (`EnemyLine.coinMin/coinMax`, `gearDropOneIn`) |
| Won marches / campaigns / conquests | gp (pooled war-chest, split by contribution) + Commendations (untradeable) + War Effort | `war/CapturePayout`; Commendations spent only at the Royal Smith (`war/forge`) |
| Grand March Warden | Warden's embers (tradeable forge component) | `war/MarchPlugin.onWardenSlain` |
| Named captains | 100k gp banked + 2 Commendations + 25 War Effort + 1/15 signature weapon | `war/captains/NamedCaptainsPlugin` |
| Mining | ores (skilling) | `MiningPlugin` |
| Thieving | gp + items | pickpocket/stall/chest |
| Slayer | **Slayer points** + Slayer xp | `SlayerPlugin` (points are a counter, mild faucet) |
| King Black Dragon | gp + runite bars + uniques/pet + **Boss points** | `bosses/KbdBossPlugin` (`DropTable`); rare uniques broadcast + Collection Log |
| PK kills (players / bots) | **Blood Money** (scaled by victim combat level) | `economy/pk/PkRewardsPlugin`; only human killers earn |
| Gambling wins | gp (paid by the house on a winning roll) | `economy/gambling` — net negative EV (the rake), so a sink overall |
| Daily reward | gp + Vote points (streak-scaled) | `economy/daily` (`::daily`) — time-gated, modest |
| Vote claim | Vote points (streak-scaled) | `economy/daily` (`::claimvote`) — local stub |
| Fight Cave clear | Boss points + first-time Fire cape | `minigames/fightcave` — consumes the player's own supplies |

## Sinks (drains) — current + planned
| Sink | Drains | Status |
|---|---|---|
| Shops (buy) | gp | live (general store, smith, etc.) |
| ~~War Effort reward shelf~~ | — | **removed** (Block 1 PR-4): War Effort is a lifetime record, not a currency |
| Boss reward shop (valaine) | Boss points | **live** (`LumbridgeShopHubPlugin`) |
| Duke Horacio ranks | gp (rank purchases) | live (`DukeHoracioPlugin` → `RankPurchase`) — Block 1 PR-5: coins are one requirement; a lifetime War Effort floor (`RankEligibility`, TUNE) gates each rank too, so the sink only fires for players who have served |
| Lord operation (`::operation <target>`) | gp (500k war-chest, **refunded on a win**) | **live** (`CampaignCommandPlugin`, Block 1 PR-2) — a sink only when the operation is lost; Marches / Grand Marches are free and spend no Realm Supplies |
| Campaigns / Conquests | Realm Supplies (1,500 / 2,800) + the sponsor's war-chest | live (`CampaignCommandPlugin`) — the only drains on the shared stockpile |
| Forge / Upgrade gear | gp + rune gear + runite bars | **live** (`economy/forge/ForgePlugin`) — the marquee sink; KBD runite bars feed it |
| High/Low Alchemy | item destroyed (gp partial faucet) | **live** (`magic/alchemy/AlchemyPlugin`) — item sink |
| Trading Post trade margin | gp | **live** (`economy/tradingpost`) — 30% buy/sell spread (the shared `ItemCurrency.BUY_RATE` 70%), value-derived; no house stock — the shelf lists only player-sold items. **Since the 2026-09 audit it buys ONLY the GE commodity allowlist** (gear/crafted goods go to the GE) |
| General Store junk sink | gp | **live** — buys any tradeable with cache cost ≤ 500 (was 5,000) at 70%, refuses guarded wares |
| Grand Exchange commodity margin | gp | **live (engine)** — NPC floor at 70% of value for every commodity (`grandexchange/GrandExchangeCommodities`); NPC ceiling at 100% ONLY for the two-sided necessities (runes, arrows, cooked food, planks). Raw materials (bars, ores, logs, gems, essence, herbs, raw fish) are floor-only: the NPC never sells them (the 2026-09 audit's S0 tap) |
| Buy-currency-for-coins tabs | gp | **live** (`grandexchange/CurrencyExchange`) — Boss Tickets 1,000 / Blood Money 800 / Vote Tickets 2,000 gp each; **one-way** (no NPC buyback) so it's a pure coin sink, and it sets the coin ceiling on special-currency gear |
| PK Rewards shop (emblem trader) | Blood Money | **live** (`economy/pk`) — PK supplies (food/potions), no tradeable gear |
| Gambling rake | gp | **live** (`economy/gambling`) — 5% house edge on dice |
| Degradable gear charges | gp | planned (Phase 2) |
| Consumables burned in combat | food/potions/runes/ammo | partial (combat consumes; needs supply skills) |

## Grand Exchange (`content/economy/grandexchange`)
The player-to-player offer book for coins — the evolution of the Trading Post into a real market that
consolidates trade and lets stores set the price floors.
- **Engine live:** 8 offer slots/player, escrow, price-time matching with partial fills, collect/cancel,
  JSON world-save persistence. Currently driven by dev commands (`::gebuy/::gesell/::geoffers/::gecollect/
  ::gecancel/::gematch`) — the native interface-465 offer packets are the remaining wiring.
- **Dupe-safety (audited):** escrow leaves the player on offer creation; a match only *moves* value
  between an offer's escrow and its collectable proceeds — a player↔player match never mints or destroys
  coins/items. The NPC commodity backstop is the *only* faucet/sink and is gated to the allowlist.
- **Store minimums (backstop):** commodity-allowlist items (runes, bars, ores, logs, food, herbs, mats)
  get an NPC floor (70% of value — the shared NPC buy rate). Only the **two-sided** tier (runes, arrows,
  cooked food, planks — the necessities the shops sell without limit) also gets an NPC ceiling (value);
  the **floor-only** tier (bars, ores, logs, gems, essence, herbs, raw fish) is never NPC-sold, because
  unlimited NPC raw materials at 100% of value fed every craft loop in the 2026-09 audit. Gear, megarares
  and the currency items have **no** backstop — they float on the pure player market. The allowlist
  excludes the deliberately premium-priced items (death rune, adamant arrow, cooked swordfish) and
  load-bearing sinks (runite bar, dragon bones).
- **Price band (all items):** every offer's price must sit within **a tenth to ten times** the item's
  economy value (`grandexchange/GrandExchangePricing`, enforced server-side in `validNew`, and mirrored
  to the client on the setup wire so the steppers clamp before Confirm). This is a sanity rail, not a
  peg — halving or tripling the guide is still legal, so player price discovery is untouched; it exists
  because the book used to accept **any** price above zero. An item with **no** credible cache value is
  unbanded *and* gets no backstop, so a silly price on it can only ever be a consenting player↔player
  trade, which mints nothing.
  - *Fixed:* an item whose cache value was 0 used to be handed a **1 gp** backstop ceiling by a
    `maxOf(1, cost)`, and `backstopSweep` then filled a 1 gp buy against the NPC on the next match tick
    — buying the item for a single coin. Value resolution (`GrandExchange.economyValue`) now returns
    null instead of inventing a 1, and null means no band and no backstop.
- **One price source:** everything above reads `GrandExchange.economyValue` — the same cache `cost` the
  coin shops price from (`ItemCurrency.getSellPrice`, mirrored by `ItemMarketValueService`) — so the GE
  and the stores cannot drift apart.
- **Cross-playstyle trade routes stay open:** a PKer sells Blood Money to a PvMer for gp, etc. — the coin
  ceiling (currency tabs above) caps those prices without hard-pegging them.

## Arbitrage audit (Team 2, 2026-09-03)
`gradlew :game-plugins:economyAudit "-PeconCache=<data/cache>"` boots the shops and recipes offline and
searches every NPC value loop (buy → craft → sell, shop → shop, shop → alch, GE floor, currency → item →
gp, ...). Findings + fix queue: `docs/economy-arbitrage-audit-2026-09.md`; generated tables:
`docs/economy-arbitrage-audit.{md,json}` (re-run after every reprice — the JSON diff is the proof).
Headline: cache `cost` is not consistent across recipes, and the 70% NPC buyback (Trading Post: anything;
General Store: cost ≤ 5,000) plus the GE backstop's 100% NPC *selling* of bars/logs/essence make most
mid/high skilling recipes coin printers. The fix queue restricts the NPC sinks and splits the backstop
allowlist into floor-only raw materials vs two-sided necessities.

## Balance to-dos
- Audit shop buy/sell spreads + high-alch values so gp drains as fast as it faucets.
- When custom-boss drops land (Phase 4), pair each tradeable faucet with a forge/tax sink.
- Any new trade/forge/gambling path: **audit for dupes** before shipping.

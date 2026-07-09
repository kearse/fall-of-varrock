# Fall of Varrock — Economy Ledger (faucet ↔ sink)

> **Rule:** every gp/item **faucet** (source) must be paired with a **sink** (drain), and
> overall **sinks ≥ faucets**. Uncontrolled inflation + dupes are the #1 cause of RSPS
> death. Keep this file current as content lands. Localhost-only project (no monetization).

## Currencies
- **gp** (`item.coins_995`) — main currency (inventory item).
- **Blood Money** (`item.blood_money`) — PK currency (inventory item; exists in cache).
- **Reward points** (persistent counters, NOT items — can't be dropped/traded/duped):
  - Slayer points — from Slayer tasks → Slayer reward shop.
  - Boss points — (planned) from PvM.
  - Vote points — (planned) from voting.
  - See `content/economy/Currencies.kt` (`PointKind`) + `PointsCurrency.kt` (sell-only reward shops).

## Faucets (sources) — current
| Source | Output | Notes |
|---|---|---|
| War goblin kills | gp (coins to inventory) | `WarEffortPlugin` (~50/kill) |
| War shock-troop / Warlord kills | gp + class gear pieces | `WarDrops` |
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
| Slayer reward shop | Slayer points | **live** (`SlayerPlugin`) |
| Boss reward shop (valaine) | Boss points | **live** (`LumbridgeShopHubPlugin`) |
| Duke Horacio ranks | gp (rank purchases) | live (`DukeHoracioPlugin`) |
| Forge / Upgrade gear | gp + rune gear + runite bars | **live** (`economy/forge/ForgePlugin`) — the marquee sink; KBD runite bars feed it |
| High/Low Alchemy | item destroyed (gp partial faucet) | **live** (`magic/alchemy/AlchemyPlugin`) — item sink |
| Trading Post trade margin | gp | **live** (`economy/tradingpost`) — 15% buy/sell spread, value-derived; the NPC-backstop marketplace sink |
| PK Rewards shop (emblem trader) | Blood Money | **live** (`economy/pk`) — PK supplies (food/potions), no tradeable gear |
| Gambling rake | gp | **live** (`economy/gambling`) — 5% house edge on dice |
| Degradable gear charges | gp | planned (Phase 2) |
| Consumables burned in combat | food/potions/runes/ammo | partial (combat consumes; needs supply skills) |

## Balance to-dos
- Audit shop buy/sell spreads + high-alch values so gp drains as fast as it faucets.
- When custom-boss drops land (Phase 4), pair each tradeable faucet with a forge/tax sink.
- Any new trade/forge/gambling path: **audit for dupes** before shipping.

# Shop Economy Redesign — early → mid → late game

> Full re-evaluation of every shop against the progression spine (rank ladder → war →
> forge → conquest), from a two-pass audit of all shops/currencies/faucets (2026-07).
> Goal: every stage of play has gear to grind FOR, content to grind AT, and a currency
> that matches the playstyle — with no dead ends, no dead currencies, and no shop that
> breaks the market. Status tags: ✅ keep as-is · 🔧 change · ➕ new · ❌ remove.

---

## 1. The five rules

**R1 — currency matches playstyle.** GP is the *progression* currency (ranks, forge,
utility); Blood Money buys *PvP* gear; Boss Tickets buy *PvM* gear; War Effort buys war
supplies; Commendations + embers buy *war-forged BIS* (exclusively); LMS/Vote points stay
self-contained; Donor and Prestige get real (cosmetic/QoL) sinks.

**R2 — shops never sell forge outputs.** Torva/Masori/Ancestral come from the Royal
Smith or a rare elite-boss jackpot (1/450) — never a shop. Otherwise Commendations (and
the whole march loop) are decorative. *This is currently violated — see §3.*

**R3 — shops sell bases and pity paths, priced above the content route.** Dual-sourcing
is good (bad-luck protection); the shop must always be the *slower* path than playing
the content (captains for claws, Barrows for Barrows, GWD for hilts).

**R4 — no BIS for raw GP.** GP is infinitely farmable (13+ faucets); any BIS-for-gold
shelf eventually gets bought out by inflation. GP sinks are ranks, the dragon Forge,
dyes, gambling rake, Trading-Post margin, and (new) mid-priced Barrows pity — never
endgame BIS.

**R5 — tradeable currencies ARE the market.** Blood Money, Boss Tickets, and Warden's
embers are tradeable items: a PKer can *sell* BM to a PvMer for gp, a raider can sell
embers to a forge-rusher. Every redesign choice below deliberately leaves cross-playstyle
trade routes open — that's the market stimulation.

---

## 2. The progression matrix (what you grind, where, at every stage)

| Stage (rank) | Gear you're chasing | Where you grind | Currency earned | Shop that serves you |
|---|---|---|---|---|
| **Early** (Peasant→Squire, 0–50k) | bronze→black; first rune scim | Recruit Trials, goblin front, starter contracts | coins, first War Effort | coin hub shops ✅, Apprentice Armoury ✅, War Rewards ✅ |
| **Mid** (Soldier→Knight, 150k–500k) | mithril→rune; dragon via Forge; Barrows; GWD bases | rank contracts, marches, slayer streaks, Barrows, KBD/Zulrah, LMS, rogue milestones | coins (3k–90k/contract), Boss Tickets, BM, Commendations | Forge ✅, Barrows pity wing 🔧, Monster shop bases 🔧 |
| **Late** (Lord+, 2M+) | war-forged Torva/Masori/Ancestral; spec weapons; megarares | Grand Marches/Wardens, captains, GWD/elite bosses, Inferno, deep-wild PK, campaigns | Commendations, embers, Boss Tickets, BM, Prestige | Royal Smith ✅, PK Rewards gear wing 🔧, Monster shop chase wing 🔧 |
| **Endgame** (Minister/King) | full BIS sets, accessories, conquest | campaigns, conquest, Corp sigils, Inferno | everything + Prestige | all of the above + Prestige shop ➕ |

The early game is already healthy (coin shops are carefully capped and spot-repriced so
skilling stays competitive — keep untouched). The problems are all mid/late.

---

## 3. The Warlord's Armoury restructure (the big change)

Today the Armoury (`weapons/custom/WarlordsArmouryPlugin.kt`) is one vendor with seven
wings whose currencies are misaligned and partly **broken**: the Armour + Crystal wings
spend the *vestigial* BOSS **counter** (`PointsCurrency`) while every boss actually pays
the BOSS **ticket item** — those wings are likely unbuyable today (bug). And it violates
R1 (PvM megarares priced in Blood Money), R2 (sells Torva/Masori/Ancestral outright,
gutting the forge), and R4 (BIS accessories for raw GP).

Restructure into **two clean vendors** matching the playstyle split:

### 3a. PK Rewards (Emblem Trader) — Blood Money 🔧
Keep the supplies shelf, add the **PvP gear wing** (moved from the Armoury):

| Shelf | Items | Note |
|---|---|---|
| Supplies ✅ | sharks/pots (current stock) | unchanged |
| Spec weapons 🔧 | AGS, dragon claws, DWH, voidwaker, elder maul, granite maul, abyssal whip | claws/DWH priced ABOVE the captains route (R3) |
| Wilderness sets ➕ | **Vesta's (incl. VLS), Statius's, Morrigan's, Zuriel's** | fixes the dead-gear gap: `Title.kt` gates these but nothing drops them |
| Revenant weapons ✅ | craw's/viggora's/thammaron's + upgrades | already BM — keep |

### 3b. Monster Rewards (new vendor or Valaine expansion) — Boss Tickets 🔧
Everything PvM, paid in the ticket item (fixes the counter bug by using `ItemCurrency`):

| Shelf | Items | Price band (TUNE) |
|---|---|---|
| GWD bases 🔧 | Bandos, Armadyl, Ahrim's pieces | 300–600 — *feeds the forge* (R3) |
| Mid armour 🔧 | Justiciar, Inquisitor, Void/Elite Void, Crystal line | 400–1,500 |
| PvM weapons 🔧 | fang, rapier, tridents, blowpipe, tentacle, DHCB/DHL, nightmare staff | 500–1,500 (charged items move here from GP) |
| Megarares 🔧 | **tbow, scythe, shadow**, sanguinesti, zaryte cbow, soulreaper, harmonised | 8,000–15,000 — career prices (~200–400 Zulrah kills / 20–40 Inferno clears); the pity path until raids ship gear |
| Accessories 🔧 | occult, torture, anguish, rings, avernic | 800–2,500 (moved off GP, R4) |

### 3c. Removed from sale entirely ❌
- **Torva / Masori / Ancestral / Virtus** — forge-exclusive (R2). Virtus parked for a
  future Nex-style source.
- **Fire cape / Infernal cape** — untradeable prestige; earn them in the Cave/Inferno
  (both repeatable, no pity needed). Selling them for gp cheapens the achievement.
- **Elysian/Arcane spirit shields** — Corp's sigil table (1/150) stays the only source;
  the realm's rarest flexes should come from its hardest boss.

### 3d. Barrows wing 🔧 — stays GP, repriced as the mid-game sink
Barrows pieces at **8–20M gp** (down from 120–180M). Rationale: the audit found a
GP-sink desert between the Duke's mid ranks and the 200M+ accessory wall — mid-game
players pile up contract/captain gp with nothing to spend it on. Barrows-for-gp at
above-minigame-cost prices is the missing mid sink, and Barrows gear is exactly
mid-late tier. (The minigame stays the smart path, R3.)

---

## 4. Currency-by-currency verdicts

| Currency | Faucet health | Sink verdict |
|---|---|---|
| **Coins** | over-sourced (13+ faucets; `GENEROSITY_MULT=2` still hot) | ✅ strong top sinks; ➕ Barrows mid sink (§3d); 🔧 step GENEROSITY to 1 as economy matures |
| **Blood Money** | healthy (real kills only; bots pay none) | 🔧 gains the PvP gear wing — finally a chase |
| **Boss Tickets** | healthy & wide (every boss/minigame) | 🔧 becomes THE PvM gear currency; fix the counter/item bug everywhere |
| **War Effort** | healthy | ✅ supplies shop + daily bonus; leave |
| **Commendations/embers** | war ops only (by design) | ✅ forge-exclusive BIS — restored to meaning by §3c |
| **Vote Tickets** | thin (1–3/day) | 🔧 add 2–3 shelf items (untradeable XP lamp, cosmetic) — keep small |
| **LMS points** | self-contained | 🔧 swap tradeable AGS/claws for LMS-flavored cosmetics + keep consumables; the crate already provides the gear RNG (5 faucets for claws is 2 too many) |
| **Donor points** | earns fine | ➕ **Donor store required** — currently NO sink exists: cosmetics (exclusive dye colors, pets/skins, march banners), QoL, never power |
| **Prestige** | sponsors only | ➕ **Prestige shop** — commander cosmetics, title ornaments, a statue/memorial hook; never power |

---

## 5. Market-safety checklist (what we're explicitly preventing)

1. **Forge bypass** — no shop sells forge outputs (§3c). The commendation economy is
   load-bearing again.
2. **GP → BIS** — eliminated (§3b moves accessories/charged to tickets; §3c removes the
   rest). GP buys progression, not endgame power.
3. **Bot double-dip** — bots already pay zero BM (verified `PkRewardsPlugin`); their
   loot-key gear drops are the intended "practice + loot" design. Watch elite-bot kit
   frequency if AGS/claws prices sag on the player market.
4. **Skilling undercuts** — the coin hub's spot-repricing discipline (adamant arrows,
   death runes, cooked swordfish priced above player-made cost) is the model; apply the
   same check to anything added later. Trading Post already excludes runite bars/dragon
   bones for this reason.
5. **Five-faucet items** — dragon claws currently have 5 sources; after §4 (LMS drops
   gear from its shop) they have 4, with the captains as the flagship. Rule of thumb
   going forward: one content source + one shop pity + incidental (bots/crates) max.
6. **Untradeable prestige stays earned** — fire/infernal capes, halos, Champion's Cape,
   ranks, Commendations.

---

## 6. Implementation checklist

1. ⬜ **Fix the boss-currency bug**: Armoury Armour/Crystal wings → `ItemCurrency(item.boss_ticket)`
   (and audit every `PointsCurrency(PointKind.BOSS)` / `PointKind.VOTE` use).
2. ⬜ **Split the Armoury** per §3: move PvP gear into PK Rewards; stand up the Monster
   Rewards vendor on tickets; delete the ❌ items; reprice Barrows wing.
3. ⬜ **LMS shop**: replace tradeable gear with cosmetics/consumables.
4. ⬜ **Donor store** (new): cosmetics + QoL on `PointsCurrency(DONOR)`.
5. ⬜ **Prestige shop** (new, small): commander cosmetics on `PointsCurrency(PRESTIGE)`.
6. ⬜ **Vote shop**: +2–3 items.
7. ⬜ **Wiki**: rewrite the shops/economy pages to the new map; update `the-war-explained`
   / `war-forging` cross-links.
8. ⬜ Post-launch: step `GENEROSITY_MULT` 2→1; watch BM/ticket prices on the Trading
   Post and tune §3 price bands.

# Fall of Varrock — Custom Overlay Design System

The single source of truth for how our custom **client overlays** look and behave, so the whole
client reads as one product. Every `lof*` overlay in
`client/runelite-client/src/main/java/net/runelite/client/plugins/` should follow this. The shared
code lives in [`LofTheme`](../client/runelite-client/src/main/java/net/runelite/client/plugins/loftheme/LofTheme.java)
— **draw from it; don't hand-roll colours or metrics.**

> Why overlays and not cache interfaces: cache (if3) interfaces crash our rev-228 client on open,
> so all custom UI is drawn client-side with Java2D. See `docs/custom-client.md`.

---

## 1. Principles

1. **One system.** Ember-red + antique-gold on warm near-black, matching the login screen and site.
2. **Draw from `LofTheme`.** Palette, logo, and the panel/pill/underline/shadow-text helpers are shared.
3. **No custom packets by default.** State reaches the client through a **varp** (small state) or by
   **reading an existing interface's widgets** (large state, e.g. item lists). Actions go back as a
   **`::cmd` public-chat token** the server intercepts and suppresses.
4. **Never crash the client.** Every widget read is null-guarded; a bad read renders empty, never throws.
5. **Themed, not disruptive.** Respect the player: movable HUDs, don't cover what the player needs to
   click (see §6), restore any `Graphics2D` state you change.

---

## 2. Palette (`LofTheme`)

| Token | RGBA | Use |
| --- | --- | --- |
| `PANEL` | 21,17,16,240 | window body (translucent) |
| `PANEL_OPAQUE` | 21,17,16 | opaque backings (HUD discs, tracks) |
| `HEADER` | 32,24,22 | title bar |
| `SHADOW` | 0,0,0,110 | drop shadow |
| `EMBER` | 203,56,38 | primary accent — borders, selection, danger, Decline |
| `EMBER_DARK` | 120,30,24 | panel border, subdued accent |
| `LAVA` | 255,120,40 | hottest state (conquest-ready, fog alert) |
| `GOLD` | 232,193,90 | titles, primary action (Accept), values |
| `GOLD_DIM` | 166,138,76 | section labels, secondary buttons |
| `TEXT` | 240,235,228 | body text |
| `TEXT_DIM` | 158,149,140 | disabled / secondary text |
| `ROW` | 255,255,255,10 | row / slot / chip fill |
| `ROW_HOVER` | 255,220,170,26 | hover fill |

**Semantic colours** (define locally, keep consistent): safe / "on" green `110,205,110`; wilderness
red `255,64,64`; hostile orange `255,128,0`; coming-soon grey `127,127,127`.

Use `LofTheme.alpha(color, a)` to vary opacity — never rebuild a colour by hand.

---

## 3. Typography

Use the RuneScape fonts via `FontManager` (never `Font.SANS_SERIF` in-window):

- **Title** — `getRunescapeBoldFont()`, colour `GOLD`.
- **Section label / subtitle / status** — `getRunescapeSmallFont()`, colour `GOLD_DIM` (labels) or
  `TEXT_DIM` (status).
- **Body / rows / buttons** — `getRunescapeFont()`.

All text is drawn with `LofTheme.shadowText(...)` (1px black shadow) so it stays legible over the world.

---

## 4. Metrics

| Constant | Value | Meaning |
| --- | --- | --- |
| Window corner `ARC` | 14 | panel rounding |
| Title bar height | 38 | header strip |
| Padding `PAD` | 12 | window inset |
| Row / chip height | 22 | list row, checkbox chip |
| Button height | 32 | footer buttons |
| Small rounding | 6–8 | chips, buttons, slots |
| Hairline stroke | 1.4f | button borders, close ✕ |

Antialiasing: turn it **on** at the top of `render` and **restore the prior hint** before returning
(see any current overlay).

---

## 5. Window anatomy (framed modals)

Build every framed window the same way:

1. **Panel** — `LofTheme.panel(g, ox, oy, w, h, ARC)` (drop shadow + rounded body + ember border).
   The body is **fully opaque** — our windows never let the game show through them.
2. **Header** — fill the top `38px` with `HEADER` (clip to the title strip), then
   `LofTheme.emberUnderline(...)` a 2px fading ember line under it. Draw the **shield logo**
   (`LofTheme.logo()`, 28px, at `ox+12, oy+5`) then the **gold bold title** at `ox+46`. Optional
   right-aligned **subtitle** in `TEXT_DIM` (e.g. "Basics • 7 destinations").
3. **Close** — a `20px` rounded square top-right (`ox + w-30, oy+9`) with a drawn ✕; ember on hover.
   (Windows opened by a server flow — duel, stake — use a **Decline** button instead of a close ✕.)
4. **Body** — the content region, inset by `PAD`.
5. **Footer** — right-aligned action buttons on the bottom row: **Accept = `GOLD`**, **Decline /
   destructive = `EMBER`**, secondary (Load Last, etc.) = `GOLD_DIM`, left-aligned.
6. **Status line** — one line of `getRunescapeSmallFont()` just above the footer; green when a
   one-sided accept is pending, else `TEXT_DIM`.

---

## 6. Standard sizes & positioning

Two categories. Pick the smallest that fits.

### A. Framed modal — **480 × 400 standard**
Teleport, duel rules, duel stake, and future tabbed/list/grid windows all use the **same 480×400
frame** unless content genuinely needs more. This is the "all the overlays should be the same" size.

- **Positioning:** centre the window in the **game viewport**, not the whole canvas — in fixed mode
  the viewport is the left ~512px, so the window sits clear of the inventory/tab column on the right.
  Compute `originX` so the window's right edge never crosses the inventory (`≈ min((canvasW-w)/2, …)`
  clamped to the viewport). Windows that need the inventory clickable **while open** (the stake screen)
  MUST keep that column clear — left/viewport-anchored, never centred over it.
- Position with `OverlayPosition.DYNAMIC` and draw at absolute `ox/oy`; these windows are not movable
  (they're modal to a flow).

### B. HUD overlay — content-sized, corner-anchored
Supply dial, war-progress bar, alerts banner, LMS panel, announcement ticker, PK stats, CW timer.

- **Sized to content**, not the 480×400 frame. Small.
- **Snap to a corner** (`TOP_LEFT/…/ABOVE_CHATBOX_RIGHT`) and set `setMovable(true)` + `setSnappable(true)`
  so players can reposition. Exception: overlays that must track the chat box compute an **absolute
  position from the `CHATBOX_FRAME` widget** (see the announcement ticker) because `BOTTOM_LEFT`
  renders behind the chat in fixed mode.
- Panelled HUDs use `PANEL_OPAQUE` backings + `EMBER_DARK` borders; **text-only tickers stay transparent**
  (no panel) for readability over the world.

**Layer / z-order:** framed **modals draw on `OverlayLayer.ALWAYS_ON_TOP`** so they sit above the
corner HUDs (e.g. the war-supply dial) — otherwise a HUD renders over the open window and blocks it.
**HUDs draw on `OverlayLayer.ABOVE_WIDGETS`.** Text tickers that position absolutely (announcements)
compute their spot from the **canvas** (e.g. `canvasHeight - 165` for the classic chat height), not
the chat-box widget — the widget can report a zeroed/off location in fixed mode and hide the overlay.

---

## 7. Component library

Reusable pieces — reach for these before inventing a new one. Metrics above.

- **Tab rail** (teleport) — left column of tabs; selected tab gets an `EMBER` bar + `alpha(EMBER,44)`
  fill + `GOLD` label; hover gets `ROW_HOVER`.
- **List row / card** — rounded `ROW` fill, `ROW_HOVER` on hover (built rows only), item icon + name +
  right-aligned value + **danger pill**.
- **Pill** (`LofTheme.pill`) — rounded tag, coloured text on a translucent same-hue fill; danger tags,
  status tags.
- **Checkbox chip** (duel rules) — `ROW`/`ROW_HOVER` rounded row, a 13px box that fills green with a
  check when on; label `TEXT` when on, `TEXT_DIM` when off.
- **Item slot / grid** (stake) — small rounded `ROW` cells with a faint ember border; item icon via
  `ItemManager.getImage(id, qty, stackable)`; read the item ids from the source interface's widgets.
- **Equipment paper-doll** (duel gear) — the classic worn-equipment layout (3 columns: head; cape/
  amulet/ammo; weapon/body/shield; legs; hands/boots/ring); a forbidden slot is ember-filled with a ✕.
- **Buttons** — rounded, hairline accent border, accent-coloured centred label; fill brightens on
  hover / when active. Accept=`GOLD`, Decline=`EMBER`, secondary=`GOLD_DIM`.
- **Scrollbar** — 5px `alpha(EMBER,190)` rounded thumb on an `alpha(white,14)` track.
- **Circular gauge** (supply dial) — `PANEL_OPAQUE` disc, track ring `alpha(white,28)`, value arc from
  12-o'clock clockwise; ember while filling, gold + pulse when a threshold is met, `LAVA` at the top tier.
- **Progress bar** (war bar) — `PANEL_OPAQUE` track, ember/gold fill, `EMBER_DARK` border, centred
  shadowed label.
- **Banner / toast** (alerts) — `PANEL_OPAQUE` rounded panel, pulsing `EMBER` border, shield logo +
  bold text.
- **Ticker** (announcements) — transparent, outlined coloured text, absolute-positioned above the chat.

---

## 8. Interaction & data channels

- **State → client:**
  - *Small state* (toggles, counters, flags): one **packed varp**. Document the bit layout in the
    server driver. Transient/event varps must **pulse to 0** and be cleared on login (persisted varps
    re-fire on login — see `docs/custom-client.md` §5e). Current varps: 4600 alert · 4601 war progress ·
    4602-4605 pk stats · 4606 wild level · 4607 teleport open · 4608 LMS · 4609 supplies · 4610-4612
    companions · 4620-4623 Castle Wars · **4630 duel rules**.
  - *Large state* (item lists): **read the existing interface's widgets** (`client.getWidget(group,
    child)` → `getDynamicChildren()`), null-guarded. No custom packet.
  - *Bulk data via chat lines* (the commands list, companion state): send from the server as
    **`ChatMessageType.CONSOLE`** lines tagged with a machine prefix (`FOV_CMDS:`, `~LOFCMP~`).
    CONSOLE is delivered to the client's `ChatMessage` event (fires exactly ONCE, on arrival —
    consume there) and lives in **its own line buffer**, so it can never evict the player's game
    messages. NOTE: this rev-228 client **does render CONSOLE in the chat box** (unlike modern
    clients) — pair it with a **block-only `chatFilterCheck`** (pure display filter, never touches
    buffers) plus one `refreshChat()` at batch end so the hide applies immediately.
    Four hard rules, each learned from a live glitch with the commands list:
    **(1) NEVER send machine data as `GAME_MESSAGE`** — even display-filtered, every hidden line
    still consumes a slot in the game-message buffer and silently **evicts the player's oldest
    game messages** ("::commands deletes my chat"). **(2) NEVER parse in `chatFilterCheck`** — it
    re-runs over the whole retained history on every chatbox rebuild (open-lag / tab-reset /
    reopen); block-only there. **(3) NEVER mass-remove lines from the chat buffers**
    (`removeMessageNode` loops) — it wiped the visible chat. **(4) CONSOLE renders in this
    client's chat box** — always pair it with the display filter.
- **Action → server:** send `"::<cmd> <args>"` via `runScript(ScriptID.CHAT_SEND, …)`; the server
  intercepts it in `MessagePublicHandler`, runs the matching command, and suppresses the chat line.
  Existing channels: `::tp`, `::duel`, `::stake`.
- **Absolute drawing (DYNAMIC overlays):** the renderer `translate()`s every overlay by its computed
  location — including a **saved drag offset** if the overlay was ever movable (offsets persist in
  config across builds). Any overlay that draws at absolute canvas coordinates MUST undo that first:
  `final Rectangle b = getBounds(); g.translate(-b.x, -b.y);` at the top of `render()` — otherwise a
  stale offset silently pushes it off-screen (the invisible-ticker bug).
- **Mouse:** a `MouseAdapter` hit-tests `mousePressed` against the window; consume any click on the
  window (incl. `mouseClicked`/`mouseReleased`) so it never falls through to the world; return
  `OUTSIDE` for clicks off the window so the game still gets them.
- **Hover:** read `client.getMouseCanvasPosition()` each frame; apply `ROW_HOVER` / brightened accents.

---

## 9. Overlay catalog

| Overlay | Type | Size / anchor | State channel |
| --- | --- | --- | --- |
| `lofteleports` | Modal (tabbed) | 480×400 * | varp 4607 open + `::tp` |
| `lofduel` (rules) | Modal | 480×400 | varp 4630 + `::duel` |
| `lofstake` | Modal (inventory-clear) | 480×400, viewport-left | read iface 335 + `::stake` |
| `lofsupplies` | HUD gauge | content, ABOVE_CHATBOX_RIGHT | varp 4609 |
| `lofwarbar` | HUD bar | content, TOP_CENTER | varp 4601 |
| `lofalerts` | HUD banner | content, TOP_CENTER | plugin/varp 4600 |
| `loflms` | HUD panel | content, TOP_RIGHT | varp 4608 |
| `lofannouncements` | Ticker | content, above chat (absolute) | BROADCAST chat |
| `lofpkstats` | Ticker | content, TOP_LEFT | varps 4602-4606 |
| `lofcwtimer` | Ticker | content, ABOVE_CHATBOX_RIGHT | varps 4620-4623 |

\* `lofteleports` currently ships at 560×400 (pre-standard) — align to 480×400 when next touched.

---

## 10. New-overlay checklist

1. Import `LofTheme`; use its palette, `logo()`, and helpers — no hand-rolled colours/metrics.
2. Pick a category (§6): 480×400 framed modal, or content-sized corner HUD.
3. Header per §5 (shield + gold title + ember underline); footer buttons Accept=gold / Decline=ember.
4. State channel per §8: packed varp (document the bits + claim a free varp id in §8) **or** read an
   existing interface's widgets (null-guarded).
5. Actions via a `::cmd` token + a server command in `MessagePublicHandler`.
6. Antialiasing on at the top of `render`, restored before returning. `ABOVE_WIDGETS` layer.
7. Never cover a control the player must click while the window is open (esp. the inventory).
8. Add a row to the §9 catalog.

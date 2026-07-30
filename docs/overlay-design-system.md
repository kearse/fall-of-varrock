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

**Stick to characters the RuneScape font actually has.** The `.ttf` covers ASCII plus a handful of
symbols (`— … × · § ¼`) but **not** the bullet `•`, en-dash `–`, arrows, or check/cross marks — those
render as a missing-glyph box (tofu) in an overlay. Use `·` as a separator, `-` for ranges, and draw a
close ✕ as two strokes (see `LofModal.frame`) rather than as a glyph.

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
5. **Footer** — right-aligned action buttons on the bottom row. **Primary / Accept = `GOLD`**,
   **Decline / destructive = `EMBER`**, secondary (Load Last, steppers, etc.) = `GOLD_DIM`,
   left-aligned. Draw them with **`LofModal.button(...)`** — the fill + border carry the accent, but
   the **label is always high-contrast** (a dark accent like ember renders its label near-white, so a
   button can never be red-on-dark and "mix in"). Default a neutral/primary action to `GOLD`; reserve
   `EMBER` for the genuinely destructive / decline action only.
6. **Status line** — one line of `getRunescapeSmallFont()` just above the footer; green when a
   one-sided accept is pending, else `TEXT_DIM`.

---

## 6. Standard sizes & positioning

Two categories. Pick the smallest that fits.

### A. Framed modal — one default size, one dynamic placement

**Every framed modal is the same size and is placed by the same code.** The default is **480 × 324**
(`LofModal.W` × `LofModal.H`) — teleport, ranks, make, forge, contracts, dice, bonds, mire, duel rules,
and the stake screen all use it. The **height fits inside the fixed-mode world view (334px)** so the
window sits centred in the game viewport, clear of the chat box — the way a default OSRS interface
does. A few windows are a different size — the kit editor **and the Grand Exchange** are **512×324**
(viewport-width, but the same chat-clear height: the GE wants the big native-GE frame for its 4-column
offer board, and 512 is the widest that still centres *beside* the inventory column in fixed mode,
which its sell flow needs — it right-clicks real inventory items); recruit 480×384 and the
character-style window 480×420 have fixed layouts that can't compress to 324; vote is 492×auto and war
spoils 340×320 — all still **placed by the same authority** as the standard ones, and all still scaled
by `beginWindow` (§6A′), so "bigger window" never means "hand-rolled placement".

**Content taller than the window scrolls — it does not grow the window.** A recipe/loot list uses a
clipped viewport with `LofModal.clampScroll` + `LofModal.scrollbar` and a `MouseWheelListener` →
`overlay.handleScroll(...)` in the plugin (mirror the teleport list; make and forge do this). This is
how the short standard window holds an arbitrarily long list without covering the chat.

**Placement is single-sourced in [`LofModal`](../client/runelite-client/src/main/java/net/runelite/client/plugins/loftheme/LofModal.java)
— never hand-roll `originX`/`originY` in an overlay again.** Each overlay's origin is one line:

```java
private int originX() { return LofModal.originX(client, WIN_W); }
private int originY() { return LofModal.originY(client, WIN_H); }
```

(Standard-size windows can call the no-arg `LofModal.originX(client)` / `originY(client)`, or
`LofModal.bounds(client)` for the whole rectangle.) Set `WIN_W`/`WIN_H` to `LofModal.W`/`LofModal.H`
so a standard window inherits the default size and follows if the default ever changes.

**The placement is dynamic and OSRS-faithful — it re-centres on the live canvas every frame instead
of being pinned to one pixel spot:**

- **Fixed pixel size.** Like a real OSRS interface, the window is authored at one design size and
  does **not** rescale with resolution; it just re-centres. (Client-wide uniform scaling already
  exists — the `stretchedmode` plugin scales *everything*, our overlays included — so we don't
  re-implement it per window.)
- **Horizontal:** centre in the **game viewport**, not the whole canvas. In fixed mode the world view
  is the left ~512px (`LofModal.FIXED_VIEWPORT_W`), so the window sits clear of the inventory/tab
  column on the right — which keeps the inventory clickable while the stake screen is open. A window
  **wider** than the viewport can't fit beside the inventory, so `LofModal` centres it on the whole
  canvas instead. In resizable mode everything centres on the whole canvas.
- **Vertical:** centre in the game view but **keep the window above the chat box** (`CHATBOX_RESERVE`,
  a ~165px bottom band). On a roomy canvas this is a plain vertical centre; as the client shrinks the
  window rides up to stay off the chat, and only a window taller than the room above the chat (a
  minimised client) reaches into that band. This is what "centred in the game viewport, not covering
  the chat" means — a default OSRS interface sits the same way.
- **Always clamped:** the origin is clamped so the window never runs off any edge, even on a canvas
  smaller than the window — a too-big window pins to the top/left edge rather than centring off-screen.
- Position with `OverlayPosition.DYNAMIC` and draw at absolute `ox/oy`; these windows are not movable
  (they're modal to a flow). Cache `ox/oy` on the client thread in `render()` and read only the cache
  from the click path (§8).

### A′. Scaling — windows grow with the canvas (auto-scale + Stretched Mode)

A real OSRS interface is a **fixed pixel size** and only re-centres — which is why our windows used
to look tiny on a big monitor. OSRS fixes that with **Stretched Mode** (it scales the whole client).
We do **both**: our framed modals auto-scale with the canvas, *and* compose correctly when Stretched
Mode is on. One authority, `LofModal`, owns it — **never hand-roll a scale or a `g.scale()` in an
overlay.**

- **`LofModal.uiScale(client)`** returns a **whole-integer** scale (`1x` or `2x`) derived from the
  **logical** canvas (`getCanvasWidth/Height`) against a `765×503` baseline, floored to a whole step
  and capped at `SCALE_MAX` (2). Integer only, because the RuneScape font is a **pixel font** — a
  fractional scale (e.g. `1.6x` → a `25.6px` font) blurs every glyph, so we snap rather than scale
  continuously. It reads the logical canvas — **never** the stretched/window dimensions — so Stretched
  Mode composes: fixed + stretched keeps the logical canvas small (scale = 1, Stretched Mode does the
  enlarging); resizable grows the logical canvas, so our windows grow a whole step at a time.
- **Draw through `LofModal.beginWindow` / `endWindow`.** `beginWindow(g, client, baseW, baseH)` places
  the *scaled* window with the standard origin authority and applies a **pivot-scale about the
  origin**, so the overlay keeps drawing at its existing `ox + PAD …` coordinates using the authored
  base constants — the transform does the scaling. The scale is a whole integer and `beginWindow` forces
  **text antialiasing off** (restored in `endWindow`), so the pixel font renders crisp, not blurred. It
  returns a `Placement` (`ox`, `oy`, `scale`); cache it in a `volatile` field. Call `endWindow(g, place)` before
  every return that runs after `beginWindow`.
- **Hit-test through the cache.** The mouse thread reads the cached `Placement` and maps the canvas
  point with `place.toLocal(canvasPoint)` into the window's authored space, then tests the same
  `ox+…` rects unchanged. `render()` maps the hover mouse the same way
  (`place.toLocal(mousePoint())`). This extends the §8 rule: origin **and scale** are computed on the
  client thread and the click path reads only the cache — never re-derives origin/scale off-thread.
- **Exceptions.** A window whose visual is pinned to the un-scaled game render can't take the
  transform: `lofstyle` frames the live in-world character model through a see-through hole in the
  panel, so it stays at 1.0 (scaling the panel would desync it from the model). Native-anchored
  storefronts (`lofshoptabs`) size to the game's shop interface (group 300) and so scale with
  Stretched Mode / client zoom rather than `uiScale`.

### B. HUD overlay — content-sized, corner-anchored
War dial row (supply · campaign/conquest · Slayer), alerts banner, LMS panel, announcement ticker, PK stats, CW timer.

- **Sized to content**, not the 480×324 frame. Small.
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
- **Buttons** (`LofModal.button`) — rounded, hairline accent border, centred label in a
  **high-contrast** colour (never the low-contrast accent); fill brightens on hover / when active.
  Primary/Accept=`GOLD`, Decline/destructive=`EMBER`, secondary/steppers=`GOLD_DIM`. Long values in a
  value-box are clipped (`fit(...)`) so they never overrun an adjacent button.
- **Scrollbar** — 5px `alpha(EMBER,190)` rounded thumb on an `alpha(white,14)` track.
- **Circular gauge** (`lofdials` row) — `PANEL_OPAQUE` disc, track ring `alpha(white,28)`, value arc from
  12-o'clock clockwise; ember while filling, gold + pulse when a threshold is met, `LAVA` at the top tier.
  The dial row draws several of these right-anchored so they **stack leftward** (supply pinned far right,
  campaign/conquest and Slayer to its left), each present only when its varp has data.
- **Progress bar** — `PANEL_OPAQUE` track, ember/gold fill, `EMBER_DARK` border, centred shadowed label.
  A reusable horizontal pattern (no HUD currently ships one — the war progress is a dial in `lofdials`).
- **Banner / toast** (alerts) — `PANEL_OPAQUE` rounded panel, pulsing `EMBER` border, shield logo +
  bold text.
- **Ticker** (announcements) — transparent, outlined coloured text, absolute-positioned above the chat.

---

## 8. Interaction & data channels

- **State → client:**
  - *Small state* (toggles, counters, flags): one **packed varp**. Document the bit layout in the
    server driver. Transient/event varps must **pulse to 0** and be cleared on login (persisted varps
    re-fire on login — see `docs/custom-client.md` §5e). Current varps: 4600 alert · 4601 war progress ·
    4602-4605 pk stats · 4606 wild level · 4607 teleport open · 4608 LMS · 4609 supplies · **4610-4612
    quests** (recruit packed / war-prep step / guide-muted — the client's `lofquests` reads these) ·
    4613-4615 companion status · 4616 slayer · 4617 quests (rogue problem) · **4618 rank menu (payload =
    title ordinal + 1)** · **4619 recruit menu (packed: open|count|cap|title)** · 4620-4623 Castle Wars ·
    **4624 supply depot** · **4625 making window (payload = kind)** · **4626 war contracts** ·
    **4627 war forge** · **4628 dice (open / result: bit9 flag, roll bits 1-7, win bit 8)** ·
    **4629 bond exchange (packed: open|tradeable|claimed)** · **4630 duel rules** ·
    **4631 mire dispenser (packed: open|data|attuned|bank|streak)** ·
    **4632 character style (open|female)** · **4633 quests (King of Lumbridge / conquest step)** ·
    **4634 dice bank coins (spendable = client inventory +
    this bank balance)** · **4635-4637 companion world-indices** (server→client; was double-claimed on
    4610-4612 over the quest varps, which pointed the guidance arrow at Duke Horacio) ·
    **4640-4679 kit editor** (control + per-slot — was
    missing from this list; a parallel branch DID double-claim 4631 the same week — keep EVERY
    varp here).
    (4601/4609/4616 all feed the `lofdials` dial row.)

    **Windows are exclusive** (`LofWindows` in loftheme): register the overlay, call
    `openExclusive()` before showing, and forward VarbitChanged to `onForeignSignal` — a window
    left open would otherwise sit on ALWAYS_ON_TOP over the next window (or the kit editor) and
    swallow its clicks.

    **Command tokens MUST be lof-prefixed** (`::lofdice`, `::lofrank`, …): the vanilla gamepack
    silently blocks a hardcoded scam-bait list of `::` words client-side (::bank proven, ::dice
    hit us) — the token never reaches the server. `lof*` can never collide with that list.
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
- **Action → server:** send `"::lof<cmd> <args>"` via `runScript(ScriptID.CHAT_SEND, …)`; the server
  intercepts it in `MessagePublicHandler`, runs the matching command, and suppresses the chat line.
  **Every** token is lof-prefixed (see the scam-bait note above) — `::loftp`, `::lofduel`,
  `::lofstake`, `::lofkit`, `::lofmire`, `::lofspoils`, `::lofshopbuy`, `::lofmake`, … The server
  keeps the bare aliases (`::tp`, `::duel`, …) so you can still drive a window by hand for testing,
  but a client that ships one is broken: `::duel` and `::stake` are on Jagex's block list and never
  leave the gamepack. When you add a channel, add BOTH the client token and its
  `MessagePublicHandler` branch — a token with no branch is broadcast as public chat.
  `ScriptID.CHAT_SEND` takes **1 string + 4 ints** (`msg, 0, 0, 0, -1`); mismatch the stacks and the
  send silently never happens.
- **The click path must never read client state.** `hitTest()` and the MouseListener run on the AWT
  **mouse** thread. `client.getWidget(...)`, `client.getItemContainer(...)`, `getVarpValue`,
  `getBoostedSkillLevel` and helpers like `LofModal.carried(...)` return **null/stale** off the
  client thread, so an affordability/visibility gate quietly fails, `hitTest` falls through to
  `INSIDE`, and the click is swallowed — **the button renders lit and does nothing**. This has now
  bitten us three times (shop window, then the whole smelt/rank/kit/duel/stake/dice/mire/recruit
  set). The rule: compute every gate on the client thread inside `render()`, store it in a
  `volatile` field, and have the click path read **only** those fields. Window origin included.
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
| `lofteleports` | Modal (tabbed) | 480×324 | varp 4607 open + `::tp` |
| `lofranks` | Modal (progression) | 480×324 | varp 4618 (rank payload) + `::rank buy` + inventory coins |
| `lofrecruit` | Modal (cards + tabs) | 480×384 * | varp 4619 (packed) + 4613-4615 roster + `::zo recruit/regalia` |
| `lofshoptabs` | Modal (store) | anchored over native iface 300 | `FOV_SHOP:` stock/tabs/balance stream + `::lofshopbuy` / `::lofshopsell` (sell-only stores, e.g. the Quartermaster's Supply Depot) |
| `lofmake` | Modal (recipes) | 480×324, scrolls | varp 4625 (kind) + `~LOFMAKE~` recipes + `::make` |
| `lofcontracts` | Modal (board) | 480×324 | varp 4626 + `~LOFCON~` state + `::con` |
| `lofforge` | Modal (recipes) | 480×324, scrolls | varp 4627 + `~LOFFORGE~` recipes + `::forge` |
| `lofdice` | Modal (table) | 480×324 | varp 4628 (open/result) + `::dice roll` + inventory coins |
| `lofbonds` | Modal (wallet) | 480×324 | varp 4629 (packed wallet) + `::lofbond` |
| `lofmire` | Modal (loot table) | 480×324 | varp 4631 (packed) + `::mire` + inventory coins |
| `lofstyle` | Modal (portrait cutout) | 480×420, viewport-centred | varp 4632 (open|female) + `::lofstyle` — a see-through hole in the panel centre frames the in-world model as the live preview; the server hides worn gear while open (`STYLE_PREVIEW_ATTR`) and `::lofstyle close`/`done` restores it |
| `lofduel` (rules) | Modal | 480×324 | varp 4630 + `::duel` |
| `lofstake` | Modal (inventory-clear) | 480×324, viewport-left | read iface 335 + `::stake` |
| `lofdials` | HUD dial row | content, ABOVE_CHATBOX_RIGHT | varps 4616 slayer · 4601 progress · 4609 supplies |
| `lofalerts` | HUD banner | content, TOP_CENTER | plugin/varp 4600 |
| `loflms` | HUD panel | content, TOP_RIGHT | varp 4608 |
| `lofannouncements` | Ticker | content, above chat (absolute) | BROADCAST chat |
| `lofpkstats` | Ticker | content, TOP_LEFT | varps 4602-4606 |
| `lofcwtimer` | Ticker | content, ABOVE_CHATBOX_RIGHT | varps 4620-4623 |

\* `lofrecruit` is taller than the 324 standard (its three fixed-layout companion cards can't
compress); it's still placed by `LofModal.originY`, so it centres in the viewport like the rest.

All modals above are placed by `LofModal.originX/originY` (§6A) — the single dynamic placement
authority. When adding one, don't reimplement the origin math; call `LofModal`. Dense list content
scrolls (`LofModal.clampScroll`/`scrollbar` + a wheel listener) rather than growing the window.

---

## 10. New-overlay checklist

1. Import `LofTheme`; use its palette, `logo()`, and helpers — no hand-rolled colours/metrics.
2. Pick a category (§6): standard **480×324** framed modal, or content-sized corner HUD.
3. **Frame + place + scale a modal through `LofModal` — never hand-roll origin, size, or scale.**
   At the top of `render()`, after undoing the renderer translate, call
   `final LofModal.Placement place = LofModal.beginWindow(g, client, WIN_W, WIN_H);` and cache it in a
   `volatile` field; draw the window at your existing `place.ox + PAD …` coordinates; call
   `LofModal.endWindow(g, place)` before every return after `beginWindow` (§A′). Set
   `WIN_W`/`WIN_H` to `LofModal.W`/`LofModal.H` for the standard size.
4. Header per §5 (shield + gold title + ember underline); footer with `LofModal.button(...)` —
   **primary/Accept = gold, Decline/destructive = ember, secondary = gold-dim**; the helper keeps the
   label legible.
5. **Hit-test through the placement cache.** In `hitTest` (and any `handleScroll`), read the cached
   `Placement`, `null`-guard it, map the canvas point with `place.toLocal(canvas)`, and test your
   existing `ox+…` rects. Map the hover mouse in `render()` the same way. Compute every gate on the
   client thread; the click path reads only the cache (§8).
6. State channel per §8: packed varp (document the bits + claim a free varp id in §8) **or** read an
   existing interface's widgets (null-guarded).
7. Actions via a `::lof<cmd>` token + a server command branch in `MessagePublicHandler`.
8. Antialiasing on at the top of `render`, restored before returning. Layer per §6: framed
   modals on `ALWAYS_ON_TOP`, corner HUDs on `ABOVE_WIDGETS`.
9. Never cover a control the player must click while the window is open (esp. the inventory).
10. Add a row to the §9 catalog. Copy an existing standard modal (e.g. `lofteleports` or `lofdice`) as
    the template.

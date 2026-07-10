# UX Review — To-Do List (2026-07)

> Findings from a full player-experience review (onboarding, gameplay loop, custom client,
> web/Discord). Kept as a checklist; strike items as they land. Detail and file citations
> are inline per item.

## High priority — journey dead-ends

- [ ] **Client downloads are empty by default.** All four platform URLs unset in
  `Alter/web/.env.example:21-24`; every `/play` card renders "Building…" while the home page
  promises "download and play in minutes". Fill the env values (or soften the home copy until
  installers exist).
- [ ] **Discord invite is a placeholder.** `NEXT_PUBLIC_DISCORD_URL` ships as the literal
  `https://discord.gg` (`web/.env.example:17`, used by `SiteHeader.tsx:41`, `page.tsx:77,352`).
- [ ] **War-Prep finale unlocks nothing.** `WarPrepChain.kt:269` announces "the war's raids are
  opening to you" but `raidReady()` (`WarPrepChain.kt:110`) is never called; the only raid is the
  `::testraid` smoke test (`Raids.kt`). Point the finale at real content (see story/sortie work).
- [ ] **Store buttons render with no provider configured** — every purchase 503s after the click
  (`BuyButtons.tsx:5-42`, `api/store/checkout/route.ts:56-66`). Pre-disable like the download
  cards, or configure a provider.

## High priority — guidance gaps

- [ ] **Post-Squire guidance cliff.** War-Prep quests 2–5 are scaffolds (`WarPrepChain.kt:34-37`);
  next milestone is Lord at 2M coins earned ~50 gp/goblin (`WarEffortPlugin.kt:75`). Needs
  mid-game quests + the gated-economy coin faucet (long-term-vision §3, roadmap #4).
- [ ] **Teleport portal is undiscoverable.** No NPC or message ever mentions it; it silently
  replaces the fountain (`TeleportPortalObjectPlugin.kt`). Have the Sergeant/Duke point at it once.
- [ ] **Stock quest tab still lists ~200 vanilla quests** (misleading). Cache DBTable rewrite is
  researched in `docs/custom-quests.md` §3.
- [ ] **War state has no stock-client fallback** — progress bar/supply dial exist only as varps
  for the custom client (`WarProgressPlugin.kt`, `WarSupplyHudPlugin.kt`). Add chat/interface
  fallback or make the custom client the hard requirement everywhere in copy.

## Medium — reads as broken

- [ ] **Seven skilling teleports land on the identical tile** `(3243, 3193)`
  (`TeleportRegistry.kt:58-65`). Differentiate or collapse into one "Skilling camp" entry.
- [ ] **Login screen "Register?" / "Forgot?" links draw but aren't clickable**
  (`LofLoginRenderer.java:245,249` vs. hit-tests at `:280-342`).
- [ ] **No character-appearance screen on first login** — `LoginAppearancePlugin.kt` commented
  out; `APPEARANCE_SET_ATTR` written but never read (`PlayerSaving.kt:91,158`).
- [ ] **Half-finished rebrand in client plugin list** — mixed "Lof …" and "Kingdom of
  Lumbridge …" descriptors (alerts, pkstats, announcements, cwtimer plugins).
- [ ] **Store advertises Discord roles but never says linking is required** (`store.ts:71-100`;
  success page `store/success/page.tsx:25-28` doesn't mention `/link`).
- [ ] **Login/register always redirect to home** (`AuthForm.tsx:34`) — buyers lose their place
  in the store; support a `?next=` return URL.

## Lower priority / polish

- [ ] Teleport modal fixed 560×400 overflows right edge below 560px canvas width
  (`LofTeleportsOverlay.java:46-47,105`); login layout `ox` unclamped, panel renders off-screen
  under 765px width (`LofLoginRenderer.java:44,155`).
- [ ] Long teleport names can collide with the "FREE" tag (`LofTeleportsOverlay.java:251-254`).
- [ ] No hover feedback on unbuilt teleport cards (`LofTeleportsOverlay.java:234`).
- [ ] Supplies dial: label can say "CONQUEST READY" without the gold pulse
  (`LofSuppliesOverlay.java:88-96`).
- [ ] Currency sprawl (~10 currencies; two marked vestigial in `Currencies.kt:24-35`) — cull or
  document in-game.
- [ ] `::sendtroops` price hardcoded 1M (`CampaignCommandPlugin.kt:215`) while
  `CampaignTier.RAID.cost = 0` (`CampaignOp.kt:31`) — single source of truth.
- [ ] Supply hand-ins don't show realm-meter movement unless it crosses a threshold
  (`RealmSupply.kt:28-32`) — echo the meter on each deposit.
- [ ] Discord event feed marks events posted even when `channel.send()` throws
  (`eventFeed.ts:74-78`) — events silently lost.
- [ ] Linking codes never expire and panel shows no validity/cancel (`api/discord/link/route.ts:51-60`,
  `DiscordLinkPanel.tsx:51-58`).
- [ ] Quest Journal sidebar doesn't use `LofTheme` (stock RuneLite colors + mismatched gold hex
  `LofQuestsPanel.java:248`) — acceptable, but note it if brand consistency matters.

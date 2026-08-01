# Fall of Varrock — website improvement notes

From a full page-by-page run-through (home, hiscores, player profile, store, vote,
register/login, news, wiki, forum, footer). Ordered by impact vs effort.

## Quick wins (high impact, low effort)

1. **Favicon + social image — MISSING.** No favicon (generic browser-tab icon) and no
   Open Graph / Twitter image, so links pasted in Discord/social unfurl blank. Add
   `app/icon.svg` (the emblem), `app/apple-icon.png`, an `opengraph-image`, and
   `openGraph`/`twitter`/`metadataBase` in `layout.tsx`. Fast win using the Crest.
2. **Dial back "everything crimson."** Hiscores total-levels, the player-profile skill
   numbers, and stat values are ALL crimson — it reads as a wall of red. Reserve
   crimson for emphasis (rank 1–3, headings, links, hover) and use white/ash
   (`#e8e2d9`) for data values. Instantly more premium.
3. ~~Real Discord invite~~ — FIXED: `NEXT_PUBLIC_DISCORD_URL` and the in-code
   fallbacks now use the real invite (`https://discord.gg/AmtccSBKYz`).
4. ~~News em-dash rendered as "?"~~ — FIXED this pass (PowerShell→mongo mangled the dash).

## Page-level

5. **Auth (login/register):** a lone form card on a big empty page. Make it a
   split-screen — form on one side, ruined-Varrock art + 3 "why join" bullets on the
   other. Fills the dead space, feels intentional.
6. **Vote page:** currently just a username box (feature was deferred). Build out cards
   per vote site (name/logo, "+X vote points", 12h cooldown state), a short "why vote"
   line, and optionally a Top Voters leaderboard.
7. **News index:** bare with one post. Add a featured-latest hero + a grid for older
   posts, category filter chips, and an empty-state. (Also: needs more actual posts.)
8. **Player profile:** add a proper header — avatar/emblem, feudal **rank title**
   (colored), citizenship/city, combat level, last-seen. Add **OSRS skill icons** beside
   each skill (big upgrade over text-only). 
9. **Hiscores:** skill icons in the left rail + rows; values in white with crimson only
   for the top 3 / on hover; a "your rank" highlight when logged in.
10. **Store:** product item-art is ~20% opacity (barely visible) — make it prominent;
    add a "Most popular" glow on the featured tier and category tabs
    (Membership / Points / Bundles / Bonds).

## Bigger / nice-to-have

11. **Home — more below the fold** (to match top RSPS sites): a "The War" showcase with
    screenshots/gif, a media strip, a getting-started 3-step, a stronger Discord/community
    band. Optional subtle parallax/zoom on the hero image.
12. **Screenshots / media:** the site has almost no in-game imagery (only item gifs). A
    gallery of real screenshots or AI ruin-art across pages would sell the game far more
    than copy alone.
13. **Wiki:** keep the parchment homage, but optionally add a dark variant that matches
    the site for consistency.
14. **Empty states + loading skeletons** across the data pages (hiscores, news, forum
    activity) for a polished first-load.

## Global / technical

15. **Mobile QA pass** — verify the header hamburger, hero text scaling (`text-8xl` on
    small screens), table horizontal-scroll, and the 3-up stat grids at 375–414px. Code
    uses responsive classes but needs a real device/emulator pass.
16. **SEO** — `metadataBase`, per-page `openGraph`, a `sitemap.xml` (robots.txt exists).
17. **Accessibility** — contrast-check small crimson/muted text on dark; add
    `focus-visible` rings on the custom nav; confirm interactive labels.
18. **Performance** — serve hero art as `webp` with a responsive `srcset`; lazy-load
    below-the-fold images.

---
Recommended first batch: **1, 2, 3** (favicon/OG, tone down the red, Discord link) —
small changes, site-wide payoff.

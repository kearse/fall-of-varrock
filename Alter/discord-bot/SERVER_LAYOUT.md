# Fall of Varrock — Discord Server Template

This is the **server layout blueprint** — the categories, channels, roles and
permissions for the official Fall of Varrock Discord. It is the equivalent
of the "template" you linked on discordextremelist; the difference is that a
Discord template is a *static layout snapshot* with **no integration logic**, so
the live integration is done by the bot in this folder.

There are two ways to stand this layout up:

1. **Automatic (recommended).** Invite the bot, then run `/setup` in your server.
   The bot creates every role, category and channel below, in order, with the
   permission overwrites already applied. See [`README.md`](./README.md).
2. **Shareable template link.** Build the server once (by hand or via `/setup`),
   then in Discord go to **Server Settings → Template → Generate Template**. That
   gives you a `discord.new/<code>` link you can share/list anywhere — exactly
   like the example you sent. Note again: that link only clones the *layout*; new
   servers created from it still need this bot invited to get integration.

---

## Roles

Top of list = highest. Colours are hex. `Varrock Bot` must sit **above** every
role it manages (donor/member/linked/player) so it can assign them.

| Role | Colour | Source of truth | Notes |
|------|--------|-----------------|-------|
| 👑 King | `#d4af37` | `accounts.roles` contains `owner` | Owner |
| ⚔️ Minister | `#c0392b` | `accounts.roles` contains `admin` | Full admin |
| 🎙️ Community Manager | `#9b59b6` | `accounts.roles` contains `community_manager` | Community lead |
| 🛡️ Senior Mod | `#1abc9c` | `accounts.roles` contains `senior_mod` | Senior moderation |
| 🗡️ Lord | `#2980b9` | `accounts.roles` contains `moderator` | Staff/mod |
| 🧙 Developer | `#8e44ad` | `accounts.roles` contains `developer` | Dev team |
| 📣 Support | `#16a085` | `accounts.roles` contains `support`/`helper` | Helpers |
| 🤖 Varrock Bot | `#5865f2` | the bot's own integration role | Auto, keep high |
| ── Donors ── | `#000000` | divider (hoisted, no perms) | cosmetic separator |
| 💎 Diamond Donor | `#b9f2ff` | `membership.tier == "diamond"` | |
| 🥇 Gold Donor | `#f1c40f` | `membership.tier == "gold"` | |
| 🥈 Silver Donor | `#bdc3c7` | `membership.tier == "silver"` | |
| 🥉 Bronze Donor | `#cd7f32` | `membership.tier == "bronze"` | |
| ⭐ Member | `#e67e22` | `membership.tier != null` (any active) | |
| 🔗 Linked | `#2ecc71` | `discord_links.discordUserId` set | granted on `/link` |
| 🎮 Player | `#95a5a6` | everyone who has linked at least once | default in-game role |
| @everyone | — | — | unverified / not linked |

The donor→role and staff→role mapping lives in
[`src/roles/roleMap.ts`](./src/roles/roleMap.ts) and can be edited without
touching the rest of the bot.

---

## Categories & Channels

> `(🔗)` = visible only to **Linked** members and above.
> `(staff)` = visible only to staff roles + bot.
> `[feed:KEY]` = the bot posts an automated feed here; `KEY` maps to an env var
> (see `.env.example`) so you can point a feed at any channel by ID.

### 📢 INFORMATION  *(read-only for @everyone)*
- `#welcome` — auto-welcome + how to `/link` (seeded; greets new members)
- `#rules` — seeded rules
- `#download` — client download links (Windows/Mac/Jar) + website link (seeded)
- `#guides` — index of the website guides, each with a "Read full guide ↗" button
- `#server-status` — live bot board: world UP/DOWN + online count (refreshes ~1 min)
- `#roles` — self-assign notification roles (button panel)
- `#announcements`  `[feed:NEWS]` — website news posts land here
- `#updates`  `[feed:STATUS]` — server boot/shutdown + status
- `#how-to-play`

### 💬 COMMUNITY
- `#general`
- `#introductions`
- `#off-topic`
- `#media`  *(screenshots/clips)*
- `#memes`
- `#polls`  *(use Discord's built-in poll feature)*
- `#community-events`

### 🎉 GIVEAWAYS
- `#enter-giveaway`
- `#daily-winners`  *(read-only)*
- `#weekly-winners`  *(read-only)*
- `#check-eligibility`

### ⚔️ GAME  *(🔗)*
- `#game-chat`
- `#hiscores` — live bot board: top-10 by total level (refreshes ~1 min)
- `#achievements`  `[feed:ACHIEVEMENTS]` — level 99s & milestone levels
- `#drops-showcase`  `[feed:DROPS]` — rare drops
- `#pk-highlights`  `[feed:PK]` — wilderness kills
- `#boss-and-war`  `[feed:BOSS]` — boss kills, raid & war campaign results
- `#goals-and-grinds`

### 🏪 SUPPORT & STORE
- `#store`  *(read-only; links to the website store)*
- `#donations`  *(read-only; perks + how donor roles sync)*
- `#market`  *(player-to-player trading)*
- `#support-ticket`  *(read-only; "Open a ticket" button → private staff thread)*
- `#bug-reports`
- `#suggestions`

### 🔗 ACCOUNT
- `#link-account` — instructions; `/link <code>` is used here
- `#bot-commands` — `/hiscores`, `/player`, `/online`, `/whoami`

### 🔊 VOICE
- `General`
- `PKing`
- `Staking / 1v1`
- `Skilling`
- `Bossing / PVM`
- `AFK` *(no text)*

### 🛡️ STAFF  *(staff)*
- `#staff-chat`
- `#staff-commands` — `/sync`, `/setup`, moderation
- `#mod-log`  `[feed:MODLOG]` — audit trail (optional)

---

## Permission model (summary)

- `@everyone`: read INFORMATION + COMMUNITY, **cannot** see GAME/ACCOUNT-gated
  channels until they hold the **Linked** role.
- `Linked`: unlocks the GAME category and `#bot-commands`.
- Donor/Member roles: cosmetic colour + hoist + (optional) a `#donator-lounge`
  you can add later; no extra channel grants by default.
- Staff roles: full access to the STAFF category and moderation.
- `Varrock Bot`: needs **Manage Roles, Manage Channels, Send Messages, Embed
  Links, Use Application Commands**, plus **Create Private Threads / Manage
  Threads / Send Messages in Threads** for the ticket system. For `/setup` it
  also needs **Manage Server** briefly. Keep its role above all managed roles.

**Tickets:** `#support-ticket` holds an "Open a ticket" button (posted by `/setup`
or `/ticketpanel`). Clicking it spins up a **private thread** named
`ticket-<user>`, adds the user, and pings the Support role; staff see it via
their Manage Threads permission. A **Close ticket** button locks + archives it.

## Content & automation

`/setup` doesn't just make empty channels — it also fills them:

- **Seed content** — polished embeds (with link buttons) in `#welcome`, `#rules`,
  `#download`, `#how-to-play`, `#store`, `#donations`, `#link-account`,
  `#bot-commands`, `#market`, `#bug-reports`, `#check-eligibility`. All copy lives
  in [`src/seed/content.ts`](./src/seed/content.ts); edit it and re-run `/seed` —
  existing posts are refreshed **in place** (matched by embed title), so copy and
  URL changes propagate without deleting anything. `/setup` runs the seed
  automatically.
- **#guides index** — `#guides` gets a card per guide with a "Read full guide ↗"
  link button. Full guides live on the **website** (`/guides`), seeded via
  `web/scripts/seed-guides.ts` (`npm run seed:guides`); the Discord index just
  links out. Guide list/slugs: [`src/seed/guidesIndex.ts`](./src/seed/guidesIndex.ts)
  (keep slugs in sync with the website seed).
- **Live boards** — `#server-status` and `#hiscores` are single pinned messages
  the bot edits every minute (no spam). Status uses a TCP probe of the game world
  (`GAME_HOST`/`GAME_PORT`), so it needs no game-side code.
- **Giveaways** — `/giveaway start prize:<…> duration:<30m|6h|2d> [winners] [type]`
  posts an Enter button in `#enter-giveaway`; entrants must be **linked**; the bot
  draws at the deadline and announces in `#daily-winners` / `#weekly-winners`.
  `/giveaway end` and `/giveaway reroll` also exist.
- **Self-roles** — a button panel in `#roles` lets members toggle 🔔 Updates,
  🎉 Giveaway Pings, ⚔️ PvP Pings, 🐲 PvM Pings.
- **Welcome** — new members are greeted in `#welcome` with linking pointers.

> Chat channels (`#general`, `#off-topic`, `#memes`, `#media`, `#game-chat`) are
> intentionally left for the community — the bot doesn't post filler there.

The exact overwrites are encoded in
[`src/setup/layout.ts`](./src/setup/layout.ts), which `/setup` reads to build the
server. Edit that file to change the structure and re-run `/setup` (it is
idempotent — it updates/creates, it does not duplicate).

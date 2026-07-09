# Fall of Varrock — Discord bot

The Discord gateway worker for Fall of Varrock. It is the **integration**
half of the Discord setup; the server **layout** lives in
[`SERVER_LAYOUT.md`](./SERVER_LAYOUT.md).

It shares the **same MongoDB** as the website (`web/`) and the game server, and
does four things:

| Feature | How it works |
|---------|--------------|
| **Account linking** | User generates a code on the website → runs `/link <code>` → bot stamps `discord_links` and grants the **Linked**/**Player** roles. |
| **Store → roles** | Reconciles donor/member/staff Discord roles from `accounts.membership` / `accounts.roles` on an interval and via `/sync`. A purchase (Stripe/PayPal/crypto) that grants membership in-game shows up as a Discord role automatically. |
| **In-game event feed** | The game's `DiscordBridge` writes events to the `discord_events` queue; the bot posts them to `#achievements`, `#drops-showcase`, `#pk-highlights`, `#boss-and-war`, `#updates`. |
| **Hiscores commands** | `/player <name>`, `/hiscores`, `/online`, `/whoami` read player data straight from the shared save. |
| **Tickets** | `#support-ticket` has an "Open a ticket" button → private staff thread. Re-post the panel with `/ticketpanel`. |
| **Seed content** | `/setup` (and `/seed`) fill the info channels with embeds + buttons. Copy lives in `src/seed/content.ts`. |
| **Guides** | `#guides` indexes the website guides (`/guides`) with link buttons. List in `src/seed/guidesIndex.ts`; full guides seeded via web `npm run seed:guides`. |
| **Live boards** | `#server-status` (world UP/DOWN via TCP probe + online count) and `#hiscores` (top-10), pinned messages refreshed every minute. |
| **Giveaways** | `/giveaway start\|end\|reroll`; enter via button (must be linked); auto-draw to `#daily-winners`/`#weekly-winners`. |
| **Engagement** | Self-assign notification roles in `#roles`, auto-welcome new members, 👍/👎 voting in `#suggestions`. |

## Prerequisites

1. A Discord application + bot (https://discord.com/developers/applications).
   - **Bot → Privileged Gateway Intents:** enable **Server Members Intent**.
   - **OAuth2 → URL Generator:** scopes `bot` + `applications.commands`;
     bot permissions: Manage Roles, Manage Channels, Send Messages, Embed Links,
     Read Message History, Use Application Commands, Create Private Threads,
     Send Messages in Threads, Manage Threads, Manage Messages (to pin the live
     boards), Add Reactions (for #suggestions voting). Invite with that URL.
2. The shared MongoDB running (same one `web/` and the game server use).

## Setup

```bash
cd discord-bot
npm install
cp .env.example .env        # fill in DISCORD_BOT_TOKEN, DISCORD_CLIENT_ID, DISCORD_GUILD_ID
npm run dev                 # or: npm start
```

On first run the bot registers its slash commands to your guild (instant).

### Build the server layout

In Discord, run **`/setup`** (needs Administrator). It creates every role,
category and channel from [`SERVER_LAYOUT.md`](./SERVER_LAYOUT.md), already
permissioned, and replies with the channel IDs for each feed:

```
DISCORD_FEED_ACHIEVEMENTS_ID=123…   # achievements
DISCORD_FEED_DROPS_ID=123…          # drops-showcase
...
```

Paste those into `.env` and restart, so the feeds post to the right channels.
`/setup` is idempotent — re-run it any time you edit `src/setup/layout.ts`.

> Want a shareable `discord.new` template link like the example? After `/setup`,
> go to **Server Settings → Template → Generate Template**. Remember the link
> only clones layout — any server made from it still needs this bot for the
> integration.

## The linking flow (end-to-end)

1. Logged-in user on the website `POST`s `/api/discord/link` (button in account
   settings) → gets an 8-char code (web route:
   `web/src/app/api/discord/link/route.ts`).
2. User runs `/link <code>` in Discord.
3. Bot matches the unclaimed `discord_links` doc, stamps `discordUserId`, and
   grants **Linked** + **Player**, then syncs donor/member roles.

## Configuration map

- **Role mapping** (donor tiers, staff, colours, thresholds):
  [`src/roles/roleMap.ts`](./src/roles/roleMap.ts)
- **Server structure** (categories/channels/permissions):
  [`src/setup/layout.ts`](./src/setup/layout.ts)
- **Event kind → channel routing:** `channelForEventKind` in
  [`src/config.ts`](./src/config.ts)
- **Bosses announced in the feed:** `BOSSES` in
  `game-plugins/.../content/discord/DiscordIntegrationPlugin.kt`

## How the game emits events

`game-server/.../discord/DiscordBridge.kt` writes to Mongo. It is wired from
`game-plugins/.../content/discord/DiscordIntegrationPlugin.kt` (login/logout
presence, PK kills, boss kills, boot) and from `LevelUpPlugin.kt` (level-99s).
To add a new feed event from anywhere in game code:

```kotlin
DiscordBridge.event(
    kind = "drop",
    title = "${player.username} received a Dragon Warhammer!",
    player = player.username,
    fields = listOf(DiscordBridge.Field("Boss", "Lizardman Shaman")),
)
```

If Mongo is unreachable the bridge disables itself after one warning — the game
loop is never affected. Disable entirely with `-DDISCORD_BRIDGE=false`.

## Hosting checklist — do this when you deploy the game server

The bot is **already wired into [`../docker-compose.yml`](../docker-compose.yml)**
(its own service, a `Dockerfile`, depends on `mongo`, and all the `DISCORD_*`
env vars), so `docker compose up -d` on the host brings it up with the site,
game, and database. It restarts on crash (`restart: unless-stopped`).

Discord can't be fully automated, so when you host, do these once:

1. **Secrets** — set these in the host's `.env` (read by docker-compose):
   `DISCORD_BOT_TOKEN`, `DISCORD_CLIENT_ID`, `DISCORD_GUILD_ID`.
2. **Intents** — confirm **Server Members Intent** is on (Developer Portal → Bot).
   Re-invite the bot if you changed its permissions (see "Prerequisites" above).
3. **Build the layout once** — the bot can't create the server or run `/setup`
   itself. After the first deploy, run **`/setup`** in Discord, then paste the
   feed channel IDs it prints into the host's `.env` and redeploy the bot
   (`docker compose up -d --build discord-bot`). The feeds stay quiet until those
   IDs are set.
4. **Public URLs** — set `SITE_URL` (and the game's `NEXT_PUBLIC_GAME_WORLD_HOST`)
   to the real domain so links in embeds and the store point at production.
5. **Mongo** — the bot must point at the **same** database as the game and site
   (compose already sets `MONGODB_URI=mongodb://mongo:27017`, db `lumbridge`).

> Not using Docker on the host? Run it under a process manager instead — `pm2
> start npm --name lumbridge-bot -- start` + `pm2 save` (see the local-run notes
> above). Either way the box has to stay powered on for feeds to be live.

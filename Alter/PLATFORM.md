# Kingdom of Lumbridge — Community Platform

Website + forum + store + Discord, all sharing one MongoDB with the game server so
**players use a single account everywhere**.

```
                       ┌────────────── MongoDB (shared) ──────────────┐
                       │ accounts · details · news · forum_* · guides │
                       │ orders · entitlements · discord_links · …     │
                       └──▲──────────────▲───────────────▲────────────┘
            ┌─────────────┘              │               └──────────────┐
   ┌────────┴────────┐         ┌─────────┴────────┐          ┌──────────┴───────┐
   │  game server    │         │  web (Next.js)   │          │  discord-bot     │
   │  (Kotlin)       │         │  site+API+admin  │          │  (discord.js)    │
   │ ·MONGO saves    │         │ ·shared auth     │          │ ·news/event feeds│
   │ ·reward delivery│         │ ·hiscores        │          │ ·account linking │
   │  on login       │         │ ·forum + guides  │          │ ·role sync       │
   │ ·::update cmd   │         │ ·store + webhooks │          │ ·/online /hiscores│
   └─────────────────┘         └──────────────────┘          └──────────────────┘
```

## Components
| Path | What it is |
|------|------------|
| `web/` | Next.js site: auth, hiscores, news, forum, guides, store, admin, account/Discord linking |
| `discord-bot/` | discord.js worker: drains `news` + `discord_events` to channels, `/link`, `/online`, `/hiscores`, role sync |
| `game-server/` (Kotlin) | env-driven Mongo, web-first login bootstrap, reward delivery, `::update` |
| `game-plugins/` (Kotlin) | `RewardDeliveryPlugin` (applies purchases on login), `UpdatePostPlugin` (`::update`) |

## Run the whole stack (local)
```bash
cp .env.example .env          # set AUTH_SECRET; add Discord/payment keys when ready
docker compose up -d          # mongo + web + discord-bot + game
# web → http://localhost:3000   game login server → localhost:43594
```

Run the web app on its own (faster dev loop):
```bash
docker run -d --name kol-mongo -p 27017:27017 mongo:7
cd web && npm install
AUTH_SECRET=dev MONGODB_URI=mongodb://localhost:27017 MONGODB_DB=lumbridge AUTH_COOKIE_INSECURE=1 npm run dev
```

## Switching the game to shared accounts (MongoDB)
1. Set `saveFormat: MONGO` in `game.yml`.
2. Migrate existing JSON saves: `cd web && npx tsx scripts/migrate-saves.ts`
   (non-destructive — upserts into Mongo, leaves the JSON files in place).
3. The game now reads/writes the same `accounts`/`details` collections as the site.

## How purchases reach the game
Store checkout → provider webhook verifies payment → writes an `entitlement` doc →
game's `RewardDeliveryPlugin` applies it on the player's next login (donor points,
membership, or items) and marks it applied. Idempotent on `orderId`.

## What still needs *your* input (everything else works without it)
- **Discord**: `DISCORD_WEBHOOK_URL` (auto-post news) + `DISCORD_BOT_TOKEN`/`DISCORD_GUILD_ID`
  (linking, feeds, role sync). Without these the site still works; news just queues.
- **Payments**: Stripe / PayPal / Coinbase keys. Without them the store shows packages and
  the checkout buttons return "not configured yet" (HTTP 503). `STORE_DEV_CHECKOUT=1`
  enables an instant local-grant button for testing the delivery pipeline.
- **Play page**: a real game-client download artifact for `/download/client`.

## Verified end-to-end (local)
Shared register/login (both directions) · session cookie · hiscores + profiles from real
migrated saves · staff-gated news publish + markdown render · forum threads/replies/lock/
anti-spam · guides · store dev-checkout → entitlement queued exactly as the game reads it ·
Discord link-code minting · all images build · containerized web serves real data.

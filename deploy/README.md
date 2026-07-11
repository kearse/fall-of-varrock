# Production deploy runbook

How Fall of Varrock runs in production and how to operate it.

## Architecture

GitHub Actions builds three Docker images on every push to `main` and pushes them to
GitHub Container Registry, then SSHes to the VPS and restarts the stack:

- `ghcr.io/<owner>/kol-game` — game server (installDist + JRE 17, port 43594)
- `ghcr.io/<owner>/kol-web` — Next.js site + store (behind Caddy)
- `ghcr.io/<owner>/kol-bot` — Discord worker

On the VPS everything lives under **`/opt/kol/`**:

```
/opt/kol/
├── docker-compose.prod.yml   # shipped by CI each deploy
├── Caddyfile                 # shipped by CI each deploy
├── backup.sh                 # shipped by CI each deploy
├── .env                      # secrets — created BY HAND, never in git
├── .deployed-tag             # image tag currently live (rollback reference)
├── runtime/                  # server-only files, never in git
│   ├── cache/                # the custom rev-228 cache
│   ├── rsa/key.pem           # RSA private key (must match client modulus)
│   ├── xteas.json
│   ├── game.yml              # production config
│   ├── dev-settings.yml
│   └── saves/                # player saves
└── backups/                  # nightly mongo dumps + runtime tarballs
```

Mongo is **not** exposed publicly (no published port). Caddy terminates TLS for
`DOMAIN` (site) and `forum.DOMAIN` (NodeBB) with automatic Let's Encrypt certs.

## One-time setup

### 1. GitHub repository secrets (Settings → Secrets and variables → Actions)

| Secret | Value |
| --- | --- |
| `DEPLOY_HOST` | VPS IP address |
| `DEPLOY_USER` | `root` (or a deploy user in the `docker` group) |
| `DEPLOY_SSH_KEY` | Private key whose public half is in the VPS `authorized_keys` (generate a dedicated pair: `ssh-keygen -t ed25519 -f kol-deploy -C github-actions`) |

### 2. Bootstrap the VPS (Ubuntu 24.04)

```bash
scp deploy/setup-server.sh root@<ip>:/root/ && ssh root@<ip> "bash setup-server.sh"
```

Installs Docker, ufw (only 22/80/443/43594 open), fail2ban, the backup cron, and
creates the `/opt/kol` layout.

### 3. GHCR pull access on the VPS

The repo is private, so the images are private. Create a GitHub PAT (classic) with
only `read:packages`, then on the VPS: `docker login ghcr.io -u <github-user>` with
the PAT as password. Credentials persist; this is once per server.

### 4. Create `/opt/kol/.env`

```ini
DOMAIN=example.com            # apex domain; forum lives at forum.DOMAIN
GHCR_OWNER=kearse
GAME_WORLD_HOST=example.com   # what the client connects to (the DDoS-protected IP's DNS name)
AUTH_SECRET=<long random string>
DISCORD_BOT_TOKEN=...
DISCORD_CLIENT_ID=...
DISCORD_GUILD_ID=...
DISCORD_NEWS_CHANNEL_ID=...
DISCORD_FEED_STATUS_ID=...
DISCORD_FEED_ACHIEVEMENTS_ID=...
DISCORD_FEED_DROPS_ID=...
DISCORD_FEED_PK_ID=...
DISCORD_FEED_BOSS_ID=...
DISCORD_FEED_MODLOG_ID=...
DISCORD_WEBHOOK_URL=...
STRIPE_SECRET_KEY=sk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...
NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY=pk_live_...
PAYPAL_CLIENT_ID=...
PAYPAL_CLIENT_SECRET=...
PAYPAL_ENV=live
COINBASE_COMMERCE_API_KEY=...
COINBASE_COMMERCE_WEBHOOK_SECRET=...
```

`chmod 600 /opt/kol/.env`.

### 5. Upload runtime files

From the dev machine, into `/opt/kol/runtime/`: the `data/cache/` contents, `data/rsa/key.pem`,
`data/xteas.json`, plus a production `game.yml` and `dev-settings.yml` (start from the
`.example` files; set the Mongo save format / hostname bits the same as local).

### 6. DNS

Point `A` records for `@`, `www`, and `forum` at the VPS IP. Caddy fetches certs
automatically on first request once DNS resolves.

### 7. First deploy

Push to `main` (or run the **Deploy** workflow manually with an empty tag). Then:
NodeBB's first-boot setup wizard + the Discord bot `/setup` step (see
`discord-bot/README.md#hosting-checklist`).

## Everyday operations

- **Deploy**: merge/push to `main`. Done.
- **Rollback**: Actions → Deploy → *Run workflow* → set `image_tag` to a previous
  deploy's 7-char SHA (visible in old run logs, `/opt/kol/.deployed-tag` history, or
  the Packages page). No build happens; the old images are redeployed in ~1 minute.
- **Logs**: `ssh root@<ip> "docker compose -f /opt/kol/docker-compose.prod.yml logs -f game"`
- **Status**: `... ps` / `... top`
- **Backups**: nightly at 07:10 UTC to `/opt/kol/backups` (14 kept). To ship offsite,
  install rclone (`apt install rclone`), run `rclone config`, name the remote `offsite`
  (Backblaze B2 recommended, ~$1/mo) — backup.sh picks it up automatically.
- **Restore drill** (do this once before launch!): `mongorestore --archive --gzip <
  mongo-*.archive.gz` into a fresh mongo + untar runtime — confirm a test login works.

## Moderation & closed beta

Staff tooling (all persisted in the `moderation` Mongo collection, shared with saves):

- `::kick <name>`, `::ban <name> [reason]`, `::unban <name>`, `::bans`,
  `::mute <name> [hours]` (no hours = until unmuted), `::unmute <name>` — require the
  `mod` power. Bans are enforced at login (client shows the "account disabled" screen);
  mutes silence public chat and `::yell`. Staff and configured owners can't be targeted.
- `::whitelist <add|remove|list> [name]` — requires the `admin` power. With
  `whitelist-only: true` in the production `game.yml` (`/opt/kol/runtime/game.yml`),
  only whitelisted names and the `owners` list can log in — everyone else gets the
  "closed beta — invited players only" login screen. Use underscores for names with
  spaces. Whitelist changes apply immediately, no restart; flipping `whitelist-only`
  itself requires a game-container restart.
- Privileges come from `game.yml`: the `owners:` list is forced to the owner rank on
  every login; to appoint a moderator, grant their account a privilege whose powers
  include `mod` (see the `privileges:` block in `game.example.yml`).

## Known follow-ups

- The client bootstrap server (port 8228) still runs on the dev machine — it needs to
  move to the VPS (then open 8228 in ufw) and the client launcher must point at the
  production host before anyone outside the LAN can play.
- `NEXT_PUBLIC_*` values are baked at `next build` time by Next.js. The images are
  built without real values, so if the Stripe publishable key doesn't reach the
  browser, pass it as a build arg in `Alter/web/Dockerfile` + the workflow build step.
- Watchdog beyond `restart: unless-stopped`: the engine's known "silent game-loop
  death" leaves the process alive but the world frozen — a cron that probes a login
  handshake and restarts the game container is the eventual fix.

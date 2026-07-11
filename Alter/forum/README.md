# Fall of Varrock — Forum (NodeBB)

The community forum runs on **NodeBB** — a modern, real-time forum engine. It shares the
**same MongoDB server** as the game and website (in its own `nodebb` database) and uses
**single sign-on** so a player logged into the website is automatically logged into the forum.

- Runs as the `forum` service in the root `docker-compose.yml` (`ghcr.io/nodebb/nodebb:latest`).
- The website's **Forum** nav link and `/forum` redirect point at it via the `FORUM_URL` env
  (default `http://localhost:4567`).

---

## First-time setup (once)

```bash
# from the repo root
docker compose up -d mongo forum
```

1. Open **http://localhost:4567** — the NodeBB **setup wizard** appears on first boot.
2. **Database** step → choose **MongoDB**:
   - Host: `mongo`  · Port: `27017`  · Database: **`nodebb`**  (leave user/pass blank locally)
3. Create the **admin account** (this is the forum super-admin; separate from game accounts).
4. Finish — NodeBB builds and starts. You're in.

## Installer reappeared? Reconnecting to the existing forum

If `forum.<domain>` greets you with the **NodeBB Installer** again, the container lost its
`config.json` (it was recreated and the config wasn't in the persisted volume — fixed in
compose by mounting `nodebb-config:/opt/config`). **Your forum data is safe**: users, posts
and settings all live in Mongo's `nodebb` database, not in that file. Re-running the wizard
against the same database reconnects to everything.

Fill the wizard like this:

| Field | Value |
| --- | --- |
| Web Address (URL) | `https://forum.<domain>` — **https**, not the pre-filled http |
| Admin username/email/password | Anything — ignored when the database already has an admin |
| Database Type | MongoDB |
| Host | `mongo` |
| Port | `27017` |
| MongoDB username / password | **Blank** — our Mongo runs without auth. Clear the pre-filled `nodebb`/`nodebb` defaults or "Test Database" fails |
| Database name | `nodebb` (must match the original install to keep the data) |

Then pull the fixed compose file, recreate the service so the config persists from now on,
and check the SSO plugin:

```bash
docker compose up -d --force-recreate forum
```

Plugins are installed into the container filesystem (not a volume), so after a recreate the
**session-sharing** plugin may be gone even though its *settings* survive in the database.
If site logins stop carrying over, reinstall/activate it per the SSO section below —
the saved settings reappear as soon as the plugin is active.

## Wire up single sign-on (game/website accounts → forum)

The website issues a signed JWT session cookie named **`kol_session`** (HS256, signed with
`AUTH_SECRET`). NodeBB reads it and auto-creates/logs-in the matching forum user.

1. In NodeBB **Admin → Extend → Plugins**, search **`session-sharing`**, install
   **`nodebb-plugin-session-sharing`**, **Activate**, then **Rebuild & Restart**.
2. **Admin → Plugins → Session Sharing** and set:
   - **Cookie Name:** `kol_session`
   - **Secret:** the exact same value as the website's `AUTH_SECRET`
   - **Algorithm:** `HS256`
   - **Payload key → field mapping:**
     - `id` ← `sub`  (the loginUsername — stable unique id)
     - `username` ← `name`
     - `email` ← `email`
   - **Allow Banned Users / Guest handling:** defaults are fine
   - **Behaviour:** enable *“Create user if they don’t exist”* and *“Log in existing users”*
3. Save. Now visit the forum while logged into the website — you'll be signed in automatically
   as your game character.

### Production (subdomains)
Host the site at `kingdomoflumbridge.com` and the forum at `forum.kingdomoflumbridge.com`, then:
- Website env: `AUTH_COOKIE_DOMAIN=.kingdomoflumbridge.com` (so the cookie is shared with the
  subdomain) and `FORUM_URL=https://forum.kingdomoflumbridge.com`.
- NodeBB `config.json` `url` = the forum URL.
- Remove `AUTH_COOKIE_INSECURE` so the cookie is `Secure` over HTTPS.

Locally it "just works" because cookies ignore the port — the `kol_session` cookie set by
`localhost:3000` is also sent to `localhost:4567`.

## Theme it to match (optional)

NodeBB's default **Harmony** theme is already modern. To match the site's dark + gold look,
paste this into **Admin → Appearance → Custom CSS**:

```css
:root { --bs-link-color: #fbbf24; --bs-link-hover-color: #f8d579; }
body { background: #08070a; }
.btn-primary { background: linear-gradient(120deg,#fcd34d,#f59e0b); border:0; color:#2a1d06; }
.card, .panel { background: rgba(255,255,255,0.035); border-color: rgba(255,255,255,0.10); }
```

## Notes
- The old **custom** forum code still exists in `web/src/app/forum/*` but is unlinked — once
  `FORUM_URL` is set, the nav and `/forum` redirect go to NodeBB. You can delete it later.
- NodeBB has its own moderation, search, notifications, mobile UI and plugin ecosystem — that's
  the whole point of switching off the custom build.

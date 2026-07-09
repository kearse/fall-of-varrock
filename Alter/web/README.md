# Fall of Varrock — Web Platform

Community site for the Fall of Varrock RSPS: public site, **shared logins with the
game**, hiscores, forum/guides, donor & membership store, and Discord integration.

Built with Next.js (App Router, TypeScript) + Tailwind, talking to the **same MongoDB**
the game server saves to. See the approved plan at
`~/.claude/plans/refactored-cuddling-starlight.md`.

## How the shared login works

The game stores accounts as bcrypt-hashed documents. The web app reads/writes the same
`accounts` collection in Mongo:

- **`accounts`** — credentials + meta. Carries the game's `DisplayName` fields
  (`currentDisplayName` / `previousDisplayName` / `dateChanged`) **and** web fields
  (`passwordHash`, `email`, `roles`, `donorPoints`, `membership`).
- Passwords are bcrypt. Web-created hashes use cost 12; the game's jBCrypt verifies any
  cost, so a web-registered account logs into the game unchanged (and vice-versa).
- Username key is normalized: lowercased, spaces → underscores (`normalizeLogin`).

> Game-side counterpart (Phase 0): the game's Mongo connection is now env-driven
> (`MONGODB_URI` / `MONGODB_DB`), and `Mongo.loadAll()` is implemented. Still **TODO**:
> separating credentials from the heavy `details` save blob in `PlayerSaving.loadPlayer`
> so a web-first account bootstraps a fresh save on its first in-game login.

## Run locally

```bash
# 1. Start MongoDB (shared with the game)
docker run -d --name kol-mongo -p 27017:27017 -v kol-mongo-data:/data/db mongo:7

# 2. Configure env
cp .env.example .env.local
#   set AUTH_SECRET to a long random string
#   for plain-HTTP local prod builds also set AUTH_COOKIE_INSECURE=1

# 3. Install + run
npm install
npm run dev          # http://localhost:3000
```

## Project layout

```
src/
  app/
    layout.tsx, page.tsx          # shell + landing
    login/, register/, play/      # auth pages + connect instructions
    api/auth/{register,login,logout,me}/route.ts
  components/                     # SiteHeader, AuthForm, ServerStatus
  lib/
    db.ts            # Mongo client singleton
    collections.ts   # shared schema (accounts/details/news/forum/orders/…)
    accounts.ts      # bcrypt verify/create + RS username rules
    session.ts       # JWT-in-cookie session (jose)
```

## Status

- ✅ Phase 1 — site skeleton + shared auth (register/login/logout/session). Verified
  end-to-end against a real Mongo.
- 🚧 Phase 0 — game-server Mongo wiring (connection + loadAll done; login-core refactor next).
- ⬜ Phases 2-7 — hiscores, news+Discord, forum/guides, store (Stripe/PayPal/Coinbase),
  Discord bot, Docker orchestration.

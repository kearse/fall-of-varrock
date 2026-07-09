# Kingdom of Lumbridge (Kearse RSPS)

OSRS revision-228 private server built on a heavily customized [Alter](Alter/) fork, plus the
community website, forum integration, and Discord bot.

## Repo layout

| Path | What it is |
| --- | --- |
| `Alter/game-server` | Server engine (Kotlin, Gradle) |
| `Alter/game-plugins` | All gameplay content — minigames, bosses, skills, war system, PK bots, companions |
| `Alter/game-api` | Plugin API layer |
| `Alter/data/cfg` | Content config (item overrides, NPC spawns, drop tables, shops) |
| `Alter/web` | Next.js community site + store (shares MongoDB with the game server) |
| `Alter/discord-bot` | discord.js worker (account linking, store→role sync, event feeds) |
| `docs/` | Design docs (bosses, raids framework, duel rules grid, custom client, war design) |

## Not in the repo (ask the project owner)

- **`Alter/data/cache/`** — the custom-edited rev-228 game cache (~165MB, exceeds GitHub's
  file-size limit, and it's Jagex-derived data). You need it to boot the server; get the
  current zip from the owner and extract it to `Alter/data/cache/`.
- **`Alter/data/rsa/`, `Alter/data/xteas.json`** — generated/decryption keys. `gradlew
  game-server:install` generates a fresh RSA pair, but the *client* must use the matching
  modulus (see `Alter/MODULUS.txt`), so for shared dev use the owner's keys.
- **`.env` files** — copy the neighbouring `.env.example` files and fill in your own values
  (`Alter/.env`, `Alter/web/.env`, `Alter/discord-bot/.env`).
- **`Alter/game.yml`, `Alter/dev-settings.yml`** — copy from the `.example` versions.

## Prerequisites

- **JDK 17** (the Gradle toolchain requires 17 exactly — newer JDKs on PATH will fail to
  resolve; set `JAVA_HOME` to a JDK 17 install before any Gradle call)
- **Docker** (MongoDB: `docker run -d --name kol-mongo -p 27017:27017 mongo:7`)
- **Node 20+** (web + discord-bot)

## First run

```powershell
# 1. One-time install (RSA key, map decryption, config copies)
$env:JAVA_HOME='C:\path\to\jdk-17'
.\Alter\gradlew.bat -p .\Alter game-server:install

# 2. Start MongoDB
docker start kol-mongo

# 3. Run the game server (binds port 43594; healthy boot logs "Alter loaded up in Xms"
#    then a ticking GameService cycle line)
.\Alter\gradlew.bat -p .\Alter game-server:run --console=plain
```

Logging in requires **three** services: MongoDB (27017), the game server (43594), and the
custom-client bootstrap server (8228, part of the client setup — separate repo/machine).

## Gotchas that will bite you

- **Never run a Gradle build while the server is running** — the live JVM holds the plugin
  jar; hot-swapping it causes `NoClassDefFoundError` at runtime and `clean` failures.
- Phantom `Unresolved reference` errors with wrong line numbers = stale Kotlin incremental
  state → `gradlew game-plugins:clean game-plugins:compileKotlin`.
- `game-server:classes` does **not** compile `game-plugins`; verify content changes with
  `game-plugins:compileKotlin`.
- Plugin boot failures are silent-ish: grep the boot log for `Failed to load` — the green
  light is the "All N plugins loaded with no failures" line.
- Cache edits are made with the in-repo tools (`:game-server:itemDef`, `npcDef`, etc.) while
  the server is **down**; the client renders names/options from the cache, not YAML.

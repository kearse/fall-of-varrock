# Connecting to the production server

The game world runs at **play.fallofvarrock.com:43594** (OVHcloud VPS, Virginia).
Website: **https://fallofvarrock.com** · Forum: **https://forum.fallofvarrock.com**

## Players: just download the client

Native installers (bundled Java runtime, no setup) are on **https://fallofvarrock.com/play**:
- Windows: `/client/download/FallOfVarrock-Setup.exe`
- macOS: `/client/download/FallOfVarrock.dmg`
- Linux: `/client/download/FallOfVarrock-linux-x86_64.tar.gz`

Install, launch, create an account at the login screen, play. No RSProx, no config.
The installer is built by the **Client Installers** GitHub Action (jpackage) from the
pre-patched `fov-client.jar`; see `client-build/` for how that jar is made + patched.

> Never use your real Jagex/OSRS password on this (or any) private server — always a
> fresh one. The client cannot touch your real OSRS account.

## Developers: RSProx (for packet inspection / client dev)

The dev path still uses RSProx + our patched RuneLite client, pulled from the production
bootstrap. You do NOT need the game repo to play — only to develop.

## One-time setup

1. **Install RSProx** (ask the team lead for the version we use, rev 228).
2. Edit `~/.rsprox/proxy-targets.yaml` (Windows: `C:\Users\<you>\.rsprox\proxy-targets.yaml`)
   and add this target:

   ```yaml
   config:
     - id: 1
       name: Fall of Varrock (prod)
       jav_config_url: https://fallofvarrock.com/client/jav_config.ws
       varp_count: 15000
       revision: 228.2
       modulus: <ask the owner — must match the server's RSA key; see Alter/MODULUS.txt>
       runelite_bootstrap_url: https://fallofvarrock.com/client/bootstrap.json
   ```

3. Launch RSProx and pick the target. The launcher fetches our bootstrap, downloads
   our client jar from the site, and connects to the production world.

Notes:
- The `jav_config.ws` points the client at `play.fallofvarrock.com` — that DNS record
  is intentionally NOT behind Cloudflare's proxy (game TCP can't be proxied).
- The modulus is public-key material but we share it out-of-band anyway with the
  cache/keys bundle (see the root README's "Not in the repo" section).

## Local development instead

To run your own local server, follow the root [README](../README.md): clone the repo,
get the cache/keys bundle from the owner, `game-server:install`, run, and use a target
with `codebase=http://127.0.0.1/` (Blurite's `jav_local_228.ws` works for that).

## Updating the hosted client

When we ship a new client jar: rebuild it, update the artifact `hash`/`size` for
`client-*.jar` inside `bootstrap.json`, and upload both to `/opt/kol/client/` on the
VPS (they're served by Caddy at `https://fallofvarrock.com/client/`). The launcher
re-downloads whenever the hash in the bootstrap changes.

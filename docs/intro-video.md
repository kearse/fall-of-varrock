# First-login intro video

New accounts see the Fall of Varrock intro film docked over the game viewport on their
**first ever login** — unskippable, closes itself when it ends. The client window stays
usable (move, minimize, resize, alt-tab); only the game itself is covered. No account
ever sees it twice.

## How it works

```
first login (NEW_ACCOUNT_ATTR)                     ~/.fov-home/client/intro/intro.mp4
        │                                                       ▲  ETag-validated
        ▼                                                       │  download on client start
IntroVideoPlugin (server)  ──BROADCAST "FOV_INTRO:play"──►  lofintro (client fork)
  sets intro_video_seen                                      plays full-screen, modal,
  persistent attr                                            always-on-top, no skip
```

- **Server** — `Alter/game-plugins/.../content/mechanics/introvideo/IntroVideoPlugin.kt`.
  Fires only when `NEW_ACCOUNT_ATTR == true` (brand-new save) and the persistent
  `intro_video_seen` attribute isn't set; sets the attribute immediately so it is
  once-per-account forever. Existing accounts never qualify.
- **Client** — `client/runelite-client/.../plugins/lofintro/` (hidden plugin, always on,
  so players can't disable it to skip the intro). Catches the trigger line, rewrites it
  in the chatbox to a welcome message, and plays the video via JavaFX Media (H.264/AAC),
  shaded into `fov-client.jar` (Windows natives self-extract to `~/.openjfx/cache`).
  The overlay is an undecorated modeless dialog OWNED by the client frame, sized to and
  tracking the game canvas — it stacks above the client, minimizes with it, and never
  steals focus, so the desktop stays fully usable while the game viewport is covered.
  Failsafes: any media error, or duration+15 s, or a 10-minute hard cap closes it — a
  playback problem can never trap a player.
- **The announcement ticker** (`lofannouncements`) explicitly ignores `FOV_INTRO:*`
  lines so the trigger never renders as a headline.

## Swapping in a new video (the whole point)

The video is **not** baked into anything. The client fetches
`https://fallofvarrock.com/client/intro.mp4` (VPS path `/opt/kol/client/intro.mp4`,
served by Caddy) into a local cache, revalidating by ETag on every client start.

To replace the intro: overwrite `deploy/client-files/intro.mp4` in the repo and push —
the **Ship Intro Video** workflow uploads it to the VPS and verifies the hosted size.
Nothing else rebuilds or redeploys.

```powershell
Copy-Item "C:\path\to\new-intro.mp4" "deploy\client-files\intro.mp4"
git add deploy/client-files/intro.mp4; git commit -m "New intro video"; git push
```

(Manual fallback, same effect, no CI:
`scp -i $env:USERPROFILE\.ssh\kol_admin new-intro.mp4 ubuntu@15.204.245.41:/opt/kol/client/intro.mp4`)

Caddy issues a new ETag for the changed file; every installed client refetches it on
next launch automatically. Keep the file **H.264 video + AAC audio in .mp4** (that's
what JavaFX Media decodes; the Blender VSE pipeline's default mp4 export is fine).

## First-time rollout order

The server trigger must go live **last** — it permanently marks new accounts as
intro-seen, so the video and client must already be in place:

1. Ship the video (Ship Intro Video workflow, or the scp fallback).
2. Ship the client (Ship Client workflow runs automatically on client changes;
   `client-build\ship-client.ps1 -Rebuild` is the local fallback).
3. Deploy the game server (push to main → Deploy workflow) with the trigger plugin.

## Testing

- **Client only, no server:** type `::introtest` in the chatbox (any account, any time).
- **Server round-trip:** `::introtest` as admin sends the real BROADCAST trigger.
- **Local video override:** launch the client with `-Dfov.intro.url=<url>` to point the
  fetcher somewhere else (e.g. a local file server) without touching production.

## Gotchas

- `PatchClient.java` deliberately skips `javafx/**`, `com/sun/{javafx,glass,prism,media,
  scenario,pisces,marlin,openpisces}/**` and all `*.dll/*.so/*.dylib` when byte-patching:
  its needle scans (`127.0.0.1`, `10001` + hex run) stop at the FIRST match anywhere in
  the jar, so an accidental hit inside a JavaFX binary would corrupt it and leave the
  real client constant unpatched.
- Only **Windows** JavaFX natives are shaded (the player base target). On mac/linux the
  FX toolkit fails to start and `lofintro` skips the video gracefully — add
  `mac`/`linux` classifier deps in `runelite-client/pom.xml` if that ever matters.
- The bundled player runtime is Java 11 → stay on JavaFX **17.x** (18+ requires 17+).

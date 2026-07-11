# Forum restore (production NodeBB)

Rebuilds the Fall of Varrock forum from a stock NodeBB install on the prod VPS:
fixes the `config.json` url to https, seeds the full section/board structure +
guides + starter threads via the Write API (with a temporary master token minted
directly in Mongo), applies the crimson theme / unified top bar / sidebar widgets /
brand into the `nodebb` db with the **actual prod cids** resolved by name, installs
the section icon art, and restarts the forum.

First used 2026-07-11 after the prod forum turned out to be a never-configured
stock install (the setup wizard had only been completed that morning).

## Run

```
# from the repo root, on the workstation
scp -i ~/.ssh/kol_admin -r deploy/forum-restore ubuntu@15.204.245.41:/opt/kol/forum-restore
scp -i ~/.ssh/kol_admin Alter/web/public/img/forum/*.png ubuntu@15.204.245.41:/opt/kol/forum-restore/icons/
ssh -i ~/.ssh/kol_admin ubuntu@15.204.245.41 "cd /opt/kol/forum-restore && mkdir -p icons && sed -i 's/\r$//' *.sh *.js *.mjs && bash restore-forum.sh"
```

(Scp the icons before running; the script expects `icons/{official,general,war,media,support}.png`.)

Idempotent: seeding skips categories/topics that already exist by name/title, and
the config url fix is guarded so an already-https config is not rewritten.

## Hard-won gotchas baked into the script

- **Any write to `/opt/config/config.json` restarts NodeBB** - the docker
  entrypoint watches it, and even a no-op `sed -i` (rewrite without a match)
  triggers a restart. The script greps before writing and waits for 3 consecutive
  successful `/api/config` probes before seeding.
- **Token timestamps must be milliseconds.** `date +%s%3N` produced *nanoseconds*
  on the VPS; a 1.78e18 timestamp makes `new Date(ts).toISOString()` throw inside
  NodeBB's token verify, which is an uncaughtException that **kills the whole
  NodeBB process on every authenticated request**. The script uses
  `$(( $(date +%s) * 1000 ))`.
- Master token shape (NodeBB v4, mongo): hash `token:<uuid>` with
  `{uid: 0, description, timestamp}` plus zset entries in `tokens:createtime`
  (score = ms timestamp) and `tokens:uid` (score = uid). uid 0 = master token;
  requests pass `_uid: 1` in the body to act as the admin. The temp token is
  revoked in a `trap ... EXIT`.
- Prod cids differ from dev, so nothing hardcodes them: `restore-forum.sh`
  resolves section/board cids by name from Mongo and substitutes the
  `__CID_*__` / `__SITE_URL__` placeholders in the theme/topbar/widgets/brand
  scripts; `nodebb-style-prod.mjs` resolves 3rd-level sub-forums by walking
  `/api/category/<cid>` children.
- `seed-forum.mjs` / `seed-forum-extra.mjs` are the dev seeds from
  `Alter/web/scripts/` with the site-wide no-em-dash rule applied to content.

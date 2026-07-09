#!/usr/bin/env bash
# Nightly backup: Mongo dump + player saves + server-only runtime configs.
# Keeps the 14 most recent local copies; ships offsite if an rclone remote
# named "offsite" is configured (rclone config -> e.g. Backblaze B2).
set -euo pipefail

STAMP="$(date -u +%Y%m%d-%H%M)"
BK=/opt/kol/backups
COMPOSE="docker compose -f /opt/kol/docker-compose.prod.yml"

$COMPOSE exec -T mongo mongodump --archive --gzip > "$BK/mongo-$STAMP.archive.gz"
tar czf "$BK/runtime-$STAMP.tar.gz" -C /opt/kol/runtime saves game.yml dev-settings.yml rsa xteas.json

# Prune local copies beyond the newest 14 of each kind
ls -1t "$BK"/mongo-*.archive.gz 2>/dev/null | tail -n +15 | xargs -r rm --
ls -1t "$BK"/runtime-*.tar.gz  2>/dev/null | tail -n +15 | xargs -r rm --

# Offsite copy (no-op until `rclone config` has created an "offsite" remote)
if command -v rclone >/dev/null 2>&1 && rclone listremotes | grep -q '^offsite:'; then
  rclone copy "$BK" offsite:kol-backups --max-age 24h
fi

echo "$(date -u +%FT%TZ) backup ok: mongo-$STAMP + runtime-$STAMP"

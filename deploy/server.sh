#!/usr/bin/env bash
# /opt/kol/server.sh — day-2 operations helper (shipped by CI each deploy).
#
#   server.sh restart [service]    restart containers in place (no image pull)
#   server.sh redeploy [service]   pull the deployed tag and recreate containers
#   server.sh stop [service]       stop the whole stack (or one service)
#   server.sh start [service]      start the whole stack (or one service)
#   server.sh status               container states + currently deployed tag
#   server.sh logs [service]       last 200 lines (TAIL=500 server.sh logs game)
#   server.sh inspect-save <user>  READ-ONLY: dump a player's on-disk save files
#   server.sh reset-save <user>    move a corrupt save aside (reversible; never deletes)
#
# Services: game web discord-bot forum caddy mongo (omit = whole stack).
#
# Rebuilding images from source does NOT happen on this box — CI builds them.
# Push to main (or run the Deploy workflow) to rebuild; run the Server Ops
# workflow to do any of the above without SSH.
set -euo pipefail
cd /opt/kol

# Compose interpolates IMAGE_TAG; without it, up/pull would silently fall back
# to :latest instead of what the last deploy (or rollback) actually shipped.
IMAGE_TAG="$(cat .deployed-tag 2>/dev/null || echo latest)"
export IMAGE_TAG

compose() { docker compose -f docker-compose.prod.yml "$@"; }

cmd="${1:-help}"
shift || true

case "$cmd" in
  restart)
    # -t 90 covers the game's stop_grace_period; quick services ignore the slack
    compose restart -t 90 "$@"
    ;;
  redeploy)
    compose pull "$@"
    compose up -d --force-recreate --remove-orphans "$@"
    docker image prune -f >/dev/null
    ;;
  stop)
    compose stop -t 90 "$@"
    ;;
  start)
    compose up -d "$@"
    ;;
  status)
    echo "deployed tag: $IMAGE_TAG"
    compose ps
    ;;
  logs)
    compose logs --tail="${TAIL:-200}" "$@"
    ;;
  inspect-save)
    # READ-ONLY. Dump whatever the game has on disk for a player so a corrupt
    # save can be diagnosed without SSH. Player save blobs live on the host at
    # /opt/kol/runtime/saves/details/ (bind-mounted into the game container).
    # loginUsername is stored lowercase on disk; match case-insensitively.
    user="${1:-}"
    [ -n "$user" ] || { echo "usage: server.sh inspect-save <username>" >&2; exit 1; }
    dir=/opt/kol/runtime/saves/details
    lc="$(printf '%s' "$user" | tr '[:upper:]' '[:lower:]')"
    echo "== inspect-save: '$user' =="
    echo "saves dir: $dir"
    found=0
    for name in "$lc" "$lc.bak" "$lc.tmp"; do
      match="$(find "$dir" -maxdepth 1 -type f -iname "$name" 2>/dev/null | head -1)"
      [ -n "$match" ] || continue
      found=1
      size="$(stat -c%s "$match")"
      echo "--- $(basename "$match")  (${size} bytes) ---"
      if [ "$size" -eq 0 ]; then
        echo "(empty file — this is corruption: a valid save is never 0 bytes)"
      else
        head -c 4000 "$match"; echo
        if [ "$size" -gt 4000 ]; then echo "(...truncated to 4000 bytes for display...)"; fi
        if head -c 1 "$match" | grep -q '{'; then
          echo "[starts with '{']"
        else
          echo "[does NOT start with '{' — not a JSON document]"
        fi
      fi
    done
    if [ "$found" -eq 0 ]; then
      echo "no save files found for '$user' (looked for $lc, $lc.bak, $lc.tmp)"
    fi
    ;;
  reset-save)
    # Move a player's (corrupt) save + backup/temp siblings into a timestamped
    # corrupt-backups/ folder. REVERSIBLE — nothing is deleted. After this the
    # next login rebuilds a fresh character on the SAME account/password (login
    # credentials live in MongoDB, not in this file). No container restart needed.
    user="${1:-}"
    [ -n "$user" ] || { echo "usage: server.sh reset-save <username>" >&2; exit 1; }
    dir=/opt/kol/runtime/saves/details
    lc="$(printf '%s' "$user" | tr '[:upper:]' '[:lower:]')"
    ts="$(date -u +%Y%m%dT%H%M%SZ)"
    dest="$dir/corrupt-backups/$ts"
    moved=0
    for name in "$lc" "$lc.bak" "$lc.tmp"; do
      match="$(find "$dir" -maxdepth 1 -type f -iname "$name" 2>/dev/null | head -1)"
      [ -n "$match" ] || continue
      mkdir -p "$dest"
      mv "$match" "$dest/"
      echo "moved $(basename "$match") -> $dest/"
      moved=1
    done
    if [ "$moved" -eq 1 ]; then
      echo "reset-save: '$user' cleared. Files preserved under $dest (not deleted)."
      echo "Next login rebuilds a fresh character on the same account/password."
    else
      echo "reset-save: nothing to move for '$user'."
    fi
    ;;
  help)
    sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
    ;;
  *)
    echo "unknown command: $cmd" >&2
    sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 1
    ;;
esac

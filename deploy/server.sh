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
#   server.sh inspect-route [user] READ-ONLY: dump a recorded ::recroute march path
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
    #
    # The saves dir is owned by the game container's user (the image runs as
    # root), so the host deploy user can't write into it — mkdir/mv would fail
    # with "Permission denied". Do the move INSIDE the running game container,
    # where the process owns the files. /app/data/saves/details there is the
    # same bind mount as /opt/kol/runtime/saves/details on the host, so the move
    # persists. loginUsername is stored lowercase on disk.
    user="${1:-}"
    [ -n "$user" ] || { echo "usage: server.sh reset-save <username>" >&2; exit 1; }
    lc="$(printf '%s' "$user" | tr '[:upper:]' '[:lower:]')"
    ts="$(date -u +%Y%m%dT%H%M%SZ)"
    compose exec -T game sh -c '
      set -eu
      dir=/app/data/saves/details
      lc=$1; ts=$2
      dest=$dir/corrupt-backups/$ts
      moved=0
      for name in "$lc" "$lc.bak" "$lc.tmp"; do
        f="$dir/$name"
        [ -e "$f" ] || continue
        mkdir -p "$dest"
        mv "$f" "$dest/"
        echo "moved $name -> corrupt-backups/$ts/"
        moved=1
      done
      if [ "$moved" -eq 1 ]; then
        echo "reset-save: $lc cleared; files preserved under $dest (not deleted)."
        echo "Next login rebuilds a fresh character on the same account/password."
      else
        echo "reset-save: nothing to move for $lc."
      fi
    ' sh "$lc" "$ts"
    ;;
  inspect-route)
    # READ-ONLY. Dump a recorded march route (::recroute) so the walked
    # waypoints can be retrieved without SSH. The recorder writes
    # data/mapdump/route_<player>.txt relative to the game's working dir,
    # which is INSIDE the game container (/app/data/mapdump) — that path is
    # NOT a host bind mount (unlike saves), so read it through the running
    # container, the same way reset-save does. With no user, dumps every
    # recorded route. loginUsername case is preserved in the filename, so
    # match case-insensitively.
    #
    # NOTE: mapdump lives in the container's writable layer, so the file
    # survives a `restart` but a `redeploy`/recreate wipes it — grab it first.
    user="${1:-}"
    compose exec -T game sh -c '
      set -eu
      dir=/app/data/mapdump
      want=$1
      if [ ! -d "$dir" ]; then
        echo "no mapdump dir on this container ($dir) — nothing recorded here."
        exit 0
      fi
      n=0
      for f in "$dir"/route_*.txt; do
        [ -e "$f" ] || continue
        base=$(basename "$f")
        if [ -n "$want" ]; then
          lcf=$(printf "%s" "$base" | tr "[:upper:]" "[:lower:]")
          lcw=$(printf "route_%s.txt" "$want" | tr "[:upper:]" "[:lower:]")
          [ "$lcf" = "$lcw" ] || continue
        fi
        n=$((n + 1))
        size=$(wc -c < "$f" | tr -d " ")
        lines=$(wc -l < "$f" | tr -d " ")
        echo "===== $base  (${size} bytes, ${lines} lines) ====="
        cat "$f"
        echo "===== end $base ====="
      done
      if [ "$n" -eq 0 ]; then
        if [ -n "$want" ]; then
          echo "no recorded route for \"$want\" (looked for route_$want.txt, any case)."
        else
          echo "no recorded routes found in $dir."
        fi
        echo "files present:"; ls -la "$dir" 2>/dev/null || true
      fi
    ' sh "$user"
    ;;
  help)
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
    ;;
  *)
    echo "unknown command: $cmd" >&2
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 1
    ;;
esac

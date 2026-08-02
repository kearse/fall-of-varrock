#!/usr/bin/env bash
# /opt/kol/server.sh — day-2 operations helper (shipped by CI each deploy).
#
#   server.sh restart [service]    restart containers in place (no image pull)
#   server.sh redeploy [service]   pull the deployed tag and recreate containers
#   server.sh stop [service]       stop the whole stack (or one service)
#   server.sh start [service]      start the whole stack (or one service)
#   server.sh status               container states + currently deployed tag
#   server.sh logs [service]       last 200 lines (TAIL=500 server.sh logs game)
#   server.sh dump-ge-locs         READ-ONLY: list the original GE floor's loc ids
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
    # TAIL=200 misses anything older than ~20 min of game chatter; 3000 reaches back
    # past a boot that happened within the last few hours, so `logs game` can answer
    # "what did the server log at startup" without a disruptive restart.
    compose logs --tail="${TAIL:-3000}" "$@"
    ;;
  dump-ge-locs)
    # READ-ONLY diagnostic: list the locs on the ORIGINAL Grand Exchange floor so
    # the central-desk / booth object ids can be identified. Prefers the pristine
    # cache.preloc backup (the live cache has the GE gutted by the ruins edit);
    # falls back to the live cache. Runs the mapDump tool that ships in the game
    # image inside a throwaway container — the running game is not touched.
    SRC=/opt/kol/runtime/cache.preloc
    [ -d "$SRC" ] || SRC=/opt/kol/runtime/cache
    echo "== GE loc dump (source cache: $SRC) =="
    TMP=$(mktemp)
    compose run --rm -T --no-deps \
      -v "$SRC":/app/bin/data/cache:ro \
      --entrypoint bash game -c \
      'java -cp "/app/lib/*" org.alter.tools.mapdump.MapDumpToolKt region 12598 >/dev/null 2>&1 || true; cat data/mapdump/r12598_*.txt 2>/dev/null || echo NO_DUMP' \
      > "$TMP"
    head -4 "$TMP"
    echo "-- columns: x z level id name type rot sizeX sizeY --"
    echo "-- ALL level-1 locs around the central GE hub (x 3155-3175, z 3480-3500) --"
    echo "-- (the central desk ring + pillar live on bridge-flagged tiles at level 1) --"
    awk -F'\t' 'NF>=9 && $3==1 && $1>=3155 && $1<=3175 && $2>=3480 && $2<=3500' "$TMP"
    rm -f "$TMP"
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
    # READ-ONLY. Retrieve a recorded march route (::recroute) without SSH, from
    # every place it can be:
    #
    #   (1) route file(s) on disk, checked in TWO dirs (the game's WORKDIR is
    #       /app/bin, so paths resolve from there):
    #         /app/data/saves/mapdump   the persistent, host-bind-mounted location
    #                                   the recorder writes to (../data/saves/...),
    #                                   = /opt/kol/runtime/saves/mapdump; survives
    #                                   redeploys.
    #         /app/bin/data/mapdump     the LEGACY cwd-relative location used before
    #                                   the write path was fixed; in the container's
    #                                   throwaway layer, so a restart keeps it but a
    #                                   redeploy wipes it.
    #   (2) the game log. finish() logs every down-sampled waypoint as
    #       "RECROUTE-WP Tile(x, z, 0)," (and the live trail as "RECLIVE <user>
    #       x,z,h") BEFORE it writes the file — so the route is in the log too, as
    #       long as this is the same container that did the walk (a redeploy
    #       rotates the log away with the old container).
    #
    # With no user, dumps every route file / all RECROUTE lines. loginUsername
    # case is preserved, so file matching is case-insensitive.
    user="${1:-}"

    echo "== (1) route file(s) on disk (persistent saves mount + legacy layer) =="
    compose exec -T game sh -c '
      set -eu
      want=$1
      found=0
      for dir in /app/data/saves/mapdump /app/bin/data/mapdump; do
        [ -d "$dir" ] || continue
        for f in "$dir"/route_*.txt; do
          [ -e "$f" ] || continue
          base=$(basename "$f")
          if [ -n "$want" ]; then
            lcf=$(printf "%s" "$base" | tr "[:upper:]" "[:lower:]")
            lcw=$(printf "route_%s.txt" "$want" | tr "[:upper:]" "[:lower:]")
            [ "$lcf" = "$lcw" ] || continue
          fi
          found=1
          size=$(wc -c < "$f" | tr -d " ")
          lines=$(wc -l < "$f" | tr -d " ")
          echo "===== $dir/$base  (${size} bytes, ${lines} lines) ====="
          cat "$f"
          echo "===== end $base ====="
        done
      done
      [ "$found" -eq 0 ] && echo "no route file on disk for \"${want:-<any>}\" (checked saves/mapdump + legacy bin/data/mapdump)."
    ' sh "$user" || echo "(could not read the container route dirs)"

    echo
    echo "== (2) recorded waypoints from the game log (RECROUTE-WP = paste-ready) =="
    echo "-- if several recordings appear, the LAST block (newest timestamps) is the latest walk --"
    logs="$(compose logs --no-log-prefix --tail 400000 game 2>/dev/null || true)"
    hits="$(printf '%s\n' "$logs" | grep -E 'RECROUTE( |-WP)' || true)"
    if [ -n "$user" ]; then
      trail="$(printf '%s\n' "$logs" | grep -E "RECLIVE $user " || true)"
    else
      trail="$(printf '%s\n' "$logs" | grep -E 'RECLIVE ' || true)"
    fi
    if [ -n "$hits" ]; then
      printf '%s\n' "$hits"
    else
      echo "no RECROUTE-WP lines in the retained container log."
      echo "=> this container has no record of the walk: it was recreated since you"
      echo "   recorded (log rotated away with it), or the walk was on a different"
      echo "   server. Re-run ::recroute start/stop on THIS world, then inspect-route."
    fi
    if [ -n "$trail" ]; then
      echo
      echo "-- raw walked trail (RECLIVE, one tile per line, highest fidelity) --"
      printf '%s\n' "$trail"
    fi
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

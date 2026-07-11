#!/usr/bin/env bash
# /opt/kol/server.sh — day-2 operations helper (shipped by CI each deploy).
#
#   server.sh restart [service]    restart containers in place (no image pull)
#   server.sh redeploy [service]   pull the deployed tag and recreate containers
#   server.sh stop [service]       stop the whole stack (or one service)
#   server.sh start [service]      start the whole stack (or one service)
#   server.sh status               container states + currently deployed tag
#   server.sh logs [service]       last 200 lines (TAIL=500 server.sh logs game)
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
  help)
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    ;;
  *)
    echo "unknown command: $cmd" >&2
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 1
    ;;
esac

#!/usr/bin/env bash
# /opt/kol/prune.sh — reclaim Docker disk on the prod box (shipped by CI each
# deploy). Removes stopped containers, unused images (including the old
# SHA-tagged deploy images that otherwise pile up between deploys), and build
# cache. Anything removed is re-pullable from GHCR.
#
# NEVER touches named volumes, so Mongo data, player saves, NodeBB, and Caddy
# certs are safe — there is deliberately no `docker volume prune` here.
#
# Runs from cron every few days (see deploy/setup-server.sh) so images can't
# accumulate between manual deploys; the deploy itself also prunes before it
# pulls, so a full box self-heals.
set -euo pipefail

echo "$(date -u +%FT%TZ) prune: start ($(df -h / | awk 'NR==2{print $4" free"}'))"

docker container prune -f  >/dev/null 2>&1 || true
docker image prune -af     >/dev/null 2>&1 || true
docker builder prune -af   >/dev/null 2>&1 || true

echo "$(date -u +%FT%TZ) prune: done  ($(df -h / | awk 'NR==2{print $4" free"}'))"

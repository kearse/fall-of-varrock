#!/usr/bin/env bash
# Set the toplist vote-postback secrets on the production box.
#
# Why this exists: with these secrets empty in /opt/kol/.env the web app answers
# every toplist callback 403 "Invalid secret", so no vote is ever recorded and
# ::claimvote / the vote window's Claim button never have anything to pay out —
# while the vote links themselves keep working, which makes it look like a
# "claiming" bug. (Found 2026-09-01: 0 votes, 0 vote_points entitlements.)
#
# Usage (run ON the VPS as the deploy user):
#   bash vote-secrets.sh <RSPS_LIST_SECRET>          # RSPS-List API secret from its dashboard
#   bash vote-secrets.sh                             # only (re)generate what's missing
#
# From the dev machine:
#   scp -i ~/.ssh/kol_admin deploy/vote-secrets.sh ubuntu@15.204.245.41:/tmp/
#   ssh -i ~/.ssh/kol_admin ubuntu@15.204.245.41 'bash /tmp/vote-secrets.sh <RSPS_LIST_SECRET>'
#
# What it does:
#   1. backs up .env (nothing is ever deleted),
#   2. sets RSPS_LIST_SECRET when given (rsps-list.com sends its dashboard API
#      secret in every callback — it must match exactly),
#   3. generates RULOCUS_SECRET if empty and PRINTS it — paste that value into the
#      RuLocus server settings "callback secret" field (they send it back as
#      `secret`); the callback URL there is https://fallofvarrock.com/api/vote/postback/rulocus
#   4. adds empty placeholder lines for TopG / Top100Arena / Moparscape,
#   5. recreates the web container so the new environment is live
#      (`server.sh start web` = compose up -d; a plain restart keeps the old env).
set -eu
cd /opt/kol
rsps_list="${1:-}"

ts=$(date -u +%Y%m%dT%H%M%SZ)
cp -p .env ".env.bak-$ts"
echo "backup written: /opt/kol/.env.bak-$ts"

set_var() { # set_var NAME VALUE — replace the line or append it
  if grep -qE "^$1=" .env; then
    sed -i "s|^$1=.*|$1=$2|" .env
  else
    printf '%s=%s\n' "$1" "$2" >> .env
  fi
}

grep -qE '_SECRET=' .env || printf '\n# Toplist vote postback secrets (see deploy/README.md)\n' >> .env

if [ -n "$rsps_list" ]; then
  set_var RSPS_LIST_SECRET "$rsps_list"
  echo "RSPS_LIST_SECRET set."
elif ! grep -qE '^RSPS_LIST_SECRET=.' .env; then
  set_var RSPS_LIST_SECRET ""
  echo "RSPS_LIST_SECRET still EMPTY — rerun with the API secret from the rsps-list.com dashboard."
fi

if grep -qE '^RULOCUS_SECRET=.' .env; then
  echo "RULOCUS_SECRET already set (unchanged): $(grep -E '^RULOCUS_SECRET=' .env | cut -d= -f2-)"
else
  secret=$(openssl rand -hex 24)
  set_var RULOCUS_SECRET "$secret"
  echo "RULOCUS_SECRET generated — paste this into the RuLocus server settings 'callback secret': $secret"
fi

for v in TOPG_SECRET TOP100ARENA_SECRET MOPARSCAPE_SECRET; do
  grep -qE "^$v=" .env || printf '%s=\n' "$v" >> .env
done
chmod 600 .env

echo "== vote secret lines in .env (values hidden) =="
grep -E '^(RSPS_LIST|RULOCUS|TOPG|TOP100ARENA|MOPARSCAPE)_SECRET=' .env | sed -E 's/=(.+)$/=<set>/; s/=$/=<EMPTY>/'

echo "== recreating the web container with the new environment =="
./server.sh start web
echo "done. Watch callbacks with: TAIL=200 /opt/kol/server.sh logs web | grep '\[vote\]'"

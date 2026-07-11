#!/usr/bin/env bash
# Restore the Fall of Varrock forum on prod: fix config url, seed the category
# structure + guides via the Write API (temp master token), apply theme/topbar/
# widgets/brand into the nodebb Mongo db, install section icons, restart.
set -euo pipefail
cd "$(dirname "$0")"

SITE="https://fallofvarrock.com"
MONGO_URI="mongodb://127.0.0.1:27017/nodebb"
FORUM=kol-forum-1
MONGOC=kol-mongo-1

msh() { docker exec -i "$MONGOC" mongosh "$MONGO_URI" --quiet "$@"; }

echo "== 1/8 Fix forum url to https in config.json =="
# Only write config.json if it needs the fix: ANY write to it makes the NodeBB
# entrypoint restart the app (even a no-op sed -i).
if docker exec "$FORUM" grep -q '"url": "http://forum\.' /opt/config/config.json; then
  docker exec "$FORUM" sed -i 's#"url": "http://forum\.#"url": "https://forum.#' /opt/config/config.json
  echo "url fixed to https (NodeBB will restart itself)"
else
  echo "url already https, leaving config.json untouched"
fi
docker exec "$FORUM" grep -m1 url /opt/config/config.json

echo "== 2/8 Verify admin uid 1 =="
msh --eval 'var u=db.objects.findOne({_key:"user:1"},{username:1}); if(!u){print("NO-ADMIN");quit(1)} print("admin uid1: "+u.username)'

echo "== 3/8 Mint temporary master API token =="
TOKEN=$(cat /proc/sys/kernel/random/uuid)
# Milliseconds since epoch. date +%s%3N is NOT portable here (produced nanoseconds,
# which overflows JS Date and crashes NodeBB's token verify with an uncaughtException).
NOW=$(($(date +%s) * 1000))
msh --eval "db.objects.insertOne({_key:'token:$TOKEN',uid:0,description:'forum-restore temp',timestamp:$NOW}); db.objects.insertOne({_key:'tokens:createtime',value:'$TOKEN',score:$NOW}); db.objects.insertOne({_key:'tokens:uid',value:'$TOKEN',score:0}); print('token minted')"

cleanup() {
  msh --eval "db.objects.deleteMany({_key:'token:$TOKEN'}); db.objects.deleteMany({_key:{\$in:['tokens:createtime','tokens:uid']},value:'$TOKEN'}); print('temp token revoked')" || true
}
trap cleanup EXIT

echo "== 4/8 Seed category structure + guides (inside $FORUM) =="
# Editing config.json makes the NodeBB entrypoint restart the app; wait until it
# serves 3 probes in a row (a single probe can hit the dying old process).
ok=0
for i in $(seq 1 90); do
  if docker exec "$FORUM" node -e "fetch('http://127.0.0.1:4567/api/config').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"; then
    ok=$((ok+1))
    [ "$ok" -ge 3 ] && { echo "NodeBB is up (3 consecutive probes)"; break; }
  else
    ok=0
  fi
  [ "$i" = 90 ] && { echo "FATAL: NodeBB never came up"; exit 1; }
  sleep 2
done
for f in seed-forum.mjs seed-forum-extra.mjs nodebb-style-prod.mjs; do
  docker cp "$f" "$FORUM:/tmp/$f"
done
docker exec -e NODEBB_URL=http://127.0.0.1:4567 -e NODEBB_TOKEN="$TOKEN" -e SITE_URL="$SITE" "$FORUM" node /tmp/seed-forum.mjs
docker exec -e NODEBB_URL=http://127.0.0.1:4567 -e NODEBB_TOKEN="$TOKEN" -e SITE_URL="$SITE" "$FORUM" node /tmp/seed-forum-extra.mjs
docker exec -e NODEBB_URL=http://127.0.0.1:4567 -e NODEBB_TOKEN="$TOKEN" -e SITE_URL="$SITE" "$FORUM" node /tmp/nodebb-style-prod.mjs

echo "== 5/8 Resolve category cids =="
cid_of() { msh --eval "var c=db.objects.findOne({_key:/^category:[0-9]+\$/,name:'$1'},{cid:1}); print(c?c.cid:'')"; }
CID_OFFICIAL=$(cid_of "Fall of Varrock")
CID_GENERAL=$(cid_of "General")
CID_WAR=$(cid_of "The War")
CID_MEDIA=$(cid_of "Media")
CID_SUPPORT=$(cid_of "Support")
CID_ANN=$(cid_of "Announcements")
CID_GUIDES=$(cid_of "Guides")
echo "official=$CID_OFFICIAL general=$CID_GENERAL war=$CID_WAR media=$CID_MEDIA support=$CID_SUPPORT ann=$CID_ANN guides=$CID_GUIDES"
for v in "$CID_OFFICIAL" "$CID_GENERAL" "$CID_WAR" "$CID_MEDIA" "$CID_SUPPORT" "$CID_ANN" "$CID_GUIDES"; do
  case "$v" in (''|*[!0-9]*) echo "FATAL: unresolved cid, aborting before theme apply"; exit 1;; esac
done

echo "== 6/8 Apply theme / topbar / widgets / brand =="
sed -e "s/__CID_OFFICIAL__/$CID_OFFICIAL/g" -e "s/__CID_GENERAL__/$CID_GENERAL/g" \
    -e "s/__CID_WAR__/$CID_WAR/g" -e "s/__CID_MEDIA__/$CID_MEDIA/g" \
    -e "s/__CID_SUPPORT__/$CID_SUPPORT/g" nodebb-theme-prod.js | msh
sed -e "s#__SITE_URL__#$SITE#g" -e "s/__CID_GUIDES__/$CID_GUIDES/g" nodebb-topbar-prod.js | msh
sed -e "s#__SITE_URL__#$SITE#g" -e "s/__CID_GUIDES__/$CID_GUIDES/g" -e "s/__CID_ANN__/$CID_ANN/g" nodebb-widgets-prod.js | msh
sed -e "s#__SITE_URL__#$SITE#g" nodebb-brand-prod.js | msh

echo "== 7/8 Install forum section icons (same-origin uploads volume) =="
docker exec "$FORUM" mkdir -p /usr/src/app/public/uploads/fov
for i in official general war media support; do
  docker cp "icons/$i.png" "$FORUM:/usr/src/app/public/uploads/fov/$i.png"
done
docker exec "$FORUM" ls /usr/src/app/public/uploads/fov/

echo "== 8/8 Restart forum =="
docker restart "$FORUM"
echo "ALL DONE"

# Apply the NodeBB theme / top-bar / widgets to the kol-forum container, injecting
# the Fall of Varrock web app base URL (which hosts the forum section icons and the
# top-bar nav links) so nothing is hardcoded to localhost in production.
#
# SITE URL precedence:
#   1. -SiteUrl argument
#   2. $env:SITE_URL
#   3. NEXT_PUBLIC_SITE_URL in ../.env.local (then ../.env)
#   4. http://localhost:3000
#
# Usage:
#   ./scripts/apply-nodebb.ps1                         # dev (localhost)
#   ./scripts/apply-nodebb.ps1 -SiteUrl https://fallofvarrock.com
#   $env:SITE_URL="https://fallofvarrock.com"; ./scripts/apply-nodebb.ps1
param([string]$SiteUrl)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$webRoot = Split-Path -Parent $here

if (-not $SiteUrl) { $SiteUrl = $env:SITE_URL }
if (-not $SiteUrl) {
  foreach ($envFile in @("$webRoot\.env.local", "$webRoot\.env")) {
    if (Test-Path $envFile) {
      $m = Select-String -Path $envFile -Pattern '^\s*NEXT_PUBLIC_SITE_URL\s*=\s*(.+?)\s*$' | Select-Object -First 1
      if ($m) { $SiteUrl = $m.Matches[0].Groups[1].Value.Trim().Trim('"'); break }
    }
  }
}
if (-not $SiteUrl) { $SiteUrl = "http://localhost:3000" }
$SiteUrl = $SiteUrl.TrimEnd('/')
Write-Host "Applying NodeBB config with SITE_URL = $SiteUrl"

# Host the forum section icons SAME-ORIGIN on NodeBB (its /public/uploads is a persistent
# named volume, so this survives restarts/recreates). Served at /assets/uploads/fov/*.png.
$forumIcons = "$webRoot\public\img\forum"
docker exec kol-forum sh -c "mkdir -p /usr/src/app/public/uploads/fov" | Out-Null
foreach ($icon in @("official", "general", "war", "media", "support")) {
  if (Test-Path "$forumIcons\$icon.png") {
    docker cp "$forumIcons\$icon.png" "kol-forum:/usr/src/app/public/uploads/fov/$icon.png" | Out-Null
  }
}
Write-Host "Forum section icons hosted at /assets/uploads/fov/."

$mongo = "mongodb://localhost:27017/nodebb"
foreach ($script in @("nodebb-theme.js", "nodebb-topbar.js", "nodebb-widgets.js")) {
  $content = (Get-Content "$here\$script" -Raw).Replace("__SITE_URL__", $SiteUrl)
  Write-Host "== $script =="
  $content | docker exec -i kol-mongo mongosh $mongo --quiet
}
Write-Host "Restarting kol-forum..."
docker restart kol-forum | Out-Null
Write-Host "Done. (Category-image / brand scripts like nodebb-style.mjs still use the NodeBB Write API separately.)"

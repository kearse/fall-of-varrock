# Fall of Varrock — ship a client update (players auto-update on next launch).
#
# PREFERRED: the "Ship Client" GitHub Actions workflow (.github/workflows/
# ship-client.yml) does all of this from any machine — it also runs
# automatically when client code changes on main. This script is the local
# Windows fallback.
#
# After changing the client (fork source, plugins, login screen, etc.):
#   .\ship-client.ps1 -Rebuild      # rebuild the fork first, then patch+host
#   .\ship-client.ps1               # just re-patch the current shaded jar + host
#
# Steps: (optional Maven rebuild) -> PatchClient -> compute sha256 -> scp BOTH
# fov-client.jar + fov-client.jar.sha256 to the VPS. The self-updating launcher in
# every installed client sees the new sha256 next launch and pulls the new jar.
# The native installers do NOT need rebuilding for a client update — only when the
# launcher stub, icon, or bundled JRE changes.
param([switch]$Rebuild)
$ErrorActionPreference = 'Stop'

$JDK17   = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$MVN     = 'C:\Users\zakea\rsps-client\apache-maven-3.9.16\bin\mvn.cmd'
$FORK    = 'C:\Program Files (x86)\Kearse RSPS\client'  # vendored into the monorepo
$SHADED  = "$FORK\runelite-client\target\client-1.10.51-shaded.jar"
$CB      = 'C:\Program Files (x86)\Kearse RSPS\client-build'
$OUT     = 'C:\Users\zakea\rsps-client\standalone-test\fov-client.jar'
$KEY     = "$env:USERPROFILE\.ssh\kol_admin"
$VPS     = 'ubuntu@15.204.245.41'
$MODULUS = (Get-Content 'C:\Program Files (x86)\Kearse RSPS\Alter\MODULUS.txt' |
            Where-Object { $_ -match '^[0-9a-f]{200,}$' }) -join ''
$env:JAVA_HOME = $JDK17

if ($Rebuild) {
  Write-Host "[1/4] Rebuilding fork (Maven, runelite-client)..."
  & $MVN -f "$FORK\pom.xml" install -DskipTests -pl runelite-client -am -q
  if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
}

Write-Host "[2/4] Patching (modulus + host + title + config dir)..."
& "$JDK17\bin\javac.exe" -d $CB "$CB\PatchClient.java"
& "$JDK17\bin\java.exe" -cp $CB PatchClient shaded $SHADED $OUT $MODULUS
if ($LASTEXITCODE -ne 0) { throw "PatchClient failed" }

Write-Host "[3/4] Computing sha256..."
$hash = (Get-FileHash $OUT -Algorithm SHA256).Hash.ToLower()
$hash | Out-File "$env:TEMP\fov-client.jar.sha256" -NoNewline -Encoding ascii

Write-Host "[4/4] Uploading jar + sha256 to the VPS..."
& scp -i $KEY $OUT "${VPS}:/opt/kol/client/fov-client.jar"
& scp -i $KEY "$env:TEMP\fov-client.jar.sha256" "${VPS}:/opt/kol/client/fov-client.jar.sha256"

Write-Host ""
Write-Host "Shipped. sha256 = $hash"
Write-Host "Players auto-update on next client launch. No reinstall needed."

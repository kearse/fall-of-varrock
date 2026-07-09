# Fall of Varrock — build the standalone (no-RSProx) client jar.
#
# Pipeline:
#   1. (optional) rebuild our RuneLite fork's shaded jar via Maven
#   2. patch it with PatchClient (bake our RSA modulus + blank host checks)
#   3. stage the patched jar + gamepack + jav_config for hosting
#
# The patched jar + gamepack + jav_config_standalone.ws + world_list.ws get
# hosted at https://fallofvarrock.com/client/ ; the branded launcher (deploy step)
# downloads the patched jar and runs it against production. PROVEN 2026-07-09:
# this jar logs into the live server with no proxy.
param([switch]$Rebuild)
$ErrorActionPreference = 'Stop'

$JDK17   = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$MVN     = 'C:\Users\zakea\rsps-client\apache-maven-3.9.16\bin\mvn.cmd'
$FORK    = 'C:\Program Files (x86)\Kearse RSPS\client'  # vendored into the monorepo
$SHADED  = "$FORK\runelite-client\target\client-1.10.51-shaded.jar"
$CB      = 'C:\Program Files (x86)\Kearse RSPS\client-build'
$STAGE   = Join-Path $env:TEMP 'fov-client'
$MODULUS = (Get-Content 'C:\Program Files (x86)\Kearse RSPS\Alter\MODULUS.txt' |
            Where-Object { $_ -match '^[0-9a-f]{200,}$' }) -join ''

New-Item -ItemType Directory -Force $STAGE | Out-Null
$env:JAVA_HOME = $JDK17

if ($Rebuild) {
  Write-Host "[1/3] Rebuilding shaded client jar (Maven, JDK 17)..."
  & $MVN -f "$FORK\pom.xml" install -DskipTests -pl runelite-client -am
}

Write-Host "[2/3] Compiling + running PatchClient (modulus + host checks)..."
& "$JDK17\bin\javac.exe" -d $CB "$CB\PatchClient.java"
& "$JDK17\bin\java.exe" -cp $CB PatchClient shaded $SHADED (Join-Path $STAGE 'fov-client.jar') $MODULUS

Write-Host "[3/3] Staged patched jar at $STAGE\fov-client.jar"
Write-Host "Modulus used: $($MODULUS.Substring(0,24))…  (len $($MODULUS.Length))"
Write-Host "Next: host it + gamepack + jav_config_standalone.ws on the VPS, point the launcher at it."

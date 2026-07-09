# apply-tower-update.ps1 — one-shot: stop the game server, apply the Void Knight cache edit,
# and relaunch. Run this when you're LOGGED OUT (saves are logout-only!).
#
#   powershell -ExecutionPolicy Bypass -File "C:\Program Files (x86)\Kearse RSPS\Alter\apply-tower-update.ps1"

$ErrorActionPreference = 'Stop'
$alter = 'C:\Program Files (x86)\Kearse RSPS\Alter'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'

Write-Host '[1/4] Stopping game server (surgical: spares the Gradle daemon)...'
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'org\.alter\.game\.Launcher|game-server:run' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

Write-Host '[2/4] Waiting for port 43594 to free...'
$tries = 0
while ($tries -lt 20) {
    Start-Sleep -Seconds 2
    if (-not (Get-NetTCPConnection -LocalPort 43594 -State Listen -ErrorAction SilentlyContinue)) { break }
    $tries++
}

Write-Host '[3/4] Applying Void Knight cache edit (Talk-to / Solo game / Multi game)...'
& (Join-Path $alter 'gradlew.bat') -p $alter :game-server:npcDef "-PnpcArgs=wizardknight" --console=plain -q

Write-Host '[4/4] Relaunching server (detached)...'
Start-Process -FilePath (Join-Path $alter 'gradlew.bat') `
    -ArgumentList '-p', "`"$alter`"", 'game-server:run', '--console=plain' `
    -RedirectStandardOutput (Join-Path $alter 'server-log.txt') `
    -RedirectStandardError (Join-Path $alter 'server-err.txt') `
    -WindowStyle Hidden

Write-Host 'Done. Watch server-log.txt for "Alter loaded up" + "plugins loaded". Fully close & relaunch RSProx so the client refetches the edited cache.'

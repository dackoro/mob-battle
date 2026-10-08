# Builds Mob Battle against the pre-release server and installs the jar.
#
#   .\deploy.ps1          build, then wait until Hytale closes and install
#   .\deploy.ps1 -NoWait  build and install only if Hytale is closed
#   .\deploy.ps1 -Force   install even with Hytale open: ONLY after "/plugin unload Dackoro:Mob Battle"
#                         in game (experimental hot reload; then "/plugin load Dackoro:Mob Battle")
param(
    [switch]$NoWait,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$serverJar = "$env:APPDATA\Hytale\install\pre-release\package\game\latest\Server\HytaleServer.jar"
$target    = "$env:APPDATA\Hytale\data\pre-release\Mods\MobBattle.jar"

Write-Host "Building..." -ForegroundColor Cyan
& .\gradlew.bat build --console=plain -q "-PhytaleServerJar=$serverJar"
if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED" -ForegroundColor Red; exit 1 }
$jar = Get-ChildItem build\libs\MobBattle-*.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1

# The jar is only held open while a world is loaded; in the main menu (or with Hytale closed) it's free.
function Test-Locked {
    if (-not (Test-Path $target)) { return $false }
    try { $f = [IO.File]::Open($target, 'Open', 'ReadWrite', 'None'); $f.Close(); return $false }
    catch { return $true }
}

if ((Test-Locked) -and -not $Force) {
    if ($NoWait) { Write-Host "A world is using the jar: built only ($($jar.Name)). Exit to the main menu and run again." -ForegroundColor Yellow; exit 0 }
    Write-Host "Built. Exit the world to the main menu to install (Ctrl+C to cancel)..." -ForegroundColor Yellow
    while (Test-Locked) { Start-Sleep -Seconds 1 }
}

Copy-Item $jar.FullName $target -Force
Write-Host "Installed $($jar.Name) -> $target ($(Get-Date -Format HH:mm:ss))" -ForegroundColor Green

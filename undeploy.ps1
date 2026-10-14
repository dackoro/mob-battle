# Removes the locally built Mob Battle from a Hytale Mods folder (Windows, PowerShell): the jar deploy.ps1 installs
# (MobBattle.jar) and the dev asset pack (MobBattle.AssetsDev), so only a copy from the mod browser
# (MobBattle-<version>.jar) is left. Two copies of Dackoro:Mob Battle stop every world with
# "Tried to load duplicate plugin" (2026-10-13). Worlds and settings are untouched: they live in the save or server folder.
#
#   .\undeploy.ps1          wait until no world uses the jar (main menu or Hytale closed), then remove
#   .\undeploy.ps1 -NoWait  remove only if the jar is free right now
#
# Same Mods folder as deploy.ps1: -ModsDir, deploy.local.json, or -Patchline release|pre-release (default release).
# Put it back with .\deploy.ps1 (or .\deploy.ps1 -Dev), after uninstalling the mod browser copy.
param(
    [switch]$NoWait,
    [ValidateSet('release', 'pre-release')][string]$Patchline,
    [string]$ModsDir
)
$ErrorActionPreference = 'Stop'

$local = Join-Path $PSScriptRoot 'deploy.local.json'
$cfg = if (Test-Path $local) { Get-Content $local -Raw | ConvertFrom-Json } else { $null }
if (-not $Patchline) { $Patchline = if ($cfg -and $cfg.patchline) { $cfg.patchline } else { 'release' } }
$defaultMods = if ($Patchline -eq 'release') { "$env:APPDATA\Hytale\UserData\Mods" } else { "$env:APPDATA\Hytale\data\pre-release\Mods" }
$mods    = if ($ModsDir) { $ModsDir } elseif ($cfg -and $cfg.modsDir) { $cfg.modsDir } else { $defaultMods }
if (-not (Test-Path $mods)) { Write-Host "Mods folder not found: $mods (use -ModsDir or deploy.local.json)" -ForegroundColor Red; exit 1 }
$target  = "$mods\MobBattle.jar"
$devPack = "$mods\MobBattle.AssetsDev"
Write-Host "Patchline $Patchline -> $mods" -ForegroundColor DarkGray

# The jar is only held open while a world is loaded (game or server); in the main menu it's free.
function Test-Locked {
    if (-not (Test-Path $target)) { return $false }
    try { $f = [IO.File]::Open($target, 'Open', 'ReadWrite', 'None'); $f.Close(); return $false }
    catch { return $true }
}

if (Test-Locked) {
    if ($NoWait) { Write-Host "A world or server is using MobBattle.jar: nothing removed. Exit to the main menu (/stop the server) and run again." -ForegroundColor Yellow; exit 1 }
    Write-Host "A world or server is using MobBattle.jar. Exit to the main menu / /stop the server (Ctrl+C to cancel)..." -ForegroundColor Yellow
    while (Test-Locked) { Start-Sleep -Seconds 1 }
}

if (Test-Path $target) {
    Remove-Item -LiteralPath $target -Force
    Write-Host "Removed MobBattle.jar" -ForegroundColor Green
} else {
    Write-Host "No MobBattle.jar to remove" -ForegroundColor DarkGray
}

# Same removal as deploy.ps1: only the links, never what they point to (Server is a junction to this repo).
if (Test-Path $devPack) {
    # Stop the background sync on its next pass.
    Remove-Item -LiteralPath (Join-Path $devPack '.asset-sync-token') -Force -ErrorAction SilentlyContinue
    foreach ($dir in 'Common', 'Server') {
        $link = Get-Item "$devPack\$dir" -Force -ErrorAction SilentlyContinue
        if ($dir -eq 'Common' -and $link -and -not $link.LinkType) {
            $checkedPath = [IO.Path]::GetFullPath($link.FullName)
            if ($checkedPath -ne [IO.Path]::GetFullPath((Join-Path $mods 'MobBattle.AssetsDev\Common'))) { throw 'Unsafe Common removal path' }
            if (Get-ChildItem -LiteralPath $checkedPath -Recurse -Force | Where-Object LinkType) { throw 'Unexpected link inside Common' }
            Remove-Item -LiteralPath $checkedPath -Recurse -Force
            continue
        }
        if ($link -and $link.LinkType -ne 'Junction') {
            Write-Host "$($link.FullName) is not a junction: left the dev pack in place, check it by hand." -ForegroundColor Red
            exit 1
        }
        if ($link) { [IO.Directory]::Delete($link.FullName) }
    }
    Remove-Item "$devPack\manifest.json" -Force -ErrorAction SilentlyContinue
    Remove-Item "$devPack\icon-256.png" -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $devPack 'asset-sync.log') -Force -ErrorAction SilentlyContinue
    Remove-Item $devPack -Force
    Write-Host "Removed the dev asset pack (MobBattle.AssetsDev)" -ForegroundColor Green
}

$other = Get-ChildItem -LiteralPath $mods -Filter 'MobBattle-*.jar' -ErrorAction SilentlyContinue
if ($other) {
    Write-Host "Left in place: $($other.Name -join ', ') (the mod browser copy)." -ForegroundColor Cyan
} else {
    Write-Host "No Mob Battle left in this Mods folder. Install it from the mod browser or run .\deploy.ps1." -ForegroundColor Cyan
}

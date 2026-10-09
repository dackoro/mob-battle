# Builds Mob Battle against the pre-release server and installs the jar.
#
#   .\deploy.ps1          full jar (code + assets), then wait until Hytale releases it and install.
#                         Also removes the dev asset pack if it is installed.
#   .\deploy.ps1 -Dev     asset dev mode: code-only jar + the "Mob Battle Assets DEV" folder pack, whose
#                         Common/ is synced from this repo; Server/ is a junction. Edits to models, textures, icons,
#                         particles, item JSON and translations here hot-reload in game (no restart).
#                         Only code changes need a new deploy.
#   .\deploy.ps1 -NoWait  build and install only if the jar is free (no world open)
#   .\deploy.ps1 -Force   install even with Hytale open: ONLY after "/plugin unload Dackoro:Mob Battle"
#                         in game (experimental hot reload; then "/plugin load Dackoro:Mob Battle")
param(
    [switch]$Dev,
    [switch]$NoWait,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$serverJar = "$env:APPDATA\Hytale\install\pre-release\package\game\latest\Server\HytaleServer.jar"
$mods      = "$env:APPDATA\Hytale\data\pre-release\Mods"
$target    = "$mods\MobBattle.jar"
$devPack   = "$mods\MobBattle.AssetsDev"

$gradleArgs = @('build', '--console=plain', '-q', "-PhytaleServerJar=$serverJar")
if ($Dev) { $gradleArgs += '-PcodeOnly' }
Write-Host ($(if ($Dev) { "Building (code only, assets from the dev pack)..." } else { "Building (code + assets)..." })) -ForegroundColor Cyan
& .\gradlew.bat @gradleArgs
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

if ($Dev) {
    # Folder pack with its own identity (same Group:Name as the jar would be a "Duplicate asset pack").
    # CommonAssetModule does not traverse the Common junction on this server. Use real files there.
    New-Item -ItemType Directory -Force $devPack | Out-Null
    $main = Get-Content manifest.json -Raw | ConvertFrom-Json
    $devManifest = [ordered]@{
        Group             = 'Dackoro'
        Name              = 'Mob Battle Assets DEV'
        Version           = $main.Version
        ServerVersion     = $main.ServerVersion
        Description       = "DEV ONLY: Mob Battle assets synced from $PSScriptRoot for hot reload. Remove with .\deploy.ps1 (no -Dev)."
        Authors           = @()
        Dependencies      = @{ 'Dackoro:Mob Battle' = '*' }
        DisabledByDefault = $false
        IncludesAssetPack = $true
    } | ConvertTo-Json
    # Windows PowerShell's UTF8 encoding adds a BOM; Hytale's RawJsonReader rejects it.
    [IO.File]::WriteAllText("$devPack\manifest.json", $devManifest, [Text.UTF8Encoding]::new($false))
    New-Item -ItemType Directory -Force "$PSScriptRoot\Common", "$PSScriptRoot\Server" | Out-Null
    $commonPath = Join-Path $devPack 'Common'
    $common = Get-Item -LiteralPath $commonPath -Force -ErrorAction SilentlyContinue
    if ($common -and $common.LinkType) {
        if ($common.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath([string]$common.Target) -ne [IO.Path]::GetFullPath("$PSScriptRoot\Common")) {
            throw "Unexpected Common link: $commonPath"
        }
        # Non-recursive .NET deletion removes only this verified junction, not its target.
        [IO.Directory]::Delete($commonPath)
    }
    New-Item -ItemType Directory -Force $commonPath | Out-Null
    if (-not (Test-Path "$devPack\Server")) {
        New-Item -ItemType Junction -Path "$devPack\Server" -Target "$PSScriptRoot\Server" | Out-Null
    }
    $syncScript = Join-Path $PSScriptRoot 'scripts\sync-common-assets.ps1'
    $syncToken = [Guid]::NewGuid().ToString()
    [IO.File]::WriteAllText((Join-Path $devPack '.asset-sync-token'), $syncToken)
    & $syncScript -Source "$PSScriptRoot\Common" -Pack $devPack -Token $syncToken -Once
    $syncShell = (Get-Command powershell.exe).Source
    Start-Process -FilePath $syncShell -WindowStyle Hidden -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$syncScript`"",
        '-Source', "`"$PSScriptRoot\Common`"", '-Pack', "`"$devPack`"", '-Token', $syncToken)
    Write-Host "Dev asset pack ready: $devPack (Common synced, Server linked). Export here; assets sync automatically." -ForegroundColor Green
} elseif (Test-Path $devPack) {
    # Stop the background sync on its next pass.
    Remove-Item -LiteralPath (Join-Path $devPack '.asset-sync-token') -Force -ErrorAction SilentlyContinue
    # Remove only the links, never what they point to (the repo's assets).
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
    Remove-Item -LiteralPath (Join-Path $devPack 'asset-sync.log') -Force -ErrorAction SilentlyContinue
    Remove-Item $devPack -Force
    Write-Host "Removed the dev asset pack: the jar now carries all the assets." -ForegroundColor Green
}

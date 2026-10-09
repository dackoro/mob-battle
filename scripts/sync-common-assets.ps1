param(
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Pack,
    [Parameter(Mandatory=$true)][string]$Token,
    [switch]$Once
)
$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath($Source).TrimEnd('\')
$packRoot = [IO.Path]::GetFullPath($Pack).TrimEnd('\')
$destinationRoot = Join-Path $packRoot 'Common'
$tokenFile = Join-Path $packRoot '.asset-sync-token'
$known = @{}

function Sync-Common {
    $present = @{}
    foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -Recurse -File) {
        # Editable Blockbench projects are not runtime assets.
        if ($file.Extension -eq '.bbmodel') { continue }
        $relative = $file.FullName.Substring($sourceRoot.Length + 1)
        $destination = [IO.Path]::GetFullPath((Join-Path $destinationRoot $relative))
        if (-not $destination.StartsWith($destinationRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw "Asset path escapes destination: $relative"
        }
        $present[$relative] = $true
        $stamp = "$($file.LastWriteTimeUtc.Ticks):$($file.Length)"
        $current = Get-Item -LiteralPath $destination -ErrorAction SilentlyContinue
        if ($current -and $current.Length -eq $file.Length -and $current.LastWriteTimeUtc -eq $file.LastWriteTimeUtc) {
            $known[$relative] = $stamp
            continue
        }
        New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
        $known[$relative] = $stamp
    }
    # Only remove mirrored files known to this process; never recurse or touch the source.
    foreach ($relative in @($known.Keys)) {
        if (-not $present.ContainsKey($relative)) {
            $destination = [IO.Path]::GetFullPath((Join-Path $destinationRoot $relative))
            if (-not $destination.StartsWith($destinationRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw "Delete path escapes destination: $relative"
            }
            Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
            $known.Remove($relative)
        }
    }
}

do {
    if (-not $Once -and (-not (Test-Path -LiteralPath $tokenFile) -or
            [IO.File]::ReadAllText($tokenFile).Trim() -ne $Token)) { break }
    try { Sync-Common }
    catch {
        # An export may hold a file briefly; retry on the next pass.
        Add-Content -LiteralPath (Join-Path $packRoot 'asset-sync.log') -Value "$(Get-Date -Format s) $($_.Exception.Message)"
        if ($Once) { throw }
    }
    if (-not $Once) { Start-Sleep -Milliseconds 750 }
} while (-not $Once)

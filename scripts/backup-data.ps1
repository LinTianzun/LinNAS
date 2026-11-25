[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Destination,
    [string]$DataDir
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($DataDir)) {
    if (-not [string]::IsNullOrWhiteSpace($env:LINNAS_DATA)) {
        $DataDir = $env:LINNAS_DATA
    } else {
        $DataDir = Join-Path $repoRoot "server/linnas-data"
    }
}

if (-not (Test-Path -LiteralPath $DataDir -PathType Container)) {
    throw "Data directory does not exist: $DataDir"
}
$sourceDir = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $DataDir).Path)
$sourceCanonical = $sourceDir.TrimEnd('\')
$sourceRoot = [System.IO.Path]::GetPathRoot($sourceDir).TrimEnd('\')
if ($sourceCanonical.Equals($sourceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "The filesystem root cannot be used as the linNas data directory."
}
$destinationRoot = [System.IO.Path]::GetFullPath($Destination)
$destinationCanonical = $destinationRoot.TrimEnd('\')
if ($destinationCanonical.Equals($sourceCanonical, [System.StringComparison]::OrdinalIgnoreCase) -or
    $destinationCanonical.StartsWith($sourceCanonical + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "The backup destination cannot be inside the data directory."
}

$backupName = "linNas-data-" + (Get-Date -Format "yyyyMMdd-HHmmss")
$backupPath = Join-Path $destinationRoot $backupName
if (Test-Path -LiteralPath $backupPath) {
    $backupPath += "-" + [guid]::NewGuid().ToString("N")
}
$payloadDir = Join-Path $backupPath "data"
$persistentFiles = @(
    "config.json",
    "users.json",
    "shares.json",
    "audit.log",
    "vault.json",
    "index.db",
    "index.db-wal",
    "index.db-shm"
)

New-Item -ItemType Directory -Force -Path $destinationRoot | Out-Null
New-Item -ItemType Directory -Path $payloadDir | Out-Null
$completed = $false
try {
    $copiedFiles = @()
    foreach ($name in $persistentFiles) {
        $sourceFile = Join-Path $sourceDir $name
        if (-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)) {
            continue
        }
        Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $payloadDir $name)
        $copiedFiles += $name
    }

    $fileEntries = @(
        foreach ($name in $copiedFiles) {
            $file = Join-Path $payloadDir $name
            [ordered]@{
                path   = $name
                size   = (Get-Item -LiteralPath $file).Length
                sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
    )
    $manifest = [ordered]@{
        formatVersion = 1
        createdAtUtc  = [DateTime]::UtcNow.ToString("o")
        sourceDataDir = $sourceDir
        files         = $fileEntries
    }
    $manifestJson = ConvertTo-Json -InputObject $manifest -Depth 5
    [System.IO.File]::WriteAllText(
        (Join-Path $backupPath "manifest.json"),
        $manifestJson,
        ([System.Text.UTF8Encoding]::new($false))
    )
    $completed = $true
    Write-Host "Backup completed: $backupPath"
    Write-Host "Backed up $($copiedFiles.Count) file(s)."
}
finally {
    if (-not $completed -and (Test-Path -LiteralPath $backupPath)) {
        Remove-Item -LiteralPath $backupPath -Recurse -Force
    }
}

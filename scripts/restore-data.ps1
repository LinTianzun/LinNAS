[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupPath,
    [string]$DataDir,
    [switch]$ReplaceExisting
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

$backupDir = (Resolve-Path -LiteralPath $BackupPath -ErrorAction Stop).Path
$manifestPath = Join-Path $backupDir "manifest.json"
$payloadDir = Join-Path $backupDir "data"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $payloadDir -PathType Container)) {
    throw "Backup directory must contain manifest.json and a data directory."
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.formatVersion -ne 1) {
    throw "Unsupported backup format version: $($manifest.formatVersion)"
}

$payloadFull = [System.IO.Path]::GetFullPath($payloadDir).TrimEnd('\') + '\'
$expectedFiles = @($manifest.files)
$allowedFiles = @(
    "config.json",
    "users.json",
    "shares.json",
    "audit.log",
    "vault.json",
    "index.db",
    "index.db-wal",
    "index.db-shm"
)
$seenFiles = @{}
foreach ($entry in $expectedFiles) {
    $relative = [string]$entry.path
    if ([System.IO.Path]::IsPathRooted($relative) -or $relative -match '(^|[\\/])\.\.([\\/]|$)') {
        throw "Backup manifest contains an invalid path: $relative"
    }
    $relative = $relative.Replace('/', '\')
    if ($relative -notin $allowedFiles -or $seenFiles.ContainsKey($relative)) {
        throw "Backup manifest contains an unsupported or duplicate file: $relative"
    }
    $seenFiles[$relative] = $true
    $filePath = [System.IO.Path]::GetFullPath((Join-Path $payloadFull $relative))
    if (-not $filePath.StartsWith($payloadFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Backup manifest path escapes the data directory: $relative"
    }
    if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
        throw "Backup file is missing: $relative"
    }
    $file = Get-Item -LiteralPath $filePath
    if ($file.Length -ne [long]$entry.size) {
        throw "Backup file size check failed: $relative"
    }
    $actualHash = (Get-FileHash -LiteralPath $filePath -Algorithm SHA256).Hash
    if (-not $actualHash.Equals([string]$entry.sha256, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Backup file SHA-256 check failed: $relative"
    }
}

$actualFileCount = @(Get-ChildItem -LiteralPath $payloadFull -File -Recurse -Force).Count
if ($actualFileCount -ne $expectedFiles.Count) {
    throw "Backup directory file count does not match the manifest."
}

$targetDir = [System.IO.Path]::GetFullPath($DataDir)
$targetRoot = [System.IO.Path]::GetPathRoot($targetDir).TrimEnd('\')
if ($targetDir.TrimEnd('\').Equals($targetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "The filesystem root cannot be used as the linNas data directory."
}
$targetDir = $targetDir.TrimEnd('\')
if ((Test-Path -LiteralPath $targetDir) -and -not $ReplaceExisting) {
    throw "Target data directory already exists. Stop linNas and pass -ReplaceExisting to replace it; the current directory will be preserved."
}
$parentDir = Split-Path -Parent $targetDir
if ([string]::IsNullOrWhiteSpace($parentDir)) {
    throw "Invalid target data directory path: $DataDir"
}
New-Item -ItemType Directory -Force -Path $parentDir | Out-Null
$stagingDir = Join-Path $parentDir (".linnas-restore-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $stagingDir | Out-Null
$previousDir = $null
$completed = $false

try {
    Get-ChildItem -LiteralPath $payloadFull -Force |
        Copy-Item -Destination $stagingDir -Recurse -Force

    if (Test-Path -LiteralPath $targetDir) {
        $previousDir = $targetDir + ".pre-restore-" + (Get-Date -Format "yyyyMMdd-HHmmss")
        if (Test-Path -LiteralPath $previousDir) {
            $previousDir += "-" + [guid]::NewGuid().ToString("N")
        }
        Move-Item -LiteralPath $targetDir -Destination $previousDir
    }

    try {
        Move-Item -LiteralPath $stagingDir -Destination $targetDir
        $completed = $true
    }
    catch {
        if ($previousDir -and (Test-Path -LiteralPath $previousDir) -and
            -not (Test-Path -LiteralPath $targetDir)) {
            Move-Item -LiteralPath $previousDir -Destination $targetDir
        }
        throw
    }

    Write-Host "Restore completed: $targetDir"
    if ($previousDir) {
        Write-Host "Previous data preserved at: $previousDir"
    }
}
finally {
    if (-not $completed -and (Test-Path -LiteralPath $stagingDir)) {
        Remove-Item -LiteralPath $stagingDir -Recurse -Force
    }
}

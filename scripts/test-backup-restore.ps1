$ErrorActionPreference = "Stop"
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("linnas-backup-test-" + [guid]::NewGuid().ToString("N"))
$dataDir = Join-Path $testRoot "source"
$backupRoot = Join-Path $testRoot "backups"
$restoreDir = Join-Path $testRoot "restore"
$backupScript = Join-Path $PSScriptRoot "backup-data.ps1"
$restoreScript = Join-Path $PSScriptRoot "restore-data.ps1"

try {
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
    New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $dataDir "config.json"), '{"port":52700}')
    [System.IO.File]::WriteAllText((Join-Path $dataDir "users.json"), '{"users":[]}')

    & $backupScript -DataDir $dataDir -Destination $backupRoot | Out-Null
    $backupPath = Get-ChildItem -LiteralPath $backupRoot -Directory |
        Select-Object -ExpandProperty FullName
    if (@($backupPath).Count -ne 1) {
        throw "Backup script did not create exactly one backup directory."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $backupPath "manifest.json"))) {
        throw "Backup manifest was not created."
    }

    New-Item -ItemType Directory -Force -Path $restoreDir | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $restoreDir "config.json"), '{"port":1}')
    $replaceRequired = $false
    try {
        & $restoreScript -BackupPath $backupPath -DataDir $restoreDir
    }
    catch {
        $replaceRequired = "$($_.Exception.Message)" -like "*ReplaceExisting*"
    }
    if (-not $replaceRequired) {
        throw "Restore did not require explicit confirmation before replacing an existing directory."
    }

    & $restoreScript -BackupPath $backupPath -DataDir $restoreDir -ReplaceExisting | Out-Null
    $restoredConfig = Get-Content -LiteralPath (Join-Path $restoreDir "config.json") -Raw
    if ($restoredConfig -ne '{"port":52700}') {
        throw "Restored configuration does not match the backup."
    }
    if (@(Get-ChildItem -LiteralPath $testRoot -Directory -Filter "restore.pre-restore-*").Count -ne 1) {
        throw "Previous data directory was not preserved."
    }

    $usersFile = Join-Path $backupPath "data/users.json"
    $tamperedBytes = [System.IO.File]::ReadAllBytes($usersFile)
    $tamperedBytes[0] = [byte][char]'['
    [System.IO.File]::WriteAllBytes($usersFile, $tamperedBytes)
    $checksumRejected = $false
    $checksumError = ""
    try {
        & $restoreScript -BackupPath $backupPath -DataDir (Join-Path $testRoot "tampered-restore")
    }
    catch {
        $checksumError = "$($_.Exception.Message)"
        $checksumRejected = $checksumError -like "*SHA-256*"
    }
    if (-not $checksumRejected) {
        throw "Restore did not reject the checksum-mismatched backup. Error: $checksumError"
    }

    Write-Host "Backup/restore script tests passed."
}
finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}

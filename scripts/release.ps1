[CmdletBinding()]
param(
    [string]$GradleCommand,
    [switch]$Overwrite,
    [switch]$ServerOnly,
    [switch]$AndroidOnly
)

$ErrorActionPreference = "Stop"
if ($ServerOnly -and $AndroidOnly) {
    throw "Choose at most one of -ServerOnly and -AndroidOnly."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $repoRoot "server"
$androidDir = Join-Path $repoRoot "android"
$releaseDir = Join-Path $repoRoot "release"

$serverConfig = Get-Content (Join-Path $serverDir "config.go") -Raw
$serverVersionMatch = [regex]::Match($serverConfig, 'const\s+Version\s*=\s*"(?<version>[^"]+)"')
if (-not $serverVersionMatch.Success) {
    throw "Could not read the server version from server/config.go."
}
$serverVersion = $serverVersionMatch.Groups["version"].Value

$androidBuild = Get-Content (Join-Path $androidDir "app/build.gradle.kts") -Raw
$androidVersionMatch = [regex]::Match($androidBuild, 'versionName\s*=\s*"(?<version>[^"]+)"')
$androidCodeMatch = [regex]::Match($androidBuild, 'versionCode\s*=\s*(?<code>\d+)')
if (-not $androidVersionMatch.Success -or -not $androidCodeMatch.Success) {
    throw "Could not read the Android versionName/versionCode from app/build.gradle.kts."
}
$androidVersion = $androidVersionMatch.Groups["version"].Value
$androidVersionCode = [int]$androidCodeMatch.Groups["code"].Value

$serverArtifact = Join-Path $releaseDir "linNas-server-$serverVersion-windows-amd64.exe"
$androidArtifact = Join-Path $releaseDir "linNas-$androidVersion-debug.apk"
$artifacts = @()
if (-not $AndroidOnly) {
    $artifacts += $serverArtifact
}
if (-not $ServerOnly) {
    $artifacts += $androidArtifact
}
$outputFiles = @()
foreach ($artifact in $artifacts) {
    $outputFiles += $artifact
    $outputFiles += "$artifact.sha256"
}

if (-not $Overwrite) {
    $existing = @($outputFiles | Where-Object { Test-Path $_ })
    if ($existing.Count -gt 0) {
        $names = ($existing | ForEach-Object { Split-Path -Leaf $_ }) -join ", "
        throw "Release artifact(s) already exist: $names. Bump the component version or pass -Overwrite intentionally."
    }
}

if (-not $AndroidOnly -and -not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw "Go was not found on PATH. Install Go compatible with server/go.mod before building the server."
}
if (-not $ServerOnly) {
    if ([string]::IsNullOrWhiteSpace($GradleCommand)) {
        $GradleCommand = Join-Path $androidDir "gradlew.bat"
    }
    if (-not (Test-Path $GradleCommand) -and -not (Get-Command $GradleCommand -ErrorAction SilentlyContinue)) {
        throw "Gradle command not found: $GradleCommand"
    }
}

New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$stageDir = Join-Path $releaseDir (".staging-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $stageDir | Out-Null
$stagedArtifacts = @($artifacts | ForEach-Object { Join-Path $stageDir (Split-Path -Leaf $_) })

try {
    if (-not $AndroidOnly) {
        Push-Location $serverDir
        try {
            & go test ./...
            if ($LASTEXITCODE -ne 0) {
                throw "go test failed."
            }

            & go vet ./...
            if ($LASTEXITCODE -ne 0) {
                throw "go vet failed."
            }

            & go build -trimpath -o (Join-Path $stageDir (Split-Path -Leaf $serverArtifact)) .
            if ($LASTEXITCODE -ne 0) {
                throw "Server build failed."
            }
        }
        finally {
            Pop-Location
        }
    }

    if (-not $ServerOnly) {
        Push-Location $androidDir
        try {
            & $GradleCommand --no-daemon :app:assembleDebug
            if ($LASTEXITCODE -ne 0) {
                throw "Android assembleDebug failed."
            }
        }
        finally {
            Pop-Location
        }

        $androidBuildArtifact = Join-Path $androidDir "app/build/outputs/apk/debug/app-debug.apk"
        if (-not (Test-Path $androidBuildArtifact)) {
            throw "Android APK was not produced at $androidBuildArtifact."
        }
        $stagedAndroidArtifact = Join-Path $stageDir (Split-Path -Leaf $androidArtifact)
        Copy-Item -LiteralPath $androidBuildArtifact -Destination $stagedAndroidArtifact
    }

    foreach ($artifact in $stagedArtifacts) {
        $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
        $checksumLine = "{0}  {1}{2}" -f $hash, (Split-Path -Leaf $artifact), [Environment]::NewLine
        [System.IO.File]::WriteAllText(
            "$artifact.sha256",
            $checksumLine,
            [System.Text.Encoding]::ASCII
        )
    }

    foreach ($artifact in $stagedArtifacts) {
        Move-Item -LiteralPath $artifact -Destination (Join-Path $releaseDir (Split-Path -Leaf $artifact)) -Force:$Overwrite
        Move-Item -LiteralPath "$artifact.sha256" -Destination (Join-Path $releaseDir ((Split-Path -Leaf $artifact) + ".sha256")) -Force:$Overwrite
    }
}
finally {
    if (Test-Path $stageDir) {
        Remove-Item -LiteralPath $stageDir -Recurse -Force
    }
}

Write-Host "Release artifacts created:"
foreach ($artifact in $artifacts) {
    Write-Host "  $artifact"
    Write-Host "  $artifact.sha256"
}
Write-Host "Server version: $serverVersion"
Write-Host "Android version: $androidVersion (versionCode $androidVersionCode)"

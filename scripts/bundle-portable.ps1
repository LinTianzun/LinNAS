[CmdletBinding()]
param(
    # 已构建好的服务端 exe;默认取 release/ 下最新的 windows-amd64 产物。
    [string]$ServerExe,
    # 随包分发的 ffmpeg.exe 路径。
    [string]$FfmpegPath = (Join-Path $env:LOCALAPPDATA "Programs\ffmpeg\bin\ffmpeg.exe"),
    # 输出 zip 路径;默认 release/linNas-portable-<version>-windows-amd64.zip。
    [string]$OutZip,
    [switch]$Overwrite
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$releaseDir = Join-Path $repoRoot "release"

# --- 解析服务端版本号 ---
$serverConfig = Get-Content (Join-Path $repoRoot "server\config.go") -Raw
$versionMatch = [regex]::Match($serverConfig, 'const\s+Version\s*=\s*"(?<version>[^"]+)"')
if (-not $versionMatch.Success) {
    throw "Could not read the server version from server/config.go."
}
$serverVersion = $versionMatch.Groups["version"].Value

# --- 默认取最新已构建的服务端 exe ---
if ([string]::IsNullOrWhiteSpace($ServerExe)) {
    $latest = Get-ChildItem -Path $releaseDir -Filter "linNas-server-*-windows-amd64.exe" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $latest) {
        throw "No built server exe found in $releaseDir. Run scripts/release.ps1 -ServerOnly first, or pass -ServerExe."
    }
    $ServerExe = $latest.FullName
}
if (-not (Test-Path $ServerExe)) {
    throw "Server exe not found: $ServerExe"
}
if (-not (Test-Path $FfmpegPath)) {
    throw "ffmpeg.exe not found: $FfmpegPath. Install ffmpeg or pass -FfmpegPath."
}

if ([string]::IsNullOrWhiteSpace($OutZip)) {
    $OutZip = Join-Path $releaseDir "linNas-portable-$serverVersion-windows-amd64.zip"
}
if ((Test-Path $OutZip) -and -not $Overwrite) {
    throw "Output zip already exists: $OutZip. Pass -Overwrite to replace it."
}

# --- 组装便携目录: exe + bin\ffmpeg.exe + 启动说明 ---
$stage = Join-Path $releaseDir (".portable-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $stage | Out-Null
try {
    Copy-Item -LiteralPath $ServerExe -Destination (Join-Path $stage "linNas-server.exe")
    New-Item -ItemType Directory -Force -Path (Join-Path $stage "bin") | Out-Null
    Copy-Item -LiteralPath $FfmpegPath -Destination (Join-Path $stage "bin\ffmpeg.exe")

    $readme = @"
LinNAS 便携版 (服务端 $serverVersion)

目录结构:
  linNas-server.exe   服务端主程序
  bin\ffmpeg.exe      视频转码 / 缩略图依赖(随包自带,无需安装)

用法:
  双击 linNas-server.exe 启动;首次运行会在同目录创建 linnas-data\ 存放数据。
  浏览器打开控制台输出的 http://<本机IP>:<端口>/ui/ 即可访问。

说明:
  - ffmpeg 会被服务端优先从 bin\ 目录加载,因此整个文件夹拷到任何 Windows 10/11 x64
    上都能直接用,目标设备无需单独安装 ffmpeg。
  - Tailscale(可选,用于外网访问)不随包分发;如需外网访问,请在目标设备单独安装并登录。
"@
    $readme | Set-Content -LiteralPath (Join-Path $stage "README.txt") -Encoding UTF8
    # 去掉 PS5.1 UTF8 BOM,写成无 BOM 的 UTF-8,避免部分查看器把首行显示成乱码。
    $readmePath = Join-Path $stage "README.txt"
    $clean = [System.IO.File]::ReadAllText($readmePath, [System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText($readmePath, $clean, (New-Object System.Text.UTF8Encoding $false))

    if (Test-Path $OutZip) { Remove-Item -LiteralPath $OutZip -Force }
    Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $OutZip
}
finally {
    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
}

$hash = (Get-FileHash -LiteralPath $OutZip -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumLine = "{0}  {1}{2}" -f $hash, (Split-Path -Leaf $OutZip), [Environment]::NewLine
[System.IO.File]::WriteAllText(
    "$OutZip.sha256",
    $checksumLine,
    [System.Text.Encoding]::ASCII
)

Write-Host "Portable bundle created:"
Write-Host "  $OutZip"
Write-Host "  $OutZip.sha256"
Write-Host "Server version: $serverVersion"

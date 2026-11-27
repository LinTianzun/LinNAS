# 构建与发布

## 组件版本

服务端与 Android 客户端分别维护版本号：

| 组件 | 当前版本 | 版本号维护位置 |
|---|---:|---|
| Windows 服务端 | 1.8.4 | `server/config.go`（`Version`） |
| Android 客户端 | 1.8.12，`versionCode` 为 21 | `android/app/build.gradle.kts` |

发布前先更新对应组件的版本号。每次发布新的 Android APK 时，`versionCode` 必须递增。请同步更新对应组件的 README 和 `PLAN.md`；两个组件的版本号互不关联，不要根据其中一个推算另一个。

## Windows 构建与打包

请先安装与 `server/go.mod` 兼容的 Go、JDK 17 或更高版本，以及 Android SDK 34。默认使用 Gradle Wrapper 构建 Android 客户端。

在项目根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\release.ps1
```

脚本会依次：

1. 运行 `go test ./...` 和 `go vet ./...`。
2. 构建 Windows amd64 服务端程序。
3. 使用 Gradle 构建 Android debug APK。
4. 将带版本号的产物复制到 `release/`，并为每个产物生成 SHA-256 校验和文件。

`-ExecutionPolicy Bypass` 只对本次 PowerShell 进程生效，不会更改系统执行策略。默认同时构建两个组件；如需单独发布，可使用 `-ServerOnly` 或 `-AndroidOnly`。脚本默认不会覆盖同版本产物：请递增对应组件的版本号；只有确实要替换该版本的构建时，才使用 `-Overwrite`。

产物命名格式：

- 服务端：`linNas-server-<服务端版本>-windows-amd64.exe`
- Android：`linNas-<Android版本>-debug.apk`
- 两种产物都会附带同名的 `.sha256` 校验和文件。

如果 Gradle Wrapper 无法下载所需版本，可指定本机已安装的 Gradle：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\release.ps1 -GradleCommand "C:\path\to\gradle.bat"
```

`scripts/release.ps1` 默认产出 **debug** 签名 APK（适合直接安装测试），不是正式生产签名包；正式发布请用下面的 release 签名或标签工作流。

## Android release 签名

release 构建启用 R8 混淆（`isMinifyEnabled = true`，`isShrinkResources = false`），签名密钥从**环境变量**或 `android/keystore.properties`（已加入 `.gitignore`）读取：

| 变量 / 键 | 说明 |
|---|---|
| `LINNAS_KEYSTORE_FILE` / `storeFile` | keystore 文件路径 |
| `LINNAS_KEYSTORE_PASSWORD` / `storePassword` | keystore 口令 |
| `LINNAS_KEY_ALIAS` / `keyAlias` | 密钥别名 |
| `LINNAS_KEY_PASSWORD` / `keyPassword` | 密钥口令 |

本地构建：

```powershell
$env:LINNAS_KEYSTORE_FILE = "C:\keys\linnas-release.jks"
$env:LINNAS_KEYSTORE_PASSWORD = "..."
$env:LINNAS_KEY_ALIAS = "linnas"
$env:LINNAS_KEY_PASSWORD = "..."
cd android
.\gradlew.bat --no-daemon :app:assembleRelease
```

产物：`android/app/build/outputs/apk/release/app-release.apk`。

> 未配置密钥时，release 构建会回退 **debug 签名**，仅用于本地验证 R8 / 可构建性，**不可作为正式发布包**。

## 标签发布（GitHub Actions）

推送 `v*` 标签会触发 [.github/workflows/release.yml](.github/workflows/release.yml)：

1. 读取服务端 / Android 版本号；
2. `go test` / `go vet` 并构建服务端 exe；
3. 用 `LINNAS_KEYSTORE_BASE64` 还原 keystore，跑 Android 单测与 `assembleRelease`；
4. 生成 `.sha256`，创建 GitHub Release 并附两个产物。

需要在仓库 Secrets 配置：`LINNAS_KEYSTORE_BASE64`（keystore 的 Base64）、`LINNAS_KEYSTORE_PASSWORD`、`LINNAS_KEY_ALIAS`、`LINNAS_KEY_PASSWORD`。缺任意一个都会失败，避免误发未签名包。

```powershell
# 生成 keystore 的 Base64
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\keys\linnas-release.jks")) | Set-Clipboard

git tag v1.8.12
git push origin v1.8.12
```

## 验证与分发

- 确认服务端启动信息或 `/api/health` 返回预期版本。
- 在测试设备上安装 APK，确认能连接服务端并完成登录。
- GitHub Actions 会运行 Go 测试、`go test -race`、`go vet`、Windows 服务端构建及 Android debug 构建；发布前仍需在目标 Windows 环境做一次手动启动与 WebDAV 客户端验证。
- 对照产物与校验和文件，例如：

  ```powershell
  Get-FileHash .\release\linNas-server-1.8.4-windows-amd64.exe -Algorithm SHA256
  Get-Content .\release\linNas-server-1.8.4-windows-amd64.exe.sha256
  ```

- 替换现有服务端前，先停止服务并备份 `linnas-data`。保留历史版本产物；不同内容不要复用同一个版本号。
- 在对应 README 和 `PLAN.md` 中记录用户可见的改动，以及兼容性或迁移说明。

服务端与 Android 客户端可以分别发布。因此，`release/` 中某个组件的最新产物不一定与另一组件同时发布。

# 构建与发布

## 组件版本

服务端与 Android 客户端分别维护版本号：

| 组件 | 当前版本 | 版本号维护位置 |
|---|---:|---|
| Windows 服务端 | 1.6.0 | `server/config.go`（`Version`） |
| Android 客户端 | 1.4.0，`versionCode` 为 6 | `android/app/build.gradle.kts` |

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

Android APK 使用 **debug** 签名，适合直接安装和测试；它不是 Google Play 或正式生产签名包。如需发布生产 APK，请另行配置 release 签名。

## 验证与分发

- 确认服务端启动信息或 `/api/health` 返回预期版本。
- 在测试设备上安装 APK，确认能连接服务端并完成登录。
- GitHub Actions 会运行 Go 测试、`go test -race`、`go vet`、Windows 服务端构建及 Android debug 构建；发布前仍需在目标 Windows 环境做一次手动启动与 WebDAV 客户端验证。
- 对照产物与校验和文件，例如：

  ```powershell
  Get-FileHash .\release\linNas-server-1.6.0-windows-amd64.exe -Algorithm SHA256
  Get-Content .\release\linNas-server-1.6.0-windows-amd64.exe.sha256
  ```

- 替换现有服务端前，先停止服务并备份 `linnas-data`。保留历史版本产物；不同内容不要复用同一个版本号。
- 在对应 README 和 `PLAN.md` 中记录用户可见的改动，以及兼容性或迁移说明。

服务端与 Android 客户端可以分别发布。因此，`release/` 中某个组件的最新产物不一定与另一组件同时发布。

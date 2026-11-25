# 数据备份与恢复

## 备份内容与范围

`scripts/backup-data.ps1` 会备份 `linnas-data` 中的配置、用户、分享、审计记录、保险箱配置和 SQLite 索引（包括存在的 WAL/SHM 文件），并为每个文件记录大小和 SHA-256。缩略图、转码缓存和媒体文件不会包含在备份中；索引丢失时可在服务端重建。

保险箱中的实际文件保存在存储库目录（通常为 `.linNasVault`），媒体文件也保存在各存储库中；它们不属于 `linnas-data`，需要另行备份。建议把备份保存到不同的物理磁盘。

**备份或恢复前先停止 linNas 服务端**，确保配置、审计文件和 SQLite 数据库不会在复制期间继续变化。

## 创建备份

从项目根目录执行，示例将备份保存到 `D:\linNas-backups`：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-data.ps1 -Destination "D:\linNas-backups"
```

默认数据目录为 `server/linnas-data`；如果服务端使用了 `LINNAS_DATA` 环境变量或自定义目录，可显式指定：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-data.ps1 -DataDir "E:\linNas-data" -Destination "D:\linNas-backups"
```

每次备份生成一个独立的 `linNas-data-时间戳` 目录，内含 `data/` 和 `manifest.json`。清单会在恢复时用于完整性校验。

## 从备份恢复

1. 停止 linNas 服务端。
2. 选择备份目录（即包含 `manifest.json` 和 `data/` 的目录）。
3. 恢复到默认数据目录：

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\restore-data.ps1 -BackupPath "D:\linNas-backups\linNas-data-20261125-120000" -ReplaceExisting
   ```

4. 如果服务端使用自定义数据目录，传入 `-DataDir`。
5. 启动服务端并确认登录、存储库和索引状态正常。

恢复脚本会先校验清单、文件大小和 SHA-256。目标目录已存在时必须明确传入 `-ReplaceExisting`；原数据目录会先改名保留为 `.pre-restore-时间戳`，不会被直接删除。恢复操作只还原应用数据，不会覆盖或移动存储库里的媒体文件或保险箱内容。

脚本自测可使用临时目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-backup-restore.ps1
```

# 当前任务（CURRENT TASK）

> 这是一个「活」文件：记录此刻正在做 / 紧接着要做的事。稳定后的结论应迁移到 [TASK_HISTORY.md](TASK_HISTORY.md)，整体状态见 [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md)。更新时间：2026-11-26。

## 进行中

- **文档补全**：依据代码与现有文档补齐 `AGENTS.md`、`CHANGELOG.md`、`docs/ARCHITECTURE.md`、`docs/DEVELOPMENT_STATUS.md`、`docs/TASK_HISTORY.md`、`docs/CURRENT_TASK.md`，并删除拼写错误的 `RELEGSING.md`。（本次）
- **待提交**：`README.md` 的未提交修改 + 上述新增 / 修改文档，等待人工复核后提交。

## 下一步（待办）

1. 人工复核本次文档内容与措辞。
2. 走查 [ANDROID_TESTING.md](../ANDROID_TESTING.md) 清单，在 Android 7 / 14 真机 + 真实服务端完成 M10 发布验收并记录结果。
3. 按 [RELEASING.md](../RELEASING.md) 流程发布（若递增版本号，同步更新 `server/config.go` / `android/app/build.gradle.kts` 与 README、PLAN）。

## 阻塞 / 风险

- 无代码级阻塞。
- 发布仍缺真机端到端验收记录；APK 为 debug 签名。

## 最近完成

- M13：Android v1.8.0 体验升级（传输中心 / 多选批量 / 生物识别 / 版本检查 / 播放器重做 / 媒体分组 / 视觉打磨）。
- M12：Android v1.7.0 对齐服务端 v1.7.0。
- M11：服务端 v1.7.0（索引增量 + 对账、安全加固、多媒体增强、存储分析、媒体库排除）。

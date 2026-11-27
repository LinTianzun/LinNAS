# 隐私保险箱静态加密(Static Encryption)技术方案

> 状态:**M1–M4 已全部实现,并随 Windows 服务端 1.9.0 发布**(M1 容器核心、M2 业务接入与信封密钥、M3 旧文件一次性迁移 + UI、M4 文件名加密 + Windows 隐藏属性);**1.9.1 起**保险箱列表支持图片/视频缩略图与点击直接预览(解密流→ffmpeg 管道,仅内存缓存,绝不落盘)。
>
> 实现文件:
> `server/vault_container.go`(容器格式 + 文件名加密头)、`server/vault_encrypted_file.go`(随机读 + 流式加密写)、
> `server/vault_keystore.go`(KEK/VMK 密钥层与解锁会话)、`server/vault_paths.go`(token 磁盘布局 / 显示名解析)、
> `server/vault_transfer.go`(导入导出加解密)、`server/vault_migrate.go`(一次性迁移)、
> `server/vault_hide_windows.go` / `vault_hide_other.go`(Windows 隐藏+系统属性)、`server/vault.go`(业务接口)。

## 1. 背景与目标

保险箱(`server/vault.go`)原本只是**应用层隐藏**:所有走 LinNAS 的访问(网页浏览 / 搜索 / 媒体库 / WebDAV / 缩略图 / 下载)都被 `inVault()` 拦截,但文件在磁盘上是**明文原样存放**在 `.linNasVault` 目录里。绕过 LinNAS、直接用资源管理器看硬盘的人能看到内容。

本方案给保险箱加上**静态加密(at-rest encryption)**:文件落盘时加密,只有用密码解锁后才能解密读取。

**目标**

- 磁盘上的保险箱文件内容不可读(无密码 = 无明文)。
- 保留现有全部能力:解锁会话、多级目录、上传 / 下载、**视频在线播放 + 拖动(Range)**、删除、导入 / 导出。
- 局域网内的播放 / 下载吞吐**不被加密拖慢**(依赖 AES-NI)。
- 永不把明文写到磁盘临时文件。

**非目标**

- 不做多人 / 多密码:沿用单密码门禁。
- 不做密码找回:密码丢失 = 数据永久不可恢复。
- 目录名暂不加密(文件名已加密,见 §6)。

## 2. 算法选型

| 用途 | 选择 | 理由 |
|---|---|---|
| 内容加密 | **AES-256-GCM** | Go 标准库 `crypto/aes` 自动走 AES-NI;GCM 自带认证;支持分块随机访问 |
| 口令派生 | **PBKDF2-HMAC-SHA256**,迭代 120000 | 沿用现有 `pbkdf2Key`(`server/vault.go`),不引入新依赖 |
| 每文件内容密钥 | 主密钥 VMK 包裹的随机 32 字节 CEK(信封加密) | 改密码不用重加密文件 |
| 文件名 | 磁盘名 = 确定性 token;真实文件名用 VMK 派生密钥 AES-GCM 加密存容器头 | 资源管理器看不到文件名 |
| 目录名 | 明文(后续可做) | 简化实现;见 §6 |

> 硬性约束:只用 Go 标准库 + 已有依赖,**不新增 CGO**。

## 3. 磁盘格式:加密容器 `.lvault`

保险箱目录内的每个**文件**对应一个 `<token>.lvault` 容器;文件夹保持原样(目录名明文)。

```
偏移     大小      字段
0        8         magic = "LVLT1\0\0\0"
8        1         version = 2(可读 1 = 旧格式)
9        1         flags(预留)
10       2         reserved
12       4         headerLen(JSON 长度)
16       496       header JSON(明文,不足补空格)
512      ...       data blocks(密文)
```

- 头部区**固定预留 512 字节**(`lvaultHeaderReserve`)。流式加密时先占位、数据全部写完后 `Seek(0)` 回填 header —— 这样既不用把整份密文缓冲在内存,也不用二次拷贝数据,还避开了"占位 header 与回填 header 长度不一致导致数据错位"的坑。JSON 尾部用空格补齐,`json.Unmarshal` 接受尾随空白。
- 数据区起点由 `headerLen` 推导(`16 + headerLen`),读取端不假设固定 512。

**header(JSON,明文)** —— 不解密数据就能读到的元数据:

```json
{
  "wrappedCEK": "hex",   // 用 VMK 包裹的 32 字节内容密钥
  "cekNonce":   "hex",   // 包裹 CEK 的 GCM nonce
  "plainSize":  12345678,
  "blockSize":  65536,
  "name":       "hex",   // v2:加密后的显示文件名(VMK 派生的名字密钥 + AES-GCM)
  "nameNonce":  "hex"    // v2:加密显示名的 nonce
}
```

`name` 为空 = 旧格式(M2 及更早):显示名就是磁盘文件名去掉 `.lvault`。

**data blocks** —— 每块独立加密,支持随机访问:

```
每块 = gcmNonce(12B) || ciphertext(明文块长度) || gcmTag(16B)
第 i 块加密的明文 = 原文件第 [i*blockSize, (i+1)*blockSize) 字节;末块可短
AAD = magic || blockIndex(uint64 LE)   // 防块被重排 / 搬移
```

`blockOffset` 必须按**各块实际编码长度**累加(末块短块更短),不能用固定满块大小 × index。

## 4. 密钥管理(信封加密)

```
用户口令 + salt(存 vault.json) --PBKDF2-HMAC-SHA256,120000--> KEK(32B)
vault.json:  wrapped(VMK, KEK) + keyNonce       ← 磁盘上只有包裹后的主密钥
.lvault:     wrapped(CEK, VMK) + cekNonce       ← 每个文件一个随机 CEK
             name = AES-GCM(subkey(VMK,"name-enc"), 显示名)
磁盘文件名 = hex(HMAC(subkey(VMK,"name-token"), 目录相对路径 + 显示名))[:32] + ".lvault"
```

- **VMK(保险箱主密钥)**:首次创建保险箱时随机生成 32 字节,用口令派生的 **KEK** 经 AES-GCM 包裹后存进 `DataDir/vault.json`(v2);磁盘上**不保存任何可直接解密文件的密钥**。
- **CEK(内容密钥)**:每个 `.lvault` 文件随机生成,存在文件 header 里,用 VMK 包裹。
- **文件名键**:`vaultSubkey(VMK, "linNas-vault-name-enc-v1")` 用于加密显示名;`vaultSubkey(VMK, "linNas-vault-name-token-v1")` 用于派生磁盘 token。两者与 CEK 包裹键互相独立。
- **解锁会话**:`vaultState` 的 `keys map[token][]byte`(`token → VMK`)。`unlock` 时解包 VMK 放进会话内存,`lock` / 过期 / 改密码时清零并删除。VMK **只在内存**。
- **改密码**:解包旧 VMK → 用新 salt + 新口令派生的 KEK 重新包裹同一个 VMK → 写回 `vault.json`。**不重写任何 `.lvault` 文件**,也不会出现新旧密钥混杂的半迁移状态。
- **v1 旧配置自动升级**:旧 `vault.json` 的 `hash` 字段就是 PBKDF2 派生值本身(等于明文主密钥)。解锁时校验通过后立即升级为 v2:生成全新随机 VMK、写 `wrappedKey`,移除 `hash`。M1 之前没有任何 `.lvault` 文件,所以换新 VMK 不会丢数据。
- `vault.json` 与保险箱目录**必须一起备份**:只留 `.lvault` 或只留 `vault.json` 都无法恢复。

## 5. 读写路径

**写入(上传 / 导入)**

```
明文字节流 → 每次读 64KB → AES-GCM 加密 → 追加到 .lvault 数据区
明文只在内存里停留一个块,磁盘上只出现密文;显示名加密进 header
```

**读取(下载 / 播放 / 导出)**

```
按显示路径算 token 文件名(或回退旧格式)→ 读 header → 解包 CEK
→ 按目标偏移只解涉及到的块 → 流式写出
```

`encryptedFile` 实现 `io.ReaderAt + io.ReadSeeker`,可直接交给 `http.ServeContent`,Range / 视频拖动只解目标 64KB 块。导出时用 `io.NewSectionReader` 顺序解密并还原显示文件名,不落临时文件。

**缩略图(1.9.1)**

```
GET /api/vault/thumb?path=&w=&vt=
解密流 → ffmpeg stdin(-i pipe:0) → 首帧 JPEG → stdout → 内存 LRU(256 项 / 32MB)
```

缩略图必须由解密后的数据生成,但**不能写磁盘缓存**(否则等于把明文图片落到 `DataDir/thumbs`)。因此只在进程内存里缓存,按「磁盘 token 路径 + mtime + 宽度」索引;锁定 / 改密码时整体清空。视频走管道无法 seek,只取容器首帧;ffmpeg 缺失时接口返回 503,前端回退为类型图标(不影响下载 / 播放)。

**缩略图 / 转码**:保险箱目录不参与媒体索引,因此不生成缩略图、不转码;如未来需要,必须走 `解密流 → ffmpeg stdin` 管道。

## 6. 权衡

1. **文件名 / 目录名加密**:已实现**文件名加密**(磁盘 token + 加密显示名)。目录名仍明文——资源管理器能看到目录结构,但看不到文件名与内容。"连目录名一起加密"(单容器 + 内部索引)留作后续。
2. **容器命名**:`<token>.lvault`;列表 / 下载 / 删除对外的 `path` 仍是**显示路径**,由 `vaultLookup` 解析到磁盘真实路径。
3. **Windows 隐藏属性**:已实现——保险箱根目录、子目录(新建 / 导入 / 迁移时)自动设置 `Hidden|System`,资源管理器默认不显示。

## 7. 与现有代码的整合点(已实现)

| 位置 | 现状 |
|---|---|
| `handleVaultUpload` | 明文字节流 → `writeEncryptedStreamNamed` 加密写成 token 容器,显示名加密进 header |
| `handleVaultRaw` | 加密文件走 `openEncryptedFile` + `http.ServeContent`(Range/拖动);旧明文文件仍按文件流读取 |
| `handleVaultList` | 从容器头解出显示名、大小取 `plainSize`、`encrypted` 标记;旧明文文件照常列出 |
| `handleVaultImport/Export` | 导入 = 逐文件加密(目录递归);导出 = 解密流写明文并还原文件名(目录递归) |
| `handleVaultDelete` / `handleVaultMkdir` | 走 `vaultLookup` / 原逻辑;新建目录打隐藏属性 |
| `handleVaultChangePassword` | 只重新包裹 VMK(见 §4),清空所有解锁会话 |
| `handleVaultMigrate` | 一次性迁移:旧明文文件 + 旧格式容器 → v2 容器(先统计、再确认、`.part` 后 rename) |
| `handleVaultThumb` | 解密流 → ffmpeg stdin → JPEG,内存 LRU 缓存(不落盘),锁定/改密清空;未解锁 401、非媒体 415 |
| `vaultState` | `keys map[string][]byte` 存 token → VMK |
| 缩略图 / 内容索引 | 保险箱不进索引(既有排除逻辑不变) |

## 8. 性能预算

| 操作 | 预期 | 依据 |
|---|---|---|
| 局域网播放 / 下载吞吐 | ≈ 不加密 | AES-NI GB/s 级 >> 网络瓶颈 |
| 视频拖动(seek) | +毫秒级 | 只解目标块 |
| 小文件随机读 | 读 1 字节解 1 块(64KB) | 块越大放大越明显;64KB 可接受 |
| 列表 | 每文件读一次 512 字节头 + 小段 GCM 解密名字 | 可忽略 |
| 首次解锁 | +0.1~0.3s | PBKDF2 12 万次迭代,一次 |
| 改密码 | O(1) | 只重包裹 VMK,与文件数无关 |

**红线**

1. 密钥派生只在解锁时做一次,不每次读文件重派生。
2. 解密直连网络 / 管道,**不落明文临时文件**。
3. 转码若要做,走 `解密流 → ffmpeg stdin`。

## 9. 迁移(现有明文保险箱 → 加密)

- **兼容读取(已实现)**:没有容器头的旧明文文件、旧格式容器(v1)仍可列表 / 下载 / 导出。
- **一次性迁移(已实现)**:`POST /api/vault/migrate {confirm}`。`confirm=false` 只统计需要迁移的文件数 / 字节数 / 其中旧明文与旧容器各多少;`confirm=true` 才逐个写新容器(`.part` → rename)并删除旧文件。Web 保险箱页「加密现有文件」按钮先展示统计、二次确认后执行,未加密文件在列表里标记「未加密」。
- 迁移可重复执行(已迁移的文件不会被再次计入),失败项会在响应的 `errors` 里逐条给出。

## 10. 测试要点(实现与测试同目录)

- `server/vault_container_test.go`:容器加解密往返(0/1/100/65535/65536/65537/204800)、随机 seek、越界 EOF、错误密钥、篡改密文、header 校验。
- `server/vault_encryption_test.go`:上传即加密且磁盘名不含显示名、列表 / 下载 / 跨块 Range、改密码不重加密容器且不保留旧 hash、v1 配置自动升级、旧明文兼容、导入 / 导出加解密、目录导入、文件名加解密与 header 落盘、固定头区与 `ReadSeeker` 语义、迁移(干跑 / 执行 / 幂等)、会话密钥生命周期(锁定 / 过期 / prune)。
- `server/vault_hide_windows_test.go`(仅 Windows):保险箱目录与子目录设置 `Hidden|System` 属性。
- `server/vault_test.go`:原有 setup/status/unlock/lock/change-password/列表/上传/删除/导入/导出/防穿越回归(已按加密后行为更新断言)。

## 11. 里程碑(全部完成)

1. **M1 容器读写核心**:`.lvault` 格式 + `encryptedFile`(ReaderAt/ReadSeeker)+ 单测。
2. **M2 业务接入**:上传 / 下载 / 播放(Range)/ 列表 / 导入导出 + 信封密钥层(VMK)+ 旧明文兼容。
3. **M3 迁移**:一次性加密迁移 API + Web 按钮与确认 + 未加密提示。
4. **M4 增强**:文件名加密(token 磁盘名 + 加密显示名)、Windows `Hidden|System` 属性。
5. **发布**:随 Windows 服务端 **1.9.0** 发布(2026-11-28)。
6. **1.9.1 体验**:保险箱列表缩略图(内存缓存)与点击/回车/空格直接预览;Web UI 成体系键盘快捷键 + `?` 帮助。

## 12. 已知限制 / 风险

- 目录结构、文件大小、修改时间仍是明文(通过资源管理器可见);文件名与内容已加密。
- `vault.json` 丢失 = 所有 `.lvault` 永久不可恢复;保险箱密码忘记同样不可恢复。
- 导出会把明文写进存储库(用户显式操作);导出后原文不再受保险箱保护。
- 旧明文文件在迁移前仍可被资源管理器直接看到(列表会标记「未加密」并提示迁移)。
- 迁移是破坏性操作(删除旧文件),必须由用户在 UI 确认后执行。
- 缩略图只在内存缓存(不落盘),服务重启或锁定后首次查看会重新生成;视频缩略图取容器首帧(管道不可 seek);ffmpeg 缺失时回退为类型图标。

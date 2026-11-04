# PureTone 安装包输出与归档约定

> 生效日期：2026-11-04 ｜ 适用仓库：`D:/PureTone`（Windows + Git Bash）

## 一、唯一输出路径

**后续所有交付用安装包统一输出到工程目录内：**

```
D:/PureTone/dist/output/
```

- **不再向桌面（`C:\Users\sidey\Desktop\PureTone-APK\`）输出任何安装包。**
- 生成入口是 `scripts/package-release.sh`，它把 `:app:assembleRelease` 的原生三包收集到这里，
  逐包做 sha256 一致性校验，并生成 `SHA256SUMS.txt` 清单。
- 重新构建会让 APK 字节变化（打包时间戳等）。脚本遇到「同名旧输出内容不同」时，会先把上一批输出
  **无损归档**到 `dist/archive/v<版本>/<时间戳>_构建更新前输出/`，再写入新包；内容相同则直接跳过。
  因此反复构建、反复运行脚本都不会卡住，也不会覆盖旧包或留下重复副本。
- `app/build/outputs/apk/release/` 仍是 Gradle 的**构建中间产物**目录（本来就归 Gradle 管，
  且已被 `.gitignore` 忽略），不是交付输出目录，不要直接从那里取包分发。

### 用法

```bash
# 1) 构建（本地等价命令见文末备注）
cd /d/PureTone && ./gradlew assembleRelease

# 2) 收集到唯一输出目录（三包 + 清单）
./scripts/package-release.sh

# 3) 需要整体传输时，额外打一个三包 zip
./scripts/package-release.sh --zip 播放核心单测加固
```

产物固定为三 ABI（v1.9.2 起收敛为 64 位 + universal）：

```
dist/output/PureTone-v1.9.2-arm64-v8a-release.apk
dist/output/PureTone-v1.9.2-x86_64-release.apk
dist/output/PureTone-v1.9.2-universal-release.apk
dist/output/SHA256SUMS.txt                # 版本 / commit / 时间 / 逐包 sha256
```

版本号唯一来源是 `app/build.gradle.kts` 的 `appVersionName`，脚本自动读取，不硬编码。

## 二、归档目录

**归档目录复用既有的 `D:/PureTone/dist/archive/`**（原已存放 v1.0.3~v1.5.0 历史包与验收证据），
新增内容按「版本 / 交付批次」分层：

```
dist/archive/
├── PureTone-v1.0.3…v1.5.0-*.apk        # 既有历史归档，保持原有平铺结构不动
├── *_delivery_*.txt / verify_v* /      # 既有交付摘要与真机验收证据，不动
├── v1.6.0/                             # 本次从 dist/ 根收拢的 2 个包
├── v1.9.0/                             # 本次从 dist/release/v1.9.0 收拢的 5 个包
├── v1.9.2/
│   ├── 2026-11-03_投屏投送取证与音量修复/
│   ├── 2026-11-04_0653_SSDP单播补搜/
│   ├── 2026-11-04_0725_自建发现/
│   ├── 2026-11-04_0756_投屏历史列表/
│   ├── 2026-11-04_0853_投屏弹窗历史同显/
│   ├── 2026-11-04_1000_PlaybackManager拆分收尾/
│   └── 2026-11-04_1245_播放核心单测加固/
└── _ARCHIVE_INDEX.md                   # 全量归档清单（路径 / 大小 / sha256 / 来源 / 生成时间）
```

完整逐文件清单见 [`archive/_ARCHIVE_INDEX.md`](archive/_ARCHIVE_INDEX.md)。

## 三、三条硬规则

1. **不丢失、不覆盖**：归档用「复制 + 目标预检 + 复制后 sha256 校验」；目标已存在且内容相同则跳过，
   内容不同直接报错退出，绝不覆盖。工程内来源（`dist/` 根、`dist/release/v1.9.0/`）用同盘移动，
   移动后同样校验哈希，不产生副本。
2. **避免重复副本**：按 sha256 去重，同一内容在归档目录内只存一份；Gradle 构建中间产物
   （`app/build/outputs/`）不与交付包重复归档。
3. **二进制不入库**：`.gitignore` 忽略 `*.apk`、`/dist/output/*`、`/dist/archive/**/*.zip`；
   只保留目录骨架（`.gitkeep`）、清单与 `.md` / `.txt` 交付文档。

## 四、其它工程行为不变

- 未改动 `app/build.gradle.kts`、签名配置、ABI 分包、ProGuard / R8、CI 工作流与任何 Kotlin 源码；
  本约定只新增了一个「事后收集」脚本、目录与文档。
- 历次交付说明（例如 `v1.9.2_播放核心单测加固_2026-11-04.md`）里写着的桌面路径，
  属于**当时的历史记录**，不回改、不伪装成新路径；从本次起以本文为准。
- 桌面 `PureTone-APK/` 的现存 20 个文件是**原件**，已 1:1 无损归档到 `dist/archive/v1.9.2/`；
  为遵守个人文件安全原则，原件暂保留未删除。若需清理，须逐项确认后用回收站方式处理。

## 五、链路验证记录（2026-11-04）

| 验证项 | 结果 |
| --- | --- |
| `:app:assembleRelease` | 两次 BUILD SUCCESSFUL（18s / 23s，55 tasks） |
| 首次运行 `package-release.sh --zip 交付链路验证` | 三包 COPY + 3/3 sha256 一致 + zip + `SHA256SUMS.txt` |
| 二次运行（Gradle 产物未变） | 三包 SKIP、清单刷新，`EXIT=0`（重复出包不卡住） |
| 产物字节变化分支 | 隔离沙箱验证：旧三包 + 旧清单自动归档到 `v1.9.2/<时间戳>_构建更新前输出/`，新包写入，零覆盖、零重复 |
| `:app:detekt` | BUILD SUCCESSFUL，0 smells |
| `apksigner verify --print-certs` | 三包均 `CN=PureTone`（证书 SHA-256 `ca868c77…`），同一把正式密钥、可覆盖安装 |
| 归档完整性 | 27/27 sha256 校验通过，哈希全唯一（0 组重复） |

> 重新构建会让 APK 字节哈希变化（打包时间戳等），但版本 / versionCode / 签名不变；
> 因此新输出包与历史归档包不是同一份字节，不构成重复副本，也不互相覆盖。

## 备注

- 本地 16GB 内存环境完整构建命令：
  `cd /d/PureTone && ANDROID_HOME=/d/devtools/android-sdk /d/devtools/gradle-8.7/bin/gradle.bat :app:assembleRelease --no-daemon`
  （构建前先结束模拟器进程避免 OOM）。脚本本身不调用 Gradle，可在任意一次成功构建后单独运行。

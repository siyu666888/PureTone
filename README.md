# PureTone 极简音乐播放器

> 一个基于 Jetpack Compose 的安卓本地音乐播放器，专注「好看、好听、轻量」。
> 自动扫描本地音乐，支持 3D 翻转歌词、APE/CUE 无损播放、联网补全元数据与歌词、均衡器、听歌统计等。

---

## 📸 界面预览

| 音乐库 · 歌曲 | 专辑网格 | 歌手列表 |
| --- | --- | --- |
| ![音乐库](docs/screenshots/01_music.png) | ![专辑](docs/screenshots/02_albums.png) | ![歌手](docs/screenshots/03_artists.png) |

| 播放页 · 歌词 | 播放页 · 封面 | 播放队列 |
| --- | --- | --- |
| ![播放页歌词](docs/screenshots/04_player_lyrics.png) | ![播放页封面](docs/screenshots/05_player_cover.png) | ![播放队列](docs/screenshots/09_queue.png) |

| 我的 | 听歌数据 | 设置 |
| --- | --- | --- |
| ![我的](docs/screenshots/06_me.png) | ![听歌数据](docs/screenshots/07_stats.png) | ![设置](docs/screenshots/08_settings.png) |

---

## ✨ 功能特性

- **本地媒体扫描**：基于 `MediaStore` 自动扫描设备音乐，经 Room 持久化，支持按歌曲/专辑/歌手/文件夹多维浏览与排序。
- **音乐页四页签**：歌曲 / 专辑 / 歌手 / 文件夹一键切换；专辑页为方形封面**网格墙**，歌手为圆形头像列表，文件夹按目录聚合。
- **二级详情页**：专辑 / 歌手 / 文件夹均可进入详情页（封面 + 标题 + 首数 + 播放全部），列表项支持单击播放、长按操作菜单、加入歌单。
- **常驻迷你播放条**：仅保留「上一首 / 播放暂停 / 下一首」三枚按钮，左右对称，底部细进度线。
- **沉浸式播放页**
  - 封面 3D 翻转露出实时歌词；
  - 旋转 CD 唱盘；
  - 上滑/下滑手势切歌（tanh 阻尼视差过渡，松手过阈值续接归位）。
- **无损与冷门格式**：集成 ffmpeg-kit，支持 **APE / FLAC / WAV** 等格式，APE 在播放前自动转码为 WAV。
- **DLNA 投屏**：SSDP 自动发现局域网内的电视 / 音箱 / Hi-Fi 网播，选中即把本地曲目投过去由渲染器出声（本机同时暂停，避免两头同时响）。
  - MP3 / FLAC / WAV / M4A / AAC 原文件**直投**，秒开；
  - **APE 等渲染器解不了的格式**先在手机端转码为 WAV 再投（复用本地播放的转码缓存——已经播过的曲目再投无需等待，未播过的会显示「正在为投屏转码…」）；
  - 投屏中「播放 / 暂停 / 上一首 / 下一首 / 进度拖动」全部路由到渲染器（含通知栏、锁屏与桌面小部件三个入口），播放页显示的当前曲目与进度与电视一致；
  - 渲染器支持 Range，可在电视端拖动进度；投屏失败会回写原因并自动恢复本地播放。
- **歌词与元数据**
  - 内置歌词解析（LRC / 内嵌歌词）；
  - 联网补全元数据（iTunes 接口）与下载歌词（LRCLIB）。
- **播放控制**
  - 顺序 / 随机 / 单曲循环三态循环；
  - 下一首播放、加入队列；
  - 收藏（红心），跨列表/播放页/迷你播放器实时同步（Room 实时派生真源）。
- **均衡器**：内置多档预设（平坦/流行/摇滚/爵士/古典/人声/低音），可开关。
- **ReplayGain 响度归一**：扫描曲库时自动用 ffprobe 提取 `REPLAYGAIN_TRACK_GAIN` / `REPLAYGAIN_ALBUM_GAIN`（MP3/AAC 取 ID3/APEv2 标签，FLAC/Ogg/Opus 取 Vorbis 注释），按曲目或专辑增益实时施加线性增益并做软限幅防削波；可调 preamp（−12~+12 dB）。
- **交叉淡化（Crossfade）**：双 ExoPlayer 实例「真重叠」——主播放器临近本曲结尾时提前起播备援播放器承载下一首，两路音量此消彼长；时长 0（默认，关）~ 12 秒可调。APE 等需转码格式在边界退化为无缝/曲间停顿。
- **听歌统计**：总播放量、收听歌曲数、累计时长与趋势；「最爱」仅统计**完整播放**的曲目。
- **桌面小部件**（Glance）：桌面播放控制条——封面 + 曲名/歌手 +「上一首 / 播放暂停 / 下一首」，整块点击进入 App。
  - 设置页「桌面」提供一键**添加到桌面**（`requestPinAppWidget`），无需在系统的小组件列表里翻找；
  - 配色跟随 App 主题（含 AMOLED 纯黑），更新由 App 在状态变化时主动推送（不轮询、不空转）；
  - 后台点击走 MediaSession 绑定通道拉起服务，绕开 Android 8+ 的后台启动限制。
- **睡眠定时（三模式）**：「关 / 播完本曲停止 / 自定义分钟」。
  - **播完本曲停止**：当前这首自然播完即暂停，不限时长（夜间听单张专辑最常用）；
  - **自定义分钟**：1 ~ 600 分钟任意值，另有 5/10/15/20/30/45/60/90 分钟常用档位；
  - 到期后（含到期瞬间被杀的残留）重启必然回到「关」，不会残留上次档位。
- **个性化**：倍速、睡眠定时（见上）、繁→简中文归一化、深色/浅色主题（含 AMOLED 纯黑模式）。
- **长按菜单**：曲库列表长按歌曲弹出操作浮层（下一首播放 / 加入队列 / 加入歌单 / 收藏 / 歌曲信息）。

---

## 🧱 技术栈

| 分类 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.2.10（AGP 9.5.1 内置 Kotlin，无 `kotlin.android` 插件） |
| UI | Jetpack Compose (BOM 2025.09.01) · Material 3 |
| 架构 | 单向数据流 · ViewModel + StateFlow · Hilt 2.60.1 依赖注入 |
| 播放 | AndroidX Media3 (ExoPlayer 1.6.1) + media3-session |
| 持久化 | Room 2.7.2 (KSP2，schema v8) · DataStore 1.1.7 |
| 图片 | Coil 2.7.0 |
| 音频处理 | `dev.ffmpegkit-maintained:ffmpeg-kit-min` 6.0.3（本地 vendored AAR，16KB 页对齐） |
| 桌面小部件 | Glance AppWidget 1.1.0 |
| 构建 | AGP 9.5.1 · Gradle Wrapper 9.6.0 · Build Tools 36.0.0 · JDK 17 · compileSdk 35 / targetSdk 35 / minSdk 26 |
| 测试 | Robolectric 4.18 · Turbine 1.2.1（**114 类 / 1142 例全绿**） |
| 静态检查 | Android Lint（`warningsAsErrors` + baseline 存量 0 条）· detekt 1.23.8（baseline 存量 0 条）· 文件行数门禁 ≤ 1200 行（白名单 0 个） |
| CI | GitHub Actions（单测 / Debug+Release 构建 / apksigner 验签 / Baseline Profile 打包断言 **+ 规则有效性校验** / lint / detekt / 文件行数 / **16KB 对齐真门禁**）+ Dependabot 依赖升级 + 每周依赖漂移巡检 |

---

## 📂 项目结构

```
app/src/main/java/com/puretone/player/
├── data/                 # 数据层
│   ├── local/            # Room 实体 / DAO / 数据库
│   ├── media/            # MediaStore 扫描
│   ├── metadata/         # iTunes 元数据补全
│   ├── playlist/         # 歌单
│   ├── repository/       # 仓储（唯一数据真源）
│   └── settings/         # 设置偏好
├── di/                   # Hilt 模块
├── effect/               # 视觉/音频效果
├── lyrics/               # 歌词解析与下载
├── playback/             # ExoPlayer 封装、队列、PlaybackManager
├── ui/
│   ├── components/       # 复用组件（SongRow / 控制图标 / 迷你播放器）
│   ├── screens/          # 各页面（音乐库 / 播放 / 我的 / 统计 / 设置）
│   ├── theme/            # 主题与配色
│   └── permission/       # 运行时权限引导
└── util/                 # 工具（中文归一化、文件等）
```

---

## 🛠 构建与运行

### 环境要求

- **JDK 17**
- **Android SDK**（compileSdk / targetSdk 35、buildTools 35.0.0），在 `local.properties` 设置 `sdk.dir`
- **Gradle Wrapper 8.14.6**（使用仓库自带 `gradlew`，无需单独安装；本地也可用独立 Gradle 8.7）

### 调试构建（无需签名）

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 发布构建（需自备签名）

仓库已配置 `signingConfig`，密钥通过 `local.properties` 读取（该文件与 `*.jks` 均已 gitignore，**不会入库**）。
在 `local.properties` 中填入：

```properties
storeFile=keystore/puretone-release.jks
storePassword=你的密钥库口令
keyAlias=puretone
keyPassword=你的密钥口令
```

然后构建，并把交付包收集到工程内唯一输出目录：

```bash
./gradlew assembleRelease -PcastVariant=<版本>-official
bash scripts/package-release.sh --zip official   # 三包 → dist/output/ + SHA256SUMS.txt + 聚合 zip
# 产物：dist/output/PureTone-v<版本>-{arm64-v8a,x86_64,universal}-release.apk
# Gradle 原生中间产物仍在 app/build/outputs/apk/release/（不用于分发）
```

> 正式包必须显式传 `-PcastVariant=<版本>-official`：该串会写进 DEX，
> 供 `tools/dex_strings.py` 核对包身份（真机取证时先看 `cast-build` 身份串）。

> 产物文件名带版本号（`archivesName` 与 `versionName` 同源，不会漂移）。
> **安装包统一输出到 `dist/output/`，不再输出到桌面**；历史包按版本 / 交付批次归档在 `dist/archive/`。
> 完整约定见 `dist/README_安装包输出与归档约定.md`；`dist/` 同时存放交付摘要与验收证据
> （验机报告 / 截图 / 签名校验记录）。

### 关于 ffmpeg-kit 依赖

`app/libs/ffmpeg-kit-min-6.0-3-maintained.aar`（`dev.ffmpegkit-maintained` 社区维护版，
仅 64 位 ABI、全 16KB 页对齐）为本地 vendored AAR，**已随仓库提交**，
构建时由 `app/build.gradle.kts` 通过 `files("libs/...")` 直接引用，无需联网下载。
原 `6.0-2.LTS`（4KB 页编译）已于 v1.9.2 换库时移除。

### 静态检查（本地同样会拦）

三层门禁，都在本地下次提交前就能跑：

```bash
./gradlew lintDebug            # Android 特有正确性（生命周期 / 权限 / 资源 / API 级别）
./gradlew :app:detekt          # Kotlin 结构与复杂度（未使用导入 / 行长 / 函数过多 / 过长方法）
bash scripts/check-file-size.sh # 文件行数上限 1200 行（detekt 的 LargeClass 管不到 Compose 顶层函数）
```

**三层的分工**：

| 门禁 | 管什么 | 存量 |
| --- | --- | --- |
| lint | Android 特有正确性 | `app/lint-baseline.xml` 存量 0 条（2026-11-04 清零） |
| detekt | Kotlin 结构与复杂度 | `app/detekt-baseline.xml` 存量 0 条（2026-11-04 清零） |
| 文件行数 | 文件规模 | `scripts/check-file-size.sh` 上限 1200 行，白名单 0 个（当前无豁免） |

**清理存量告警的纪律**（务必遵守）：从 baseline 里**删掉对应条目再修代码**，
不要整体删除 baseline 文件重新生成——那会一次性把既有问题全部重新冻结，等于闸门失效。

### 16KB 页大小（发布合规）

Android 15 起支持 16KB 页大小设备，Google Play 自 2025-11-01 起要求
面向 Android 15+（即 **targetSdk 35+**）的应用必须支持。

兼容性要过**两关**，缺一不可：

| 关 | 管什么 | 谁负责 | 本项目现状 |
| --- | --- | --- | --- |
| ZIP 层 | APK 内未压缩 `.so` 的条目偏移按 16KB 对齐 | AGP 打包 + `zipalign` | ✅ 3 个 ABI 包全部通过 |
| ELF 层 | `.so` 内 `PT_LOAD` 段的 `p_align` ≥ 16384 | **第三方库的编译方式**，打包工具管不了 | ✅ 三包合计 48 个 `.so` 全部合规（arm64 11 / universal 26 / x86_64 11，不合规 0 个） |

```bash
# ELF 层检查（自研解析器，零外部依赖，不需要 NDK）
python3 tools/check_16kb_alignment.py app/build/outputs/apk/release/*universal-release.apk

# ZIP 层检查（官方工具）
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 -v 4 <apk>   # Windows 本地为 zipalign.exe
```

> **「zipalign 通过」≠「支持 16KB」**——这是最容易搞混的一点：ZIP 层由 AGP 打包保证，
> ELF 层只取决于第三方库是用 4KB 还是 16KB 页 NDK 编译，打包工具管不了。
>
> 历史：原 `ffmpeg-kit-min 6.0-2.LTS` 的 `.so` 为 4KB 页编译，实测 52 个中 44 个 `p_align=4096`，
> 会让 **APE 转码** 与 **ReplayGain** 在 16KB 页设备上加载失败（详见
> `dist/v1.7.24_P0-3_16KB对齐实测_2026-11-01.md`）。
>
> 自 v1.9.2（commit `9eff959`）起：已换用 `dev.ffmpegkit-maintained:ffmpeg-kit-min:6.0.3`
> 维护版（仅 arm64-v8a / x86_64，全 16KB 对齐），universal 包 **26 个 `.so` 全部合规**；
> 同时 ABI 收敛为 arm64-v8a / x86_64 / universal 三包。CI 的
> 「检查 16KB 页大小对齐（ELF LOAD 段，当前已合规）」已删掉 `continue-on-error`、升级为**真门禁**，
> 不合格（含 `.so` 解析失败无法判定）直接让 CI 失败。
>
> 自分发（不上 Google Play）仍使 Play 的 16KB **政策**不适用，但**设备兼容性**问题已闭环，
> 不再以「只报告不阻塞」的方式保留风险。

### Baseline Profile（性能）

App 附带 Baseline Profile，安装时随包下发，dex2oat 直接 AOT 预编译热路径，
减少冷启动时的 JIT 解释执行开销。

```bash
# 校验：规则里的类是否真的存在于 dex（防止「打包了但不生效」）
bash scripts/verify-baseline-profile.sh

# 真机采集（替换手写占位版，需物理 Android 设备）
scripts/collect-baseline-profile.sh <device-serial>
```

> **采集必须物理设备**：androidx.benchmark 的 Macrobenchmark 明确拒绝模拟器
> （设备指纹含 `generic`、无 `cpufreq`、ART 的类加载时机与真机不同）。
> 在模拟器上采出的 profile 装到真机上**没有收益甚至有害**。
>
> 当前 `baseline-prof.txt` 是**手写占位版**（308 条类级规则，
> `scripts/verify-baseline-profile.sh` 校验 308/308 命中 dex），采集链路已就绪，
> 待具备物理设备时执行采集替换（Macrobenchmark 明确拒绝模拟器）。

---

## 🔌 扩展点

| 扩展 | 说明 |
| --- | --- |
| **元数据补全** | `data/metadata/` 调用 iTunes 接口补全专辑/歌手/封面，可替换数据源。 |
| **歌词下载** | `lyrics/` 通过 LRCLIB 下载 LRC，可接入其他歌词源。 |
| **歌单导入/导出** | 数据层已支持歌单实体，可扩展 M3U 等格式互导。 |
| **均衡器预设** | `ui/screens` 均衡器页预设集中在 `presetLabel()`，新增预设即加一行。 |
| **无损转码** | `playback` 中 ffmpeg-kit 负责 APE→WAV，可扩展更多格式。 |
| **桌面小部件** | `widget/` 下 `PureToneWidget` 输出 UI、`WidgetStateProvider` 提供内容与配色，更新入口统一收敛到 `WidgetUpdater`。 |

---

## 📝 版本历史

> **版本号规则（自 v2.0.0 起）**：各段保持一位数，某段到 9 后再 +1 时进位到前一段并归零；
> 序列 `2.0.0 → … → 2.0.9 → 2.1.0 → … → 2.9.9 → 3.0.0`。
> `v1.9.13` 及更早版本是旧规则下第三段出现两位数的遗留。

| 版本 | 要点 |
| --- | --- |
| v1.1.0 | 播放页四按钮重构、封面 3D 翻转歌词、tanh 阻尼视差切歌、浏览排序、iTunes 元数据、听歌统计看板 |
| v1.1.1 | 倍速/定时选中态、歌曲信息自动填充 + LRCLIB 歌词、封面补全迁移、列表去冗余按钮 |
| v1.1.2 | 设置页排版重构、切歌手势改为上滑/下滑 |
| v1.1.3 | 音乐页「播放全部」、听歌统计→听歌数据、均衡器开关、歌曲编辑实时更新 |
| v1.1.4 | ControlIcons 自绘线形图标、旋转 CD 唱盘、播放全部 FilledTonalButton |
| v1.1.5 | 队列图标、均衡器中文预设、仅完整播放计最爱、繁→简归一化、胶囊输入框 |
| v1.1.8 | 三态循环、红心收藏、移除多余状态色 |
| v1.1.9 | 列表 `key` 防串位、长按操作菜单、收藏跨入口实时同步（Room 派生真源） |
| v1.2.0 | 播放队列浮层精简、循环图标圆角重设计、播放卡片新增「播放顺序/喜欢」对称按钮、切换按钮中性化 |
| v1.3.0 | 迷你播放条精简为三按钮；音乐页新增 歌曲/专辑/歌手/文件夹 四页签；专辑方形封面网格墙；专辑/歌手/文件夹二级详情页 |
| v1.3.1 | 首轮代码审查（CODE_REVIEW）P0~P3 全量修复 |
| v1.3.2 | 修复「进播放页被自动暂停」；SQL 批量参数分片等崩溃防护 |
| v1.4.0 | 第二轮代码审查全量修复：统计虚高、APE 转码期队列竞态、MediaSession 准入白名单、睡眠定时墙钟基准等 |
| v1.4.1 | 歌词高亮居中悬浮；设置页子项高度统一；听歌数据页配色对齐 |
| v1.5.0 | 第三轮复审按序修复：主题行高度统一、续播恢复竞态、APE 锁原子化、均衡器主开关语义、Room v5 索引迁移、封面占位 URI 清理等 |
| v1.6.0 | 桌面小部件（Glance 播放控制条 + 一键添加到桌面 + 后台点击走 MediaSession 通道）；睡眠定时细粒度（播完本曲停止 + 自定义分钟 1~600m，四字段统一归一化）；统计与元数据逻辑下沉 `domain` 用例层 |
| v1.7.0 | P1 收尾：ReplayGain 响度归一 + 交叉淡化（双 ExoPlayer 实例真重叠）+ 智能歌单 + 队列拖拽 |
| v1.7.1 | 修复升级到 v1.7.0 后打开即闪退：协程异常兜底 + `PlaybackService.onCreate` 降级 + R8 keep 扩大到 `androidx.media3.**` |
| v1.7.2 | 修复升级后闪退与曲库空白：Room 观察流全部 `.catch` + 统一异常落盘 + `syncLibrary` 防误删保险（被移除音乐占比 >50% 才拦截） |
| v1.7.3 | 扫描异常落盘 `crash_last.txt`——兜底类改动必须同步给出诊断出口 |
| v1.7.4 | **闪退真根因**：补注册 `MIGRATION_6_7`（Room 编译期不校验迁移注册，只在真机开库那一刻查），并新增 `DatabaseMigrationWiringTest` 直接跑生产 builder 做端到端校验 |
| v1.7.5 | **无声真根因**：`ReplayGainAudioProcessor` 在增益 == 1.0 时旁路标准音频路径（常驻自定义 PCM 处理链在部分 ROM 上导致静音）；智能歌单图标前置 + 三卡均分整屏 |
| v1.7.6 | 设置页新增「实验室」总开关，响度（ReplayGain）迁入其下并由总开关门控 |
| v1.7.7 | DLNA 投屏 M1：SSDP 设备发现 + 本地文件直投 + 投射态路由（播放 / 暂停 / 上下首 / 进度）+ 投屏按钮与设备选择对话框；`DlnaCastManager` 全程 try/catch，投屏失败绝不引发闪退 |
| v1.7.8 | 交叉淡化策略与设置项接线补单测（`CrossfadePolicy` 纯函数化 + `SettingsRepositoryCrossfadeTest`） |
| v1.7.9 | 投屏 M1 止血：next/prev 投屏分流与同步镜像、交叉淡化投屏守卫、pause 同步播放态 |
| v1.7.10 | 合规硬化：明文流量改显式 NSC（仅 DLNA 局域网）+ LGPL 许可证文件不再被剥离 + PURETONE_NOTICE 随包分发。**附模拟器运行期验机报告与 9 张截图**（`dist/v1.7.10_验机报告_2026-10-31.txt`、`dist/verify_1.7.10/`） |
| v1.7.11 | 交叉淡化步进计划抽为 `CrossfadeRamp` 纯函数（+23 例单测） |
| v1.7.12 | DLNA 投屏 M2：APE 等渲染器不支持的格式先转 WAV 再投（复用本地转码缓存），直投/转码按扩展名白名单分流，转码期 UI 显示进度，投屏失败自动恢复本地播放；交叉淡化步进计划抽为 `CrossfadeRamp` 纯函数 |
| v1.7.13 | P1-1 拆分第一刀：媒体项构造 / 服务拉起 / APE 转码入口三块下沉，`PlaybackManager` 1408 → 1304 行 |
| v1.7.14 | P1-1 拆分第二刀：`queueGeneration` / `pendingXxx` / `apeRetryIndex` 收敛为 `PlaybackCommandGate`，同时修掉「删歌后 APE 不转码」「清空后队列冒回来」「bind 重放时追加作废点歌」三个缺陷 |
| v1.7.15 | 投屏 M1 收尾：新增 `CastPlaybackPlayer` 门面，投屏期间把 MediaSession 切到门面，通知栏 / 锁屏 / 小部件的控制真正作用于渲染器（此前投屏中按通知栏操作的是已暂停的本地播放器）；播放态镜像与进度改跟渲染器；投屏路由统一收敛到 `PlaybackManager`（原先 ViewModel 与管理器各判断一次）。设置页「桌面」「存储」并入实验室 |
| v1.7.16 | P1-1 拆分 PlaybackManager 第四刀：交叉淡化状态机与备援播放器下沉到 `CrossfadeCoordinator`（备援引用、淡出方、淡入索引、剩余曲目、渐变协程五份状态一并移出），触发判定与步进计划仍分别委托 `CrossfadePolicy` / `CrossfadeRamp`；修复「角色收尾后备援播放器引用未换位」——连续两次交叉淡化时第二次会拿主播放器当备援，把队列清成单曲 |
| v1.7.17 | P1-1 拆分第五刀：播放会话存档下沉到 `PlaybackSessionStore`，把「保存位置钳到结尾前 500ms」与「恢复时按保存顺序重排（`IN(:ids)` 无序且去重）」两个已踩过的修复点抽出并补测；去抖保存改为写入时才取快照，避免切歌后存档滞后 |
| **v1.8.0** | **聚合发布**（原计划 v1.7.18~v1.7.25 各档工作合并为一次发版）：投屏 M3（队列自动续播 + 断网回退）/ 遗留审查对账（债务=0）/ 静态检查门禁（detekt + 文件行数 + 依赖漂移）/ 交付记录纪律 / Baseline Profile 采集链路 / P0-3 16KB 对齐实测（`tools/check_16kb_alignment.py`）+ LGPL 合规闭环 / **targetSdk 34→35（自分发路线，不上 Google Play）**。详见 `dist/v1.8.0_发布说明_2026-11-01.md` |
| v1.8.1 | 用户反馈修复 + P3 启动：智能歌单卡片重设计为竖向大图标（修复图标显示不全）、移除底部歌曲数量文字、卡片最小高度与间距协调、列表末项底部留白；**P3-1 崩溃诊断导出**（设置页「诊断」分组 + 经 FileProvider 系统分享面板导出崩溃堆栈/设备信息/FFmpeg 最近命令输出，无需存储权限、不接入崩溃上报 SDK）。详见 `dist/v1.8.1_智能歌单UI修复与P3-1诊断导出_2026-11-01.md` |
| v1.8.2 | **P3-2 无障碍与触控目标**：`ControlIconButton` 补 `Role.Button` 语义；`PlayerProgressBar` 加 `progressBarRangeInfo`+`setProgress`（TalkBack 可读/调进度）且触控热区 24→48dp；`MiniPlayer` 长按加 `onLongClickLabel`；播放页均衡器/歌词切换/歌词字号按钮及 MiniPlayer 三按钮触控目标补到 48dp。详见 `dist/v1.8.2_无障碍与触控目标_P3-2_2026-11-01.md` |
| v1.8.3 | **P3-3 歌单 UI 补完**：新增歌单详情页（头部播放全部 + 溢出菜单重命名/导出 M3U/删除 + 歌曲列表点按播放、拖动排序、移除单曲）；`MeScreen` 歌单卡片点击由「直接播放」改为进入详情页；数据层补 `PlaylistDao.reorder`/`updatePosition` + repo/ViewModel 包装。详见 `dist/v1.8.3_歌单UI补完_P3-3_2026-11-01.md` |
| v1.8.4 | **P3-4 字符串资源化**：将 UI 层 314 处硬编码中文字面量抽取到 `res/values/strings.xml`（新增 294 个 `pt_` 资源键），为国际化/合规做准备；采用「一次性脚本 `tools/string_extract.py` 批量抽取 composable 内文案 + 手工 Phase B 收尾非 composable/助手函数/诊断报告」策略；`strings.xml` 条目由 2 → 296。详见 `dist/v1.8.4_字符串资源化_P3-4_2026-11-01.md` |
| v1.8.5 | **ViewModel 消息流资源化收尾 + 设置隐私文案单行化**：引入 `UiMessage` 密封类型（`Res` 资源模板 / `Raw` 动态文本），将 `MetadataViewModel._message` 与 `LibraryViewModel.m3uMessage` 全部改为发射 `UiMessage`（含 `pt_meta_001~012` 共 12 条资源）；M3U 导入/导出提示因含文件路径/歌单名等动态内容保持 `UiMessage.Raw`。设置页「联网补全元数据与歌词」开启提示改为单行文案「已开启：向iTunes与LRCLIB发送信息匹配歌词与元数据」。本地 `compile/lint/test/detekt` 全绿。详见 `dist/v1.8.5_ViewModel消息流资源化_2026-11-02.md` |
| v1.8.6 | **设置结构调整 + 智能歌单图标修复 + 播放自动停止修复**：①「诊断」整组从设置页顶层迁入「实验室」分类（实验室关闭时随父级隐藏），说明文案压缩为单行「仅导出崩溃与设备信息，不含任何个人曲库内容」并加 `maxLines=1`；②修复智能歌单三枚图标完全不渲染——`DrawTransform.scale` 默认以画布中心为轴心，而图标坐标是 0..24 左上角原点，缩放后整枚图标被推出画布外，改为 `pivot = Offset.Zero`；③修复「播放自动停止」：关闭无缝播放时旧实现只 `pause()` 不续播（每播完一首永久停住），新增 `InterTrackGap` 做「暂停→1.2s→续播」并可被用户命令打断；播放失败（非 APE）不再整队停死，改为跳下一首（连续 3 首失败才放弃且保留队列）；④新增 `PlaybackEventLog` 环形事件日志并纳入诊断报告，补齐「自动停止」缺证据链的问题。详见 `dist/v1.8.6_设置诊断迁移与播放自愈_2026-11-02.md` |
| v1.8.7 | **交叉淡化导致「自动暂停 / 一首播完就停」根因修复**：①音频焦点上移到应用层（新增 `AudioFocusController` + `PlaybackAudioFocus`，两个 ExoPlayer 均改为 `handleAudioFocus=false`）——修复「备援起播申请 GAIN → 系统把 AUDIOFOCUS_LOSS 派给主播放器 → 淡出方当场静音」；②`CrossfadeCoordinator.finalize()` 改为「先交权（player 引用/MediaSession/监听器）再复位旧主」，并把空闲停服判据改成「主/备两个实例都不在播且都没有队列」——修复旧主清空瞬间被误判空闲、整个服务被停掉（连带 release 两个播放器、随后 FGS 超时崩溃）；③主监听器按播放器实例创建 + 事件恒等校验，修复换主后残留异步回调把「新曲」误记为完整播放 / 误触发播完本曲停止；④附带：finalize 真取消渐变协程、cancel 归还让出的下一首、新增 crossfade-*/audio-focus-*/service-stop 事件日志。本地 `test/assembleDebug/detekt/lint` 与文件行数门禁全绿，模拟器 4s 淡化连播 4 个边界全程 PLAYING（前后对照见文档）。详见 `dist/v1.8.7_交叉淡化自动暂停修复_2026-11-02.md` |
| v1.8.8 | **交叉淡化后队列索引漂移修复**：根因——换主后新主播放器只持有「剩余尾部」，而代码始终假设「播放器索引 == UI 完整队列绝对索引」(queueOffset=0)，导致①播放页队列高亮错位（点完下一首高亮跑偏）、②第二次淡化的 `maybeStart` 用相对索引去完整队列取 APE 判定项取错歌。修复——在 `onFinalize` 把已播前缀重新 prepend 回新主、维持「主播放器持有完整队列」不变式（queueOffset 恒为 0）；`CrossfadeCoordinator.Host` 增 `queueOffset` 契约、`maybeStart`/`finalize` 改为用绝对下标取判定项与记日志；`PlaybackUiState` 增 `queueOffset` 字段。`CrossfadeCoordinatorTest` 增绝对下标换算契约测试，本地 test/assembleDebug/detekt/lint/文件行数门禁全绿，模拟器 4s 淡化连播 9+ 次 crossfade-start/finalize 严格配对无异常、队列高亮随播放精确归位（CF Test 1→第1行、CF Test 4→第4行）。详见 `dist/v1.8.8_交叉淡化队列索引漂移修复_2026-11-02.md` |
| v1.8.9 | 修复「播完本曲停止」在交叉淡化边界误触发。详见 `dist/v1.8.9_播完本曲停止_交叉淡化边界修复_2026-11-02.md` |
| v1.9.0 | 进度条四态×三交互态重设计（轨道居中、响应式 + 单测/预览）；投屏「未发现设备」权限修复、本地播放音频焦点不再暂停；补提交智能歌单图标配套代码使 CI lint 转绿 |
| v1.9.1 | 四项用户反馈修复：智能歌单「最近添加」图标、投屏真机发现失败（补 `MulticastLock` + 诊断落盘，顺带修诊断假绿灯 / 主线程冻结 / 吞 `CancellationException`）、歌词多渠道下载并校验曲名防给错歌、默认海报方案 B。详见 `dist/v1.9.1_四项反馈修复_交付说明_2026-11-03.md` |
| v1.9.2 | 16KB **ELF 层**兼容闭环：换 `dev.ffmpegkit-maintained:ffmpeg-kit-min:6.0.3`，ABI 收敛为 arm64-v8a / x86_64 / universal；投屏 M3 补齐投射音量 UI；`PlaybackManager` 拆分收尾（1385→933 行、11 协作类）。详见 `dist/v1.9.2_P1-1_PlaybackManager拆分收尾_2026-11-03.md` |
| v1.9.3 | **验证包批次（code 55，未打 tag、未发正式 Release）**：卡片锁屏进度同步与遥控页布局、投屏后台冻结与曲末推进、投屏自动暂停与进度同步、循环模式换主与本地播放音频取证，逐项出 VERIFY 包，文书见 `dist/v1.9.3_*_验证包_*.md` |
| v1.9.4 | 正式发版（code 56）：投屏保活根治 / 进度同步 / 遥控页重设计 / 循环模式换主修复与音频取证；听歌报告阶段 3（周/月/年趋势分桶）；lint baseline 清零、UTC 年报归零修复、失效 Baseline Profile 规则清理、编排层拆分与单测 |
| v1.9.5 | 投屏六轮真机复测修复：曲末推进 / 建立确认 / 死循环 / 误报；`DlnaCastManager` 音量链路与 UI 大文件拆分。详见 `dist/v1.9.5_投屏六轮真机复测修复_发布说明_2026-11-08.md` |
| v1.9.6 | 第三轮审查低风险批（非法 Range 响应码 / JSON `null` 字面量 / SOAP 响应体上限 / NSC 公网明文 / 换曲日志计数）；投屏 H1 卡死兜底、170435 建立确认判据、建立期 UI 兜底与四个大文件拆分。详见 `dist/v1.9.6_后续修复与结构治理_发布说明_2026-11-09.md` |
| v1.9.7 | P2-1 HTTP server 绑 Wi-Fi IP、P2-3 目标校验五轮、`cast-build` 包身份日志；第三轮 P3 低风险批（MIME 注入 / 原子快照 / 一次性实体解码）；R7 取流诊断埋点；DEX 比对工具提升到 `tools/`。详见 `dist/v1.9.7_发布说明_2026-11-12.md` |
| v1.9.8 | A 方案「投屏拖动后静音压降」seek-recover + VERIFY-120805 收尾；三轮 VERIFY 真机取证未复现曲中 STOPPED，保留代码与单测后收口（**收口 ≠ 真机验收**）。详见 `dist/v1.9.8_A方案seek-recover真机取证收口_2026-11-13.md` |
| v1.9.9 | `#220` KSP1→KSP2 独立工作线收口（Room 2.7.0 + Hilt 2.56.2 + `ksp.useKSP2=true`，Kotlin/AGP/Gradle 不动，非功能变更）；第四轮审查 R4-1~R4-6 全量落地 + 第 6 刀异步窗口守护。详见 `dist/v1.9.9_发布说明.md` |
| v1.9.10 | Compose BOM 2024.09.00 → 2025.02.00 + 清理 detekt 未用 import + README 同步；Kotlin / KSP / AGP / 投屏行为零变更 |
| v1.9.11 | 无障碍补完：13 个 UI 源文件 `contentDescription` 审计 + 触控目标达标（均衡器「删除预设」改 `IconButton(48.dp)`）；零依赖、零行为变更 |
| v1.9.12 | AGP 9.5.1 协调升级（Kotlin 2.2.10 / BOM 2025.09.01 / Hilt 2.60.1 / Gradle 9.6.0 / Build Tools 36）+ 16KB **ZIP 层**对齐补全（根因 `zipalign -P 16 4`） |
| v1.9.13 | 投屏链路单测加固（`CastVolumeControllerTest` 7 例防音量漂移）+ `allNetworks` 弃用清理改 `NetworkCallback` + `DlnaTransport` 加 `open` 测试缝 |
| **v2.0.0** | **新版本规则首发**：依赖升级批次（9 项 + ben-manes 插件 id）、响度设置去英文/改开关/关闭态隐藏子设置、Robolectric 4.18 替身适配、`CastNetworkBridge` 网络所有权单测、启动器前景下移 2dp；三次远端 CI 26/26 全绿，5 项资产 Release。详见 `dist/archive/v2.0.0/ReleaseNotes-v2.0.0.md` |
| **v2.0.1** | **第五轮/第六轮复审 19 条落地**：投屏网络所有权同步化 / HTTP 请求行长上限 / 日志串行化 / 同步计数订正 / 响度 Slider 档位 / token 淘汰竞态 / 联网封面 URL 白名单 / 歌单快照直查 / 主源码 `!!` 清零 / CI 供应链最小权限 + Action 固定 SHA；同步抑制提示接线、专辑分组 key 收敛、ReportFormat 五函数补直接断言、诊断导出移出主线程、**周角标「周所属年」口径修正**；三次远端 CI 26/26 全绿，5 项资产 Release。详见 `dist/archive/v2.0.1/ReleaseNotes-v2.0.1.md` |
| **v2.0.2** | **听歌报告 V2「声色叙事」改造**：新增配色单一真源 `ReportPalette` + 数据派生层 `ReportInsights`，周报面积折线/时段条/行为金句/绝对差、月报声音日历热力/周柱/日均/最长连续，海报画幅 1080×2016；**主题随 App 自动切换**（删手动按钮）、页脚改「PureTone·纯净之音」、设置页删 4 处底部说明；合并 PR #26 **区间文案跨年补年 + 月份补零**。114 类/1142 例全绿，远端 CI 26/26，5 项资产 Release。详见 `dist/archive/v2.0.2/ReleaseNotes-v2.0.2.md` |
| **v2.0.3** | **代码质量工作线**（纯重构 + 测试加固，无新功能）：`PosterRenderer` 先测量后布局，极端满配月报保留全部 3 首 Top 且绝不裁切；抽 `isTimeBarEmpty`/`wifiBoundText`/泛型 `pickWifiNetwork` 等纯函数，B5 双网卡选网路由可测（active 优先/cellular 兜底，变异验证非假绿灯）；新增 4 个测试类。118 类/1169 例全绿（较上版 +4 类/+27 例），远端 CI 26/26，5 项资产 Release。详见 `dist/archive/v2.0.3/ReleaseNotes-v2.0.3.md` |

---

## 📋 交付纪律

本项目的交付记录不只是「构建绿灯」。以下是约定，**每次发版都要遵守**：

### 1. 每版必落 `dist/` 交付文档

`dist/` 下每版一份 `v<版本号>_<主题>_<日期>.md`，至少包含：

- **版本号 / versionCode / commit / 日期**；
- **改了什么**（涉及哪些层、为什么这么改）；
- **验证结论**——分三档如实写：
  - ✅ 已做且通过（写清验证方式：单测 / 构建 / 真机 / 模拟器）；
  - ⚠️ 部分验证（写清哪部分没验、为什么）；
  - ❌ **未验证**（写清原因，通常是「沙箱无对应设备」）。

> **「未验证」如实写，比「已验证」更有价值。**
> v1.7.7 投屏版如实写「沙箱无 LAN 设备，无法功能验证」，
> 直接催生了后续 v1.7.9 投屏止血与 v1.7.10 / v1.7.15 的模拟器验收。
> 写成「已验证」，这条改进链就断了。

### 2. 功能改动要带运行期证据

纯「构建通过 + 单测全绿」**不能**证明功能可用。本项目的两个真实教训：

| 症状 | 静态检查全绿却仍然存在 | 真根因 |
| --- | --- | --- |
| 升级后打开即闪退 | ✅ 单测通过、R8 构建通过 | Room 漏注册 `MIGRATION_6_7`——Room **编译期不校验**迁移注册，只在真机开库那一刻查 |
| 放歌完全无声 | ✅ 单测通过、构建通过 | ReplayGain 处理器在增益 == 1.0 时仍常驻，自定义 PCM 处理链在部分 ROM 上静音 |

两者都需要**运行期**验证才能发现。所以：**改了数据层迁移、音频通路、播放生命周期，
就必须做运行期验证并把结论写进 `dist/`**。

### 3. 编译期不检查的东西，用测试补上

Room 迁移注册就是典型：debug 包 + 新装用户 + CI 单测，三重都测不出来。
所以项目里有 `DatabaseMigrationWiringTest`——**直接跑生产 builder** 做端到端校验。
遇到「编译期不校验、运行时才炸」的接线问题，都要补一个同类的接线测试。

### 4. 兜底类改动必须同步给出诊断出口

把崩溃改成静默降级会止住症状，但也让问题变得看不见。
v1.7.1 / v1.7.2 加了兜底，v1.7.3 才补上 `crash_last.txt` 落盘。
**顺序应该是：先能看见，再能降级。**

---

## 📄 许可证

本项目以 **MIT License** 开源。详见 [LICENSE](LICENSE)（如未包含可补）。

---

*PureTone 持续迭代中，欢迎 Issue 与 PR。*

# PureTone v1.3.1 全面代码复审报告

- 复审对象：`b0edc15`（v1.3.1 / versionCode 18）全量源码
- 复审日期：2026-10-24
- 复审方式：4 个并行专项代理（播放/音频/并发、数据层、UI/ViewModel、安全/构建）交叉扫描 + 逐条源码核实
- 覆盖范围：`app/src/main`（51 个 Kotlin 文件）+ 资源 + 清单 + 构建脚本

## 总体结论

代码整体质量良好：上一轮 P0–P3 修复已全部落地且经过构建/测试/真机验证；增量对账、APE 懒转码、续播会话、CASCADE 迁移等核心设计都有充分注释与防御。本轮新发现 **1 个 P0（特定条件下崩溃）、5 个 P1（功能/数据正确性）、11 个 P2、若干 P3/P4**，无一影响主路径的日常使用，但 P0/P1 建议在下一版全部处理。

分级说明：P0 = 崩溃/数据丢失；P1 = 功能错误或统计错误；P2 = 边缘场景错误或明显性能浪费；P3 = 细节/健壮性；P4 = 可维护性建议。

---

## P0 — 必须修复

### P0-1 批量 SQL 参数超限：Android ≤ 11 上大库同步/大会话恢复即崩溃

- **位置**：`SongDao.kt:148-152`（`insertIgnoreAll` / `updateAll`）、`SongDao.kt:58-59`（`getByIds`）；调用方 `MusicRepository.kt:181`（`upsertAll(toUpsert)`）、`PlaybackManager.kt:603` → `MusicRepository.kt:101`
- **问题**：Room 为整个 List 生成**单条多值** INSERT/UPDATE，参数量 = 行数 × 列数（`SongEntity` 约 19 列）。Android ≤ 11 的 SQLite < 3.32，`SQLITE_MAX_VARIABLE_NUMBER` = 999——首次同步约 **52 行**以上新歌/变更行即抛 `SQLiteException`。`getByIds` 的 `IN (:ids)` 同限：保存的播放队列 > **999** 首时，冷启动恢复会话直接崩溃。
- **佐证**：`deleteByPathIn` 调用处已按 500 分片（`MusicRepository.kt:184`，注释「规避 SQLite 参数上限」）——团队已知该坑，但漏掉了 upsert 与会话恢复两条路径。minSdk 26，受影响区间 API 26–30（Android 8–11）。
- **修复**：
  1. `syncLibrary` 中 `toUpsert.chunked(400).forEach { songDao.upsertAll(it) }`（保持同一事务即可）；
  2. `getSongs(ids)` 内部分片 `ids.chunked(900)` 后 `flatten().associateBy { it.id }`，`restoreLastSession` 逻辑不变。

---

## P1 — 高优先级

### P1-2 播放统计虚高：手动切歌/换队列也被记为「播放」

- **位置**：`PlaybackManager.kt:177`（`onMediaItemTransition` 内 `scope.launch { musicRepository.recordPlay(songId) }`）
- **问题**：该回调对所有 `reason` 无差别执行——`SEEK`（用户手动上/下一首）、`PLAYLIST_CHANGED`（设置新队列）、`AUTO`、`REPEAT` 全都写一条 `play_history`（`MusicRepository.kt:210-223`）。后果：用户连点 10 次 next，「最近播放」「累计听歌时长」「活跃天数/日均时长」全部虚高；只听 5 秒就跳走的歌也计入时长。
- **修复**：与 `recordCompletionOnce` 同条件——仅 `reason == MEDIA_ITEM_TRANSITION_REASON_AUTO || REPEAT` 时记录；或改为按播放进度阈值（如进度 > 50%）记录，语义更准。

### P1-3 APE 转码挂起期间，旧队列可覆盖新队列（竞态）

- **位置**：`PlaybackManager.kt:338-389`（`doSetQueueAndPlay`），入口 `:330`（`scope.launch`）
- **问题**：起始曲是 APE 时 `ensureWav` 挂起（长曲可数十秒）。此窗口内用户点另一首歌 → 第二个协程先执行到 `p.setMediaItems(...)`（:375）并开始播放；旧协程恢复后用**旧的** `mediaItems` 再次 `setMediaItems`，把新队列覆盖回去——表现为「点了新歌，播的是旧歌/旧队列」，UI 状态与播放内容不一致。
- **修复**：加 `queueGeneration` 计数器（或持有 `queueJob: Job?`）：`doSetQueueAndPlay` 入口捕获 generation，`setMediaItems` 前校验 `generation == current`，过期则直接 return。

### P1-4 MediaSessionService 无 onConnect 白名单，任意应用可控播放

- **位置**：`PlaybackService.kt:42-49`（`exported="true"` + intent-filter）、`:67`（`onGetSession` 直接返回 session）
- **问题**：设备上任意已安装 App 均可连接该媒体会话：读取当前队列元数据（歌名/歌手/专辑）、控制播放/暂停/跳歌。对本地播放器非灾难性，但属明确的隐私与干扰面。
- **修复**：override `onConnect(controllerInfo)`，按 packageName/UID 白名单放行（自身包名 + Android Auto/Wear 等已知控制器），其余 `MediaSession.ConnectionResult.reject()`；`onGetSession` 保持不变。

### P1-5 values-v27 主题「整体替换」丢失 windowBackground 与透明系统栏

- **位置**：`res/values-v27/themes.xml`、`res/values-night-v27/themes.xml`
- **问题**：同名 `<style>` 在配置限定符目录中是**整体替换**而非合并。API 27+ 设备上 `Theme.PureTone` 只剩 `windowLayoutInDisplayCutoutMode` 一项：`windowBackground` 丢失（冷启动闪平台默认灰而非 `surface_light/surface_dark`）、`statusBarColor`/`navigationBarColor` 丢失透明（系统栏区域露默认色）。深色模式同样受影响（night-v27 只保留了刘海项）。
- **修复**：两个 v27 变体补全基础主题的全部 item（windowBackground/statusBarColor/navigationBarColor），与父主题声明保持一致。

### P1-6 albumArtCache 仅按 albumId 缓存，跨卷冲突

- **位置**：`MediaStoreScanner.kt:81`、`:222-234`
- **问题**：`ALBUM_ID` 是**每卷独立**编号——内部存储与 SD 卡的 albumId 会撞号。缓存键只有 `albumId`（:80 注释却写「跨卷共享」，与实际语义相反）：先扫到「有图」的卷，另一卷同 id 专辑被误判有图 → 写入实际打不开的封面 URI（列表封面空白）；反之误判无图 → 漏记可用封面。
- **修复**：缓存键改为 `"$volumeName:$albumId"`（一行改动）。

### P1-7 ALBUM_ART_URI_PREFIX 只匹配 external 卷，SD 卡占位 URI 被当真实封面

- **位置**：`MusicRepository.kt:360`（前缀常量 `content://media/external/audio/albumart/`）、使用点 `:173`（preservedCoverUri）、`:206`（hasChanged 占位判定）
- **问题**：API 29+ scanner 写入的是 `content://media/<卷名>/audio/albumart/`（`external_primary` 或 SD 卷名），不匹配该前缀 → SD 卡专辑的占位 URI 被判定为「真实封面」而保留（封面补全对 SD 卡失效、坏 URI 长期留存）。
- **修复**：判定改为子串匹配 `coverUri.contains("/audio/albumart/")`，并同步修正 hasChanged 中的占位比对。

---

## P2 — 中优先级

### P2-8 backfillMissingCovers 成功计数与注释矛盾

- **位置**：`MetadataViewModel.kt:275-279`
- **问题**：注释称「仅当确实写入了封面才计入成功」，实际 `runCatching { repo.updateCover(...) }` 丢弃结果，只要 `candidate?.artworkUrl != null` 就 `done++`——写库失败也计入成功（下方 remaining 复查能暴露偏差，但进度数字误导用户）。
- **修复**：`if (runCatching { repo.updateCover(song.id, candidate.artworkUrl) }.isSuccess) done++`。

### P2-9 SongInfoSheet 编辑表单用 remember 而非 rememberSaveable

- **位置**：`SongInfoSheet.kt:74-88`
- **问题**：编辑中的标题/歌手/专辑/搜索词在**进程被系统回收后恢复**时全部丢失（manifest 的 configChanges 已挡住旋屏重建，但挡不住后台杀进程）。
- **修复**：五个 `remember(song.id)` 改 `rememberSaveable(song.id)`（String 均可保存）。

### P2-10 PlayerScreen 根部收集含 2Hz positionMs 的完整 state

- **位置**：`PlayerScreen.kt:122`
- **问题**：`vm.state` 每 500ms 发射（ticker，`PlaybackManager.kt:190-205`），整棵播放页 2Hz 重组。P1-10 已在 `PureToneApp.kt:67` 根部修复（只收 `hasCurrentSong`），播放页自身仍是同一反模式。播放页确实需要进度，但标题/按钮/队列等离散部分被无谓重组。
- **修复**：根部只收离散字段（`currentEntry/isPlaying/isTranscoding/error`），`positionMs` 用独立 `collectAsStateWithLifecycle` 后仅传给 `PlayerProgressBar` 与 `LyricsFace`。

### P2-11 observeSongsByFolder 全库内存过滤

- **位置**：`MusicRepository.kt:96-97`
- **问题**：`observeLibrary().map { list.filter { folderOf(it.path) == folder } }`——每打开一次文件夹页（以及曲库每次变化）都全表过滤。数千首库时明显浪费。
- **修复**：DAO 加 `WHERE path LIKE :folder || '/%' ESCAPE '\'` 参数化查询；或 Repository 缓存 folderOf 映射复用。

### P2-12 M3U 导入后缀匹配可命中错误目录

- **位置**：`PlaylistImportResolver.kt:30-35`
- **问题**：`libPath.endsWith(normalized)` 无路径段边界检查：条目 `music/a.mp3` 会命中曲库 `.../mymusic/a.mp3`（唯一匹配时静默导错歌）。
- **修复**：改为 `libPath.endsWith("/" + normalized) || libPath == normalized`，保证段边界。

### P2-13 playAtIndex 在服务未绑定时是空操作

- **位置**：`PlaybackManager.kt:432`
- **问题**：player 为 null 时写 `PendingPlay(emptyList(), index, 0L)`，bind 后 `doSetQueueAndPlay` 在 `songs.isEmpty()` 处直接 return——冷启动瞬间点队列项无声失败。
- **修复**：置 `pendingRestore = true`（走续播路径）。

### P2-14 睡眠定时在设备重启后按 elapsedRealtime 错位

- **位置**：`SettingsRepository.kt:171`、`:181-185`
- **问题**：deadline 存 `SystemClock.elapsedRealtime()`，重启后该时钟归零：长时间离机重启 → 剩余算出巨大值或 0（定时失效/立即触发）；快速重启则大致正确。代码注释已说明这是取舍，但用户侧表现为「定时莫名失效」。
- **改进**：同时存 wall-clock 到期时刻（`System.currentTimeMillis() + minutes`），恢复时两者取较合理者；或维持现状但在设置页对「已跨重启」状态给出提示。

### P2-15 allowBackup=true 且无备份规则

- **位置**：`AndroidManifest.xml:23`
- **问题**：`adb backup` / 云备份可整体抽取 Room DB（播放历史、歌单）与 DataStore。本地播放器无账号体系，敏感度低，但违反隐私最小化。
- **修复**：`android:allowBackup="false"`，或提供 `dataExtractionRules`（API 31+）/`fullBackupContent` 排除 `databases/` 与 `datastore/`。

### P2-16 ApeTranscoder trimCache 可能删掉在写的 .tmp

- **位置**：`ApeTranscoder.kt:117-126`
- **问题**：LRU 列表 `filter { it.isFile }` 包含并发写入中的 `<id>.wav.tmp`：缓存超 2GB 时可能把正在转码的 tmp 删掉 → 该次转码失败（有失败兜底不崩，但属无谓失败）。
- **修复**：`filter { it.isFile && !it.name.endsWith(".tmp") }`。另 `lockFor`（:43-50）的清理窗口存在极小概率「两把锁同曲双转码」，可用 `ConcurrentHashMap.compute` 语义收紧或维持现状（已注释防御）。

---

## P3 — 低优先级 / 健壮性

| # | 位置 | 问题与建议 |
|---|---|---|
| P3-17 | `PlaybackManager.kt:350` | `songs[startIndex]` 无越界防护，改 `getOrNull(startIndex) ?: return` |
| P3-18 | `PlaybackManager.kt:190-205` | ticker 500ms 循环在暂停时仍空转；可暂停时拉长间隔或完全停走（影响很小） |
| P3-19 | `MusicRepository.kt:221-222` | `recordPlay` 每次都跑 `clearOlderThan` 全表 DELETE；改为按天一次或启动时清理 |
| P3-20 | `PlaybackManager.kt:146` | `saveSessionDebounced` 每次 play/pause 派生新协程；持 Job + cancel 防堆叠 |
| P3-21 | `PlaybackManager.kt:522-529` | `stop()` 整体重置 uiState，把 repeatMode/shuffle/speed 打回默认，与 player 实际状态不一致；当前无调用方（死代码）——删除或与 `clearQueue` 语义对齐 |
| P3-22 | `ApeTranscoder.kt:74-84` | ffmpeg 转码无墙钟超时，损坏 APE 可能长挂；加 `--listen`/外部超时取消 |
| P3-23 | `Theme.kt:63` | `view.context as Activity` 硬转，Compose 预览或非 Activity context 直接崩；改 `as? Activity ?: return@SideEffect` |
| P3-24 | `SettingsScreen.kt:87/94` | `indexOf(...).coerceAtLeast(0)`：旧版本存入的非常规值（如 0.75x）静默显示第一项；给兜底提示或补选项 |
| P3-25 | `PlaylistDao.kt:147-159` | `addSongsAtEnd` 依赖 FK 开启时 songId 有效，与「扫描删除」竞态下可能触发约束异常；写入前 `SELECT` 校验存在性 |
| P3-26 | `SongDao.kt:44-53` | 搜索 LIKE 通配符未转义，用户输入 `%`/`_` 匹配过多；查询词 escape 或改大小写不敏感包含匹配 |
| P3-27 | 多处 UI | 部分图标触摸目标 < 48dp；个别 `nav.navigate` 缺 `launchSingleTop`；播放错误仅靠定时 consumeMessage 消失、无手动 dismiss；StatsScreen「最爱歌曲」点击播放在队列就绪前发导航等竞态 |
| P3-28 | `dist/` | 交付 APK 已入库，仓库体积持续增长；建议 gitignore + 发布页托管，或 Git LFS |

## P4 — 可维护性建议（不影响功能）

- **BrowseScreen 根部 11 个 collect**（`BrowseScreen.kt:119-129`）：均为离散字段可接受，但可打包成单一 `BrowseUiState` 数据类，缩小重组面、统一测试入口。
- **LibraryViewModel 三次 observeLibrary 分组**（albums/artists/folders）：可合并为一次扫描产出三个分组，减少 2/3 的全库遍历。
- **死代码清理**：`PlayerViewModel.refresh/play/pause/playFromLibrary`、`StatsViewModel` 的 `cal`、`ApeTranscoder.isCached`、`PlaybackManager.stop()` 等无调用方入口。
- **hasChanged 故意不比标签字段**（`MusicRepository.kt:202-206`）：依赖「标签变必伴随 dateModified 变」，对不改 mtime 的标签编辑器会漏同步；可在 user_edited 之外加一个低成本兜底（如 title/artist 变化也触发 UPDATE）。
- **多碟片专辑**：`track % 1000`（`MediaStoreScanner.kt:176`）对 disc > 9 的专辑排序不完美；可拼 `disc*1000+track`。
- **构建**：`ksp.useKSP2=false`（KSP1 技术债，Room 2.6.1 + Kotlin 2.0.21 可评估迁移）；无 lint baseline；instrumentation runner 已配但无用例；release Gradle 堆 2GB 可评估调低；keystore 口令仅存本地 `local.properties`（已 gitignore，注意强度，切勿入库）。

## 做得好的方面（保持）

- P1-10 根部高频态隔离到位（`PureToneApp.kt:67` 只订阅 `hasCurrentSong`，进度下沉 MiniPlayer 子树）
- 增量对账 + 空扫安全闸 + 事务包裹 upsert/删除（`MusicRepository.kt:130-194`）
- APE 懒转码体系：每曲 Mutex、tmp 原子 rename、字节数 LRU、按需切歌替换
- Room v4 CASCADE 迁移 + 两段式 upsert，保证「同步不丢歌单/历史/收藏」
- 睡眠定时到期清零防「重启复活」；续播 autoPlay=false 的显式语义
- 联网匹配低置信保护 + 自动填充可撤销（P2-2 已实打实落地）

## 建议修复顺序

1. **v1.3.2（必做）**：P0-1、P1-2、P1-3、P1-4
2. **v1.4.0（功能正确性）**：P1-5、P1-6、P1-7、P2-8～P2-13
3. **后续迭代**：P2-14～P2-16、P3 全量、P4 按收益排期

---

## 修复记录（v1.4.0 / code 20，2026-10-24）

本轮已按上表把 P0–P3 全量落地，P4 只做低风险项，其余明确延期。

### 已修复
- **P0-1**：`MusicRepository.syncLibrary` 的 upsert 改 `chunked(50)`（songs 表 18 列 × 50 < 999）；`getSongs(ids)` 改 `chunked(900)`；新增常量 `UPSERT_CHUNK` / `ID_CHUNK` 并写明推导。
- **P1-2**：`onMediaItemTransition` 只在 AUTO/REPEAT（自然切歌/循环）与「换歌起播」时 `recordPlay`；手动上/下一首（SEEK）不再计入。
- **P1-3**：`doSetQueueAndPlay` 引入 `queueGeneration`，APE 转码挂起恢复后校验代次，过期即放弃；`stop()` 也自增代次。
- **P1-4**：准入改在 **MediaSession.Callback.onConnect**（MediaSessionService 基类没有 onConnect），白名单 = 自身包 + 已知系统控制器 + 持有 `MEDIA_CONTENT_CONTROL` 者，其余 `reject()`；配套新增 `app/lint.xml` 对 Media3 UnstableApi 做项目级 opt-in。
- **P1-5**：`values-v27` / `values-night-v27` 两个主题补全 windowBackground + 透明状态栏/导航栏。
- **P1-6**：`albumArtCache` 键改 `"$volumeName:$albumId"`（同步更新单测里的缓存类型）。
- **P1-7**：`ALBUM_ART_URI_PREFIX` → `isAlbumArtPlaceholder()`（匹配 `/audio/albumart/` 片段，覆盖 SD 卡卷）。
- **P2-8**：backfill 仅 `updateCover` 成功才计数；**P2-9**：SongInfoSheet 表单改 `rememberSaveable`；**P2-10**：新增 `PlayerViewModel.discreteState` / `positionMs`，播放页根部只订阅离散态，进度下沉 `ProgressSection` 与歌词 lambda；**P2-11**：文件夹页改 SQL `LIKE '<folder>/%' ESCAPE '\'`；**P2-12**：M3U 后缀匹配要求 `/` 段边界（新增回归用例）；**P2-13**：`playAtIndex` 未绑定改 `pendingRestore`；**P2-14**：睡眠定时增存墙钟到期时刻（读取优先墙钟，兼容旧数据）；**P2-15**：`allowBackup=false`；**P2-16**：`trimCache` 排除 `.tmp`。
- **P3**：startIndex 越界防护、ticker 暂停时 2s、历史清理限频 1h、`saveSessionDebounced` 复用 Job、`stop()` 不再重置用户偏好、ffmpeg 超时 5min + cancel、Theme 转 Activity 改 `as?`、分段控件 indexOf 不再 `coerceAtLeast(0)`、`addToPlaylist` 先过滤失效 id、搜索/文件夹 LIKE 转义、播放错误可点掉、3 处 navigate 加 `launchSingleTop`。
- **P4（低风险项）**：删除无调用方的 `PlayerViewModel.play/pause`、`ApeTranscoder.isCached`；`TRACK` 不再 `%1000`（保留碟号，多碟排序正确）。

### 核实后撤销/修正的判断
- **P3-21** 原文称 `stop()` 无调用方（死代码）——实际被 `handlePlayerError` 调用，故改为「对齐 clearQueue 语义」而非删除。
- **P4** 原文称 `StatsViewModel` 的 `cal` 为死代码——实际参与 `dayKey()`，保留。
- **P3-28**（dist 入库）：`.gitignore` 已有 `*.apk`，APK 从未入库，仅少量说明 txt/docx 被跟踪，无需处理。

### 主动延期（需产品/更大改动）
- **P4 BrowseScreen 11 个 collect 合并为单一 UiState**、**LibraryViewModel 三次 observeLibrary 分组合并**：纯重构收益，改动面大且本轮已在多处触碰播放/数据层，单独一轮做更可控。
- **P3-27 触摸目标 < 48dp**：涉及多处视觉尺寸调整，需与 UI 一并确认后再改。

### 验证
`:app:testDebugUnitTest`（59 用例 0 失败，含新增 `RestoreGuardTest`、`PlaylistImportResolverTest` 段边界用例）+ `:app:lintDebug` + `:app:assembleRelease`（R8 + lintVitalRelease）全绿。

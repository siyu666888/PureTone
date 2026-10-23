# PureTone 工程审查报告

> 审查范围：`app/src/main` 全部 Kotlin / 资源 / Manifest、Gradle 配置、README 与 `dist/` 交付物。
> 审查日期：2026-10-23
> 总体判断：分层、注释、UI 完成度在个人项目中属于上游水平；但有一批「入口在、实际不生效」或「会写错数据」的问题，建议先修 P0，再考虑新功能。

---

## 0. 验证结果

| 项目 | 结果 |
| --- | --- |
| Debug 编译 | ✅ 通过（隔离 Gradle home 下 `compileDebugKotlin` 成功） |
| 单元测试 | 31 个纯 JVM 用例全部通过；4 个 Robolectric 用例受当前沙箱写用户目录限制（`.robolectric-download-lock` / SQLite native 解压）未完整跑通，不是断言失败 |
| Lint | ❌ **5 errors / 12 warnings**（详见 P1-9 与附录 B） |
| KSP | ⚠️ `playlist_songs.song_id` 未建索引，Room 关系查询会全表扫描 |
| 源码改动 | 无；仅新增本报告 |

> 说明：Robolectric 的失败信息是 `Couldn't create lock file C:\Users\...\.robolectric-download-lock` 与 `FileNotFoundException`，属于本机沙箱环境问题；项目本身在正常开发机上应可跑完。

---

## 1. P0：正确性问题（建议下个版本前必修）

### P0-1 从曲库编辑歌曲信息，会写到「当前正在播放」的歌

**证据**

- [SongInfoSheet.kt:157-160](app/src/main/java/com/puretone/player/ui/screens/SongInfoSheet.kt#L157-L160) 只把标题/歌手/专辑等传给 `playerVm.saveSongInfo(...)`，没有传 `song.id`。
- [PlayerViewModel.kt:123-129](app/src/main/java/com/puretone/player/ui/PlayerViewModel.kt#L123-L129) 使用的是 `state.value.currentSongId`，而不是弹窗里那首歌的 id。

**影响**

- 在音乐页长按歌曲 A →「歌曲信息」→ 保存，而当前正在播放歌曲 B：B 的标题/歌手/封面被改成 A 的编辑内容，A 没变。属于数据写错。
- 如果当前没有播放歌曲，`currentSongId` 为 null，`?: return` 直接返回，弹窗仍然关闭，用户编辑的内容静默丢失。

**建议**

```kotlin
fun saveSongInfo(
    songId: Long,
    title: String, artist: String, album: String,
    coverUri: String?, year: Int
): Boolean
```

让 `SongInfoSheet` 传 `song.id`，保存成功再关闭；失败保留弹窗并提示。再加一个「两首歌 + 无播放」的回归测试。

---

### P0-2 均衡器/重低音/环绕的持久化没有接线，设置页开关也不生效

**证据**

- [AudioEffectController.kt:52-56](app/src/main/java/com/puretone/player/effect/AudioEffectController.kt#L52-L56) 的 `enabled` / `bandLevels` / `bassStrength` / `virtualizerStrength` 都是进程内字段，默认全关/全 0。
- [PlaybackManager.kt:189-207](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L189-L207) 的 `bind()` 只做 `attachToSession`，没有把 DataStore 里的 EQ 设置灌进控制器；[PlaybackManager.kt:222-230](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L222-L230) 的 `applySettings` 只处理倍速/无缝/睡眠定时。
- [EqViewModel.kt:53-57](app/src/main/java/com/puretone/player/ui/EqViewModel.kt#L53-L57) 只在用户操作时调用效果器，初始化时没有把已保存的 `eqBandLevels`、`bassBoost`、`virtualizer` 推给 `AudioEffectController`。
- [SettingsViewModel.kt:33](app/src/main/java/com/puretone/player/ui/SettingsViewModel.kt#L33) 的 `setEqEnabled` 只写 DataStore，完全不碰 `AudioEffectController`。

**影响**

- 冷启动后所有音效恢复默认；用户明明开着 EQ，实际播放没有 EQ，直到进入均衡器页并手动拨动开关。
- 设置页「均衡器」开关只是一个 UI 开关，不产生任何声音变化。

**建议**

- 把 `PureToneSettings` → `AudioEffectController` 的映射收敛为一个 `applySettings(settings)`，由 `PlaybackManager.settingsJob` 在 `bind` 后以及每次设置变化时调用。
- `EqViewModel` 也调用同一个 `applySettings`，避免两套状态。
- `SettingsViewModel.setEqEnabled` 注入控制器并立即生效（或统一改由控制器观察 DataStore）。
- 在 `attachToSession` 重建效果器后重新应用当前设置。

---

### P0-3「启动时续播」「播放顺序」是死配置

**证据**

- [SettingsRepository.kt:41-46](app/src/main/java/com/puretone/player/data/settings/SettingsRepository.kt#L41-L46) 定义了 `resumeOnStart`、`repeatMode`、`shuffle`；[SettingsRepository.kt:145-147](app/src/main/java/com/puretone/player/data/settings/SettingsRepository.kt#L145-L147) 有 setter。
- 全工程搜索不到 `settings.setRepeatMode` / `settings.setShuffle` 的调用；[PlaybackManager.kt:222-230](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L222-L230) 的 `applySettings` 也不读这两个值。
- [PlayerViewModel.kt:176-177](app/src/main/java/com/puretone/player/ui/PlayerViewModel.kt#L176-L177) 的 `tryRestore()` 没有任何调用点；[PlaybackManager.kt:443-451](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L443-L451) 的 `restoreLastSession()` 也没有判断 `resumeOnStart`。

**影响**

- 「启动时续播上次歌曲」开关无论开关都不会在启动时恢复会话。
- 播放顺序（顺序/随机/单曲）每次冷启动都重置；用户的选择不会保存。
- 设置页展示的是「假开关」，降低信任。

**建议**

- `PlaybackManager.bind` 时读取 `repeatMode` / `shuffle` 并应用到 `ExoPlayer` / `uiState`。
- `setRepeatMode` / `setShuffle` 同步写 DataStore。
- 在权限就绪、曲库加载完成后，仅当 `resumeOnStart == true` 调用一次 `restoreLastSession()`（注意 P0-7 的自动播放问题）。
- 为「恢复/不恢复」各加一条测试。

---

### P0-4 睡眠定时会「复活」，且不显示剩余时间

**证据**

- [PlaybackManager.kt:428-436](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L428-L436)：`setSleepTimer(minutes)` 用 `delay(minutes * 60_000)` 后直接 `pause()`，没有把设置清零。
- [PlaybackManager.kt:226-229](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L226-L229)：`applySettings` 在 `appliedSleepMin != s.sleepTimerMinutes` 时重新计时；`appliedSleepMin` 初始为 -1。
- [SettingsRepository.kt:145](app/src/main/java/com/puretone/player/data/settings/SettingsRepository.kt#L145)：定时值只写不清。

**影响**

- 定时 15 分钟到了，音乐暂停，但设置仍然是 15m；**进程重启后**首次绑定播放服务时，`appliedSleepMin` 回到 -1，会再次启动 15 分钟倒计时（同一进程内 manager 是单例，单纯重绑服务不会重计时）。
- 进程被杀后，剩余时间丢失；重新启动可能按完整时长重新计时。
- UI 没有剩余时间，用户无法知道定时是否还在生效。

**建议**

- 存「到期时间戳」而不是「分钟数」；触发后把设置恢复为 0。
- 用 `SystemClock.elapsedRealtime()` 或 `AlarmManager` 计算剩余时间，进程重建后仍能恢复。
- 设置页/播放页展示「剩余 12:34」，并提供「取消定时」。

---

### P0-5 APE 首播会转码整个队列；WAV 缓存无大小上限

**证据**

- [PlaybackManager.kt:255-261](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L255-L261) 在 `setQueueAndPlay` 里调用 `buildMediaItems(songs)`；[PlaybackManager.kt:534-542](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L534-L542) 对队列里的**每一首** APE 都 `ensureWav`，全部完成后才 `setMediaItems` + `play`。
- [PlaybackManager.kt:579-587](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L579-L587) 的 `warmUpFollowingApe` 又把「后面所有 APE」全部转码一遍。
- [ApeTranscoder.kt:87-90](app/src/main/java/com/puretone/player/playback/ApeTranscoder.kt#L87-L90) 的缓存上限是 **100 个文件**，不是字节数；[ApeTranscoder.kt:40-64](app/src/main/java/com/puretone/player/playback/ApeTranscoder.kt#L40-L64) 直接写目标文件，没有并发锁，也没有临时文件。

**影响**

- 一个 20 首 APE 的专辑，点「播放全部」要等 20 次 FFmpeg 转码才开始出声；期间 UI 只有一个 `isTranscoding` 圈。
- WAV 体积约为每分钟 10 MB（16bit/44.1kHz/立体声）：一首 5 分钟约 50 MB，100 首约 5 GB；一小时现场录音单首可到 600 MB。`cacheDir` 可能被撑爆，也可能被系统静默清理。
- 两个入口（首播 + warmUp）可能同时转码同一首歌，写出半截 WAV 被当成缓存命中。
- `FFmpegKit.execute(command)` 用字符串拼路径，文件名里带 `"` 时命令会被截断/注入参数。

**建议**

- 只转码「当前曲」；最多预取「下一首」；不要为整个队列预转码。
- 缓存按**总字节数**限制（例如 1–2 GB）+ LRU；在设置页提供「清理 APE 缓存」和缓存大小显示（`ApeTranscoder.cacheSize()/clearCache()` 已有，但没用）。
- 每首歌一把 `Mutex` 或 in-flight map；先写 `.tmp`，成功后原子 rename。
- 用 `FFmpegKit.executeWithArguments(...)`，避免手工拼命令。
- 考虑转成 FLAC/ALAC/AAC 而不是 WAV（体积可降 50%–70%），或改用自定义 `Extractor/Renderer` 直接播 APE。

---

### P0-6 冷启动每次都可能全库重扫，且可能并发扫两次

**证据**

- [MainActivity.kt:27-33](app/src/main/java/com/puretone/player/MainActivity.kt#L27-L33)：`if (libVm.library.value.isEmpty()) libVm.scan()`。
  `library` 是 `stateIn(WhileSubscribed, emptyList())`，冷启动时 Room 还没发出第一帧，`value` 必然是空列表——**曲库非空也会触发扫描**。
- [LibraryViewModel.kt:147-149](app/src/main/java/com/puretone/player/ui/LibraryViewModel.kt#L147-L149) 已经有一个正确的 `autoScanIfEmpty()`（用 `countSongs()` 判断），BrowseScreen 也在调用它；MainActivity 这段是重复且错误的。
- `scan()` 没有 `if (isScanning) return` 保护。空库时 MainActivity 的 `scan()` 先置 `isScanning=true`，Browse 的 `autoScanIfEmpty` 可能在挂起的 `countSongs()` 返回后仍然通过检查，形成两次并发同步。

**影响**

- 每次冷启动都要扫描 + 对账 + 校验所有专辑封面，启动变慢、耗电。
- 两次 sync 并发时，`upsertAll`/`deleteByPathIn` 交错，可能出现重复写或中间态。

**建议**

- 删掉 MainActivity 里的 `LaunchedEffect`，只保留 `autoScanIfEmpty()`。
- `scan()` 增加互斥（`Mutex` 或 `if (isScanning.value) return`）。
- 用持久化的 `lastScanAt` 或「最后一次扫描结果」判断，而不是拿 StateFlow 的初始值当真实库状态。
- 把 `SyncResult` 展示出来（新增/移除/跳过），`lastSync` 目前完全没被 UI 使用。

---

### P0-7 续播恢复存在竞态，且会丢失队列顺序/重复项

**证据**

- [PlaybackManager.kt:443-451](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L443-L451)：

  ```kotlin
  setQueueAndPlay(songs, ...)
  player?.pause()
  ```

  `setQueueAndPlay` 内部是 `scope.launch { doSetQueueAndPlay(...) }`。非 APE 队列可能同步执行到 `play()` 再返回，APE 队列会在 `ensureWav` 处挂起，`pause()` 先执行、`play()` 后执行——恢复后是否自动播放取决于队列里有没有 APE。

- [SongDao.kt:57-58](app/src/main/java/com/puretone/player/data/local/dao/SongDao.kt#L57-L58) 的 `getByIds` 是 `SELECT * FROM songs WHERE id IN (:ids)`，**没有 ORDER BY**；[Room 生成的 SQL](app/src/main/java/com/puretone/player/data/local/dao/SongDao.kt#L57-L58) 也确认没有排序。恢复时按数据库返回顺序重建队列，保存的队列顺序和 `index` 会错位；重复歌曲会被 `IN` 去重。

**建议**

- `doSetQueueAndPlay(..., autoPlay: Boolean)` 显式控制是否 `play()`；恢复时传 `false`，在 `prepare()` 后暂停。
- 恢复时按 `saved.songIds` 的顺序 `mapNotNull { byId[it] }` 重建，保留重复项；`getByIds` 只作为批量查询，不要依赖返回顺序。
- 加测试：`[A,B,C]` 保存后恢复仍为 `[A,B,C]`，`index=1` 时当前曲为 B。
- `stop()` 应清空已保存会话，否则用户「停止」后下次启动/点击播放会恢复旧队列。

---

### P0-8 编辑完歌曲信息，播放页/迷你条仍显示旧标题和旧封面

**证据**

- [PlayerScreen.kt:113](app/src/main/java/com/puretone/player/ui/screens/PlayerScreen.kt#L113) 用 `state.currentEntry` 展示标题/歌手/封面；[PlayerScreen.kt:175-185](app/src/main/java/com/puretone/player/ui/screens/PlayerScreen.kt#L175-L185) 用的是 `entry.title/artist`。
- `MiniPlayer` 同样使用 `state.currentEntry`（[PlayerComposables.kt:540](app/src/main/java/com/puretone/player/ui/components/PlayerComposables.kt#L540)）。
- `QueueEntry` 只在设置队列时构建一次（[PlaybackManager.kt:589-597](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L589-L597)）。
- 保存/自动填充后只刷新 `_currentSong`（[PlayerViewModel.kt:131-137](app/src/main/java/com/puretone/player/ui/PlayerViewModel.kt#L131-L137)），没有更新 `PlaybackManager.uiState.queue`。

**影响**

- 用户改完标题/封面，播放页和迷你条仍显示旧值；README 的「歌曲编辑实时更新」并不成立（只有 SongInfoSheet 内部字段和 DB 更新了）。
- 从 Browse 给当前播放曲改信息后，要重新 setQueue 才会刷新。

**建议**

- 让播放页直接观察 Room 的 `currentSong`（`PlayerViewModel.currentSong` 已有），或者让 `PlaybackManager` 在 `currentSong` 变化时更新对应 `QueueEntry`。
- 统一「当前曲展示数据」的来源，避免 `QueueEntry` 快照和 `SongEntity` 双份状态。

---

## 2. P1：性能、稳定性与数据一致性

### P1-1 队列操作会留下错位的 `queueIndex`，`enqueue` 在未起播时直接丢歌

- [PlaybackManager.kt:339-344](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L339-L344)：`removeFromQueue(index)` 只改 `queue`，不改 `queueIndex`。删除当前曲之前的条目后，`player.currentMediaItemIndex` 已经左移，但 UI 的 `currentEntry` 还按旧索引取——播放页会显示错歌。目前 UI 没有暴露移除按钮，但方法已公开，后续一接就会踩。
- [PlaybackManager.kt:283-293](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L283-L293)：`enqueue` 在 `player == null` 时只 `ensureServiceStarted()` 然后 `return`，歌曲被静默丢弃；`playAtIndex` 同理（[321-336](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L321-L336)）。
- [PlaybackManager.kt:346-349](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L346-L349) 的 `clearQueue()` 和 [382-387](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L382-L387) 的 `stop()` 用 `PlaybackUiState()` 重置整个状态，把 `repeatMode` / `shuffle` / `speed` 也重置了；播放器实际状态和 UI 显示会不一致。
- `songCache`（[PlaybackManager.kt:107](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L107)）只写入、从不读取，还会随队列无限增长。

**建议**

- 以 `player.currentMediaItemIndex` 为准派生 UI 索引，或在每次队列变更后同步修正 `queueIndex`。
- 增加 `pendingEnqueue` / `pendingPlayAt`，bind 后统一执行；UI 给出「已加入队列」反馈。
- 只重置瞬时字段，保留用户模式/倍速；停止时调用 `clearPlaybackSession()`。
- 删除 `songCache`。

---

### P1-2「完整播放」计数会重复，统计口径也不一致

- [PlaybackManager.kt:141-151](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L141-L151)：`onMediaItemTransition` 在 `REASON_AUTO` 和 `REASON_REPEAT` 时都记一次完成。单曲循环时每次循环都会走 `REASON_REPEAT`，`play_count` 会按循环次数暴涨；`handlePlaybackEnded`（[495-503](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L495-L503)）还会补记最后一首，和过渡回调之间需要去重。
- [MusicRepository.kt:176-197](app/src/main/java/com/puretone/player/data/repository/MusicRepository.kt#L176-L197)：`recordPlay` 每次开始播放插一条 history，`recordCompletion` 才加 `play_count`。
- [StatsDao.kt:19-24](app/src/main/java/com/puretone/player/data/local/dao/StatsDao.kt#L19-L24)：累计听歌时长按「每次播放加整首时长」估算，跳过/拖动也会算完整首。

**影响**

- 「最爱歌曲/歌手」会被单曲循环和重复恢复放大。
- 「总播放」（history 行数）和「完整播放」（play_count）是两个口径，UI 没有区分；「累计时长」会虚高。

**建议**

- 只在真正播完（位置接近时长或 `STATE_ENDED`）时记完成；单曲循环是否计数需要产品明确（建议每次完整播放只计一次，或不计循环）。
- 统一统计口径：`started` vs `completed`；在 UI 上分别命名。
- 若要精确听歌时长，周期性采样 `currentPosition` 增量，而不是开始即加整首。
- 把 `play_history` 清理由每次播放触发改为定期/批量，并加索引（见 P1-3）。

---

### P1-3 扫描对账、搜索、分组都有主线程重活；数据库写放大

**证据**

- [LibraryViewModel.kt:136-140](app/src/main/java/com/puretone/player/ui/LibraryViewModel.kt#L136-L140) 在 `viewModelScope`（Main）里调 `repo.syncLibrary()`；[MusicRepository.kt:115-172](app/src/main/java/com/puretone/player/data/repository/MusicRepository.kt#L115-L172) 只把 `scanner.scan()` 切到 IO，之后的 `associateBy`、`map`、`toEntity`、集合差集都在调用线程（Main）执行。
- [LibraryViewModel.kt:86-96](app/src/main/java/com/puretone/player/ui/LibraryViewModel.kt#L86-L96) 的 `searchResults` 在 Main 上对全库 `filter`，没有 debounce；`SongDao.search` 的 SQL 版本反而没被使用。
- [LibraryViewModel.kt:104-117](app/src/main/java/com/puretone/player/ui/LibraryViewModel.kt#L104-L117) 的 `albums` / `artists` / `folders` 各自收集一次 `observeLibrary()` 并在 Main 上全量分组；BrowseScreen 即使只看一个页签，也同时收集三个。
- [MusicRepository.kt:90-91](app/src/main/java/com/puretone/player/data/repository/MusicRepository.kt#L90-L91) 的 `observeSongsByFolder` 先取全库再在内存过滤。
- [MusicRepository.kt:143-165](app/src/main/java/com/puretone/player/data/repository/MusicRepository.kt#L143-L165) 每次 sync 对**所有**扫描结果 `upsertAll`（`REPLACE`），即使文件没变；会触发全表失效、所有 Flow 重发、UI 重新分组。而且 upsert 与按路径删除不在同一事务里。

**影响**

- 5000–10000 首的库在扫描、输入搜索、切页签时明显掉帧；每次扫描把整库重写一遍，Room 失效风暴。
- 文件只在路径不变时保留 id；文件移动/重命名后旧行被删，歌单/历史的 `song_id` 变成悬空引用（没有外键，见 P1-4）。

**建议**

- `syncLibrary` 整体 `withContext(Dispatchers.IO/Default)`；搜索用 `SongDao.search` + `debounce(250)`；分组用 `flowOn(Default)` 且只计算当前页签，或合并成一个 `LibraryUiState`。
- 增量对账：按 `path` 找到旧行后，比较 `dateModified/size`，只对新增/变化行写入；已有行用 `UPDATE` 而不是 `REPLACE`，保留 id 和统计数据。
- 把 upsert + 删除包进 `@Transaction`（`withTransaction`），避免中途失败留下半套数据。
- 文件夹/专辑/歌手详情优先走 SQL（GROUP BY / WHERE），不要全库内存过滤。

---

### P1-4 歌单顺序实际不按 `position`，删除歌单会留下孤儿关联行

- [PlaylistWithSongs.kt:15-23](app/src/main/java/com/puretone/player/data/local/dao/PlaylistWithSongs.kt#L15-L23) 用 `@Relation` + `Junction`；[PlaylistDao.kt:35-38](app/src/main/java/com/puretone/player/data/local/dao/PlaylistDao.kt#L35-L38) 注释写「按 position 排序」，但 Room 生成的查询只 `SELECT songs... FROM playlist_songs _junction INNER JOIN songs`，**没有 ORDER BY position，也没有取 position**。实际顺序取决于 SQLite 返回顺序，不是用户/导入设定的顺序。
- [PlaylistSongEntity.kt:12-27](app/src/main/java/com/puretone/player/data/local/entity/PlaylistSongEntity.kt#L12-L27) 没有 `song_id` 索引，KSP 已警告。
- [MusicRepository.kt:252-254](app/src/main/java/com/puretone/player/data/repository/MusicRepository.kt#L252-L254) 的 `deletePlaylist` 只 `playlistDao.delete(playlist)`；`playlist_songs` 没有外键级联，关联行会永久残留。
- `addSongsAtEnd` 不更新 `playlists.updated_at`，「我的」按更新时间排序会失真。

**建议**

- 用显式 JOIN 查询（`ORDER BY ps.position`）替代 `@Relation`，或在 Kotlin 里按 position 排序。
- 给 `playlist_songs.song_id` 加索引；给 `playlist_songs` / `play_history` 加外键 + `ON DELETE CASCADE`（或删除歌单时显式 `clearSongs`）。
- 增删歌曲后更新 `updated_at`；补歌单详情页，支持重命名/删除/排序/移除单曲。

---

### P1-5 DataStore 读取没有异常兜底，EQ 滑杆存在写入风暴和丢更新

**证据**

- [SettingsRepository.kt:114-133](app/src/main/java/com/puretone/player/data/settings/SettingsRepository.kt#L114-L133) 的 `dataStore.data.map` 没有 `catch { IOException -> emit(emptyPreferences()) }`；DataStore 文件损坏/不可读时异常会传到 `stateIn` / `collect`，可能直接崩。
- [DataModule.kt:55-60](app/src/main/java/com/puretone/player/di/DataModule.kt#L55-L60) 没有 `ReplaceFileCorruptionHandler`。
- [EqViewModel.kt:59-65](app/src/main/java/com/puretone/player/ui/EqViewModel.kt#L59-L65) 的 `setBandLevel` 每拖动一次就 `launch` 一个协程读取 `settingsState.value` 再写 DataStore；快速拖动时多个协程读到同一份旧列表，后写覆盖先写，最终可能只保留最后一个 band 的改动。
- [EqScreen.kt:90-96](app/src/main/java/com/puretone/player/ui/screens/EqScreen.kt#L90-L96) 的 Slider `onValueChange` 直接写库，整个拖动过程会产生几十上百次磁盘写入。

**建议**

- `dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }`；创建 DataStore 时加 corruption handler。
- EQ 用本地 `mutableStateOf`/`mutableStateListOf` 驱动 UI，`onValueChangeFinished` 或 250ms debounce 再落库；写入串行化（Mutex/Channel）。
- 对 DataStore 写入统一 `runCatching` 并把错误反馈到 UI，避免协程异常崩溃。

---

### P1-6 MediaStore 扫描的覆盖面与效率

- [MediaStoreScanner.kt:93-99](app/src/main/java/com/puretone/player/data/media/MediaStoreScanner.kt#L93-L99) 只查 `EXTERNAL_CONTENT_URI`（主外部存储），SD 卡/多卷设备上的音乐不会被收录；应使用 `MediaStore.getExternalVolumeNames()` 逐卷查询。
- [MediaStoreScanner.kt:67](app/src/main/java/com/puretone/player/data/media/MediaStoreScanner.kt#L67) 依赖已废弃的 `DATA` 列；Android 10+ 更适合 `RELATIVE_PATH` + content URI。
- 没有过滤 `IS_PENDING` / `IS_TRASHED`，可能扫到正在写入或已进回收站的条目。
- `EXCLUDED_DIRS` 包含 `Sounds` / `Telegram Audio` 这类泛目录，可能把用户放在 `Sounds` 下的正常音乐误杀；建议做成可配置白名单，或只按录音 App 的精确路径排除。
- [MediaStoreScanner.kt:173-181](app/src/main/java/com/puretone/player/data/media/MediaStoreScanner.kt#L173-L181) 对**每个专辑** `openInputStream` 读 1 字节验证 albumart；专辑多时扫描会非常慢，而且每次扫描都重来。
- [MediaStoreScanner.kt:154-164](app/src/main/java/com/puretone/player/data/media/MediaStoreScanner.kt#L154-L164) 的 `countCandidates()` 是第二次全表查询，只为算「跳过多少」；可以合并或省略。

**建议**

- 遍历所有外部卷；加 `IS_PENDING=0`、`IS_TRASHED=0`（API 30+）过滤。
- 封面改为「先入库，Coil 异步加载/显示占位」；或对 albumart 做持久化负缓存，避免每次扫描逐个打开。
- 做增量扫描：只查询 `DATE_MODIFIED > lastScan` 的文件；对已入库且未变化的条目直接跳过。
- 把「跳过数量」改为一次查询的统计，或不再展示。

---

### P1-7 播放页/统计页顶部多出一圈系统栏高度（双重 inset）

**证据**

- [PureToneApp.kt:77-122](app/src/main/java/com/puretone/player/ui/PureToneApp.kt#L77-L122) 的最外层 `Scaffold` 使用默认 `contentWindowInsets`（systemBars），`NavHost` 又 `padding(padding)`；里层页面（[BrowseScreen.kt:168](app/src/main/java/com/puretone/player/ui/screens/BrowseScreen.kt#L168)、[PlayerScreen.kt:121-134](app/src/main/java/com/puretone/player/ui/screens/PlayerScreen.kt#L121-L134)、[StatsScreen.kt:53-70](app/src/main/java/com/puretone/player/ui/screens/StatsScreen.kt#L53-L70)、GroupDetail/Eq）又各自有 `TopAppBar`/`Scaffold`，它们的 `windowInsets` 再加一次状态栏。
- 从截图可见：「音乐」「专辑」「听歌数据」「正在播放」标题上方有约 24dp 的额外空白；Settings/Me 没有 TopAppBar，反而正常。

**建议**

- 只让一层负责 inset：外层 `Scaffold(contentWindowInsets = WindowInsets(0))`，由各页 `TopAppBar` 处理状态栏；或反过来外层保留 inset，内层 `TopAppBar(windowInsets = WindowInsets(0))`。
- 对没有 TopAppBar 的 Settings/Me 显式 `statusBarsPadding()`，避免修完外层后它们贴顶。
- NavHost 使用 `Modifier.padding(padding).consumeWindowInsets(padding)`，防止子级重复消费。

---

### P1-8 通知权限被当作硬门槛，且授权后不会自动恢复

- [PermissionGate.kt:45-55](app/src/main/java/com/puretone/player/ui/permission/PermissionGate.kt#L45-L55) 把 `POST_NOTIFICATIONS` 和音频权限一起放进 `required`，用户拒绝通知就完全进不了 App；Android 13+ 没有通知权限时前台服务仍可运行，只是通知不展示（或由系统媒体控制接管）。
- [PermissionGate.kt:57-67](app/src/main/java/com/puretone/player/ui/permission/PermissionGate.kt#L57-L67) 的 `granted` 只在首次组合时计算；用户点「前往系统设置」授权后返回，没有 `ON_RESUME` 重查，页面仍停在授权引导。

**建议**

- 只把音频读取作为硬门槛；通知权限单独请求，并允许「暂不授权，继续使用」。
- 用 `LifecycleEventObserver` 在 `ON_RESUME` 重查权限；永久拒绝时展示「去设置」+ 解释。
- 如果确实依赖通知，至少把「拒绝通知 → 无法使用」改成可理解的说明，而不是笼统的「需要音乐访问权限」。

---

### P1-9 Lint 本身已失败：5 个 error 会卡住 CI / 发布门禁

**证据（`lintDebug` 实际输出）**

1. `values/themes.xml:10` 与 `values-night/themes.xml:8`：`android:windowLayoutInDisplayCutoutMode` 需要 API 27，而 `minSdk=26`（[themes.xml:10](app/src/main/res/values/themes.xml#L10)、[values-night/themes.xml:8](app/src/main/res/values-night/themes.xml#L8)）。
2. [PlaybackManager.kt:121](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L121)、[193](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L193)、[564](app/src/main/java/com/puretone/player/playback/PlaybackManager.kt#L564)：Media3 的 `UnstableApi` 未 opt-in（`onAudioSessionIdChanged`、`player.audioSessionId`、`setDurationMs`）。

**建议**

- 把 cutout 属性移到 `values-v27/themes.xml` / `values-night-v27/themes.xml`，或 `tools:targetApi="27"`。
- 在 `PlaybackManager` 上加 `@OptIn(UnstableApi::class)`（或 `lint.xml` 配置项目级 opt-in），并锁定 Media3 版本升级时的回归。
- 在 `build.gradle.kts` 加 `lint { abortOnError = true; warningsAsErrors = false }`，CI 跑 `lintDebug`；否则这 5 个 error 会一直潜伏到某次发布。

---

### P1-10 根级 2Hz 重组 + 播放页 60fps 旋转，和「轻量」目标冲突

- [PureToneApp.kt:64-65](app/src/main/java/com/puretone/player/ui/PureToneApp.kt#L64-L65) 在应用根部收集完整 `PlaybackUiState`（含 `queue: List`，是不稳定类型）；`PlaybackManager` 的 ticker 每 500ms 更新一次 position，导致根部持续重组，`Scaffold`/`NavHost` 也随之产生重组开销，即使用户不在播放页。
- [PlayerScreen.kt:404-414](app/src/main/java/com/puretone/player/ui/screens/PlayerScreen.kt#L404-L414) 的 CD 旋转用 `while(true) { animateTo(...) }`，播放期间持续 60fps 重绘；这会放大低端机/软件渲染模拟器上的掉帧（交付说明里记录了 6.5s/帧 的 ANR 风险）。

**建议**

- 根部只收集 `currentSongId` / `isPlaying` 等离散字段；MiniPlayer 的进度单独用 `derivedStateOf` 或独立 StateFlow，播放页内部再收 position。
- CD 旋转在页面不可见/暂停时停止；可降低到 30fps、或用 `RenderEffect`/缓存层；同时尊重系统「移除动画」设置。
- 给 `PlaybackUiState` 的 `queue` 换成 `ImmutableList`/`@Immutable` 包装，减少无谓重组。

---

## 3. P2：体验、功能完整性与可访问性

### P2-1 均衡器：10 段 UI 与设备实际段数不匹配，预设胶囊会溢出

- [EqScreen.kt:78-98](app/src/main/java/com/puretone/player/ui/screens/EqScreen.kt#L78-L98) 固定渲染 10 段（31Hz–16kHz）；[AudioEffectController.kt:154-161](app/src/main/java/com/puretone/player/effect/AudioEffectController.kt#L154-L161) 直接对 band 0..9 调 `setBandLevel`。大多数手机 `Equalizer.numberOfBands` 是 5，band≥5 会抛 `IllegalArgumentException`；`runCatching` 包住整个循环，于是从第一个越界 band 开始，后面的频段全部没应用。
- [EqScreen.kt:64-73](app/src/main/java/com/puretone/player/ui/screens/EqScreen.kt#L64-L73) 的 7 个预设胶囊放在不可滚动/不换行的 `Row` 里；360dp 屏上总宽约 460–500dp，末尾「低音」等会被裁切。
- [AudioEffectController.kt:112-115](app/src/main/java/com/puretone/player/effect/AudioEffectController.kt#L112-L115) 的 `setEnabled(false)` 只关 `Equalizer`；`bassBoost` / `virtualizer` 从未显式 `setEnabled`，主开关无法统一关闭三者（是否生效还依赖厂商默认）。
- `isAvailable` / `bandCount()` 都没有在 UI 使用：设备不支持时用户仍能拖动滑杆，没有任何反馈。

**建议**

- 用 `numberOfBands` + `getCenterFreq` 把 UI 的 10 段映射到设备实际频段（就近合并），或直接展示设备支持的段数；越界段禁用并标注。
- 预设改成 `LazyRow` 或 `FlowRow`。
- `setEnabled` 同时 `enabled = value` 到三个效果（或按 `needsEffects` 统一门控），并在 UI 显示「设备不支持」。
- 手动改任意 band 后把预设切换为 `CUSTOM`，不要继续高亮旧预设。

---

### P2-2 联网元数据：自动填充过于激进，补全封面会触发限流

- [MetadataViewModel.kt:95-128](app/src/main/java/com/puretone/player/ui/MetadataViewModel.kt#L95-L128) 的 `autoFill` 直接把首个搜索结果写入 DB（并标记 `user_edited=1`），没有相似度/时长/歌手校验，也没有确认/撤销；一个错误匹配会被永久保留，后续扫描也不会再纠正。
- [MetadataViewModel.kt:190-213](app/src/main/java/com/puretone/player/ui/MetadataViewModel.kt#L190-L213) 的 `backfillMissingCovers` 对最多 200 首逐首串行请求 iTunes，没有并发上限/节流/取消；iTunes 限流时大部分会失败，且一次要跑很久。成功计数也没有检查 `updateCover` 是否真的成功。
- [ItunesMetadataProvider.kt:36-40](app/src/main/java/com/puretone/player/data/metadata/ItunesMetadataProvider.kt#L36-L40) 没有 `User-Agent` / `Accept-Language`，中文查询可能返回英文结果；`parse` 也不做过滤。
- [SongInfoSheet.kt:247-249](app/src/main/java/com/puretone/player/ui/screens/SongInfoSheet.kt#L247-L249) 的「清空结果」只调用 `metadataVm.setQuery("")`；[MetadataViewModel.kt:59-61](app/src/main/java/com/puretone/player/ui/MetadataViewModel.kt#L59-L61) 的 `setQuery` 只改查询词，`_results` 没清空，所以结果列表不会消失。
- `SongInfoSheet` 的 `metadataVm` 结果在换歌后仍保留（VM 作用域是页面，不是弹窗/歌曲），打开另一首会先看到上一首的候选。

**建议**

- 自动填充先展示预览（原标题/新标题、封面）让用户确认，或至少提供「撤销」；按标题+歌手+时长做相似度评分，不要无脑取第一条。
- 补全封面按专辑去重、限制并发 1–2、每次请求间 sleep、支持取消；或改用支持批量/更高配额的数据源。
- 加 `clearResults()` 并在弹窗打开/切歌时重置；「清空结果」调用它。
- 设置页加「联网元数据」总开关和隐私说明（见 P3-6）。

---

### P2-3 歌单功能只有「播放/删除」，缺少详情、排序与 M3U 入口

- `M3uPlaylistStore` 完全没有调用点；`PlaylistImportResolver` / `M3uCodec` 只在单测里用，**没有任何 UI 调用**；README 的「歌单导入/导出」只是数据层半成品。
- `PlaylistWithSongs` 不按 `position` 排序（P1-4）；没有歌单详情页，不能重命名、移除单曲、拖动排序。
- [MeScreen.kt:140-142](app/src/main/java/com/puretone/player/ui/screens/MeScreen.kt#L140-L142) 的删除歌单是一个 `IconButton`，单击立即删除，没有确认/撤销；歌单卡片点击直接「播放全部」，不是进入详情，交互容易误触。
- [MeScreen.kt:98-110](app/src/main/java/com/puretone/player/ui/screens/MeScreen.kt#L98-L110) 的「我喜欢」用 `Column` + `verticalScroll` 一次性组合所有行；收藏几百首时会明显卡顿，应改为 `LazyColumn`（或分页）。

**建议**

- 补歌单详情页：封面/名称/歌曲数、播放全部、重命名、移除、排序、导出 M3U；导入入口放到「我的」或设置。
- 删除加 `AlertDialog` 或 `Snackbar` 撤销；卡片点击进详情，播放按钮单独放。
- 「我喜欢」改为懒加载列表；`recently` 只 take 8 却把 50 首传进队列，行为也建议统一。

---

### P2-4 播放页：隐藏手势不可发现，空封面像故障，旋转耗电

- 歌词入口是「点封面翻转」，没有任何按钮/提示；新用户很难发现。上/下滑切歌同理（[PlayerScreen.kt:374-380](app/src/main/java/com/puretone/player/ui/screens/PlayerScreen.kt#L374-L380)）。
- 没有封面时渲染一个巨大的灰色圆盘 + 小黑点（截图 `docs/screenshots/02_player.png`），观感像加载失败；建议用品牌渐变 + 黑胶纹路/首字母。
- [PlayerScreen.kt:439-448](app/src/main/java/com/puretone/player/ui/screens/PlayerScreen.kt#L439-L448) 的拖动阈值 `90f` / `240f` 是**像素**，不同密度设备手感差异很大；应按 dp/density 换算。
- 旋转动画持续 60fps（P1-10）；歌词自动滚动在用户手动滚动后会被下一次 500ms 高亮拉回，建议检测用户拖动后暂停自动滚动几秒。
- 队列浮层（[PlayerScreen.kt:309-329](app/src/main/java/com/puretone/player/ui/screens/PlayerScreen.kt#L309-L329)）不标当前曲、没有移除/清空；`removeFromQueue`/`clearQueue` 已有但没接 UI。队列行用 `album=""` 的假 `SongEntity`，副标题出现 `"artist · "` 的尾点（截图 `docs/screenshots/03_queue.png`）。

**建议**

- 增加明确的「歌词」按钮/提示，保留封面点击翻转作为快捷方式；首次进入可展示一次手势引导。
- 空封面用品牌色渐变 + 音符/首字母，不要纯灰。
- 阈值改 dp；队列高亮当前曲，长按/滑动移除，提供「清空」。
- 队列行不要构造假实体，直接给 `SongRow` 传展示字段或复用 `QueueEntry` 的格式化逻辑。

---

### P2-5 设置页有多个「假开关」，纯黑与动态取色还会互相覆盖

- `coverPalette`（「播放页封面取色」）只被 Settings 读写，[Theme.kt:47-56](app/src/main/java/com/puretone/player/ui/theme/Theme.kt#L47-L56) 没有用它；`Palette` 依赖也完全未使用。README 的「封面取色主题」并未实现。
- `amoledBlack` 有字段和 setter，但 `SettingsScreen` 没有任何入口调用 `setAmoledBlack`；只有主题模式 `BLACK` 能触发纯黑。
- [Theme.kt:47-56](app/src/main/java/com/puretone/player/ui/theme/Theme.kt#L47-L56) 先判断 `dynamicColor`，再判断 `dark && useBlack`；用户同时开「纯黑」和「系统动态取色」时，得到的是动态深色而不是纯黑，AMOLED 省电失效。
- 设置页「均衡器」开关不影响声音（P0-2）；「启动时续播」（P0-3）也不生效。

**建议**

- 要么实现封面取色（Palette 从封面提取 seed，用于播放页光晕/主题），要么删除开关和依赖。
- 明确主题优先级：`BLACK` 应压过 `dynamicColor`，或在 UI 上禁用互斥选项。
- 删除死开关，或接到真实逻辑；设置页只保留已验证生效的项。

---

### P2-6 统计口径与展示

- 「活跃天数」「日均时长」只基于近 30 天，但卡片没有标注周期；「累计时长」是全部历史。建议在标题上写清「近 30 天」。
- `sinceMs` 在 `StatsViewModel` 构造时固定（[StatsViewModel.kt:62](app/src/main/java/com/puretone/player/ui/StatsViewModel.kt#L62)），页面跨午夜不会滚动；可在每次订阅/刷新时重算。
- [StatsScreen.kt:102](app/src/main/java/com/puretone/player/ui/screens/StatsScreen.kt#L102) 的 `highlightIndex` 取「最后一个有播放的日期」，不是峰值；注释却写「当前/峰值柱」。
- 最爱歌曲/歌手行不可点击，不能跳到对应歌曲/歌手；没有「清空历史」入口（`MusicRepository.clearHistory` 已有但未用）。
- `formatDuration` 对 <1 分钟显示秒，对 60 分钟以上显示小时+分，基本可用。

**建议**

- 统一时间窗口并写进 UI；跨天自动刷新。
- 明确「完成播放」口径（P1-2），如需精确时长改成采样统计。
- 峰值高亮改为真正的最大值；榜单可点击。

---

### P2-7 字符串与可访问性

- [strings.xml](app/src/main/res/values/strings.xml) 只有 `app_name`，全部 UI 文案/内容描述硬编码在 Kotlin 中（数十处）。无法做英文/繁体/其它语言，也不利于统一改文案；`supportsRtl="true"` 但没有任何 RTL 测试。
- [ControlIconButton](app/src/main/java/com/puretone/player/ui/components/PlayerComposables.kt#L292-L333) 用 `Box.clickable` + 内部 `Icon`，没有 `role = Button`；TalkBack 的按钮语义不完整。
- [PlayerProgressBar](app/src/main/java/com/puretone/player/ui/components/PlayerComposables.kt#L407-L521) 是 Canvas/手势，没有 `ProgressBarRangeInfo`/`setProgress` 语义，TalkBack 无法拖动进度。
- 歌词页字号按钮 36dp、进度条热区 24dp，低于 48dp 触控目标；固定行高 55/63dp 在大字体下会裁切。
- `MiniPlayer` 长按切换剩余时间是隐藏交互，没有可见提示或无障碍动作。

**建议**

- 把文案迁到 `strings.xml`，至少建立中文 + 英文 `values-en`；使用 `stringResource`。
- 用 `IconButton` 或给 `clickable` 加 `Role.Button`；进度条加 `progressBarRangeInfo` + `semantics { setProgress { ... } }`。
- 触控目标 ≥48dp；行高用 `heightIn(min=...)` 而不是固定 `height`。
- 给隐藏手势提供可见替代入口。

---

### P2-8 文档与实现不一致，截图已过期

- README 声称支持 **CUE**、**内嵌歌词**、**封面取色**、**FLAC/WAV 由 ffmpeg 支持**；代码里没有 CUE 解析器、没有内嵌歌词读取（只找同名 `.lrc`/下载歌词）、`Palette` 未使用，FLAC/WAV 实际由 ExoPlayer 原生支持。
- README 截图（`docs/screenshots/06_settings.png` 等）里的迷你条还是旧的 4 个控件（播放模式/播放/收藏/下一首），与 v1.3.0 的 3 按钮实现不符；播放页标题是测试数据 `_qtest_b`。
- `dist/v1.3.0_delivery` 自己记录了模拟器 ANR，但 README 写「单元回归全绿」「实测全部通过」，口径不一致。
- 版本号/交付说明与 README 的构建命令（`gradle.bat` vs `gradlew`）也不一致。

**建议**

- 以当前实现为准重写 README 功能列表，删除未实现项；重新生成截图（用真实音乐库，不用 `_qtest_*`）。
- 把「测试全绿」改成具体命令 + 环境 + 结果；把已知问题放到 README 的 Known Issues。
- 版本历史与 `dist/` 说明统一用 wrapper。

---

### P2-9 文本/编码细节：`<unknown>` 原样显示，LRC 有 BOM/逐字标签问题

- [MediaStoreScanner.kt:118-139](app/src/main/java/com/puretone/player/data/media/MediaStoreScanner.kt#L118-L139) 把 MediaStore 的 `ARTIST` / `ALBUM` 原样写入；[MusicRepository.kt:282-285](app/src/main/java/com/puretone/player/data/repository/MusicRepository.kt#L282-L285) 只把**空串**换成「未知艺术家/未知专辑」，`<unknown>` 不是空串。截图里可见 `<unknown>` 直接显示在播放页/队列/迷你条。
- [LrcParser.kt:73-88](app/src/main/java/com/puretone/player/lyrics/LrcParser.kt#L73-L88) 没有剥掉 UTF-8 BOM；带 BOM 的 `.lrc` 第一行（经常是 `[ti:]` 或第一句歌词）会被正则判为不匹配而丢弃。[LrcParser.kt:126-133](app/src/main/java/com/puretone/player/lyrics/LrcParser.kt#L126-L133) 只尝试 UTF-8 → GBK，UTF-16/Big5 歌词会乱码。
- [LrcParser.kt:56](app/src/main/java/com/puretone/player/lyrics/LrcParser.kt#L56) 明确把增强型 LRC 的逐字标签 `<0,120>` 当普通文本保留；播放页会原样显示这些标签。

**建议**

- 扫描/入库时把 `<unknown>`、`unknown`、`<unknown> ` 等统一归一为「未知艺术家/未知专辑」，`SongRow` 再做一次兜底。
- `decode` 先按 BOM 判断 UTF-8/UTF-16LE/BE，再尝试 UTF-8 严格解码 → GB18030（可再兜底 Big5）；`parse` 开头 `removePrefix("\uFEFF")`。
- 逐字标签至少用正则剥掉（`<\\d+,\\d+>`），或实现逐字高亮；不要直接显示给用户。

---

## 4. P3：工程化、构建与合规

### P3-1 targetSdk 34 与依赖版本已落后

- [app/build.gradle.kts:44-49](app/build.gradle.kts#L44-L49)：`compileSdk=34`、`targetSdk=34`。截至 2026-10，Google Play 对新应用/更新的 targetSdk 要求早已高于 34（2025-08 起为 35，之后继续上调），**当前配置无法通过新的上架审核**。
- [libs.versions.toml:2-17](gradle/libs.versions.toml#L2-L17)：AGP 8.5.2、Kotlin 2.0.21、Compose BOM 2024.09、Media3 1.4.1、Room 2.6.1、Navigation 2.8.2 均偏旧；缺少近一年的稳定性/安全修复。
- [gradle.properties:1](gradle.properties#L1) 只给 Gradle 2GB 堆，R8 + Compose 大项目容易 OOM；可提到 4GB。

**建议**

- 目标 `compileSdk/targetSdk 35`（或 36，按当年 Play 要求），AGP 8.7+/Gradle 8.9+、Kotlin 2.1/2.2、Compose BOM 2025.x、Media3 1.5+/1.6+、Room 2.7+。
- 升级后跑 `lintDebug` + 全量单测；用 `dependencyUpdates`/Renovate 保持版本。
- 加 `org.gradle.configuration-cache=true`、`org.gradle.jvmargs=-Xmx4g`。

### P3-2 KSP1 回退是技术债

- [gradle.properties:7-8](gradle.properties#L7-L8)：`ksp.useKSP2=false`，注释说明 Room 多对多 `@Relation` 在 KSP2 下报错。KSP1 已停止演进，后续 Kotlin/Room 升级会越来越难。
- KSP 已经对 `PlaylistSongEntity.song_id` 给出无索引警告（P1-4）。

**建议**

- 升级 Room 到 2.7+ 并切回 KSP2；如果 `@Relation` 仍有问题，改用显式 JOIN 查询（同时解决 position 排序）。
- 在 CI 中固定 KSP/Room/Kotlin 版本组合，避免升级时集中爆雷。

### P3-3 ffmpeg-kit 已停止维护，且存在 16KB / 许可证风险

- [app/build.gradle.kts:183-185](app/build.gradle.kts#L183-L185) 内置 `ffmpeg-kit-min-6.0-2.LTS.aar`。ffmpeg-kit 已于 2025 年停止维护，不再有安全/兼容更新。
- Android 15 起，targetSdk 35 的应用需要支持 16KB page size；6.0 LTS 的原生 `.so` 是否 16KB 对齐需要实测/重编，否则在部分新设备上可能加载失败。
- ffmpeg-kit / FFmpeg 是 LGPL（部分构建 GPL）许可；当前 README 只写 MIT，没有第三方许可/NOTICE/源码获取说明。
- 通用包 76MB，主要来自四 ABI 的 ffmpeg 库；虽然已有 ABI 分包，但 Play 发布更推荐 AAB。

**建议**

- 评估替代方案：自己用 NDK r27+ 重编 ffmpeg/ffmpeg-kit fork，或用 Media3 的自定义 `Renderer`/`Extractor` 直接解 APE；至少做 16KB 真机验证。
- 补 `NOTICE`/开源许可页，附 LGPL 文本与源码获取方式；README 的 License 段落写清第三方组件。
- 发布改用 AAB，保留 ABI split；移除未使用依赖（见 P3-4）。

### P3-4 未使用依赖与死代码

**未使用依赖**

- `androidx.palette:palette-ktx`（全工程无引用）
- `app.cash.turbine:turbine`（测试无引用）
- `androidx.room:room-testing`（无迁移测试）
- `androidx.test.ext:junit`（测试用 Robolectric runner，无引用）
- `ACCESS_NETWORK_STATE` 权限（无 `ConnectivityManager` 使用）
- `compose-ui-tooling-preview` 无 `@Preview`（可保留给 IDE，但当前无实际使用）

**死配置/死代码**

- `coverPalette`、`amoledBlack`、`resumeOnStart`、`repeatMode`、`shuffle`（只存不读或只读不写）
- `PlayerViewModel.tryRestore()`、`setRepeatMode()`、`setShuffle()`（无外部调用）
- `LibraryViewModel.librarySize`（类型/命名都不对，未使用）
- `PlaybackManager.songCache`（只写不读）
- `AudioEffectController.isAvailable()` / `bandCount()`（未使用）
- `M3uPlaylistStore` / `PlaylistImportResolver`（无 UI）
- `MusicRepository.renamePlaylist/removeFromPlaylist/clearHistory`、`PlaylistDao.observePlaylist/clearSongs` 等（无 UI）

**建议**：要么接入，要么删除；死配置比缺功能更伤信任。用 `./gradlew lintDebug` 的 `UnusedResources` + IDE 的 unused declaration 检查定期清理。

### P3-5 测试覆盖与测试质量

- 35 个用例集中在 LRC/M3U/中文转换/扫描/同步对账；**播放管理器、会话恢复、EQ 映射、歌单排序、DataStore 并发**没有测试。
- [ControlIconsTest.kt:30-37](app/src/test/java/com/puretone/player/ui/components/ControlIconsTest.kt#L30-L37) 用 Kotlin `assert(...)`，JVM 默认不启用断言，这两个检查实际是空操作；应改 `assertTrue`。
- 没有 `androidTest`（虽然配置了 `testInstrumentationRunner`），没有 Compose UI 测试，没有 Macrobenchmark/Baseline Profile。
- `MusicRepositorySyncTest` 没有覆盖：文件移动/重命名、`userEdited` 保留、事务回滚、`playCount` 保留。
- `PlaylistWithSongs` 没有「按 position 排序」测试，所以 P1-4 没被发现。

**建议**

- 抽出 `PlaybackQueue`/`SessionStore` 等纯逻辑，用 fake `Player` 做单测；恢复顺序、重复项、暂停/播放语义都要覆盖。
- 加 Room 测试：歌单顺序、删除歌单级联、sync 事务、文件移动后的引用。
- 加 Compose UI 测试：权限 gate、队列 sheet、设置开关；至少一条 `PermissionGate` 的拒绝/返回流程。
- CI 跑 `testDebugUnitTest` + `lintDebug` + `assembleRelease`；用 JUnit 4 的 `assertTrue` 替换 `assert`。

### P3-6 隐私与产品合规

- App 是本地播放器，却在「自动填充/补全封面/下载歌词」时把歌手/歌名发送到 iTunes、LRCLIB；没有隐私说明或总开关。
- `allowBackup=true` 没有 `dataExtractionRules`：`crash_last.txt`、DataStore、Room 都可能被云备份；跨设备恢复后 `mediaStoreId`/路径可能失效。
- 崩溃日志只写 `filesDir/crash_last.txt`，普通用户无法取出，也没有「导出诊断」入口；native 崩溃（FFmpeg/音频底层）完全捕获不到。
- `PlaybackService` 是 `exported=true` 且无权限（Lint `ExportedService` 警告），需要确认 Media3 的控制器校验是否符合预期，避免任意应用绑定。

**建议**

- 加「联网功能」总开关 + 首次使用说明 + 隐私政策链接；Data Safety 表单如实填写。
- 加 `android:dataExtractionRules` / `fullBackupContent`，排除 crash/cache，或明确只备份歌单/收藏。
- 设置页加「导出诊断日志」；native 崩溃用 `FFmpegKitConfig` 日志/`debugSymbolLevel` 辅助定位。
- 复核 Service 的 `android:permission`（Media3 官方模板通常 exported=true；如不需要外部控制可改 false，但这会影响车机/手表/语音助手）。

---

## 5. 建议修复顺序

### 第 1 周：先把「看起来有、实际没有」的功能修实（正确性）

1. **P0-1** `saveSongInfo(songId, ...)`，补回归测试。
2. **P0-2/P0-3/P0-4** 统一「设置 → 引擎」：`AudioEffectController.applySettings`、repeat/shuffle 持久化、resumeOnStart 接线、睡眠定时改 deadline + 触发清零。
3. **P0-6/P0-7** 删除 MainActivity 的扫描副作用；`scan()` 加互斥；恢复会话改成显式 `autoPlay=false` + 按保存顺序重建。
4. **P0-8/P1-1** 播放页/迷你条改观察 Room 当前曲；修 `removeFromQueue` 索引、`enqueue` pending、`stop` 清会话。
5. **P1-7/P1-8** 统一 inset；通知权限降级为可选 + `ON_RESUME` 重查。

### 第 2 周：性能、存储与质量门禁

6. **P0-5** APE 改为「当前曲按需 + 下一首预取」；缓存按字节上限；`executeWithArguments` + 临时文件 + 互斥。
7. **P1-3/P1-4/P1-6** sync 移出主线程 + 增量对账 + 事务；搜索/分组走 SQL + `flowOn`；补索引、修歌单排序/孤儿行；MediaStore 多卷/增量扫描。
8. **P1-5/P1-9** DataStore `catch` + corruption handler；EQ 滑杆本地态 + `onValueChangeFinished`；修 5 个 lint error，CI 加 `lintDebug`。
9. **P0-8/P2-1/P2-4** 播放页刷新、EQ 段数映射、空封面/手势提示/队列高亮。
10. **P3-5** 补恢复/队列/歌单/权限测试；`ControlIconsTest` 改 `assertTrue`。

### 之后：产品化与长期维护

- 歌单详情 + M3U 导入导出 + 删除确认/撤销；「我喜欢」懒加载。
- 元数据自动填充预览/撤销、封面补全限流。
- 字符串资源化 + 英文 + 可访问性语义。
- 升级 targetSdk 35/36、AGP/Kotlin/Compose/Media3/Room；切 KSP2。
- ffmpeg 16KB 验证/替代方案、LGPL NOTICE、AAB + Baseline Profile。
- 重写 README/截图，补隐私说明与诊断导出。

---

## 6. 快速收益清单（可以今天就改）

- [ ] `saveSongInfo` 接收 `songId`，不要用 `currentSongId`。
- [ ] 删除 `MainActivity` 里的自动扫描 `LaunchedEffect`。
- [ ] `PlaybackManager.bind` 读取 `repeatMode`/`shuffle`，`setRepeatMode/setShuffle` 回写 DataStore。
- [ ] `setSleepTimer` 触发后 `settings.setSleepTimerMinutes(0)`。
- [ ] `SettingsRepository` 的 `dataStore.data` 加 `.catch { IOException -> emptyPreferences() }`。
- [ ] `AudioEffectController` 从 `PureToneSettings` 初始化，`SettingsViewModel.setEqEnabled` 调控制器。
- [ ] `Scaffold(contentWindowInsets = WindowInsets(0))` 或内层 `TopAppBar(windowInsets = WindowInsets(0))`。
- [ ] 扫描时把 `<unknown>` 归一为「未知艺术家/未知专辑」。
- [ ] 队列行不要用 `album=""` 的假 `SongEntity`。
- [ ] 给 `PlaybackManager` 加 `@OptIn(UnstableApi::class)`，cutout 属性移到 `values-v27`。
- [ ] 移除 `palette-ktx`、`turbine`、`room-testing`、`androidx-test-ext-junit`、`ACCESS_NETWORK_STATE`（确认无引用后）。
- [ ] 再次运行 `lintDebug`、`testDebugUnitTest`、`assembleRelease`，把结果写进 README。

---

## 附录 A：死配置/死代码清单

| 名称 | 位置 | 状态 |
| --- | --- | --- |
| `coverPalette` | `SettingsRepository` / `SettingsScreen` | 只存不读（Palette 未使用） |
| `amoledBlack` | `SettingsRepository` / `Theme` | 有 setter，无 UI 入口 |
| `resumeOnStart` | `SettingsRepository` / `SettingsScreen` | 只存不读 |
| `repeatMode` / `shuffle` | `SettingsRepository` | 只存不读，`PlaybackManager` 不加载 |
| `tryRestore()` | `PlayerViewModel` | 无调用点 |
| `setRepeatMode()` / `setShuffle()` | `PlayerViewModel` | 无外部调用 |
| `librarySize` | `LibraryViewModel:98` | 类型/命名错误，未使用 |
| `songCache` | `PlaybackManager:107` | 只写不读 |
| `isAvailable()` / `bandCount()` | `AudioEffectController` | 未使用 |
| `M3uPlaylistStore` | `data/playlist` | 无调用点 |
| `PlaylistImportResolver` / `M3uCodec` | `data/playlist` / `lyrics` | 仅测试使用 |
| `renamePlaylist` / `removeFromPlaylist` / `clearHistory` | `MusicRepository` | 无 UI |
| `observePlaylist` / `clearSongs` | `PlaylistDao` | 无 UI |
| `ACCESS_NETWORK_STATE` | Manifest | 无 `ConnectivityManager` 使用 |
| `Palette` / `Turbine` / `room-testing` / `test-ext-junit` | Gradle | 无引用 |

## 附录 B：Lint 明细（5 errors / 12 warnings）

**Errors**

1. `values/themes.xml:10` — `windowLayoutInDisplayCutoutMode` requires API 27 (min 26).
2. `values-night/themes.xml:8` — 同上。
3. `PlaybackManager.kt:121` — `onAudioSessionIdChanged` 是 Media3 `UnstableApi`，未 opt-in。
4. `PlaybackManager.kt:193` — `player.audioSessionId` 同上。
5. `PlaybackManager.kt:564` — `MediaMetadata.Builder.setDurationMs` 同上。

**Warnings**

- `AndroidManifest.xml:42` — Exported service does not require permission.
- `mipmap-anydpi-v26` — 冗余目录（minSdk 26）。
- `colors.xml:4-5` — `brand_primary` / `brand_primary_dark` 未使用。
- `mipmap-anydpi-v26/ic_launcher*.xml` — 缺少 monochrome 图标（Android 13 主题图标）。
- `libs.versions.toml:21` — Robolectric 4.12.2 可升 4.13。
- Navigation 2.8.2 的 lint.jar 与当前 lint 版本不兼容，`EmptyNavDeepLink` 等检查被跳过（升级 Navigation/AGP 可解）。
- KSP：`playlist_songs.song_id` 无索引（见 P1-4）。

## 附录 C：建议新增的测试

| 模块 | 用例 |
| --- | --- |
| 歌曲编辑 | 播放 A 时编辑 B，A 不变、B 更新；无播放时编辑不丢数据 |
| 会话恢复 | 保存 `[A,B,C]` + index=1，恢复后顺序/当前曲一致；重复项保留；恢复后不自动播放（若产品要求） |
| 队列 | 删除当前曲之前的条目后 `currentEntry` 正确；player 未 bind 时 enqueue 不丢歌 |
| 设置 | repeat/shuffle/resume/sleep 冷启动后仍生效；睡眠定时触发后归零 |
| EQ | 持久化设置在 bind 后应用；设备 5 段时映射不越界；主开关关闭 bass/virtualizer |
| 歌单 | 按 position 排序；删除歌单清理 junction；`updated_at` 更新 |
| 同步 | 文件移动/重命名；userEdited 保留；事务失败不留半套；空扫描不清库 |
| UI | 权限拒绝/返回设置；队列高亮；歌词按钮；大字体不裁切 |

---

*本报告基于静态阅读 + 本机编译/lint/单测验证；未改动任何源码。*

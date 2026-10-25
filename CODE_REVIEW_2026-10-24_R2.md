# PureTone 全面代码复审报告（第二轮）

- **复审对象**：v1.4.1（code 21）工作树，含 v1.3.2 Bug 修复 + 首轮复审（CODE_REVIEW_2026-10-24.md）P0–P3 全量落地 + 三项 UI 调整
- **范围**：`app/src/main/java` 全部 56 个文件（约 10.7k 行）+ 11 个单测文件
- **方法**：全文件通读；对关键结论做**字节码级实证**（navigation-common-2.8.2 AAR、Robolectric android-all-instrumented API 26/29/32/33/34 的框架 `org.json`、org.json:20231013）
- **结论概览**：P0 ×2、P1 ×5、P2 ×17、P3 ×16；另附可维护性改进建议与「已专项核查无问题」清单

> 说明：本轮为**复审报告，未修改任何代码**。所有行号均指当前工作树。

---

## P0 — 崩溃 / 数据丢失

### P0-1 导航参数双重解码：含 `%` 的专辑/歌手名点击即崩，含 `+` 的名静默错组
**位置**：`ui/PureToneApp.kt:55-56`（编码）、`ui/PureToneApp.kt:152-153`（解码）
**调用方**：`ui/screens/BrowseScreen.kt:388/394/400`、`ui/screens/StatsScreen.kt:173`

**证据（字节码实证）**：
- `Routes.groupDetail` 用 `URLEncoder.encode`（form 语义：空格→`+`、`%`→`%25`、`/`→`%2F`）；
- navigation-common-2.8.2 的 `NavDeepLink.getMatchingPathArguments` 在入 Bundle **前已经解码一次**：
  `107: invokevirtual Matcher.group(I)` → `110: invokestatic android/net/Uri.decode` → `141: parseArgument(...)`；
- App 在 `PureToneApp.kt:152-153` 又做了**第二次** `URLDecoder.decode`。

`Uri.decode` 只解 `%XX`、**不转 `+`**；`URLDecoder.decode` 两者都做。两次解码叠加后：

| 分组名 | 路由（URLEncoder） | Navigation 解一次后 | App 再解一次 | 结果 |
|---|---|---|---|---|
| The Beatles | `The+Beatles` | `The+Beatles` | `The Beatles` | ✓ 侥幸正确（作者只测了这类） |
| **100% Rock** | `100%25+Rock` | `100%+Rock` | `%+R` 非法转义 | **抛 IllegalArgumentException → 崩溃** |
| **+44 / C++** | `%2B44` / `C%2B%2B` | `+44` / `C++` | `" 44"` / `"C  "` | key 错乱 → 分组页空列表 |
| 90%20Off | `90%2520Off` | `90%20Off` | `90 Off` | 静默错组 |

曲库里 `%` 极常见（合集「100% Hits」「90%20 精选」），`+` 亦常见（「+44」「C++」）。真实曲库点击即崩/即错。

**修复**：改用对称的 RFC-3986 编解码——`Routes.groupDetail` 用 `android.net.Uri.encode(key)`（`+`→`%2B`、`%`→`%25`、空格→`%20`、`/`→`%2F`），`PureToneApp.kt:152` 直接 `backStackEntry.arguments?.getString("key") ?: ""`，删掉 `URLDecoder.decode`（Navigation 已解过一次，再解反而错）。

---

### P0-2 APE 懒加载路径 `replaceMediaItemUri` 无边界检查 + `ensureWav` 未保护 → 进程崩溃
**位置**：`playback/PlaybackManager.kt:812-816`（`replaceMediaItemUri`）、`:704-722`（`handlePlayerError`）

**证据**：
```kotlin
// PlaybackManager.kt:812-816
private fun replaceMediaItemUri(index: Int, wavFile: File) {
    val p = player ?: return
    val updated = p.getMediaItemAt(index).buildUpon()...   // ← 无 index 边界检查
```
```kotlin
// PlaybackManager.kt:712 —— 与 382 / 473 行的 runCatching{...}.getOrNull() 不一致
val wav = apeTranscoder.ensureWav(entry.songId, entry.filePath)
```

**问题**：`handlePlayerError` 是 APE 占位 URI（`Uri.parse(path)`，必然解码失败）的**主路径**，不是边缘路径。`ensureWav` 可挂起数十秒，期间用户可能 `clearQueue()`（`:509` `clearMediaItems()`）/`removeFromQueue()` / `stop()`；协程恢复后 `getMediaItemAt(index)` 对空/更短 playlist 抛 `IndexOutOfBoundsException`，且该异常发生在无 try/catch 的 `scope.launch` 里，经 SupervisorJob 直达未捕获异常处理器 → **进程崩溃**。同时 `ensureWav` 一旦抛出（IO/FFmpegKit/取消），`:711` 置上的 `isTranscoding = true` 永不复位，UI 永久卡「转码中」。

**修复**：`replaceMediaItemUri` 入口加 `if (index !in 0 until p.mediaItemCount) return` 并整体 `runCatching`；恢复后核对 `player === p`；`handlePlayerError` / `warmUpFollowingApe`（`:826`）的 `ensureWav` 统一包 `runCatching`，`isTranscoding` 置位放 `try/finally`。

---

## P1 — 重大功能缺陷

### P1-1 LRCLIB 歌词提供者把 JSON `null` 当歌词字符串 `"null"`，歌词下载功能整体失效
**位置**：`data/metadata/LrcLibLyricsProvider.kt:45,47,57,58`

**证据（AOSP 框架字节码实证，API 26/29/32/33/34 完全一致）**：
- `JSONObject$1`（即 `JSONObject.NULL` 单例）`toString()` → `ldc "null"`；
- `JSON.toString(Object)` → `value != null ? String.valueOf(value) : null` → 对 NULL 单例返回 `"null"`；
- `JSONObject.optString(name, fallback)` → `result != null ? result : fallback` → **返回 `"null"`**。

```kotlin
obj.optString("syncedLyrics").takeIf { !it.isNullOrBlank() }   // JSON null → "null" → 非空 → 通过
```

LRCLIB 对缺失字段显式返回 `"plainLyrics":null,"syncedLyrics":null`（非省略键），因此这是**常态路径**：
`fetch()` 返回字面量 `"null"` → `MetadataViewModel.kt:242` 的 `lrc.isNullOrBlank()` 失效 → `lyricsStore.save()` 把 `null` 四个字母写进 `.lrc` 并提示「歌词已下载并保存」→ `LrcParser.parse("null")` 无标签匹配 → 永远不显示歌词。附带：搜索兜底（`:42-50`）对首个 plain-only 结果即返回 `"null"` 而不再扫描有同步歌词的后续结果；instrumental 条目产出 `[00:00.00]null` 伪 LRC。

**旁证**：同仓 `ItunesMetadataProvider.kt:59-63` 已正确写成 `takeIf { it.isNotBlank() && it != "null" }` —— 说明团队知道这个坑，只是后加的 LrcLib 漏了。

**修复**：与 iTunes 一致——`obj.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }`，或先 `obj.isNull("syncedLyrics")` 判断。

---

### P1-2 `restoreLastSession` 守卫只求值一次，恢复会覆盖用户新起的播放
**位置**：`playback/PlaybackManager.kt:660-681`

**证据**：`:662` 的 `canRestoreSession(...)` 在**所有挂起点之前**求值；`:665` `loadPlaybackSession()`、`:669` `getSongs(...)`、`:674` `doSetQueueAndPlay`（内部还有 `ensureWav` 挂起）之后**没有二次校验**。

**问题**：冷启动首帧触发恢复，DB 加载的几十毫秒内用户点了首歌 → `setQueueAndPlay` 已建好队列；恢复协程恢复后 `doSetQueueAndPlay(autoPlay = false)` 会 `++queueGeneration`（超过用户命令的代次）并通过 `:405` 的代次检查 → **把用户正在听的歌换成旧会话并暂停**。这正是 `:648-658` 注释想防的场景，但守卫只查了一次。

**修复**：在 `:669` 拿到 `songs` 之后、调用 `doSetQueueAndPlay` 之前，再执行一次 `canRestoreSession(player?.mediaItemCount ?: 0, ...)`，不满足则 `return false`。

---

### P1-3 `bind()` 中 `pendingPlay` 与 `pendingRestore` 竞态，显式播放请求被旧会话顶掉
**位置**：`playback/PlaybackManager.kt:248-259`（bind）、`:346-354`（setQueueAndPlay）、`:463-481`（playAtIndex）、`:528-550`（play/togglePlayPause）

**证据**：`bind()` 先 launch `doSetQueueAndPlay`（pendingPlay），再 launch `restoreLastSession()`（pendingRestore）；`setQueueAndPlay` 在 player 为 null 时置 `pendingPlay`（`:349`）**不清** `pendingRestore`，而 `playAtIndex`/`play()` 会置 `pendingRestore = true`（`:467/:531/:541`）。

**问题**：待播曲是 APE 时，`pendingPlay` 协程在 `ensureWav` 挂起 → `restoreLastSession` 看到 `mediaItemCount == 0` 守卫通过 → 两条 `doSetQueueAndPlay` 竞态，代次机制保证**后启动的恢复胜出** → 用户点的歌被旧会话（且暂停态）取代。

**修复**：`setQueueAndPlay` 置 `pendingPlay` 时同步 `pendingRestore = false`；`bind()` 执行完 `pendingPlay` 后也清一次 `pendingRestore`。

---

### P1-4 `ApeTranscoder` 的 `songLocks.getOrPut` 非原子：同曲并发互斥失效，双 ffmpeg 同写一个 `.tmp`
**位置**：`playback/ApeTranscoder.kt:45-52`

**证据**：
```kotlin
val lock = songLocks.getOrPut(songId) { Mutex() }          // get + put 两步，非原子
if (songLocks.size > LOCK_MAP_MAX) {
    songLocks.entries.removeAll { it.key != songId && !it.value.isLocked }   // 会删掉别人即将持有的锁
}
```
Kotlin 的 `MutableMap.getOrPut` 是 `get` + `put` 两步；`ConcurrentHashMap` 上亦然。两个线程可各自创建 `Mutex` 并互相覆盖，之后持有**不同锁实例** → `withLock` 互斥完全失效；`:48-50` 的清理进一步放大窗口（A 拿到锁但还没进 `withLock`，B 删掉它，C 新建）。并发路径可达：`doSetQueueAndPlay`（`:382`，Main.immediate）、`playAtIndex`（`:473`）、`handlePlayerError`（`:712`）、`warmUpFollowingApe`（`:826`，`Dispatchers.IO`）会对同一 songId 并发调 `ensureWav`。后果正是类注释 `:25-28` 声称要防的「两个入口同时写出半截 WAV，被当成缓存命中播损坏文件」。

**修复**：`songLocks.computeIfAbsent(songId) { Mutex() }`（Java 8 API，minSdk 26 可用），锁条目永不删除（songId 有界），删掉 `LOCK_MAP_MAX` 清理逻辑。

---

### P1-5 `AudioEffectController` 主开关语义分裂：关了均衡器，低音增强仍在响
**位置**：`effect/AudioEffectController.kt:209`（`applySettings`）、`:259-266`（`applyBass`）

**证据**：`needsEffects() = enabled || bassStrength > 0 || virtualizerStrength > 0`；`applySettings` 走 `if (needsEffects()) ensureEffects() else disableAllEffects()`；而 `applyBass` 里是 `bb.enabled = bassStrength > 0`（**不含 `enabled`**）。

**问题**：用户关主开关（`eqEnabled=false`）但低音滑块停在 500 → `applySettings` 走 `ensureEffects()` → `applyBass` 把 `bb.enabled` 重新置 true → **主开关关了重低音仍响**；而直接 `setEnabled(false)` 走 `disableAllEffects()` 全关。同一语义两个入口行为相反，且设置页操作经 DataStore 流回 `applySettings` 是主路径。

**修复**：统一语义——若 bass/virtualizer 从属于主开关，`applyBass`/`applyVirtualizer` 用 `enabled && strength > 0`；若独立，则 `setEnabled(false)` 不应关它们。二者取一，并补单测锁死。

---

## P2 — 次要 / 边缘缺陷

### P2-1 封面占位 URI 实际清不掉，「补全缺失封面」对旧数据永久失效
**位置**：`data/repository/MusicRepository.kt:234`（`hasChanged`）、`data/local/dao/SongDao.kt:184-192`
**证据**：`(s.coverUri != null && (old.coverUri == null || isAlbumArtPlaceholder(old.coverUri)))`；而 `getSongsMissingCover` 的判据是 `cover_uri IS NULL OR cover_uri = ''`
**问题**：重扫时若仍解析不出封面（`s.coverUri == null`），该分支为 false；mediaStoreId/dateModified/size 未变则整行跳过（`:188`）→ 占位 URI 原样留在 `cover_uri`，而它既非 NULL 也非 `''` → **永远不会进入补全队列**。`:183-184` 注释声称的「借此清理」不会发生。
**修复**：`getSongsMissingCover` 增加 `OR cover_uri LIKE '%/audio/albumart/%'`（零迁移）；或在 `hasChanged` 补 `|| (s.coverUri == null && isAlbumArtPlaceholder(old.coverUri))`。

### P2-2 `play_history` 缺 `played_at` 索引，热路径全表扫描 + 重排
**位置**：`data/local/entity/PlayHistoryEntity.kt:20`（仅 `song_id` 唯一索引）；受害查询 `PlayHistoryDao.kt:18,22`、`StatsDao.kt:41`
**证据**：`SELECT * FROM play_history ORDER BY played_at DESC LIMIT :limit`、`WHERE played_at >= :sinceMs`、`DELETE ... WHERE played_at < :before`
**问题**：历史保留 1 年且每次播放都插行；这些全是 Flow，「最近播放」/统计页订阅期间每插一条就全表扫+重排一次；小时级清理 DELETE 也是全表扫。
**修复**：v5 迁移加 `CREATE INDEX index_play_history_played_at ON play_history(played_at)`。

### P2-3 `songs` 排序类查询缺索引，任意写操作触发全表扫描 + 临时排序
**位置**：`data/local/entity/SongEntity.kt:16`（仅 path/album_id/artist）；查询 `SongDao.kt:20,32,36`、`StatsDao.kt:54`
**问题**：曲库页 4 个排序 Flow 在任意 songs 写操作（每次完整播放的 `play_count+1`、收藏、封面更新）后全部重扫+重排；最爱歌曲榜同理。大库掉帧。
**修复**：v5 迁移加 `index_songs_date_added(date_added)`、`index_songs_play_count(play_count, last_played_at)`、`index_songs_duration(duration)`。注意 Room 不会为既有装机自动补索引，必须显式迁移。

### P2-4 `songs.album_id` 跨卷撞号：SD 卡设备专辑详情串页
**位置**：`data/local/entity/SongEntity.kt:29-30`、`data/local/dao/SongDao.kt:129`、`data/media/MediaStoreScanner.kt:156-165`
**证据**：扫描器自己承认（`MediaStoreScanner.kt:81-83`）「ALBUM_ID 是每卷独立编号，SD 卡与内部存储会撞号」，但只把卷名用在 albumart 缓存键上，入库的 `albumId` 仍是**无卷限定的裸 id**。
**问题**：多卷设备上内部卷与 SD 卡的专辑 id 均从 1 开始编号 → 两个不同专辑可同 id → 专辑详情页（及按 album_id 的分组）把另一卷的歌曲并入。附带：`SongDao.kt:129-130` 的 `OR` 双分支参数化条件也使 `index_songs_album_id` 失效退化为全表扫描。
**修复**：songs 表增加卷列（如 `album_volume TEXT`，v5 迁移 DEFAULT ''），扫描器写 `volumeName`，详情查询改 `album_id = :albumId AND album_volume = :volume`。

### P2-5 歌单 Flow 失效放大 + `combine` 重复发射
**位置**：`data/local/dao/PlaylistDao.kt:107-116`，关联 `:41-68`
**证据**：`observePlaylists().combine(observePlaylistSongRows())`，而 `observePlaylistSongRows` 是无 WHERE 的全量 `playlist_songs ⋈ songs`
**问题**：(a) 任意 songs 行更新（播放中每次 `incrementPlayCount`）都会重查全部歌单关联行，「我的」页随每次播放完成全量重算；(b) `addSongsAtEnd` 在同一事务写 `playlist_songs` + `playlists`，两条上游 Flow 同批失效 → combine 连发两次相同数据，下游无 `distinctUntilChanged` 时重复重组。
**修复**：(a) 单歌单详情改用 `observePlaylistSongRowsOf(id)` 一带 WHERE 的查询；(b) combine 后加 `.distinctUntilChanged()`。

### P2-6 `getSongs(emptyList())` 会崩（公共 API 未防空列表）
**位置**：`data/local/dao/SongDao.kt:74-75`（`WHERE id IN (:ids)`），入口 `MusicRepository.kt:123-125`
**证据**：Room 生成代码为 `append("... IN ("); appendPlaceholders(_stringBuilder, _inputSize); append(")")` —— 空列表生成 `IN ()`，SQLite 语法错误抛 `SQLiteException`
**问题**：当前全部调用方都自行判空，暂无触发路径；但这是仓库公开方法，后续任何调用方漏判即崩。
**修复**：`MusicRepository.kt:123` 加 `if (ids.isEmpty()) return emptyList()`（一行）。

### P2-7 `M3uCodec.parse` 丢弃时长为 0/负数/小数的 EXTINF 标题，且往返不自洽
**位置**：`lyrics/M3uCodec.kt:32-36`、`:57-70`
**证据**：`pendingTitle = label.takeIf { pendingDuration > 0 }`；而 `serialize` 对「有时长 0 但有标题」的条目写出 `#EXTINF:0,Title`
**问题**：`#EXTINF:-1,Title`、`#EXTINF:0,Title`、`#EXTINF:229.5,Title`（`toIntOrNull` → null → 0）都丢标题；serialize 写出的 `#EXTINF:0,Title` 再 parse 即丢标题——与 `:34-35` 注释声称的「保证往返稳定」自相矛盾。外部歌单导入后条目显示文件名而非标题。
**修复**：标题存在即保留（`pendingTitle = label`），时长单独解析；序列化时始终写 `#EXTINF:<dur>,<title>`。

### P2-8 `PlaylistImportResolver` 不剥离 `file://` 前缀、不做 URL 解码
**位置**：`data/playlist/PlaylistImportResolver.kt:27`
**证据**：`val normalized = entry.path.replace('\\', '/')`
**问题**：百分比编码条目（如 `%E4%B8%AD%E6%96%87.mp3`）既匹配不上路径两档，`fileNameKey` 也是编码文本 → `return@forEach` 静默跳过 → 导入的歌单缺歌。（裸 `file:///sdcard/...` 目前靠 `:36` 的 `normalized.endsWith("/$libPath")` 碰巧能命中。）
**修复**：匹配前剥离 `file://` scheme 并对路径段做 `URLDecoder.decode(..., UTF-8)`（失败保留原串）。

### P2-9 `LrcParser` 单遍解析，`[offset:]` 之前的歌词行不应用偏移
**位置**：`lyrics/LrcParser.kt:95`（读 offset）、`:122`（应用 offset）在同一遍迭代中
**问题**：offset 标签出现在歌词行之后（非头部）时，之前的行用默认 0 计算，时间轴整体错位。多数文件 offset 在头部故为边缘场景。
**修复**：两遍——先收集 `(times, text)`，遍历结束后再统一减 offset。

### P2-10 `LyricsStore` 文件名无长度约束，长歌曲名导致保存静默失败
**位置**：`lyrics/LyricsStore.kt:31-33`
**证据**：`File(audioPath).nameWithoutExtension.ifBlank { "lyrics" }` → `"${base}_${hash}.lrc"`
**问题**：`externalFilesDir`（ext4/FUSE）文件名上限约 255 字节，base 可取到约 250 字节，再加 `"_xxxxxxxx.lrc"` 13 字节即超限 → `writeText` 抛 `ENAMETOOLONG` → 被 `:40-42` 的 `runCatching` 吞掉，`save` 返回 false，歌词永远存不上。
**修复**：`base.take(100)`（哈希后缀已保证唯一性）。

### P2-11 歌曲信息弹窗「保存修改」回写旧年份，覆盖刚自动填充的年份
**位置**：`ui/screens/SongInfoSheet.kt:178`；写入方 `ui/MetadataViewModel.kt:159,209`
**证据**：`playerVm.saveSongInfo(song.id, title, artist, album, coverUri, song.year)` —— `song.year` 是打开弹窗时的旧值；而 `autoFill`/`apply` 已把 `best.year`/`candidate.year` 写库
**问题**：先「自动填充」写入候选年份，再点「保存修改」会把旧年份回写，覆盖刚填充的年份（弹窗里根本没有 year 输入框）。
**修复**：本地缓存 `year` 变量，随 autoFill/apply 回调一起更新并传入 `saveSongInfo`。

### P2-12 `warmUpFollowingApe` 裸调 `ensureWav`，抛异常即崩
**位置**：`playback/PlaybackManager.kt:826`
**证据**：`apeTranscoder.ensureWav(nextApe.songId, nextApe.filePath)` 无 `runCatching`（对比 `:382`/`:473`）
**问题**：预热是 fire-and-forget，抛异常同样会经 SupervisorJob 到达未捕获处理器 → 崩溃。
**修复**：包 `runCatching`（或 try/catch 记日志）。

### P2-13 队列增删操作不参与 `queueGeneration` 协议，被在途旧 setter 覆盖
**位置**：`playback/PlaybackManager.kt:433-436`（enqueue）、`:452-459`（playNext）、`:488-502`（removeFromQueue）均不递增代次；setter 在 `:405-407` 整体 `setMediaItems` 替换
**问题**：用户点了 APE 歌（setter 在 `ensureWav` 挂起）→ 期间 `playNext`/`removeFromQueue` → setter 恢复后照常执行 → **用户的插播/删除被静默抹掉**（删掉的歌也会回来）。
**修复**：让 setter 先用占位 URI 建队列并起播，起播项的 `ensureWav` + `replaceMediaItemUri` 放到 `setMediaItems` 之后异步做（复用既有懒加载路径）；或让 mutator 也递增 `queueGeneration` 使在途 setter 失效。

### P2-14 `apeRetryIndex` 永不复位，换队列后 APE 重试被永久屏蔽
**位置**：`playback/PlaybackManager.kt:117`（字段）、`:708-709`（写入）；`stop()`（`:557-580`）、`clearQueue()`（`:508-524`）、`doSetQueueAndPlay` 都不重置
**问题**：旧队列里 index=2 的 APE 失败重试过一次；新队列同样 index=2 的 APE 首次出错时 `apeRetryIndex == index` → 跳过转码重试，直接走 else 分支只弹错误、播放器卡在 IDLE。
**修复**：`stop()` / `clearQueue()` / 设置新队列时置 `apeRetryIndex = -1`。

### P2-15 `ApeTranscoder` 缓存 key 仅用 songId，源文件被替换后命中旧 WAV
**位置**：`playback/ApeTranscoder.kt:149`
**证据**：`private fun fileName(songId: Long) = "ape_$songId.wav"`
**问题**：Room 对同一 `id` 行可发生 path/文件更新（`MusicRepository.kt:230-234` 的对账逻辑就比对 `mediaStoreId/dateModified/size`），此时缓存仍返回**旧文件转出的 WAV**，用户听到的是替换前的内容；且缓存命中（`:62`）不校验源文件是否还存在。
**修复**：key 掺入 `source.length()` + `source.lastModified()`（或内容哈希），不符时重转。

### P2-16 `MetadataViewModel.search` 无请求排序/取消
**位置**：`ui/MetadataViewModel.kt:112-124`
**问题**：连点搜索时，先发的请求后完成会把 `_searching.value = false` 提前清掉（转圈消失但请求仍在飞），且旧结果可覆盖新结果。
**修复**：`flatMapLatest` 化，或保存 `Job` 引用进入时 `cancel()` 前一个。

### P2-17 `STATE_ENDED` 时保存的 position 等于 duration，续播表现为「恢复了但什么都没发生」
**位置**：`playback/PlaybackManager.kt:697`（`saveSessionNow`）、`:728-734`（`handlePlaybackEnded`）
**问题**：ENDED 时 `player.currentPosition` 即总时长；下次 `restoreLastSession` 以 `positionMs = duration` 起播，seek 到结尾立即再次 ENDED。
**修复**：`saveSessionNow` 中对 `positionMs` 做 `min(positionMs, duration - 500)`，或检测 ENDED 时存 0。

### P2-18 `playSongById` 在歌曲不存在时静默播放第一首
**位置**：`ui/PlayerViewModel.kt:240`
**证据**：`val index = library.indexOfFirst { it.id == id }.coerceAtLeast(0)`
**问题**：歌曲已从曲库删除时 indexOfFirst 返回 -1，被压成 0 → 播放第一首歌而非无操作（统计页「最爱歌曲」点击即中招）。
**修复**：`indexOfFirst(...).takeIf { it >= 0 } ?: return@launch`。

---

## P3 — 细节 / 代码规范

| # | 位置 | 问题 | 修复建议 |
|---|---|---|---|
| P3-1 | `ui/screens/MeScreen.kt:112-123` | `LazyColumn(heightIn(max=480.dp))` 嵌在 `verticalScroll` 的 Column 内，同轴嵌套滚动互相抢手势（收藏区拖动时外层滚不动） | 改整页单个 LazyColumn，或去掉外层 `verticalScroll` |
| P3-2 | `ui/screens/MeScreen.kt:117,143` | `itemsIndexed(favorites)` / `items(playlists)` 未传 `key`，收藏切换/歌单增删时可见项全部重组（对比 `BrowseScreen.kt:357` 正确传了 `key = { _, song -> song.id }`） | 补 `key = { it.id }` |
| P3-3 | `ui/screens/MeScreen.kt:178,181` | `playlistToDelete!!` 在判空后跨 lambda 执行，状态被置 null 时 NPE | lambda 内先取局部 `val pw = playlistToDelete ?: return@TextButton` |
| P3-4 | `ui/PlayerViewModel.kt:188` | `!favorite.value` 读的是 `WhileSubscribed(5000)` StateFlow 的上次值，快速连点两次写入同一目标值 → 双击无效果（同类：`BrowseScreen.kt:445`、`GroupDetailScreen.kt:190` 用捕获的 `menuSong.favorite` 取反） | 协程内先查库取当前值再取反，或让 repository 提供原子 toggle |
| P3-5 | `ui/PlayerViewModel.kt:251-273` | `loadLyrics` 每次新启协程无取消/无序号，连续切歌时先发的慢请求可能后完成，把旧歌歌词写进 `_lyrics` | 保存 `Job` 引用，进入时 `cancel()` 前一个 |
| P3-6 | `lyrics/LyricsStore.kt:41`；`data/playlist/M3uPlaylistStore.kt:43` | `writeText` 先截断再写，崩溃易残留截断文件 | 写 `.tmp` 后 `renameTo` 原子替换 |
| P3-7 | `playback/ApeTranscoder.kt:127-129` vs `:137-139` | `cacheSize()` 不排除 `.tmp`、`trimCache()` 排除 → 孤儿 `.tmp` 计入统计却永不回收，缓存虚高且 `cacheDir` 缓慢无界增长 | 统一口径：`ensureWav` 入口清理 mtime 超 10 分钟的 `.tmp`，或让 trim 也纳入淘汰 |
| P3-8 | `playback/ApeTranscoder.kt:61-90` | 取消路径无 `try/finally`：ffmpeg 会话不 cancel、`.tmp` 不清理（当前 `scope` 进程级不取消，属潜伏） | `try { ... } finally { runCatching { FFmpegKit.cancel(...) }; tmp.delete() }` |
| P3-9 | `data/playlist/M3uPlaylistStore.kt:31,40,43` | 两个 IO 入口无异常保护；`getExternalFilesDir(null)` 返回 null 时 `File(null, "playlists")` 变相对路径（当前**无任何调用方**，死代码潜伏） | 整体 `runCatching`；`export` 对目录做空值回退并校验 `mkdirs()` |
| P3-10 | `lyrics/M3uCodec.kt:62-64` | `serialize` 不转义标题中的换行，往返损坏格式（`\n` 会被当成新路径条目） | `title.replace("\r\n", " ")` |
| P3-11 | `data/local/dao/PlayHistoryDao.kt:22-31` | `observeRecentSongs` 同毫秒平局返回重复行（同一首歌两条历史 `played_at` 相同） | 平局加 id 决胜：`AND h.id = (SELECT MAX(id) ...)` |
| P3-12 | `data/local/dao/PlayHistoryDao.kt:36` | 注释「清理 7 天前的历史」与实际保留 1 年（`MusicRepository.kt:444`）不符，排查数据表现时误导 | 改为「清理约 1 年前的历史（保留期见 `HISTORY_RETENTION_MS`）」 |
| P3-13 | `playback/PlayMode.kt:20` | `RepeatMode.OFF` 归并为 `PlayMode.SEQUENTIAL`：UI 显示「顺序播放」图标但播放器实际 OFF（列表放完即停）；且 `cyclePlayMode` 从 SEQUENTIAL 点击进入 SHUFFLE，用户看「循环」图标点一次却变随机 | 冷启动若 settings 为 OFF 则先规整，或 UI 增加 OFF 态展示，使图标/状态/行为三者一致 |
| P3-14 | `ui/screens/BrowseScreen.kt:127-129` | 屏幕根部**无条件**收集 albums/artists/folders 三条流：停留在「歌曲」页签时任何曲库变化仍触发三次全库分组计算 | 按当前 `tab` 只收集对应流，或合并为一次分组 pass |
| P3-15 | `playback/PlaybackManager.kt:585` | `next()` 回绕 `seekTo(0, 0L)` 回到 timeline 首位而非 shuffle 顺序的首位 | 用 `player.shuffleOrder` 或 `seekToDefaultPosition()` |
| P3-16 | `playback/PlaybackManager.kt:557-580` vs `:349` | `stop()` 不清 `pendingPlay`，服务重启后可能执行早已作废的播放请求 | `stop()` 里 `pendingPlay = null` |
| P3-17 | `playback/PlaybackManager.kt:241-246` | `bind()` 重复调用时 `settingsJob` 不取消旧任务、`addListener` 重复注册（正常服务生命周期不可达，防御性） | `settingsJob?.cancel()` + 去重 addListener |
| P3-18 | `effect/AudioEffectController.kt:152-188` | `detach()`/各 mutator 无 `@Synchronized`，与 `attachToSession/ensureEffects/applySettings` 的 `@Synchronized` 混用；当前调用点均在主线程故不可达 | 统一 `@Synchronized` |
| P3-19 | `effect/AudioEffectController.kt:214` | `normalizeBands` 对等长输入不钳制，DataStore 存有越界 mB 值时原样进入 `bandLevels` | `return levels.map { it.coerceIn(MIN_LEVEL_MB, MAX_LEVEL_MB) }` |
| P3-20 | `effect/AudioEffectController.kt:247-256` | `applyBands` 仍是「单个 runCatching 包住整个循环」——正是注释 `:43-45` 声称已修复的历史 bug 模式；`setBandLevel` 一旦抛异常，后续频段全部不被设置（用户听到半套 EQ） | 逐段 `runCatching` |
| P3-21 | `playback/PlaybackService.kt:124-131` | `onDestroy` 中 `super.onDestroy()` 先于资源释放，且 `player.release()` 先于 `session.release()`，与官方 demo 顺序相反（Media3 1.4.1 基类为空实现，当前无实际影响） | 调整为 session → player → super |
| P3-22 | `lyrics/LyricsStore.kt:46` | 哈希后缀仅 8 位十六进制（32 位） | `take(12)` 以上或全量 MD5（base 名已含文件名，实际碰撞概率低） |
| P3-23 | `playback/PlaybackManager.kt:788/806` | `Uri.parse(song.path)` 未转义 `#`/`?`（会被解析为 fragment/query）；该 URI 本就是设计为必然解码失败的占位源，影响仅限错误类型 | 可记 `Uri.encode(path)` 让错误类型更准确 |
| P3-24 | `ui/screens/MeScreen.kt:145` | `LazyRow` 内的 `Card(modifier = Modifier.fillMaxWidth())`：LazyRow 给子项的最大宽度即视口宽，每张歌单卡片会占满整行（一屏只见一张） | 若意图为横向多卡预览，改为 `width(160.dp)` 之类；若意图一屏一张则可忽略 |
| P3-25 | `data/local/dao/SongDao.kt:129-130` | `WHERE (:albumId > 0 AND album_id = :albumId) OR (...)` 的 OR 双分支参数化条件使索引失效 | 拆成两条查询按条件分支调用 |

---

## 可维护性改进建议（不改变行为）

1. **抽公共 HTTP 客户端**：`LrcLibLyricsProvider.getJson/getJsonArray` 与 `ItunesMetadataProvider` 的 HttpURLConnection 样板（超时、UA、`use` 关流、非 2xx 处理、`disconnect`）几乎逐行重复，且都手写 URL 拼接。建议抽一个 `HttpUtil.getJsonString(url)` + `Moshi/kotlinx.serialization`（顺带消灭 P1-1 这类 `optString` 陷阱），并统一加 `Accept-Encoding`/重试策略。
2. **`PlaybackManager` 单文件 849 行**：建议按职责拆为「队列/会话持久化」「APE 懒加载协调」「播放控制」三个内部类或扩展文件，`handlePlayerError` 与 `playAtIndex` 的转码重试逻辑可合并为一个 `transcodeAndReplace(index, entry)`。
3. **`PlayerComposables.kt` 与控制图标**：多处 `buttonSize`/`iconSize` 魔法数散落，可收敛为 `ControlIconSizes` 常量对象（与设置页 `SETTINGS_ROW_HEIGHT` 同思路）。
4. **补测试**：`ParsedLyrics.lineIndexAt` 二分、`LrcParser` offset 两遍化、`M3uCodec` 往返、`ApeTranscoder` 缓存 key 变更都是纯逻辑，适合单测锁定；`canRestoreSession` 已有 4 用例，可同样方式覆盖 `resolve()` 的编码往返。
5. **统一 `URLEncoder`/`Uri.encode` 使用规范**：本次 P0-1 的根因是「form 编码 + URI 路径解码」混用。建议在 `Routes` 的 KDoc 里写明约定（路由参数一律 `Uri.encode`，取出时不再手动解码），避免后续新增路由重蹈覆辙。

---

## 已专项核查、确认无问题的重点项

**安全**
- `PlaybackService.SessionCallback.onConnect` 白名单：`ControllerInfo.packageName` 由系统按 calling uid 解析不可伪造；`MEDIA_CONTENT_CONTROL` 为 signature|privileged 级，`checkPermission` 判定第三方不可绕过；硬编码 systemui 等包名必要。
- 前台服务合规：manifest 已声明 `foregroundServiceType="mediaPlayback"` + 对应权限（targetSdk 34）；刻意用 `startService()` 而非 `startForegroundService()`（避免 5 秒 ANR 炸弹）的取舍有文档说明。
- `android:allowBackup="false"` 已按隐私最小化关闭。
- 无导出深链、无 WebView、无敏感数据日志；`ensureServiceStarted` 用显式组件 Intent 且不携带 extras。
- ffmpeg 走参数数组（非 shell 拼接）；`fileName(songId)` 为 Long；`LyricsStore.fileFor` 取 `nameWithoutExtension` 不可能含 `/` → 无路径穿越。
- SQL 全参数化；`escapeLike`（先 `\` 后 `%` `_`）与各处 `ESCAPE '\'` 配套正确。

**数据层**
- Room 迁移 1→4 与导出 schema 逐列、外键（双 CASCADE）、索引完全一致；`PRAGMA foreign_keys = ON` 确认存在于生成的 `PureToneDatabase_Impl`。
- `upsertAll` 的 `INSERT OR IGNORE` + `-1` 判定 + `@Update` 两段式正确，不触发 REPLACE 级联（这是历史上「同步一次歌单全没」的根因，已修）。
- 分片上限：`IN (:ids)` 分片 900、删除分片 500 均 < 999。（注：Room 2.6.1 的批量 `@Insert` 实为逐行单语句预编译，999 上限对批量 insert 并不适用，`UPSERT_CHUNK=50` 无害但注释里「多值 INSERT」的表述不准确。）
- 睡眠定时 elapsed/wall 双基准单次 edit 原子写入，读取优先墙钟，旧数据回退 elapsed，重启语义正确。
- DataStore 每 setter 单 edit 原子，损坏双保险（`ReplaceFileCorruptionHandler` + `catch IOException`），会话保存 800ms 尾部去抖。
- MediaStore 游标 `?.use {}` 关闭、`getColumnIndexOrThrow` 全列投影覆盖、多卷查询按路径去重、albumart 缓存键带卷名均正确。
- `recordPlay`/`recordCompletion` 虽从 `Main.immediate` scope 启动，但 Room suspend DAO 经 `CoroutinesRoom.execute` 派发到查询执行器，不阻塞主线程。

**UI 层**
- NavHost 内 BROWSE/ME/PLAYER/STATS/GROUP_DETAIL 均复用根部（Activity 作用域）`playerVm`，只有无副效用例的 Lib/Metadata/EQ/Stats VM 各自建实例——「进播放页被自动暂停」的根因修复到位。
- 14 个文件所有 Flow 收集均用 `collectAsStateWithLifecycle`，无裸 `collectAsState`。
- `PermissionGate` 授权/拒绝后的 ON_RESUME 重查、观察器注销、通知软权限放行均正确。
- P2-10 高频态下沉（`discreteState` + `positionMs`）与 P1-10（根部只收 `hasCurrentSong`）落地正确，歌词面板 `backContent` lambda 独立重组作用域的写法也对。

**解析层**
- `ChineseText` 按码点遍历 + `appendCodePoint`，CJK 扩展 B 代理对处理正确，无错位。
- `LrcParser` 单行多时间戳、负 offset、1/2/3 位小数、BOM（UTF-8/UTF-16LE/BE）、CRLF、增强型 `<0,120>` 剥除、畸形行跳过均正确；`decode` 的 BOM → 严格 UTF-8 → GBK → GB18030 链合理。
- `ItunesMetadataProvider` 用户输入经 `URLEncoder` 注入 URL、8s 超时、`!= "null"` guard 齐全。
- `PlayMode` 三态循环本身正确；`next()/previous()` 常规路径无问题。

---

## 建议修复顺序

1. **P0-1 导航双重解码**（改 2 行，崩溃面覆盖全部含 `%`/`+` 的专辑歌手名，真实曲库必踩）
2. **P0-2 `replaceMediaItemUri` 边界 + `ensureWav` 保护**（改 3 处，APE 队列主路径）
3. **P1-1 LRCLIB `"null"`**（改 4 处，歌词下载功能当前整体不可用）
4. **P1-2 / P1-3 恢复竞态**（同一类问题的两个窗口，一起修）
5. **P1-4 ApeTranscoder 锁原子性**、**P1-5 均衡器主开关语义**
6. P2 按影响面分批：P2-1/P2-2/P2-3（数据与索引，可合并一个 v5 迁移）、P2-5/P2-6（DAO 健壮性）、P2-11/P2-17/P2-18（UI 写入正确性）、其余随迭代
7. P3 随日常维护

---

## 修复状态（2026-10-25，v1.5.0 / code 22 交付）

按上述顺序执行完毕并通过验证（单测 64/64 + assembleRelease + apksigner v2）：

- ✅ **P0-1 / P0-2 / P1-1**：已修（含 `optLyrics` 扩展过滤 AOSP `org.json` 的 `"null"` 字面量）。
- ✅ **P1-2**：`restoreLastSession` 在 DB 读取挂起后、起播前二次校验 `canRestoreSession`。
- ✅ **P1-3**：`bind()` 执行 `pendingPlay` 后清 `pendingRestore`；`setQueueAndPlay` 置 `pendingPlay` 时同步清 `pendingRestore`。
- ✅ **P1-4**：`songLocks.computeIfAbsent` 原子建锁，删除 `LOCK_MAP_MAX` 清理逻辑。
- ✅ **P1-5**：`applyBass`/`applyVirtualizer` 统一为 `shouldEnableEffect(enabled, strength)`；`needsEffects` 改为主开关单独判定；新增 4 个纯函数单测锁死。
- ✅ **P2-1**：`getSongsMissingCover` 增加占位 URI 判据 + `hasChanged` 补「变坏」分支，重扫即清占位。
- ✅ **P2-2 / P2-3**：Room version 5 + `MIGRATION_4_5` 纯 CREATE INDEX 四个；实体 `@Index` 注解同步；迁移测试扩为 3→4→5 全链路 + 4→5 专测（`PRAGMA index_list` 实证）。
- ✅ **P2-5**：`observeAllWithSongs` / `observeWithSongs` 的 combine 后加 `distinctUntilChanged`。
- ✅ **P2-6**：`MusicRepository.getSongs` 空列表短路。
- ✅ **P2-11**：SongInfoSheet 本地 `year` 状态随 autoFill/apply 更新；`undoAutoFill` 回传原始实体、撤销时表单一并还原。
- ✅ **P2-17**：`saveSessionNow` 位置钳到 `duration - 500` 以内。
- ✅ **P2-18**：`playSongById` 找不到歌曲时无操作。
- ✅ **P2-12**（确认在位）：`warmUpFollowingApe` 的 `ensureWav` 已包 `runCatching`（P0-2 轮次覆盖）。
- ✅ **P2-14**：`stop()` / `clearQueue()` / `doSetQueueAndPlay` 生效新队列前统一复位 `apeRetryIndex`。

**未修（后续迭代）**：P2-4（album_id 跨卷，需加列改造）、P2-7、P2-8、P2-9、P2-10、P2-13、P2-15、P2-16、P3 全部。
交付细节见 `dist/v1.5.0_r2_fixes_2026-10-25.txt`。

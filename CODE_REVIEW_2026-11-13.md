# PureTone 代码审查报告（第四轮）

- **审查日期**：2026-11-13 18:0x（用户选择「方向 1 + 方向 3」后执行）
- **范围**：`v1.9.7..HEAD`（`aff7704` A 方案 + C 项 + 取流一致性修复、`c42f43a` bump、`7dcfb3c` UI 常量收口）的**新增/改动代码**；另含暂缓项 `#197` / `#220` 的解封可行性评估
- **方法**：`git diff v1.9.7..HEAD` 逐文件通读 + 生产侧调用链回溯 + 与既有同类实现横向对比 + 构建期实测
- **结论概览**：发现 **3 条中风险 + 3 条低风险**（均为窄窗/可维护性，非线上缺陷）；**红线一处未动**；本轮**未改任何源码**，按流程先给方案待确认。`#197` 无真机阻塞；`#220` 单翻 flag 实测编译失败，建议维持暂缓。

---

## 一、审查范围

`git log --oneline v1.9.7..HEAD` 共 6 个提交，代码改动集中在 10 个主源文件（+ 6 个测试文件）：

| 文件 | 改动 | 归属 |
| --- | --- | --- |
| `playback/CastSessionController.kt` | +49 | A 方案 `onCastInterruptedSeekRecover` |
| `playback/cast/CastQueueCoordinator.kt` | +60/- | 3s 保护窗 + 短确认窗 + `seekRecover` 分支 |
| `playback/cast/CastModels.kt` | +9 | `CastState.lastSeekMs` |
| `playback/CastSessionPorts.kt` / `PlaybackController.kt` / `DlnaCastManager.kt` | +8 / +1 / +4 | `noteUserSeek()` 注入链 |
| `playback/cast/CastHttpServer.kt` | +115 | `servableSize` / `countsAsFetch` / `buildFileHeader` / `skipShortfallReason` |
| `data/metadata/TtmlToLrc.kt` | +11 | 缺 `</p>` 容错 |
| `lyrics/LyricsStore.kt` | +22 | 按码点截断 |
| `ui/screens/CastRemoteParts.kt` | +3/-2 | `DisconnectHeight` 常量收口 |

---

## 二、发现清单

| # | 级别 | 位置 | 一句话 |
| --- | --- | --- | --- |
| R4-1 | 中 | `DlnaCastManager.kt:550-565` | 换曲/重投时 `lastSeekMs` 未清零，保护窗跨曲泄漏 |
| R4-2 | 中 | `CastSessionController.kt:461-486` | seek-recover 异步恢复缺代次守护，且会误清 `uiState.error` |
| R4-3 | 中 | `CastQueueCoordinator.kt:252-258` | seekRecover 分支绕过 `MAX_CONSECUTIVE_INTERRUPT=2` 计数 |
| R4-4 | 低 | `CastQueueCoordinator.kt:330-344` | 窗口顺延时不重算保护窗，3s 口径可被无限延长 |
| R4-5 | 低 | `CastQueueCoordinator.kt:135-141` | `PendingStop.windowMs` 默认值 `0L` 是静默陷阱 |
| R4-6 | 低 | `CastHttpServer.kt` `buildFileHeader` | `mimeType` 未做 CRLF 防御（纵深防御，非缺陷） |

### R4-1｜`lastSeekMs` 跨曲泄漏（中）

`DlnaCastManager.kt:550-565` 在 `cast-ok` 时重置了 `castStartMs` / `lastFetchMs` / `progressMs` / `durationMs` / `isEstablished`，**唯独漏了 `lastSeekMs`**：

```kotlin
_state.update {
    it.copy(
        ...,
        castStartMs = nowMs,
        lastFetchMs = 0L,      // 有
        isEstablished = established
        // lastSeekMs 未列出 → 沿用上一首的值
    )
}
```

`stopCast()`（line 714）用 `_state.value = CastState()` 全量清零，所以只有「停止投屏」会清；**换曲、自动推进、`recastCurrent()` 重投走的都是 `copy`，`lastSeekMs` 保留**。

后果：用户在 A 曲拖过进度条后 3s 内切到 B 曲（或 A 曲重投），B 曲开播初期若渲染器报曲中 STOPPED，`CastQueueCoordinator.onState`（line 362）仍判 `recentSeek = true` → 走 1s 短窗 + 就地 play，而非正常的 2.5s 确认 + 重投/推进。属窄窗误判（≤3s 且需渲染器配合），但与 R4-3 叠加会放大。

**建议**：`cast-ok` 的 copy 补 `lastSeekMs = 0L`；`CastState.lastSeekMs` KDoc 补「每次新投送清零」；补 1 例 JVM 单测（构造带非零 `lastSeekMs` 的新代次 state，断言不进 seekRecover）。零红线影响。

### R4-2｜异步恢复缺代次守护 + 误清错误提示（中）

`CastSessionController.kt:461-486` 的 `scope.launch` 只校验 `isCasting`，**没有代次校验**；而同文件的 `onCastTrackEnded()`（line 376 / 386）有成熟的 `generation` + `st.pushGeneration != generation` 守护：

```kotlin
override fun onCastInterruptedSeekRecover(progressMs: Long) {
    ...
    scope.launch {
        if (!dlnaCast.state.value.isCasting) return@launch
        dlnaCast.play("seek-recover")
        delay(SEEK_RECOVER_OBSERVE_MS)          // 800ms
        ...
        if (recovered) {
            uiState.update { it.copy(error = null) }   // ← 无差别清空
            return@launch
        }
        recastCurrent()
        delay(RECAST_SEEK_DELAY_MS)                  // 再 800ms
        if (!dlnaCast.state.value.isCasting) return@launch
        dlnaCast.seek(progressMs)                    // ← 可能作用到新曲
        ...
    }
}
```

A 方案的异步窗口（800ms，降级时再 800ms + 重投）比既有 `onCastInterrupted` 更长。期间用户切歌/停止/换设备：协程醒来仍会 `seek(progressMs)` 把**新曲**进度拉回旧位置；恢复成功分支 `error = null` 会把期间新写入的错误提示一并清掉。

补充：既有 `onCastInterrupted()`（line 432-445）同样无代次守护，所以不是 A 方案独有；但新路径窗口更长、且多了 `error = null` 清空动作，风险放大。

**建议**：入口记 `val generation = dlnaCast.state.value.pushGeneration`，在 `delay` 后与 `seek` 前都校验 `st.pushGeneration == generation && st.isCasting`；`error = null` 改为「仅当当前 error 是本分支写入的那条提示时才清」（或改用独立 notice 通道）。既有 `onCastInterrupted` 可另开一小刀补同款守护。

### R4-3｜seekRecover 绕过「同位置连续中断」上限（中）

`CastQueueCoordinator.onInterruptConfirmed()`（line 252-258）：

```kotlin
private fun onInterruptConfirmed(progressMs: Long, seekRecover: Boolean) {
    playingSeen = false
    if (seekRecover) {
        sink.onCastInterruptedSeekRecover(progressMs)
        return                                  // ← 直接 return
    }
    val sameSpot = ...                          // 以下计数不在 seekRecover 路径执行
    consecutiveInterrupt = ...
    lastInterruptPosMs = progressMs
    if (consecutiveInterrupt >= MAX_CONSECUTIVE_INTERRUPT) { ... abandon ... }
    sink.onCastInterrupted(progressMs)
}
```

保护窗内反复 STOPPED → 反复就地 play（每次 800ms），既不累计同位置计数、也不触发 line 263 的「放弃重投改按曲末推进」保护。叠加 R4-1（重投不清 `lastSeekMs`），降级重投后若仍在 3s 窗口内又报 STOPPED，会再进 seekRecover，形成**窗口内的多轮循环**，直到保护窗自然过期。

顺带订正一处注释口径：`CastSessionController.kt:458-459` 写「但不调 `noteUserSeek`，否则会重置 `lastSeekMs` 再次进本分支递归」——不刷新 `lastSeekMs` **并不能**阻止再次进本分支（窗口内它本来就还在），只是让它 3s 后自然过期，因而不会无限递归。结论「不调」是对的，理由不准确。

**建议**：seekRecover 分支 return 前也更新 `lastInterruptPosMs` / `consecutiveInterrupt`（或至少记一次尝试计数），让降级后的重投仍受上限保护；补单测断言「同位置多次 seekRecover 后仍会 abandon」。**不动 `MAX_CONSECUTIVE_INTERRUPT=2` 数值**。

### R4-4｜窗口顺延不重算保护窗（低）

`onState` line 330-344 的顺延分支：

```kotlin
if (st.lastFetchMs > pending.sinceMs) {
    pendingStop = pending.copy(sinceMs = st.lastFetchMs)   // seekRecover / windowMs 原样保留
    schedulePendingConfirm()
    return
}
```

3s 的 `seekProtectMs` 只在**新建 pending**（line 362）时判一次。电视在 STOPPED 后持续来取流时窗口被反复顺延，`seekRecover=true` 与 1s 短窗可以一直生效，即使早已超过用户拖动后的 3s——名义 3s 的保护窗实际可无限延长。

**建议**：顺延分支按 `nowProvider()` 重算 `recentSeek`，同步刷新 `seekRecover` 与 `windowMs`；补虚拟时钟单测（推进 >3s 后顺延，断言回落普通中断路径）。

### R4-5｜`PendingStop.windowMs = 0L` 默认值（低）

`CastQueueCoordinator.kt:135-141` 注释写明「两个构造点均显式传入，此处默认值不参与实际创建」，但默认值是 `0L`。`schedulePendingConfirm()`（line 225）用 `pending.windowMs - elapsed` 算剩余时长：未来若新增构造点漏传，会静默变成「0ms 立即确认」，行为改变且极难排查。

**建议**：去掉默认值改成必传 `windowMs: Long`，让编译器守住。零运行时变化。

### R4-6｜`buildFileHeader` 的 `mimeType` 未防 CRLF（低，纵深防御）

`CastHttpServer.kt` 新增的纯函数直接 `append("Content-Type: ").append(mimeType)`。当前 `mimeType` 只来自 `CastSourcePolicy.mimeTypeOf()` 内部常量表（audio/flac 等），不可能含 CRLF，**不是实际缺陷**。

**建议**：可不改；若要与 `DidlBuilder.safeMimeType` 的白名单口径对齐，加一行 `require(!mimeType.any { it == '\r' || it == '\n' })`。

> 11-13 19:41 落地订正：实际未采用 `require`，改为复用 `DidlBuilder.safeMimeType()` 白名单回退，理由见 §十。

---

## 三、复核确认无问题（避免误报）

- **红线一处未动**：`CastStopPolicy.isConfirmed()` 仍是 `lastFetchMs > stoppedSinceMs`（line 101）；`MID_TRACK_END_TOLERANCE_MS = 10_000L`、`CONFIRM_MS = 2_500L`、`MAX_CONSECUTIVE_INTERRUPT = 2`、AutoAdvance 15s 全部零改动。
- `playingSeen = false` 从 `onInterruptConfirmed` 中段移到函数开头是**等价改写**：两条语句之间没有对 `playingSeen` 的读取。
- `countsAsFetch(method) = (method == "GET")` 与 `serve()` 的 `method != "GET" && method != "HEAD" → 405` 严格比较一致，无大小写/其它方法漏洞；HEAD 不再伪造 `lastFetchMs > 0L` 建立确认，是正确修复。
- `servableSize()` 的 `length()` / `isFile` / `canRead` 是三次独立 IO（理论 TOCTOU），但 `sendFile` 的 `FileInputStream` 会抛且已有 `http-copy-fail` / `skipShortfallReason` 归因，可接受。
- `TtmlToLrc` 的 `minOf(closeEnd, nextOpen)` 缺闭合容错、`LyricsStore.truncateToBytes` 按 Unicode 码点迭代，经复核正确；BMP/ASCII 片段长度与旧实现一致，正常歌名零变化。
- `CastRemoteParts.kt` 的 `DisconnectHeight → ControlIconSizes.Button` 同 48.dp，DEX 常量池已实证零功能串差异。

---

## 四、改码方案（待确认，本轮未执行）

按「先给方案、确认才改码」的既定流程，建议按此顺序拆刀，**每刀独立单测 + `testDebugUnitTest` / `lintDebug` / `detekt`**：

| 刀 | 内容 | 风险 | 说明 |
| --- | --- | --- | --- |
| 1 | R4-1 + R4-5（`lastSeekMs` 清零 + 去掉默认值） | 极低 | 纯状态清理 / 编译期约束，1 行 + 2 例单测 |
| 2 | R4-4（顺延时重算保护窗） | 低 | 状态机时序，需虚拟时钟单测 |
| 3 | R4-2（代次守护 + 错误提示清空的边界） | 中 | 触碰异步恢复路径，建议单独一刀并配真机复测 |
| 4 | R4-3（seekRecover 计入中断上限） | 中 | 触碰「放弃重投」保护，必须与 `174553` 基线对照，红线数值不动 |

R4-6 可视情况并入任一小刀或明确「不改」。

---

## 五、`#197` Macrobenchmark 真实 Baseline Profile：阻塞

| 检查 | 结果 |
| --- | --- |
| `adb devices -l` | **空**（daemon 启动成功但无设备/模拟器），与 2026-11-08 执行清单记载一致 |
| `benchmark` 模块 | 存在（`/d/PureTone/benchmark`） |
| `app/src/main/baselineProfiles/baseline-prof.txt` | 17,315 B 手写兜底；文件头注释明确「具备真机/模拟器条件后改用 Macrobenchmark 实测生成」 |

**结论**：无真机即无法实测生成，本轮**不硬做、不伪造**。解封条件：设备/模拟器在线且 `adb devices -l` 可见 → 生成后必跑 `scripts/verify-baseline-profile.sh` 校验（历史坑：DI 注解改动后手写 `baseline-prof.txt` 的旧类名会被 AGP 静默丢弃）→ 再跑全量门禁。

---

## 六、`#220` KSP1→KSP2：影响面与本轮实测

### 6.1 版本与历史事实

| 项 | 当前值 |
| --- | --- |
| Kotlin / KSP | `2.0.21` / `2.0.21-1.0.28` |
| AGP / Gradle | `8.6.1` / 本地 `8.7`（CI wrapper `8.14.6`） |
| Room / Hilt | `2.6.1` / `2.51.1` |
| `gradle.properties:7-8` | `# Room 多对多 @Relation 在 KSP2 下报 "Cannot find setter for field"，回退 KSP1` + `ksp.useKSP2=false` |

项目在 2026-11-01 已由用户拍板**暂缓**，留待 Kotlin/AGP 大版本升级时一并做（见 `v1.8.5` / `v1.8.8` / `v1.9.2` / `v1.9.6` 多份发布说明与 `docs/PureTone后续开发可行性建议_2026-10-31.md`）；`CODE_REVIEW.md:540/545` 的建议是「升级 Room 到 2.7+ 并切回 KSP2」。KSP2 的 processor 用 `ksp(...)` 配置，Room / Hilt 两个 processor 都在 `app/build.gradle.kts:342/353`。

### 6.2 官方兼容性（外部事实，2026-11-13 检索）

- **Room**：官方 release notes 明确 **2.7.0（2025-04-09）才「Support for KSP2 is also added and is recommended」**，并要求 Kotlin 2.0+；社区 issue（`google/ksp#1896`）确认 **Room 2.6.x 与 KSP2 不能共存**。
- **Dagger / Hilt**：官方 issue #4303 明确「Dagger / Hilt currently does not support KSP2」；KSP2 相关崩溃到 **Dagger 2.53.1（2025-04-10）** 才修（#4496）；Hilt Gradle Plugin 的 KSP2 配置更新在 2024-12-20 才合入，晚于 2.51.1。

即当前 **Room 2.6.1 + Hilt 2.51.1 两个 processor 都不支持 KSP2**，单翻 `ksp.useKSP2=true` 在原理上不可能通过。

### 6.3 本轮运行期实测（新增证据）

不改任何文件，仅命令行覆盖 flag：

```bash
ANDROID_HOME=/d/devtools/android-sdk /d/devtools/gradle-8.7/bin/gradle.bat \
  :app:kspDebugKotlin --no-build-cache -Pksp.useKSP2=true --console=plain
```

结果：`KSP2_PROBE_EXIT=1`、`BUILD FAILED in 1m 23s`：

```text
> Task :app:kspDebugKotlin
e: [ksp] java.lang.IllegalStateException: unexpected jvm signature V
> Task :app:kspDebugKotlin FAILED
> A failure occurred while executing com.google.devtools.ksp.gradle.KspAAWorkerAction
```

`KspAAWorkerAction` 是 **KSP2 的 Analysis API worker**（KSP1 走另一套 worker），证明 flag 确实生效、失败发生在 KSP2 路径上。日志：`.workbuddy/ksp2_probe_20261113.log`（gitignore，本地证据）。

> 注意：后台 shell 的整体退出码为 0 只是因为命令末尾有 `tail`；**Gradle 的真实退出码是 `KSP2_PROBE_EXIT=1`**，判读以该行为准。

### 6.4 解封所需联动改动（若要真做）

1. Room `2.6.1 → ≥2.7.0`：KMP 重构、Kotlin CodeGen 默认开启、`room-ktx` 合并、`room.schemaLocation` / schema 导出行为变化；项目对 Room 迁移敏感（迁移测试造库须补索引 `createSql`）。
2. Hilt `2.51.1 → ≥2.53.1`（建议 `2.56.2`）：DI 注解生成类变化，之后**必跑** `scripts/verify-baseline-profile.sh`。
3. KSP 版本前缀须与 Kotlin 版本对齐；若同时升 Kotlin/AGP，则 Compose compiler、detekt、lint FIR 缓存、CI 26 步都要重验。
4. 验证链：全量门禁（单测 / 双 assemble / apksigner / lint / detekt / 行数 / 16KB / LGPL）+ Room 迁移测试 + Baseline Profile 类名核验 + DEX 常量池/产物字节比对。

**建议**：维持 `#220` 暂缓。这不是「只改一行 flag」的小刀，而是工具链大版本联动升级，应作为独立工作线在明确授权后单独排期。

---

## 七、本轮边界与证据

- **未修改任何 app 源码 / 构建配置**；`gradle.properties` 的 `ksp.useKSP2=false` 保持原样（实测只用 `-P` 命令行覆盖）。
- 未 commit / bump / tag / push / Release / 出包；`main` 仍 `7dcfb3c`、`ahead origin 1` 未 push；正式 v1.9.8 三 ABI 字节未动。
- 实测后已跑一次 KSP1 恢复构建清理中间产物，结果见文末「实测后恢复」。
- 证据文件：`.workbuddy/ksp2_probe_20261113.log`、`.workbuddy/ksp1_restore_20261113.log`（均 gitignore）、`CODE_REVIEW_2026-11-09.md`（第三轮）、`dist/v1.9.8_VERIFY-20261113-104721_172347-172915判读_*`（真机判读）。

### 实测后恢复

```text
KSP1_RESTORE_EXIT=0
> Task :app:kspDebugKotlin
BUILD SUCCESSFUL in 54s
16 actionable tasks: 2 executed, 14 up-to-date
```

即用不带 `-P` 覆盖的同一任务（`:app:kspDebugKotlin --no-build-cache`）重跑一次，确认 `gradle.properties` 的 `ksp.useKSP2=false` 仍生效、KSP2 失败留下的中间产物已被 KSP1 正常产物覆盖，工作区可继续开发。日志：`.workbuddy/ksp1_restore_20261113.log`。

---

## 八、第 1 刀落地记录（11-13 追加）

用户回复「确认」后按 §五 第 1 刀执行，**仅 R4-1 + R4-5 落地，其余 5 项仍为待确认的发现**：

| # | 状态 | 落地方式 |
| --- | --- | --- |
| R4-1 | **已修复** | `cast-ok` 重置清单抽成 `CastState.forNewCast` 纯函数并补 `lastSeekMs = 0L`；字段集合机器核对＝旧 12 项 + 该 1 项 |
| R4-5 | **已修复** | `PendingStop.windowMs` 去掉 `0L` 默认值改必传（编译期守护，零运行时变化） |
| R4-2 / R4-3 / R4-4 | **已修复（见 §九）** | 11-13 19:08 授权「继续第2-4刀」后落地 |
| R4-6 | **已修复（见 §十）** | 11-13 19:41 授权「继续 R4-6 小刀」后落地：复用既有 `DidlBuilder.safeMimeType()` 白名单回退 |

门禁：`:app:testDebugUnitTest :app:lintDebug :app:detekt --no-build-cache` → `GATE_EXIT=0` / `BUILD SUCCESSFUL in 5m13s`；**103 类 / 1002 例 / 0 失败**（新增 `CastStateResetTest` 2 例）；lint 0 issue、detekt 0 finding、198 主源文件 ≤1200 行。红线（`lastFetchMs>0L` / 曲末 10s / 上限 2 / `CONFIRM_MS` / AutoAdvance 15s）一处未动。

完整证据与改动清单见 `dist/v1.9.8_第1刀_R4-1-R4-5_改码与门禁_2026-11-13.md`，日志 `.workbuddy/gate_20261113_r4k1.log`。本轮未 commit / bump / tag / push / Release / 出包。

---

## 九、第 2–4 刀落地记录（11-13 19:08「继续第2-4刀」授权）

| # | 状态 | 落地方式 |
| --- | --- | --- |
| R4-4 | **已修复** | 两个顺延出口（状态帧 + 定时器兜底）共用 `postponePendingStop()`，按 `nowProvider()` 重算 `recentSeek`，同步刷新 `seekRecover` / `windowMs`；超 3s 回落普通中断路径 |
| R4-2 | **已修复** | 观察窗用「代次 + 曲目标识」守护，降级重投后用「曲目标识 + 投屏态」守护；恢复成功只清自己写入的 `SEEK_RECOVER_NOTICE` |
| R4-3 | **已修复** | seekRecover 分支也累计 `lastInterruptPosMs` / `consecutiveInterrupt`，abandon 仍只在普通中断路径生效，`MAX_CONSECUTIVE_INTERRUPT=2` 未动 |

**两处对报告建议的订正**（详见证据文档 §三 / §四）：
1. `recastCurrent()` → `PlaybackManager.recastCurrentIfCasting()` → `castSong()` 会 `castEpoch.incrementAndGet()` 并写 `pushGeneration = epoch`，照搬「seek 前校验代次不变」会挡掉 A 方案降级续播；第二段 delay 改用曲目标识守护。
2. abandon 的动作是曲末语义 sink（`onCastTrackEnded` / `onCastQueueFinished`），若在 seekRecover 分支直接 abandon，用户 3s 内拖两次进度条就会跳歌；改为只累计计数，把上限留给降级后的整套重投——正是「让降级后的重投仍受上限保护」的主目的。

顺带订正 `CastSessionController` KDoc 中 `noteUserSeek` 的理由（不刷新 `lastSeekMs` 只是让 3s 保护窗自然过期，并不能阻止再次进分支）。

门禁：`:app:testDebugUnitTest :app:lintDebug :app:detekt :app:assembleDebug --no-build-cache` → `GATE_EXIT=0` / `BUILD SUCCESSFUL in 5m5s`；**103 类 / 1009 例 / 0 失败**（本轮 +7）；lint 0 issue、detekt 0 finding、198 主源文件 ≤1200 行、`assembleDebug` 成功。每刀另有定向单测（`r4k2` / `r4k3` / `r4k4` 日志，均退出码 0）。红线数值一处未动。

证据：`dist/v1.9.8_第2-4刀_R4-4-R4-2-R4-3_改码与门禁_2026-11-13.md`、`.workbuddy/gate_20261113_r4k2-4_full.log`。本轮未 commit / bump / tag / push / Release / 出包。**R4-6 已于 §十 落地**；仍待确认的是「既有 `onCastInterrupted` 补同款代次守护」小刀，A 方案真机验收也未闭环。

---

## 十、第 5 刀落地记录（11-13 19:41「继续 R4-6 小刀、然后提交commit」授权）

| # | 状态 | 落地方式 |
| --- | --- | --- |
| R4-6 | **已修复** | `CastHttpServer.buildFileHeader()` 的 `mimeType` 统一过 `DidlBuilder.safeMimeType()` 再拼进 `Content-Type`；白名单 `[\w.+-]+/[\w.+-]+` 天然排除 CRLF 及 `"` / `;` / 空格等头注入字符，非法值回退 `application/octet-stream` |

**对报告建议的第三处订正**：§二 字面建议 `require(!mimeType.any { … })`，但 `buildFileHeader` 抛 `IllegalArgumentException` 只会被 `handle()` 外层 `runCatching` 记成 `handle failed`，电视收不到任何响应头 —— 把「脏 MIME」升级成「取流失败」，与纵深防御本意相反。项目既有 `DidlBuilder.safeMimeType()` 明确「回退而非抛异常：投屏链路不该因为一个脏 MIME 就崩掉」，且让 HTTP `Content-Type` 与 DIDL `protocolInfo` 对同一脏值口径一致。`CastSourcePolicy.mimeTypeOf()` 返回值全是单段 `type/subtype`，正常路径逐字不变（9 个扩展名逐一单测锁定）。

新增 3 例 JVM 单测：CRLF 注入逐字回退 / 8 种脏值都不破坏头结构 / 9 个正常扩展名响应头逐字不变。改动仅 `CastHttpServer.kt`（+9/-1）与 `CastHttpServerTest.kt`（+60）。

门禁：定向 `--tests CastHttpServerTest` → `R4K6_TEST_EXIT=0` / `BUILD SUCCESSFUL in 1m48s`；全量 `:app:testDebugUnitTest :app:lintDebug :app:detekt :app:assembleDebug --no-build-cache` → `GATE_EXIT=0` / `BUILD SUCCESSFUL in 4m39s`；**103 类 / 1012 例 / 0 失败**（本轮 +3，四刀累计 +12）、lint 0 issue、detekt 0 finding、197 个主源 Kotlin 文件最大 1069 行零超限、`assembleDebug` 成功。全量日志仅既有 `CastNetworkLog.kt:180` 弃用告警 1 条，零新增告警。日志 `.workbuddy/gate_20261113_r4k6_unit.log` / `gate_20261113_r4k6_full.log`。

证据：`dist/v1.9.8_第5刀_R4-6_MIME头注入防御_改码与门禁_2026-11-13.md`。红线数值一处未动，未碰 `DidlBuilder` / `CastSourcePolicy` 本身，未出包装机。

**四轮审查 6 项发现 R4-1~R4-6 至此全部落地。** 按 19:41 单独授权执行一次本地 commit（仅本地，未 push）；未 bump / tag / push / Release / 出包，正式 v1.9.8 三 ABI 字节未动。剩余代码待确认项只有「既有 `onCastInterrupted()` 补同款代次守护」小刀；`#197`（无真机）、`#220`（KSP2 暂缓）与 A 方案真机验收仍未闭环。

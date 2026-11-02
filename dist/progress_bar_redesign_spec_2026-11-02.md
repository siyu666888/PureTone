# PureTone 播放进度条重建设计规范

> 目标组件：播放页进度条 `PlayerProgressBar`
> 现状位置：`app/src/main/java/com/puretone/player/ui/components/PlayerComposables.kt:410`
> 调用点：`PlayerScreen.kt:ProgressSection`（line 525，2Hz 高频重组已下沉到组件内，P2-10）
> 数据来源：`PlayerViewModel.positionMs`（StateFlow<Long>）+ `PlayerViewModel.seek(Long)`；状态快照 `PlaybackUiState`（`PlaybackManager.kt:65`）

## 0. 现状审计（为什么需要重建）

| 项 | 现状 | 缺口 |
|----|------|------|
| 点击跳转 | ✅ `awaitEachGesture` 松手定位 | — |
| 拖拽跳转 | ✅ 拖动更新 `dragFraction`，松手 `onSeek` | 拖拽中实时 seek 与节流策略未显式定义 |
| 时间预览气泡 | ⚠️ 仅 **拖拽时** 显示 | **悬停（鼠标/触控板）预览缺失** |
| 已播放/未播放 | ✅ 两段（surfaceContainerHighest + primary） | — |
| 缓冲中进度 | ❌ 无 | 需新增 `bufferedPositionMs` 数据 + 缓冲段视觉 |
| 默认/悬停/拖拽视觉反馈 | ⚠️ 仅拖拽出气泡 | 悬停态滑块/轨道加粗、聚焦环缺失 |
| 加载/播放/暂停/结束 适配 | ❌ 恒为确定态实条 | LOADING 不确定态、ENDED 满条、ERROR 着色缺失 |
| 响应式 | ✅ `BoxWithConstraints` 按 px 计算 | 宽屏/字体缩放下气泡高度未校核 |
| 可访问性 | ✅ `progressBarRangeInfo` + `setProgress` | 缺 ±步进动作、焦点环、reduce-motion、读屏文案模板 |

**结论**：在保留现有手势内核与 2Hz 重组隔离的前提下，补齐「悬停预览 + 缓冲段 + 生命周期态视觉 + 多交互态反馈 + 强化 a11y」。

---

## 1. 功能需求（Functional Requirements）

| 编号 | 需求 | 验收标准 |
|------|------|----------|
| **FR-1** | 点击跳转（tap-to-seek） | 在轨道任意 x 处按下并抬起，播放定位到该比例对应时间；点击热区为整条 48dp 高 |
| **FR-2** | 拖拽跳转（drag-to-seek） | 按下后移动可连续预览，松手提交最终位置；拖拽期间进度显示为拖拽值（不被 2Hz `positionMs` 覆盖） |
| **FR-3** | 悬停时间预览气泡 | 鼠标/触控板悬停（无按键）时，在悬停 x 处显示时间气泡；离开即隐藏；**不触发 seek** |
| **FR-4** | 三态进度区分 | 同时绘制 已播放 / 缓冲中 / 未播放 三段，缓冲段介于已播与未播之间 |
| **FR-5** | 多交互态视觉反馈 | 默认 / 悬停 / 拖拽 / 键盘聚焦 四态下轨道高度、滑块尺寸与阴影、配色有可辨识差异 |
| **FR-6** | 生命周期态适配 | 加载（不确定动画）、播放（静止实条）、暂停（静止实条）、结束（满条）、错误（着色+禁用）各有明确外观 |
| **FR-7** | 响应式布局 | 宽度随父容器自适应；尊重 RTL；在字体放大（Font Scale）下气泡与热区不溢出 |
| **FR-8** | 可访问性 | 48×48dp 触控目标；TalkBack 进度条角色 + 当前/总时长朗读；方向键 ±步进；聚焦环；尊重「减少动效」 |
| **FR-9** | 动画节流 | 拖拽/悬停期间气泡与轨道高度变化走短 tween；`reduce-motion` 时退化为瞬时切换 |

---

## 2. 交互逻辑（Interaction Logic）

### 2.1 手势状态机

```
        pointerDown(x)
   RESTING ───────────────► DRAGGING
      │                        │  pointerMove(x) → dragFraction = clamp(x/width)
      │                        │  bubble 跟随 dragFraction
      │                        │
      │  pointerUp             │  pointerUp
      └──────────► seek(finalFraction) ──► RESTING
                  （tap 与 drag 共用：按下即进入 DRAGGING，
                   位移≈0 即视为 tap，位移>阈值即视为 drag）

   HOVER（仅指针设备，无按键）：
      onHoverEnter(x) → HOVER（显示滑块+气泡预览，不 seek）
      onHoverMove(x)  → 气泡跟随 x
      onHoverExit     → RESTING（隐藏）

   KEYBOARD_FOCUSED：
      focus 获得 → 显示焦点环；方向键 Left/Right ±STEP；Home/End 跳 0/尾；
      seek 提交；focus 丢失 → RESTING
```

### 2.2 关键交互规则

1. **坐标映射（含 RTL）**：`fraction = clamp( (x - thumbRadius) / (width - 2*thumbRadius) )`；RTL 下 `fraction = 1 - fraction`（`LocalLayoutDirection` 判定）。
2. **拖拽期间冻结真实进度**：`display = dragFraction ?: fraction`。`dragFraction!=null` 时即便 `positionMs` tick 也只渲染拖拽值，松手置 `null` 并 `onSeek`。
3. **tap vs drag 区分**：以「按下到抬起的最大水平位移」是否超过 `ViewConfiguration.touchSlop` 区分；二者最终都走 `onSeek(final)`，但 drag 过程中每帧更新预览、tap 无中间预览帧。
4. **悬停不 seek**：`onHoverMove` 仅更新一个独立的 `hoverFraction` 用于气泡定位，绝不调用 `onSeek`；`onHoverExit` 清空。
5. **键盘步进**：默认 `STEP = 5_000ms`（可在设置暴露）；`setProgress { }` 语义动作仍保留用于 TalkBack 连续调节。
6. **加载态禁用交互**：`phase == LOADING` 时忽略 pointer/键盘 seek，气泡不显示，避免在无 duration 时定位到无意义位置。

### 2.3 数据契约（落地需改）

`PlaybackUiState` 新增数值字段（布尔 `isBuffering` 已存在，但缺位置值）：

```kotlin
data class PlaybackUiState(
    // ...existing...
    val bufferedPositionMs: Long = 0L,   // 新增：player.contentBufferedPosition
)
```

组件签名演进（建议收敛为状态对象，便于 2Hz 隔离）：

```kotlin
data class ProgressState(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,        // = min(bufferedPositionMs, durationMs)
    val phase: ProgressPhase,    // 见 3.2
)

@Composable
fun PlayerProgressBar(
    state: ProgressState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
)
```

> 维持 P2-10 原则：`positionMs`/`bufferedMs` 的高频收集仍下沉在 `PlayerProgressBar` 内部（`collectAsStateWithLifecycle`），根部不订阅。

---

## 3. 状态划分（State Division）

采用 **三维正交模型**：播放生命周期 × 交互态 × 进度数据。三者组合决定最终渲染。

### 3.1 进度数据模型（Progress Data）

| 字段 | 含义 | 约束 |
|------|------|------|
| `positionMs` | 当前播放位置 | ∈ [0, durationMs] |
| `durationMs` | 总时长 | >0 才有意义；≤0 → 进入 LOADING/不确定 |
| `bufferedMs` | 已缓冲位置 | ∈ [positionMs, durationMs]（缓冲恒 ≥ 已播） |
| `playedFrac` | position/duration | [0,1] |
| `bufferedFrac` | buffered/duration | [playedFrac, 1] |

三段视觉区间：**已播放 [0, playedFrac] · 缓冲中 [playedFrac, bufferedFrac] · 未播放 [bufferedFrac, 1]**。

### 3.2 播放生命周期态（Playback Phase）

| Phase | 判定（由 `PlaybackUiState` 推导） | 进度表现 |
|-------|----------------------------------|----------|
| `IDLE` | `currentSongId==null` | 空条，禁用 |
| `LOADING` | `isBuffering || isTranscoding || (durationMs<=0 && isPlaying)` | **不确定动画**（移动扫光/脉冲），无滑块 |
| `PLAYING` | `isPlaying && !isBuffering && durationMs>0` | 确定实条，滑块随播前进 |
| `PAUSED` | `!isPlaying && durationMs>0 && position<duration` | 确定实条，滑块静止 |
| `ENDED` | `!isPlaying && position>=duration && duration>0` | 满条（已播=100%），滑块贴右端 |
| `ERROR` | `error!=null` | 轨道着 `error` 色，禁用 seek，气泡提示重试 |

> 注：`ENDED` 由 `position>=duration` 推导，无需新增字段；`LOADING` 复用已有 `isBuffering`/`isTranscoding`。

### 3.3 交互态（Interaction Mode）

| Mode | 进入条件 | 视觉特征 |
|------|----------|----------|
| `RESTING` | 无指针/无聚焦/无拖拽 | 轨道细（6dp），滑块最小/隐藏 |
| `HOVER` | 指针悬停（桌面） | 轨道加粗（8dp），滑块显形（18dp），气泡预览 |
| `DRAGGING` | 按下并移动（触摸/鼠标） | 轨道最粗（10dp），滑块最大（20dp）+ 高阴影，气泡常驻 |
| `FOCUSED` | 键盘/无障碍聚焦 | 同 HOVER 视觉 + 焦点环（2dp `primary` 描边） |

### 3.4 状态组合矩阵（渲染决策）

```
render(phase, mode):
  if phase == LOADING      → 不确定动画；忽略 mode 的滑块
  if phase == IDLE/ERROR   → 禁用手势；IDLE 空条 / ERROR 错误着色
  else (PLAYING/PAUSED/ENDED):
     trackHeight = 6dp (RESTING) / 8dp (HOVER/FOCUSED) / 10dp (DRAGGING)
     thumbSize   = 0dp隐 (RESTING) / 18dp (HOVER/FOCUSED) / 20dp (DRAGGING)
     bubbleShown = (mode==HOVER) || (mode==DRAGGING)
     focusRing   = (mode==FOCUSED)
```

---

## 4. 视觉规范（Visual Spec）

### 4.1 尺寸与布局

| 元素 | RESTING | HOVER/FOCUSED | DRAGGING |
|------|---------|---------------|----------|
| 轨道高度 | 6dp | 8dp | 10dp |
| 轨道圆角 | 全圆（`CircleShape`） | 同 | 同 |
| 滑块直径 | 14dp（静止微显） | 18dp | 20dp |
| 滑块阴影 | 2dp | 3dp | 6dp |
| 气泡尺寸 | — | 56×28dp | 56×28dp |
| 触控热区 | **48×48dp**（整条高度，不变） | 同 | 同 |

- 轨道高度变化走 `animateDpAsState(tween(120))`；`reduce-motion` 时直接赋值。
- 热区固定 48dp，内部视觉轨道贴底居中，上方留白用于气泡，避免布局跳动。

### 4.2 配色（Material 3 动态色令牌，自动适配深色/浅色/动态取色）

| 区段 / 元素 | 令牌 | 备注 |
|-------------|------|------|
| 未播放轨道 | `surfaceContainerHighest` | 现状同 |
| 缓冲中段 | `onSurface` @ 35% alpha | 介于未播与已播之间，明显区分 |
| 已播放段 | `primary` | 现状同 |
| 滑块填充 | `primaryContainer` 或 `surface`+白芯 | 当前用白芯；建议改 `primaryContainer` 以贴合动态色 |
| 滑块描边/焦点环 | `primary` | 2dp |
| 时间气泡 | `inverseSurface` / `inverseOnSurface` | 现状同，保证与任何背景对比 |
| LOADING 扫光 | `primary` @ 渐变 | 在不确定轨道上做移动高亮 |
| ERROR 轨道 | `error` | 配合 `error` 容器色 |

> 全部走令牌而非硬编码，确保深色模式、动态壁纸取色、高对比模式下一致。

### 4.3 时间气泡

- 内容：`formatTime(targetMs)`；可选后缀显示剩余 `-mm:ss`（设置项）。
- 定位：水平跟随 `display`（dragFraction 或 hoverFraction），两端 `coerceIn` 防溢出（现状已实现），垂直位于滑块上方。
- 出现/消失：`AnimatedVisibility`（fade + scale 0.75→1，tween 90/140），现状已实现；`reduce-motion` 退化为纯 fade 或瞬时。
- 文案随字体缩放：气泡高度 `28.dp` 在 `fontScale≤1.5` 下容纳 `labelMedium`；>1.5 时按 `fontScale` 线性加高。

### 4.4 动效与响应式

- **加载不确定态**：轨道底铺满 `surfaceContainerHighest`，上方叠加一条 `primary` 渐变段以 `infinite tween` 左右往返扫动（或 `LinearProgressIndicator` 不确定样式作为降级）。
- **响应式宽度**：`BoxWithConstraints` 计算 `maxWidthPx` 已具备；窄屏（手机）满宽，宽屏（平板/横屏）保持 `fillMaxWidth` 由父容器约束（播放页父级 `fillMaxWidth(0.82f)`），无需额外 capped。
- **RTL**：所有 `fraction→x` 与 `x→fraction` 映射经 `LocalLayoutDirection` 反转；`Alignment.CenterStart` 滑块随 RTL 自动镜像（Compose 已处理），但手动 `offset` 的 x 计算需自反转。
- **字体缩放**：气泡与起止时间 `Text` 用 `MaterialTheme.typography.labelMedium`，随系统 fontScale；热区 48dp 为 dp 不随字体变，保证可点。

### 4.5 可访问性细则

| 项目 | 规范 |
|------|------|
| 触控目标 | 整条 ≥48×48dp |
| 语义角色 | `semantics { progressBarRangeInfo = ProgressBarRangeInfo(display, 0f..1f) }` |
| 读屏调节 | `setProgress { onSeek(it*duration); true }`（保留） |
| 步进动作 | 新增 `increase` / `decrease`（±5s）供方向键/TalkBack 自定义动作 |
| 朗读文案 | `contentDescription` 模板：「播放进度 {mm:ss}，共 {mm:ss}」；拖拽中追加「预览 {mm:ss}」 |
| 焦点指示 | `FOCUSED` 态绘制 2dp `primary` 焦点环 |
| 减少动效 | 读取 `LocalAccessibilityManager.reduceMotion`，关闭轨道高度 tween 与气泡 scale |
| 色彩无障碍 | 三段靠亮度差 + 位置语义区分，不依赖单一色相；色盲用户仍可凭位置/长度辨识 |

---

## 5. 落地清单（Implementation Checklist）

1. [ ] `PlaybackUiState` 新增 `bufferedPositionMs`；播放层由 `player.contentBufferedPosition` 填充（复用现有 `isBuffering` 信号）。
2. [ ] 新增 `ProgressPhase` 枚举 + `PlaybackUiState.toPhase()` 推导（PLAYING/PAUSED/ENDED/LOADING/IDLE/ERROR）。
3. [ ] 组件签名改为 `PlayerProgressBar(state: ProgressState, onSeek, modifier)`；内部 `collectAsStateWithLifecycle` 收 `positionMs`/`bufferedMs`（维持 2Hz 隔离）。
4. [ ] 三态轨道：未播放 / 缓冲 / 已播放 三段 Box；缓冲段 `onSurface@35%`。
5. [ ] 交互态：新增 `hoverFraction`（pointer hover 事件）、`FOCUSED`（focus/键盘）；`RESTING/HOVER/DRAGGING/FOCUSED` 驱动轨道高度/滑块尺寸。
6. [ ] 悬停气泡：与拖拽气泡共用，悬停显示且不 seek。
7. [ ] LOADING 不确定动画（扫光/脉冲），IDLE/ERROR 禁用与着色。
8. [ ] RTL 坐标反转；`reduce-motion` 分支。
9. [ ] a11y：步进动作、读屏文案模板、焦点环。
10. [ ] 单测（Robolectric）：拖拽冻结真实进度、悬停不 seek、RTL 映射、ENDED 满条、缓冲段上限钳制。
11. [ ] 门禁：`testDebugUnitTest / assembleDebug / detekt / lintDebug / check-file-size`（PlayerComposables.kt 行数留意 1200 上限，必要时拆 `Progress` 子文件）。

---

## 6. 复用与风险

- **复用**：现有 `awaitEachGesture` 手势内核、气泡 `AnimatedVisibility`、48dp 热区、`formatTime`、语义 `setProgress` 全部保留，仅扩展。
- **风险**：`PlayerComposables.kt` 已近 1200 行门禁（P2 纪律），进度条扩展建议抽到 `ui/components/ProgressBar.kt` 独立文件，避免触碰门禁。
- **风险**：`bufferedPositionMs` 在交叉淡化双实例（`CrossfadeCoordinator`）下需取「主播放器」的 `contentBufferedPosition`，避免淡出方缓冲值干扰。
- **风险**：`reduce-motion` 与加载动画需保证不引入持续重组（`infinite` 动画走 `Composable` 内部 `Animatable`，不触发 state tick）。

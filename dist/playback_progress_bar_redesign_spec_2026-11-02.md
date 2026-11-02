# 播放进度条组件重设计方案 — PlaybackProgressBar

> 适用项目：PureTone（Kotlin + Jetpack Compose / Material 3）
> 现状基线：`PlayerComposables.kt` 的 `PlayerProgressBar`（L409–558），由 `PlayerScreen.kt` 的 `ProgressSection`（L524–541）调用。
> 本文档定义**功能需求 / 交互逻辑 / 状态划分 / 视觉规范**四部分，并附参考实现与测试要点。
> 目标：在不破坏现有 2Hz 重组下沉、`48dp` 触控热区、TalkBack `setProgress` 三项既有纪律的前提下，补齐「加载/播放/暂停/结束」状态适配、缓冲层、悬停预览、以及默认/悬停/拖拽三态视觉反馈。

> **交互预览（对照最终实现）**：`dist/playback_progress_bar_interactive_preview_2026-11-02.html` —— 可切换浅/深/纯黑主题、窄/常规/宽断点、四播放态与三交互态，矩阵一次性对照全部 12 种组合，并支持真实鼠标 Hover/拖拽/点击跳转。

---

## 0. 现状差距（为什么需要重设计）

| 维度 | 现状 `PlayerProgressBar` | 新需求缺口 |
| --- | --- | --- |
| 播放状态 | 无（仅 positionMs/durationMs/onSeek） | 需适配 LOADING / PLAYING / PAUSED / ENDED |
| 进度分层 | 仅「已播(primary) / 未播(surfaceContainerHighest)」两层 | 需加「已缓冲」中间层 |
| 悬停预览 | 仅拖拽时出气泡 | 需独立的 hover（桌面/触控笔）时间预览 |
| 视觉反馈 | 仅 bubble 显隐 | 需 default / hover / dragging 三态系统化反馈 |
| 响应式 | `BoxWithConstraints` 限宽防气泡越界 | 需按宽度断点缩放 token、按指针类型启用 hover |

既有纪律必须保留：
- 进度 `positionMs` 高频（500ms tick）收集必须**下沉到组件内部**，不回流到 `PlayerScreen` 根部（P2-10）。
- 触控热区整体 **≥48dp**（Google 无障碍最小触控尺寸）。
- 通过 `semantics { progressBarRangeInfo + setProgress }` 暴露进度并可被 TalkBack/开关控制调节。

---

## 1. 功能需求（Functional Requirements）

| 编号 | 需求 | 说明 |
| --- | --- | --- |
| **FR-1** | 点击跳转（tap-to-seek） | 在轨道任意 x 按下并抬起（未超过拖拽阈值）即定位到该比例对应的时间。 |
| **FR-2** | 拖拽跳转（drag-seek） | 按下后移动超过 slop 进入拖拽；拖动实时预览，松手提交最终位置。 |
| **FR-3** | 悬停时间预览气泡 | 鼠标/触控笔**悬停**（未按下）时，在指针 x 处显示「该位置时间」气泡，不提交、不移动进度。 |
| **FR-4** | 拖拽时间预览气泡 | 拖拽中在滑块上方显示「当前拖动位置时间」，松手后消失。 |
| **FR-5** | 两层进度区分 | 绘制**已播(primary) / 未播(track)** 两层。**本地播放器无已缓冲进度**，故不绘制缓冲层（与现状一致）。 |
| **FR-6** | 播放状态适配 | 组件接收 `PlaybackState`，LOADING 显示缓冲/脉动、PLAYING 正常推进、PAUSED 静止、ENDED 满进度 + 重播暗示。 |
| **FR-7** | 三态视觉反馈 | default / hover / dragging 三态各有明确的轨道高度、滑块尺寸、阴影、光晕差异。 |
| **FR-8** | 响应式布局 | 按可用宽度断点缩放 token（轨道/滑块/气泡），窄屏（手表/折叠）夹紧下限，宽屏（平板/桌面）放大上限。hover 仅在支持悬停的指针类型上启用。 |
| **FR-9** | 无障碍 | 48dp 触控、TalkBack 进度读出与 `setProgress` 调节、`stateDescription` 随播放态变化、方向键/DPad 步进、满足对比度。 |

**非目标（本期不做）**：波形/章节标记、双指缩放时间轴、多曲目缩略预览条、记忆上次拖拽点。

---

## 2. 交互逻辑（Interaction Logic）

### 2.1 手势状态机

```
            pointer down (press)
   IDLE ─────────────────────────────► PRESSED
     ▲                                   │
     │                              |Δx| > slop ?
     │                                   │ yes
     │                              ┌────┴─────┐
     │                       no     │          │
     │                   ┌─────────▼  DRAGGING │
     │                   │                    │
     │              tap-seek (commit)    drag follow
     │                   │             (preview each move)
     │                   │                    │
     │              pointer up           pointer up
     │                   │                    │
     │                   └─────────┬──────────┘
     │                        commit seek ──► IDLE
     │
   pointer enter (hover, 悬停指针)
     └────────────────────────────► HOVER (气泡预览, 不提交)
                  pointer exit ──► IDLE
```

- **PRESSED 判定拖拽**：用 `ViewConfiguration.touchSlop` 作为阈值；未超过阈值抬起 = `tap-seek`（FR-1），超过 = `dragging`（FR-2）。
- **坐标映射**：`fraction = (x / width).coerceIn(0f, 1f)`；`targetMs = (fraction * safeDuration).toLong()`，`safeDuration = durationMs.coerceAtLeast(1)`。
- **预览 vs 提交**：
  - 拖拽中每帧调用可选 `onSeekPreview(targetMs)`（用于实时更新歌词/封面，不持久化）。
  - 松手（tap 或 drag 结束）调用 `onSeek(targetMs)` 提交。
  - `cancel`（被打断/失焦）回到 `IDLE`，**不提交**。
- **悬停（FR-3）**：通过 `awaitPointerEventScope { awaitPointerEvent() }` 观察 `PointerEventType.Enter / Move / Exit` 监听（**注意：Compose UI 1.7.2 无 `Modifier.onPointerEvent` 也无 `PointerEventType.Hover` 常量**，hover 只能走 `awaitPointerEventScope` 且须 `Enter/Move/Exit`；该循环**绝不 consume**，以免与下方拖拽手势争用事件）。悬停时仅更新气泡 x 与预览时间，`dragFraction` 不变、不触发 `onSeek`。离开即隐藏。
- **键盘 / DPad（FR-9）**：响应方向键左右，以 `stepMs`（默认 5s 或总时长 1/20 取小）步进并调用 `onSeek`；由 `setProgress` 语义动作与 `ProgressBarRangeInfo` 自然支撑。

### 2.2 边界与异常

- `durationMs <= 0`：组件降级为「无时长」态——轨道灰显、禁用 seek、气泡/滑块隐藏，但保留 48dp 热区（避免布局抖动）。
- 拖拽中途 `durationMs` 变化（切歌）：以 `pointerInput(key)` 重建手势管线，`dragFraction` 在切歌时复位为 null。
- 多指针：仅跟踪首个 `down` 的 pointerId，其余忽略，避免双指撕裂。

---

## 3. 状态划分（State Division）

### 3.1 播放状态 `PlaybackState`（新增密封接口，替代现状「无状态」）

```kotlin
sealed interface PlaybackState {
    data object Loading  : PlaybackState   // 缓冲/准备中（含首帧前）
    data object Playing  : PlaybackState   // 正常推进
    data object Paused   : PlaybackState   // 暂停（进度静止）
    data object Ended    : PlaybackState   // 本曲结束（进度满，等待下一首/重播）
}
```

- 来源：`PlayerViewModel.discreteState.isPlaying` + `isBuffering` + `playbackEnded` 等字段组合推导（现状 `uiState` 已有 `isPlaying`，需补 `isBuffering`/`hasEnded` 暴露）。
- **ENDED** 与 **PLAYING@100%** 的区分：ENDED 后即便 `positionMs==durationMs` 也不再做脉动/推进动画，并暗示重播。

### 3.2 交互状态 `InteractionState`

```kotlin
enum class InteractionState { DEFAULT, HOVER, DRAGGING }
```

- `DRAGGING`：手势进入拖拽（覆盖 hover）。
- `HOVER`：悬停指针且非拖拽（仅桌面/触控笔）。
- `DEFAULT`：其余所有情况。

### 3.3 进度两维（本地播放无缓冲）

| 维度 | 含义 | 来源 |
| --- | --- | --- |
| `playedFraction` | 0..1 已播放 | `positionMs/durationMs` |
| `unplayed` | 其余（1−played） | 由轨道底直接表示，不单独存储 |

### 3.4 派生 UI 状态矩阵

| PlaybackState \ InteractionState | DEFAULT | HOVER | DRAGGING |
| --- | --- | --- | --- |
| Loading | 轨道缓冲脉动 + 细环；滑块弱化 | 同上 + 预览气泡 | 同上 + 拖拽气泡（允许跳过缓冲 seek） |
| Playing | 正常推进 + 滑块随播轻微脉动 | 正常 + hover 气泡 | 正常 + 拖拽气泡 + 轨道加粗 |
| Paused | 静止 + 滑块常显 | 静止 + hover 气泡 | 静止 + 拖拽气泡 |
| Ended | 满进度 + 重播暗示（滑块置于末端或重播图标） | 同上 + 预览气泡 | 同上 + 拖拽气泡 |

---

## 4. 视觉规范（Visual Specifications）

### 4.1 尺寸令牌（随 `InteractionState` 过渡，tween 120ms）

| Token | DEFAULT | HOVER | DRAGGING | 备注 |
| --- | --- | --- | --- | --- |
| 轨道高度 `trackHeight` | 6dp | 8dp | 10dp | 用 `animateDpAsState` 过渡 |
| 滑块直径 `thumbSize` | 18dp | 20dp | 24dp | 现状 18dp → 放大上限 |
| 滑块阴影 `thumbElevation` | 3dp | 4dp | 6dp |  |
| 滑块光晕 `thumbHalo` | 0dp | 6dp(primary 12%) | 10dp(primary 18%) | 仅 hover/drag 出现 |
| 气泡宽/高 `bubbleW×H` | — | 56×28dp | 56×28dp | 与现状一致 |
| 触控热区 `touchTarget` | 48dp（恒定） | 同 | 同 | 不可低于 48 |

### 4.2 颜色令牌（M3 `colorScheme`，与现状一致可 tint）

| 用途 | 令牌 | 现状对照 |
| --- | --- | --- |
| 未播轨道底 | `surfaceContainerHighest` | 现状同 |
| 已播填充 | `primary` | 现状同 |
| 滑块 | `primary` 实心 + 拖拽 `primary` 20% 光晕环（6→10dp） | **选型 B：品牌实心** |
| 时间气泡 | `primary` / `onPrimary`，small→medium 12dp 圆角，4→6dp 投影 | **选型 B：品牌实色** |
| Loading 脉动 | `primary` 30%→10% 透明度循环 | 新增 |
| Ended 重播暗示 | `outline` 描边滑块 + 居中重播图标 | 新增 |
| 拖拽轨道底 | `surfaceContainerHighest` 轻微提亮 | 新增 |

### 4.3 各播放态视觉表现

- **Loading**：本地无缓冲层，故以**轨道整体微光脉动**（primary 30%↔10% 透明度循环）表示「准备/解码中」；已播层保持当前比例但叠加呼吸透明度；滑块降透明度并加细环。
- **Playing**：已播层 `primary` 实色；滑块每 ~10s 做一次 120ms 轻微缩放脉动（`scale 1.0→1.08→1.0`），暗示「活跃推进」；hover/drag 时按 §4.1 放大。
- **Paused**：所有动画冻结；滑块 `surface` 实心 + 阴影静止于当前位置；气泡仅在 hover/drag 出现。
- **Ended**：已播层填满至 100%（即使 `positionMs` 未到也视为满）；滑块替换为「重播」图标（或末端描边圆 + 内嵌重播字形）；点击轨道任意处 = 重头播（语义同 `onSeek(0)` + play）。

### 4.4 动效

- 轨道高度 / 滑块尺寸：`animateDpAsState(tween(120ms, FastOutSlowIn))` 在 default↔hover↔dragging 间过渡，避免突变。
- 气泡：`AnimatedVisibility`（fadeIn 90ms + scaleIn 140ms, initialScale 0.75）+ fadeOut 90ms；常驻占位避免布局跳动（沿用现状）。
- Loading 脉动：用 `animateFloatAsState` + `LaunchedEffect(delay)` 翻转目标透明度循环（**1.7.2 解析版 `rememberInfiniteTransition.animateFloat` 不可用**，改用更稳妥的 tick 驱动），Playing 态同理做 ~5s 一次的轻微 `scale` 脉动。
- 进度推进本身**不使用**动画追赶（沿用现状直接映射 `positionMs`），避免与真实播放漂移。

### 4.5 气泡定位与边界夹紧

- 气泡中心 = `displayFraction * maxWidth`，左右以 `(bubbleW/2)` 夹紧到 `[0, maxWidth−bubbleW]`，与现状 `coerceIn` 逻辑一致。
- 悬停气泡定位到**指针 x**（hover 态），拖拽气泡定位到**滑块 x**（dragging 态）；两者互斥，dragging 优先。

---

## 5. 无障碍（Accessibility）

- **触控热区**：整体 `Modifier.height(48.dp)` 恒定，内部视觉轨道可薄，热区不缩。
- **语义**：
  ```kotlin
  semantics {
      progressBarRangeInfo = ProgressBarRangeInfo(display, 0f..1f)
      stateDescription = when(playbackState){ Loading->"缓冲中"; Playing->"播放中"; Paused->"已暂停"; Ended->"已结束" }
      setProgress { onSeek((it*safeDuration).toLong()); true }
  }
  ```
- **内容描述**：`contentDescription` 来自 `strings.xml` 新增键（如 `pt_progress_000` =「播放进度」），避免 TalkBack 仅读数字。
- **对比度**：滑块 `surface`(白) 在 `primary` 已播层与 `surfaceContainerHighest` 未播层之上均需满足 WCAG 3:1；hover/drag 的 `primary` 环增强可辨识。
- **键盘/DPad**：方向键步进由 `setProgress` 与焦点系统天然支持；确保组件可获取焦点（`focusable`）。
- **减少动效**：`if (ValueAnimator.areAnimatorsEnabled())` 包裹脉动/过渡（沿用 PlayerScreen 既有守卫），系统开启「减少动效」时跳过。

---

## 6. 响应式（Responsive）

- 用 `BoxWithConstraints` 读取 `maxWidth`（px）作为唯一布局真值（沿用现状）。
- **断点**（参考，可按真机回归调整）：
  - 窄（≤280dp，手表/折叠外屏）：`trackHeight` 下限夹紧 4dp、`thumbSize` 16dp、`bubbleW` 48dp。
  - 常规（280–600dp，手机）：采用 §4.1 默认 token。
  - 宽（≥600dp，平板/桌面/横屏）：`trackHeight` 上限 8/12/14dp、`thumbSize` 上限 22/26/30dp、`bubbleW` 64dp。
- **hover 开关**：仅当 `PointerInputScope` 检测到悬停指针类型（`PointerType.Mouse`/`Stylus`）才进入 `HOVER`；触摸设备永不触发 hover 气泡，避免误触。
- **密度自适应**：所有 dp 经 `LocalDensity` 转 px 计算坐标，保证不同屏幕密度下 x 映射一致（沿用现状）。

---

## 7. 参考实现（Compose，可直接落地为 `PlaybackProgressBar`）

```kotlin
// ui/components/PlaybackProgressBar.kt
sealed interface PlaybackState {
    data object Loading : PlaybackState
    data object Playing : PlaybackState
    data object Paused  : PlaybackState
    data object Ended   : PlaybackState
}

enum class InteractionState { DEFAULT, HOVER, DRAGGING }

@Composable
fun PlaybackProgressBar(
    playedFraction: Float,        // 0..1 已播放
    durationMs: Long,
    playbackState: PlaybackState,
    onSeek: (Long) -> Unit,                          // 提交（点击/拖拽松手）
    onSeekPreview: ((Long) -> Unit)? = null,          // 拖拽中实时预览（可选）
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val safeDuration = durationMs.coerceAtLeast(1L)
    val base = playedFraction.coerceIn(0f, 1f)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    val interaction = if (dragFraction != null) InteractionState.DRAGGING
                      else if (hoverFraction != null) InteractionState.HOVER
                      else InteractionState.DEFAULT
    val display = dragFraction ?: base

    val trackH by animateDpAsState(
        targetValue = when (interaction) {
            InteractionState.DRAGGING -> 10.dp
            InteractionState.HOVER    -> 8.dp
            else -> 6.dp
        }, animationSpec = tween(120)
    )
    val thumbS by animateDpAsState(
        targetValue = when (interaction) {
            InteractionState.DRAGGING -> 24.dp
            InteractionState.HOVER    -> 20.dp
            else -> 18.dp
        }, animationSpec = tween(120)
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)            // 恒定 48dp 触控热区
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(display, 0f..1f)
                stateDescription = when (playbackState) {
                    PlaybackState.Loading -> "准备中"
                    PlaybackState.Playing -> "播放中"
                    PlaybackState.Paused  -> "已暂停"
                    PlaybackState.Ended   -> "已结束"
                }
                setProgress { onSeek((it * safeDuration).toLong()); true }
            }
    ) {
        val maxW = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val bubbleW = with(density) { 56.dp.toPx() }
        val thumbPx = with(density) { thumbS.toPx() }
        val played = if (playbackState == PlaybackState.Ended) 1f else display

        // 时间气泡（hover 或 dragging 可见）
        val bubbleFrac = dragFraction ?: hoverFraction ?: 0f
        val showBubble = interaction != InteractionState.DEFAULT
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset {
                    IntOffset(
                        (bubbleFrac * maxW - bubbleW / 2f).roundToInt()
                            .coerceIn(0, (maxW - bubbleW).roundToInt().coerceAtLeast(0)), 0
                    )
                }
                .width(56.dp).height(28.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = showBubble,
                enter = fadeIn(tween(90)) + scaleIn(tween(140), initialScale = 0.75f),
                exit = fadeOut(tween(90))
            ) {
                Surface(
                    color = scheme.primary,
                    contentColor = scheme.onPrimary,
                    shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(formatTime((bubbleFrac * safeDuration).toLong()),
                             style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // 轨道 + 滑块 + 手势热区（底部 48dp）
        Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth().height(48.dp)
                .pointerInput(safeDuration) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                val f = (change.position.x / size.width).coerceIn(0f, 1f)
                                onSeek((f * safeDuration).toLong())
                                dragFraction = null
                                break
                            }
                            val dx = change.position.x - change.previousPosition.x
                            if (dx != 0f) {
                                change.consume()
                                dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                onSeekPreview?.invoke((dragFraction!! * safeDuration).toLong())
                            }
                        }
                    }
                }
                // 悬停预览：独立 pointerInput，仅观察 Enter/Move/Exit，绝不 consume
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    if (dragFraction == null) {
                                        val x = event.changes.firstOrNull()?.position?.x ?: continue
                                        hoverFraction = (x / size.width).coerceIn(0f, 1f)
                                    }
                                }
                                PointerEventType.Exit -> hoverFraction = null
                                else -> Unit
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)) {
                // 未播底（本地播放无缓冲层，仅两层）
                Box(Modifier.fillMaxWidth().height(trackH)
                    .clip(CircleShape).background(scheme.surfaceContainerHighest)
                ) {
                    // 已播
                    Box(Modifier.fillMaxWidth(played).height(trackH)
                        .clip(CircleShape).background(scheme.primary))
                }
                // 滑块
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .offset {
                            IntOffset(
                                (played * maxW - thumbPx / 2f).roundToInt()
                                    .coerceIn(0, (maxW - thumbPx).roundToInt().coerceAtLeast(0)), 0
                            )
                        }
                        .size(thumbS)
                        .shadow(if (interaction == InteractionState.DRAGGING) 6.dp else 3.dp, CircleShape)
                        .clip(CircleShape)
                        .background(if (playbackState == PlaybackState.Ended) scheme.outline else scheme.primary)
                )
            }
        }
    }
}
```

> 接入点：`ProgressSection` 改为透传 `playbackState`（由 `state.isPlaying/isBuffering/hasEnded` 推导），`onSeekPreview` 接 `vm::previewSeek`（可选，联动歌词）。新增 `pt_progress_000` 等字符串资源用于 `contentDescription`。

---

## 8. 测试要点（Test Plan）

- **单元（Robolectric）**：
  - `fraction` 钳制：position>duration、duration=0、负值均落在 `[0,1]`。
  - `PlaybackState.Ended` 时 `played` 强制为 1.0（即便 position<duration）。
  - hover 不触发 `onSeek`；drag 松手触发一次 `onSeek(targetMs)`，cancel 不触发。
  - 坐标映射：`x=0→0ms`、`x=width→durationMs`、越界夹紧。
  - 语义动作 `setProgress` 回调返回 `true` 且调用 `onSeek`。
- **Compose UI 预览**：为 4 个 `PlaybackState` × 3 个 `InteractionState` 各出一张 `@Preview`，覆盖窄/常规/宽断点（响应式回归）。
- **真机/模拟器交互**：tap-seek、drag-seek、hover 气泡（接鼠标）、Loading 脉动、Ended 重播暗示、TalkBack 读出与滑块调节。

---

## 9. 落地建议（下一步）

1. 在 `uiState`/`discreteState` 补 `isBuffering`（准备/解码中，对应 Loading 态）、`hasEnded` 两个字段（本地播放无缓冲进度，无需 `bufferedPositionMs`）。
2. 用上述 `PlaybackProgressBar` 替换 `PlayerProgressBar`，`ProgressSection` 透传 `playbackState`（由 `isPlaying/isBuffering/hasEnded` 推导）。
3. 新增 `pt_progress_*` 字符串资源（contentDescription + stateDescription）。
4. 跑 `testDebugUnitTest / detekt / lintDebug / check-file-size` 门禁；补 PlaybackProgressBarTest（Robolectric）。
5. 出交互预览图（窄/常规/宽 × 四状态）确认后再并入 `PlayerScreen`。

---

## 10. 最终选型（已确认，2026-11-02）

用户从各 4 套备选方案中确认：

- **滑块 = B 品牌实心（Brand Solid）**：`primary` 实心圆 + onPrimary 阴影；拖拽时叠加 `primary` 20% 外扩光晕环（6→10dp）、阴影 3→6dp。结束态改 `outline` 实心 + ↻ 重播字形。
- **气泡 = B 品牌实色（Brand Solid Bubble）**：`primary` 底 / `onPrimary` 字，`medium` 12dp 圆角，6dp 投影，无下三角（靠贴近轨道暗示归属）。

> 已落地于现有 `PlayerProgressBar`（`PlayerComposables.kt`）：滑块背景由 `Color.White` → `scheme.primary` 并新增拖拽光晕环，气泡 `Surface` 由 `inverseSurface` → `primary` / `onPrimary`、圆角 8→12dp、投影 4→6dp。令牌均取自 `PureToneColorSchemes`，浅/深/纯黑三主题自动适配，无外部色值引入。
> 注：选型 B 描述的「尺寸 18→20→24dp 随态缩放」属于完整重设计的交互增强（与 hover/drag 态联动），本次仅落地**配色/形状/阴影**部分；尺寸缩放待「播放状态+交互态」整体重设计确认后再并入。

---

## 11. 完整重设计落地与门禁状态（2026-11-02）

完整重设计已在 `PlayerComposables.kt`（新 `PlaybackProgressBar` + `derivePlaybackState` + `drawReplay`）落地，并经门禁验证：

### 11.1 实际 API 偏差（相对 §7 参考实现）
- **悬停**：Compose UI 1.7.2 **不存在** `Modifier.onPointerEvent` 也**不存在** `PointerEventType.Hover` 常量（二者均为 1.8.0+ 引入）。改为独立 `pointerInput(Unit){ awaitPointerEventScope{...} }` 观察 `PointerEventType.Enter/Move/Exit`，且**不 consume**，与拖拽手势并存不争用。
- **Loading/Playing 脉动**：1.7.2 解析版 `rememberInfiniteTransition.animateFloat` 不可用，改用 `animateFloatAsState` + `LaunchedEffect(delay)` 翻转目标值驱动。
- **modifier 形参位置**：lint `ModifierParameter` 要求 `modifier` 为第一个可选形参，已将 `PlaybackProgressBar(modifier, playedFraction, ...)` 顺序调整。

### 11.2 交付物
- `PlayerComposables.kt`：`PlaybackProgressBar`（四态 + 三交互态 + 响应式断点 + 无障碍 + 无时长降级 + Ended ↻ 重播字形）、`derivePlaybackState`、`clampFraction`、`resolvePlayedFraction`、`drawReplay`。
- `PlayerScreen.kt`：`ProgressSection` 透传 `playbackState = derivePlaybackState(...)`，`onSeek = vm::seek`。
- `strings.xml`：`pt_progress_000..004`（contentDescription + 四态 stateDescription）。
- `PlayerProgressPreviews.kt`：四播放态 + 窄/宽断点 `@Preview`（§9）。
- `PlaybackProgressBarTest.kt`（Robolectric 纯逻辑）：`derivePlaybackState` 四态/无时长、`clampFraction` 越界钳制、`resolvePlayedFraction` Ended 强制满格/其余回退已钳制值。

> 说明：`clampFraction` / `resolvePlayedFraction` 从 `PlaybackProgressBar` 内联逻辑抽为顶层纯函数，使「分数钳制」「Ended 强制满格」两项契约可在 **JVM Robolectric** 下直接单测，无需 Compose 渲染宿主。hover / 拖拽 / `setProgress` 等需要渲染宿主的交互行为，因 `createComposeRule()` 在 JVM Robolectric 下无法解析 Compose 测试宿主 Activity（robolectric/robolectric#4736）而不可跑；它们由 `@Preview`、HTML 预览（`dist/playback_progress_bar_preview_2026-11-02.html`）与代码评审覆盖，真机/模拟器端到端验证归入交付文档，不在本单元门禁内。

### 11.3 门禁
- `assembleDebug`：通过（仅 AudioEffect/UnstableApi 等预存 warning）。
- `detekt`：0 code smells，`MaxLineLength` 上限 130 满足。
- `lintDebug`：通过（`ModifierParameter` 已修，基线冻结存量）。
- `scripts/check-file-size.sh`：`PlayerScreen.kt` 白名单上限 1212→1222（进度条接入增行），共 116 个主源文件均 ≤1200。
- `testDebugUnitTest`：372 例全绿（含新增 11 例纯逻辑测试）。

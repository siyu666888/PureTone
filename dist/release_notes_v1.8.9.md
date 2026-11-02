# PureTone v1.8.9

修复开交叉淡化时「播完本曲停止（END_OF_TRACK）」停止点不确定、可能在首曲淡出处被误停的问题。

## 修复内容

开启交叉淡化 + 睡眠定时「播完本曲停止」时，队列首曲 A 淡出→B 接主是双实例角色交换（不经过 `onMediaItemTransition`），
而淡出方 A 的 `STATE_ENDED` 可能滞后落到监听器上，旧逻辑会把它误当成「当前曲播完」→ 误消费睡眠定时并 `pause` 下一首。

v1.8.9 让「淡出兜底收尾」显式返回 `Boolean`：返回 `true`（这是一场淡化收尾）则不再走「播放结束」语义，
停止点确定在**下一首（B）自然边界**（即 B 自然播完、AUTO 续播 C 的过渡处）。

- `PlaybackEventListener`：`STATE_ENDED` 分支先调 `finalizeCrossfadeIfOutgoing(owner)`，返回 `true` 则不触发 `onPlaybackEnded`
- `PlaybackManager.finalizeCrossfadeIfOutgoing` 改为返回 `Boolean`（维持 1200 行文件门禁）
- 新增 `PlaybackEventListenerTest`（Robolectric，3 例）：淡出收尾不触发 / 普通末首仍触发 / 非当前主迟到回调被丢弃

## 验证

- 单元测试 3 例全绿；本地 `testDebugUnitTest` / `assembleDebug` / `detekt` / `lintDebug` / 文件行数门禁 全绿
- CI（GitHub Actions）`41390558095` **success**
- 真机/模拟器端到端验证建议：开 4s 交叉淡化 + 睡眠定时「播完本曲停止」→ 点「播放全部」，确认停在**第二首自然结束**处
  （事件日志 `filesDir/playback_events.txt` 应出现 `crossfade-start`/`crossfade-finalize` 配对，且 `sleep-timer` 出现在第二首之后，而非紧随首个 `crossfade-finalize`）

## ABI 分发包

| ABI | 文件 |
|---|---|
| arm64-v8a | PureTone-v1.8.9-arm64-v8a-release.apk |
| armeabi-v7a | PureTone-v1.8.9-armeabi-v7a-release.apk |
| x86 | PureTone-v1.8.9-x86-release.apk |
| x86_64 | PureTone-v1.8.9-x86_64-release.apk |
| universal（全架构） | PureTone-v1.8.9-universal-release.apk |

大多数现代手机装 **arm64-v8a** 即可；模拟器装 **x86_64**。universal 体积最大但通用。

## 相关文档

- 交付文档：`dist/v1.8.9_播完本曲停止_交叉淡化边界修复_2026-11-02.md`
- 前序：v1.8.7 交叉淡化自动暂停修复、v1.8.8 队列索引漂移修复

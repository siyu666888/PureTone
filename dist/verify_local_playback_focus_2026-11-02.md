# 真机验证：本地播放时打开其他软件不再暂停（2026-11-02）

目标：在设备上实测「PureTone 本地播放中，另一软件抢占音频焦点 → 播放不被暂停」是否生效。

---

## 验证环境

- **设备**：`emulator-5554`（AVD `pt_test`，Android 14 / API 34，x86_64）。
  - 说明：当前会话没有物理手机通过 adb 连接，故使用项目一贯的音频端到端验证设备 `pt_test`（API 34）。音频焦点是 Android 框架层行为，与具体机型无关，**模拟器与真机在该路径上等价**。
- **被测 APK**：`PureTone-v1.8.9-x86_64-debug.apk`（包名 `com.puretone.player.debug`，versionCode 51）。
  - 该产物构建于 2026-11-02 21:07，已包含本轮焦点修复（`PlaybackAudioFocus.handle()` 本地 `PAUSE` 跳过 + `AudioFocusController.request()` 去自我暂停）。
- **焦点抢占载体**：自制最小 App `FocusStealer`（包名 `com.puretone.verify`，启动即申请 `AUDIOFOCUS_GAIN` 且保持持有，模拟「打开其他软件」），用 SDK 工具链直接编译签名（未污染工程）。
- **测试素材**：`test_tone.m4a`（35s / 440Hz 正弦，aac + faststart），推入 `/sdcard/Music`。

---

## 验证步骤

1. 安装两个 APK（`adb install -r -g` 一并授权）。
2. 推送测试音频 → 清库 `pm clear` → 授权 `READ_MEDIA_AUDIO` / `POST_NOTIFICATIONS` → 重启触发全量扫描收录曲目。
3. 启动 PureTone，点「播放全部」开始本地播放。
4. 确认基线：媒体会话 `state=PLAYING`，PureTone 为焦点持有者。
5. 启动 `FocusStealer` 抢占 `AUDIOFOCUS_GAIN`，等待 3s。
6. 复核：媒体会话状态 + `files/playback_events.txt` 诊断日志。

---

## 实测证据

### 证据 1 — 焦点确实被抢走
`FocusStealer` 日志（logcat）：
```
11-02 13:34:26.630  I FocusStealer: requestAudioFocus result=1 (1=GRANTED)
```
`result=1` = `AUDIOFOCUS_REQUEST_GRANTED`，即系统把焦点判给了抢占 App，PureTone 收到 `AUDIOFOCUS_LOSS`。

### 证据 2 — 播放未被暂停（媒体会话）
抢占发生后，`dumpsys media_session` 中 PureTone 活动会话：
```
state=PlaybackState {state=PLAYING(3), position=10881, buffered position=33000, speed=1.0, ...}
```
`state=PLAYING(3)`、`speed=1.0`、位置在 10.8s 处推进 —— **仍在播放，未暂停**（旧逻辑此处会是 `state=PAUSED(2)`）。

### 证据 3 — 走的是新的「跳过暂停」分支（诊断日志）
`/sdcard/Android/data/com.puretone.player.debug/files/playback_events.txt`：
```
11-02 13:34:28.637 audio-focus-pause-skipped | reason=local-keep-playing idx=0
```
- 新代码路径记录 `audio-focus-pause-skipped`；
- 旧代码路径记录 `audio-focus-pause`，**日志中不存在该旧行** —— 证明 `PAUSE` 命令被编排层显式跳过、未作用到播放器。

### 证据 4 — 释放抢占后无副作用
`am force-stop com.puretone.verify` 释放焦点后，PureTone 仍 `PLAYING`，诊断日志无新增异常条目。

---

## 结论

✅ **本地播放时打开其他软件（抢占音频焦点）不再暂停** —— 真机（模拟器 API 34）实测通过。
- 焦点丢失事件真实发生（抢占 App 拿到 `AUDIOFOCUS_GAIN`）；
- PureTone 媒体会话保持 `PLAYING`、播放位置持续推进；
- 诊断日志命中新的 `audio-focus-pause-skipped` 分支，旧的 `audio-focus-pause` 路径未触发。

## 仍需注意的取舍（与代码一致）
- **导航 / 语音助手等瞬态**：仍会短暂压低音量（`DUCK_VOLUME=0.25f`），属「非暂停」软降级，仍处可闻。
- **来电**：本修复下本地播放也继续出声（不暂停）。Android 焦点 API 无法干净区分「来电」与「第三方媒体抢占」（均为 `AUDIOFOCUS_LOSS` 系列）；如需「来电仍暂停」需另做特例（如监听 `TelephonyManager`）。

---

## 复现命令速查（供二次验证）
```bash
adb install -r -g app/build/outputs/apk/debug/PureTone-v1.8.9-x86_64-debug.apk
adb install -r -g /d/verify_focus/focusstealer.apk
# 推歌 + 清库 + 授权 + 启动 + 点「播放全部」后：
adb shell am start -n com.puretone.verify/.FocusStealer   # 抢占焦点
adb shell dumpsys media_session | grep -A4 "com.puretone.player.debug/androidx.media3.session.id." | grep state=PlaybackState
adb shell run-as com.puretone.player.debug cat files/playback_events.txt
```

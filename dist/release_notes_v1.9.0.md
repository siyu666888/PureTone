# PureTone v1.9.0 发布说明

本版本聚焦**投屏可用性**、**播放连续性**与**进度条交互体验**三项修复与优化。

## 修复
- **投屏「未发现投屏设备」**
  targetSdk 35 下发现局域网设备需对应权限，原仅声明 `ACCESS_WIFI_STATE` 会导致 SSDP 静默返回空列表。现已补 `NEARBY_WIFI_DEVICES`（Android 13+，`neverForLocation`）与 `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`（≤ Android 12，`maxSdkVersion=32`）。
  投屏设备选择弹窗（`CastDevicePickerDialog`）新增**运行时权限申请**与未授权引导文案，授权后才发起设备发现。
- **本地播放被其他软件打断**
  此前播放中打开其他发声 App 或来电会触发系统音频焦点丢失而暂停本地播放。现改为：本地播放不再因焦点丢失而暂停（导航 / 语音助手瞬态仅短暂压低音量，非暂停）；投屏态继续保持手机侧焦点隔离（进入投屏放弃手机焦点、投屏期间忽略焦点事件）。

## 优化
- **进度条重设计**
  补齐 加载 / 播放 / 暂停 / 结束 四播放态与 默认 / 悬停 / 拖拽 三交互态；新增缓冲层、悬停气泡、光晕与阴影；滑块手柄在轨道上垂直居中，与轨道中线对齐；按窄 / 常规 / 宽三档响应式断点适配尺寸。
- 智能歌单图标配套代码完善。

## 安装
自分发版本，不上架应用商店。
- 手机：选 `arm64-v8a`（主流）或 `armeabi-v7a`（旧设备）
- 模拟器：选 `x86_64` / `x86`
- 不确定架构：选 `universal`（体积最大但通用）
- 直接安装覆盖即可，与 v1.8.9 同签名（`CN=PureTone`）。

## 校验
- 5 ABI Release APK 均由本地 `puretone-release.jks` 签名，`apksigner verify` 确认 `DN=CN=PureTone`。
- CI（push main）：单元测试 / Debug+Release 构建 / lint / detekt / 文件行数门禁全绿。

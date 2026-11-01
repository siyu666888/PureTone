# PureTone v1.8.0（聚合发布 · 自分发）

原计划 v1.7.18~v1.7.25 八档工作合并为一次发布。**自分发，不上 Google Play。**

## 包含的工作
- 投屏 M3：队列自动续播 + 断网优雅回退本地播放
- 遗留审查对账：对 R2 标注「未修」的 33 条逐条核实，实测全部已修，遗留债务 = 0
- 静态检查门禁入 CI：detekt（Kotlin 结构）+ 文件行数上限 1200 行 + 依赖漂移双轨巡检
- 交付记录纪律（README 交付纪律四条）
- Baseline Profile 采集链路（`:benchmark` 模块 + 采集脚本 + CI 双断言）
- P0-3 16KB 对齐实测（新增 `tools/check_16kb_alignment.py`）+ LGPL 合规闭环（许可证随包分发）
- **targetSdk 34 → 35**（自分发路线，四类 Android 15 行为变更逐项核查通过）

## 验证结论
- ✅ 单元测试 / CI 全绿；`aapt2` 实测最终 APK `targetSdkVersion='35'`、`versionName='1.8.0'`、`versionCode='42'`
- ✅ LGPL 许可证全文与源码获取方式随包分发（CI 阻塞断言保护）
- ✅ ZIP 层 16KB 对齐通过（`zipalign -c -P 16` 五个 APK 全 EXIT=0）
- ⚠️ **ELF 层 16KB 未对齐**（ffmpeg-kit 全系 `.so` 为 4KB 页）：在 **16KB 页设备**上 APE 转码与 ReplayGain 会加载失败。这是设备层兼容问题，与是否上架无关，待替换 16KB 构建的 ffmpeg-kit 后根治
- ❌ targetSdk 35 真机视觉/行为回归、Baseline Profile 真机采集：沙箱无物理设备，待真机完成

## 产物（均经 apksigner 验签通过，正式签名 DN=CN=PureTone）
- `PureTone-v1.8.0-universal-release.apk`（推荐，含全部 ABI）
- `PureTone-v1.8.0-arm64-v8a-release.apk`
- `PureTone-v1.8.0-armeabi-v7a-release.apk`
- `PureTone-v1.8.0-x86-release.apk`
- `PureTone-v1.8.0-x86_64-release.apk`

> 安装：启用「未知来源」后直接安装对应 APK；universal 包兼容性最好但体积最大，单 ABI 包更小。

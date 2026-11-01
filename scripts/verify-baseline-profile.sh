#!/usr/bin/env bash
# Baseline Profile 有效性校验（CI 用，P2-1 配套）。
#
# 检查两件事：
# ① **规则里的类必须真的存在于 dex 中** —— 这是最实质的检查。
#    手写 profile 最常见的失败是「写了 dex 中不存在的类」：AGP 打包时静默跳过，
#    于是 profile 看着存在、实际不生效，性能收益为零且无人察觉。
# ② **标记来源** —— 文件头应标明是「采集产出」还是「手写占位」。
#    手写占位允许存在（当前仍是手写版），但必须如实标明，避免有人误以为已经采集过。
#
# 用法：
#   scripts/verify-baseline-profile.sh
#
# 退出码：0 = 通过；1 = 失败。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROFILE="app/src/main/baselineProfiles/baseline-prof.txt"
DEBUG_APK="${1:-}"

echo "=========================================================="
echo " Baseline Profile 有效性校验"
echo "=========================================================="

if [ ! -f "$PROFILE" ]; then
  echo "::error::profile 文件不存在：$PROFILE"
  exit 1
fi

# --- ① 类存在性校验（核心）---
echo ""
echo "[1/2] 校验规则中的类是否存在于 dex"
if [ -z "$DEBUG_APK" ]; then
  # 取最新构建的 debug APK（universal 优先——它含全部 ABI，类最全）
  DEBUG_APK=$(ls app/build/outputs/apk/debug/*universal*.apk 2>/dev/null | head -1)
  [ -z "$DEBUG_APK" ] && DEBUG_APK=$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
fi

if [ -z "$DEBUG_APK" ] || [ ! -f "$DEBUG_APK" ]; then
  echo "::error::找不到 debug APK 作为参照。请先执行 ./gradlew assembleDebug"
  exit 1
fi
echo "  参照 APK：$DEBUG_APK"

if ! python3 tools/verify_baseline_profile.py "$PROFILE" --apk "$DEBUG_APK"; then
  echo ""
  echo "::error::Baseline Profile 校验失败——存在 dex 中不存在的类（会被静默丢弃）"
  exit 1
fi

# --- ② 来源标记校验 ---
echo ""
echo "[2/2] 校验来源标记"
if grep -q "由 scripts/collect-baseline-profile.sh 于物理设备实测采集生成" "$PROFILE"; then
  echo "  ✅ 标记为「物理设备实测采集产出」"
  IS_COLLECTED=1
elif grep -q "手写 Baseline Profile" "$PROFILE"; then
  echo "  ⚠️ 当前为「手写占位」版本（P2-1 已交付采集链路，待有真机时执行采集替换）"
  echo "     执行：scripts/collect-baseline-profile.sh"
  IS_COLLECTED=0
else
  echo "::error::profile 文件头既没有「采集产出」标记也没有「手写占位」标记"
  echo "  请参照 scripts/collect-baseline-profile.sh 生成的格式补齐文件头"
  exit 1
fi

# --- 汇总 ---
echo ""
echo "=========================================================="
if [ "$IS_COLLECTED" = "1" ]; then
  echo " ✅ Baseline Profile 校验通过（采集产出）"
else
  echo " ✅ Baseline Profile 校验通过（手写占位，类全部有效）"
fi
echo "=========================================================="
echo ""
echo "提醒：当前 CI 只断言「profile 有效且被打包」，**不验证性能收益**。"
echo "     收益需在真机上做冷启动对比实测——官方数据量级 ~20~40%，"
echo "     但本项目需自行实测，不可直接套用官方数字。"

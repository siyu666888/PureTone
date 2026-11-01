#!/usr/bin/env bash
# Baseline Profile 真机采集脚本（P2-1）。
#
# 做什么：
#   1) 检查设备是否满足采集要求（**必须是物理设备**）；
#   2) 构建可 profile 的 benchmark 包（app + benchmark 模块）；
#   3) 跑 BaselineProfileGenerator 采集三条路径；
#   4) 把采集产物拷进 app/src/main/baselineProfiles/baseline-prof.txt；
#   5) 校验产物：类是否真的存在于 dex 中（防止手抄式错误）。
#
# ⚠️ 为什么必须物理设备：
#   androidx.benchmark 的 Macrobenchmark 在模拟器上会被拒绝——
#   模拟器设备指纹含 "generic"、无 cpufreq、ART 行为与真机不同，
#   采集出的 profile 在真机上没有收益（甚至可能有害）。
#   官方要求用 physical device 或经 Google 认证的 Gradle Managed Device。
#
# 用法：
#   scripts/collect-baseline-profile.sh                    # 自动选唯一设备
#   scripts/collect-baseline-profile.sh <device-serial>    # 指定设备
#   scripts/collect-baseline-profile.sh --check-only       # 只检查环境，不采集

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROFILE_OUT="app/src/main/baselineProfiles/baseline-prof.txt"
PKG="com.puretone.player"

CHECK_ONLY=0
SERIAL=""
for arg in "$@"; do
  case "$arg" in
    --check-only) CHECK_ONLY=1 ;;
    *) SERIAL="$arg" ;;
  esac
done

ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
if [ -z "$ADB" ] || [ ! -x "$ADB" ]; then
  echo "::error::找不到 adb。请设置 ANDROID_HOME 或把 adb 加入 PATH。"
  exit 1
fi

echo "=========================================================="
echo " PureTone Baseline Profile 采集（P2-1）"
echo "=========================================================="

# ---------------------------------------------------------------------------
# 一、环境检查
# ---------------------------------------------------------------------------
echo ""
echo "[1/5] 检查采集设备"
if [ -n "$SERIAL" ]; then
  DEVICES="$("$ADB" devices | awk -v s="$SERIAL" '$1==s {print $1}')"
else
  DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
fi

if [ -z "$DEVICES" ]; then
  echo "::error::没有可用的 adb 设备。请连接真机并开启 USB 调试。"
  exit 1
fi

# 多设备时要求显式指定，避免采到错设备
DEV_COUNT=$(echo "$DEVICES" | wc -l | tr -d ' ')
if [ "$DEV_COUNT" -gt 1 ] && [ -z "$SERIAL" ]; then
  echo "::error::检测到 $DEV_COUNT 台设备，请显式指定 serial："
  echo "$DEVICES" | sed 's/^/  - /'
  exit 1
fi

TARGET=$(echo "$DEVICES" | head -1)
echo "  目标设备：$TARGET"

FINGERPRINT="$("$ADB" -s "$TARGET" shell getprop ro.build.fingerprint 2>/dev/null | tr -d '\r')"
MODEL="$("$ADB" -s "$TARGET" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
SDK="$("$ADB" -s "$TARGET" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
echo "  型号：$MODEL"
echo "  API：$SDK"
echo "  指纹：$FINGERPRINT"

# 模拟器判定：指纹含 generic / sdk_gphone / 模拟器常见 model
IS_EMULATOR=0
case "$FINGERPRINT" in
  *generic*|sdk_gphone*|emulator*) IS_EMULATOR=1 ;;
esac
case "$MODEL" in
  sdk_gphone*|Android\ SDK\ built\ for*|Emulator*) IS_EMULATOR=1 ;;
esac
# 还有一条硬判据：emulator-XXXX 的 serial
case "$TARGET" in
  emulator-*) IS_EMULATOR=1 ;;
esac

if [ "$IS_EMULATOR" = "1" ]; then
  echo ""
  echo "::error::检测到模拟器（serial=$TARGET）。"
  echo "  Macrobenchmark 在模拟器上采集出的 profile 在真机上没有收益"
  echo "  （ART 行为、类加载时机、dex 布局都与真机不同）。"
  echo "  请连接物理 Android 设备后重试。"
  echo ""
  echo "  附：模拟器可用于跑普通功能验证（见 dist/verify_*/ 下的验机报告），"
  echo "  但 Baseline Profile 采集必须真机。"
  exit 1
fi

if [ "$SDK" -lt 28 ] 2>/dev/null; then
  echo "::error::设备 API $SDK < 28。采集要求 API 28+（ProfileInstaller 与 ART AOT 的最低要求）。"
  exit 1
fi

# ProfileInstaller / ART AOT 还需要设备未禁用 dex2oat
echo "  ✅ 物理设备检查通过"

if [ "$CHECK_ONLY" = "1" ]; then
  echo ""
  echo "（--check-only）环境检查通过，结束。"
  exit 0
fi

# ---------------------------------------------------------------------------
# 二、权限准备
# ---------------------------------------------------------------------------
echo ""
echo "[2/5] 准备权限"
# 采集需要媒体读取权限，否则应用停在引导页，走不到曲库/播放页。
# Android 13+ 是 READ_MEDIA_AUDIO，更低版本是 READ_EXTERNAL_STORAGE。
PERM="android.permission.READ_EXTERNAL_STORAGE"
[ "$SDK" -ge 33 ] && PERM="android.permission.READ_MEDIA_AUDIO"
"$ADB" -s "$TARGET" shell pm grant "$PKG" "$PERM" 2>/dev/null \
  && echo "  已授予 $PERM" \
  || echo "  ::warning::授予 $PERM 失败（若应用停在引导页，请手动授予后重试）"
# POST_NOTIFICATIONS 是软权限，给了更接近真实使用
"$ADB" -s "$TARGET" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# ---------------------------------------------------------------------------
# 三、构建
# ---------------------------------------------------------------------------
echo ""
echo "[3/5] 构建 benchmark 包（R8 开启 + 可 profile）"
./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark --no-daemon
if [ $? -ne 0 ]; then
  echo "::error::构建失败"
  exit 1
fi

# ---------------------------------------------------------------------------
# 四、采集
# ---------------------------------------------------------------------------
echo ""
echo "[4/5] 采集 Baseline Profile（三条路径）"
export ANDROID_SERIAL="$TARGET"
./gradlew :benchmark:generateBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR \
  --no-daemon
GEN_STATUS=$?
if [ $GEN_STATUS -ne 0 ]; then
  echo "::error::采集失败（退出码 $GEN_STATUS）"
  echo "  常见原因："
  echo "  - 设备息屏/锁屏 → 先解锁并保持屏幕常亮"
  echo "  - 设备有其它应用抢占 → 关闭后台应用"
  echo "  - 应用崩溃 → 查看 logcat: adb logcat -s AndroidRuntime:E"
  exit $GEN_STATUS
fi

# ---------------------------------------------------------------------------
# 五、产物落位与校验
# ---------------------------------------------------------------------------
echo ""
echo "[5/5] 落位与校验产物"
GEN_DIR="benchmark/build/outputs/baselineProfiles"
if [ ! -d "$GEN_DIR" ]; then
  echo "::error::未找到采集产物目录：$GEN_DIR"
  exit 1
fi

SRC_PROFILE=""
for f in "$GEN_DIR"/*.prof; do
  [ -f "$f" ] && SRC_PROFILE="$f" && break
done
if [ -z "$SRC_PROFILE" ]; then
  echo "::error::采集产物目录里没有 .prof 文件"
  ls -la "$GEN_DIR"
  exit 1
fi

echo "  采集产物：$SRC_PROFILE"
echo "  规则行数：$(wc -l < "$SRC_PROFILE" | tr -d ' ')"

# 备份旧的（手写版），便于回滚
if [ -f "$PROFILE_OUT" ]; then
  cp "$PROFILE_OUT" "$PROFILE_OUT.bak"
  echo "  旧版已备份到 $PROFILE_OUT.bak"
fi

{
  echo "# Baseline Profile —— 由 scripts/collect-baseline-profile.sh 于物理设备实测采集生成"
  echo "#"
  echo "# 采集设备：$MODEL / API $SDK / $TARGET"
  echo "# 采集时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 采集路径：startupJourney / startupToPlayerJourney / playerInteractionJourney"
  echo "#"
  echo "# ⚠️ 不要手工编辑本文件——手改的行可能引用 dex 中不存在的类，"
  echo "#    导致 profgen 报 \"Cannot find class\" 或安装时静默丢弃。"
  echo "#    需要变更请重新采集。"
  echo ""
  cat "$SRC_PROFILE"
} > "$PROFILE_OUT"

echo "  ✅ 已写入 $PROFILE_OUT"

# 校验：规则里的类是否真的存在于 dex（用 debug APK 的 dex 做参照，
# 因为 release 的 dex 被 R8 重命名过，源码类名会被 mapping 改写）
echo ""
echo "  校验：抽取类名与 APK dex 求交"
python3 tools/verify_baseline_profile.py "$PROFILE_OUT" || {
  echo "::error::校验失败"
  exit 1
}

echo ""
echo "=========================================================="
echo " ✅ 采集完成"
echo "=========================================================="
echo "文件：$PROFILE_OUT"
echo "下一步："
echo "  1) git add $PROFILE_OUT"
echo "  2) CI 会断言 release 包内含 assets/dexopt/baseline.prof"
echo "  3) 建议在真机上做一次冷启动对比，确认有实际收益（官方数据量级 ~20~40%，"
echo "     但**本项目需实测**，不可直接套用官方数字）"

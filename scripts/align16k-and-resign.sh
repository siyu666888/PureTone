#!/usr/bin/env bash
# 16KB ZIP 偏移对齐 + 重签（P0-3 16KB 页设备兼容补全）。
#
# 背景：AGP 9.5.1 在 minSdk<35 时默认按 4KB 对齐未压缩的 .so；而 16KB 页设备要求
# .so 在 APK 内也按 16KB 偏移存储才能 mmap 直载（否则加载失败）。
# AGP 无 minSdk<35 下的 16KB 对齐开关（对齐由 minSdk>=35 触发，升 minSdk 会砍掉
# Android 8-14 设备支持，不可取）。故本脚本对已签名的 release APK 重新 zipalign 并
# apksigner 重签，使产物满足 16KB 页设备要求，且设备支持面保持不变。
#
# 关键：zipalign 的 16KB 页对齐用 -P 16（<pagesize_kb> 可取 4/16/64），而非 -p 16。
#   - -p        = 4KB 页对齐 .so（经典 Android 默认），其后的 16 被当作「逐条目字节对齐」位置参数，
#                 导致 .so 实际仍按 4KB 存储——这是早期脚本版本对齐校验「假性失败」的根因。
#   - -P 16 4   = .so 按 16KB 页对齐 + 其余 STORED 条目按 4 字节对齐（位置参数 4 必填）。
#   经验证：zipalign -P 16 4 后 apksigner 重签（v1+v2+v3）不会移动 .so 数据偏移，
#   16KB 对齐在签名后依然保持（zipalign -c -P 16 4 通过）。
#
# 用法：scripts/align16k-and-resign.sh [apk ...]
#   缺省处理 app/build/outputs/apk/release/*.apk
#
# 前置：local.properties 含 storeFile/storePassword/keyAlias/keyPassword；ANDROID_HOME 已设。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "::error::ANDROID_HOME 未设置" >&2
  exit 1
fi

BT="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)"
APKSIGNER="$BT/apksigner.bat"; [ -f "$APKSIGNER" ] || APKSIGNER="$BT/apksigner"
ZIPALIGN="$BT/zipalign.exe"; [ -f "$ZIPALIGN" ] || ZIPALIGN="$BT/zipalign"

if [ ! -f "$APKSIGNER" ] || [ ! -f "$ZIPALIGN" ]; then
  echo "::error::找不到 apksigner/zipalign（build-tools=$BT）" >&2
  exit 1
fi

# 读取 local.properties（与 app/build.gradle.kts 同源）
props() { grep -E "^$1=" local.properties 2>/dev/null | head -1 | cut -d= -f2-; }
STORE_FILE="$(props storeFile)"; STORE_FILE="${STORE_FILE:-keystore/puretone-release.jks}"
STORE_PW="$(props storePassword)"; STORE_PW="${STORE_PW:-}"
KEY_ALIAS="$(props keyAlias)"; KEY_ALIAS="${KEY_ALIAS:-puretone}"
KEY_PW="$(props keyPassword)"; KEY_PW="${KEY_PW:-}"

if [ ! -f "$STORE_FILE" ]; then
  echo "::error::keystore 不存在：$STORE_FILE" >&2
  exit 1
fi

APKS=("$@")
if [ ${#APKS[@]} -eq 0 ]; then
  APKS=(app/build/outputs/apk/release/*.apk)
fi

echo "build-tools: $BT"
echo "keystore:    $STORE_FILE (alias=$KEY_ALIAS)"
echo "=========================================================="

for apk in "${APKS[@]}"; do
  [ -f "$apk" ] || { echo "::warn::跳过（不存在）：$apk"; continue; }
  tmp="${apk%.apk}.aligned.tmp"
  echo ">> $apk"
  "$ZIPALIGN" -P 16 4 "$apk" "$tmp"
  "$APKSIGNER" sign \
    --ks "$STORE_FILE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PW" --key-pass "pass:$KEY_PW" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    "$tmp"
  if "$ZIPALIGN" -c -P 16 4 "$tmp" >/dev/null 2>&1 && "$APKSIGNER" verify "$tmp" >/dev/null 2>&1; then
    mv -f "$tmp" "$apk"
    # apksigner 会生成同名 .idsig 侧车（V3 轮换证明，设备端不需要），随临时文件清理
    rm -f "$tmp.idsig"
    echo "   ✅ 16KB 对齐 + 重签完成"
  else
    echo "::error::对齐/重签校验失败，保留原始 APK，删除临时文件" >&2
    rm -f "$tmp"
    exit 1
  fi
done

echo "=========================================================="
echo " ✅ 全部 APK 已 16KB 对齐并重签"

#!/usr/bin/env bash
# PureTone 安装包交付脚本。
#
# 职责（唯一）：把 :app:assembleRelease 的原生 APK 收集到工程内唯一的交付输出目录
#   D:/PureTone/dist/output/
# 并逐包做 sha256 一致性校验、生成 SHA256SUMS.txt 清单。
#
# 约定：
#   - **不再向桌面输出任何安装包**。需要对外分发时，从 dist/output/ 取包。
#   - 本脚本是「事后收集」，不改动 Gradle 构建配置、签名、ABI 分包或 CI 流程。
#   - 同名目标已存在且内容相同 → 跳过；内容不同（重新构建导致 APK 字节变化）→
#     旧包先无损移入 dist/archive/v<版本>/<时间戳>_构建更新前输出/，再写入新包，绝不覆盖。
#   - 历史交付包按版本/批次归档在 dist/archive/，与本脚本的 dist/output/ 相互独立。
#
# 用法：
#   ./scripts/package-release.sh                 # 收集三包 + 清单
#   ./scripts/package-release.sh --zip 交付标签   # 额外打一个三包 zip（便于上传/传输）
#
# 前置：先执行 ./gradlew assembleRelease（或本地等价的 gradle assembleRelease）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/build/outputs/apk/release"
OUT="$ROOT/dist/output"
ARC="$ROOT/dist/archive"
GRADLE="$ROOT/app/build.gradle.kts"
ABIS=(arm64-v8a x86_64 universal)

ZIP_LABEL=""
if [ "${1:-}" = "--zip" ]; then
    ZIP_LABEL="${2:-}"
    if [ -z "$ZIP_LABEL" ]; then
        echo "ERROR: --zip 需要一个交付标签，例如 --zip 播放核心单测加固" >&2
        exit 2
    fi
elif [ -n "${1:-}" ]; then
    echo "ERROR: 未知参数 '$1'（仅支持 --zip <标签>）" >&2
    exit 2
fi

# 版本号唯一来源：app/build.gradle.kts 的 appVersionName。
VERSION="$(sed -n 's/^val appVersionName = "\([^"]*\)".*/\1/p' "$GRADLE" | head -n 1)"
if [ -z "$VERSION" ]; then
    echo "ERROR: 无法从 $GRADLE 读取 appVersionName" >&2
    exit 1
fi

mkdir -p "$OUT"

# 预检：三包必须齐全，缺一即失败（不允许产出「半套」交付物）。
for abi in "${ABIS[@]}"; do
    f="$SRC/PureTone-v$VERSION-$abi-release.apk"
    if [ ! -f "$f" ]; then
        echo "ERROR: 缺少构建产物 $f" >&2
        echo "       请先执行 ./gradlew assembleRelease" >&2
        exit 1
    fi
done

echo "版本 v$VERSION · 源 $SRC · 输出 $OUT"

# 重新构建会让 APK 字节变化（打包时间戳等）：先把上一批输出无损归档，再写新包。
# 归档按「版本 / 时间」分层，与 dist/archive/ 既有约定一致；绝不覆盖、不产生重复副本。
PREV=""
for abi in "${ABIS[@]}"; do
    name="PureTone-v$VERSION-$abi-release.apk"
    src="$SRC/$name"
    dst="$OUT/$name"
    if [ -e "$dst" ] && ! cmp -s "$src" "$dst"; then
        if [ -z "$PREV" ]; then
            STAMP="$(date '+%Y-%m-%d_%H%M')_构建更新前输出"
            PREV="$ARC/v$VERSION/$STAMP"
            mkdir -p "$PREV"
        fi
        old="$(sha256sum "$dst" | cut -d' ' -f1)"
        mv -n "$dst" "$PREV/$name"
        moved="$(sha256sum "$PREV/$name" | cut -d' ' -f1)"
        if [ "$old" != "$moved" ]; then
            echo "ERROR: 旧输出归档校验失败：$name" >&2
            exit 1
        fi
        echo "ARCHIVE 旧输出 → dist/archive/v$VERSION/$STAMP/$name  ${old:0:16}…"
    fi
done
if [ -n "$PREV" ] && [ -f "$OUT/SHA256SUMS.txt" ]; then
    mv -n "$OUT/SHA256SUMS.txt" "$PREV/SHA256SUMS.txt"
    echo "ARCHIVE 旧清单 → $PREV/SHA256SUMS.txt"
fi

# 复制：不覆盖、不产生重复副本。
for abi in "${ABIS[@]}"; do
    name="PureTone-v$VERSION-$abi-release.apk"
    src="$SRC/$name"
    dst="$OUT/$name"
    if [ -e "$dst" ]; then
        if cmp -s "$src" "$dst"; then
            echo "SKIP  已存在且内容一致：$name"
            continue
        fi
        echo "ERROR: 目标已存在且内容不同，拒绝覆盖：$dst" >&2
        echo "       若确需保留两版，请先把旧包归档到 dist/archive/ 再重跑。" >&2
        exit 1
    fi
    cp -p "$src" "$dst"
    echo "COPY  $name"
done

# 逐包校验：源文件与目标文件 sha256 必须一致（证明完整、未损坏）。
echo "--- sha256 校验 ---"
for abi in "${ABIS[@]}"; do
    name="PureTone-v$VERSION-$abi-release.apk"
    a="$(sha256sum "$SRC/$name" | cut -d' ' -f1)"
    b="$(sha256sum "$OUT/$name" | cut -d' ' -f1)"
    if [ "$a" != "$b" ]; then
        echo "ERROR: sha256 不一致：$name" >&2
        exit 1
    fi
    echo "OK    ${a:0:16}…  $name"
done

# 可选：三包打 zip（沿用历次交付习惯；用 python zipfile，不依赖系统 zip 命令）。
if [ -n "$ZIP_LABEL" ]; then
    ZIP_NAME="PureTone-v$VERSION-$ZIP_LABEL.zip"
    ZIP_PATH="$OUT/$ZIP_NAME"
    if [ -e "$ZIP_PATH" ]; then
        echo "ERROR: zip 已存在，拒绝覆盖：$ZIP_PATH" >&2
        exit 1
    fi
    PY=""
    for cand in python python3 py; do
        if command -v "$cand" >/dev/null 2>&1; then PY="$cand"; break; fi
    done
    if [ -z "$PY" ]; then
        echo "ERROR: 未找到 python，无法生成 zip（三包已正常输出到 dist/output/）" >&2
        exit 1
    fi
    "$PY" - "$OUT" "$VERSION" "$ZIP_PATH" <<'PYCODE'
import os, sys, zipfile
out, version, zip_path = sys.argv[1], sys.argv[2], sys.argv[3]
abis = ("arm64-v8a", "x86_64", "universal")
tmp = zip_path + ".tmp"
with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED) as z:
    for abi in abis:
        name = "PureTone-v%s-%s-release.apk" % (version, abi)
        z.write(os.path.join(out, name), arcname=name)
os.replace(tmp, zip_path)
print("ZIP   %s" % os.path.basename(zip_path))
PYCODE
fi

# 交付清单（不入库；每次出包重新生成）。
SUMS="$OUT/SHA256SUMS.txt"
{
    echo "# PureTone 安装包交付清单"
    echo "# 生成时间: $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "# 版本    : v$VERSION"
    echo "# commit  : $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
    echo "# 来源    : app/build/outputs/apk/release（:app:assembleRelease 原生产物）"
    echo "# 输出    : dist/output（唯一交付输出目录，不再输出到桌面）"
    echo "#"
    ( cd "$OUT" && sha256sum PureTone-v"$VERSION"-*-release.apk )
} > "$SUMS"

echo "--- 完成 ---"
echo "输出目录 : $OUT"
echo "清单     : $SUMS"
echo "提示     : 需要分发时从 dist/output/ 取包；历史包归档在 dist/archive/。"

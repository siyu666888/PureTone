#!/usr/bin/env bash
# 文件行数门禁（P2-2 配套）：卡住「下一个 1000+ 行文件」的出现。
#
# 为什么需要它（detekt 覆盖不到的部分）：
# - detekt 的 LargeClass 只统计**类体**行数，而本项目的大文件多为
#   Compose 顶层函数 + 顶层状态（PlayerScreen.kt 1209 行、BrowseScreen.kt 979 行），
#   这些根本不在 class 里 —— LargeClass 对它们完全无效；
# - detekt 的 LongMethod 虽能覆盖顶层函数，但对 Compose 声明式代码是系统性误报
#   （详见 config/detekt/detekt.yml 的说明），已按 ui 包排除；
# - 于是「文件规模」这条线在两个工具之间成了盲区，需要一道显式检查补上。
#
# 阈值设计：
# - 1200 行：现状最大 PlayerScreen.kt 1209 行 —— 恰好被拦。
#   这不是巧合：它意味着**下一个同级文件一落地就会失败**，
#   想加新文件必须先拆分现有的，起到「结构性压力」的作用。
# - 存量豁免用文件级白名单，且必须写明豁免理由与拆分计划 ——
#   白名单是临时的债务记录，不是永久通行证。
#
# 用法：
#   scripts/check-file-size.sh            # 校验（有超标即 exit 1）
#   scripts/check-file-size.sh --report   # 只报告不失败（本地排查用）

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT/app/src/main/java"
LIMIT=1200
REPORT_ONLY=0
[ "${1:-}" = "--report" ] && REPORT_ONLY=1

# 存量豁免：文件路径（相对 SRC_DIR）| 行数 | 豁免理由
# 新增豁免时必须写清理由——这行字就是未来的拆分 TODO。
WHITELIST=(
  "ui/screens/PlayerScreen.kt|1209|播放页主屏：封面翻转 + 歌词面板 + 进度条 + 队列预览；拆分计划见 P3 段「歌单功能补完」后的 UI 重构"
)

FAILED=0
OVERFLOW=()

while IFS= read -r file; do
  # 相对 app/src/main/java/ 的路径（去掉 com/puretone/player/ 前缀，便于阅读）
  rel="${file#"$SRC_DIR"/}"
  rel="${rel//\\//}"
  rel="${rel#com/puretone/player/}"
  lines="$(wc -l < "$file" | tr -d ' ')"
  [ "$lines" -le "$LIMIT" ] && continue

  # 查白名单
  allowed=""
  for entry in "${WHITELIST[@]}"; do
    wl_file="${entry%%|*}"
    if [ "$wl_file" = "$rel" ]; then
      allowed="$entry"
      break
    fi
  done

  if [ -n "$allowed" ]; then
    wl_lines="$(echo "$allowed" | cut -d'|' -f2)"
    wl_reason="$(echo "$allowed" | cut -d'|' -f3)"
    if [ "$lines" -gt "$wl_lines" ]; then
      echo "::error file=$rel::行数 $lines 超过豁免上限 $wl_lines（豁免理由：$wl_reason）"
      OVERFLOW+=("$rel ($lines > $wl_lines)")
      FAILED=1
    else
      echo "OK   $rel: $lines 行（豁免上限 $wl_lines）"
    fi
  else
    echo "::error file=$rel::行数 $lines 超过上限 $LIMIT"
    OVERFLOW+=("$rel ($lines)")
    FAILED=1
  fi
done < <(find "$SRC_DIR" -name "*.kt" -type f)

TOTAL=$(find "$SRC_DIR" -name "*.kt" -type f | wc -l | tr -d ' ')
echo ""
echo "文件行数门禁：共扫描 $TOTAL 个主源文件，上限 $LIMIT 行"
if [ ${#OVERFLOW[@]} -gt 0 ]; then
  echo "超标文件："
  for o in "${OVERFLOW[@]}"; do echo "  - $o"; done
else
  echo "全部通过"
fi

if [ "$REPORT_ONLY" = "1" ]; then
  exit 0
fi
exit $FAILED

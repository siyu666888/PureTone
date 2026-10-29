#!/usr/bin/env bash
# backup-keystore.sh — PureTone 发布签名密钥备份 + GitHub Secrets 素材生成
#
# 作用（只做复制，不删除/不移动原始密钥，不把口令写进备份目录）：
#   1) 把 app/keystore/puretone-release.jks 复制到 ≥2 个异地安全位置
#      （本地专用备份目录 + 云同步盘，后者会随网盘同步到异地）。
#      注：PKCS12 密钥库本身已用 storePassword 加密，复制出去的文件
#      没有口令无法使用，可安全存放；口令只存在于 local.properties。
#   2) 生成 GitHub Actions Secret 所需素材：
#        - SIGNING_KEY : 密钥库 base64（单行），写入仓库外文件供复制
#        - 并在终端提示 KEY_ALIAS / KEY_STORE_PASSWORD / KEY_PASSWORD 的取值
#
# 用法（Git Bash）：  bash scripts/backup-keystore.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO_ROOT/app/keystore/puretone-release.jks"

if [ ! -f "$SRC" ]; then
  echo ":: 错误：找不到密钥库 $SRC" >&2
  exit 1
fi

TS="$(date +%Y%m%d-%H%M%S)"
BASENAME="puretone-release.jks"

# 备份目标：均为仓库之外的安全位置（可按需增删）
BACKUP_TARGETS=(
  "/d/keystore-backups"
  "/d/BaiduSyncdisk/PureTone-keystore"
)

echo "==> 源密钥库: $SRC ($(wc -c < "$SRC") bytes)"
for dst_dir in "${BACKUP_TARGETS[@]}"; do
  mkdir -p "$dst_dir"
  cp -p "$SRC" "$dst_dir/$BASENAME.$TS"      # 带时间戳的历史副本
  cp -p "$SRC" "$dst_dir/$BASENAME"           # 同名“最新”副本，便于一键还原
  echo "    已备份 -> $dst_dir/$BASENAME.$TS"
done

# 生成 SIGNING_KEY（base64 单行，禁用换行便于直接粘贴进 Secret）
B64_OUT="/d/keystore-backups/${BASENAME}.${TS}.b64"
mkdir -p "$(dirname "$B64_OUT")"
base64 -w0 "$SRC" > "$B64_OUT"
echo
echo "==> GitHub Secret: SIGNING_KEY"
echo "    内容已写入 $B64_OUT ($(wc -c < "$B64_OUT") 字节)，复制其全部内容到仓库 Secrets"

# 提示其余 Secret 取值（取自 local.properties 的真实字段名）
# local.properties: keyAlias / storePassword / keyPassword
read_prop() { grep -m1 -E "^${1}=" "$2" 2>/dev/null | cut -d= -f2-; }

echo
echo "==> 请在仓库 Secrets 补充以下值（取自 local.properties，勿与密钥同处存放）："
ALIAS_VAL="$(read_prop keyAlias "$REPO_ROOT/local.properties")"
SP_VAL="$(read_prop storePassword "$REPO_ROOT/local.properties")"
KP_VAL="$(read_prop keyPassword "$REPO_ROOT/local.properties")"
echo "    KEY_ALIAS            = ${ALIAS_VAL:-puretone}"
echo "    KEY_STORE_PASSWORD   = ${SP_VAL:-<请在 local.properties 查看>}"
echo "    KEY_PASSWORD         = ${KP_VAL:-<请在 local.properties 查看>}"

echo
echo "==> 完成。原始密钥未被移动/删除：$SRC"

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验 Baseline Profile 里的类是否真的存在于 APK 的 dex 中。

为什么需要：手写 profile 最常见的失败是「规则里写了 dex 中不存在的类」——
AGP 在打包时会静默跳过这些规则，于是 profile 看着存在、实际不生效，
性能收益为零且无人察觉。

原理：
  1) 从 debug APK 的 classes*.dex 导出全部类名（dexdump 或直接解析 dex 字符串池）；
  2) 从 profile 中抽出 L<全限定名>; 形式的类级规则；
  3) 求交，报告「存在于 dex 的」与「不存在的」。

注意 release 包经 R8 重命名，源码类名会被 mapping 改写，所以这里用
**debug APK** 做参照（debug 不混淆，类名与源码一致）。

用法：
  python tools/verify_baseline_profile.py [profile 文件] [--apk <debug apk>]

退出码：0 = 全部命中（或 profile 为空）；1 = 有类不存在。
"""
import argparse
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Lcom/example/Foo;  →  com.example.Foo
CLASS_RULE = re.compile(r"^(L[^;]+;)$")


def dex_class_names(apk_path: Path) -> set[str]:
    """从 APK 的 dex 里提取全部类名（内部形式 com/example/Foo）。"""
    names: set[str] = set()
    with zipfile.ZipFile(apk_path) as zf:
        dex_files = [n for n in zf.namelist() if n.endswith(".dex")]
        for dex in dex_files:
            data = zf.read(dex)
            # dex 字符串池里的类描述符形如 "Lcom/example/Foo;"
            for m in re.finditer(rb"L([A-Za-z0-9_$/]+);", data):
                names.add(m.group(1).decode("ascii", errors="ignore"))
    return names


def profile_classes(profile_path: Path) -> list[str]:
    """抽取 profile 中的类级规则（返回内部形式 com/example/Foo）。"""
    out: list[str] = []
    for raw in profile_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = CLASS_RULE.match(line)
        if m:
            out.append(m.group(1)[1:-1])
    return out


def find_debug_apk(explicit: str | None) -> Path | None:
    if explicit:
        p = Path(explicit)
        return p if p.exists() else None
    candidates = sorted(
        (ROOT / "app/build/outputs/apk/debug").glob("*.apk"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    return candidates[0] if candidates else None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("profile", nargs="?", default=str(ROOT / "app/src/main/baselineProfiles/baseline-prof.txt"))
    ap.add_argument("--apk", help="参照用的 debug APK（默认取 app/build/outputs/apk/debug 下最新的）")
    args = ap.parse_args()

    profile_path = Path(args.profile)
    if not profile_path.exists():
        print(f"::error::profile 文件不存在：{profile_path}")
        return 1

    rules = profile_classes(profile_path)
    if not rules:
        print("::error::profile 中没有找到任何类级规则（L<全限定名>;）")
        return 1

    apk = find_debug_apk(args.apk)
    if apk is None:
        print("::error::找不到参照用的 debug APK。请先执行：./gradlew assembleDebug")
        print("        或用 --apk <path> 显式指定。")
        return 1

    print(f"profile：{profile_path}（{len(rules)} 条类级规则）")
    print(f"参照 APK：{apk}")

    dex_names = dex_class_names(apk)
    print(f"dex 类总数：{len(dex_names)}")

    missing = [r for r in rules if r not in dex_names]
    hit = len(rules) - len(missing)

    print("")
    print(f"命中：{hit}/{len(rules)}（{hit * 100 // max(len(rules), 1)}%）")

    if missing:
        print("")
        print(f"::error::以下 {len(missing)} 条规则的类在 dex 中不存在（会被静默丢弃）：")
        for m in missing[:40]:
            print(f"  - {m}")
        if len(missing) > 40:
            print(f"  ... 另有 {len(missing) - 40} 条")
        return 1

    print("")
    print("✅ 全部类级规则都能在 dex 中找到")
    return 0


if __name__ == "__main__":
    sys.exit(main())

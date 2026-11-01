#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
16KB 页大小兼容性检查（P0-3 · 第一步「先测再决策」）。

背景
----
Android 15 起支持 16KB 页大小设备。若要上架 Google Play，
APK 必须在 16KB 设备上可加载。这件事有两层，**缺一不可**：

① **ZIP 层**：APK 内未压缩（Stored）的 .so，其 zip 条目必须按 16KB 对齐。
   理由：extractNativeLibs=false 时 .so 直接从 APK 内 mmap 加载，
   条目起始偏移不对齐就会 mmap 失败。
   → 这一层由 AGP 打包时保证，用 `zipalign -c -P 16 -v 4 <apk>` 检查。

② **ELF 层**：每个 .so 的 PT_LOAD 段的 p_align 必须 ≥ 16384。
   理由：linker 在 16KB 页设备上按页对齐映射 LOAD 段，
   若 .so 用 4KB 页编译（p_align=4096），映射会失败。
   → 这一层取决于**第三方库怎么编译的**，打包工具管不了，
   只能逐个 .so 检查。

本脚本只做 ②（ZIP 层请用 zipalign）。用法：

    python3 tools/check_16kb_alignment.py app/build/outputs/apk/release/*.apk

退出码：0 = 全部合规；1 = 存在不合规的 .so（列出清单）。

实现说明
--------
不依赖 NDK / llvm-readelf——直接按 ELF 规范解析文件头与 program header：
  ELF64: e_phoff@0x20(u64), e_phentsize@0x36(u16), e_phnum@0x38(u16)
         PT_LOAD 项: p_type@0(u32), ..., p_align@0x30(u64)
  ELF32: e_phoff@0x1C(u32), e_phentsize@0x2A(u16), e_phnum@0x2C(u16)
         PT_LOAD 项: p_type@0(u32), ..., p_align@0x1C(u32)
"""

import struct
import sys
import zipfile

PAGE_SIZE_16KB = 16384

# PT_LOAD = 1
PT_LOAD = 1


def read_elf_load_aligns(data: bytes):
    """返回 (load 段数量, 所有 PT_LOAD 的 p_align 列表)。解析失败抛 ValueError。"""
    if len(data) < 20 or data[:4] != b"\x7fELF":
        raise ValueError("不是 ELF 文件")

    ei_class = data[4]  # 1 = 32bit, 2 = 64bit
    endian = data[5]  # 1 = little, 2 = big
    if endian == 1:
        e = "<"
    elif endian == 2:
        e = ">"
    else:
        raise ValueError("未知字节序 %d" % endian)

    if ei_class == 2:  # ELF64
        if len(data) < 64:
            raise ValueError("ELF64 头不完整")
        phoff = struct.unpack_from(e + "Q", data, 0x20)[0]
        phentsize = struct.unpack_from(e + "H", data, 0x36)[0]
        phnum = struct.unpack_from(e + "H", data, 0x38)[0]
        align_off, align_size, align_fmt = 0x30, 8, e + "Q"
    elif ei_class == 1:  # ELF32
        if len(data) < 52:
            raise ValueError("ELF32 头不完整")
        phoff = struct.unpack_from(e + "I", data, 0x1C)[0]
        phentsize = struct.unpack_from(e + "H", data, 0x2A)[0]
        phnum = struct.unpack_from(e + "H", data, 0x2C)[0]
        align_off, align_size, align_fmt = 0x1C, 4, e + "I"
    else:
        raise ValueError("未知 ELF class %d" % ei_class)

    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if off + phentsize > len(data):
            raise ValueError("program header 越界")
        p_type = struct.unpack_from(e + "I", data, off)[0]
        if p_type == PT_LOAD:
            aligns.append(struct.unpack_from(align_fmt, data, off + align_off)[0])
    return len(aligns), aligns


def check_apk(path: str):
    """返回 (不合规条目列表, 受检 .so 数量, 解析失败列表)。"""
    bad, checked, failed = [], 0, []
    with zipfile.ZipFile(path) as zf:
        names = [n for n in zf.namelist() if n.endswith(".so")]
        for name in sorted(names):
            data = zf.read(name)
            try:
                _, aligns = read_elf_load_aligns(data)
            except ValueError as exc:
                failed.append((name, str(exc)))
                continue
            if not aligns:
                failed.append((name, "无 PT_LOAD 段"))
                continue
            checked += 1
            # 所有 LOAD 段都必须满足 16KB 对齐；取最小值判定
            worst = min(aligns)
            if worst < PAGE_SIZE_16KB:
                bad.append((name, worst, aligns))
    return bad, checked, failed


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2

    total_bad = 0
    total_checked = 0
    total_failed = 0
    total_unreadable = 0
    print("=" * 68)
    print(" 16KB 页大小兼容性检查 · ELF LOAD 段对齐")
    print("=" * 68)

    for apk in argv[1:]:
        print("\n▶ %s" % apk)
        try:
            bad, checked, failed = check_apk(apk)
        except (zipfile.BadZipFile, OSError) as exc:
            print("   ::error::无法读取 APK：%s" % exc)
            total_unreadable += 1
            continue

        total_checked += checked
        total_failed += len(failed)
        for name, reason in failed:
            print("   ⚠ %-52s 解析失败：%s" % (name, reason))

        if not bad and not failed:
            if checked == 0:
                print("   · APK 内无 .so —— 无原生库，无 16KB 约束")
            else:
                print("   ✅ %d 个 .so 全部合规（PT_LOAD p_align ≥ %d）"
                      % (checked, PAGE_SIZE_16KB))
        elif not bad and failed:
            # 关键：解析失败 ≠ 合规。
            # 若在此处报「全部合规」，则「所有 .so 都解析失败」这种极端情况会给出假绿——
            # 那是门禁最危险的失效模式（悄悄放行一切）。
            print("   ❓ %d 个 .so 合规，但有 %d 个**无法判定**——不得据此认为合规"
                  % (checked, len(failed)))
        else:
            total_bad += len(bad)
            print("   ❌ %d / %d 个 .so 不合规：" % (len(bad), checked))
            for name, worst, aligns in bad:
                uniq = sorted(set(aligns))
                print("      %-52s p_align=%s（各段：%s）"
                      % (name, worst, uniq))

    print("\n" + "-" * 68)
    print("合计：检查 %d 个 .so，不合规 %d 个，无法判定 %d 个，APK 读取失败 %d 个"
          % (total_checked, total_bad, total_failed, total_unreadable))
    if total_unreadable:
        print("结论：%d 个 APK 未能读取——无法作出任何判定，请先确认产物是否存在。"
              % total_unreadable)
        return 1
    if total_bad:
        print("结论：存在 16KB 不合规的原生库 —— 在 16KB 页设备上会加载失败。")
        print("      需替换/重新编译对应库（对 ffmpeg-kit 而言即 P0-3 的路径 A/B/C）。")
        return 1
    if total_failed:
        print("结论：有 .so 无法判定对齐情况，不能视为合规（请检查上方的解析失败原因）。")
        return 1
    print("结论：全部 .so 的 ELF LOAD 段均满足 16KB 对齐要求。")
    print("      （ZIP 条目对齐是另一层，用 `zipalign -c -P 16 -v 4 <apk>` 检查。）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

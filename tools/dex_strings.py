#!/usr/bin/env python3
"""Compare DEX string constant pools between two APKs (or dump one APK).

Parses the DEX `string_ids` table properly (header: string_ids_size@0x38,
string_ids_off@0x3C), then reads each MUTF-8 string via its uleb128 length
prefix. Regex-scanning raw DEX bytes is NOT reliable -- bytecode contains
accidental printable fragments and produced ~8000 false positives in testing.

Usage:
  python dex_strings.py dump  <apk>
  python dex_strings.py diff  <apk_a> <apk_b>
  python dex_strings.py check <apk> <needle1> [needle2 ...]
"""
import struct
import sys
import zipfile


def uleb128(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def dex_strings(blob):
    size, table_off = struct.unpack_from("<II", blob, 0x38)
    out = set()
    for i in range(size):
        (item_off,) = struct.unpack_from("<I", blob, table_off + i * 4)
        length, p = uleb128(blob, item_off)
        end = blob.index(0, p)
        raw = blob[p:end]
        try:
            out.add(raw.decode("utf-8"))
        except UnicodeDecodeError:
            out.add(raw.decode("utf-8", errors="replace"))
    return out


def apk_strings(path):
    result = set()
    with zipfile.ZipFile(path) as z:
        dexes = sorted(n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex"))
        if not dexes:
            raise SystemExit("ERROR: no classes*.dex in %s" % path)
        for name in dexes:
            result |= dex_strings(z.read(name))
    return result, dexes


def main():
    mode = sys.argv[1]
    if mode == "dump":
        strings, dexes = apk_strings(sys.argv[2])
        print("DEX: %s  strings=%d" % (",".join(dexes), len(strings)))
        for s in sorted(strings):
            if any(k in s for k in ("cast-", "fetchOffset", "version", "pg-map-id", "nudge", "autoAdvance")):
                print("  " + s[:200])
    elif mode == "diff":
        a, da = apk_strings(sys.argv[2])
        b, db = apk_strings(sys.argv[3])
        print("A: %s  dex=%s  strings=%d" % (sys.argv[2], ",".join(da), len(a)))
        print("B: %s  dex=%s  strings=%d" % (sys.argv[3], ",".join(db), len(b)))
        added, removed = sorted(b - a), sorted(a - b)
        print("ADDED (%d):" % len(added))
        for s in added:
            print("  + " + s[:200])
        print("REMOVED (%d):" % len(removed))
        for s in removed:
            print("  - " + s[:200])
    elif mode == "check":
        strings, dexes = apk_strings(sys.argv[2])
        needles = sys.argv[3:]
        missing = [n for n in needles if not any(n in s for s in strings)]
        print("DEX: %s  strings=%d  needles=%d  missing=%d" %
              (",".join(dexes), len(strings), len(needles), len(missing)))
        for n in needles:
            print("  %s %s" % ("OK    " if not any(n == m for m in missing) else "MISS  ", n))
        if missing:
            raise SystemExit(1)
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()

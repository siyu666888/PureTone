#!/usr/bin/env python3
# 合并脚本：把正确的 crossfade + END_OF_TRACK 两键并入已有的 DataStore 文件，
# 保留其余键不动（避免清掉主题/音量等偏好）。
#
# 只解析外层 map entry（field 1 = key string, field 2 = value message 原始字节），
# 不解读各 value 内部结构；我们新增/覆盖的 2 个键用正确字段号重建。
import sys

def varint(b, i):
    s = 0; sh = 0
    while True:
        x = b[i]; i += 1; s |= (x & 0x7f) << sh; sh += 7
        if not (x & 0x80): break
    return s, i

def parse(b):
    o = {}; i = 0
    while i < len(b):
        t, i = varint(b, i); fn = t >> 3; wt = t & 7
        if wt == 0:
            v, i = varint(b, i); o.setdefault(fn, []).append(v)
        elif wt == 2:
            l, i = varint(b, i); v = b[i:i + l]; i += l; o.setdefault(fn, []).append(v)
        else:
            i += 1
    return o

def value_int32(v):
    return bytes([(4 << 3) | 0]) + varint_num(v)

def value_string(s):
    b = s.encode("utf-8")
    return bytes([(6 << 3) | 2]) + varint_num(len(b)) + b

def varint_num(n):
    out = bytearray()
    while True:
        x = n & 0x7f; n >>= 7
        if n: out.append(x | 0x80)
        else: out.append(x); break
    return bytes(out)

def map_entry(key, value_bytes):
    kb = key.encode("utf-8")
    entry = bytes([(1 << 3) | 2]) + varint_num(len(kb)) + kb
    entry += bytes([(2 << 3) | 2]) + varint_num(len(value_bytes)) + value_bytes
    return entry

def emit(entries):
    out = b""
    for e in entries:
        out += bytes([(1 << 3) | 2]) + varint_num(len(e)) + e
    return out

def main():
    existing_path, out_path = sys.argv[1], sys.argv[2]
    data = open(existing_path, "rb").read()
    top = parse(data)
    merged = {}
    for v in top.get(1, []):
        e = parse(v)
        key = e.get(1, [b""])[0].decode(errors="replace")
        val = e.get(2, [b""])[0]
        merged[key] = val
    merged["playback_crossfade_ms"] = value_int32(4000)
    merged["playback_sleep_timer_mode"] = value_string("END_OF_TRACK")
    entries = [map_entry(k, vb) for k, vb in merged.items()]
    out = emit(entries)
    open(out_path, "wb").write(out)
    print("merged entries:", len(entries), "->", out_path, "len", len(out))

if __name__ == "__main__":
    main()

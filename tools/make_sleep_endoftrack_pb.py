#!/usr/bin/env python3
# 生成 DataStore Preferences 注入文件，用于在真机/模拟器上验证 v1.8.9 的
# 「开交叉淡化时『播完本曲停止』停在下一首自然边界」语义。
#
# 字段号严格对照 androidx.datastore:datastore-preferences-core:1.1.1 的
# PreferencesProto.Value（proto2 oneof）:
#   boolean=1  float=2  double=3  int32=4  int64=5  string=6
# 外层 Preferences.preferences_map = field 1 (map<string,Value>)，
# map entry 消息: field 1 = key(string), field 2 = value(Value message)。
import sys

def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)

def value_int32(v):
    # Value.int32 = field 4, wire type 0 (varint) -> tag 0x20
    return bytes([(4 << 3) | 0]) + varint(v)

def value_string(s):
    b = s.encode("utf-8")
    # Value.string = field 6, wire type 2 (len-delimited) -> tag 0x32
    return bytes([(6 << 3) | 2]) + varint(len(b)) + b

def map_entry(key, value_bytes):
    kb = key.encode("utf-8")
    entry = bytes([(1 << 3) | 2]) + varint(len(kb)) + kb
    entry += bytes([(2 << 3) | 2]) + varint(len(value_bytes)) + value_bytes
    return entry

def preferences_file(entries):
    # Preferences.preferences_map = field 1 (map<string,Value>)，按 proto map 语义
    # 编码为「重复」的 entry 消息：每条键值各是一个独立的 field 1 (tag 0x0a)
    # 长度块，绝不可把所有 entry 塞进一个 field 1 块（否则会被当成一条 entry，
    # 内部重复 key/value 互相覆盖）。
    out = b""
    for entry in entries:
        out += bytes([(1 << 3) | 2]) + varint(len(entry)) + entry
    return out

def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else \
        r"C:/Users/sidey/AppData/Local/Temp/sleep_endoftrack.pb"
    entries = [
        map_entry("playback_crossfade_ms", value_int32(4000)),
        map_entry("playback_sleep_timer_mode", value_string("END_OF_TRACK")),
    ]
    data = preferences_file(entries)
    with open(out_path, "wb") as f:
        f.write(data)
    print("wrote", out_path, "len", len(data), "bytes:", list(data))

if __name__ == "__main__":
    main()

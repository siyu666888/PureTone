#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P3-4 字符串资源化抽取脚本（一次性开发工具，不进入仓库）。

扫描 PureTone UI 层 .kt，抽取 @Composable fun 函数体内的中文界面字符串到 strings.xml：
- 生成 ASCII 资源键 pt_<模块>_<3位序号>，原文字面量写入 XML 注释便于追溯。
- 插值（$ident / ${expr}）转 %1$s ... 格式参数，kotlin 侧 stringResource(R.string.x, args...)。
- 排除：注释、字符字面量、诊断/日志调用（Log/writeCrash/Logcat/println）、非 @Composable 作用域、
       函数签名/默认值区（在首个 { 之前）、非 CJK 字面量。
- 幂等：已含 R.string / stringResource 的跳过。

用法：
  python3 tools/string_extract.py --dry            # 仅报告，不改文件
  python3 tools/string_extract.py --apply          # 修改 .kt + strings.xml，并写出映射 csv
"""
import re, os, sys, json
from collections import Counter

UI_ROOT = "D:/PureTone/app/src/main/java/com/puretone/player/ui"
STRINGS_XML = "D:/PureTone/app/src/main/res/values/strings.xml"
IMPORT = "import androidx.compose.ui.res.stringResource"

TAGS = {
    "BrowseScreen.kt": "browse",
    "SettingsScreen.kt": "settings",
    "PlayerScreen.kt": "player",
    "StatsScreen.kt": "stats",
    "EqScreen.kt": "eq",
    "SongInfoSheet.kt": "songinfo",
    "MeScreen.kt": "me",
    "PlaylistDetailScreen.kt": "playlist",
    "DiagnosticExport.kt": "diag",
    "PlayerComposables.kt": "ui",
    "GroupDetailScreen.kt": "group",
    "PermissionGate.kt": "perm",
    "PureToneApp.kt": "app",
    "Shape.kt": "theme",
}

CJK = re.compile(r'[一-鿿]')
DIAG = re.compile(r'\b(Log\.|writeCrash|Logcat|println)\b')


def skip_string(text, i):
    n = len(text); j = i + 1
    while j < n:
        c = text[j]
        if c == '\\':
            j += 2; continue
        if c == text[i]:
            return j + 1
        j += 1
    return n


def skip_comment(text, i):
    n = len(text)
    if text[i:i+2] == '//':
        nl = text.find('\n', i); return n if nl == -1 else nl
    if text[i:i+2] == '/*':
        e = text.find('*/', i+2); return n if e == -1 else e+2
    return i + 1


def scan_strings(text):
    res = []
    i = 0; n = len(text)
    while i < n:
        c = text[i]
        if c == '/' and i+1 < n and (text[i+1] == '/' or text[i+1] == '*'):
            i = skip_comment(text, i); continue
        if c == '"':
            j = i + 1; buf = []
            while j < n:
                d = text[j]
                if d == '\\':
                    buf.append(text[j:j+2]); j += 2; continue
                if d == '"':
                    break
                buf.append(d); j += 1
            raw = ''.join(buf)
            res.append((i, j+1, raw))
            i = j + 1; continue
        if c == "'":
            i = skip_string(text, i); continue
        i += 1
    return res


def find_functions(text):
    n = len(text)
    funs = []
    for m in re.finditer(r'\bfun\b', text):
        fpos = m.start()
        line_start = text.rfind('\n', 0, fpos) + 1
        prev1 = (text.rfind('\n', 0, line_start - 1) + 1) if line_start > 0 else 0
        prev2 = (text.rfind('\n', 0, prev1 - 1) + 1) if prev1 > 0 else 0
        window = text[prev2:fpos]
        is_comp = '@Composable' in window
        p = text.find('(', fpos)
        if p == -1:
            continue
        depth = 0; q = p
        while q < n:
            cc = text[q]
            if cc == '(':
                depth += 1
            elif cc == ')':
                depth -= 1
                if depth == 0:
                    break
            elif cc == '"' or cc == "'":
                q = skip_string(text, q); continue
            elif cc == '/' and q+1 < n and (text[q+1] == '/' or text[q+1] == '*'):
                q = skip_comment(text, q); continue
            q += 1
        param_close = q
        bstart = text.find('{', param_close + 1)
        if bstart == -1:
            continue
        depth = 0; k = bstart
        while k < n:
            c = text[k]
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    break
            elif c == '"' or c == "'":
                k = skip_string(text, k); continue
            elif c == '/' and k+1 < n and (text[k+1] == '/' or text[k+1] == '*'):
                k = skip_comment(text, k); continue
            k += 1
        funs.append((fpos, bstart, k, is_comp))
    return funs


def enclosing_fun(funs, pos):
    best = None
    for (s, bs, be, comp) in funs:
        if s <= pos <= be:
            if best is None or s > best[0]:
                best = (s, bs, be, comp)
    return best


def parse_placeholder(raw):
    args = []
    pos_map = {}
    xml = []
    i = 0; n = len(raw)
    while i < n:
        ch = raw[i]
        if ch == '\\':
            nxt = raw[i+1] if i+1 < n else ''
            mapping = {'n': '\\n', 't': '\\t', '"': '"', '\\': '\\', '$': '$', "'": "'"}
            xml.append(mapping.get(nxt, '\\' + nxt))
            i += 2; continue
        if ch == '$':
            if i+1 < n and raw[i+1] == '{':
                close = raw.find('}', i+2)
                if close == -1:
                    xml.append('$'); i += 1; continue
                expr = raw[i+2:close]
                key = expr.strip()
                if key not in pos_map:
                    pos_map[key] = len(args) + 1
                    args.append(expr)
                xml.append('%%%d$s' % pos_map[key])
                i = close + 1; continue
            else:
                mm = re.match(r'[A-Za-z_][A-Za-z0-9_]*', raw[i+1:])
                if mm:
                    ident = mm.group(0)
                    key = ident
                    if key not in pos_map:
                        pos_map[key] = len(args) + 1
                        args.append(ident)
                    xml.append('%%%d$s' % pos_map[key])
                    i = i + 1 + len(ident); continue
                else:
                    xml.append('$'); i += 1; continue
        if ch == '&':
            xml.append('&amp;')
        elif ch == '<':
            xml.append('&lt;')
        elif ch == '>':
            xml.append('&gt;')
        elif ch == '"':
            xml.append('&quot;')
        elif ch == "'":
            xml.append('&apos;')
        elif ch == '%':
            xml.append('%%')
        else:
            xml.append(ch)
        i += 1
    return ''.join(xml), args


def xml_escape_comment(s):
    return s.replace('--', '—')


def main():
    mode = 'dry' if '--dry' in sys.argv else 'apply'
    files = []
    for root, _, fs in os.walk(UI_ROOT):
        for f in fs:
            if f.endswith('.kt'):
                files.append(os.path.join(root, f))
    files.sort()

    all_accepted = []
    all_skipped = []
    new_entries = []
    per_file_counts = {}

    for path in files:
        base = os.path.basename(path)
        tag = TAGS.get(base)
        if tag is None:
            tag = re.sub(r'[^A-Za-z0-9]', '_', base.replace('.kt', '')).lower()
        text = open(path, encoding='utf-8').read()
        funs = find_functions(text)
        strings = scan_strings(text)
        seen = {}
        counter = 0
        replace_list = []
        accepted_here = 0; skipped_here = 0
        for (s, e, raw) in strings:
            if not CJK.search(raw):
                continue
            if 'R.string' in raw or 'stringResource' in raw:
                continue
            line = text[:s].count('\n') + 1
            fun = enclosing_fun(funs, s)
            line_start = text.rfind('\n', 0, s) + 1
            line_end = text.find('\n', e)
            if line_end == -1:
                line_end = len(text)
            line_text = text[line_start:line_end]
            if DIAG.search(line_text):
                all_skipped.append((path, line, raw, 'diagnostic'))
                skipped_here += 1
                continue
            if fun is None:
                all_skipped.append((path, line, raw, 'not_in_fun'))
                skipped_here += 1
                continue
            fs_, bs_, be_, comp = fun
            if not comp:
                all_skipped.append((path, line, raw, 'non_composable'))
                skipped_here += 1
                continue
            if not (bs_ <= s <= be_):
                all_skipped.append((path, line, raw, 'signature_or_default'))
                skipped_here += 1
                continue
            key = seen.get(raw)
            if key is None:
                counter += 1
                key = 'pt_%s_%03d' % (tag, counter)
                seen[raw] = key
                xml_value, args = parse_placeholder(raw)
                new_entries.append((key, raw, xml_value))
            xml_value, args = parse_placeholder(raw)
            kotlin = ('stringResource(R.string.%s)' % key) if not args else (
                'stringResource(R.string.%s, %s)' % (key, ', '.join(args)))
            replace_list.append((s, e, kotlin))
            all_accepted.append((path, line, key, raw, kotlin))
            accepted_here += 1
        per_file_counts[path] = (accepted_here, skipped_here)
        if mode == 'apply' and replace_list:
            replace_list.sort(reverse=True)
            out = text
            for (s, e, rep) in replace_list:
                out = out[:s] + rep + out[e:]
            if IMPORT not in out:
                pkg_m = re.search(r'^package .+$', out, re.M)
                if pkg_m:
                    ins = pkg_m.end()
                    out = out[:ins] + '\n' + IMPORT + out[ins:]
                else:
                    out = IMPORT + '\n' + out
            open(path, 'w', encoding='utf-8').write(out)

    if mode == 'apply' and new_entries:
        existing = open(STRINGS_XML, encoding='utf-8').read()
        block_lines = []
        for (key, original, xml_value) in new_entries:
            block_lines.append('    <!-- %s -->' % xml_escape_comment(original))
            block_lines.append('    <string name="%s" formatted="false">%s</string>' % (key, xml_value))
        block = '\n'.join(block_lines)
        idx = existing.rfind('</resources>')
        if idx != -1:
            new_xml = existing[:idx] + block + '\n' + existing[idx:]
        else:
            new_xml = existing + '\n' + block + '\n</resources>\n'
        open(STRINGS_XML, 'w', encoding='utf-8').write(new_xml)

    if mode == 'apply':
        with open('D:/PureTone/tools/strings_mapping.csv', 'w', encoding='utf-8') as fh:
            fh.write('key,file,line,original,kotlin\n')
            for (path, line, key, raw, kotlin) in all_accepted:
                fh.write('%s,%s,%d,%s,%s\n' % (key, os.path.relpath(path, UI_ROOT), line, raw, kotlin))

    print('MODE:', mode)
    print('=== 每文件 accepted / skipped ===')
    for path in files:
        a, sk = per_file_counts.get(path, (0,0))
        print('  %-60s acc=%d skip=%d' % (os.path.relpath(path, UI_ROOT), a, sk))
    print('总 accepted=%d, 新增 entries=%d' % (len(all_accepted), len(new_entries)))
    print('=== skipped 原因分布 ===')
    rc = Counter(r for _,_,_,r in all_skipped)
    for r, c in rc.most_common():
        print('  %s: %d' % (r, c))
    if mode == 'dry':
        print('=== accepted 样例(前100) ===')
        for (path, line, key, raw, kotlin) in all_accepted[:100]:
            print('  [%s:%d] %-22s <- %r' % (os.path.relpath(path, UI_ROOT), line, key, raw))
        print('=== skipped non_composable 样例(全，决定 Phase B) ===')
        for (path, line, raw, reason) in all_skipped:
            if reason == 'non_composable':
                print('  [%s:%d] %r' % (os.path.relpath(path, UI_ROOT), line, raw))
        print('=== skipped 其它原因样例(前40) ===')
        for (path, line, raw, reason) in all_skipped:
            if reason != 'non_composable':
                print('  [%s:%d] (%s) %r' % (os.path.relpath(path, UI_ROOT), line, reason, raw))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""扫描：同一个标签上既有 Alpine 的 x-show、又有 Bootstrap 的 !important 显示工具类。

Bootstrap 5 的 display 工具类（d-flex / d-block / d-none / d-grid …）都是
`display: xxx !important`；Alpine 的 x-show 靠写**内联** `display: none` 来隐藏元素，
内联样式压不过 !important ⇒ 元素**永远隐藏不掉**（或永远显示不出来）。
真实案例：pages/tts-export.html 的「重新检测」按钮（2026-10-16 踩到）。

用法：python3 scan_xshow_display_clash.py [根目录...]
"""
import re
import sys
from pathlib import Path

TAG = re.compile(r'<([A-Za-z][A-Za-z0-9-]*)\b((?:"[^"]*"|\'[^\']*\'|[^>"\'])*?)>', re.S)

# !important 的显示工具类（含响应式前缀）
DISPLAY_UTIL = re.compile(
    r'(?:^|\s)d-(?:none|inline|inline-block|block|grid|inline-grid|table|table-row'
    r'|table-cell|flex|inline-flex)\b'
    r'|(?:^|\s)d-(?:sm|md|lg|xl|xxl)-(?:none|inline|inline-block|block|grid|inline-grid'
    r'|table|table-row|table-cell|flex|inline-flex)\b'
)


def scan_file(path: Path):
    src = path.read_text(encoding='utf-8', errors='replace')
    hits = []
    for m in TAG.finditer(src):
        attrs = m.group(2)
        if 'x-show' not in attrs:
            continue
        cm = re.search(r'class\s*=\s*"([^"]*)"', attrs)
        if not cm:
            continue
        classes = cm.group(1)
        bad = DISPLAY_UTIL.findall(classes)
        if bad:
            line = src[:m.start()].count('\n') + 1
            tag = m.group(1)
            snippet = re.sub(r'\s+', ' ', m.group(0))[:120]
            hits.append((line, tag, classes.strip(), bad, snippet))
    return hits


def main():
    roots = sys.argv[1:] or ['src/main/resources/static/pages']
    total = 0
    for root in roots:
        for p in sorted(Path(root).rglob('*.html')):
            hits = scan_file(p)
            if not hits:
                continue
            total += len(hits)
            print(f'\n{p}')
            for line, tag, cls, bad, snippet in hits:
                print(f'  第 {line} 行  <{tag} class="{cls}">')
                print(f'     与 x-show 冲突的类: {sorted(set(bad))}')
                print(f'     {snippet}')
    print(f'\n==== 冲突数: {total} ====')
    return 0 if total == 0 else 1


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「英语单词匹配」的图标（favicon-word-match.svg）同步成 /learn 卡片墙上的内联 SVG。

为什么要有它：这枚图标有两处用途 ——
  ① 站点 favicon / 导出单页件的 data URI：`static/favicon-word-match.svg`（小程序码中央的
     A/B 双色积木，纯色 + 无 <text>，807 字节）；
  ② /learn 页「英语单词匹配」那张卡的图标：本脚本写进 learn.html 的内联 SVG。
两处画的是同一对积木，若各抄一份路径数据，改一次就要记得改两处（本仓库在这类「多副本」
上翻过车）。所以这里**只认 favicon 这一个真相**：读它、去掉米色底板、重新算个居中的
正方形 viewBox，再把结果写回页面的标记块。

    python3 scripts/gen_word_match_icon.py            # 只把 SVG 打到 stdout
    python3 scripts/gen_word_match_icon.py --write     # 写回 learn.html 的标记块内（幂等）
    python3 scripts/gen_word_match_icon.py --check     # 校验页面里那块是否与生成结果一致（漂移检测）

设计：
- **不带米色底板**。卡片容器 .sc-learn-icon 本身就是 7rem 圆角色块（--accent-soft 绿），
  再塞一块米色圆角方就是「方套方」，与隔壁「俄罗斯方块」那张卡（只有方块、没有底板）
  也不一致。底板只属于 favicon —— 浏览器标签页需要不自带底的独立图形。
- viewBox 重算：favicon 里两块积木各带 rotate(±14°)，旋转后包围盒约 49.1×40.1，
  中心落在 (30, 31.5)。直接沿用 `0 0 64 64` 会上下留一大截空白、图标显小；
  所以按包围盒中心取正方形、留 3% 余量（↔ 边长 = 长边 × 1.06）。
- 尺寸写 `width:1em;height:1em`，由 .sc-learn-icon 的 font-size(5rem) 驱动，
  与隔壁 bootstrap-icons 字形同号同盒（同 gen_block_blast_icon.py 的做法）。
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_SRC = os.path.join(HERE, '..', 'src/main/resources/static/favicon-word-match.svg')
PAGE = os.path.join(HERE, '..', 'src/main/resources/static/pages/learn.html')

START, END = '<!-- wm-ab-icon:start -->', '<!-- wm-ab-icon:end -->'
AIR = 1.06          # 正方形 viewBox 相对包围盒长边的放大系数（视效留白）
CLASS = 'wm-ab-icon'
LABEL = '英语单词匹配：A/B 双色积木'


def _rotate(x, y, deg, cx, cy):
    """绕 (cx, cy) 旋转 deg 度后的坐标。"""
    import math
    a = math.radians(deg)
    dx, dy = x - cx, y - cy
    return cx + dx * math.cos(a) - dy * math.sin(a), cy + dx * math.sin(a) + dy * math.cos(a)


def _bbox_of_block(g):
    """算一个 <g transform="rotate(a cx cy)"><rect …/>…</g> 旋转后的包围盒。"""
    m_rot = re.search(r'rotate\(\s*(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)\s*\)', g)
    m_rect = re.search(r'<rect\s+x="([\d.]+)"\s+y="([\d.]+)"\s+width="([\d.]+)"\s+height="([\d.]+)"', g)
    if not m_rect:
        raise SystemExit('favicon 里的积木不是 <rect x y width height> 形式，无法算包围盒')
    x, y, w, h = (float(v) for v in m_rect.groups())
    deg, cx, cy = (float(v) for v in m_rot.groups()) if m_rot else (0.0, x + w / 2, y + h / 2)
    pts = [_rotate(px, py, deg, cx, cy)
           for px in (x, x + w) for py in (y, y + h)]
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)


def n(v):
    s = f'{v:.2f}'.rstrip('0').rstrip('.')
    return s if s else '0'


def build():
    with open(SVG_SRC, encoding='utf-8') as f:
        src = f.read()
    blocks = re.findall(r'<g\b[^>]*>.*?</g>', src, flags=re.S)
    if len(blocks) != 2:
        raise SystemExit(f'favicon 里应当正好 2 块积木（A/B），实际 {len(blocks)} 块')

    boxes = [_bbox_of_block(b) for b in blocks]
    x0 = min(b[0] for b in boxes)
    y0 = min(b[1] for b in boxes)
    x1 = max(b[2] for b in boxes)
    y1 = max(b[3] for b in boxes)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    side = max(x1 - x0, y1 - y0) * AIR
    vb = f'{n(cx - side / 2)} {n(cy - side / 2)} {n(side)} {n(side)}'

    svg = (f'<svg class="{CLASS}" viewBox="{vb}"'
           f' style="width:1em;height:1em;vertical-align:baseline" role="img"'
           f' aria-label="{LABEL}" xmlns="http://www.w3.org/2000/svg">'
           + ''.join(blocks) + '</svg>')
    return svg, vb, boxes


def _splice(html, svg):
    if START not in html or END not in html:
        raise SystemExit(f'{os.path.normpath(PAGE)} 里找不到 {START} / {END} 标记块')
    return re.sub(re.escape(START) + r'.*?' + re.escape(END),
                  lambda _: START + '\n                        ' + svg + '\n                        '
                            + END,
                  html, count=1, flags=re.S)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group()
    g.add_argument('--write', action='store_true', help='写回 learn.html 的标记块内（幂等）')
    g.add_argument('--check', action='store_true', help='页面里那块与生成结果不一致时退出码 1')
    args = ap.parse_args()

    svg, vb, boxes = build()
    print(f'# viewBox {vb} · {len(svg.encode())} 字节 · 两块包围盒 '
          + ' / '.join('%.1f,%.1f–%.1f,%.1f' % b for b in boxes), file=sys.stderr)

    if not args.write and not args.check:
        print(svg)
        raise SystemExit(0)

    with open(PAGE, encoding='utf-8') as f:
        html = f.read()
    if args.write:
        with open(PAGE, 'w', encoding='utf-8') as f:
            f.write(_splice(html, svg))
        print(f'已写入 {os.path.normpath(PAGE)}')
    else:
        cur = re.search(re.escape(START) + r'\s*(.*?)\s*' + re.escape(END), html, flags=re.S)
        if not cur or cur.group(1) != svg:
            print('页面图标与 favicon 不同步，跑 --write 修一下', file=sys.stderr)
            raise SystemExit(1)
        print('已同步')

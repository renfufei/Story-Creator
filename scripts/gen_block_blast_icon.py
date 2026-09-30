#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 /learn 卡片上「俄罗斯方块」那张卡的内联 SVG 图标。

为什么要脚本：图标是 20 个格子 × 4 层（渐变 / 描边 / 斜角线 / 高光）拼出来的，
手写既易错也没法整体换色。改配色或排布时改这里的常量，再 `--write` 写回页面即可。

    python3 scripts/gen_block_blast_icon.py            # 只把 SVG 打到 stdout
    python3 scripts/gen_block_blast_icon.py --write     # 写回 learn.html 的标记块内（幂等）

设计：
- 配色直接取 learn-block-blast.html 的 .gem-* 调色板（亮 / 本色 / 暗三档），
  卡片图标与游戏内方块同一套色，点进去不会有色差。
- 每个方块 = 圆角矩形 + 对角渐变 + 左上亮 / 右下暗的斜角线 + 左上白色高光块，
  复刻游戏里宝石方块的立体感（对应 .gem::after / .gem::before）。
- 排布是 6 块能辨认的方块（T 形 / 竖骨牌 / 竖条 / L 形 / 骨牌），**正方形画布**，
  80px 下一眼看出「多色方块」。
- 尺寸写成 `1em`，靠外层容器的 `display-1` 字号驱动 —— 见 build() 里的注释，
  这是让卡片标题与隔壁两张卡对齐的关键。
- 体积：每种颜色只定义一次图形（<defs>），格子用 <use> 摆放，全文件约 5KB、零外部引用。
"""
import argparse
import os
import re
import sys

# 与 learn-block-blast.html 的 .gem-* 同源： (亮, 本色, 暗)
PALETTE = {
    'red':    ('#ff7070', '#e83030', '#b81818'),
    'orange': ('#ffb850', '#f07820', '#c05000'),
    'yellow': ('#ffe870', '#f0c020', '#c89010'),
    'green':  ('#70ff90', '#18d040', '#0e9830'),
    'teal':   ('#50e8e8', '#00c0c0', '#008080'),
    'blue':   ('#6fd4ff', '#1a88f0', '#0e5cc0'),
    'purple': ('#d870ff', '#9820f0', '#7010c8'),
    'pink':   ('#ff88b8', '#f02878', '#a00058'),
}

# 5 列 × 5 行（正方形画布），'.' = 空。
# R=红 T 形 / B=蓝竖骨牌 / G=绿竖条 / T=青 L 形 / P=紫骨牌 / Y=黄竖骨牌
BOARD = [
    ".RR.B",
    "RRR.B",
    "G.TT.",
    "G..TY",
    "GPP.Y",
]
LEGEND = {'R': 'red', 'B': 'blue', 'G': 'green', 'T': 'teal', 'Y': 'yellow', 'P': 'purple'}

U, GAP, RADIUS = 15.0, 2.0, 3.2            # 单格边长 / 间距 / 圆角
INSET, SW, AR = 1.5, 0.85, RADIUS - 0.9    # 斜角线内缩量 / 线宽 / 圆角处弧半径

START, END = '<!-- wm-bb-icon:start -->', '<!-- wm-bb-icon:end -->'
PAGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                    'src/main/resources/static/pages/learn.html')


def n(v):
    """坐标取一位小数，去掉多余的 .0 —— 几千个数字，累计省下的体积很可观。"""
    s = f'{v:.1f}'.rstrip('0').rstrip('.')
    return s if s else '0'


def build():
    cols, rows = len(BOARD[0]), len(BOARD)
    w, h = cols * U + (cols - 1) * GAP, rows * U + (rows - 1) * GAP
    step = U + GAP
    used = sorted({LEGEND[c] for row in BOARD for c in row if c != '.'})

    defs, uses = [], []
    for i, name in enumerate(used):
        light, base, dark = PALETTE[name]
        defs.append(f'<linearGradient id="bbg{i}" x1="0" y1="0" x2="1" y2="1">'
                    f'<stop offset=".08" stop-color="{light}"/>'
                    f'<stop offset=".38" stop-color="{base}"/>'
                    f'<stop offset="1" stop-color="{dark}"/></linearGradient>')
        hi = (f'M{n(INSET)},{n(INSET + AR)} A{n(AR)},{n(AR)} 0 0 1 {n(INSET + AR)},{n(INSET)}'
              f' L{n(U - INSET - AR)},{n(INSET)}')
        lo = (f'M{n(U - INSET)},{n(INSET + AR)} L{n(U - INSET)},{n(U - INSET - AR)}'
              f' A{n(AR)},{n(AR)} 0 0 1 {n(U - INSET - AR)},{n(U - INSET)}'
              f' L{n(INSET + AR)},{n(U - INSET)}')
        defs.append(
            f'<g id="bbk{i}">'
            f'<rect width="{n(U)}" height="{n(U)}" rx="{n(RADIUS)}" fill="url(#bbg{i})"'
            f' stroke="{dark}" stroke-opacity=".5" stroke-width=".7"/>'
            f'<path d="{hi}" fill="none" stroke="#fffdf0" stroke-opacity=".62"'
            f' stroke-width="{n(SW)}" stroke-linecap="round"/>'
            f'<path d="{lo}" fill="none" stroke="#000" stroke-opacity=".22"'
            f' stroke-width="{n(SW)}" stroke-linecap="round"/>'
            f'<ellipse cx="{n(U * 0.31)}" cy="{n(U * 0.28)}" rx="{n(U * 0.19)}" ry="{n(U * 0.14)}"'
            f' fill="#fff" fill-opacity=".5" transform="rotate(-20 {n(U * 0.31)} {n(U * 0.28)})"/>'
            f'</g>')

    for r, row in enumerate(BOARD):
        for c, ch in enumerate(row):
            if ch != '.':
                uses.append(f'<use href="#bbk{used.index(LEGEND[ch])}"'
                            f' x="{n(c * step)}" y="{n(r * step)}"/>')

    # 尺寸用 em、并让外层容器处在 display-1 的字号里 —— 这是在复刻隔壁两张卡的盒模型：
    #   <i class="bi … display-1"> 的字形是 80px，坐在 1.2 × 80 = 96px 的行盒里
    #   （基线下方是字体降部空间）。图标若写死 px 或 display:block，就只剩 80px，
    #   这张卡的标题会比隔壁高 16~19px，卡片墙看着歪。
    # 用 em 还有一个好处：Bootstrap 的 display-1 是流体字号（<1200px 时
    #   font-size = calc(1.625rem + 4.5vw)），em 会跟着一起缩，
    #   于是任何视口下都与隔壁两张卡严丝合缝。回归断言在 probe_block_blast.mjs。
    svg = (f'<svg class="wm-bb-icon" viewBox="0 0 {n(w)} {n(h)}"'
           f' style="width:1em;height:1em;vertical-align:baseline" role="img"'
           f' aria-label="俄罗斯方块：多色方块拼合" xmlns="http://www.w3.org/2000/svg">'
           f'<defs>{"".join(defs)}</defs>{"".join(uses)}</svg>')
    return svg, w, h, used


def write_into_page(svg):
    with open(PAGE, encoding='utf-8') as f:
        html = f.read()
    if START not in html or END not in html:
        raise SystemExit(f'{PAGE} 里找不到 {START} / {END} 标记块，无法写入')
    new = re.sub(re.escape(START) + r'.*?' + re.escape(END),
                 lambda _: START + '\n                        ' + svg + '\n                        ' + END,
                 html, count=1, flags=re.S)
    with open(PAGE, 'w', encoding='utf-8') as f:
        f.write(new)
    print(f'已写入 {os.path.normpath(PAGE)}')


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--write', action='store_true', help='写回 learn.html 的标记块内（幂等）')
    args = ap.parse_args()
    svg, w, h, used = build()
    print(f'# viewBox 0 0 {n(w)} {n(h)}  ·  {len(svg.encode())} 字节  ·  {len(used)} 种颜色：'
          + ', '.join(used), file=sys.stderr)
    if args.write:
        write_into_page(svg)
    else:
        print(svg)

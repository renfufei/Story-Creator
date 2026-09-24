#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「单词匹配」的关卡数据从线上服务导出成微信小程序可直接 require() 的紧凑模块。

为什么不自己重新切关：
  切关算法（WordMatchBank.splitBalanced / CetWordBank 按主题切）的**唯一真相在后端**。
  在这里重写一份 = 两份实现迟早漂移，而漂移的表现是「同一册在小程序里关卡数和网页不一样」，
  极难发现。所以本脚本只做**搬运 + 压扁**，不做任何业务计算：
    数据来自 GET /learn/word-match/data 与 /learn/word-match/cet（正是网页用的那条读路径）。

压缩手段（纯结构变换，不改语义）：
  ① 去掉每对的 key —— 它恒等于 `bookId:levelIndex:pairIndex`，运行时可以直接拼出来；
     20191 对 × 约 22 字节，这一项省掉约 440 KB。
  ② 对象 {"en":..,"zh":..} → 数组 ["en","zh"]，每对再省约 14 字节。
  ③ level 里的 index 字段与数组下标恒等，删掉。
  ④ 按学段拆文件：小程序只 require() 用得到的那个学段，启动时不必解析全部。

用法：
  python3 scripts/learn/build_miniprogram_data.py
  python3 scripts/learn/build_miniprogram_data.py --from-files /tmp/wm_data.json /tmp/wm_cet.json
"""

import argparse
import json
import os
import sys
import urllib.request

BASE = 'http://localhost:1888'
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       '..', '..', 'word-match-miniprogram', 'data')

# 学段 → 输出文件名后缀（ASCII，避免小程序里出现非 ASCII 模块名）
STAGE_SLUG = {
    '小学': 'primary',
    '初中': 'junior',
    '高中': 'senior',
    '大学': 'college',
}


def fetch(path):
    url = BASE + path
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.loads(r.read().decode('utf-8'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--from-files', nargs=2, metavar=('DATA_JSON', 'CET_JSON'),
                    help='改用本地已有的两份 JSON，而不是请求服务')
    args = ap.parse_args()

    if args.from_files:
        with open(args.from_files[0], encoding='utf-8') as f:
            d = json.load(f)
        with open(args.from_files[1], encoding='utf-8') as f:
            c = json.load(f)
    else:
        d = fetch('/learn/word-match/data')
        c = fetch('/learn/word-match/cet')

    books = list(d.get('books', [])) + list(d.get('extraBooks', []))
    levels = dict(d.get('levels', {}))
    levels.update(c.get('levels', {}))

    if not books:
        print('!! 没有取到任何册次，中止（服务是否在 1888 上跑着？）', file=sys.stderr)
        return 1

    out_dir = os.path.normpath(OUT_DIR)
    os.makedirs(out_dir, exist_ok=True)

    # ---------- books.js：册元信息（小，进主包，首屏就要用） ----------
    meta = []
    for b in books:
        meta.append({
            'id': b['id'],
            'label': b['label'],
            'stage': b['stage'],
            'levelCount': b['levelCount'],
            'wordCount': b['wordCount'],
        })

    # 按学段分组，保持词库原顺序（大学那两册在末尾，顺序与网页一致）
    stage_order = []
    stage_books = {}
    for m in meta:
        st = m['stage']
        if st not in stage_books:
            stage_books[st] = []
            stage_order.append(st)
        stage_books[st].append(m)

    books_js = (
        '// 由 scripts/learn/build_miniprogram_data.py 生成，请勿手改。\n'
        '// 源：GET /learn/word-match/data + /learn/word-match/cet\n'
        'module.exports = {\n'
        '  books: ' + json.dumps(meta, ensure_ascii=False, separators=(',', ':')) + ',\n'
        '  stages: ' + json.dumps(
            [{'name': st, 'slug': STAGE_SLUG.get(st, 'stage%d' % i), 'count': len(stage_books[st])}
             for i, st in enumerate(stage_order)], ensure_ascii=False, separators=(',', ':')) + '\n'
        '};\n'
    )
    with open(os.path.join(out_dir, 'books.js'), 'w', encoding='utf-8') as f:
        f.write(books_js)

    # ---------- levels-<slug>.js：按学段拆的关卡数据 ----------
    total_pairs = 0
    total_levels = 0
    report = []
    for st in stage_order:
        slug = STAGE_SLUG.get(st, 'stage%d' % len(report))
        payload = {}
        stage_levels = 0
        for m in stage_books[st]:
            bid = m['id']
            raw_levels = levels.get(bid) or []
            out_levels = []
            for lv in raw_levels:
                pairs = [[p['en'], p['zh']] for p in lv.get('pairs', [])]
                out_levels.append({'t': lv.get('theme', ''), 'p': pairs})
                total_pairs += len(pairs)
            payload[bid] = out_levels
            total_levels += len(out_levels)
            stage_levels += len(out_levels)

        body = ('// 由 scripts/learn/build_miniprogram_data.py 生成，请勿手改。\n'
                '// 关卡结构：[{ t: 主题, p: [[英文, 中文], ...] }]\n'
                '// 每对的 key 由运行时拼出：bookId + ":" + levelIndex + ":" + pairIndex\n'
                'module.exports = ' + json.dumps(payload, ensure_ascii=False, separators=(',', ':')) + ';\n')
        fp = os.path.join(out_dir, 'levels-%s.js' % slug)
        with open(fp, 'w', encoding='utf-8') as f:
            f.write(body)
        report.append((st, slug, len(stage_books[st]), stage_levels,
                       sum(m['wordCount'] for m in stage_books[st]), os.path.getsize(fp)))

    print('=' * 68)
    print('  学段      文件                    册   关卡    词条     体积')
    print('-' * 68)
    grand = 0
    for st, slug, nb, nl, nw, size in report:
        grand += size
        print(f'  {st:<6}  levels-{slug + ".js":<18} {nb:>3} {nl:>6} {nw:>7}  {size / 1024:>8.1f} KB')
    print('-' * 68)
    print(f'  {"合计":<6}  {"4 个学段模块":<18} {len(meta):>3} {total_levels:>6} {total_pairs:>7}  {grand / 1024:>8.1f} KB')
    print(f'  books.js 元信息: {os.path.getsize(os.path.join(out_dir, "books.js")) / 1024:.1f} KB')
    print(f'  → 全部内置主包合计 ≈ {(grand + os.path.getsize(os.path.join(out_dir, "books.js"))) / 1024:.1f} KB'
          f'（小程序主包上限 2048 KB）')
    print(f'  校验：关卡 {total_levels} 应等于 3448；词条 {total_pairs} 应等于 20191')
    return 0


if __name__ == '__main__':
    sys.exit(main())

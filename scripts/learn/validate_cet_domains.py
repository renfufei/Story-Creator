#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验四六级词库的「语义域体系」是否自洽（一条命令查全部不变量）。

设计意图：域体系散在四个地方 —— 源清单（词条）、`cet-themes.tsv`（逐条归属）、
`cet-domain-tree.tsv`（域清单 + 顺序）、`cet-words.json`（产物）+ 小程序快照。
任何一处改漏都会让「同一关的词同类」这条承诺悄悄失效，所以把全部不变量收在一个脚本里。

用法：
  python3 scripts/learn/validate_cet_domains.py                 # 查全部
  python3 scripts/learn/validate_cet_domains.py --max-rows 150  # 改粒度上限（默认 150 条/域）
  python3 scripts/learn/validate_cet_domains.py --parts /tmp/x  # 额外校验细分类的分片产出

`--parts DIR` 用于「又要把某些大域再拆细」的场景：DIR 里放
`gN-in.tsv`（`英文\\t释义\\t原域`）、`gN-out.tsv`（`英文\\t释义\\t细域`）、
`gN-tree.tsv`（`父域\\t细域\\t说明`），脚本会检查
「不增不删不改 / 不跨父域 / 细域已登记 / 与新老域名不撞 / 规模在区间内」。

⚠️ `--parts` 要在**合并进 `cet-themes.tsv` / `cet-domain-tree.tsv` 之前**跑：
合并之后新域名已经进了树，再跑会全部报「与现有域名相同」。

新的细分类就是那样做出来的（见 docs/cet-domain-taxonomy.md 的维护手册）。

退出码 0 = 全过；1 = 有问题。
"""
import argparse
import collections
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC_DIR = os.path.join(ROOT, 'src', 'test', 'resources', 'learn', 'cet-words-source')
THEMES = os.path.join(SRC_DIR, 'cet-themes.tsv')
TREE = os.path.join(SRC_DIR, 'cet-domain-tree.tsv')
CET_JSON = os.path.join(ROOT, 'src', 'main', 'resources', 'learn', 'cet-words.json')
MP_COLLEGE = os.path.join(ROOT, 'word-match-miniprogram', 'data', 'levels-college.js')
BOOKS = ('cet-4', 'cet-6')
MIN_PAIRS, MAX_PAIRS = 3, 7
MAX_SAME_DOMAIN_RUN = 5
FALLBACK_PARENT = '特殊类别'
MAX_DESC = 30

problems = []
notes = []


def bad(msg):
    problems.append(msg)


def read_tsv(path, ncol):
    rows = []
    with open(path, encoding='utf-8') as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip('\n').rstrip('\r')
            if not line.strip():
                continue
            parts = line.split('\t')
            if len(parts) != ncol:
                bad('%s 第 %d 行不是 %d 列：%r' % (os.path.basename(path), lineno, ncol, line))
                continue
            rows.append(tuple(p.strip() for p in parts))
    return rows


def read_source(book_id):
    rows = []
    with open(os.path.join(SRC_DIR, book_id + '.txt'), encoding='utf-8') as fh:
        for line in fh:
            line = line.rstrip('\n')
            if not line.strip():
                continue
            en, zh = line.split('\t', 1)
            rows.append((en.strip(), zh.strip()))
    return rows


def base_theme(name):
    return re.sub(r'\s*·\s*\d+$', '', name)


# ---------------------------------------------------------------- 1. 细域树
def check_tree():
    tree = read_tsv(TREE, 3)
    if not tree:
        bad('细域树为空：%s' % TREE)
        return None, None
    parent, desc, order = {}, {}, []
    for par, sub, note in tree:
        if sub in parent:
            bad('细域树里细域重复定义：%s' % sub)
        if len(note) > MAX_DESC:
            bad('细域「%s」的说明 %d 字（上限 %d）' % (sub, len(note), MAX_DESC))
        parent[sub] = par
        desc[sub] = note
        order.append(sub)
    if FALLBACK_PARENT not in set(parent.values()):
        bad('细域树里找不到兜底父域 %r' % FALLBACK_PARENT)
    # 一个父域要么自己就是细域（说明它没被细分、保持原样），要么它的名字不出现在细域里；
    # 两者都不是就说明树写歪了（比如把粗域拆了却还留着自己那行）。
    subs = set(order)
    for par in sorted(set(parent.values())):
        children = [s for s in order if parent[s] == par]
        if par in subs and children != [par]:
            bad('父域「%s」既是被细分的粗域、又自己当细域，语义含混' % par)
    print('  细域树：%d 个细域 / %d 个父域' % (len(order), len(set(parent.values()))))
    return parent, order


# ------------------------------------------------- 2. 逐条归属 vs 源清单
def check_themes(parent):
    themes = read_tsv(THEMES, 3)
    theme_of = {}
    for en, zh, dom in themes:
        if (en, zh) in theme_of:
            bad('语义域映射里 (英文,释义) 重复：%s / %s' % (en, zh))
        if dom not in parent:
            bad('语义域映射第 %d 行用了树里没登记的域：%s（词 %s）' % (len(theme_of) + 1, dom, en))
        theme_of[(en, zh)] = dom

    src = {}
    for bid in BOOKS:
        src[bid] = read_source(bid)
    merged = collections.Counter()
    for bid in BOOKS:
        merged.update(src[bid])
    if set(merged) != set(theme_of):
        missing = list(set(merged) - set(theme_of))[:5]
        extra = list(set(theme_of) - set(merged))[:5]
        bad('逐条归属与源清单不是同一组词条：缺 %s / 多 %s' % (missing, extra))

    # 每册每个域的应有词条数（>= MIN_PAIRS 才会成关）
    per_book = {}
    for bid in BOOKS:
        cnt = collections.Counter()
        for pair in src[bid]:
            cnt[theme_of[pair]] += 1
        per_book[bid] = cnt
    print('  逐条归属：%d 行 / 源清单 %d 行（四级 %d + 六级 %d）'
          % (len(themes), len(merged), len(src['cet-4']), len(src['cet-6'])))
    return theme_of, per_book


# ---------------------------------------------------- 3. 粒度（域规模上限）
def check_granularity(parent, max_rows):
    cnt = collections.Counter()
    for _, _, dom in read_tsv(THEMES, 3):
        cnt[dom] += 1
    over = [(n, k) for n, k in cnt.items() if k > max_rows]
    for n, k in sorted(over, key=lambda kv: -kv[1]):
        bad('细域「%s」有 %d 条词，超过粒度上限 %d —— 该域需要再细分' % (n, k, max_rows))
    small = [(n, k) for n, k in cnt.items() if k < MIN_PAIRS]
    for n, k in small:
        notes.append('碎域「%s」只有 %d 条（< %d），构建时会并进同父域的兄弟细域' % (n, k, MIN_PAIRS))
    print('  粒度：%d 个细域，规模 %d~%d 条（上限 %d），%d 个碎域'
          % (len(cnt), min(cnt.values()), max(cnt.values()), max_rows, len(small)))
    return cnt


# ------------------------------------------- 4. 产物 cet-words.json
def check_bank(parent, theme_of, per_book, max_rows):
    if not os.path.exists(CET_JSON):
        notes.append('没有 %s，跳过产物校验' % CET_JSON)
        return None
    data = json.load(open(CET_JSON, encoding='utf-8'))
    books = {b['id']: b for b in data['books']}
    if set(books) != set(BOOKS):
        bad('词库册次不是 %s：%s' % (list(BOOKS), list(books)))
    for bid in BOOKS:
        if bid not in books:
            continue
        themes = books[bid]['themes']
        pairs = [tuple(w) for t in themes for w in t['words']]
        if collections.Counter(pairs) != collections.Counter(read_source(bid)):
            bad('%s 词条多重集与源清单不一致（有增删改）' % bid)
        # 每关 3~7 对 + 关内英文不重复
        for i, t in enumerate(themes, 1):
            n = len(t['words'])
            if not MIN_PAIRS <= n <= MAX_PAIRS:
                bad('%s 第 %d 关 %d 对，越界 %d~%d' % (bid, i, n, MIN_PAIRS, MAX_PAIRS))
            ens = [w[0] for w in t['words']]
            if len(set(ens)) != len(ens):
                bad('%s 第 %d 关关内英文重复：%s' % (bid, i, ens))
        # 域清单 + 档粗粒度
        words_of = collections.Counter()
        seen = set()
        for t in themes:
            b = base_theme(t['name'])
            if b not in parent:
                bad('%s 第 %d 关用了树里没登记的域：%s' % (bid, themes.index(t) + 1, t['name']))
            words_of[b] += len(t['words'])
            seen.add(b)
        for n, k in words_of.items():
            if k > max_rows:
                bad('%s 的细域「%s」有 %d 条词（> %d），粒度不足' % (bid, n, k, max_rows))
        # 用到的域应恰好是「本册词条数 >= MIN_PAIRS」的那些
        expected = {d for d, c in per_book[bid].items() if c >= MIN_PAIRS}
        if seen != expected:
            bad('%s 用到的域与「本册词条数 >= %d」不符：多 %s / 少 %s'
                % (bid, MIN_PAIRS, sorted(seen - expected)[:5], sorted(expected - seen)[:5]))
        # 同类连排
        run, prev, mx = 0, None, 0
        for t in themes:
            b = base_theme(t['name'])
            run = run + 1 if b == prev else 1
            mx = max(mx, run)
            prev = b
        if mx > MAX_SAME_DOMAIN_RUN:
            bad('%s 同类最长连排 %d 关，超过 %d' % (bid, mx, MAX_SAME_DOMAIN_RUN))
        # 兜底域占比
        fb = sum(k for d, k in words_of.items() if parent.get(d) == FALLBACK_PARENT)
        share = fb * 100.0 / len(pairs)
        if share >= 3.0:
            bad('%s 兜底域「%s」系占比 %.2f%%，超过 3%%' % (bid, FALLBACK_PARENT, share))
        print('  %s：%d 关 / %d 词 / 用到 %d 个细域 / 最长同类连排 %d / 兜底 %.2f%%'
              % (bid, len(themes), len(pairs), len(seen), mx, share))
    return books


# ------------------------------------------- 5. 小程序快照
def check_miniprogram(books):
    if not os.path.exists(MP_COLLEGE) or books is None:
        notes.append('没有小程序快照或词库，跳过端上一致性校验')
        return
    # 快照是 `module.exports = {...};` + 头部注释；不引 node，直接把 JSON 抠出来解析
    text = open(MP_COLLEGE, encoding='utf-8').read()
    text = re.sub(r'^\s*//.*$', '', text, flags=re.M)
    payload = text[text.index('module.exports'):].split('=', 1)[1].strip().rstrip(';').strip()
    try:
        snapshot = json.loads(payload)
    except Exception as exc:                                  # pragma: no cover
        notes.append('解析不了小程序快照（%s），跳过端上一致性校验' % exc)
        return
    for bid in BOOKS:
        if bid not in snapshot or bid not in books:
            continue
        snap = snapshot[bid]
        themes = books[bid]['themes']
        if [l['t'] for l in snap] != [t['name'] for t in themes]:
            bad('%s 小程序快照的关卡主题/顺序与服务 JSON 不一致' % bid)
        a = sorted(x[0] + '\u0000' + x[1] for l in snap for x in l['p'])
        b = sorted(w[0] + '\u0000' + w[1] for t in themes for w in t['words'])
        if a != b:
            bad('%s 小程序快照的词条与服务 JSON 不一致' % bid)
    print('  小程序快照：%d 册逐关比对完成' % len(snapshot))


# ------------------------------------------- 6. 细分类分片（--parts）
def check_parts(parts_dir, parent):
    groups = sorted(glob.glob(os.path.join(parts_dir, 'g*-in.tsv')))
    if not groups:
        bad('--parts 目录里没有 gN-in.tsv：%s' % parts_dir)
        return
    existing = set(parent)
    used_names = {}
    for inp in groups:
        gid = os.path.basename(inp).split('-in.tsv')[0]
        out = os.path.join(parts_dir, gid + '-out.tsv')
        tree = os.path.join(parts_dir, gid + '-tree.tsv')
        if not (os.path.exists(out) and os.path.exists(tree)):
            bad('%s 缺 out/tree 产出' % gid)
            continue
        ins = read_tsv(inp, 3)
        outs = read_tsv(out, 3)
        trees = read_tsv(tree, 3)
        if collections.Counter((e, z) for e, z, _ in ins) != collections.Counter((e, z) for e, z, _ in outs):
            bad('%s 词条多重集不一致（不增不删不改 这条没守住）' % gid)
        new_parent = {}
        for par, sub, note in trees:
            if sub in existing:
                bad('%s 新细域「%s」与现有域清单里的域名相同（分片校验要在**合并前**跑；'
                    '如果这批分片已经合并进树里了，这条报错就是正常的、可以忽略）' % (gid, sub))
            if sub in used_names and used_names[sub] != par:
                bad('%s 新细域「%s」跨组重名（%s / %s）' % (gid, sub, used_names[sub], par))
            if sub in new_parent:
                bad('%s 新细域重复定义：%s' % (gid, sub))
            new_parent[sub] = par
            used_names[sub] = par
            if len(note) > MAX_DESC:
                bad('%s 细域「%s」说明超 %d 字' % (gid, sub, MAX_DESC))
        in_parent = {(e, z): p for e, z, p in ins}
        sizes = collections.Counter()
        seen_en = collections.defaultdict(set)
        for e, z, sub in outs:
            if sub not in new_parent:
                bad('%s 用了未登记的细域：%s（词 %s）' % (gid, sub, e))
                continue
            if new_parent[sub] != in_parent.get((e, z)):
                bad('%s 词 %s 跨父域：%s → %s' % (gid, e, in_parent.get((e, z)), sub))
            seen_en[(new_parent[sub], e)].add(sub)
            sizes[sub] += 1
        for (par, e), subs in seen_en.items():
            if len(subs) > 1:
                bad('%s 同一父域 %s 下的 %s 被拆到多个细域' % (gid, par, e))
        print('  %s：%d 行 → %d 个细域，规模 %d~%d'
              % (gid, len(outs), len(sizes), min(sizes.values()), max(sizes.values())))
        if len(sizes) != len(new_parent):
            bad('%s tree 里声明了 %d 个细域，out 只用到 %d 个'
                % (gid, len(new_parent), len(sizes)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--max-rows', type=int, default=150,
                    help='单个细域的条数上限（默认 150，即约 25 关）')
    ap.add_argument('--parts', default=None, help='细分类分片目录（可选）')
    args = ap.parse_args()

    print('=' * 68)
    print(' 四六级词库 · 语义域体系校验')
    print('=' * 68)
    parent, _ = check_tree()
    if parent:
        theme_of, per_book = check_themes(parent)
        check_granularity(parent, args.max_rows)
        books = check_bank(parent, theme_of, per_book, args.max_rows)
        check_miniprogram(books)
    if args.parts:
        print('  --- 细分类分片 ---')
        check_parts(args.parts, parent or {})

    print('-' * 68)
    for n in notes:
        print('  提示：%s' % n)
    if problems:
        print('\n❌ %d 项问题：' % len(problems))
        for p in problems:
            print('  -', p)
        return 1
    print('\n✅ 全部通过：域体系自洽、粒度达标、三副本一致')
    return 0


if __name__ == '__main__':
    sys.exit(main())

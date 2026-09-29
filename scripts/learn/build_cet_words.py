#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建「大学」独立词源（不与人教版做任何比较、不做去重）。

产出两份东西：
  1. 源清单 src/test/resources/learn/cet-words-source/cet-4.txt / cet-6.txt
     —— 逐行照抄上游词表（`英文\\t词性+释义`），是**唯一真相**，单测按它断言零丢失。
  2. 词库 src/main/resources/learn/cet-words.json
     —— 结构与人教版词库一致（册 → 主题 → 单词），供前端小游戏使用。

设计要点
--------
* **独立**：四级 / 六级各成一册（id=cet-4 / cet-6，stage=大学），不掺进 PEP 的 24 册里，
  也不拿 PEP 已有的词去过滤——上游给什么就收什么。
* **不去重**：上游「乱序」词表其实是三段词表拼接，同一个词可能出现 2~3 次且释义略有差异
  （例：access 出现 3 次）。这些重复**全部保留**：它们会被分散到不同关卡，形成自然的复习节奏。
* **按语义域切主题**：词表本身是扁平的，没有主题。由 `cet-themes.tsv`（人工/模型逐条判定的
  `英文\\t释义\\t语义域` 映射）给出每个词条的语义域，满足游戏「同一关的词同类型」的设计。
  映射里查不到的条目回退到「特殊类别」。
* **域分两级，粒度以「同一关的词彼此相干」为准**：`cet-domain-tree.tsv`（`父域\\t细域\\t说明`）
  定义全部细域与展示顺序。68 个粗域里有 35 个装得太多（最大的「性质与特征」818 条，
  从 able 到 awkward 什么形容词都有，6 条随手抽出来毫无关系），已按语义细分成
  **98 个小分类**，加 33 个没超阈值、保持原样的域，共 131 个细域，规模 30~149 条。
  细分只换标签、**不动任何词条与释义**，也不跨父域。
* **域内按源顺序切关**：同一语义域内顺着源顺序每 6 个切一关。上游是「三段乱序词表拼接」，
  同一个词的三次出现天然相隔上千行，重复就自动落在相隔很远的关卡 —— 相当于内置了复习节奏。
  若改用「把重复尽量摊开」的贪心分配，重复反而会挤在相邻几关里，体验更差。
* **关内英文不重复**：顺序切完后逐关扫一遍，撞名的记录顺延到下一关（carry），
  最后再在不破坏「关内英文唯一」的前提下把各关大小拉回 3~7 对（正常数据几乎不触发）。
* **不足 3 对的碎域先并「兄弟」**：同一个父域下的细域语义最近，所以碎域先并到**该父域下最大
  的兄弟细域**里；父域只有一个细域（没细分过的原域）时才落到兜底细域（`特殊类别` 下最大的那个）。
  绝不丢词。当前只有「短语与搭配」（2 条）会触发。
* **域间交错（册内关卡顺序）**：语义域内切好的关卡**不按域整段连着排**，否则一册开头连着做
  51 关「人物与身份」、接着 84 关「性质与特征」，做久了很疲劳。改为把每域切成**最多 5 关一块**
  （`MAX_SAME_DOMAIN_RUN`），再 `interleave_blocks` 交错：**首轮**每个域先各出一块（按
  DOMAIN_ORDER，具体 → 抽象 → 兜底），**其余块**按各域剩余块数做**平滑加权轮转**（SWRR）公平铺开。
  同一域最长连排 5 关；块数多的域出现更频繁；各域几乎同时收尾，不会把大域剩到最后堆成一长串。
  **只改关卡顺序，不动任何词条与释义**；域内顺序、每域关头序号（`域 · N`）全部保持。
* **域间交错（册内关卡顺序）**：语义域内切好的关卡**不按域整段连着排**，否则一册开头连着做
  51 关「人物与身份」、接着 84 关「性质与特征」，做久了很疲劳。改为把每域切成**最多 5 关一块**
  （`MAX_SAME_DOMAIN_RUN`），再 `interleave_blocks` 交错：**首轮**每个域先各出一块（按
  DOMAIN_ORDER，具体 → 抽象 → 兜底），**其余块**按各域剩余块数做**平滑加权轮转**（SWRR）公平铺开。
  同一域最长连排 5 关；块数多的域出现更频繁；各域几乎同时收尾，不会把大域剩到最后堆成一长串。
  **只改关卡顺序，不动任何词条与释义**；域内顺序、每域关头序号（`域 · N`）全部保持。

用法：python3 scripts/learn/build_cet_words.py [--raw-dir DIR]
  默认从上游仓库直接下载；给了 --raw-dir 就从本地读 `cet4.txt` / `cet6.txt`。
"""

import argparse
import collections
import json
import os
import re
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC_DIR = os.path.join(ROOT, 'src', 'test', 'resources', 'learn', 'cet-words-source')
OUT_JSON = os.path.join(ROOT, 'src', 'main', 'resources', 'learn', 'cet-words.json')
# 语义域映射（唯一真相）：`英文\t释义\t语义域`
THEME_SOURCE = os.path.join(SRC_DIR, 'cet-themes.tsv')

REPO = 'https://raw.githubusercontent.com/KyleBing/english-vocabulary/master/'
UPSTREAM = {
    'cet-4': REPO + urllib.parse.quote('3 四级-乱序.txt'),
    'cet-6': REPO + urllib.parse.quote('4 六级-乱序.txt'),
}

# 每关配对数：与人教版保持一致（WordMatchBank.MIN_PAIRS / MAX_PAIRS / TARGET_PAIRS）
MIN_PAIRS, MAX_PAIRS, TARGET_PAIRS = 3, 7, 6

BOOKS = {
    'cet-4': {'label': '四级', 'grade': 13, 'semester': '四级'},
    'cet-6': {'label': '六级', 'grade': 14, 'semester': '六级'},
}
STAGE = '大学'

# 细域树（第二个唯一真相）：`父域\t细域\t说明`。行序即主题在册内的排列顺序
# （父域按「具体生活 → 自然与物质 → 社会 → 心智与抽象 → 功能与特殊」，父域内按人工定的顺序）。
# 没细分过的原域在树里也有一行（父域 == 细域），这样「域清单」只有一个来源。
DOMAIN_TREE_SOURCE = os.path.join(SRC_DIR, 'cet-domain-tree.tsv')

# 兜底**父域**：映射里查不到的词落这里；不足 MIN_PAIRS 的碎域在父域内没有兄弟可并时，也落这里。
FALLBACK_PARENT = '特殊类别'


def read_domain_tree(path=DOMAIN_TREE_SOURCE):
    """读细域树 → (DOMAIN_ORDER, DOMAIN_PARENT, DOMAIN_NOTE)。

    DOMAIN_ORDER 是细域的展示顺序，DOMAIN_PARENT 把细域映射回它的父域
    （碎域合并要靠它找「兄弟」，也是分类体系的文档线索）。
    """
    order, parent, note = [], {}, {}
    with open(path, encoding='utf-8') as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip('\n').rstrip('\r')
            if not line.strip():
                continue
            parts = line.split('\t')
            if len(parts) != 3:
                raise SystemExit('细域树第 %d 行不是三段式：%r' % (lineno, line))
            par, sub, desc = (p.strip() for p in parts)
            if not par or not sub:
                raise SystemExit('细域树第 %d 行有空字段：%r' % (lineno, line))
            if sub in parent:
                raise SystemExit('细域树第 %d 行细域重复定义：%r' % (lineno, sub))
            order.append(sub)
            parent[sub] = par
            note[sub] = desc
    if not order:
        raise SystemExit('细域树为空：%s' % path)
    if FALLBACK_PARENT not in {parent[n] for n in order}:
        raise SystemExit('细域树里找不到兜底父域 %r' % FALLBACK_PARENT)
    return order, parent, note


DOMAIN_ORDER, DOMAIN_PARENT, DOMAIN_NOTE = read_domain_tree()
# 兜底**细域**：兜底父域下的**第一个**细域，碎域在本父域内找不到兄弟时的最后一站。
# 取「第一个」而不是「数据上最大的那个」，是为了让这条规则只依赖树、不依赖数据 —— 好解释、可复现。
FALLBACK_THEME = next(n for n in DOMAIN_ORDER if DOMAIN_PARENT[n] == FALLBACK_PARENT)

# 同一语义域在册内**最多连排几关**。超过就换一类，避免连续做同类词产生的疲劳。
MAX_SAME_DOMAIN_RUN = 5


def read_themes(path=THEME_SOURCE):
    """读语义域映射：`英文\\t释义\\t语义域` → {(英文, 释义): 语义域}。"""
    allowed = set(DOMAIN_ORDER)
    mapping = {}
    with open(path, encoding='utf-8') as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip('\n').rstrip('\r')
            if not line.strip():
                continue
            parts = line.split('\t')
            if len(parts) != 3:
                raise SystemExit('语义域映射第 %d 行不是三段式：%r' % (lineno, line))
            en, zh, domain = (p.strip() for p in parts)
            if domain not in allowed:
                raise SystemExit('语义域映射第 %d 行出现未登记的域：%r' % (lineno, domain))
            mapping[(en, zh)] = domain
    if not mapping:
        raise SystemExit('语义域映射为空：%s' % path)
    return mapping


def read_source(path):
    """读源清单：`英文\\t中文`，空行跳过，顺序原样保留。"""
    rows = []
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            line = line.rstrip('\n').rstrip('\r')
            if not line.strip():
                continue
            if '\t' not in line:
                raise SystemExit('源清单第 %d 行没有制表符：%r' % (len(rows) + 1, line))
            en, zh = line.split('\t', 1)
            en, zh = en.strip(), zh.strip()
            if not en or not zh:
                raise SystemExit('源清单第 %d 行有空字段：%r' % (len(rows) + 1, line))
            rows.append((en, zh))
    if not rows:
        raise SystemExit('源清单为空：%s' % path)
    return rows


def level_sizes(n):
    """关数与各关大小：既要没有一关超过 MAX_PAIRS，又要尽量贴近 TARGET_PAIRS。

    与 WordMatchBank.splitBalanced 同一套算法（两处必须一致，否则前后端对不上）。
    """
    if n <= MAX_PAIRS:
        return [n]
    groups = min(max(-(-n // MAX_PAIRS), int(round(n / float(TARGET_PAIRS)))), n)
    base, rem = divmod(n, groups)
    return [base + (1 if i < rem else 0) for i in range(groups)]


def split_bucket(rows):
    """把一个域桶按源顺序切成若干关，保证每关 3~7 对、且关内英文不重复。

    入参 rows 为 [(en, zh), ...]；返回 [[(en, zh), ...], ...]（关内仍按源顺序）。
    """
    n = len(rows)
    if n < MIN_PAIRS:
        raise SystemExit('桶内只有 %d 个词，不足 %d 对' % (n, MIN_PAIRS))

    items = [(i, en, zh) for i, (en, zh) in enumerate(rows)]
    chunks, carry, cursor = [], [], 0
    for size in level_sizes(n):
        take = items[cursor:cursor + size]
        cursor += size
        keep, defer, seen = [], [], set()
        for item in carry + take:                 # 上一关挤出来的先落位
            if item[1] in seen:
                defer.append(item)
            else:
                keep.append(item)
                seen.add(item[1])
        chunks.append(keep)
        carry = defer
    if carry:                                      # 兜底：末尾还有顺延下来的，单独成关
        chunks.append(carry)

    # 把各关大小拉回 3~7 对：从最满的关搬一个词到最空的关（不能撞名）
    for _ in range(1000):
        sizes = [len(c) for c in chunks]
        big = max(range(len(chunks)), key=lambda i: sizes[i])
        small = min(range(len(chunks)), key=lambda i: sizes[i])
        if sizes[big] <= MAX_PAIRS and sizes[small] >= MIN_PAIRS:
            break
        moved = False
        for k, item in enumerate(chunks[big]):
            if all(item[1] != e[1] for e in chunks[small]):
                chunks[small].append(chunks[big].pop(k))
                moved = True
                break
        if not moved:
            break

    for i, chunk in enumerate(chunks):
        if not MIN_PAIRS <= len(chunk) <= MAX_PAIRS:
            raise SystemExit('切关结果越界：第 %d 关 %d 对（%d 个词）' % (i, len(chunk), n))
        ens = [c[1] for c in chunk]
        if len(set(ens)) != len(ens):
            raise SystemExit('切关结果里同一关出现重复英文：%s' % ens)

    return [[(en, zh) for _, en, zh in sorted(chunk, key=lambda c: c[0])] for chunk in chunks]


def swrr_block_order(block_counts, order_index, last=None):
    """平滑加权轮转（nginx 的 smooth weighted round-robin）排「块」的顺序。

    权重 = 该域**剩余的块数**（≈ 剩余词条数 / cap）。产出序列满足：每个域恰好出现
    `block_counts[域]` 次，且出现位置尽量均匀 —— 块数多的域更频繁地插进来，各域几乎同时收尾
    （不会把大域的剩余关卡全堆到册尾）。权重相同时按 `order_index`（即 DOMAIN_ORDER）兜底，
    保证结果可复现。

    `last` 是上一块的域：同一个域的两块**不允许相邻**，否则连排会变成 2×cap 关（守卫一）。
    """
    current = {name: 0 for name in block_counts}
    total = sum(block_counts.values())
    seq = []
    for _ in range(total):
        for name in block_counts:
            current[name] += block_counts[name]
        ranked = sorted(block_counts, key=lambda n: (-current[n], order_index[n]))
        pick = ranked[0]
        if len(ranked) > 1 and pick == last:
            pick = ranked[1]
        current[pick] -= total
        seq.append(pick)
        last = pick
    return seq


def interleave_blocks(groups, cap=MAX_SAME_DOMAIN_RUN):
    """把一个域的关卡打散到全册：每域最多连排 cap 关，块间交错。

    入参 groups：`[(域基名, [(关头序号, 关卡), ...]), ...]`，顺序即 DOMAIN_ORDER，域内保持源顺序。
    返回：`[(域基名, 关头序号, 关卡), ...]`，即册内最终关卡顺序。

    **为什么不能整段连着排**：一册开头会是 51 连排「人物与身份」、接着 84 连排「性质与特征」，
    做久了疲劳。

    **为什么不是简单的「每域取 cap 关轮流」**：域大小差别很大（84 关 vs 2 关），轮流会让小域
    早早做完、大域的剩余关卡全堆在册尾（实测尾部连排 34~38 关，反而更糟）。

    所以分两步：
      ① **首轮**：按 DOMAIN_ORDER（具体 → 抽象 → 兜底）让每个域先各出一块，玩家早点见到每一类；
      ② **其余**：按各域**剩余块数**加权做 SWRR 公平铺开，大域出现更频繁，各域几乎同时收尾。
    两步都守着「最长连排 ≤ cap」，最后还有硬断言兜底。
    """
    blocks = collections.OrderedDict()
    for name, entries in groups:
        if entries:
            blocks[name] = [entries[i:i + cap] for i in range(0, len(entries), cap)]
    if not blocks:
        return []

    order_index = {name: i for i, (name, _) in enumerate(groups)}
    cursor = {name: 0 for name in blocks}

    # ① 首轮：每域各一块（域序 = DOMAIN_ORDER）
    order = list(blocks)
    # ② 其余块：只在还有剩余块的域之间加权轮转
    rest = collections.OrderedDict((n, len(c) - 1) for n, c in blocks.items())
    rest = collections.OrderedDict((n, c) for n, c in rest.items() if c > 0)
    if rest:
        order += swrr_block_order(rest, order_index, last=order[-1] if order else None)

    result, prev, run = [], None, 0
    for name in order:
        for ordinal, level in blocks[name][cursor[name]]:
            result.append((name, ordinal, level))
            run = run + 1 if name == prev else 1
            if run > cap:
                # 超限要分清「可避免」和「躲不开」：
                # 若此刻还有别的域剩着关卡，说明是块序排坏了 —— 宁可构建失败也不出成品；
                # 若整册只剩这一个域，那就是数据本身太偏（一个域比其余全部加起来还多），只能连排。
                others = [n for n in blocks if n != name and cursor[n] < len(blocks[n])]
                if others:
                    raise SystemExit('交错失败：%s 连着排了 %d 关（上限 %d），且仍有 %d 个域有待排关卡'
                                     % (name, run, cap, len(others)))
            prev = name
        cursor[name] += 1
    return result


def max_same_domain_run(themes):
    """册内同一语义域的最长连排关数（构建后自检用）。"""
    best, run, prev = 0, 0, None
    for theme in themes:
        name = theme['name'].split(' · ')[0]
        run = run + 1 if name == prev else 1
        prev = name
        best = max(best, run)
    return best


def build_book(book_id, rows, theme_map):
    buckets = collections.OrderedDict((name, []) for name in DOMAIN_ORDER)
    for en, zh in rows:
        buckets[theme_map.get((en, zh), FALLBACK_THEME)].append((en, zh))

    # 碎域要并进哪个桶，必须在**开始消费桶之前**算完（否则先被消费掉的桶拿不到后来并进来的词）。
    # 并「兄弟」优先，落兜底其次 —— 同父域的细域语义最近，语义损失最小。
    siblings = collections.defaultdict(list)
    for name in DOMAIN_ORDER:
        siblings[DOMAIN_PARENT[name]].append(name)
    incoming = collections.defaultdict(list)
    merged = []
    for name in DOMAIN_ORDER:
        if not buckets[name] or len(buckets[name]) >= MIN_PAIRS:
            continue
        peers = [n for n in siblings[DOMAIN_PARENT[name]] if n != name and buckets[n]]
        target = max(peers, key=lambda n: len(buckets[n])) if peers else FALLBACK_THEME
        incoming[target].extend(buckets[name])
        merged.append((name, len(buckets[name]), target))
        buckets[name] = []
    for name, n, target in merged:
        print('    · 碎域「%s」只有 %d 条（不足 %d），并入「%s」' % (name, n, MIN_PAIRS, target))

    # 先按域切好关（域内保持源顺序），再整体交错 —— 两步分开，互不干扰
    groups = []
    for name in DOMAIN_ORDER:
        items = buckets[name] + incoming[name]
        if not items:
            continue
        levels = split_bucket(items)
        # 同一域多关时加序号，便于玩家知道「看到第几关」；序号是**域内**序号，交错后依然连续
        groups.append((name, [(i, level) for i, level in enumerate(levels, 1)]))

    themes = []
    levels_per_domain = {name: len(entries) for name, entries in groups}
    for name, ordinal, level in interleave_blocks(groups):
        title = name if levels_per_domain[name] == 1 else '%s · %d' % (name, ordinal)
        themes.append({'name': title, 'words': [[e, z] for e, z in level]})

    meta = BOOKS[book_id]
    words = sum(len(t['words']) for t in themes)
    if words != len(rows):
        raise SystemExit('%s 词数对不上：源清单 %d / 产出 %d' % (book_id, len(rows), words))
    return {
        'id': book_id,
        'label': meta['label'],
        'grade': meta['grade'],
        'semester': meta['semester'],
        'stage': STAGE,
        'themes': themes,
    }, words


def fetch(url, dest):
    print('  下载 %s' % url)
    req = urllib.request.Request(url, headers={'User-Agent': 'build-cet-words'})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, 'wb') as out:
        out.write(resp.read())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--raw-dir', default=None, help='本地原始词表目录（含 cet4.txt / cet6.txt）')
    args = ap.parse_args()

    os.makedirs(SRC_DIR, exist_ok=True)
    raw_dir = args.raw_dir or os.path.join('/tmp', 'cet-raw')
    if args.raw_dir is None:
        os.makedirs(raw_dir, exist_ok=True)
    if not os.path.exists(THEME_SOURCE):
        raise SystemExit('缺少语义域映射文件：%s' % THEME_SOURCE)
    theme_map = read_themes()

    books = []
    for book_id, url in UPSTREAM.items():
        dest = os.path.join(raw_dir, book_id.replace('-', '') + '.txt')
        if not os.path.exists(dest):
            fetch(url, dest)
        # 源清单 = 唯一真相：把上游原文照抄进测试资源（不去重、不排序）
        text = open(dest, encoding='utf-8').read()
        if not text.endswith('\n'):
            text += '\n'
        with open(os.path.join(SRC_DIR, book_id + '.txt'), 'w', encoding='utf-8') as fh:
            fh.write(text)

        rows = read_source(os.path.join(SRC_DIR, book_id + '.txt'))
        book, words = build_book(book_id, rows, theme_map)
        levels = len(book['themes'])
        pairs = sum(len(t['words']) for t in book['themes'])
        used = {t['name'].split(' · ')[0] for t in book['themes']}
        run = max_same_domain_run(book['themes'])
        if run > MAX_SAME_DOMAIN_RUN:
            # 能走到这里只可能是「一个域比其余全部加起来还多」的数据偏斜（可避免的超限
            # 已在 interleave_blocks 里直接失败了），告警但不阻断构建
            print('  ⚠️ %s 同类最长连排 %d 关（超过 %d）：该域关卡数远超其余域，躲不开'
                  % (book_id, run, MAX_SAME_DOMAIN_RUN))
        print('  %s：源 %d 词 → %d 关 / 平均 %.2f 对，命中语义域 %d / %d，同类最长连排 %d 关'
              % (book_id, words, levels, pairs / float(levels), len(used), len(DOMAIN_ORDER), run))
        books.append(book)

    with open(OUT_JSON, 'w', encoding='utf-8') as fh:
        json.dump({'books': books}, fh, ensure_ascii=False, separators=(',', ':'))
    size = os.path.getsize(OUT_JSON)
    total_words = sum(sum(len(t['words']) for t in b['themes']) for b in books)
    total_levels = sum(len(b['themes']) for b in books)
    print('写出 %s：%d 册 / %d 关 / %d 词 / %.1f KB'
          % (OUT_JSON, len(books), total_levels, total_words, size / 1024.0))


if __name__ == '__main__':
    sys.exit(main())

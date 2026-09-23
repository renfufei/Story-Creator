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
* **按词性切主题**：词表本身是扁平的，没有主题。释义以词性标记开头（n. / v. / adj. …），
  按它归并成「名词 / 动词 / 形容词 / 副词 / 其他」五个主题，满足游戏「同一关的词同类型」的设计。
* **按源顺序切关**：上游文件是「三段乱序词表拼接」，同一个词的三次出现天然相隔上千行，
  顺着源顺序每 6 个切一关，重复就自动落在相隔上百关的位置 —— 相当于内置了复习节奏
  （实测四级 1251 关里没有一关出现重复英文，重复间距中位数 147 关）。
  若改用「把重复尽量摊开」的贪心分配，重复反而会挤在相邻几关里，体验更差。
* **关内英文不重复**：顺序切完后逐关扫一遍，撞名的记录顺延到下一关（carry），
  最后再在不破坏「关内英文唯一」的前提下把各关大小拉回 3~7 对（正常数据几乎不触发）。

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

# 词性标记 → 主题名。顺序即主题在册内的排列顺序。
POS_THEME = [
    (('n.',), '名词'),
    (('v.', 'vt.', 'vi.'), '动词'),
    (('adj.',), '形容词'),
    (('adv.',), '副词'),
]
OTHER_THEME = '其他'


def theme_of(zh):
    """按释义开头的词性标记归主题；没有标记的（多为短语/专名）进「其他」。"""
    m = re.match(r'^([a-z]+\.)', zh)
    tag = m.group(1) if m else ''
    for tags, name in POS_THEME:
        if tag in tags:
            return name
    return OTHER_THEME


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
    """把一个词性桶按源顺序切成若干关，保证每关 3~7 对、且关内英文不重复。

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


def build_book(book_id, rows):
    order = [name for _, name in POS_THEME] + [OTHER_THEME]
    buckets = collections.OrderedDict((name, []) for name in order)
    for en, zh in rows:
        buckets[theme_of(zh)].append((en, zh))

    themes = []
    for name, items in buckets.items():
        if not items:
            continue
        if len(items) < MIN_PAIRS:
            # 不足以成关的碎主题并入「其他」，绝不丢词
            buckets[OTHER_THEME].extend(items)
            continue
        for level in split_bucket(items):
            themes.append({'name': name, 'words': [[e, z] for e, z in level]})

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
        book, words = build_book(book_id, rows)
        levels = len(book['themes'])
        pairs = sum(len(t['words']) for t in book['themes'])
        print('  %s：源 %d 词 → %d 关 / 平均 %.2f 对，主题 %s'
              % (book_id, words, levels, pairs / float(levels),
                 '、'.join(sorted({t['name'] for t in book['themes']}))))
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

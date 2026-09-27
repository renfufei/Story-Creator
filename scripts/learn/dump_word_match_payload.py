#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线重建「单词匹配」两个接口的载荷，供 build_miniprogram_data.py --from-files 使用。

为什么要它
----------
`word-match-miniprogram/data/` 的内容来自线上服务（`GET /learn/word-match/data` 与
`GET /learn/word-match/cet`），而本机 agent 起的 java 服务进程活不过一个回合，
于是「改完词库 → 同步小程序快照」这条路以前必须先让用户重启服务。

本脚本把 `LearnController` + `WordMatchBank` / `CetWordBank` 的**读路径**用 Python 复刻一遍：
只从 `src/main/resources/learn/*.json` 读数据，按同一套规则切关（人教版走 `splitBalanced`，
大学「主题即关卡」），产出与线上**等价**的两份载荷。

等价性不是靠信心，而是靠可验证的旁证
------------------------------------
把产出的载荷喂给 `build_miniprogram_data.py --from-files`：如果**没有被本次改动波及**的
学段文件（`levels-primary/junior/senior.js` 与 `books.js`）产出后**逐字节不变**，就说明复刻的
载荷与线上一字不差 —— 否则那几份会跟着漂移。所以任何一次用它同步数据，都自带这个对照检查。

用法
----
  python3 scripts/learn/dump_word_match_payload.py --out-dir /tmp/wm_payload
  python3 scripts/learn/build_miniprogram_data.py \
      --from-files /tmp/wm_payload/data.json /tmp/wm_payload/cet.json
"""

import argparse
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LEARN_DIR = os.path.join(ROOT, 'src', 'main', 'resources', 'learn')
PEP_JSON = os.path.join(LEARN_DIR, 'pep-words.json')
CET_JSON = os.path.join(LEARN_DIR, 'cet-words.json')

# 与 WordMatchBank 一致
MIN_PAIRS, MAX_PAIRS, TARGET_PAIRS = 3, 7, 6


def split_balanced(n):
    """镜像 WordMatchBank.splitBalanced(int)：每关不超过 MAX_PAIRS，尽量贴近 TARGET_PAIRS。

    ⚠️ 这里必须用 Java 的 `Math.round` 语义（**四舍五入**，`.5` 向上），
    不能用 Python 内置 `round()`（**银行家舍入**，`.5` 取偶）。n=27 时 `27/6=4.5`：
    Java 得 5 关、Python 得 4 关 —— 实测这一处差异让 pep-9-1 / pep-h-7 / pep-h-8 三册少 4 关。
    """
    if n <= 0:
        return []
    if n <= MAX_PAIRS:
        return [n]
    by_max = int(math.ceil(n / float(MAX_PAIRS)))
    by_target = int(math.floor(n / float(TARGET_PAIRS) + 0.5))     # Java Math.round
    groups = min(max(by_max, by_target), n)
    base, remainder = divmod(n, groups)
    return [base + (1 if i < remainder else 0) for i in range(groups)]


def clean_words(theme_node):
    """取词对并 strip，空字段丢弃（与两个 Bank 的加载逻辑一致）。"""
    words = []
    for pair in theme_node.get('words', []):
        if not isinstance(pair, list) or len(pair) < 2:
            continue
        en, zh = str(pair[0]).strip(), str(pair[1]).strip()
        if en and zh:
            words.append((en, zh))
    return words


def book_meta(book_id, label, grade, semester, stage, level_count, word_count):
    """WordBankJson.booksNode 的字段顺序即前端契约，顺序不能改。"""
    return {
        'id': book_id,
        'label': label,
        'grade': grade,
        'semester': semester,
        'stage': stage,
        'levelCount': level_count,
        'wordCount': word_count,
    }


def pair_node(key, en, zh):
    return {'key': key, 'en': en, 'zh': zh}


def level_node(index, theme, pairs):
    return {'index': index, 'theme': theme, 'pairs': pairs}


def build_pep(root):
    """人教版 24 册：主题（课本单元）内再按 splitBalanced 均分成关，同一主题的关卡同名。"""
    books, levels = [], {}
    for book in root['books']:
        book_id = book['id']
        book_levels, word_count = [], 0
        for theme in book.get('themes', []):
            words = clean_words(theme)
            if len(words) < MIN_PAIRS:
                continue                      # 不足 MIN_PAIRS 对的主题不成关
            word_count += len(words)
            cursor = 0
            for size in split_balanced(len(words)):
                pairs = [pair_node('%s:%d:%d' % (book_id, len(book_levels), i),
                                   words[cursor + i][0], words[cursor + i][1])
                         for i in range(size)]
                cursor += size
                book_levels.append(level_node(len(book_levels), theme['name'], pairs))
        books.append(book_meta(book_id, book['label'], book['grade'], book['semester'],
                               book.get('stage', ''), len(book_levels), word_count))
        levels[book_id] = book_levels
    return books, levels


def build_cet(root):
    """大学 2 册：主题即关卡（切关已在 build_cet_words.py 里做完），不再二次切分。"""
    books, levels = [], {}
    for book in root['books']:
        book_id = book['id']
        book_levels, word_count = [], 0
        for theme in book.get('themes', []):
            words = clean_words(theme)
            if len(words) < MIN_PAIRS:
                continue
            word_count += len(words)
            pairs = [pair_node('%s:%d:%d' % (book_id, len(book_levels), i), en, zh)
                     for i, (en, zh) in enumerate(words)]
            book_levels.append(level_node(len(book_levels), theme['name'], pairs))
        books.append(book_meta(book_id, book['label'], book['grade'], book['semester'],
                               book['stage'], len(book_levels), word_count))
        levels[book_id] = book_levels
    return books, levels


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out-dir', default=os.path.join('/tmp', 'wm_payload'))
    args = ap.parse_args()

    with open(PEP_JSON, encoding='utf-8') as fh:
        pep = json.load(fh)
    with open(CET_JSON, encoding='utf-8') as fh:
        cet = json.load(fh)

    pep_books, pep_levels = build_pep(pep)
    cet_books, cet_levels = build_cet(cet)

    # /learn/word-match/data：人教版全量 + 大学两册的册元信息（extraBooks）
    data = {'books': pep_books, 'levels': pep_levels, 'extraBooks': cet_books}
    # /learn/word-match/cet：大学全量
    uni = {'books': cet_books, 'levels': cet_levels}

    os.makedirs(args.out_dir, exist_ok=True)
    for name, payload in (('data.json', data), ('cet.json', uni)):
        path = os.path.join(args.out_dir, name)
        with open(path, 'w', encoding='utf-8') as fh:
            json.dump(payload, fh, ensure_ascii=False, separators=(',', ':'))
        print('  %s  %.1f KB' % (path, os.path.getsize(path) / 1024.0))

    def totals(payload, key):
        books = payload['books']
        return (len(books),
                sum(b['levelCount'] for b in books),
                sum(b['wordCount'] for b in books))

    print('  人教版：%d 册 / %d 关 / %d 词' % totals(data, 'books'))
    print('  大学  ：%d 册 / %d 关 / %d 词' % totals(uni, 'books'))
    print('  大学册的关数（应与 cet-words.json 的 themes 数一致）：%s'
          % ', '.join('%s=%d' % (b['id'], b['levelCount']) for b in cet_books))
    return 0


if __name__ == '__main__':
    sys.exit(main())

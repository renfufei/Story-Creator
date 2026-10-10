#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重建 PEP 词库主题：把「按课文顺序切段」的语义主题换成按语义域归类的主题。

支持三个学段
  · primary（小学 8 册 pep-3-1 ~ pep-6-2） → primary-semantic-domains.tsv
  · junior（初中 5 册 pep-7-1 ~ pep-9-1）  → junior-semantic-domains.tsv
  · senior（高中 11 册 pep-h-1 ~ pep-h-11）→ senior-semantic-domains.tsv
小学与初/高中各自一套域名（小学主题=课本单元主题，域名只在小写内部复用）；
初中/高中共用同一套域名（跨学段统一域池），senior 新增了
政治与政府 / 科学与研究 / 经济与商业 / 宗教与信仰 四个域。

输入（唯一真相）
  · src/main/resources/learn/pep-words.json                                    现词库（提供词与中文）
  · src/test/resources/learn/pep-words-source/<stage>-semantic-domains.tsv     语义域表
输出
  · 默认写到 /tmp/<stage>-preview.json；加 --write 才覆盖 pep-words.json

规则
  · 只动目标学段的册，其它册一字不改；
  · 特殊主题（不规则变化 / 专有名词* / 常用功能词* / 同义表达：* /
    问候与日常用语 / 句型框架与常用搭配）原样保留，排在语义主题之后；
  · 词的增删一律不做（零丢失）；
  · 域内词序 = 域表中的顺序；域顺序 = 域表行序。

用法
  python3 scripts/learn/build_semantic_themes.py --stage primary
  python3 scripts/learn/build_semantic_themes.py --stage senior --write
  python3 scripts/learn/build_semantic_themes.py --stage both --write        # 初中+高中
  python3 scripts/learn/build_semantic_themes.py --stage all --write         # 小学+初中+高中
"""
import argparse
import collections
import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JSON_PATH = os.path.join(ROOT, 'src/main/resources/learn/pep-words.json')
SRC_DIR = os.path.join(ROOT, 'src/test/resources/learn/pep-words-source')

STAGES = {
    'primary': {
        'label': '小学',
        'books': ['pep-3-1', 'pep-3-2', 'pep-4-1', 'pep-4-2',
                  'pep-5-1', 'pep-5-2', 'pep-6-1', 'pep-6-2'],
        'dom': 'primary-semantic-domains.tsv',
    },
    'junior': {
        'label': '初中',
        'books': ['pep-7-1', 'pep-7-2', 'pep-8-1', 'pep-8-2', 'pep-9-1'],
        'dom': 'junior-semantic-domains.tsv',
    },
    'senior': {
        'label': '高中',
        'books': [f'pep-h-{i}' for i in range(1, 12)],
        'dom': 'senior-semantic-domains.tsv',
    },
}

SPECIAL_EXACT = {'不规则变化', '问候与日常用语', '句型框架与常用搭配'}
SPECIAL_PREFIX = ('专有名词', '常用功能词', '同义表达：')
QUOTES = {'\u2018': "'", '\u2019': "'", '\u02bc': "'", '\u201c': '"', '\u201d': '"'}

MIN_PAIRS, MAX_PAIRS, TARGET_PAIRS = 3, 7, 6


def norm(s):
    s = str(s).strip()
    for a, b in QUOTES.items():
        s = s.replace(a, b)
    return s.lower()


def is_special(name):
    return name in SPECIAL_EXACT or name.startswith(SPECIAL_PREFIX)


def split_balanced(n):
    """复刻 WordMatchBank.splitBalanced。Java Math.round = floor(x+0.5)。"""
    if n <= 0:
        return []
    if n <= MAX_PAIRS:
        return [n]
    by_max = math.ceil(n / MAX_PAIRS)
    by_target = int(math.floor(n / TARGET_PAIRS + 0.5))
    groups = min(max(by_max, by_target), n)
    base, rem = divmod(n, groups)
    return [base + (1 if i < rem else 0) for i in range(groups)]


def load_domains(path):
    """域表 -> (域顺序, 通用词映射, (词,册) 映射)。"""
    order, generic, per_book = [], {}, {}
    for line in open(path, encoding='utf-8'):
        line = line.rstrip('\n').lstrip('\ufeff')
        if not line.strip() or line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) < 2:
            continue
        dom = parts[0].strip()
        if dom not in order:
            order.append(dom)
        for tok in parts[1].split(','):
            tok = tok.strip()
            if not tok:
                continue
            if '@' in tok:
                en, bk = [x.strip() for x in tok.split('@', 1)]
                per_book[(norm(en), bk)] = dom
            else:
                generic[norm(tok)] = dom
    return order, generic, per_book


def rebuild(data, stage, write):
    cfg = STAGES[stage]
    books = set(cfg['books'])
    dom_path = os.path.join(SRC_DIR, cfg['dom'])
    order, generic, per_book = load_domains(dom_path)

    problems, summary = [], []
    for book in data['books']:
        bid = book['id']
        if bid not in books:
            continue
        special = [t for t in book['themes'] if is_special(t['name'])]
        groups = collections.OrderedDict((d, []) for d in order)
        sem_total = 0
        for t in book['themes']:
            if is_special(t['name']):
                continue
            for w in t['words']:
                en, zh = w[0], w[1]
                d = per_book.get((norm(en), bid)) or generic.get(norm(en))
                if not d:
                    problems.append(f'{bid}: 未归类 {en}({zh})')
                    continue
                groups[d].append([en, zh])
                sem_total += 1

        themes = [{'name': d, 'words': groups[d]} for d in order if groups[d]]
        themes += special
        old_levels = sum(len(split_balanced(len(t['words']))) for t in book['themes'])
        new_levels = sum(len(split_balanced(len(t['words']))) for t in themes)
        short = [f'{d}({len(groups[d])})' for d in order if 0 < len(groups[d]) < MIN_PAIRS]
        if short:
            problems.append(f'{bid}: 域不足 {MIN_PAIRS} 词 -> {", ".join(short)}')
        word_total = sem_total + sum(len(t['words']) for t in special)
        if word_total != sum(len(t['words']) for t in book['themes']):
            problems.append(f'{bid}: 词数变化（零丢失校验失败）')
        book['themes'] = themes
        summary.append((bid, book.get('label', ''), len(themes), word_total, old_levels, new_levels))

    print('=' * 78)
    print(f'学段 {stage}（{cfg["label"]}）  域池 {len(order)} 个  域表 {cfg["dom"]}')
    print('=' * 78)
    tot_old = tot_new = 0
    for bid, label, nt, nw, lo, ln in summary:
        tot_old += lo
        tot_new += ln
        print(f'  {bid:<9} {label:<12} 主题 {nt:>3} 词 {nw:>4} 关 {lo:>3} -> {ln:>3}')
    print(f'  {"合计":<9} {"":<12} {"":>3} {"":>4}     {tot_old:>3} -> {tot_new:>3}')
    print('=' * 78)
    if problems:
        print('!! 问题:')
        for p in problems:
            print('   ' + p)
        return False, summary
    return True, summary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--stage', choices=['primary', 'junior', 'senior', 'both', 'all'], default='both')
    ap.add_argument('--write', action='store_true', help='覆盖 pep-words.json')
    ap.add_argument('--out', default=None, help='预览输出路径（不给则用 /tmp/<stage>-preview.json）')
    args = ap.parse_args()

    if args.stage == 'all':
        stages = ['primary', 'junior', 'senior']
    elif args.stage == 'both':
        stages = ['junior', 'senior']
    else:
        stages = [args.stage]
    data = json.load(open(JSON_PATH, encoding='utf-8'))

    all_ok = True
    for st in stages:
        ok, _ = rebuild(data, st, args.write)
        all_ok = all_ok and ok
        print()

    if not all_ok and not args.write:
        print('预览校验未通过，未写文件。修好后再加 --write。')
        sys.exit(1)

    out = JSON_PATH if args.write else (args.out or f'/tmp/{args.stage}-preview.json')
    with open(out, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print('已写出:', out)


if __name__ == '__main__':
    main()

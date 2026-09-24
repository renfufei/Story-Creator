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
  `英文\\t释义\\t语义域` 映射）给出每个词条的语义域，归并成 68 个语义域主题，
  满足游戏「同一关的词同类型」的设计。映射里查不到的条目回退到「特殊类别」。
* **域内按源顺序切关**：同一语义域内顺着源顺序每 6 个切一关。上游是「三段乱序词表拼接」，
  同一个词的三次出现天然相隔上千行，重复就自动落在相隔很远的关卡 —— 相当于内置了复习节奏。
  若改用「把重复尽量摊开」的贪心分配，重复反而会挤在相邻几关里，体验更差。
* **关内英文不重复**：顺序切完后逐关扫一遍，撞名的记录顺延到下一关（carry），
  最后再在不破坏「关内英文唯一」的前提下把各关大小拉回 3~7 对（正常数据几乎不触发）。
* **不足 3 对的碎域**：并入「特殊类别」，绝不丢词。

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

# 68 个语义域。顺序即主题在册内的排列顺序（具体 → 抽象 → 功能兜底）。
DOMAIN_ORDER = [
    # 具体生活（10）
    '人物与身份', '家庭与亲属', '身体与健康', '饮食与食物', '服饰与打扮',
    '居住与建筑', '交通与出行', '购物与消费', '娱乐与休闲', '日常用品与工具',
    # 自然与物质（5）
    '动物与植物', '自然与天气', '物质与材料', '空间与方位', '事物与部件',
    # 社会（19）
    '组织与机构', '政治与政府', '法律与司法', '军事与战争', '经济与金融',
    '商业与贸易', '工作与职业', '教育与学习', '科学技术', '计算机与信息',
    '媒体与传播', '文学与写作', '艺术与绘画', '音乐与表演', '影视与娱乐',
    '体育与运动', '宗教与信仰', '节日与习俗', '历史与考古',
    # 心智与抽象（30）
    '情绪与感受', '性格与品质', '态度与意愿', '思考与观点', '认知与理解', '记忆与注意',
    '语言与交流', '数量与度量', '时间与频率', '性质与特征', '状态与情况',
    '变化与发展', '增长与减少', '因果与逻辑', '方法与手段', '计划与安排',
    '重要性', '优劣评价', '正确与错误', '关系与异同', '程度与强度',
    '移动与位移', '操作与处理', '获取与给予', '建立与破坏', '保护与维持',
    '帮助与合作', '竞争与冲突', '控制与影响', '交往与联系',
    # 功能与特殊（4）
    '功能词', '专有名词', '短语与搭配', '特殊类别',
]

# 兜底域：映射里查不到、或词数不足以成关的，都并到这里。
FALLBACK_THEME = '特殊类别'


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


def build_book(book_id, rows, theme_map):
    buckets = collections.OrderedDict((name, []) for name in DOMAIN_ORDER)
    for en, zh in rows:
        buckets[theme_map.get((en, zh), FALLBACK_THEME)].append((en, zh))

    themes = []
    for name in DOMAIN_ORDER:
        items = buckets[name]
        if not items:
            continue
        if len(items) < MIN_PAIRS:
            # 不足以成关的碎域并入「特殊类别」，绝不丢词
            buckets[FALLBACK_THEME].extend(items)
            continue
        levels = split_bucket(items)
        for i, level in enumerate(levels, 1):
            # 同一域多关时加序号，便于玩家知道「看到第几关」
            title = name if len(levels) == 1 else '%s · %d' % (name, i)
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
        print('  %s：源 %d 词 → %d 关 / 平均 %.2f 对，命中语义域 %d / %d'
              % (book_id, words, levels, pairs / float(levels), len(used), len(DOMAIN_ORDER)))
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

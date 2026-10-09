#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从产物生成高中语义域清单文档（`docs/senior-domain-taxonomy.md`）。

体例与 `gen_junior_domain_doc.py` 一致：直接读**产物** `pep-words.json`
（而不是域表），因为域表里词可带 `@册id` 限定，且 `WordMatchBank` 会丢弃
「某册不足 3 词」的整主题 —— 「某册某域到底有多少词」只有在产物里才是最终答案。

用法：
  python3 scripts/learn/gen_senior_domain_doc.py
"""

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JSON_PATH = os.path.join(ROOT, 'src/main/resources/learn/pep-words.json')
DOM_PATH = os.path.join(ROOT, 'src/test/resources/learn/pep-words-source/senior-semantic-domains.tsv')
OUT_PATH = os.path.join(ROOT, 'docs/senior-domain-taxonomy.md')

SENIOR = [f'pep-h-{i}' for i in range(1, 12)]
SPECIAL_EXACT = {'不规则变化', '问候与日常用语', '句型框架与常用搭配'}
SPECIAL_PREFIX = ('专有名词', '常用功能词', '同义表达：')

# 重分类前每册的语义主题数（都是课本单元 Unit 1~5）与关数
OLD_SEM_THEMES = 5
OLD_LEVELS = {'pep-h-1': 54, 'pep-h-2': 56, 'pep-h-3': 63, 'pep-h-4': 55, 'pep-h-5': 62,
              'pep-h-6': 66, 'pep-h-7': 66, 'pep-h-8': 73, 'pep-h-9': 59, 'pep-h-10': 63,
              'pep-h-11': 52}

# 因「11 册总量 < 33 词（= 3×11）必然有册不足 3 词、整主题会被丢弃」而整体并入近邻域的 11 个域
MERGED_AWAY = [
    ('学习用品', '学校与学习', 2),
    ('家庭成员', '人物与称谓', 5),
    ('日常起居与家务', '家居与住所', 11),
    ('英雄与传奇', '人物与称谓', 12),
    ('问候与应答', '语言与交流', 13),
    ('厨房与餐具', '家居与住所', 14),
    ('水果与蔬菜', '食物与饮料', 16),
    ('节日与庆典', '休闲与娱乐', 18),
    ('天气与季节', '自然与地理', 20),
    ('音乐与乐器', '影视与艺术', 21),
    ('运动与比赛', '休闲与娱乐', 38),
]

NEW_DOMAINS = [
    ('政治与政府', '政治制度、政府机构、选举、政策、外交、公民权利'),
    ('科学与研究', '科学方法、理论、假说、实验、学科分支、科研仪器'),
    ('经济与商业', '宏观经济、金融货币、税收、企业公司、生产贸易、就业'),
    ('宗教与信仰', '宗教、教派、信仰、灵魂、神话宗教仪式'),
]


def read_domains():
    names, seen = [], set()
    with open(DOM_PATH, encoding='utf-8') as fh:
        for line in fh:
            line = line.rstrip('\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            name = line.split('\t', 1)[0].strip()
            if name not in seen:
                seen.add(name)
                names.append(name)
    return names


def main():
    with open(JSON_PATH, encoding='utf-8') as fh:
        root = json.load(fh)

    order = read_domains()
    books = {b['id']: b for b in root['books']}

    per = {d: {} for d in order}
    special = {}
    labels = {}
    for bid in SENIOR:
        b = books[bid]
        labels[bid] = b.get('label', bid)
        sp = []
        for t in b['themes']:
            n = len(t.get('words', []))
            nm = t['name']
            if nm in SPECIAL_EXACT or nm.startswith(SPECIAL_PREFIX):
                sp.append((nm, n))
                continue
            per.setdefault(nm, {})[bid] = n
        special[bid] = sp

    unknown = [d for d in per if d not in order and any(per[d].values())]
    if unknown:
        print('!! 出现了不在域表里的域：%s' % unknown, file=sys.stderr)
        return 1

    L = []
    L.append('# 高中词库 · 语义域体系（跨学段统一域池，41 个域）')
    L.append('')
    L.append('> **生成物，别手改**：`python3 scripts/learn/gen_senior_domain_doc.py` 重生成。')
    L.append('> 唯一真相是 `src/test/resources/learn/pep-words-source/senior-semantic-domains.tsv`')
    L.append('> （域清单 + 逐词归属，词后可带 `@册id` 限定单册）；')
    L.append('> 本页数字取自产物 `src/main/resources/learn/pep-words.json`'
             '（用 `scripts/learn/build_semantic_themes.py --stage senior` 重建）。')
    L.append('')
    L.append('## 一、为什么重排')
    L.append('')
    L.append('高中 11 册（必修1-5 / 选修6-11）原来的「主题」**连名字都没有**，就叫 `Unit 1` ~ `Unit 5` ——')
    L.append('是课本单元编号，不是分类。一个 Unit 50~80 词，段内词汇毫无关联，'
             '例如选修8 `Unit 1`（75 词）里同时装着：')
    L.append('')
    L.append('- 人口与社会 `immigrant` / `racial` / `Hispanic`　·　政治 `elect` / `federal` / `socialism`')
    L.append('- 地理 `strait` / `pole` / `Arctic`　·　交通 `cable car` / `tram` / `ferry` / `brake`')
    L.append('- 思辨 `illustrate` / `distinct` / `indicate`　·　动物 `seagull`　·　情绪 `miserable`')
    L.append('')
    L.append('切关后这个 Unit 会变成连续 12 关，关与关之间没有任何语义关联。')
    L.append('')
    L.append('按同样的判据重排：把本域的词随机抽 6 个放进一关，玩家会不会觉得「这 6 个是一伙」；')
    L.append('归类**主要按含义**，同时允许**按前缀/词根聚类**（例：`study` / `student` 同类）。')
    L.append('')
    L.append('## 二、域池 = 初中 48 域 + 高中新增 4 域')
    L.append('')
    L.append('高中与初中**共用同一套域名**（跨学段统一），高中按需要新增 4 个域：')
    L.append('')
    L.append('| 新增域 | 收什么 |')
    L.append('|---|---|')
    for nm, desc in NEW_DOMAINS:
        L.append('| %s | %s |' % (nm, desc))
    L.append('')
    L.append('另有 **11 个域**在高中「结构性不足」被整体并入近邻域 —— 判据是'
             '**11 册总量 < 33 词**（= 3 词 × 11 册），必然有册掉到 1~2 词，'
             '而 `WordMatchBank.load()` 对 `< MIN_PAIRS(3)` 的主题直接 `continue`，'
             '**整主题连同词一起丢**：')
    L.append('')
    L.append('| 被并入的域 | 总量 | → 并入 |')
    L.append('|---|---|---|')
    for nm, tgt, n in MERGED_AWAY:
        L.append('| %s | %d 词 | %s |' % (nm, n, tgt))
    L.append('')
    L.append('剩余「域总量够、只是个别册不足 3 词」的情况，用**逐册就近合并**解决'
             '（该册那个词组并进语义最近的邻域），从而保住分类粒度 —— '
             '所以同一域名在不同册的存废可能不同，这是词库分布的真实反映。')
    L.append('')
    L.append('## 三、41 个域 × 高中 11 册（词条数）')
    L.append('')
    L.append('「—」= 该域在这册没有词。')
    L.append('')
    L.append('| # | 语义域 | 合计 | 出现 | ' + ' | '.join(labels[b] for b in SENIOR) + ' |')
    L.append('|---|---|---' + '|---' * (len(SENIOR) + 1) + '|')
    for i, d in enumerate(order, 1):
        cells, total, nbooks = [], 0, 0
        for bid in SENIOR:
            n = per.get(d, {}).get(bid, 0)
            cells.append('—' if not n else str(n))
            total += n
            nbooks += 1 if n else 0
        L.append('| %d | %s | %d | %d 册 | %s |' % (i, d, total, nbooks, ' | '.join(cells)))
    L.append('')
    L.append('## 四、特殊主题（**原样保留，不参与重分类**）')
    L.append('')
    L.append('不规则变形、固定句型、专有名词、同义表达这几类词按设计就不该混进语义域；'
             '`WordMatchBankTest#specialThemes_existForIrregularAndSpecialWords` 把它们钉住了 ——')
    L.append('**同义表达的主题允许同一个中文对应多个词**，是「按释义判定而非按配对 key」的依据。')
    L.append('')
    for bid in SENIOR:
        tot = sum(n for _, n in special[bid])
        L.append('- **%s**（%d 个特殊主题 / %d 词）：%s'
                 % (labels[bid], len(special[bid]), tot,
                    ' · '.join('%s(%d)' % (nm, n) for nm, n in special[bid])))
    L.append('')
    L.append('## 五、结果与下游')
    L.append('')
    L.append('| 册 | id | 语义主题（旧 → 新） | 词 | 关（旧 → 新） |')
    L.append('|---|---|---|---|---|')
    tot_old = tot_new = 0
    for bid in SENIOR:
        b = books[bid]
        sem = [t for t in b['themes']
               if not (t['name'] in SPECIAL_EXACT or t['name'].startswith(SPECIAL_PREFIX))]
        words = sum(len(t.get('words', [])) for t in b['themes'])
        o = OLD_LEVELS[bid]
        n = sum(_split_balanced(len(t.get('words', []))) for t in b['themes'])
        tot_old += o
        tot_new += n
        L.append('| %s | %s | %d → %d | %d | %d → **%d** |'
                 % (labels[bid], bid, OLD_SEM_THEMES, len(sem), words, o, n))
    L.append('| **合计** | | %d → **%d** | %d | %d → **%d** |'
             % (OLD_SEM_THEMES * len(SENIOR),
                sum(len([t for t in books[b]['themes']
                         if not (t['name'] in SPECIAL_EXACT or t['name'].startswith(SPECIAL_PREFIX))])
                    for b in SENIOR),
                sum(len(t.get('words', [])) for b in SENIOR for t in books[b]['themes']),
                tot_old, tot_new))
    L.append('')
    L.append('- **词数一条不差**：高中 3877 / PEP 24 册 7032 / 全站 20191 均不变。')
    L.append('  关数 高中 669 → **731**，于是 PEP 24 册 1269 → **1331**、26 册合计 3472 → **3534**。')
    L.append('- **每关对数的均值下移**：语义域细化后出现大量 3~7 词的小域'
             '（`splitBalanced` 对 n≤7 直接成一关），PEP 均值由 5.54 降到 5.28、'
             '「恰好 6 对」由 56.8% 降到 35.4%。这是「分类准确度 vs 关卡长度」的取舍，选了前者；')
    L.append('  3~7 对的硬边界未变，`WordMatchBankTest#levels_centerAroundSixPairs` 已按新结构改写并注明原因。')
    L.append('- **进度指纹不手工升版**：关数变了 ⇒ `dataSignature()` / `signature()` 的数字部分自动失配 ⇒')
    L.append('  旧进度自动作废。项目约定：只有「只改顺序、数量不变」才需要手工升版本号。')
    L.append('- 复核方式：本页「合计」列对不上就该重跑 `build_semantic_themes.py --stage senior`；')
    L.append('  零丢失由 `WordMatchBankTest` 与「词对多重集」对比双向守着。')
    L.append('')

    with open(OUT_PATH, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(L))
    print('已写出 %s（%d 个域，%d 字节）' % (OUT_PATH, len(order), sum(len(x) + 1 for x in L)))
    return 0


def _split_balanced(n):
    """复刻 WordMatchBank.splitBalanced（Java Math.round = floor(x+0.5)）。"""
    import math
    if n <= 0:
        return 0
    if n <= 7:
        return 1
    g = min(max(math.ceil(n / 7), int(math.floor(n / 6 + 0.5))), n)
    return g


if __name__ == '__main__':
    sys.exit(main())

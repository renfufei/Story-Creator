#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从产物生成初中语义域清单文档（`docs/junior-domain-taxonomy.md`）。

为什么从**产物**生成而不是从域表生成
--------------------------------------
域表 `junior-semantic-domains.tsv` 里同一个词可以带 `@册id` 限定（表示「只在这册生效」），
而 `WordMatchBank` 是**逐册**读主题、**主题 < 3 词整主题丢弃**的。所以「某册某域到底有多少词」
只有在 `pep-words.json` 里才是最终答案（也能顺带暴露「被丢弃的碎域」）。
本脚本因此直接读 `pep-words.json`，域表只用来取**域名与顺序**。

用法：
  python3 scripts/learn/gen_junior_domain_doc.py
"""

import json
import math
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JSON_PATH = os.path.join(ROOT, 'src/main/resources/learn/pep-words.json')
DOM_PATH = os.path.join(ROOT, 'src/test/resources/learn/pep-words-source/junior-semantic-domains.tsv')
OUT_PATH = os.path.join(ROOT, 'docs/junior-domain-taxonomy.md')

JUNIOR = ['pep-7-1', 'pep-7-2', 'pep-8-1', 'pep-8-2', 'pep-9-1']
SHORT = {'pep-7-1': '七上', 'pep-7-2': '七下', 'pep-8-1': '八上', 'pep-8-2': '八下', 'pep-9-1': '九全'}
SPECIAL_EXACT = {'不规则变化', '问候与日常用语', '句型框架与常用搭配'}
SPECIAL_PREFIX = ('专有名词', '常用功能词', '同义表达：')


def split_balanced(n):
    """复刻 WordMatchBank.splitBalanced（MIN_PAIRS 3 / MAX_PAIRS 7 / TARGET_PAIRS 6）。

    注意 Java 用 Math.round，Python 必须写成 floor(x + 0.5)；内置 round() 是银行家舍入，
    在 n=9 这类点上会差 1（9/6+0.5=2.0 两法相同，但 15/6+0.5=3.0... 边界一律按 floor+0.5）。
    """
    if n <= 7:
        return 1
    groups = min(max(int(math.ceil(n / 7)), int(math.floor(n / 6.0 + 0.5))), n)
    return groups

# 关数（旧 → 新）：旧值取自重分类前的 pep-words.json（重切关算法未变，可按 splitBalanced 复算）
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

    # 语义域 -> 册 -> 词条数；以及每册的「域 -> 词条数」
    per = {d: {} for d in order}
    special = {}          # 册 -> [(主题名, 词条数)]
    for bid in JUNIOR:
        b = books[bid]
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
    L.append('# 初中词库 · 语义域体系（48 个跨册统一域）')
    L.append('')
    L.append('> **生成物，别手改**：`python3 scripts/learn/gen_junior_domain_doc.py` 重生成。')
    L.append('> 唯一真相是 `src/test/resources/learn/pep-words-source/junior-semantic-domains.tsv`')
    L.append('> （域清单 + 逐词归属，词后可带 `@册id` 限定单册）；')
    L.append('> 本页数字取自产物 `src/main/resources/learn/pep-words.json`（用 `scripts/learn/build_semantic_themes.py --stage junior` 重建）。')
    L.append('')
    L.append('## 一、为什么重排')
    L.append('')
    L.append('七~九年级 5 册原来的「主题」**不是分类，是课文顺序**：一节 Unit 被硬切成 2~3 段，')
    L.append('段名临时起（「乐器与俱乐部 / 才艺与表达 / 俱乐部与才能」），段内词汇风马牛不相及 ——')
    L.append('七下「博物馆与感受」里装的是 `worry` / `sun` / `museum` / `fire` / `painting`。')
    L.append('小学 8 册是全套真语义分类，只有初中是课文顺序，所以整体重排。')
    L.append('')
    L.append('**判据**（照抄四六级那套自问）：把本域的词随机抽 6 个放进一关，玩家会不会觉得「这 6 个是一伙的」？')
    L.append('归类**主要按含义**，同时允许**按前缀/词根聚类**（例：`study` / `student` / `studies` 同类）。')
    L.append('')
    L.append('## 二、粒度与三条硬约束')
    L.append('')
    L.append('- **每册每域 ≥ 3 词**（硬）：`WordMatchBank.load()` 对 `< MIN_PAIRS(3)` 的主题直接 `continue`，')
    L.append('  **整主题连同词一起丢**。首版有 24 处碎域（1~2 词）会被静默吞掉，全部并域解决。')
    L.append('- **粒度 5~50 词**：个别天然小域可以到 3，但不允许出现 1~2 词的域。')
    L.append('- **同一册内中文不得重复**（「同义表达：」这类特殊主题除外，见第四节）。')
    L.append('')
    L.append('## 三、48 个域 × 初中 5 册（词条数）')
    L.append('')
    L.append('「—」= 该域在这册没有词（域表里这个域的词都带了别的册的 `@` 限定）。')
    L.append('')
    L.append('| # | 语义域 | 合计 | 出现 | 七上 | 七下 | 八上 | 八下 | 九全 |')
    L.append('|---|---|---|---|---|---|---|---|---|')
    for i, d in enumerate(order, 1):
        cells, total, nbooks = [], 0, 0
        for bid in JUNIOR:
            n = per.get(d, {}).get(bid, 0)
            cells.append('—' if not n else str(n))
            total += n
            nbooks += 1 if n else 0
        L.append('| %d | %s | %d | %d 册 | %s |' % (i, d, total, nbooks, ' | '.join(cells)))
    L.append('')
    L.append('## 四、特殊主题（**原样保留，不参与重分类**）')
    L.append('')
    L.append('这些主题的词按设计就不该混进语义域（不规则变形、固定句型、专有名词、同义表达），')
    L.append('`WordMatchBankTest#specialThemes_existForIrregularAndSpecialWords` 把它们钉住了 ——')
    L.append('**同义表达的主题允许同一个中文对应多个词**，是「按释义判定而非按配对 key」的依据。')
    L.append('')
    for bid in JUNIOR:
        tot = sum(n for _, n in special[bid])
        L.append('- **%s**（%d 个特殊主题 / %d 词）：%s'
                 % (SHORT[bid], len(special[bid]), tot,
                    ' · '.join('%s(%d)' % (nm, n) for nm, n in special[bid])))
    L.append('')
    L.append('## 五、结果与下游')
    L.append('')
    L.append('「关」列是 `splitBalanced`（MIN 3 / MAX 7 / TARGET 6）对本册各域词数**现算**的，')
    L.append('与 `WordMatchBank` 运行时逐字一致；「旧」是重分类前（课文顺序切段）的值。')
    L.append('')
    L.append('| 册 | id | 语义域（旧 → 现） | 词 | 关（旧 → 现） |')
    L.append('|---|---|---|---|---|')
    OLD_THEMES = {'pep-7-1': 43, 'pep-7-2': 51, 'pep-8-1': 32, 'pep-8-2': 33, 'pep-9-1': 37}
    OLD_LEVELS = {'pep-7-1': 75, 'pep-7-2': 91, 'pep-8-1': 75, 'pep-8-2': 81, 'pep-9-1': 97}
    LABEL = {'pep-7-1': '七年级上册', 'pep-7-2': '七年级下册', 'pep-8-1': '八年级上册',
             'pep-8-2': '八年级下册', 'pep-9-1': '九年级全一册'}
    tot_old = tot_new = 0
    for bid in JUNIOR:
        b = books[bid]
        sem = [t for t in b['themes'] if not (t['name'] in SPECIAL_EXACT or t['name'].startswith(SPECIAL_PREFIX))]
        words = sum(len(t.get('words', [])) for t in b['themes'])
        n = sum(split_balanced(len(t.get('words', []))) for t in b['themes'])
        tot_old += OLD_LEVELS[bid]
        tot_new += n
        L.append('| %s | %s | %d → %d | %d | %d → **%d** |'
                 % (LABEL[bid], bid, OLD_THEMES[bid], len(sem), words, OLD_LEVELS[bid], n))
    L.append('| **合计** | | | 2309 | %d → **%d** |' % (tot_old, tot_new))
    L.append('')
    L.append('- **词数一条不差**（初中 2309 / PEP 7032 / 全站 20191 均不变）；关数 初中 419 → **444**。')
    L.append('  四段变化：① 初中重分类 419 → **439** ⇒ PEP 24 册 1249 → 1269、26 册 3452 → 3472；')
    L.append('  ② 高中 11 册重分类 +62 ⇒ PEP **1331**、26 册 **3534**（见 `docs/senior-domain-taxonomy.md`）；')
    L.append('  ③ 初中「逐关校对」+5 ⇒ PEP **1336** / 26 册 **3539**（见 `docs/junior-level-audit.md`）；')
    L.append('  ④ 高中「逐关校对」+5 ⇒ PEP **1341** / 26 册 **3544**（见 `docs/senior-level-audit.md`）。')
    L.append('- **逐关校对（2026-10-09）**：只动**归属**不动词 —— 子代理逐关共标出 **96 条**不搭词，')
    L.append('  75 条落地 + 21 条放弃（目标域在该册凑不到 3 词，硬塞会让**整域连词一起丢**）；')
    L.append('  另外为把目标域补到 ≥3 词**补了 6 条移动** ⇒ 合计 **81 条域变更**（59 条换语义域 + 22 条进特殊主题）。')
    L.append('  新点亮了 `媒体与通讯`(七上/七下)、`科技与网络`(七上)、`厨房与餐具`(七下)、')
    L.append('  `水果与蔬菜`(九全) 这些此前在该册为空的域实例。完整清单见 `docs/junior-level-audit.md`。')
    L.append('  **不为凑数扭曲语义**：宁可放弃，也不把 `waste` 塞进只剩 2 词的域。')
    L.append('- 逐册关数：七上 74 · 七下 **91** · 八上 **83** · 八下 92 · 九全 **104**。')
    L.append('- **进度指纹不手工升版**：关数变了 ⇒ `dataSignature()` / `signature()` 的数字部分自动失配 ⇒')
    L.append('  旧进度自动作废。项目约定：只有「只改顺序、数量不变」才需要手工升版本号。')
    L.append('- 复核方式：本页「合计」列对不上就该重跑 `build_semantic_themes.py --stage junior`；')
    L.append('  零丢失由 `WordMatchBankTest` 与「词对多重集」对比双向守着。')
    L.append('')

    with open(OUT_PATH, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(L))
    print('已写出 %s（%d 个域，%d 字节）' % (OUT_PATH, len(order), sum(len(x) + 1 for x in L)))
    return 0


if __name__ == '__main__':
    sys.exit(main())

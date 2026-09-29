#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成（或重生成）两份域体系文档：

  docs/cet-domain-taxonomy.md —— 131 个细域的完整清单、两级由来、维护手册
  docs/cet-level-order.md     —— 关卡顺序与域分布：逐域位置 + 前 60 关逐关、验证、回滚

为什么要脚本生成而不是手写：这两份文档的内容完全由
`cet-domain-tree.tsv` + `cet-themes.tsv` + `cet-words.json` 决定，
词库一改手写版立刻过期。重跑 `build_cet_words.py` 之后跟着跑一下这个就行。

用法：python3 scripts/learn/gen_cet_domain_docs.py
"""
import collections
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC_DIR = os.path.join(ROOT, 'src', 'test', 'resources', 'learn', 'cet-words-source')
TREE = os.path.join(SRC_DIR, 'cet-domain-tree.tsv')
THEMES = os.path.join(SRC_DIR, 'cet-themes.tsv')
CET_JSON = os.path.join(ROOT, 'src', 'main', 'resources', 'learn', 'cet-words.json')
OUT_TAXONOMY = os.path.join(ROOT, 'docs', 'cet-domain-taxonomy.md')
OUT_ORDER = os.path.join(ROOT, 'docs', 'cet-level-order.md')
BOOKS = ('cet-4', 'cet-6')
MAX_SAME_DOMAIN_RUN = 5
FALLBACK_PARENT = '特殊类别'


def read_tsv(path, ncol):
    rows = []
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            line = line.rstrip('\n').rstrip('\r')
            if not line.strip():
                continue
            parts = line.split('\t')
            assert len(parts) == ncol, (path, line)
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


def spans(indices, cap=6):
    """把一组关卡下标压成「起-止 / 起-止 …」的段列表（最多 cap 段，超出用「共 N 段」结尾）。"""
    idx = sorted(indices)
    out, start, prev = [], idx[0], idx[0]
    for i in idx[1:]:
        if i == prev + 1:
            prev = i
            continue
        out.append((start, prev))
        start = prev = i
    out.append((start, prev))
    shown = ['%d-%d' % (a, b) if a != b else '%d' % a for a, b in out[:cap]]
    text = ' / '.join(shown)
    if len(out) > cap:
        text += ' …（共 %d 段）' % len(out)
    return text, len(out)


def main():
    tree = read_tsv(TREE, 3)
    themes_tsv = read_tsv(THEMES, 3)
    parent = {sub: par for par, sub, _ in tree}
    desc = {sub: note for _, sub, note in tree}
    order = [sub for _, sub, _ in tree]

    rows_of = collections.Counter(d for _, _, d in themes_tsv)
    theme_of = {(e, z): d for e, z, d in themes_tsv}
    per_book = {}
    for bid in BOOKS:
        cnt = collections.Counter()
        for pair in read_source(bid):
            cnt[theme_of[pair]] += 1
        per_book[bid] = cnt

    data = json.load(open(CET_JSON, encoding='utf-8'))
    books = {b['id']: b for b in data['books']}

    split_parents = [p for p in dict.fromkeys(parent.values())
                     if [s for s in order if parent[s] == p] != [p]]
    whole_parents = [p for p in dict.fromkeys(parent.values()) if p not in split_parents]

    write_taxonomy(tree, parent, desc, order, rows_of, per_book,
                   split_parents, whole_parents)
    write_order(tree, parent, order, books, rows_of)


def write_taxonomy(tree, parent, desc, order, rows_of, per_book, split_parents, whole_parents):
    L = []
    L.append('# 四六级词库 · 语义域体系（131 个细域）')
    L.append('')
    L.append('> **生成物，别手改**：`python3 scripts/learn/gen_cet_domain_docs.py` 重生成。')
    L.append('> 唯一真相是 `src/test/resources/learn/cet-words-source/` 下的')
    L.append('> `cet-themes.tsv`（逐条归属）与 `cet-domain-tree.tsv`（域清单 + 顺序）。')
    L.append('')
    L.append('## 一、两级结构：父域与细域')
    L.append('')
    L.append('原来的 68 个语义域是**一层**。它有 35 个域装得太多 —— 最极端的「性质与特征」818 条，')
    L.append('从 `able` 到 `awkward` 什么形容词都有，一关 6 条随机抽出来彼此毫无关系，玩家记不住，')
    L.append('也没有「同类词一起记」的效果。')
    L.append('')
    L.append('所以现在分两级：')
    L.append('')
    L.append('| 层级 | 是什么 | 谁在用 |')
    L.append('|---|---|---|')
    L.append('| **父域**（68 个，原样保留） | 语义大类，只做分组与兜底依据 | 文档、碎域合并、`cet-domain-tree.tsv` 第一列 |')
    L.append('| **细域**（131 个，玩家看到的主题名） | 真正的关卡分类 | 关卡主题、前端显示 |')
    L.append('')
    L.append('**粒度标准**：每个细域 **60~130 条**（约 10~22 关），**硬上限 150 条**（≈25 关）；')
    L.append('天然稀少的词类（感叹词、序数词）可以到 5 条，但**不许出现 1~2 条的细域**')
    L.append('（`MIN_PAIRS = 3` 以下的会被并走）。')
    L.append('')
    L.append('**切分原则**：只按**词义所指的东西**分，不按词性、难度、词频；')
    L.append('**不跨父域**（一个词不会从「性质与特征」被挪到别的父域去）。')
    L.append('判据是那句自问：**把这个细域的词随机抽 6 个放进一关，玩家会不会觉得「这 6 个是一伙的」？**')
    L.append('')

    n_fine = sum(1 for s in order if parent[s] in split_parents)
    L.append('## 二、细分一览（%d 个粗域 → %d 个细域）' % (len(split_parents), n_fine))
    L.append('')
    L.append('| 粗域（父域） | 词条数 | 拆成 | 细域（词条数） |')
    L.append('|---|---|---|---|')
    for p in split_parents:
        kids = [s for s in order if parent[s] == p]
        cells = ' · '.join('%s（%d）' % (s, rows_of[s]) for s in kids)
        L.append('| %s | %d | %d | %s |' % (p, sum(rows_of[s] for s in kids), len(kids), cells))
    L.append('')

    L.append('## 三、没细分的 %d 个域（词条数没超阈值，保持原样）' % len(whole_parents))
    L.append('')
    L.append('| 域 | 词条数 | 四级 | 六级 |')
    L.append('|---|---|---|---|')
    for p in whole_parents:
        L.append('| %s | %d | %d | %d |'
                 % (p, rows_of[p], per_book['cet-4'][p], per_book['cet-6'][p]))
    L.append('')

    L.append('## 四、完整清单（按册内出现顺序）')
    L.append('')
    L.append('| # | 细域 | 父域 | 词条数 | 四级 | 六级 | 收什么词 |')
    L.append('|---:|---|---|---:|---:|---:|---|')
    for i, s in enumerate(order, 1):
        L.append('| %d | **%s** | %s | %d | %d | %d | %s |'
                 % (i, s, parent[s], rows_of[s], per_book['cet-4'][s], per_book['cet-6'][s], desc[s]))
    L.append('')
    L.append('> 「四级 / 六级」列是该细域在本册里的词条数；为 0 表示这一册没有这类词（该册不出这类关）。')
    L.append('')

    L.append('## 五、维护手册')
    L.append('')
    L.append('### 改某个词的归属')
    L.append('')
    L.append('1. 改 `cet-themes.tsv` 第 3 列（保持 `英文\\t释义\\t细域` 三段式，英文释义一字不动）。')
    L.append('2. 若该域规模因此超过 150 条，就得再拆（见下）。')
    L.append('')
    L.append('### 再细分某个域（粒度又不够时）')
    L.append('')
    L.append('1. 把该域的词条切成 `gN-in.tsv`（`英文\\t释义\\t原域`）。')
    L.append('2. 逐条判到新细域，产出 `gN-out.tsv`（`英文\\t释义\\t细域`）与')
    L.append('   `gN-tree.tsv`（`父域\\t细域\\t说明`，说明 ≤ 30 字）。')
    L.append('3. 校验：`python3 scripts/learn/validate_cet_domains.py --parts <目录>`')
    L.append('   —— 它会检查「不增不删不改 / 不跨父域 / 细域已登记 / 与新老域名不撞 / 规模区间」。')
    L.append('4. 合并进 `cet-themes.tsv` 与 `cet-domain-tree.tsv`（未细分的域在树里占一行、父域 == 细域）。')
    L.append('5. **注意**：细域名会成为玩家看到的新主题名，**不要复用旧名字指代新含义**。')
    L.append('')
    L.append('### 重建与同步（改完必做）')
    L.append('')
    L.append('```bash')
    L.append('# 1) 重建词库')
    L.append('python3 scripts/learn/build_cet_words.py')
    L.append('# 2) 总校验（域体系 + 粒度 + 三副本）')
    L.append('python3 scripts/learn/validate_cet_domains.py')
    L.append('# 3) 同步小程序端（离线复刻接口载荷，不需要本机起服务）')
    L.append('python3 scripts/learn/dump_word_match_payload.py --out-dir /tmp/wm_payload')
    L.append('python3 scripts/learn/build_miniprogram_data.py \\')
    L.append('    --from-files /tmp/wm_payload/data.json /tmp/wm_payload/cet.json')
    L.append('# 4) 重生成这两份文档')
    L.append('python3 scripts/learn/gen_cet_domain_docs.py')
    L.append('```')
    L.append('')
    L.append('> ⚠️ **关数变了就必须升进度指纹版本** —— 网页版 `learn-word-match.html` 的')
    L.append('> `dataSignature()`、小程序 `utils/bank.js` 的 `signature()`，两处同步改。')
    L.append('> 只改归属、关数不变时也要改版本（「第 N 关」指向的内容变了，光看数字识别不出来）。')
    L.append('')
    L.append('> ⚠️ **别顺手重跑词库生成链**：本项目的释义有人工修订，重跑会覆盖。')
    L.append('> 只重建四六级（`build_cet_words.py`）是安全的，它只读 `cet-themes.tsv` + 源清单。')
    L.append('')
    open(OUT_TAXONOMY, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
    print('  写出 %s（%d 字节）' % (OUT_TAXONOMY, os.path.getsize(OUT_TAXONOMY)))


def write_order(tree, parent, order, books, rows_of):
    L = []
    L.append('# 四六级关卡顺序与域分布（2026-09-27）')
    L.append('')
    L.append('> **生成物，别手改**：`python3 scripts/learn/gen_cet_domain_docs.py` 重生成。')
    L.append('>')
    L.append('> 本文用途：词库改动只动顺序或分类时，最需要核对的是「某一关现在是什么」。')
    L.append('> 下面能查到每个细域散落在哪些关、以及前 60 关逐关的主题与单词。')
    L.append('> 域体系本身（131 个细域怎么来的）见 `docs/cet-domain-taxonomy.md`。')
    L.append('')

    # 概览
    L.append('## 一、结果概览')
    L.append('')
    L.append('| 册 | 关数 | 词数 | 用到的细域 | 最长同类连排 | 前 100 关覆盖域数 | 兜底域占比 |')
    L.append('|---|---:|---:|---:|---:|---:|---:|')
    for bid in BOOKS:
        th = books[bid]['themes']
        bases = [base_theme(t['name']) for t in th]
        run, prev, mx = 0, None, 0
        for b in bases:
            run = run + 1 if b == prev else 1
            mx = max(mx, run)
            prev = b
        fb = sum(len(t['words']) for t, b in zip(th, bases) if parent.get(b) == FALLBACK_PARENT)
        words = sum(len(t['words']) for t in th)
        L.append('| %s（%s） | %d | %d | %d | %d 关 | %d | %.2f%% |'
                 % (bid, books[bid]['label'], len(th), words, len(set(bases)), mx,
                    len(set(bases[:100])), fb * 100.0 / words))
    L.append('')
    L.append('> 交错规则：同一细域**最多连排 %d 关**，其余互相穿插（块级 SWRR，见'
             ' `docs/cet-semantic-themes-plan.md` 第八节）。' % MAX_SAME_DOMAIN_RUN)
    L.append('> 词条与释义零变化：产物词条多重集 == 源清单（两册 7508 / 5651 条）。')
    L.append('')
    L.append('> 进度指纹版本：网页版与小程序的 `dataSignature()` / `signature()` **都是 `v3-`**')
    L.append('> （域体系换代 + 关数变化，旧进度须作废）。')
    L.append('')

    # 逐域位置
    for k, bid in enumerate(BOOKS, 2):
        th = books[bid]['themes']
        bases = [base_theme(t['name']) for t in th]
        where = collections.defaultdict(list)
        for i, b in enumerate(bases, 1):
            where[b].append(i)
        L.append('## %s、%s 逐域位置（按关数降序）'
                 % ('二三'[k - 2], books[bid]['label']))
        L.append('')
        L.append('| 细域 | 父域 | 关数 | 分几段 | 出现在哪些关 |')
        L.append('|---|---|---:|---:|---|')
        for b, _ in sorted(where.items(), key=lambda kv: (-len(kv[1]), kv[0])):
            text, segs = spans(where[b])
            L.append('| %s | %s | %d | %d | %s |' % (b, parent[b], len(where[b]), segs, text))
        L.append('')

    # 前 60 关
    for k, bid in enumerate(BOOKS, 4):
        th = books[bid]['themes']
        L.append('## %s、%s 前 60 关逐关' % ('四五'[k - 4], books[bid]['label']))
        L.append('')
        L.append('<details><summary>展开 %s 前 60 关（主题 + 单词）</summary>' % books[bid]['label'])
        L.append('')
        L.append('| 关 | 主题 | 单词 |')
        L.append('|---:|---|---|')
        for i, t in enumerate(th[:60], 1):
            L.append('| %d | %s | %s |'
                     % (i, t['name'], ' · '.join(w[0] for w in t['words'])))
        L.append('')
        L.append('</details>')
        L.append('')

    L.append('## 六、怎么验证')
    L.append('')
    L.append('```bash')
    L.append('# 一条命令查全部不变量：域体系自洽 / 粒度达标 / 产物与源清单一致 / 三副本一致')
    L.append('python3 scripts/learn/validate_cet_domains.py')
    L.append('')
    L.append('# 单测（闸门，不用 Mockito）')
    L.append('mvn test -Dtest=CetWordBankTest,WordMatchBankTest \\')
    L.append('    -Dsurefire.failIfNoSpecifiedTests=false')
    L.append('')
    L.append('# 词库与静态页都打进 jar，要看到新主题必须重新打包 + 重启')
    L.append('STORY_DB_PATH=/Users/renfufei/LLM_ALL/STORY_DB/data ./start_web.sh')
    L.append('')
    L.append('# 线上交互级回归（需要服务已在跑）')
    L.append('node scripts/check_word_match.mjs      # S6c：关数 / 细域 / 粒度 / 关内英文不重复')
    L.append('node scripts/probe_cet_export.mjs      # 单机件导出')
    L.append('```')
    L.append('')
    L.append('## 七、怎么回滚')
    L.append('')
    L.append('改动只落在四个文件上，按顺序回退即可：')
    L.append('')
    L.append('| 文件 | 回滚方式 |')
    L.append('|---|---|')
    L.append('| `src/test/resources/learn/cet-words-source/cet-themes.tsv` | 恢复上一版的第 3 列（域归属） |')
    L.append('| `src/test/resources/learn/cet-words-source/cet-domain-tree.tsv` | 恢复上一版 / 删除（删了要同时回退构建脚本） |')
    L.append('| `src/main/resources/learn/cet-words.json` | 重跑 `build_cet_words.py` |')
    L.append('| `word-match-miniprogram/data/levels-college.js` | 重跑 `dump_word_match_payload.py` + `build_miniprogram_data.py` |')
    L.append('')
    L.append('> 进度指纹版本**只升不降**：回滚后旧进度同样作废，这是预期行为。')
    L.append('> 想保留一份现场再动手，先把这四个文件复制到 `.workbuddy/backup/<日期>-<名字>/`。')
    L.append('')
    open(OUT_ORDER, 'w', encoding='utf-8').write('\n'.join(L) + '\n')
    print('  写出 %s（%d 字节）' % (OUT_ORDER, os.path.getsize(OUT_ORDER)))


if __name__ == '__main__':
    sys.exit(main())

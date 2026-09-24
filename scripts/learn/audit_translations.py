#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
全量翻译审计：给 PEP 24 册 + CET 2 册 共 20191 条逐条打标。

分级：
  ok      —— 通过
  mech    —— 纯机械格式问题（全角标点后多余空格、首尾空白、尾部标点），可脚本零判断修复
  rewrite —— 需要语义判断后重写（词典式长释义、义项堆叠、省略号、某人某物、地域标注、释义含英文）

输出：
  docs/translation-review/_audit.json     机器可读全量数据
  docs/translation-review/_audit_summary.md  汇总统计

用法：
  python3 scripts/learn/audit_translations.py
"""
import json
import os
import re
import sys
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PEP = os.path.join(ROOT, "src/main/resources/learn/pep-words.json")
CET = os.path.join(ROOT, "src/main/resources/learn/cet-words.json")
OUTDIR = os.path.join(ROOT, "docs/translation-review")

# ── 清洗规则 ──────────────────────────────────────────────────────────
# 上游 CET 数据在部分全角标点后带一个半角空格（如「逗…乐， 给…娱乐」），属格式噪音
RE_SPACE_AFTER_PUNCT = re.compile(r"([，；、。：])\s+")
RE_TAIL_PUNCT = re.compile(r"[，。；、：]+$")
RE_HEAD_SPACE = re.compile(r"^\s+|\s+$")

# 词性前缀（允许 interjection. 这类长前缀）
# ⚠️ 必须**不锚定在开头**：CET 条目常是「n. 潜力 adj. 有可能的」这种「前缀 + 中文 + 前缀 + 中文」，
#    只剥开头的正则会让后面的 `adj.` 被当成「释义含英文」。
#    实测漏这个 ⇒ 799 条里 716 条是误报。
_POS = (r"n|v|vt|vi|adj|adv|prep|conj|pron|art|num|int|aux|abbr|interjection|"
        r"pl|sing|compar|superl|det|modal")
POS_PREFIX = re.compile(
    r"(?:^|(?<=[\s；，、]))(?:" + _POS + r")(?:,(?:" + _POS + r"))*\.\s*",
    re.I,
)


def strip_pos(s: str) -> str:
    """剥掉**所有**位置的词性前缀（含 `adv,prep.` 这种逗号连写）。
    只剥开头会把后续的 `vt.` 误判成「释义含英文」。"""
    prev = None
    while prev != s:
        prev = s
        s = POS_PREFIX.sub("", s)
    return s


RE_ELLIPSIS = re.compile(r"[…]|\.\.\.")
RE_PLACEHOLDER = re.compile(r"某人|某物|某地|某事|某时|某处")
# 合法占位符：整条释义就是「某人/某处」这类，是正确翻译（someone / somewhere），不是冗余
WHOLE_PLACEHOLDER = re.compile(r"^(某人|某物|某地|某事|某时|某处|某事物|某人某物)$")
RE_REGION = re.compile(r"（[英美]）|\([英美]\)")
RE_ASCII_WORD = re.compile(r"[A-Za-z]{2,}")
# 上游残留：占位符文案、音标方括号、阿拉伯数字
RE_UPSTREAM_JUNK = re.compile(r"中文释义|音标|\[[^\]]{2,}\]|\d")

# 长度阈值：超过即认为「词典式长释义」，需要精简
LEN_SOFT = 14          # >14 字进 rewrite
LEN_HARD = 16          # >16 字一定会被校验器打回
SEMI_MAX = 2           # 「；」>=2 即 3 个以上义项


def mech_fix(zh: str) -> str:
    """零判断的机械清洗，返回清洗后的字符串。"""
    s = zh.strip()
    s = RE_SPACE_AFTER_PUNCT.sub(r"\1", s)
    s = re.sub(r"([，；、。：])\s*([（(])", r"\1\2", s)
    s = RE_TAIL_PUNCT.sub("", s)
    s = RE_HEAD_SPACE.sub("", s)
    return s


def classify(zh: str):
    """返回 (level, flags)。level ∈ ok/mech/rewrite"""
    raw = zh
    flags = []

    # 先看纯机械项
    mech_hit = []
    if RE_SPACE_AFTER_PUNCT.search(raw):
        mech_hit.append("标点后空格")
    if RE_TAIL_PUNCT.search(raw):
        mech_hit.append("尾部标点")
    if raw != raw.strip():
        mech_hit.append("首尾空白")

    fixed = mech_fix(raw)

    # 再看语义项（在机械清洗后的串上判断）
    sem_hit = []
    if RE_UPSTREAM_JUNK.search(fixed):
        sem_hit.append("上游残留")
    # 省略号要分情况：`on → 在…上` 是标准写法，必须保留；
    # 但「给…发电报」「对…进行民意测验」里的占位符是冗余，要去掉。
    # 判据：短（≤7 字）的方位类搭配视为合法，其余标记待处理。
    if RE_ELLIPSIS.search(fixed) and len(fixed) > 7:
        sem_hit.append("省略号冗余")
    if RE_PLACEHOLDER.search(fixed) and not WHOLE_PLACEHOLDER.match(fixed):
        sem_hit.append("冗余某人某物")
    if RE_REGION.search(fixed):
        sem_hit.append("地域标注")
    core = strip_pos(fixed)
    # 剥掉全部词性前缀后仍有连续英文 → 释义里真的混了英文
    if RE_ASCII_WORD.search(core):
        sem_hit.append("释义含英文")
    if len(fixed) > LEN_SOFT:
        sem_hit.append("过长")
    if fixed.count("；") >= SEMI_MAX:
        sem_hit.append("义项过多")
    if not fixed.strip():
        sem_hit.append("空释义")

    flags = mech_hit + sem_hit
    if sem_hit:
        level = "rewrite"
    elif mech_hit:
        level = "mech"
    else:
        level = "ok"
    return level, flags, fixed


def load(path):
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    rows = []
    for bk in d["books"]:
        for ti, th in enumerate(bk["themes"]):
            for wi, w in enumerate(th["words"]):
                rows.append(
                    {
                        "book": bk["id"],
                        "label": bk["label"],
                        "stage": bk["stage"],
                        "theme": th["name"],
                        "themeIdx": ti,
                        "wordIdx": wi,
                        "en": w[0],
                        "zh": w[1],
                    }
                )
    return rows


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    rows = []
    for src, kind in ((PEP, "pep"), (CET, "cet")):
        loaded = load(src)
        for r in loaded:
            r["src"] = kind
        rows.extend(loaded)

    lv = Counter()
    flagc = Counter()
    for r in rows:
        level, flags, fixed = classify(r["zh"])
        r["level"] = level
        r["flags"] = flags
        r["zhMechFixed"] = fixed
        if fixed != r["zh"]:
            r["mechChanged"] = True
        lv[level] += 1
        flagc.update(flags)

    total = len(rows)
    bybook = defaultdict(lambda: Counter())
    for r in rows:
        bybook[(r["book"], r["label"], r["stage"])][r["level"]] += 1
        bybook[(r["book"], r["label"], r["stage"])]["total"] += 1

    audit = {
        "total": total,
        "levels": dict(lv),
        "flags": dict(flagc),
        "rows": rows,
    }
    with open(os.path.join(OUTDIR, "_audit.json"), "w", encoding="utf-8") as f:
        json.dump(audit, f, ensure_ascii=False, indent=1)

    lines = []
    lines.append("# 全量翻译审计汇总\n")
    lines.append(f"总计 **{total}** 条\n")
    lines.append("| 级别 | 条数 | 占比 |")
    lines.append("|---|---|---|")
    for k in ("ok", "mech", "rewrite"):
        lines.append(f"| {k} | {lv[k]} | {100*lv[k]/total:.1f}% |")
    lines.append("")
    lines.append("| 问题类型 | 条数 |")
    lines.append("|---|---|")
    for k, v in flagc.most_common():
        lines.append(f"| {k} | {v} |")
    lines.append("")
    lines.append("| 册 | 学段 | 总数 | 通过 | 仅格式 | 需重写 | 需重写占比 |")
    lines.append("|---|---|---|---|---|---|---|")
    for (b, l, s), c in bybook.items():
        rw = c["rewrite"]
        lines.append(
            f"| {b} | {s} | {c['total']} | {c['ok']} | {c['mech']} | {rw} | {100*rw/c['total']:.1f}% |"
        )
    with open(os.path.join(OUTDIR, "_audit_summary.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print(f"total={total} ok={lv['ok']} mech={lv['mech']} rewrite={lv['rewrite']}")
    print("flags:", dict(flagc))
    print("written:", os.path.join(OUTDIR, "_audit.json"))


if __name__ == "__main__":
    sys.exit(main())

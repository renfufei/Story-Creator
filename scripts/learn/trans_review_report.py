#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 _changes.json 生成 26 份分册校对记录 + 总览 README。

用法：
  python3 scripts/learn/trans_review_report.py
"""
import collections
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "docs/translation-review")

BOOK_ORDER = [
    "pep-3-1", "pep-3-2", "pep-4-1", "pep-4-2", "pep-5-1", "pep-5-2",
    "pep-6-1", "pep-6-2", "pep-7-1", "pep-7-2", "pep-8-1", "pep-8-2", "pep-9-1",
    "pep-h-1", "pep-h-2", "pep-h-3", "pep-h-4", "pep-h-5", "pep-h-6",
    "pep-h-7", "pep-h-8", "pep-h-9", "pep-h-10", "pep-h-11",
    "cet-4", "cet-6",
]

KIND_LABEL = {"agent": "代理改写", "manual": "人工覆盖", "mech": "格式清洗"}
KIND_ORDER = ["agent", "manual", "mech"]

BACKUP = ".workbuddy/backup/trans-review-20260923-235230"


def md_escape(s):
    return (s or "").replace("|", "\\|").replace("\n", " ")


def main():
    audit = json.load(open(os.path.join(OUT, "_audit.json"), encoding="utf-8"))
    rows = audit["rows"]
    for i, r in enumerate(rows):
        r["idx"] = i

    chpath = os.path.join(OUT, "_changes.json")
    if not os.path.exists(chpath):
        print("缺少 _changes.json，先跑 trans_review_apply.py")
        return 1
    changes = json.load(open(chpath, encoding="utf-8"))

    rejected = []
    rp = os.path.join(OUT, "_rejected.json")
    if os.path.exists(rp):
        rejected = json.load(open(rp, encoding="utf-8"))

    by_book_changes = collections.defaultdict(list)
    for c in changes:
        by_book_changes[c["book"]].append(c)
    by_book_rows = collections.defaultdict(list)
    for r in rows:
        by_book_rows[r["book"]].append(r)

    # ── 分册文档 ──────────────────────────────────────────────
    n_written = 0
    for bid in BOOK_ORDER:
        rs = by_book_rows.get(bid)
        if not rs:
            continue
        cs = sorted(by_book_changes.get(bid, []), key=lambda c: c["idx"])
        label, stage = rs[0]["label"], rs[0]["stage"]
        n = len(rs)
        kc = collections.Counter(c["kind"] for c in cs)

        flagc = collections.Counter()
        for c in cs:
            for f in c["flags"]:
                flagc[f] += 1

        L = []
        L.append(f"# {label}（`{bid}`）· 中文释义校对记录\n")
        L.append(f"> 学段：**{stage}** · 全册 **{n}** 条 · 本次改动 **{len(cs)}** 条"
                 f"（{100 * len(cs) / n:.1f}%）· 未改动 **{n - len(cs)}** 条\n")
        if cs:
            parts = [f"{KIND_LABEL[k]} **{kc[k]}** 条" for k in KIND_ORDER if kc.get(k)]
            L.append("> 改动构成：" + " · ".join(parts) + "\n")
        L.append(f"> 回滚：原始三副本已备份于 `{BACKUP}/`，按下方对照表反向替换即可。\n")

        if not cs:
            L.append("\n**本册无改动** —— 全部条目在校对口径下已达标"
                     "（无冗长、无义项堆积、无上游残留、无标点空格问题）。\n")
        else:
            L.append("\n## 改动触发的问题类型\n")
            L.append("| 问题类型 | 条数 |")
            L.append("|---|---|")
            for k, v in flagc.most_common():
                L.append(f"| {k} | {v} |")
            if not flagc:
                L.append("| （人工覆盖，无机器标记） | 0 |")
            L.append("")

            # 人工覆盖单列，便于核对
            manual = [c for c in cs if c["kind"] == "manual"]
            if manual:
                L.append("## 人工覆盖条目（需重点核对）\n")
                L.append("| # | 主题 | 英文 | 改前 | 改后 | 理由 |")
                L.append("|---|---|---|---|---|---|")
                for k, c in enumerate(manual, 1):
                    L.append(f"| M{k} | {md_escape(c['theme'])} | `{md_escape(c['en'])}` | "
                             f"{md_escape(c['old'])} | **{md_escape(c['new'])}** | "
                             f"{md_escape(c.get('why'))} |")
                L.append("")

            L.append("## 改动明细（old → new）\n")
            L.append("> 按词库顺序排列；`why` 为改动理由，`来源` 区分代理改写 / 格式清洗。\n")
            L.append("| # | 主题 | 英文 | 改前 | 改后 | 理由 | 来源 |")
            L.append("|---|---|---|---|---|---|---|")
            for k, c in enumerate(cs, 1):
                L.append(f"| {k} | {md_escape(c['theme'])} | `{md_escape(c['en'])}` | "
                         f"{md_escape(c['old'])} | **{md_escape(c['new'])}** | "
                         f"{md_escape(c.get('why'))} | {KIND_LABEL.get(c['kind'], c['kind'])} |")
            L.append("")

        with open(os.path.join(OUT, f"{bid}.md"), "w", encoding="utf-8") as f:
            f.write("\n".join(L) + "\n")
        n_written += 1

    # ── 总览 ─────────────────────────────────────────────────
    rows_sum = []
    tot_n = tot_c = 0
    tot_kind = collections.Counter()
    for bid in BOOK_ORDER:
        rs = by_book_rows.get(bid)
        if not rs:
            continue
        cs = by_book_changes.get(bid, [])
        n = len(rs)
        tot_n += n
        tot_c += len(cs)
        for c in cs:
            tot_kind[c["kind"]] += 1
        rows_sum.append((bid, rs[0]["label"], rs[0]["stage"], n, len(cs), 100 * len(cs) / n))

    L = []
    L.append("# 26 册单词中文释义校对 · 总览\n")
    L.append("> 校对口径与执行流程见 [PLAN.md](./PLAN.md)；每册明细见同目录 `<册号>.md`。\n")
    L.append(f"**全量 {tot_n} 条，改动 {tot_c} 条（{100 * tot_c / tot_n:.1f}%），"
             f"未改动 {tot_n - tot_c} 条。**\n")
    L.append("改动构成：" + " · ".join(
        f"{KIND_LABEL[k]} {tot_kind[k]} 条" for k in KIND_ORDER if tot_kind.get(k)) + "\n")

    L.append("## 各册改动量\n")
    L.append("| 册 | 学段 | 全册条数 | 改动条数 | 改动率 | 明细 |")
    L.append("|---|---|---|---|---|---|")
    for bid, label, stage, n, c, p in rows_sum:
        L.append(f"| {label} `{bid}` | {stage} | {n} | {c} | {p:.1f}% | [{bid}.md](./{bid}.md) |")
    L.append("")

    if rejected:
        rc = collections.Counter(x[3] for x in rejected)
        L.append("## 被硬校验打回的条目\n")
        L.append(f"共 **{len(rejected)}** 条 —— 这些条目**保留了原文**（宁可少改，不可改错）。\n")
        L.append("| 原因 | 条数 |")
        L.append("|---|---|")
        for k, v in rc.most_common():
            L.append(f"| {k} | {v} |")
        L.append("")
    else:
        L.append("## 被硬校验打回的条目\n")
        L.append("**0 条** —— 所有改写建议均通过长度 / 英文残留 / 词性前缀 / 占位文案 / 册内中文唯一性等硬约束。\n")

    L.append("## 生效方式\n")
    L.append("改动落在 `src/main/resources/learn/*.json` 与测试源清单、小程序快照三处。"
             "网页端需**重新打包 + 重启服务**后生效：\n")
    L.append("```bash\n"
             "STORY_DB_PATH=/Users/renfufei/LLM_ALL/STORY_DB/data ./only_start_web.sh   # 已打包过\n"
             "# 或完整重建：\n"
             "STORY_DB_PATH=/Users/renfufei/LLM_ALL/STORY_DB/data ./start_web.sh\n"
             "```\n")
    L.append(f"回滚：`{BACKUP}/` 保存了改动前的两个 JSON、两套源清单与小程序 `data/`。\n")

    with open(os.path.join(OUT, "README.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")

    print(f"生成 {n_written} 份分册文档 + README.md")
    print(f"总条数 {tot_n}，改动 {tot_c}（{100 * tot_c / tot_n:.1f}%）")
    print("改动构成：" + " · ".join(
        f"{KIND_LABEL[k]} {tot_kind[k]}" for k in KIND_ORDER if tot_kind.get(k)))
    return 0


if __name__ == "__main__":
    sys.exit(main())

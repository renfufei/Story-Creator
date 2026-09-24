#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把批次打包成「代理工作包」（group）：一个 group = 一个子代理要处理的一串批次文件。

  R 队列（需重写）：12 批 × 15 条 = 180 条/group   —— 需要真判断，密度压低保质量
  C 队列（复核确认）：8 批 × 100 条 = 800 条/group —— 只扫误译，密度可高

输出：
  /tmp/transrev/groups/G-<n>.json   代理工作包
  /tmp/transrev/groups/_groups.md   人类可读清单

用法：
  python3 scripts/learn/trans_review_groups.py
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WORK = os.environ.get("TRANSREV_WORK", "/tmp/transrev")
BATCHDIR = os.path.join(WORK, "batches")
GROUPDIR = os.path.join(WORK, "groups")

PER_GROUP = {"R": 12, "C": 24}


def main():
    with open(os.path.join(BATCHDIR, "index.json"), encoding="utf-8") as f:
        index = json.load(f)
    os.makedirs(GROUPDIR, exist_ok=True)
    for fn in os.listdir(GROUPDIR):
        if fn.endswith(".json"):
            os.remove(os.path.join(GROUPDIR, fn))

    def key_of(kind, book):
        """CET 两册各成一池（单册量够大，便于并行）；PEP 24 册合成一池（单册批数太少）。"""
        if book.startswith("cet"):
            return book, book
        return "pep", "人教版 24 册"

    groups = []
    for kind in ("R", "C"):
        pools = {}
        for e in index:
            if e["kind"] == kind:
                k, label = key_of(kind, e["book"])
                pools.setdefault(k, (label, []))[1].append(e)
        for k in sorted(pools):
            label, files = pools[k]
            n = PER_GROUP[kind]
            for gi in range(0, len(files), n):
                chunk = files[gi:gi + n]
                groups.append({
                    "group": f"G-{kind}-{k}-{gi // n + 1:02d}",
                    "kind": kind,
                    "book": k if k.startswith("cet") else "pep(多册)",
                    "label": label,
                    "batches": [c["batch"] for c in chunk],
                    "entries": sum(c["size"] for c in chunk),
                })

    for g in groups:
        with open(os.path.join(GROUPDIR, g["group"] + ".json"), "w", encoding="utf-8") as f:
            json.dump(g, f, ensure_ascii=False, indent=1)

    lines = ["# 代理工作包清单", ""]
    for kind, title in (("R", "R 队列 · 需重写（15 条/批）"), ("C", "C 队列 · 复核确认（100 条/批）")):
        sub = [g for g in groups if g["kind"] == kind]
        lines.append(f"## {title} —— {len(sub)} 个 group，{sum(g['entries'] for g in sub)} 条")
        lines.append("")
        lines.append("| group | 册 | 批数 | 条数 |")
        lines.append("|---|---|---|---|")
        for g in sub:
            lines.append(f"| {g['group']} | {g['label']} | {len(g['batches'])} | {g['entries']} |")
        lines.append("")
    with open(os.path.join(GROUPDIR, "_groups.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    print(f"groups: {len(groups)}")
    for kind in ("R", "C"):
        sub = [g for g in groups if g["kind"] == kind]
        print(f"  {kind}: {len(sub)} groups, {sum(g['entries'] for g in sub)} entries")
    print("R groups:", " ".join(g["group"] for g in groups if g["kind"] == "R"))
    print()
    print("C groups:", " ".join(g["group"] for g in groups if g["kind"] == "C"))


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按批次切分待校对条目。

两个队列：
  R 队列（rewrite，需语义判断）—— 15 条/批，对齐用户「每批 10~20 条」的要求
  C 队列（ok/mech，复核确认）  —— 100 条/批，只用于扫出误译，保守少改

输出到 WORK 目录：
  batches/R-<book>-<n>.json   每批一个文件
  batches/C-<book>-<n>.json
  batches/index.json          清单

用法：
  python3 scripts/learn/trans_review_batches.py
"""
import json
import os
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
AUDIT = os.path.join(ROOT, "docs/translation-review/_audit.json")
WORK = os.environ.get("TRANSREV_WORK", "/tmp/transrev")
BATCHDIR = os.path.join(WORK, "batches")

R_SIZE = 15    # rewrite 队列批大小
C_SIZE = 100   # confirm 队列批大小


def chunk(seq, n):
    for i in range(0, len(seq), n):
        yield seq[i:i + n]


def main():
    with open(AUDIT, encoding="utf-8") as f:
        audit = json.load(f)
    rows = audit["rows"]
    # 索引化，供后续 apply 回写
    for i, r in enumerate(rows):
        r["idx"] = i

    os.makedirs(BATCHDIR, exist_ok=True)
    # 清掉旧批次
    for fn in os.listdir(BATCHDIR):
        if fn.endswith(".json"):
            os.remove(os.path.join(BATCHDIR, fn))

    index = []

    def emit(kind, book, label, n, items):
        name = f"{kind}-{book}-{n:03d}"
        payload = {
            "batch": name,
            "kind": kind,
            "book": book,
            "label": label,
            "size": len(items),
            "items": [
                {"i": it["idx"], "en": it["en"], "zh": it["zh"], "clean": it["zhMechFixed"],
                 "flags": it["flags"], "theme": it["theme"]}
                for it in items
            ],
        }
        with open(os.path.join(BATCHDIR, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=1)
        index.append({"batch": name, "kind": kind, "book": book, "label": label,
                      "size": len(items), "indices": [it["idx"] for it in items]})

    for kind, size, lvset in (("R", R_SIZE, {"rewrite"}), ("C", C_SIZE, {"ok", "mech"})):
        bybook = defaultdict(list)
        for r in rows:
            if r["level"] in lvset:
                bybook[(r["book"], r["label"])].append(r)
        for (book, label), items in bybook.items():
            for n, ch in enumerate(chunk(items, size), 1):
                emit(kind, book, label, n, ch)

    with open(os.path.join(BATCHDIR, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)

    stat = defaultdict(lambda: defaultdict(int))
    for e in index:
        stat[e["kind"]][e["book"]] += 1
    print("batch dir:", BATCHDIR)
    for kind in ("R", "C"):
        print(f"== {kind} 队列 ==")
        tot = 0
        for b, c in stat[kind].items():
            print(f"   {b:9s} {c:4d} 批")
            tot += c
        print(f"   合计 {tot} 批")
    print("total batches:", len(index))


if __name__ == "__main__":
    sys.exit(main())

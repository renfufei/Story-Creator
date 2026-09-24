#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
修正两处产物：

1. `_audit.json` / `_audit_summary.md` 是 **修复 `strip_pos()` 之前**跑的，
   里面 `释义含英文` 有 716 条误报（`n. 潜力 adj. 有可能的` 这类多词性前缀被当成英文残留）。
   → 换成用**改动前的备份** + 修好的审计器重跑，得到正确的「改动前画像」。

2. `_changes.json` 的 `flags` 沿用了上面那份误报（775 条里 692 条是误报），
   而分册报告的「改动触发的问题类型」表正是取这个字段。
   → 用修好的 `classify()` 对每条改动的 **old 文本**重新判定。

用法：
  python3 scripts/learn/trans_review_reflags.py
"""
import importlib.util
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUTDIR = os.path.join(ROOT, "docs/translation-review")
BACKUP = os.path.join(ROOT, ".workbuddy/backup/trans-review-20260923-235230")


def load_module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    audit_mod = load_module(
        os.path.join(ROOT, "scripts/learn/audit_translations.py"), "audit_translations")

    # ── 1. 用改动前的备份重跑审计 ──────────────────────────────
    bak_pep = os.path.join(BACKUP, "pep-words.json")
    bak_cet = os.path.join(BACKUP, "cet-words.json")
    if not (os.path.exists(bak_pep) and os.path.exists(bak_cet)):
        print("缺少备份 JSON，无法重建原始画像：", BACKUP)
        return 1
    audit_mod.PEP = bak_pep
    audit_mod.CET = bak_cet
    audit_mod.OUTDIR = OUTDIR
    audit_mod.main()

    # 给汇总表加醒目标注：这是改动前的快照
    sp = os.path.join(OUTDIR, "_audit_summary.md")
    body = open(sp, encoding="utf-8").read()
    note = ("# 全量翻译审计汇总（**改动前快照**）\n\n"
            "> 数据源：`.workbuddy/backup/trans-review-20260923-235230/`（改动前的两个词库 JSON）。\n"
            "> 本表描述的是**修订前**的问题分布，用于对照改动量。\n")
    body = body.replace("# 全量翻译审计汇总\n", note, 1)
    open(sp, "w", encoding="utf-8").write(body)

    # ── 2. 重算 _changes.json 的 flags ─────────────────────────
    cp = os.path.join(OUTDIR, "_changes.json")
    changes = json.load(open(cp, encoding="utf-8"))
    before = {}
    changed_n = 0
    for c in changes:
        old_flags = list(c.get("flags") or [])
        _, flags, _ = audit_mod.classify(c["old"])
        if flags != old_flags:
            changed_n += 1
        for f in old_flags:
            before[f] = before.get(f, 0) + 1
        c["flags"] = flags
    json.dump(changes, open(cp, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)

    import collections
    after = collections.Counter()
    for c in changes:
        after.update(c["flags"])

    print("\n── _changes.json flags 已重算 ──")
    print("受影响条目 %d / %d" % (changed_n, len(changes)))
    print("%-12s %8s %8s" % ("问题类型", "重算前", "重算后"))
    for k in sorted(set(before) | set(after),
                    key=lambda x: -(after.get(x, 0) + before.get(x, 0))):
        print("%-12s %8d %8d" % (k, before.get(k, 0), after.get(k, 0)))
    return 0


if __name__ == "__main__":
    sys.exit(main())

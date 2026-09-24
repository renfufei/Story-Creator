#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验代理输出并写回三份副本。

三副本关系（2026-09-23 实测确认）：
  ① src/main/resources/learn/{pep,cet}-words.json   —— 服务读的产物；**权威顺序**
  ② src/test/resources/learn/*-source/*.txt         —— 源清单；是 ① 的**乱序排列**（CET 有 4565 行带首尾空格）
  ③ word-match-miniprogram/data/levels-*.js         —— 端上快照；与 ① **同序同内容**（实测 100% 一致）

因此写回策略：
  ① 按下标写（audit rows 的 idx）
  ② 用「(英文, 旧中文) 多重集」映射写（因为顺序不同、且有重复词条）
  ③ 按下标写（与 ① 同序）

用法：
  python3 scripts/learn/trans_review_apply.py --dry-run     # 只校验，不落盘
  python3 scripts/learn/trans_review_apply.py --write       # 校验 + 落盘
"""
import argparse
import collections
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUTDIR = os.path.join(ROOT, "docs/translation-review")
AUDIT = os.path.join(OUTDIR, "_audit.json")
WORK = os.environ.get("TRANSREV_WORK", "/tmp/transrev")
BATCHDIR = os.path.join(WORK, "batches")
FIXDIR = os.path.join(WORK, "out")

PEP_JSON = os.path.join(ROOT, "src/main/resources/learn/pep-words.json")
CET_JSON = os.path.join(ROOT, "src/main/resources/learn/cet-words.json")
SRC_DIRS = {
    "pep": os.path.join(ROOT, "src/test/resources/learn/pep-words-source"),
    "cet": os.path.join(ROOT, "src/test/resources/learn/cet-words-source"),
}
MP_DIR = os.path.join(ROOT, "word-match-miniprogram/data")
MP_FILES = {
    "primary": "levels-primary.js", "junior": "levels-junior.js",
    "senior": "levels-senior.js", "college": "levels-college.js",
}

LEN_MAX = 16          # 硬上限（>16 一律打回）
LEN_WARN = 15         # 超过只告警
# ⚠️ 词性前缀会**多次出现**（`n. 信号 v. 发信号`），不能只剥开头一个——
# 只剥开头会把第二个 `v.` 误判成「释义含英文」（曾因此误打回 450 条）。
POS_RE = re.compile(r"^(?:[A-Za-z]{1,12}\.\s*)+")
POS_ANY = re.compile(r"(?:^|(?<=[\s；，、]))([A-Za-z]{1,12})\.\s*")
RE_ASCII = re.compile(r"[A-Za-z]")
RE_REGION = re.compile(r"（[英美]）|\([英美]\)")
RE_JUNK = re.compile(r"[\[\]\t]|中文释义|释义[:：]|\d")
RE_SPACE_AFTER_PUNCT = re.compile(r"[，；、。：]\s")
RE_TAIL = re.compile(r"[，。；、：]+$")
RE_ELLIPSIS = re.compile(r"[…]|\.\.\.")
RE_PLACEHOLDER = re.compile(r"某人|某物|某地|某事|某时|某处")
WHOLE_PLACEHOLDER = re.compile(r"^(某人|某物|某地|某事|某时|某处|某事物|某人某物)$")
# 省略号在**简短**条目里是标准词典写法（`由…组成`、`以…为特征`、`在…上`），必须放行；
# 只有它出现在仍嫌啰嗦的长条目里才说明没精简干净。
ELLIPSIS_OK_LEN = 15
PLACEHOLDER_OK_LEN = 8
# 允许保留的含字母特例：卡拉OK 是通行译法
ALLOW_ASCII = ["卡拉OK"]
# 允许保留的方括号用法：过时/冒犯性标注，用户明确要保留这层提示
ALLOW_LITERAL = ["[过时]"]


def validate(zh, old_clean, book, is_cet, allow_ellipsis_short=True):
    """返回 (ok, reason)。"""
    if not zh or not zh.strip():
        return False, "空释义"
    if zh != zh.strip():
        return False, "首尾空白"
    if len(zh) > LEN_MAX:
        return False, f"超长({len(zh)})"
    if RE_SPACE_AFTER_PUNCT.search(zh):
        return False, "标点后空格"
    if RE_TAIL.search(zh):
        return False, "尾部标点"
    if RE_REGION.search(zh):
        return False, "地域标注"
    junk_probe = zh
    for a in ALLOW_LITERAL:
        junk_probe = junk_probe.replace(a, "")
    if RE_JUNK.search(junk_probe):
        return False, "残留噪音(括号/方括号/制表/数字/占位文案)"
    body = zh
    for a in ALLOW_ASCII:
        body = body.replace(a, "")
    # 剥掉**任意位置**的词性前缀后不许再有字母
    body = POS_ANY.sub("", body)
    if RE_ASCII.search(body):
        return False, "释义含英文"
    if is_cet and not POS_RE.match(zh):
        return False, "CET 缺词性前缀"
    if (not is_cet) and POS_RE.match(zh):
        return False, "PEP 不应带词性前缀"
    if RE_ELLIPSIS.search(zh) and len(zh) > ELLIPSIS_OK_LEN:
        return False, f"省略号冗余(长{len(zh)})"
    if RE_PLACEHOLDER.search(zh) and not WHOLE_PLACEHOLDER.match(zh) \
            and len(zh) > PLACEHOLDER_OK_LEN:
        return False, f"冗余某人某物(长{len(zh)})"
    if zh == old_clean:
        return False, "无变化(no-op)"
    return True, "ok"


def load_audit():
    with open(AUDIT, encoding="utf-8") as f:
        return json.load(f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help="实际落盘（默认 dry-run）")
    ap.add_argument("--no-mp", action="store_true", help="跳过小程序数据")
    ap.add_argument("--no-src", action="store_true", help="跳过源清单")
    args = ap.parse_args()

    audit = load_audit()
    rows = audit["rows"]
    for i, r in enumerate(rows):
        r["idx"] = i

    # 批次 → 允许的下标集合
    allowed = {}
    for fn in os.listdir(BATCHDIR):
        if fn.endswith(".json") and fn != "index.json":
            d = json.load(open(os.path.join(BATCHDIR, fn), encoding="utf-8"))
            allowed[d["batch"]] = {it["i"] for it in d["items"]}

    accepted = {}      # idx -> (newzh, why, batch)
    rejected = []      # (batch, i, zh, reason)
    missing_batches = []
    n_of = collections.Counter()

    for batch, idxs in sorted(allowed.items()):
        fp = os.path.join(FIXDIR, batch + ".json")
        if not os.path.exists(fp):
            missing_batches.append(batch)
            continue
        try:
            d = json.load(open(fp, encoding="utf-8"))
        except Exception as e:
            rejected.append((batch, "-", "-", f"JSON 解析失败: {e}"))
            continue
        for fx in d.get("fixes", []):
            i = fx.get("i")
            zh = (fx.get("zh") or "").strip()
            if i not in idxs:
                rejected.append((batch, i, zh, "i 不在该批次内"))
                continue
            r = rows[i]
            ok, why = validate(zh, r["zhMechFixed"], r["book"],
                               r["book"].startswith("cet"))
            if not ok:
                rejected.append((batch, i, zh, why))
                continue
            accepted[i] = (zh, fx.get("why", ""), batch)
            n_of[r["book"]] += 1

    # ── 组装最终中文 ────────────────────────────────────────────────
    # 人工覆盖表优先于代理输出（用于纠正代理的个别误判）
    overrides = {}
    op = os.path.join(WORK, "overrides.json")
    if os.path.exists(op):
        for o in json.load(open(op, encoding="utf-8")):
            overrides[o["idx"]] = o
        print(f"人工覆盖 {len(overrides)} 条："
              + ", ".join(f'{o.get("en")}→{o["zh"]}' for o in overrides.values()))

    final = []
    for r in rows:
        if r["idx"] in overrides:
            final.append(overrides[r["idx"]]["zh"])
        elif r["idx"] in accepted:
            final.append(accepted[r["idx"]][0])
        else:
            final.append(r["zhMechFixed"])

    # ── 变更清单（用于生成分册文档）─────────────────────────────────
    changes = []
    for r in rows:
        new = final[r["idx"]]
        if new == r["zh"]:
            continue
        if r["idx"] in overrides:
            kind, why = "manual", overrides[r["idx"]]["why"]
        elif r["idx"] in accepted:
            kind, why = "agent", accepted[r["idx"]][1]
        else:
            kind, why = "mech", "格式清洗"
        changes.append({
            "book": r["book"], "label": r["label"], "stage": r["stage"],
            "theme": r["theme"], "idx": r["idx"], "en": r["en"],
            "old": r["zh"], "new": new,
            "why": why, "kind": kind, "flags": r["flags"],
        })

    print(f"代理输出文件：{len(allowed) - len(missing_batches)}/{len(allowed)} 批已交付")
    if missing_batches:
        print(f"⚠️ 未交付批次 {len(missing_batches)}：{missing_batches[:8]}"
              f"{' ...' if len(missing_batches) > 8 else ''}")
    print(f"接受 {len(accepted)} 条 / 打回 {len(rejected)} 条")
    rc = collections.Counter(x[3] for x in rejected)
    for k, v in rc.most_common():
        print(f"   打回原因 {k}: {v}")
    print(f"总变更 {len(changes)} 条（其中代理改写 {sum(1 for c in changes if c['kind']=='agent')}，"
          f"纯格式清洗 {sum(1 for c in changes if c['kind']=='mech')}）")

    if not args.write:
        print("\n[dry-run] 未落盘。加 --write 执行。")
        with open(os.path.join(OUTDIR, "_changes_dryrun.json"), "w", encoding="utf-8") as f:
            json.dump(changes, f, ensure_ascii=False, indent=1)
        with open(os.path.join(OUTDIR, "_rejected_dryrun.json"), "w", encoding="utf-8") as f:
            json.dump(rejected, f, ensure_ascii=False, indent=1)
        return 0

    # ── ① 写 JSON ───────────────────────────────────────────────────
    cursor = 0
    for path, src in ((PEP_JSON, "pep"), (CET_JSON, "cet")):
        doc = json.load(open(path, encoding="utf-8"))
        for bk in doc["books"]:
            for t in bk["themes"]:
                for w in t["words"]:
                    w[1] = final[cursor]
                    cursor += 1
        assert cursor == len(rows) or src == "pep"
        with open(path, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=1)
            f.write("\n")
        print("写入", os.path.relpath(path, ROOT))

    # ── ② 写源清单（按 (en, 旧中文) 多重集映射）────────────────────
    if not args.no_src:
        bybook_new = collections.defaultdict(lambda: collections.defaultdict(collections.deque))
        for r in rows:
            bybook_new[r["book"]][(r["en"], r["zh"])].append(final[r["idx"]])
        for kind, sdir in SRC_DIRS.items():
            for fn in sorted(os.listdir(sdir)):
                if not fn.endswith(".txt"):
                    continue
                bid = fn[:-4]
                path = os.path.join(sdir, fn)
                lines = [l for l in open(path, encoding="utf-8").read().split("\n") if l.strip()]
                out, unmatched = [], 0
                for l in lines:
                    en, _, zh = l.partition("\t")
                    q = bybook_new[bid].get((en, zh.strip()))
                    if q:
                        out.append(f"{en}\t{q.popleft()}")
                    else:
                        out.append(f"{en}\t{zh.strip()}")
                        unmatched += 1
                if unmatched:
                    print(f"   ⚠️ {bid}: {unmatched} 行未匹配到 JSON 条目（保持原样）")

                # 一致性修复：把源清单的多重集**对齐**到 JSON。
                # 用于收掉历史上的漂移（如 pep-7-1 的 grandma：源清单「祖母」/ JSON「奶奶」）。
                target = collections.Counter(
                    (r["en"], final[r["idx"]]) for r in rows if r["book"] == bid)
                cur = collections.Counter(tuple(x.split("\t", 1)) for x in out)
                need, excess = target - cur, cur - target
                if need or excess:
                    bexc, bneed = collections.defaultdict(list), collections.defaultdict(list)
                    for (en, zh), c in excess.items():
                        bexc[en].extend([zh] * c)
                    for (en, zh), c in need.items():
                        bneed[en].extend([zh] * c)
                    rep = collections.defaultdict(collections.deque)
                    for en, olds in bexc.items():
                        news = bneed.get(en, [])
                        for k, o in enumerate(olds):
                            if k < len(news):
                                rep[(en, o)].append(news[k])
                    fixed = 0
                    for k, x in enumerate(out):
                        en, _, zh = x.partition("\t")
                        q = rep.get((en, zh))
                        if q:
                            out[k] = f"{en}\t{q.popleft()}"
                            fixed += 1
                    left = sum(len(v) for v in rep.values())
                    print(f"   ↻ {bid}: 对齐源清单 {fixed} 行"
                          + (f"（仍有 {left} 行无法按英文配对，保留原样）" if left else ""))

                with open(path, "w", encoding="utf-8") as f:
                    f.write("\n".join(out) + "\n")
        print("写入源清单 txt（同时清掉首尾空格）")

    # ── ③ 写小程序数据（与 JSON 同序，按下标）───────────────────────
    if not args.no_mp:
        bybook_final = collections.defaultdict(list)
        for r in rows:
            bybook_final[r["book"]].append(final[r["idx"]])
        for tag, fn in MP_FILES.items():
            path = os.path.join(MP_DIR, fn)
            raw = open(path, encoding="utf-8").read()
            head_end = raw.index("module.exports = ")
            head = raw[:head_end]
            payload = raw[head_end + len("module.exports = "):].rstrip().rstrip(";")
            data = json.loads(payload)
            cur = collections.Counter()
            for bid, themes in data.items():
                if bid not in bybook_final:
                    continue
                base = 0
                for th in themes:
                    for pr in th["p"]:
                        pr[1] = bybook_final[bid][base]
                        base += 1
            with open(path, "w", encoding="utf-8") as f:
                f.write(head + "module.exports = "
                        + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + ";\n")
            print("写入", os.path.relpath(path, ROOT))

    with open(os.path.join(OUTDIR, "_changes.json"), "w", encoding="utf-8") as f:
        json.dump(changes, f, ensure_ascii=False, indent=1)
    with open(os.path.join(OUTDIR, "_rejected.json"), "w", encoding="utf-8") as f:
        json.dump(rejected, f, ensure_ascii=False, indent=1)
    print("变更清单 →", os.path.relpath(os.path.join(OUTDIR, "_changes.json"), ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())

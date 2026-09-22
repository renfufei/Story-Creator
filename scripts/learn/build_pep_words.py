#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v5 重建 pep-words.json：在 v3（13 册 / 3155 词）基础上加入高中 11 册。

核心不变（零丢失 / 同义组同关 / 特殊主题 / 就近回填），两处差异：
  ① 源清单 24 册（/tmp/pep_lists_v4）
  ② 新册（高中）没有历史主题骨架 → 按课本单元播种（每册 5 单元，词表顺序均分，主题名 Unit 1..5）
规则集分册选择：
  * 老册（当前词库已有）→ 严格沿用 v3 规则，保证 13 册零回归
  * 新册（高中）→ 扩展规则（补高中不规则动词表 / 功能词 / 专名线索）
"""
import io, os, json, re, collections

REPO = "/Users/renfufei/GITHUB_ALL/Story-Creator"
CUR = os.path.join(REPO, "src/main/resources/learn/pep-words.json")
SRC = "/tmp/pep_lists_v4"
OUT = "/tmp/pep-words.v3.json"

MIN_PAIRS, MAX_PAIRS, TARGET_PAIRS = 3, 7, 6
# 同义组「装箱」上限：一组同义词最多并进几个主题（**与每关词数是两回事**）。
# 这个值参与主题编排，改动会重排词库主题 → 老册会整体回归，故单独命名、保持 5 不动。
SYN_BUCKET_MAX = 5
UNITS_PER_BOOK = 5

# ---------------- v3 规则（老册沿用，一字不改） ----------------
V3_IRR_V = {"went", "ate", "saw", "took", "bought", "came", "did", "had", "made", "gave", "knew", "ran",
            "got", "said", "told", "found", "left", "felt", "kept", "sold", "taught", "thought",
            "brought", "caught", "drank", "drove", "flew", "forgot", "grew", "heard", "held", "hung",
            "lost", "meant", "met", "paid", "put", "read", "rode", "sang", "sat", "slept", "spoke",
            "spent", "stood", "swam", "wore", "won", "wrote", "began", "broke", "built", "chose", "cut",
            "drew", "fell", "fought", "hid", "hit", "hurt", "led", "lay", "lent", "let", "lit", "rose",
            "shut", "sank", "shook", "shone", "shot", "showed", "shown", "sprang", "stole", "stuck",
            "swept", "threw", "understood", "woke", "wound"}
V3_IRR_N = {"children", "feet", "teeth", "mice", "men", "women", "geese", "police", "people", "oxen"}
V3_IRR_SKIP = {"left", "set"}
V3_IRR_A = {"better", "best", "worse", "worst", "more", "most", "less", "least", "farther",
            "farthest", "further", "furthest", "elder", "eldest"}
V3_FUNC = {"a", "an", "the", "this", "that", "these", "those", "it", "its", "i", "me", "my", "mine",
           "you", "your", "yours", "he", "him", "his", "she", "her", "hers", "we", "us", "our", "ours",
           "they", "them", "their", "theirs", "am", "is", "are", "be", "was", "were", "do", "does",
           "did", "have", "has", "had", "not", "no", "yes", "will", "would", "shall", "should", "can",
           "could", "may", "might", "must", "need", "and", "but", "or", "so", "because", "if", "when",
           "while", "than", "then", "also", "too", "very", "all", "both", "each", "every", "some",
           "any", "few", "other", "another", "such", "own", "same"}
V3_NAME_RE = re.compile(r"(男名|女名|人名|姓氏|作家|国家|城市|地区|节日|首都|政府|组织|机构|公司|品牌|协会|缩写|缩略)")

# ---------------- 高中扩展规则 ----------------
HS_IRR_V = V3_IRR_V | {
    "arisen", "awoken", "borne", "beaten", "bent", "bitten", "bled", "blown", "bred", "broadcast",
    "burnt", "cast", "crept", "dealt", "dug", "fed", "fled", "flown", "forgiven", "frozen", "gotten",
    "ground", "grown", "laid", "leaned", "leapt", "learnt", "lain", "overcame", "overcome", "quit",
    "rang", "risen", "sewn", "shaken", "shattered", "shed", "shrank", "shrunk", "slid", "sought",
    "sped", "spilt", "spoil", "spread", "sprung", "stung", "struck", "swollen", "swore", "sworn",
    "swung", "tore", "torn", "undertook", "undertaken", "wept", "withdrew", "withdrawn", "cost",
    "dealt", "fought", "frozen", "sunk", "trod", "trodden", "wove", "woven"}
HS_IRR_N = V3_IRR_N | {"bacteria", "crises", "criteria", "fungi", "phenomena", "analyses", "media",
                       "data", "cattle", "species", "series", "means"}
# 高中里这些词形「同形不同义」，不该归到不规则变化（found=建立 / upset=心烦的 / lying=躺·说谎）
HS_IRR_SKIP = V3_IRR_SKIP | {"found", "upset", "lying", "wound", "content", "present", "close",
                             "minute", "separate", "desert", "record", "object", "subject", "project"}
HS_FUNC = V3_FUNC | {"neither", "nor", "either", "whether", "unless", "until", "although", "though",
                     "however", "therefore", "besides", "otherwise", "moreover", "thus", "whom",
                     "whose", "what", "which", "who", "where", "why", "how", "there", "here",
                     "itself", "himself", "herself", "myself", "yourself", "themselves", "ourselves"}
HS_NAME_RE = re.compile(r"(男名|女名|人名|姓氏|作家|国家|城市|地区|节日|首都|政府|组织|机构|公司|品牌|协会|"
                        r"缩写|缩略|州|省|岛屿|河流|山脉|语言|民族|部落|王朝)")

FRAME_RE = re.compile(r"\bsb\b|\bsth\b|one's|\bsb's\b", re.I)

THEME_IRR = "不规则变化"
THEME_NAME = "专有名词（人名与地名）"
THEME_GREET = "问候与日常用语"
THEME_FUNC = "常用功能词（代词/冠词/助动词）"
THEME_FRAME = "句型框架与常用搭配"
SYN_PREFIX = "同义表达："


def rules_for(is_new):
    if is_new:
        return HS_IRR_V, HS_IRR_N, HS_IRR_A_SET, HS_IRR_SKIP, HS_FUNC, HS_NAME_RE
    return V3_IRR_V, V3_IRR_N, V3_IRR_A, V3_IRR_SKIP, V3_FUNC, V3_NAME_RE


V3_IRR_A = {"better", "best", "worse", "worst", "more", "most", "less", "least", "farther",
            "farthest", "further", "furthest", "elder", "eldest"}
HS_IRR_A_SET = V3_IRR_A


def nk(s):
    return re.sub(r"\s+", " ", (s or "").strip().lower()).rstrip(".")


def split_balanced(n):
    """与 WordMatchBank.splitBalanced 严格一致：每关 3~7 对，围绕 6 对为目标。

    只影响「报告里的关数」，不参与主题编排（主题用 SYN_BUCKET_MAX）。"""
    if n <= 0:
        return []
    if n <= MAX_PAIRS:
        return [n]
    # 关卡数取两约束较大者：ceil(n/MAX) 不超上限，round(n/TARGET) 贴近目标
    groups = max(-(-n // MAX_PAIRS), int(n / TARGET_PAIRS + 0.5))
    groups = min(groups, n)
    base, rem = divmod(n, groups)
    return [base + (1 if i < rem else 0) for i in range(groups)]


def seed_from_units(words):
    n = len(words)
    if n < UNITS_PER_BOOK * MIN_PAIRS:
        return collections.OrderedDict([("Unit 1", [en for en, zh in words])])
    base, rem = divmod(n, UNITS_PER_BOOK)
    out = collections.OrderedDict()
    cur = 0
    for i in range(UNITS_PER_BOOK):
        size = base + (1 if i < rem else 0)
        out["Unit %d" % (i + 1)] = [en for en, zh in words[cur:cur + size]]
        cur += size
    return out


def load_source():
    books = json.load(io.open(os.path.join(SRC, "_books.json"), encoding="utf-8"))
    out = []
    for b in books:
        rows = [json.loads(l) for l in
                io.open(os.path.join(SRC, "_raw", b["id"] + ".jsonl"), encoding="utf-8") if l.strip()]
        out.append((b, rows))
    return out


def main():
    cur = json.load(io.open(CUR, encoding="utf-8"))
    cur_by_book = {b["id"]: b for b in cur["books"]}
    result, report, new_books = [], [], []
    for b, rows in load_source():
        bid = b["id"]
        words = [(r["en"], r["zh"]) for r in rows]
        zh_of = {en: zh for en, zh in words}
        pos = {nk(en): i for i, (en, zh) in enumerate(words)}
        src_en = {nk(en): en for en, zh in words}
        old = cur_by_book.get(bid)
        is_new = old is None
        IRR_V, IRR_N, IRR_A, IRR_SKIP, FUNC, NAME_RE = rules_for(is_new)

        if not is_new:
            seed = collections.OrderedDict(
                (t["name"], [en for en, zz in t["words"] if nk(en) in src_en])
                for t in old["themes"])
            seed = collections.OrderedDict((n, v) for n, v in seed.items() if v)
        else:
            seed = seed_from_units(words)
            new_books.append((bid, b["label"], len(words), len(seed)))

        # ① 同义组
        by_zh = collections.OrderedDict()
        for en, zh in words:
            by_zh.setdefault(zh, []).append(en)
        syn = {zh: ens for zh, ens in by_zh.items() if len(ens) > 1}
        syn_keys = set()
        for ens in syn.values():
            syn_keys.update(nk(x) for x in ens)

        # ② 特殊类别
        special = collections.OrderedDict((t, []) for t in
                                          (THEME_IRR, THEME_NAME, THEME_GREET, THEME_FUNC, THEME_FRAME))
        for r in rows:
            en, zh, raw, k = r["en"], r["zh"], r["raw"], nk(r["en"])
            if k in syn_keys:
                continue
            if k in IRR_SKIP:
                pass
            elif k in IRR_V or k in IRR_N or k in IRR_A:
                special[THEME_IRR].append((en, zh))
            elif en[:1].isupper() and NAME_RE.search(raw):
                special[THEME_NAME].append((en, zh))
            elif "？" in zh or "！" in zh or "?" in en or "表示祝愿" in raw:
                special[THEME_GREET].append((en, zh))
            elif k in FUNC and en.islower():
                special[THEME_FUNC].append((en, zh))
            elif "..." in en or FRAME_RE.search(raw):
                special[THEME_FRAME].append((en, zh))
        special = collections.OrderedDict((t, v) for t, v in special.items() if len(v) >= MIN_PAIRS)
        taken = set()
        for v in special.values():
            taken.update(nk(en) for en, zh in v)

        # ③ 剩余词 → 就近主题
        theme_words = collections.OrderedDict()
        anchors, assigned = {}, set()
        for tname, ens in seed.items():
            ps = [pos[nk(en)] for en in ens if nk(en) in pos]
            keep = [src_en[nk(en)] for en in ens
                    if nk(en) in src_en and nk(en) not in taken and nk(en) not in syn_keys]
            if ps:
                anchors[tname] = ps
            if keep:
                theme_words[tname] = keep
                assigned.update(nk(en) for en in keep)

        left = [w for w in words if nk(w[0]) not in taken and nk(w[0]) not in syn_keys
                and nk(w[0]) not in assigned]
        order = list(theme_words)
        for en, zh in left:
            p = pos[nk(en)]
            cands = [n for n in theme_words if anchors.get(n)]
            best, best_d = None, None
            for n in cands:
                d = min(abs(p - q) for q in anchors[n])
                if best_d is None or d < best_d:
                    best, best_d = n, d
            theme_words[best or order[0]].append(en)
        for n in theme_words:
            theme_words[n].sort(key=lambda e: pos[nk(e)])

        # ④ 同义组打包
        bins = []
        for zh, ens in sorted(syn.items(), key=lambda kv: -len(kv[1])):
            for bn in bins:
                if bn["n"] + len(ens) <= SYN_BUCKET_MAX:
                    bn["zhs"].append(zh)
                    bn["n"] += len(ens)
                    break
            else:
                bins.append({"zhs": [zh], "extra": [], "n": len(ens)})
        singles = [w for w in words if nk(w[0]) not in taken and nk(w[0]) not in syn_keys]
        singles.sort(key=lambda w: pos[nk(w[0])])
        used_pos = set()
        for bn in bins:
            total = sum(len(syn[z]) for z in bn["zhs"])
            anchors_p = [pos[nk(en)] for z in bn["zhs"] for en in by_zh[z]] if bn["zhs"] else []
            while total < MIN_PAIRS:
                cand = None
                for w in singles:
                    if nk(w[0]) in used_pos:
                        continue
                    d = min(abs(pos[nk(w[0])] - q) for q in anchors_p) if anchors_p else 0
                    if cand is None or d < cand[0]:
                        cand = (d, w)
                if cand is None:
                    break
                bn["extra"].append(cand[1])
                used_pos.add(nk(cand[1][0]))
                anchors_p.append(pos[nk(cand[1][0])])
                total += 1
        keep_bins = []
        for bn in bins:
            if sum(len(syn[z]) for z in bn["zhs"]) + len(bn["extra"]) >= MIN_PAIRS:
                keep_bins.append(bn)
            else:
                for z in bn["zhs"]:
                    report.append("%s：同义组「%s」词数不足，退回语义主题" % (bid, z))
        for bn in keep_bins:
            for en, zh in bn["extra"]:
                for n in list(theme_words):
                    theme_words[n] = [x for x in theme_words[n] if nk(x) != nk(en)]
        theme_words = collections.OrderedDict((n, v) for n, v in theme_words.items() if v)

        # ⑤ 主题不足 3 词并入相邻
        names = list(theme_words)
        for i, n in enumerate(names):
            if 0 < len(theme_words[n]) < MIN_PAIRS:
                target = names[i - 1] if i > 0 else (names[1] if len(names) > 1 else None)
                if target:
                    theme_words[target] = sorted(theme_words[target] + theme_words[n],
                                                 key=lambda e: pos[nk(e)])
                    theme_words[n] = []
                    report.append("%s：主题「%s」词数不足，已并入「%s」" % (bid, n, target))
        theme_words = collections.OrderedDict((n, v) for n, v in theme_words.items() if v)

        # ⑥ 组装
        themes = [{"name": n, "words": [[en, zh_of[en]] for en in ens]}
                  for n, ens in theme_words.items()]
        for n, v in special.items():
            themes.append({"name": n, "words": [[en, zh] for en, zh in v]})
        for bn in keep_bins:
            labels = list(bn["zhs"]) + [zh for en, zh in bn["extra"]]
            label = "、".join(labels)
            if len(label) > 14:
                label = "、".join(labels[:2]) + " 等"
            w = []
            for z in bn["zhs"]:
                w.extend([[en, z] for en in by_zh[z]])
            w.extend([[en, zh] for en, zh in bn["extra"]])
            themes.append({"name": SYN_PREFIX + label, "words": w})

        # ⑦ 硬校验
        flat = [en for t in themes for en, zh in t["words"]]
        src_all = [en for en, zh in words]
        assert len(set(nk(x) for x in flat)) == len(flat), "%s 有重复词条" % bid
        assert sorted(nk(x) for x in flat) == sorted(nk(x) for x in src_all), \
            "%s 词条不一致：库%d 源%d" % (bid, len(flat), len(src_all))
        levels = 0
        for t in themes:
            assert len(t["words"]) >= MIN_PAIRS, "%s 主题 %s 不足 3 词" % (bid, t["name"])
            levels += len(split_balanced(len(t["words"])))
            txt, i = t["words"], 0
            for size in split_balanced(len(txt)):
                chunk, i = txt[i:i + size], i + size
                zs = [z for _, z in chunk]
                if len(set(zs)) != len(zs):
                    assert t["name"].startswith(SYN_PREFIX), \
                        "%s 主题「%s」出现同关中文重复" % (bid, t["name"])
        result.append({"id": bid, "label": b["label"], "grade": b["grade"],
                       "semester": b["semester"], "stage": b["stage"], "themes": themes})
        report.append("✔ %-9s %-11s 词%4d 主题%3d 关%3d" % (bid, b["label"], len(flat), len(themes), levels))

    with io.open(OUT, "w", encoding="utf-8") as w:
        json.dump({"books": result}, w, ensure_ascii=False, indent=1)
    print("\n".join(report))
    print("\n新增册（按课本单元播种）:")
    for x in new_books:
        print("   %-10s %-8s 词%4d 单元%d" % x)
    tw = sum(len(t["words"]) for b in result for t in b["themes"])
    tl = sum(len(split_balanced(len(t["words"]))) for b in result for t in b["themes"])
    print("\n合计 %d 册 / %d 词 / %d 关 → %s (%.0fKB)"
          % (len(result), tw, tl, OUT, os.path.getsize(OUT) / 1024))


main()

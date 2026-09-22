#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v4 导出：把人教 PEP 24 册（小学8 + 初中5 + 高中11）从原始 JSONL 导出为「英文\t中文」源清单。

在 v3 基础上：
  * 读 /tmp/pep_books/<bookId>.jsonl（fetch.py 下载的原始词表）
  * 新增高中 11 册（必修1-5 + 选修6-11）
  * OCR 噪声截断规则收紧：先体检、再决定，避免误伤 iPhone 这类词
输出：/tmp/pep_lists_v4/<bookId>.txt 与 _raw/<bookId>.jsonl
"""
import io, json, os, re, glob

SRC = "/tmp/pep_books"
DST = "/tmp/pep_lists_v4"

# (bookId, 词库id, label, grade, semester, stage)
BOOKS = [
    ("PEPXiaoXue3_1", "pep-3-1", "三年级上册", 3, "上册", "小学"),
    ("PEPXiaoXue3_2", "pep-3-2", "三年级下册", 3, "下册", "小学"),
    ("PEPXiaoXue4_1", "pep-4-1", "四年级上册", 4, "上册", "小学"),
    ("PEPXiaoXue4_2", "pep-4-2", "四年级下册", 4, "下册", "小学"),
    ("PEPXiaoXue5_1", "pep-5-1", "五年级上册", 5, "上册", "小学"),
    ("PEPXiaoXue5_2", "pep-5-2", "五年级下册", 5, "下册", "小学"),
    ("PEPXiaoXue6_1", "pep-6-1", "六年级上册", 6, "上册", "小学"),
    ("PEPXiaoXue6_2", "pep-6-2", "六年级下册", 6, "下册", "小学"),
    ("PEPChuZhong7_1", "pep-7-1", "七年级上册", 7, "上册", "初中"),
    ("PEPChuZhong7_2", "pep-7-2", "七年级下册", 7, "下册", "初中"),
    ("PEPChuZhong8_1", "pep-8-1", "八年级上册", 8, "上册", "初中"),
    ("PEPChuZhong8_2", "pep-8-2", "八年级下册", 8, "下册", "初中"),
    ("PEPChuZhong9_1", "pep-9-1", "九年级全一册", 9, "全一册", "初中"),
    ("PEPGaoZhong_1", "pep-h-1", "必修1", 10, "必修", "高中"),
    ("PEPGaoZhong_2", "pep-h-2", "必修2", 10, "必修", "高中"),
    ("PEPGaoZhong_3", "pep-h-3", "必修3", 11, "必修", "高中"),
    ("PEPGaoZhong_4", "pep-h-4", "必修4", 11, "必修", "高中"),
    ("PEPGaoZhong_5", "pep-h-5", "必修5", 11, "必修", "高中"),
    ("PEPGaoZhong_6", "pep-h-6", "选修6", 12, "选修", "高中"),
    ("PEPGaoZhong_7", "pep-h-7", "选修7", 12, "选修", "高中"),
    ("PEPGaoZhong_8", "pep-h-8", "选修8", 12, "选修", "高中"),
    ("PEPGaoZhong_9", "pep-h-9", "选修9", 12, "选修", "高中"),
    ("PEPGaoZhong_10", "pep-h-10", "选修10", 12, "选修", "高中"),
    ("PEPGaoZhong_11", "pep-h-11", "选修11", 12, "选修", "高中"),
]

JUNK_EN = {"colombia vmbI".lower(), "vmbI".lower()}

ABBR = {"p.e.", "a.m.", "p.m.", "mrs.", "mr.", "mrs", "mr", "etc.", "u.s.", "u.k.", "s.o.s"}

POS = r"(?:n|v|vt|vi|adj|adv|prep|pron|conj|num|art|int|aux|abbr|modal\s+v|pl)"

MANUAL_ZH = {
    "the": "这个（特指）", "to": "到（表方向）", "have": "有", "be": "是",
    "anymore": "不再", "have a good time": "玩得开心", "primary": "初级的",
    "service": "公共服务", "watch": "手表", "level": "水平", "by": "乘（交通工具）",
    "in": "在…里", "it": "它", "what about ...": "怎么样？（提建议）",
}

MANUAL_FIX = {
    "sliver": ("silver", "银"),
    "marc": ("Marc", "马克"),
    # 词典源 OCR 错字（多了尾巴 "Upez"），邻居是西方人名主题
    "teresa lopez upez": ("Teresa Lopez", "特蕾莎•洛佩斯"),
}

# 这些「小写夹大写」的词是正常词形，不能被 OCR 截断规则砍掉
OCR_KEEP = {"iphone", "ipad", "ipod", "imac", "itunes", "youtube", "ebay", "mcDonald".lower()}


def clean_zh(raw: str) -> str:
    t = (raw or "").replace("\u3000", " ").strip()
    if not t:
        return ""
    t = re.sub(r"^\s*(中文释义|英文释义|释义|中文)\s*[:：]\s*", "", t)
    t = re.sub(r"^\s*" + POS + r"\.?\s*", "", t, flags=re.I)
    t = re.sub(r"^\s*" + POS + r"\.\s*", "", t, flags=re.I)
    t = re.sub(r"[.\u2026\u22ef]{2,}", "\u2026", t)

    def strip_par(s):
        return re.sub(r"[（(][^（()）]*[）)]", "", s)

    core = strip_par(t)
    if not re.search(r"[\u4e00-\u9fffA-Za-z]", core):
        t = re.sub(r"[（()）]", "", t)
    else:
        t = core
    t = t.replace(" ", "")
    t = re.sub(r"^[\s;；,，、.。:：\-—\u2026]+", "", t)
    t = re.sub(r"[;；]\s*$", "", t)
    for sep in ("；", ";", "，", ",", "、"):
        if sep in t:
            first = t.split(sep)[0].strip()
            if first:
                t = first
                break
    t = t.rstrip(".。 　")
    if not re.search(r"[\u4e00-\u9fffA-Za-z]", t):
        return ""
    if len(t) > 24:
        t = t[:24]
    return t


def clean_en(raw: str) -> str:
    s = (raw or "").replace("\u3000", " ").replace("\u2026", "...")
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"(?<=\w)\(", " (", s)
    s = re.sub(r"[（(][^（()）]*[）)]", "", s)
    s = s.replace("（", "").replace("）", "")
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"[!?。]+$", "", s).strip()
    low = s.lower()
    if low in ABBR:
        return s
    if re.fullmatch(r"[a-z]+\.", low):
        s = s[:-1]
    elif re.search(r"[a-z]{2,}\.$", s):
        s = s[:-1]
    m = re.match(r"^(.*?)\s+([A-Za-z]*[a-z][A-Za-z]*[A-Z][A-Za-z]*)$", s)
    if m and s.lower() not in OCR_KEEP:
        s = m.group(1)
    s = re.sub(r"\s*\.\.\.\s*", " ... ", s).strip()
    s = re.sub(r"\s+", " ", s).strip()
    return s


def transfer(o):
    w = o.get("content", {}).get("word", {})
    c = w.get("content", {}) or {}
    t = c.get("trans")
    if isinstance(t, list) and t:
        return t[0].get("tranCn", "") or ""
    return ""


def main():
    os.makedirs(DST, exist_ok=True)
    os.makedirs(os.path.join(DST, "_raw"), exist_ok=True)
    dropped, stat = [], []
    for folder, bid, label, grade, sem, stage in BOOKS:
        path = os.path.join(SRC, folder + ".jsonl")
        if not os.path.exists(path):
            raise SystemExit("缺 %s 的源文件" % path)
        rows, seen = [], set()
        for l in io.open(path, encoding="utf-8"):
            if not l.strip():
                continue
            o = json.loads(l)
            hw = (o.get("content", {}).get("word", {}).get("wordHead") or "").strip()
            raw = transfer(o)
            en = clean_en(hw)
            zh = clean_zh(raw)
            if en.lower() in MANUAL_FIX:
                en, zh = MANUAL_FIX[en.lower()]
            if en and en.lower() in MANUAL_ZH:
                zh = MANUAL_ZH[en.lower()]
            if not en or not zh:
                dropped.append((bid, hw, raw, "空英文/空释义"))
                continue
            if en.lower() in JUNK_EN:
                dropped.append((bid, hw, raw, "OCR 噪声"))
                continue
            k = en.lower()
            if k in seen:
                dropped.append((bid, hw, raw, "同册重复词形（保留首条）"))
                continue
            seen.add(k)
            rows.append((en, zh, raw))
        with io.open(os.path.join(DST, bid + ".txt"), "w", encoding="utf-8") as w:
            for en, zh, raw in rows:
                w.write("%s\t%s\n" % (en, zh))
        with io.open(os.path.join(DST, "_raw", bid + ".jsonl"), "w", encoding="utf-8") as w:
            for en, zh, raw in rows:
                w.write(json.dumps({"en": en, "zh": zh, "raw": raw}, ensure_ascii=False) + "\n")
        stat.append((bid, label, len(rows)))
        print("%-18s %-12s %4d" % (bid, label, len(rows)))

    total = sum(s[2] for s in stat)
    print("\n合计 %d 册 / %d 词条" % (len(stat), total))
    print("剔除 %d 条：" % len(dropped))
    with io.open(os.path.join(DST, "_dropped.txt"), "w", encoding="utf-8") as w:
        for bid, hw, raw, why in dropped:
            w.write("%-16s %-28s | %-40s | %s\n" % (bid, hw, raw[:40], why))
    with io.open(os.path.join(DST, "_books.json"), "w", encoding="utf-8") as w:
        w.write(json.dumps([{"folder": f, "id": i, "label": l, "grade": g, "semester": s, "stage": st}
                            for f, i, l, g, s, st in BOOKS], ensure_ascii=False, indent=2))


main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""单词库硬性质校验（长期闸门）。

用法: python3 scripts/validate_pep_words.py [pep-words.json 路径]

校验项与 WordMatchBank 的切关规则严格一致：
  1. 每册每个主题 ≥3 词（不足则不成关，会被丢弃）
  2. 切关后「同一关内」英文不重复；中文重复**只允许出现在「同义表达：」主题**里
     （同义关的中文本来就一模一样，前端按释义判定，选哪个都算对）
  3. 同一册内英文不重复
  4. 同一个中文在**同一关**里出现的词，必须构成一个同义组；同义组的成员必须全在同一关
  5. 英文词条不含中文、无首尾空格、无连续空格
  6. **覆盖率**：词库必须完整收录 src/test/resources/learn/pep-words-source/<册>.txt 的全部词条
     （「保证所有词汇都在」的长期闸门，源清单来自人教版分册词汇表）
  7. 册 id / 学段 / 年级字段完整，册次顺序为小学 → 初中 → 高中
"""
import io, json, math, sys, os, collections

MIN_PAIRS, MAX_PAIRS, TARGET_PAIRS = 3, 7, 6
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT = os.path.join(HERE, "..", "src", "main", "resources", "learn", "pep-words.json")
SOURCE_DIR = os.path.join(HERE, "..", "src", "test", "resources", "learn", "pep-words-source")
SYN_PREFIX = "同义表达："

# 每册都该有的特殊/不规则主题（抽查用：这些册确实存在该类词）
EXPECT_SPECIAL = {
    "pep-3-2": ["同义表达：", "常用功能词"],
    "pep-6-2": ["不规则变化", "同义表达："],
    "pep-7-1": ["问候与日常用语", "句型框架", "常用功能词", "同义表达："],
    "pep-8-1": ["不规则变化", "句型框架", "同义表达："],
    "pep-9-1": ["专有名词", "不规则变化", "句型框架", "同义表达："],
    # 高中：单元主题 + 专有名词（人名地名多）+ 同义表达
    "pep-h-1": ["Unit 1", "专有名词", "同义表达："],
    "pep-h-3": ["Unit 1", "专有名词", "同义表达："],
    "pep-h-11": ["Unit 1", "专有名词", "同义表达："],
}

# 学段出现顺序（册次选择器按它分组显示）
STAGE_ORDER = ["小学", "初中", "高中"]


def split_balanced(n):
    """与 WordMatchBank.splitBalanced 严格一致：3~7 对，围绕 6 对为目标。"""
    if n <= 0:
        return []
    if n <= MAX_PAIRS:
        return [n]
    # 关卡数取两约束的较大者：ceil(n/MAX) 保证不超上限，round(n/TARGET) 让每关贴近目标
    g = max(math.ceil(n / MAX_PAIRS), int(math.floor(n / TARGET_PAIRS + 0.5)))
    g = min(g, n)
    base, rem = divmod(n, g)
    return [base + (1 if i < rem else 0) for i in range(g)]


def cut_levels(words):
    """按 WordMatchBank 的规则把主题内的词顺序切成若干关。"""
    levels, pos = [], 0
    for size in split_balanced(len(words)):
        levels.append(words[pos:pos + size])
        pos += size
    return levels


def load_source(book_ids):
    out = {}
    for bid in book_ids:
        path = os.path.join(SOURCE_DIR, bid + ".txt")
        if not os.path.exists(path):
            continue
        rows = []
        for line in io.open(path, encoding="utf-8"):
            if not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 2:
                rows.append((parts[0], parts[1]))
        out[bid] = rows
    return out


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    root = json.load(io.open(path, encoding="utf-8"))
    errors, warns = [], []
    total_words = total_levels = 0
    stage_seq = []
    ids = [b["id"] for b in root.get("books", [])]
    source = load_source(ids)
    if not source:
        warns.append("未找到源清单目录 %s，跳过覆盖率校验" % SOURCE_DIR)

    for b in root.get("books", []):
        bid = b["id"]
        for key in ("id", "label", "grade", "semester", "stage"):
            if not b.get(key):
                errors.append("%s 缺少字段 %s" % (bid, key))
        if b.get("stage"):
            if not stage_seq or stage_seq[-1] != b["stage"]:
                stage_seq.append(b["stage"])
        book_en = collections.Counter()
        # zh -> {(关序号, 主题名): [en...]}：用于检查同义组是否落在同一关
        zh_levels = collections.defaultdict(lambda: collections.defaultdict(list))
        level_no = 0
        for th in b.get("themes", []):
            words = [(w[0], w[1]) for w in th.get("words", []) if len(w) >= 2]
            if len(words) < MIN_PAIRS:
                errors.append("%s / %s：只有 %d 词，不成关" % (bid, th["name"], len(words)))
            for en, zh in words:
                if not en or not zh:
                    errors.append("%s / %s：空词条" % (bid, th["name"]))
                    continue
                if en != en.strip() or "  " in en:
                    errors.append("%s / %s：英文空格异常 %r" % (bid, th["name"], en))
                if any("\u4e00" <= c <= "\u9fff" for c in en):
                    errors.append("%s / %s：英文含中文 %r" % (bid, th["name"], en))
                book_en[en.lower()] += 1
            for lv in cut_levels(words):
                ens = [w[0].lower() for w in lv]
                zhs = [w[1] for w in lv]
                if len(set(ens)) != len(ens):
                    errors.append("%s / %s：同一关英文重复 %s" % (bid, th["name"], ens))
                if len(set(zhs)) != len(zhs) and not th["name"].startswith(SYN_PREFIX):
                    errors.append("%s / %s：同一关中文重复（且不是同义表达主题）%s"
                                  % (bid, th["name"], zhs))
                for en, zh in lv:
                    zh_levels[zh][(level_no, th["name"])].append(en)
                total_levels += 1
                level_no += 1
            total_words += len(words)
        for en, n in book_en.items():
            if n > 1:
                errors.append("%s：英文 %s 在同册出现 %d 次" % (bid, en, n))
        # 同一中文出现在多处：要么同关（同义关，选哪个都对），要么分属不同关（彼此无歧义）
        # 但**同一主题内**必须同关，否则同义组会被拆散
        for zh, where in zh_levels.items():
            if len(where) < 2:
                continue
            themes_of_zh = set(t for _, t in where)
            if len(themes_of_zh) != len(where) and not all(
                    t.startswith(SYN_PREFIX) for _, t in where):
                errors.append("%s：中文「%s」同主题内被拆到多关：%s"
                              % (bid, zh, sorted(where)))
        # 特殊主题抽查
        names = [t["name"] for t in b.get("themes", [])]
        for want in EXPECT_SPECIAL.get(bid, []):
            if not any(n.startswith(want) for n in names):
                errors.append("%s：缺少特殊主题「%s」" % (bid, want))
        # 覆盖率
        if bid in source:
            src_zh = collections.Counter(en for en, zh in source[bid])
            bank_zh = collections.Counter()
            for th in b.get("themes", []):
                for w in th.get("words", []):
                    if len(w) >= 2:
                        bank_zh[w[0]] += 1
            missing = [en for en in src_zh if bank_zh[en] < src_zh[en]]
            extra = [en for en in bank_zh if bank_zh[en] > src_zh[en]]
            if missing:
                errors.append("%s：漏收 %d 个词 %s" % (bid, len(missing), missing[:8]))
            if extra:
                errors.append("%s：多出 %d 个词 %s" % (bid, len(extra), extra[:8]))

    if stage_seq != STAGE_ORDER:
        errors.append("学段顺序异常：期望 %s，实际 %s" % (STAGE_ORDER, stage_seq))

    print("册数 %d / 词数 %d / 关数 %d" % (len(root.get("books", [])), total_words, total_levels))
    print("学段顺序 %s" % stage_seq)
    for w in warns:
        print("⚠", w)
    for e in errors[:60]:
        print("✗", e)
    if len(errors) > 60:
        print("... 其余 %d 条省略" % (len(errors) - 60))
    print("== %s ==" % ("全部通过" if not errors else "发现 %d 个问题" % len(errors)))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())

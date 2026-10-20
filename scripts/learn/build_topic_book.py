#!/usr/bin/env python3
"""从 PEP 24 册词库抽取「专题练习」册（同类的、有序的一组词）。

为什么单独做一册：
  数字 / 序数 / 星期 / 月份 / 季节 / 时间，这些是**封闭的序列**，练习价值在「按顺序背下来」，
  所以这一册在页面上**左列英文保持原顺序、只打乱右列中文**（普通册两列都打乱）。

产出 `src/main/resources/learn/topic-words.json`，形状与 `pep-words.json` 完全一致
（`{books:[{id,label,grade,semester,stage,themes:[{name,words:[[en,zh]]}]}]}`），
`WordMatchBank` 直接按同一套逻辑加载切关。

用法：
    python3 scripts/learn/build_topic_book.py            # 只预览，不写文件
    python3 scripts/learn/build_topic_book.py --write    # 覆盖 topic-words.json

⚠️ 白名单里的 zh 是**显式指定**的：词库里同一个 en 可能有多条释义
   （`second`=第二 / `May`=五月·可能 / `March`=三月·行军 / `season`=季·季节），
   按 en 取「第一条」会取错。校验不通过时脚本直接报错退出，不会静默少词。
"""

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PEP = os.path.join(ROOT, "src/main/resources/learn/pep-words.json")
OUT = os.path.join(ROOT, "src/main/resources/learn/topic-words.json")

# 一个专题 = 一个 theme，词序即页面上左列（英文）的显示顺序。
# 每项 (en, zh)：zh 必须与 PEP 词库里该词**某一条**释义逐字相同。
TOPICS = [
    ("数字", [
        ("zero", "零"), ("one", "一"), ("two", "二"), ("three", "三"), ("four", "四"),
        ("five", "五"), ("six", "六"), ("seven", "七"), ("eight", "八"), ("nine", "九"),
        ("ten", "十"), ("eleven", "十一"), ("twelve", "十二"), ("thirteen", "十三"),
        ("fourteen", "十四"), ("fifteen", "十五"), ("sixteen", "十六"), ("seventeen", "十七"),
        ("eighteen", "十八"), ("nineteen", "十九"), ("twenty", "二十"),
        ("thirty", "三十"), ("forty", "四十"), ("fifty", "五十"), ("sixty", "六十"),
        ("seventy", "七十"), ("eighty", "八十"), ("ninety", "九十"),
        ("hundred", "一百"), ("thousand", "一千"), ("million", "百万"), ("billion", "十亿"),
        ("number", "号码"), ("half", "一半"), ("quarter", "四分之一"),
    ]),
    ("序数词", [
        ("first", "第一"), ("second", "第二"), ("third", "第三"), ("fourth", "第四"),
        ("fifth", "第五"), ("eighth", "第八"), ("ninth", "第九"),
        ("twelfth", "第十二"), ("twentieth", "第二十"), ("thirtieth", "第三十"),
    ]),
    ("星期与周", [
        ("Monday", "星期一"), ("Tuesday", "星期二"), ("Wednesday", "星期三"),
        ("Thursday", "星期四"), ("Friday", "星期五"), ("Saturday", "星期六"),
        ("Sunday", "星期日"), ("week", "周"), ("weekday", "工作日"),
        ("weekend", "周末"), ("weekly", "每周的"),
    ]),
    ("月份与年", [
        ("January", "一月"), ("February", "二月"), ("March", "三月"), ("April", "四月"),
        ("May", "五月"), ("June", "六月"), ("July", "七月"), ("August", "八月"),
        ("September", "九月"), ("October", "十月"), ("November", "十一月"),
        ("December", "十二月"), ("month", "月"), ("year", "年"),
    ]),
    ("季节", [
        ("spring", "春天"), ("summer", "夏天"), ("autumn", "秋天"),
        ("winter", "冬天"), ("season", "季节"), ("seasonal", "季节的"),
    ]),
    ("时间与时刻", [
        ("minute", "分钟"), ("hour", "小时"), ("day", "一天"), ("time", "时间"),
        ("clock", "时钟"), ("o'clock", "点钟"), ("past", "晚于"),
        ("century", "世纪"), ("decade", "十年"), ("daily", "每日的"),
    ]),
]


def build_index():
    """en(小写) → {zh: 词库里的原始拼写}；同一个词在不同册可能有不同释义。"""
    with open(PEP, encoding="utf-8") as f:
        data = json.load(f)
    index = {}
    for book in data["books"]:
        for theme in book["themes"]:
            for en, zh in theme["words"]:
                index.setdefault(en.strip().lower(), {}).setdefault(zh.strip(), en.strip())
    return index


def main():
    index = build_index()
    themes = []
    errors = []
    total = 0
    for name, words in TOPICS:
        out_words = []
        seen = set()
        for en, zh in words:
            key = en.lower()
            raw = index.get(key, {}).get(zh)
            if raw is None:
                near = sorted(index.get(key, {}).keys()) or ["（词库里没有这个词）"]
                errors.append(f"{name} / {en}={zh} —— 词库中不存在；该词现有释义：{'、'.join(near)}")
                continue
            if key in seen:
                errors.append(f"{name} / {en} 重复出现（同一专题内英文必须唯一）")
                continue
            seen.add(key)
            out_words.append([raw, zh])
        total += len(out_words)
        themes.append({"name": name, "words": out_words})

    if errors:
        print("❌ 校验失败，未写文件：", file=sys.stderr)
        for e in errors:
            print("  - " + e, file=sys.stderr)
        return 1

    for t in themes:
        print(f"{t['name']}: {len(t['words'])} 词")

    book = {
        "id": "pep-topic",
        "label": "序列配对",
        "grade": 0,
        "semester": "",
        "stage": "专题",
        "themes": themes,
    }
    data = {"books": [book]}

    if "--write" not in sys.argv:
        print(f"\n（预览模式，共 {total} 词；加 --write 才会写 {OUT}）")
        return 0

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"\n✅ 已写入 {OUT}（{len(themes)} 个专题 / {total} 词）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

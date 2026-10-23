#!/usr/bin/env python3
"""从 PEP 24 册词库抽取「序列配对」册（同类的、有序的一组词）。

为什么单独做一册：
  数字 / 序数 / 星期 / 月份 / 季节 / 时间 / 亲人关系 / 疑问词 / 人称代词，
  这些是**封闭的、有内在顺序的词群**，练习价值在「按顺序背下来」，
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

⚠️ 三元组 `(en, zh, display)` 的第三个元素 = **本册显示用的消歧文本**，
   只在「同一关里有两个词释义完全相同」时才需要（目前只有 who / whom 都是「谁」）。
   `zh` 仍然必须逐字命中词库某一条，它才是校验依据；`display` 只影响配对卡面上的中文，
   **不改动 PEP 词库本身**（`pep-words.json` 一个字都不动）。

⚠️ 三条自动断言（违反直接退出、不写文件）：
   ① 主题内英文唯一 —— 否则同一个词在一册里教两遍；
   ② 主题内**显示中文唯一** —— 一关里出现两张一模一样的中文卡，玩家无从选择，
      配对判定（`l.mean === r.text`）也会让「点错那张」反而算对；
   ③ 主题词数 ≥ 3 —— `WordMatchBank.load()` 对不足 3 词的主题直接 `continue`，
      连词一起丢（`MIN_PAIRS`）。
"""

import collections
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PEP = os.path.join(ROOT, "src/main/resources/learn/pep-words.json")
OUT = os.path.join(ROOT, "src/main/resources/learn/topic-words.json")

# 一个专题 = 一个 theme，词序即页面上左列（英文）的显示顺序。
# 每项 (en, zh) 或 (en, zh, display)：zh 必须与 PEP 词库里该词**某一条**释义逐字相同。
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
    # 亲人关系：不是封闭序列，但有一条天然的「辈分轴」——
    # 家（总纲）→ 祖辈 → 父母辈 → 夫妻 → 同辈 → 子辈 → 孙辈 → 旁系（长/同/幼）→ 亲属（收尾）。
    # 每一代内部统一「男 → 女」，再「正式 → 口语」（father/dad、grandfather/grandpa、mum），
    # 这样左列自上而下正好是一张家谱，顺序本身就是记忆线索。
    ("亲人关系", [
        ("family", "家"), ("parents", "父母"),
        ("grandfather", "祖父"), ("grandpa", "爷爷"),
        ("grandmother", "祖母"), ("grandma", "奶奶"), ("grandparent", "祖父母"),
        ("father", "父亲"), ("dad", "爸爸"), ("mother", "母亲"), ("mum", "妈妈"),
        ("couple", "夫妻"), ("husband", "丈夫"), ("wife", "妻子"),
        ("brother", "哥/弟"), ("sister", "姐/妹"), ("twin", "双胞胎之一"),
        ("son", "儿子"), ("daughter", "女儿"), ("child", "儿童"),
        ("kid", "孩子"), ("baby", "婴儿"),
        ("grandson", "孙子；外孙"), ("granddaughter", "孙女；外孙女"),
        ("uncle", "叔叔"), ("aunt", "姑母"),
        ("cousin", "同辈表亲"),
        ("nephew", "侄子/外甥"), ("niece", "侄女；外甥女"),
        ("relative", "亲属"),
    ]),
    # ==================== 疑问词（2026-10-22 新增） ====================
    # 按语法书的两个自然类别切，正好一关一类、各 5 对：
    #   疑问代词 = 用来「代」一个名词的（问事物 / 问选择 / 问人 / 问所属）
    #   疑问副词 = 用来「问状语」的（时间 / 地点 / 原因 / 方式 / 程度）
    # 左列顺序就是语法书里的讲解顺序：what → which → who → whom → whose，
    # when → where → why → how → how much（时间地点原因方式在前，问价钱收尾）。
    #
    # ⚠️ who 与 whom 词库释义都是「谁」。同一关里两张「谁」无法区分，
    #    所以给 whom 显示成「谁（宾格）」——这也是这一对词唯一的区别，顺带把「格」教了。
    ("疑问代词", [
        ("what", "什么"),
        ("which", "哪一个"),
        ("who", "谁"),
        ("whom", "谁", "谁（宾格）"),
        ("whose", "谁的"),
    ]),
    ("疑问副词", [
        ("when", "什么时候"),
        ("where", "在哪里"),
        ("why", "为什么"),
        ("how", "怎样"),
        ("how much", "多少钱"),
    ]),
    # ==================== 人称代词（2026-10-22 新增） ====================
    # ★ 为什么一个「格」一个主题，而不是把 I/me/my/mine 放一关：
    #   ① 同一主题内英文必须唯一，而 you / it / his 的**主格与宾格（或形物与名物）同形**，
    #      放在同一主题里这三个人称只能出现一次，等于半个格教不了；
    #   ② I/me/my/mine/myself 一关的中文是「我 / 我 / 我的 / 我的 / 我自己」，
    #      同关两张「我」两张「我的」⇒ 配对歧义，玩家只能瞎点；
    #   ③ 一关一个格，关内 7 个人称按「一单 → 一复 → 二 → 三单男 → 三单女 → 三单中 → 三复」
    #      排下来，左列自上而下就是课本后面那张人称代词表的一行 —— 这才是「序列配对」。
    #   练完主格再练宾格，玩家会看到「英文全换、中文一模一样」，格变位的体感就出来了。
    #
    # ⚠️ 认账的两处教材缺口（**不补词、不造释义**，练的永远是课本里有的）：
    #    · 宾格缺 her —— PEP 只收 `her = 她的`（形容词性物主），没收宾格义「她」；
    #    · 名物缺 his / its —— 它们与形物同形，教材不单列；
    #    · 反身缺 yourselves —— PEP 只收了 `yourself`，没有 `yourselves`。
    ("主格代词", [
        ("I", "我"), ("we", "我们"), ("you", "你"),
        ("he", "他"), ("she", "她"), ("it", "它"), ("they", "他们"),
    ]),
    ("宾格代词", [
        ("me", "我"), ("us", "我们"), ("you", "你"),
        ("him", "他"), ("it", "它"), ("them", "他们"),
    ]),
    ("形物代词", [
        ("my", "我的"), ("our", "我们的"), ("your", "你的"),
        ("his", "他的"), ("her", "她的"), ("its", "它的"), ("their", "他们的"),
    ]),
    ("名物代词", [
        ("mine", "我的"), ("ours", "我们的"), ("yours", "你的"),
        ("hers", "她的"), ("theirs", "他们的"),
    ]),
    # 反身代词：单数五个人称 → 复数（教材只收了 ourselves / themselves 两个复数）。
    # 7 对正好一关（MAX_PAIRS = 7），不必按单复数拆成 5+2（2 词不成关）。
    ("反身代词", [
        ("myself", "我自己"), ("yourself", "你自己"),
        ("himself", "他自己"), ("herself", "她自己"), ("itself", "它自己"),
        ("ourselves", "我们自己"), ("themselves", "他们自己"),
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
        seen_en = set()
        seen_zh = collections.Counter()
        for item in words:
            en, zh = item[0], item[1]
            display = item[2] if len(item) > 2 else zh
            key = en.lower()
            raw = index.get(key, {}).get(zh)
            if raw is None:
                near = sorted(index.get(key, {}).keys()) or ["（词库里没有这个词）"]
                errors.append(f"{name} / {en}={zh} —— 词库中不存在；该词现有释义：{'、'.join(near)}")
                continue
            if key in seen_en:
                errors.append(f"{name} / {en} 重复出现（同一专题内英文必须唯一）")
                continue
            seen_en.add(key)
            seen_zh[display] += 1
            out_words.append([raw, display])

        # 关内中文重复 = 配对歧义（见文件头断言②）
        for zh, n in seen_zh.items():
            if n > 1:
                errors.append(f"{name} / 中文「{zh}」出现 {n} 次 —— 同关两张一样的卡，玩家无从区分；"
                              f"请用三元组 (en, zh, display) 给其中一个消歧")
        # 不足 3 词的主题会被 WordMatchBank 静默丢弃（连词一起丢）
        if out_words and len(out_words) < 3:
            errors.append(f"{name} 只有 {len(out_words)} 个词（< 3），WordMatchBank 会整主题丢弃")

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

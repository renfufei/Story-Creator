# 四六级关卡顺序与域分布（2026-09-27）

> **生成物，别手改**：`python3 scripts/learn/gen_cet_domain_docs.py` 重生成。
>
> 本文用途：词库改动只动顺序或分类时，最需要核对的是「某一关现在是什么」。
> 下面能查到每个细域散落在哪些关、以及前 60 关逐关的主题与单词。
> 域体系本身（131 个细域怎么来的）见 `docs/cet-domain-taxonomy.md`。

## 一、结果概览

| 册 | 关数 | 词数 | 用到的细域 | 最长同类连排 | 前 100 关覆盖域数 | 兜底域占比 |
|---|---:|---:|---:|---:|---:|---:|
| cet-4（四级） | 1257 | 7508 | 130 | 5 关 | 21 | 1.40% |
| cet-6（六级） | 946 | 5651 | 130 | 5 关 | 23 | 1.79% |

> 交错规则：同一细域**最多连排 5 关**，其余互相穿插（块级 SWRR，见 `docs/cet-semantic-themes-plan.md` 第八节）。
> 词条与释义零变化：产物词条多重集 == 源清单（两册 7508 / 5651 条）。

> 进度指纹版本：网页版与小程序的 `dataSignature()` / `signature()` **都是 `v3-`**
> （域体系换代 + 关数变化，旧进度须作废）。

## 二、四级 逐域位置（按关数降序）

| 细域 | 父域 | 关数 | 分几段 | 出现在哪些关 |
|---|---|---:|---:|---|
| 职业与从业者 | 人物与身份 | 20 | 4 | 1-5 / 636-640 / 986-990 / 1238-1242 |
| 亲友与人群 | 人物与身份 | 19 | 4 | 11-15 / 641-645 / 991-995 / 1243-1246 |
| 物料与化工品 | 物质与材料 | 19 | 4 | 128-132 / 651-655 / 1001-1005 / 1248-1251 |
| 虚词与感叹 | 功能词 | 19 | 4 | 613-617 / 666-670 / 1016-1020 / 1254-1257 |
| 交谈与议论 | 语言与交流 | 16 | 4 | 350-354 / 661-665 / 1011-1015 / 1253 |
| 政治与政府 | 政治与政府 | 16 | 4 | 163-167 / 656-660 / 1006-1010 / 1252 |
| 鸟兽虫鱼 | 动物与植物 | 16 | 4 | 103-107 / 646-650 / 996-1000 / 1247 |
| 工具与器械 | 日常用品与工具 | 15 | 3 | 98-102 / 706-710 / 1142-1146 |
| 建筑与设施 | 居住与建筑 | 15 | 3 | 66-70 / 686-690 / 1133-1137 |
| 物质与器械 | 科学技术 | 15 | 3 | 208-212 / 736-740 / 1159-1163 |
| 优劣与价值 | 优劣评价 | 14 | 3 | 479-483 / 846-850 / 1216-1219 |
| 存在与境况 | 状态与情况 | 14 | 3 | 430-434 / 826-830 / 1203-1206 |
| 意愿与抉择 | 态度与意愿 | 14 | 3 | 290-294 / 756-760 / 1169-1172 |
| 新旧与常异 | 性质与特征 | 14 | 3 | 415-419 / 816-820 / 1196-1199 |
| 检查与经办 | 操作与处理 | 14 | 3 | 548-552 / 871-875 / 1228-1231 |
| 秩序与身心 | 状态与情况 | 14 | 3 | 435-439 / 831-835 / 1207-1210 |
| 身体部位 | 身体与健康 | 14 | 3 | 26-30 / 671-675 / 1124-1127 |
| 食物与饮品 | 饮食与食物 | 14 | 3 | 46-50 / 681-685 / 1129-1132 |
| 交往与联系 | 交往与联系 | 13 | 3 | 603-607 / 881-885 / 1233-1235 |
| 告知与宣布 | 语言与交流 | 13 | 3 | 335-339 / 771-775 / 1178-1180 |
| 增长与减少 | 增长与减少 | 13 | 3 | 455-459 / 836-840 / 1211-1213 |
| 多少与比例 | 数量与度量 | 13 | 3 | 365-369 / 786-790 / 1184-1186 |
| 本性与才具 | 性质与特征 | 13 | 3 | 420-424 / 821-825 / 1200-1202 |
| 法律与司法 | 法律与司法 | 13 | 3 | 168-172 / 721-725 / 1151-1153 |
| 行走与出行 | 移动与位移 | 13 | 3 | 519-523 / 866-870 / 1225-1227 |
| 认可与立场 | 态度与意愿 | 13 | 3 | 295-299 / 761-765 / 1173-1175 |
| 财政与收支 | 经济与金融 | 13 | 3 | 183-187 / 726-730 / 1154-1156 |
| 体量与强弱 | 程度与强度 | 12 | 3 | 509-513 / 856-860 / 1221-1222 |
| 先后与早晚 | 时间与频率 | 12 | 3 | 380-384 / 801-805 / 1190-1191 |
| 反复与持续 | 时间与频率 | 12 | 3 | 375-379 / 796-800 / 1188-1189 |
| 地点与区域 | 空间与方位 | 12 | 3 | 138-142 / 716-720 / 1149-1150 |
| 地理与天象 | 自然与天气 | 12 | 3 | 118-122 / 711-715 / 1147-1148 |
| 家具与家居 | 日常用品与工具 | 12 | 3 | 93-97 / 701-705 / 1140-1141 |
| 彻底与约略 | 程度与强度 | 12 | 3 | 514-518 / 861-865 / 1223-1224 |
| 性情脾气 | 性格与品质 | 12 | 3 | 281-285 / 751-755 / 1167-1168 |
| 指代与数量 | 功能词 | 12 | 3 | 608-612 / 886-890 / 1236-1237 |
| 教育与学习 | 教育与学习 | 12 | 3 | 198-202 / 731-735 / 1157-1158 |
| 数字与计数 | 数量与度量 | 12 | 3 | 355-359 / 776-780 / 1181-1182 |
| 明晰与确定 | 性质与特征 | 12 | 3 | 405-409 / 806-810 / 1192-1193 |
| 爱憎与荣辱 | 情绪与感受 | 12 | 3 | 256-260 / 741-745 / 1164-1165 |
| 知晓与理解 | 认知与理解 | 12 | 3 | 315-319 / 766-770 / 1176-1177 |
| 美好与危害 | 性质与特征 | 12 | 3 | 410-414 / 811-815 / 1194-1195 |
| 重要性 | 重要性 | 12 | 3 | 474-478 / 841-845 / 1214-1215 |
| 度量与单位 | 数量与度量 | 11 | 3 | 360-364 / 781-785 / 1183 |
| 悲忧与消沉 | 情绪与感受 | 11 | 3 | 266-270 / 746-750 / 1166 |
| 时段与日期 | 时间与频率 | 11 | 3 | 370-374 / 791-795 / 1187 |
| 生理机能 | 身体与健康 | 11 | 3 | 31-35 / 676-680 / 1128 |
| 相同与相似 | 关系与异同 | 11 | 3 | 494-498 / 851-855 / 1220 |
| 给予与分配 | 获取与给予 | 11 | 3 | 558-562 / 876-880 / 1232 |
| 载具与港站 | 交通与出行 | 11 | 3 | 71-75 / 691-695 / 1138 |
| 道路与行程 | 交通与出行 | 11 | 3 | 76-80 / 696-700 / 1139 |
| 保护与维持 | 保护与维持 | 10 | 2 | 573-577 / 1089-1093 |
| 关联与归属 | 关系与异同 | 10 | 2 | 504-508 / 1062-1066 |
| 器物与零件 | 事物与部件 | 10 | 2 | 148-152 / 925-929 |
| 因果与逻辑 | 因果与逻辑 | 10 | 2 | 460-464 / 1046-1050 |
| 天气与光火 | 自然与天气 | 10 | 2 | 113-117 / 913-917 |
| 帮助与合作 | 帮助与合作 | 10 | 2 | 578-582 / 1094-1098 |
| 得失与占有 | 获取与给予 | 10 | 2 | 553-557 / 1078-1082 |
| 支配与影响 | 控制与影响 | 10 | 2 | 593-597 / 1109-1113 |
| 敌对与攻击 | 竞争与冲突 | 10 | 2 | 588-592 / 1104-1108 |
| 文学与写作 | 文学与写作 | 10 | 2 | 223-227 / 954-958 |
| 材质与触感 | 性质与特征 | 10 | 2 | 395-399 / 1025-1029 |
| 欢喜与激动 | 情绪与感受 | 10 | 2 | 271-275 / 964-968 |
| 竞争与对抗 | 竞争与冲突 | 10 | 2 | 583-587 / 1099-1103 |
| 组织与机构 | 组织与机构 | 10 | 2 | 158-162 / 932-936 |
| 计划与安排 | 计划与安排 | 10 | 2 | 469-473 / 1051-1055 |
| 内外与边界 | 空间与方位 | 9 | 2 | 143-147 / 921-924 |
| 切削与击打 | 操作与处理 | 9 | 2 | 533-537 / 1070-1073 |
| 创建与制造 | 建立与破坏 | 9 | 2 | 563-567 / 1083-1086 |
| 危难与困苦 | 状态与情况 | 9 | 2 | 425-429 / 1033-1036 |
| 商业与贸易 | 商业与贸易 | 9 | 2 | 188-192 / 941-944 |
| 学科与研究 | 科学技术 | 9 | 2 | 203-207 / 947-950 |
| 捆扎与装配 | 操作与处理 | 9 | 2 | 543-547 / 1074-1077 |
| 疾病症状 | 身体与健康 | 9 | 2 | 36-40 / 896-899 |
| 过程与趋向 | 变化与发展 | 9 | 2 | 450-454 / 1042-1045 |
| 军事与战争 | 军事与战争 | 8 | 2 | 173-177 / 937-939 |
| 压制与限制 | 控制与影响 | 8 | 2 | 598-602 / 1114-1116 |
| 变化与更替 | 变化与发展 | 8 | 2 | 440-444 / 1037-1039 |
| 名物与事务 | 特殊类别 | 8 | 2 | 623-627 / 1118-1120 |
| 媒体与传播 | 媒体与传播 | 8 | 2 | 218-222 / 951-953 |
| 家庭与亲属 | 家庭与亲属 | 8 | 2 | 21-25 / 893-895 |
| 容器与餐具 | 日常用品与工具 | 8 | 2 | 88-92 / 908-910 |
| 感官与色彩 | 性质与特征 | 8 | 2 | 385-389 / 1021-1023 |
| 感知与推测 | 认知与理解 | 8 | 2 | 320-324 / 975-977 |
| 房屋与居所 | 居住与建筑 | 8 | 2 | 61-65 / 903-905 |
| 方位与形状 | 空间与方位 | 8 | 2 | 133-137 / 918-920 |
| 欺瞒与灾祸 | 特殊类别 | 8 | 2 | 628-632 / 1121-1123 |
| 正确与错误 | 正确与错误 | 8 | 2 | 489-493 / 1058-1060 |
| 流动与旋转 | 移动与位移 | 8 | 2 | 528-532 / 1067-1069 |
| 见解与信念 | 思考与观点 | 8 | 2 | 310-314 / 972-974 |
| 解释与释义 | 语言与交流 | 8 | 2 | 340-344 / 982-984 |
| 记忆与注意 | 记忆与注意 | 8 | 2 | 325-329 / 978-980 |
| 道德品性 | 性格与品质 | 8 | 2 | 276-280 / 969-971 |
| 难易与繁简 | 性质与特征 | 8 | 2 | 400-404 / 1030-1032 |
| 发展与兴衰 | 变化与发展 | 7 | 2 | 445-449 / 1040-1041 |
| 娱乐与休闲 | 娱乐与休闲 | 7 | 2 | 83-87 / 906-907 |
| 宗教与信仰 | 宗教与信仰 | 7 | 2 | 244-248 / 961-962 |
| 工作与职业 | 工作与职业 | 7 | 2 | 193-197 / 945-946 |
| 成分与构造 | 事物与部件 | 7 | 2 | 153-157 / 930-931 |
| 服饰与打扮 | 服饰与打扮 | 7 | 2 | 56-60 / 901-902 |
| 生物与草木 | 动物与植物 | 7 | 2 | 108-112 / 911-912 |
| 破坏与毁灭 | 建立与破坏 | 7 | 2 | 568-572 / 1087-1088 |
| 评断与名声 | 优劣评价 | 7 | 2 | 484-488 / 1056-1057 |
| 专有名词 | 专有名词 | 6 | 2 | 618-622 / 1117 |
| 吃喝与烹调 | 饮食与食物 | 6 | 2 | 51-55 / 900 |
| 听说读写 | 语言与交流 | 6 | 2 | 330-334 / 981 |
| 品性境遇人物 | 人物与身份 | 6 | 2 | 16-20 / 892 |
| 官职与职衔 | 人物与身份 | 6 | 2 | 6-10 / 891 |
| 差异与区分 | 关系与异同 | 6 | 2 | 499-503 / 1061 |
| 形体与尺寸 | 性质与特征 | 6 | 2 | 390-394 / 1024 |
| 愤怒与恐惧 | 情绪与感受 | 6 | 2 | 261-265 / 963 |
| 艺术与绘画 | 艺术与绘画 | 6 | 2 | 228-232 / 959 |
| 语法与文字 | 语言与交流 | 6 | 2 | 345-349 / 985 |
| 钱财与资产 | 经济与金融 | 6 | 2 | 178-182 / 940 |
| 音乐与表演 | 音乐与表演 | 6 | 2 | 233-237 / 960 |
| 医疗诊治 | 身体与健康 | 5 | 1 | 41-45 |
| 否定与拒绝 | 态度与意愿 | 5 | 1 | 300-304 |
| 思考与推断 | 思考与观点 | 5 | 1 | 305-309 |
| 混合与提炼 | 操作与处理 | 5 | 1 | 538-542 |
| 计算机与信息 | 计算机与信息 | 5 | 1 | 213-217 |
| 金属与矿物 | 物质与材料 | 5 | 1 | 123-127 |
| 体育与运动 | 体育与运动 | 4 | 1 | 240-243 |
| 勤勉与才智 | 性格与品质 | 4 | 1 | 286-289 |
| 搬运与投掷 | 移动与位移 | 4 | 1 | 524-527 |
| 方法与手段 | 方法与手段 | 4 | 1 | 465-468 |
| 节日与习俗 | 节日与习俗 | 4 | 1 | 249-252 |
| 历史与考古 | 历史与考古 | 3 | 1 | 253-255 |
| 声响杂项 | 特殊类别 | 3 | 1 | 633-635 |
| 影视与娱乐 | 影视与娱乐 | 2 | 1 | 238-239 |
| 购物与消费 | 购物与消费 | 2 | 1 | 81-82 |

## 三、六级 逐域位置（按关数降序）

| 细域 | 父域 | 关数 | 分几段 | 出现在哪些关 |
|---|---|---:|---:|---|
| 法律与司法 | 法律与司法 | 16 | 4 | 159-163 / 606-610 / 791-795 / 946 |
| 器物与零件 | 事物与部件 | 14 | 3 | 140-144 / 616-620 / 910-913 |
| 性情脾气 | 性格与品质 | 14 | 3 | 266-270 / 636-640 / 922-925 |
| 物质与器械 | 科学技术 | 14 | 3 | 199-203 / 631-635 / 918-921 |
| 交往与联系 | 交往与联系 | 12 | 3 | 581-585 / 691-695 / 944-945 |
| 优劣与价值 | 优劣评价 | 12 | 3 | 456-460 / 671-675 / 936-937 |
| 告知与宣布 | 语言与交流 | 12 | 3 | 318-322 / 641-645 / 926-927 |
| 增长与减少 | 增长与减少 | 12 | 3 | 433-437 / 661-665 / 932-933 |
| 政治与政府 | 政治与政府 | 12 | 3 | 154-158 / 621-625 / 914-915 |
| 敌对与攻击 | 竞争与冲突 | 12 | 3 | 566-570 / 686-690 / 942-943 |
| 新旧与常异 | 性质与特征 | 12 | 3 | 393-397 / 656-660 / 930-931 |
| 检查与经办 | 操作与处理 | 12 | 3 | 526-530 / 681-685 / 940-941 |
| 行走与出行 | 移动与位移 | 12 | 3 | 496-500 / 676-680 / 938-939 |
| 财政与收支 | 经济与金融 | 12 | 3 | 174-178 / 626-630 / 916-917 |
| 重要性 | 重要性 | 12 | 3 | 451-455 / 666-670 / 934-935 |
| 鸟兽虫鱼 | 动物与植物 | 12 | 3 | 95-99 / 611-615 / 908-909 |
| 交谈与议论 | 语言与交流 | 11 | 3 | 331-335 / 646-650 / 928 |
| 明晰与确定 | 性质与特征 | 11 | 3 | 383-387 / 651-655 / 929 |
| 压制与限制 | 控制与影响 | 10 | 2 | 576-580 / 900-904 |
| 变化与更替 | 变化与发展 | 10 | 2 | 418-422 / 839-843 |
| 多少与比例 | 数量与度量 | 10 | 2 | 345-349 / 799-803 |
| 帮助与合作 | 帮助与合作 | 10 | 2 | 556-560 / 887-891 |
| 支配与影响 | 控制与影响 | 10 | 2 | 571-575 / 895-899 |
| 本性与才具 | 性质与特征 | 10 | 2 | 398-402 / 825-829 |
| 材质与触感 | 性质与特征 | 10 | 2 | 373-377 / 813-817 |
| 相同与相似 | 关系与异同 | 10 | 2 | 471-475 / 854-858 |
| 知晓与理解 | 认知与理解 | 10 | 2 | 300-304 / 783-787 |
| 破坏与毁灭 | 建立与破坏 | 10 | 2 | 546-550 / 880-884 |
| 美好与危害 | 性质与特征 | 10 | 2 | 388-392 / 820-824 |
| 亲友与人群 | 人物与身份 | 9 | 2 | 11-15 / 701-704 |
| 媒体与传播 | 媒体与传播 | 9 | 2 | 208-212 / 749-752 |
| 工具与器械 | 日常用品与工具 | 9 | 2 | 90-94 / 723-726 |
| 建筑与设施 | 居住与建筑 | 9 | 2 | 62-66 / 717-720 |
| 文学与写作 | 文学与写作 | 9 | 2 | 213-217 / 753-756 |
| 正确与错误 | 正确与错误 | 9 | 2 | 466-470 / 850-853 |
| 流动与旋转 | 移动与位移 | 9 | 2 | 506-510 / 869-872 |
| 物料与化工品 | 物质与材料 | 9 | 2 | 120-124 / 735-738 |
| 疾病症状 | 身体与健康 | 9 | 2 | 34-38 / 712-715 |
| 秩序与身心 | 状态与情况 | 9 | 2 | 413-417 / 835-838 |
| 职业与从业者 | 人物与身份 | 9 | 2 | 1-5 / 696-699 |
| 体量与强弱 | 程度与强度 | 8 | 2 | 486-490 / 862-864 |
| 先后与早晚 | 时间与频率 | 8 | 2 | 358-362 / 806-808 |
| 名物与事务 | 特殊类别 | 8 | 2 | 593-597 / 905-907 |
| 品性境遇人物 | 人物与身份 | 8 | 2 | 16-20 / 705-707 |
| 商业与贸易 | 商业与贸易 | 8 | 2 | 179-183 / 742-744 |
| 地理与天象 | 自然与天气 | 8 | 2 | 110-114 / 732-734 |
| 存在与境况 | 状态与情况 | 8 | 2 | 408-412 / 832-834 |
| 宗教与信仰 | 宗教与信仰 | 8 | 2 | 230-234 / 758-760 |
| 形体与尺寸 | 性质与特征 | 8 | 2 | 368-372 / 810-812 |
| 悲忧与消沉 | 情绪与感受 | 8 | 2 | 251-255 / 765-767 |
| 意愿与抉择 | 态度与意愿 | 8 | 2 | 275-279 / 772-774 |
| 感知与推测 | 认知与理解 | 8 | 2 | 305-309 / 788-790 |
| 搬运与投掷 | 移动与位移 | 8 | 2 | 501-505 / 866-868 |
| 生物与草木 | 动物与植物 | 8 | 2 | 100-104 / 727-729 |
| 生理机能 | 身体与健康 | 8 | 2 | 29-33 / 709-711 |
| 竞争与对抗 | 竞争与冲突 | 8 | 2 | 561-565 / 892-894 |
| 给予与分配 | 获取与给予 | 8 | 2 | 536-540 / 877-879 |
| 见解与信念 | 思考与观点 | 8 | 2 | 295-299 / 780-782 |
| 认可与立场 | 态度与意愿 | 8 | 2 | 280-284 / 775-777 |
| 道德品性 | 性格与品质 | 8 | 2 | 261-265 / 769-771 |
| 保护与维持 | 保护与维持 | 7 | 2 | 551-555 / 885-886 |
| 关联与归属 | 关系与异同 | 7 | 2 | 481-485 / 860-861 |
| 切削与击打 | 操作与处理 | 7 | 2 | 511-515 / 873-874 |
| 危难与困苦 | 状态与情况 | 7 | 2 | 403-407 / 830-831 |
| 反复与持续 | 时间与频率 | 7 | 2 | 353-357 / 804-805 |
| 因果与逻辑 | 因果与逻辑 | 7 | 2 | 438-442 / 845-846 |
| 天气与光火 | 自然与天气 | 7 | 2 | 105-109 / 730-731 |
| 思考与推断 | 思考与观点 | 7 | 2 | 290-294 / 778-779 |
| 愤怒与恐惧 | 情绪与感受 | 7 | 2 | 246-250 / 763-764 |
| 教育与学习 | 教育与学习 | 7 | 2 | 189-193 / 746-747 |
| 数字与计数 | 数量与度量 | 7 | 2 | 336-340 / 797-798 |
| 爱憎与荣辱 | 情绪与感受 | 7 | 2 | 241-245 / 761-762 |
| 计划与安排 | 计划与安排 | 7 | 2 | 446-450 / 847-848 |
| 难易与繁简 | 性质与特征 | 7 | 2 | 378-382 / 818-819 |
| 军事与战争 | 军事与战争 | 6 | 2 | 164-168 / 741 |
| 发展与兴衰 | 变化与发展 | 6 | 2 | 423-427 / 844 |
| 地点与区域 | 空间与方位 | 6 | 2 | 130-134 / 740 |
| 学科与研究 | 科学技术 | 6 | 2 | 194-198 / 748 |
| 官职与职衔 | 人物与身份 | 6 | 2 | 6-10 / 700 |
| 家具与家居 | 日常用品与工具 | 6 | 2 | 85-89 / 722 |
| 工作与职业 | 工作与职业 | 6 | 2 | 184-188 / 745 |
| 差异与区分 | 关系与异同 | 6 | 2 | 476-480 / 859 |
| 彻底与约略 | 程度与强度 | 6 | 2 | 491-495 / 865 |
| 得失与占有 | 获取与给予 | 6 | 2 | 531-535 / 876 |
| 感官与色彩 | 性质与特征 | 6 | 2 | 363-367 / 809 |
| 捆扎与装配 | 操作与处理 | 6 | 2 | 521-525 / 875 |
| 方位与形状 | 空间与方位 | 6 | 2 | 125-129 / 739 |
| 欢喜与激动 | 情绪与感受 | 6 | 2 | 256-260 / 768 |
| 解释与释义 | 语言与交流 | 6 | 2 | 323-327 / 796 |
| 评断与名声 | 优劣评价 | 6 | 2 | 461-465 / 849 |
| 身体部位 | 身体与健康 | 6 | 2 | 24-28 / 708 |
| 道路与行程 | 交通与出行 | 6 | 2 | 71-75 / 721 |
| 音乐与表演 | 音乐与表演 | 6 | 2 | 221-225 / 757 |
| 食物与饮品 | 饮食与食物 | 6 | 2 | 43-47 / 716 |
| 内外与边界 | 空间与方位 | 5 | 1 | 135-139 |
| 创建与制造 | 建立与破坏 | 5 | 1 | 541-545 |
| 吃喝与烹调 | 饮食与食物 | 5 | 1 | 48-52 |
| 否定与拒绝 | 态度与意愿 | 5 | 1 | 285-289 |
| 房屋与居所 | 居住与建筑 | 5 | 1 | 57-61 |
| 欺瞒与灾祸 | 特殊类别 | 5 | 1 | 598-602 |
| 混合与提炼 | 操作与处理 | 5 | 1 | 516-520 |
| 组织与机构 | 组织与机构 | 5 | 1 | 149-153 |
| 虚词与感叹 | 功能词 | 5 | 1 | 587-591 |
| 过程与趋向 | 变化与发展 | 5 | 1 | 428-432 |
| 金属与矿物 | 物质与材料 | 5 | 1 | 115-119 |
| 钱财与资产 | 经济与金融 | 5 | 1 | 169-173 |
| 勤勉与才智 | 性格与品质 | 4 | 1 | 271-274 |
| 医疗诊治 | 身体与健康 | 4 | 1 | 39-42 |
| 听说读写 | 语言与交流 | 4 | 1 | 314-317 |
| 娱乐与休闲 | 娱乐与休闲 | 4 | 1 | 78-81 |
| 度量与单位 | 数量与度量 | 4 | 1 | 341-344 |
| 成分与构造 | 事物与部件 | 4 | 1 | 145-148 |
| 服饰与打扮 | 服饰与打扮 | 4 | 1 | 53-56 |
| 计算机与信息 | 计算机与信息 | 4 | 1 | 204-207 |
| 记忆与注意 | 记忆与注意 | 4 | 1 | 310-313 |
| 载具与港站 | 交通与出行 | 4 | 1 | 67-70 |
| 历史与考古 | 历史与考古 | 3 | 1 | 238-240 |
| 声响杂项 | 特殊类别 | 3 | 1 | 603-605 |
| 家庭与亲属 | 家庭与亲属 | 3 | 1 | 21-23 |
| 容器与餐具 | 日常用品与工具 | 3 | 1 | 82-84 |
| 方法与手段 | 方法与手段 | 3 | 1 | 443-445 |
| 时段与日期 | 时间与频率 | 3 | 1 | 350-352 |
| 艺术与绘画 | 艺术与绘画 | 3 | 1 | 218-220 |
| 节日与习俗 | 节日与习俗 | 3 | 1 | 235-237 |
| 语法与文字 | 语言与交流 | 3 | 1 | 328-330 |
| 体育与运动 | 体育与运动 | 2 | 1 | 228-229 |
| 影视与娱乐 | 影视与娱乐 | 2 | 1 | 226-227 |
| 购物与消费 | 购物与消费 | 2 | 1 | 76-77 |
| 专有名词 | 专有名词 | 1 | 1 | 592 |
| 指代与数量 | 功能词 | 1 | 1 | 586 |

## 四、四级 前 60 关逐关

<details><summary>展开 四级 前 60 关（主题 + 单词）</summary>

| 关 | 主题 | 单词 |
|---:|---|---|
| 1 | 职业与从业者 · 1 | philosopher · psychologist · technician · coach · caregiver · consultant · architect |
| 2 | 职业与从业者 · 2 | homemaker · critic · carpenter · advocator · porter · agent · chef |
| 3 | 职业与从业者 · 3 | programmer · mechanic · pilot · messenger · professor · interpreter |
| 4 | 职业与从业者 · 4 | technician · workman · author · artist · secretary · editor |
| 5 | 职业与从业者 · 5 | critic · porter · operator · boss · actress · fireman |
| 6 | 官职与职衔 · 1 | dictator · inspector · detective · representative · president · queen · prince |
| 7 | 官职与职衔 · 2 | representative · judge · lawyer · officer · policeman · manager |
| 8 | 官职与职衔 · 3 | leader · lord · king · emperor · princess · politician |
| 9 | 官职与职衔 · 4 | executive · chairman · cop · representative · senator · solicitor |
| 10 | 官职与职衔 · 5 | deputy · executive · spokesman · lord · politician · emperor |
| 11 | 亲友与人群 · 1 | senior · individual · colleague · candidate · resident · racially |
| 12 | 亲友与人群 · 2 | applicant · civilian · humanity · identity · peer · generation |
| 13 | 亲友与人群 · 3 | chap · partner · public · youth · classmate · child |
| 14 | 亲友与人群 · 4 | man · master · kid · gentleman · person · stranger |
| 15 | 亲友与人群 · 5 | reader · tourist · resident · human · lover · infant |
| 16 | 品性境遇人物 · 1 | slave · expert · orphan · liar · hero · genius |
| 17 | 品性境遇人物 · 2 | pioneer · accessary · beggar · beginner · scholar · coward |
| 18 | 品性境遇人物 · 3 | champion · veteran · expert · giant · fool · prisoner |
| 19 | 品性境遇人物 · 4 | intellectual · murderer · heroine · champion |
| 20 | 品性境遇人物 · 5 | intellectual · victim · genius · scholar · refugee · amateur |
| 21 | 家庭与亲属 · 1 | upbringing · relative · bride · engagement · household · couple |
| 22 | 家庭与亲属 · 2 | wedding · nephew · brother · divorce · family · couple |
| 23 | 家庭与亲属 · 3 | married · ancestor · household · marry · honeymoon · widow |
| 24 | 家庭与亲属 · 4 | cousin · grandfather · heir · marriage · aunt · daughter |
| 25 | 家庭与亲属 · 5 | mother · grandmother · twin · father · parent · niece |
| 26 | 身体部位 · 1 | cheek · lap · gum · organ · forefinger · palm |
| 27 | 身体部位 · 2 | lung · hair · heart · toe · knee · flesh |
| 28 | 身体部位 · 3 | arm · lip · tissue · hand · breast · nerve |
| 29 | 身体部位 · 4 | finger · heel · ankle · nose · leg · chin |
| 30 | 身体部位 · 5 | brain · limb · neck · wrist · fist · liver |
| 31 | 生理机能 · 1 | overweight · exhaust · choke · pregnant · appetite · pose · fatigue |
| 32 | 生理机能 · 2 | physical · intake · tremble · fitness · pulse · bathe · bleed |
| 33 | 生理机能 · 3 | aural · hunger · visual · vital · fatigue · choke |
| 34 | 生理机能 · 4 | gasp · waken · healthy · flush · digest · eyesight |
| 35 | 生理机能 · 5 | tremble · born · vision · birth · hungry · unconscious |
| 36 | 疾病症状 · 1 | tumor · injure · strain · toothache · fatal · symptom · virus |
| 37 | 疾病症状 · 2 | painful · illness · sting · disease · lame · bruise |
| 38 | 疾病症状 · 3 | cough · dying · cancer · dumb · injury · sore |
| 39 | 疾病症状 · 4 | painful · mute · ache · infect · wound · stroke |
| 40 | 疾病症状 · 5 | cripple · headache · pain · deaf · flu · disable |
| 41 | 医疗诊治 · 1 | hospitalize · prescription · therapy · remedy · treatment · screen · poison |
| 42 | 医疗诊治 · 2 | remedy · medical · heal · prescribe · cure · patient · surgery |
| 43 | 医疗诊治 · 3 | treatment · medicine · injection · drug · pill · surgery |
| 44 | 医疗诊治 · 4 | pill · prescribe · therapy · dental · treatment · injection |
| 45 | 医疗诊治 · 5 | remedy · hospitalize · patient · diagnose · heal · medication |
| 46 | 食物与饮品 · 1 | snack · grain · dessert · hamburger · honey · apple |
| 47 | 食物与饮品 · 2 | juice · paste · bean · berry · pear · flour |
| 48 | 食物与饮品 · 3 | bread · coffee · meat · sausage · carrot · dairy |
| 49 | 食物与饮品 · 4 | grape · nut · chocolate · pie · cake · cheese |
| 50 | 食物与饮品 · 5 | crust · onion · grain · milk · loaf · beef |
| 51 | 吃喝与烹调 · 1 | swallow · canteen · menu · nutritious · tasteless · roast |
| 52 | 吃喝与烹调 · 2 | toast · breakfast · feed · feast · taste · dinner |
| 53 | 吃喝与烹调 · 3 | sour · drink · cook · meal · fry · flavour |
| 54 | 吃喝与烹调 · 4 | cafe · canteen · bake · eat · menu · cafeteria |
| 55 | 吃喝与烹调 · 5 | lunch · chew · toast · roast |
| 56 | 服饰与打扮 · 1 | makeup · hat · crown · fashionable · glove · gown |
| 57 | 服饰与打扮 · 2 | clothe · sleeve · sweater · uniform · button · jewel |
| 58 | 服饰与打扮 · 3 | fashion · stocking · wear · pocket · clothes · coat |
| 59 | 服饰与打扮 · 4 | clothing · necklace · jacket · lace · collar · boot |
| 60 | 服饰与打扮 · 5 | helmet · belt · cloak · dress · cap · overcoat |

</details>

## 五、六级 前 60 关逐关

<details><summary>展开 六级 前 60 关（主题 + 单词）</summary>

| 关 | 主题 | 单词 |
|---:|---|---|
| 1 | 职业与从业者 · 1 | stockbroker · recruiter · carpenter · drummer · commentator · philosopher |
| 2 | 职业与从业者 · 2 | counselor · entrepreneur · attendant · accountant · coach · receptionist |
| 3 | 职业与从业者 · 3 | specialist · lobbyist · instructor · weaver · banker · dentist |
| 4 | 职业与从业者 · 4 | blacksmith · attendant · producer · maid · tradesman · trader |
| 5 | 职业与从业者 · 5 | dealer · waitress · magician · historian · architect · electrician |
| 6 | 官职与职衔 · 1 | supervisor · representative · detective · legislator · executive |
| 7 | 官职与职衔 · 2 | executive · conqueror · detective · treasurer · knight · baron · consul |
| 8 | 官职与职衔 · 3 | sheriff · inspector · duke · spokesman · dictator · attorney |
| 9 | 官职与职衔 · 4 | deputy · delegate · ambassador · herald · senator · tyrant |
| 10 | 官职与职衔 · 5 | dean · premier · senator · solicitor · statesman · diplomat |
| 11 | 亲友与人群 · 1 | vegetarian · contestant · resident · dweller · applicant · immigrant · eyewitness |
| 12 | 亲友与人群 · 2 | peer · elite · infant · colonist · baby · consumer · maiden |
| 13 | 亲友与人群 · 3 | humanity · mistress · predecessor · follower · civilian · hostess |
| 14 | 亲友与人群 · 4 | feminine · junior · successor · pal · pedestrian · youngster |
| 15 | 亲友与人群 · 5 | resident · participant · spectator · patron · successor · mistress |
| 16 | 品性境遇人物 · 1 | genius · adventurer · addict · donor · layman · victim |
| 17 | 品性境遇人物 · 2 | pessimist · volunteer · burglar · amateur · bachelor · bandit |
| 18 | 品性境遇人物 · 3 | bore · outlaw · dependant · miser · snob · refugee |
| 19 | 品性境遇人物 · 4 | ass · rascal · martyr · terrorist · pirate · gangster |
| 20 | 品性境遇人物 · 5 | champion · virgin · millionaire · blond · idiot · patriot |
| 21 | 家庭与亲属 · 1 | descendant · household · bride · descent · bridegroom · engagement |
| 22 | 家庭与亲属 · 2 | descendant · offspring · divorce · clan · relative · household |
| 23 | 家庭与亲属 · 3 | descendant · paternity · spouse · engagement · offspring · ancestor · heir |
| 24 | 身体部位 · 1 | vessel · ankle · wrinkle · head · body · hip |
| 25 | 身体部位 · 2 | nose · bald · kidney · whisker · pore · vein |
| 26 | 身体部位 · 3 | muscular · skeleton · thigh · limb · ear |
| 27 | 身体部位 · 4 | skeleton · kidney · skull · thigh · gland · corpse · muscular |
| 28 | 身体部位 · 5 | spine · pore · visceral · bowel · wrinkle · complexion |
| 29 | 生理机能 · 1 | obesity · nap · robust · inhale · fatigue · pose |
| 30 | 生理机能 · 2 | sanitation · wink · sniff · flush · intake · physically |
| 31 | 生理机能 · 3 | slumber · wholesome · fitness · vision · vital · pant |
| 32 | 生理机能 · 4 | tickle · blush · pregnant · sneeze · snore · pose |
| 33 | 生理机能 · 5 | dazzle · sniff · tickle · posture · robust |
| 34 | 疾病症状 · 1 | chronic · infectious · virus · chronically · symptom · infect |
| 35 | 疾病症状 · 2 | perish · fatal · bruise · epidemic · mortality · cripple |
| 36 | 疾病症状 · 3 | allergic · seasick · suicide · paralyze · dysfunction · symptom |
| 37 | 疾病症状 · 4 | plague · dizzy · perish · paralyse · deafen · mute |
| 38 | 疾病症状 · 5 | sting · tuberculosis · smart · infectious · scar · suicide |
| 39 | 医疗诊治 · 1 | diagnose · therapy · prescribe · surgical · inject |
| 40 | 医疗诊治 · 2 | diagnose · prescription · opium · vaccinate · bandage · transplant |
| 41 | 医疗诊治 · 3 | surgery · inject · bandage · capsule · cocaine |
| 42 | 医疗诊治 · 4 | remedy · transplant · diagnose · medication · prescription |
| 43 | 食物与饮品 · 1 | vitamin · chop · pickle · spice · bacon |
| 44 | 食物与饮品 · 2 | vitamin · dessert · garlic · raisin · pumpkin · nut · snack |
| 45 | 食物与饮品 · 3 | mustard · yeast · soy · ginger · ham · peel |
| 46 | 食物与饮品 · 4 | cereal · pineapple · jam · jelly · yolk · steak |
| 47 | 食物与饮品 · 5 | pastry · cocktail · cucumber · staple · grain · pumpkin |
| 48 | 吃喝与烹调 · 1 | recipe · tasteless · chew · cafeteria · nutritious · flavor · diet |
| 49 | 吃喝与烹调 · 2 | banquet · luncheon · devour · nourish · sweeten · nourishment · flavour |
| 50 | 吃喝与烹调 · 3 | stew · breakfast · dine · napkin · recipe · cafeteria |
| 51 | 吃喝与烹调 · 4 | feast · brew · nourish · banquet · sour · napkin |
| 52 | 吃喝与烹调 · 5 | nutrition · dine · sip · recipe · edible · refreshment |
| 53 | 服饰与打扮 · 1 | blouse · badge · gown · costume · makeup · jewellery · garment |
| 54 | 服饰与打扮 · 2 | jean · underwear · cape · blouse · mitten · pants · line |
| 55 | 服饰与打扮 · 3 | badge · lipstick · lining · frock · pyjamas · lace · gown |
| 56 | 服饰与打扮 · 4 | outfit · perfume · cape · veil · garment · costume |
| 57 | 房屋与居所 · 1 | apartment · dorm · motel · accommodate · flat · closet · pantry |
| 58 | 房屋与居所 · 2 | motel · nursery · reside · dwell · lodging · basement |
| 59 | 房屋与居所 · 3 | mansion · villa · suite · motel · reside · apartment |
| 60 | 房屋与居所 · 4 | accommodation · shelter · residential · mansion · inhabit · accommodate |

</details>

## 六、怎么验证

```bash
# 一条命令查全部不变量：域体系自洽 / 粒度达标 / 产物与源清单一致 / 三副本一致
python3 scripts/learn/validate_cet_domains.py

# 单测（闸门，不用 Mockito）
mvn test -Dtest=CetWordBankTest,WordMatchBankTest \
    -Dsurefire.failIfNoSpecifiedTests=false

# 词库与静态页都打进 jar，要看到新主题必须重新打包 + 重启
STORY_DB_PATH=/Users/renfufei/LLM_ALL/STORY_DB/data ./start_web.sh

# 线上交互级回归（需要服务已在跑）
node scripts/check_word_match.mjs      # S6c：关数 / 细域 / 粒度 / 关内英文不重复
node scripts/probe_cet_export.mjs      # 单机件导出
```

## 七、怎么回滚

改动只落在四个文件上，按顺序回退即可：

| 文件 | 回滚方式 |
|---|---|
| `src/test/resources/learn/cet-words-source/cet-themes.tsv` | 恢复上一版的第 3 列（域归属） |
| `src/test/resources/learn/cet-words-source/cet-domain-tree.tsv` | 恢复上一版 / 删除（删了要同时回退构建脚本） |
| `src/main/resources/learn/cet-words.json` | 重跑 `build_cet_words.py` |
| `word-match-miniprogram/data/levels-college.js` | 重跑 `dump_word_match_payload.py` + `build_miniprogram_data.py` |

> 进度指纹版本**只升不降**：回滚后旧进度同样作废，这是预期行为。
> 想保留一份现场再动手，先把这四个文件复制到 `.workbuddy/backup/<日期>-<名字>/`。


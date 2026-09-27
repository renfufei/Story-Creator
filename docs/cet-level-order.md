# 四六级关卡顺序重排记录（2026-09-27）

> **需求**：四级 / 六级原本把同一类词汇整段连排（一册开头连着 51 关「人物与身份」、接着 84 关「性质与特征」），做久了容易疲劳。现改为**同一语义域最多连排 5 关**，其余互相穿插。
>
> **本文用途**：这次只动顺序、一个词都没改，所以最需要核对的是「某一关现在是什么」。下面可以查到每个语义域现在散落在哪些关、以及前 60 关逐关的主题与单词。
>
> 生成自 `src/main/resources/learn/cet-words.json`（重跑 `build_cet_words.py` 后需重新生成本文）。

## 一、结果概览

| 册 | 关数 | 词数 | 语义域 | 重排前最长同类连排 | 重排后最长同类连排 | 前 100 关覆盖域数 | 域最晚首次出现 |
|---|---|---|---|---|---|---|---|
| cet-4（四级） | 1255 | 7508 | 67 | 84 关 | **5 关** | 21 | 第 320 关 |
| cet-6（六级） | 944 | 5651 | 67 | 73 关 | **5 关** | 22 | 第 304 关 |

> 词条与释义**零变化**：关卡集合、词条多重集、域内顺序、`域 · N` 序号全部保持；源清单 `cet-4.txt` / `cet-6.txt` 的 md5 也与重排前一致（纯置换）。

## 二、四级 逐域位置（原来整段 → 现在最多 17 段交替）

| 语义域 | 关数 | 现在分几段 | 重排前的位置 | 现在出现在哪些关（最多列 6 段） |
|---|---|---|---|---|
| 性质与特征 | 84 | 17 | 第 758 关起，连续 84 关 | 206-210 / 325-329 / 410-414 / 485-489 / 555-559 / 605-609 …（共 17 段） |
| 人物与身份 | 51 | 11 | 第 1 关起，连续 51 关 | 1-5 / 330-334 / 470-474 / 580-584 / 659-663 / 769-773 …（共 11 段） |
| 语言与交流 | 48 | 10 | 第 639 关起，连续 48 关 | 191-195 / 335-339 / 495-499 / 600-604 / 724-728 / 814-818 …（共 10 段） |
| 情绪与感受 | 39 | 8 | 第 502 关起，连续 39 关 | 161-165 / 345-349 / 535-539 / 669-673 / 819-823 / 995-999 …（共 8 段） |
| 操作与处理 | 38 | 8 | 第 1056 关起，连续 38 关 | 270-274 / 360-364 / 550-554 / 684-688 / 834-838 / 1010-1014 …（共 8 段） |
| 数量与度量 | 36 | 8 | 第 687 关起，连续 36 关 | 196-200 / 350-354 / 540-544 / 674-678 / 824-828 / 1000-1004 …（共 8 段） |
| 状态与情况 | 37 | 8 | 第 842 关起，连续 37 关 | 211-215 / 355-359 / 545-549 / 679-683 / 829-833 / 1005-1009 …（共 8 段） |
| 身体与健康 | 40 | 8 | 第 60 关起，连续 40 关 | 11-15 / 340-344 / 530-534 / 664-668 / 809-813 / 990-994 …（共 8 段） |
| 态度与意愿 | 32 | 7 | 第 565 关起，连续 32 关 | 171-175 / 370-374 / 590-594 / 759-763 / 924-928 / 1070-1074 …（共 7 段） |
| 日常用品与工具 | 35 | 7 | 第 179 关起，连续 35 关 | 43-47 / 365-369 / 585-589 / 754-758 / 919-923 / 1065-1069 …（共 7 段） |
| 时间与频率 | 35 | 7 | 第 723 关起，连续 35 关 | 201-205 / 375-379 / 595-599 / 764-768 / 929-933 / 1075-1079 …（共 7 段） |
| 关系与异同 | 27 | 6 | 第 980 关起，连续 27 关 | 255-259 / 385-389 / 615-619 / 844-848 / 1035-1039 / 1214-1215 |
| 功能词 | 30 | 6 | 第 1202 关起，连续 30 关 | 310-314 / 390-394 / 620-624 / 849-853 / 1040-1044 / 1216-1220 |
| 空间与方位 | 28 | 6 | 第 284 关起，连续 28 关 | 63-67 / 380-384 / 610-614 / 839-843 / 1030-1034 / 1211-1213 |
| 交通与出行 | 22 | 5 | 第 148 关起，连续 22 关 | 31-35 / 400-404 / 694-698 / 945-949 / 1172-1173 |
| 动物与植物 | 23 | 5 | 第 214 关起，连续 23 关 | 48-52 / 405-409 / 699-703 / 950-954 / 1174-1176 |
| 变化与发展 | 24 | 5 | 第 879 关起，连续 24 关 | 216-220 / 435-439 / 729-733 / 975-979 / 1192-1195 |
| 居住与建筑 | 22 | 5 | 第 126 关起，连续 22 关 | 26-30 / 395-399 / 689-693 / 940-944 / 1170-1171 |
| 性格与品质 | 24 | 5 | 第 541 关起，连续 24 关 | 166-170 / 430-434 / 719-723 / 970-974 / 1188-1191 |
| 物质与材料 | 25 | 5 | 第 259 关起，连续 25 关 | 58-62 / 420-424 / 709-713 / 960-964 / 1179-1183 |
| 科学技术 | 24 | 5 | 第 423 关起，连续 24 关 | 113-117 / 425-429 / 714-718 / 965-969 / 1184-1187 |
| 移动与位移 | 25 | 5 | 第 1031 关起，连续 25 关 | 265-269 / 445-449 / 744-748 / 1020-1024 / 1205-1209 |
| 程度与强度 | 24 | 5 | 第 1007 关起，连续 24 关 | 260-264 / 440-444 / 734-738 / 1015-1019 / 1201-1204 |
| 自然与天气 | 22 | 5 | 第 237 关起，连续 22 关 | 53-57 / 415-419 / 704-708 / 955-959 / 1177-1178 |
| 获取与给予 | 21 | 5 | 第 1094 关起，连续 21 关 | 275-279 / 450-454 / 749-753 / 1025-1029 / 1210 |
| 事物与部件 | 18 | 4 | 第 312 关起，连续 18 关 | 68-72 / 460-464 / 864-868 / 1126-1128 |
| 优劣评价 | 20 | 4 | 第 952 关起，连续 20 关 | 245-249 / 490-494 / 889-893 / 1148-1152 |
| 建立与破坏 | 16 | 4 | 第 1115 关起，连续 16 关 | 280-284 / 500-504 / 894-898 / 1153 |
| 控制与影响 | 18 | 4 | 第 1171 关起，连续 18 关 | 300-304 / 510-514 / 904-908 / 1164-1166 |
| 政治与政府 | 16 | 4 | 第 340 关起，连续 16 关 | 78-82 / 465-469 / 869-873 / 1129 |
| 特殊类别 | 18 | 4 | 第 1238 关起，连续 18 关 | 320-324 / 515-519 / 909-913 / 1167-1169 |
| 竞争与冲突 | 20 | 4 | 第 1151 关起，连续 20 关 | 295-299 / 505-509 / 899-903 / 1159-1163 |
| 经济与金融 | 18 | 4 | 第 377 关起，连续 18 关 | 93-97 / 475-479 / 874-878 / 1130-1132 |
| 认知与理解 | 20 | 4 | 第 611 关起，连续 20 关 | 181-185 / 480-484 / 879-883 / 1138-1142 |
| 饮食与食物 | 19 | 4 | 第 100 关起，连续 19 关 | 16-20 / 455-459 / 859-863 / 1122-1125 |
| 交往与联系 | 13 | 3 | 第 1189 关起，连续 13 关 | 305-309 / 575-579 / 1089-1091 |
| 增长与减少 | 13 | 3 | 第 903 关起，连续 13 关 | 221-225 / 565-569 / 1084-1086 |
| 思考与观点 | 14 | 3 | 第 597 关起，连续 14 关 | 176-180 / 560-564 / 1080-1083 |
| 教育与学习 | 12 | 3 | 第 411 关起，连续 12 关 | 108-112 / 525-529 / 1058-1059 |
| 法律与司法 | 13 | 3 | 第 356 关起，连续 13 关 | 83-87 / 520-524 / 1055-1057 |
| 重要性 | 12 | 3 | 第 940 关起，连续 12 关 | 240-244 / 570-574 / 1087-1088 |
| 专有名词 | 6 | 2 | 第 1232 关起，连续 6 关 | 315-319 / 934 |
| 保护与维持 | 10 | 2 | 第 1131 关起，连续 10 关 | 285-289 / 799-803 |
| 军事与战争 | 8 | 2 | 第 369 关起，连续 8 关 | 88-92 / 637-639 |
| 商业与贸易 | 9 | 2 | 第 395 关起，连续 9 关 | 98-102 / 640-643 |
| 因果与逻辑 | 10 | 2 | 第 916 关起，连续 10 关 | 226-230 / 786-790 |
| 娱乐与休闲 | 7 | 2 | 第 172 关起，连续 7 关 | 38-42 / 630-631 |
| 媒体与传播 | 8 | 2 | 第 452 关起，连续 8 关 | 123-127 / 646-648 |
| 宗教与信仰 | 7 | 2 | 第 488 关起，连续 7 关 | 149-153 / 776-777 |
| 家庭与亲属 | 8 | 2 | 第 52 关起，连续 8 关 | 6-10 / 625-627 |
| 工作与职业 | 7 | 2 | 第 404 关起，连续 7 关 | 103-107 / 644-645 |
| 帮助与合作 | 10 | 2 | 第 1141 关起，连续 10 关 | 290-294 / 804-808 |
| 文学与写作 | 10 | 2 | 第 460 关起，连续 10 关 | 128-132 / 649-653 |
| 服饰与打扮 | 7 | 2 | 第 119 关起，连续 7 关 | 21-25 / 628-629 |
| 正确与错误 | 8 | 2 | 第 972 关起，连续 8 关 | 250-254 / 796-798 |
| 组织与机构 | 10 | 2 | 第 330 关起，连续 10 关 | 73-77 / 632-636 |
| 艺术与绘画 | 6 | 2 | 第 470 关起，连续 6 关 | 133-137 / 774 |
| 计划与安排 | 10 | 2 | 第 930 关起，连续 10 关 | 235-239 / 791-795 |
| 记忆与注意 | 8 | 2 | 第 631 关起，连续 8 关 | 186-190 / 778-780 |
| 音乐与表演 | 6 | 2 | 第 476 关起，连续 6 关 | 138-142 / 775 |
| 体育与运动 | 4 | 1 | 第 484 关起，连续 4 关 | 145-148 |
| 历史与考古 | 3 | 1 | 第 499 关起，连续 3 关 | 158-160 |
| 影视与娱乐 | 2 | 1 | 第 482 关起，连续 2 关 | 143-144 |
| 方法与手段 | 4 | 1 | 第 926 关起，连续 4 关 | 231-234 |
| 节日与习俗 | 4 | 1 | 第 495 关起，连续 4 关 | 154-157 |
| 计算机与信息 | 5 | 1 | 第 447 关起，连续 5 关 | 118-122 |
| 购物与消费 | 2 | 1 | 第 170 关起，连续 2 关 | 36-37 |

<details><summary><b>前 60 关逐关对照（点开看每关的主题与单词）</b></summary>

| 关 | 主题 | 本关单词 |
|---|---|---|
| 1 | 人物与身份 · 1 | senior n. 老年人、individual n. 个人、philosopher n. 哲学家、psychologist n. 心理学家、colleague n. 同事、technician n. 技术员，技师 |
| 2 | 人物与身份 · 2 | slave n. 奴隶、expert adj. 熟练的，内行的、coach n. 教练 v. 训练、dictator n. 独裁者；专制者、caregiver n. 照料者、candidate n. 候选人 |
| 3 | 人物与身份 · 3 | consultant n. 顾问、architect n. 建筑师、resident n. 居民、homemaker n. 主妇、critic n. 批评家，评论家、carpenter n. 木匠 |
| 4 | 人物与身份 · 4 | advocator n. 主张者，倡导者、porter n. 搬运工、agent n. 代理商、racially adv. 种族上地、inspector n. 检查员、applicant n. 申请人 |
| 5 | 人物与身份 · 5 | chef n. 厨师、civilian n. 平民，百姓、humanity n. 人类；人性、detective n. 侦探、identity n. 身份、representative n. 代表 |
| 6 | 家庭与亲属 · 1 | upbringing n. 养育，培养，教养、relative adj. 相对的 n. 亲戚、bride n. 新娘、engagement n. 婚约、household n. 家庭，一家人、couple n. 夫妻，情侣 |
| 7 | 家庭与亲属 · 2 | wedding n. 婚礼、nephew n. 侄子，外甥、brother n. 兄弟；同事，同胞、divorce n. 离婚，离异 v. 离婚、family n. 家，家庭；家族、couple n. 夫妇；一对 |
| 8 | 家庭与亲属 · 3 | married adj. 已婚的；婚姻的、ancestor n. 祖宗，祖先、household n. 家庭，户；家务、marry v. 结婚；娶，嫁、honeymoon n. 蜜月、widow n. 寡妇 |
| 9 | 家庭与亲属 · 4 | cousin n. 堂（或表）兄弟（姐妹）、grandfather n. 祖父；外祖父、heir n. 后嗣，继承人、marriage n. 结婚，婚姻；婚礼、aunt n. 伯母，姑母，姨母、daughter n. 女儿 |
| 10 | 家庭与亲属 · 5 | mother n. 母亲，妈妈、grandmother n. 祖母，外祖母、twin adj. 孪生的 n. 孪生儿、father n. 父亲、parent n. 父亲，母亲，双亲、niece n. 侄女，外甥女 |
| 11 | 身体与健康 · 1 | overweight adj. 超重的、cheek n. 脸颊、exhaust v. 使筋疲力尽、tumor n. 瘤、injure v. 使受伤、choke v. 使窒息 |
| 12 | 身体与健康 · 2 | strain v. 扭伤，拉伤、lap n. 大腿部、hospitalize v. 使住院、pregnant adj. 怀孕的、toothache n. 牙痛，牙疼、gum n. 牙龈 |
| 13 | 身体与健康 · 3 | fatal adj. 致命的，攸关的、prescription n. 处方、appetite n. 欲望，胃口、therapy n. 疗法，治疗、remedy n. 药物、pose v. 摆姿势 n. 姿势 |
| 14 | 身体与健康 · 4 | treatment n. 治疗；对待、organ n. 器官、symptom n. 症状、virus n. 病毒、fatigue n. 疲劳，疲乏、physical adj. 身体上的 |
| 15 | 身体与健康 · 5 | screen v. 筛查、painful adj. 痛苦的；疼痛的、poison v. 使中毒、intake n. 摄入、tremble v. 颤抖、forefinger n. 食指 |
| 16 | 饮食与食物 · 1 | swallow v. 咽下，吞下、snack n. 零食，小吃、canteen n. 食堂、grain n. 谷物，粮食、menu n. 菜单、nutritious adj. 营养的、tasteless adj. 无味的 |
| 17 | 饮食与食物 · 2 | dessert n. 甜点、roast v. 烤，炙；烘、hamburger n. 汉堡包，牛肉饼、toast n. 烤面包 v. 烘，烤、honey n. 蜜，蜂蜜、apple n. 苹果、breakfast n. 早饭，早餐 |
| 18 | 饮食与食物 · 3 | juice n. （水果等）汁，液、paste n. 糊；酱、bean n. 豆，蚕豆、berry n. 浆果（如草莓等）、pear n. 梨子，梨树、flour n. 面粉，粉；粉状物质 |
| 19 | 饮食与食物 · 4 | feed v. 喂（养）；吃饲料、feast n. 盛宴；节日、bread n. 面包；食物，粮食、coffee n. 咖啡，咖啡茶、taste n. 味觉；品味、meat n. 肉 |
| 20 | 饮食与食物 · 5 | dinner n. 正餐，主餐；宴会、sausage n. 香肠，腊肠、carrot n. 胡萝卜、dairy n. 牛奶场；乳制品、grape n. 葡萄；葡萄藤、sour adj. 酸的；脾气坏的 |
| 21 | 服饰与打扮 · 1 | makeup n. 化妆品、hat n. 帽子（一般指有边的）、crown n. 王冠，冕；花冠、fashionable adj. 流行的，时髦的、glove n. 手套、gown n. 长袍，长外衣 |
| 22 | 服饰与打扮 · 2 | clothe v. 穿衣、sleeve n. 袖子，袖套、sweater n. 厚运动衫，毛线衫、uniform adj. 一样的 n. 制服、button n. 扣子；按钮 v. 扣紧、jewel n. 宝石；宝石饰物 |
| 23 | 服饰与打扮 · 3 | fashion n. 风尚；方式、stocking n. 长（筒）袜、wear v. 穿，戴；磨损、pocket n. 衣袋 adj. 袖珍的、clothes n. 衣服，服装、coat n. 外套，上衣；皮毛 |
| 24 | 服饰与打扮 · 4 | clothing n. 衣服、necklace n. 项链，项圈、jacket n. 短上衣，茄克衫、lace n. 鞋带，系带；花边、collar n. 衣领；项圈、boot n. 靴子，长统靴 |
| 25 | 服饰与打扮 · 5 | helmet n. 头盔，钢盔、belt n. 腰带、cloak n. 斗篷；覆盖（物）、dress n. 服装、cap n. 帽子，便帽；帽状物、overcoat n. 外衣，大衣 |
| 26 | 居住与建筑 · 1 | dormitory n. 宿舍、accommodate v. 容纳；提供住宿、clinic n. 诊所、apartment n. 公寓、gym n. 体育馆，健身房、garage n. 车库、plant n. 工厂 |
| 27 | 居住与建筑 · 2 | mill n. 工厂、lodge v. 住宿，暂住、lobby n. 大堂 v. 游说、laundry n. 要洗的衣物；洗衣房、tent n. 帐篷、archive n. 档案馆，档案室；档案 |
| 28 | 居住与建筑 · 3 | construction n. 建筑、accommodation n. 住处；工作场所、palace n. 宫，宫殿、hut n. 小屋，棚屋、monument n. 纪念碑；纪念馆、stair n. 楼梯 |
| 29 | 居住与建筑 · 4 | lodge v. 暂住，借宿，投宿、apartment n. 一套公寓房间、inhabit v. 居住于，栖息于、prison n. 监狱；监禁、barn n. 谷仓；牲口棚、home n. 家；家乡 |
| 30 | 居住与建筑 · 5 | chimney n. 烟囱，烟筒、library n. 图书馆；藏书、laundry n. 洗衣房，洗衣店、temple n. 圣堂，神殿，庙宇、floor n. 地板；楼层、cellar n. 地窖，地下室 |
| 31 | 交通与出行 · 1 | vehicle n. 车辆、aviate v. 驾驶飞机、takeoff n. 起飞、terminal n. 终点站，航空站、steer v. 驾驶、transportation n. 运输、overtake v. 超车 |
| 32 | 交通与出行 · 2 | aviation n. 航空、refuel v. （给）加油，加燃料、lane n. （乡间）小路；跑道、tour n. 旅行 v. 旅游、tunnel n. 隧道，坑道，地道、ambulance n. 救护车 |
| 33 | 交通与出行 · 3 | bike n. 自行车 v. 骑自行车、canoe n. 独木舟，皮艇，划子、lorry n. 运货汽车，卡车、elevator n. 电梯；升降机、steer v. 驾驶、plane n. 飞机；平面 |
| 34 | 交通与出行 · 4 | destination n. 目的地，终点、route n. 路，路线、journey n. 旅行，旅程、van n. 大篷车，运货车、steamer n. 轮船，汽船、liner n. 班船，班机 |
| 35 | 交通与出行 · 5 | transport v. 运输 n. 运输、harbour n. 海港，港口 v. 庇护、railroad n. 铁路、aeroplane n. 飞机、trail n. 小径；痕迹 v. 跟踪、passage n. 通路，通道；通过 |
| 36 | 购物与消费 · 1 | grocery n. 杂货店、discount n. 折扣、purchase n. 买，购买 v. 买、expensive adj. 昂贵的，花钱多的、inexpensive adj. 花费不多的，廉价的 |
| 37 | 购物与消费 · 2 | buy v. 买，购买、grocery n. 食品杂货店、purchase v. 购买；采购、discount n. 折扣 vt. 打折、coupon n. 优惠券 |
| 38 | 娱乐与休闲 · 1 | gambling n. 赌博、entertaining adj. 有趣的，使人愉快的、sightseeing n. 观光、entertainment n. 娱乐、leisure n. 悠闲，空闲、amusing adj. 有趣的，好玩儿的、fun n. 乐趣，娱乐；玩笑 |
| 39 | 娱乐与休闲 · 2 | entertain v. 使欢乐；招待、amuse v. 逗乐、play v. 玩，游戏；演奏、adventure n. 冒险；惊险活动、bet v. 打赌 n. 打赌、magic n. 魔法，巫术；戏法、stake n. 赌金；桩 |
| 40 | 娱乐与休闲 · 3 | park n. 公园；停车场、playground n. 操场，运动场、bar n. 酒吧；条，杆、hunt n. 打猎；搜寻 v. 追猎、picnic n. 郊游，野餐 v. 野餐、balloon n. 气球，玩具气球 |
| 41 | 娱乐与休闲 · 4 | chess n. 棋；国际象棋、kite n. 风筝、party n. 聚会；党，党派、leisure n. 空闲时间；悠闲、game n. 游戏；比赛、hobby n. 业余爱好，癖好 |
| 42 | 娱乐与休闲 · 5 | resort vi. 诉诸 n. 度假胜地、joke n. 笑话 v. 说笑话、sightseeing n. 观光，游览、adventure n. 奇遇；冒险，冒险活动、entertainment n. 娱乐；款待、entertain vt. 使欢乐；招待，请客 |
| 43 | 日常用品与工具 · 1 | device n. 装置，设备、album n. 粘贴簿，集邮簿，册、groundsheet n. 防潮布、instrument n. 仪器，器械，工具、dishwasher n. 洗碗机、facility n. 设施 |
| 44 | 日常用品与工具 · 2 | tank n. 油箱，水箱、bench n. 长凳，条凳、rug n. 小地毯；毛毯、mirror n. 镜子 v. 反映、net n. 网，网状物；互联网、scale n. 天平，磅秤，秤 |
| 45 | 日常用品与工具 · 3 | refrigerator n. 冰箱，冷藏库、notebook n. 笔记本，期票簿、lever n. 控制杆；杆，杠杆、ribbon n. 缎带，丝带；带、pump n. 泵 v. 抽水，打气、board n. 板 v. 登上 |
| 46 | 日常用品与工具 · 4 | jar n. 罐子，坛子，广口瓶、ornament n. 装饰物；装饰、bell n. 钟，铃，门铃；钟声、pipe n. 管子，导管；烟斗、basket n. 篮，篓，筐、clasp n. 扣子，钩子；别针 |
| 47 | 日常用品与工具 · 5 | clock n. 钟，仪表、ladder n. 梯子，梯状物、fork n. 叉子；分叉、scissors n. 剪刀，剪子、hook n. 钩，挂钩 v. 钩住、purse n. 钱包，小钱袋；手袋 |
| 48 | 动物与植物 · 1 | reproductive adj. 生殖的，再生的、bacteria n. 细菌、dragon n. 龙、cow n. 母牛，奶牛；母兽、brood n. 同窝幼鸟 v. 孵蛋、hatch v. （蛋）孵化；孵出 |
| 49 | 动物与植物 · 2 | nest n. 巢；窝，穴、animal n. 动物；兽、hedge n. 篱笆，树篱、hare n. 野兔、fish n. 鱼；鱼肉 v. 钓鱼、stem n. 茎；树干 v. 起源 |
| 50 | 动物与植物 · 3 | hawk n. 鹰，隼、duck n. 鸭子；鸭肉、leaf n. 叶，叶子、lion n. 狮子；勇猛的人、frog n. 蛙、cattle n. 牛；牲口，家畜 |
| 51 | 动物与植物 · 4 | mushroom n. 蘑菇，菌类植物、creature n. 生物，动物、bloom n. 花；开花，开花期、bud n. 芽，萌芽；蓓蕾、bristle n. 短而硬的毛；鬃毛、monkey n. 猴子，猿 |
| 52 | 动物与植物 · 5 | goat n. 山羊、pigeon n. 鸽子、beast n. 兽，野兽、fox n. 狐狸；狡猾的人、cock n. 公鸡、paw n. 脚爪，爪子 |
| 53 | 自然与天气 · 1 | shade n. 阴凉处、tide n. 潮水、atmosphere n. 气氛；大气、visibility n. 能见度、planet n. 行星、beach n. 海滩，湖滩，河滩、lake n. 湖 |
| 54 | 自然与天气 · 2 | scenery n. 风景；舞台布景、brook n. 小河，溪流、meadow n. 草地，牧草地、land n. 土地；陆地 v. 上岸、dew n. 露，露水、cold adj. 冷的 n. 感冒、mountain n. 山，山岳；山脉 |
| 55 | 自然与天气 · 3 | cliff n. 悬崖，峭壁、tide n. 潮，潮汐；潮流、blow v. 吹；吹动、flash n. 闪光 v. 闪，闪烁、mist n. 薄雾、atmospheric adj. 大气的；大气层的 |
| 56 | 自然与天气 · 4 | burn v. 烧，燃烧 n. 烧伤、rain n. 雨，雨水 v. 下雨、atmosphere n. 气氛；大气、solar adj. 太阳的，日光的、cloud n. 云；云状物、glitter v. 闪闪发光 n. 闪光 |
| 57 | 自然与天气 · 5 | spark n. 火花，火星、steam n. 蒸汽 v. 蒸发；蒸、fog n. 雾；烟雾，尘雾、breeze n. 微风，和风、flood n. 洪水 v. 淹没、desert n. 沙漠 v. 离弃；擅离 |
| 58 | 物质与材料 · 1 | dust n. 灰尘、plastic adj. 塑料的、alcohol n. 酒精、particle n. 微粒、mineral n. 矿物、matter n. 事情；物质 v. 要紧 |
| 59 | 物质与材料 · 2 | water n. 水 v. 使湿，灌溉、mineral n. 矿物、steel n. 钢，钢铁、explosive n. 炸药 adj. 爆炸的、wax n. 蜡，蜂蜡、leather n. 皮革；皮革制品 |
| 60 | 物质与材料 · 3 | aluminium n. 铝、alloy n. 合金；（金属的）成色、cotton n. 棉花；棉布、lumber n. 木材；木料、rag n. 破布，碎布，抹布、liquid n. 液体 |

</details>

## 三、六级 逐域位置（原来整段 → 现在最多 15 段交替）

| 语义域 | 关数 | 现在分几段 | 重排前的位置 | 现在出现在哪些关（最多列 6 段） |
|---|---|---|---|---|
| 性质与特征 | 73 | 15 | 第 541 关起，连续 73 关 | 195-199 / 309-313 / 379-383 / 444-448 / 499-503 / 554-558 …（共 15 段） |
| 语言与交流 | 36 | 8 | 第 467 关起，连续 36 关 | 180-184 / 314-318 / 459-463 / 560-564 / 654-658 / 779-783 …（共 8 段） |
| 人物与身份 | 32 | 7 | 第 1 关起，连续 32 关 | 1-5 / 319-323 / 504-508 / 604-608 / 744-748 / 838-842 …（共 7 段） |
| 性格与品质 | 26 | 6 | 第 382 关起，连续 26 关 | 156-160 / 334-338 / 539-543 / 669-673 / 804-808 / 929 |
| 情绪与感受 | 27 | 6 | 第 355 关起，连续 27 关 | 151-155 / 329-333 / 534-538 / 664-668 / 799-803 / 927-928 |
| 操作与处理 | 30 | 6 | 第 793 关起，连续 30 关 | 258-262 / 344-348 / 549-553 / 679-683 / 814-818 / 934-938 |
| 移动与位移 | 29 | 6 | 第 764 关起，连续 29 关 | 253-257 / 339-343 / 544-548 / 674-678 / 809-813 / 930-933 |
| 身体与健康 | 26 | 6 | 第 36 关起，连续 26 关 | 9-13 / 324-328 / 529-533 / 659-663 / 794-798 / 926 |
| 关系与异同 | 22 | 5 | 第 728 关起，连续 22 关 | 243-247 / 374-378 / 590-594 / 784-788 / 924-925 |
| 变化与发展 | 22 | 5 | 第 638 关起，连续 22 关 | 205-209 / 369-373 / 585-589 / 774-778 / 922-923 |
| 态度与意愿 | 21 | 5 | 第 408 关起，连续 21 关 | 161-165 / 354-358 / 570-574 / 759-763 / 916 |
| 数量与度量 | 21 | 5 | 第 503 关起，连续 21 关 | 185-189 / 359-363 / 575-579 / 764-768 / 917 |
| 状态与情况 | 24 | 5 | 第 614 关起，连续 24 关 | 200-204 / 364-368 / 580-584 / 769-773 / 918-921 |
| 科学技术 | 21 | 5 | 第 285 关起，连续 21 关 | 109-113 / 349-353 / 565-569 / 754-758 / 915 |
| 事物与部件 | 18 | 4 | 第 190 关起，连续 18 关 | 64-68 / 399-403 / 694-698 / 883-885 |
| 优劣评价 | 18 | 4 | 第 701 关起，连续 18 关 | 233-237 / 429-433 / 724-728 / 895-897 |
| 动物与植物 | 20 | 4 | 第 125 关起，连续 20 关 | 44-48 / 389-393 / 649-653 / 876-880 |
| 思考与观点 | 16 | 4 | 第 429 关起，连续 16 关 | 166-170 / 414-418 / 709-713 / 889 |
| 控制与影响 | 20 | 4 | 第 889 关起，连续 20 关 | 288-292 / 439-443 / 734-738 / 903-907 |
| 日常用品与工具 | 17 | 4 | 第 108 关起，连续 17 关 | 39-43 / 384-388 / 644-648 / 869-870 |
| 时间与频率 | 17 | 4 | 第 524 关起，连续 17 关 | 190-194 / 424-428 / 719-723 / 893-894 |
| 法律与司法 | 16 | 4 | 第 225 关起，连续 16 关 | 79-83 / 404-408 / 699-703 / 886 |
| 特殊类别 | 17 | 4 | 第 928 关起，连续 17 关 | 304-308 / 449-453 / 739-743 / 908-909 |
| 空间与方位 | 17 | 4 | 第 173 关起，连续 17 关 | 59-63 / 394-398 / 684-688 / 881-882 |
| 竞争与冲突 | 20 | 4 | 第 869 关起，连续 20 关 | 283-287 / 434-438 / 729-733 / 898-902 |
| 经济与金融 | 17 | 4 | 第 247 关起，连续 17 关 | 89-93 / 409-413 / 704-708 / 887-888 |
| 认知与理解 | 18 | 4 | 第 445 关起，连续 18 关 | 171-175 / 419-423 / 714-718 / 890-892 |
| 交往与联系 | 12 | 3 | 第 909 关起，连续 12 关 | 293-297 / 524-528 / 867-868 |
| 交通与出行 | 11 | 3 | 第 91 关起，连续 11 关 | 28-32 / 469-473 / 824 |
| 增长与减少 | 12 | 3 | 第 660 关起，连续 12 关 | 210-214 / 489-493 / 845-846 |
| 居住与建筑 | 14 | 3 | 第 77 关起，连续 14 关 | 23-27 / 464-468 / 820-823 |
| 建立与破坏 | 15 | 3 | 第 837 关起，连续 15 关 | 268-272 / 519-523 / 857-861 |
| 政治与政府 | 12 | 3 | 第 213 关起，连续 12 关 | 74-78 / 484-488 / 843-844 |
| 物质与材料 | 14 | 3 | 第 159 关起，连续 14 关 | 54-58 / 479-483 / 829-832 |
| 程度与强度 | 14 | 3 | 第 750 关起，连续 14 关 | 248-252 / 509-513 / 849-852 |
| 自然与天气 | 14 | 3 | 第 145 关起，连续 14 关 | 49-53 / 474-478 / 825-828 |
| 获取与给予 | 14 | 3 | 第 823 关起，连续 14 关 | 263-267 / 514-518 / 853-856 |
| 重要性 | 12 | 3 | 第 689 关起，连续 12 关 | 228-232 / 494-498 / 847-848 |
| 饮食与食物 | 11 | 3 | 第 62 关起，连续 11 关 | 14-18 / 454-458 / 819 |
| 保护与维持 | 7 | 2 | 第 852 关起，连续 7 关 | 273-277 / 636-637 |
| 军事与战争 | 6 | 2 | 第 241 关起，连续 6 关 | 84-88 / 559 |
| 功能词 | 6 | 2 | 第 921 关起，连续 6 关 | 298-302 / 643 |
| 商业与贸易 | 8 | 2 | 第 264 关起，连续 8 关 | 94-98 / 600-602 |
| 因果与逻辑 | 7 | 2 | 第 672 关起，连续 7 关 | 215-219 / 623-624 |
| 媒体与传播 | 9 | 2 | 第 310 关起，连续 9 关 | 118-122 / 611-614 |
| 宗教与信仰 | 8 | 2 | 第 341 关起，连续 8 关 | 140-144 / 620-622 |
| 工作与职业 | 6 | 2 | 第 272 关起，连续 6 关 | 99-103 / 603 |
| 帮助与合作 | 10 | 2 | 第 859 关起，连续 10 关 | 278-282 / 638-642 |
| 教育与学习 | 7 | 2 | 第 278 关起，连续 7 关 | 104-108 / 609-610 |
| 文学与写作 | 9 | 2 | 第 319 关起，连续 9 关 | 123-127 / 615-618 |
| 正确与错误 | 9 | 2 | 第 719 关起，连续 9 关 | 238-242 / 627-630 |
| 计划与安排 | 7 | 2 | 第 682 关起，连续 7 关 | 223-227 / 625-626 |
| 音乐与表演 | 6 | 2 | 第 331 关起，连续 6 关 | 131-135 / 619 |
| 专有名词 | 1 | 1 | 第 927 关起，连续 1 关 | 303 |
| 体育与运动 | 2 | 1 | 第 339 关起，连续 2 关 | 138-139 |
| 历史与考古 | 3 | 1 | 第 352 关起，连续 3 关 | 148-150 |
| 娱乐与休闲 | 4 | 1 | 第 104 关起，连续 4 关 | 35-38 |
| 家庭与亲属 | 3 | 1 | 第 33 关起，连续 3 关 | 6-8 |
| 影视与娱乐 | 2 | 1 | 第 337 关起，连续 2 关 | 136-137 |
| 方法与手段 | 3 | 1 | 第 679 关起，连续 3 关 | 220-222 |
| 服饰与打扮 | 4 | 1 | 第 73 关起，连续 4 关 | 19-22 |
| 组织与机构 | 5 | 1 | 第 208 关起，连续 5 关 | 69-73 |
| 艺术与绘画 | 3 | 1 | 第 328 关起，连续 3 关 | 128-130 |
| 节日与习俗 | 3 | 1 | 第 349 关起，连续 3 关 | 145-147 |
| 计算机与信息 | 4 | 1 | 第 306 关起，连续 4 关 | 114-117 |
| 记忆与注意 | 4 | 1 | 第 463 关起，连续 4 关 | 176-179 |
| 购物与消费 | 2 | 1 | 第 102 关起，连续 2 关 | 33-34 |

<details><summary><b>前 60 关逐关对照（点开看每关的主题与单词）</b></summary>

| 关 | 主题 | 本关单词 |
|---|---|---|
| 1 | 人物与身份 · 1 | stockbroker n. 股票经纪人、supervisor n. 督导、recruiter n. 招聘者、carpenter n. 木匠、vegetarian n. 素食主义者、contestant n. 参赛者、drummer n. 鼓手 |
| 2 | 人物与身份 · 2 | representative n. 代表、genius n. 天才、resident n. 居民、commentator n. 评论员、philosopher n. 哲学家、adventurer n. 冒险者 |
| 3 | 人物与身份 · 3 | dweller n. 居住者、addict n. 上瘾者；入迷的人、applicant n. 申请者、counselor n. 咨询顾问、immigrant n. 移民、eyewitness n. 目击者 |
| 4 | 人物与身份 · 4 | entrepreneur n. 企业家、attendant n. 服务人员、peer n. 同龄人、donor n. 捐赠者、accountant n. 会计、layman n. 门外汉 |
| 5 | 人物与身份 · 5 | victim n. 受害者、elite n. 精英、detective n. 侦探、coach n. 教练、receptionist n. 接待员、legislator n. 立法者 |
| 6 | 家庭与亲属 · 1 | descendant n. 子孙后代、household n. 家庭，一家人、bride n. 新娘、descent n. 血统，后裔、bridegroom n. 新郎、engagement n. 婚约；约会 |
| 7 | 家庭与亲属 · 2 | descendant n. 子孙，后裔、offspring n. 儿女，子孙，后代、divorce n. 离婚 v. 使分离、clan n. 宗族，家族、relative adj. 相对的 n. 亲戚、household adj. 家庭的 n. 家务 |
| 8 | 家庭与亲属 · 3 | descendant n. 后裔，后代、paternity n. 父权，父子关系、spouse n. 配偶、engagement n. 订婚，婚约；约会、offspring n. 子女，子孙，后代；崽、ancestor n. 祖先、heir n. 继承人 |
| 9 | 身体与健康 · 1 | chronic adj. 长期的；慢性的、obesity n. 肥胖、nap n. 小睡、robust adj. 强健的、diagnose v. 诊断、vessel n. 血管 |
| 10 | 身体与健康 · 2 | infectious adj. 感染的，传染的、virus n. 病毒、chronically adv. 慢性地；持久地、symptom n. 症状、therapy n. 疗法、prescribe v. 开处方 |
| 11 | 身体与健康 · 3 | infect v. 感染、inhale v. 吸入、perish v. 死亡、fatal adj. 致命的、bruise v. 使碰伤，擦伤、fatigue n. 疲惫 |
| 12 | 身体与健康 · 4 | epidemic n. 流行病、mortality n. 死亡率、cripple n. 残疾，跛子、allergic adj. 过敏的、seasick adj. 晕船的、suicide n. 自杀 |
| 13 | 身体与健康 · 5 | paralyze v. 瘫痪，麻痹、pose v. 造成；摆姿势、dysfunction n. 失常、sanitation n. 卫生环境，卫生设备、ankle n. 脚踝、surgical adj. 手术的 |
| 14 | 饮食与食物 · 1 | recipe n. 食谱；秘诀、tasteless adj. 没有味道的、chew v. 咀嚼；深思、cafeteria n. 自助餐厅、vitamin n. 维他命、nutritious adj. 有营养的、flavor n. 口味 |
| 15 | 饮食与食物 · 2 | diet n. 日常饮食，日常食物、chop n. 一块排骨，肉块、banquet n. 宴会，盛会，酒席、pickle n. 腌制食品，泡菜、spice n. 香料，调味品、bacon n. 咸猪肉，熏猪肉、luncheon n. 午宴，午餐，便宴 |
| 16 | 饮食与食物 · 3 | vitamin n. 维生素，维他命、devour v. 吞食；吞灭，毁灭、dessert n. 甜点心、nourish v. 提供养分，养育、garlic n. 蒜，大蒜、sweeten v. 使变甜，变甜 |
| 17 | 饮食与食物 · 4 | nourishment n. 食物；营养（情况）、raisin n. 葡萄干、pumpkin n. 南瓜、nut n. 坚果；螺母、snack n. 快餐，小吃、mustard n. 芥子，芥末 |
| 18 | 饮食与食物 · 5 | yeast n. 酵母、soy n. 大豆，黄豆；酱油、ginger n. 姜，生姜、ham n. 火腿、peel n. 果皮，蔬菜皮、cereal n. 谷类，五谷，禾谷 |
| 19 | 服饰与打扮 · 1 | blouse n. 女衬衫、badge n. 标记，徽章、gown n. 长袍、costume n. 服装、makeup n. 化妆品；构成、jewellery n. 珠宝，珠宝饰物、garment n. 衣服；服装，衣着 |
| 20 | 服饰与打扮 · 2 | jean n. 斜纹布；牛仔裤、underwear n. 衫衣，内衣，贴身衣、cape n. 披肩，斗篷；海角、blouse n. 女衬衫；罩衫、mitten n. 连指手套、pants n. 裤子；男用短衬裤、line v. 加衬里于 |
| 21 | 服饰与打扮 · 3 | badge n. 徽章，像章；标志、lipstick n. 唇膏，口红、lining n. （衣服里的）衬里、frock n. （女）连衣裙、pyjamas n. （宽大的）睡衣裤、lace n. 花边；鞋带 vt. 系紧、gown n. 长袍；礼服 |
| 22 | 服饰与打扮 · 4 | outfit n. 全套服装；装备、perfume n. 香水；香气、cape n. 斗篷，披肩；海角，岬、veil n. 面纱 vt. 掩盖、garment n. 衣服、costume n. 服装；戏装 |
| 23 | 居住与建筑 · 1 | cornerstone n. 基石、apartment n. 公寓、dorm n. 宿舍、compartment n. 车厢，隔间、landmark n. 地标、motel n. 汽车旅馆、accommodate v. 容纳；适应 |
| 24 | 居住与建筑 · 2 | construction n. 建筑、scaffolding n. 脚手架、garage n. 车库、lounge n. 休息室、threshold n. 门槛、flat adj. 平的 n. 公寓 |
| 25 | 居住与建筑 · 3 | urban adj. 城市的，都市的、jail n. 监狱、skyscraper n. 摩天大楼、platform n. 平台；站台、clinic n. 诊所；门诊、dome n. 圆屋顶，拱顶 |
| 26 | 居住与建筑 · 4 | balcony n. 阳台；楼厅，楼座、closet n. 壁橱；小房间、theatre n. 剧场，戏院、entry n. 入口，通道；条目、laundry n. 送洗衣店去洗的东西、pantry n. 食品柜，餐具室 |
| 27 | 居住与建筑 · 5 | lobby n. 前厅，（剧院的）门廊、motel n. 汽车游客旅馆、garage n. 汽车修理站、terrace n. 平台，阳台，露台、skyscraper n. 摩天楼、nursery n. 托儿所；苗圃 |
| 28 | 交通与出行 · 1 | destination n. 目的地、navigate v. 航行，驾驶、bridge v. 架桥于…上；缩小、derail v. 脱轨、vehicle n. 车辆、shuttle n. 航天飞机 |
| 29 | 交通与出行 · 2 | pave v. 铺路、expedition n. 探险，远征、helicopter n. 直升飞机、freight n. 货运；运费、yacht n. 游艇，快艇、tanker n. 油船；空中加油飞机 |
| 30 | 交通与出行 · 3 | harbour n. 港口 v. 怀有；窝藏、cruise v. 巡航、footpath n. 小路，人行道、expedition n. 探险；探险队、trolley n. 手推车；有轨电车、motorway n. 汽车道，快车路 |
| 31 | 交通与出行 · 4 | bypass n. 旁通管 v. 绕过、wharf n. 码头，停泊所、junction n. 连接点，汇合处、excursion n. 远足；短途旅行、flight n. 飞行；航班、bridge v. 架桥于，用桥连接 |
| 32 | 交通与出行 · 5 | glider n. 滑翔机；滑翔导弹、seaport n. 海港，港口，港市、aviation n. 飞行（术）、ferry n. 渡船 v. 运送、passport n. 护照；手段、shipwreck n. 船舶失事 |
| 33 | 购物与消费 · 1 | purchase n. 购买、mall n. 商场、grocery n. 杂货店、discount n. 折扣 v. 打折扣卖 |
| 34 | 购物与消费 · 2 | purchase vt. 购买、discount n. 折扣 vt. 打折、mall n. 购物中心、refund n. 退款 vt. 退还、coupon n. 优惠券；票证、expend vt. 花费，消费；消耗 |
| 35 | 娱乐与休闲 · 1 | diversion n. 转移；娱乐、spectacle n. 景象、host v. 主持、adventure n. 冒险、resort n. 度假胜地、hike v. 作长途徒步旅行 |
| 36 | 娱乐与休闲 · 2 | diversion n. 转移；消遣、entertainment n. 娱乐；招待、amusement n. 乐趣；娱乐，消遣、recreation n. 消遣，娱乐活动、circus n. 马戏；马戏团、souvenir n. 纪念品、gamble n. 赌博 v. 冒险 |
| 37 | 娱乐与休闲 · 3 | spectacle n. 场面，景象；奇观、entertainment n. 娱乐；招待、pastime n. 消遣，娱乐、circus n. 马戏团、sightseeing n. 观光，游览、leisure n. 空闲时间；悠闲 |
| 38 | 娱乐与休闲 · 4 | resort vi. 诉诸；求助 n. 胜地、diversion n. 转移；消遣、gamble v. 赌博；冒险 n. 赌博、recreation n. 娱乐活动，消遣、hike v. 徒步旅行 n. 增加、spectacle n. 壮观；景象 |
| 39 | 日常用品与工具 · 1 | stove n. 炉子、facility n. 设施、mask n. 面具 v. 掩饰，掩盖、plough n. 犁、needle n. 指针；针、ornament n. 装饰物 |
| 40 | 日常用品与工具 · 2 | box n. 盒子；箱子、hose n. 软管；长筒袜、sofa n. 长沙发，沙发、album n. 相册；专辑、cutter n. 用于切割的器械、hook v. （使）钩住，挂住 |
| 41 | 日常用品与工具 · 3 | printer n. 打印机；印刷工、wardrobe n. 衣柜，衣橱，藏衣室、cradle n. 摇篮；发源地、cigar n. 雪茄烟，叶卷烟、suitcase n. 手提箱，衣箱、incense n. 香，熏香；香气 |
| 42 | 日常用品与工具 · 4 | taper n. 细小的蜡烛；微光、stationery n. 信笺，信纸；文具、lever n. 杠杆；手段、projector n. 投影仪；探照灯、earthenware n. 陶器、pedal n. 踏板；脚蹬 |
| 43 | 日常用品与工具 · 5 | couch n. 睡椅，长沙发椅、utensil n. 器皿，用具、siren n. 汽笛，警报器、jack n. 起重器；传动装置、flask n. 瓶、tack n. 平头钉 v. 钉住 |
| 44 | 动物与植物 · 1 | stray n. 走失的动物、dolphin n. 海豚、turtle n. 海龟、species n. 物种、cropland n. 农田、pasture n. 牧场 |
| 45 | 动物与植物 · 2 | bloom v. 开花，茂盛、hatch v. 孵化、wildlife n. 野生动物、bacteria n. 细菌、reproduction n. 繁殖、breed v. 繁殖；导致 |
| 46 | 动物与植物 · 3 | owl n. 猫头鹰、biodiversity n. 生物多样性、woodpecker n. 啄木鸟、nest v. 筑巢、stalk n. 主茎，叶柄、bamboo n. 竹；竹杆，竹棍 |
| 47 | 动物与植物 · 4 | species n. 种，物种；种类、grasshopper n. 蚱蜢，蝗虫，蚂蚱、reproduction n. 再生（产）；繁殖、tulip n. 郁金香、domestic adj. 家养的、cock n. 公鸡 |
| 48 | 动物与植物 · 5 | crab n. 蟹；蟹肉 v. 捕蟹、puppy n. 小狗；幼小的动物、bacterium n. 细菌、snail n. 蜗牛；行动缓慢的人、graze v. 喂草；放牧（牲畜）、moss n. 苔藓，地衣 |
| 49 | 自然与天气 · 1 | wildfire n. 野火、spark n. 火花、polar adj. 极地的、atmosphere n. 气氛；大气（层）、tropical adj. 热带的、lawn n. 草坪、erupt v. 爆发；喷出 |
| 50 | 自然与天气 · 2 | flame n. 火焰、drought n. 干旱、coastal adj. 海滨的、marine adj. 海洋的、turbulence n. 气流；骚乱、gust n. 阵风，一阵狂风、marsh n. 沼泽地，湿地 |
| 51 | 自然与天气 · 3 | lunar adj. 月亮的、cosmic adj. 宇宙的、cosmos n. 宇宙、wind n. 风、shower v. 下阵雨，使湿透、gleam n. 微光 v. 发微光、blaze v. 使燃烧，燃烧 |
| 52 | 自然与天气 · 4 | ice v. 使成冰，结冰、humidity n. 湿气；湿度、tropic n. 回归线；热带地区、tropical adj. 热带的、sight n. 景象；名胜，风景、basin n. 盆地；流域 |
| 53 | 自然与天气 · 5 | polar adj. 极地的；两极的、ripple n. 涟漪，细浪，波纹、solar adj. 太阳的；太阳能的、hurricane n. 飓风，十二级风、inlet n. 水湾；进口、reef n. 礁，礁石，暗礁 |
| 54 | 物质与材料 · 1 | substance n. 物质、material n. 材料、fabric n. 布料、stuff n. 材料，东西、fertilizer n. 肥料、particle n. 粒子，微粒 |
| 55 | 物质与材料 · 2 | uranium n. 铀、oxide n. 氧化物、zinc n. 锌 vt. 镀锌、ivory n. 象牙（质）；乳白色、filth n. 污物；淫秽、tile n. 瓷砖；瓦片 |
| 56 | 物质与材料 · 3 | hydrocarbon n. 烃，碳氢化合物、ferrous adj. 铁的；亚铁的、charcoal n. 炭，木炭；生物炭、plastic adj. 塑料的；塑性的、velvet n. 天鹅绒、quartz n. 石英 |
| 57 | 物质与材料 · 4 | graphite n. 石墨，石墨电极、whitewash n. 石灰水 v. 粉饰、limestone n. 石灰石、hide n. 生皮，兽皮，皮革、gravel n. 砾石；砂砾、pitch n. 沥青 |
| 58 | 物质与材料 · 5 | bronze n. 青铜色、foam n. 泡沫塑料；泡沫、nickel n. 镍；镍币、sodium n. 钠、dust n. 灰尘、polymer n. 聚合物，多聚物 |
| 59 | 空间与方位 · 1 | neighborhood n. 附近、frontier n. 边境、spot n. 斑点；地点 v. 发现、worldwide adv. 世界范围地，全世界、orbit n. 轨道、orient v. 使确定方向；使适应 |
| 60 | 空间与方位 · 2 | suburban adj. 郊区的、overlap v. 重叠、domain n. 领域、position v. 安放 n. 位置；职位、territory n. 领域，领土、outermost adj. 最外面的，最远的 |

</details>

## 四、怎么验证

```bash
# 词库与静态页都打进 jar，必须重新打包 + 重启才生效
STORY_DB_PATH=/Users/renfufei/LLM_ALL/STORY_DB/data ./start_web.sh

# 打开 http://localhost:1888/learn/word-match → 选「四级」
#   前 20 关观感：人物与身份 ×5 → 家庭与亲属 ×5 → 身体与健康 ×5 → 饮食与食物 ×5

# 硬断言：同类连排 ≤5，且语义域出现段数 > 域数×3（防「整段排」这种假交错）
mvn test -Dtest=CetWordBankTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 五、怎么回滚

- 整册顺序回滚：用 `.workbuddy/backup/level-interleave-20260927-110035/` 里的 `cet-words.json` 覆盖 `src/main/resources/learn/cet-words.json`，小程序快照用同目录 `miniprogram-data/levels-college.js` 覆盖；
- 或调 `scripts/learn/build_cet_words.py` 的 `MAX_SAME_DOMAIN_RUN`（当前 5）：调大＝同类更集中，调小＝更分散；设成极大值（如 999）就回到「按域整段排」的原状。

> 提醒：改完顺序若整册重跑生成链，**进度指纹要再升一版**（Web 页 `dataSignature()` + 小程序 `utils/bank.js#signature`），否则旧进度会张冠李戴。

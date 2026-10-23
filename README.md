# Story Creator · AI 网文创作工坊

**你出想法，AI 出初稿，你拍板定稿。**

一个跑在自己电脑上的中文小说创作台。把脑子里的那点灵光、喜欢的主角类型、绝不能碰的雷点告诉它，它就会按「世界观 → 角色 → 大纲 → 章节 → 润色 → 校对」的顺序一章一章往下写。每一步都摆在你面前——可以改、可以重写一遍、也可以干脆放手让它一口气写完。

写完能导出 Markdown、TXT、PDF、EPUB，手机、Kindle、微信读书都能打开。

另外还顺带做了一桌小玩意儿：单词配对、成语填空、近义词、反义词、乘法口诀、数独、方块爆破，网页和微信小程序都能玩。

<p>
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-orange">
  <img alt="Spring Boot 3.3" src="https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen">
  <img alt="无需前端构建" src="https://img.shields.io/badge/前端-零构建-blue">
  <img alt="内嵌数据库" src="https://img.shields.io/badge/数据库-H2%20内嵌-lightgrey">
</p>

## 目录

- [为什么值得试一下](#为什么值得试一下)
- [三分钟跑起来](#三分钟跑起来)
- [写一本小说](#写一本小说)
- [不想一步步点？交给它自己跑](#不想一步步点交给它自己跑)
- [写完之后](#写完之后)
- [玩着学](#玩着学)
- [常见问题](#常见问题)
- [给开发者](#给开发者)
- [延伸文档](#延伸文档)

## 为什么值得试一下

大多数 AI 写作工具停在一问一答：你贴一段要求，它给你一段文字，然后上下文就散了。Story Creator 想解决的是后半段——**几十万字的长篇怎么保持不崩**。

它的做法很土但有效：把世界观、角色卡、卷纲、章纲、上一章正文都存下来，写第 37 章时自动把这些喂给模型。于是主角不会突然换个性格，上一章埋的伏笔这一章还记得，第 20 章写的门派规矩到第 40 章依然成立。

具体来说：

- **每一步都能插手**——不喜欢这个角色？改。大纲第三卷太水？重生成。不是全有或全无。
- **不同步骤可以用不同模型**——大纲挑最强的，正文挑又快又便宜的，钱花在刀刃上。
- **指导可以沉淀**——这一本里写的「对白别太文绉绉」，可以存成通用指导，下一本直接套。
- **写崩了能救**——支持整章扩写、润色、校对，也支持导入现成的 TXT 让 AI 反推出世界观和大纲。

## 三分钟跑起来

### 1. 装两样东西

- [Java 21](https://adoptium.net/) 或更高
- [Maven 3.8+](https://maven.apache.org/)

数据库不用装，用的是内嵌 H2，数据就放在项目下的 `./data/` 目录里。

### 2. 启动

```bash
./start_web.sh
```

它会自动打包 jar 并在后台把服务拉起来，端口 **1888**，日志写到 `app.log`。

想在前台跑、看实时日志也行：

```bash
mvn spring-boot:run     # 端口 8080
```

然后浏览器打开 <http://localhost:1888>（前台模式则是 8080）。

### 3. 接上 AI 的大脑

**这一步不做，什么都写不出来。** 首次进来请先配模型：

1. 点页面右上角「设置」→「添加模型配置」
2. 填四样东西：
   - **提供商类型**：Claude / OpenAI / Ollama
   - **模型 ID**：比如 `claude-sonnet-4-20250514`、`gpt-4o`、`qwen3:32b`
   - **Base URL**：接口地址，比如 `https://api.anthropic.com`、`http://localhost:11434`
   - **API Key**：对应的密钥（本地 Ollama 不用填）
3. 点「测试连接」，能通过就说明通了
4. 保存后，在页面顶部把「全局默认模型」也选上并保存

> 任何兼容 OpenAI 接口的服务都能用：DeepSeek、通义千问、Kimi、oMLX 本地模型……类型选 OpenAI，Base URL 填它们各自的地址即可。

### 4. 开工

回首页点「新建项目」，填个标题、选个类型、把故事想法写进「简介」——**简介就是种子，写得越具体，长出来的东西越像你想要的**。然后进「工作流」，从世界观开始点生成。

## 写一本小说

新建项目时要填的几项，其实就是给 AI 画的框：

| 项目 | 说明 | 默认 |
|---|---|---|
| 标题 | 书名叫什么 | — |
| 类型 | 玄幻 / 仙侠 / 都市 / 科幻 / 言情 / 悬疑…… | — |
| 简介 | 故事构想，AI 创作的起点 | — |
| 总章节数 | 想写多少章，随时能改 | 10 |
| 每卷章节数 | 一卷装几章 | 10 |
| 角色数量 | 要几个核心角色 | 5 |
| 章节字数 | 目标 / 最少 / 最多 | 可选 |
| 创作指导 | 风格、口味、禁忌 | 可选 |

建好项目，点「工作流」，六步走：

### 1. 世界观设定

点「生成」，AI 会给你一整套世界：时代背景、力量体系（或行业规则）、势力分布、历史脉络。

**你要做的事**：读一遍，觉得哪不对就改。这一步错了后面全歪，值得多花十分钟。

### 2. 角色设计

按世界观生成角色卡：姓名、性格、出身、能力、和谁有什么恩怨。

- 对某个角色不满意，点「精修」让 AI 单独往深里挖
- 也可以自己动手改任何一个字
- 配角不够用？加人就行

### 3. 大纲生成

先出分卷的故事弧线，再出逐章大纲，每章写清「这章发生什么、谁出场」。

**这是整本书的骨架**，也是唯一值得你逐条看的步骤。章纲里埋的钩子够不够，直接决定读者会不会弃书。

### 4. 章节写作

开始出正文。写每一章时，AI 会自动带上世界观、本章出场角色的卡片、本章大纲、上一章结尾——所以衔接是连着的。

可以一章一章确认着写，也可以批量生成然后回头一起看。

### 5. 润色修改

对已经写完的章节做文字打磨：节奏、对白、描写。可以附一句意见，比如「这段打斗太碎，收得干净点」。

### 6. 校对精修

最后一遍体检：角色有没有前后不一致、时间线有没有矛盾、章节之间接不接得上、前面埋的伏笔有没有收。发现问题会直接动手改。

## 不想一步步点？交给它自己跑

新建项目时打开 **Auto Mode**，之后点「开始全自动创作」，从世界观到校对它会自己走完。

- **能中途看**——随时点进来看现在写到哪、写成了什么样
- **能随时停**——停止不会丢已经写出来的内容
- **能接着跑**——第二天开机再点继续，从断的地方往下写

建议第一次用还是手动走一遍六步，知道每一环长什么样，再决定要不要放手。

## 写完之后

**阅读**：项目页点「阅读」，干净的排版，按章翻。

**导出**，四种格式随便挑：

| 格式 | 适合 |
|---|---|
| Markdown | 想再编辑、丢进别的工具 |
| TXT | 什么都能打开，最保险 |
| PDF | 直接读、打印、发人 |
| EPUB | 手机阅读器、Kindle |

还有一些用得上的小工具：

- **番外篇**——给已有项目单开一条支线，角色和世界观直接复用
- **情节拓展**——某一章写得太赶？整章扩写，把细节补厚
- **TXT 导入**——手上有写完的半本书？导进来，AI 帮你反推世界观、角色和大纲，接了继续写
- **TTS 语音导出**——章节转语音，多音色、可试听、能分段导
- **角色图片**——生成头像和立绘（接 DALL·E 或本地 SD WebUI）
- **素材库**——存参考素材，AI 能提炼摘要
- **AI 聊天**——不想动流程，就想找人聊聊设定，直接开聊

## 玩着学

写东西写累了可以摸鱼，几个小模块都是打开就能玩，进度存本地：

| 模块 | 页面 | 玩什么 |
|---|---|---|
| 单词配对 | `/learn/word-match` | 英汉配对，一关一关往下闯。27 册、约 2 万词，可以先选册次，也能直接搜一个词开练。[【在线发音版】](https://word-match-61798.app.workbuddy.host/?sharecode=ENQEODEVZ3) |
| 成语填空 | `/learn/idiom-fill` | 四字成语缺一个字，补上。小初高 3 学段 × 10 主题、180 条，缺的位置每次随机 |
| 近义词匹配 | `/learn/synonym-match` | 找意思最接近的两个词，附词义辨析。540 对 / 90 关 |
| 反义词匹配 | `/learn/antonym-match` | 找意思相反的两个词。540 对 / 90 关 |
| 乘法口诀 | `/learn/multiplication` | 九九表和大九九，带朗读跟读 |
| 数独 | `/learn/sudoku` | 3×3 到 9×9 七种尺寸，难度可选，卡住了可以要提示 |
| 方块爆破 | `/learn/block-blast` | 拖方块填 8×8 棋盘，整行整列填满就消，记最高分 |

> 只想先随便玩玩？单词配对有一个部署在公网的 **[【在线发音版】](https://word-match-61798.app.workbuddy.host/?sharecode=ENQEODEVZ3)**：手机浏览器点开就能玩，带发音，不用装 Java，也不用起服务。

### 手机也能玩：单词配对小程序

<p align="center">
  <img src="docs/WX-mini-program.png" alt="单词趣味配对 - 微信小程序" width="240">
</p>

<p align="center">微信扫码直接开玩，不用下载安装</p>

玩法跟网页版一样：先在首页选册次或查单词，再进配对关卡。小程序内置 26 册（小学 8、初中 5、高中 11、大学 2）、3500 多关，进度存手机本地。

## 常见问题

**提示「请先配置模型」，可我明明填了？**
配置完还要在页面顶部把「全局默认模型」选上并保存。它俩是两件事。

**想省钱，能不能大纲用好模型、正文用便宜的？**
能。每个步骤都可以单独指定模型，不必都跟全局默认走。

**写到一半关掉浏览器，进度会没吗？**
不会。内容都存在数据库里，服务重启也不影响，重新打开项目接着写就行。

**1888 端口被占了？**
`./start_web.sh` 会先把旧进程停掉再启动，一般不用管。实在冲突就走 `mvn spring-boot:run` 用 8080。

**全量 `mvn test` 报了一堆 Mockito 错？**
已知问题，跟业务用例无关，是某些 JDK 组合下的注入失败。日常验证按类跑就行（见下一节）。

**我的数据存在哪？要不要备份？**
`./data/story-creator.mv.db` 一个文件就是全部家当，复制走就是备份。想换位置，设环境变量 `STORY_DB_PATH` 指向别的目录。

## 给开发者

### 环境要求

- Java 21+
- Maven 3.8+
- 至少一个可用的模型 API Key（Claude / OpenAI，或本地 Ollama 免 Key）

### 启动、构建、测试

```bash
./start_web.sh                                  # 后台跑，端口 1888，日志 app.log
mvn spring-boot:run                             # 前台跑，端口 8080
mvn -DskipTests package                         # 打包成 target/story-creator-1.0.0-SNAPSHOT.jar
mvn -Dtest=PageRenderingIntegrationTest test    # 测试按类跑
```

> 全量 `mvn test` 在部分 JDK 组合下会因 Mockito 注入失败整体报错，与业务用例无关。日常验证按类运行。

### 环境变量（可选）

| 变量 | 说明 | 默认值 |
|---|---|---|
| `STORY_DB_PATH` | 数据库存储目录 | `./data` |
| `CLAUDE_API_KEY` | Claude API Key（也可在设置页配置） | 无 |
| `OPENAI_API_KEY` | OpenAI API Key（也可在设置页配置） | 无 |

### 技术栈

- **后端**：Spring Boot 3.3 / Java 21，JPA + H2，Flyway 迁移，SSE + Reactor 做流式生成
- **前端**：Thymeleaf 页面 + Alpine.js + Bootstrap，**没有前端构建步骤**，改完刷新就是
- **小程序**：原生微信小程序，页面模板在 `src/main/resources/miniprogram-template/`

### 小程序工程是生成物

仓库里**不保存**小程序源码和词库（词库 752 KB，存第二份必然和网页版漂移）。它是「模板 + 现场切关」拼出来的：

```bash
./scripts/learn/export_miniprogram.sh    # 从 localhost:1888 导出到 word-match-miniprogram/
```

或者在网页里 `/learn/word-match` → 设置 → **导出小程序**，会下载一个 `word-pair-miniprogram.zip`（约 330 KB）。两条路产物一致，解压后直接导入微信开发者工具（`appid` 留空，用测试号或填自己的）。

改玩法请改 `src/main/resources/miniprogram-template/` 再重新导出；直接改生成目录，下次导出就被覆盖了。

### 目录结构

```
src/main/java/com/storycreator/            后端（web 控制器 / core 领域与策略 / persistence 仓储）
src/main/resources/templates/              页面模板
src/main/resources/static/                 静态资源（pages/ 为各功能页）
src/main/resources/db/migration/           Flyway 迁移脚本
src/main/resources/learn/                  学习模块词库（词语、成语、近反义词）
src/main/resources/miniprogram-template/   小程序页面模板（导出时拼上词库）
word-match-miniprogram/                    小程序工程（生成物，不入库）
docs/                                      设计与校验文档
scripts/                                   词库构建、页面探针脚本
```

## 延伸文档

| 文档 | 内容 |
|---|---|
| [`docs/WECHAT_MINIPROGRAM_MIGRATION.md`](docs/WECHAT_MINIPROGRAM_MIGRATION.md) | 单词匹配小程序化的完整方案与约束 |
| [`docs/cet-semantic-themes-plan.md`](docs/cet-semantic-themes-plan.md) | 四六级词库语义域划分 |
| [`docs/primary-category-upgrade.md`](docs/primary-category-upgrade.md) | 小学词库语义域升级 |
| [`docs/junior-level-audit.md`](docs/junior-level-audit.md) · [`docs/senior-level-audit.md`](docs/senior-level-audit.md) | 初中 / 高中逐关校对报告 |
| [`docs/qwen3-tts-guide.md`](docs/qwen3-tts-guide.md) | TTS 音色与参数配置 |

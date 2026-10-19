# Story Creator - AI 网文创作工坊

AI 驱动的中文网络小说创作系统。从一句故事构想开始，AI 帮你完成世界观设定、角色设计、大纲规划、章节写作、润色校对的全流程创作。支持全自动一键出书，也支持逐步人工审核把控质量。

除小说创作外，还内置一组「玩着学」的中英文小模块（单词匹配、成语填空、近义/反义词匹配、乘法口诀），网页与微信小程序双端可用。

## 功能一览

### AI 小说创作

| 功能 | 说明 |
|------|------|
| 六步工作流 | 世界观 → 角色 → 大纲 → 章节 → 润色 → 校对，每步可编辑、可重生成、可回退 |
| 全自动创作 | 一键跑完全流程，支持断点续跑、实时进度、随时停止 |
| 多模型混用 | 不同步骤可指定不同模型（如大纲用强模型，写作用快模型） |
| 创作指导 | 每一步都可写指导，可「存为通用」沉淀为个人模板，也可「复用指导」一键套用 |
| 提示词管理 | 自定义各步骤的提示词模板，精细控制生成风格 |
| 番外篇 | 为已有项目创建独立番外故事，复用角色与世界观 |
| 情节拓展 | 对已完成章节整章扩写，丰富细节 |
| TXT 导入 | 导入已有的 TXT 小说，AI 自动反推世界观、角色、大纲 |
| TTS 语音导出 | 章节转语音，支持多音色、试听、分段导出 |
| 角色图片 | AI 生成角色头像/立绘（支持 DALL·E 和 SD WebUI） |
| 素材库 | 管理创作参考素材，支持 AI 提炼摘要 |
| AI 聊天 | 内置聊天界面，随时与 AI 讨论创作想法 |

### 学习小模块

| 模块 | 入口 | 说明 |
|------|------|------|
| 单词匹配 | `/learn/word-match` | 小学 / 初中 / 高中课本词库 + 四六级，逐关配对，支持分类练习 |
| 成语填空 | `/learn/idiom-fill` | 小初高 3 学段 × 10 主题，180 条四字成语，缺字每次随机 |
| 近义词匹配 | `/learn/synonym-match` | 540 对 / 90 关，含词义辨析 |
| 反义词匹配 | `/learn/antonym-match` | 540 对 / 90 关，含词义辨析 |
| 乘法口诀 | `/learn/multiplication` | 九九表 + 大九九，带朗读与练习 |

## 小程序版：单词趣味配对

<p align="center">
  <img src="docs/WX-mini-program.png" alt="单词趣味配对 - 小程序" width="240">
</p>

<p align="center">微信扫码即可开玩，无需下载安装</p>

玩法与网页版 `/learn/word-match` 一致：先在检索首页选册次 / 查单词，再进入配对玩法逐关闯。小程序内置 26 册（小学 8、初中 5、高中 11、大学 2）、共 3546 关 / 20191 个单词，进度保存在本地。

## 创作一部小说

### 准备：连接 AI 模型

启动后第一件事是配置 AI 模型，否则无法进行创作。

1. 进入**设置页面**（页面右上角「设置」）
2. 点击「添加模型配置」
3. 填写以下信息：
   - **提供商类型**：Claude / OpenAI / Ollama
   - **模型 ID**：如 `claude-sonnet-4-20250514`、`gpt-4o`、`qwen3:32b` 等
   - **Base URL**：API 地址（如 `https://api.anthropic.com`、`http://localhost:11434`）
   - **API Key**：对应的密钥
4. 点击「测试连接」确认可用
5. 保存后，在页面顶部选择「全局默认模型」并保存

> 支持任何兼容 OpenAI 接口的服务（DeepSeek、通义千问、本地 oMLX 等），选择 OpenAI 类型填入对应地址即可。

### 第一步：新建项目

首页点击「新建项目」，填写：

- **标题**：你的小说名称
- **类型**：玄幻 / 仙侠 / 都市 / 科幻 / 言情 / 悬疑 等
- **简介**：一段故事构想（这是 AI 创作的种子，写得越具体，生成质量越高）
- **总章节数**：想要多少章（默认 10 章，可随时调整）
- **每卷章节数**：每卷包含多少章（默认 10）
- **角色数量**：核心角色数（默认 5）

可选配置：

- **章节字数**：目标 / 最小 / 最大字数
- **创作指导**：告诉 AI 你对风格、内容、禁忌的要求

### 第二步：进入工作流

创建项目后，点击「工作流」进入创作界面。创作分 6 个步骤，按顺序推进：

#### 1. 世界观设定

点击「生成」，AI 会根据你的项目简介创建完整的世界观设定（世界背景、力量体系、势力分布、历史脉络等）。

生成后你可以自由编辑修改，满意后点「确认」进入下一步。

#### 2. 角色设计

AI 根据世界观生成角色卡片（每个角色包含姓名、性格、背景、能力、人际关系等）。

你可以：

- 对单个角色点「精修」让 AI 深化细节
- 手动编辑任何角色信息
- 满意后确认进入下一步

#### 3. 大纲生成

AI 生成分卷故事弧线和逐章详细大纲。每章大纲包含情节要点和出场角色。

这一步决定了整部小说的骨架，建议仔细审阅。

#### 4. 章节写作

逐章生成正文。AI 在写每一章时会自动参考：世界观、出场角色、本章大纲、前一章内容等上下文，保证内容连贯。

你可以逐章确认，也可以批量生成。

#### 5. 润色修改

对已完成的章节进行文字润色，提升流畅度和表现力。可以附加修改意见引导润色方向。

#### 6. 校对精修

AI 对全文进行 5 项检查（角色一致性、逻辑连贯、前后衔接、伏笔追踪等），发现问题后自动修正。

### 全自动模式

如果不想逐步操作，可以在创建项目时开启「Auto Mode」，然后点击「开始全自动创作」——AI 会自动完成从世界观到校对的全部流程。

全自动模式支持：

- **断点续跑**：随时停止，下次启动从中断处继续
- **实时查看**：可随时查看当前创作进度和生成内容
- **随时停止**：点击停止后不会丢失已生成的内容

## 查看与导出

### 阅读器

项目页面点击「阅读」进入清爽的阅读界面，按章节浏览生成的小说内容。

### 导出

支持 4 种格式导出成品：

- **Markdown**：适合二次编辑
- **纯文本 TXT**：通用格式
- **PDF**：支持中文排版，可直接阅读
- **EPUB**：电子书格式，适合手机 / Kindle 阅读

## 延伸文档

| 文档 | 内容 |
|------|------|
| [`docs/WECHAT_MINIPROGRAM_MIGRATION.md`](docs/WECHAT_MINIPROGRAM_MIGRATION.md) | 单词匹配小程序化的完整方案与约束 |
| [`docs/cet-semantic-themes-plan.md`](docs/cet-semantic-themes-plan.md) | 四六级词库语义域划分 |
| [`docs/primary-category-upgrade.md`](docs/primary-category-upgrade.md) | 小学词库语义域升级 |
| [`docs/junior-level-audit.md`](docs/junior-level-audit.md) · [`docs/senior-level-audit.md`](docs/senior-level-audit.md) | 初中 / 高中逐关校对报告 |
| [`docs/qwen3-tts-guide.md`](docs/qwen3-tts-guide.md) | TTS 音色与参数配置 |

## 开发

### 环境要求

- Java 21+
- Maven 3.8+
- 至少一个 AI 模型的 API Key（Claude / OpenAI / Ollama 本地免 Key）

### 启动服务

```bash
# 推荐：后台运行，端口 1888（会先打包 jar，日志写入 app.log）
./start_web.sh

# 或前台运行，端口 8080
mvn spring-boot:run
```

启动后浏览器访问 `http://localhost:1888`（或 8080）即可使用。

数据库使用内嵌 H2，无需安装，数据保存在 `./data/` 目录下，表结构由 Flyway 迁移自动维护。

### 构建与测试

```bash
mvn -DskipTests package          # 打包成可执行 jar
mvn -Dtest=PageRenderingIntegrationTest test   # 建议按类跑
```

全量 `mvn test` 在部分 JDK 组合下会因 Mockito 注入失败而整体报错，与业务用例无关，日常验证按类运行即可。

### 环境变量（可选）

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `STORY_DB_PATH` | 数据库存储目录 | `./data` |
| `CLAUDE_API_KEY` | Claude API Key（也可在设置页配置） | 无 |
| `OPENAI_API_KEY` | OpenAI API Key（也可在设置页配置） | 无 |

### 技术栈

- **后端**：Spring Boot 3.3 / Java 21，JPA + H2，Flyway 迁移，SSE + Reactor 处理流式生成
- **前端**：Thymeleaf 页面 + Alpine.js + Bootstrap，无前端构建步骤
- **小程序**：原生微信小程序，页面模板在 `src/main/resources/miniprogram-template/`

### 小程序工程是生成物

仓库里**不保存**小程序源码和词库（词库 752 KB，存第二份必然与网页版漂移）。小程序工程由「模板 + 现场切关」拼出来：

```bash
./scripts/learn/export_miniprogram.sh          # 从 localhost:1888 导出到 word-match-miniprogram/
```

或者在网页里 `/learn/word-match` → 设置 → **导出小程序**，会下载一个 `word-pair-miniprogram.zip`（约 330 KB）。
两者产物一致，解压后直接导入微信开发者工具即可（`appid` 留空，用测试号或填自己的）。

改玩法请改 `src/main/resources/miniprogram-template/`，然后重新导出；直接改生成目录会在下次导出时被覆盖。

### 目录结构

```
src/main/java/com/storycreator/       后端（web 控制器 / core 领域与策略 / persistence 仓储）
src/main/resources/templates/         页面模板
src/main/resources/static/            静态资源（pages/ 为各功能页）
src/main/resources/db/migration/      Flyway 迁移脚本
src/main/resources/learn/             学习模块词库（词语、成语、近反义词）
src/main/resources/miniprogram-template/  小程序页面模板（导出时拼上词库）
word-match-miniprogram/               小程序工程（生成物，不入库）
docs/                                 设计与校验文档
scripts/                              词库构建、页面探针脚本
```

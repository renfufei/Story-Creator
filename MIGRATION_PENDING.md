# Thymeleaf → 静态页 迁移清单

> **状态：全部完成（2026-09-14）。** 34 个 URL 映射 / 35 个静态页文件，`src/main/resources/templates/` 已清空，Thymeleaf 不再参与任何页面渲染。

目标：把前端从 Thymeleaf 服务端渲染改造为「静态 HTML + JS + Ajax」架构。
原则：
- URL 保持不变（通过 `StaticPageController` 的 `forward:/pages/*.html` 映射，或控制器自身方法返回 `forward:`）。
- 引导数据改为**同步 XHR** 在 Alpine（`defer`）解析前注入全局变量（如 `window.__READER_DATA__`），Alpine mixin 构造时同步读取，行为与原 `th:inline` 一致。纯 JS 渲染页同理（如 `window.__PROMPTS_DATA__`）。
- 迁移完一页：删除对应 `templates/*.html` + 摘掉 Controller 的 view 映射（改 JSON 接口或复用已有 API），并更新 `PageRenderingIntegrationTest`。
- 验证：`mvn` 编译 → 全量测试 → 重建 jar → 重启 1888 → curl 验证（页面 200 且无 `th:`/`@{/`/`xmlns:th` 残留；JSON 接口契约正确；`app.log` 无异常）。

## 已完成（34 个 URL 映射 / 35 个静态页文件）

| 页面 | URL | 静态页 | 后端接口 / 改造 |
|---|---|---|---|
| 仪表盘 | `/` | `pages/dashboard.html` | `StaticPageController` 转发 |
| 项目列表/新建 | `/projects/new` | `pages/project-form.html` | — |
| 项目详情 | `/projects/{id}` | `pages/project-detail.html` | `ProjectApiController` |
| 项目编辑 | `/projects/{id}/edit` | `pages/project-form.html` | — |
| 灵感 | `/projects/{id}/inspirations*` | `pages/inspirations/*` | `InspirationController` |
| 设置 | `/settings` | `pages/settings.html` | — |
| 工作流（六步，已拆 6 个分步页） | `/projects/{id}/workflow` → 302 `/workflow/world-building`（Hub 页已下线） | `pages/workflow/*.html` | `StaticPageController` 重定向；数据 → `/workflow/{step}/data` |
| 阅读 | `/projects/{id}/read` | `pages/reader.html` | 新增 `ReaderApiController` → `/api/projects/{id}/read-data` |
| 番外列表 | `/projects/{id}/side-stories` | `pages/side-story-list.html` | `SideStoryController`（`@RestController`）→ `/list-data` |
| 番外详情/编辑 | `/projects/{id}/side-stories/{ssid}` | `pages/side-story.html` | `SideStoryController` 复用 `SideStoryApiController` 业务 → `/{id}/data` |
| 情节拓展 | `/projects/{id}/expansion` | `pages/expansion.html` | `ExpansionController` 改 `page()` 为 `/data`，复用已有 `/status` `/stream` 等 |
| 创作透视（审阅总览） | `/projects/{id}/inspect` | `pages/inspect.html` | `InspectController`（`@RestController`）→ `/data` |
| 章节透视 | `/projects/{id}/inspect/chapters/{num}` | `pages/inspect-chapter.html` | `InspectController` → `/chapters/{num}/data`（复用 `/chapters/{num}/field/{field}` AJAX） |
| 角色透视 | `/projects/{id}/inspect/characters` | `pages/inspect-characters.html` | `InspectController` → `/characters/data` |
| 流程模板配置 | `/prompts` | `pages/prompts.html` | `PromptController` → `/prompts/data`（列表 JSON + 枚举选项；创建/默认/重置仍为表单 POST） |
| 模板编辑 / 内置查看 | `/prompts/{id}/edit`、`/prompts/builtin/{key}` | `pages/prompt-edit.html` | `PromptController` → `/{id}/edit-data`、`/builtin/{key}/edit-data`（只读用 `isBuiltin` 区分；保存/删除仍为表单 POST） |
| 提示词探索 | `/prompts/explore?templateKey=\|templateId=` | `pages/prompt-explore.html` | `PromptExploreController` → `/prompts/explore/data`（复用 `/chapters` `/characters` `/resolve` `/call-ai-stream`） |
| 创作指导库 | `/settings/guidances` | `pages/guidances.html` | `GuidanceLibraryController` → `/data`（新建/删除仍为表单 POST；导入结果改由 `?imported=` / `?err=` 带回） |
| 创作指导编辑 | `/settings/guidances/{id}/edit` | `pages/guidance-edit.html` | `GuidanceLibraryController` → `/{id}/edit-data`（未知 id → 404） |
| 素材库 | `/settings/materials` | `pages/materials.html` | `MaterialLibraryController` → `/data`（`/distill` `/projects-json` `/project-sources` `/list-json` 原样保留） |
| 素材编辑 | `/settings/materials/{id}/edit` | `pages/material-edit.html` | `MaterialLibraryController` → `/{id}/edit-data`（未知 id → 404） |
| 章节分割配置 | `/settings/chapter-split-configs` | `pages/chapter-split-configs.html` | `ChapterSplitConfigController` → `/data`（`/test` 正则测试保留；弹窗改用 Bootstrap Modal） |
| 项目导入 | `/import` | `pages/import.html` | `ImportController` 转发；错误信息改由 `?error=` 带回（静态页读不到 flash） |
| TXT 导入 | `/import/txt` | `pages/txt-import.html` | `TxtImportPageController` → `/import/txt/data`；业务接口全在 `TxtImportApiController`（含 `/stream` SSE，静态页无 Thymeleaf 截断风险） |
| 聊天 | `/chat` | `pages/chat.html` | `ChatController` → `/chat/data`（会话 + 三类模型配置）；`/api/chat/**` 全部原样保留 |
| TTS 导出 | `/tts-export?projectId=` | `pages/tts-export.html` | `TtsExportController` → `/tts-export/data`（项目列表 + 预选 id）；`/api/tts-export/**` 全部原样保留 |
| TTS 全文收听 | `/tts-fullplay?taskId=` | `pages/tts-fullplay.html` | `TtsExportController` 转发；taskId 由静态页自行从查询串解析 |
| TTS 替换模板列表 | `/settings/tts-templates` | `pages/tts-templates.html` | `TtsReplacementController` → `/data`（内置 + 自定义） |
| TTS 替换模板编辑 | `/settings/tts-templates/{id}/edit` | `pages/tts-template-edit.html` | `TtsReplacementController` → `/{id}/edit-data`（未知 id → 404）；规则增删仍为表单 POST |
| TTS 内置模板查看 | `/settings/tts-templates/builtin/{id}/view` | `pages/tts-template-view.html` | `TtsReplacementController` → `/builtin/{id}/view-data`（未知 id → 404） |
| TTS 模型模板绑定 | `/settings/tts-templates/bindings/{configId}` | `pages/tts-template-bindings.html` | `TtsReplacementController` → `/bindings/{configId}/data`；绑定增删仍为表单 POST |
| 教学入口 | `/learn` | `pages/learn.html` | `LearnController` 转发（纯静态卡片，无引导数据） |
| 九九乘法口诀 | `/learn/multiplication` | `pages/learn-multiplication.html` | `LearnController` 转发；口诀数组内置在页面（与原模板一致），音频走 `/api/learn/multiplication/audio/{itemKey}` |
| 乘法音频管理 | `/learn/multiplication/settings` | `pages/learn-multiplication-settings.html` | `LearnController` → `/learn/multiplication/settings/data`（TTS 配置 + 默认 id + 口诀/前缀 + 音频总数）；`/api/learn/multiplication/**` 全部原样保留 |

## 迁移后的收尾状态

- `src/main/resources/templates/` **已空**（`layout.html` 随最后三页一并删除）。所有控制器只返回 `forward:` / `redirect:`，没有任何方法返回 Thymeleaf view name（可用 `grep -rnoE 'return "[a-z][a-z0-9-]*"' src/main/java/com/storycreator/web src/main/java/com/storycreator/chat | grep -vE 'forward:|redirect:'` 复核，应无输出）。
- **Thymeleaf 依赖与配置已彻底摘除（2026-09-14 收尾）**：`pom.xml` 删除 `spring-boot-starter-thymeleaf`；`src/main/resources/application.yml`、`src/test/resources/application.yml` 删除 `spring.thymeleaf.*` 块；空的 `src/main/resources/templates/` 目录一并删除。项目模板引擎依赖归零，`forward:/pages/*.html` 由 Spring MVC 内置 `InternalResourceViewResolver` 处理（`UrlBasedViewResolver` 原生识别 `forward:` 前缀）。
- 迁移前的 `learn*` 三页是本项目最后一批 Thymeleaf 页面；`layout.html` 的导航已由 `static/js/nav.js`（`NAV_ITEMS` 含 `learn` key）接管。

## 已知坑（迁移通用）

- **不要给「控制器自己已声明的路径」再在 `StaticPageController` 加转发**：会 `Ambiguous mapping` 启动失败。正确做法：**改控制器自己的方法返回 `forward:/pages/xxx.html`**，`StaticPageController` 只登记「没有对应控制器方法」的 URL。
- **同一路径已有任意 `@XxxMapping` 时 view-controller 不生效**：必须在 Controller 显式 `@GetMapping` 返回 `forward:/pages/xxx.html`。
- **静态页读不到 `RedirectAttributes` flash**：迁移后把提示信息改成查询串带回（guidances 导入用 `?imported=` / `?err=`，import 用 `?error=<urlencoded>`），前端用 `SC.params()` 读取。
- **测试文件里可能已有同名用例**：追加新用例前先 `grep -n "void <名字>"`（本项目 `guidances_rendersSuccessfully` / `materials_rendersSuccessfully` / `ttsTemplates_rendersSuccessfully` 原本就存在，直接追加会「方法重复定义」编译失败）。既有 `assertStaticPage(response, name, marker)` 助手优先复用。
- **静态页断言只能校验 HTML 源字面量**（RestTemplate 不执行 JS）。真实渲染用无头 Chrome：`Chrome --headless=new --dump-dom <url>`。
- **`--dump-dom` 不反映 JS 赋的 `input.value`/`textarea.value`**：`el.value = x` 只改 IDL 属性、不改内容属性，序列化出来是空的。要验证「脚本读到了数据」，看**数据驱动的痕迹**（动态生成的 `<code>` 列表、`form[action]`、可见性切换），别去 grep `value="..."`。
- **`AutoRunHttpIntegrationTest` 在基线 HEAD 也失败**（计时断言 `pollForStatus` 超时，环境问题），与具体迁移无关，勿误判为回归。
- **Alpine 启动机制**：`cdn.min.js` 为 `defer`，内部 `queueMicrotask(() => start())`，早于 `DOMContentLoaded`；故在 `defer` 脚本里用同步 XHR 注入全局变量后 Alpine 才能读到。静态页里引导脚本必须放在 `<body>` **最前**。
- **Thymeleaf 文本内联 `[[...]]` 陷阱**（历史）：`reader.html`/`side-story.html`/`txt-import.html` 的 SSE 协议标记若含字面量 `[[]]` 会导致渲染静默截断（响应已 flush，状态码仍是 200）。迁移成静态页后该风险消失，但 `PageRenderingIntegrationTest#txtImport_rendersSuccessfully` 保留了「SSE 监听器必须齐全」的断言作为护栏。
- **纯 JS 渲染页（`prompts.html` / `prompt-edit.html` / `guidances.html` / `materials.html` / `chapter-split-configs.html` / `tts-templates.html` 等）**：无 Alpine 时，引导数据同样用同步 XHR 注入，再由底部 inline `<script>` 渲染并绑定事件；用 `SC.escape`（已转义 `&<>"'`，可安全用于属性）或 `textContent` 处理动态文本；表单类操作保留原生 `form POST`（服务端仍 `redirect:`）。
- **带 layout 的模板迁成静态页要补 shell**：需自行加 `<body class="sc-page">` + `<header id="site-nav">` + `/js/common.js` + `/js/nav.js`（顺序：common 先、nav 后，nav 自动挂载，高亮项由 `<body data-nav="...">` 指定，可选 key 见 `nav.js` 的 `NAV_ITEMS`：`projects` / `chat` / `tts` / `learn` / `import`）；`templates` 里没走 layout 的页（如 inspect 家族）则不需要导航。
- **原模板里针对 layout 的 CSS 选择器要跟着改**：`learn-multiplication.html` 的移动端媒体查询原本写 `.navbar { display: none !important; } main.container { ... }`，迁成静态页后必须改成 `#site-nav` / `body.sc-page main.sc-main`，否则选择器失效（不报错、静默不生效）。
- **`x-init` 里读 DOM 的勾选状态不可靠**：Alpine 初始化根元素时先跑 `x-init`，子级 `x-for` 可能尚未渲染，`querySelectorAll('.xxx:checked')` 会拿到空集合（原 Thymeleaf 是服务端渲染好勾选，所以原代码可能依赖 DOM）。**改为从引导数据推导**（如 `splitConfigs.filter(c => c.enabled).map(c => c.id)`），行为等价且不受渲染时序影响。
- **引导脚本"整体覆盖"陷阱**：若脚本先用 `location.pathname` 解析出 id，再用接口 JSON **整体替换**变量对象，而接口未回传该 id，则 id 会丢失（症状：`form.action` 变成 `/xxx/undefined/update`）。**修法**：覆盖后回填（`parsed.templateId = d.templateId`）或直接取 `data.template.id`。
- **计数校验 DOM 时要剔除 `<script>` 与 `<template>` 里的模板字符串**：`grep -c`/正则会同时命中行内脚本字面量与 Alpine `x-for` 蓝图（`<template>` 内容不渲染但留在 DOM 中）。正确姿势：先去 `<script>...</script>` 再去 `<template>...</template>` 再统计。
- **`grep -c 'th:'` 会误报**：静态页 CSS 里 `width:` 含子串 `th:`。复核 Thymeleaf 残留要用精确模式 `grep -oE 'th:(href|src|if|unless|each|text|action|value|replace|inline|attr|classappend|selected)'`。
- **路径含 `|` 的内置模板 key**：走 HTTP 时用 `encodeURIComponent`/`%7C`；`TestRestTemplate.getForEntity(String)` 会再编码一次（`%` → `%25`），因此测试里直接传**原始** key（含 `|`）交给 RestTemplate 编码即可，不要预编码。
- **探针用例要用「真实存在」的外键**：如 `tts_model_template_bindings.model_config_id` 有外键约束，绑定接口传一个不存在的 configId 会 500（`JdbcSQLIntegrityConstraintViolationException`），这属数据前提而非迁移缺陷。本机当前无 TTS 模型配置，绑定页只能验证空态。

# 单词匹配 → 微信小程序 迁移方案

> 目标：把 `http://localhost:1888/learn/word-match`（人教版 24 册 + 四六级 2 册 / 3448 关 / 20191 词）
> 的功能迁移到微信小程序。
> 本文所有结论都基于对现有页面与词库的**实测统计**，不是估算。

---

## 0. 一句话结论

**HTML 不能"转"成小程序 —— 但这个页面的移植成本远低于典型 Web→小程序 迁移。**
理由是三条实测事实：交互是纯点击（无拖拽）、无自绘动画循环、Alpine 绑定密度很低。
真正需要重写的只有 7 类浏览器专有 API，其中 3 类在小程序里**没有等价物**，必须换方案或砍掉。

**选型结论：做小程序（Mini Program），不做小游戏（Mini Game）。**
小游戏只有 Canvas 渲染，没有 WXML/WXSS —— 等于把整个 UI 手工重画一遍（含中文换行、卡片布局、滚动弹层），
工作量是原生小程序的 2–3 倍。本游戏是"卡片点击 + CSS 过渡"，属于典型 UI 交互，没有任何理由走 Canvas。

---

## 1. 为什么不能直接复用

小程序的运行时是**双线程架构**，逻辑层（JSCore）**没有 DOM**：

| 能力 | Web（当前页面） | 小程序 |
|---|---|---|
| 标签 | `<div> <span> <button>` | `<view> <text> <button>` |
| 样式 | CSS（含 Bootstrap） | WXSS（**不认选择器嵌套、不支持外部 CSS 库**） |
| 布局单位 | px + `@media` | rpx（750rpx = 屏宽，设计稿 375px → ×2） |
| 模板语法 | Alpine.js 指令 | WXML `{{ }}` / `wx:for` / `wx:if` |
| 更新视图 | 直接改 `this.xxx` | `this.setData({...})`（异步批处理） |
| 取 DOM | `document.querySelector` | `wx.createSelectorQuery()`（异步） |
| 存储 | `localStorage` | `wx.setStorageSync` |
| 网络 | `fetch` / XHR | `wx.request`（域名须备案 + 后台加白） |
| 字体图标 | Bootstrap Icons 字体文件 | 需转 base64 或改 `<text>` / SVG |

**所以"迁移"的实质 = 把 132KB 的三件套（HTML+CSS+JS）按上表逐项翻译，业务逻辑本身可整体保留。**

---

## 2. 实测：现状规模（决定工作量）

### 2.1 页面本体

| 指标 | 数值 | 含义 |
|---|---|---|
| 文件 | `src/main/resources/static/pages/learn-word-match.html` | 单文件三件套 |
| 行数 / 体积 | **2940 行 / 132,227 B** | 其中 JS 逻辑段 ≈ **1387 行** |
| 入口 | `<div class="wm-root" x-data="wordMatchApp()">` | 单一 Alpine 组件 |

### 2.2 交互形态 —— 这是最关键的利好

| 检测项 | 出现次数 |
|---|---|
| `touchstart` / `touchmove` | **0** |
| `pointerdown` / `pointermove` | **0** |
| `mousedown` / `mousemove` | **0** |
| `draggable` / `dragstart` | **0** |
| `requestAnimationFrame` | **0** |
| `IntersectionObserver` | **0** |

**结论：全程只有 `click`。没有拖拽、没有手势、没有自绘动画帧。**
小程序的 `bindtap` 可以一比一替换 `@click`，触屏适配成本 ≈ 0。

### 2.3 Alpine 绑定密度（= WXML 模板改写量）

| 绑定 | 次数 | WXML 对应写法 |
|---|---|---|
| `@click` | 37 | `bindtap="fn" data-idx="{{i}}"` |
| `@change` | 2 | `bindchange` |
| `@keydown` | 1 | ⚠️ 无键盘 → 删掉 |
| `x-show` | 19 | `hidden="{{!flag}}"` |
| `x-text` | 56 | `{{ expr }}` |
| `x-for` | 10 | `wx:for="{{list}}" wx:key="..."` |
| `x-if` | 5 | `wx:if` |
| `:class` / `:style` | 29 | `class="{{cond ? 'a' : 'b'}}"` |
| `x-cloak` | 14 | 小程序无闪烁问题 → 删掉 |
| `@click.outside` | 1 | 遮罩层 `bindtap` 关闭 |

合计约 **176 个绑定**。都是机械翻译，没有一个是"需要重新设计"的。

### 2.4 浏览器专有 API —— 需要换方案的清单

| 能力 | 现用量 | 小程序方案 | 难度 |
|---|---|---|---|
| 进度存储 | `localStorage` ×14 | `wx.setStorageSync` / `getStorageSync` | 🟢 直接换 |
| 音效 | `AudioContext` ×2 | `wx.createInnerAudioContext()` + mp3 | 🟢 需备音频文件 |
| 自适应布局 | `ResizeObserver` ×2、`getBoundingClientRect` ×2 | `wx.createSelectorQuery()`、`onResize` | 🟡 少量重写 |
| 引导数据 | 同步 `XMLHttpRequest` ×2 | `require()` 本地模块（小程序**不支持同步 XHR**） | 🟡 架构调整 |
| 朗读（TTS） | `speechSynthesis` ×10 | ❌ **无原生 TTS** | 🔴 见 §3.1 |
| 导出单页 HTML | `Blob` ×3 / `createObjectURL` ×1 / `download` ×7 | ❌ **无文件下载** | 🔴 见 §3.2 |
| DOM 操作 | `document.*` ×6 | ❌ 无 DOM → 全走 `setData` | 🟡 逐处改写 |
| Bootstrap 样式库 | 13 处引用 | ❌ 不能用 | 🟡 见 §3.3 |

---

## 3. 三个必须做决策的地方

### 3.1 朗读功能（`speechSynthesis` → ？）

Web 版点英文卡会用系统 TTS 朗读。小程序**没有** `speechSynthesis`，三条路：

| 方案 | 做法 | 优劣 |
|---|---|---|
| A. 微信同声传译插件 | 引入 `plugin://WechatSI`，调 `textToSpeech` | 免费、无需后端；**需在 `app.json` 声明插件**，且插件有调用频率限制 |
| B. 后端预生成音频 | 服务端批量 TTS 出 mp3，随包内置或云存储 | 音质可控、离线可用；但 20191 个词全量生成体积爆炸，只能按需 |
| C. 后端实时 TTS | 小程序请求你的服务 → 返回音频 URL | 灵活；需备案域名 + 服务器常驻 |
| D. 首版砍掉 | 只保留音效，朗读放二期 | 最快跑通，但功能有缺失 |

> 你有 `docs/qwen3-tts-guide.md`，方案 B/C 已有基础。

### 3.2 导出单页 HTML（`Blob` + `download` → ？）

小程序**不能下载文件**，这个功能无法平移。替代：

- 生成**小程序码**分享给朋友（`wxacode`）—— 最贴合"分享给同学"的原意
- 「分享到聊天/朋友圈」（`onShareAppMessage`）带上课次与进度
- 若要"离线单机件"这个能力本身，只能保留 Web 版做。

### 3.3 Bootstrap 与样式

页面里 Bootstrap 主要用在：`btn btn-primary btn-sm`、`d-flex gap-2`、`container`、`col-*`、弹层。
`.wm-*` 自定义样式才是主体（配色变量、卡片、闪光动画、移动端纵向预算都在这里）。

**建议：只保留 `.wm-*`，Bootstrap 的 4 个用到的工具类手写进 `app.wxss`（共几十行）。**
不要引入任何第三方 WXSS 库 —— 小程序生态里的 UI 库（WeUI/Vant）会改变现有视觉，而你对这套配色已经精调过。

---

## 4. 数据打包方案（这是最容易踩坑的地方）

### 4.1 现状

Web 版是**两段式**：首屏同步 XHR 取 114.7KB(gzip) 引导，切到四六级再懒拉 324KB(gzip)。
这个设计是为 Web 首屏服务的，**小程序里不适用**：

- 小程序**不支持同步 XHR** → 首屏引导必须改成 `require()`
- `wx.request` 需要**备案域名 + 后台加白名单** → 本地开发期很麻烦

### 4.2 实测体积

| 词库文件 | 原始体积 |
|---|---|
| `pep-words.json`（24 册 / 1249 关 / 7032 词） | 396.5 KB |
| `cet-words.json`（2 册 / 2199 关 / 13159 词） | 647.3 KB |
| **合计** | **1043.8 KB** |

对照小程序限制：**主包 ≤ 2048 KB，整包（含分包）≤ 20 MB**。

### 4.3 三个方案

| 方案 | 做法 | 主包占用 | 评价 |
|---|---|---|---|
| **① 分包按学段**（推荐） | 小学 8 册 / 初中 5 册 / 高中 11 册 / 大学 2 册 各一个分包 | 主包只放壳，每分包 1 个学段 | 启动最快，且天然对应现有「册次弹层按学段折叠」；切学段首次会等一下 |
| ② 全量内置主包 | 把两份 JSON 打成 `levels.js` 放主包 | ~1.05 MB + 代码 | 可行（没超 2MB），但主包臃肿、审核与启动都变慢 |
| ③ 走网络 | 数据部署到 HTTPS + 备案域名，`wx.request` 拉 | 近 0 | 与你"离线可玩"的定位冲突，且需要备案 |

> ⚠️ **务必注意**：不要直接把服务端 `/learn/word-match/data` 的响应体（442KB + 1086KB ≈ 1.5MB）
> 塞进小程序 —— 那是**展开后的关卡结构**（每个 pair 都带 `bookId` 前缀），比源 JSON 大 46%。
> 应在**构建期**用与后端 `WordMatchBank.splitBalanced` 完全一致的算法生成关卡，再落盘。

---

## 5. 工程结构（原生小程序标准骨架）

```
word-match-miniprogram/
├── project.config.json          # miniprogramRoot: "./", compileType: "miniprogram"
├── app.json                     # pages / window / lazyCodeLoading / 分包声明
├── app.js                       # App({}) + 进度存储初始化
├── app.wxss                     # .wm-* 自定义样式 + 手写工具类
├── sitemap.json
├── pages/
│   ├── index/                   # 册次选择（对应现有"选择年级册次"弹层 → 提为独立页）
│   └── play/                    # 棋盘主页面（对应 wm-root）
├── components/
│   ├── book-picker/             # 册次弹层（按学段折叠）
│   ├── settings-sheet/          # 设置弹层（错题本/音效/重置）
│   └── finish-dialog/           # 通关弹窗（含右上角关闭叉 + 主按钮「下一册」）
├── utils/
│   ├── bank.js                  # 词库加载 + 关卡索引（移植 WordMatchBank 的读路径）
│   ├── storage.js               # localStorage → wx.setStorageSync 适配层
│   └── sound.js                 # AudioContext → InnerAudioContext 适配层
└── data/                        # 构建期生成的关卡 JSON（或分包）
```

**必须的 `app.json` 基础项**（漏了会白屏）：

```json
{
  "pages": ["pages/index/index", "pages/play/play"],
  "lazyCodeLoading": "requiredComponents"
}
```

`lazyCodeLoading: "requiredComponents"` 是硬要求 —— 缺它在部分 iPhone 上会白屏（组件注入期 `__wxAppCode__` undefined），而模拟器看不出来。

---

## 6. 翻译对照表（照表施工）

| Web | 小程序 | 备注 |
|---|---|---|
| `x-data="wordMatchApp()"` | `Page({ data: {...} })` | 原 29 行 x-data → data 对象 |
| `this.xxx = v` | `this.setData({ xxx: v })` | ⚠️ **异步批处理**：改完立即读 DOM 是旧值 |
| `@click="pick(1)"` | `bindtap="pick" data-i="1"` | 取值 `e.currentTarget.dataset.i` |
| `x-for="p in pairs"` | `wx:for="{{pairs}}" wx:key="key"` | `:key` 必须唯一且随数据变化 |
| `x-if` | `wx:if` | 会销毁重建 |
| `x-show` | `hidden="{{...}}"` | 只切显隐，保留实例 |
| `x-text` | `{{ }}` | — |
| `:class="a ? 'x' : 'y'"` | `class="{{a ? 'x' : 'y'}}"` | — |
| `localStorage.getItem(k)` | `wx.getStorageSync(k)` | 返回 `''` 而非 `null`，判空要小心 |
| `localStorage.setItem(k, JSON.stringify(v))` | `wx.setStorageSync(k, v)` | 小程序可直接存对象 |
| `speechSynthesis.speak(u)` | 见 §3.1 | — |
| `new AudioContext()` | `wx.createInnerAudioContext()` | 音效需 mp3 资源 |
| `new Blob([...])` + `<a download>` | 见 §3.2 | — |
| `@media (max-width: 767.98px)` | rpx + `onResize` | 小程序本来就只有移动端 → **大部分移动端特化规则可删** |
| `position: fixed` 底部胶囊 | 同样可行 | 小程序里 `cover-view` 才浮在原生组件上；纯 view 没问题 |
| `@click.outside` | 遮罩层 `catchtap` | 用 `catchtap` 防穿透 |

### 6.1 尺寸换算

Web 设计基准是 375px 宽（Bootstrap `sm` 断点 767.98px）。小程序：
**750rpx = 屏宽**，所以 `1px(设计稿) = 2rpx`。

现有 `px` 值统一 ×2 → `rpx`。例外：**触控热区不要低于 88rpx（=44px 红线）**，现有 `min-height:46px` 卡片 → `92rpx`。

### 6.2 可以整段删掉的逻辑

| 现有实现 | 删除理由 |
|---|---|
| `x-cloak` 相关（14 处） | 小程序无 FOUC 问题 |
| 移动端/桌面端双套媒体查询 | 小程序只有移动端 |
| 同步 XHR 引导 + `__WORD_MATCH_DATA__` | 改 `require()` |
| gzip / 首屏体积优化那套 | 本地文件不走网络 |
| `__WM_BOOK_KEY__` 里那串 12位时间戳+6位随机 | 那是为 `file://` 同源冲突设计的，小程序天然隔离 |
| `#site-nav` / `sc-main` / 公共 `common.js` `nav.js` | 小程序无这套外壳 |
| `退出 / 导出` 按钮 | 见 §3.2 |
| `?wmStep=&wmThink=...` URL 参数覆盖 | 小程序无 query 传参这套调试习惯 → 改成配置常量 |

---

## 7. 分阶段施工计划

> **状态（2026-09-23）：S1–S7 已全部实施完毕**，交付清单与逐项验证结果见 §11。

每个阶段都能独立跑起来看效果，不需要等全部完成。

| 阶段 | 内容 | 验收 |
|---|---|---|
| **S1 骨架** | 工程结构 + 册次选择页 + 词库内置 + 学段折叠 | 能选到某一册并进入棋盘 |
| **S2 玩法** | 棋盘渲染、点击配对、同义判定（`sameMeaning` 严格相等）、逐关推进 | 能从头通一关 |
| **S3 进度** | `progress_v2` / `auto_progress_v2` / `wrong_v1` 三键换 `wx.setStorageSync` | 杀掉重进进度还在 |
| **S4 体验** | 音效、配对成功双闪（`is-ok` + 500ms）、错配标红、过关提示 | 动画与 Web 版一致 |
| **S5 册尾** | 通关弹窗（右上叉 + 主按钮「下一册」）、册尾按钮变「下一册」、自动续册 | 一册学完自动进下一册 |
| **S6 自动连播** | `autoPlayLevel()` 节奏 1s/2s/4s/5s，可中断 | 挂机能自动过 |
| **S7 收尾** | 朗读（按 §3.1 决策）、错题本、设置弹层、分享 | 与 Web 版功能对齐 |

> **S1–S5 是"可玩闭环"**，做完就能发体验版给人试。S6/S7 是体验打磨。

---

## 8. 明确的取舍（不要做的事）

| 不做 | 原因 |
|---|---|
| ❌ 用 `web-view` 包一层 | 个人主体小程序**不支持** web-view；企业主体也需 HTTPS + ICP 备案域名 + 后台配置业务域名。这是"绕过迁移"而非迁移，且体验割裂 |
| ❌ 做小游戏（Canvas） | UI 全部重画，工作量 2–3 倍，收益为 0 |
| ❌ 引入 Vant / WeUI | 会改变现有精调过的配色与布局 |
| ❌ 保留 Bootstrap | WXSS 不支持外部 CSS 库；只用到 4 个工具类，手写几十行更省 |
| ❌ 把 `/data` 接口响应直接塞包 | 比源 JSON 大 46%，且丢掉了构建期可裁剪的优势 |
| ❌ 首版就做"朗读 + 导出" | 这两个是**唯一没有等价物**的能力，会卡住整条链路 |

---

## 9. 里程碑风险清单

| 风险 | 触发条件 | 应对 |
|---|---|---|
| 主包超 2MB | 全量数据进主包 + 后续加功能 | 分包按学段（§4.3 方案①） |
| iPhone 白屏 | 缺 `lazyCodeLoading` | 从第一次生成就带上，交付前 parse `app.json` 校验 |
| 进度丢失 | 直接用了 `wx.getStorageSync` 返回值当对象 | `getStorageSync` 无值时返回 `''`，必须判空 |
| 同义关判定失灵 | 中文翻译调整后未同步 | 小程序版与 Web 版共用一份源 JSON（唯一真相），改译文两边同时生效 |
| 样式错位 | Bootstrap 的 `d-flex` / `gap` 未手写 | 在 `app.wxss` 补齐用到的工具类 |
| 朗读体验差 | 选了同声传译插件但超频 | 加节流 + 失败降级为静音 |

---

## 10. 交付与发布（两条路线，二选一）

### 路线 A：WorkBuddy 托管（无需开发者工具）

生成工程 → 在**产物卡片**里打开预览 → 预览面板右上角「分享 / Share」→ 发布。
可发「试用小程序」（扫码一次、无需 AppID、14 天）或「绑定已有小程序」。
适合：先拿到二维码给同学试玩，或不想装几百 MB 开发者工具。

### 路线 B：微信开发者工具（专业开发）

用 DevTools 做**真机调试**、编译、模拟器预览、自动化测试、性能分析。
适合：要长期运营、需要反复真机验证动画/音频效果。
注：正式版需在小程序公众平台完成**备案**并填写**隐私协议**。

---

## 11. 实施结果（2026-09-23 已完成）

工程目录 `word-match-miniprogram/`，原生小程序，单页 `pages/play/play`。

### 11.1 交付清单

| 文件 | 行数 | 说明 |
|---|---|---|
| `project.config.json` | 18 | `compileType: miniprogram`，`es6/enhance/postcss/minified` 全开（**`async/await` 靠 es6 转译，必须开**） |
| `app.json` | 15 | 单页路由 + `lazyCodeLoading: requiredComponents`（**缺它部分 iPhone 会白屏，模拟器看不出来**） |
| `app.js` | 19 | `wx.setInnerAudioOption({obeyMuteSwitch:false})`，包 try/catch 兼容旧基础库 |
| `app.wxss` | 104 | `page` 基础样式 + 手写替代 Bootstrap 的按钮原语 + `.wm-em` 强调系列 |
| `utils/store.js` | 112 | 存储适配层（吞掉「取不到返回空串」的怪癖）+ **词库指纹作废旧进度** |
| `utils/bank.js` | 125 | 词库访问层，**按学段按需 require**（大学那 632 KB 不进首屏）+ 单关物化缓存 |
| `utils/sound.js` | 96 | `wx.createWebAudioContext()` 现场合成振荡器，**零音频资源**，低版本静默降级 |
| `utils/ui.js` | 57 | `wx.showToast` / `wx.showModal` 包装（注意 `icon` 没有 `warn`，提醒类走 `'none'`） |
| `pages/play/play.js` | 1038 | 业务逻辑，逐条对齐 Web 版 |
| `pages/play/play.wxml` | 297 | 模板 |
| `pages/play/play.wxss` | 956 | 样式，以 Web 版**移动端规则**为基线 + px→rpx(×2) + CSS 变量展开成字面值 |
| `data/books.js` | — | 26 册元信息 + 4 学段 |
| `data/levels-*.js` | — | 按学段拆 4 份，合计 **829.6 KB** |

### 11.2 关键移植决策

| 位置 | Web 版 | 小程序版 | 原因 |
|---|---|---|---|
| 状态管理 | Alpine 响应式 | 权威状态放 `this._s`，`sync()` 一次性投影到 `data` | 小程序无响应式，随手改 `this.data.xxx` 极易漏 `setData`（现象是「逻辑对但画面不动」） |
| 触摸反馈 | `:hover` | `hover-class="wm-tap"` | 顺带根除了 Web 版「移动端点完留粘滞 `:hover` 导致白字压白底」的整类问题 |
| 遮罩关闭 | `@click.self` | 遮罩 `bindtap` + 内容区 `catchtap="noop"` | 等价语义 |
| 朗读 | `speechSynthesis` | **砍掉**（§3.1 决策），自动学习那一步换成等长「默读一遍」停顿 | 小程序无原生 TTS；节奏感保留，不靠压短间隔作弊 |
| 导出单页 HTML | `Blob` + `<a download>` | **砍掉**（§3.2 决策） | 小程序不能下载文件 |
| 进度条 | 逐关画小格 | 关卡数 > 60 时改用**比例横条** | 大学的 1255 / 944 关逐关画会有上千个 0.5px 碎点，既看不出进度又白渲染节点 |
| 棋盘布局 | `display: grid` | `flex` 两列 | grid 在小程序里支持度参差 |
| 键盘 ← / → | 支持 | 去掉 | 手机没有键盘 |
| 启动自动学习 | 沿用 `_enterBookInAuto()` | 跨册**只换册、不重启**自动学习。原版这里必须 `await` 大学词库，小程序全内置后异步消失，但函数刻意保留以守住该语义 |

### 11.3 验证情况（以及验证不到的部分）

**能做到的验证，全部通过：**

| 项 | 手段 | 结果 |
|---|---|---|
| 词库完整性 | `node` 实跑 `data/*.js`，逐册比对 `levelCount` / `wordCount` | 26 册 / **3448 关** / **20191 词**，逐册零偏差 |
| 逐学段规模 | 同上 | 小学 161 关/846 词 · 初中 419/2309 · 高中 669/3877 · 大学 2199/13159 |
| 包体积 | 文件系统实测 | **829.6 KB**（压缩前 1043.8 KB，省 20.5%），主包 2048 KB 上限下**不需要分包** |
| JS 语法 | `node --check` 全部 11 个文件 | 通过 |
| JSON 合法性 | 逐个 `JSON.parse` | 通过 |
| 浏览器专有 API 残留 | 正则扫 `document` / `window` / `localStorage` / `Blob` / `fetch` / `speechSynthesis` / `ResizeObserver` | **0 处** |
| 路由一致性 | 比对 `app.json.pages` 与实际文件 | 四件套齐全 |
| WXML / WXSS 静态校验 | `scripts/learn/validate_miniprogram.js` | **ERROR 0 / WARN 0** |
| 校验器自身有效性 | 变异测试：注入 5 处错误（拼错字段名 / 残留 `<div>` / 标签不匹配 / `display:grid` / `var(--x)`） | **5 处全部命中**，未漏报 |

> 注（2026-09-24）：四六级两册的关卡已由「按词性粗分」改为「按 68 个语义域归类」，
> 关数随之为 四级 1255 / 六级 944（大学合计 2199，26 册合计 3448），词数不变。上表数字已按新切关结果更新。

**验证不到的部分（必须说明）：** 本机**未安装微信开发者工具**，因此没有跑过真实编译、没有模拟器截图、没有真机联调。以下只能靠用户首次打开工程时确认：

- 视觉走查（间距、字号、配色是否与 Web 版移动端一致）
- 音效实际发声（`wx.createWebAudioContext` 在真机上的表现，需基础库 ≥ 2.19.0）
- 触摸反馈（`hover-class`）手感
- 超小屏（`max-height: 620px` 分支）与超长中文卡片的换行

### 11.4 静态校验器

本机没有开发者工具，所以补了一个 `scripts/learn/validate_miniprogram.js` 作为替代（放在仓库 `scripts/` 下，**不进小程序包体**）：

```
node scripts/learn/validate_miniprogram.js
```

覆盖 10 类问题：WXML 标签配平 / `wx:for` 缺 `wx:key` / `wx:else` 相邻性 / `{{}}` 引用不存在的 `data` 字段 / 残留 HTML 标签 / WXSS 残留浏览器专有写法 / WXSS 残留标签选择器 / CSS 变量未展开 / class 交叉引用 / `app.json` 页面四件套。

三个实现要点（都是踩过的坑）：① 注释必须先整体剥离再逐行判断，否则文档里举的例子会被当成真代码报假错；② `data` 字段要能解析「一行多个键」的写法（本项目就是这么排的）；③ `class` 属性里的 `{{ 三元 }}` 要先剔除再切分，否则会切出 `{{autoMode` 这种碎片。

### 11.5 与 Web 版的后续同步

词库是**唯一真相**：小程序数据由 `scripts/learn/build_miniprogram_data.py` 从线上服务搬运（**不重写切关算法**，避免两份实现漂移）。Web 版改了词库后重跑该脚本即可，小程序侧的进度会因**词库指纹变化而自动作废**（对应 Web 版当年手工做的 v1→v2 迁移，这里改成按指纹自动判定）。错题本存的是单词本身，不受切关影响，**不清除**。

---

## 附：本次调研的原始数据

```
页面          2940 行 / 132,227 B，JS 逻辑段 ≈ 1387 行
绑定          @click 37 · x-show 19 · x-text 56 · x-for 10 · x-if 5 · :class/:style 29
浏览器 API    localStorage 14 · speechSynthesis 10 · AudioContext 2 · Blob 3
              createObjectURL 1 · download 7 · ResizeObserver 2 · document.* 6
触屏/拖拽     touchstart/touchmove/pointerdown/mousedown/draggable = 全 0
动画帧        requestAnimationFrame = 0 · IntersectionObserver = 0
词库          pep 396.5 KB + cet 647.3 KB = 1043.8 KB（主包上限 2048 KB）
规模          26 册 / 3448 关 / 20191 词（PEP 24 册 1249 关 7032 词 + 四六级 2 册 2199 关 13159 词）
```

#!/usr/bin/env node
'use strict';
/* ===========================================================================
 * 单词趣味配对（微信小程序）· Node 断言探针
 * ---------------------------------------------------------------------------
 * 小程序没有编译器、也没有能在 CI 里跑起来的运行时，但本项目的页面逻辑是**普通
 * CommonJS**：`Page({...})` 只是个注册调用，`wx.*` 全部走 utils/ 一层收口。
 * 所以把 `wx` / `Page` 桩掉，就能在 Node 里直接 `require` 页面文件、拿到那个对象、
 * 按真实调用序列驱动它，并断言它 setData 出去的东西。
 *
 * 覆盖四块：
 *   A. utils/search.js —— 检索口径（与网页版 WM_SEARCH 同一套）：
 *      完全匹配 > 前缀匹配 > 部分匹配；「最多 5 个」按**去重后的单词数**算；
 *      同一个词的所有出处要么都列、要么都不列；中文要剥掉 n. / v. 词性前缀再比。
 *      期望值用**独立算出来的 oracle** 校验（不拿实现验自己），
 *      并逐条复核「命中行的性质确实符合它自称的档位」。
 *   B. pages/index/index.js —— 主页面：输入去抖后自动下拉、点搜索/回车后落结果、
 *      清除复位、空关键词给提示、【查看】拼出的 URL 正确。
 *   C. pages/play/play.js —— 按 ?bookId=&level= 直达指定关；越界 / 不存在的 id /
 *      非数字 level / 无参数，四种情况都不能白屏。
 *   D. 源码级闸门 —— 类目口径不许回退（「学习」「人教版」「年级册次」…一律不许出现）、
 *      app.json 首页与标题、每页四件套齐全。
 *
 * 用法： node scripts/learn/probe_miniprogram.js
 * 退出码 0 = 全绿；1 = 有 FAIL。
 * =========================================================================== */

const path = require('path');
const fs = require('fs');

const ROOT = path.resolve(__dirname, '../../word-match-miniprogram');

/* ---------------------------------------------------------------- 桩 */
const storage = {};
const toasts = [];
const navs = [];

global.wx = {
  getStorageSync: k => (k in storage ? storage[k] : ''),
  setStorageSync: (k, v) => { storage[k] = v; },
  removeStorageSync: k => { delete storage[k]; },
  showToast: o => { toasts.push((o && o.title) || ''); },
  showModal: o => { if (o && o.success) o.success({ confirm: false }); },
  navigateTo: o => { navs.push(o && o.url); },
  setInnerAudioOption: () => { },
  /* 刻意**不**提供 createWebAudioContext：让 utils/sound.js 走「基础库不支持」的
     降级分支，音效静默失败 —— 探针只关心逻辑，不该去建音频图。 */
};

let registered = null;
global.Page = function (opts) { registered = opts; };
global.App = function () { };

function loadPage(rel) {
  const file = path.join(ROOT, rel);
  delete require.cache[require.resolve(file)];
  registered = null;
  require(file);
  if (!registered) throw new Error(rel + ' 没有调用 Page()');
  return registered;
}

/* 迷你版页面实例：data 深拷一份，setData 直接并进去。
   （小程序里 this.data 在 setData 返回后就已更新，只是视图渲染是异步的 ——
   断言只读 this.data，所以这样等价。） */
function instantiate(def) {
  const inst = {};
  for (const k of Object.keys(def)) inst[k] = def[k];
  inst.data = JSON.parse(JSON.stringify(def.data || {}));
  inst.setData = function (patch) { Object.assign(this.data, patch); };
  return inst;
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

/* ---------------------------------------------------------------- 断言 */
let pass = 0;
const fails = [];
function check(name, cond, detail) {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fails.push(name); console.log('  ✗ ' + name + (detail ? '   ← ' + detail : '')); }
}
const section = t => console.log('\n' + t);

/* ---------------------------------------------------------------- 数据与 oracle */
const bank = require(path.join(ROOT, 'utils/bank.js'));
const search = require(path.join(ROOT, 'utils/search.js'));

/* 把「每个词在全库出现几次」独立算出来，不依赖 search 的实现。
   Object.create(null)：词库里将来若真有叫 constructor / toString 的词，
   用 {} 会读到原型上的键，把计数算成 NaN。 */
const WORD_COUNT = Object.create(null);
let ALL_ROWS = 0;
bank.forEachPair((book, level, theme, en) => {
  const e = String(en == null ? '' : en);
  if (!e) return;
  ALL_ROWS++;
  const k = e.toLowerCase();
  WORD_COUNT[k] = (WORD_COUNT[k] || 0) + 1;
});

/* 按**单词**分组统计出处数。键必须和检索口径一致地转小写 —— 「National Day」这种
   带大写的词条，用原串当键会与 WORD_COUNT（小写键）对不上，把 oracle 自己搞红。 */
const groupsOf = hits => {
  const m = new Map();
  for (const h of hits) {
    const k = String(h.en).toLowerCase();
    m.set(k, (m.get(k) || 0) + 1);
  }
  return m;
};

/* 每行自称的档位必须真的成立（只看这一行自己的 en/zh，不碰实现内部） */
function rankHolds(row, q) {
  const en = String(row.en).toLowerCase();
  const parts = search.zhParts(row.zh);
  const exact = en === q || parts.indexOf(q) >= 0;
  const prefix = en.indexOf(q) === 0 || parts.some(p => p.indexOf(q) === 0);
  if (row.rank === 0) return exact;
  if (row.rank === 1) return prefix && !exact;
  if (row.rank === 2) return en.indexOf(q) >= 0 || String(row.zh).toLowerCase().indexOf(q) >= 0;
  return false;
}

(async function main() {

  /* ================================================================ A */
  section('A. utils/search.js —— 检索口径');

  const totals = bank.totals();
  check('词库规模与 data/books.js 声明一致（26 册 / 3534 组 / 20191 词）',
    totals.books === 26 && totals.levels === 3534 && totals.words === 20191,
    JSON.stringify(totals));
  check('索引摊平后的行数 = 词条总数（没有漏册漏关）',
    search._size() === ALL_ROWS && ALL_ROWS === 20191,
    'size=' + search._size() + ' oracle=' + ALL_ROWS);

  const empty = search.run('   ');
  check('空输入（只有空格）零命中、mode=empty，不抛异常',
    empty.hits.length === 0 && empty.mode === 'empty', empty.mode);

  const none = search.run('qqzzxx');
  check('查不到的词 mode=none、零命中',
    none.hits.length === 0 && none.mode === 'none', none.mode);

  /* --- 完全匹配：英文 --- */
  const lib = search.run('liberty');
  check('英文完全匹配：mode=exact',
    lib.mode === 'exact', lib.mode);
  check('英文完全匹配：全部 rank=0 且 en 全等',
    lib.hits.every(h => h.rank === 0 && h.en === 'liberty'),
    JSON.stringify(lib.hits.map(h => h.en + '/' + h.rank)));
  const libWant = WORD_COUNT['liberty'];
  check('完全匹配把**所有出处**都列出来（同词多出处要么全列、要么不列）',
    lib.hits.length === libWant && libWant >= 3,
    'hits=' + lib.hits.length + ' oracle=' + libWant);
  check('每行都带正确的「词库 · 第 N 关」（下标 +1 后展示，且关号不是 id 里那段）',
    lib.hits.every(h => h.levelNo === h.level + 1 && h.src.indexOf('第 ' + h.levelNo + ' 关') > 0),
    lib.hits.map(h => h.src).join(' | '));
  check('uid 唯一（wx:key 直接用它，重复会让列表错位）',
    new Set(lib.hits.map(h => h.uid)).size === lib.hits.length,
    lib.hits.map(h => h.uid).join(','));

  /* --- 完全匹配：中文义项（必须先剥掉 n./v. 词性前缀） --- */
  const zy = search.run('自由');
  check('中文义项完全匹配：剥掉「n.」前缀后能命中，mode 以 exact 开头',
    zy.mode.indexOf('exact') === 0, zy.mode);
  const zyExact = zy.hits.filter(h => h.rank === 0);
  check('中文完全匹配的每一行，其义项里确实有一项与关键词全等',
    zyExact.length > 0 && zyExact.every(h => search.zhParts(h.zh).indexOf('自由') >= 0),
    zyExact.map(h => h.en + '=' + h.zh).join(' | '));
  check('完全匹配排在前缀匹配之前（rank 单调不减）',
    zy.hits.every((h, i) => i === 0 || zy.hits[i - 1].rank <= h.rank),
    zy.hits.map(h => h.rank).join(','));

  const aa = search.run('a');
  check('「a」：完全匹配的 a 全列、前缀只补 5 个不同单词（合计 1 + 5 = 6 个词）',
    aa.mode === 'exact+prefix' && groupsOf(aa.hits).size === 6
      && groupsOf(aa.hits).has('a'),
    'mode=' + aa.mode + ' words=' + [...groupsOf(aa.hits).keys()].join(','));

  /* --- 前缀匹配 --- */
  const libe = search.run('libe');
  const libeGroups = groupsOf(libe.hits);
  check('前缀匹配：mode=prefix，去重单词数 ≤ 5',
    libe.mode === 'prefix' && libeGroups.size <= 5,
    'mode=' + libe.mode + ' words=' + [...libeGroups.keys()].join(','));
  check('前缀匹配：每一行的档位都真的成立（en 或某个义项以关键词开头）',
    libe.hits.every(h => rankHolds(h, 'libe')),
    JSON.stringify(libe.hits.map(h => h.en + '/' + h.rank)));
  check('前缀匹配：每个被列出的单词，其**全部出处**都在结果里（不给半个词）',
    [...libeGroups].every(([w, n]) => n === WORD_COUNT[w]),
    [...libeGroups].map(([w, n]) => w + ':' + n + '/' + WORD_COUNT[w]).join(' '));

  const st = search.run('st');
  const stGroups = groupsOf(st.hits);
  check('上限确实生效：「st」的前缀候选多于 5 个单词，结果正好截在 5 个',
    st.mode === 'prefix' && stGroups.size === 5,
    'words=' + [...stGroups.keys()].join(','));
  check('截断按**单词**而不是按行：被列出的 5 个词各自出处完整',
    [...stGroups].every(([w, n]) => n === WORD_COUNT[w]),
    [...stGroups].map(([w, n]) => w + ':' + n + '/' + WORD_COUNT[w]).join(' '));

  /* 这条是上面那个 bug 的**定点回归闸**：student 在三年级下册第 2 关与七年级上册第 49 关
     各出现一次，两处相隔极远。旧实现边扫边 break，扫到第 6 个新词就停，
     会把 student 排在后面的那一处丢掉（网页版原先同样如此，只是断言没覆盖到）。 */
  const studentRows = st.hits.filter(h => h.en === 'student');
  check('定点回归：student 跨两个词库各有一处，前缀匹配时两条都在（旧实现会漏掉第二条）',
    studentRows.length === 2 && new Set(studentRows.map(h => h.bookId)).size === 2,
    studentRows.map(h => h.src).join(' | ') || '（一条都没有）');

  /* --- 部分匹配：只有前两类都空时才做 --- */
  const part = search.run('berty');
  check('部分匹配：既无完全也无前缀命中时才退到 mode=part，且有结果',
    part.mode === 'part' && part.hits.length > 0, part.mode);
  check('部分匹配：每行确实「包含」关键词（但不是开头），单词数 ≤ 5',
    part.hits.every(h => rankHolds(h, 'berty')) && groupsOf(part.hits).size <= 5,
    part.hits.map(h => h.en + '/' + h.rank).join(' '));

  const tion = search.run('tion');
  check('部分匹配同样受 5 个单词上限约束，且分组完整',
    tion.mode === 'part' && groupsOf(tion.hits).size === 5
      && [...groupsOf(tion.hits)].every(([w, n]) => n === WORD_COUNT[w]),
    tion.hits.map(h => h.en).join(','));

  const upper = search.run('  LIBERTY  ');
  check('检索前 trim + 转小写（「  LIBERTY  」与「liberty」同结果）',
    upper.mode === lib.mode && upper.hits.length === lib.hits.length
      && upper.hits[0].uid === lib.hits[0].uid,
    upper.mode + ' / ' + upper.hits.length);

  /* ================================================================ B */
  section('B. pages/index/index.js —— 主页面（检索首页）');

  const idxDef = loadPage('pages/index/index.js');
  const idx = instantiate(idxDef);
  idx.onLoad();

  /* 页头统计串是 bank.totals() 拼出来的，所以这里**从 totals 反推期望值**，
     别再抄第二遍数字 —— 词库规模真要变，只需要改 A 段那一处 canary
     （'词库规模与 data/books.js 声明一致（26 册 / 3534 组 / 20191 词）'）。 */
  const statsWant = totals.books + ' 个词库 · ' + totals.levels + ' 组 · ' + totals.words + ' 词';
  check('页头统计取自 bank.totals()（不是写死的文案）',
    idx.data.stats === statsWant, idx.data.stats);
  check('刚打开：下拉收起、还没搜索过（下方显示用法说明而不是空结果区）',
    idx.data.dropOpen === false && idx.data.searched === false
      && idx.data.q === '' && idx.data.results.length === 0,
    JSON.stringify({ dropOpen: idx.data.dropOpen, searched: idx.data.searched }));

  idx.onInput({ detail: { value: 'liberty' } });
  check('输入后 q 立刻回填（不等去抖），此刻下拉还没弹（去抖窗口内）',
    idx.data.q === 'liberty' && idx.data.dropOpen === false, idx.data.q);
  await sleep(280);
  check('去抖到点后自动下拉展开，命中与 search.run 一致',
    idx.data.dropOpen === true && idx.data.hits.length === lib.hits.length
      && idx.data.hits[0].en === 'liberty',
    JSON.stringify({ drop: idx.data.dropOpen, n: idx.data.hits.length }));

  const navsBeforeDrop = navs.length;
  idx.onPickDrop({ currentTarget: { dataset: { i: 0 } } });
  check('点下拉里的一行 → 跳玩法页，URL 带上该行所属的词库与关号',
    navs.length === navsBeforeDrop + 1 && navs[navs.length - 1] ===
      '/pages/play/play?bookId=' + encodeURIComponent(lib.hits[0].bookId) + '&level=' + lib.hits[0].level,
    navs[navs.length - 1]);

  idx.onSearch({ detail: {} });
  check('点「搜索」：下拉收起（不再压着结果区）',
    idx.data.dropOpen === false && idx.data.hits.length === 0, String(idx.data.dropOpen));
  check('点「搜索」：结果落到下方列表，与下拉用的是同一份命中',
    idx.data.searched === true && idx.data.results.length === lib.hits.length
      && idx.data.resultQ === 'liberty',
    JSON.stringify({ searched: idx.data.searched, n: idx.data.results.length }));
  check('匹配档位说明有中文口径（exact → 「完全匹配」）',
    idx.data.resultTip === '完全匹配', idx.data.resultTip);

  const navsBeforeRow = navs.length;
  idx.onPickResult({ currentTarget: { dataset: { i: idx.data.results.length - 1 } } });
  check('点结果行的【查看】→ 跳到该行自己的那一关（不是第一行）',
    navs.length === navsBeforeRow + 1
      && navs[navs.length - 1].indexOf('level=' + idx.data.results[idx.data.results.length - 1].level) > 0,
    navs[navs.length - 1]);

  idx.onInput({ detail: { value: 'libe' } });
  idx.onSearch({ detail: { value: 'libe' } });
  check('键盘回车（bindconfirm）以事件里的 value 为准，拿到的是最新关键词',
    idx.data.resultQ === 'libe' && idx.data.q === 'libe'
      && idx.data.resultTip === '前缀匹配 · 最多 5 个单词',
    idx.data.resultQ + ' / ' + idx.data.resultTip);

  const toastBefore = toasts.length;
  idx.onClear();
  idx.onSearch();
  check('空关键词点「搜索」：提示一下并清干净，不留一个空结果区',
    toasts.length === toastBefore + 1 && toasts[toasts.length - 1].length > 0
      && idx.data.searched === false && idx.data.results.length === 0 && idx.data.q === '',
    JSON.stringify({ toast: toasts[toasts.length - 1], searched: idx.data.searched }));

  idx.onInput({ detail: { value: 'qqzzxx' } });
  await sleep(280);
  check('查不到时下拉给出「没有匹配」而不是空白面板',
    idx.data.dropOpen === true && idx.data.hits.length === 0, JSON.stringify(idx.data.hits));
  idx.onSearch({ detail: {} });
  check('查不到时结果区的档位说明是「没有匹配的单词」',
    idx.data.resultTip === '没有匹配的单词' && idx.data.results.length === 0, idx.data.resultTip);

  idx.onInput({ detail: { value: 'liberty' } });
  await sleep(200);
  idx.onClear();
  check('点清除：输入框、下拉、结果区一起复位',
    idx.data.q === '' && idx.data.dropOpen === false && idx.data.hits.length === 0
      && idx.data.searched === false && idx.data.results.length === 0
      && idx.data.resultTip === '',
    JSON.stringify(idx.data));

  const navBeforePlay = navs.length;
  idx.gotoPlay();
  check('页尾入口进玩法页（不带参数 → 玩法页按上次那册恢复）',
    navs.length === navBeforePlay + 1 && navs[navs.length - 1] === '/pages/play/play',
    navs[navs.length - 1]);

  /* 点空白处收起下拉：根容器 bindtap + 搜索区 catchtap 拦冒泡，等价网页版的 @click.outside */
  idx.onInput({ detail: { value: 'liberty' } });
  await sleep(280);
  check('下拉开着（准备验「点空白处收起」）', idx.data.dropOpen === true);
  idx.closeDrop();
  check('点搜索区以外的地方 → 下拉收起、命中行清空',
    idx.data.dropOpen === false && idx.data.hits.length === 0,
    JSON.stringify({ drop: idx.data.dropOpen, n: idx.data.hits.length }));
  idx.closeDrop();
  check('closeDrop 幂等（已收起时不再写 data —— 根容器会收到所有子节点冒泡）',
    idx.data.dropOpen === false && idx.data.hits.length === 0);

  /* 离开页面后，去抖回调不能再去 setData —— 那会得到「对已销毁页面 setData」的告警。
     用独立实例驱动，别把主实例搞脏。 */
  {
    let calls = 0;
    const spy = instantiate(idxDef);
    spy.setData = function (p) { calls++; Object.assign(this.data, p); };
    spy.onLoad();
    spy.onInput({ detail: { value: 'liberty' } });   // 回填 q 是一次**同步** setData，要算进去
    const base = calls;
    spy.onUnload();
    await sleep(340);
    check('离开页面后去抖回调不再 setData（避免对已销毁页面 setData 的告警）',
      calls === base, 'called=' + calls + ' base=' + base);
  }

  /* ================================================================ C */
  section('C. pages/play/play.js —— 按 ?bookId=&level= 直达');

  const playDef = loadPage('pages/play/play.js');

  function openPlay(query) {
    const inst = instantiate(playDef);
    delete storage['word_match_progress_v2'];    // 让「恢复上次那册」走可控的默认态
    inst.onLoad(query);
    return inst;
  }

  const jump = openPlay({ bookId: 'pep-h-4', level: 11 });
  check('带 bookId + level 进来 → 直达那一关（level 是下标，第 N 关 = N-1）',
    jump.data.bookId === 'pep-h-4' && jump.data.levelIndex === 11
      && jump.data.bookLabel === '必修4',
    JSON.stringify({ id: jump.data.bookId, idx: jump.data.levelIndex, label: jump.data.bookLabel }));
  check('直达的那一关真的装上了卡片（不是空棋盘）',
    jump.data.levelCount === 61 && jump.data.leftCards.length > 0
      && jump.data.leftCards.length === jump.data.rightCards.length,
    JSON.stringify({ lc: jump.data.levelCount, left: jump.data.leftCards.length }));

  const oob = openPlay({ bookId: 'pep-3-1', level: 9999 });
  check('level 越界 → 退回该册第 1 关，不白屏',
    oob.data.bookId === 'pep-3-1' && oob.data.levelIndex === 0
      && oob.data.leftCards.length > 0,
    JSON.stringify({ idx: oob.data.levelIndex, left: oob.data.leftCards.length }));

  const badId = openPlay({ bookId: 'not-a-book', level: 3 });
  check('bookId 不存在（分享链接被手改 / 词库改版）→ 按默认册打开',
    badId.data.bookId === bank.books()[0].id && badId.data.leftCards.length > 0,
    badId.data.bookId);

  const badLevel = openPlay({ bookId: 'pep-3-1', level: 'abc' });
  check('level 不是数字 → 按该册第 1 关处理（不 NaN、不空棋盘）',
    badLevel.data.levelIndex === 0 && badLevel.data.leftCards.length > 0,
    String(badLevel.data.levelIndex));

  const noArgs = openPlay(undefined);
  check('不带参数进来 → 保持原来的行为（回到上次那册）',
    noArgs.data.bookId === bank.books()[0].id && noArgs.data.levelIndex === 0,
    noArgs.data.bookId);

  const onlyBook = openPlay({ bookId: 'pep-9-1' });
  check('只给 bookId（以后加「练这一册」按钮）→ 从该册第 1 关开始',
    onlyBook.data.bookId === 'pep-9-1' && onlyBook.data.levelIndex === 0
      && onlyBook.data.levelCount === 103,
    JSON.stringify({ id: onlyBook.data.bookId, idx: onlyBook.data.levelIndex }));

  const r0 = search.run('vacation').hits[0];
  const play2 = openPlay({ bookId: r0.bookId, level: String(r0.level) });
  check('端到端：检索结果 → URL → 玩法页，落在同一册同一关',
    play2.data.bookId === r0.bookId && play2.data.levelIndex === r0.level
      && play2.data.leftCards.length > 0,
    r0.src + ' → ' + play2.data.bookLabel + ' 第 ' + (play2.data.levelIndex + 1) + ' 关');

  /* ================================================================ D */
  section('D. 源码级闸门 —— 类目口径与工程约束');

  const appJson = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  check('app.json 第一页是检索首页（打开即查询工具，适配「工具 - 信息查询」类目）',
    appJson.pages[0] === 'pages/index/index'
      && appJson.pages.indexOf('pages/play/play') > 0,
    JSON.stringify(appJson.pages));
  check('导航标题是「单词趣味配对」（与新注册的小程序名一致）',
    appJson.window.navigationBarTitleText === '单词趣味配对',
    appJson.window.navigationBarTitleText);
  check('app.json 仍带 lazyCodeLoading: requiredComponents（漏了部分 iPhone 会白屏）',
    appJson.lazyCodeLoading === 'requiredComponents', String(appJson.lazyCodeLoading));

  /* 扫源码，但**排除 data/**：那里是词库内容，本来就含「学习」这类释义，不该改 */
  function walk(dir, out) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) { if (e.name !== 'data' && e.name !== 'node_modules') walk(p, out); }
      else if (/\.(js|wxml|wxss|json)$/.test(e.name)) out.push(p);
    }
    return out;
  }
  const codeFiles = walk(ROOT, []);
  const codeText = codeFiles.map(f => fs.readFileSync(f, 'utf8')).join('\n');

  const BANNED = ['学习', '学完', '已学', '人教版', '年级册次', '英语单词匹配', '通关', '闯关', '教材', '课程'];
  const hitWords = BANNED.filter(w => codeText.indexOf(w) >= 0);
  check('小程序自身代码/文案里已无教育口径词（词库释义不动）',
    hitWords.length === 0, hitWords.join(' / '));
  check('替换后的口径确实落地（代码里出现「自动练习」与「选择词库」）',
    codeText.indexOf('自动练习') >= 0 && codeText.indexOf('选择词库') >= 0);
  check('词库数据文件未被误改（levels-*.js 里仍保留词义原文）',
    fs.readFileSync(path.join(ROOT, 'data/levels-college.js'), 'utf8').indexOf('学习') >= 0,
    'data/ 里应该有「学习」这类释义，没有说明被动过');

  const idxWxml = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxml'), 'utf8');
  check('首页搜索框是**常驻**的（模板里直接有 input + bindinput + 回车搜索）',
    idxWxml.indexOf('<input') >= 0 && idxWxml.indexOf('bindinput=') >= 0
      && idxWxml.indexOf('confirm-type="search"') >= 0);
  check('首页两条交互都在：输入即出的下拉 + 点搜索出的结果列表',
    idxWxml.indexOf('onPickDrop') >= 0 && idxWxml.indexOf('bindtap="onSearch"') >= 0
      && idxWxml.indexOf('onPickResult') >= 0);
  check('每条结果带【查看】，并显示单词 / 翻译 / 词库·关号',
    idxWxml.indexOf('查看') >= 0 && idxWxml.indexOf('{{item.en}}') >= 0
      && idxWxml.indexOf('{{item.zh}}') >= 0 && idxWxml.indexOf('{{item.src}}') >= 0);

  /* 下拉能被「点空白处」关掉：根容器收 bindtap，搜索区用 catchtap 拦冒泡。
     少了 catchtap，点输入框的瞬间就会被根容器关掉下拉 —— 这是最容易漏的一处。 */
  check('下拉可点空白处收起：根容器 bindtap="closeDrop"，搜索区 catchtap 拦冒泡',
    idxWxml.indexOf('class="ix-root" bindtap="closeDrop"') >= 0
      && idxWxml.indexOf('class="ix-search-wrap" catchtap="noop"') >= 0);

  /* 下拉高度要装得下 5 行（调小了「最多 5 个单词」会变成「看着只有 4 个」） */
  const idxWxss = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxss'), 'utf8');
  const mDrop = /\.ix-drop-scroll\s*\{[^}]*max-height:\s*(\d+)rpx/.exec(idxWxss);
  /* 真实行高 ≈ 144rpx：行高由**右列**决定（.ix-hit-src 的 max-width 260rpx 会把
     「四级 · 第 833 关 · 秩序与身心 · 8」折成 2 行，再加【查看】按钮），
     不是左列那两行文字（按左列估会得到 116rpx，于是写下 620rpx —— 第 5 行只露一半）。
     5 行 = 720rpx，阈值就卡 720：调小了这里就红，别让「最多列 5 个」变成「看着只有 4 个」。 */
  check('下拉滚动区高度按 5 行留（≥720rpx = 实测行高 144rpx × 5）',
    !!mDrop && Number(mDrop[1]) >= 720, mDrop ? mDrop[1] + 'rpx' : '（没找到 max-height）');

  for (const pg of appJson.pages) {
    const miss = ['.js', '.wxml', '.json', '.wxss']
      .filter(ext => !fs.existsSync(path.join(ROOT, pg + ext)));
    check('页面 ' + pg + ' 四件套齐全', miss.length === 0, miss.join(','));
  }

  /* ================================================================ 结果 */
  console.log('\n==============================================');
  console.log(' 探针结果：' + pass + ' 通过 / ' + fails.length + ' 失败');
  if (fails.length) {
    console.log(' 失败项：');
    for (const f of fails) console.log('   - ' + f);
  }
  console.log('==============================================');
  process.exit(fails.length ? 1 : 0);
})();

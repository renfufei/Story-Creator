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

/* 默认校验仓库里那份同步副本；也可指向导出目录：
     node scripts/learn/probe_miniprogram.js /tmp/mp-new/word-pair */
const ROOT = path.resolve(process.argv[2] || path.join(__dirname, '../../word-match-miniprogram'));

/* ---------------------------------------------------------------- 桩 */
const storage = {};
const toasts = [];
const navs = [];
const redirects = [];          // redirectTo 的 url（读音分包路由靠它断言）
const sheets = [];             // showActionSheet 的 itemList（校验「6 项上限」这条硬规则）

/* 音频桩：只记录「怎么被调用的」，不真的出声。
   点读有没有跳到正确的起点，全靠这里的 src / startTime 断言 ——
   真机上「从整册开头念」这种毛病，桩里能提前抓住。 */
const audios = [];
function newAudio() {
  const a = {
    src: '', startTime: 0, currentTime: 0, volume: 1, obeyMuteSwitch: true,
    /* paused：真机上 stop() 之后为 true、play() 之后为 false。
       speech 的起播看门狗靠它排除「其实在播、只是 currentTime 不涨」的机型。 */
    paused: true,
    plays: 0, stops: 0, seeks: [], destroyed: false,
    _time: [], _err: [], _play: [],
    onTimeUpdate(f) { this._time.push(f); },
    onError(f) { this._err.push(f); },
    onCanplay(f) { this._canplay = f; },
    onPlay(f) { this._play.push(f); },
    play() {
      /* 真机上 destroy 之后再 play 是静默失败（没声音、不报错）。桩记下来，
         这样「旧页把新页的实例销毁了」这种错才咬得住。 */
      if (this.destroyed) { this.deadPlays = (this.deadPlays || 0) + 1; return; }
      this.plays++;
      this.paused = false;
      for (const f of this._play) f();     // 真机是异步回调，桩里同步发，断言好写
    },
    stop() { this.stops++; this.currentTime = 0; this.paused = true; },
    seek(s) { this.seeks.push(s); this.currentTime = s; },
    destroy() { this.destroyed = true; this.paused = true; }
  };
  audios.push(a);
  return a;
}

/* 受控定时器：speech.js 用 setInterval 主动轮询 currentTime 判停（真机上
   onTimeUpdate 在 seek 之后会停摆，只靠它会「读完第一个词顺着整册一直念」）。
   桩把 setInterval 接管下来，由测试手动走一格 —— 于是「会不会自己停」变成
   确定性断言，而不是等真时钟、还测不出来。 */
const intervals = new Map();
let ivSeq = 0;
global.setInterval = (fn) => { intervals.set(++ivSeq, fn); return ivSeq; };
global.clearInterval = (id) => { intervals.delete(id); };
/* 把虚拟时间推到 t（秒）并让所有轮询跑一格。 */
function advance(a, t) { a.currentTime = t; for (const [id, fn] of [...intervals]) if (intervals.has(id)) fn(); }
/* 同步睡一会儿（硬超时那一条要等真时钟）。 */
function sleepSync(ms) { Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms); }

/* 当前页 route：主包页是 pages/play/play，读音分包页是 packages/<id>/play/play。
   play.js 正是靠这个前缀判断「我是不是已经在读音分包里」，所以桩必须能改它。 */
let currentRoute = 'pages/play/play';
global.getCurrentPages = () => [{ route: currentRoute }];

/* 系统音频中断（锁屏 / 来电）与前后台切换的钩子注册表：speech.js 在 configure 时
   挂一次，探针手动触发，用来验证「锁屏回来怎么自愈」。 */
const interrupt = { begin: [], end: [], show: [], hide: [] };

global.wx = {
  getStorageSync: k => (k in storage ? storage[k] : ''),
  setStorageSync: (k, v) => { storage[k] = v; },
  removeStorageSync: k => { delete storage[k]; },
  showToast: o => { toasts.push((o && o.title) || ''); },
  showModal: o => { if (o && o.success) o.success({ confirm: false }); },
  navigateTo: o => { navs.push(o && o.url); },
  redirectTo: o => { redirects.push(o && o.url); },     // 桩里不真跳，只记录
  /* wx.showActionSheet 的**官方硬限制**：itemList 最多 6 项，超了直接 fail。
     桩照真机模拟这条，于是「拿 actionSheet 列 14 个专题」的旧实现会得到干净的红色，
     而不是 TypeError 把整个探针打断（那样后面上百条都测不到）。 */
  showActionSheet: o => {
    const list = (o && o.itemList) || [];
    sheets.push(list.slice());
    if (list.length > 6) {
      if (o && o.fail) o.fail({ errMsg: 'showActionSheet:fail parameter error: itemList should not be larger than 6' });
      return;
    }
    if (o && o.success) o.success({ tapIndex: 0 });
  },
  createInnerAudioContext: () => newAudio(),
  setInnerAudioOption: () => { },
  onAudioInterruptionBegin: f => { interrupt.begin.push(f); },
  onAudioInterruptionEnd: f => { interrupt.end.push(f); },
  onAppShow: f => { interrupt.show.push(f); },
  onAppHide: f => { interrupt.hide.push(f); },
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

/* 丢掉某个模块的缓存。★ 必须经 require.resolve：ROOT 可能是 /tmp（符号链接），
   而 require.cache 的键是 realpath（/private/tmp），直接拿拼接路径删不掉。 */
function dropCache(rel) {
  try { delete require.cache[require.resolve(path.join(ROOT, rel))]; } catch (e) { /* 没加载过 */ }
}

/* 与 loadPage 相同，但**不清模块缓存** —— 用来模拟「第二次进入同一册的分包页」
   （小程序模块只执行一次，顶层代码不会再跑，只有 onLoad 里的注入点能生效）。 */
function loadPageKeepCache(rel) {
  registered = null;
  require(path.join(ROOT, rel));
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
  check('词库规模与 data/books.js 声明一致（27 册 / 3573 组 / 20349 词）',
    totals.books === 27 && totals.levels === 3573 && totals.words === 20349,
    JSON.stringify(totals));
  check('索引摊平后的行数 = 词条总数（没有漏册漏关）',
    search._size() === ALL_ROWS && ALL_ROWS === 20349,
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
     （'词库规模与 data/books.js 声明一致（26 册 / 3546 组 / 20191 词）'）。 */
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

  /* ================================================================ B2. 序列配对入口
     曾经的 bug：点【序列配对】没有任何反应 —— 原因是 UI 用了 wx.showActionSheet，
     它**一次最多列 6 项**（微信硬限制），而专题册有 14 个主题 ⇒ API 直接 fail，
     fail 回调里又什么都不做，于是「点了没反应」。
     下面这几条把「面板能列全、能选、能跳、起点对」钉死。 */
  section('B2. 【序列配对】入口 —— 页内专题面板（不是 actionSheet）');

  const storeMod = require(path.join(ROOT, 'utils/store.js'));
  const idxSrc = fs.readFileSync(path.join(ROOT, 'pages/index/index.js'), 'utf8');
  check('首页源码不再调用 wx.showActionSheet（6 项上限，14 个主题必然失败）',
    !/wx\.showActionSheet\s*\(/.test(idxSrc));
  check('首页挂了页内专题面板的处理器（gotoTopic / pickTopic / closeTopic）',
    typeof idxDef.gotoTopic === 'function' && typeof idxDef.pickTopic === 'function'
      && typeof idxDef.closeTopic === 'function');

  const topicBookInfo = bank.books().filter(b => b.stage === '专题')[0];
  check('词库里存在序列配对册（stage=专题）', !!topicBookInfo,
    topicBookInfo ? topicBookInfo.id : '（没有）');

  /* 主题清单用 bank 现算（不写死 14），并且按「同一主题连续排」口径分组 */
  const themes = [];
  {
    const seen = {};
    const n = bank.levelCount(topicBookInfo.id);
    for (let i = 0; i < n; i++) {
      const lv = bank.levelAt(topicBookInfo.id, i);
      if (!lv || !lv.theme) continue;
      if (!seen[lv.theme]) {
        seen[lv.theme] = { name: lv.theme, first: i, last: i };
        themes.push(seen[lv.theme]);
      }
      seen[lv.theme].last = i;
    }
  }
  check('专题册主题数 > 6（这正是 actionSheet 装不下的临界点）', themes.length > 6, themes.length + ' 个');

  /* 把「为什么不能用 actionSheet」也做成可执行断言：桩按官方 6 项上限模拟，
     一次塞进全部主题会被微信直接 fail —— 谁日后改回 actionSheet，这条就会红。 */
  let sheetFailed = false;
  wx.showActionSheet({
    itemList: themes.map(t => t.name),
    success: () => { },
    fail: () => { sheetFailed = true; }
  });
  check('wx.showActionSheet 装不下全部主题（桩按官方 6 项上限 fail）',
    sheetFailed === true && themes.length > 6, themes.length + ' 项 → fail=' + sheetFailed);

  delete storage[storeMod.KEYS.progress];
  const idxT = instantiate(idxDef);
  idxT.onLoad();
  check('首页认出序列配对册（入口才会显示）', idxT.data.hasTopic === true);

  const navsBeforeTopic = navs.length;
  idxT.gotoTopic();
  check('点【序列配对】→ 弹面板，而不是直接跳页',
    idxT.data.topicOpen === true && navs.length === navsBeforeTopic,
    JSON.stringify({ open: idxT.data.topicOpen, nav: navs.length - navsBeforeTopic }));
  /* 旧实现没有 topicRows，所以一律用 `|| []` 兜住 —— 反向验证时让它「红」而不是崩。 */
  const rowsT = idxT.data.topicRows || [];
  check('面板条目 = 全部主题，一个不少（含第 7 项之后的，旧 actionSheet 到第 6 项就没了）',
    rowsT.length === themes.length
      && rowsT.every((r, i) => r.name === themes[i].name && /关 · \d+ 词/.test(r.sub)),
    rowsT.map(r => r.name).join(','));
  check('面板第 7 条（下标 6）确实存在且有可点内容 —— 旧实现到这就超限失败',
    !!rowsT[6] && !!rowsT[6].name, rowsT[6] && rowsT[6].name);

  const navsBeforePick = navs.length;
  if (typeof idxT.pickTopic === 'function') idxT.pickTopic({ currentTarget: { dataset: { i: 6 } } });
  check('点第 7 个主题 → 关面板 + 进玩法页，落在该主题第一关',
    idxT.data.topicOpen === false && navs.length === navsBeforePick + 1
      && navs[navs.length - 1] === '/pages/play/play?bookId='
        + encodeURIComponent(topicBookInfo.id) + '&level=' + themes[6].first,
    navs[navs.length - 1]);

  /* 起点口径：该主题前两关练过 ⇒ 从第 3 关进（与网页端同一规则） */
  storage[storeMod.KEYS.progress] = JSON.stringify({
    [topicBookInfo.id]: [themes[6].first, themes[6].first + 1]
  });
  const idxP = instantiate(idxDef);
  idxP.onLoad();
  idxP.gotoTopic();
  const rowsP = idxP.data.topicRows || [];
  check('面板条目带上「已练 N 关」（读的是本册进度）',
    !!rowsP[6] && /已练 2 关/.test(rowsP[6].sub), rowsP[6] && rowsP[6].sub);
  const navsBeforeJump = navs.length;
  if (typeof idxP.pickTopic === 'function') idxP.pickTopic({ currentTarget: { dataset: { i: 6 } } });
  check('起点跳过已练过的关：该主题前两关练过 ⇒ 直接进第 3 关',
    navs.length === navsBeforeJump + 1
      && navs[navs.length - 1].slice(-('level=' + (themes[6].first + 2)).length)
        === 'level=' + (themes[6].first + 2),
    navs[navs.length - 1]);

  delete storage[storeMod.KEYS.progress];
  const idxC = instantiate(idxDef);
  idxC.onLoad();
  idxC.gotoTopic();
  const navsBeforeClose = navs.length;
  if (typeof idxC.closeTopic === 'function') idxC.closeTopic();
  check('点遮罩 / 【取消】→ 面板收起，不跳页',
    idxC.data.topicOpen === false && navs.length === navsBeforeClose, String(idxC.data.topicOpen));

  /* WXML / WXSS 侧的硬要求：遮罩可关、面板本体拦冒泡、列表自身可滚 */
  const sheetWxml = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxml'), 'utf8');
  const sheetWxss = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxss'), 'utf8');
  check('WXML：遮罩 catchtap 关闭 + 面板本体 catchtap 拦冒泡（点列表不会误关）',
    /ix-sheet-wrap[^>]*catchtap="closeTopic"/.test(sheetWxml)
    && /class="ix-sheet" catchtap="noop"/.test(sheetWxml));
  check('WXML：每条主题整行可点（bindtap="pickTopic" + data-i）',
    /wx:for="\{\{topicRows\}\}"/.test(sheetWxml) && /bindtap="pickTopic"/.test(sheetWxml));
  check('WXSS：列表自身可滚（14 条一屏放不下）且避让底部安全区',
    /\.ix-sheet-list[^}]*max-height[^}]*vh/.test(sheetWxss)
    && /env\(safe-area-inset-bottom\)/.test(sheetWxss));

  /* ================================================================ C */
  section('C. pages/play/play.js —— 按 ?bookId=&level= 直达');

  const playDef = loadPage('pages/play/play.js');

  /* 有读音的册，真机上打开的是**分包页**（主包页只认出来就 redirect，不做初始化）。
     探针必须跟真机一致：该册有分包就加载分包页、route 也设成分包页，
     否则测到的是「redirect 之后什么都没初始化」的空壳，一堆假红。 */
  const packOf = (bookId) => `packages/${bookId}/play`;
  const hasPack = (bookId) => fs.existsSync(path.join(ROOT, packOf(bookId), 'play.js'));
  function openPlay(query) {
    /* bookId 不存在时页面会退回默认册 —— 那多半是有读音的册，所以这里也按默认册的路由走，
       模拟的是「redirect 完成之后」的真实状态。 */
    const want = (query && query.bookId && bank.book(query.bookId))
      ? query.bookId : bank.books()[0].id;
    const usePack = hasPack(want);
    const def = usePack ? loadPage(packOf(want) + '/play.js') : playDef;
    currentRoute = usePack ? packOf(want) : 'pages/play/play';
    const inst = instantiate(def);
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
      && onlyBook.data.levelCount === 104,
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

  /* ================================================================ E. 读音分包
     微信硬规定：主包不能使用分包资源（开发者工具能播、真机静默无声）。
     所以「播音频的页面」必须和音频同处一个分包 ⇒ 每册复制一份玩法页进分包，
     主包的玩法页只负责识别「这册有读音」并 redirect 过去。
     这一节就守这两件事：路由对不对、点读有没有跳到正确的起点。 */
  const APP = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const subs = APP.subpackages || APP.subPackages || [];
  const subRoots = subs.map(s => s.root);

  section('E. 读音分包 —— 主包只做路由，分包页才点读');

  if (!subRoots.length) {
    console.log('  （本工程的 app.json 没有 subpackages，跳过：这份是加读音之前的旧结构）');
  } else {
    const BOOK = 'pep-3-1';
    const PLAIN = 'pep-7-1';                     // 初中册，没有读音
    const subPlay = `packages/${BOOK}/play/play.js`;
    const subIndexPath = path.join(ROOT, `packages/${BOOK}/audio/index.js`);
    const speechPath = path.join(ROOT, 'utils/speech.js');
    const store = require(path.join(ROOT, 'utils/store.js'));

    /* 分包数不再写死：books.js 里 hasAudio=1 的册就是应该有的分包。
       两边对不上（漏册 / 多册）都说明导出与音频资源不同步。 */
    const audioIds = bank.books().filter(b => b.hasAudio).map(b => b.id);
    check('app.json 登记了全部读音分包（= books.js 里 hasAudio 的册）',
      subRoots.length === audioIds.length
      && audioIds.every(id => subRoots.indexOf(`packages/${id}`) >= 0),
      subRoots.join(',') + ' vs ' + audioIds.join(','));
    check('序列配对册（pep-topic）也在读音分包里 —— 它以前是唯一没有读音的有序词群',
      subRoots.indexOf('packages/pep-topic') >= 0, subRoots.join(','));
    check('每个分包都带页面（纯资源目录微信根本不打包，音频会凭空消失）',
      subs.every(s => (s.pages || []).length > 0 && s.pages[0] === 'play/play'));
    check('主包仍是检索页 + 玩法页（没有读音的册走主包这条）',
      APP.pages.length === 2 && APP.pages.indexOf('pages/play/play') >= 0, APP.pages.join(','));

    const missSub = [subPlay, `packages/${BOOK}/play/play.wxml`,
      `packages/${BOOK}/play/play.json`, `packages/${BOOK}/play/play.wxss`,
      `packages/${BOOK}/audio/index.js`, `packages/${BOOK}/audio/sprite.m4a`]
      .filter(f => !fs.existsSync(path.join(ROOT, f)));
    check('分包自包含：玩法页四件套 + 索引 + 音频', missSub.length === 0, missSub.join(','));

    const subJs = fs.readFileSync(path.join(ROOT, subPlay), 'utf8');
    check('分包页的 utils 路径已下移一层（否则真机点进那册就报模块找不到）',
      subJs.indexOf("require('../../../utils/bank.js')") >= 0
      && subJs.indexOf("require('../../../utils/speech.js')") >= 0
      && subJs.indexOf("require('../../utils/") < 0);

    /* speech.js 是模块级单例：每段测试前必须清掉 cache，
       否则上一段 configure 过的状态会漏到下一段，得到假绿。 */
    const resetSpeech = () => { delete require.cache[require.resolve(speechPath)]; };

    /* ---- E1 主包页：有读音的册 ⇒ 换成读音分包页 ---- */
    resetSpeech();
    currentRoute = 'pages/play/play';
    redirects.length = 0;
    storage[store.KEYS.book] = BOOK;
    let page = instantiate(loadPage('pages/play/play.js'));
    page.onLoad({});
    check('主包玩法页：有读音的册 redirect 到读音分包',
      redirects.length === 1 && redirects[0] === `/packages/${BOOK}/play/play?bookId=${BOOK}`,
      redirects.join(' | '));
    check('redirect 之后不做无用的棋盘初始化', !page._s);

    /* ---- E2 主包页：检索结果直达某一关，level 要原样带过去 ---- */
    resetSpeech();
    currentRoute = 'pages/play/play';
    redirects.length = 0;
    instantiate(loadPage('pages/play/play.js'))
      .onLoad({ bookId: BOOK, level: '7' });
    check('从检索结果进某关：redirect 时把 level 一起带过去（否则退回第一关）',
      redirects.length === 1 && redirects[0].indexOf('&level=7') > 0, redirects.join(' | '));

    /* ---- E3 主包页：没有读音的册 ⇒ 原地跑，行为一点不变 ---- */
    resetSpeech();
    currentRoute = 'pages/play/play';
    redirects.length = 0;
    audios.length = 0;
    page = instantiate(loadPage('pages/play/play.js'));
    page.onLoad({ bookId: PLAIN });
    check('没有读音的册：不 redirect，原地跑', redirects.length === 0, redirects.join(' | '));
    check('原地跑时棋盘照常铺开（配对逻辑未受影响）',
      !!(page._s && page._s.left.length >= 3), page._s && page._s.left.length);
    page.onTapLeft({ currentTarget: { dataset: { i: 0 } } });
    check('没有读音的册：点英文卡不建音频实例（静默，不报错）', audios.length === 0);

    /* ---- E4 分包页：不自我重定向，且装载时就把读音接上 ---- */
    resetSpeech();
    currentRoute = `packages/${BOOK}/play/play`;
    redirects.length = 0;
    audios.length = 0;
    page = instantiate(loadPage(subPlay));
    page.onLoad({ bookId: BOOK });
    check('分包页里不会再 redirect（防自我重定向）', redirects.length === 0, redirects.join(' | '));
    const speech = require(speechPath);
    check('分包页一加载就接上读音（导出时追加的 configure 生效）', speech.enabled() === true);
    check('分包页棋盘照常铺开', !!(page._s && page._s.left.length >= 3), page._s && page._s.left.length);

    /* ---- E5 点读：src 指向本册 sprite，起点取索引 ---- */
    const meta = require(subIndexPath);
    const firstWord = page._s.left[0].text;
    const entry = (meta.words || []).find(w => String(w[0]).toLowerCase() === String(firstWord).toLowerCase());
    check('能点到索引里有的词（否则下面的断言无意义）', !!entry, firstWord);
    page.onTapLeft({ currentTarget: { dataset: { i: 0 } } });
    const a = audios[0];
    check('点英文卡：建实例并把 src 指向本册 sprite',
      !!a && a.src === `/packages/${BOOK}/audio/sprite.m4a`, a && a.src);
    check('点英文卡：起点 = 索引里该词的起点（不会从整册开头念）',
      !!a && !!entry && Math.abs(a.startTime - entry[1]) < 1e-6,
      a && entry ? `${a.startTime} vs ${entry[1]}` : '找不到词');
    check('点一次英文卡只播一次', !!a && a.plays === 1, a && a.plays);

    /* ---- E6 连点第二个词：换全新实例、旧的当场销毁（体验版 bug：念一半掐断重念 /
           连点偶发无声 —— 复用同一实例时 stop/startTime/play 命令异步交错） ---- */
    const second = page._s.left[1];
    const entry2 = (meta.words || []).find(w => String(w[0]).toLowerCase() === String(second.text).toLowerCase());
    if (entry2) {
      page.onTapLeft({ currentTarget: { dataset: { i: 1 } } });
      const a2 = audios[audios.length - 1];
      check('连点第二个词：换一个全新实例（不给旧实例的交错命令留土壤）',
        a2 !== a && audios.length === 2, `${audios.length} 个实例`);
      check('……旧实例当场销毁（两个声音绝无可能重叠）', a.destroyed === true, `destroyed=${a.destroyed}`);
      check('……新实例起点 = 第二个词的起点',
        !!a2 && Math.abs(a2.startTime - entry2[1]) < 1e-6,
        a2 && `${a2.startTime} vs ${entry2[1]}`);
      check('……新实例一次 play（不重播、不补播）', !!a2 && a2.plays === 1, a2 && a2.plays);
    }

    /* ---- E7 点中文卡不读（读音库里只有英文） ---- */
    page._s.selUid = null;
    const before = a.plays;
    page.onTapRight({ currentTarget: { dataset: { i: 0 } } });
    check('点中文卡不发声（中文没有录音，硬读会变成念错）', a.plays === before, `${before} → ${a.plays}`);

    /* ---- E8 切后台停读、退出释放 ---- */
    page.onHide();
    check('切后台立刻停读（否则耳朵里还在念上一个单词）', a.stops >= 1, a.stops);
    page.onUnload();
    check('退出页面释放音频实例', a.destroyed === true);

    /* ---- E9 索引里没有的词：安静失败，不建实例 ---- */
    resetSpeech();
    const speech2 = require(speechPath);
    check('未 configure 时 play() 直接返回 false（主包页就是这样）',
      speech2.play('ruler') === false);
    currentRoute = `packages/${BOOK}/play/play`;
    require(speechPath).configure(`packages/${BOOK}`, require(subIndexPath));
    audios.length = 0;
    check('索引里没有的词：返回 false，不建实例、不抛异常',
      speech2.play('zzz-not-in-this-book') === false && audios.length === 0);
    check('索引里有的词：返回 true 并真的开播',
      speech2.play(firstWord) === true && audios.length === 1);

    /* ---- E12 播到词尾必须停（体验版真机 bug：读完第一个词一直往下念） ----
       根因：判停原先挂在 onTimeUpdate 上，而真机上这个回调在 seek 之后会停摆，
       条件永远不成立 ⇒ 音频顺着整册播完。这里把「何时该停」钉成确定性断言：
       两种 currentTime 口径都要停、seek 之后还要停、currentTime 卡死也要停。 */
    speech2.stop();
    intervals.clear();
    const longWord = (meta.words || []).filter(w => (w[2] - w[1]) > 1.2)[0];
    const lateWord = (meta.words || []).find(w => w[1] > 1 && (w[2] - w[1]) > 0.5);
    check('本册有可用于判停测试的词（否则下面几条无意义）', !!longWord && !!lateWord);

    /* ① 绝对口径：currentTime 报文件位置（startTime 生效时就是这种） */
    speech2.play(longWord[0]);
    let ax = audios[audios.length - 1];
    let a0 = ax.stops;
    advance(ax, longWord[1] + 0.08);                    // 首帧：定基准
    advance(ax, longWord[1] + (longWord[2] - longWord[1]) * 0.6);
    const aMid = ax.stops;
    advance(ax, longWord[1] + (longWord[2] - longWord[1]) + 0.2);
    check('绝对口径：读到一半不停、到词尾立刻停',
      aMid === a0 && ax.stops === a0 + 1, `mid=${aMid} end=${ax.stops} base=${a0}`);
    check('停的时候轮询一起清掉（不留空转计时器）', intervals.size === 0, intervals.size);

    /* ② 相对口径：currentTime 从本次播放起点算（部分基础库如此） */
    speech2.play(longWord[0]);
    let bx = audios[audios.length - 1];
    let b0 = bx.stops;
    advance(bx, 0.08);
    advance(bx, (longWord[2] - longWord[1]) * 0.6);
    const bMid = bx.stops;
    advance(bx, (longWord[2] - longWord[1]) + 0.2);
    check('相对口径（currentTime 从播放起点算）：同样到词尾停',
      bMid === b0 && bx.stops === b0 + 1, `mid=${bMid} end=${bx.stops}`);

    /* ③ startTime 没生效（从 0 开始播）⇒ 连续两拍确认后纠偏一次，且纠偏后仍能判停 */
    speech2.play(lateWord[0]);
    let cx = audios[audios.length - 1];
    cx.seeks.length = 0;
    let c0 = cx.stops;
    advance(cx, 0.05);
    advance(cx, 0.13);
    check('只涨了一拍还不纠偏（先排除「读数滞后」，见 ⑥b）',
      cx.seeks.length === 0, JSON.stringify(cx.seeks));
    advance(cx, 0.21);
    check('startTime 没生效（读数每拍稳定递增）时纠偏一次（否则「点第 40 个词先把前面念一遍」）',
      cx.seeks.length === 1 && Math.abs(cx.seeks[0] - lateWord[1]) < 1e-6, JSON.stringify(cx.seeks));
    advance(cx, lateWord[1] + (lateWord[2] - lateWord[1]) + 0.2);
    check('纠偏之后仍能正常判停（seek 不该把收尾逻辑带坏）',
      cx.stops === c0 + 1, `${c0} → ${cx.stops}`);

    /* ④ currentTime 卡死不动（解码异常）⇒ 硬超时兜底，绝不能永远读下去 */
    require(speechPath).configure('packages/fake', { words: [['fake', 0, 0.3]] });
    speech2.play('fake');
    let dx = audios[audios.length - 1];
    let d0 = dx.stops;
    const t0 = Date.now();
    advance(dx, 0.05);                                   // 起播了，但位置从此不动
    sleepSync(1900);                                     // 越过 dur(300ms) + 硬超时余量(1500ms)
    advance(dx, 0.05);
    check('currentTime 卡死时靠硬超时收尾（不会一直读下去）',
      dx.stops === d0 + 1, `${d0} → ${dx.stops} / 等了 ${Date.now() - t0}ms`);

    /* ⑤ 起播慢时不能把时间基准定成 0
       否则 startTime 一生效，词还没出声就被判成「已播完」。 */
    require(speechPath).configure('packages/fake', { words: [['slow', 5, 5.9]] });
    speech2.play('slow');
    let ex = audios[audios.length - 1];
    let e0 = ex.stops;
    ex.seeks.length = 0;                                 // ③ 已经 seek 过一次，这里只算本段
    for (let k = 0; k < 5; k++) advance(ex, 0);          // 五个格子都还没起播
    check('还没起播时不判停、不纠偏（否则词还没出声就被掐掉）',
      ex.stops === e0 && ex.seeks.length === 0, `stops=${ex.stops} seeks=${ex.seeks.length}`);
    check('还没起播时也不急着定基准', (speech2._state() || {}).base === null,
      JSON.stringify((speech2._state() || {}).base));
    advance(ex, 5.05);                                   // 起播：基准定在这里
    const eMid = ex.stops;
    advance(ex, 5.9 + 0.2);                              // 播完这个词
    check('起播后正常判停（慢启动不影响收尾）', eMid === e0 && ex.stops === e0 + 1,
      `mid=${eMid} end=${ex.stops}`);
    require(speechPath).configure(`packages/${BOOK}`, require(subIndexPath));

    /* ⑥ 首帧采样晚（切册后首屏 setData 卡顿）绝不能 seek ——
       体验版 bug：切册后点第一个词，先念半句、紧接着从头再念一遍。
       根因：纠偏看的是「首帧位置与起点之差的**绝对值**」，采样晚 0.6s 时把
       「其实已经从起点播了 0.6s」误判成「startTime 没生效」，seek 回起点重播。 */
    speech2.stop();
    const lateW = (meta.words || []).find(w => w[1] > 1 && (w[2] - w[1]) > 0.8);
    check('有可用于「采样晚」测试的词（否则下面几条无意义）', !!lateW);
    speech2.play(lateW[0]);
    let fx = audios[audios.length - 1];
    fx.seeks.length = 0;
    const f0 = fx.plays, fs0 = fx.stops;
    advance(fx, lateW[1] + 0.6);                       // 第一拍就已经晚到起点后 0.6s
    check('首帧比起点晚（首屏卡顿）时不 seek —— seek 回起点等于把这个词重念一遍',
      fx.seeks.length === 0, JSON.stringify(fx.seeks));
    check('……也不会因此多播一次', fx.plays === f0, `${f0} → ${fx.plays}`);
    advance(fx, lateW[2] + 0.05);
    check('采样晚的那一段仍按真实词尾收（基准取起点，不串到下一个词）',
      fx.stops === fs0 + 1, `${fs0} → ${fx.stops}`);

    /* ⑥b 解码期读数滞后（**从没下载过的册**首播）也绝不能 seek ——
       体验版 bug：新册第一次点词「第一遍念到一半、紧接着从头念一遍」。
       根因：首播要现解码，currentTime 先报一个很小的残留值（0.02~0.05），
       随后**跳变**到真实位置；纠偏若只看「ct 早于起点」就立刻 seek 回起点 ⇒ 重念。
       修法：读数是「低位原地踏步 + 跳变」还是「每拍稳定递增」，必须分开对待。 */
    speech2.stop();
    speech2.play(lateW[0]);
    let gx = audios[audios.length - 1];
    gx.seeks.length = 0;
    const g0 = gx.plays, gs0 = gx.stops;
    advance(gx, 0.02);                                  // 解码期残留读数
    advance(gx, 0.03);                                  // 原地踏步（涨幅 < 20ms）
    advance(gx, 0.04);
    check('解码期读数在低位原地踏步时不 seek（否则这个词会被拉回起点重念）',
      gx.seeks.length === 0, JSON.stringify(gx.seeks));
    advance(gx, lateW[1] + 0.6);                        // 解码完成：读数跳变到真实位置
    check('读数跳变到真实位置后也绝不 seek', gx.seeks.length === 0, JSON.stringify(gx.seeks));
    check('……整个过程只有一次 play、没有被掐断重播（一次点读只一遍声）',
      gx.plays === g0 && gx.stops === gs0, `plays+${gx.plays - g0} stops+${gx.stops - gs0}`);
    advance(gx, lateW[2] + 0.05);
    check('……仍按真实词尾收（基准取起点，不串到下一个词）',
      gx.stops === gs0 + 1, `${gs0} → ${gx.stops}`);

    /* ⑦ 播放模型：一次点读 = 一个全新实例（体验版两轮 bug 的根治点）
       复用同一实例时，「stop → 设 startTime → play」三条命令在真机上异步交错：
       上一次的 stop 排到本次 play 之后 ⇒ 点了没声；残留读数触发误纠偏 ⇒ 念一半重念。 */
    speech2.play(firstWord);
    let hx = audios[audios.length - 1];
    const hPrev = audios[audios.length - 2];
    check('每次点读都换新实例（从根上避开命令交错，不靠现场状态碰运气）',
      !!hx && hx !== hPrev, '实例没有换新');
    check('……上一次的实例已被销毁（不存在双声重叠的可能）',
      !!hPrev && hPrev.destroyed === true, `destroyed=${hPrev && hPrev.destroyed}`);
    check('……一次点读只有一次 play、零 seek（不重播、不补播、不纠偏）',
      hx.plays === 1 && hx.seeks.length === 0, `plays=${hx.plays} seeks=${JSON.stringify(hx.seeks)}`);
    speech2.stop();
    check('stop 之后实例是停止态（不销毁，留给下一次点读换新时销毁）',
      hx.stops >= 1 && hx.destroyed === false, `stops=${hx.stops} destroyed=${hx.destroyed}`);

    /* ⑤ 连点两个词只留一套轮询 */
    intervals.clear();
    speech2.play(firstWord);
    speech2.play(page._s.left[1].text);
    check('连点两个词只留一套轮询（两份轮询互相打架会让判停错乱）',
      intervals.size === 1, intervals.size);
    speech2.stop();
    check('stop() 之后不留任何轮询', intervals.size === 0, intervals.size);

    /* ⑥ 源码级：判停靠轮询，不能退回 onTimeUpdate */
    const speechSrc = fs.readFileSync(speechPath, 'utf8');
    check('判停走 setInterval 轮询（onTimeUpdate 在真机会停摆）',
      /setInterval\s*\(/.test(speechSrc) && /\btick\s*\(\s*mine\s*\)/.test(speechSrc),
      '未找到 setInterval(... tick(mine) ...)');

    /* ---- E13 换册必须换页（体验版 bug：只有三年级上册能读） ----
       根因：选册面板 / 下一册 / 错题本 / 自动练习跨册都只是「改册号」，
       而一个页面只能访问自己所在分包的资源 ⇒ 音频与索引还停在旧册，点上去没声音。
       修法：目标册有读音就 redirect 到它的分包页；configure 改到 onLoad 里执行
       （模块只执行一次，写在顶层的 configure 第二次进页就不跑了）。 */
    section('E13. 换册 —— 必须换到目标册的分包页，否则音频还是旧册的');
    const audioBookIds = bank.books().filter(b => b.hasAudio).map(b => b.id);
    check('本工程至少有 2 个有读音的册（否则「换册要换页」无从谈起）',
      audioBookIds.length >= 2, audioBookIds.join(','));

    /* ① 每一册都能从主包页换到自己的分包页 */
    const badRedirect = [];
    for (const id of audioBookIds) {
      resetSpeech();
      currentRoute = 'pages/play/play';
      redirects.length = 0;
      instantiate(loadPage('pages/play/play.js')).onLoad({ bookId: id });
      if (redirects[0] !== `/packages/${id}/play/play?bookId=${id}`) {
        badRedirect.push(`${id}→${redirects[0]}`);
      }
    }
    check('每个有读音的册都 redirect 到**自己**的分包页（不是都去 3 上）',
      badRedirect.length === 0, badRedirect.join(' , '));

    /* ② 每册分包页进来后，点读用的就是这一册的音频 */
    const badBook = [], badSrc = [];
    for (const id of audioBookIds) {
      resetSpeech();
      currentRoute = `packages/${id}/play/play`;
      audios.length = 0;
      const p = instantiate(loadPage(`packages/${id}/play/play.js`));
      p.onLoad({ bookId: id });
      const sp = require(speechPath);
      /* 老版本没有 bookId()：记成「无册号」，让断言红出来而不是把探针弄崩。 */
      const gotBook = typeof sp.bookId === 'function' ? sp.bookId() : '(无 bookId)';
      if (gotBook !== id) badBook.push(`${id}:${gotBook}`);
      const subIdx = require(path.join(ROOT, `packages/${id}/audio/index.js`));
      sp.play(subIdx.words[0][0], id);
      const aa = audios[audios.length - 1];
      if (!aa || aa.src !== `/packages/${id}/audio/sprite.m4a`) badSrc.push(`${id}:${aa && aa.src}`);
    }
    check('每册分包页 configure 到的就是本册', badBook.length === 0, badBook.join(' , '));
    check('每册点读的 src 都指向本册的 sprite（换册后不能还在播旧册）',
      badSrc.length === 0, badSrc.join(' , '));

    /* ③ 来回换册后回到某册：模块已在缓存里（顶层不再执行），仍必须切回该册配置 */
    resetSpeech();
    dropCache('packages/pep-3-1/play/play.js');
    dropCache('packages/pep-3-2/play/play.js');
    currentRoute = 'packages/pep-3-1/play/play';
    const d31 = loadPageKeepCache('packages/pep-3-1/play/play.js');
    const sp3 = require(speechPath);
    const readBook = () => (typeof sp3.bookId === 'function' ? sp3.bookId() : '(无 bookId)');
    check('分包页顶层**不**直接 configure（写在顶层的话第二次进页就不生效）',
      sp3.enabled() === false);
    instantiate(d31).onLoad({ bookId: 'pep-3-1' });
    const bk1 = readBook();
    currentRoute = 'packages/pep-3-2/play/play';
    const d32 = loadPageKeepCache('packages/pep-3-2/play/play.js');
    instantiate(d32).onLoad({ bookId: 'pep-3-2' });
    const bk2 = readBook();
    currentRoute = 'packages/pep-3-1/play/play';
    instantiate(d31).onLoad({ bookId: 'pep-3-1' });     // 第二次进 3 上：模块不重新执行
    check('3上 → 3下 → 回 3上：每次进页都重新 configure（模块缓存不执行顶层代码）',
      bk1 === 'pep-3-1' && bk2 === 'pep-3-2' && readBook() === 'pep-3-1',
      `${bk1} → ${bk2} → ${readBook()}`);

    /* ④ 换册的入口都要走「换页」 */
    resetSpeech();
    currentRoute = 'packages/pep-3-1/play/play';
    redirects.length = 0;
    const pSwitch = instantiate(loadPage(subPlay));
    pSwitch.onLoad({ bookId: 'pep-3-1' });
    pSwitch.pickBook({ currentTarget: { dataset: { id: 'pep-3-2' } } });
    check('选册面板换到有读音的册 ⇒ redirect 到该册分包页',
      redirects.length === 1 && redirects[0] === '/packages/pep-3-2/play/play?bookId=pep-3-2',
      redirects.join(' | '));
    check('换页后不再本地改册号（否则就变成「册变了、音频没变」）',
      pSwitch._s.bookId === 'pep-3-1', pSwitch._s.bookId);

    resetSpeech();
    currentRoute = 'packages/pep-3-1/play/play';
    redirects.length = 0;
    const pNext = instantiate(loadPage(subPlay));
    pNext.onLoad({ bookId: 'pep-3-1' });
    pNext.gotoNextBook();
    check('「下一册」按钮同样换页', redirects.length === 1, redirects.join(' | '));

    resetSpeech();
    currentRoute = 'packages/pep-3-1/play/play';
    redirects.length = 0;
    const pAuto = instantiate(loadPage(subPlay));
    pAuto.onLoad({ bookId: 'pep-3-1' });
    pAuto._enterBookInAuto('pep-4-1');
    check('自动练习跨册：换到下一册分包页，并把 auto=1 带过去（否则自动练习断掉）',
      redirects.length === 1 && redirects[0].indexOf('/packages/pep-4-1/play/play') === 0
      && redirects[0].indexOf('auto=1') > 0, redirects.join(' | '));

    /* ⑤ 换到没有读音的册：本地换册，绝不跳页 */
    resetSpeech();
    currentRoute = 'packages/pep-3-1/play/play';
    redirects.length = 0;
    const pPlain = instantiate(loadPage(subPlay));
    pPlain.onLoad({ bookId: 'pep-3-1' });
    pPlain.pickBook({ currentTarget: { dataset: { id: PLAIN } } });
    check('换到没有读音的册：本地换册、不跳页（跳了反而多一次白屏）',
      redirects.length === 0 && pPlain._s.bookId === PLAIN,
      `${redirects.length} / ${pPlain._s.bookId}`);

    /* ⑥ 兜底：册号对不上就不出声，宁可不读也不能念错册 */
    resetSpeech();
    currentRoute = 'packages/pep-3-1/play/play';
    const spGuard = require(speechPath);
    instantiate(loadPage(subPlay)).onLoad({ bookId: 'pep-3-1' });
    const guardOk = typeof spGuard.play === 'function'
      && spGuard.play('ruler', 'pep-3-2') === false && spGuard.play('ruler', 'pep-3-1') === true;
    check('speech.play 带错册号 ⇒ 返回 false（不拿 3 上的录音去念 3 下的词）', guardOk);

    /* ---- E14 换册后点读不能带残音（体验版 bug：先念半句上一个词，再从头念一遍） ----
       根因两条，叠在一起：
         ① 换册是 wx.redirectTo，旧页的 onUnload 要等新页渲染完才执行 ⇒ 中间那几百毫秒里
            正在念的词一直响到新页上；
         ② speech 里的 InnerAudioContext 是**模块级单例**（小程序模块只执行一次、跨页面共享），
            换册后只在旧实例上改 src + startTime。真机上这一串异步命令会让起播位置停在
            上一次的播放点上，先漏出半句旧词，随后轮询纠偏再 seek 回词起点 —— 听感正是
            「读了后半段，然后又读一遍」。
       修法：换页/换册前立刻 stop；实例按「文件」归属，换文件就重建。 */
    section('E14. 换册后点读不能带残音：先断声 + 换文件重建实例');

    /* ① 换册跳页前必须立刻断声，不能等 onUnload */
    resetSpeech();
    currentRoute = `packages/${BOOK}/play/play`;
    audios.length = 0;
    redirects.length = 0;
    const pS = instantiate(loadPage(subPlay));
    pS.onLoad({ bookId: BOOK });
    const spS = require(speechPath);
    spS.play(pS._s.left[0].text, BOOK);
    const aS = audios[audios.length - 1];
    const stopsBefore = aS ? aS.stops : -1;
    pS.pickBook({ currentTarget: { dataset: { id: 'pep-3-2' } } });
    check('换册跳页前立刻断声（不等旧页 onUnload，否则旧词会一直响到新页上）',
      !!aS && aS.stops > stopsBefore, `${stopsBefore} → ${aS && aS.stops}`);
    check('断声之后仍然换页（别把换册本身弄丢）',
      redirects.length === 1 && redirects[0].indexOf('/packages/pep-3-2/play/play') === 0,
      redirects.join(' | '));

    /* ② 换到另一册后点读：必须换成新实例 —— 旧实例还带着旧册的播放位置 */
    resetSpeech();
    currentRoute = `packages/${BOOK}/play/play`;
    audios.length = 0;
    const pOld = instantiate(loadPage(subPlay));
    pOld.onLoad({ bookId: BOOK });
    const spX = require(speechPath);
    spX.play(pOld._s.left[0].text, BOOK);
    const oldA = audios[audios.length - 1];
    check('同册第一次点读：建 1 个实例', audios.length === 1 && !!oldA, audios.length);

    /* 新页（3 下）进来：configure 换册，再点本册第一个词 —— 等价于 redirect 之后那一下 */
    currentRoute = 'packages/pep-3-2/play/play';
    dropCache('packages/pep-3-2/play/play.js');
    const pNew = instantiate(loadPage('packages/pep-3-2/play/play.js'));
    pNew.onLoad({ bookId: 'pep-3-2' });
    const idx32 = require(path.join(ROOT, 'packages/pep-3-2/audio/index.js'));
    const w32 = idx32.words[0];
    spX.play(w32[0], 'pep-3-2');
    const newA = audios[audios.length - 1];
    check('换册后点读换新实例（复用旧实例 ⇒ 起播停在上一次的播放点，就是那半句残音）',
      audios.length === 2 && newA !== oldA, audios.length);
    check('旧册实例已销毁（播放位置跟着一起丢掉）', !!oldA && oldA.destroyed === true);
    check('新实例的 src 指向新册的 sprite',
      !!newA && newA.src === '/packages/pep-3-2/audio/sprite.m4a', newA && newA.src);
    check('新实例的起点 = 新册索引里该词的起点（从头念，不是接着上一次的位置）',
      !!newA && Math.abs(newA.startTime - w32[1]) < 1e-6,
      newA && `${newA.startTime} vs ${w32[1]}`);

    /* ③ 同一册内连点：也换新实例。旧模型「复用同一实例不重复解码」在真机上换来的是
       stop / startTime / play 三条命令异步交错 —— 上一次的 stop 排到本次 play 之后
       ⇒ 点了没声；残留读数触发误纠偏 ⇒ 念一半掐断重念。重建的开销只是解码头。 */
    const w32b = idx32.words[1] || w32;
    const cntBefore = audios.length;
    spX.play(w32b[0], 'pep-3-2');
    const w32bA = audios[audios.length - 1];
    check('同一册内连点第二个词：换新实例（不给交错命令留土壤）',
      audios.length === cntBefore + 1 && w32bA !== newA, `${cntBefore} → ${audios.length}`);
    check('……上一个实例当场销毁（两个声音绝无可能重叠）',
      newA.destroyed === true, `destroyed=${newA.destroyed}`);
    check('……新实例的起点 = 第二个词的起点',
      !!w32bA && Math.abs(w32bA.startTime - w32b[1]) < 1e-6,
      w32bA && `${w32bA.startTime} vs ${w32b[1]}`);

    /* ④ 旧页卸载不能碰新页正在用的实例：redirect 时新旧页先后并无保证 */
    pOld.onUnload();                       // 3 上 的老页面此刻才卸载，配置已经是 3 下
    check('旧页卸载不销毁新页的实例（否则新页刚点下去念半句就没声了）',
      w32bA.destroyed === false, `destroyed=${w32bA.destroyed}`);
    spX.play(w32[0], 'pep-3-2');
    const afterA = audios[audios.length - 1];
    check('旧页卸载后新页仍能正常点读（新实例 play 没落空、旧实例没在销毁后 play）',
      afterA !== w32bA && afterA.plays === 1 && !w32bA.deadPlays,
      `plays=${afterA.plays} dead=${w32bA.deadPlays || 0}`);

    /* ⑤ 换回先前那册：重建 3 上的实例（别命中 3 下那个），src 跟着回去 */
    currentRoute = `packages/${BOOK}/play/play`;
    const pBack = instantiate(loadPage(subPlay));
    pBack.onLoad({ bookId: BOOK });
    spX.play(pBack._s.left[0].text, BOOK);
    const backA = audios[audios.length - 1];
    check('换回 3 上：src 跟着回到 3 上（不会还在播 3 下）',
      !!backA && backA !== afterA && backA.src === `/packages/${BOOK}/audio/sprite.m4a`,
      backA && backA.src);
    check('3 下 的实例在这次切换时被销毁', afterA.destroyed === true);

    /* ⑥ 源码级：每次点读都新建实例（防止有人改回「复用同一实例」） */
    const spSrc = fs.readFileSync(speechPath, 'utf8');
    check('freshAudio：每次建实例前先销毁旧的（play 里也必须走它）',
      /function freshAudio\(url\)/.test(spSrc) && /if \(audio\) dropAudio\(\)/.test(spSrc),
      '未找到 freshAudio / dropAudio 组合');
    check('播放入口直接调 freshAudio（不允许按 url 复用旧实例）',
      /var a = freshAudio\(url\)/.test(spSrc), '未找到 freshAudio(url) 调用');
    check('换文件时销毁旧实例（dropAudio）',
      /function dropAudio\(\)/.test(spSrc));
    const playSrc = fs.readFileSync(path.join(ROOT, 'pages/play/play.js'), 'utf8');
    check('换页前断声写在 _goAudioPack 里（所有换册入口都经过它）',
      /speech\.stop\(\);\s*\n\s*wx\.redirectTo/.test(playSrc), '未找到 stop 后紧跟 redirectTo');

    /* ---- E15 进页面先看到「正在准备词库」，而不是「加载失败」 ----
       初始 data 里 levelCount 是 0：改成三态之前，页面第一帧就把
       「单词库加载失败」的兜底当默认背景亮了出来（redirect 等分包下载的
       等待期里一直停在这帧）。改成 加载中 / 正常 / 真失败 三态互斥。 */
    section('E15. 进页面先显示「正在准备词库」，失败兜底只在真失败时出现');
    const wxmlSrc = fs.readFileSync(path.join(ROOT, 'pages/play/play.wxml'), 'utf8');
    check('加载态分支在最前（wx:if={{!loaded}}，带转圈）',
      /wx:if="\{\{!loaded\}\}"/.test(wxmlSrc) && /wm-fallback-spinner/.test(wxmlSrc));
    check('主舞台改用 wx:elif（三态互斥，levelCount=0 不会再落到兜底）',
      /wx:elif="\{\{levelCount > 0\}\}"/.test(wxmlSrc));
    check('失败兜底文案友好并带「重试」出口',
      /词库暂时没有加载出来/.test(wxmlSrc) && /bindtap="retryBoot"/.test(wxmlSrc)
      && !/单词库加载失败/.test(wxmlSrc));

    resetSpeech();
    currentRoute = `packages/${BOOK}/play/play`;
    const pL = instantiate(loadPage(subPlay));
    check('初始 data.loaded 是 false（第一帧显示加载态）', pL.data.loaded === false);
    pL.onLoad({ bookId: BOOK });
    check('初始化完成后 loaded 变 true（加载态撤掉、棋盘铺开）',
      pL.data.loaded === true && !!(pL._s && pL._s.left.length >= 3));

    /* 兜底上的「重试」：按原入口参数重跑 _boot。
       旧版没有 retryBoot —— 用防御式判断，让断言红出来而不是把探针弄崩。 */
    const canRetry = typeof pL.retryBoot === 'function';
    if (canRetry) {
      pL.data.loaded = false;                      // 模拟兜底可见时按下重试
      pL.retryBoot();
    }
    check('重试后 loaded 恢复 true、棋盘重新铺开',
      canRetry && pL.data.loaded === true && !!(pL._s && pL._s.left.length >= 3),
      canRetry ? '见上' : '页面没有 retryBoot 方法');

    /* ---- E16 锁屏回来「点哪个词都没声、换册才恢复」----
       体验版实测：锁屏几分钟解锁后，本页点读集体静音，只有换册（重新 configure）
       或重开小程序才恢复。两个来源都已定位：
       ① onError 里把 conf 永久置空 —— 中断期间底层报一次系统错误，本页点读就地全废；
       ② 音频会话被打断后没恢复，新实例 play 也静默失败 ⇒ 需要销毁实例 + 探针戳醒，
          并接官方 wx.onAudioInterruptionBegin/End。 */
    section('E16. 锁屏 / 系统中断后必须能自愈（不能靠换册救）');
    resetSpeech();
    currentRoute = `packages/${BOOK}/play/play`;
    audios.length = 0;
    interrupt.begin.length = 0; interrupt.end.length = 0;
    interrupt.show.length = 0; interrupt.hide.length = 0;
    require(speechPath).configure(`packages/${BOOK}`, require(subIndexPath));
    const spL = require(speechPath);
    /* 防御式触发：旧版没有这些钩子时不许把探针打断（要「红完跑到底」）。 */
    const fire = (arr) => { if (typeof arr[0] === 'function') arr[0](); };

    /* ① 钩子必须在 configure 时挂上 */
    check('configure 时挂了音频中断开始/结束监听',
      interrupt.begin.length === 1 && interrupt.end.length === 1,
      `begin=${interrupt.begin.length} end=${interrupt.end.length}`);
    check('configure 时挂了前后台切换监听（锁屏时小程序会切后台）',
      interrupt.hide.length === 1 && interrupt.show.length === 1,
      `hide=${interrupt.hide.length} show=${interrupt.show.length}`);

    /* ② onError 不能把 conf 封死：中断期间偶发一次系统错误很正常 */
    spL.play(firstWord);
    let ex2 = audios[audios.length - 1];
    check('实例上注册了 onError', !!ex2 && ex2._err.length > 0, ex2 && ex2._err.length);
    if (ex2 && ex2._err.length) ex2._err[0]({ errMsg: 'errCode:10002 system error', errCode: 10002 });
    check('onError 只停当前段，不把 conf 封死（否则本页读音全废、只能换册）',
      spL.enabled() === true && spL._state() === null, `enabled=${spL.enabled()}`);
    const nErr = audios.length;
    spL.play(firstWord);
    check('报过错之后下一次点读照常出声（换新实例重试，不需要换册）',
      audios.length === nErr + 1 && audios[audios.length - 1].plays === 1,
      `新实例 ${audios.length - nErr} 个`);

    /* ③ 中断开始：立即停声（锁屏/来电不该继续念） */
    spL.play(firstWord);
    const curAudio = audios[audios.length - 1];
    const stopsB = curAudio.stops;
    check('中断开始立即停声（锁屏 / 来电时不该继续念）',
      typeof interrupt.begin[0] === 'function'
        && (fire(interrupt.begin), curAudio.stops > stopsB && spL._state() === null),
      typeof interrupt.begin[0] === 'function' ? `stops+${curAudio.stops - stopsB}` : '没有挂 onAudioInterruptionBegin');

    /* ④ 中断结束：销毁可能已被打坏的实例（留着只会静默无声） */
    spL.play(firstWord);
    const zombie = audios[audios.length - 1];
    fire(interrupt.end);
    check('中断结束时销毁实例（被打坏的实例留着 = 后续永远没声）', zombie.destroyed === true);
    const nEnd = audios.length;
    spL.play(firstWord);
    const afterEnd = audios[audios.length - 1];
    check('中断结束后点读用全新实例、能正常 play',
      audios.length === nEnd + 1 && afterEnd.plays === 1 && !afterEnd.deadPlays,
      `plays=${afterEnd && afterEnd.plays} dead=${afterEnd && afterEnd.deadPlays}`);

    /* ⑤ 后台超过 3 秒回来：revive 发一个 0 音量探针戳醒音频会话 */
    fire(interrupt.hide);
    sleepSync(3100);
    const nProbe = audios.length;
    fire(interrupt.show);
    const probe = audios[audios.length - 1];
    check('后台超过 3 秒回来会发一个探针（戳醒中断后哑掉的音频会话）',
      audios.length === nProbe + 1, `${nProbe} → ${audios.length}`);
    check('探针是 0 音量、播本册 sprite（只戳会话：不出声、不串册）',
      !!probe && probe.volume === 0 && probe.src === `/packages/${BOOK}/audio/sprite.m4a`,
      probe && `${probe.volume} / ${probe.src}`);
    check('探针真的发起了播放', !!probe && probe.plays === 1, probe && probe.plays);
    sleepSync(700);                       // 探针 600ms 后自毁（等真定时器）
    await sleep(20);                      // 让出事件循环，定时器才会真的跑
    check('探针几百毫秒后自己销毁（不留常驻实例、不会偷偷念完整册）',
      !!probe && probe.destroyed === true);

    /* ⑥ 刚切走就回来（< 3 秒）不该打扰用户：不发探针 */
    const nShort = audios.length;
    fire(interrupt.hide);
    fire(interrupt.show);
    check('刚切走就回来（< 3 秒）不发探针', audios.length === nShort, `${nShort} → ${audios.length}`);

    /* ⑦ 自愈之后点读照常（探针不能把正常路径带坏） */
    const nAfter = audios.length;
    spL.play(firstWord);
    const afterProbe = audios[audios.length - 1];
    check('自愈之后点读照常：新实例、音量正常、一次 play',
      audios.length === nAfter + 1 && afterProbe.plays === 1 && afterProbe.volume === 1,
      `plays=${afterProbe && afterProbe.plays} vol=${afterProbe && afterProbe.volume}`);
    check('……此时 conf 仍在（不需要换册重新 configure）', spL.enabled() === true);

    /* ⑧ 源码级：防止有人把这套改回去 */
    const spSrc2 = fs.readFileSync(speechPath, 'utf8');
    check('全文件只在 reset() 里清 conf（onError 不再封印读音）',
      (spSrc2.match(/^\s*conf = null;/gm) || []).length === 1,
      (spSrc2.match(/^\s*conf = null;/gm) || []).length + ' 处');
    check('接了官方 onAudioInterruptionBegin / End',
      /onAudioInterruptionBegin/.test(spSrc2) && /onAudioInterruptionEnd/.test(spSrc2));
    check('前后台切换用 onAppHide / onAppShow 追踪，并在后台超时后 revive',
      /onAppHide/.test(spSrc2) && /onAppShow/.test(spSrc2) && /function revive\(/.test(spSrc2));

    /* ---- E10 体积闸门：单分包 ≤ 2048 KB（超了上传直接被拒） ---- */
    const sizeKb = (dir) => {
      let sum = 0;
      for (const e of fs.readdirSync(dir, { withFileTypes: true, recursive: true })) {
        if (e.isFile()) sum += fs.statSync(path.join(e.parentPath || dir, e.name)).size;
      }
      return Math.round(sum / 1024);
    };
    const overs = subRoots
      .map(r => [r, sizeKb(path.join(ROOT, r))])
      .filter(([, kb]) => kb > 2048);
    const biggest = Math.max(...subRoots.map(r => sizeKb(path.join(ROOT, r))));
    check('每个分包都 ≤ 2048 KB（最大的 ' + biggest + ' KB）', overs.length === 0,
      overs.map(([r, kb]) => `${r}=${kb}KB`).join(','));

    /* ---- E11 books.js 的 hasAudio 与分包一一对应 ---- */
    const marked = bank.books().filter(b => b.hasAudio).map(b => b.id).sort();
    const packIds = subRoots.map(r => r.split('/')[1]).sort();
    check('books.js 标记了读音的册 = 有分包的册（缺一个就会「有音频却永远进不去」）',
      JSON.stringify(marked) === JSON.stringify(packIds), `marked=${marked.join(',')} packs=${packIds.join(',')}`);
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

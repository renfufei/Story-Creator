#!/usr/bin/env node
'use strict';
/* ===========================================================================
 * 序列配对（微信小程序端）· 模板级 Node 探针
 * ---------------------------------------------------------------------------
 * 小程序产物目录 word-match-miniprogram/ 由 build_miniprogram_data.py 从**线上服务**
 * 导出（要重启 1888 才能带上专题册），所以直接跑产物目录验证不了这一轮改动。
 * 这里换个思路：**数据现场造、页面用模板里的真文件**——
 *   1. 读 src/main/resources/learn/topic-words.json，按 WordMatchBank.splitBalanced
 *      同一套规则切关（MIN 3 / MAX 7 / TARGET 6），生成 data/books.js + data/levels-topic.js；
 *   2. 再造一册「假教材册」放在 primary 学段，用来验证「锁左列没有误伤普通册」；
 *   3. 把模板里的 utils/*.js、pages/index/index.js、pages/play/play.js 拷进临时目录；
 *   4. 桩掉 wx / Page，按真实调用序列驱动，断言 setData 出去的东西。
 *
 * 覆盖：
 *   A. 首页入口：hasTopic / topicDesc（6 个专题 · 86 词）、选主题弹层、URL 与起点关号；
 *   B. 起点口径：该主题第一个未完成关；全部完成则从头再练；
 *   C. 发牌：专题册左列英文保持原顺序、右列打乱；普通册左列仍被打乱；
 *   D. 源码闸门：入口在【趣味配对】之后、锁左列用的是 stageOf(bookId) === '专题'。
 *
 * 用法： node scripts/learn/probe_miniprogram_topic.js
 * 退出码 0 = 全绿；1 = 有 FAIL。
 * =========================================================================== */

const path = require('path');
const fs = require('fs');
const os = require('os');

const ROOT = path.resolve(__dirname, '../..');
const TPL = path.join(ROOT, 'src/main/resources/miniprogram-template');
const TOPIC_JSON = path.join(ROOT, 'src/main/resources/learn/topic-words.json');

/* ---------------------------------------------------------------- 断言 */
let pass = 0;
const fails = [];
function check(name, cond, detail) {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fails.push(name); console.log('  ✗ ' + name + (detail ? '   ← ' + detail : '')); }
}
const section = t => console.log('\n' + t);

/* ---------------------------------------------------------------- 切关（复刻 WordMatchBank.splitBalanced） */
const MIN_PAIRS = 3, MAX_PAIRS = 7, TARGET_PAIRS = 6;

function splitBalanced(n) {
  const out = [];
  if (n <= 0) return out;
  if (n <= MAX_PAIRS) { out.push(n); return out; }
  const byMax = Math.ceil(n / MAX_PAIRS);
  const byTarget = Math.round(n / TARGET_PAIRS);
  const groups = Math.min(Math.max(byMax, byTarget), n);
  const base = Math.floor(n / groups);
  const rem = n % groups;
  for (let i = 0; i < groups; i++) out.push(base + (i < rem ? 1 : 0));
  return out;
}

/** 把 topic-words.json 的一册切成 [[{t, p}]]，与 Java 侧 loadBooks 一致 */
function buildLevels(book) {
  const levels = [];
  for (const theme of book.themes) {
    const words = theme.words.filter(w => Array.isArray(w) && w.length >= 2
      && String(w[0]).trim() && String(w[1]).trim());
    if (words.length < MIN_PAIRS) continue;
    let cursor = 0;
    for (const size of splitBalanced(words.length)) {
      const pairs = [];
      for (let i = 0; i < size; i++) pairs.push(words[cursor + i]);
      cursor += size;
      levels.push({ t: theme.name, p: pairs });
    }
  }
  return levels;
}

/* ---------------------------------------------------------------- 造临时工程 */
const topicDoc = JSON.parse(fs.readFileSync(TOPIC_JSON, 'utf8'));
const topicBook = topicDoc.books[0];
const topicLevels = buildLevels(topicBook);
const topicWordCount = topicLevels.reduce((a, lv) => a + lv.p.length, 0);

/* 假教材册：12 对 / 2 关（6+6），左列被打乱才说明「锁序」只作用于专题册 */
const FAKE_WORDS = [['apple', '苹果'], ['banana', '香蕉'], ['cherry', '樱桃'], ['durian', '榴莲'],
  ['elder', '接骨木'], ['fig', '无花果'], ['grape', '葡萄'], ['honey', '蜂蜜'],
  ['iris', '鸢尾'], ['jujube', '枣'], ['kiwi', '猕猴桃'], ['lemon', '柠檬']];
const fakeLevels = [];
for (let i = 0; i < 12; i += 6) fakeLevels.push({ t: '假教材主题', p: FAKE_WORDS.slice(i, i + 6) });

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mp-topic-'));
for (const d of ['data', 'utils', 'pages/index', 'pages/play']) {
  fs.mkdirSync(path.join(tmp, d), { recursive: true });
}

const books = [
  { id: 'pep-topic', label: '序列配对', stage: '专题', grade: 0, semester: '', levelCount: topicLevels.length, wordCount: topicWordCount },
  { id: 'fake-primary', label: '假教材册', stage: '小学', grade: 3, semester: '上册', levelCount: fakeLevels.length, wordCount: 12 }
];
const stages = [{ name: '专题', slug: 'topic', count: 1 }, { name: '小学', slug: 'primary', count: 1 }];

fs.writeFileSync(path.join(tmp, 'data/books.js'),
  'module.exports = ' + JSON.stringify({ books: books, stages: stages }) + ';\n');
fs.writeFileSync(path.join(tmp, 'data/levels-topic.js'),
  'module.exports = ' + JSON.stringify({ 'pep-topic': topicLevels }) + ';\n');
fs.writeFileSync(path.join(tmp, 'data/levels-primary.js'),
  'module.exports = ' + JSON.stringify({ 'fake-primary': fakeLevels }) + ';\n');
/* 其余学段给空模块：首页 setData 只读元信息，但 search.warm() 会 forEachPair 走全部册 */
for (const slug of ['junior', 'senior', 'college']) {
  fs.writeFileSync(path.join(tmp, 'data/levels-' + slug + '.js'), 'module.exports = {};\n');
}

for (const f of ['bank.js', 'search.js', 'store.js', 'ui.js', 'sound.js']) {
  fs.copyFileSync(path.join(TPL, 'utils', f), path.join(tmp, 'utils', f));
}
fs.copyFileSync(path.join(TPL, 'pages/index/index.js'), path.join(tmp, 'pages/index/index.js'));
fs.copyFileSync(path.join(TPL, 'pages/play/play.js'), path.join(tmp, 'pages/play/play.js'));

/* ---------------------------------------------------------------- 桩 */
const storage = {};
const actionSheets = [];
const navs = [];
const toasts = [];

global.wx = {
  getStorageSync: k => (k in storage ? storage[k] : ''),
  setStorageSync: (k, v) => { storage[k] = v; },
  removeStorageSync: k => { delete storage[k]; },
  showToast: o => { toasts.push((o && o.title) || ''); },
  showModal: o => { if (o && o.success) o.success({ confirm: false }); },
  showActionSheet: o => { actionSheets.push(o); },
  navigateTo: o => { navs.push(o && o.url); },
  setInnerAudioOption: () => { }
  /* 不提供 createInnerAudioContext：让 sound.js 走「基础库不支持」的降级分支 */
};

let registered = null;
global.Page = function (opts) { registered = opts; };
global.App = function () { };

function loadPage(rel) {
  const file = path.join(tmp, rel);
  delete require.cache[require.resolve(file)];
  registered = null;
  require(file);
  if (!registered) throw new Error(rel + ' 没有调用 Page()');
  return registered;
}

function instantiate(def) {
  const inst = {};
  for (const k of Object.keys(def)) inst[k] = def[k];
  inst.data = JSON.parse(JSON.stringify(def.data || {}));
  inst.setData = function (patch) { Object.assign(this.data, patch); };
  return inst;
}

const bank = require(path.join(tmp, 'utils/bank.js'));
const store = require(path.join(tmp, 'utils/store.js'));

/* ---------------------------------------------------------------- A. 首页入口 */
section('A. 首页【序列配对】入口');

const indexDef = loadPage('pages/index/index.js');
const index = instantiate(indexDef);
index.onLoad({});

const themes = [];
{
  /* 期望值独立算：按关卡顺序，主题名首次出现即一个专题 */
  const seen = new Set();
  for (let i = 0; i < topicLevels.length; i++) {
    const t = topicLevels[i].t;
    if (!seen.has(t)) { seen.add(t); themes.push(t); }
  }
}

check('词库里有专题册（stage=专题）', index.data.hasTopic === true, 'hasTopic=' + index.data.hasTopic);
check('入口描述报出体量与口径',
  index.data.topicDesc === '英文按顺序 · 中文打乱 · ' + themes.length + ' 个专题 · ' + topicWordCount + ' 词',
  'desc=' + index.data.topicDesc);
check('专题数 = ' + themes.length + '（数字/序数/星期/月份/季节/时间）',
  themes.length === 6 && themes[0] === '数字', themes.join('/'));
check('册元信息对齐切关结果（' + topicLevels.length + ' 关 / ' + topicWordCount + ' 词）',
  bank.book('pep-topic').levelCount === topicLevels.length
  && bank.book('pep-topic').wordCount === topicWordCount,
  'meta=' + JSON.stringify(bank.book('pep-topic')));

index.gotoTopic();
check('点入口弹出主题选择（原生 actionSheet）', actionSheets.length === 1, 'n=' + actionSheets.length);
check('弹层里是 ' + themes.length + ' 个专题、顺序与关卡一致',
  actionSheets[0] && JSON.stringify(actionSheets[0].itemList) === JSON.stringify(themes),
  actionSheets[0] ? actionSheets[0].itemList.join('/') : '');

/* 选第 1 个专题（数字）→ 第 1 关 */
actionSheets[0].success({ tapIndex: 0 });
check('选「数字」→ 进专题册第 1 关',
  navs[0] === '/pages/play/play?bookId=pep-topic&level=0', 'url=' + navs[0]);

/* 选第 2 个专题（序数词）→ 起点应是它在整册里的第一个关卡下标 */
const ordinalFirst = topicLevels.findIndex(lv => lv.t === themes[1]);
index.gotoTopic();
actionSheets[1].success({ tapIndex: 1 });
check('选「' + themes[1] + '」→ 起点是它在册内的第一关（level=' + ordinalFirst + '）',
  navs[1] === '/pages/play/play?bookId=pep-topic&level=' + ordinalFirst, 'url=' + navs[1]);

/* ---------------------------------------------------------------- B. 起点口径（未完成优先） */
section('B. 起点口径：该主题第一个未完成的关');

const numberLast = topicLevels.findIndex(lv => lv.t !== themes[0]) - 1;
storage[store.KEYS.progress] = { 'pep-topic': [0, 1, 2] };
storage[store.KEYS.dataSig] = bank.signature();

navs.length = 0;
index.gotoTopic();
actionSheets[actionSheets.length - 1].success({ tapIndex: 0 });
check('数字前 3 关已完成 → 从第 4 关（level=3）接着练',
  navs[0] === '/pages/play/play?bookId=pep-topic&level=3', 'url=' + navs[0]);

const numberDone = [];
for (let i = 0; i <= numberLast; i++) numberDone.push(i);
storage[store.KEYS.progress] = { 'pep-topic': numberDone };
navs.length = 0;
index.gotoTopic();
actionSheets[actionSheets.length - 1].success({ tapIndex: 0 });
check('数字全部练完 → 从头再练（level=0）', navs[0] === '/pages/play/play?bookId=pep-topic&level=0', 'url=' + navs[0]);

/* ---------------------------------------------------------------- C. 发牌：锁左列 */
section('C. 发牌：专题册锁左列 / 普通册照旧乱序');

const playDef = loadPage('pages/play/play.js');

function openPlay(bookId, level) {
  const inst = instantiate(playDef);
  inst.onLoad({ bookId: bookId, level: String(level) });
  return inst;
}

const p1 = openPlay('pep-topic', 0);
const left1 = p1.data.leftCards.map(c => c.text);
const right1 = p1.data.rightCards.map(c => c.text);
const expectLeft = topicLevels[0].p.map(w => w[0]);

check('专题册第 1 关左列 = 词库原顺序（' + expectLeft.join(' ') + '）',
  JSON.stringify(left1) === JSON.stringify(expectLeft), 'left=' + left1.join(' '));
check('右列被洗过（与原顺序不同位）',
  JSON.stringify(right1) !== JSON.stringify(expectLeft)
  || JSON.stringify(right1) !== JSON.stringify(topicLevels[0].p.map(w => w[1])),
  'right=' + right1.join(' '));

/* 再进同一关：左列必须还是原顺序（页面里 _lastLayout 的「换一批」逻辑不能把它洗掉） */
const p1b = openPlay('pep-topic', 0);
check('同一关重复进入，左列仍保持原顺序',
  JSON.stringify(p1b.data.leftCards.map(c => c.text)) === JSON.stringify(expectLeft),
  p1b.data.leftCards.map(c => c.text).join(' '));

/* 第 2 关：继续按词库顺序往前推 */
const p2 = openPlay('pep-topic', 1);
check('第 2 关左列接续词库顺序（' + topicLevels[1].p.map(w => w[0]).join(' ') + '）',
  JSON.stringify(p2.data.leftCards.map(c => c.text)) === JSON.stringify(topicLevels[1].p.map(w => w[0])),
  p2.data.leftCards.map(c => c.text).join(' '));

/* 普通册：左列仍要被打乱 —— 连开 5 次，至少有一次顺序与词库不同 */
let shuffledSome = false;
let sameTimes = 0;
for (let k = 0; k < 5; k++) {
  const fp = openPlay('fake-primary', 0);
  const got = fp.data.leftCards.map(c => c.text);
  const want = FAKE_WORDS.slice(0, 6).map(w => w[0]);
  if (JSON.stringify(got) !== JSON.stringify(want)) shuffledSome = true; else sameTimes++;
}
check('普通册左列仍被打乱（锁序没有误伤全站）', shuffledSome, 'same=' + sameTimes + '/5');

/* 防对齐：专题册右列不能恰好和左列一一对应（否则一进关就全对上了）。
   ⚠️ 断言必须读 this._s.left/_s.right —— data.leftCards 是「视图投影」，
   _cardsView 只给 uid/text/cls，**不含 key**，拿 data 比 key 会得到 undefined===undefined
   恒真（网页版探针踩过一模一样的坑）。 */
const sL = p1._s.left, sR = p1._s.right;
let alignedCount = 0;
for (let i = 0; i < sL.length; i++) {
  if (sL[i].key === sR[i].key) alignedCount++;
}
check('专题册右列打乱后同位对齐 ≤ 1（防「一进来就全对上」）', alignedCount <= 1, 'aligned=' + alignedCount);

/* ---------------------------------------------------------------- D. 源码闸门 */
section('D. 源码闸门');

const wxml = fs.readFileSync(path.join(TPL, 'pages/index/index.wxml'), 'utf8');
const iPlay = wxml.indexOf('趣味配对');
const iSeq = wxml.indexOf('序列配对');
check('首页入口顺序：【趣味配对】在上、【序列配对】在下', iPlay >= 0 && iSeq > iPlay,
  'play=' + iPlay + ' seq=' + iSeq);
check('序列配对入口只有一册在时显形（wx:if="{{hasTopic}}"）', /ix-entry-seq[^>]*wx:if="\{\{hasTopic\}\}"/.test(wxml)
  || /wx:if="\{\{hasTopic\}\}"[^>]*ix-entry-seq/.test(wxml));

const playSrc = fs.readFileSync(path.join(TPL, 'pages/play/play.js'), 'utf8');
check('页面按 stage 判定锁左列（stageOf(bookId) === \'专题\'）',
  /stageOf\(s\.bookId\)\s*===\s*'专题'/.test(playSrc));
check('锁左列时左列用 enBase 原序、右列照旧洗牌',
  /lockLeft \? enBase\.slice\(\) : this\._shuffle\(enBase\.slice\(\)\)/.test(playSrc));

const bankSrc = fs.readFileSync(path.join(TPL, 'utils/bank.js'), 'utf8');
check('bank.js 的 LOADERS 注册了 topic 模块',
  /topic:\s*function\s*\(\)\s*\{\s*return require\('\.\.\/data\/levels-topic\.js'\)/.test(bankSrc));

const wxss = fs.readFileSync(path.join(TPL, 'pages/index/index.wxss'), 'utf8');
check('序列配对入口有自己的配色（.ix-entry-seq），不与上面一行混同', /\.ix-entry-seq\s*\{/.test(wxss));

/* ---------------------------------------------------------------- 收尾 */
fs.rmSync(tmp, { recursive: true, force: true });

console.log('\n' + '='.repeat(50));
console.log('===== ' + pass + '/' + (pass + fails.length) + ' 通过 =====');
if (fails.length) {
  console.log('失败项：');
  for (const f of fails) console.log('  - ' + f);
  process.exit(1);
}

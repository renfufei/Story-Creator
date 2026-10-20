// 词库访问层
// ---------------------------------------------------------------------------
// 数据由 scripts/learn/build_miniprogram_data.py 从线上服务导出（零漂移），
// 按学段拆成 4 个模块，**用到哪个学段才 require 哪个** —— 大学那一个模块有 632 KB，
// 如果启动时无条件解析，首屏会被它拖慢。
//
// 关卡结构经过压缩：pairs 是 [["en","zh"], ...] 数组（见生成脚本注释）。
// 每对的 key 恒等于 `bookId:levelIndex:pairIndex`，所以不入库、运行时拼出来即可 ——
// 20191 对省掉约 440 KB。

var META = require('../data/books.js');

var LOADERS = {
  primary: function () { return require('../data/levels-primary.js'); },
  junior: function () { return require('../data/levels-junior.js'); },
  senior: function () { return require('../data/levels-senior.js'); },
  /* 序列配对册（有序词群：数字 / 星期 / 月份 …）。86 词，单文件很小，
     但同样按需 require —— 只想练教材的人不必为它付解析成本。 */
  topic: function () { return require('../data/levels-topic.js'); },
  college: function () { return require('../data/levels-college.js'); }
};

var bookById = {};
var slugByStage = {};
var i;
for (i = 0; i < META.books.length; i++) bookById[META.books[i].id] = META.books[i];
for (i = 0; i < META.stages.length; i++) slugByStage[META.stages[i].name] = META.stages[i].slug;

var payloads = {};      // slug -> { bookId: [ {t, p} ] }
var levelCache = {};    // "bookId#index" -> { index, theme, pairs: [{key,en,zh}] }

function books() { return META.books; }

function stages() { return META.stages; }

function book(bookId) { return bookById[bookId] || null; }

function stageOf(bookId) {
  var b = bookById[bookId];
  return b ? b.stage : '';
}

function levelCount(bookId) {
  var b = bookById[bookId];
  return b ? b.levelCount : 0;
}

function ensureStage(bookId) {
  var st = stageOf(bookId);
  var slug = slugByStage[st];
  if (!slug) return null;
  if (!payloads[slug]) {
    var fn = LOADERS[slug];
    payloads[slug] = fn ? (fn() || {}) : {};
  }
  return payloads[slug];
}

// 单关按需物化 + 缓存。不把整册（更不把整个学段）的 13159 对一次性展开成对象，
// 只展开真正要下的那几关。
function levelAt(bookId, index) {
  if (index < 0) return null;
  var ck = bookId + '#' + index;
  var hit = levelCache[ck];
  if (hit) return hit;

  var p = ensureStage(bookId);
  var raw = (p && p[bookId]) ? p[bookId][index] : null;
  if (!raw) return null;

  var pairs = [];
  for (var k = 0; k < raw.p.length; k++) {
    pairs.push({
      key: bookId + ':' + index + ':' + k,
      en: raw.p[k][0],
      zh: raw.p[k][1]
    });
  }
  var lv = { index: index, theme: raw.t || '', pairs: pairs };
  levelCache[ck] = lv;
  return lv;
}

// 摊平遍历**全部**词条（检索用）。
// 与 levelAt 的区别：levelAt 只物化「要下的那一关」，这里为了检索必须把所有学段都载进来，
// 所以**只在搜索首次触发时调一次**（调用方 utils/search.js 自己缓存索引）。
// 刻意不物化中间对象 —— 直接读原始压缩数组（[["en","zh"], ...]），
// 20k 条走一遍不产生垃圾对象，内存只被索引占一份。
function forEachPair(fn) {
  for (var n = 0; n < META.books.length; n++) {
    var b = META.books[n];
    var p = ensureStage(b.id);
    var lvs = (p && p[b.id]) || [];
    for (var li = 0; li < lvs.length; li++) {
      var raw = lvs[li] || {};
      var pairs = raw.p || [];
      for (var pi = 0; pi < pairs.length; pi++) {
        fn(b, li, raw.t || '', pairs[pi][0], pairs[pi][1]);
      }
    }
  }
}

// 册次按学段分组（册次弹层用），并把每个学段的册数 / 词数 / 关数汇总好，
// 免得每次渲染都在 WXML 里重复算（WXML 里没法做这种累加）。
function groups() {
  var out = [];
  var map = {};
  for (var n = 0; n < META.books.length; n++) {
    var b = META.books[n];
    var st = b.stage || '小学';
    if (!map[st]) {
      map[st] = { stage: st, books: [], levelCount: 0, wordCount: 0 };
      out.push(map[st]);
    }
    map[st].books.push(b);
    map[st].levelCount += b.levelCount;
    map[st].wordCount += b.wordCount;
  }
  return out;
}

function totals() {
  var levels = 0, words = 0;
  for (var n = 0; n < META.books.length; n++) {
    levels += META.books[n].levelCount;
    words += META.books[n].wordCount;
  }
  return { books: META.books.length, levels: levels, words: words };
}

// 词库指纹：词库一变，指纹变，旧进度自动作废（见 store.ensureDataVersion）
// 版本号 v1 → v2：2026-09-27 四六级关卡顺序做了交错重排（同类最多连排 5 关），
// 关数与词数都没变、光看数字指纹识别不出来，但「第 N 关」指向的内容已全变，故升版本作废旧进度。
// 版本号 v2 → v3：同日又把 35 个过粗的语义域细分成 98 个小分类（68 → 131 个域），
// 关数也变了（2199 → 2203），但为了与网页版口径一致、并明确标记「域体系换代」，照旧显式升版本。
function signature() {
  var t = totals();
  return 'v3-' + t.books + '-' + t.levels + '-' + t.words;
}

module.exports = {
  books: books,
  stages: stages,
  book: book,
  stageOf: stageOf,
  levelCount: levelCount,
  levelAt: levelAt,
  forEachPair: forEachPair,
  groups: groups,
  totals: totals,
  signature: signature
};

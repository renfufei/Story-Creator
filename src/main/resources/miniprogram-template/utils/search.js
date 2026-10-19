// 全库单词检索（纯函数模块）
// ===========================================================================
// 与网页版 src/main/resources/static/pages/learn-word-match.html 里的 WM_SEARCH
// 是同一套匹配口径，一字不改地搬过来：
//
//   ① 完全匹配 —— 英文全等，或**某个中文义项**全等；全部列出、排在最前；
//   ② 前缀匹配 —— 英文开头，或某个中文义项开头；最多取前 5 个**单词**；
//   ③ 部分匹配 —— 只有前两类都空时才做（英文 / 中文含关键词即可）；同样最多 5 个。
//   「5 个单词」按**去重后的单词数**算：同一个词可能出现在多个词库的多组里
//   （四六级词表不去重），把它所有出处一起列出来 —— 否则用户查到 liberty 只看到
//   一处，会以为那是唯一出处。
//
// 为什么单独成模块而不是塞进页面：
//   这段是**纯函数**，不碰 wx.*、不碰 setData、不依赖任何页面状态，
//   所以能在 Node 里直接 require 并对真实词库跑断言
//   （见 scripts/learn/probe_miniprogram.js）。页面只负责把返回值投影到视图上。
//
// 索引只在**第一次搜索**时建一次，之后常驻本模块（页面对它只读，不进 data：
// 两万条摊平对象塞进 setData 会白白序列化一遍）。首次搜索因此会有一次
// 「把 4 个学段的数据模块全 require 进来」的开销，页面 onLoad 时已用
// setTimeout 预热，正常操作看不出来。

var bank = require('./bank.js');

/* 中文释义 → 可比对的义项数组。第一步必须剥掉词性前缀：四级 99% 的条目长这样
   「n. 自由权；自由」，不剥掉的话用户查「自由」永远凑不出前缀命中。
   再按中英文标点拆成多个义项，「完全匹配」才能落在单个义项上而不是整串上。 */
var POS_RE = /^\s*(?:n|v|vt|vi|adj|adv|prep|pron|conj|num|art|int|interj|aux|abbr|pl)\.\s*/i;

function zhParts(zh) {
  var t = String(zh == null ? '' : zh).replace(POS_RE, '');
  var out = [];
  var segs = t.split(/[；;，,、/（）()]/);
  for (var i = 0; i < segs.length; i++) {
    var s = segs[i].trim();
    if (s) out.push(s.toLowerCase());
  }
  if (!out.length && t.trim()) out.push(t.trim().toLowerCase());
  return out;
}

/* 义项比对：kind 0 = 全等，1 = 前缀（只认义项开头，中文没有「词中」的概念） */
function zhHit(parts, q, kind) {
  for (var i = 0; i < parts.length; i++) {
    if (kind === 0 ? parts[i] === q : parts[i].indexOf(q) === 0) return true;
  }
  return false;
}

/* 一条命中的等级：0 完全 / 1 前缀 / 2 包含。按条目自身性质判定（与它在哪一段无关） */
function rankOf(it, q) {
  if (it.enL === q || zhHit(it.zhParts, q, 0)) return 0;
  if (it.enL.indexOf(q) === 0 || zhHit(it.zhParts, q, 1)) return 1;
  return 2;
}

/* ---------- 索引：册 → 关 → 词对，一次性摊平 ---------- */
var _items = null;

function items() {
  if (_items) return _items;
  var out = [];
  bank.forEachPair(function (book, level, theme, en, zh) {
    var e = String(en == null ? '' : en);
    if (!e) return;                       // 没英文的空条目不入索引（原实现同样跳过）
    var z = String(zh == null ? '' : zh);
    out.push({
      en: e,
      enL: e.toLowerCase(),
      zh: z,
      zhL: z.toLowerCase(),
      zhParts: zhParts(z),
      /* level 存的是**下标**，展示时要 +1 才是「第 N 关」 */
      bookId: book.id,
      bookLabel: book.label || book.id,
      level: level,
      theme: theme
    });
  });
  _items = out;
  return _items;
}

/* 截断到「最多 maxWords 个不同单词」—— 先按出现顺序挑出前 maxWords 个**不同单词**，再把这一步挑中的单词在这份候选列表里的
  全部行收进来。不能边扫边收 —— 候选列表是按「册 → 关 → 词对」排的，同一个词的多处出处
  分散在天各一方（例：student 同时在三年级下册第 2 关与七年级上册第 49 关），扫到第 6 个
  新词就 break，会把前面那 5 个词**排在后面的出处**一起丢掉 —— 于是「同一个词的所有出处
  一起列出来」这条口径名存实亡，用户还会以为那是唯一出处。 */
function capWords(list, maxWords) {
  /* 用 Object.create(null) 而不是 {}：Object.prototype 上的键名
     （constructor / toString）会被误判成「这个键已经见过了」 */
  var allow = Object.create(null);
  var words = 0, i;
  for (i = 0; i < list.length && words < maxWords; i++) {
    var w = list[i].enL;
    if (!allow[w]) { allow[w] = true; words++; }
  }
  var out = [];
  for (i = 0; i < list.length; i++) {
    if (allow[list[i].enL]) out.push(list[i]);
  }
  return out;
}

/* 结果行：把展示要用的东西一并算好（WXML 里做不了字符串拼接以外的逻辑） */
function toRow(it, q, rank) {
  return {
    /* wx:key 要唯一：同一个词在同一关里不会重复，但可能出现在不同关 / 不同词库 */
    uid: it.en + '#' + it.bookId + '#' + it.level,
    en: it.en,
    zh: it.zh,
    bookId: it.bookId,
    level: it.level,
    levelNo: it.level + 1,
    src: it.bookLabel + ' · 第 ' + (it.level + 1) + ' 关' + (it.theme ? ' · ' + it.theme : ''),
    rank: rank
  };
}

var MODE_TEXT = {
  empty: '',
  exact: '完全匹配',
  'exact+prefix': '完全匹配 + 前缀匹配',
  prefix: '前缀匹配 · 最多 5 个单词',
  part: '包含匹配 · 最多 5 个单词',
  none: '没有匹配的单词'
};

function modeText(mode) {
  return MODE_TEXT[mode] || '';
}

/* 返回 { hits, mode, q }；mode ∈ empty | exact | exact+prefix | prefix | part | none */
function run(rawQ) {
  var q = String(rawQ == null ? '' : rawQ).trim().toLowerCase();
  if (!q) return { hits: [], mode: 'empty', q: '' };

  var list = items();
  var exact = [], prefix = [], part = [];
  for (var i = 0; i < list.length; i++) {
    var it = list[i];
    var r = rankOf(it, q);
    if (r === 0) exact.push(it);
    else if (r === 1) prefix.push(it);
    else if (it.enL.indexOf(q) >= 0 || it.zhL.indexOf(q) >= 0) part.push(it);
  }

  var picked, mode;
  if (exact.length) {
    picked = exact.concat(capWords(prefix, 5));
    mode = prefix.length ? 'exact+prefix' : 'exact';
  } else if (prefix.length) {
    picked = capWords(prefix, 5);
    mode = 'prefix';
  } else if (part.length) {
    picked = capWords(part, 5);
    mode = 'part';
  } else {
    return { hits: [], mode: 'none', q: q };
  }

  var hits = [];
  for (var k = 0; k < picked.length; k++) {
    hits.push(toRow(picked[k], q, rankOf(picked[k], q)));
  }
  return { hits: hits, mode: mode, q: q };
}

module.exports = {
  run: run,
  zhParts: zhParts,
  modeText: modeText,
  /* 预热索引（首屏渲染后调一次，别让用户第一次敲字时才建索引）。
     失败无副作用：真搜索时会重建。 */
  warm: function () { items(); },
  /* 仅供测试用：让探针能验证索引规模（页面不要调） */
  _size: function () { return items().length; }
};

// 小程序工程静态校验器（开发工具，不进小程序包体）
// ---------------------------------------------------------------------------
// 本机没装微信开发者工具，跑不了真编译，所以把「编译器会报错 / 会静默失效」的几类
// 问题用静态规则兜住。
//
// 设计要点（踩过坑的地方）：
//   · 注释必须**先整体剥离再逐行判断**，否则注释里举的例子（比如顶部说明写的
//     「<div>/<span>/<button> → <view>/<text>」）会被当成真代码报一堆假错。
//     剥离时用「等长空白替换、保留换行」，这样行号不会错位。
//   · data 字段要能解析「一行多个键」的写法（本项目 data 就是这样排的），
//     只按行取第一个键会漏掉绝大多数字段。
//   · class 属性里的 {{ 三元表达式 }} 要先剔除再切分，否则会切出
//     「{{autoMode」「?」「wm-btn-success}}」这种碎片。
//   · wx:else 的相邻性要在**标签流**上判断（找 wx:if 元素的闭合标签，再看它的
//     下一个兄弟开标签是不是 wx:else），不能在行上往后数几行猜。
//
// 用法： node scripts/learn/validate_miniprogram.js [工程目录]
// 退出码 0 = 无 ERROR；1 = 有 ERROR。
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(process.argv[2] || path.join(__dirname, '../../word-match-miniprogram'));
const results = [];
const fail = (file, line, msg) => results.push({ level: 'ERROR', file, line, msg });
const warn = (file, line, msg) => results.push({ level: 'WARN', file, line, msg });
const rel = p => path.relative(ROOT, p);

/* ---------- 注释剥离：等长空白替换，保留换行 => 行号不错位 ---------- */
function stripBlockComments(src, open, close) {
  let out = '';
  let i = 0;
  while (i < src.length) {
    const s = src.indexOf(open, i);
    if (s < 0) { out += src.slice(i); break; }
    out += src.slice(i, s);
    const e = src.indexOf(close, s + open.length);
    const end = e < 0 ? src.length : e + close.length;
    // 用空白填掉整段，但换行照留
    for (let k = s; k < end; k++) out += (src[k] === '\n' ? '\n' : ' ');
    i = end;
  }
  return out;
}
const stripWxmlComment = s => stripBlockComments(s, '<!--', '-->');
const stripCssComment = s => stripBlockComments(s, '/*', '*/');
const lineOf = (src, idx) => src.slice(0, idx).split('\n').length;

/* ===================== WXML ===================== */
const VOID_TAGS = new Set(['image', 'input', 'icon', 'progress', 'slider', 'switch',
  'audio', 'video', 'canvas', 'camera', 'live-player', 'live-pusher', 'open-data',
  'cover-image', 'textarea', 'import', 'include', 'wxs']);
const HTML_TAGS = new Set(['div', 'span', 'b', 'i', 'u', 's', 'em', 'strong', 'button',
  'a', 'img', 'ul', 'ol', 'li', 'p', 'h1', 'h2', 'h3', 'h4', 'table', 'tr', 'td', 'br', 'hr', 'label']);

function tokenizeWxml(src) {
  const tags = [];
  const re = /<(\/?)([a-zA-Z][\w-]*)((?:"[^"]*"|'[^']*'|[^>])*?)(\/?)>/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    tags.push({
      closing: m[1] === '/',
      tag: m[2],
      attrs: m[3],
      selfClose: m[4] === '/',
      idx: m.index,
      line: lineOf(src, m.index)
    });
  }
  return tags;
}

function checkWxml(file, dataFields) {
  const raw = fs.readFileSync(file, 'utf8');
  const src = stripWxmlComment(raw);
  const tags = tokenizeWxml(src);

  /* --- 标签配平 + HTML 标签残留 --- */
  const stack = [];
  for (const t of tags) {
    if (HTML_TAGS.has(t.tag)) {
      fail(rel(file), t.line, `残留 HTML 标签 <${t.tag}>，WXML 不识别（应换成 view / text）`);
    }
    if (VOID_TAGS.has(t.tag) || t.selfClose) continue;
    if (!t.closing) stack.push(t);
    else {
      const top = stack.pop();
      if (!top) fail(rel(file), t.line, `多余的闭合标签 </${t.tag}>`);
      else if (top.tag !== t.tag) fail(rel(file), t.line, `标签不匹配：</${t.tag}> 对应第 ${top.line} 行的 <${top.tag}>`);
      else { top.pair = t; }
    }
  }
  for (const t of stack) fail(rel(file), t.line, `<${t.tag}> 未闭合`);

  /* --- wx:for 必须有 wx:key --- */
  for (const t of tags) {
    if (/\bwx:for\s*=/.test(t.attrs) && !/\bwx:key\s*=/.test(t.attrs)) {
      fail(rel(file), t.line, 'wx:for 缺少 wx:key（同一标签内没有）');
    }
  }

  /* --- wx:else 必须紧跟 wx:if：在标签流上找兄弟 --- */
  for (const t of tags) {
    if (!/\bwx:else\b/.test(t.attrs)) continue;
    // 找紧邻的前一个「已闭合元素」：即前一个结束的标签
    const prevIdx = tags.indexOf(t) - 1;
    const prev = tags[prevIdx];
    if (!prev) { fail(rel(file), t.line, 'wx:else 前面没有任何节点'); continue; }
    // prev 应是某个元素的闭合标签；它对应的开标签里必须有 wx:if / wx:elif
    if (!prev.closing) { fail(rel(file), t.line, 'wx:else 前一个节点不是已闭合的元素'); continue; }
    const opener = tags.find(x => x.pair === prev);
    if (!opener) { fail(rel(file), t.line, 'wx:else 前一个节点的开标签没找到'); continue; }
    if (!/\bwx:if\b|\bwx:elif\b/.test(opener.attrs)) {
      fail(rel(file), t.line, `wx:else 前面第 ${opener.line} 行的 <${opener.tag}> 没有 wx:if`);
    }
  }

  /* --- {{ }} 里引用的 data 字段 --- */
  const locals = new Set(['item', 'index', 'true', 'false', 'null', 'undefined']);
  let fm;
  const aliasRe = /wx:for-(?:item|index)\s*=\s*"([^"]+)"/g;
  while ((fm = aliasRe.exec(src)) !== null) locals.add(fm[1].trim());

  const exprRe = /\{\{([^}]*)\}\}/g;
  let em;
  while ((em = exprRe.exec(src)) !== null) {
    const lineNo = lineOf(src, em.index);
    const expr = em[1];
    const stripped = expr.replace(/'[^']*'/g, "''").replace(/"[^"]*"/g, '""');
    const ids = new Set();
    const idRe = /(?:^|[^\w.$'"])([A-Za-z_$][\w$]*)/g;
    let im;
    while ((im = idRe.exec(stripped)) !== null) ids.add(im[1]);
    for (const id of ids) {
      if (locals.has(id) || dataFields.has(id)) continue;
      fail(rel(file), lineNo, `{{ ${expr.trim()} }} 引用了未在 data 声明的字段「${id}」`);
    }
  }
}

/* ===================== WXSS ===================== */
const BAD_WXSS = [
  [/:(hover|focus|focus-within|focus-visible|visited|link|active)\b/, '伪类 :$1 在小程序里不成立（触摸反馈用 hover-class）'],
  [/\d(?:dvh|dvw|svh|lvh)\b/, '视口单位 dvh/dvw/lvh 不支持'],
  [/\bclamp\s*\(/, 'clamp() 支持度差，直接写字面量'],
  [/\bmin\s*\(/, 'min() 支持度差'],
  [/\bmax\s*\(/, 'max() 支持度差'],
  [/backdrop-filter/, 'backdrop-filter 不支持'],
  [/user-select\s*:/, 'user-select 无意义'],
  [/\bcursor\s*:/, 'cursor 无意义'],
  [/touch-action\s*:/, 'touch-action 无意义'],
  [/prefers-reduced-motion/, 'prefers-reduced-motion 无意义'],
  [/position\s*:\s*sticky/, 'position:sticky 在 scroll-view 内不可靠'],
  [/\bdisplay\s*:\s*grid\b/, 'display:grid 支持度参差，建议改 flex'],
  [/^\s*(b|i|u|s|em|strong|span|div|a|img|ul|ol|li|p|h[1-6]|table|tr|td)\s*[,{:>+~ ]/, '标签选择器 <$1>：WXML 没有这个标签，永不命中'],
  [/var\s*\(\s*--/, 'CSS 变量需在 WXSS 里就地展开为字面量'],
];

function checkWxss(file) {
  const src = stripCssComment(fs.readFileSync(file, 'utf8'));
  const L = src.split('\n');
  for (let i = 0; i < L.length; i++) {
    if (!L[i].trim()) continue;
    for (const [re, msg] of BAD_WXSS) {
      const hit = L[i].match(re);
      if (hit) warn(rel(file), i + 1, msg.replace(/\$(\d)/g, (_, d) => hit[+d] || ''));
    }
  }
}

/* ===================== class 交叉引用 ===================== */
function collectClasses() {
  const used = new Map();
  const defined = new Set();

  const walk = (dir, ext, fn) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p, ext, fn);
      else if (path.extname(e.name) === ext) fn(p);
    }
  };
  const add = (c, where) => {
    if (!c || c === 'none' || !/^[A-Za-z][\w-]*$/.test(c)) return;
    if (!used.has(c)) used.set(c, []);
    used.get(c).push(where);
  };

  walk(ROOT, '.wxml', f => {
    const src = stripWxmlComment(fs.readFileSync(f, 'utf8'));
    const re = /(?:class|hover-class)\s*=\s*"([^"]*)"/g;
    let m;
    while ((m = re.exec(src)) !== null) {
      const line = lineOf(src, m.index);
      const v = m[1];
      // 先取出三元里的字符串字面量，再剔除 {{ }} 后按空白切分静态部分
      let lit;
      const litRe = /'([^']*)'|"([^"]*)"/g;
      while ((lit = litRe.exec(v)) !== null) add(lit[1] || lit[2], `${rel(f)}:${line}`);
      for (const c of v.replace(/\{\{[^}]*\}\}/g, ' ').split(/\s+/)) add(c, `${rel(f)}:${line}`);
    }
  });
  walk(ROOT, '.js', f => {
    const src = fs.readFileSync(f, 'utf8');
    const re = /['"`]\s?((?:wm|is)-[a-z0-9-]+)/g;
    let m;
    while ((m = re.exec(src)) !== null) add(m[1], `${rel(f)}:${lineOf(src, m.index)}`);
  });
  walk(ROOT, '.wxss', f => {
    const src = stripCssComment(fs.readFileSync(f, 'utf8'));
    const re = /\.([A-Za-z][\w-]*)/g;
    let m;
    while ((m = re.exec(src)) !== null) defined.add(m[1]);
  });

  return { used, defined };
}

/* ===================== data 字段提取 ===================== */
function extractDataFields(jsPath) {
  const src = fs.readFileSync(jsPath, 'utf8');
  const at = src.search(/\n\s{2}data:\s*\{/);
  if (at < 0) return null;
  const open = src.indexOf('{', at);
  let depth = 0, end = -1;
  for (let i = open; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') { depth--; if (depth === 0) { end = i; break; } }
  }
  if (end < 0) return null;
  const body = src.slice(open + 1, end);
  const fields = new Set();
  // 只取顶层键：本项目 data 是扁平的（标量/数组），但为稳妥仍按深度过滤
  let d = 0, i = 0;
  while (i < body.length) {
    const c = body[i];
    if (c === '{' || c === '[') d++;
    else if (c === '}' || c === ']') d--;
    // 跳过字符串
    if (c === "'" || c === '"' || c === '`') {
      const q = c; i++;
      while (i < body.length && body[i] !== q) { if (body[i] === '\\') i++; i++; }
    } else if (d === 0 && /[A-Za-z_$]/.test(c)) {
      const m = /^([A-Za-z_$][\w$]*)\s*:/.exec(body.slice(i));
      if (m) { fields.add(m[1]); i += m[0].length; continue; }
    }
    i++;
  }
  return fields;
}

/* ===================== 跑 ===================== */
function findFiles(dir, ext) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...findFiles(p, ext));
    else if (path.extname(e.name) === ext) out.push(p);
  }
  return out;
}

const appJson = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));

const dataFields = new Set();
for (const pg of appJson.pages) {
  const f = extractDataFields(path.join(ROOT, pg + '.js'));
  if (!f) fail(pg + '.js', 0, '解析不出 data: { ... } 块');
  else for (const k of f) dataFields.add(k);
}

const wxmls = findFiles(ROOT, '.wxml');
const wxsss = findFiles(ROOT, '.wxss');
for (const f of wxmls) checkWxml(f, dataFields);
for (const f of wxsss) checkWxss(f);

const { used, defined } = collectClasses();
for (const [c, where] of used) {
  if (!defined.has(c)) warn(where[0], 0, `class「${c}」被引用但没有任何 WXSS 定义`);
}

for (const pg of appJson.pages) {
  for (const ext of ['.js', '.wxml', '.json', '.wxss']) {
    if (!fs.existsSync(path.join(ROOT, pg + ext))) fail('app.json', 0, `页面 ${pg} 缺少 ${ext}`);
  }
}

/* ===================== 输出 ===================== */
const errors = results.filter(r => r.level === 'ERROR');
const warns = results.filter(r => r.level === 'WARN');
const group = (arr, title) => {
  if (!arr.length) return;
  console.log(`\n${title}`);
  const byFile = {};
  for (const r of arr) (byFile[r.file] = byFile[r.file] || []).push(r);
  for (const f of Object.keys(byFile)) {
    console.log(`  ${f}`);
    for (const r of byFile[f]) console.log(`    ${r.line ? 'L' + r.line + '  ' : ''}${r.msg}`);
  }
};

console.log('==============================================');
console.log(' 微信小程序工程 · 静态校验');
console.log('==============================================');
console.log(` 工程目录：${ROOT}`);
console.log(` 扫描：${wxmls.length} WXML / ${wxsss.length} WXSS / ${appJson.pages.length} 页面`);
console.log(` data 字段 ${dataFields.size} 个；WXSS 定义 class ${defined.size} 个，被引用 ${used.size} 个`);

group(errors, `【ERROR】${errors.length} 项 —— 必须修`);
group(warns, `【WARN】${warns.length} 项 —— 建议逐条确认`);
if (!errors.length && !warns.length) console.log('\n 全部通过 ✔');
console.log('\n----------------------------------------------');
console.log(` 结果：ERROR ${errors.length} / WARN ${warns.length}`);
process.exit(errors.length ? 1 : 0);

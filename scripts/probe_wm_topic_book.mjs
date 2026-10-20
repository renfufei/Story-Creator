#!/usr/bin/env node
/**
 * 单词匹配 · 【序列配对】册（2026-10-20，原「专题练习」）
 *
 * 专题册 = 从 PEP 24 册里抽出来的**有序词群**（数字 / 序数词 / 星期 / 月份 / 季节 / 时间）。
 * 它和普通册唯一的玩法差别：
 *   **左列英文保持词库顺序，只打乱右列中文。**
 * 因为这类词的价值就在「按序列背下来」——左列一乱，练的就不是同一个东西了。
 *
 * 实现落点：
 *   · 后端 WordMatchBank 额外加载 learn/topic-words.json（stage = 专题），切关逻辑完全复用；
 *   · 前端 buildCards 用 lockLeft = stageOf(bookId) === '专题' 决定左列是否洗牌；
 *   · 首页底部按 theme 现算出一组快捷卡（topicThemes），点了 enterTopic(theme)
 *     → 展开入口出一排 chips，点 chip 切册 + themeScope 收窄到该专题 → 进棋盘。
 *
 * 本脚本逐条守护：
 *   T1 【核心】首页只有一个【序列配对】入口（默认收起，展开才出专题 chips）
 *   T2 【核心】点「数字」进棋盘：左列严格 zero→five，右列中文被随机打乱
 *   T3 【核心】下一关：左列继续 six→eleven（顺序延续，不是重新洗过）
 *   T4 【核心】点「序数词」：左列 first→fifth（另一专题同样锁序）
 *   T5 【核心】重复进入同一专题 3 次，左列顺序**每次都不变**
 *   T6 【核心】普通册左列仍会被打乱（锁序只对专题生效，没有误伤全站）
 *   T7 320px：首页与棋盘都无横向溢出，卡片仍两列
 *   T8 全程无 JS 运行时异常
 *
 * ★ 自带静态服务器，不依赖 1888（它的静态资源处理已坏，见记忆 dev-workflow）。
 *
 * 用法：node scripts/probe_wm_topic_book.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9386;
const HTTP_PORT = 18907;
const SHOT_DIR = '/tmp/wm-topic-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC = path.join(ROOT, 'src/main/resources/static');
/* vendor 在 data/static（classpath 静态目录里没有），线上由 file:./data/static 提供 */
const STATIC_ROOTS = [STATIC, path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假词库 ==================== */
const L = (theme, pairs) => ({ theme: theme, pairs: pairs });
/* ★ 必须带 key：页面 countAligned 比的是 pair key，mock 里缺 key 就全是 undefined，
   防对齐循环会「永远认为对齐 6 个」却无法收敛（真后端一定给 key，这是 mock 的坑）。 */
const P = (en, zh) => ({ key: 'k-' + en, en, zh });

const NUM1 = [P('zero', '零'), P('one', '一'), P('two', '二'),
              P('three', '三'), P('four', '四'), P('five', '五')];
const NUM2 = [P('six', '六'), P('seven', '七'), P('eight', '八'),
              P('nine', '九'), P('ten', '十'), P('eleven', '十一')];
const ORD1 = [P('first', '第一'), P('second', '第二'), P('third', '第三'),
              P('fourth', '第四'), P('fifth', '第五')];
const SEASON = [P('spring', '春天'), P('summer', '夏天'), P('autumn', '秋天')];
/* 普通册：用来证明「左列锁序」只对专题生效 */
const NORMAL = [P('pen', '钢笔'), P('ruler', '尺子'), P('bag', '包'),
                P('book', '书'), P('desk', '课桌'), P('chair', '椅子')];

const WM_DATA = {
    books: [
        { id: 'pep-3a', label: '三年级上册', grade: 3, semester: '上册', stage: '小学',
          levelCount: 1, wordCount: NORMAL.length },
        { id: 'pep-topic', label: '序列配对', grade: 0, semester: '', stage: '专题',
          levelCount: 4, wordCount: NUM1.length + NUM2.length + ORD1.length + SEASON.length }
    ],
    extraBooks: [],
    levels: {
        'pep-3a': [L('学习用品', NORMAL)],
        'pep-topic': [L('数字', NUM1), L('数字', NUM2), L('序数词', ORD1), L('季节', SEASON)]
    }
};

/* ==================== 静态服务器 ==================== */
const MIME = {
    '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml', '.png': 'image/png',
    '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf'
};

function serveStatic(res, relPath) {
    let buf = null, hit = null;
    for (const root of STATIC_ROOTS) {
        const file = path.join(root, relPath);
        if (!file.startsWith(root)) continue;
        try { buf = fs.readFileSync(file); hit = file; break; } catch (e) { /* 换下一个根目录 */ }
    }
    if (!buf) { res.writeHead(404); return res.end(''); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(hit)] || 'application/octet-stream' });
    res.end(buf);
}

let dataRequests = 0;
const server = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (pn === '/learn/word-match') return serveStatic(res, 'pages/learn-word-match.html');
    if (pn === '/learn/word-match/data') {
        dataRequests++;
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify(WM_DATA));
    }
    if (pn === '/learn/word-match/cet') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify({}));
    }
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-topic-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + CDP_PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

let ws, msgId = 0;
const pending = new Map();
const runtimeErrors = [];

function send(method, params = {}) {
    const id = ++msgId;
    return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method, params })); });
}
async function evalJs(expression) {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error('页面 JS 异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    return r.result.value;
}
async function waitFor(fn, timeout = 12000, step = 120) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) { if (await fn()) return true; await sleep(step); }
    return false;
}

async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

let navSeq = 0;
async function goto(width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}/learn/word-match?_probe=` + (++navSeq) });
}
const waitSettled = () => waitFor(async () => {
    try {
        return (await evalJs(`(() => { const r = document.querySelector('.wm-root');
            const d = r && window.Alpine ? window.Alpine.$data(r) : null; return !!d && d.booting === false; })()`)) === true;
    } catch (e) { return false; }
});

/** 真实鼠标点击（Alpine @click 需要真实事件流；element.click() 会绕过一些指针状态）
 *  先 scrollIntoView —— 窄屏下专题区块可能在首屏视口之外，坐标落到视口外时 CDP 点击不生效。 */
async function clickSel(selector) {
    const box = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        if (!el) return null;
        el.scrollIntoView({ block: 'center' });
        const r = el.getBoundingClientRect();
        if (!r.width || !r.height) return null;
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!box) throw new Error('点击目标不存在或不可见: ' + selector);
    await sleep(120);
    const pt = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        const r = el.getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: pt.x, y: pt.y, button: 'none', clickCount: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: pt.x, y: pt.y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: pt.x, y: pt.y, button: 'left', clickCount: 1 });
}

/* 按卡片可见顺序读两列文案 */
const BOARD = `(() => {
    const txt = (sel) => Array.from(document.querySelectorAll(sel))
        .filter(el => el.getClientRects().length > 0)
        .map(el => (el.innerText || '').replace(/\\s+/g, ' ').trim());
    const vis = (el) => !!el && el.getClientRects().length > 0 && getComputedStyle(el).display !== 'none';
    const root = document.querySelector('.wm-root');
    const st = (root && window.Alpine) ? window.Alpine.$data(root) : null;
    return {
        left: txt('.wm-col-en .wm-card'),
        right: txt('.wm-col-zh .wm-card'),
        view: st ? st.view : null,
        bookId: st ? st.bookId : null,
        theme: st ? (st.currentLevel ? st.currentLevel.theme : null) : null,
        levelIndex: st ? st.levelIndex : -1,
        scopeTheme: st && st.themeScope ? st.themeScope.theme : null,
        scopeLevels: st && st.themeScope ? st.themeScope.indexes.length : 0,
        scopeText: (() => { const el = document.querySelector('.wm-scope-name'); return el ? el.innerText.trim() : null; })(),
        /* 【序列配对】入口：默认只占一行，点开才出 chips */
        entry: (() => {
            const el = document.querySelector('#wm-topic-entry');
            if (!vis(el)) return null;
            const q = (sel) => { const e = el.querySelector(sel); return e ? (e.innerText || '').trim() : ''; };
            return {
                title: q('.wm-home-entry-title'),
                desc: q('.wm-home-entry-desc'),
                go: q('.wm-home-entry-go'),
                h: Math.round(el.getBoundingClientRect().height)
            };
        })(),
        chipsVisible: vis(document.querySelector('#wm-topic-chips')),
        chipsOverflow: (() => {
            const el = document.querySelector('#wm-topic-chips');
            return el ? el.scrollWidth - el.clientWidth : -1;
        })(),
        topicCards: Array.from(document.querySelectorAll('.wm-home-chip'))
            .filter(vis)
            .map(el => ({
                name: el.firstElementChild ? (el.firstElementChild.innerText || '').trim() : '',
                meta: (el.querySelector('.wm-home-chip-meta') || {}).innerText || ''
            })),
        topicSectionVisible: vis(document.querySelector('#wm-home-topic')),
        keysL: st ? Array.from(st.leftCards).map(c => c.key) : [],
        keysR: st ? Array.from(st.rightCards).map(c => c.key) : [],
        alignedNow: st ? st.countAligned(Array.from(st.leftCards), Array.from(st.rightCards)) : -1,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;
const board = async (ms = 260) => { await sleep(ms); return evalJs(BOARD); };

const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

/** 右列中文被「真打乱」的判据：位置对得上的不超过 1 个（页面本来就有这个防对齐规则） */
function alignedCount(leftEn, rightZh) {
    let n = 0;
    const zhOf = {};
    (NUM1.concat(NUM2, ORD1, SEASON)).forEach(p => { zhOf[p.en] = p.zh; });
    for (let i = 0; i < leftEn.length && i < rightZh.length; i++) {
        if (zhOf[leftEn[i]] === rightZh[i]) n++;
    }
    return n;
}

/** 按索引点第 n 个专题 chip（chips 默认收起，点之前先确保入口是展开的） */
async function clickTopicCard(idx) {
    const open = await evalJs(`(() => {
        const el = document.querySelector('#wm-topic-chips');
        return !!el && el.getClientRects().length > 0;
    })()`);
    if (!open) { await clickSel('#wm-topic-entry'); await sleep(240); }

    const box = await evalJs(`(() => {
        const els = Array.from(document.querySelectorAll('.wm-home-chip'))
            .filter(e => e.getClientRects().length > 0);
        const el = els[${idx}];
        if (!el) return null;
        el.scrollIntoView({ block: 'center' });
        const r = el.getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!box) throw new Error('专题 chip #' + idx + ' 不存在或不可见');
    await sleep(120);
    const pt = await evalJs(`(() => {
        const els = Array.from(document.querySelectorAll('.wm-home-chip'))
            .filter(e => e.getClientRects().length > 0);
        const r = els[${idx}].getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: pt.x, y: pt.y, button: 'none', clickCount: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: pt.x, y: pt.y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: pt.x, y: pt.y, button: 'left', clickCount: 1 });
}

/* ==================== 主流程 ==================== */
async function main() {
    await new Promise((res, rej) => { server.once('error', rej); server.listen(HTTP_PORT, '127.0.0.1', res); });
    console.log(`静态服务器: http://127.0.0.1:${HTTP_PORT}`);

    let ver = null;
    for (let i = 0; i < 80; i++) {
        try { const r = await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`); if (r.ok) { ver = await r.json(); break; } } catch (e) {}
        await sleep(250);
    }
    if (!ver) throw new Error('Chrome CDP 端点未就绪');
    console.log('Chrome: ' + ver['Browser']);

    const target = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/new?about:blank`, { method: 'PUT' })).json();
    ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = (ev) => {
        const m = JSON.parse(ev.data);
        if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); return; }
        if (m.method === 'Runtime.exceptionThrown') runtimeErrors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
    };
    await send('Runtime.enable');
    await send('Page.enable');

    /* ---------- T1 首页专题入口（一行，默认收起） ---------- */
    console.log('\n— T1 检索首页的【序列配对】入口 —');
    await goto();
    check('页面 boot 完成', (await waitSettled()) === true);
    let s = await board(400);
    check('【核心】首页出现【序列配对】入口', s.topicSectionVisible === true && !!s.entry);
    check('【核心】入口标题 = 序列配对', s.entry && s.entry.title === '序列配对', s.entry && s.entry.title);
    check('入口描述报口径与体量（英文按顺序 · 中文打乱 · 3 个专题 · 20 词）',
        s.entry && s.entry.desc === '英文按顺序 · 中文打乱 · 3 个专题 · 20 词', s.entry && s.entry.desc);
    check('【核心】默认收起：chips 一个都不渲染', s.chipsVisible === false && s.topicCards.length === 0,
        'chips=' + s.chipsVisible + ' n=' + s.topicCards.length);
    check('【核心】入口只占一行（高度 ≤ 60px，不再是半屏卡片墙）', s.entry && s.entry.h <= 60,
        'h=' + (s.entry && s.entry.h));
    check('首页本身无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('topic-01-home');

    /* 点入口展开 */
    await clickSel('#wm-topic-entry');
    s = await board(300);
    check('点入口展开：出现 3 个专题 chip', s.chipsVisible === true && s.topicCards.length === 3,
        'n=' + s.topicCards.length);
    check('chip 名称与顺序正确',
        s.topicCards.map(c => c.name).join('|') === '数字|序数词|季节',
        s.topicCards.map(c => c.name).join('|'));
    check('chip 带「词数 · 关数」',
        s.topicCards[0] && s.topicCards[0].meta.indexOf('12 词') >= 0 && s.topicCards[0].meta.indexOf('2 关') >= 0,
        s.topicCards[0] && s.topicCards[0].meta);
    check('右侧提示已变成「收起」', s.entry && s.entry.go === '收起', s.entry && s.entry.go);
    await shot('topic-01b-home-open');

    /* ---------- T2 点「数字」 ---------- */
    console.log('\n— T2 点「数字」：左列锁序、右列打乱 —');
    await clickTopicCard(0);
    await sleep(400);
    s = await board(300);
    check('【核心】已切进棋盘', s.view === 'play', 'view=' + s.view);
    check('【核心】进的是专题册', s.bookId === 'pep-topic', 'bookId=' + s.bookId);
    check('【核心】左列严格 = zero→five（词库原顺序）',
        same(s.left, NUM1.map(p => p.en)), s.left.join(','));
    check('右列 6 个中文都在，只是换了位置',
        same(s.right.slice().sort(), NUM1.map(p => p.zh).slice().sort()), s.right.join(','));
    const al = alignedCount(NUM1.map(p => p.en), s.right);
    check('【核心】右列确实被打乱（同位正确 ≤ 1 个）', al <= 1,
        `aligned=${al} pageAligned=${s.alignedNow} L=${s.keysL.join('/')} R=${s.keysR.join('/')}`);
    check('【核心】右列顺序不等于原顺序', !same(s.right, NUM1.map(p => p.zh)), s.right.join(','));
    check('分类练习作用域 = 该专题的 2 关', s.scopeLevels === 2, 'levels=' + s.scopeLevels);
    check('状态条显示专题名「数字」', s.scopeText === '数字', 'text=' + s.scopeText);
    check('页头类别 = 数字', s.theme === '数字', 'theme=' + s.theme);
    await shot('topic-02-number');

    /* ---------- T3 下一关 ---------- */
    console.log('\n— T3 下一关：顺序继续，不是重新洗 —');
    await evalJs(`(() => { const r = document.querySelector('.wm-root');
        const d = window.Alpine.$data(r); d.nextLevel(); return true; })()`);
    s = await board(320);
    check('已到第 2 关（本册第 2 关）', s.levelIndex === 1, 'idx=' + s.levelIndex);
    check('【核心】左列 = six→eleven（延续上一关）',
        same(s.left, NUM2.map(p => p.en)), s.left.join(','));

    /* ---------- T4 另一个专题 ---------- */
    console.log('\n— T4 点「序数词」：另一专题同样锁序 —');
    /* goSearch 回检索页（exit() 是退出到 /learn，会把页面导航走） */
    await evalJs(`(() => { const r = document.querySelector('.wm-root');
        const d = window.Alpine.$data(r); d.goSearch(); return true; })()`);
    await sleep(300);
    await clickTopicCard(1);
    s = await board(400);
    check('【核心】左列 = first→fifth', same(s.left, ORD1.map(p => p.en)), s.left.join(','));
    check('状态条换成「序数词」', s.scopeText === '序数词', 'text=' + s.scopeText);
    check('作用域只有 1 关', s.scopeLevels === 1, 'levels=' + s.scopeLevels);

    /* ---------- T5 反复进入：顺序稳定 ---------- */
    console.log('\n— T5 同一专题连进 3 次：左列每次都是原顺序 —');
    let stable = true, detail = [];
    for (let i = 0; i < 3; i++) {
        await evalJs(`(() => { const d = window.Alpine.$data(document.querySelector('.wm-root'));
            d.loadLevel(); return true; })()`);
        const b = await board(220);
        detail.push(b.left.join(','));
        if (!same(b.left, ORD1.map(p => p.en))) stable = false;
    }
    check('【核心】重复发牌不会把左列打乱', stable, detail[0] + ' / ' + detail[2]);

    /* ---------- T6 普通册仍打乱 ---------- */
    console.log('\n— T6 普通册：左列照样洗牌（锁序只对专题生效）—');
    let shuffledAtLeastOnce = false, seen = [];
    for (let i = 0; i < 8; i++) {
        await evalJs(`(() => { const d = window.Alpine.$data(document.querySelector('.wm-root'));
            d.applyBook('pep-3a', false); d.view = 'play'; return true; })()`);
        await sleep(150);
        const b = await board(120);
        seen.push(b.left.join(','));
        if (!same(b.left, NORMAL.map(p => p.en))) { shuffledAtLeastOnce = true; break; }
    }
    check('【核心】普通册左列会被打乱（至少 8 次里有一次不是原顺序）', shuffledAtLeastOnce,
        seen[0]);

    /* ---------- T7 320px ---------- */
    console.log('\n— T7 320px 窄屏 —');
    await goto(320, 568);
    await waitSettled();
    s = await board(400);
    check('窄屏首页无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏入口行仍只是一行（高度 ≤ 64px）', s.entry && s.entry.h <= 64, 'h=' + (s.entry && s.entry.h));
    /* 截图前滚到入口：320×568 一屏装不下整页，不滚的话截图里看不到入口 */
    await evalJs(`(() => { const el = document.querySelector('#wm-topic-entry');
        if (el) el.scrollIntoView({ block: 'center' }); return true; })()`);
    await sleep(200);
    await shot('topic-03-mobile-home');

    /* 展开后 chips 必须折行，不能把页面撑宽 */
    await clickSel('#wm-topic-entry');
    s = await board(320);
    check('窄屏展开后 chips 出现且自动折行（自身无横向溢出）',
        s.chipsVisible === true && s.topicCards.length === 3 && s.chipsOverflow <= 1,
        `n=${s.topicCards.length} overflow=${s.chipsOverflow}`);
    check('窄屏展开后整页仍无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await evalJs(`(() => { const el = document.querySelector('#wm-topic-chips');
        if (el) el.scrollIntoView({ block: 'center' }); return true; })()`);
    await sleep(200);
    await shot('topic-03b-mobile-home-open');

    await clickTopicCard(0);
    s = await board(400);
    check('窄屏棋盘无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏下左列仍是 zero→five', same(s.left, NUM1.map(p => p.en)), s.left.join(','));
    await shot('topic-04-mobile-board');

    /* ---------- T8 ---------- */
    console.log('\n— T8 全程 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));
    check('两次加载各请求一次词库（共 2 次，没有重复拉）', dataRequests === 2, 'requests=' + dataRequests);

    const failed = results.filter(x => !x.ok).length;
    console.log(`\n===== ${results.length - failed}/${results.length} 通过 =====`);
    if (failed) console.log('失败项：\n' + results.filter(x => !x.ok).map(x => ' - ' + x.name).join('\n'));

    try { await send('Browser.close'); } catch (e) {}
    try { chrome.kill(); } catch (e) {}
    try { server.close(); } catch (e) {}
    process.exit(failed ? 1 : 0);
}

main().catch(async (e) => {
    console.error('PROBE FAILED: ' + e.message);
    try { chrome.kill(); } catch (err) {}
    try { server.close(); } catch (err) {}
    process.exit(2);
});

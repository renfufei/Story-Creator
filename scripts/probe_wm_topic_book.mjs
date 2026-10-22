#!/usr/bin/env node
/**
 * 单词匹配 · 首页两个练习入口（趣味配对 / 序列配对），2026-10-20
 *
 * 形态（与小程序 pages/index 页尾一致）：
 *   · 检索首页底部两个入口各占**一行**，右侧一个【进入】，点一次直接进棋盘；
 *   · 趣味配对 = 普通册（小学 / 初中 / 高中 / 大学）；
 *   · 序列配对 = 专题册（PEP 里的有序词群：数字 / 序数词 / 星期 / 月份 / 季节 / 时间），
 *     玩法差别只在发牌：**左列英文保持词库顺序，只打乱右列中文**；
 *   · 两者起点同口径：该册第一个还没通关的关，全通关则从第 1 关重来（applyBook(restore)）。
 *
 * 实现落点：
 *   · 后端 WordMatchBank 额外加载 learn/topic-words.json（stage = 专题），切关逻辑完全复用；
 *   · 前端 buildCards 用 lockLeft = stageOf(bookId) === '专题' 决定左列是否洗牌；
 *   · 入口：enterFunPlay() / enterTopicBook() → 共用 enterBook(bookId) → applyBook(id, true)。
 *
 * 本脚本逐条守护：
 *   T1 首页两个入口：各一行、右侧【进入】、**不再展开专题 chips**
 *   T2 点【沉浸式】序列配对进入：第 1 关，左列 zero→five，右列中文被打乱
 *   T3 下一关：左列继续 six→eleven（顺序延续）
 *   T4 有学习记录时进入：直接落到第一个**没通关**的关（第 3 关 first→fifth）
 *   T5 反复发牌：左列顺序每次都不变
 *   T6 趣味配对入口：落到普通册，左列照样洗牌（锁序没误伤全站）
 *   T7 320px：两个入口仍各一行、无横向溢出
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
 *  先 scrollIntoView —— 窄屏下入口可能在首屏视口之外，坐标落到视口外时 CDP 点击不生效。 */
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

/* 按可见顺序读两列文案 + 首页两个入口 */
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
        levelCount: st ? st.levels.length : -1,
        scopeTheme: st && st.themeScope ? st.themeScope.theme : null,
        /* 首页两个入口：各一行，右侧【进入】 */
        entries: Array.from(document.querySelectorAll('.wm-home-entry'))
            .filter(vis)
            .map(el => {
                const q = (sel) => { const e = el.querySelector(sel); return e ? (e.innerText || '').replace(/\\s+/g, ' ').trim() : ''; };
                const de = el.querySelector('.wm-home-entry-desc');
                return {
                    id: el.id,
                    title: q('.wm-home-entry-title'),
                    desc: q('.wm-home-entry-desc'),
                    go: q('.wm-home-entry-go'),
                    icon: q('.wm-home-entry-icon'),
                    h: Math.round(el.getBoundingClientRect().height),
                    /* 副文案是否被裁掉（桌面是 nowrap+ellipsis ⇒ 比 scrollWidth；窄屏是两行 clamp ⇒ 比 scrollHeight） */
                    descFits: de ? (de.scrollWidth <= de.clientWidth + 1 && de.scrollHeight <= de.clientHeight + 1) : true
                };
            }),
        /* 专题 chips 已经取消：这两个数必须恒为 0 / false */
        chipCount: document.querySelectorAll('.wm-home-chip').length,
        chipsEl: !!document.querySelector('#wm-topic-chips'),
        entriesVisible: vis(document.querySelector('#wm-home-entries')),
        keysL: st ? Array.from(st.leftCards).map(c => c.key) : [],
        keysR: st ? Array.from(st.rightCards).map(c => c.key) : [],
        alignedNow: st ? st.countAligned(Array.from(st.leftCards), Array.from(st.rightCards)) : -1,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;
const board = async (ms = 260) => { await sleep(ms); return evalJs(BOARD); };

const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
const entryOf = (s, id) => s.entries.find(e => e.id === id) || null;

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

    /* ---------- T1 首页两个入口 ---------- */
    console.log('\n— T1 检索首页的两个练习入口 —');
    await goto();
    check('页面 boot 完成', (await waitSettled()) === true);
    let s = await board(400);
    const fun = entryOf(s, 'wm-fun-entry');
    const topic = entryOf(s, 'wm-topic-entry');
    check('【核心】首页底部出现两个入口（趣味配对 + 序列配对）',
        s.entriesVisible === true && !!fun && !!topic, 'entries=' + s.entries.length);
    check('【核心】入口标题 = 趣味配对 / 序列配对',
        !!fun && !!topic && fun.title === '趣味配对' && topic.title === '序列配对',
        [fun && fun.title, topic && topic.title].join(' | '));
    check('右侧都有【进入】按钮',
        !!fun && /^进入/.test(fun.go) && !!topic && /^进入/.test(topic.go),
        [fun && fun.go, topic && topic.go].join(' | '));
    check('【核心】趣味配对图标 = 小程序的 🎯（不是 Bootstrap 图标）',
        !!fun && fun.icon === '🎯', fun && fun.icon);
    check('趣味配对副文案报「册数 + 接着练哪册」',
        !!fun && fun.desc.indexOf('册') >= 0 && fun.desc.indexOf('英汉两列点选配对') === 0, fun && fun.desc);
    check('序列配对副文案报口径与体量（英文按顺序 · 中文打乱 · 3 个专题 · 20 词）',
        !!topic && topic.desc === '英文按顺序 · 中文打乱 · 3 个专题 · 20 词', topic && topic.desc);
    check('【核心】不再展开：chips 元素与专题面板都不存在',
        s.chipCount === 0 && s.chipsEl === false, 'chip=' + s.chipCount + ' el=' + s.chipsEl);
    check('【核心】两个入口各只占一行（高度 ≤ 60px）',
        !!fun && fun.h <= 60 && !!topic && topic.h <= 60,
        'fun=' + (fun && fun.h) + ' topic=' + (topic && topic.h));
    check('首页本身无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await evalJs(`(() => { const el = document.querySelector('#wm-home-entries');
        if (el) el.scrollIntoView({ block: 'center' }); return true; })()`);
    await sleep(200);
    await shot('topic-01-home');

    /* ---------- T2 点序列配对【进入】 ---------- */
    console.log('\n— T2 点「序列配对 · 进入」：直接进第 1 关，左列锁序、右列打乱 —');
    await clickSel('#wm-topic-entry');
    await sleep(400);
    s = await board(300);
    check('【核心】已切进棋盘', s.view === 'play', 'view=' + s.view);
    check('【核心】进的是专题册', s.bookId === 'pep-topic', 'bookId=' + s.bookId);
    check('【核心】零进度 ⇒ 从第 1 关开始（levelIndex=0）', s.levelIndex === 0, 'idx=' + s.levelIndex);
    check('【核心】左列严格 = zero→five（词库原顺序）',
        same(s.left, NUM1.map(p => p.en)), s.left.join(','));
    check('右列 6 个中文都在，只是换了位置',
        same(s.right.slice().sort(), NUM1.map(p => p.zh).slice().sort()), s.right.join(','));
    const al = alignedCount(NUM1.map(p => p.en), s.right);
    check('【核心】右列确实被打乱（同位正确 ≤ 1 个）', al <= 1,
        `aligned=${al} pageAligned=${s.alignedNow} L=${s.keysL.join('/')} R=${s.keysR.join('/')}`);
    check('【核心】右列顺序不等于原顺序', !same(s.right, NUM1.map(p => p.zh)), s.right.join(','));
    check('整册连续练：不再按专题收窄作用域', s.scopeTheme === null, 'scope=' + s.scopeTheme);
    check('页头类别 = 数字', s.theme === '数字', 'theme=' + s.theme);
    check('本册共 4 关（数字 ×2 + 序数词 + 季节）', s.levelCount === 4, 'n=' + s.levelCount);
    await shot('topic-02-number');

    /* ---------- T3 下一关 ---------- */
    console.log('\n— T3 下一关：顺序继续，不是重新洗 —');
    await evalJs(`(() => { const r = document.querySelector('.wm-root');
        const d = window.Alpine.$data(r); d.nextLevel(); return true; })()`);
    s = await board(320);
    check('已到第 2 关', s.levelIndex === 1, 'idx=' + s.levelIndex);
    check('【核心】左列 = six→eleven（延续上一关）',
        same(s.left, NUM2.map(p => p.en)), s.left.join(','));

    /* ---------- T4 有学习记录：落到第一个没通关的关 ---------- */
    console.log('\n— T4 有学习记录时进入：直接从第一个没通关的关开始 —');
    /* 指纹在 boot 时已经写好；这时往进度里塞「前两关通关」，reload 后指纹不变 ⇒ 不会被清。
       （ensureDataVersion 只在指纹变化时清进度，所以必须在页面跑过一遍之后再写。） */
    await evalJs(`(() => {
        const sig = localStorage.getItem('word_match_data_sig_v1');
        localStorage.setItem('word_match_progress_v2', JSON.stringify({ 'pep-topic': [0, 1] }));
        return sig;
    })()`);
    await goto();
    check('reload 后 boot 完成', (await waitSettled()) === true);
    await clickSel('#wm-topic-entry');
    await sleep(400);
    s = await board(320);
    check('【核心】直接从第 3 关开始（前两关已通关）', s.levelIndex === 2, 'idx=' + s.levelIndex);
    check('【核心】左列 = first→fifth（该关仍在专题册里锁序）',
        same(s.left, ORD1.map(p => p.en)), s.left.join(','));
    check('页头类别换成「序数词」', s.theme === '序数词', 'theme=' + s.theme);
    check('进度记录没有被词库指纹误清（仍能看到上次的关）',
        (await evalJs(`JSON.stringify(JSON.parse(localStorage.getItem('word_match_progress_v2') || '{}')['pep-topic'] || [])`)) === '[0,1]');

    /* ---------- T5 反复发牌：顺序稳定 ---------- */
    console.log('\n— T5 同一关连发 3 次：左列每次都是原顺序 —');
    let stable = true, detail = [];
    for (let i = 0; i < 3; i++) {
        await evalJs(`(() => { const d = window.Alpine.$data(document.querySelector('.wm-root'));
            d.loadLevel(); return true; })()`);
        const b = await board(220);
        detail.push(b.left.join(','));
        if (!same(b.left, ORD1.map(p => p.en))) stable = false;
    }
    check('【核心】重复发牌不会把左列打乱', stable, detail[0] + ' / ' + detail[2]);

    /* ---------- T6 趣味配对入口 ---------- */
    console.log('\n— T6 点「趣味配对 · 进入」：落到普通册，左列照样洗牌 —');
    /* goSearch 回检索页（exit() 是退出到 /learn，会把页面导航走） */
    await evalJs(`(() => { const d = window.Alpine.$data(document.querySelector('.wm-root'));
        d.goSearch(); return true; })()`);
    await sleep(300);
    s = await board(240);
    check('已回到检索首页', s.view === 'search', 'view=' + s.view);
    const funNow = entryOf(s, 'wm-fun-entry');
    check('上次停在专题册时，趣味配对副文案不报「接着练」（两入口不串册）',
        !!funNow && funNow.desc.indexOf('接着练') < 0, funNow && funNow.desc);

    await clickSel('#wm-fun-entry');
    await sleep(400);
    s = await board(300);
    check('【核心】已切进棋盘', s.view === 'play', 'view=' + s.view);
    check('【核心】进的是普通册（不是专题册）', s.bookId === 'pep-3a', 'bookId=' + s.bookId);
    check('【核心】普通册第 1 关', s.levelIndex === 0, 'idx=' + s.levelIndex);

    /* 普通册左列必须仍被洗牌（锁序只对专题生效） */
    let shuffledAtLeastOnce = false, seen = [];
    for (let i = 0; i < 8; i++) {
        await evalJs(`(() => { const d = window.Alpine.$data(document.querySelector('.wm-root'));
            d.loadLevel(); return true; })()`);
        await sleep(120);
        const b = await board(120);
        seen.push(b.left.join(','));
        if (!same(b.left, NORMAL.map(p => p.en))) { shuffledAtLeastOnce = true; break; }
    }
    check('【核心】普通册左列会被打乱（至少 8 次里有一次不是原顺序）', shuffledAtLeastOnce, seen[0]);

    /* 回到检索页，点趣味配对应当「接着练」这一册 */
    await evalJs(`(() => { const d = window.Alpine.$data(document.querySelector('.wm-root'));
        d.goSearch(); return true; })()`);
    s = await board(240);
    const funCur = entryOf(s, 'wm-fun-entry');
    check('普通册成为当前册后，趣味配对副文案报「接着练「三年级上册」」',
        !!funCur && funCur.desc.indexOf('接着练「三年级上册」') > 0, funCur && funCur.desc);
    await shot('topic-03-home-fun');

    /* ---------- T7 320px ---------- */
    console.log('\n— T7 320px 窄屏 —');
    await goto(320, 568);
    await waitSettled();
    s = await board(400);
    check('窄屏首页无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏两个入口仍各一行（高度 ≤ 76px：副文案允许折两行）',
        s.entries.length === 2 && s.entries.every(e => e.h <= 76),
        s.entries.map(e => e.id + ':' + e.h).join(' '));
    check('窄屏副文案没有被截断（折两行装得下「接着练哪册」）',
        s.entries.every(e => e.descFits === true),
        s.entries.map(e => e.id + '=' + e.desc + ' fit=' + e.descFits).join(' | '));
    check('窄屏右侧【进入】没有被挤掉',
        s.entries.every(e => /^进入/.test(e.go)), s.entries.map(e => e.go).join(' | '));
    /* 截图前滚到入口：320×568 一屏装不下整页，不滚的话截图里看不到入口 */
    await evalJs(`(() => { const el = document.querySelector('#wm-home-entries');
        if (el) el.scrollIntoView({ block: 'center' }); return true; })()`);
    await sleep(200);
    await shot('topic-04-mobile-home');

    /* 进度仍是 [0,1] ⇒ 窄屏进入同样落到第 3 关（first→fifth） */
    await clickSel('#wm-topic-entry');
    s = await board(400);
    check('窄屏棋盘无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏下仍从第一个没通关的关开始、左列锁序',
        s.levelIndex === 2 && same(s.left, ORD1.map(p => p.en)), `idx=${s.levelIndex} L=${s.left.join(',')}`);
    await shot('topic-05-mobile-board');

    /* ---------- T8 ---------- */
    console.log('\n— T8 全程 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));
    check('每次加载各请求一次词库（共 3 次，没有重复拉）', dataRequests === 3, 'requests=' + dataRequests);

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

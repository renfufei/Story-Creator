#!/usr/bin/env node
/**
 * 英语单词匹配页：**引导数据异步化 + 骨架 + 进度指纹不误清**（2026-10-20）。
 *
 *   页面 /learn/word-match.html（线上路由 /learn/word-match）
 *   数据 /learn/word-match/data  →  { books, extraBooks, levels }
 *
 * 原来词库是同步 XHR 拉的：2 万条词、24 册，浏览器停在 HTML 解析期等这一趟，
 * 首屏纯白屏。改异步之后有个**必须守住的连带风险**：
 *   页面构造期就算「词库指纹」(v3-册数-关数-词数)，指纹一变就清空用户进度。
 *   数据没到齐时算出来是 v3-0-0-0 ⇒ 会把真实进度整片清掉。
 *   所以指纹校验搬进 boot()（await 之后），并且「词库为空时直接跳过」。
 *
 * 本脚本逐条守护：
 *   P0 【核心】挂住词库：DOM 已 complete、骨架在、检索首页还没出现（books 为空）
 *   P1 【核心】放行词库：骨架退场、检索首页出现、册数/关数/年级分组都对
 *   P1b【核心】指纹相同 ⇒ 进度**不被清**（异步化最容易踩的回归）
 *   P1c【核心】指纹不同 ⇒ 旧进度仍会被清（换代机制没被改坏）
 *   P2 【核心】词库 500：页面不崩、骨架收掉、**进度同样不被清**
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 每次加载只请求一次词库
 *
 * ★ 自带静态服务器，不依赖 1888（2026-10-20 实测它的静态资源处理无响应）。
 *
 * 用法：node scripts/probe_learn_word_match_boot.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9379;
const HTTP_PORT = 18900;
const SHOT_DIR = '/tmp/wm-boot-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC = path.join(ROOT, 'src/main/resources/static');
/* vendor（bootstrap / bootstrap-icons / alpinejs）不在 classpath 静态目录里，
   线上由 file:./data/static 提供（见站点配置），这里一并复制这个口径 */
const STATIC_ROOTS = [STATIC, path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假词库 ==================== */
const L = (theme, pairs) => ({ theme: theme, pairs: pairs });
const WM_DATA = {
    books: [
        { id: 'pep-3a', label: '三年级上册', levelCount: 2, wordCount: 8 },
        { id: 'pep-3b', label: '三年级下册', levelCount: 2, wordCount: 8 }
    ],
    extraBooks: [],
    levels: {
        'pep-3a': [
            L('学习用品', [{ en: 'pen', zh: 'n. 钢笔' }, { en: 'ruler', zh: 'n. 尺子' }]),
            L('动物', [{ en: 'cat', zh: 'n. 猫' }, { en: 'dog', zh: 'n. 狗' }])
        ],
        'pep-3b': [
            L('食物', [{ en: 'bread', zh: 'n. 面包' }, { en: 'milk', zh: 'n. 牛奶' }]),
            L('颜色', [{ en: 'red', zh: 'adj. 红色的' }, { en: 'blue', zh: 'adj. 蓝色的' }])
        ]
    }
};
/* 与页面 dataSignature() 同口径：'v3-' + 册数 + '-' + 关数 + '-' + 词数 */
const SIG_OK = 'v3-2-4-16';
const PROGRESS_KEY = 'word_match_progress_v2';
const SIG_KEY = 'word_match_data_sig_v1';
const PROGRESS_SEED = JSON.stringify({ 'pep-3a': { 0: 1 } });

/* ==================== 静态服务器 ==================== */
let holdData = false, failData = false, heldRes = null, dataRequests = 0;

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

const server = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (pn === '/learn/word-match') return serveStatic(res, 'pages/learn-word-match.html');
    if (pn === '/learn/word-match/data') {
        dataRequests++;
        if (holdData) { heldRes = res; return; }
        if (failData) {
            res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
            return res.end(JSON.stringify({ error: '词库炸了' }));
        }
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify(WM_DATA));
    }
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

function releaseData() {
    if (!heldRes) return false;
    const res = heldRes;
    heldRes = null;
    holdData = false;
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(WM_DATA));
    return true;
}

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-boot-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + CDP_PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

let ws, msgId = 0;
const pending = new Map();
const runtimeErrors = [];
const consoleErrors = [];

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

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const vis = (el) => !!el && el.getClientRects().length > 0 && getComputedStyle(el).display !== 'none';
    const root = document.querySelector('.wm-root');
    const st = (root && window.Alpine) ? window.Alpine.$data(root) : null;
    const skel = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    const home = document.querySelector('.wm-home');
    const btn = document.querySelector('.wm-book-btn');
    let prog = null, sig = null;
    try { prog = localStorage.getItem('${PROGRESS_KEY}'); sig = localStorage.getItem('${SIG_KEY}'); } catch (e) {}
    return {
        readyState: document.readyState,
        booting: st ? st.booting : null,
        bookCount: st ? Array.from(st.books).length : -1,
        levelKeys: st ? Object.keys(st.allLevels).length : -1,
        lv3a: st ? Array.from(st.allLevels['pep-3a'] || []).length : -1,
        view: st ? st.view : null,
        skelVisible: skel.length,
        homeVisible: vis(home),
        toolbarVisible: vis(document.querySelector('.wm-toolbar')),
        bookBtnText: btn ? (btn.innerText || '').replace(/\\s+/g, ' ').trim() : null,
        progress: prog,
        sig: sig,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;
const snap = async (ms = 200) => { if (ms) await sleep(ms); return evalJs(SNAP); };

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
    try { return (await evalJs(`(() => { const r = document.querySelector('.wm-root');
        const d = r && window.Alpine ? window.Alpine.$data(r) : null; return !!d && d.booting === false; })()`)) === true; }
    catch (e) { return false; }
});
const waitSkeleton = () => waitFor(async () => {
    try { return (await evalJs(`document.querySelectorAll('.sc-skel').length`)) > 0; } catch (e) { return false; }
}, 15000, 100);

/** 每次加载都只请求一次词库 */
let lastCount = 0;
function checkOnce(label) {
    const delta = dataRequests - lastCount;
    lastCount = dataRequests;
    check(label + ' 本次加载只请求了一次词库', delta === 1, 'delta=' + delta);
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
        else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') consoleErrors.push((m.params.args || []).map(a => a.value || a.description || '').join(' '));
    };
    await send('Runtime.enable');
    await send('Page.enable');

    /* ---------- P0 挂住词库 ---------- */
    console.log('\n— P0 把词库挂住：首屏仍要出来 —');
    holdData = true;
    await goto();
    await waitSkeleton();
    let s = await snap(500);
    check('【核心】词库挂着时 DOM 已 readyState=complete', s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】骨架已渲染（不是白屏）', s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】内容区还没露出', s.toolbarVisible === false, 'toolbar=' + s.toolbarVisible);
    check('【核心】检索首页还没出现（books 还是空）', s.homeVisible === false && s.bookCount === 0,
        `home=${s.homeVisible} books=${s.bookCount}`);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('wm-01-skeleton');

    /* ---------- P1 放行 ---------- */
    console.log('\n— P1 放行词库：骨架退场、首页出来 —');
    check('词库请求确实被挂住过（说明是异步发的）', releaseData());
    check('【核心】boot 结束（booting 转 false）', (await waitSettled()) === true);
    checkOnce('P1');
    s = await snap(400);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】内容区露出', s.toolbarVisible === true);
    check('【核心】检索首页出现', s.homeVisible === true, 'home=' + s.homeVisible);
    check('【核心】册数/关数都对',
        s.bookCount === 2 && s.levelKeys === 2 && s.lv3a === 2,
        `books=${s.bookCount} levelKeys=${s.levelKeys} lv3a=${s.lv3a}`);
    check('册次下拉停在占位文案（首次访问不预设册）',
        (s.bookBtnText || '').indexOf('选择关卡练习') >= 0, 'text=' + s.bookBtnText);
    check('【核心】指纹已按真实词库写入', s.sig === SIG_OK, 'sig=' + s.sig);
    await shot('wm-02-loaded');

    /* ---------- P1b 指纹相同：进度不能被清 ---------- */
    console.log('\n— P1b 指纹相同：进度必须保留 —');
    await evalJs(`localStorage.setItem('${PROGRESS_KEY}', ${JSON.stringify(PROGRESS_SEED)});
                 localStorage.setItem('${SIG_KEY}', '${SIG_OK}');`);
    lastCount = dataRequests;
    await goto();
    await waitSettled();
    checkOnce('P1b');
    s = await snap(300);
    check('【核心】异步化后同样的指纹不会清进度', s.progress === PROGRESS_SEED,
        'progress=' + String(s.progress).slice(0, 40));

    /* ---------- P1c 指纹不同：老机制仍要清 ---------- */
    console.log('\n— P1c 指纹不同：旧进度仍会被清（换代机制没坏） —');
    await evalJs(`localStorage.setItem('${PROGRESS_KEY}', ${JSON.stringify(PROGRESS_SEED)});
                 localStorage.setItem('${SIG_KEY}', 'v3-9-9-9');`);
    lastCount = dataRequests;
    await goto();
    await waitSettled();
    checkOnce('P1c');
    s = await snap(300);
    check('【核心】指纹不符 ⇒ 旧进度清掉', s.progress === null, 'progress=' + s.progress);
    check('【核心】指纹被改成当前词库的值', s.sig === SIG_OK, 'sig=' + s.sig);

    /* ---------- P2 词库 500 ---------- */
    console.log('\n— P2 词库 500：页面退化，但别误清进度 —');
    const errBefore = runtimeErrors.length;
    await evalJs(`localStorage.setItem('${PROGRESS_KEY}', ${JSON.stringify(PROGRESS_SEED)});
                 localStorage.setItem('${SIG_KEY}', '${SIG_OK}');`);
    failData = true;
    lastCount = dataRequests;
    await goto();
    check('【核心】500 时 booting 仍会转 false（不卡骨架）', (await waitSettled()) === true);
    checkOnce('P2');
    s = await snap(300);
    check('页面没崩：骨架收掉', s.skelVisible === 0);
    check('【核心】词库为空时不算指纹 ⇒ 进度保住', s.progress === PROGRESS_SEED,
        'progress=' + String(s.progress).slice(0, 40));
    check('【核心】空词库不报未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    await shot('wm-03-degraded');

    /* ---------- P3 390px ---------- */
    console.log('\n— P3 390px 窄屏 —');
    failData = false;
    holdData = true;
    await goto(390, 780);
    await waitSkeleton();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('wm-04-mobile-skeleton');
    releaseData();
    await waitSettled();
    s = await snap(400);
    check('窄屏数据态无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏检索首页同样出来', s.homeVisible === true);
    await shot('wm-05-mobile-loaded');

    /* ---------- 收尾 ---------- */
    console.log('\n— 全程 —');
    check('全程无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));

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

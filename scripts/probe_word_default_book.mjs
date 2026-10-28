#!/usr/bin/env node
/**
 * 探针：线上单页件（发布出去的词库页）「默认册从哪来」。
 *
 * 用户问：发布后打开默认是三年级上册，还是会被本地记录影响？
 *
 * 钉这几件事：
 *   ① 干净浏览器（该域名下没有任何 localStorage）→ 落在导出时锁定的起始册（__WM_DEFAULT_BOOK__）；
 *   ② 同一域名下**这份单页件**的记录（word_match_book_solo_<时间戳>）存在时 → 用它，覆盖默认册；
 *   ③ 另一个时间戳的 solo 键（＝上一次发布的旧版本留下的）→ 不影响本次；
 *   ④ 通用键 word_match_book_v1（本地 1888 页写的）→ 不影响单页件。
 *
 * 用法：node scripts/probe_word_default_book.mjs
 *      WM_PAGE='https://…/?sharecode=…' node scripts/probe_word_default_book.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const PAGE = process.env.WM_PAGE || 'https://word-match-61798.app.workbuddy.host/?sharecode=ENQEODEVZ3';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.WM_CDP_PORT || 9364);

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wmd-'));

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--autoplay-policy=no-user-gesture-required',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let ws;
let msgId = 0;
const pending = new Map();
const pageErrors = [];

function send(method, params = {}) {
    const id = ++msgId;
    return new Promise((res, rej) => {
        pending.set(id, { res, rej });
        ws.send(JSON.stringify({ id, method, params }));
    });
}
async function evalJs(expression) {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
        throw new Error('页面 JS 异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    }
    return r.result.value;
}
async function waitFor(expr, tries = 120, gap = 200) {
    for (let i = 0; i < tries; i++) {
        try { if (await evalJs(expr)) return true; } catch (e) { /* 导航中 */ }
        await sleep(gap);
    }
    return false;
}
const READY = `!!(window.Alpine && document.querySelector('.wm-root')
    && (function(){ try { return Alpine.$data(document.querySelector('.wm-root')).booting === false; } catch(e){ return false; } })())`;

/** 读页面状态：当前册 / 册标签 / 视图 / 锁定的默认册 / 本文件存储键 */
const STATE = `(() => {
    var d = window.Alpine.$data(document.querySelector('.wm-root'));
    var keys = {};
    for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k.indexOf('word_match') === 0) keys[k] = localStorage.getItem(k);
    }
    return JSON.stringify({
        bookId: d.bookId, label: d.bookLabel, view: d.view,
        dft: window.__WM_DEFAULT_BOOK__ || '', bookKey: d.bookKey || '',
        standalone: !!window.__WM_STANDALONE__, nBooks: (d.books || []).length,
        ls: keys
    });
})()`;

async function state() {
    const raw = await evalJs(STATE);
    return JSON.parse(raw);
}

/** 从 about:blank 走到目标页（第一次需要真正的导航；之后用 reload 复用同源） */
async function reload() {
    await send('Page.navigate', { url: 'about:blank' });
    await sleep(150);
    await send('Page.navigate', { url: PAGE + '&_p=' + Date.now() });
    const ok = await waitFor(READY, 150, 250);
    if (!ok) throw new Error('页面没就绪（Alpine 未挂载或 boot 未结束）');
    return state();
}

async function seed(items) {
    // 先到同源页，才能写这个域名的 localStorage
    await send('Page.navigate', { url: PAGE + '&_p=' + Date.now() });
    await waitFor(`!!window.Alpine`, 120, 200);
    await evalJs(`(() => { localStorage.clear(); return true; })()`);
    if (Object.keys(items).length) {
        const js = Object.entries(items)
            .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
            .join('');
        await evalJs(`(() => { ${js} return true; })()`);
    }
    await sleep(120);
}

try {
    let wsUrl = null;
    for (let i = 0; i < 60; i++) {
        try {
            const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
            const p = list.find((t) => t.type === 'page');
            if (p && p.webSocketDebuggerUrl) { wsUrl = p.webSocketDebuggerUrl; break; }
        } catch (e) { /* 还没起来 */ }
        await sleep(200);
    }
    if (!wsUrl) throw new Error('连不上无头 Chrome 的 CDP');
    ws = new WebSocket(wsUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = (ev) => {
        const m = JSON.parse(ev.data);
        if (m.id && pending.has(m.id)) {
            const { res, rej } = pending.get(m.id);
            pending.delete(m.id);
            m.error ? rej(new Error(m.error.message)) : res(m.result);
        } else if (m.method === 'Runtime.exceptionThrown') {
            pageErrors.push(m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text);
        }
    };
    await send('Page.enable');
    await send('Runtime.enable');

    console.log('页面：' + PAGE + '\n');

    /* ---------- 场景 A：干净浏览器（等同于第一次来的访客） ---------- */
    let s = await reload();
    console.log('A 干净浏览器 →', JSON.stringify({ bookId: s.bookId, label: s.label, view: s.view, dft: s.dft, bookKey: s.bookKey }));
    check('A1 单页件模式（__WM_STANDALONE__ = true，直接落在棋盘）', s.standalone === true && s.view === 'play', `view=${s.view}`);
    check('A2 默认册 = 导出锁定的起始册 pep-3-1', s.bookId === 'pep-3-1', `bookId=${s.bookId}`);
    check('A3 页头标签是「三年级上册」', /三年级上册/.test(s.label || ''), `label=${s.label}`);
    check('A4 首页渲染出了册列表', s.nBooks >= 24, `nBooks=${s.nBooks}`);
    check('A5 首次打开就把默认册写进本文件专属键', s.bookKey && s.ls[s.bookKey] === 'pep-3-1', `${s.bookKey}=${s.ls[s.bookKey]}`);

    /* ---------- 场景 B：这份单页件自己记录过别的册 ---------- */
    await seed({ [s.bookKey]: 'pep-5-1' });
    let b = await reload();
    console.log('B 本件记录=pep-5-1 →', JSON.stringify({ bookId: b.bookId, label: b.label, view: b.view }));
    check('B1 有本件记录时按记录走（不是 3 上）', b.bookId === 'pep-5-1', `bookId=${b.bookId}`);
    check('B2 标签同步成「五年级上册」', /五年级上册/.test(b.label || ''), `label=${b.label}`);

    /* ---------- 场景 C：旧版本发布留下的另一个 solo 键 ---------- */
    await seed({
        'word_match_book_solo_202501010000-aaaaa': 'pep-6-2',
        'word_match_book_v1': 'pep-4-1'
    });
    let c = await reload();
    console.log('C 旧 solo 键 + 通用键 →', JSON.stringify({ bookId: c.bookId, label: c.label, bookKey: c.bookKey }));
    check('C1 旧版本的时间戳 solo 键不串册（仍是默认册）', c.bookId === 'pep-3-1', `bookId=${c.bookId}`);
    check('C2 通用键 word_match_book_v1 也不串册', c.bookId !== 'pep-4-1', `bookId=${c.bookId}`);

    check('D 全程零未捕获 JS 异常', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));

    const pass = results.filter((r) => r.ok).length;
    console.log(`\n合计 ${pass}/${results.length} 通过`);
    process.exitCode = pass === results.length ? 0 : 1;
} catch (e) {
    console.error('探针异常：' + e.message);
    process.exitCode = 2;
} finally {
    try { ws && ws.close(); } catch (e) { /* 忽略 */ }
    chrome.kill('SIGKILL');
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* 忽略 */ }
}

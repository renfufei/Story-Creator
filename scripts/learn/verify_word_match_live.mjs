#!/usr/bin/env node
/**
 * 公网验收：直接打开已发布的「英语单词匹配」单页，确认线上跑的是刚发布的那一版。
 * 只读不写 —— 不碰本地 publish 目录，也不重新发布。
 *
 * 用法：node /tmp/wm-publish/verify-live.mjs
 *       URL=<其它地址> node /tmp/wm-publish/verify-live.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { SILENT_AUDIO_FLAGS, installSilentAudio }
    from '../lib/silent-audio.mjs';

const URL_ = process.env.URL
    || 'https://word-match-61798.app.workbuddy.host/?sharecode=ENQEODEVZ3';
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9351;
const OUT = '/tmp/wm-live';

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wml-'));

const results = [];
const check = (n, ok, extra = '') => {
    results.push({ n, ok: !!ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const chrome = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu',
    '--hide-scrollbars', ...SILENT_AUDIO_FLAGS, '--remote-debugging-port=' + PORT,
    '--user-data-dir=' + userDataDir, 'about:blank'], { stdio: 'ignore' });

let ws, msgId = 0;
const pending = new Map();
const external = [], errors = [], failed = [];
const send = (method, params = {}) => new Promise((res, rej) => {
    const id = ++msgId;
    pending.set(id, { res, rej });
    ws.send(JSON.stringify({ id, method, params }));
});
async function evalJs(expression) {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result.value;
}
async function waitFor(p, tries = 80, gap = 150) {
    for (let i = 0; i < tries; i++) {
        try { if (await evalJs(p)) return true; } catch (e) { /* 导航中 */ }
        await sleep(gap);
    }
    return false;
}

let ok = false;
try {
    for (let i = 0; i < 80; i++) {
        try {
            const v = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json();
            if (v && v['Browser']) break;
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    const t = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`,
        { method: 'PUT' })).json();
    ws = new WebSocket(t.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = (ev) => {
        const m = JSON.parse(ev.data);
        if (m.method === 'Runtime.exceptionThrown') {
            errors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
            return;
        }
        if (m.method === 'Network.requestWillBeSent') {
            const u = m.params.request.url;
            const host = new URL(URL_).host;
            if (/^https?:/i.test(u) && !u.includes(host)) external.push(u);
            return;
        }
        if (m.method === 'Network.loadingFailed') { failed.push(m.params.errorText); return; }
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id);
            pending.delete(m.id);
            m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
        }
    };
    await installSilentAudio(send);
    await send('Runtime.enable');
    await send('Network.enable');
    await send('Page.enable');
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 940, deviceScaleFactor: 1, mobile: false });

    await send('Page.navigate', { url: URL_ });
    const booted = await waitFor(`document.querySelectorAll('.wm-col-en .wm-card').length >= 3
        && !!window.__WM_STANDALONE__`, 100, 200);
    const st = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const data = window.__WORD_MATCH_DATA__ || {};
        const books = data.books || [];
        let levels = 0, words = 0;
        books.forEach(b => {
            const arr = (data.levels || {})[b.id] || [];
            levels += arr.length; arr.forEach(l => { words += (l.pairs || []).length; });
        });
        const icon = document.querySelector('link[rel="icon"]');
        const href = icon ? icon.getAttribute('href') : '';
        return { standalone: !!window.__WM_STANDALONE__, title: document.title,
                 books: books.length, levels, words,
                 stages: Array.from(new Set(books.map(b => b.stage))),
                 view: d.view, booting: d.booting, navGone: !document.getElementById('site-nav'),
                 iconData: /^data:image\\/svg\\+xml,/.test(href),
                 iconBytes: href ? decodeURIComponent(href.split(',').slice(1).join(',')).length : 0,
                 iconHasBlocks: /f0954f/.test(href) && /5ab5a8/.test(href),
                 en: document.querySelectorAll('.wm-col-en .wm-card').length };
    })()`);
    check('线上页面打开了，直接落棋盘', booted && st.view === 'play', `view=${st.view}`);
    check('是单机件（自包含），站点导航已摘掉', st.standalone && st.navGone);
    check('标题 / 册数 / 关卡 / 词数都是新导出的那一版',
        /等 27 册/.test(st.title) && st.books === 27 && st.levels === 3573 && st.words === 20349,
        `${st.books} 册 / ${st.levels} 关 / ${st.words} 词`);
    check('五个学段齐全（小学/初中/高中/专题/大学）', st.stages.length === 5, st.stages.join(','));
    check('favicon 是内联 data URI 的 A/B 积木（线上也带着新图标）',
        st.iconData && st.iconHasBlocks && st.iconBytes > 500,
        `data URI · ${st.iconBytes} 字节 · 含新配色`);

    /* 真点一对词 */
    const played = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const p = (d.currentLevel || {}).pairs?.[0] || {};
        const pos = (sel, txt) => {
            const el = Array.from(document.querySelectorAll(sel + ' .wm-card'))
                .find(e => (e.innerText || '').trim() === txt);
            if (!el) return null;
            const r = el.getBoundingClientRect();
            return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
        };
        return { en: pos('.wm-col-en', p.en), zh: pos('.wm-col-zh', p.zh) };
    })()`);
    if (played.en && played.zh) {
        for (const pt of [played.en, played.zh]) {
            const p = { x: pt.x, y: pt.y, button: 'left', buttons: 1, clickCount: 1, pointerType: 'mouse' };
            await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...p });
            await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...p });
            await sleep(220);
        }
        const m = await evalJs(`Alpine.$data(document.querySelector('.wm-root')).matchedCount`);
        check('线上点一对词能配对成功（不是只能看的空壳）', m === 1, `matchedCount=${m}`);
    } else {
        check('线上棋盘上找得到当前关的词卡', false);
    }

    await send('Emulation.setDeviceMetricsOverride',
        { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
    await sleep(700);
    const mob = await evalJs(`({ sw: document.documentElement.scrollWidth,
        cw: document.documentElement.clientWidth,
        en: document.querySelectorAll('.wm-col-en .wm-card').length })`);
    check('手机 390 不横向溢出、词卡还在', mob.sw <= mob.cw + 1 && mob.en >= 3,
        `${mob.sw} <= ${mob.cw} · ${mob.en} 张`);
    await send('Page.captureScreenshot', { format: 'png' }).then(r =>
        fs.writeFileSync(path.join(OUT, 'live-mobile.png'), Buffer.from(r.data, 'base64')));

    check('线上零站外请求、零资源加载失败', external.length === 0 && failed.length === 0,
        (external.slice(0, 3).join(' | ') || failed.slice(0, 3).join(' | ') || '零'));
    check('无未捕获 JS 错误', errors.length === 0, errors.slice(0, 2).join(' | ') || '无');

    const bad = results.filter(r => !r.ok);
    console.log(`\n===== ${results.length - bad.length}/${results.length} 通过 =====`);
    bad.forEach(b => console.log('  FAILED: ' + b.n));
    ok = bad.length === 0;
} catch (e) {
    console.error('执行异常：' + (e && e.stack || e));
} finally {
    try { ws && ws.close(); } catch (e) { /* ignore */ }
    try { chrome.kill('SIGKILL'); } catch (e) { /* ignore */ }
    try { fs.rmSync(userDataDir, { recursive: true, force: true, maxRetries: 5 }); } catch (e) { /* ignore */ }
}
process.exit(ok ? 0 : 1);

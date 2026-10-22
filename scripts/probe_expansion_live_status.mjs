#!/usr/bin/env node
/**
 * 情节拓展页 · 扩写完成后「行状态实时翻转」（2026-10-20）
 *
 * 背景（用户报的问题）：某个章节扩写完成后，前端状态不会自动变 —— 队列里那一行一直停在
 * 「扩写中」，操作列也不出现「预览 / 回滚 / 确认」，必须手动刷新整页才正确。
 *
 * 根因：后端只在整批结束发 `done`，前端靠 `done` 触发 window.location.reload() 才拿到新状态；
 * 中途只有 `expand-section`（标记 GENERATING），**没有任何「这一章存完了」的信号**。
 * 顺带一处矛盾：`pollStatus` 每 5 秒把服务器计数写进徽标，行状态却不动，
 * 于是「5 已扩写」和满屏「扩写中」可以同时挂着。
 *
 * 修法：
 *   1. ExpansionService 每章落库后补发 `[[EXPAND:SAVED:<章>:<字数>]]`，
 *      Controller 映射成 SSE 事件 `chapter-saved`，前端据此当场翻状态 + 更新字数；
 *   2. pollStatus 发现「服务器计数 ≠ 本地行」就 resyncChapters() 补一次章节状态（漏事件的兜底）；
 *   3. done / stopped 不再整页 reload（会丢掉实时日志和勾选），只补状态。
 *
 * 本脚本守护：
 *   T1 初始：队列 4 待扩写 + 1 已扩写，操作列形态各自正确
 *   T2 点「开始批量扩写」后，第 1 章变「扩写中」（转圈、无操作按钮）
 *   T3 ★ 收到 chapter-saved 后**不刷新**就把第 1 章翻成「已扩写」+ 三个操作按钮 + 新字数
 *   T4 第 2 章同样自动翻转；徽标计数同步（2 待扩写 / 3 已扩写）
 *   T5 done 之后页面**没有整页刷新**（探针埋的标记还在、日志还在）
 *   T6 ★ 漏事件的兜底：不经 SSE 直接改后端状态，下一次 5 秒轮询把行补正
 *   T7 卡片头【刷新】按钮：立即补状态、也不整页重载
 *   T8 全程无 JS 运行时异常
 *
 * 用法：node scripts/probe_expansion_live_status.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9393;
const HTTP_PORT = 18913;
const SHOT_DIR = '/tmp/exp-live-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC_ROOTS = [path.join(ROOT, 'src/main/resources/static'), path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 可变的后端状态（模拟一次批量扩写） ==================== */
const TOTAL = 8;
const chapters = Array.from({ length: TOTAL }, (_, i) => {
    const n = i + 1;
    return {
        chapterNumber: n,
        title: '第' + n + '章 山谷里的第一场雨',
        wordCount: 2000 + n * 10,
        // 1~4 待扩写、5 已扩写、6~8 不在队列
        expansionStatus: n <= 4 ? 'PENDING' : (n === 5 ? 'EXPANDED' : null),
        hasContent: true
    };
});
const volumes = [{ volumeNumber: 1, title: '第1卷', chapterNumbers: chapters.map(c => c.chapterNumber) }];
const BOOT = {
    projectId: '1', projectTitle: '探针项目', expansionGuidance: '把场景写厚一点。',
    chaptersPerVolume: 10, volumes, chapters
};
const findCh = (n) => chapters.find(c => c.chapterNumber === n);
let active = false;

/* ==================== SSE 广播 ==================== */
const subs = [];
function emit(name, data) {
    const payload = `event: ${name}\ndata: ${data}\n\n`;
    subs.forEach(res => { try { res.write(payload); } catch (e) { /* 已断开 */ } });
}
async function waitSubscriber(timeout = 4000) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) { if (subs.length) return true; await sleep(60); }
    return false;
}

/** 模拟后端：开局 → 第 1 章 → 第 2 章 → done（每步之间留出手工查看的窗口） */
async function runBatch() {
    active = true;
    if (!await waitSubscriber()) { console.log('    (警告) 等不到 SSE 订阅者'); }
    await sleep(300);

    for (const n of [1, 2]) {
        findCh(n).expansionStatus = 'GENERATING';
        emit('expand-section', `[[EXPAND:CHAPTER:${n}]]`);
        await sleep(700);
        const words = 4000 + n * 200;
        const ch = findCh(n);
        ch.expansionStatus = 'EXPANDED';
        ch.wordCount = words;
        emit('chapter-saved', `[[EXPAND:SAVED:${n}:${words}]]`);
        await sleep(700);
    }
    active = false;
    emit('done', 'complete');
}

/* ==================== 静态资源 ==================== */
const MIME = {
    '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml', '.png': 'image/png', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf'
};
function serveStatic(res, relPath) {
    for (const root of STATIC_ROOTS) {
        const file = path.join(root, relPath);
        if (!file.startsWith(root)) continue;
        let buf = null;
        try { buf = fs.readFileSync(file); } catch (e) { continue; }
        res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
        return res.end(buf);
    }
    res.writeHead(404);
    res.end('');
}
const json = (res, obj) => {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(obj));
};
const statusPayload = () => ({
    active,
    pendingCount: chapters.filter(c => c.expansionStatus === 'PENDING').length,
    expandedCount: chapters.filter(c => c.expansionStatus === 'EXPANDED').length,
    generatingCount: chapters.filter(c => c.expansionStatus === 'GENERATING').length
});

const server = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (pn === '/projects/1/expansion') return serveStatic(res, 'pages/expansion.html');
    if (pn === '/projects/1/expansion/data') return json(res, BOOT);
    if (pn === '/projects/1/expansion/status') return json(res, statusPayload());
    if (pn === '/projects/1/expansion/stream') {
        res.writeHead(200, {
            'Content-Type': 'text/event-stream; charset=utf-8',
            'Cache-Control': 'no-cache', 'Connection': 'keep-alive', 'X-Accel-Buffering': 'no'
        });
        res.write(': connected\n\n');
        subs.push(res);
        req.on('close', () => { const i = subs.indexOf(res); if (i >= 0) subs.splice(i, 1); });
        return;
    }
    if (pn === '/projects/1/expansion/start') {
        json(res, { status: 'ok' });
        runBatch();
        return;
    }
    if (pn === '/projects/1/expansion/stop') {
        active = false;
        json(res, { status: 'ok', resetCount: 0 });
        return;
    }
    if (pn.indexOf('/api/') === 0) return json(res, {});
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'exp-live-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--mute-audio',
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
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}/projects/1/expansion?_probe=` + (++navSeq) });
}
async function clickAt(x, y) {
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none', clickCount: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
}
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
    await sleep(140);
    const pt = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        const r = el.getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    await clickAt(pt.x, pt.y);
}

const START_BTN = 'button[title^="按队列顺序"]';

const waitSettled = () => waitFor(async () => {
    try {
        return (await evalJs(`(() => { const r = document.querySelector('.sc-main');
            const d = r && window.Alpine ? window.Alpine.$data(r) : null; return !!d && d.booting === false; })()`)) === true;
    } catch (e) { return false; }
});

/* 队列快照：每行的状态徽标 / 字数 / 操作按钮 / 转圈，外加头部两个计数徽标与实时日志 */
const SNAP = `(() => {
    const box = document.querySelector('.exp-queue-scroll');
    if (!box) return { missing: true };
    const badgeText = (el) => el ? (el.innerText || '').trim() : '';
    const headBadges = Array.from(document.querySelectorAll('.card-header .badge'))
        .map(badgeText).filter(t => /待扩写|已扩写/.test(t));
    const rows = Array.from(box.querySelectorAll('tbody.exp-chapter-group')).map(g => {
        const tr = g.querySelector('tr');
        const td = tr.children;
        const actions = tr.querySelector('.exp-actions');
        return {
            num: (td[0].innerText || '').trim(),
            words: (td[2].innerText || '').trim(),
            status: badgeText(td[3].querySelector('.badge')),
            spinner: !!actions.querySelector('.spinner-border'),
            btns: Array.from(actions.querySelectorAll('button'))
                .map(b => (b.getAttribute('title') || '').slice(0, 6))
        };
    });
    const logBox = document.querySelector('[x-ref="streamBox"]');
    return {
        missing: false, rows, headBadges,
        pendingBadge: headBadges.find(t => t.indexOf('待扩写') >= 0) || '',
        expandedBadge: headBadges.find(t => t.indexOf('已扩写') >= 0) || '',
        log: logBox ? (logBox.innerText || '') : '',
        mark: window.__probeMark || null,
        active: (() => { const d = window.Alpine && window.Alpine.$data(document.querySelector('.sc-main'));
                         return d ? d.active : null; })()
    };
})()`;

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

    /* ---------- T1 初始形态 ---------- */
    console.log('\n— T1 初始状态 —');
    await goto();
    check('页面 boot 完成', (await waitSettled()) === true);
    await sleep(400);
    let s = await evalJs(SNAP);
    check('队列 5 行（4 待扩写 + 1 已扩写）', s.rows.length === 5, 'n=' + s.rows.length);
    check('第 1 章：待扩写、操作列是【移除】',
        s.rows[0].status === '待扩写' && s.rows[0].btns.length === 1 && s.rows[0].btns[0] === '移出扩写队列',
        JSON.stringify(s.rows[0]));
    check('第 5 章：已扩写、操作列是【预览/回滚/确认】三键',
        s.rows[4].status === '已扩写' && s.rows[4].btns.length === 3,
        JSON.stringify(s.rows[4]));
    check('徽标计数：4 待扩写 / 1 已扩写',
        s.pendingBadge === '4 待扩写' && s.expandedBadge === '1 已扩写',
        s.pendingBadge + ' | ' + s.expandedBadge);
    await shot('live-01-initial');

    /* ---------- T2 开始扩写 → 第 1 章转圈 ---------- */
    console.log('\n— T2 开始批量扩写 —');
    await clickSel(START_BTN);
    /* 埋个标记：整页刷新会把它抹掉，用来证明 done 之后没有 reload */
    await evalJs(`window.__probeMark = 42; true`);
    const sawGenerating = await waitFor(async () => {
        const x = await evalJs(SNAP);
        return x.rows[0].status === '扩写中' && x.rows[0].spinner;
    }, 6000);
    s = await evalJs(SNAP);
    check('第 1 章变「扩写中」且没有操作按钮',
        sawGenerating && s.rows[0].btns.length === 0, JSON.stringify({ status: s.rows[0].status, btns: s.rows[0].btns }));
    check('开始按钮变成【暂停扩写】（active=true）', s.active === true);
    await shot('live-02-generating');

    /* ---------- T3 ★ 单章完成 → 不刷新就翻状态 ---------- */
    console.log('\n— T3 第一章完成后自动翻转（核心） —');
    const flipped1 = await waitFor(async () => {
        const x = await evalJs(SNAP);
        return x.rows[0].status === '已扩写' && x.rows[0].btns.length === 3;
    }, 8000);
    s = await evalJs(SNAP);
    check('【核心】没有手动刷新，第 1 章自动从「扩写中」变「已扩写」', flipped1,
        JSON.stringify(s.rows[0]));
    check('【核心】操作列当场出现【预览/回滚/确认】三键',
        s.rows[0].btns.length === 3 && !s.rows[0].spinner, JSON.stringify(s.rows[0].btns));
    check('字数列同步成扩写后的 4200（不是原来的 2010）', s.rows[0].words === '4200', s.rows[0].words);
    check('徽标计数同步：3 待扩写 / 2 已扩写',
        s.pendingBadge === '3 待扩写' && s.expandedBadge === '2 已扩写',
        s.pendingBadge + ' | ' + s.expandedBadge);
    check('实时日志里出现「第1章 扩写完成（4200 字）」', /第1章 扩写完成（4200 字）/.test(s.log),
        s.log.slice(-80).replace(/\n/g, ' ⏎ '));
    await shot('live-03-first-done');

    /* ---------- T4 第二章同样自动翻转 + done ---------- */
    console.log('\n— T4 第二章 + 整批结束 —');
    const flipped2 = await waitFor(async () => {
        const x = await evalJs(SNAP);
        return x.rows[1].status === '已扩写' && x.active === false;
    }, 10000);
    s = await evalJs(SNAP);
    check('第 2 章也自动翻成「已扩写」（4400 字）',
        flipped2 && s.rows[1].words === '4400' && s.rows[1].btns.length === 3,
        JSON.stringify(s.rows[1]));
    check('done 之后 active 归位（按钮回到【开始批量扩写】）', s.active === false);
    check('徽标收尾：2 待扩写 / 3 已扩写',
        s.pendingBadge === '2 待扩写' && s.expandedBadge === '3 已扩写',
        s.pendingBadge + ' | ' + s.expandedBadge);

    /* ---------- T5 done 不再整页刷新 ---------- */
    console.log('\n— T5 done 不整页刷新 —');
    check('【核心】页面没有被 reload（探针埋的 window 标记还在）', s.mark === 42, 'mark=' + s.mark);
    check('实时日志没被刷掉（还能看到前两章的完成记录）',
        /第1章 扩写完成/.test(s.log) && /第2章 扩写完成/.test(s.log),
        s.log.slice(0, 60).replace(/\n/g, ' ⏎ '));
    await shot('live-04-done');

    /* ---------- T6 漏事件的兜底：轮询补状态 ---------- */
    console.log('\n— T6 漏了 SSE 也能补上（5 秒轮询兜底） —');
    /* 模拟「事件丢了」：只在后端改状态，不发任何 SSE */
    const c3 = findCh(3);
    c3.expansionStatus = 'EXPANDED';
    c3.wordCount = 6100;
    const healed = await waitFor(async () => {
        const x = await evalJs(SNAP);
        return x.rows[2].status === '已扩写';
    }, 9000, 200);
    s = await evalJs(SNAP);
    check('【核心】不发事件、只改后端，轮询发现计数对不上后自动补正第 3 章',
        healed && s.rows[2].words === '6100', JSON.stringify(s.rows[2]));
    check('补正后徽标与表格一致（1 待扩写 / 4 已扩写）',
        s.pendingBadge === '1 待扩写' && s.expandedBadge === '4 已扩写',
        s.pendingBadge + ' | ' + s.expandedBadge);
    check('补状态不会误伤预览面板（没打开过就不该有）',
        (await evalJs(`document.querySelectorAll('.exp-compare-row').length`)) >= 0 &&
        (await evalJs(`Array.from(document.querySelectorAll('.exp-compare-row'))
            .filter(el => el.getClientRects().length > 0).length`)) === 0);
    await shot('live-05-poll-healed');

    /* ---------- T7 卡片头的【刷新】按钮：只补状态、不整页重载 ---------- */
    console.log('\n— T7 手动刷新按钮 —');
    const c4 = findCh(4);
    c4.expansionStatus = 'EXPANDED';
    c4.wordCount = 7200;
    await clickSel('button[title="重新拉取任务状态与章节"]');
    const refreshed = await waitFor(async () => {
        const x = await evalJs(SNAP);
        return x.rows[3].status === '已扩写';
    }, 3000, 100);
    s = await evalJs(SNAP);
    check('点【刷新】后第 4 章当场变「已扩写」（不用等 5 秒轮询）',
        refreshed && s.rows[3].words === '7200', JSON.stringify(s.rows[3]));
    check('【刷新】也不再整页重载（标记仍在）', s.mark === 42, 'mark=' + s.mark);
    await shot('live-06-manual-refresh');

    /* ---------- T8 ---------- */
    console.log('\n— T8 全程 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));

    const failed = results.filter(r => !r.ok);
    console.log(`\n===== ${results.length - failed.length}/${results.length} 通过 =====`);
    if (failed.length) {
        console.log('失败项：');
        failed.forEach(f => console.log('  - ' + f.name + (f.extra ? '  [' + f.extra + ']' : '')));
    }
    return failed.length === 0;
}

let code = 1;
try {
    code = (await main()) ? 0 : 1;
} catch (e) {
    console.error('探针异常: ' + (e && e.stack || e));
    code = 1;
} finally {
    try { ws && ws.close(); } catch (e) {}
    chrome.kill('SIGKILL');
    try { server.close(); } catch (e) {}
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) {}
}
process.exit(code);

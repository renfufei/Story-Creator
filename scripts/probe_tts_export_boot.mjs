#!/usr/bin/env node
/**
 * /tts-export TTS 语音导出页：**引导数据异步化 + 骨架屏**（2026-10-20）。
 *
 * 原来页面顶部有一段同步 XHR 拿 /tts-export/data（项目列表 + 预选项目）：
 * 浏览器要停在 HTML 解析期等往返，JS 没开始跑、DOM 也没解析完 —— 首屏白屏。
 * 现在改成 fetch + 骨架屏：任务表先出骨架行，数据到了再由 init() 铺上来。
 *
 * 本脚本逐条守护：
 *   P0 【核心】把 /tts-export/data 挂住：DOM 仍要 readyState=complete、骨架要出来，
 *      不能先闪「还没有导出任务」这种假空态，也不能先闪空表头
 *   P1 【核心】放行数据：骨架退场、任务行/项目列表全部填对，ffmpeg 状态也落到界面
 *   P2 新增导出任务弹层：向导里的项目卡片来自异步引导数据（异步之后仍可用）
 *   P3 【核心】接口 500：页面不崩，退化成空项目列表并给空态
 *   P4 390px：骨架态/数据态都无横向溢出
 *   P5 运行期无 JS 异常；引导数据每次加载只请求一次
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_tts_export_boot.mjs   # 1889 临时实例
 *   node scripts/probe_tts_export_boot.mjs                               # 默认 1888
 * 环境变量：TTS_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.TTS_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9372;
const SHOT_DIR = '/tmp/tts-export-boot-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tts-export-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let ws, msgId = 0;
const pending = new Map();
const runtimeErrors = [];
const consoleErrors = [];

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

async function waitFor(label, fn, timeout = 8000, step = 120) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        if (await fn()) return true;
        await sleep(step);
    }
    return false;
}

/* ==================== 假数据 ==================== */
const DATA = {
    projects: [
        { id: 1, title: '导出探针项目', chapterCount: 5, wordCount: 1234 },
        { id: 2, title: '另一个项目', chapterCount: 3, wordCount: 800 }
    ],
    preselectedProjectId: null
};

/* 两个任务都用「非活跃」状态：页内有 2s 轮询，RUNNING/PENDING 会不停重拉任务列表，
   把请求计数搅乱（本次要数的是引导数据） */
const TASKS = [
    {
        id: 11, projectId: 1, projectName: '导出探针项目', voice: 'alloy', speed: 1.0,
        status: 'COMPLETED', progressChapter: 5, progressTotalChapters: 5, errorMessage: null
    },
    {
        id: 12, projectId: 2, projectName: '另一个项目', voice: 'nova', speed: 1.2,
        status: 'FAILED', progressChapter: 1, progressTotalChapters: 3, errorMessage: '合成失败'
    }
];

/* ==================== 网络层拦截 ==================== */
let holdBootstrap = false;
let failBootstrap = false;
let heldBootstrap = null;
let bootstrapRequests = 0;

const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');
function fulfil(requestId, code, contentType, body) {
    return send('Fetch.fulfillRequest', {
        requestId,
        responseCode: code,
        responseHeaders: [{ name: 'Content-Type', value: contentType }],
        body: b64(body)
    });
}
const fulfilJson = (requestId, obj) =>
    fulfil(requestId, 200, 'application/json; charset=utf-8', JSON.stringify(obj));

async function handlePaused(p) {
    const pn = new URL(p.request.url).pathname;

    if (pn === '/tts-export/data') {
        bootstrapRequests++;
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        if (failBootstrap) {
            return fulfil(p.requestId, 500, 'application/json; charset=utf-8',
                JSON.stringify({ error: 'tts-export/data 炸了' }));
        }
        return fulfilJson(p.requestId, DATA);
    }
    if (pn === '/api/tts-export/tasks') return fulfilJson(p.requestId, TASKS);
    // 别真去 spawn 一次 `ffmpeg -version`
    if (pn === '/api/tts-export/ffmpeg-status') {
        return fulfilJson(p.requestId, { available: false, version: null });
    }
    if (/^\/projects\/\d+\/tts\/configs$/.test(pn)) return fulfilJson(p.requestId, []);
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

async function releaseBootstrap() {
    if (!heldBootstrap) return false;
    const id = heldBootstrap;
    heldBootstrap = null;
    holdBootstrap = false;
    await fulfilJson(id, DATA);
    return true;
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const host = document.querySelector('[x-data]') || document.body;
    const data = window.Alpine ? Alpine.$data(host) : null;
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const skels = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    const rows = Array.from(document.querySelectorAll('table.table tbody tr')).filter(vis);
    const empty = document.querySelector('.card-body .text-center.text-muted.py-5');
    const table = document.querySelector('table.table');
    return {
        ready: !!(data && window.Alpine),
        readyState: document.readyState,
        booting: data ? data.booting : null,
        taskCount: data ? data.tasks.length : -1,
        projectCount: data ? data.projects.length : -1,
        ffmpegAvailable: data ? data.ffmpegAvailable : null,
        skelVisible: skels.length,
        rowVisible: rows.length,
        tableVisible: vis(table),
        emptyVisible: vis(empty),
        bodyHasProject: (document.body.innerText || '').indexOf('导出探针项目') >= 0,
        wizardCards: Array.from(document.querySelectorAll('.project-picker-item')).filter(vis).length,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;
const snap = async (ms = 250) => { if (ms) await sleep(ms); return evalJs(SNAP); };

async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

let navSeq = 0;
async function goto(query = '', width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    /* 加一个纯查询串当缓存钥匙：保证每次都是真·新加载（页面路由只看 pathname） */
    await send('Page.navigate', { url: BASE + '/tts-export?_probe=' + (++navSeq) + query });
}

async function waitMounted() {
    return waitFor('页面框架就绪', async () => {
        try {
            return await evalJs(`(() => !!(window.Alpine && document.querySelector('[x-data]')
                && Alpine.$data(document.querySelector('[x-data]'))))()`);
        } catch (e) { return false; }
    }, 20000, 120);
}

async function waitBooted() {
    return waitFor('booting 结束', async () => {
        const x = await evalJs(SNAP);
        return x.booting === false;
    }, 10000, 120);
}

/* ==================== 主流程 ==================== */
async function main() {
    let ver = null;
    for (let i = 0; i < 80; i++) {
        try {
            const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
            if (r.ok) { ver = await r.json(); break; }
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    if (!ver) throw new Error('Chrome CDP 端点未就绪');
    console.log('Chrome: ' + ver['Browser']);

    const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json();
    ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = (ev) => {
        const m = JSON.parse(ev.data);
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id);
            pending.delete(m.id);
            m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
            return;
        }
        if (m.method === 'Runtime.exceptionThrown') {
            runtimeErrors.push(m.params.exceptionDetails.exception?.description
                || m.params.exceptionDetails.text);
        } else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
            consoleErrors.push((m.params.args || []).map(a => a.value || a.description || '').join(' '));
        } else if (m.method === 'Fetch.requestPaused') {
            handlePaused(m.params).catch(e => consoleErrors.push('Fetch 拦截失败: ' + e.message));
        }
    };

    await send('Runtime.enable');
    await send('Page.enable');
    await send('Fetch.enable', {
        patterns: [
            { urlPattern: '*/tts-export/data*', requestStage: 'Request' },
            { urlPattern: '*/api/tts-export/tasks*', requestStage: 'Request' },
            { urlPattern: '*/api/tts-export/ffmpeg-status*', requestStage: 'Request' },
            { urlPattern: '*/tts/configs*', requestStage: 'Request' }
        ]
    });

    /* ---------- P0 挂住引导数据：首屏不能被卡住 ---------- */
    console.log('\n— P0 把引导数据挂住：首屏仍要出来 —');
    holdBootstrap = true;
    await goto();
    await waitMounted();
    let s = await snap(500);
    check('【核心】数据挂着时 DOM 已 readyState=complete（同步 XHR 会一直 loading）',
        s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】组件已挂载且 booting=true', s.ready === true && s.booting === true,
        `ready=${s.ready} booting=${s.booting}`);
    check('【核心】骨架屏已渲染（不是白屏）', s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】还没渲染任何任务行', s.rowVisible === 0, 'rows=' + s.rowVisible);
    check('【核心】没有先闪「还没有导出任务」', !s.emptyVisible);
    check('没先闪一张空表头', !s.tableVisible);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('01-skeleton');

    /* ---------- P1 放行数据：骨架退场、任务与项目填对 ---------- */
    console.log('\n— P1 放行数据：骨架退场、任务与项目填对 —');
    check('引导数据请求确实被挂住过（说明真的是异步发的）', await releaseBootstrap());
    check('【核心】数据到了 booting 自动收掉', (await waitBooted()) === true);
    s = await snap(300);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】2 个任务行全部渲染', s.rowVisible === 2, 'rows=' + s.rowVisible);
    check('任务行里能看到项目名', s.bodyHasProject === true);
    check('任务列表已接管（表格显示、空态隐藏）', s.tableVisible === true && s.emptyVisible === false,
        `table=${s.tableVisible} empty=${s.emptyVisible}`);
    check('【核心】向导要用的项目列表也铺好了', s.projectCount === 2, 'projects=' + s.projectCount);
    check('ffmpeg 状态落到界面（探测结果 false）', s.ffmpegAvailable === false);
    await shot('02-loaded');

    /* ---------- P2 向导：项目卡片来自异步引导数据 ---------- */
    console.log('\n— P2 新增导出任务弹层：项目卡片来自引导数据 —');
    await evalJs(`(() => {
        const btn = Array.from(document.querySelectorAll('button'))
            .find(b => (b.innerText || '').indexOf('新增导出任务') >= 0);
        if (btn) btn.click();
        return !!btn;
    })()`);
    s = await snap(600);
    check('【核心】向导里能列出 2 个项目卡片', s.wizardCards === 2, 'cards=' + s.wizardCards);
    await shot('03-wizard');
    await evalJs(`(() => {
        const el = document.getElementById('wizardModal');
        const m = el && window.bootstrap ? bootstrap.Modal.getInstance(el) : null;
        if (m) m.hide();
        return true;
    })()`);
    await sleep(400);

    /* ---------- P3 接口 500：退化成空列表 ---------- */
    console.log('\n— P3 引导数据 500：页面退化但可用 —');
    const errBefore = runtimeErrors.length;
    const consoleBefore = consoleErrors.length;
    failBootstrap = true;
    await goto();
    await waitMounted();
    check('【核心】接口 500 时 booting 仍会收掉', (await waitBooted()) === true);
    s = await snap(250);
    check('【核心】页面没崩：任务列表照常渲染，不留骨架', s.ready === true && s.skelVisible === 0
        && s.rowVisible === 2, `ready=${s.ready} skel=${s.skelVisible} rows=${s.rowVisible}`);
    /* 500 只打挂引导数据（项目列表），任务列表是另一个接口，仍应正常 */
    check('引导数据失败只影响项目列表：退化成空数组', s.projectCount === 0, 'projects=' + s.projectCount);
    check('任务还在，所以不显示「还没有导出任务」', s.emptyVisible === false);
    check('【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    check('失败只打了一条兜底日志', consoleErrors.length - consoleBefore <= 1,
        consoleErrors.slice(consoleBefore).join(' | '));
    await shot('04-degraded');

    /* ---------- P4 390px ---------- */
    console.log('\n— P4 390px 窄屏 —');
    failBootstrap = false;
    holdBootstrap = true;
    await goto('', 390, 780);
    await waitMounted();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('05-mobile-skeleton');
    await releaseBootstrap();
    await waitBooted();
    s = await snap(300);
    check('窄屏数据态无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏任务行正常渲染', s.rowVisible === 2, 'rows=' + s.rowVisible);
    await shot('06-mobile-loaded');

    /* ---------- P5 运行期 ---------- */
    console.log('\n— P5 运行期异常与请求次数 —');
    /* P0+放行算一次、P3、P4 各一次 ⇒ 3 次页面加载，各请求一次 */
    check('引导数据每次加载只请求一次（3 次页面加载）', bootstrapRequests === 3,
        'requests=' + bootstrapRequests);
    check('全程无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));

    const failed = results.filter(x => !x.ok).length;
    console.log(`\n===== ${results.length - failed}/${results.length} 通过 =====`);
    if (failed) console.log('失败项：\n' + results.filter(x => !x.ok).map(x => ' - ' + x.name).join('\n'));

    try { await send('Browser.close'); } catch (e) { /* ignore */ }
    try { chrome.kill(); } catch (e) { /* ignore */ }
    process.exit(failed ? 1 : 0);
}

main().catch(async (e) => {
    console.error('PROBE FAILED: ' + e.message);
    try { chrome.kill(); } catch (err) { /* ignore */ }
    process.exit(2);
});

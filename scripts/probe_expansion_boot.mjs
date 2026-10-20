#!/usr/bin/env node
/**
 * /projects/{id}/expansion 扩写页：**引导数据异步化 + 骨架屏**（2026-10-20）。
 *
 * 背景：这一页原来用同步 XHR 拉 `/expansion/data`。同步 XHR 会让浏览器停下来等一个
 * 网络往返——HTML 解析、样式计算、首次绘制全被卡住，接口慢就是一片白屏；而且同步 XHR
 * 已被浏览器标记为弃用。现在改成 fetch + 骨架屏，数据到了再由 Alpine 的 init 铺上去。
 *
 * 本脚本逐条守护（钩子全是「故意把接口挂住」，比肉眼看更快更准）：
 *   P0 【核心】把 /expansion/data 挂住：DOM 仍要 readyState=complete、骨架要出现，
 *      且不能先闪「队列为空」/「0 待扩写」/「暂无章节」这类假结论
 *   P1 【核心】放行数据：骨架退场、队列行/计数/指导正文/项目名都填对
 *   P2 「全部章节」按分卷渲染，空态不出现
 *   P3 【核心】接口 500：页面不崩、不白屏，退化成空队列并给空态提示
 *   P4 390px：骨架与数据两态都不产生横向溢出
 *   P5 运行期无 JS 异常；引导数据只请求一次（没有重试风暴）
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_expansion_boot.mjs   # 1889 临时实例
 *   node scripts/probe_expansion_boot.mjs                               # 默认 1888
 * 环境变量：EXP_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.EXP_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const PAGE = BASE + '/projects/1/expansion';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9361;
const SHOT_DIR = '/tmp/exp-boot-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'exp-boot-probe-'));
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
    projectId: 1,
    projectTitle: '骨架探针项目',
    expansionGuidance: '加强环境描写与心理活动，补充对话细节与伏笔。',
    chapters: [
        { chapterNumber: 1, title: '第一章 雨夜', wordCount: 1200, expansionStatus: 'EXPANDED', content: 'Y' },
        { chapterNumber: 2, title: '第二章 山道', wordCount: 900, expansionStatus: 'EXPANDED', content: 'Y' },
        { chapterNumber: 3, title: '第三章 剑意', wordCount: 800, expansionStatus: 'PENDING', content: 'Y' },
        { chapterNumber: 4, title: '第四章 未完', wordCount: 0, expansionStatus: null, content: null },
        { chapterNumber: 5, title: '第五章 长夜', wordCount: 3000, expansionStatus: 'EXPANDED', content: 'Y' }
    ],
    chaptersPerVolume: 10,
    volumes: [{ volumeNumber: 1, title: '第一卷', chapterNumbers: [1, 2, 3, 4, 5] }]
};
const STATUS = { active: false, pendingCount: 1, expandedCount: 3 };

/* ==================== 网络层拦截 ==================== */
let holdBootstrap = false;      // 挂住 /expansion/data
let failBootstrap = false;      // 用 500 顶回去
let heldBootstrap = null;
let dataRequestCount = 0;

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
    const u = new URL(p.request.url);
    const pn = u.pathname;

    if (pn === '/projects/1/expansion/data') {
        dataRequestCount++;
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        if (failBootstrap) {
            return fulfil(p.requestId, 500, 'application/json; charset=utf-8',
                JSON.stringify({ error: '引导数据炸了' }));
        }
        return fulfilJson(p.requestId, DATA);
    }
    if (pn === '/projects/1/expansion/status') {
        return fulfilJson(p.requestId, STATUS);
    }
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
    const host = document.querySelector('[x-data]');
    const data = host && window.Alpine ? Alpine.$data(host) : null;
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden';
    const txt = (el) => el ? (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim() : '';
    const skels = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    const rows = Array.from(document.querySelectorAll('.exp-chapter-group'));
    const emptyCells = Array.from(document.querySelectorAll('td')).filter(td => txt(td).indexOf('队列为空') >= 0);
    const badgeWarn = Array.from(document.querySelectorAll('.badge')).filter(b => txt(b).indexOf('待扩写') >= 0);
    const headerTitle = document.querySelector('.sc-main .text-muted.small span:nth-child(2)');
    return {
        ready: !!(data && window.Alpine),
        readyState: document.readyState,
        booting: data ? data.booting : null,
        projectTitle: data ? data.projectTitle : null,
        guidance: data ? data.guidance : null,
        chapterCount: data ? data.allChapters.length : -1,
        pendingCount: data ? data.pendingCount : -1,
        expandedCount: data ? data.expandedCount : -1,
        volumeGroups: data ? data.volumeGroups.length : -1,
        skelVisible: skels.length,
        rowCount: rows.length,
        emptyVisible: emptyCells.some(vis),
        badgeWarnVisible: badgeWarn.some(vis),
        headerText: txt(document.querySelector('.sc-main')),
        emptyChapterVisible: Array.from(document.querySelectorAll('.card .p-4.text-center.text-muted'))
            .some(el => vis(el) && txt(el).indexOf('暂无章节') >= 0),
        volumeDomCount: document.querySelectorAll('.volume-group').length,
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

async function goto(width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: PAGE });
}

/** 页面结构是否已挂上 Alpine（骨架阶段就要 true） */
async function waitMounted() {
    return waitFor('页面框架就绪', async () => {
        try {
            return await evalJs(`(() => !!(window.Alpine && document.querySelector('.sc-main')
                && Alpine.$data(document.querySelector('[x-data]'))))()`);
        } catch (e) { return false; }
    }, 20000, 120);
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
            { urlPattern: '*/expansion/data*', requestStage: 'Request' },
            { urlPattern: '*/expansion/status*', requestStage: 'Request' }
        ]
    });

    /* ---------- P0 挂住接口：首屏不能被卡住 ---------- */
    console.log('\n— P0 把引导数据挂住：首屏仍要出来 —');
    holdBootstrap = true;
    await goto();
    await waitMounted();
    let s = await snap(500);
    check('【核心】数据挂着时 DOM 已经 readyState=complete（同步 XHR 会一直 loading）',
        s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】页面框架已挂载，booting=true', s.ready === true && s.booting === true,
        `ready=${s.ready} booting=${s.booting}`);
    check('【核心】骨架屏已渲染（不是白屏）', s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】队列区是骨架行，还没出现任何章节行', s.rowCount === 0, 'rows=' + s.rowCount);
    check('【核心】没有先闪「队列为空」', !s.emptyVisible);
    check('【核心】没有先闪「0 待扩写」这种假计数', !s.badgeWarnVisible);
    check('【核心】「全部章节」没有先闪「暂无章节」', !s.emptyChapterVisible);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('01-skeleton-holding');

    /* ---------- P1 放行：骨架退场、数据填对 ---------- */
    console.log('\n— P1 放行数据：骨架退场、内容填对 —');
    const released = await releaseBootstrap();
    check('引导数据请求确实被挂住过（说明页面真的是异步发的）', released === true);
    const filled = await waitFor('数据铺完', async () => {
        const x = await evalJs(SNAP);
        return x.booting === false;
    }, 8000, 120);
    check('【核心】数据到了 booting 自动收掉', filled === true);
    s = await snap(150);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】队列渲染出 4 行（3 已扩写 + 1 待扩写）', s.rowCount === 4, 'rows=' + s.rowCount);
    check('计数按数据填写', s.pendingCount === 1 && s.expandedCount === 3,
        `pending=${s.pendingCount} expanded=${s.expandedCount}`);
    check('项目名已填进页头', s.projectTitle === DATA.projectTitle
        && s.headerText.indexOf(DATA.projectTitle) >= 0, s.projectTitle);
    check('扩写指导正文已填', s.guidance === DATA.expansionGuidance);
    check('空态不再出现', !s.emptyVisible);
    await shot('02-loaded');

    /* ---------- P2 全部章节按分卷 ---------- */
    console.log('\n— P2 「全部章节」按分卷渲染 —');
    check('章节对象都进来了', s.chapterCount === 5, 'chapters=' + s.chapterCount);
    check('分卷分组渲染到 DOM', s.volumeDomCount >= 1, 'volumes=' + s.volumeDomCount);
    check('没有「暂无章节」空态', !s.emptyChapterVisible);

    /* ---------- P3 接口 500：退化成空队列，不崩 ---------- */
    console.log('\n— P3 引导数据 500：页面退化但可用 —');
    const errBefore = runtimeErrors.length;
    const consoleBefore = consoleErrors.length;
    failBootstrap = true;
    holdBootstrap = false;
    await goto();
    await waitMounted();
    const degraded = await waitFor('失败兜底生效', async () => {
        const x = await evalJs(SNAP);
        return x.booting === false;
    }, 8000, 120);
    check('【核心】接口 500 时 booting 仍会收掉（不会永远转圈）', degraded === true);
    s = await snap(200);
    check('【核心】页面没崩：页头与卡片仍在', s.ready === true && s.headerText.length > 0);
    check('退化成空队列并明确提示', s.rowCount === 0 && s.emptyVisible === true,
        `rows=${s.rowCount} empty=${s.emptyVisible}`);
    check('【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    check('失败只打了引导日志（console.error 仅一条兜底说明）',
        consoleErrors.length - consoleBefore <= 1,
        consoleErrors.slice(consoleBefore).join(' | '));
    await shot('03-degraded');

    /* ---------- P4 390px 两态都不横溢 ---------- */
    console.log('\n— P4 390px 窄屏 —');
    failBootstrap = false;
    holdBootstrap = true;
    await goto(390, 780);
    await waitMounted();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏骨架可见', s.skelVisible > 0);
    await shot('04-mobile-skeleton');
    await releaseBootstrap();
    await waitFor('窄屏数据铺完', async () => {
        const x = await evalJs(SNAP);
        return x.booting === false;
    }, 8000, 120);
    s = await snap(250);
    check('窄屏数据态也无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏队列行正常渲染', s.rowCount === 4, 'rows=' + s.rowCount);
    await shot('05-mobile-loaded');

    /* ---------- P5 运行期 ---------- */
    console.log('\n— P5 运行期异常与请求次数 —');
    check('引导数据只请求了一次/每次加载（没有重试风暴）', dataRequestCount === 3,
        'requests=' + dataRequestCount);
    check('全程无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));

    const failed = results.filter(r => !r.ok).length;
    console.log(`\n===== ${results.length - failed}/${results.length} 通过 =====`);
    if (failed) console.log('失败项：\n' + results.filter(r => !r.ok).map(r => ' - ' + r.name).join('\n'));

    try { await send('Browser.close'); } catch (e) { /* ignore */ }
    try { chrome.kill(); } catch (e) { /* ignore */ }
    process.exit(failed ? 1 : 0);
}

main().catch(async (e) => {
    console.error('PROBE FAILED: ' + e.message);
    try { chrome.kill(); } catch (err) { /* ignore */ }
    process.exit(2);
});

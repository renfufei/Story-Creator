#!/usr/bin/env node
/**
 * /projects/{id}/inspect/chapters/{num} 章节透视页：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 * 页面原来在 body 里跑同步 XHR 拿 /inspect/chapters/{num}/data：浏览器停在解析期等往返，
 * 侧栏与正文全空（白屏）。现在先盖一层骨架，数据到了由 Alpine 的 boot()（x-init）铺上来，
 * 并自动打开第一个可用 tab（tab 可用性来自 fieldAvail）。
 *
 * 本脚本逐条守护：
 *   P0 【核心】挂住数据：DOM 仍要 readyState=complete、骨架盖住、标题还空着
 *   P1 【核心】放行数据：骨架退场、项目名/章名/目录/首个 tab 正文都填对
 *   P2 【核心】接口 500：页面不崩，骨架收掉
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 运行期无 JS 异常；引导数据每次加载只请求一次
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_inspect_chapter_boot.mjs
 *   node scripts/probe_inspect_chapter_boot.mjs      # 默认 1888
 * 环境变量：INSPECT_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.INSPECT_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9379;
const SHOT_DIR = '/tmp/inspect-chapter-boot-shots';
const PID = 1;
const NUM = 1;

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'inspect-chapter-probe-'));
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
const CHAPTER_DATA = {
    projectId: PID,
    projectTitle: '章节透视探针',
    chapterNum: NUM,
    chapterTitle: '雨夜',
    prevNum: null,
    nextNum: 2,
    fieldAvail: {
        outlineSummary: true, writingBriefing: false, eventPlan: false,
        content: true, contentDraft: false, contentSummary: false, characterStates: false
    },
    chapterMetas: [
        { number: 1, title: '雨夜', volumeTitle: '第一卷' },
        { number: 2, title: '山道', volumeTitle: '第一卷' },
        { number: 3, title: '旧人', volumeTitle: null }
    ]
};

const FIELD_BODY = '章节正文样例：雨下了一整夜，山道泥泞。';

/* ==================== 网络层拦截 ==================== */
let holdBootstrap = false;
let failBootstrap = false;
let heldBootstrap = null;
let bootstrapRequests = 0;
let fieldRequests = 0;

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

const BOOT_PATH = `/projects/${PID}/inspect/chapters/${NUM}/data`;

async function handlePaused(p) {
    const pn = new URL(p.request.url).pathname;
    if (pn === BOOT_PATH) {
        bootstrapRequests++;
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        if (failBootstrap) {
            return fulfil(p.requestId, 500, 'application/json; charset=utf-8',
                JSON.stringify({ error: 'chapter data 炸了' }));
        }
        return fulfilJson(p.requestId, CHAPTER_DATA);
    }
    if (pn.indexOf(`/projects/${PID}/inspect/chapters/${NUM}/field/`) === 0) {
        fieldRequests++;
        return fulfilJson(p.requestId, { content: FIELD_BODY, type: 'text' });
    }
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

async function releaseBootstrap() {
    if (!heldBootstrap) return false;
    const id = heldBootstrap;
    heldBootstrap = null;
    holdBootstrap = false;
    await fulfilJson(id, CHAPTER_DATA);
    return true;
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const host = document.body;
    const data = window.Alpine ? Alpine.$data(host) : null;
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const skels = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    const toc = Array.from(document.querySelectorAll('.inspect-sidebar .toc-link')).filter(vis);
    const text = (document.body.innerText || '');
    return {
        ready: !!(data && window.Alpine),
        readyState: document.readyState,
        booting: data ? data.booting : null,
        pid: data ? data.projectId : null,
        projectTitle: data ? data.projectTitle : null,
        chapterTitle: data ? data.chapterTitle : null,
        activeTab: data ? data.activeTab : null,
        metaCount: data ? (data.chapterMetas || []).length : -1,
        cacheKeys: data ? Object.keys(data.cache || {}) : [],
        skelVisible: skels.length,
        tocCount: toc.length,
        bodyHasTitle: text.indexOf('章节透视探针') >= 0,
        bodyHasChapterTitle: text.indexOf('雨夜') >= 0,
        bodyHasField: text.indexOf('章节正文样例') >= 0,
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
async function goto(width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', {
        url: BASE + '/projects/' + PID + '/inspect/chapters/' + NUM + '?_probe=' + (++navSeq)
    });
}

async function waitMounted() {
    return waitFor('页面框架就绪', async () => {
        try {
            return await evalJs(`(() => !!(window.Alpine && document.body && Alpine.$data(document.body)))()`);
        } catch (e) { return false; }
    }, 20000, 120);
}

async function waitBooted() {
    return waitFor('booting 结束', async () => {
        const x = await evalJs(SNAP);
        return x.booting === false;
    }, 12000, 120);
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
            { urlPattern: '*/inspect/chapters/*/data*', requestStage: 'Request' },
            { urlPattern: '*/inspect/chapters/*/field/*', requestStage: 'Request' }
        ]
    });

    /* ---------- P0 挂住引导数据 ---------- */
    console.log('\n— P0 把引导数据挂住：首屏仍要出来 —');
    holdBootstrap = true;
    await goto();
    await waitMounted();
    let s = await snap(500);
    check('【核心】数据挂着时 DOM 已 readyState=complete（同步 XHR 会一直 loading）',
        s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】组件已挂载且 booting=true', s.ready === true && s.booting === true,
        `ready=${s.ready} booting=${s.booting}`);
    check('【核心】骨架已盖住页面（不是白屏）', s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】标题与目录还空着（骨架期间不渲染半截数据）',
        s.projectTitle === '' && s.metaCount === 0 && s.tocCount === 0,
        `title=${s.projectTitle} metas=${s.metaCount} toc=${s.tocCount}`);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('01-skeleton');

    /* ---------- P1 放行数据 ---------- */
    console.log('\n— P1 放行数据：骨架退场、首个 tab 正文填对 —');
    check('引导数据请求确实被挂住过（说明真的是异步发的）', await releaseBootstrap());
    check('【核心】数据到了 booting 自动收掉', (await waitBooted()) === true);
    s = await snap(500);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】项目名与章名已填', s.projectTitle === '章节透视探针'
        && s.chapterTitle === '雨夜' && s.bodyHasTitle === true, `${s.projectTitle} / ${s.chapterTitle}`);
    check('【核心】侧栏目录渲染出 3 章', s.metaCount === 3 && s.tocCount === 3,
        `metas=${s.metaCount} toc=${s.tocCount}`);
    check('【核心】自动打开第一个可用 tab 并加载正文',
        s.activeTab === 'outlineSummary' && s.bodyHasField === true,
        `tab=${s.activeTab} hasField=${s.bodyHasField}`);
    check('字段内容确实请求过一次', fieldRequests === 1, 'field=' + fieldRequests);
    await shot('02-loaded');

    /* ---------- P2 接口 500 ---------- */
    console.log('\n— P2 引导数据 500：页面退化但可用 —');
    const errBefore = runtimeErrors.length;
    const consoleBefore = consoleErrors.length;
    failBootstrap = true;
    await goto();
    await waitMounted();
    check('【核心】接口 500 时 booting 仍会收掉', (await waitBooted()) === true);
    s = await snap(300);
    check('【核心】页面没崩：骨架收掉、框架还在', s.ready === true && s.skelVisible === 0,
        'skel=' + s.skelVisible);
    check('失败时标题退化成空', s.projectTitle === '' && s.metaCount === 0,
        `title=${s.projectTitle} metas=${s.metaCount}`);
    check('【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    check('失败只打了一条兜底日志', consoleErrors.length - consoleBefore <= 1,
        consoleErrors.slice(consoleBefore).join(' | '));
    await shot('03-degraded');

    /* ---------- P3 390px ---------- */
    console.log('\n— P3 390px 窄屏 —');
    failBootstrap = false;
    holdBootstrap = true;
    await goto(390, 780);
    await waitMounted();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('04-mobile-skeleton');
    await releaseBootstrap();
    await waitBooted();
    s = await snap(300);
    check('窄屏数据态无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('05-mobile-loaded');

    /* ---------- P4 运行期 ---------- */
    console.log('\n— P4 运行期异常与请求次数 —');
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

#!/usr/bin/env node
/**
 * /projects/{id}/workflow/polishing 分步创作页（6 步页与枢纽页的代表）：
 * **引导数据异步化 + 骨架屏**（2026-10-20）。
 *
 * 原来 7 个 workflow 页面各自在页面顶部跑一段同步 XHR 拿 /workflow/{step}/data：
 * 浏览器停在 HTML 解析期等往返，JS 没开始跑、DOM 也没解析完 → 白屏。
 * 现在统一走 /js/workflow/core.js 的 loadWorkflowData()，由 workflowCoreMixin().init()
 * await 之后 applyBootstrap() 铺数据，期间显示骨架。
 *
 * 本脚本逐条守护：
 *   P0 【核心】把引导数据挂住：DOM 仍要 readyState=complete、骨架要出来、正文区不可见
 *   P1 【核心】放行数据：骨架退场、项目名/步骤名/创作指导/章节列表全部填对
 *   P2 【核心】接口 500：页面不崩，骨架收掉（退化成空步骤）
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 运行期无 JS 异常；引导数据每次加载只请求一次
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_workflow_boot.mjs
 *   node scripts/probe_workflow_boot.mjs      # 默认 1888
 * 环境变量：WORKFLOW_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.WORKFLOW_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9376;
const SHOT_DIR = '/tmp/workflow-boot-shots';
const PID = 1;
const STEP = 'polishing';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'workflow-probe-'));
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
const WF_DATA = {
    projectId: PID,
    projectTitle: '润色探针项目',
    currentStep: 'POLISHING',
    currentStepOrder: 5,
    viewStepDisplayName: '润色修改',
    genreDisplayName: '玄幻',
    projectCurrentStep: 'POLISHING',
    projectCurrentStepOrder: 5,
    isCurrentStep: true,
    hasNextStep: true,
    isLastStep: false,
    stepConfirmed: false,
    totalChapters: 5,
    chapterWordCount: 2000,
    chapterWordCountMin: 1000,
    chapterWordCountMax: 4000,
    stepGuidance: '润色时保持冷峻笔调，多用短句。',
    currentContent: '这是当前步骤的正文。',
    modelConfigs: [{ id: 1, name: '默认模型' }],
    stepModelConfigId: 1,
    autoMode: false,
    autoRunStepConfigs: {},
    autoRunStrategy: 'DEFAULT',
    characterStateDims: []
};

const CHAPTERS = [1, 2, 3, 4, 5].map(n => ({
    chapterNumber: n,
    title: '第' + n + '章标题',
    wordCount: 1800 + n,
    status: 'CONFIRMED',
    polishStatus: n === 1 ? 'CONFIRMED' : 'PENDING',
    polishNote: n === 1 ? '' : '待润色'
}));

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

const BOOTSTRAP_PATH = `/projects/${PID}/workflow/${STEP}/data`;

async function handlePaused(p) {
    const pn = new URL(p.request.url).pathname;
    if (pn === BOOTSTRAP_PATH) {
        bootstrapRequests++;
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        if (failBootstrap) {
            return fulfil(p.requestId, 500, 'application/json; charset=utf-8',
                JSON.stringify({ error: 'workflow data 炸了' }));
        }
        return fulfilJson(p.requestId, WF_DATA);
    }
    // init() 里另外几个 loader：空库下会 404 → 未捕获的 promise rejection，这里给空数组
    if (pn === `/projects/${PID}/chapters/list`) return fulfilJson(p.requestId, CHAPTERS);
    if (pn === `/projects/${PID}/volumes`) return fulfilJson(p.requestId, []);
    if (pn === `/projects/${PID}/characters`) return fulfilJson(p.requestId, []);
    if (pn === `/projects/${PID}/characters/overview`) return fulfilJson(p.requestId, {});
    if (pn === `/projects/${PID}/guidance-library`) return fulfilJson(p.requestId, []);
    if (pn.indexOf(`/projects/${PID}/bg-gen/status`) === 0) {
        return fulfilJson(p.requestId, { bgActive: false });
    }
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

async function releaseBootstrap() {
    if (!heldBootstrap) return false;
    const id = heldBootstrap;
    heldBootstrap = null;
    holdBootstrap = false;
    await fulfilJson(id, WF_DATA);
    return true;
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const host = document.querySelector('[x-data]');
    const data = window.Alpine ? Alpine.$data(host) : null;
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const skels = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    const wrapper = document.querySelector('main > div > div[x-show="!booting"]');
    const items = Array.from(document.querySelectorAll('.chapter-item')).filter(vis);
    const text = (document.body.innerText || '');
    return {
        ready: !!(data && window.Alpine),
        readyState: document.readyState,
        booting: data ? data.booting : null,
        pid: data ? data.projectId : null,
        projectTitle: data ? data.projectTitle : null,
        currentStep: data ? data.currentStep : null,
        viewStepDisplayName: data ? data.viewStepDisplayName : null,
        chapterCount: data ? data.chapterListData.length : -1,
        guidance: data ? data.stepGuidance : null,
        skelVisible: skels.length,
        itemVisible: items.length,
        contentVisible: wrapper ? vis(wrapper) : false,
        bodyHasTitle: text.indexOf('润色探针项目') >= 0,
        bodyHasGuidance: text.indexOf('保持冷峻笔调') >= 0,
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
        url: BASE + '/projects/' + PID + '/workflow/' + STEP + '?_probe=' + (++navSeq)
    });
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
            { urlPattern: '*/workflow/*/data*', requestStage: 'Request' },
            { urlPattern: '*/chapters/list*', requestStage: 'Request' },
            { urlPattern: '*/volumes*', requestStage: 'Request' },
            { urlPattern: '*/characters*', requestStage: 'Request' },
            { urlPattern: '*/guidance-library*', requestStage: 'Request' },
            { urlPattern: '*/bg-gen/status*', requestStage: 'Request' }
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
    check('【核心】骨架屏已渲染（不是白屏）', s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】正文区还没渲染（包装层隐藏）', s.contentVisible === false);
    check('【核心】还没渲染任何章节行', s.itemVisible === 0 && s.chapterCount === 0,
        `items=${s.itemVisible} chapters=${s.chapterCount}`);
    check('projectId 从路径兜底取到', String(s.pid) === String(PID), 'pid=' + s.pid);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('01-skeleton');

    /* ---------- P1 放行数据 ---------- */
    console.log('\n— P1 放行数据：骨架退场、步骤与章节填对 —');
    check('引导数据请求确实被挂住过（说明真的是异步发的）', await releaseBootstrap());
    check('【核心】数据到了 booting 自动收掉', (await waitBooted()) === true);
    s = await snap(400);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】正文区已显示', s.contentVisible === true);
    check('【核心】项目名与步骤名已填', s.projectTitle === '润色探针项目'
        && s.currentStep === 'POLISHING' && s.viewStepDisplayName === '润色修改',
        `${s.projectTitle} / ${s.viewStepDisplayName}`);
    check('项目名渲染到页头', s.bodyHasTitle === true);
    check('【核心】5 章列表渲染出来', s.chapterCount === 5 && s.itemVisible === 5,
        `state=${s.chapterCount} dom=${s.itemVisible}`);
    check('创作指导已填', (s.guidance || '').indexOf('冷峻') >= 0 && s.bodyHasGuidance === true);
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
    check('【核心】页面没崩：骨架收掉、框架还在', s.ready === true && s.skelVisible === 0
        && s.contentVisible === true, `skel=${s.skelVisible}`);
    check('失败时项目名退化成空', s.projectTitle === '' && s.bodyHasTitle === false,
        'title=' + s.projectTitle);
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
    check('窄屏章节列表正常渲染', s.itemVisible === 5, 'items=' + s.itemVisible);
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

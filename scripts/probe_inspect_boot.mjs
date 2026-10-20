#!/usr/bin/env node
/**
 * 创作透视两页：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 *   /projects/{id}/inspect              概览（/inspect/data）
 *   /projects/{id}/inspect/characters   角色透视（/inspect/characters/data）
 *
 * 两页原来都在页面里跑同步 XHR，且 <main> 挂 x-cloak：浏览器停在解析期等往返，
 * 用户看到的就是整片白屏。现在先出骨架，数据到了再由 Alpine 的 boot() 铺上来。
 *
 * 本脚本逐条守护（两页各一组）：
 *   P0 【核心】挂住数据：DOM 仍要 readyState=complete、骨架要出来、内容区不可见
 *   P1 【核心】放行数据：骨架退场、项目名/正文/统计全部填对
 *   P2 【核心】接口 500：页面不崩，骨架收掉（退化成空数据）
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 运行期无 JS 异常；引导数据每次加载只请求一次
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_inspect_boot.mjs
 *   node scripts/probe_inspect_boot.mjs      # 默认 1888
 * 环境变量：INSPECT_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.INSPECT_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9378;
const SHOT_DIR = '/tmp/inspect-boot-shots';
const PID = 1;

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'inspect-probe-'));
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
const OVERVIEW_DATA = {
    projectId: PID,
    projectTitle: '透视探针项目',
    writingRules: '写作规则：每章两千字，保持冷峻笔调。',
    styleFingerprint: { tone: 'cold', sentence: 'short' },
    storyOutline: '整体大纲：雨夜复仇线，三卷收束。',
    volumes: [{ volumeNumber: 1, title: '第一卷 · 雨夜', arcName: '开篇' }],
    outlines: [{ chapterNumber: 1, title: '雨夜', synopsis: '主角入山' }],
    chapterList: [{ number: 1, title: '第1章 雨夜', wordCount: 1800, status: 'CONFIRMED' }]
};

const CHARACTERS_DATA = {
    projectId: PID,
    projectTitle: '透视探针项目',
    characters: [
        { id: 3, name: '李四', role: '主角', appearanceCount: 12, dialogueCount: 40 },
        { id: 4, name: '王五', role: '配角', appearanceCount: 5, dialogueCount: 9 }
    ]
};

/* ==================== 网络层拦截 ==================== */
let holdBootstrap = false;
let failBootstrap = false;
let heldBootstrap = null;
let bootstrapRequests = 0;
let currentPath = '/inspect/data';
let currentPayload = OVERVIEW_DATA;

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
    if (pn === `/projects/${PID}${currentPath}`) {
        bootstrapRequests++;
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        if (failBootstrap) {
            return fulfil(p.requestId, 500, 'application/json; charset=utf-8',
                JSON.stringify({ error: 'inspect data 炸了' }));
        }
        return fulfilJson(p.requestId, currentPayload);
    }
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

async function releaseBootstrap() {
    if (!heldBootstrap) return false;
    const id = heldBootstrap;
    heldBootstrap = null;
    holdBootstrap = false;
    await fulfilJson(id, currentPayload);
    return true;
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const host = document.querySelector('[x-data]');
    const data = window.Alpine ? Alpine.$data(host) : null;
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const skels = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    const wrapper = document.querySelector('main > div[x-show="!booting"]');
    const text = (document.body.innerText || '');
    return {
        ready: !!(data && window.Alpine),
        readyState: document.readyState,
        booting: data ? data.booting : null,
        pid: data ? data.projectId : null,
        projectTitle: data ? data.projectTitle : null,
        volumeCount: data && data.volumes ? data.volumes.length : -1,
        outlineCount: data && data.outlines ? data.outlines.length : -1,
        chapterCount: data && data.chapterList ? data.chapterList.length : -1,
        charCount: data && data.characters ? data.characters.length : -1,
        rules: data ? data.writingRules : null,
        skelVisible: skels.length,
        contentVisible: wrapper ? vis(wrapper) : false,
        bodyHasTitle: text.indexOf('透视探针项目') >= 0,
        bodyHasRules: text.indexOf('冷峻笔调') >= 0,
        bodyHasChar: text.indexOf('李四') >= 0,
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
async function goto(urlPath, width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: BASE + urlPath + '?_probe=' + (++navSeq) });
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
    }, 12000, 120);
}

async function verifyPage({ tag, urlPath, dataPath, payload, extraCheck }) {
    currentPath = dataPath;
    currentPayload = payload;
    bootstrapRequests = 0;
    holdBootstrap = false;
    failBootstrap = false;
    heldBootstrap = null;

    console.log(`\n===== ${tag}（${urlPath}） =====`);

    console.log(`— ${tag} P0 挂住引导数据：首屏仍要出来 —`);
    holdBootstrap = true;
    await goto(urlPath);
    await waitMounted();
    let s = await snap(500);
    check(`${tag} 【核心】数据挂着时 DOM 已 readyState=complete`, s.readyState === 'complete',
        'readyState=' + s.readyState);
    check(`${tag} 【核心】组件已挂载且 booting=true`, s.ready === true && s.booting === true,
        `ready=${s.ready} booting=${s.booting}`);
    check(`${tag} 【核心】骨架已渲染（不是白屏）`, s.skelVisible > 0, 'skel=' + s.skelVisible);
    check(`${tag} 【核心】内容区还没渲染（包装层隐藏）`, s.contentVisible === false);
    check(`${tag} 首屏无横向溢出`, s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot(`${tag}-01-skeleton`);

    console.log(`— ${tag} P1 放行数据：骨架退场、数据填对 —`);
    check(`${tag} 引导数据请求确实被挂住过（说明真的是异步发的）`, await releaseBootstrap());
    check(`${tag} 【核心】数据到了 booting 自动收掉`, (await waitBooted()) === true);
    s = await snap(350);
    check(`${tag} 骨架全部退场`, s.skelVisible === 0, 'skel=' + s.skelVisible);
    check(`${tag} 【核心】内容区已显示、项目名已填`, s.contentVisible === true
        && s.projectTitle === '透视探针项目' && s.bodyHasTitle === true, `title=${s.projectTitle}`);
    if (extraCheck) extraCheck(s);
    await shot(`${tag}-02-loaded`);

    console.log(`— ${tag} P2 引导数据 500：页面退化但可用 —`);
    const errBefore = runtimeErrors.length;
    const consoleBefore = consoleErrors.length;
    failBootstrap = true;
    await goto(urlPath);
    await waitMounted();
    check(`${tag} 【核心】接口 500 时 booting 仍会收掉`, (await waitBooted()) === true);
    s = await snap(300);
    check(`${tag} 【核心】页面没崩：骨架收掉、框架还在`, s.ready === true && s.skelVisible === 0
        && s.contentVisible === true, `skel=${s.skelVisible}`);
    check(`${tag} 【核心】失败不产生未捕获异常`, runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    check(`${tag} 失败只打了一条兜底日志`, consoleErrors.length - consoleBefore <= 1,
        consoleErrors.slice(consoleBefore).join(' | '));
    await shot(`${tag}-03-degraded`);

    console.log(`— ${tag} P3 390px 窄屏 —`);
    failBootstrap = false;
    holdBootstrap = true;
    await goto(urlPath, 390, 780);
    await waitMounted();
    s = await snap(400);
    check(`${tag} 窄屏骨架阶段无横向溢出`, s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot(`${tag}-04-mobile-skeleton`);
    await releaseBootstrap();
    await waitBooted();
    s = await snap(300);
    check(`${tag} 窄屏数据态无横向溢出`, s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot(`${tag}-05-mobile-loaded`);

    console.log(`— ${tag} P4 运行期异常与请求次数 —`);
    check(`${tag} 引导数据每次加载只请求一次（3 次页面加载）`, bootstrapRequests === 3,
        'requests=' + bootstrapRequests);
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
            { urlPattern: '*/inspect/data*', requestStage: 'Request' },
            { urlPattern: '*/inspect/characters/data*', requestStage: 'Request' }
        ]
    });

    await verifyPage({
        tag: '透视概览', urlPath: '/projects/' + PID + '/inspect', dataPath: '/inspect/data',
        payload: OVERVIEW_DATA,
        extraCheck: (s) => {
            check('透视概览 【核心】写作规则/大纲/分卷/章节都填对',
                (s.rules || '').indexOf('冷峻') >= 0 && s.bodyHasRules === true
                && s.volumeCount === 1 && s.outlineCount === 1 && s.chapterCount === 1,
                `rules=${(s.rules || '').length} vols=${s.volumeCount} outlines=${s.outlineCount} chapters=${s.chapterCount}`);
        }
    });

    await verifyPage({
        tag: '角色透视', urlPath: '/projects/' + PID + '/inspect/characters',
        dataPath: '/inspect/characters/data', payload: CHARACTERS_DATA,
        extraCheck: (s) => {
            check('角色透视 【核心】2 个角色渲染出来', s.charCount === 2 && s.bodyHasChar === true,
                'chars=' + s.charCount);
        }
    });

    console.log('\n— 全程运行期异常 —');
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

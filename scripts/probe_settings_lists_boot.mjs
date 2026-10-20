#!/usr/bin/env node
/**
 * 设置族两个列表页：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 *   /settings/materials  素材库（/settings/materials/data）
 *   /settings/guidances  创作指导库（/settings/guidances/data）
 *
 * 两页原来都在页面顶部跑同步 XHR：浏览器停在 HTML 解析期等往返，首屏白屏。
 * 现在先给空壳（列表区顶一块骨架），数据到了再 renderList()，用 body[data-booting] 暴露阶段。
 *
 * 本脚本逐条守护（两页各一组）：
 *   P0 【核心】把引导数据挂住：DOM 仍要 readyState=complete、骨架要出来、不能先闪空态
 *   P1 【核心】放行数据：骨架退场、条目/徽章/下拉全部填对
 *   P2 【核心】接口 500：页面不崩，退化成空列表并给空态
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 运行期无 JS 异常；引导数据每次加载只请求一次
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_settings_lists_boot.mjs
 *   node scripts/probe_settings_lists_boot.mjs      # 默认 1888
 * 环境变量：SETTINGS_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.SETTINGS_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9377;
const SHOT_DIR = '/tmp/settings-lists-boot-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'settings-lists-probe-'));
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
const MATERIALS_DATA = {
    items: [
        {
            id: 11, name: '山道描写素材', category: 'SCENE', categoryLabel: '场景',
            preview: '雨夜、泥泞、远处的呼喊', sourceHint: '蒸馏自《雨夜》',
            updatedAt: '2026-10-18T10:00:00'
        },
        {
            id: 12, name: '人物口头禅', category: 'CHARACTER', categoryLabel: '角色',
            preview: '"别怕，跟着我。"', sourceHint: '手动创建',
            updatedAt: '2026-10-19T09:00:00'
        }
    ],
    categories: [
        { name: 'SCENE', displayName: '场景' },
        { name: 'CHARACTER', displayName: '角色' }
    ],
    modelConfigs: [{ id: 1, name: '默认模型' }]
};

const GUIDANCES_DATA = {
    items: [
        {
            id: 21, name: '冷峻笔调指导', guidance: '多用短句，少形容词。',
            stepLabel: '正文', updatedAt: '2026-10-18T10:00:00'
        },
        {
            id: 22, name: '群像戏节奏', guidance: '每章至少推进一条支线。',
            stepLabel: '大纲', updatedAt: '2026-10-19T09:00:00'
        }
    ],
    itemIds: [21, 22],
    steps: [{ name: 'WRITING', displayName: '正文' }, { name: 'OUTLINE', displayName: '大纲' }]
};

const PROJECTS = [{ id: 1, title: '润色探针项目' }];

/* ==================== 网络层拦截 ==================== */
let holdBootstrap = false;
let failBootstrap = false;
let heldBootstrap = null;
let bootstrapRequests = 0;
let currentDataPath = '/settings/materials/data';
let currentPayload = MATERIALS_DATA;

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
    if (pn === currentDataPath) {
        bootstrapRequests++;
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        if (failBootstrap) {
            return fulfil(p.requestId, 500, 'application/json; charset=utf-8',
                JSON.stringify({ error: 'data 炸了' }));
        }
        return fulfilJson(p.requestId, currentPayload);
    }
    if (pn === '/settings/materials/projects-json') return fulfilJson(p.requestId, PROJECTS);
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
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const skels = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    const rows = Array.from(document.querySelectorAll('.sc-skel-row')).filter(vis);
    const text = (document.body.innerText || '');
    const listArea = document.getElementById('listArea');
    return {
        readyState: document.readyState,
        booting: document.body.getAttribute('data-booting'),
        skelVisible: skels.length,
        skelRows: rows.length,
        rowCount: document.querySelectorAll('#listArea tbody tr').length,
        cardCount: document.querySelectorAll('#listArea .sc-guidance-card').length,
        listHtmlLen: listArea ? listArea.innerHTML.trim().length : -1,
        bodyHasEmpty: text.indexOf('暂无素材') >= 0 || text.indexOf('暂无') >= 0,
        bodyHasItem1: text.indexOf('山道描写素材') >= 0 || text.indexOf('冷峻笔调指导') >= 0,
        bodyHasBadge: text.indexOf('场景') >= 0 || text.indexOf('正文') >= 0,
        addOptions: document.getElementById('addCategory')
            ? document.getElementById('addCategory').options.length : -1,
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

async function waitSettled() {
    return waitFor('boot 结束', async () => {
        const x = await evalJs(`(() => document.body ? document.body.getAttribute('data-booting') : '1')()`);
        return x === null;
    }, 12000, 120);
}

/* ==================== 单页验证流程 ==================== */
async function verifyPage({ tag, urlPath, dataPath, payload, expectRows, rowSel, label }) {
    currentDataPath = dataPath;
    currentPayload = payload;
    bootstrapRequests = 0;
    holdBootstrap = false;
    failBootstrap = false;
    heldBootstrap = null;

    console.log(`\n===== ${label}（${urlPath}） =====`);

    /* ---------- P0 挂住引导数据 ---------- */
    console.log(`— ${tag} P0 把引导数据挂住：首屏仍要出来 —`);
    holdBootstrap = true;
    await goto(urlPath);
    await waitFor('骨架出现', async () => {
        try { return (await evalJs(`document.querySelectorAll('.sc-skel').length`)) > 0; } catch (e) { return false; }
    }, 15000, 100);
    let s = await snap(400);
    check(`${tag} 【核心】数据挂着时 DOM 已 readyState=complete`, s.readyState === 'complete',
        'readyState=' + s.readyState);
    check(`${tag} 【核心】骨架已渲染（不是白屏、也不是空列表）`,
        s.skelVisible > 0 && s.skelRows > 0, `skel=${s.skelVisible} rows=${s.skelRows}`);
    check(`${tag} 【核心】还没渲染任何条目`, s.rowCount === 0 && s.cardCount === 0,
        `tr=${s.rowCount} card=${s.cardCount}`);
    check(`${tag} 还没闪空态提示`, s.bodyHasEmpty === false);
    check(`${tag} 首屏无横向溢出`, s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot(`${tag}-01-skeleton`);

    /* ---------- P1 放行数据 ---------- */
    console.log(`— ${tag} P1 放行数据：骨架退场、条目填对 —`);
    check(`${tag} 引导数据请求确实被挂住过（说明真的是异步发的）`, await releaseBootstrap());
    check(`${tag} 【核心】数据到了 data-booting 摘掉`, (await waitSettled()) === true);
    s = await snap(350);
    check(`${tag} 骨架全部退场`, s.skelRows === 0 && s.skelVisible === 0,
        `rows=${s.skelRows} skel=${s.skelVisible}`);
    const got = rowSel === 'tr' ? s.rowCount : s.cardCount;
    check(`${tag} 【核心】${expectRows} 条全部渲染`, got === expectRows, 'got=' + got);
    check(`${tag} 条目文案与徽章都在`, s.bodyHasItem1 === true && s.bodyHasBadge === true);
    if (rowSel === 'tr') {
        check(`${tag} 【核心】「新建」下拉吃到异步分类`, s.addOptions === 2, 'options=' + s.addOptions);
    }
    await shot(`${tag}-02-loaded`);

    /* ---------- P2 接口 500 ---------- */
    console.log(`— ${tag} P2 引导数据 500：页面退化但可用 —`);
    const errBefore = runtimeErrors.length;
    const consoleBefore = consoleErrors.length;
    failBootstrap = true;
    await goto(urlPath);
    check(`${tag} 【核心】接口 500 时 data-booting 仍会摘掉`, (await waitSettled()) === true);
    s = await snap(300);
    check(`${tag} 【核心】页面没崩：骨架收掉、给出空态`, s.skelRows === 0
        && s.bodyHasEmpty === true, `rows=${s.skelRows} empty=${s.bodyHasEmpty}`);
    check(`${tag} 【核心】失败不产生未捕获异常`, runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    check(`${tag} 失败只打了一条兜底日志`, consoleErrors.length - consoleBefore <= 1,
        consoleErrors.slice(consoleBefore).join(' | '));
    await shot(`${tag}-03-degraded`);

    /* ---------- P3 390px ---------- */
    console.log(`— ${tag} P3 390px 窄屏 —`);
    failBootstrap = false;
    holdBootstrap = true;
    await goto(urlPath, 390, 780);
    await waitFor('骨架出现', async () => {
        try { return (await evalJs(`document.querySelectorAll('.sc-skel').length`)) > 0; } catch (e) { return false; }
    }, 15000, 100);
    s = await snap(400);
    check(`${tag} 窄屏骨架阶段无横向溢出`, s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot(`${tag}-04-mobile-skeleton`);
    await releaseBootstrap();
    await waitSettled();
    s = await snap(300);
    check(`${tag} 窄屏数据态无横向溢出`, s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot(`${tag}-05-mobile-loaded`);

    /* ---------- P4 运行期 ---------- */
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
            { urlPattern: '*/settings/materials/data*', requestStage: 'Request' },
            { urlPattern: '*/settings/guidances/data*', requestStage: 'Request' },
            { urlPattern: '*/settings/materials/projects-json*', requestStage: 'Request' }
        ]
    });

    await verifyPage({
        tag: '素材库', urlPath: '/settings/materials', dataPath: '/settings/materials/data',
        payload: MATERIALS_DATA, expectRows: 2, rowSel: 'tr', label: '素材库列表'
    });
    await verifyPage({
        tag: '指导库', urlPath: '/settings/guidances', dataPath: '/settings/guidances/data',
        payload: GUIDANCES_DATA, expectRows: 2, rowSel: 'card', label: '创作指导库'
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

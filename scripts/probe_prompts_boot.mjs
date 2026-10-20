#!/usr/bin/env node
/**
 * /prompts 流程模板配置页：**引导数据异步化 + 表格骨架**（2026-10-20）。
 *
 * 原来页面顶部一段同步 XHR 拿 /prompts/data：浏览器停在 HTML 解析期等往返，
 * 首屏白屏、表格还是空的。现在先摆 3 行骨架，数据到了由 renderTemplates() 整块替换。
 * 本页不是 Alpine（命令式渲染），所以用 body[data-booting] 暴露阶段给探针。
 *
 * 本脚本逐条守护：
 *   P0 【核心】把引导数据挂住：DOM 仍要 readyState=complete、骨架行要出来、不能出现空表
 *   P1 【核心】放行数据：骨架退场、模板行/步骤徽章/内置自定义标记全部填对，
 *      并确认「新建模板」下拉与「导出」列表也吃到了异步数据
 *   P2 【核心】接口 500：页面不崩，退化成空表（新建/导入仍可用）
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 运行期无 JS 异常；引导数据每次加载只请求一次
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_prompts_boot.mjs
 *   node scripts/probe_prompts_boot.mjs      # 默认 1888
 * 环境变量：PROMPTS_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.PROMPTS_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9375;
const SHOT_DIR = '/tmp/prompts-boot-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'prompts-probe-'));
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
const PROMPTS_DATA = {
    templates: [
        {
            id: 1, builtin: true, key: 'std-outline',
            step: 'OUTLINE', stepDisplayName: '大纲',
            subStep: 'OUTLINE_DRAFT', subStepDisplayName: '大纲起草',
            genre: 'FANTASY', genreDisplayName: '玄幻',
            name: '标准大纲模板', isDefault: true, updatedAt: '2026-10-01',
            workflowTags: [{ name: 'STANDARD', displayName: '标准' }],
            workflowTagNamesCsv: 'STANDARD'
        },
        {
            id: 2, builtin: false,
            step: 'WRITING', stepDisplayName: '正文',
            subStep: null, subStepDisplayName: null,
            genre: null, genreDisplayName: null,
            name: '自定义正文模板', isDefault: false, updatedAt: '2026-10-02',
            workflowTags: [{ name: 'SIDE_STORY', displayName: '番外' }],
            workflowTagNamesCsv: 'SIDE_STORY'
        }
    ],
    steps: [{ value: 'OUTLINE', displayName: '大纲' }, { value: 'WRITING', displayName: '正文' }],
    subSteps: [{ value: 'OUTLINE_DRAFT', displayName: '大纲起草', parentStep: 'OUTLINE' }],
    genres: [{ value: 'FANTASY', displayName: '玄幻' }]
};

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
    if (pn === '/prompts/data') {
        bootstrapRequests++;
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        if (failBootstrap) {
            return fulfil(p.requestId, 500, 'application/json; charset=utf-8',
                JSON.stringify({ error: 'prompts/data 炸了' }));
        }
        return fulfilJson(p.requestId, PROMPTS_DATA);
    }
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

async function releaseBootstrap() {
    if (!heldBootstrap) return false;
    const id = heldBootstrap;
    heldBootstrap = null;
    holdBootstrap = false;
    await fulfilJson(id, PROMPTS_DATA);
    return true;
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const skels = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    const skelRows = Array.from(document.querySelectorAll('#templateTbody .sc-skel-row')).filter(vis);
    const rows = Array.from(document.querySelectorAll('#templateTbody tr[data-builtin]')).filter(vis);
    const tbody = document.getElementById('templateTbody');
    const stepSel = document.getElementById('create-step');
    const subSel = document.getElementById('create-substep');
    const genreSel = document.getElementById('create-genre');
    const exportItems = Array.from(document.querySelectorAll('#exportTemplateList .list-group-item'));
    const text = (document.body.innerText || '');
    return {
        readyState: document.readyState,
        booting: document.body.getAttribute('data-booting'),
        skelVisible: skels.length,
        skelRows: skelRows.length,
        rowCount: rows.length,
        tbodyHtmlLen: tbody ? tbody.innerHTML.trim().length : -1,
        bodyHasBuiltin: text.indexOf('内置') >= 0,
        bodyHasCustom: text.indexOf('自定义') >= 0,
        bodyHasName: text.indexOf('标准大纲模板') >= 0,
        bodyHasStepTag: text.indexOf('大纲起草') >= 0,
        stepOptions: stepSel ? stepSel.options.length : -1,
        subStepOptions: subSel ? subSel.options.length : -1,
        genreOptions: genreSel ? genreSel.options.length : -1,
        exportItems: exportItems.length,
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
    await send('Page.navigate', { url: BASE + '/prompts?_probe=' + (++navSeq) });
}

async function waitSettled() {
    return waitFor('boot 结束', async () => {
        const x = await evalJs(`(() => document.body ? document.body.getAttribute('data-booting') : '1')()`);
        return x === null;
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
        patterns: [{ urlPattern: '*/prompts/data*', requestStage: 'Request' }]
    });

    /* ---------- P0 挂住引导数据 ---------- */
    console.log('\n— P0 把引导数据挂住：首屏仍要出来 —');
    holdBootstrap = true;
    await goto();
    await waitFor('骨架出现', async () => {
        try { return (await evalJs(`document.querySelectorAll('.sc-skel').length`)) > 0; } catch (e) { return false; }
    }, 15000, 100);
    let s = await snap(400);
    check('【核心】数据挂着时 DOM 已 readyState=complete（同步 XHR 会一直 loading）',
        s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】骨架行已渲染（不是白屏、也不是空表）',
        s.skelVisible > 0 && s.skelRows === 3, `skel=${s.skelVisible} skelRows=${s.skelRows}`);
    check('【核心】还没渲染任何模板行', s.rowCount === 0, 'rows=' + s.rowCount);
    check('还没提示「暂无模板」之类假空态', s.bodyHasName === false);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('01-skeleton');

    /* ---------- P1 放行数据 ---------- */
    console.log('\n— P1 放行数据：骨架退场、模板行填对 —');
    check('引导数据请求确实被挂住过（说明真的是异步发的）', await releaseBootstrap());
    check('【核心】数据到了 data-booting 摘掉', (await waitSettled()) === true);
    s = await snap(350);
    check('骨架行全部退场', s.skelRows === 0 && s.skelVisible === 0,
        `skelRows=${s.skelRows} skel=${s.skelVisible}`);
    check('【核心】2 个模板行全部渲染', s.rowCount === 2, 'rows=' + s.rowCount);
    check('行里有名称/步骤/子步骤徽章', s.bodyHasName === true && s.bodyHasStepTag === true);
    check('内置与自定义标记都在', s.bodyHasBuiltin === true && s.bodyHasCustom === true);
    check('【核心】新建模板下拉也吃到异步数据', s.stepOptions === 3 && s.subStepOptions === 2
        && s.genreOptions === 2, `step=${s.stepOptions} sub=${s.subStepOptions} genre=${s.genreOptions}`);
    check('导出自定义模板列表有 1 条', s.exportItems === 1, 'items=' + s.exportItems);
    await shot('02-loaded');

    /* ---------- P2 接口 500 ---------- */
    console.log('\n— P2 引导数据 500：页面退化但可用 —');
    const errBefore = runtimeErrors.length;
    const consoleBefore = consoleErrors.length;
    failBootstrap = true;
    await goto();
    check('【核心】接口 500 时 data-booting 仍会摘掉', (await waitSettled()) === true);
    s = await snap(250);
    check('【核心】页面没崩：退化成空表，骨架不留', s.skelRows === 0 && s.rowCount === 0
        && s.tbodyHtmlLen === 0, `skelRows=${s.skelRows} rows=${s.rowCount} len=${s.tbodyHtmlLen}`);
    check('导出列表给出「暂无自定义模板」兜底', s.exportItems === 0);
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
    await waitFor('骨架出现', async () => {
        try { return (await evalJs(`document.querySelectorAll('.sc-skel').length`)) > 0; } catch (e) { return false; }
    }, 15000, 100);
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('04-mobile-skeleton');
    await releaseBootstrap();
    await waitSettled();
    s = await snap(300);
    check('窄屏数据态无横向溢出（宽表在 table-responsive 里滚）',
        s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏模板行正常渲染', s.rowCount === 2, 'rows=' + s.rowCount);
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

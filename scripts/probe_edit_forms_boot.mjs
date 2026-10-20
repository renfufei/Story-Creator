#!/usr/bin/env node
/**
 * 两个「编辑表单」页：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 *   /settings/materials/{id}/edit   素材编辑（/settings/materials/{id}/edit-data）
 *   /settings/guidances/{id}/edit   创作指导编辑（/settings/guidances/{id}/edit-data）
 *
 * 两页原来都在页面顶部跑同步 XHR：浏览器停在 HTML 解析期等往返，JS 没开始跑、DOM 也没解析完，
 * 首屏白屏；更糟的是数据没到之前表单已经渲染出来 ⇒ 用户会看到一屏空表单闪一下。
 * 现在先给空壳（卡片里顶一块骨架、表单 display:none），数据到了再填，最后才收骨架露表单。
 *
 * ★ 为什么自带一个极简静态服务器（而不是直连 1888）：
 *   2026-10-20 实测运行中的 dev server 对静态资源（/css/*、/js/*、/vendor/*）**完全不响应**
 *   （curl 8s 无响应、000；而 /settings/materials/data 5ms 200）。为让本探针只受页面代码影响、
 *   不受 dev server 状态影响，这里自己起一个 127.0.0.1 上的静态服务器：
 *     - 页面 / CSS / JS 全部来自 src/main/resources/static 的**真实文件**
 *     - 引导数据接口喂假数据（可挂住 / 可 500），和别的探针口径一致
 *
 * 本脚本逐条守护（两页各一组）：
 *   P0 【核心】把引导数据挂住：DOM 仍要 readyState=complete、骨架要出来、表单不能先露（不闪空表单）
 *   P1 【核心】放行数据：骨架退场、表单露出、名称/下拉选中/正文/action 全部填对
 *   P2 【核心】接口 500：页面不崩，退化成空表单且不写出 /undefined/ 的错误 action
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 运行期无 JS 异常；引导数据每次加载只请求一次
 *
 * 用法：
 *   node scripts/probe_edit_forms_boot.mjs
 * 环境变量：STORY_BROWSER_PATH 指定浏览器
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9378;
const HTTP_PORT = 18899;
const SHOT_DIR = '/tmp/edit-forms-boot-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC = path.join(ROOT, 'src/main/resources/static');
/* vendor（bootstrap / bootstrap-icons）由 file:./data/static 提供，与线上口径一致 */
const STATIC_ROOTS = [STATIC, path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假数据 ==================== */
const MATERIAL_EDIT_DATA = {
    id: 11,
    name: '山道描写素材',
    category: 'SCENE',
    categoryLabel: '场景',
    content: '雨夜，泥泞没过脚踝，远处的呼喊被风撕成碎片。',
    sourceHint: '蒸馏自《雨夜》',
    categories: [
        { name: 'SCENE', displayName: '场景' },
        { name: 'CHARACTER', displayName: '角色' }
    ]
};

const GUIDANCE_EDIT_DATA = {
    id: 8,
    name: '冷峻笔调指导',
    step: 'WORLD_BUILDING',
    stepLabel: '世界观构建',
    guidance: '删掉所有形容词，只留动作和物件。',
    steps: [
        { name: 'WORLD_BUILDING', displayName: '世界观构建' },
        { name: 'EXPANSION', displayName: '情节拓展' }
    ]
};

/* ==================== 两张页面的差异 ==================== */
const PAGES = [
    {
        tag: '素材编辑',
        label: '素材库编辑表单',
        pagePath: '/settings/materials/11/edit',
        dataPath: '/settings/materials/11/edit-data',
        localFile: 'pages/material-edit.html',
        payload: MATERIAL_EDIT_DATA,
        titleText: '编辑素材',
        name: MATERIAL_EDIT_DATA.name,
        selectId: 'fCategory', selectValue: 'SCENE', selectOptions: 2,
        textId: 'fContent', textHead: '雨夜，泥',
        extraId: 'fSourceHint', extraValue: '蒸馏自《雨夜》',
        formAction: '/settings/materials/11/update',
        delAction: null
    },
    {
        tag: '指导编辑',
        label: '创作指导编辑表单',
        pagePath: '/settings/guidances/8/edit',
        dataPath: '/settings/guidances/8/edit-data',
        localFile: 'pages/guidance-edit.html',
        payload: GUIDANCE_EDIT_DATA,
        titleText: '编辑创作指导',
        name: GUIDANCE_EDIT_DATA.name,
        selectId: 'fStep', selectValue: 'WORLD_BUILDING', selectOptions: 2,
        textId: 'fGuidance', textHead: '删掉所有',
        extraId: null, extraValue: null,
        formAction: '/settings/guidances/8/update',
        delAction: '/settings/guidances/8/delete'
    }
];

/* ==================== 拦截状态（本进程内的静态服务器） ==================== */
let currentCfg = PAGES[0];
let holdBootstrap = false;
let failBootstrap = false;
let heldRes = null;           // 挂住的那个 res
let bootstrapRequests = 0;

const MIME = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.png': 'image/png',
    '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf'
};

function serveStatic(res, relPath) {
    let buf = null, hit = null;
    for (const root of STATIC_ROOTS) {
        const file = path.join(root, relPath);
        if (!file.startsWith(root)) continue;
        try { buf = fs.readFileSync(file); hit = file; break; } catch (e) { /* 换下一个根目录 */ }
    }
    if (!buf) { res.writeHead(404); return res.end(''); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(hit)] || 'application/octet-stream' });
    res.end(buf);
}

const pageServer = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (process.env.PROBE_DEBUG) console.log('  [req] ' + pn);

    // ① 页面
    if (pn === currentCfg.pagePath) return serveStatic(res, currentCfg.localFile);

    // ② 引导数据：可挂住、可 500
    if (pn === currentCfg.dataPath) {
        bootstrapRequests++;
        if (holdBootstrap) { heldRes = res; return; }
        if (failBootstrap) {
            res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
            return res.end(JSON.stringify({ error: 'edit-data 炸了' }));
        }
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify(currentCfg.payload));
    }

    // ③ 其余静态资源（/css/*、/js/*、/vendor/*）
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

function releaseBootstrap() {
    if (!heldRes) return false;
    const res = heldRes;
    heldRes = null;
    holdBootstrap = false;
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(currentCfg.payload));
    return true;
}

const delay = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'edit-forms-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + CDP_PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

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

/* ==================== 快照 ==================== */
function snapExpr(cfg) {
    return `(() => {
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const form = document.getElementById('editForm');
    const skel = document.getElementById('bootSkel');
    const sel = document.getElementById(${JSON.stringify(cfg.selectId)});
    const ta = document.getElementById(${JSON.stringify(cfg.textId)});
    const ex = ${cfg.extraId ? `document.getElementById(${JSON.stringify(cfg.extraId)})` : 'null'};
    const del = document.getElementById('deleteForm');
    const nameEl = document.getElementById('fName');
    const text = document.body ? (document.body.innerText || '') : '';
    return {
        readyState: document.readyState,
        skelExists: !!skel,
        skelVisible: Array.from(document.querySelectorAll('.sc-skel')).filter(vis).length,
        skelRows: Array.from(document.querySelectorAll('.sc-skel-row')).filter(vis).length,
        formVisible: vis(form),
        formDisplay: form ? getComputedStyle(form).display : null,
        name: nameEl ? nameEl.value : null,
        selOptions: sel ? sel.options.length : -1,
        selValue: sel ? sel.value : null,
        taLen: ta ? (ta.value || '').length : -1,
        taHead: ta ? (ta.value || '').slice(0, 4) : null,
        exValue: ex ? ex.value : null,
        formAction: form ? form.getAttribute('action') : null,
        delAction: del ? del.getAttribute('action') : null,
        hasTitle: text.indexOf(${JSON.stringify(cfg.titleText)}) >= 0,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;
}
const snap = async (cfg, ms = 250) => { if (ms) await sleep(ms); return evalJs(snapExpr(cfg)); };

async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

let navSeq = 0;
async function goto(urlPath, width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}` + urlPath });
}

/** 编辑页没有 Alpine 状态可查，「boot 结束」= 骨架被摘掉 */
const waitSettled = () => waitFor('boot 结束', async () => {
    try { return (await evalJs(`!document.getElementById('bootSkel')`)) === true; } catch (e) { return false; }
}, 12000, 120);

const waitSkeleton = () => waitFor('骨架出现', async () => {
    try {
        return (await evalJs(`!!document.getElementById('bootSkel')
            && document.querySelectorAll('.sc-skel').length > 0`)) === true;
    } catch (e) { return false; }
}, 15000, 100);

/* ==================== 单页验证流程 ==================== */
async function verifyPage(cfg) {
    const { tag, pagePath } = cfg;
    currentCfg = cfg;
    bootstrapRequests = 0;
    holdBootstrap = false;
    failBootstrap = false;
    heldRes = null;

    console.log(`\n===== ${cfg.label}（${pagePath}） =====`);

    /* ---------- P0 挂住引导数据 ---------- */
    console.log(`— ${tag} P0 把引导数据挂住：首屏仍要出来、表单不能先露 —`);
    holdBootstrap = true;
    await goto(pagePath);
    await waitSkeleton();
    let s = await snap(cfg, 400);
    check(`${tag} 【核心】数据挂着时 DOM 已 readyState=complete`, s.readyState === 'complete',
        'readyState=' + s.readyState);
    check(`${tag} 页面骨架（标题/返回）已渲染`, s.hasTitle === true);
    check(`${tag} 【核心】表单区骨架已渲染（不是白屏）`,
        s.skelExists === true && s.skelVisible >= 4 && s.skelRows >= 1,
        `skel=${s.skelVisible} rows=${s.skelRows}`);
    check(`${tag} 【核心】表单还没露出（不闪空表单）`,
        s.formVisible === false && s.formDisplay === 'none', `display=${s.formDisplay}`);
    check(`${tag} 名称输入框还没被填过`, !s.name, `name=${JSON.stringify(s.name)}`);
    check(`${tag} 首屏无横向溢出`, s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot(`${tag}-01-skeleton`);

    /* ---------- P1 放行数据 ---------- */
    console.log(`— ${tag} P1 放行数据：骨架退场、表单填对 —`);
    check(`${tag} 引导数据请求确实被挂住过（说明真的是异步发的）`, releaseBootstrap());
    check(`${tag} 【核心】数据到了骨架被摘掉`, (await waitSettled()) === true);
    s = await snap(cfg, 350);
    check(`${tag} 骨架全部退场`, s.skelExists === false && s.skelVisible === 0,
        `exists=${s.skelExists} skel=${s.skelVisible}`);
    check(`${tag} 【核心】表单露出`, s.formVisible === true, `display=${s.formDisplay}`);
    check(`${tag} 【核心】名称已填`, s.name === cfg.name, `name=${JSON.stringify(s.name)}`);
    check(`${tag} 【核心】下拉吃到异步选项且选中正确`,
        s.selOptions === cfg.selectOptions && s.selValue === cfg.selectValue,
        `options=${s.selOptions} value=${s.selValue}`);
    check(`${tag} 【核心】正文已填`, s.taLen > 0 && s.taHead === cfg.textHead,
        `len=${s.taLen} head=${JSON.stringify(s.taHead)}`);
    if (cfg.extraId) {
        check(`${tag} 附加字段已填`, s.exValue === cfg.extraValue, `value=${JSON.stringify(s.exValue)}`);
    }
    check(`${tag} 【核心】表单 action 指向 update`,
        s.formAction === cfg.formAction, `action=${s.formAction}`);
    if (cfg.delAction) {
        check(`${tag} 删除表单 action 指向 delete`, s.delAction === cfg.delAction,
            `action=${s.delAction}`);
    }
    await shot(`${tag}-02-loaded`);

    /* ---------- P2 接口 500 ---------- */
    console.log(`— ${tag} P2 引导数据 500：页面退化但可用 —`);
    const errBefore = runtimeErrors.length;
    const bootErrBefore = consoleErrors.filter(x => /bootstrap failed|edit-data|edit_data/.test(x)).length;
    failBootstrap = true;
    await goto(pagePath);
    check(`${tag} 【核心】接口 500 时骨架也会被摘掉（不会卡死在骨架）`,
        (await waitSettled()) === true);
    s = await snap(cfg, 300);
    check(`${tag} 页面没崩：骨架收掉、标题还在`, s.skelExists === false && s.hasTitle === true);
    check(`${tag} 【核心】退化成空表单且仍可编辑`, s.formVisible === true && !s.name,
        `display=${s.formDisplay} name=${JSON.stringify(s.name)}`);
    check(`${tag} 【核心】失败时不写出含 undefined 的错误 action`,
        !s.formAction || s.formAction.indexOf('undefined') < 0, `action=${s.formAction}`);
    if (cfg.delAction) {
        check(`${tag} 删除表单同样没写出 undefined`, !s.delAction || s.delAction.indexOf('undefined') < 0,
            `action=${s.delAction}`);
    }
    check(`${tag} 【核心】失败不产生未捕获异常`, runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    const bootErrAfter = consoleErrors.filter(x => /bootstrap failed|edit-data|edit_data/.test(x)).length;
    check(`${tag} 失败只打了一条兜底日志`, bootErrAfter - bootErrBefore === 1,
        'delta=' + (bootErrAfter - bootErrBefore));
    await shot(`${tag}-03-degraded`);

    /* ---------- P3 390px ---------- */
    console.log(`— ${tag} P3 390px 窄屏 —`);
    failBootstrap = false;
    holdBootstrap = true;
    await goto(pagePath, 390, 780);
    await waitSkeleton();
    s = await snap(cfg, 400);
    check(`${tag} 窄屏骨架阶段无横向溢出`, s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot(`${tag}-04-mobile-skeleton`);
    releaseBootstrap();
    await waitSettled();
    s = await snap(cfg, 300);
    check(`${tag} 窄屏数据态无横向溢出`, s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check(`${tag} 窄屏表单同样填对`, s.name === cfg.name && s.formVisible === true);
    await shot(`${tag}-05-mobile-loaded`);

    /* ---------- P4 运行期 ---------- */
    console.log(`— ${tag} P4 运行期异常与请求次数 —`);
    check(`${tag} 引导数据每次加载只请求一次（3 次页面加载）`, bootstrapRequests === 3,
        'requests=' + bootstrapRequests);
}

/* ==================== 主流程 ==================== */
async function main() {
    await new Promise((res, rej) => {
        pageServer.once('error', rej);
        pageServer.listen(HTTP_PORT, '127.0.0.1', res);
    });
    console.log(`静态服务器: http://127.0.0.1:${HTTP_PORT}  (root=${STATIC})`);

    let ver = null;
    for (let i = 0; i < 80; i++) {
        try {
            const r = await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`);
            if (r.ok) { ver = await r.json(); break; }
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    if (!ver) throw new Error('Chrome CDP 端点未就绪');
    console.log('Chrome: ' + ver['Browser']);

    const target = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/new?about:blank`, { method: 'PUT' })).json();
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
        }
    };

    await send('Runtime.enable');
    await send('Page.enable');

    for (const cfg of PAGES) await verifyPage(cfg);

    console.log('\n— 全程运行期异常 —');
    check('全程无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));

    const failed = results.filter(x => !x.ok).length;
    console.log(`\n===== ${results.length - failed}/${results.length} 通过 =====`);
    if (failed) console.log('失败项：\n' + results.filter(x => !x.ok).map(x => ' - ' + x.name).join('\n'));

    try { await send('Browser.close'); } catch (e) { /* ignore */ }
    try { chrome.kill(); } catch (e) { /* ignore */ }
    try { pageServer.close(); } catch (e) { /* ignore */ }
    process.exit(failed ? 1 : 0);
}

main().catch(async (e) => {
    console.error('PROBE FAILED: ' + e.message);
    try { chrome.kill(); } catch (err) { /* ignore */ }
    try { pageServer.close(); } catch (err) { /* ignore */ }
    process.exit(2);
});

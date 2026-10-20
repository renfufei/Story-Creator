#!/usr/bin/env node
/**
 * TTS 文字替换模板族 4 页：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 *   /settings/tts-templates                        列表（内置 + 自定义两张表）
 *   /settings/tts-templates/builtin/{key}/view     内置模板只读查看（规则表）
 *   /settings/tts-templates/{id}/edit              自定义模板编辑（信息 + 规则表）
 *   /settings/tts-templates/bindings/{id}          某 TTS 模型的模板绑定
 *
 * 四页原来都在页面顶部跑同步 XHR：浏览器停在 HTML 解析期等往返，首屏白屏，
 * 而且表格容器已经铺出来了 ⇒ 先见空表再见数据。现在先摆骨架，数据到了再渲染。
 *
 * 逐页守护：挂住 ⇒ 骨架在、内容区没露；放行 ⇒ 骨架退场、行数/字段都对；500 ⇒ 不崩。
 * 另外：390px 无横向溢出；每次加载只请求一次引导数据。
 *
 * ★ 自带静态服务器，不依赖 1888（其静态资源处理当前无响应）。
 *
 * 用法：node scripts/probe_tts_templates_boot.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9385;
const HTTP_PORT = 18906;
const SHOT_DIR = '/tmp/tts-templates-boot-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC_ROOTS = [path.join(ROOT, 'src/main/resources/static'), path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假数据 ==================== */
const LIST = {
    builtinTemplates: [{ id: 'b1', name: '内置模板A', description: '内置描述', ruleCount: 3 }],
    userTemplates: [{ id: 7, name: '自定义模板A', description: '自定义描述', ruleCount: 2, enabled: true }]
};
const VIEW = {
    name: '内置模板A', description: '内置描述',
    rules: [
        { pattern: '\\n+', replacement: '', regex: true, description: '合并空行' },
        { pattern: 'abc', replacement: 'X', regex: false, description: '文本替换' }
    ]
};
const EDIT = {
    template: { name: '自定义模板A', description: '自定义描述' },
    rules: [{ pattern: 'a', replacement: 'b', regex: false, description: '规则一' }]
};
const BIND = {
    currentConfig: { displayName: '模型A', modelId: 'm1' },
    bindings: [{ sortOrder: 1, name: '内置模板A', type: 'builtin', templateRef: 'b1' }],
    allOptions: [
        { type: '内置', name: '内置模板A', templateRef: 'b1' },
        { type: '自定义', name: '自定义模板A', templateRef: 'u7' }
    ]
};

/* ==================== 页面清单 ==================== */
const PAGES = [
    {
        tag: '列表页', route: '/settings/tts-templates', dataRoute: '/settings/tts-templates/data',
        file: 'pages/tts-templates.html', payload: LIST,
        // ★ 骨架只遮数据区，<h2> 标题栏在骨架外是正常露出 ⇒ P0 断言看数据容器，不看标题
        emptyExpr: `document.querySelectorAll('#builtinArea tbody tr, #userArea tbody tr').length`,
        snap: `(() => ({
            builtinRows: document.querySelectorAll('#builtinArea tbody tr').length,
            userRows: document.querySelectorAll('#userArea tbody tr').length
        }))()`,
        expect: (v) => {
            check('列表页 【核心】内置表 1 行', v.builtinRows === 1, 'rows=' + v.builtinRows);
            check('列表页 【核心】自定义表 1 行', v.userRows === 1, 'rows=' + v.userRows);
        }
    },
    {
        tag: '查看页', route: '/settings/tts-templates/builtin/b1/view',
        dataRoute: '/settings/tts-templates/builtin/b1/view-data',
        file: 'pages/tts-template-view.html', payload: VIEW,
        emptyExpr: `document.querySelectorAll('#rulesArea tbody tr').length`,
        snap: `(() => ({
            title: (document.getElementById('pageTitle')||{}).textContent || '',
            name: (document.getElementById('bName')||{}).value || '',
            rows: document.querySelectorAll('#rulesArea tbody tr').length
        }))()`,
        expect: (v) => {
            check('查看页 【核心】标题带上模板名', v.title.indexOf('内置模板A') >= 0, 'title=' + v.title);
            check('查看页 【核心】名称字段已填', v.name === '内置模板A', 'name=' + v.name);
            check('查看页 【核心】规则表 2 行', v.rows === 2, 'rows=' + v.rows);
        }
    },
    {
        tag: '编辑页', route: '/settings/tts-templates/7/edit', dataRoute: '/settings/tts-templates/7/edit-data',
        file: 'pages/tts-template-edit.html', payload: EDIT,
        emptyExpr: `document.querySelectorAll('#rulesArea tbody tr').length`,
        snap: `(() => ({
            title: (document.getElementById('pageTitle')||{}).textContent || '',
            name: (document.getElementById('tplName')||{}).value || '',
            action: (document.getElementById('updateForm')||{}).getAttribute
                ? document.getElementById('updateForm').getAttribute('action') : null,
            addAction: document.getElementById('addRuleForm') ? document.getElementById('addRuleForm').getAttribute('action') : null,
            rows: document.querySelectorAll('#rulesArea tbody tr').length
        }))()`,
        expect: (v) => {
            check('编辑页 【核心】标题带上模板名', v.title.indexOf('自定义模板A') >= 0, 'title=' + v.title);
            check('编辑页 【核心】名称字段已填', v.name === '自定义模板A', 'name=' + v.name);
            check('编辑页 【核心】update 表单 action 用上了路径里的 id',
                v.action === '/settings/tts-templates/7/update', 'action=' + v.action);
            check('编辑页 【核心】加规则表单 action 同样带 id',
                v.addAction === '/settings/tts-templates/7/rules', 'action=' + v.addAction);
            check('编辑页 【核心】规则表 1 行', v.rows === 1, 'rows=' + v.rows);
        }
    },
    {
        tag: '绑定页', route: '/settings/tts-templates/bindings/11',
        dataRoute: '/settings/tts-templates/bindings/11/data',
        file: 'pages/tts-template-bindings.html', payload: BIND,
        emptyExpr: `document.querySelectorAll('#bindingsArea tbody tr').length`,
        snap: `(() => ({
            label: (document.getElementById('configLabel')||{}).textContent || '',
            rows: document.querySelectorAll('#bindingsArea tbody tr').length,
            formAction: document.getElementById('addBindingForm')
                ? document.getElementById('addBindingForm').getAttribute('action') : null,
            optGroups: document.querySelectorAll('select optgroup').length
        }))()`,
        expect: (v) => {
            check('绑定页 【核心】模型名铺上了', v.label.indexOf('模型A') >= 0, 'label=' + v.label);
            check('绑定页 【核心】已绑定表 1 行', v.rows === 1, 'rows=' + v.rows);
            check('绑定页 【核心】添加表单 action 带 configId',
                v.formAction === '/settings/tts-templates/bindings/11/add', 'action=' + v.formAction);
            check('绑定页 【核心】下拉按「内置/自定义」分组', v.optGroups === 2, 'groups=' + v.optGroups);
        }
    }
];

/* ==================== 静态服务器 ==================== */
let cfg = PAGES[0];
let hold = false, fail = false, held = null, requests = 0;

const MIME = {
    '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml', '.png': 'image/png',
    '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf'
};

function serveStatic(res, relPath) {
    let buf = null, hit = null;
    for (const root of STATIC_ROOTS) {
        const file = path.join(root, relPath);
        if (!file.startsWith(root)) continue;
        try { buf = fs.readFileSync(file); hit = file; break; } catch (e) { /* 下一个根 */ }
    }
    if (!buf) { res.writeHead(404); return res.end(''); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(hit)] || 'application/octet-stream' });
    res.end(buf);
}
const json = (res, obj, code = 200) => {
    res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(obj));
};

const server = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (pn === cfg.route) return serveStatic(res, cfg.file);
    if (pn === cfg.dataRoute) {
        requests++;
        if (hold) { held = res; return; }
        if (fail) return json(res, { error: '引导数据炸了' }, 500);
        return json(res, cfg.payload);
    }
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

function release() {
    if (!held) return false;
    const res = held;
    held = null;
    hold = false;
    json(res, cfg.payload);
    return true;
}

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tts-templates-probe-'));
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

const BASE_SNAP = `(() => {
    const vis = (el) => !!el && el.getClientRects().length > 0 && getComputedStyle(el).display !== 'none';
    return {
        readyState: document.readyState,
        skelExists: !!document.getElementById('bootSkel'),
        skelVisible: Array.from(document.querySelectorAll('.sc-skel')).filter(vis).length,
        headVisible: vis(document.querySelector('h2')),
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;
const baseSnap = async (ms = 200) => { if (ms) await sleep(ms); return evalJs(BASE_SNAP); };
const pageSnap = async (ms = 250) => { if (ms) await sleep(ms); return evalJs(cfg.snap); };

async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

let navSeq = 0;
async function goto(route, width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}${route}?_probe=` + (++navSeq) });
}
const waitSettled = () => waitFor(async () => {
    try { return (await evalJs(`!document.getElementById('bootSkel')`)) === true; } catch (e) { return false; }
});
const waitSkeleton = () => waitFor(async () => {
    try { return (await evalJs(`document.querySelectorAll('.sc-skel').length`)) > 0; } catch (e) { return false; }
}, 15000, 100);

let last = 0;
function checkOnce(tag) {
    const delta = requests - last;
    last = requests;
    check(tag + ' 本次加载只请求了一次引导数据', delta === 1, 'delta=' + delta);
}

async function verifyPage(p) {
    cfg = p; hold = false; fail = false; held = null; requests = 0; last = 0;
    console.log(`\n===== ${p.tag}（${p.route}） =====`);

    console.log(`— ${p.tag} 挂住引导数据 —`);
    hold = true;
    await goto(p.route);
    await waitSkeleton();
    let s = await baseSnap(500);
    check(`${p.tag} 【核心】挂住时 DOM 已 readyState=complete`, s.readyState === 'complete', 'readyState=' + s.readyState);
    check(`${p.tag} 【核心】骨架已渲染（不是白屏）`, s.skelExists === true && s.skelVisible > 0,
        `skel=${s.skelVisible}`);
    const emptyRows = await evalJs(p.emptyExpr);
    check(`${p.tag} 【核心】数据容器还没被填充`, emptyRows === 0, 'rows=' + emptyRows);
    check(`${p.tag} 首屏无横向溢出`, s.docScrollW <= s.docClientW + 1);
    await shot(`${p.tag}-01-skeleton`);

    console.log(`— ${p.tag} 放行 —`);
    check(`${p.tag} 引导数据请求确实被挂住过（说明是异步发的）`, release());
    check(`${p.tag} 【核心】骨架被摘掉`, (await waitSettled()) === true);
    checkOnce(p.tag);
    s = await baseSnap(300);
    check(`${p.tag} 骨架全部退场`, s.skelExists === false && s.skelVisible === 0);
    check(`${p.tag} 【核心】标题露出`, s.headVisible === true);
    p.expect(await pageSnap(300));
    await shot(`${p.tag}-02-loaded`);

    console.log(`— ${p.tag} 引导数据 500 —`);
    const errBefore = runtimeErrors.length;
    fail = true;
    await goto(p.route);
    check(`${p.tag} 【核心】500 时骨架也会被摘掉（不卡骨架）`, (await waitSettled()) === true);
    s = await baseSnap(300);
    check(`${p.tag} 页面没崩：骨架收掉、标题还在`, s.skelExists === false && s.headVisible === true);
    check(`${p.tag} 【核心】失败不产生未捕获异常`, runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));

    console.log(`— ${p.tag} 390px —`);
    fail = false; hold = true;
    await goto(p.route, 390, 780);
    await waitSkeleton();
    s = await baseSnap(400);
    check(`${p.tag} 窄屏骨架阶段无横向溢出`, s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW}`);
    release();
    await waitSettled();
    s = await baseSnap(300);
    check(`${p.tag} 窄屏数据态无横向溢出`, s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW}`);
    await shot(`${p.tag}-03-mobile`);
}

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
        else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') consoleErrors.push((m.params.args || []).map(a => a.value || a.description || '').join(' '));
    };
    await send('Runtime.enable');
    await send('Page.enable');

    for (const p of PAGES) await verifyPage(p);

    console.log('\n— 全程 —');
    check('全程无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));

    const failed = results.filter(x => !x.ok).length;
    console.log(`\n===== ${results.length - failed}/${results.length} 通过 =====`);
    if (failed) console.log('失败项：\n' + results.filter(x => !x.ok).map(x => ' - ' + x.name).join('\n'));

    try { await send('Browser.close'); } catch (e) {}
    try { chrome.kill(); } catch (e) {}
    try { server.close(); } catch (e) {}
    process.exit(failed ? 1 : 0);
}

main().catch(async (e) => {
    console.error('PROBE FAILED: ' + e.message);
    try { chrome.kill(); } catch (err) {}
    try { server.close(); } catch (err) {}
    process.exit(2);
});

#!/usr/bin/env node
/**
 * 提示词两个页面：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 *   /prompts/explore                         提示词探索（Alpine）
 *     /prompts/explore/data?templateKey=&templateId=
 *   /prompts/{id}/edit                       自定义模板编辑
 *   /prompts/builtin/{key}/edit              内置模板（只读）
 *     /prompts/{id}/edit-data、/prompts/builtin/{key}/edit-data
 *
 * 原来都是页面顶部同步 XHR：浏览器停在 HTML 解析期等往返，首屏白屏，
 * 而且数据没到之前表单区已经铺出来了 ⇒ 会先闪一屏空表单。
 *
 * 守护点（三个场景各一组）：
 *   G1 探索页：挂住 ⇒ 骨架在、内容不在；放行 ⇒ 模板名/步骤徽标/变量徽标都对；500 ⇒ 不崩
 *   G2 内置模板（只读）：骨架退场后 #builtinView 露出、标题改成「查看内置Prompt模板」、
 *      值填对、**不出现删除按钮**
 *   G3 自定义模板：骨架退场后 #customForm 露出、action 指向 /prompts/{id}、删除按钮出现
 *   另外：390px 无横向溢出；每次加载只请求一次引导数据
 *
 * ★ 自带静态服务器，不依赖 1888（其静态资源处理当前无响应）。
 *
 * 用法：node scripts/probe_prompts_boot.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9384;
const HTTP_PORT = 18905;
const SHOT_DIR = '/tmp/prompts-boot-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC_ROOTS = [path.join(ROOT, 'src/main/resources/static'), path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假数据 ==================== */
const EXPLORE_DATA = {
    step: 'CHAPTER_WRITING',
    subStep: 'CHAPTER_CONTEXT_BRIEFING',
    templateKey: 'world-facet',
    templateId: null,
    templateName: '世界切片探索模板',
    stepDisplayName: '章节写作',
    subStepDisplayName: '上下文简报',
    variableNames: ['title', 'genre', 'content'],
    templateContent: '你是一位小说编辑，请基于以下内容：\n{{title}} / {{genre}}\n{{content}}',
    systemPromptContent: '你是严谨的中文小说编辑。',
    projects: [{ id: 1, title: '示例项目' }],
    modelConfigs: []
};

const EDIT_CUSTOM = {
    id: 42,
    isBuiltin: false,
    stepDisplayName: '章节写作',
    subStepDisplayName: '上下文简报',
    genreDisplayName: '玄幻',
    name: '我的自定义模板',
    systemPrompt: '你是严谨的中文小说编辑。',
    template: '请基于 {{content}} 续写。',
    variableHints: ['content', 'chapterTitle']
};

const EDIT_BUILTIN = {
    id: null,
    isBuiltin: true,
    stepDisplayName: '章节写作',
    subStepDisplayName: '上下文简报',
    genreDisplayName: '通用',
    name: '内置：上下文简报',
    systemPrompt: '你是严谨的中文小说编辑。',
    template: '内置模板正文 {{content}}',
    variableHints: ['content']
};

/* ==================== 静态服务器 ==================== */
const state = {
    hold: false, fail: false, held: null, requests: 0,
    mode: 'explore',          // explore | edit-custom | edit-builtin
    route: '/prompts/explore',
    dataRoute: '/prompts/explore/data'
};

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
const payloadFor = (mode) => mode === 'explore' ? EXPLORE_DATA
    : (mode === 'edit-builtin' ? EDIT_BUILTIN : EDIT_CUSTOM);

const server = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);

    // 页面
    if (pn === '/prompts/explore') return serveStatic(res, 'pages/prompt-explore.html');
    if (/^\/prompts\/(builtin\/[^/]+|\d+)\/edit$/.test(pn)) return serveStatic(res, 'pages/prompt-edit.html');

    // 引导数据
    if (pn === '/prompts/explore/data' || /^\/prompts\/(builtin\/[^/]+|\d+)\/edit-data$/.test(pn)) {
        state.requests++;
        if (state.hold) { state.held = res; return; }
        if (state.fail) return json(res, { error: '引导数据炸了' }, 500);
        return json(res, payloadFor(state.mode));
    }
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

function release() {
    if (!state.held) return false;
    const res = state.held;
    state.held = null;
    state.hold = false;
    json(res, payloadFor(state.mode));
    return true;
}

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'prompts-boot-probe-'));
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

const SNAP = `(() => {
    const vis = (el) => !!el && el.getClientRects().length > 0 && getComputedStyle(el).display !== 'none';
    const root = document.querySelector('[x-data]');
    const st = (root && window.Alpine) ? window.Alpine.$data(root) : null;
    const txt = (id) => { const el = document.getElementById(id); return el ? el.value : null; };
    const form = document.getElementById('customForm');
    const del = document.getElementById('btnDelete');
    return {
        readyState: document.readyState,
        booting: st ? st.booting : null,
        // 探索页
        templateName: st ? st.templateName : null,
        pageStep: st ? st.pageStep : null,
        varCount: st ? Array.from(st.variableNames).length : -1,
        tmplLen: st ? String(st.templateContent || '').length : -1,
        titleText: (document.getElementById('pageTitle') || {}).textContent || '',
        // 编辑页
        skelExists: !!document.getElementById('bootSkel'),
        skelVisible: Array.from(document.querySelectorAll('.sc-skel')).filter(vis).length,
        builtinVisible: vis(document.getElementById('builtinView')),
        customVisible: vis(form),
        formAction: form ? form.getAttribute('action') : null,
        delVisible: vis(del),
        loadErrorVisible: vis(document.getElementById('loadError')),
        bName: txt('bName'), bTemplate: txt('bTemplate'), bGenre: txt('bGenre'),
        cName: txt('cName'), cTemplate: txt('cTemplate'), cGenre: txt('cGenre'),
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;
const snap = async (ms = 200) => { if (ms) await sleep(ms); return evalJs(SNAP); };

async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

let navSeq = 0;
async function goto(urlPath, width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}${urlPath}?_probe=` + (++navSeq) });
}
/* 探索页看 Alpine 的 booting；编辑页看骨架是否被摘掉 */
const waitSettled = (isAlpine) => waitFor(async () => {
    try {
        if (isAlpine) {
            return (await evalJs(`(() => { const r = document.querySelector('[x-data]');
                const d = r && window.Alpine ? window.Alpine.$data(r) : null; return !!d && d.booting === false; })()`)) === true;
        }
        return (await evalJs(`!document.getElementById('bootSkel')`)) === true;
    } catch (e) { return false; }
});
const waitSkeleton = () => waitFor(async () => {
    try { return (await evalJs(`document.querySelectorAll('.sc-skel').length`)) > 0; } catch (e) { return false; }
}, 15000, 100);

let lastCount = 0;
function checkOnce(label) {
    const delta = state.requests - lastCount;
    lastCount = state.requests;
    check(label + ' 本次加载只请求了一次引导数据', delta === 1, 'delta=' + delta);
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

    /* ============ G1 探索页 ============ */
    console.log('\n===== G1 提示词探索页 =====');
    console.log('— 挂住引导数据 —');
    state.mode = 'explore'; state.hold = true; state.fail = false; state.held = null;
    await goto('/prompts/explore');
    await waitSkeleton();
    let s = await snap(500);
    check('G1 【核心】挂住时 DOM 已 readyState=complete', s.readyState === 'complete', 'readyState=' + s.readyState);
    check('G1 【核心】骨架已渲染（不是白屏）', s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('G1 【核心】模板名/步骤徽标都还是空',
        !s.templateName && !s.pageStep, `name=${JSON.stringify(s.templateName)} step=${s.pageStep}`);
    check('G1 首屏无横向溢出', s.docScrollW <= s.docClientW + 1);
    await shot('pm-01-explore-skeleton');

    console.log('— 放行 —');
    check('G1 引导数据请求确实被挂住过（说明是异步发的）', release());
    check('G1 【核心】boot 结束', (await waitSettled(true)) === true);
    checkOnce('G1');
    s = await snap(400);
    check('G1 骨架全部退场', s.skelVisible === 0);
    check('G1 【核心】模板名铺上了', s.templateName === EXPLORE_DATA.templateName, 'name=' + s.templateName);
    check('G1 【核心】步骤/子步骤徽标数据到位',
        s.pageStep === 'CHAPTER_WRITING', 'step=' + s.pageStep);
    check('G1 【核心】变量徽标 3 个、模板正文非空', s.varCount === 3 && s.tmplLen > 10,
        `vars=${s.varCount} len=${s.tmplLen}`);
    await shot('pm-02-explore-loaded');

    console.log('— 引导数据 500 —');
    const errBefore1 = runtimeErrors.length;
    state.fail = true; lastCount = state.requests;
    await goto('/prompts/explore');
    check('G1 【核心】500 时同样会收骨架', (await waitSettled(true)) === true);
    checkOnce('G1-500');
    s = await snap(300);
    check('G1 页面没崩、骨架收掉', s.skelVisible === 0);
    check('G1 【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore1,
        runtimeErrors.slice(errBefore1).join(' | '));

    /* ============ G2 内置模板（只读） ============ */
    console.log('\n===== G2 内置模板只读页 =====');
    state.mode = 'edit-builtin'; state.hold = true; state.fail = false; state.held = null;
    await goto('/prompts/builtin/world-facet/edit');
    await waitSkeleton();
    s = await snap(500);
    check('G2 【核心】骨架在、只读区与编辑表单都没露',
        s.skelExists === true && !s.builtinVisible && !s.customVisible,
        `skel=${s.skelExists} builtin=${s.builtinVisible} custom=${s.customVisible}`);
    await shot('pm-03-builtin-skeleton');

    check('G2 引导数据请求确实被挂住过', release());
    check('G2 【核心】骨架被摘掉', (await waitSettled(false)) === true);
    checkOnce('G2');
    s = await snap(400);
    check('G2 骨架全部退场', s.skelExists === false && s.skelVisible === 0);
    check('G2 【核心】只读区露出、编辑表单不露', s.builtinVisible === true && s.customVisible === false);
    check('G2 【核心】标题改成「查看内置Prompt模板」',
        s.titleText.indexOf('查看内置') >= 0, 'title=' + s.titleText);
    check('G2 【核心】名称/正文/题材都填对',
        s.bName === EDIT_BUILTIN.name && s.bTemplate === EDIT_BUILTIN.template
        && s.bGenre === '通用', `name=${s.bName} genre=${s.bGenre}`);
    check('G2 【核心】只读模式不出现删除按钮', s.delVisible === false);
    check('G2 没有误报「模板加载失败」', s.loadErrorVisible === false);
    await shot('pm-04-builtin-loaded');

    /* ============ G3 自定义模板 ============ */
    console.log('\n===== G3 自定义模板编辑页 =====');
    state.mode = 'edit-custom'; state.hold = false; state.fail = false; state.held = null;
    lastCount = state.requests;
    await goto('/prompts/42/edit');
    check('G3 【核心】boot 结束', (await waitSettled(false)) === true);
    checkOnce('G3');
    s = await snap(400);
    check('G3 骨架退场', s.skelExists === false);
    check('G3 【核心】编辑表单露出、只读区不露', s.customVisible === true && s.builtinVisible === false);
    check('G3 【核心】表单 action 指向 /prompts/42', s.formAction === '/prompts/42', 'action=' + s.formAction);
    check('G3 【核心】名称/正文/题材都填对',
        s.cName === EDIT_CUSTOM.name && s.cTemplate === EDIT_CUSTOM.template
        && s.cGenre === '玄幻', `name=${s.cName} genre=${s.cGenre}`);
    check('G3 【核心】自定义模式出现删除按钮', s.delVisible === true);
    await shot('pm-05-custom-loaded');

    /* ============ 390px ============ */
    console.log('\n===== 390px 窄屏 =====');
    state.hold = true; state.held = null;
    await goto('/prompts/explore', 390, 780);
    await waitSkeleton();
    s = await snap(400);
    check('窄屏（探索页骨架）无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW}`);
    await shot('pm-06-mobile-skeleton');
    release();
    await waitSettled(true);
    s = await snap(300);
    check('窄屏（探索页数据态）无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW}`);
    await shot('pm-07-mobile-loaded');

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

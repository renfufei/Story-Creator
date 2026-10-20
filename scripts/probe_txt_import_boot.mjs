#!/usr/bin/env node
/**
 * TXT 导入页：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 *   页面 /import/txt
 *   数据 /import/txt/data → { splitConfigs, genres, modelConfigs }
 *
 * 原来这里同步 XHR 取「章节分割策略 + 题材」，浏览器停在 HTML 解析期等这一趟，首屏白屏。
 * 现在先给空壳（x-show="booting" 骨架），数据到了再铺，并且**默认勾选集合也在数据到了之后才算**
 * （原来靠构造期同步就绪，改异步后如果把这段留在构造期，勾选永远是空的）。
 *
 * 守护点：
 *   P0 【核心】挂住引导数据：DOM 已 complete、骨架在、表单卡片还没出
 *   P1 【核心】放行：骨架退场、题材下拉吃到异步选项、自定义配置复选框渲染、默认勾选集合算对
 *   P2 【核心】引导数据 500：页面不崩、骨架收掉、勾选退化为空
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 每次加载只请求一次引导数据
 *
 * ★ 自带静态服务器，不依赖 1888（其静态资源处理当前无响应）。
 *
 * 用法：node scripts/probe_txt_import_boot.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9383;
const HTTP_PORT = 18904;
const SHOT_DIR = '/tmp/txt-boot-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC_ROOTS = [path.join(ROOT, 'src/main/resources/static'), path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假数据 ==================== */
const TXT_DATA = {
    splitConfigs: [
        { id: '1', name: '按阿拉伯数字', builtin: true, enabled: true },
        { id: '2', name: '按卷章', builtin: true, enabled: true },
        { id: '7', name: '自定义正则 A', description: '第X章', builtin: false, enabled: true },
        { id: '8', name: '自定义正则 B', description: 'Part X', builtin: false, enabled: false }
    ],
    genres: [
        { name: 'FANTASY', displayName: '玄幻' },
        { name: 'OTHER', displayName: '其他' }
    ],
    modelConfigs: []
};

/* ==================== 静态服务器 ==================== */
let holdData = false, failData = false, heldRes = null, dataRequests = 0;

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
    if (pn === '/import/txt') return serveStatic(res, 'pages/txt-import.html');
    if (pn === '/import/txt/data') {
        dataRequests++;
        if (holdData) { heldRes = res; return; }
        if (failData) return json(res, { error: '引导数据炸了' }, 500);
        return json(res, TXT_DATA);
    }
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

function releaseData() {
    if (!heldRes) return false;
    const res = heldRes;
    heldRes = null;
    holdData = false;
    json(res, TXT_DATA);
    return true;
}

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'txt-boot-probe-'));
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
    const genreSel = Array.from(document.querySelectorAll('select')).find(s =>
        s.querySelector('option[value="FANTASY"]')) || document.querySelector('select');
    return {
        readyState: document.readyState,
        booting: st ? st.booting : null,
        splitCount: st ? Array.from(st.splitConfigs).length : -1,
        genreCount: st ? Array.from(st.genres).length : -1,
        selected: st ? Array.from(st.selectedConfigIds).map(Number) : [],
        skelVisible: Array.from(document.querySelectorAll('.sc-skel')).filter(vis).length,
        cardVisible: vis(document.querySelector('.card')),
        headVisible: vis(document.querySelector('h2')),
        genreOptions: genreSel ? genreSel.options.length : -1,
        customBoxes: document.querySelectorAll('input[type="checkbox"][id^="config-"]').length,
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
async function goto(width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}/import/txt?_probe=` + (++navSeq) });
}
const waitSettled = () => waitFor(async () => {
    try { return (await evalJs(`(() => { const r = document.querySelector('[x-data]');
        const d = r && window.Alpine ? window.Alpine.$data(r) : null; return !!d && d.booting === false; })()`)) === true; }
    catch (e) { return false; }
});
const waitSkeleton = () => waitFor(async () => {
    try { return (await evalJs(`document.querySelectorAll('.sc-skel').length`)) > 0; } catch (e) { return false; }
}, 15000, 100);

let lastCount = 0;
function checkOnce(label) {
    const delta = dataRequests - lastCount;
    lastCount = dataRequests;
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

    /* ---------- P0 ---------- */
    console.log('\n— P0 把引导数据挂住：首屏仍要出来 —');
    holdData = true;
    await goto();
    await waitSkeleton();
    let s = await snap(500);
    check('【核心】挂住时 DOM 已 readyState=complete', s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】骨架已渲染（不是白屏）', s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】表单卡片还没露出', s.cardVisible === false, 'card=' + s.cardVisible);
    check('【核心】默认勾选集合还是空', s.selected.length === 0, 'selected=' + s.selected.join(','));
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('txt-01-skeleton');

    /* ---------- P1 ---------- */
    console.log('\n— P1 放行：骨架退场、表单铺好 —');
    check('引导数据请求确实被挂住过（说明是异步发的）', releaseData());
    check('【核心】boot 结束（booting 转 false）', (await waitSettled()) === true);
    checkOnce('P1');
    s = await snap(400);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】表单卡片露出', s.cardVisible === true && s.headVisible === true);
    check('【核心】分割配置 4 条、题材 2 个都到了', s.splitCount === 4 && s.genreCount === 2,
        `split=${s.splitCount} genres=${s.genreCount}`);
    check('【核心】题材下拉吃到异步选项（未指定 + 2）', s.genreOptions === 3, 'options=' + s.genreOptions);
    check('【核心】自定义正则只渲染 2 个复选框', s.customBoxes === 2, 'boxes=' + s.customBoxes);
    check('【核心】默认勾选 = 内置全部 + 启用的自定义（1,2,7）',
        s.selected.length === 3 && s.selected.includes(1) && s.selected.includes(2) && s.selected.includes(7)
        && !s.selected.includes(8), 'selected=' + s.selected.join(','));
    await shot('txt-02-loaded');

    /* ---------- P2 500 ---------- */
    console.log('\n— P2 引导数据 500：页面不崩 —');
    const errBefore = runtimeErrors.length;
    failData = true;
    lastCount = dataRequests;
    await goto();
    check('【核心】500 时 booting 仍会转 false（不卡骨架）', (await waitSettled()) === true);
    checkOnce('P2');
    s = await snap(300);
    check('页面没崩：骨架收掉、标题还在', s.skelVisible === 0 && s.headVisible === true);
    check('【核心】没有配置也算得干净（空集合、不报错）',
        s.splitCount === 0 && s.selected.length === 0, `split=${s.splitCount} sel=${s.selected.join(',')}`);
    check('【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    await shot('txt-03-degraded');

    /* ---------- P3 390px ---------- */
    console.log('\n— P3 390px 窄屏 —');
    failData = false;
    holdData = true;
    await goto(390, 780);
    await waitSkeleton();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('txt-04-mobile-skeleton');
    releaseData();
    await waitSettled();
    s = await snap(400);
    check('窄屏数据态无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏表单同样铺好', s.cardVisible === true && s.selected.length === 3);
    await shot('txt-05-mobile-loaded');

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

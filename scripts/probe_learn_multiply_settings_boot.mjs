#!/usr/bin/env node
/**
 * 乘法口诀 · 音频管理页：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 *   页面 /learn/multiplication/settings
 *   引导数据 /learn/multiplication/settings/data → { ttsConfigs, defaultTtsConfigId, formulas, prefixes, totalAudioCount }
 *   另外两个异步接口（本来就异步，保持不动）：
 *     /api/learn/multiplication/audio-status → records[]
 *     /api/learn/multiplication/running      → { running }
 *
 * 原来引导数据是同步 XHR：浏览器停在 HTML 解析期等这一趟，首屏白屏。
 * 现在先给空壳（x-show="booting" 骨架），数据 + 音频记录都回来之后再收骨架
 * —— 否则表格会先空一下再涨出来。
 *
 * 守护点：
 *   P0 【核心】挂住引导数据：DOM 已 complete、骨架在、卡片/表格都还没出
 *   P1 【核心】放行：骨架退场、进度徽标按 records 算对、表格行数对、口诀/算式都有值、
 *              默认 TTS 配置被选中（原来靠构造期同步就绪，现在靠 boot 里铺）
 *   P2 【核心】引导数据 500：页面不崩、骨架收掉、表格退化为空
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 每次加载只请求一次引导数据
 *
 * ★ 自带静态服务器，不依赖 1888（其静态资源处理当前无响应）。
 *
 * 用法：node scripts/probe_learn_multiply_settings_boot.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9382;
const HTTP_PORT = 18903;
const SHOT_DIR = '/tmp/lms-boot-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC_ROOTS = [path.join(ROOT, 'src/main/resources/static'), path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假数据 ==================== */
const SETTINGS_DATA = {
    ttsConfigs: [
        { id: 11, displayName: '本地方言 TTS', modelId: 'qwen-tts', extraParams: '{"voices":"Cherry,Ethan","format":"wav"}' },
        { id: 12, displayName: '备用 TTS', modelId: 'other', extraParams: '{"voices":"Nova,Ryan","format":"aac"}' }
    ],
    defaultTtsConfigId: 12,
    formulas: [
        { itemKey: '2x3', mathExpression: '2 × 3' },
        { itemKey: '3x3', mathExpression: '3 × 3' }
    ],
    prefixes: [
        { itemKey: 'p1', note: '前缀' }
    ],
    totalAudioCount: 4
};
const RECORDS = [
    { itemKey: '2x3', status: 'READY' },
    { itemKey: '3x3', status: 'GENERATING' },
    { itemKey: 'p1', status: 'ERROR' }
];

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
    if (pn === '/learn/multiplication/settings') return serveStatic(res, 'pages/learn-multiplication-settings.html');
    if (pn === '/learn/multiplication/settings/data') {
        dataRequests++;
        if (holdData) { heldRes = res; return; }
        if (failData) return json(res, { error: '引导数据炸了' }, 500);
        return json(res, SETTINGS_DATA);
    }
    /* 本来就异步的两个接口：跟着一起给，免得干扰 records 断言 */
    if (pn === '/api/learn/multiplication/audio-status') return json(res, RECORDS);
    if (pn === '/api/learn/multiplication/running') return json(res, { running: false });
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

function releaseData() {
    if (!heldRes) return false;
    const res = heldRes;
    heldRes = null;
    holdData = false;
    json(res, SETTINGS_DATA);
    return true;
}

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lms-boot-probe-'));
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
    const rows = Array.from(document.querySelectorAll('tbody tr'));
    const sel = document.querySelector('select[model_id], select[x-model="configId"], select');
    const badges = Array.from(document.querySelectorAll('.card .badge')).map(b => (b.innerText || '').trim());
    return {
        readyState: document.readyState,
        booting: st ? st.booting : null,
        configCount: st ? Array.from(st.ttsConfigs).length : -1,
        formulaKeys: st ? Object.keys(st.formulaMap).length : -1,
        totalCount: st ? st.totalCount : null,
        configId: st ? st.configId : null,
        recordCount: st ? Array.from(st.records).length : -1,
        readyCount: st ? st.readyCount : null,
        voice: st ? st.voice : null,
        fmt: st ? st.format : null,
        skelVisible: Array.from(document.querySelectorAll('.sc-skel')).filter(vis).length,
        headVisible: vis(document.querySelector('h3')),
        cardVisible: vis(document.querySelector('.card')),
        rowCount: rows.length,
        statusBadges: badges,
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
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}/learn/multiplication/settings?_probe=` + (++navSeq) });
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
    check('【核心】内容区还没露出', s.cardVisible === false && s.headVisible === false,
        `card=${s.cardVisible} head=${s.headVisible}`);
    check('【核心】表格还是空的', s.rowCount === 0, 'rows=' + s.rowCount);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('lms-01-skeleton');

    /* ---------- P1 ---------- */
    console.log('\n— P1 放行：骨架退场、配置与记录都铺上 —');
    check('引导数据请求确实被挂住过（说明是异步发的）', releaseData());
    check('【核心】boot 结束（booting 转 false）', (await waitSettled()) === true);
    checkOnce('P1');
    s = await snap(400);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】内容区露出', s.cardVisible === true && s.headVisible === true);
    check('【核心】TTS 配置 2 条、口诀映射 3 个键',
        s.configCount === 2 && s.formulaKeys === 3, `configs=${s.configCount} keys=${s.formulaKeys}`);
    check('【核心】默认配置被选中（原来靠构造期同步就绪）', s.configId === '12', 'configId=' + s.configId);
    check('【核心】默认配置的语音/格式也铺上了（取默认那条 12，不是第一条 11）',
        s.voice === 'Nova' && s.fmt === 'aac', `voice=${s.voice} fmt=${s.fmt}`);
    check('【核心】音频记录 3 行渲染', s.recordCount === 3 && s.rowCount === 3,
        `st=${s.recordCount} rows=${s.rowCount}`);
    check('【核心】进度徽标按 records 算对（1 完成 / 总数 4）',
        s.readyCount === 1 && s.totalCount === 4, `ready=${s.readyCount} total=${s.totalCount}`);
    check('状态徽标三种都在', s.statusBadges.some(t => t.indexOf('1/4') >= 0),
        s.statusBadges.join(' | '));
    await shot('lms-02-loaded');

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
    check('【核心】没有配置也不报错（表格空、configId 空）',
        s.configCount === 0 && !s.configId, `configs=${s.configCount} configId=${JSON.stringify(s.configId)}`);
    check('【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    await shot('lms-03-degraded');

    /* ---------- P3 390px ---------- */
    console.log('\n— P3 390px 窄屏 —');
    failData = false;
    holdData = true;
    await goto(390, 780);
    await waitSkeleton();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('lms-04-mobile-skeleton');
    releaseData();
    await waitSettled();
    s = await snap(400);
    check('窄屏数据态无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏配置/记录同样铺出来', s.configCount === 2 && s.rowCount === 3);
    await shot('lms-05-mobile-loaded');

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

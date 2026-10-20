#!/usr/bin/env node
/**
 * 成语填空页：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 *   页面 /learn/idiom-fill
 *   数据 /learn/idiom-fill/data →  { stages, levels }
 *
 * 原来 180 条成语是同步 XHR 拉的（gzip 约 20KB）：浏览器停在 HTML 解析期等这一趟，首屏白屏。
 * 现在先给空壳（模板里 x-show="booting" 的骨架），数据到了再铺场。
 *
 * 守护点：
 *   P0 【核心】挂住成语库：DOM 已 complete、骨架在、棋盘/学段页签都还没出
 *   P1 【核心】放行：骨架退场、4 条成语 + 4 个字块 + 3 个学段页签
 *   P2 【核心】成语库 500：页面不崩、骨架收掉、不显示棋盘
 *   P3 390px：骨架态/数据态都无横向溢出（答题盘是 position:fixed，别顶进正文）
 *   P4 每次加载只请求一次成语库
 *
 * ★ 自带静态服务器，不依赖 1888（其静态资源处理当前无响应）。
 *
 * 用法：node scripts/probe_learn_idiom_fill_boot.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9381;
const HTTP_PORT = 18902;
const SHOT_DIR = '/tmp/if-boot-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC_ROOTS = [path.join(ROOT, 'src/main/resources/static'), path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假成语库 ==================== */
const LV = (items) => ({ theme: '假数据关', items: items });
const IDIOM_DATA = {
    stages: [
        { id: 'primary', label: '小学', gradeLabel: '3-6 年级', levelCount: 2 },
        { id: 'junior', label: '初中', gradeLabel: '7-9 年级', levelCount: 1 },
        { id: 'senior', label: '高中', gradeLabel: '10-12 年级', levelCount: 1 }
    ],
    levels: {
        primary: [
            LV([
                { idiom: '画蛇添足', meaning: '多做反而坏事', source: '《战国策》' },
                { idiom: '守株待兔', meaning: '死守经验不知变通', source: '《韩非子》' },
                { idiom: '亡羊补牢', meaning: '出事后及时补救', source: '《战国策》' },
                { idiom: '刻舟求剑', meaning: '拘泥成法不知变化', source: '《吕氏春秋》' }
            ]),
            LV([{ idiom: '掩耳盗铃', meaning: '自欺欺人', source: '《吕氏春秋》' }])
        ],
        junior: [LV([{ idiom: '破釜沉舟', meaning: '下定决心不留退路', source: '《史记》' }])],
        senior: [LV([{ idiom: '焚膏继晷', meaning: '夜以继日地勤奋', source: '韩愈' }])]
    }
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

const server = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (pn === '/learn/idiom-fill') return serveStatic(res, 'pages/learn-idiom-fill.html');
    if (pn === '/learn/idiom-fill/data') {
        dataRequests++;
        if (holdData) { heldRes = res; return; }
        if (failData) {
            res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
            return res.end(JSON.stringify({ error: '成语库炸了' }));
        }
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify(IDIOM_DATA));
    }
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

function releaseData() {
    if (!heldRes) return false;
    const res = heldRes;
    heldRes = null;
    holdData = false;
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(IDIOM_DATA));
    return true;
}

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'if-boot-probe-'));
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
    const root = document.querySelector('.ic-root');
    const st = (root && window.Alpine) ? window.Alpine.$data(root) : null;
    const board = document.querySelector('.ic-board') || document.querySelector('.ic-tiles');
    let boardPos = null;
    if (board) { const cs = getComputedStyle(board); boardPos = cs.position; }
    return {
        readyState: document.readyState,
        booting: st ? st.booting : null,
        stageCount: st ? Array.from(st.stages).length : -1,
        itemCount: st ? Array.from(st.items).length : -1,
        tileCount: st ? Array.from(st.tiles).length : -1,
        skelVisible: Array.from(document.querySelectorAll('.sc-skel')).filter(vis).length,
        toolbarVisible: vis(document.querySelector('.ic-toolbar')),
        stagesVisible: vis(document.querySelector('.ic-stages')),
        playVisible: vis(document.querySelector('.ic-play')),
        domItems: document.querySelectorAll('.ic-item').length,
        domTiles: document.querySelectorAll('.ic-tile').length,
        domStages: document.querySelectorAll('.ic-stage').length,
        firstIdiom: (document.querySelector('.ic-item') || {}).innerText || '',
        boardPos: boardPos,
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
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}/learn/idiom-fill?_probe=` + (++navSeq) });
}
const waitSettled = () => waitFor(async () => {
    try { return (await evalJs(`(() => { const r = document.querySelector('.ic-root');
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
    check(label + ' 本次加载只请求了一次成语库', delta === 1, 'delta=' + delta);
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
    console.log('\n— P0 把成语库挂住：首屏仍要出来 —');
    holdData = true;
    await goto();
    await waitSkeleton();
    let s = await snap(500);
    check('【核心】成语库挂着时 DOM 已 readyState=complete', s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】骨架已渲染（不是白屏）', s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】工具栏/学段/答题盘都还没露出',
        s.toolbarVisible === false && s.stagesVisible === false && s.playVisible === false,
        `toolbar=${s.toolbarVisible} stages=${s.stagesVisible} play=${s.playVisible}`);
    check('【核心】还没渲染任何成语', s.domItems === 0 && s.domStages === 0,
        `items=${s.domItems} stages=${s.domStages}`);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('if-01-skeleton');

    /* ---------- P1 ---------- */
    console.log('\n— P1 放行成语库：骨架退场、答题盘出来 —');
    check('成语库请求确实被挂住过（说明是异步发的）', releaseData());
    check('【核心】boot 结束（booting 转 false）', (await waitSettled()) === true);
    checkOnce('P1');
    s = await snap(400);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】工具栏 + 学段页签 + 答题盘都露出',
        s.toolbarVisible && s.stagesVisible && s.playVisible,
        `toolbar=${s.toolbarVisible} stages=${s.stagesVisible} play=${s.playVisible}`);
    check('【核心】4 条成语渲染', s.domItems === 4 && s.itemCount === 4, `dom=${s.domItems} st=${s.itemCount}`);
    check('【核心】4 个字块渲染', s.domTiles === 4, 'tiles=' + s.domTiles);
    check('【核心】3 个学段页签渲染', s.domStages === 3, 'stages=' + s.domStages);
    check('成语内容确实铺上去了', s.firstIdiom.length >= 4, 'first=' + s.firstIdiom.replace(/\s+/g, ' ').slice(0, 20));
    await shot('if-02-loaded');

    /* ---------- P2 500 ---------- */
    console.log('\n— P2 成语库 500：页面不崩 —');
    const errBefore = runtimeErrors.length;
    failData = true;
    lastCount = dataRequests;
    await goto();
    check('【核心】500 时 booting 仍会转 false（不卡骨架）', (await waitSettled()) === true);
    checkOnce('P2');
    s = await snap(300);
    check('页面没崩：骨架收掉', s.skelVisible === 0);
    check('【核心】没有关卡时不显示答题盘', s.playVisible === false, 'play=' + s.playVisible);
    check('【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    await shot('if-03-degraded');

    /* ---------- P3 390px ---------- */
    console.log('\n— P3 390px 窄屏 —');
    failData = false;
    holdData = true;
    await goto(390, 780);
    await waitSkeleton();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('if-04-mobile-skeleton');
    releaseData();
    await waitSettled();
    s = await snap(400);
    check('窄屏数据态无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏答题盘同样铺出来', s.playVisible === true && s.domItems === 4);
    await shot('if-05-mobile-loaded');

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

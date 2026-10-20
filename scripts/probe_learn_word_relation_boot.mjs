#!/usr/bin/env node
/**
 * 近义词 / 反义词匹配页：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 *   同一张静态页两个路由：
 *     /learn/synonym-match   → mode=synonym（近义词）
 *     /learn/antonym-match   → mode=antonym（反义词）
 *   数据 /learn/word-relation/data?mode=synonym|antonym
 *
 * 原来词库是同步 XHR：浏览器停在 HTML 解析期等这一趟（540 对，gzip 三十几 KB），首屏白屏。
 * 现在先给空壳（模板里 x-show="booting" 的骨架），数据到了再铺场。
 * 模式判定仍走 pathname（两个路由是 forward 到同一张页，地址栏里没有 ?mode=）。
 *
 * 守护点：
 *   P0 【核心】挂住词库：DOM 已 complete、骨架在、棋盘/学段页签都还没出
 *   P1 【核心】放行词库（近义词路由）：骨架退场、6 对题面 + 2 个学段页签、标题=近义词匹配
 *   P1b【核心】换到反义词路由：mode/label/请求参数都跟着切对（pathname 判定没被异步改坏）
 *   P2 【核心】词库 500：页面不崩、骨架收掉、不显示棋盘
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 每次加载只请求一次词库
 *
 * ★ 自带静态服务器，不依赖 1888（其静态资源处理当前无响应）。
 *
 * 用法：node scripts/probe_learn_word_relation_boot.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9380;
const HTTP_PORT = 18901;
const SHOT_DIR = '/tmp/wr-boot-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC_ROOTS = [path.join(ROOT, 'src/main/resources/static'), path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假词库 ==================== */
const PAIRS = (list) => ({ theme: '假数据关', items: list });
const SYN = {
    mode: 'synonym', label: '近义词',
    stages: [
        { id: 'primary', label: '小学', gradeLabel: '3-6 年级', levelCount: 2, source: '人教版' },
        { id: 'junior', label: '初中', gradeLabel: '7-9 年级', levelCount: 2, source: '人教版' }
    ],
    levels: {
        primary: [
            PAIRS([
                { word: '高兴', match: '愉快', note: '都表示心情好' },
                { word: '美丽', match: '漂亮', note: '都可形容外观' },
                { word: '安静', match: '寂静', note: '都可形容环境' },
                { word: '立刻', match: '马上', note: '都表示时间短' },
                { word: '帮助', match: '协助', note: '都可表示帮扶' },
                { word: '清楚', match: '清晰', note: '都可表示明白' }
            ]),
            PAIRS([
                { word: '忽然', match: '突然', note: '都表示出乎意料' },
                { word: '快乐', match: '欢乐', note: '都表示高兴' }
            ])
        ],
        junior: [PAIRS([{ word: '诚挚', match: '诚恳', note: '都可形容态度' }])]
    }
};
const ANT = {
    mode: 'antonym', label: '反义词',
    stages: [
        { id: 'primary', label: '小学', gradeLabel: '3-6 年级', levelCount: 2, source: '人教版' },
        { id: 'junior', label: '初中', gradeLabel: '7-9 年级', levelCount: 2, source: '人教版' }
    ],
    levels: {
        primary: [
            PAIRS([
                { word: '高大', match: '矮小', note: '外形相反' },
                { word: '明亮', match: '昏暗', note: '光线相反' },
                { word: '热闹', match: '冷清', note: '场面相反' },
                { word: '谦虚', match: '骄傲', note: '态度相反' },
                { word: '前进', match: '后退', note: '方向相反' },
                { word: '粗糙', match: '光滑', note: '触感相反' }
            ]),
            PAIRS([{ word: '宽阔', match: '狭窄', note: '宽度相反' }])
        ],
        junior: [PAIRS([{ word: '慷慨', match: '吝啬', note: '气度相反' }])]
    }
};

/* ==================== 静态服务器 ==================== */
let holdData = false, failData = false, heldRes = null, dataRequests = 0;
let lastModeQuery = null, currentPayload = SYN;

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
    const u = new URL(req.url, 'http://127.0.0.1');
    const pn = decodeURIComponent(u.pathname);
    if (pn === '/learn/synonym-match' || pn === '/learn/antonym-match') {
        return serveStatic(res, 'pages/learn-word-relation.html');
    }
    if (pn === '/learn/word-relation/data') {
        dataRequests++;
        lastModeQuery = u.searchParams.get('mode');
        if (holdData) { heldRes = res; return; }
        if (failData) {
            res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
            return res.end(JSON.stringify({ error: '词库炸了' }));
        }
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify(currentPayload));
    }
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

function releaseData() {
    if (!heldRes) return false;
    const res = heldRes;
    heldRes = null;
    holdData = false;
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(currentPayload));
    return true;
}

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wr-boot-probe-'));
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
    const root = document.querySelector('.wr-root');
    const st = (root && window.Alpine) ? window.Alpine.$data(root) : null;
    return {
        readyState: document.readyState,
        booting: st ? st.booting : null,
        mode: st ? st.mode : null,
        label: st ? st.label : null,
        stageCount: st ? Array.from(st.stages).length : -1,
        itemCount: st ? Array.from(st.items).length : -1,
        levelCount: st ? st.levelCount : -1,
        skelVisible: Array.from(document.querySelectorAll('.sc-skel')).filter(vis).length,
        toolbarVisible: vis(document.querySelector('.wr-toolbar')),
        stagesVisible: vis(document.querySelector('.wr-stages')),
        playVisible: vis(document.querySelector('.wr-play')),
        domItems: document.querySelectorAll('.wr-item').length,
        domStages: document.querySelectorAll('.wr-stage').length,
        docTitle: document.title,
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
const waitSettled = () => waitFor(async () => {
    try { return (await evalJs(`(() => { const r = document.querySelector('.wr-root');
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
    check(label + ' 本次加载只请求了一次词库', delta === 1, 'delta=' + delta);
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

    /* ---------- P0 挂住词库 ---------- */
    console.log('\n— P0 把词库挂住：首屏仍要出来 —');
    holdData = true;
    currentPayload = SYN;
    await goto('/learn/synonym-match');
    await waitSkeleton();
    let s = await snap(500);
    check('【核心】词库挂着时 DOM 已 readyState=complete', s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】骨架已渲染（不是白屏）', s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】工具栏/学段/棋盘都还没露出',
        s.toolbarVisible === false && s.stagesVisible === false && s.playVisible === false,
        `toolbar=${s.toolbarVisible} stages=${s.stagesVisible} play=${s.playVisible}`);
    check('【核心】还没渲染任何题面', s.domItems === 0 && s.domStages === 0,
        `items=${s.domItems} stages=${s.domStages}`);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('wr-01-skeleton');

    /* ---------- P1 放行（近义词） ---------- */
    console.log('\n— P1 放行词库：骨架退场、棋盘出来 —');
    check('词库请求确实被挂住过（说明是异步发的）', releaseData());
    check('【核心】boot 结束（booting 转 false）', (await waitSettled()) === true);
    checkOnce('P1');
    s = await snap(400);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】工具栏 + 学段页签 + 棋盘都露出',
        s.toolbarVisible && s.stagesVisible && s.playVisible,
        `toolbar=${s.toolbarVisible} stages=${s.stagesVisible} play=${s.playVisible}`);
    check('【核心】6 对题面全部渲染', s.domItems === 6 && s.itemCount === 6, `dom=${s.domItems} st=${s.itemCount}`);
    check('【核心】2 个学段页签渲染', s.domStages === 2, 'stages=' + s.domStages);
    check('【核心】近义词路由 ⇒ mode/label 正确', s.mode === 'synonym' && s.label === '近义词',
        `mode=${s.mode} label=${s.label}`);
    check('【核心】标题按模式改写', s.docTitle.indexOf('近义词匹配') === 0, 'title=' + s.docTitle);
    check('请求带上了 mode=synonym', lastModeQuery === 'synonym', 'query=' + lastModeQuery);
    await shot('wr-02-loaded');

    /* ---------- P1b 反义词路由 ---------- */
    console.log('\n— P1b 换到反义词路由：模式判定要跟着走 —');
    currentPayload = ANT;
    lastCount = dataRequests;
    await goto('/learn/antonym-match');
    await waitSettled();
    checkOnce('P1b');
    s = await snap(400);
    check('【核心】反义词路由 ⇒ mode/label 正确', s.mode === 'antonym' && s.label === '反义词',
        `mode=${s.mode} label=${s.label}`);
    check('【核心】标题改成反义词匹配', s.docTitle.indexOf('反义词匹配') === 0, 'title=' + s.docTitle);
    check('【核心】请求带上了 mode=antonym（不只靠前端改写）', lastModeQuery === 'antonym', 'query=' + lastModeQuery);
    check('棋盘同样铺出来', s.domItems === 6 && s.playVisible === true, `items=${s.domItems}`);

    /* ---------- P2 词库 500 ---------- */
    console.log('\n— P2 词库 500：页面不崩 —');
    const errBefore = runtimeErrors.length;
    failData = true;
    lastCount = dataRequests;
    await goto('/learn/synonym-match');
    check('【核心】500 时 booting 仍会转 false（不卡骨架）', (await waitSettled()) === true);
    checkOnce('P2');
    s = await snap(300);
    check('页面没崩：骨架收掉', s.skelVisible === 0);
    check('【核心】没有关卡时不显示棋盘', s.playVisible === false, 'play=' + s.playVisible);
    check('【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    await shot('wr-03-degraded');

    /* ---------- P3 390px ---------- */
    console.log('\n— P3 390px 窄屏 —');
    failData = false;
    currentPayload = SYN;
    holdData = true;
    await goto('/learn/synonym-match', 390, 780);
    await waitSkeleton();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('wr-04-mobile-skeleton');
    releaseData();
    await waitSettled();
    s = await snap(400);
    check('窄屏数据态无横向溢出', s.docScrollW <= s.docClientW + 1, `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏棋盘同样铺出来', s.playVisible === true && s.domItems === 6);
    await shot('wr-05-mobile-loaded');

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

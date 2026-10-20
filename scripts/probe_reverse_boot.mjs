#!/usr/bin/env node
/**
 * 逆向工程两页：**引导数据异步化 + 骨架**（2026-10-20）。
 *
 *   /projects/{id}/reverse/options   逆向选项（开始 / 继续入口）
 *   /projects/{id}/reverse/progress  执行监控（状态徽标 + 实时输出区）
 *
 * 两页原来各自在顶部跑同步 XHR 注入 window.__REVERSE_DATA__：浏览器停在 HTML
 * 解析期等往返，首屏白屏。现在改由 /js/reverse.js 的 loadReverseData() 异步取，
 * 组件 boot() 里 await 完再铺字段；引导数据在路上时先出骨架。
 *
 * 逐页守护：挂住 ⇒ DOM 已 complete、骨架在、标题没露；放行 ⇒ 骨架退场、
 * 选项回填（含 false 值）、plan 扫描铺上、按钮文案正确；500 ⇒ bootError 警告、不崩。
 * 另外：390px 无横向溢出；每次加载只请求一次引导数据。
 *
 * ★ 自带静态服务器，不依赖 1888（其静态资源处理当前无响应）。
 *
 * 用法：node scripts/probe_reverse_boot.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9390;
const HTTP_PORT = 18910;
const SHOT_DIR = '/tmp/reverse-boot-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC_ROOTS = [path.join(ROOT, 'src/main/resources/static'), path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假数据 ==================== */
/* 引导数据：故意把 runCharacters 设为 false，验证「false 值也能回填」而不是被默认 true 盖掉 */
const REVERSE_DATA = {
    jobId: 33,
    title: '测试小说',
    status: 'RE_INTERRUPTED',
    runWorldBuilding: true,
    runCharacters: false,
    runOutline: true,
    chaptersPerVolume: 12,
    modelConfigId: 5,
    modelConfigs: [{ id: 5, displayName: 'GPT-4o', provider: 'openai' }]
};
/* 进度扫描：completedUnits=4 / totalUnits=10 ⇒ 「继续执行（剩余 6 项）」 */
const PLAN = {
    completedUnits: 4, totalUnits: 10, totalChapters: 24, totalVolumes: 2, chaptersPerVolume: 12,
    resumable: true, completed: false, interrupted: true,
    phases: [
        { phase: 'GENRE', status: 'COMPLETED', totalUnits: 1, completedUnits: 1, runnable: true },
        { phase: 'CHAPTER_OUTLINE', status: 'COMPLETED', totalUnits: 24, completedUnits: 24, runnable: true },
        { phase: 'WORLD', status: 'PARTIAL', totalUnits: 1, completedUnits: 0, runnable: true }
    ]
};
/* status 探测返回未运行 ⇒ 走静态兜底文案，不接 SSE（探针里不测流） */
const STATUS = { active: false, status: 'RE_PHASE_WORLD' };

/* ==================== 页面清单 ==================== */
const PAGES = [
    {
        tag: '选项页', route: '/projects/01/reverse/options',
        file: 'pages/reverse/options.html',
        snap: `(() => {
            const root = document.querySelector('[x-data]');
            const d = window.Alpine ? window.Alpine.$data(root) : {};
            return {
                bootError: d.bootError || '',
                jobTitle: d.jobTitle || '',
                cpv: d.chaptersPerVolume,
                runChars: d.runCharacters,
                runWorld: d.runWorldBuilding,
                modelId: String(d.modelConfigId || ''),
                optCount: (d.modelConfigs || []).length,
                planHeadline: d.planHeadline || '',
                startText: d.startButtonText || '',
                badge: d.plan ? ((d.plan.completedUnits||0) + '/' + (d.plan.totalUnits||0)) : '',
                phaseCount: (d.phases || []).length,
                startBtnVisible: (() => { const b = document.querySelector('.btn-primary'); return !!b && b.getClientRects().length > 0; })()
            };
        })()`,
        expect: (v) => {
            check('选项页 【核心】bootError 为空（合并清掉了空壳 error）', v.bootError === '', 'bootError=' + v.bootError);
            check('选项页 【核心】标题与 jobId 铺上', v.jobTitle === '测试小说', 'title=' + v.jobTitle);
            check('选项页 【核心】false 选项也回填（不被默认 true 盖掉）', v.runChars === false, 'runChars=' + v.runChars);
            check('选项页 true 选项回填', v.runWorld === true, 'runWorld=' + v.runWorld);
            check('选项页 【核心】每卷章节数取自引导数据', v.cpv === 12, 'cpv=' + v.cpv);
            check('选项页 模型下拉回填', v.modelId === '5' && v.optCount === 1, `id=${v.modelId} n=${v.optCount}`);
            check('选项页 【核心】plan 扫描铺上（徽标 4/10）', v.badge === '4/10', 'badge=' + v.badge);
            check('选项页 plan 标题 = 中断可继续', v.planHeadline.indexOf('中断') >= 0, 'head=' + v.planHeadline);
            check('选项页 阶段徽标 3 个', v.phaseCount === 3, 'phases=' + v.phaseCount);
            check('选项页 【核心】开始按钮文案 = 继续执行（剩余 6 项）',
                v.startText === '继续执行（剩余 6 项）', 'text=' + v.startText);
            check('选项页 开始按钮可见可点', v.startBtnVisible === true);
        }
    },
    {
        tag: '监控页', route: '/projects/01/reverse/progress',
        file: 'pages/reverse/progress.html',
        snap: `(() => {
            const root = document.querySelector('[x-data]');
            const d = window.Alpine ? window.Alpine.$data(root) : {};
            return {
                bootError: d.bootError || '',
                jobTitle: d.jobTitle || '',
                phaseText: d.phaseText || '',
                badgeClass: d.phaseBadgeClass || '',
                active: d.active,
                done: d.done,
                output: d.output || '',
                phaseCount: (d.phases || []).length,
                startBtnVisible: (() => { const bs = Array.from(document.querySelectorAll('.btn-primary')); return bs.some(b => b.getClientRects().length > 0 && b.textContent.indexOf('执行') >= 0); })(),
                stopBtnVisible: (() => { const bs = Array.from(document.querySelectorAll('.btn-outline-danger')); return bs.some(b => b.getClientRects().length > 0); })()
            };
        })()`,
        expect: (v) => {
            check('监控页 【核心】bootError 为空', v.bootError === '', 'bootError=' + v.bootError);
            check('监控页 【核心】标题铺上', v.jobTitle === '测试小说', 'title=' + v.jobTitle);
            check('监控页 【核心】静态兜底文案 = 已中断（可继续）',
                v.phaseText === '已中断（可继续）', 'text=' + v.phaseText);
            check('监控页 徽标 = 警告色', v.badgeClass === 'bg-warning', 'class=' + v.badgeClass);
            check('监控页 未在运行（不接 SSE）', v.active === false && v.done === false,
                `active=${v.active} done=${v.done}`);
            check('监控页 无残留输出', v.output === '', 'len=' + v.output.length);
            check('监控页 阶段圆圈 3 个', v.phaseCount === 3, 'phases=' + v.phaseCount);
            check('监控页 【核心】开始（继续）按钮露出', v.startBtnVisible === true);
            check('监控页 停止按钮隐藏（未在运行）', v.stopBtnVisible === false);
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

const DATA_ROUTE = '/projects/01/reverse/data';
const server = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (pn === cfg.route) return serveStatic(res, cfg.file);
    if (pn === DATA_ROUTE) {
        requests++;
        if (hold) { held = res; return; }
        if (fail) return json(res, { error: '引导数据炸了' }, 500);
        return json(res, REVERSE_DATA);
    }
    if (/^\/import\/txt\/\d+\/reverse-plan$/.test(pn)) return json(res, PLAN);
    if (/^\/import\/txt\/\d+\/status$/.test(pn)) return json(res, STATUS);
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

function release() {
    if (!held) return false;
    const res = held;
    held = null;
    hold = false;
    json(res, REVERSE_DATA);
    return true;
}

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'reverse-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + CDP_PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

let ws, msgId = 0;
const pending = new Map();
const runtimeErrors = [];

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
        skelExists: document.querySelectorAll('.sc-skel').length > 0,
        skelVisible: Array.from(document.querySelectorAll('.sc-skel')).filter(vis).length,
        headVisible: vis(document.querySelector('h2')),
        bootErrorShown: vis(document.querySelector('.alert-warning')),
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
    try { return (await evalJs(`(() => { const r = document.querySelector('[x-data]'); return window.Alpine ? window.Alpine.$data(r).booting : true; })()`)) === false; } catch (e) { return false; }
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
    check(`${p.tag} 【核心】标题还没露出（骨架遮住整个内容区）`, s.headVisible === false, 'head=' + s.headVisible);
    check(`${p.tag} 挂住时失败警告不可见`, s.bootErrorShown === false);
    check(`${p.tag} 首屏无横向溢出`, s.docScrollW <= s.docClientW + 1);
    await shot(`${p.tag}-01-skeleton`);

    console.log(`— ${p.tag} 放行 —`);
    check(`${p.tag} 引导数据请求确实被挂住过（说明是异步发的）`, release());
    check(`${p.tag} 【核心】骨架被摘掉（booting=false）`, (await waitSettled()) === true);
    checkOnce(p.tag);
    s = await baseSnap(300);
    check(`${p.tag} 骨架全部退场`, s.skelVisible === 0);
    check(`${p.tag} 【核心】标题露出`, s.headVisible === true);
    p.expect(await pageSnap(300));
    await shot(`${p.tag}-02-loaded`);

    console.log(`— ${p.tag} 引导数据 500 —`);
    const errBefore = runtimeErrors.length;
    fail = true;
    await goto(p.route);
    check(`${p.tag} 【核心】500 时骨架也会被摘掉（不卡骨架）`, (await waitSettled()) === true);
    s = await baseSnap(300);
    check(`${p.tag} 【核心】500 时失败警告露出`, s.bootErrorShown === true);
    check(`${p.tag} 页面没崩：标题还在`, s.headVisible === true);
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

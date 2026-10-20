#!/usr/bin/env node
/**
 * /projects/{id}/read 全文阅读页：**引导数据异步化 + 骨架屏**（2026-10-20）。
 *
 * 这一页的 payload 是整本书的正文，原来用同步 XHR：浏览器要一直停在解析期等几百 KB 回来，
 * HTML 解析、样式计算、首次绘制全被卡住（网速慢就是白屏）。现在改成 fetch + 骨架屏，
 * 书目/正文到了再由 boot() 铺上来。
 *
 * 本脚本逐条守护：
 *   P0 【核心】把 /read-data 挂住：DOM 仍要 readyState=complete、骨架要出来，
 *      不能先闪「暂无章节内容」这种假空态
 *   P1 【核心】放行数据：骨架退场、书名/正文/目录全部填对，document.title 也补上
 *   P2 深链 #chapter-3：数据到了要自动展开第 1 卷（initVolumes 在异步之后仍生效）
 *   P3 【核心】接口 500：页面不崩，退化成空书并给空态
 *   P4 390px：侧栏默认收起、无横向溢出
 *   P5 运行期无 JS 异常；数据只请求一次
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_reader_boot.mjs   # 1889 临时实例
 *   node scripts/probe_reader_boot.mjs                               # 默认 1888
 * 环境变量：READER_BASE / TTS_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.READER_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9371;
const SHOT_DIR = '/tmp/reader-boot-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'reader-boot-probe-'));
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
const PARA = '雨下了一整夜。山道泥泞，他一步一滑。他握紧了剑，听见远处有人在喊他的名字。';
const DATA = {
    projectId: 1,
    projectTitle: '阅读探针项目',
    author: '张三',
    volumes: [{
        volumeNumber: 1, title: '第一卷 · 雨夜', arcName: '开篇',
        chapterStart: 1, chapterEnd: 5, chapterNumbers: [1, 2, 3, 4, 5]
    }],
    chapters: [1, 2, 3, 4, 5].map(n => ({
        number: n,
        title: '第' + n + '章标题',
        content: Array.from({ length: 4 }, () => PARA).join('\n\n'),
        wordCount: 120,
        expansionStatus: null
    })),
    sideStories: []
};

/* ==================== 网络层拦截 ==================== */
let holdBootstrap = false;
let failBootstrap = false;
let heldBootstrap = null;
let dataRequestCount = 0;

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
    const u = new URL(p.request.url);
    const pn = u.pathname;

    if (/^\/api\/projects\/1\/read-data$/.test(pn)) {
        dataRequestCount++;
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        if (failBootstrap) {
            return fulfil(p.requestId, 500, 'application/json; charset=utf-8',
                JSON.stringify({ error: 'read-data 炸了' }));
        }
        return fulfilJson(p.requestId, DATA);
    }
    if (pn === '/projects/1/tts/configs') return fulfilJson(p.requestId, []);
    if (pn.indexOf('/api/tts-export/projects/1/completed-files') === 0) return fulfilJson(p.requestId, []);
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

async function releaseBootstrap() {
    if (!heldBootstrap) return false;
    const id = heldBootstrap;
    heldBootstrap = null;
    holdBootstrap = false;
    await fulfilJson(id, DATA);
    return true;
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const host = document.body;
    const data = host && window.Alpine ? Alpine.$data(host) : null;
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const txt = (el) => el ? (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim() : '';
    const skels = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    const h3s = Array.from(document.querySelectorAll('.reader-main h3[id^="chapter-"]')).filter(vis);
    const tocLinks = Array.from(document.querySelectorAll('.reader-sidebar .toc-link')).filter(vis);
    const empty = document.querySelector('.reader-main .text-center.py-5');
    return {
        ready: !!(data && window.Alpine),
        readyState: document.readyState,
        booting: data ? data.booting : null,
        title: data ? data.projectTitle : null,
        author: data ? data.author : null,
        chapterCount: data ? data.chapters.length : -1,
        volumeCount: data ? data.volumes.length : -1,
        /* Alpine 的响应式数组是 Proxy：CDP returnByValue 认不出它是 Array，
           会按普通对象序列化成 {"0":1}，这里先 Array.from 摘成真数组 */
        openVolumes: data ? Array.from(data.openVolumes || []) : null,
        sidebarOpen: data ? data.sidebarOpen : null,
        skelVisible: skels.length,
        h3Count: h3s.length,
        firstH3: h3s.length ? txt(h3s[0]) : '',
        tocCount: tocLinks.length,
        docTitle: document.title,
        emptyVisible: vis(empty),
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
async function goto(hash = '', width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    /* 只改 hash 属于同文档导航，不会重新加载页面（P2 就白测了）；
       加一个纯查询串当缓存钥匙，保证每次都是真·新加载。页面路由只看 pathname，不吃这个参数 */
    await send('Page.navigate', { url: BASE + '/projects/1/read?probe=' + (++navSeq) + hash });
}

async function waitMounted() {
    return waitFor('页面框架就绪', async () => {
        try {
            return await evalJs(`(() => !!(window.Alpine && document.body && Alpine.$data(document.body)))()`);
        } catch (e) { return false; }
    }, 20000, 120);
}

async function waitBooted() {
    const ok = await waitFor('booting 结束', async () => {
        const x = await evalJs(SNAP);
        return x.booting === false;
    }, 10000, 120);
    return ok;
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
            { urlPattern: '*/api/projects/*/read-data*', requestStage: 'Request' },
            { urlPattern: '*/tts/configs*', requestStage: 'Request' },
            { urlPattern: '*/completed-files*', requestStage: 'Request' }
        ]
    });

    /* ---------- P0 挂住数据：首屏不能被卡住 ---------- */
    console.log('\n— P0 把正文数据挂住：首屏仍要出来 —');
    holdBootstrap = true;
    await goto();
    await waitMounted();
    let s = await snap(500);
    check('【核心】数据挂着时 DOM 已 readyState=complete（同步 XHR 会一直 loading）',
        s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】页面框架已挂载且 booting=true', s.ready === true && s.booting === true,
        `ready=${s.ready} booting=${s.booting}`);
    check('【核心】骨架屏已渲染（不是白屏）', s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】还没渲染任何章节正文', s.h3Count === 0, 'h3=' + s.h3Count);
    check('【核心】没有先闪「暂无章节内容」', !s.emptyVisible);
    check('侧栏没有先闪空目录（目录也是骨架）', s.tocCount === 0, 'toc=' + s.tocCount);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('01-skeleton-holding');

    /* ---------- P1 放行：正文填充 ---------- */
    console.log('\n— P1 放行数据：骨架退场、正文与目录填对 —');
    check('正文数据请求确实被挂住过（说明真的是异步发的）', (await releaseBootstrap()) === true);
    check('【核心】数据到了 booting 自动收掉', (await waitBooted()) === true);
    s = await snap(200);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】5 章正文全部渲染', s.h3Count === 5, 'h3=' + s.h3Count);
    check('章节标题正确', s.firstH3.indexOf('第1章') >= 0, s.firstH3);
    check('书名已填', s.title === DATA.projectTitle, s.title);
    check('作者已填', s.author === DATA.author, s.author);
    check('document.title 补上书名（异步后由 boot 设置）',
        s.docTitle.indexOf(DATA.projectTitle) >= 0, s.docTitle);
    check('侧栏目录渲染出 5 个章节链接', s.tocCount >= 5, 'toc=' + s.tocCount);
    check('空态不再出现', !s.emptyVisible);
    await shot('02-loaded');

    /* ---------- P2 深链 #chapter-3 ---------- */
    console.log('\n— P2 深链：异步之后 initVolumes 仍生效 —');
    holdBootstrap = false;
    await goto('#chapter-3');
    await waitMounted();
    await waitBooted();
    s = await snap(300);
    check('【核心】带 #chapter-3 进来，第 1 卷自动展开', Array.isArray(s.openVolumes)
        && s.openVolumes.indexOf(1) >= 0, JSON.stringify(s.openVolumes));
    check('目录里能看到第 3 章链接', s.tocCount >= 3, 'toc=' + s.tocCount);

    /* ---------- P3 接口 500：退化成空书 ---------- */
    console.log('\n— P3 正文数据 500：页面退化但可用 —');
    const errBefore = runtimeErrors.length;
    const consoleBefore = consoleErrors.length;
    failBootstrap = true;
    await goto();
    await waitMounted();
    check('【核心】接口 500 时 booting 仍会收掉', (await waitBooted()) === true);
    s = await snap(200);
    check('【核心】页面没崩：书名区仍在，显示空书空态',
        s.ready === true && s.h3Count === 0 && s.emptyVisible === true,
        `h3=${s.h3Count} empty=${s.emptyVisible}`);
    check('【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    check('失败只打了一条兜底日志', consoleErrors.length - consoleBefore <= 1,
        consoleErrors.slice(consoleBefore).join(' | '));
    await shot('03-degraded');

    /* ---------- P4 390px ---------- */
    console.log('\n— P4 390px 窄屏 —');
    failBootstrap = false;
    holdBootstrap = true;
    await goto('', 390, 780);
    await waitMounted();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏侧栏默认收起（不挤占正文宽度）', s.sidebarOpen === false, 'open=' + s.sidebarOpen);
    await shot('04-mobile-skeleton');
    await releaseBootstrap();
    await waitBooted();
    s = await snap(250);
    check('窄屏数据态无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏正文正常渲染', s.h3Count === 5, 'h3=' + s.h3Count);
    await shot('05-mobile-loaded');

    /* ---------- P5 运行期 ---------- */
    console.log('\n— P5 运行期异常与请求次数 —');
    check('正文数据每次加载只请求一次（没有重试风暴）', dataRequestCount === 4,
        'requests=' + dataRequestCount);
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

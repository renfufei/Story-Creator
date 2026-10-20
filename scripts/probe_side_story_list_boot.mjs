#!/usr/bin/env node
/**
 * /projects/{id}/side-stories 番外列表页：**引导数据异步化 + 骨架屏**（2026-10-20）。
 *
 * 原来页面顶部一段同步 XHR 拿 /side-stories/list-data，同时 <main> 挂 x-cloak：
 * 浏览器卡在 HTML 解析期等往返，用户看到的是纯白；改好后先出 3 张骨架卡，数据到了再铺。
 *
 * 本脚本逐条守护：
 *   P0 【核心】把引导数据挂住：DOM 仍要 readyState=complete、骨架卡要出来，
 *      不能先闪「暂无番外篇」
 *   P1 【核心】放行数据：骨架退场、2 张番外卡片/项目名/状态徽章全部填对
 *   P2 新建番外向导：弹层里的「关联卷」选项来自异步引导数据
 *   P3 【核心】接口 500：页面不崩，退化成空列表并给空态
 *   P4 390px：骨架态/数据态都无横向溢出
 *   P5 运行期无 JS 异常；引导数据每次加载只请求一次
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_side_story_list_boot.mjs
 *   node scripts/probe_side_story_list_boot.mjs      # 默认 1888
 * 环境变量：SIDE_STORY_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.SIDE_STORY_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9374;
const SHOT_DIR = '/tmp/side-story-list-boot-shots';
const PID = 1;

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'side-story-list-probe-'));
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
const LIST_DATA = {
    projectId: PID,
    projectTitle: '番外列表探针项目',
    sideStories: [
        {
            story: {
                id: 7, title: '番外·雨夜后传', type: 'SUPPLEMENTARY', status: 'IN_PROGRESS',
                description: '一段番外', attachedVolume: 1
            }, chapterCount: 3
        },
        {
            story: {
                id: 8, title: '番外·另一条线', type: 'TANGENTIAL', status: 'DRAFT',
                description: '', attachedVolume: null
            }, chapterCount: 0
        }
    ],
    characters: [{ id: 3, name: '李四', role: '主角' }],
    volumes: [{ volumeNumber: 1, title: '第一卷 · 雨夜', arcName: '开篇' }]
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
    if (pn === `/projects/${PID}/side-stories/list-data`) {
        bootstrapRequests++;
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        if (failBootstrap) {
            return fulfil(p.requestId, 500, 'application/json; charset=utf-8',
                JSON.stringify({ error: 'list-data 炸了' }));
        }
        return fulfilJson(p.requestId, LIST_DATA);
    }
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

async function releaseBootstrap() {
    if (!heldBootstrap) return false;
    const id = heldBootstrap;
    heldBootstrap = null;
    holdBootstrap = false;
    await fulfilJson(id, LIST_DATA);
    return true;
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const host = document.querySelector('[x-data]') || document.body;
    const data = window.Alpine ? Alpine.$data(host) : null;
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const skels = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    const wrapper = document.querySelector('main > div[x-show="!booting"]');
    const cards = Array.from(document.querySelectorAll('.row.g-3 .card')).filter(vis);
    const empty = document.querySelector('main .text-center.text-muted.py-5');
    const text = (document.body.innerText || '');
    const selects = Array.from(document.querySelectorAll('.modal select'))
        .filter(s => s.getClientRects().length > 0);
    return {
        ready: !!(data && window.Alpine),
        readyState: document.readyState,
        booting: data ? data.booting : null,
        pid: data ? data.projectId : null,
        projectTitle: data ? data.projectTitle : null,
        storyCount: data ? data.sideStories.length : -1,
        charCount: data ? data.characters.length : -1,
        allCharCount: data ? data.allCharCount : -1,
        volumeCount: data ? data.volumes.length : -1,
        skelVisible: skels.length,
        cardCount: cards.length,
        emptyVisible: vis(empty),
        contentVisible: wrapper ? vis(wrapper) : false,
        bodyHasTitle: text.indexOf('番外·雨夜后传') >= 0,
        bodyHasType: text.indexOf('衍生故事') >= 0,
        bodyHasInProgress: text.indexOf('创作中') >= 0,
        volumeOptions: selects.length > 1 ? selects[1].options.length : -1,
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
    await send('Page.navigate', {
        url: BASE + '/projects/' + PID + '/side-stories?_probe=' + (++navSeq)
    });
}

async function waitMounted() {
    return waitFor('页面框架就绪', async () => {
        try {
            return await evalJs(`(() => !!(window.Alpine && document.querySelector('[x-data]')
                && Alpine.$data(document.querySelector('[x-data]'))))()`);
        } catch (e) { return false; }
    }, 20000, 120);
}

async function waitBooted() {
    return waitFor('booting 结束', async () => {
        const x = await evalJs(SNAP);
        return x.booting === false;
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
        patterns: [{ urlPattern: '*/side-stories/list-data*', requestStage: 'Request' }]
    });

    /* ---------- P0 挂住引导数据 ---------- */
    console.log('\n— P0 把引导数据挂住：首屏仍要出来 —');
    holdBootstrap = true;
    await goto();
    await waitMounted();
    let s = await snap(500);
    check('【核心】数据挂着时 DOM 已 readyState=complete（同步 XHR 会一直 loading）',
        s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】组件已挂载且 booting=true', s.ready === true && s.booting === true,
        `ready=${s.ready} booting=${s.booting}`);
    check('【核心】骨架卡已渲染（不是白屏，也不再靠 x-cloak 挡着）',
        s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】还没渲染任何番外卡片', s.cardCount === 0, 'cards=' + s.cardCount);
    check('【核心】没有先闪「暂无番外篇」', s.emptyVisible === false);
    check('projectId 从路径同步取到（不用等接口）', String(s.pid) === String(PID), 'pid=' + s.pid);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('01-skeleton');

    /* ---------- P1 放行数据 ---------- */
    console.log('\n— P1 放行数据：骨架退场、番外卡片填对 —');
    check('引导数据请求确实被挂住过（说明真的是异步发的）', await releaseBootstrap());
    check('【核心】数据到了 booting 自动收掉', (await waitBooted()) === true);
    s = await snap(350);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】2 张番外卡片全部渲染', s.cardCount === 2 && s.storyCount === 2,
        `cards=${s.cardCount} stories=${s.storyCount}`);
    check('卡片里能看到标题与类型', s.bodyHasTitle === true && s.bodyHasType === true);
    check('状态徽章显示「创作中」', s.bodyHasInProgress === true);
    check('项目名已填', s.projectTitle === '番外列表探针项目', s.projectTitle);
    check('内容区已显示', s.contentVisible === true);
    check('空态不再出现', s.emptyVisible === false);
    check('角色与分卷也铺好了（向导要用）', s.charCount === 1 && s.allCharCount === 1
        && s.volumeCount === 1, `chars=${s.charCount} vols=${s.volumeCount}`);
    await shot('02-loaded');

    /* ---------- P2 新建番外向导 ---------- */
    console.log('\n— P2 新建番外向导：关联卷选项来自引导数据 —');
    await evalJs(`(() => {
        const btn = Array.from(document.querySelectorAll('button'))
            .find(b => (b.innerText || '').indexOf('新建番外') >= 0);
        if (btn) btn.click();
        return !!btn;
    })()`);
    s = await snap(500);
    check('【核心】「关联卷」下拉有 2 个选项（不关联 + 第1卷）', s.volumeOptions === 2,
        'options=' + s.volumeOptions);
    await shot('03-wizard');
    await evalJs(`(() => {
        const host = document.querySelector('[x-data]');
        const c = host && window.Alpine ? Alpine.$data(host) : null;
        if (c) c.showWizard = false;
        return true;
    })()`);
    await sleep(300);

    /* ---------- P3 接口 500 ---------- */
    console.log('\n— P3 引导数据 500：页面退化但可用 —');
    const errBefore = runtimeErrors.length;
    const consoleBefore = consoleErrors.length;
    failBootstrap = true;
    await goto();
    await waitMounted();
    check('【核心】接口 500 时 booting 仍会收掉', (await waitBooted()) === true);
    s = await snap(250);
    check('【核心】页面没崩：骨架收掉、显示空态', s.ready === true && s.skelVisible === 0
        && s.emptyVisible === true, `skel=${s.skelVisible} empty=${s.emptyVisible}`);
    check('失败时列表退化成空数组', s.storyCount === 0 && s.cardCount === 0,
        'stories=' + s.storyCount);
    check('【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    check('失败只打了一条兜底日志', consoleErrors.length - consoleBefore <= 1,
        consoleErrors.slice(consoleBefore).join(' | '));
    await shot('04-degraded');

    /* ---------- P4 390px ---------- */
    console.log('\n— P4 390px 窄屏 —');
    failBootstrap = false;
    holdBootstrap = true;
    await goto(390, 780);
    await waitMounted();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('05-mobile-skeleton');
    await releaseBootstrap();
    await waitBooted();
    s = await snap(300);
    check('窄屏数据态无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏卡片正常渲染', s.cardCount === 2, 'cards=' + s.cardCount);
    await shot('06-mobile-loaded');

    /* ---------- P5 运行期 ---------- */
    console.log('\n— P5 运行期异常与请求次数 —');
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

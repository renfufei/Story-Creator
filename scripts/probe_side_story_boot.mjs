#!/usr/bin/env node
/**
 * /projects/{id}/side-stories/{ssid} 番外创作页：**引导数据异步化 + 骨架屏**（2026-10-20）。
 *
 * 原来页面顶部一段同步 XHR 拿 /side-stories/{ssid}/data（番外信息 + 章节正文 + 角色），
 * 而且 <main> 挂着 x-cloak —— 浏览器卡在解析期等往返，用户看到的就是整片白屏。
 * 现在改成 fetch + 骨架屏：标题/正文到了再由 boot() 铺上来。
 *
 * 本脚本逐条守护：
 *   P0 【核心】把引导数据挂住：DOM 仍要 readyState=complete、骨架要出来，
 *      不能先闪空标题、也不能先闪空章节列表
 *   P1 【核心】放行数据：骨架退场、标题/项目名/章节/角色/大纲全部填对，状态徽章也跟上
 *   P2 【核心】接口 500：页面不崩，退化成空番外（骨架收掉、不抛异常）
 *   P3 390px：骨架态/数据态都无横向溢出
 *   P4 运行期无 JS 异常；引导数据每次加载只请求一次
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_side_story_boot.mjs   # 1889 临时实例
 *   node scripts/probe_side_story_boot.mjs                               # 默认 1888
 * 环境变量：SIDE_STORY_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.SIDE_STORY_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9373;
const SHOT_DIR = '/tmp/side-story-boot-shots';
const PID = 1;
const SSID = 7;

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'side-story-probe-'));
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
const PARA = '雨停之后，山道上只剩脚印。他把剑收回鞘里，回头看了一眼。';
const SS_DATA = {
    projectId: PID,
    projectTitle: '番外探针项目',
    sideStory: {
        id: SSID,
        title: '番外·雨夜后传',
        type: 'SUPPLEMENTARY',
        status: 'IN_PROGRESS',
        outline: '大纲正文：主角回到山道，遇见旧人。',
        arcName: '后传',
        creativeGuidance: '保持冷峻笔调，多用短句。',
        description: '一段番外',
        attachedVolume: 1
    },
    volumes: [{ volumeNumber: 1, title: '第一卷 · 雨夜', chapterStart: 1, chapterEnd: 3 }],
    characterIds: [3],
    characters: [{ id: 3, name: '李四', role: '主角' }],
    chapters: [1, 2, 3].map(n => ({
        chapterNumber: n,
        title: '番外第' + n + '章',
        outlineSummary: '摘要' + n,
        content: PARA,
        wordCount: 120,
        status: 'COMPLETED'
    }))
};
const AUTO_RUN_IDLE = { status: 'IDLE', step: '', progress: '', chapter: 0, error: '' };

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

const BOOTSTRAP_PATH = `/projects/${PID}/side-stories/${SSID}/data`;

async function handlePaused(p) {
    const pn = new URL(p.request.url).pathname;

    if (pn === BOOTSTRAP_PATH) {
        bootstrapRequests++;
        if (holdBootstrap) { heldBootstrap = p.requestId; return; }
        if (failBootstrap) {
            return fulfil(p.requestId, 500, 'application/json; charset=utf-8',
                JSON.stringify({ error: 'side-story/data 炸了' }));
        }
        return fulfilJson(p.requestId, SS_DATA);
    }
    // 自动创作状态：页面 init 就会问一次，给 IDLE 免得进轮询
    if (/\/side-stories\/api\/\d+\/auto-run\/status$/.test(pn)) {
        return fulfilJson(p.requestId, AUTO_RUN_IDLE);
    }
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

async function releaseBootstrap() {
    if (!heldBootstrap) return false;
    const id = heldBootstrap;
    heldBootstrap = null;
    holdBootstrap = false;
    await fulfilJson(id, SS_DATA);
    return true;
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const host = document.querySelector('[x-data]') || document.body;
    const data = window.Alpine ? Alpine.$data(host) : null;
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const skels = Array.from(document.querySelectorAll('.sc-skel')).filter(vis);
    /* 骨架里也有一个 h4（只装骨架条），真正的内容标题在 x-show="!booting" 的包装层里 */
    const wrapper = document.querySelector('main > div[x-show="!booting"]');
    const h4 = wrapper ? wrapper.querySelector('h4') : null;
    const text = (document.body.innerText || '');
    return {
        ready: !!(data && window.Alpine),
        readyState: document.readyState,
        booting: data ? data.booting : null,
        pid: data ? data.projectId : null,
        infoTitle: data ? data.infoTitle : null,
        projectTitle: data ? data.projectTitle : null,
        chapterCount: data ? data.chapters.length : -1,
        charCount: data ? data.allCharacters.length : -1,
        selectedCharIds: data ? Array.from(data.selectedCharIds || []) : null,
        outline: data ? data.outline : null,
        guidance: data ? data.creativeGuidance : null,
        status: data ? (data.sideStory || {}).status : null,
        skelVisible: skels.length,
        h4Text: h4 ? (h4.innerText || '').replace(/\\s+/g, ' ').trim() : '',
        contentVisible: wrapper ? vis(wrapper) : false,
        bodyHasStatus: text.indexOf('创作中') >= 0,
        bodyHasChapter: text.indexOf('番外第1章') >= 0,
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
    /* 纯查询串当缓存钥匙，保证每次都是真·新加载（页面路由只看 pathname） */
    await send('Page.navigate', {
        url: BASE + '/projects/' + PID + '/side-stories/' + SSID + '?_probe=' + (++navSeq)
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
        patterns: [
            { urlPattern: '*/side-stories/*/data*', requestStage: 'Request' },
            { urlPattern: '*/auto-run/status*', requestStage: 'Request' }
        ]
    });

    /* ---------- P0 挂住引导数据：首屏不能被卡住 ---------- */
    console.log('\n— P0 把引导数据挂住：首屏仍要出来 —');
    holdBootstrap = true;
    await goto();
    await waitMounted();
    let s = await snap(500);
    check('【核心】数据挂着时 DOM 已 readyState=complete（同步 XHR 会一直 loading）',
        s.readyState === 'complete', 'readyState=' + s.readyState);
    check('【核心】组件已挂载且 booting=true', s.ready === true && s.booting === true,
        `ready=${s.ready} booting=${s.booting}`);
    check('【核心】骨架屏已渲染（不是白屏，也不再靠 x-cloak 挡着）',
        s.skelVisible > 0, 'skel=' + s.skelVisible);
    check('【核心】还没渲染番外正文（标题仍为空）', s.infoTitle === '', 'title=' + s.infoTitle);
    check('【核心】没有先闪空章节列表', s.contentVisible === false && s.bodyHasChapter === false);
    check('projectId 从路径同步取到（不用等接口）', String(s.pid) === String(PID), 'pid=' + s.pid);
    check('首屏无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('01-skeleton');

    /* ---------- P1 放行数据：骨架退场、正文填对 ---------- */
    console.log('\n— P1 放行数据：骨架退场、正文与角色填对 —');
    check('引导数据请求确实被挂住过（说明真的是异步发的）', await releaseBootstrap());
    check('【核心】数据到了 booting 自动收掉', (await waitBooted()) === true);
    s = await snap(350);
    check('骨架全部退场', s.skelVisible === 0, 'skel=' + s.skelVisible);
    check('【核心】番外标题已填', s.infoTitle === '番外·雨夜后传', s.infoTitle);
    check('项目名已填', s.projectTitle === '番外探针项目', s.projectTitle);
    check('【核心】内容区已显示、标题可读', s.contentVisible === true && s.h4Text.indexOf('雨夜后传') >= 0,
        'h4=' + s.h4Text);
    check('【核心】3 个章节全部渲染', s.chapterCount === 3 && s.bodyHasChapter === true,
        'chapters=' + s.chapterCount);
    check('角色列表与已选角色已填', s.charCount === 1 && s.selectedCharIds.length === 1
        && String(s.selectedCharIds[0]) === '3', `chars=${s.charCount} sel=${JSON.stringify(s.selectedCharIds)}`);
    check('大纲与创作指导已填', (s.outline || '').indexOf('主角回到山道') >= 0
        && (s.guidance || '').indexOf('冷峻') >= 0);
    check('状态徽章显示「创作中」', s.status === 'IN_PROGRESS' && s.bodyHasStatus === true,
        'status=' + s.status);
    await shot('02-loaded');

    /* ---------- P2 接口 500：退化成空番外 ---------- */
    console.log('\n— P2 引导数据 500：页面退化但可用 —');
    const errBefore = runtimeErrors.length;
    const consoleBefore = consoleErrors.length;
    failBootstrap = true;
    await goto();
    await waitMounted();
    check('【核心】接口 500 时 booting 仍会收掉', (await waitBooted()) === true);
    s = await snap(250);
    check('【核心】页面没崩：骨架收掉、框架还在', s.ready === true && s.skelVisible === 0
        && s.contentVisible === true, `ready=${s.ready} skel=${s.skelVisible}`);
    check('失败时退化成空番外（标题/章节都空）', s.infoTitle === '' && s.chapterCount === 0,
        `title=${s.infoTitle} chapters=${s.chapterCount}`);
    check('【核心】失败不产生未捕获异常', runtimeErrors.length === errBefore,
        runtimeErrors.slice(errBefore).join(' | '));
    check('失败只打了一条兜底日志', consoleErrors.length - consoleBefore <= 1,
        consoleErrors.slice(consoleBefore).join(' | '));
    await shot('03-degraded');

    /* ---------- P3 390px ---------- */
    console.log('\n— P3 390px 窄屏 —');
    failBootstrap = false;
    holdBootstrap = true;
    await goto(390, 780);
    await waitMounted();
    s = await snap(400);
    check('窄屏骨架阶段无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('04-mobile-skeleton');
    await releaseBootstrap();
    await waitBooted();
    s = await snap(300);
    check('窄屏数据态无横向溢出', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('窄屏正文正常渲染', s.chapterCount === 3, 'chapters=' + s.chapterCount);
    await shot('05-mobile-loaded');

    /* ---------- P4 运行期 ---------- */
    console.log('\n— P4 运行期异常与请求次数 —');
    /* P0+放行算一次、P2、P3 各一次 ⇒ 3 次页面加载，各请求一次 */
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

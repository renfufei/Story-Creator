#!/usr/bin/env node
/**
 * 探针：/projects/{id}/read 的正文列宽与留白（2026-10-28）。
 *
 * 用户反馈两件事：
 *   ① PC 上右侧空白太大 —— 根因是 `.reader-main{max-width:900px}`：侧栏吃掉 260px 后
 *      整块被顶在左边，1920 屏右边能空出 ~1000px（实测改造前 toolbox 右边缘 990，屏幕 1920）。
 *   ② 手机上左右各有 48px 内边距 —— 390 屏正文只剩 294，可用区域明显偏小。
 *
 * 现在的规则：桌面在「侧栏右侧的剩余区域」里居中（宽屏左右留白均分，窄屏直接铺满）；
 * 手机内边距收到 16px，同时把原先竖着叠的配色按钮改成与 ☰ 并排，正文顶部不必空出 110px。
 *
 * 逐条钉住：
 *   A 1920 侧栏开：左右留白基本相等（差 ≤8px）、正文宽 ≥1000（改造前 900 还偏左）
 *   B 1920 侧栏收起：同样居中，左右仍相等
 *   C 1280 / 1024：右侧几乎不留空（≤60px），不吃掉本就不多的宽度
 *   D 390：左右内边距 ≤20px、正文可用宽 ≥350、无横向溢出
 *   E 390 / 320：书名与两个浮动按钮的矩形不相交（padding 收窄后最容易撞的一处）
 *   F 骨架屏与正文同宽（数据到了不「撑开」一下）
 *   G 全程零未捕获 JS 异常
 *
 * 1888 跑的是旧 jar ⇒ 默认用 Fetch.fulfillRequest 把文档请求换成本地 reader.html。
 * PROBE_LOCAL=0 关闭（跑已打包的版本，用来做反向验证）。
 *
 * 用法：node scripts/probe_reader_layout.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const BASE = process.env.READER_BASE || 'http://localhost:1888';
const LOCAL_HTML = path.join(ROOT, 'src/main/resources/static/pages/reader.html');
const INTERCEPT = process.env.PROBE_LOCAL !== '0';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.PROBE_CDP_PORT || 9393);
const SIDEBAR_W = 260;

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'reader-layout-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let ws, msgId = 0;
const pending = new Map();
const pageErrors = [];
const send = (method, params = {}) => {
    const id = ++msgId;
    return new Promise((res, rej) => {
        pending.set(id, { res, rej });
        ws.send(JSON.stringify({ id, method, params }));
    });
};
async function evalJs(expression) {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
        throw new Error('页面 JS 异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
    }
    return r.result.value;
}
async function waitFor(expr, tries = 150, gap = 200) {
    for (let i = 0; i < tries; i++) {
        try { if (await evalJs(expr)) return true; } catch (e) { /* 导航中 */ }
        await sleep(gap);
    }
    return false;
}

/** 量正文列：容器盒、正文首段、书名、两个浮动按钮 */
const GEO = `(() => {
    const rect = (sel) => {
        const e = document.querySelector(sel);
        if (!e) return null;
        const r = e.getBoundingClientRect();
        return { l: Math.round(r.left), r: Math.round(r.right), t: Math.round(r.top),
                 b: Math.round(r.bottom), w: Math.round(r.width), h: Math.round(r.height) };
    };
    const main = document.querySelector('.reader-main');
    const cs = main ? getComputedStyle(main) : null;
    const content = document.querySelector('.chapter-content');
    const paras = [...document.querySelectorAll('.chapter-content')];
    const far = paras.length
        ? Math.max(...paras.map((p) => p.getBoundingClientRect().right))
        : null;
    return JSON.stringify({
        vw: document.documentElement.clientWidth,
        main: rect('.reader-main'),
        title: rect('.reader-main h2'),
        contentRight: far === null ? null : Math.round(far),
        contentLeft: content ? Math.round(content.getBoundingClientRect().left) : null,
        contentW: content ? Math.round(content.getBoundingClientRect().width) : null,
        padL: cs ? Math.round(parseFloat(cs.paddingLeft)) : null,
        padR: cs ? Math.round(parseFloat(cs.paddingRight)) : null,
        skeletonMaxW: (() => {
            const s = document.querySelector('.reader-skeleton');
            return s ? getComputedStyle(s).maxWidth : null;
        })(),
        toggle: rect('.toggle-btn'),
        bgFab: rect('.bg-fab'),
        expanded: main ? main.classList.contains('expanded') : null,
        overflowX: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1
    });
})()`;

const overlap = (a, b) => !!a && !!b
    && a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b;

const HTML = INTERCEPT ? fs.readFileSync(LOCAL_HTML) : null;
let seq = 0;

async function load(w, h) {
    await send('Emulation.setDeviceMetricsOverride', {
        width: w, height: h, deviceScaleFactor: 1, mobile: w < 800
    });
    await send('Page.navigate', { url: BASE + '/projects/1/read?probe=' + (++seq) });
    /* 骨架是 x-show 控制的：元素一直在 DOM 里，只是 display:none，不能判「存在」 */
    const ok = await waitFor(`!!(window.Alpine && document.querySelector('.chapter-content')
        && getComputedStyle(document.querySelector('.reader-skeleton')).display === 'none')`, 150, 200);
    if (!ok) throw new Error('页面没就绪（正文骨架没退场）');
    await sleep(300);
    return JSON.parse(await evalJs(GEO));
}

try {
    let wsUrl = null;
    for (let i = 0; i < 60; i++) {
        try {
            const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
            const p = list.find((t) => t.type === 'page');
            if (p?.webSocketDebuggerUrl) { wsUrl = p.webSocketDebuggerUrl; break; }
        } catch (e) { /* 还没起来 */ }
        await sleep(200);
    }
    if (!wsUrl) throw new Error('连不上无头 Chrome 的 CDP');
    ws = new WebSocket(wsUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = async (ev) => {
        const m = JSON.parse(ev.data);
        if (m.id && pending.has(m.id)) {
            const { res, rej } = pending.get(m.id);
            pending.delete(m.id);
            m.error ? rej(new Error(m.error.message)) : res(m.result);
            return;
        }
        if (m.method === 'Runtime.exceptionThrown') {
            pageErrors.push(m.params.exceptionDetails?.exception?.description
                || m.params.exceptionDetails?.text);
        }
        if (m.method === 'Fetch.requestPaused') {
            const { requestId, resourceType } = m.params;
            try {
                if (resourceType === 'Document' && HTML) {
                    await send('Fetch.fulfillRequest', {
                        requestId, responseCode: 200,
                        responseHeaders: [{ name: 'Content-Type', value: 'text/html; charset=utf-8' }],
                        body: HTML.toString('base64')
                    });
                } else {
                    await send('Fetch.continueRequest', { requestId });
                }
            } catch (e) { /* 请求已失效 */ }
        }
    };
    await send('Page.enable');
    await send('Runtime.enable');
    if (INTERCEPT) {
        await send('Fetch.enable', {
            patterns: [{ urlPattern: '*projects/*/read*', requestStage: 'Request' }]
        });
    }

    console.log('页面：' + BASE + '/projects/1/read'
        + (INTERCEPT ? '（文档已换成本地 reader.html）' : '（用服务里已打包的页面）') + '\n');

    /* ---------- A：1920 侧栏开 ---------- */
    const a = await load(1920, 1000);
    const aLeftGap = a.contentLeft - SIDEBAR_W - a.padL;
    const aRightGap = a.vw - a.contentRight - a.padR;
    check('A1 侧栏是展开态（260px）', a.expanded === false);
    check('A2 正文内容宽 ≥1000px（改造前受 900 上限挤压）',
        (a.contentRight - a.contentLeft) >= 1000, `${a.contentRight - a.contentLeft}px`);
    check('A3 左右留白基本相等（差 ≤8px，正文居中）',
        Math.abs(aLeftGap - aRightGap) <= 8, `左${aLeftGap} / 右${aRightGap}`);
    check('A4 右侧留白没有大块浪费（≤400px，原来 ~1000px）', aRightGap <= 400, `右${aRightGap}`);

    /* ---------- B：1920 侧栏收起 ---------- */
    await evalJs(`(() => { document.querySelector('.toggle-btn').click(); return true; })()`);
    await sleep(450);
    const b = JSON.parse(await evalJs(GEO));
    const bLeft = b.contentLeft - b.padL;
    const bRight = b.vw - b.contentRight - b.padR;
    /* 正文有 1120px 上限（一行 ~56 字，再宽反而难读），所以收起侧栏不会让它继续变宽，
       只把两侧留白从 270 放大到 400。这里钉的是「不缩水」而不是「必须变宽」。 */
    check('B1 侧栏收起后正文不缩水（1120 上限封顶，留白变大）',
        b.expanded === true && (b.contentRight - b.contentLeft) >= (a.contentRight - a.contentLeft),
        `${a.contentRight - a.contentLeft} → ${b.contentRight - b.contentLeft}`);
    check('B2 侧栏收起后仍居中（左右差 ≤8px）', Math.abs(bLeft - bRight) <= 8, `左${bLeft} / 右${bRight}`);
    check('B3 侧栏收起后无横向溢出', b.overflowX === false);

    /* ---------- C：常见笔记本宽度不该留白 ---------- */
    for (const w of [1440, 1280, 1024]) {
        const c = await load(w, 900);
        const right = c.vw - c.contentRight - c.padR;
        check(`C${w} ${w}px 右侧留白 ≤60px（不吃掉本就不多的宽度）`, right <= 60, `右${right}`);
        check(`C${w}b ${w}px 无横向溢出`, c.overflowX === false);
    }

    /* ---------- D/E：手机 ---------- */
    for (const w of [430, 390, 320]) {
        const d = await load(w, 844);
        check(`D${w} ${w}px 左右内边距 ≤20px`, d.padL <= 20 && d.padR <= 20, `${d.padL}/${d.padR}`);
        check(`D${w}b ${w}px 正文可用宽 ≥${w - 40}`, (d.contentRight - d.contentLeft) >= w - 40,
            `${d.contentRight - d.contentLeft}`);
        check(`D${w}c ${w}px 无横向溢出`, d.overflowX === false);
        check(`E${w} ${w}px 书名不被 ☰ 按钮遮住`, !overlap(d.title, d.toggle),
            d.title && d.toggle ? `title ${d.title.l},${d.title.t}-${d.title.r},${d.title.b} / btn ${d.toggle.l},${d.toggle.t}-${d.toggle.r},${d.toggle.b}` : '缺元素');
        check(`E${w}b ${w}px 书名不被配色按钮遮住`, !overlap(d.title, d.bgFab),
            d.bgFab ? `btn ${d.bgFab.l},${d.bgFab.t}-${d.bgFab.r},${d.bgFab.b}` : '缺元素');
        /* 配色按钮改成与 ☰ 并排后，它的浮层（从按钮右侧 8px 展开）更容易顶出右边界 */
        await evalJs(`(() => {
            var b = document.querySelector('.bg-fab button, .bg-fab .btn');
            if (b) b.click(); else document.querySelector('.bg-fab').click();
            return true;
        })()`);
        await sleep(280);
        const pop = JSON.parse(await evalJs(`(() => {
            const p = document.querySelector('.bg-popover');
            const vw = document.documentElement.clientWidth;
            if (!p || getComputedStyle(p).display === 'none') {
                return JSON.stringify({ open: false, vw: vw });
            }
            const r = p.getBoundingClientRect();
            return JSON.stringify({
                open: true, vw: vw, l: Math.round(r.left), r: Math.round(r.right),
                overflowX: document.documentElement.scrollWidth > vw + 1
            });
        })()`));
        check(`E${w}c ${w}px 配色浮层在右边界内`, pop.open && pop.r <= pop.vw,
            pop.open ? `浮层右 ${pop.r} / 视口 ${pop.vw}` : '浮层没打开');
        check(`E${w}d ${w}px 浮层不撑出横向滚动`, pop.open && pop.overflowX === false);
    }

    /* ---------- F：骨架屏宽度 ---------- */
    check('F1 骨架屏不再单独写死 46rem 上限', a.skeletonMaxW === 'none', String(a.skeletonMaxW));

    check('G 全程零未捕获 JS 异常', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));

    const pass = results.filter((r) => r.ok).length;
    console.log(`\n合计 ${pass}/${results.length} 通过`);
    process.exitCode = pass === results.length ? 0 : 1;
} catch (err) {
    console.error('探针异常：' + err.message);
    process.exitCode = 2;
} finally {
    try { ws && ws.close(); } catch (e) { /* 忽略 */ }
    chrome.kill('SIGKILL');
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* 忽略 */ }
}

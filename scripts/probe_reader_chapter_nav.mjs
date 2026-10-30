#!/usr/bin/env node
/**
 * 探针：/projects/{id}/read 的「上一章 / 下一章」浮钮（2026-10-30）。
 *
 * 用户要求：在界面右边加两个按钮翻章，Web 与移动端都要能用。
 * 落地形态：
 *   桌面（≥769px）贴右边缘竖排文字（38px 宽，正好落在正文右侧 48px 留白里，压不到字）；
 *   手机（≤768px）落到底部居中做胶囊（右边缘只剩 16px 留白，竖排必然压字）。
 *
 * 逐条钉住：
 *   A 桌面：两个按钮存在、文字对、竖排、贴右、垂直居中、不压正文、首章「上一章」禁用
 *   B 点【下一章】：真的滚下去了、落在第 2 章、标题顶到视口上部、hash 跟着变
 *   C 点【上一章】：回到第 1 章且「上一章」重新禁用
 *   D 滚到文末：「下一章」禁用（末章标题可能停在 120px 线下方，这里最容易漏）
 *   E 手机：横向胶囊、贴底、水平居中、按钮 ≥44px、无横向溢出
 *   F 深色背景：按钮跟着换皮（不是白底）
 *   G 全程零未捕获 JS 异常
 *
 * 1888 上跑的是旧 jar，磁盘上的 reader.html 还没进包 ⇒ 默认用 Fetch.fulfillRequest
 * 把文档请求换成本地文件，接口请求照旧打到服务。PROBE_LOCAL=0 可关闭（跑已打包的版本）。
 *
 * 用法：node scripts/probe_reader_chapter_nav.mjs
 *      READER_BASE=http://localhost:1889 node scripts/probe_reader_chapter_nav.mjs
 *      PROBE_LOCAL=0 node scripts/probe_reader_chapter_nav.mjs   # 反向验证：旧页面应大面积红
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
const PORT = Number(process.env.PROBE_CDP_PORT || 9394);

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'reader-chapnav-'));
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
async function waitFor(expr, tries = 120, gap = 200) {
    for (let i = 0; i < tries; i++) {
        try { if (await evalJs(expr)) return true; } catch (e) { /* 导航中 */ }
        await sleep(gap);
    }
    return false;
}

/* 旧页面上 .chapter-nav 根本不存在 —— 一律容错成 null，让断言 FAIL 而不是探针抛异常 */
const GEO = `(() => {
    const nav = document.querySelector('.chapter-nav');
    const btns = [...document.querySelectorAll('.chapter-nav-btn')];
    let d = null;
    try { d = Alpine.$data(document.body); } catch (e) { d = null; }
    const rect = nav ? nav.getBoundingClientRect() : null;
    const marks = [...document.querySelectorAll('.reader-main h3[id^="chapter-"]')];
    const para = document.querySelector('.reader-main .chapter-content');
    const pRect = para ? para.getBoundingClientRect() : null;
    const hit = (a, b) => !a || !b ? false : !(a.right <= b.left || a.left >= b.right || a.bottom <= b.top || a.top >= b.bottom);
    return JSON.stringify({
        hasNav: !!nav,
        navRect: rect ? { t: Math.round(rect.top), l: Math.round(rect.left), w: Math.round(rect.width), h: Math.round(rect.height), r: Math.round(rect.right), b: Math.round(rect.bottom) } : null,
        labels: btns.map(b => b.textContent.trim()),
        disabled: btns.map(b => b.disabled),
        btnH: btns.length ? Math.round(btns[0].getBoundingClientRect().height) : -1,
        navChapter: d ? d.navChapter : null,
        chapterCount: marks.length,
        scrollY: Math.round(window.scrollY),
        innerH: window.innerHeight,
        innerW: window.innerWidth,
        docH: document.documentElement.scrollHeight,
        hash: location.hash,
        topChapterTop: (() => {
            /* 点完【下一章】后期望标题贴在视口顶部附近：找出第一个「顶端 >= 0」的章节标题 */
            const e = marks.find(m => m.getBoundingClientRect().top >= -2);
            return e ? { id: e.id, top: Math.round(e.getBoundingClientRect().top) } : null;
        })(),
        overlapsText: hit(rect, pRect),
        bg: btns.length ? getComputedStyle(btns[0]).backgroundColor : '',
        overflowX: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1
    });
})()`;

const lum = (rgb) => {
    const m = String(rgb).match(/\d+/g);
    if (!m) return 0;
    return 0.299 * Number(m[0]) + 0.587 * Number(m[1]) + 0.114 * Number(m[2]);
};

let seq = 0;
async function load(w, h) {
    await send('Emulation.setDeviceMetricsOverride', {
        width: w, height: h, deviceScaleFactor: 1, mobile: w < 800
    });
    await send('Page.navigate', { url: BASE + '/projects/1/read?probe=' + (++seq) });
    /* 就绪条件取旧页面也有的元素（章节标题），跑旧版本时是断言 FAIL 而不是卡在等待 */
    const ok = await waitFor(`!!(window.Alpine && document.querySelector('.reader-main h3[id^="chapter-"]'))`, 150, 200);
    if (!ok) throw new Error('页面没就绪（章节标题没渲染出来，检查项目 1 是否有正文）');
    await sleep(500);
}
const geo = async () => JSON.parse(await evalJs(GEO));

/* 旧页面没有按钮：点不到就返回 false，让后续断言以 FAIL 收场，别把探针打断 */
const clickBtn = async (i) => {
    const hit = await evalJs(`(() => {
        var b = document.querySelectorAll('.chapter-nav-btn')[${i}];
        if (!b) return false;
        b.click();
        return true;
    })()`);
    await sleep(1500);
    return hit;
};

try {
    let wsUrl = null;
    for (let i = 0; i < 60; i++) {
        try {
            const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
            const p = list.find((t) => t.type === 'page');
            if (p?.webSocketDebuggerUrl) { wsUrl = p.webSocketDebuggerUrl; break; }
        } catch (e) { /* 等 Chrome 起来 */ }
        await sleep(200);
    }
    if (!wsUrl) throw new Error('Chrome 调试端口没就绪');
    ws = new WebSocket(wsUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

    const HTML = INTERCEPT ? fs.readFileSync(LOCAL_HTML) : null;
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
                || m.params.exceptionDetails?.text || 'unknown');
            return;
        }
        if (m.method === 'Fetch.requestPaused') {
            const { requestId, resourceType } = m.params;
            try {
                if (resourceType === 'Document') {
                    await send('Fetch.fulfillRequest', {
                        requestId, responseCode: 200,
                        responseHeaders: [{ name: 'Content-Type', value: 'text/html; charset=utf-8' }],
                        body: HTML.toString('base64')
                    });
                } else {
                    await send('Fetch.continueRequest', { requestId });
                }
            } catch (e) { /* 忽略 */ }
        }
    };
    await send('Page.enable');
    await send('Runtime.enable');
    if (INTERCEPT) {
        await send('Fetch.enable', { patterns: [{ urlPattern: '*projects/*/read*', requestStage: 'Request' }] });
    }

    /* ---------- A：桌面 1440 的形态 ---------- */
    await load(1440, 900);
    const a = await geo();
    check('A0 页面上有 .chapter-nav 容器', a.hasNav);
    check('A1 两颗按钮，文字是「上一章 / 下一章」',
        a.labels.length === 2 && a.labels[0] === '上一章' && a.labels[1] === '下一章',
        a.labels.join('|'));
    check('A2 桌面是竖排（高 > 宽）', !!a.navRect && a.navRect.h > a.navRect.w,
        a.navRect ? `${a.navRect.w}x${a.navRect.h}` : '无');
    check('A3 贴右边缘（右侧留白 ≤24px）',
        !!a.navRect && (a.innerW - a.navRect.r) <= 24 && (a.innerW - a.navRect.r) >= 0,
        a.navRect ? `${a.innerW - a.navRect.r}px` : '无');
    check('A4 垂直居中（中心偏差 ≤30px）',
        !!a.navRect && Math.abs((a.navRect.t + a.navRect.h / 2) - a.innerH / 2) <= 30,
        a.navRect ? Math.round(a.navRect.t + a.navRect.h / 2) + ' vs ' + Math.round(a.innerH / 2) : '无');
    check('A5 不压正文文字', a.hasNav && a.overlapsText === false);
    check('A6 首章「上一章」禁用', a.disabled[0] === true, String(a.disabled[0]));
    check('A7 首章「下一章」可用', a.disabled[1] === false, String(a.disabled[1]));
    check('A8 章数 ≥2（够翻页才能验后续）', a.chapterCount >= 2, a.chapterCount);

    /* ---------- B：点【下一章】 ---------- */
    await clickBtn(1);
    const b = await geo();
    check('B1 页面真的滚下去了', b.scrollY > a.scrollY + 200, `${a.scrollY} → ${b.scrollY}`);
    check('B2 落在第 2 章（上一章=1，下一章=3）',
        b.navChapter && b.navChapter.prev === 1 && b.navChapter.next === 3,
        JSON.stringify(b.navChapter));
    check('B3 第 2 章标题贴到视口上部（0~160px 内）',
        !!b.topChapterTop && b.topChapterTop.id === 'chapter-2'
        && b.topChapterTop.top >= 0 && b.topChapterTop.top <= 160,
        b.topChapterTop ? `${b.topChapterTop.id}@${b.topChapterTop.top}` : '无');
    check('B4 hash 同步成 #chapter-2', b.hash === '#chapter-2', b.hash);

    /* ---------- C：点【上一章】回退 ---------- */
    await clickBtn(0);
    const c = await geo();
    check('C1 回到第 1 章（上一章重新禁用）',
        c.navChapter && c.navChapter.prev === null && c.disabled[0] === true,
        JSON.stringify(c.navChapter) + ' / ' + String(c.disabled[0]));
    check('C2 回到页首附近', c.scrollY < 600, String(c.scrollY));

    /* ---------- D：滚到文末 ---------- */
    await evalJs(`(() => { window.scrollTo({ top: document.body.scrollHeight, behavior: 'instant' }); return true; })()`);
    await sleep(1200);
    const d = await geo();
    check('D1 文末「下一章」禁用（next 为 null）',
        d.navChapter && d.navChapter.next === null && d.disabled[1] === true,
        JSON.stringify(d.navChapter));
    check('D2 文末「上一章」仍可用', d.disabled[0] === false, String(d.disabled[0]));

    /* ---------- E：手机 390 ---------- */
    await load(390, 844);
    const e = await geo();
    check('E1 手机是横向胶囊（宽 > 高）', !!e.navRect && e.navRect.w > e.navRect.h,
        e.navRect ? `${e.navRect.w}x${e.navRect.h}` : '无');
    check('E2 贴底部（底部留白 ≤24px 且非负）',
        !!e.navRect && (e.innerH - e.navRect.b) <= 24 && (e.innerH - e.navRect.b) >= 0,
        e.navRect ? `${e.innerH - e.navRect.b}px` : '无');
    check('E3 水平居中（中心偏差 ≤20px）',
        !!e.navRect && Math.abs((e.navRect.l + e.navRect.w / 2) - e.innerW / 2) <= 20,
        e.navRect ? Math.round(e.navRect.l + e.navRect.w / 2) + ' vs ' + Math.round(e.innerW / 2) : '无');
    check('E4 手机按钮 ≥44px（触控下限）', e.btnH >= 44, `${e.btnH}px`);
    check('E5 手机无横向溢出', e.overflowX === false);

    /* ---------- F：深色背景换皮 ---------- */
    await evalJs(`(() => {
        var d = Alpine.$data(document.body);
        var p = d.bgPresets.find(function (x) { return d.isDarkBg(x.bg); });
        if (p) d.applyBg(p);
        return true;
    })()`);
    await sleep(350);
    const f = await geo();
    check('F1 深色底上按钮底色也跟着变深', !!f.bg && lum(f.bg) < 110, f.bg || '无按钮');

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

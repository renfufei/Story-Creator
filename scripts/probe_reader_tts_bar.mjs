#!/usr/bin/env node
/**
 * 探针：/projects/{id}/read 顶部「收听」条的折叠结构（2026-10-28）。
 *
 * 用户反馈：手机上这块铺开太大（实测 237px 高、5 行堆叠），音色/语速/段落/导出/全文收听
 * 各占一行，左边缘还对不齐。改成：默认折叠成一行（标题 + 音色·语速摘要 + 主操作 + 箭头），
 * 点箭头才摊开设置。
 *
 * 逐条钉住：
 *   A 折叠态（390px）：详情区不可见、折叠条 ≤40px、整块 ≤60px、只有【全文收听】、无横向溢出
 *   B 点箭头展开：详情区可见、aria-expanded=true、箭头转 180°、音色/语速/段落三行都在、
 *     标签左边缘一致、控件左边缘一致（对齐是本轮的主要诉求之一）
 *   C 再点一次收起：回到折叠态，高度复原
 *   D 播放中折叠态：仍然 ≤60px，且给的是【暂停】【停止】而不是【全文收听】
 *   E 宽屏 1280 展开：三行横排在同一水平带上，整块 ≤120px（不回到竖着堆三行的老样子）
 *   F 深色底：折叠条标题是浅色字（不能黑字黑底）
 *   G 全程零未捕获 JS 异常
 *
 * 1888 上跑的是旧 jar，磁盘上的 reader.html 还没进包 ⇒ 默认用 Fetch.fulfillRequest
 * 把文档请求换成本地文件，接口请求照旧打到服务。PROBE_LOCAL=0 可关闭（跑已打包的版本）。
 *
 * 用法：node scripts/probe_reader_tts_bar.mjs
 *      READER_BASE=http://localhost:1889 node scripts/probe_reader_tts_bar.mjs
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
const PORT = Number(process.env.PROBE_CDP_PORT || 9392);

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'reader-tts-bar-'));
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

const GEO = `(() => {
    const vis = (e) => !!e && getComputedStyle(e).display !== 'none' && e.getBoundingClientRect().height > 0;
    const tb = document.querySelector('.tts-toolbar');
    if (!tb) return JSON.stringify({ missing: 'tts-toolbar' });
    /* 下面这些新结构在改造前的页面上一个都不存在 —— 一律容错成 null/空，
       让断言以 FAIL 的形式呈现（而不是探针自己抛异常、看不出哪条不合规）。 */
    const bar = document.querySelector('.tts-bar');
    const det = document.querySelector('.tts-detail');
    const toggle = document.querySelector('.tts-toggle');
    const caret = document.querySelector('.tts-caret');
    const acts = document.querySelector('.tts-bar-actions');
    const rows = [...document.querySelectorAll('.tts-row')].filter(vis).map((e) => {
        const lab = e.querySelector('.tts-label');
        const ctl = e.querySelector('.form-select, .form-range, input[type=number], .btn');
        const r = e.getBoundingClientRect();
        return {
            label: lab ? lab.textContent.trim() : '',
            top: Math.round(r.top), h: Math.round(r.height),
            labelLeft: lab ? Math.round(lab.getBoundingClientRect().left) : null,
            ctlLeft: ctl ? Math.round(ctl.getBoundingClientRect().left) : null
        };
    });
    const r = tb.getBoundingClientRect();
    return JSON.stringify({
        hasBar: !!bar, tbH: Math.round(r.height), tbW: Math.round(r.width),
        barH: bar ? Math.round(bar.getBoundingClientRect().height) : -1,
        open: vis(det),
        expanded: toggle ? toggle.getAttribute('aria-expanded') : 'MISSING',
        caretT: caret ? getComputedStyle(caret).transform : '',
        rows: rows,
        play: vis(acts && acts.querySelector('.btn-outline-success')),
        pause: vis(acts && acts.querySelector('.btn-outline-primary')),
        stop: vis(acts && acts.querySelector('.btn-outline-danger')),
        overflowX: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
        titleColor: document.querySelector('.tts-title')
            ? getComputedStyle(document.querySelector('.tts-title')).color : '',
        labelColor: rows.length ? getComputedStyle(document.querySelector('.tts-label')).color : ''
    });
})()`;

const spread = (xs) => {
    const a = xs.filter((x) => typeof x === 'number');
    return a.length ? Math.max(...a) - Math.min(...a) : 999;
};
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
    /* 就绪条件用旧页面也有的 .tts-toolbar（改造前的页面上 .tts-bar 根本没有），
       这样跑旧版本时是断言 FAIL，而不是探针自己卡在等待里 */
    const ok = await waitFor(`!!(window.Alpine && document.querySelector('.tts-toolbar')
        && getComputedStyle(document.querySelector('.tts-toolbar')).display !== 'none')`, 150, 200);
    if (!ok) throw new Error('页面没就绪（.tts-toolbar 没出现，检查项目 1 是否有 TTS 配置）');
    await sleep(350);
}
const geo = async () => JSON.parse(await evalJs(GEO));
const clickToggle = async () => {
    /* 旧页面上没有 .tts-toggle：返回 false 让后续断言以 FAIL 收场，别把探针打断 */
    const hit = await evalJs(`(() => {
        var t = document.querySelector('.tts-toggle');
        if (!t) return false;
        t.click();
        return true;
    })()`);
    await sleep(320);
    return hit;
};

const HTML = INTERCEPT ? fs.readFileSync(LOCAL_HTML) : null;

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

    /* ---------- A / B / C：手机上的折叠 → 展开 → 收起 ---------- */
    await load(390, 844);
    const a = await geo();
    check('A0 存在折叠条 .tts-bar（旧结构是一堆平铺控件）', a.hasBar === true);
    check('A1 默认是折叠态（详情区不可见）', a.open === false);
    check('A2 折叠条本身 ≤40px', a.barH > 0 && a.barH <= 40, `barH=${a.barH}`);
    check('A3 整块 ≤60px（改造前 237px）', a.tbH <= 60, `tbH=${a.tbH}`);
    check('A4 折叠态只给【全文收听】', a.play && !a.pause && !a.stop,
        `play=${a.play} pause=${a.pause} stop=${a.stop}`);
    check('A5 窄屏无横向溢出', a.overflowX === false);
    check('A6 折叠条铺满卡片宽度（不留半截）', Math.abs(a.tbW - 262) <= 20 || a.tbW >= 240, `tbW=${a.tbW}`);

    await clickToggle();
    const b = await geo();
    const labels = b.rows.map((r) => r.label);
    check('B1 点箭头展开出详情区', b.open === true);
    check('B2 aria-expanded 同步成 true', b.expanded === 'true', `expanded=${b.expanded}`);
    check('B3 箭头转了 180°', /matrix\(-1/.test(b.caretT), b.caretT);
    check('B4 音色 / 语速 / 段落三行都在',
        labels.includes('音色') && labels.includes('语速') && labels.includes('段落'),
        labels.join('/'));
    check('B5 三行标签左边缘对齐（差 ≤1px）', spread(b.rows.map((r) => r.labelLeft)) <= 1,
        b.rows.map((r) => r.labelLeft).join(','));
    check('B6 控件左边缘对齐（差 ≤1px）', spread(b.rows.map((r) => r.ctlLeft)) <= 1,
        b.rows.map((r) => r.ctlLeft).join(','));
    check('B7 详情区竖排：行 top 严格递增', (() => {
        const tops = b.rows.map((r) => r.top);
        // 空数组会真空通过，这里必须要求真的有三行
        return tops.length >= 3 && tops.every((t, i) => i === 0 || t > tops[i - 1]);
    })(), b.rows.map((r) => r.top).join('<'));

    await clickToggle();
    const c = await geo();
    check('C1 再点一次收起', c.open === false);
    check('C2 收起后高度复原 ≤60px', c.tbH <= 60, `tbH=${c.tbH}`);
    check('C3 收起后 aria-expanded=false', c.expanded === 'false', `expanded=${c.expanded}`);

    /* ---------- D：播放中的折叠态 ---------- */
    await evalJs(`(() => {
        var d = Alpine.$data(document.querySelector('.tts-toolbar'));
        d.playlist.chapters = [1,2,3,4,5,6,7,8,9,10,11,12];
        d.playlist.currentIndex = 2;
        d.playlist.isActive = true;
        d.playlist.paused = true;
        return true;
    })()`);
    await sleep(320);
    const d = await geo();
    check('D1 播放中折叠态仍 ≤60px（不因控制项变高）', d.tbH <= 60, `tbH=${d.tbH}`);
    check('D2 播放中给的是【暂停】【停止】', d.pause && d.stop && !d.play,
        `pause=${d.pause} stop=${d.stop} play=${d.play}`);
    check('D3 播放中无横向溢出', d.overflowX === false);

    /* ---------- E：宽屏展开横排 ---------- */
    await load(1280, 900);
    await clickToggle();
    const e = await geo();
    check('E1 宽屏三行横排在同一水平带（top 差 ≤14px）',
        spread(e.rows.map((r) => r.top)) <= 14, e.rows.map((r) => r.top).join(','));
    check('E2 宽屏展开后整块 ≤120px（没退回竖堆）', e.tbH <= 120, `tbH=${e.tbH}`);

    /* ---------- F：深色底可读 ---------- */
    await evalJs(`(() => {
        var d = Alpine.$data(document.body);
        var p = d.bgPresets.find(function (x) { return d.isDarkBg(x.bg); });
        if (p) d.applyBg(p);
        return true;
    })()`);
    await sleep(320);
    const f = await geo();
    check('F1 深色底上标题是浅色字', lum(f.titleColor) > 128, f.titleColor);
    check('F2 深色底上标签是浅色字', lum(f.labelColor) > 110, f.labelColor);

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

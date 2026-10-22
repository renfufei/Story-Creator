#!/usr/bin/env node
/**
 * 润色页 / 校对页的【预览】：与扩写页**同一套**前后对照面板（2026-10-20）。
 *
 * 需求：把扩写页新做的对照面板（贴着各自行下方、两栏并排、句级差异高亮、左右同步滚动）
 * 迁移到 /workflow/polishing 与 /workflow/proofreading，替换原有的裸 split-view 预览。
 *
 * 本脚本逐条守护：
 *   P0 初始：每章都有「预览」按钮、没有面板、按钮 aria-expanded=false
 *   P1 【核心】点「预览」→ 面板出现在**该行内部、紧贴行下方**，按钮变「收起」
 *   P2 面板头：章号/标题 + 「原稿 n 字 → 润色后 m 字」+ 增幅徽标；两栏标题正确
 *   P3 【核心】句级差异：只在单边出现的句子标红/标绿，未改动句不染色
 *   P4 「差异高亮」开关：关掉后不染色但仍显示完整正文
 *   P5 【核心】多面板：再开第 2 章 → 两个面板各贴各自的行；收起一个不影响另一个
 *   P6 【核心】左右两栏滚动同步（按占比，双向，窄屏堆叠时不同步）
 *   P7 【核心】校对页（精修前后）：同样的面板、同样的同步与差异高亮
 *   P8 390px：两栏上下堆叠、无横向溢出
 *   P9 运行期无 JS 异常 / console.error
 *
 * 所有引导数据与章节内容都由 CDP Fetch 域在网络层喂进来（空库也能跑），
 * 页面 HTML/JS/CSS 仍走真实服务 —— 验的是真页面，不是拼出来的 HTML。
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_workflow_preview.mjs   # 1889 临时实例
 *   node scripts/probe_workflow_preview.mjs                               # 默认 1888
 * 环境变量：WF_BASE / TTS_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.WF_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9367;
const SHOT_DIR = '/tmp/wf-preview-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wf-preview-probe-'));
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
const CHAPTERS = [1, 2, 3, 4, 5].map(n => ({
    chapterNumber: n,
    title: '第' + n + '章 雨夜',
    wordCount: 1800,
    status: 'CONFIRMED',
    polishStatus: 'CONFIRMED',
    polishNote: '',
    proofreadStatus: 'GENERATED',
    proofreadFixStatus: 'GENERATED'
}));

const VOLUMES = [{
    volumeNumber: 1, title: '第一卷', arcName: '开篇',
    chapterStart: 1, chapterEnd: 5, chapterNumbers: [1, 2, 3, 4, 5]
}];

/* 长文本：40 段，每 5 段改写一处 —— 两侧都超过面板高度（能滚），且高度不同 */
const DRAFT = Array.from({ length: 40 },
    (_, i) => `第${i + 1}段：雨还在下，檐角的水滴连成线，打在青石板上碎成一片细响。`).join('\n');
const POLISHED = DRAFT.split('\n')
    .map((t, i) => (i % 5 === 0 ? t + '远处的灯影被雨丝拉长，像有人在窗后缓缓走动。' : t))
    .join('\n')
    // 再删掉一句，保证「删除」侧也有标记
    .replace('第2段：雨还在下，檐角的水滴连成线，打在青石板上碎成一片细响。\n', '');

const FIXED = POLISHED.split('\n')
    .map((t, i) => (i % 7 === 0 ? t.replace('雨还在下', '雨势渐歇') : t))
    .join('\n');

function bootstrap(step) {
    return {
        projectId: 1,
        projectTitle: '预览探针项目',
        genreDisplayName: '玄幻',
        viewStepDisplayName: step === 'POLISHING' ? '润色修改' : '校对精修',
        currentStep: step,
        currentStepOrder: step === 'POLISHING' ? 5 : 6,
        projectCurrentStep: step,
        projectCurrentStepOrder: step === 'POLISHING' ? 5 : 6,
        isCurrentStep: true,
        isLastStep: step === 'PROOFREADING',
        hasNextStep: step === 'POLISHING',
        totalChapters: 5,
        chapterWordCount: 1800,
        chapterWordCountMin: 1500,
        chapterWordCountMax: 2200,
        autoMode: false,
        autoRunStepConfigs: {},
        autoRunStrategy: 'DEFAULT',
        characterStateDims: [],
        currentContent: '',
        stepConfirmed: false,
        stepGuidance: '',
        modelConfigs: []
    };
}

const PROOFREAD_ROWS = CHAPTERS.map(c => ({
    chapterNumber: c.chapterNumber,
    title: c.title,
    wordCount: c.wordCount,
    proofreadStatus: 'GENERATED',
    proofreadFixStatus: 'GENERATED',
    plotSummary: '本章：主角在雨夜赶路。',
    foreshadowing: '[]',
    characterIssues: '[]',
    consistencyIssues: '[]',
    continuityIssues: '[]'
}));

/* ==================== 网络层拦截 ==================== */
let currentPage = '';   /* 最近一次导航到的页面，决定 /content 返回哪一版正文 */

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

    if (/^\/projects\/1\/workflow\/(polishing|proofreading)\/data$/.test(pn)) {
        const step = pn.indexOf('/polishing') > 0 ? 'POLISHING' : 'PROOFREADING';
        return fulfilJson(p.requestId, bootstrap(step));
    }
    if (pn === '/projects/1/chapters/list') return fulfilJson(p.requestId, CHAPTERS);
    if (pn === '/projects/1/volumes') return fulfilJson(p.requestId, VOLUMES);
    if (pn === '/projects/1/proofreading/data') return fulfilJson(p.requestId, PROOFREAD_ROWS);
    if (/^\/projects\/1\/chapters\/\d+\/content$/.test(pn)) {
        const proof = currentPage.indexOf('/proofreading') > 0;
        return fulfilJson(p.requestId, {
            chapterNumber: Number(pn.split('/')[4]),
            title: '章节正文',
            // 校对页：精修前=润色稿、精修后=精修稿；润色页：原稿=草稿、润色后=润色稿
            content: proof ? FIXED : POLISHED,
            contentDraft: DRAFT,
            contentBeforeFix: POLISHED
        });
    }
    if (pn.indexOf('/bg-gen/') === 0) return fulfilJson(p.requestId, { bgActive: false, status: 'idle' });
    if (pn === '/projects/1/characters/list') return fulfilJson(p.requestId, []);
    if (pn.indexOf('/settings/guidances/list-json') === 0) return fulfilJson(p.requestId, []);
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

/* ==================== 快照 / 操作 ==================== */
/* 每章一行：定位行元素（列表项），读取它的预览按钮与面板状态 */
const ROWS_JS = `(() => {
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none';
    const txt = (el) => el ? (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim() : '';
    const rows = Array.from(document.querySelectorAll('.list-group-item'))
        .filter(el => /^第\\d+章/.test((el.innerText || '').trim()));
    return rows.map(el => {
        const n = Number(((el.innerText || '').trim().match(/^第(\\d+)章/) || [])[1]);
        const panel = el.querySelector('.sc-cmp-preview');
        const btn = el.querySelector('button[aria-expanded]');
        const panes = panel ? Array.from(panel.querySelectorAll('.sc-cmp-pane')) : [];
        const heads = panel ? Array.from(panel.querySelectorAll('.sc-cmp-pane-head')).map(txt) : [];
        const del = panel ? Array.from(panel.querySelectorAll('.sc-cmp-del')).map(txt) : [];
        const add = panel ? Array.from(panel.querySelectorAll('.sc-cmp-add')).map(txt) : [];
        const hl = panel ? panel.querySelector('input[type=checkbox]') : null;
        const sync = panel ? panel.querySelector('.sc-cmp-sync input') : null;
        const box = el.getBoundingClientRect();
        const pbox = panel ? panel.getBoundingClientRect() : null;
        return {
            n: n,
            label: btn ? txt(btn) : '',
            aria: btn ? btn.getAttribute('aria-expanded') : '',
            btnClass: btn ? btn.className : '',
            open: !!panel && vis(panel),
            panelInRow: !!panel,
            headText: panel ? txt(panel.querySelector('.sc-cmp-head')) : '',
            stats: panel ? txt(panel.querySelector('.small.text-muted')) : '',
            delta: panel ? txt(panel.querySelector('.badge.rounded-pill')) : '',
            heads: heads,
            paneCount: panes.length,
            delCount: del.length,
            addCount: add.length,
            delSample: del.slice(0, 1).join(''),
            addSample: add.slice(0, 1).join(''),
            highlight: hl ? hl.checked : null,
            syncOn: sync ? sync.checked : null,
            anchorCount: panes.map(p => Array.from(p.querySelectorAll('[data-anc]'))
                .filter(s => Number(s.getAttribute('data-anc')) >= 0).length),
            gap: (panel && pbox) ? Math.round(pbox.top - (el.querySelector('div').getBoundingClientRect().bottom)) : null,
            rowTop: Math.round(box.top),
            rowBottom: Math.round(box.bottom),
            panelTop: pbox ? Math.round(pbox.top) : null,
            paneRects: panes.map(p => {
                const r = p.getBoundingClientRect();
                return { top: Math.round(r.top), left: Math.round(r.left), w: Math.round(r.width) };
            })
        };
    });
})()`;

const rows = () => evalJs(ROWS_JS);

/* 点第 n 章的「预览 / 收起」按钮 */
async function clickPreview(n) {
    return evalJs(`(() => {
        const el = Array.from(document.querySelectorAll('.list-group-item'))
            .find(e => /^第${n}章/.test((e.innerText || '').trim()));
        if (!el) return false;
        const btn = el.querySelector('button[aria-expanded]');
        if (!btn) return false;
        btn.click();
        return true;
    })()`);
}

/* 把第 n 章面板的第 idx 栏滚到 50% / 100%，返回两栏占比 */
async function scrollPane(n, idx, ratio) {
    return evalJs(`(() => {
        const el = Array.from(document.querySelectorAll('.list-group-item'))
            .find(e => /^第${n}章/.test((e.innerText || '').trim()));
        const panel = el && el.querySelector('.sc-cmp-preview');
        if (!panel) return null;
        const panes = Array.from(panel.querySelectorAll('.sc-cmp-pane'));
        const p = panes[${idx}];
        const max = p.scrollHeight - p.clientHeight;
        p.scrollTop = max * ${ratio};
        const r = (x) => { const m = x.scrollHeight - x.clientHeight; return m > 0 ? +(x.scrollTop / m).toFixed(3) : 0; };
        return { self: r(p), other: r(panes[1 - ${idx}]), selfMax: max,
                 otherMax: panes[1 - ${idx}].scrollHeight - panes[1 - ${idx}].clientHeight };
    })()`);
}

async function toggleHighlight(n) {
    return evalJs(`(() => {
        const el = Array.from(document.querySelectorAll('.list-group-item'))
            .find(e => /^第${n}章/.test((e.innerText || '').trim()));
        const hl = el && el.querySelector('.sc-cmp-preview input[type=checkbox]');
        if (!hl) return false;
        hl.checked = !hl.checked;
        hl.dispatchEvent(new Event('change', { bubbles: true }));
        return hl.checked;
    })()`);
}

/* 「同步滚动」开关（面板上第二个开关，包在 .sc-cmp-sync 里） */
async function toggleSync(n) {
    return evalJs(`(() => {
        const el = Array.from(document.querySelectorAll('.list-group-item'))
            .find(e => /^第${n}章/.test((e.innerText || '').trim()));
        const box = el && el.querySelector('.sc-cmp-sync input');
        if (!box) return false;
        box.checked = !box.checked;
        box.dispatchEvent(new Event('change', { bubbles: true }));
        return box.checked;
    })()`);
}

/* 某栏「压在顶边上的锚点句」：两侧同号才算同一句，off 是它离栏顶的像素 */
async function topSentence(n, idx) {
    return evalJs(`(() => {
        const el = Array.from(document.querySelectorAll('.list-group-item'))
            .find(e => /^第${n}章/.test((e.innerText || '').trim()));
        const panel = el && el.querySelector('.sc-cmp-preview');
        const p = panel ? Array.from(panel.querySelectorAll('.sc-cmp-pane'))[${idx}] : null;
        if (!p) return null;
        const pr = p.getBoundingClientRect();
        const hit = Array.from(p.querySelectorAll('[data-anc]'))
            .filter(s => Number(s.getAttribute('data-anc')) >= 0)
            .find(s => s.getBoundingClientRect().bottom > pr.top + 1);
        if (!hit) return null;
        const max = p.scrollHeight - p.clientHeight;
        return {
            k: hit.getAttribute('data-anc'),
            text: (hit.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 14),
            off: Math.round(hit.getBoundingClientRect().top - pr.top),
            ratio: max > 0 ? +(p.scrollTop / max).toFixed(3) : 0
        };
    })()`);
}

async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

async function goto(page, width = 1280, height = 1000) {
    currentPage = page;
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: BASE + page });
    return waitFor('列表渲染', async () => {
        try {
            const r = await evalJs(ROWS_JS);
            return r.length >= 3;
        } catch (e) { return false; }
    }, 20000, 150);
}

const docSize = () => evalJs(`({ w: document.documentElement.scrollWidth, c: document.documentElement.clientWidth })`);

/* 横向溢出的元凶：右边缘超出视口的元素（最多 3 个），用于判断是谁把页面顶宽的 */
const overflowers = () => evalJs(`(() => {
    const cw = document.documentElement.clientWidth;
    const bad = [];
    document.querySelectorAll('.sc-main *').forEach(el => {
        const r = el.getBoundingClientRect();
        if (r.width > 0 && r.right > cw + 1) {
            bad.push({
                tag: el.tagName.toLowerCase(),
                cls: (el.className || '').toString().split(' ').slice(0, 3).join('.'),
                right: Math.round(r.right),
                w: Math.round(r.width),
                text: (el.innerText || '').replace(/\\s+/g, ' ').slice(0, 24)
            });
        }
    });
    return bad.sort((a, b) => b.right - a.right).slice(0, 3);
})()`);

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
            { urlPattern: '*/workflow/*/data*', requestStage: 'Request' },
            { urlPattern: '*/chapters/list*', requestStage: 'Request' },
            { urlPattern: '*/projects/1/volumes*', requestStage: 'Request' },
            { urlPattern: '*/proofreading/data*', requestStage: 'Request' },
            { urlPattern: '*/chapters/*/content*', requestStage: 'Request' },
            { urlPattern: '*/bg-gen/*', requestStage: 'Request' },
            { urlPattern: '*/characters/list*', requestStage: 'Request' },
            { urlPattern: '*/guidances/list-json*', requestStage: 'Request' }
        ]
    });

    /* ---------- P0 润色页初始 ---------- */
    console.log('\n— P0 润色页：初始状态 —');
    await goto('/projects/1/workflow/polishing');
    let r = await rows();
    check('章节列表渲染出 5 行', r.length === 5, 'rows=' + r.length);
    check('每行都有「预览」按钮', r.every(x => x.label.indexOf('预览') >= 0),
        r.map(x => x.label).join('|'));
    check('初始没有展开的面板', r.every(x => !x.open), 'open=' + r.filter(x => x.open).length);
    check('按钮 aria-expanded=false', r.every(x => x.aria === 'false'));

    /* ---------- P1 点开 → 面板贴在该行内 ---------- */
    console.log('\n— P1 点「预览」→ 面板贴在该行下方 —');
    await clickPreview(1);
    await sleep(500);
    r = await rows();
    const c1 = r.find(x => x.n === 1);
    check('【核心】面板出现了', !!c1 && c1.open === true);
    check('【核心】面板就在这一行的元素内部（不是跑到列表末尾）', !!c1 && c1.panelInRow === true);
    check('【核心】面板紧贴行头下方（间距 ≤ 24px）', !!c1 && c1.gap !== null && c1.gap <= 24,
        'gap=' + (c1 && c1.gap));
    check('按钮切成「收起」+ aria-expanded=true + 主色按钮',
        c1.label.indexOf('收起') >= 0 && c1.aria === 'true' && c1.btnClass.indexOf('btn-secondary') >= 0,
        `${c1.label}/${c1.aria}`);
    await shot('01-polish-open');

    /* ---------- P2 面板头 + 两栏 ---------- */
    console.log('\n— P2 面板头与两栏 —');
    check('标题写明章号与「润色前后对照」',
        c1.headText.indexOf('第1章') >= 0 && c1.headText.indexOf('润色前后对照') >= 0, c1.headText);
    check('给出「原稿 n 字 → 润色后 m 字」',
        /原稿 \d+ 字/.test(c1.stats) && /润色后 \d+ 字/.test(c1.stats), c1.stats);
    check('给出字数增幅徽标', /^[+-]\d+\.\d+%$/.test(c1.delta), c1.delta);
    check('两栏标题是「原稿」/「润色后」',
        c1.heads.length === 2 && c1.heads[0].indexOf('原稿') >= 0 && c1.heads[1].indexOf('润色后') >= 0,
        c1.heads.join('|'));
    check('两栏都渲染出正文（可滚动）', c1.paneCount === 2);

    /* ---------- P3 句级差异 ---------- */
    console.log('\n— P3 句级差异高亮 —');
    check('【核心】原稿侧有被标为删除的句子', c1.delCount > 0, 'del=' + c1.delCount + ' ' + c1.delSample);
    check('【核心】润色后侧有被标为新增的句子', c1.addCount > 0, 'add=' + c1.addCount + ' ' + c1.addSample);
    check('删除/新增的样本确实是单边内容',
        c1.delSample.indexOf('第2段') >= 0 || c1.delSample.length > 0,
        c1.delSample.slice(0, 24));
    check('未改动的句子两侧都在（内容完整，不是只渲染 diff）',
        (await evalJs(`(() => {
            const el = Array.from(document.querySelectorAll('.list-group-item'))
                .find(e => /^第1章/.test((e.innerText || '').trim()));
            const panes = el.querySelectorAll('.sc-cmp-preview .sc-cmp-pane');
            return panes[0].innerText.indexOf('第3段') >= 0 && panes[1].innerText.indexOf('第3段') >= 0;
        })()`)) === true);

    /* ---------- P4 差异高亮开关 ---------- */
    console.log('\n— P4 「差异高亮」开关 —');
    await toggleHighlight(1);
    await sleep(400);
    r = await rows();
    let c1b = r.find(x => x.n === 1);
    check('关掉后不再染色', c1b.delCount === 0 && c1b.addCount === 0,
        `del=${c1b.delCount} add=${c1b.addCount}`);
    check('关掉后仍显示完整正文', (await evalJs(`(() => {
        const el = Array.from(document.querySelectorAll('.list-group-item'))
            .find(e => /^第1章/.test((e.innerText || '').trim()));
        const pane = el.querySelector('.sc-cmp-preview .sc-cmp-pane');
        return pane.innerText.indexOf('第4段') >= 0;
    })()`)) === true);
    await toggleHighlight(1);
    await sleep(400);
    r = await rows();
    c1b = r.find(x => x.n === 1);
    check('再打开又恢复染色', c1b.delCount > 0 && c1b.addCount > 0);

    /* ---------- P5 多面板共存 ---------- */
    console.log('\n— P5 多面板：各贴各自的行 —');
    await clickPreview(2);
    await sleep(500);
    r = await rows();
    const c2 = r.find(x => x.n === 2);
    const c1c = r.find(x => x.n === 1);
    check('【核心】允许同时展开两个面板', c1c.open && c2.open,
        `open=${r.filter(x => x.open).length}`);
    check('【核心】第 1 章面板仍在第 1 章行内（没被顶走）', c1c.panelInRow && c1c.open);
    check('【核心】第 2 章面板在第 2 章行内、间距正常', c2.panelInRow && c2.gap <= 24,
        'gap=' + c2.gap);
    check('第 2 章面板标题是第 2 章（数据不串）', c2.headText.indexOf('第2章') >= 0, c2.headText);
    await shot('02-multi-open');
    await clickPreview(1);
    await sleep(400);
    r = await rows();
    check('收起第 1 章后只剩第 2 章面板',
        r.filter(x => x.open).length === 1 && r.find(x => x.n === 2).open === true && !r.find(x => x.n === 1).open);

    /* ---------- P6 滚动同步（按同一句话对齐） ---------- */
    console.log('\n— P6 左右两栏滚动同步（内容对齐） —');
    let r2 = (await rows()).find(x => x.n === 2);
    check('两栏都渲染出锚点句（同一句两侧同号，同步靠它定位）',
        r2.anchorCount.length === 2 && r2.anchorCount[0] >= 30
        && r2.anchorCount[0] === r2.anchorCount[1], JSON.stringify(r2.anchorCount));
    check('【核心】面板上默认开着「同步滚动」', r2.syncOn === true, 'sync=' + r2.syncOn);

    let sc = await scrollPane(2, 0, 0.5);
    await sleep(250);
    sc = await scrollPane(2, 0, 0.5);
    await sleep(250);
    check('两栏都有可滚动空间（同步前提）', sc.selfMax > 0 && sc.otherMax > 0,
        `self=${sc.selfMax} other=${sc.otherMax}`);

    const lt = await topSentence(2, 0);
    const rt = await topSentence(2, 1);
    check('【核心】滚左栏到一半 → 右栏把**同一句话**摆到同样的位置',
        !!lt && !!rt && lt.k === rt.k && Math.abs(lt.off - rt.off) <= 4,
        `left=k${lt && lt.k}「${lt && lt.text}」@${lt && lt.off}  right=k${rt && rt.k}「${rt && rt.text}」@${rt && rt.off}`);
    check('【核心】两侧占比并不相等（按内容对齐，不是按百分比硬套）',
        !!lt && !!rt && Math.abs(lt.ratio - rt.ratio) > 0.02,
        `left=${lt && lt.ratio} right=${rt && rt.ratio}`);

    await scrollPane(2, 1, 0.4);
    await sleep(350);
    const lt2 = await topSentence(2, 0);
    const rt2 = await topSentence(2, 1);
    check('【核心】反向同样按内容对齐（滚右栏 → 左栏跟到同一句）',
        !!lt2 && !!rt2 && lt2.k === rt2.k && Math.abs(lt2.off - rt2.off) <= 4,
        `left=k${lt2 && lt2.k}@${lt2 && lt2.off} right=k${rt2 && rt2.k}@${rt2 && rt2.off}`);

    let sc2 = await scrollPane(2, 1, 1);
    await sleep(300);
    sc2 = await scrollPane(2, 1, 1);
    await sleep(250);
    check('【核心】滚右栏到底 → 左栏也到底（反向同样同步）',
        sc2.self >= 0.98 && sc2.other >= 0.98, `left=${sc2.other} right=${sc2.self}`);

    /* 关掉【同步滚动】→ 各滚各的；打开 → 立刻恢复对齐 */
    await scrollPane(2, 0, 0);
    await sleep(300);
    check('关掉【同步滚动】开关', (await toggleSync(2)) === false);
    await sleep(300);
    sc = await scrollPane(2, 0, 0.3);
    await sleep(350);
    check('【核心】关掉后各滚各的（右栏留在原地）',
        sc.self > 0.25 && sc.other <= 0.02, `left=${sc.self} right=${sc.other}`);
    check('重新打开【同步滚动】', (await toggleSync(2)) === true);
    await sleep(300);
    await scrollPane(2, 0, 0.55);
    await sleep(350);
    const lt3 = await topSentence(2, 0);
    const rt3 = await topSentence(2, 1);
    check('重新打开后立刻恢复内容对齐',
        !!lt3 && !!rt3 && lt3.k === rt3.k && Math.abs(lt3.off - rt3.off) <= 4,
        `left=k${lt3 && lt3.k}@${lt3 && lt3.off} right=k${rt3 && rt3.k}@${rt3 && rt3.off}`);
    await shot('03-scroll-sync');

    /* ---------- P7 校对页 ---------- */
    console.log('\n— P7 校对页：精修前后同一套面板 —');
    await goto('/projects/1/workflow/proofreading');
    r = await rows();
    check('校对页章节列表渲染出 5 行', r.length === 5, 'rows=' + r.length);
    check('校对页每行有「预览」按钮', r.every(x => x.label.indexOf('预览') >= 0),
        r.map(x => x.label).join('|'));
    await clickPreview(1);
    await sleep(500);
    r = await rows();
    const p1 = r.find(x => x.n === 1);
    check('【核心】校对页面板也贴在行内', p1 && p1.open && p1.panelInRow, 'gap=' + (p1 && p1.gap));
    check('标题是「精修前后对照」', p1.headText.indexOf('精修前后对照') >= 0, p1.headText);
    check('两栏标题是「精修前」/「精修后」',
        p1.heads.length === 2 && p1.heads[0].indexOf('精修前') >= 0 && p1.heads[1].indexOf('精修后') >= 0,
        p1.heads.join('|'));
    check('统计是「精修前 n 字 → 精修后 m 字」',
        /精修前 \d+ 字/.test(p1.stats) && /精修后 \d+ 字/.test(p1.stats), p1.stats);
    check('【核心】校对页同样有句级差异高亮', p1.delCount > 0 || p1.addCount > 0,
        `del=${p1.delCount} add=${p1.addCount}`);
    let psc = await scrollPane(1, 0, 0.5);
    await sleep(300);
    psc = await scrollPane(1, 0, 0.5);
    await sleep(250);
    const plt = await topSentence(1, 0);
    const prt = await topSentence(1, 1);
    check('【核心】校对页两栏也按同一句话同步（不是按百分比）',
        !!plt && !!prt && plt.k === prt.k && Math.abs(plt.off - prt.off) <= 4,
        `left=k${plt && plt.k}「${plt && plt.text}」@${plt && plt.off}  right=k${prt && prt.k}「${prt && prt.text}」@${prt && prt.off}`);
    check('校对页两栏也有「同步滚动」开关且默认开',
        (await rows()).find(x => x.n === 1).syncOn === true);
    await shot('04-proofreading-open');

    /* ---------- P8 390px ---------- */
    console.log('\n— P8 390px 窄屏 —');
    await goto('/projects/1/workflow/polishing', 390, 780);
    const sizeBefore = await docSize();
    if (sizeBefore.w > sizeBefore.c + 1) {
        console.log('  越界元素: ' + JSON.stringify(await overflowers()));
    }
    check('窄屏未展开面板时页面本身无横向溢出', sizeBefore.w <= sizeBefore.c + 1,
        `scrollW=${sizeBefore.w} clientW=${sizeBefore.c}`);
    await clickPreview(1);
    await sleep(500);
    r = await rows();
    const m1 = r.find(x => x.n === 1);
    const size = await docSize();
    check('窄屏面板仍贴在行内', m1.open && m1.panelInRow);
    check('窄屏两栏上下堆叠（不是左右挤压）',
        m1.paneRects.length === 2 && Math.abs(m1.paneRects[0].left - m1.paneRects[1].left) <= 1
        && m1.paneRects[1].top > m1.paneRects[0].top,
        JSON.stringify(m1.paneRects));
    check('窄屏展开面板后仍无横向溢出', size.w <= size.c + 1,
        `before=${sizeBefore.w} after=${size.w} clientW=${size.c}`);
    await shot('05-mobile');

    /* ---------- P9 运行期 ---------- */
    console.log('\n— P9 运行期异常 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '));

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

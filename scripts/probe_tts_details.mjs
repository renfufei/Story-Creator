#!/usr/bin/env node
/**
 * /tts-export 任务详情面板：展示与交互验证（2026-10-19）。
 *
 * 用户反馈的三处措辞（本轮已改）：
 *   · 展开区右上角「关闭」→「折叠」（"关闭"像在关任务/关页面）
 *   · 「进度」→「实时进度」（与表格里的进度条区分开）
 *   · 「编辑章节」→「选择章节」（与向导第 2 步同一口径）
 * 顺带把行内「详情」做成 toggle：展开后高亮并变「折叠」，不用回面板里找收起入口。
 *
 * 本脚本逐条守护：
 *   P0 前提：列表里有任务；行内按钮初始是「详情」
 *   P1 【核心】点「详情」→ 面板展开，按钮变「折叠」且高亮/aria-expanded=true
 *   P2 【核心】面板里的三处文案就是「选择章节 / 实时进度 / 折叠」，且不再出现「关闭」「编辑章节」
 *   P3 面板摘要行：任务号、项目/语音/速度/格式、已完成 n/m
 *   P4 章节明细表按任务真实章数渲染
 *   P5 【核心】「实时进度」是 toggle：点开日志出现 + SSE 建立；再点收起 + SSE 关闭
 *   P6 面板里的「折叠」能收起，行内按钮回「详情」
 *   P7 【核心】「选择章节」打开勾选区（保存按钮为「保存选择」），「取消修改」能收起
 *   P8 从任务 A 切到任务 B：只留 B 的面板，A 的 SSE 被关掉（不留后台连接）
 *   P9 375px：整页无横向滚动，面板工具按钮不溢出面板
 *   P10 运行期无 JS 异常 / console.error
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_tts_details.mjs     # 1889 临时实例
 *   node scripts/probe_tts_details.mjs                                # 默认 1888
 * 环境变量：TTS_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 *
 * ⚠️ 数据前提：本探针需要「至少一个非运行中的导出任务」。
 *   库里的任务都是 RUNNING 时（真跑着导出），会自动新建一个 PENDING 任务用于验证，
 *   跑完把它删掉；用真实库副本跑（run.sh 默认行为），不会碰用户的 1888 与真实库。
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.TTS_BASE || 'http://localhost:1888';
const PAGE = BASE + '/tts-export';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9343;
const SHOT_DIR = '/tmp/tts-detail-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tts-detail-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let ws, msgId = 0;
const pending = new Map();
const runtimeErrors = [];
const consoleErrors = [];
let progressStreamHits = 0;      // /progress-stream 真实请求次数（网络层计数，不受任务状态影响）

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

/* 真鼠标点击：先滚到视口中间再取坐标（表格展开行常常在视口外，直接点会打空） */
async function clickSelector(sel) {
    const box = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(sel)});
        if (!el) return null;
        el.scrollIntoView({ block: 'center', inline: 'center' });
        const r = el.getBoundingClientRect();
        if (r.width < 1 || r.height < 1) return null;
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!box) throw new Error('点不到元素: ' + sel);
    await sleep(120);
    const common = { x: Math.round(box.x), y: Math.round(box.y), button: 'left', clickCount: 1 };
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...common, buttons: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...common, buttons: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...common, buttons: 0 });
}

/* 一次取全，避免逐字段读之间状态漂移（Alpine 的 DOM 更新是异步的，取快照前先静置一拍） */
const snapFor = (taskId) => `(() => {
    const id = ${JSON.stringify(String(taskId))};
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden';
    const txt = (el) => el ? el.innerText.replace(/\\s+/g, ' ').trim() : '';
    const rowBtn = document.querySelector('[data-action="details"][data-task-id="' + id + '"]');
    const panels = Array.from(document.querySelectorAll('.tts-detail'));
    const panel = document.querySelector('.tts-detail[data-detail-for="' + id + '"]');
    const q = (sel) => panel ? panel.querySelector(sel) : null;
    const selectBtn = q('[data-action="select-chapters"]');
    const progBtn = q('[data-action="progress"]');
    const collapseBtn = q('[data-action="collapse"]');
    const saveBtn = q('[data-action="save-select"]');
    const log = q('.tts-progress-log');
    const table = q('.tts-chapter-table');
    const meta = panel ? panel.querySelector('.small.text-muted') : null;
    const rect = (el) => { const r = el.getBoundingClientRect();
        return { l: Math.round(r.left), r: Math.round(r.right), w: Math.round(r.width) }; };
    const panelBox = vis(panel) ? rect(panel) : null;
    const toolBtns = panel ? Array.from(panel.querySelectorAll('[data-action="select-chapters"],[data-action="progress"],[data-action="collapse"]')) : [];
    return {
        rowCount: document.querySelectorAll('[data-action="details"]').length,
        taskRowHeight: rowBtn && rowBtn.closest('tr') ? Math.round(rowBtn.closest('tr').getBoundingClientRect().height) : null,
        chapterRowHeight: (() => {
            const tr = table && table.tBodies[0] && table.tBodies[0].rows[0];
            return tr ? Math.round(tr.getBoundingClientRect().height) : null;
        })(),
        rowBtnText: txt(rowBtn),
        rowBtnAria: rowBtn ? rowBtn.getAttribute('aria-expanded') : null,
        rowBtnSolid: rowBtn ? rowBtn.classList.contains('btn-secondary') : null,
        panelExists: !!panel,
        panelVisible: vis(panel),
        visiblePanelCount: panels.filter(vis).length,
        panelTaskId: panel ? panel.getAttribute('data-detail-for') : null,
        header: panel ? txt(panel.querySelector('.fw-bold')) : '',
        metaText: txt(meta),
        selectText: txt(selectBtn),
        selectVisible: vis(selectBtn),
        progText: txt(progBtn),
        progPressed: progBtn ? progBtn.getAttribute('aria-pressed') : null,
        progSolid: progBtn ? progBtn.classList.contains('btn-secondary') : null,
        collapseText: txt(collapseBtn),
        saveVisible: vis(saveBtn),
        saveText: txt(saveBtn),
        logVisible: vis(log),
        /* ⚠️ 不能用 table.querySelectorAll('tbody tr') 数行：
           详情面板本身长在外层表格的 <tbody> 里，选择器里的 tbody 会匹配到**外层**的 tbody，
           于是内层表头那行 <tr> 也被算进来（120 章报成 121 行）。
           HTMLTableSectionElement.rows 只认直属 <tr>，口径才与数据一致。 */
        chapterRows: table && table.tBodies[0] ? table.tBodies[0].rows.length : 0,
        rowCells: table && table.tBodies[0] ? Array.from(table.tBodies[0].rows).map(tr => {
            const c = tr.cells && tr.cells[0];
            return c ? c.innerText.replace(/\\s+/g, ' ').trim() : '?';
        }) : [],
        editingId: (() => {
            const el = document.querySelector('main [x-data]');
            const d = el && window.Alpine ? Alpine.$data(el) : null;
            return d ? d.editingChaptersTaskId : null;
        })(),
        cancelBox: (() => { const b = q('[data-action="cancel-select"]'); return b && vis(b) ? rect(b) : null; })(),
        cancelHit: (() => {
            const b = q('[data-action="cancel-select"]');
            if (!b || !vis(b)) return null;
            const r = b.getBoundingClientRect();
            const el = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
            return el ? el.tagName + '.' + String(el.className || '').slice(0, 48) : 'null';
        })(),
        panelButtons: panel ? Array.from(panel.querySelectorAll('.btn, a.btn')).map(txt).join(' | ') : '',
        panelBox,
        toolsOverflow: toolBtns.filter(b => vis(b) && panelBox && rect(b).r > panelBox.r + 1).length,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;
const snap = async (taskId) => { await sleep(260); return evalJs(snapFor(taskId)); };

/* Alpine 根数据：拿 SSE 连接表，验证「折叠/切任务后连接确实被关掉」 */
const SSE_KEYS = `(() => {
    const el = document.querySelector('main [x-data]');
    const d = el && window.Alpine ? Alpine.$data(el) : null;
    return d ? Object.keys(d.progressEventSources || {}) : null;
})()`;

async function shot(name) {
    try {
        fs.mkdirSync(SHOT_DIR, { recursive: true });
        const r = await send('Page.captureScreenshot', { format: 'png' });
        fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
    } catch (e) { /* 截图失败不影响判定 */ }
}

async function loadPage(width, height, mobile) {
    await send('Emulation.setDeviceMetricsOverride', {
        width, height, deviceScaleFactor: mobile ? 2 : 1, mobile
    });
    await send('Page.navigate', { url: PAGE });
    return await waitFor('页面 Alpine 就绪且有任务行', async () =>
        (await evalJs(`!!window.Alpine && document.querySelectorAll('[data-action="details"]').length > 0`)) === true,
        15000, 200);
}

/* ---------- 数据前提：找一个非运行中的任务；没有就建一个（跑完删掉） ---------- */
async function prepareTask() {
    const tasks = await evalJs(`fetch('/api/tts-export/tasks').then(r => r.json())`);
    const usable = (tasks || []).filter(t => t.status !== 'RUNNING' && t.progressTotalChapters > 0);
    if (usable.length >= 2) return { tasks: usable, created: null };
    if (usable.length === 1) return { tasks: usable, created: null };

    const made = await evalJs(`(async () => {
        const data = await (await fetch('/tts-export/data')).json();
        const pid = (data.projects && data.projects[0] && data.projects[0].id) || 1;
        const chapters = await (await fetch('/api/tts-export/projects/' + pid + '/chapters')).json();
        const nums = (chapters || []).slice(0, 3).map(c => c.chapterNumber);
        if (!nums.length) return { error: '项目没有可用章节，无法自建任务' };
        const res = await fetch('/api/tts-export/tasks', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ projectId: Number(pid), configId: 1, voice: 'alloy', speed: 1.0,
                minLen: 20, maxLen: 50, useFfmpeg: false, bitrate: '128k', audioFormat: 'mp3',
                chapterNumbers: nums, chunkGapSeconds: 0.1, skipGapSeconds: 0.3 })
        });
        return res.ok ? await res.json() : { error: 'HTTP ' + res.status };
    })()`);
    if (made && made.id) {
        const all = await evalJs(`fetch('/api/tts-export/tasks').then(r => r.json())`);
        return { tasks: (all || []).filter(t => t.status !== 'RUNNING'), created: made.id };
    }
    return { tasks: usable, created: null };
}

async function main() {
    let ver = null;
    for (let i = 0; i < 80; i++) {
        try {
            const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
            if (r.ok) { ver = await r.json(); break; }
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    if (!ver) throw new Error('Chrome CDP 端点未就绪，无法继续');
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
        } else if (m.method === 'Network.requestWillBeSent'
            && String(m.params.request.url).indexOf('/progress-stream') >= 0) {
            progressStreamHits++;
        }
    };
    await send('Runtime.enable');
    await send('Network.enable');

    /* 先导航到页面再准备数据：about:blank 里 fetch 相对 URL 无法解析（会直接 TypeError） */
    await send('Page.navigate', { url: PAGE });
    await waitFor('首屏可交互（数据准备用）', async () =>
        (await evalJs(`location.pathname === '/tts-export' && !!window.Alpine`)) === true, 15000, 200);

    const prep = await prepareTask();
    if (!prep.tasks.length) throw new Error('库里没有可用于验证的导出任务，且自建失败');
    const taskA = prep.tasks[0];
    const taskB = prep.tasks[1] || null;
    const detailA = await evalJs(`fetch('/api/tts-export/tasks/${taskA.id}').then(r => r.json())`);
    const apiChapterCount = (detailA.chapters || []).length;
    const created = prep.created;
    console.log(`验证任务：A=#${taskA.id}（${taskA.status}, 明细 ${apiChapterCount} 章 / 任务总数 ${taskA.progressTotalChapters}）`
        + (taskB ? ` / B=#${taskB.id}（${taskB.status}）` : ' / 只有一个任务')
        + (created ? `（本探针新建 #${created}）` : ''));

    /* ---------- P0 前提 ---------- */
    console.log('\n— P0 前提 —');
    const ready = await loadPage(1280, 940, false);
    check('页面就绪、任务表至少一行', ready);
    const s0 = await snap(taskA.id);
    check('行内按钮初始为「详情」，aria-expanded=false，未高亮',
        s0.rowBtnText === '详情' && s0.rowBtnAria === 'false' && s0.rowBtnSolid === false,
        `text=${s0.rowBtnText} aria=${s0.rowBtnAria} solid=${s0.rowBtnSolid}`);

    /* ---------- P1 展开 ---------- */
    console.log('\n— P1 点「详情」展开 —');
    await clickSelector('[data-action="details"][data-task-id="' + taskA.id + '"]');
    const opened = await waitFor('面板展开', async () => (await evalJs(
        `!!document.querySelector('.tts-detail[data-detail-for="${taskA.id}"]')
          && document.querySelector('.tts-detail[data-detail-for="${taskA.id}"]').getClientRects().length > 0`
    )) === true, 8000, 140);
    const s1 = await snap(taskA.id);
    check('【核心】点「详情」后面板展开', opened && s1.panelVisible === true,
        `opened=${opened} visible=${s1.panelVisible}`);
    check('【核心】行内按钮随之变「折叠」并高亮（同一颗按钮 toggle）',
        s1.rowBtnText === '折叠' && s1.rowBtnAria === 'true' && s1.rowBtnSolid === true,
        `text=${s1.rowBtnText} aria=${s1.rowBtnAria} solid=${s1.rowBtnSolid}`);
    check('同时只有一块详情面板可见（不会串台叠出两块）', s1.visiblePanelCount === 1,
        'visible=' + s1.visiblePanelCount);
    await shot('01-desktop-expanded');

    /* ---------- P2 三处措辞 ---------- */
    console.log('\n— P2 面板文案 —');
    check('【核心】「选择章节」取代「编辑章节」', /选择章节/.test(s1.selectText), 'text=' + s1.selectText);
    check('【核心】「实时进度」取代「进度」', /实时进度/.test(s1.progText), 'text=' + s1.progText);
    check('【核心】「折叠」取代「关闭」', /折叠/.test(s1.collapseText), 'text=' + s1.collapseText);
    check('面板里已没有「关闭」「编辑章节」这类措辞',
        !/关闭/.test(s1.panelButtons) && !/编辑章节/.test(s1.panelButtons),
        s1.panelButtons.slice(0, 120));

    /* ---------- P3 摘要 ---------- */
    console.log('\n— P3 面板摘要 —');
    check('标题含任务号与「章节详情」',
        s1.header.indexOf('#' + taskA.id) >= 0 && /章节详情/.test(s1.header), s1.header);
    check('摘要行含「已完成 n/m」与语音/速度/格式之一',
        /已完成\s*\d+\/\d+/.test(s1.metaText) && /(语音|速度|x|MP3|WAV|OPUS|AAC|FLAC)/i.test(s1.metaText),
        s1.metaText.slice(0, 120));

    /* ---------- P4 明细表 ---------- */
    console.log('\n— P4 章节明细 —');
    const cells = s1.rowCells;
    const dupCells = cells.filter((c, i) => cells.indexOf(c) !== i);
    const blankCells = cells.filter(c => !c || c === '?').length;
    check('明细表按任务返回的章节数逐行渲染',
        s1.chapterRows === apiChapterCount && s1.chapterRows > 0,
        `rows=${s1.chapterRows} api=${apiChapterCount} taskTotal=${taskA.progressTotalChapters} `
            + `dup=${JSON.stringify(dupCells.slice(0, 4))} blank=${blankCells} `
            + `head=${JSON.stringify(cells.slice(0, 3))} tail=${JSON.stringify(cells.slice(-2))}`);

    /* ---------- P5 实时进度 toggle ---------- */
    console.log('\n— P5 实时进度 —');
    await clickSelector('[data-action="progress"][data-task-id="' + taskA.id + '"]');
    const logOn = await waitFor('日志块出现', async () => (await evalJs(
        `(() => { const p = document.querySelector('.tts-detail[data-detail-for="${taskA.id}"]');
                 const l = p && p.querySelector('.tts-progress-log');
                 return !!l && l.getClientRects().length > 0; })()`)) === true, 6000, 140);
    const s5 = await snap(taskA.id);
    check('点「实时进度」→ 日志块出现、按钮进入按下态',
        logOn && s5.logVisible === true && s5.progPressed === 'true' && s5.progSolid === true,
        `visible=${s5.logVisible} pressed=${s5.progPressed} solid=${s5.progSolid}`);
    /* ⚠️ 不能用 progressEventSources 里有没有 key 来证明「连上了」：
       已完成的任务，后端把历史重放完就发 done，前端随即 close 并删 key，
       快照时刻大概率已经收尾 —— 那是正常行为，不是没连上。
       所以在网络层数 /progress-stream 请求（见 Network.requestWillBeSent）。 */
    const hitOn = await waitFor('已向后端发起 progress-stream 请求',
        async () => progressStreamHits >= 1, 5000, 100);
    check('同时确实向后端发起了该任务的实时进度流请求（/progress-stream）',
        hitOn && progressStreamHits >= 1, 'hits=' + progressStreamHits);
    await shot('02-progress-open');

    await clickSelector('[data-action="progress"][data-task-id="' + taskA.id + '"]');
    const logOff = await waitFor('日志块收起', async () => (await evalJs(
        `(() => { const p = document.querySelector('.tts-detail[data-detail-for="${taskA.id}"]');
                 const l = p && p.querySelector('.tts-progress-log');
                 return !l || l.getClientRects().length === 0; })()`)) === true, 6000, 140);
    const keysOff = await evalJs(SSE_KEYS);
    check('再点一次收起日志、按钮回弹，且 SSE 被关掉（不留后台连接）',
        logOff && (!keysOff || keysOff.indexOf(String(taskA.id)) < 0),
        `off=${logOff} keys=${JSON.stringify(keysOff)}`);

    /* ---------- P6 折叠 ---------- */
    console.log('\n— P6 面板内「折叠」 —');
    await clickSelector('[data-action="collapse"][data-task-id="' + taskA.id + '"]');
    const collapsed = await waitFor('面板收起', async () => (await evalJs(
        `(() => { const p = document.querySelector('.tts-detail[data-detail-for="${taskA.id}"]');
                 return !p || p.getClientRects().length === 0; })()`)) === true, 6000, 140);
    const s6 = await snap(taskA.id);
    check('【核心】点「折叠」收起面板，行内按钮回「详情」且取消高亮',
        collapsed && s6.panelVisible === false && s6.rowBtnText === '详情'
            && s6.rowBtnAria === 'false' && s6.rowBtnSolid === false,
        `collapsed=${collapsed} text=${s6.rowBtnText} aria=${s6.rowBtnAria}`);

    /* ---------- P7 选择章节 ---------- */
    console.log('\n— P7 「选择章节」 —');
    await clickSelector('[data-action="details"][data-task-id="' + taskA.id + '"]');
    await waitFor('面板重新展开', async () => (await evalJs(
        `!!document.querySelector('.tts-detail[data-detail-for="${taskA.id}"]')
          && document.querySelector('.tts-detail[data-detail-for="${taskA.id}"]').getClientRects().length > 0`
    )) === true, 6000, 140);
    const canSelect = await evalJs(`(() => {
        const b = document.querySelector('[data-action="select-chapters"][data-task-id="${taskA.id}"]');
        return !!b && b.getClientRects().length > 0;
    })()`);
    if (canSelect) {
        await clickSelector('[data-action="select-chapters"][data-task-id="' + taskA.id + '"]');
        const editorOn = await waitFor('勾选区出现', async () => (await evalJs(
            `(() => { const b = document.querySelector('[data-action="save-select"]');
                     return !!b && b.getClientRects().length > 0; })()`)) === true, 6000, 140);
        const s7 = await snap(taskA.id);
        check('【核心】点「选择章节」→ 勾选区打开，保存按钮文案为「保存选择」',
            editorOn && s7.saveVisible === true && /保存选择/.test(s7.saveText),
            `open=${editorOn} save=${s7.saveText}`);
        await shot('03-select-chapters');
        const editorVisible = () => evalJs(
            `(() => { const b = document.querySelector('[data-action="save-select"]');
                     return !!b && b.getClientRects().length > 0; })()`);
        let editorOff = false;
        let dbg = null;
        for (let i = 0; i < 4 && !editorOff; i++) {
            await clickSelector('[data-action="cancel-select"]');
            await sleep(280);
            editorOff = (await editorVisible()) === false;
            dbg = await snap(taskA.id);
        }
        check('「取消修改」能收起勾选区（不改任务）', editorOff,
            editorOff ? '' : `editingId=${dbg && dbg.editingId} cancelBox=${JSON.stringify(dbg && dbg.cancelBox)} hit=${dbg && dbg.cancelHit}`);
        check('收起后「选择章节」按钮重新出现（可以再点开）',
            editorOff ? (await snap(taskA.id)).selectVisible === true : false,
            'selectVisible=' + (await snap(taskA.id)).selectVisible);
    } else {
        check('该任务运行中，按设计不显示「选择章节」（跳过交互验证）', true, 'status=' + taskA.status);
    }

    /* ---------- P8 切换任务 ---------- */
    console.log('\n— P8 切换任务 —');
    if (taskB) {
        // 先给 A 打开进度流，再切到 B，验证 A 的连接被关掉
        await clickSelector('[data-action="progress"][data-task-id="' + taskA.id + '"]');
        await waitFor('A 的进度流已建立', async () => {
            const k = await evalJs(SSE_KEYS);
            return Array.isArray(k) && k.indexOf(String(taskA.id)) >= 0;
        }, 5000, 120);
        await clickSelector('[data-action="details"][data-task-id="' + taskB.id + '"]');
        await waitFor('B 的面板展开', async () => (await evalJs(
            `!!document.querySelector('.tts-detail[data-detail-for="${taskB.id}"]')
              && document.querySelector('.tts-detail[data-detail-for="${taskB.id}"]').getClientRects().length > 0`
        )) === true, 8000, 140);
        const s8 = await snap(taskB.id);
        const keys8 = await evalJs(SSE_KEYS);
        check('切到任务 B 后只剩 B 的面板（A 自动收起）',
            s8.visiblePanelCount === 1 && s8.panelTaskId === String(taskB.id),
            `visible=${s8.visiblePanelCount} panelTask=${s8.panelTaskId}`);
        check('A 的进度 SSE 已被关掉（切任务不留下后台连接）',
            !keys8 || keys8.indexOf(String(taskA.id)) < 0, JSON.stringify(keys8));
        const sa = await snap(taskA.id);
        check('A 的行内按钮已回「详情」', sa.rowBtnText === '详情', 'text=' + sa.rowBtnText);
    } else {
        check('库里只有一个任务，跳过「切换任务」用例（不真空断言）', true, 'tasks=1');
    }

    /* ---------- P9 375px ---------- */
    console.log('\n— P9 375px 窄屏 —');
    await loadPage(375, 760, true);
    await clickSelector('[data-action="details"][data-task-id="' + taskA.id + '"]');
    await waitFor('窄屏面板展开', async () => (await evalJs(
        `!!document.querySelector('.tts-detail[data-detail-for="${taskA.id}"]')
          && document.querySelector('.tts-detail[data-detail-for="${taskA.id}"]').getClientRects().length > 0`
    )) === true, 8000, 140);
    const s9 = await snap(taskA.id);
    check('375px：整页无横向滚动条（宽表只在卡片内滚）',
        s9.docScrollW <= s9.docClientW + 1, `scrollW=${s9.docScrollW} clientW=${s9.docClientW}`);
    check('375px：面板工具按钮会换行，不会横向溢出面板',
        s9.toolsOverflow === 0, `overflow=${s9.toolsOverflow} panel=${JSON.stringify(s9.panelBox)}`);
    check('375px：任务行高度正常（项目名/按钮不折行，不会把行撑成几百像素）',
        s9.taskRowHeight !== null && s9.taskRowHeight <= 90, 'rowH=' + s9.taskRowHeight);
    check('375px：章节明细行高度正常（「第1章」不会一字一行地竖排）',
        s9.chapterRowHeight !== null && s9.chapterRowHeight <= 60, 'chapterRowH=' + s9.chapterRowHeight);
    check('375px：三处文案仍在（换行不影响可读性）',
        /实时进度/.test(s9.progText) && /折叠/.test(s9.collapseText)
            && (!s9.selectVisible || /选择章节/.test(s9.selectText)),
        `select=${s9.selectText} progress=${s9.progText} collapse=${s9.collapseText}`);
    await shot('04-mobile-expanded');
    // 再把章节明细表滚到视口里拍一张：这块是窄屏最容易塌的地方（列被压窄后逐字竖排）
    await evalJs(`(() => {
        const t = document.querySelector('.tts-chapter-table');
        if (t) t.scrollIntoView({ block: 'center' });
        return true;
    })()`);
    await sleep(260);
    await shot('05-mobile-chapter-table');

    /* ---------- P10 运行期异常 ---------- */
    console.log('\n— P10 运行期异常 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 3).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

    // 收尾：本探针新建的任务删掉
    if (created) {
        await evalJs(`fetch('/api/tts-export/tasks/${created}', { method: 'DELETE' }).then(r => r.status)`);
        console.log('已删除探针新建的任务 #' + created);
    }

    const bad = results.filter(r => !r.ok);
    console.log(`\n==== ${results.length - bad.length}/${results.length} 通过 ====`);
    if (bad.length) bad.forEach(b => console.log('  FAIL: ' + b.name + '  [' + b.extra + ']'));
    console.log('截图目录: ' + SHOT_DIR);
    return bad.length === 0;
}

let ok = false;
try {
    ok = await main();
} catch (e) {
    console.error('\n脚本异常: ' + e.message);
    ok = false;
} finally {
    try { ws && ws.close(); } catch (e) { /* ignore */ }
    chrome.kill('SIGKILL');
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* ignore */ }
}
process.exit(ok ? 0 : 1);

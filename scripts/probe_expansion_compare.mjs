#!/usr/bin/env node
/**
 * /projects/{id}/expansion 扩写队列：**「预览」按钮 + 贴着各自行下方的前后对照**（2026-10-19）。
 *
 * 用户需求演进：
 *   ① 队列列表缺少【对比】按钮，点开后要在下方展示扩写前后的对比；
 *   ② 面板要**紧贴在列表行下方**（不是跑到很多行后面），且允许同时展开多个、每章各一份。
 *
 * 本脚本逐条守护：
 *   P0 前提：已扩写行有「预览」；待扩写行只有「移除」；初始无面板
 *   P1 【核心】点「预览」→ 面板出现在该行**正下方**（同一 tbody 的下一行、间距 ~0），
 *      按钮变「收起」+ aria-expanded=true + 该行高亮
 *   P2 面板头：章号/标题 + 「原文 n 字 → 扩写后 m 字」+ 增幅徽标；两栏标题
 *   P3 【核心】句级差异：只在单边出现的句子标红/标绿，未改动句不染色
 *   P4 「差异高亮」「同步滚动」两个开关默认开、且只影响自己那份面板
 *   P5 【核心】多面板共存：再开第 2 章 → 两个面板各贴各自的行，互不顶掉
 *   P6 收起其中一个 → 另一个仍展开
 *   P7 【核心】左右两栏滚动同步 = **按同一句话对齐**（双向；关掉开关就各滚各的）
 *   P8 没有原文备份 / 接口失败 → 面板里给提示，不静默空白
 *   P9 390px 窄屏：面板仍贴行、宽度不超视口、整页无横向滚动、两栏上下堆叠
 *   P10 运行期无 JS 异常 / console.error
 *
 * 数据全部由 CDP Fetch 域在网络层拦截后喂进来（不依赖库里真有没有扩写过的章），
 * 页面 HTML/JS/CSS 仍走真实服务，所以验的是真页面而不是 mock 出来的 HTML。
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_expansion_compare.mjs   # 1889 临时实例
 *   node scripts/probe_expansion_compare.mjs                               # 默认 1888
 * 环境变量：EXP_BASE / TTS_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.EXP_BASE || process.env.TTS_BASE || 'http://localhost:1888';
const PAGE = BASE + '/projects/1/expansion';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9347;
const SHOT_DIR = '/tmp/exp-compare-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'exp-compare-probe-'));
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

/* ==================== 网络层拦截（喂数据） ====================
   页面引导数据走的是同步 XHR（__EXPANSION_DATA__），对比内容是点击后现拉，
   两者都在这里按 URL 应答；其余请求一律放行到真实服务。 */
const DATA = {
    projectId: 1,
    projectTitle: '对比探针项目',
    expansionGuidance: '加强环境描写与心理活动',
    chapters: [
        { chapterNumber: 1, title: '第一章 雨夜', wordCount: 1200, expansionStatus: 'EXPANDED', content: 'Y' },
        { chapterNumber: 2, title: '第二章 山道', wordCount: 900, expansionStatus: 'EXPANDED', content: 'Y' },
        { chapterNumber: 3, title: '第三章 剑意', wordCount: 800, expansionStatus: 'PENDING', content: 'Y' },
        { chapterNumber: 4, title: '第四章 未完', wordCount: 0, expansionStatus: null, content: null },
        { chapterNumber: 5, title: '第五章 长夜', wordCount: 3000, expansionStatus: 'EXPANDED', content: 'Y' }
    ],
    chaptersPerVolume: 10,
    volumes: [{ volumeNumber: 1, title: '第一卷', chapterNumbers: [1, 2, 3, 4, 5] }]
};

const BEFORE = '雨下了一整夜。\n山道泥泞，他一步一滑。\n他握紧了剑。';
const AFTER = '雨下了一整夜。雷声在山谷里滚了两圈才散。\n山道泥泞，他一步一滑，靴底裹满黄泥。\n他握紧了剑。';

// 长章正文：40 段，每 5 段改写一处 —— 两栏都超过 420px 面板高度（能滚），
// 且两侧高度不同（正好检验"按占比同步"而不是"按像素同步"）
const LONG_BEFORE = Array.from({ length: 40 },
    (_, i) => `第${i + 1}段：雨还在下，檐角的水滴连成线，打在青石板上碎成一片细响。`).join('\n');
const LONG_AFTER = LONG_BEFORE.split('\n')
    .map((t, i) => (i % 5 === 0 ? t + '远处的灯影被雨丝拉长，像有人在窗后缓缓走动。' : t)).join('\n');

let failCompare2 = false;   // 让 /compare/2 返回 500

function compareBody(n) {
    if (n === '5') {
        return {
            chapterNumber: 5, title: '第五章 长夜', expansionStatus: 'EXPANDED', hasBefore: true,
            before: LONG_BEFORE, after: LONG_AFTER,
            beforeWordCount: LONG_BEFORE.length, afterWordCount: LONG_AFTER.length
        };
    }
    if (n === '1') {
        return {
            chapterNumber: 1, title: '第一章 雨夜', expansionStatus: 'EXPANDED', hasBefore: true,
            before: BEFORE, after: AFTER,
            beforeWordCount: BEFORE.length, afterWordCount: AFTER.length
        };
    }
    // 第 2 章：备份已被清空（例如已确认/回滚）
    return {
        chapterNumber: 2, title: '第二章 山道', expansionStatus: 'EXPANDED', hasBefore: false,
        before: '', after: '第二章正文。', beforeWordCount: 0, afterWordCount: 6
    };
}

async function handlePaused(p) {
    const url = p.request.url;
    const p1 = url.indexOf('/projects/1/expansion/');
    const tail = p1 >= 0 ? url.substring(p1) : '';
    const fulfil = (code, obj) => send('Fetch.fulfillRequest', {
        requestId: p.requestId,
        responseCode: code,
        responseHeaders: [{ name: 'Content-Type', value: 'application/json; charset=utf-8' }],
        body: Buffer.from(JSON.stringify(obj)).toString('base64')
    });

    if (tail.startsWith('/projects/1/expansion/data')) return fulfil(200, DATA);
    if (tail.startsWith('/projects/1/expansion/status')) {
        return fulfil(200, { active: false, pendingCount: 1, expandedCount: 3, generatingCount: 0 });
    }
    if (tail.startsWith('/projects/1/expansion/compare/')) {
        const n = tail.split('?')[0].split('/').pop();
        if (n === '2' && failCompare2) return fulfil(500, { error: '对比内容读取失败' });
        return fulfil(200, compareBody(n));
    }
    if (tail.startsWith('/projects/1/expansion/')) return fulfil(200, { status: 'ok' });
    return send('Fetch.continueRequest', { requestId: p.requestId });
}

/* ==================== 快照 ==================== */
const SNAP = `(() => {
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden';
    const rect = (el) => { const r = el.getBoundingClientRect();
        return { l: Math.round(r.left), t: Math.round(r.top), r: Math.round(r.right), b: Math.round(r.bottom),
                 w: Math.round(r.width), h: Math.round(r.height) }; };
    const txt = (el) => el ? el.innerText.replace(/\\s+/g, ' ').trim() : '';

    // 每章一组 tbody：第 1 行主行、第 2 行（可选）对比面板行
    const groups = Array.from(document.querySelectorAll('table.exp-table tbody.exp-chapter-group')).map(tb => {
        const trs = Array.from(tb.children).filter(el => el.tagName === 'TR');
        const main = trs[0] || null;
        const panelTr = trs[1] || null;
        const panel = panelTr ? panelTr.querySelector('.sc-cmp') : null;
        const btn = main ? main.querySelector('.exp-compare-toggle') : null;
        const btns = main ? Array.from(main.querySelectorAll('td.exp-actions button')) : [];
        const open = !!(panelTr && vis(panelTr) && panel);
        const paneEls = open ? Array.from(panel.querySelectorAll('.sc-cmp-pane')).filter(vis) : [];
        const del = open ? Array.from(panel.querySelectorAll('.sc-cmp-del')).map(e => e.innerText) : [];
        const add = open ? Array.from(panel.querySelectorAll('.sc-cmp-add')).map(e => e.innerText) : [];
        const sw = open ? panel.querySelector('.form-switch input') : null;
        const syncSw = open ? panel.querySelector('.sc-cmp-sync input') : null;
        const alertEl = open ? Array.from(panel.querySelectorAll('.alert')).filter(vis)[0] : null;
        return {
            chapter: main && main.querySelector('td') ? main.querySelector('td').innerText.trim() : '',
            status: main && main.querySelector('.badge') ? main.querySelector('.badge').innerText.trim() : '',
            buttons: btns.map(b => b.innerText.replace(/\\s+/g, ' ').trim()),
            hasCompare: !!btn,
            compareLabel: btn ? btn.innerText.replace(/\\s+/g, ' ').trim() : null,
            compareAria: btn ? btn.getAttribute('aria-expanded') : null,
            active: main ? main.classList.contains('table-active') : null,
            rowBox: main ? rect(main) : null,
            rowCount: trs.length,
            panelIndex: panelTr ? trs.indexOf(panelTr) : -1,
            panelOpen: open,
            panelBox: open ? rect(panel) : null,
            // 行底到面板顶的间距：0~2px 才算「紧贴」，跑到表格末尾时会是一大段
            panelGap: (open && main) ? Math.round(rect(panel).t - rect(main).b) : null,
            panelInSameTable: !!(panel && main && panel.closest('table') === main.closest('table')),
            // innerText 对隐藏元素会退回 textContent，所以统计/徽标必须先判可见，
            // 否则「接口失败时不该有统计」这条会误判
            header: open ? txt(panel.querySelector('.exp-cmp-header')) : '',
            stats: open && vis(panel.querySelector('.exp-cmp-stats'))
                ? txt(panel.querySelector('.exp-cmp-stats')) : '',
            delta: open && vis(panel.querySelector('.exp-cmp-delta'))
                ? panel.querySelector('.exp-cmp-delta').innerText.trim() : '',
            paneTitle: open ? Array.from(panel.querySelectorAll('.sc-cmp-pane-head')).filter(vis)
                .map(e => txt(e.querySelector('span'))) : [],
            paneText: paneEls.map(e => e.innerText.replace(/\\s+/g, ' ').trim()),
            delText: del,
            addText: add,
            delCount: del.length,
            addCount: add.length,
            hasSwitch: !!sw,
            switchOn: sw ? sw.checked : null,
            hasSyncSwitch: !!syncSw,
            syncSwitchOn: syncSw ? syncSw.checked : null,
            // 锚点句（两侧同号 = 同一句），同步滚动靠它定位；data-anc=-1 的是单边独有的句子
            anchorCount: open ? paneEls.map(e => Array.from(e.querySelectorAll('[data-anc]'))
                .filter(s => Number(s.getAttribute('data-anc')) >= 0).length) : [],
            alertText: alertEl ? txt(alertEl) : '',
            paneBoxes: paneEls.map(rect)
        };
    });

    return {
        groups,
        panelCount: groups.filter(g => g.panelOpen).length,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;

/* Alpine 的 DOM effect 是异步的：设值/点击后先静置一拍，再取快照 */
const snap = async (delay = 300) => { await sleep(delay); return evalJs(SNAP); };

/* 真鼠标点击：先滚到视口中间再取坐标（展开行常在视口外，直接点会打空） */
async function clickAt(findExpr, label) {
    await evalJs(`(() => { const el = ${findExpr}; if (el) el.scrollIntoView({ block: 'center', inline: 'center' }); })()`);
    await sleep(260);
    const box = await evalJs(`(() => {
        const el = ${findExpr};
        if (!el) return null;
        const r = el.getBoundingClientRect();
        if (r.width < 1 || r.height < 1) return null;
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!box) throw new Error('点不到: ' + label);
    await sleep(120);
    const common = { x: Math.round(box.x), y: Math.round(box.y), button: 'left', clickCount: 1 };
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...common, buttons: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...common, buttons: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...common, buttons: 0 });
}

/* 第 idx 章（按队列顺序）的「对比 / 收起」按钮 —— 用稳定的 class 而不是文字，
   窄屏下按钮文字被收成图标，靠 innerText 会找不到 */
const clickCompareOfRow = (idx) =>
    clickAt(`document.querySelectorAll('table.exp-table tbody.exp-chapter-group')[${idx}]
        .querySelector('.exp-compare-toggle')`, `第 ${idx} 章的预览按钮`);

/* 面板内元素：同样按章节定位，避免点到别章的面板 */
async function clickInGroup(idx, sel, label) {
    await clickAt(`(() => { const tb = document.querySelectorAll('table.exp-table tbody.exp-chapter-group')[${idx}];
        return tb ? tb.querySelector(${JSON.stringify(sel)}) : null; })()`, label || sel);
}

/* 开关是 toggle：先读状态，只有不一致才点，避免「点一次读旧值」的假失败。
   sel 默认是「差异高亮」，传 '.sc-cmp-sync input' 就是「同步滚动」。 */
async function ensureSwitch(idx, expected, sel = '.form-switch input') {
    const read = () => evalJs(`(() => {
        const tb = document.querySelectorAll('table.exp-table tbody.exp-chapter-group')[${idx}];
        const box = tb ? tb.querySelector(${JSON.stringify(sel)}) : null;
        return box ? box.checked : null;
    })()`);
    for (let i = 0; i < 5; i++) {
        if (await read() === expected) return true;
        await clickInGroup(idx, sel, '第 ' + idx + ' 章的开关 ' + sel);
        await sleep(320);
    }
    return await read() === expected;
}

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
    const ok = await waitFor('页面 Alpine 就绪', async () =>
        (await evalJs(`!!window.Alpine && document.querySelectorAll('table.exp-table tbody.exp-chapter-group').length > 0`)) === true,
        15000, 200);
    if (!ok) throw new Error('页面没有渲染出队列分组');
    await sleep(400);
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
    await send('Fetch.enable', {
        patterns: [{ urlPattern: '*/projects/1/expansion/*', requestStage: 'Request' }]
    });

    /* ---------- P0 前提 ---------- */
    console.log('\n— P0 前提 —');
    await loadPage(1280, 900, false);
    let s = await snap();
    const expanded = s.groups.filter(g => g.status === '已扩写');
    const pendingRow = s.groups.find(g => g.status === '待扩写');
    check('队列渲染出 4 章（3 已扩写 / 1 待扩写，无状态的不显示）', s.groups.length === 4,
        s.groups.map(g => g.status).join('|'));
    check('每章一组 tbody（主行 + 面板行两个位置）',
        s.groups.every(g => g.rowCount === 2), s.groups.map(g => g.rowCount).join('|'));
    check('已扩写章节每行都有「预览」按钮',
        expanded.length === 3 && expanded.every(g => g.hasCompare && g.compareLabel === '预览'),
        expanded.map(g => g.compareLabel).join('|'));
    check('待扩写章节只有「移除」，没有「预览」', !!pendingRow && !pendingRow.hasCompare
        && pendingRow.buttons.join('|').indexOf('移除') >= 0,
        pendingRow ? pendingRow.buttons.join('|') : 'none');
    check('初始没有展开的对比面板', s.panelCount === 0 && s.groups.every(g => !g.panelOpen));

    /* ---------- P1 展开位置 ---------- */
    console.log('\n— P1 点「预览」→ 面板贴在行下方 —');
    await clickCompareOfRow(0);
    s = await snap(450);
    let g0 = s.groups[0];
    check('【核心】点「预览」后出现对比面板', g0.panelOpen === true);
    check('【核心】面板是该行的**下一个 tr**（中间没有任何章节行）',
        g0.panelIndex === 1, 'panelIndex=' + g0.panelIndex);
    check('【核心】面板顶紧贴行底（间距 ≤ 2px），不是跑到列表后面',
        g0.panelGap !== null && g0.panelGap >= 0 && g0.panelGap <= 2,
        'gap=' + g0.panelGap + ' rowBottom=' + (g0.rowBox && g0.rowBox.b));
    check('【核心】面板就在队列表格内部（同一张表，不是表格外的一块）',
        g0.panelInSameTable === true);
    check('按钮切成「收起」且 aria-expanded=true、该行高亮',
        g0.compareLabel === '收起' && g0.compareAria === 'true' && g0.active === true,
        `${g0.compareLabel}/${g0.compareAria}/active=${g0.active}`);
    check('面板标题写明章号与标题', /第\s*1\s*章/.test(g0.header) && g0.header.indexOf('第一章 雨夜') >= 0, g0.header);
    await shot('01-open');

    /* ---------- P2 字数统计 ---------- */
    console.log('\n— P2 字数与增幅 —');
    check('头部给出「原文 n 字 → 扩写后 m 字」',
        /原文\s*\d+\s*字/.test(g0.stats) && /扩写后\s*\d+\s*字/.test(g0.stats), g0.stats);
    check('给出字数增幅百分比徽标', /^\+\d+\.\d%$/.test(g0.delta), g0.delta);
    check('两栏标题是「扩写前（原文）」/「扩写后」',
        g0.paneTitle.length === 2 && g0.paneTitle[0].indexOf('扩写前') >= 0 && g0.paneTitle[1].indexOf('扩写后') >= 0,
        g0.paneTitle.join('|'));

    /* ---------- P3 句级差异 ---------- */
    console.log('\n— P3 句级差异 —');
    check('【核心】原文侧只有「山道泥泞…」被标为删除',
        g0.delCount === 1 && g0.delText[0].indexOf('一步一滑。') >= 0,
        `del=${g0.delCount}:${g0.delText.join('|')}`);
    check('【核心】扩写后侧新增「雷声…」、改写句「…靴底裹满黄泥」都标为新增',
        g0.addCount === 2 && g0.addText.some(t => t.indexOf('雷声') >= 0)
        && g0.addText.some(t => t.indexOf('靴底裹满黄泥') >= 0),
        `add=${g0.addCount}:${g0.addText.join('|')}`);
    check('未改动的句子两侧都完整保留且不染色',
        g0.paneText[0].indexOf('雨下了一整夜。') >= 0 && g0.paneText[1].indexOf('雨下了一整夜。') >= 0
        && g0.delText.every(t => t.indexOf('雨下了一整夜') < 0)
        && g0.addText.every(t => t.indexOf('雨下了一整夜') < 0));
    await shot('02-diff');

    /* ---------- P4 每份面板的开关互不影响 ---------- */
    console.log('\n— P4 差异高亮开关（各面板独立） —');
    check('面板上默认开着「差异高亮」', g0.hasSwitch === true && g0.switchOn === true);
    check('【核心】差异高亮旁边还有「同步滚动」开关，默认也是开的',
        g0.hasSyncSwitch === true && g0.syncSwitchOn === true,
        `has=${g0.hasSyncSwitch} on=${g0.syncSwitchOn}`);
    check('两栏都渲染出锚点句（同一句在两侧同号，同步靠它定位）',
        g0.anchorCount.length === 2 && g0.anchorCount[0] >= 2
        && g0.anchorCount[0] === g0.anchorCount[1],
        JSON.stringify(g0.anchorCount));
    await ensureSwitch(0, false);
    s = await snap(400);
    g0 = s.groups[0];
    check('关掉后不再染色（但仍显示完整正文）',
        g0.delCount === 0 && g0.addCount === 0 && g0.paneText[0].indexOf('一步一滑') >= 0
        && g0.paneText[1].indexOf('雷声') >= 0,
        `del=${g0.delCount} add=${g0.addCount} switch=${g0.switchOn}`);

    /* ---------- P5 多面板共存 ---------- */
    console.log('\n— P5 再开第 2 章：两个面板各贴各自的行 —');
    await clickCompareOfRow(1);
    s = await snap(500);
    g0 = s.groups[0];
    const g1 = s.groups[1];
    check('【核心】允许同时展开两个面板', s.panelCount === 2 && g0.panelOpen && g1.panelOpen,
        'panels=' + s.panelCount);
    check('【核心】第 1 章面板仍在第 1 章行下方（没被顶走）',
        g0.panelIndex === 1 && g0.panelGap !== null && g0.panelGap <= 2, 'gap=' + g0.panelGap);
    check('【核心】第 2 章面板紧随第 2 章行（panelIndex=1、间距 ≤ 2px）',
        g1.panelIndex === 1 && g1.panelGap !== null && g1.panelGap >= 0 && g1.panelGap <= 2,
        `gap=${g1.panelGap} rowBottom=${g1.rowBox && g1.rowBox.b} panelTop=${g1.panelBox && g1.panelBox.t}`);
    check('第 2 章的面板标题是第 2 章（各面板数据不串）',
        /第\s*2\s*章/.test(g1.header) && g1.header.indexOf('第二章 山道') >= 0, g1.header);
    check('第 2 章没有原文备份 → 给提示而不是空面板',
        g1.alertText.indexOf('没有可对比的原文备份') >= 0 && g1.paneTitle.length === 0, g1.alertText);
    check('第 1 章的开关状态没被第 2 章影响（仍关着）',
        g0.hasSwitch === true && g0.switchOn === false && g0.delCount === 0,
        `switch=${g0.switchOn} del=${g0.delCount}`);
    await shot('03-two-panels');

    /* ---------- P6 单独收起 ---------- */
    console.log('\n— P6 收起其中一个，另一个保持展开 —');
    await clickInGroup(0, '.exp-close-compare', '第 1 章的收起按钮');
    s = await snap(400);
    check('收起第 1 章后只剩第 2 章面板',
        s.panelCount === 1 && s.groups[0].panelOpen === false && s.groups[1].panelOpen === true,
        'panels=' + s.panelCount);
    check('第 1 章按钮回到「预览」、aria-expanded=false、行不再高亮',
        s.groups[0].compareLabel === '预览' && s.groups[0].compareAria === 'false'
        && s.groups[0].active === false,
        `${s.groups[0].compareLabel}/${s.groups[0].compareAria}`);

    /* ---------- P7 左右两栏滚动同步（按同一句话对齐） ---------- */
    console.log('\n— P7 左右两栏滚动同步（内容对齐） —');
    const paneState = (idx) => evalJs(`(() => {
        const tb = document.querySelectorAll('table.exp-table tbody.exp-chapter-group')[${idx}];
        if (!tb) return null;
        return Array.from(tb.querySelectorAll('.sc-cmp-pane')).map(p => {
            const max = p.scrollHeight - p.clientHeight;
            return { st: Math.round(p.scrollTop), max: max,
                     ratio: max > 0 ? +(p.scrollTop / max).toFixed(3) : 0 };
        });
    })()`);
    const scrollPane = (idx, which, ratio) => evalJs(`(() => {
        const tb = document.querySelectorAll('table.exp-table tbody.exp-chapter-group')[${idx}];
        const p = tb ? tb.querySelectorAll('.sc-cmp-pane')[${which}] : null;
        if (!p) return false;
        p.scrollTop = Math.round((p.scrollHeight - p.clientHeight) * ${ratio});
        return true;
    })()`);
    /* 某栏「压在顶边上的那句话」是谁、离栏顶多少像素 —— 同步对不对就看这个。
       只认 [data-anc] 的锚点句：它们两侧同号，才能判断是不是同一句。 */
    const topSentence = (idx, which) => evalJs(`(() => {
        const tb = document.querySelectorAll('table.exp-table tbody.exp-chapter-group')[${idx}];
        const p = tb ? tb.querySelectorAll('.sc-cmp-pane')[${which}] : null;
        if (!p) return null;
        const pr = p.getBoundingClientRect();
        const hit = Array.from(p.querySelectorAll('[data-anc]'))
            .filter(s => Number(s.getAttribute('data-anc')) >= 0)
            .find(s => s.getBoundingClientRect().bottom > pr.top + 1);
        if (!hit) return null;
        const max = p.scrollHeight - p.clientHeight;
        return {
            k: hit.getAttribute('data-anc'),
            text: hit.innerText.replace(/\\s+/g, ' ').trim().slice(0, 14),
            off: Math.round(hit.getBoundingClientRect().top - pr.top),
            ratio: max > 0 ? +(p.scrollTop / max).toFixed(3) : 0
        };
    })()`);

    await clickCompareOfRow(3);      // 第五章 长夜（40 段，两栏都超出行高，能滚）
    await snap(600);
    let ps = await paneState(3);
    check('长章两栏都出现纵向滚动（同步的前提）',
        Array.isArray(ps) && ps.length === 2 && ps.every(x => x.max > 60), JSON.stringify(ps));
    const anc = await evalJs(`(() => {
        const tb = document.querySelectorAll('table.exp-table tbody.exp-chapter-group')[3];
        return Array.from(tb.querySelectorAll('.sc-cmp-pane')).map(p =>
            Array.from(p.querySelectorAll('[data-anc]'))
                .filter(s => Number(s.getAttribute('data-anc')) >= 0).length);
    })()`);
    check('长章两栏的锚点句数量一致（同一批句号，两侧同号）',
        anc[0] >= 40 && anc[0] === anc[1], JSON.stringify(anc));

    await scrollPane(3, 0, 0.5);     // 左栏滚到一半
    await sleep(450);
    const lt = await topSentence(3, 0);
    const rt = await topSentence(3, 1);
    check('【核心】滚左栏到一半 → 右栏把**同一句话**摆到同样的位置',
        !!lt && !!rt && lt.k === rt.k && Math.abs(lt.off - rt.off) <= 4,
        `left=k${lt && lt.k}「${lt && lt.text}」@${lt && lt.off}  right=k${rt && rt.k}「${rt && rt.text}」@${rt && rt.off}`);
    check('【核心】两侧滚动占比**并不相等**（证明是按内容对齐，不是按百分比硬套）',
        !!lt && !!rt && Math.abs(lt.ratio - rt.ratio) > 0.02,
        `left=${lt && lt.ratio} right=${rt && rt.ratio}`);

    await scrollPane(3, 1, 0.62);    // 反向：滚右栏
    await sleep(450);
    const lt2 = await topSentence(3, 0);
    const rt2 = await topSentence(3, 1);
    check('【核心】反向同样按内容对齐（滚右栏 → 左栏跟到同一句、同一位置）',
        !!lt2 && !!rt2 && lt2.k === rt2.k && Math.abs(lt2.off - rt2.off) <= 4,
        `left=k${lt2 && lt2.k}「${lt2 && lt2.text}」@${lt2 && lt2.off}  right=k${rt2 && rt2.k}「${rt2 && rt2.text}」@${rt2 && rt2.off}`);

    await scrollPane(3, 1, 1);       // 右栏滚到底
    await sleep(450);
    ps = await paneState(3);
    check('滚右栏到底 → 左栏也到底', ps[1].ratio >= 0.98 && ps[0].ratio >= 0.98,
        `left=${ps[0].ratio} right=${ps[1].ratio}`);

    await scrollPane(3, 0, 0);       // 左栏回顶
    await sleep(450);
    ps = await paneState(3);
    check('滚回顶部时两栏一起回顶', ps[0].ratio <= 0.01 && ps[1].ratio <= 0.01,
        `left=${ps[0].ratio} right=${ps[1].ratio}`);

    await sleep(400);
    const ps2 = await paneState(3);
    check('同步后位置稳定（连读两拍不抖动，没有互相触发的死循环）',
        ps[0].st === ps2[0].st && ps[1].st === ps2[1].st,
        `${ps[0].st}/${ps[1].st} → ${ps2[0].st}/${ps2[1].st}`);

    /* 关掉「差异高亮」也不该丢掉锚点：句子仍然逐句渲染，同步照旧按内容对齐 */
    await ensureSwitch(3, false);
    await scrollPane(3, 0, 0.45);
    await sleep(450);
    const lt4 = await topSentence(3, 0);
    const rt4 = await topSentence(3, 1);
    const colored = await evalJs(`(() => {
        const tb = document.querySelectorAll('table.exp-table tbody.exp-chapter-group')[3];
        return tb.querySelectorAll('.sc-cmp-del, .sc-cmp-add').length;
    })()`);
    check('关掉【差异高亮】后不再染色', colored === 0, 'colored=' + colored);
    check('【核心】关掉高亮后同步仍按内容对齐（句子 span 没退化成整段文本）',
        !!lt4 && !!rt4 && lt4.k === rt4.k && Math.abs(lt4.off - rt4.off) <= 4,
        `left=k${lt4 && lt4.k}@${lt4 && lt4.off} right=k${rt4 && rt4.k}@${rt4 && rt4.off}`);
    await ensureSwitch(3, true);

    /* 【同步滚动】开关：关掉就彻底各滚各的，重新打开立刻接上 */
    await scrollPane(3, 0, 0);       // 先两栏一起回顶，下面才好判断「另一栏没动」
    await sleep(400);
    await ensureSwitch(3, false, '.sc-cmp-sync input');
    await scrollPane(3, 0, 0.3);
    await sleep(450);
    ps = await paneState(3);
    check('【核心】关掉【同步滚动】后各滚各的（右栏留在原地）',
        Math.abs(ps[0].ratio - 0.3) <= 0.03 && ps[1].ratio <= 0.02,
        `left=${ps[0].ratio} right=${ps[1].ratio}`);
    await ensureSwitch(3, true, '.sc-cmp-sync input');
    await scrollPane(3, 0, 0.55);
    await sleep(450);
    const lt3 = await topSentence(3, 0);
    const rt3 = await topSentence(3, 1);
    check('重新打开【同步滚动】后立刻恢复内容对齐',
        !!lt3 && !!rt3 && lt3.k === rt3.k && Math.abs(lt3.off - rt3.off) <= 4,
        `left=k${lt3 && lt3.k}@${lt3 && lt3.off} right=k${rt3 && rt3.k}@${rt3 && rt3.off}`);
    await shot('05-sync');

    await clickInGroup(3, '.exp-close-compare', '第五章的收起按钮');
    await snap(300);

    /* ---------- P8 接口失败 ---------- */
    console.log('\n— P8 接口失败 —');
    await clickInGroup(1, '.exp-close-compare', '第 2 章的收起按钮');
    await snap(300);
    failCompare2 = true;
    await clickCompareOfRow(1);
    s = await snap(500);
    const g1f = s.groups[1];
    check('接口失败时面板给出「加载失败」提示', g1f.alertText.indexOf('加载失败') >= 0, g1f.alertText);
    check('失败时不残留正文与统计', g1f.paneTitle.length === 0 && g1f.stats === '', g1f.stats);
    check('失败时面板仍贴在行下方', g1f.panelIndex === 1 && g1f.panelGap <= 2, 'gap=' + g1f.panelGap);
    failCompare2 = false;
    await clickInGroup(1, '.exp-close-compare', '第 2 章的收起按钮');
    await snap(300);

    /* ---------- P9 窄屏 ---------- */
    console.log('\n— P9 390px 窄屏 —');
    await loadPage(390, 780, false);
    await clickCompareOfRow(0);
    s = await snap(500);
    const m0 = s.groups[0];
    check('窄屏也能展开对比', m0.panelOpen === true);
    check('窄屏面板仍紧贴行下方', m0.panelIndex === 1 && m0.panelGap !== null && m0.panelGap <= 2,
        `gap=${m0.panelGap}`);
    check('整页无横向滚动', s.docScrollW <= s.docClientW + 1,
        `scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    check('面板宽度不超视口',
        m0.panelBox && m0.panelBox.w <= s.docClientW + 1, m0.panelBox ? 'w=' + m0.panelBox.w : 'none');
    check('两栏上下堆叠（不是左右挤压）',
        m0.paneBoxes.length === 2 && m0.paneBoxes[0].b <= m0.paneBoxes[1].t + 1,
        m0.paneBoxes.map(b => `${b.t}-${b.b}`).join(' '));
    await clickCompareOfRow(1);
    s = await snap(500);
    check('窄屏也能同时展开两个面板、且不产生横向滚动',
        s.panelCount === 2 && s.docScrollW <= s.docClientW + 1,
        `panels=${s.panelCount} scrollW=${s.docScrollW} clientW=${s.docClientW}`);

    // 窄屏两栏是**上下堆叠**：这时不该同步，否则会偷偷改掉用户看不见的那一栏的位置
    await clickCompareOfRow(3);      // 长章第 5 章
    s = await snap(600);
    await scrollPane(3, 0, 0.5);
    await sleep(450);
    const mPanes = await paneState(3);
    check('窄屏上下堆叠时各滚各的（不做同步）',
        Array.isArray(mPanes) && mPanes.length === 2
        && mPanes[0].ratio >= 0.45 && mPanes[1].st === 0,
        `top=${mPanes && mPanes[0].ratio} bottom=${mPanes && mPanes[1].st}`);
    check('窄屏三章面板同时展开仍无横向溢出',
        s.panelCount === 3 && s.docScrollW <= s.docClientW + 1,
        `panels=${s.panelCount} scrollW=${s.docScrollW} clientW=${s.docClientW}`);
    await shot('04-mobile');

    /* ---------- P10 无异常 ---------- */
    console.log('\n— P10 运行期异常 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '));

    const failed = results.filter(r => !r.ok).length;
    console.log(`\n===== ${results.length - failed}/${results.length} 通过 =====`);
    if (failed) console.log('失败项：\n' + results.filter(r => !r.ok).map(r => ' - ' + r.name).join('\n'));

    try { await send('Browser.close'); } catch (e) { /* ignore */ }
    try { chrome.kill(); } catch (e) { /* ignore */ }
    process.exit(failed ? 1 : 0);
}

main().catch(e => {
    console.error('PROBE FAILED: ' + e.message);
    try { chrome.kill(); } catch (err) { /* ignore */ }
    process.exit(2);
});

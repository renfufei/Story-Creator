#!/usr/bin/env node
/**
 * 情节拓展页 · 扩写队列「限高滚动」（2026-10-20）
 *
 * 背景：队列以前是全平铺的 —— 标记 50 章就把下面的「全部章节」推到几屏之外，
 * 想回队列点个「确认」得先滚过整条队列。现在队列限高约 12 行（窄屏 8 行）、表头吸顶。
 *
 * 实现：`div.table-responsive` 再加 `.exp-queue-scroll`（max-height + overflow-y:auto），
 * thead th 用 position:sticky 吸顶（背景必须不透明，否则下面的行会透上来）。
 *
 * 本脚本守护：
 *   T1 队列容器真的在滚（scrollHeight > clientHeight），且可见行数 ≈ 12
 *   T2 容器高度 ≤ 88vh 与「12 行 + 表头」两个上限
 *   T3 表头吸顶：滚 400px 后 thead 仍贴着容器顶边
 *   T4 长队列时给出「共 N 章 · 列表内可滚动」提示
 *   T5 队列 30 章也不会把「全部章节」推到很远（页面总高受控）
 *   T6 320px：无横向溢出、容器仍可滚、可见行数 ≈ 8
 *   T7 全程无 JS 运行时异常
 *
 * 用法：node scripts/probe_expansion_queue_scroll.mjs
 */
import { spawn } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9392;
const HTTP_PORT = 18912;
const SHOT_DIR = '/tmp/exp-queue-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC_ROOTS = [path.join(ROOT, 'src/main/resources/static'), path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 假数据：60 章 / 队列 30 章 ==================== */
const TOTAL = 60, PENDING = 25, EXPANDED = 5;
const chapters = Array.from({ length: TOTAL }, (_, i) => {
    const n = i + 1;
    const status = n <= PENDING ? 'PENDING' : (n <= PENDING + EXPANDED ? 'EXPANDED' : null);
    return {
        chapterNumber: n,
        title: '第' + n + '章 山谷里的第一场雨',
        wordCount: 2200 + n * 37,
        expansionStatus: status,
        hasContent: true
    };
});
const volumes = Array.from({ length: 6 }, (_, i) => ({
    volumeNumber: i + 1,
    title: '第' + (i + 1) + '卷',
    chapterNumbers: chapters.slice(i * 10, i * 10 + 10).map(c => c.chapterNumber)
}));

/* 长指导：只读态限高 20 行要有东西撑满才验得出来（原来 16 个字，永远不溢出 ⇒ 断言假绿）。
   26 条 ≈ 26 行小字 > 20 行，必然溢出 ⇒ 既验限高滚动，也验编辑框【展开】能撑到内容高度。 */
const LONG_GUIDANCE = [
    '1. 以原文为骨架：原文有几段，扩写后就几段，顺序不变，不合并也不调换。',
    '2. 每个场景补三样东西：环境细节、人物的身体感受、一两句自然的口语对话。',
    '3. 对话要短、要有停顿和打断，不要把信息一次性说完。',
    '4. 心理描写从动作里带出来，少用「他想」「他感到」这类直白句式。',
    '5. 保留原文里所有的伏笔与线索，可以加浓，但不能改动指向。',
    '6. 比喻从人物熟悉的事物里取，避免现代词汇与书面腔。',
    '7. 时间线严格照原文，不新增事件、不提前揭晓后面才该知道的事。',
    '8. 每段结尾留一点未完成感，让读者想接着往下看。',
    '9. 环境描写要参与情绪：下雨不只是下雨，它得让这场戏更难受。',
    '10. 不要用总结句收尾，不要写「他明白了」这种替读者下结论的句子。',
    '11. 称谓、口音、称呼习惯全篇统一，别中途换叫法。',
    '12. 数字与量词按原文，不要为了「更具体」自己编新的。',
    '13. 段落长度控制在三到六句，太长会让人跳读。',
    '14. 需要强调的地方用短句、断句，不要用感叹号堆情绪。',
    '15. 通读一遍：删掉所有「其实」「仿佛」「似乎」的滥用。',
    '16. 扩写的目标是「同一场景看得更清楚」，不是把情节往前推，节奏不能被拉快。',
    '17. 有具体物件时优先用物件承担情绪，比如一只裂了口的碗，比一句「他很穷」有力得多。',
    '18. 光线与声音按时间推移变化：清晨的院子、正午的院子、傍晚的院子，读起来不能一样。',
    '19. 人物的动作要有生活质感：洗手、擦桌、把凳子往后挪半尺，这些都比形容词可靠。',
    '20. 多人场景里，每个人给一个小动作或一句短话，别让人物只当背景板。',
    '21. 方言与土话只在对话里出现，叙述部分一律用通顺的普通话。',
    '22. 扩写后与前后章对不上时，以后一章为准，回去改当前章的细节，不要改动后面。',
    '23. 情绪高点前后各留一段相对平静的描写，让高点有落差。',
    '24. 不要解释人物的动机，把动机藏在他做了什么、没做什么里面。',
    '25. 每章至少留一处「读者知道、人物不知道」的信息差。',
    '26. 收尾加一句具体可感的画面，别用抽象抒情收束整章。'
].join('\n');

const BOOT = {
    projectId: '1',
    projectTitle: '探针项目',
    expansionGuidance: LONG_GUIDANCE,
    chaptersPerVolume: 10,
    volumes,
    chapters
};

/* 短队列（8 章 / 队列 3 章）：用来证明「内容不满一屏时容器不会留一大块空白」 */
const BOOT_SHORT = {
    projectId: '2',
    projectTitle: '短队列项目',
    expansionGuidance: '',
    chaptersPerVolume: 10,
    volumes: [{ volumeNumber: 1, title: '第1卷', chapterNumbers: [1, 2, 3, 4, 5, 6, 7, 8] }],
    chapters: chapters.slice(0, 8).map((c, i) => ({ ...c, expansionStatus: i < 3 ? 'PENDING' : null }))
};

const MIME = {
    '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml', '.png': 'image/png', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf'
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

const server = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (pn === '/projects/1/expansion' || pn === '/projects/2/expansion') {
        return serveStatic(res, 'pages/expansion.html');
    }
    if (pn === '/projects/1/expansion/data' || pn === '/projects/2/expansion/data') {
        const boot = pn.indexOf('/projects/2/') === 0 ? BOOT_SHORT : BOOT;
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify(boot));
    }
    if (pn.indexOf('/projects/') === 0 && /\/expansion\/status$/.test(pn)) {
        const short = pn.indexOf('/projects/2/') === 0;
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify({
            active: false,
            pendingCount: short ? 3 : PENDING,
            expandedCount: short ? 0 : EXPANDED
        }));
    }
    /* 扩写前后对照：给一段够长的正文，面板才会真的撑出高度（用来验「面板不被队列小窗裁掉」） */
    if (pn.indexOf('/projects/') === 0 && /\/expansion\/compare\/\d+$/.test(pn)) {
        const before = Array.from({ length: 44 }, (_, i) => `第${i + 1}句原文。山谷里的雨下了一整夜，屋檐的水声一直没有停。`).join('');
        const after = before + '扩写补上的环境与心理细节。'.repeat(10);
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify({
            before, after,
            beforeWordCount: before.length, afterWordCount: after.length,
            hasBefore: true
        }));
    }
    if (pn.indexOf('/api/') === 0) {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end('{}');
    }
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'exp-queue-probe-'));
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
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

let navSeq = 0;
async function goto(width = 1280, height = 900, pid = 1) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}/projects/${pid}/expansion?_probe=` + (++navSeq) });
}
/* 真实鼠标点击（Alpine 的 @click 需要真实事件流；element.click() 会绕过部分指针状态） */
async function clickAt(x, y) {
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none', clickCount: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
}
async function clickSel(selector) {
    const box = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        if (!el) return null;
        el.scrollIntoView({ block: 'center' });
        const r = el.getBoundingClientRect();
        if (!r.width || !r.height) return null;
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!box) throw new Error('点击目标不存在或不可见: ' + selector);
    await sleep(140);
    /* scrollIntoView 之后再取一次坐标：第一次取到的是滚动前的 */
    const pt = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        const r = el.getBoundingClientRect();
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    await clickAt(pt.x, pt.y);
}

const waitSettled = () => waitFor(async () => {
    try {
        return (await evalJs(`(() => { const r = document.querySelector('.sc-main');
            const d = r && window.Alpine ? window.Alpine.$data(r) : null; return !!d && d.booting === false; })()`)) === true;
    } catch (e) { return false; }
});

/* 量队列滚动区：高度、行高、可见行数、卷头位置 */
const QUEUE = `(() => {
    const box = document.querySelector('.exp-queue-scroll');
    if (!box) return { missing: true };
    const cs = getComputedStyle(box);
    const rows = Array.from(box.querySelectorAll('tbody > tr')).filter(tr => tr.getClientRects().length > 0 && !tr.classList.contains('exp-compare-row'));
    const rowH = rows.length ? rows[0].getBoundingClientRect().height : 0;
    const th = box.querySelector('thead th');
    const headH = th ? th.getBoundingClientRect().height : 0;
    const hint = document.querySelector('.exp-queue-hint');
    const card = box.closest('.card');
    /* 可见行数改成「整行都落在容器内」的章节行数 —— 队列现在按卷分组，
       卷头（~34px）也占容器高度，拿 (clientH - thead) / rowH 估会偏高。 */
    const boxRect = box.getBoundingClientRect();
    const top = boxRect.top, bot = top + box.clientHeight;
    const fully = rows.filter(tr => {
        const r = tr.getBoundingClientRect();
        return r.height > 0 && r.top >= top - 1 && r.bottom <= bot + 1;
    });
    const volHeads = Array.from(box.querySelectorAll('.exp-queue-vol-head'));
    return {
        missing: false,
        overflowY: cs.overflowY,
        maxHeight: cs.maxHeight,
        flow: cs.maxHeight === 'none',
        clientH: Math.round(box.clientHeight),
        scrollH: Math.round(box.scrollHeight),
        /* 窄屏要看这里：横向溢出的话，容器会同时横竖两套滚动，很难用 */
        clientW: Math.round(box.clientWidth),
        scrollW: Math.round(box.scrollWidth),
        rowH: Math.round(rowH * 10) / 10,
        headH: Math.round(headH * 10) / 10,
        /* 完整可见的**数据行**数（不含卷头与表头） */
        visibleRows: fully.length,
        volCount: volHeads.length,
        dataRows: rows.length,
        vh: Math.round(window.innerHeight),
        /* CSS 里声明的三个上限变量（别拿实测行高反推：行高受按钮高度影响，会飘） */
        rowsVar: parseFloat(cs.getPropertyValue('--exp-queue-rows')) || 0,
        rowVar: parseFloat(cs.getPropertyValue('--exp-queue-row')) || 0,
        headVar: parseFloat(cs.getPropertyValue('--exp-queue-head')) || 0,
        maxVhVar: parseFloat(cs.getPropertyValue('--exp-queue-maxvh')) || 1,
        thTop: th ? Math.round(th.getBoundingClientRect().top) : null,
        boxTop: Math.round(boxRect.top),
        cardH: card ? Math.round(card.getBoundingClientRect().height) : -1,
        pageH: document.documentElement.scrollHeight,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth,
        hintText: hint ? (hint.innerText || '').replace(/\\s+/g, ' ').trim() : null,
        hintVisible: !!hint && hint.getClientRects().length > 0
    };
})()`;

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

    /* ---------- T1 / T2 容器真的在滚、可见行数 ≈ 12 ---------- */
    console.log('\n— T1 队列限高滚动 —');
    await goto(1280, 1000);
    check('页面 boot 完成', (await waitSettled()) === true);
    await sleep(400);
    let q = await evalJs(QUEUE);
    check('队列滚动容器存在', q.missing === false);
    check('容器 overflow-y 是 auto（不是 visible）', q.overflowY === 'auto', q.overflowY);
    check('【核心】队列内容比容器高：真的可以滚（scrollH > clientH）',
        q.scrollH > q.clientH, `clientH=${q.clientH} scrollH=${q.scrollH}`);
    check('队列共 30 章（25 待扩写 + 5 已扩写）', q.dataRows === 30, 'n=' + q.dataRows);
    check('队列按卷分组：3 卷（队列只碰得到前 3 卷）', q.volCount === 3, 'vols=' + q.volCount);
    check('【核心】可见数据行 ≈ 11（10~12；卷头与表头占掉约 70px）',
        q.visibleRows >= 10 && q.visibleRows <= 12,
        `visible=${q.visibleRows} rowH=${q.rowH} headH=${q.headH} clientH=${q.clientH}`);
    check('容器高度 = min(12 行 + 表头, 88vh)',
        q.clientH <= Math.min(q.rowsVar * q.rowVar + q.headVar, q.vh * q.maxVhVar / 100) + 2,
        `clientH=${q.clientH} cap${q.rowsVar}=${q.rowsVar * q.rowVar + q.headVar} ${q.maxVhVar}vh=${Math.round(q.vh * q.maxVhVar / 100)}`);
    check('【核心】整张队列卡片高度受控（滚动区之外只有卡片头/按钮/提示）',
        q.cardH > 0 && q.cardH < q.clientH + 220,
        `cardH=${q.cardH} clientH=${q.clientH}`);
    await shot('queue-01-desktop');

    /* ---------- T3 卷头吸顶 ---------- */
    console.log('\n— T2 卷头吸顶 —');
    await evalJs(`(() => { const b = document.querySelector('.exp-queue-scroll'); b.scrollTop = 400; return b.scrollTop; })()`);
    await sleep(320);
    q = await evalJs(QUEUE);
    check('滚动 400px 后第一行已移出视野（内容真的滚了）',
        (await evalJs(`(() => { const b = document.querySelector('.exp-queue-scroll');
            const tr = b.querySelectorAll('tbody > tr')[0];
            return Math.round(tr.getBoundingClientRect().top - b.getBoundingClientRect().top); })()`)) < 0);
    /* 表头不再吸顶（每卷一张表、thead 各一份，外面还套着 table-responsive 的横滚容器，
       sticky 在那种结构里贴不到滚动框），改为**卷头吸顶**：滚到哪一卷，那一卷的卷名就在框顶。 */
    check('【核心】卷头吸顶：当前卷的卷头仍贴着容器顶边（-1 ~ +2px）',
        (await evalJs(`(() => {
            const b = document.querySelector('.exp-queue-scroll');
            const top = b.getBoundingClientRect().top;
            const heads = [...b.querySelectorAll('.exp-queue-vol-head')];
            return heads.some(h => Math.abs(h.getBoundingClientRect().top - top) <= 2);
        })()`)) === true,
        `box=${q.boxTop}`);
    await shot('queue-02-scrolled');

    /* ---------- T4 提示文案 ---------- */
    console.log('\n— T3 长队列提示 —');
    check('【核心】提示「共 30 章 · 3 卷 · 列表内可滚动」可见',
        q.hintVisible === true && /共 30 章/.test(q.hintText || '')
        && /3 卷/.test(q.hintText || '') && /列表内可滚动/.test(q.hintText || ''),
        q.hintText || '(none)');

    /* ---------- T5 页面总高受控 ---------- */
    console.log('\n— T4 页面总高 —');
    check('下方「全部章节」卡片仍在队列之后（结构未动）',
        (await evalJs(`(() => { const a = document.querySelector('.exp-queue-scroll');
            const b = document.querySelectorAll('.volume-group')[0];
            return !!b && a.getBoundingClientRect().top < b.getBoundingClientRect().top; })()`)) === true);
    console.log('    （页面总高 ' + q.pageH + 'px，队列卡片 ' + q.cardH + 'px —— 队列不再随章数线性撑高）');

    /* ---------- T6 320px ---------- */
    console.log('\n— T5 320px 窄屏 —');
    await goto(320, 568);
    await waitSettled();
    await sleep(400);
    q = await evalJs(QUEUE);
    check('窄屏无横向溢出', q.docScrollW <= q.docClientW + 1, `scrollW=${q.docScrollW} clientW=${q.docClientW}`);
    /* 窄屏这张表本来就横着放不下（「预览/回滚/确认」三键 108px），
       所以这里只**记录**容器内的横向量，不做硬断言 —— 免得为了一个数字把标题列压扁。 */
    console.log(`    （窄屏队列容器内横向可滚 ${q.scrollW - q.clientW}px：既有行为，整表横滑可看全）`);
    check('窄屏队列仍可滚（scrollH > clientH）', q.scrollH > q.clientH, `clientH=${q.clientH} scrollH=${q.scrollH}`);
    check('窄屏可见行数 ≈ 7（6~8；卷头也占高度）',
        q.visibleRows >= 6 && q.visibleRows <= 8,
        `visible=${q.visibleRows} rowH=${q.rowH} headH=${q.headH} clientH=${q.clientH} vh=${q.vh}`);
    check('窄屏容器高度 ≤ min(8 行, 80vh)',
        q.clientH <= Math.min(q.rowsVar * q.rowVar + q.headVar, q.vh * q.maxVhVar / 100) + 2,
        `clientH=${q.clientH} cap${q.rowsVar}=${q.rowsVar * q.rowVar + q.headVar} ${q.maxVhVar}vh=${Math.round(q.vh * q.maxVhVar / 100)}`);
    await evalJs(`(() => { const b = document.querySelector('.exp-queue-scroll'); if (b) b.scrollIntoView({ block: 'start' }); return true; })()`);
    await sleep(220);
    await shot('queue-03-mobile');

    /* 窄屏的指导框也得收：20 行 420px 在 568px 高的视口里几乎占满一屏。
       这里用 JS 直接 click（不派发鼠标事件）—— 320px 下页头按钮会折行、可能被吸顶导航压住，
       本项验的是高度，不是命中区域，别让坐标抖动把断言带红。 */
    await evalJs(`(() => {
        const b = document.querySelector('button[title="编辑本项目的扩写指导"]');
        if (b) b.click();
        return !!b;
    })()`);
    await sleep(320);
    const gdM = await evalJs(`(() => {
        const t = document.querySelector('textarea[placeholder^="输入扩写方向"]');
        if (!t) return { missing: true };
        return { missing: false, editH: Math.round(t.getBoundingClientRect().height),
                 scrollW: document.documentElement.scrollWidth,
                 clientW: document.documentElement.clientWidth };
    })()`);
    check('窄屏编辑框收到 12 行（≈264px），不占满整屏',
        gdM.missing === false && gdM.editH >= 255 && gdM.editH <= 275, JSON.stringify(gdM));
    check('窄屏指导编辑态无横向溢出',
        gdM.missing === false && gdM.scrollW <= gdM.clientW + 1,
        `scrollW=${gdM.scrollW} clientW=${gdM.clientW}`);
    await shot('guide-04-mobile-edit');
    await evalJs(`(() => {
        const b = [...document.querySelectorAll('button')].find(x => x.textContent.trim() === '取消');
        if (b) b.click();
        return !!b;
    })()`);
    await sleep(200);

    /* ---------- T6 短队列：不留空白 ---------- */
    console.log('\n— T6 短队列（3 章）不产生空白 —');
    await goto(1280, 1000, 2);
    await waitSettled();
    await sleep(300);
    q = await evalJs(QUEUE);
    check('短队列只有 3 行', q.dataRows === 3, 'n=' + q.dataRows);
    check('短队列不滚动（内容高 ≈ 容器高，没有预留 12 行的空白）',
        q.scrollH <= q.clientH + 2, `clientH=${q.clientH} scrollH=${q.scrollH}`);
    /* 队列头信息现在常显「共 N 章 · M 卷」；只有**真的还有内容要滚**时才会多一句「列表内可滚动」 */
    check('短队列不说「列表内可滚动」，但仍标明章数/卷数',
        q.hintVisible === true && !/列表内可滚动/.test(q.hintText || '')
        && /共 3 章/.test(q.hintText || '') && /1 卷/.test(q.hintText || ''),
        'hint=' + q.hintText);

    /* ---------- T7 扩写指导：只读限高 20 行 / 编辑框 20 行 / 【展开】按内容撑高 ---------- */
    console.log('\n— T7 扩写指导（20 行限高 + 展开） —');
    await goto(1280, 1000, 1);
    await waitSettled();
    await sleep(360);
    const gd = await evalJs(`(() => {
        const el = document.querySelector('.exp-guidance-view');
        if (!el) return { missing: true };
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        return { missing: false, maxH: cs.maxHeight, overflowY: cs.overflowY,
                 line: cs.lineHeight, fs: cs.fontSize,
                 clientH: Math.round(el.clientHeight), scrollH: Math.round(el.scrollHeight),
                 h: Math.round(r.height), ws: cs.whiteSpace };
    })()`);
    check('扩写指导正文有自己的滚动容器', gd.missing === false);
    /* 20 行 × 1.3125rem(21px) = 420px */
    check('【核心】指导区限高 20 行（420px）、超长内容在框内滚',
        gd.maxH === '420px' && gd.overflowY === 'auto' && gd.scrollH > gd.clientH,
        JSON.stringify(gd));
    check('指导区实际高度没有超过 420px（不会把队列推走）',
        gd.h > 380 && gd.h <= 421, 'h=' + gd.h);
    check('指导正文字号行高与行数算式一致（.875rem / 21px）',
        gd.fs === '14px' && gd.line === '21px', `fs=${gd.fs} line=${gd.line}`);
    check('指导正文仍保留换行（white-space: pre-line）', gd.ws === 'pre-line', gd.ws);
    await shot('guide-01-view');

    await clickSel('button[title="编辑本项目的扩写指导"]');
    await sleep(360);
    const ta = await evalJs(`(() => {
        const t = document.querySelector('textarea[placeholder^="输入扩写方向"]');
        if (!t) return { missing: true };
        const r = t.getBoundingClientRect();
        return { missing: false, rows: t.getAttribute('rows'), h: Math.round(r.height),
                 clientH: t.clientHeight, scrollH: t.scrollHeight,
                 len: (t.value || '').length, focused: document.activeElement === t,
                 expanded: !!document.querySelector('button[title*="按内容自动撑高"]') };
    })()`);
    check('点【配置】进入编辑态，文本框出现', ta.missing === false);
    check('【核心】编辑框默认 20 行、高度 ≈ 432px（原 6 行 136px 太矮）',
        ta.rows === '20' && ta.h >= 425 && ta.h <= 445, JSON.stringify(ta));
    check('编辑框里是完整指导（不受只读态限高影响）', ta.len > 500, 'len=' + ta.len);

    /* 【展开】：把 26 行内容一次撑开 —— 内部不再有滚动条，高度 > 默认 20 行 */
    await clickSel('button[title*="按内容自动撑高"]');
    await sleep(320);
    const exp1 = await evalJs(`(() => {
        const t = document.querySelector('textarea[placeholder^="输入扩写方向"]');
        const r = t.getBoundingClientRect();
        const btn = [...document.querySelectorAll('button')].find(b => /收起|展开/.test(b.textContent));
        return { h: Math.round(r.height), clientH: t.clientHeight, scrollH: t.scrollHeight,
                 inlineH: t.style.height, label: btn ? btn.textContent.trim() : '' };
    })()`);
    check('【核心】点【展开】后按内容撑高（高度 > 默认 432px，且内部不滚动）',
        exp1.h > 500 && exp1.scrollH <= exp1.clientH + 2, JSON.stringify(exp1));
    check('按钮文案切到【收起】', exp1.label.indexOf('收起') >= 0, exp1.label);
    await shot('guide-03-expanded');

    /* 打字让内容变长，展开态要跟着长 */
    await evalJs(`(() => {
        const t = document.querySelector('textarea[placeholder^="输入扩写方向"]');
        t.focus();
        t.value = t.value + '\\n27. 追加一条很长的补充说明：' + '用来验证展开态会随内容继续增长。'.repeat(6);
        t.dispatchEvent(new Event('input', { bubbles: true }));
        return true;
    })()`);
    await sleep(280);
    const exp2 = await evalJs(`(() => {
        const t = document.querySelector('textarea[placeholder^="输入扩写方向"]');
        return { h: Math.round(t.getBoundingClientRect().height), clientH: t.clientHeight,
                 scrollH: t.scrollHeight };
    })()`);
    check('展开态下继续输入，框跟着长高（不会冒出内部滚动条）',
        exp2.h > exp1.h && exp2.scrollH <= exp2.clientH + 2,
        JSON.stringify(exp2));

    /* 【收起】：回到默认 20 行 */
    await clickSel('button[title*="收回到默认行数"]');
    await sleep(300);
    const back = await evalJs(`(() => {
        const t = document.querySelector('textarea[placeholder^="输入扩写方向"]');
        const btn = [...document.querySelectorAll('button')].find(b => /收起|展开/.test(b.textContent));
        return { h: Math.round(t.getBoundingClientRect().height), inlineH: t.style.height,
                 scrollH: t.scrollHeight, clientH: t.clientHeight,
                 label: btn ? btn.textContent.trim() : '' };
    })()`);
    check('【核心】点【收起】回到默认 20 行高度（行内 height 已清空）',
        back.h >= 425 && back.h <= 445 && back.inlineH === '', JSON.stringify(back));
    check('收起后按钮文案回到【展开】', back.label.indexOf('展开') >= 0, back.label);
    check('收起后内容溢出、重新出现内部滚动', back.scrollH > back.clientH,
        `clientH=${back.clientH} scrollH=${back.scrollH}`);
    await shot('guide-02-edit');

    /* ---------- T8 队列分卷折叠 + 面板不受队列小窗限制 ---------- */
    console.log('\n— T8 队列分卷 / 面板放开限高 —');
    await goto(1280, 1000, 1);
    await waitSettled();
    await sleep(400);

    const volState = await evalJs(`(() => {
        const heads = [...document.querySelectorAll('.exp-queue-vol-head')];
        return {
            n: heads.length,
            labels: heads.map(h => (h.innerText || '').replace(/\\s+/g, ' ').trim()),
            carets: document.querySelectorAll('.exp-queue-vol .exp-vol-caret').length,
            rowsVisible: [...document.querySelectorAll('.exp-queue-scroll tbody > tr')]
                .filter(tr => tr.getClientRects().length > 0 && !tr.classList.contains('exp-compare-row')).length
        };
    })()`);
    check('【核心】队列按卷分组：3 个卷头，每卷带章数与状态计数',
        volState.n === 3 && volState.labels.every(t => /第\d+卷/.test(t) && /章/.test(t)),
        JSON.stringify(volState.labels));
    check('卷头有折叠箭头，初始全展开（30 行都在）',
        volState.carets === 3 && volState.rowsVisible === 30, `carets=${volState.carets} rows=${volState.rowsVisible}`);
    await shot('vol-01-grouped');

    /* 折第一卷：该卷 10 行消失，另两卷不受影响 */
    await evalJs(`(() => { document.querySelectorAll('.exp-queue-vol .exp-vol-caret')[0].click(); return true; })()`);
    await sleep(320);
    const folding = await evalJs(`(() => {
        const vols = [...document.querySelectorAll('.exp-queue-vol')];
        const rowsOf = v => [...v.querySelectorAll('tbody > tr')]
            .filter(tr => tr.getClientRects().length > 0 && !tr.classList.contains('exp-compare-row')).length;
        return { perVol: vols.map(rowsOf), total: document.querySelector('.exp-queue-scroll').scrollHeight };
    })()`);
    check('【核心】折叠第 1 卷：该卷章节行全部收起（10 → 0），其它卷不变',
        folding.perVol[0] === 0 && folding.perVol[1] === 10 && folding.perVol[2] === 10,
        JSON.stringify(folding.perVol));

    /* 全部展开 / 全部折叠（此时第 1 卷刚被折起来，页头按钮是【全部展开】） */
    await clickSel('button[title*="展开所有分卷"]');
    await sleep(320);
    const allExpand1 = await evalJs(`(() => [...document.querySelectorAll('.exp-queue-scroll tbody > tr')]
        .filter(tr => tr.getClientRects().length > 0 && !tr.classList.contains('exp-compare-row')).length)()`);
    check('【核心】点【全部展开】：30 行全回来', allExpand1 === 30, 'rows=' + allExpand1);

    await clickSel('button[title*="折叠所有分卷"]');
    await sleep(320);
    const allFold = await evalJs(`(() => ({
        rows: [...document.querySelectorAll('.exp-queue-scroll tbody > tr')]
            .filter(tr => tr.getClientRects().length > 0 && !tr.classList.contains('exp-compare-row')).length,
        heads: [...document.querySelectorAll('.exp-queue-vol-head')].filter(h => h.getClientRects().length > 0).length,
        label: [...document.querySelectorAll('button')].map(b => b.textContent.trim()).find(t => /全部折叠|全部展开/.test(t)) || ''
    }))()`);
    check('【核心】点【全部折叠】：所有章节行隐藏，只剩 3 个卷头',
        allFold.rows === 0 && allFold.heads === 3, JSON.stringify(allFold));
    check('全部折叠后按钮文案切到【全部展开】', allFold.label.indexOf('全部展开') >= 0, allFold.label);

    await clickSel('button[title*="展开所有分卷"]');
    await sleep(320);
    const allExpand2 = await evalJs(`(() => ({
        rows: [...document.querySelectorAll('.exp-queue-scroll tbody > tr')]
            .filter(tr => tr.getClientRects().length > 0 && !tr.classList.contains('exp-compare-row')).length,
        label: [...document.querySelectorAll('button')].map(b => b.textContent.trim()).find(t => /全部折叠|全部展开/.test(t)) || ''
    }))()`);
    check('再点【全部展开】：30 行回来，按钮文案切回【全部折叠】',
        allExpand2.rows === 30 && allExpand2.label.indexOf('全部折叠') >= 0, JSON.stringify(allExpand2));

    /* 打开第 26 章（已扩写）的预览面板：队列必须放开限高，面板不能被小窗裁掉 */
    await evalJs(`(() => {
        const btn = [...document.querySelectorAll('.exp-compare-toggle')][0];
        if (btn) btn.click();
        return !!btn;
    })()`);
    await sleep(600);
    const withPanel = await evalJs(`(() => {
        const box = document.querySelector('.exp-queue-scroll');
        const cs = getComputedStyle(box);
        /* 每章都有一份 .sc-cmp（面板行 x-show 控制显隐）⇒ 必须按「可见」挑，
           否则量到的是第一个隐藏面板（height 0），断言会误判 */
        const panel = [...box.querySelectorAll('.sc-cmp')].find(p => p.getClientRects().length > 0);
        const panes = panel ? [...panel.querySelectorAll('.sc-cmp-pane')] : [];
        const boxRect = box.getBoundingClientRect();
        return {
            flow: cs.maxHeight === 'none',
            hasClass: box.classList.contains('exp-queue-flow'),
            boxScroll: box.scrollHeight - box.clientHeight,
            panelH: panel ? Math.round(panel.getBoundingClientRect().height) : 0,
            panelClipped: panel ? Math.round(panel.getBoundingClientRect().bottom - (boxRect.top + box.clientHeight)) : null,
            paneH: panes.map(p => Math.round(p.getBoundingClientRect().height)),
            paneScroll: panes.map(p => p.scrollHeight - p.clientHeight),
            visiblePanels: [...box.querySelectorAll('.sc-cmp')].filter(p => p.getClientRects().length > 0).length,
            outerOverflow: cs.overflowY
        };
    })()`);
    check('【核心】面板打开后队列切成不限高（max-height:none），外层不再滚',
        withPanel.flow === true && withPanel.hasClass === true && withPanel.boxScroll <= 2
        && withPanel.visiblePanels === 1,
        JSON.stringify({ flow: withPanel.flow, boxScroll: withPanel.boxScroll, panels: withPanel.visiblePanels }));
    check('【核心】面板不被队列小窗裁掉（面板底部没有超出容器）',
        withPanel.panelH > 300 && withPanel.panelClipped !== null && withPanel.panelClipped <= 2,
        `panelH=${withPanel.panelH} clipped=${withPanel.panelClipped}`);
    check('同一时刻只有面板这一层滚动（左右两栏各自可滚）',
        withPanel.paneH.length === 2 && withPanel.paneH.every(h => h === 420)
        && withPanel.paneScroll[0] > 0,
        `paneH=${withPanel.paneH} paneScroll=${withPanel.paneScroll}`);
    check('面板打开时不再提示「列表内可滚动」',
        (await evalJs(`(() => { const h = document.querySelector('.exp-queue-hint');
            return !h || (h.innerText || '').indexOf('列表内可滚动') < 0; })()`)) === true);
    await shot('vol-02-panel-flow');

    /* 折叠面板所在的那一卷：面板要跟着收起（cmpOpen 归零），队列回到小窗 */
    await evalJs(`(() => { document.querySelectorAll('.exp-queue-vol .exp-vol-caret')[2].click(); return true; })()`);
    await sleep(400);
    const afterFold = await evalJs(`(() => {
        const root = document.querySelector('.sc-main');
        const d = window.Alpine.$data(root);
        const box = document.querySelector('.exp-queue-scroll');
        return {
            openPanels: d.allChapters.filter(ch => ch.cmpOpen).length,
            flow: getComputedStyle(box).maxHeight === 'none',
            paneCount: box.querySelectorAll('.sc-cmp').length,
            visiblePaneCount: [...box.querySelectorAll('.sc-cmp')].filter(p => p.getClientRects().length > 0).length,
            scrollable: box.scrollHeight > box.clientHeight
        };
    })()`);
    check('【核心】折叠第 3 卷：卷内预览面板一起收起（cmpOpen 归零、可见面板消失）',
        afterFold.openPanels === 0 && afterFold.visiblePaneCount === 0, JSON.stringify(afterFold));
    check('面板全收起后队列回到 12 行小窗（恢复内部滚动）',
        afterFold.flow === false && afterFold.scrollable === true, JSON.stringify(afterFold));
    await shot('vol-03-collapsed');

    /* ---------- T9 ---------- */
    console.log('\n— T9 全程 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));

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

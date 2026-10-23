#!/usr/bin/env node
/**
 * 单词匹配 · 序列配对新增两个分类（疑问词 / 人称代词）验收探针，2026-10-22
 *
 * 与 `probe_wm_topic_book.mjs` 的分工：
 *   那个用**手写小样本**（3 个专题 20 词）守「两个入口 / 锁序 / 窄屏」这套壳子；
 *   这个用**真实词库**（src/main/resources/learn/topic-words.json 全量）守「14 个专题 27 关」的数据落地，
 *   数据一改就跟着变，不写死词数（期望值全部由 JSON 现算）。
 *
 * 为什么值得单独验：
 *   ① 人称代词按「格」拆了 5 个主题，最怕的就是**同一关里出现两张一样的中文**
 *      （I 与 me 都是「我」），页面按释义判定（l.mean === r.text）⇒ 点错那张反而算对。
 *      所以这里逐关断言「中文唯一」，这是这册的设计红线；
 *   ② 左列锁序是人称代词表的教学价值本身 —— I→we→you→he→she→it→they 的顺序错了，这一关就没意义了；
 *   ③ 疑问词 who / whom 词库释义都是「谁」，本册靠 `display` 把 whom 写成「谁（宾格）」消歧，
 *      要确认这条真的落到了卡面上（而不是只写进了脚本）。
 *
 * 跑法：node scripts/probe_wm_topic_pronouns.mjs
 */

import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const CDP_PORT = 9387;
const HTTP_PORT = 18908;
const SHOT_DIR = '/tmp/wm-pronoun-shots';
const ROOT = path.resolve(import.meta.dirname, '..');
const STATIC = path.join(ROOT, 'src/main/resources/static');
const STATIC_ROOTS = [STATIC, path.join(ROOT, 'data/static')];

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ==================== 用真实词库构造 mock 数据 ==================== */
const TOPIC_BOOK = JSON.parse(
    fs.readFileSync(path.join(ROOT, 'src/main/resources/learn/topic-words.json'), 'utf8')).books[0];

/* 与 WordMatchBank 逐字同源的切关口径（MIN 3 / MAX 7 / TARGET 6） */
const MIN_PAIRS = 3, MAX_PAIRS = 7, TARGET_PAIRS = 6;
function splitBalanced(n) {
    if (n <= 0) return [];
    if (n <= MAX_PAIRS) return [n];
    const groups = Math.min(Math.max(Math.ceil(n / MAX_PAIRS), Math.round(n / TARGET_PAIRS)), n);
    const base = Math.floor(n / groups), rem = n % groups;
    return Array.from({ length: groups }, (_, i) => base + (i < rem ? 1 : 0));
}

const LEVELS = [];
(function build() {
    let seq = 0;
    for (const theme of TOPIC_BOOK.themes) {
        if (theme.words.length < MIN_PAIRS) continue;   // WordMatchBank 对 <3 词的主题直接丢弃
        let cur = 0;
        for (const size of splitBalanced(theme.words.length)) {
            const pairs = [];
            for (let i = 0; i < size; i++) {
                const [en, zh] = theme.words[cur + i];
                pairs.push({ key: 't' + seq + ':' + i, en, zh });
            }
            cur += size;
            LEVELS.push({ theme: theme.name, pairs });
            seq++;
        }
    }
})();

const THEME_ORDER = [...new Set(LEVELS.map(l => l.theme))];
const WORD_TOTAL = TOPIC_BOOK.themes.reduce((a, t) => a + t.words.length, 0);
const TOPIC_SUMMARY = `英文按顺序 · 中文打乱 · ${THEME_ORDER.length} 个专题 · ${WORD_TOTAL} 词`;

const WM_DATA = {
    books: [{
        id: 'pep-topic', label: '序列配对', grade: 0, semester: '', stage: '专题',
        levelCount: LEVELS.length, wordCount: WORD_TOTAL
    }],
    extraBooks: [],
    levels: { 'pep-topic': LEVELS }
};

/* ==================== 静态服务器 ==================== */
const MIME = {
    '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8',
    '.svg': 'image/svg+xml', '.png': 'image/png',
    '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf'
};

function serveStatic(res, relPath) {
    let buf = null, hit = null;
    for (const root of STATIC_ROOTS) {
        const file = path.join(root, relPath);
        if (!file.startsWith(root)) continue;
        try { buf = fs.readFileSync(file); hit = file; break; } catch (e) { /* 换下一个根目录 */ }
    }
    if (!buf) { res.writeHead(404); return res.end(''); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(hit)] || 'application/octet-stream' });
    res.end(buf);
}

const server = http.createServer((req, res) => {
    const pn = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (pn === '/learn/word-match') return serveStatic(res, 'pages/learn-word-match.html');
    if (pn === '/learn/word-match/data') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify(WM_DATA));
    }
    if (pn === '/learn/word-match/cet') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify({}));
    }
    return serveStatic(res, pn.replace(/^\/+/, ''));
});

/* ==================== Chrome / CDP ==================== */
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-pronoun-probe-'));
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
async function goto(width = 1280, height = 900) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}/learn/word-match?_probe=` + (++navSeq) });
}
const waitSettled = () => waitFor(async () => {
    try {
        return (await evalJs(`(() => { const r = document.querySelector('.wm-root');
            const d = r && window.Alpine ? window.Alpine.$data(r) : null; return !!d && d.booting === false; })()`)) === true;
    } catch (e) { return false; }
});

/** 真实鼠标点击（Alpine @click 需要真实事件流） */
async function clickSel(selector) {
    const pt = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(selector)});
        if (!el) return null;
        el.scrollIntoView({ block: 'center' });
        const r = el.getBoundingClientRect();
        if (!r.width || !r.height) return null;
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!pt) throw new Error('点不到元素: ' + selector);
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: pt.x, y: pt.y, button: 'none', clickCount: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: pt.x, y: pt.y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: pt.x, y: pt.y, button: 'left', clickCount: 1 });
}

/** 按卡面文案点某列的一张卡（返回是否点到） */
async function clickCard(col, text) {
    const sel = await evalJs(`(() => {
        const cards = Array.from(document.querySelectorAll(${JSON.stringify(col)} + ' .wm-card'))
            .filter(el => el.getClientRects().length > 0);
        const el = cards.find(c => (c.innerText || '').replace(/\\s+/g, ' ').trim() === ${JSON.stringify(text)});
        if (!el) return null;
        el.scrollIntoView({ block: 'center' });
        const r = el.getBoundingClientRect();
        el.dataset.probeHit = '1';
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!sel) return false;
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: sel.x, y: sel.y, button: 'none', clickCount: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: sel.x, y: sel.y, button: 'left', clickCount: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: sel.x, y: sel.y, button: 'left', clickCount: 1 });
    return true;
}

const STATE = `(() => {
    const root = document.querySelector('.wm-root');
    const st = (root && window.Alpine) ? window.Alpine.$data(root) : null;
    return st ? {
        view: st.view, bookId: st.bookId, levelIndex: st.levelIndex,
        levelCount: st.levels.length, theme: st.currentLevel ? st.currentLevel.theme : null,
        matched: st.matchedCount, locked: st.locked,
        left: Array.from(st.leftCards).map(c => ({ text: c.text, done: c.done })),
        right: Array.from(st.rightCards).map(c => ({ text: c.text, done: c.done })),
        themeLabel: (() => { const e = document.querySelector('.wm-theme');
            return e ? (e.innerText || '').replace(/\\s+/g, ' ').trim() : null; })(),
        themeW: (() => { const e = document.querySelector('.wm-theme');
            return e ? { sw: e.scrollWidth, cw: e.clientWidth } : null; })(),
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    } : null;
})()`;
const state = async (ms = 260) => { await sleep(ms); return evalJs(STATE); };

/* ==================== 主流程 ==================== */
async function main() {
    await new Promise((res, rej) => { server.once('error', rej); server.listen(HTTP_PORT, '127.0.0.1', res); });
    console.log(`静态服务器: http://127.0.0.1:${HTTP_PORT}`);
    console.log(`词源: ${THEME_ORDER.length} 个专题 / ${LEVELS.length} 关 / ${WORD_TOTAL} 词`);
    console.log('专题顺序: ' + THEME_ORDER.join(' → '));

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
    /* 静音：否则点卡片会走语音合成，进度不可控 */
    await send('Page.addScriptToEvaluateOnNewDocument', {
        source: `try { localStorage.setItem('word_match_sound_v1', '0'); } catch (e) {}`
    });

    /* ---------- P1 首页口径现算 ---------- */
    console.log('\n— P1 首页序列配对入口：专题数与词数按真实词库现算 —');
    await goto();
    check('页面 boot 完成', (await waitSettled()) === true);
    const entry = await evalJs(`(() => {
        const el = document.querySelector('#wm-topic-entry');
        if (!el) return null;
        const de = el.querySelector('.wm-home-entry-desc');
        return { desc: (de ? de.innerText : '').replace(/\\s+/g, ' ').trim(),
                 h: Math.round(el.getBoundingClientRect().height) };
    })()`);
    check(`副文案 = 「${TOPIC_SUMMARY}」`, !!entry && entry.desc === TOPIC_SUMMARY, entry && entry.desc);
    check('入口仍只占一行（高度 ≤ 60px）', !!entry && entry.h <= 60, entry && entry.h);
    await shot('p1-home');

    /* ---------- P2 进棋盘 + 逐关扫描 ---------- */
    console.log('\n— P2 逐关扫描：左侧锁序 / 右侧打乱 / 关内中文唯一 —');
    await evalJs(`(() => { const d = window.Alpine.$data(document.querySelector('.wm-root'));
        d.applyBook('pep-topic', false); d.view = 'play'; })()`);
    await sleep(400);
    const boot = await state();
    check('已进棋盘且是本册', !!boot && boot.bookId === 'pep-topic' && boot.view === 'play',
        boot && `${boot.bookId}/${boot.view}`);
    check(`关卡数 = ${LEVELS.length}`, !!boot && boot.levelCount === LEVELS.length, boot && boot.levelCount);

    /* 一次 evaluate 里走完所有关：读的是 state（leftCards/rightCards），不等 DOM 渲染 */
    const scan = await evalJs(`(() => {
        const d = window.Alpine.$data(document.querySelector('.wm-root'));
        const out = [];
        for (let i = 0; i < d.levels.length; i++) {
            d.levelIndex = i;
            d.loadLevel();
            out.push({
                theme: d.currentLevel.theme,
                L: Array.from(d.leftCards).map(c => c.text),
                R: Array.from(d.rightCards).map(c => c.text),
                aligned: d.countAligned(Array.from(d.leftCards), Array.from(d.rightCards))
            });
        }
        d.levelIndex = 0; d.loadLevel();
        return out;
    })()`);

    check(`扫到 ${LEVELS.length} 关`, scan.length === LEVELS.length, scan.length);

    /* 主题顺序：新分类必须整段排在末尾，且内部按「疑问 → 人称」的语法顺序 */
    const themeSeq = [...new Set(scan.map(l => l.theme))];
    check('14 个专题的名字与顺序 = 词库顺序（新分类追加在末尾）',
        JSON.stringify(themeSeq) === JSON.stringify(THEME_ORDER),
        themeSeq.join(' → '));
    check('疑问词两个专题在亲人关系之后',
        themeSeq.indexOf('疑问代词') === 7 && themeSeq.indexOf('疑问副词') === 8,
        themeSeq.slice(7, 9).join(','));
    check('人称代词五个「格」专题顺序：主格 → 宾格 → 形物 → 名物 → 反身',
        JSON.stringify(themeSeq.slice(9)) === JSON.stringify(['主格代词', '宾格代词', '形物代词', '名物代词', '反身代词']),
        themeSeq.slice(9).join(','));

    /* 逐关三条硬性质 */
    const orderBad = [], dupBad = [], alignBad = [];
    scan.forEach((lv, i) => {
        const want = LEVELS[i];
        if (JSON.stringify(lv.L) !== JSON.stringify(want.pairs.map(p => p.en))) orderBad.push(i + 1);
        const zh = lv.R.filter(t => t);
        if (new Set(zh).size !== zh.length) dupBad.push(i + 1);
        if (lv.aligned > 1) alignBad.push(i + 1 + ':' + lv.aligned);
    });
    check('每关左列英文 = 词库顺序（锁序没被新主题破坏）', orderBad.length === 0, '异常关: ' + orderBad.join(','));
    check('【核心】每关中文唯一（否则两张一样的卡，玩家无从选择）', dupBad.length === 0, '重复关: ' + dupBad.join(','));
    check('每关右列中文确实被打乱（同位正确 ≤1）', alignBad.length === 0, alignBad.join(','));

    /* ---------- P3 人称代词：格变位表 ---------- */
    console.log('\n— P3 人称代词：一个「格」一关，左列就是人称代词表的一行 —');
    const idxOf = (theme) => LEVELS.findIndex(l => l.theme === theme);
    const levelOf = (theme) => scan[idxOf(theme)];
    const nom = levelOf('主格代词');
    check('主格关左列 = I → we → you → he → she → it → they',
        JSON.stringify(nom.L) === JSON.stringify(['I', 'we', 'you', 'he', 'she', 'it', 'they']),
        nom.L.join(','));
    check('主格关中文字面 = 我/我们/你/他/她/它/他们',
        nom.R.slice().sort().join(',') === ['我', '我们', '你', '他', '她', '它', '他们'].sort().join(','),
        nom.R.join(','));
    const acc = levelOf('宾格代词');
    check('宾格关左列 = me → us → you → him → it → them（教材未收 her 的宾格义）',
        JSON.stringify(acc.L) === JSON.stringify(['me', 'us', 'you', 'him', 'it', 'them']),
        acc.L.join(','));
    const adj = levelOf('形物代词');
    check('形物关左列 = my → our → your → his → her → its → their',
        JSON.stringify(adj.L) === JSON.stringify(['my', 'our', 'your', 'his', 'her', 'its', 'their']),
        adj.L.join(','));
    const pos = levelOf('名物代词');
    check('名物关左列 = mine → ours → yours → hers → theirs',
        JSON.stringify(pos.L) === JSON.stringify(['mine', 'ours', 'yours', 'hers', 'theirs']),
        pos.L.join(','));
    const refl = levelOf('反身代词');
    check('反身关左列 = myself → … → themselves（7 对，正好一关）',
        JSON.stringify(refl.L) === JSON.stringify(['myself', 'yourself', 'himself', 'herself', 'itself', 'ourselves', 'themselves']),
        refl.L.join(','));
    check('主格与宾格的中文集合故意重叠（I 与 me 都是「我」）—— 跨关重复是设计，不是 bug',
        acc.R.includes('我') && nom.R.includes('我'),
        `主格含「我」=${nom.R.includes('我')} 宾格含「我」=${acc.R.includes('我')}`);

    /* ---------- P4 疑问词：who / whom 消歧 ---------- */
    console.log('\n— P4 疑问词：who 与 whom 都释义「谁」，靠 whom 的格标注消歧 —');
    const qp = levelOf('疑问代词');
    check('疑问代词关左列 = what → which → who → whom → whose',
        JSON.stringify(qp.L) === JSON.stringify(['what', 'which', 'who', 'whom', 'whose']),
        qp.L.join(','));
    check('卡面上「谁」与「谁（宾格）」两张都在（消歧真的落到了显示层）',
        qp.R.includes('谁') && qp.R.includes('谁（宾格）'), qp.R.join(','));
    check('疑问副词关左列 = when → where → why → how → how much',
        JSON.stringify(levelOf('疑问副词').L) === JSON.stringify(['when', 'where', 'why', 'how', 'how much']),
        levelOf('疑问副词').L.join(','));

    /* ---------- P5 真实点击：主格关配一对 ---------- */
    console.log('\n— P5 真实点击：主格关把 I 配上「我」 —');
    await evalJs(`(() => { const d = window.Alpine.$data(document.querySelector('.wm-root'));
        d.levelIndex = ${idxOf('主格代词')}; d.loadLevel(); })()`);
    await sleep(360);
    let st = await state(120);
    check('已切到主格关', st.theme === '主格代词', st.theme);
    check('页头类别标签显示「主格代词」', !!st.themeLabel && st.themeLabel.indexOf('主格代词') >= 0, st.themeLabel);

    const hitI = await clickCard('.wm-col-en', 'I');
    await sleep(200);
    const hitWo = await clickCard('.wm-col-zh', '我');
    await sleep(420);
    st = await state(200);
    check('两张卡都点到了（I / 我）', hitI === true && hitWo === true, `${hitI}/${hitWo}`);
    check('【核心】配对判定成立：matchedCount = 1', st.matched === 1, st.matched);
    check('I 与我同时置为已完成', st.left.find(c => c.text === 'I').done === true
        && st.right.find(c => c.text === '我').done === true,
        `${st.left.find(c => c.text === 'I').done}/${st.right.find(c => c.text === '我').done}`);
    await shot('p5-nom-level');

    /* ---------- P6 320px 窄屏 ---------- */
    console.log('\n— P6 320px：新的 4 字主题名不要把标题行顶出去 —');
    await goto(320, 568);
    await waitSettled();
    await evalJs(`(() => { const d = window.Alpine.$data(document.querySelector('.wm-root'));
        d.applyBook('pep-topic', false); d.view = 'play';
        d.levelIndex = ${idxOf('主格代词')}; d.loadLevel(); })()`);
    await sleep(520);
    st = await state(200);
    check('窄屏无横向溢出', st.docScrollW <= st.docClientW, `${st.docScrollW}/${st.docClientW}`);
    check('类别标签「主格代词」没有溢出自身容器',
        !!st.themeW && st.themeW.sw <= st.themeW.cw + 1, st.themeW && `${st.themeW.sw}/${st.themeW.cw}`);
    check('窄屏棋盘 7 对卡都在（主格关 7 对）', st.left.length === 7 && st.right.length === 7,
        `${st.left.length}/${st.right.length}`);
    await shot('p6-narrow');

    /* ---------- P7 320px 首页副文案 ---------- */
    console.log('\n— P7 320px 首页：案例副文案多出「14 个专题 · 158 词」，两行装得下 —');
    await goto(320, 568);
    await waitSettled();
    const desc = await evalJs(`(() => {
        const de = document.querySelector('#wm-topic-entry .wm-home-entry-desc');
        if (!de) return null;
        return { text: (de.innerText || '').replace(/\\s+/g, ' ').trim(),
                 fit: de.scrollWidth <= de.clientWidth + 1 && de.scrollHeight <= de.clientHeight + 1,
                 h: Math.round(de.getBoundingClientRect().height),
                 docW: document.documentElement.scrollWidth,
                 cliW: document.documentElement.clientWidth };
    })()`);
    check('窄屏副文案完整可读（专题数 / 词数没被 ellipsis 吃掉）',
        !!desc && desc.fit, desc && `${desc.text} fit=${desc.fit} h=${desc.h}`);
    check('窄屏首页无横向溢出', !!desc && desc.docW <= desc.cliW, desc && `${desc.docW}/${desc.cliW}`);

    /* ---------- 收尾 ---------- */
    check('全程无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));

    const pass = results.filter(r => r.ok).length;
    console.log(`\n===== ${pass}/${results.length} 通过 =====`);
    if (pass !== results.length) {
        console.log('失败项：');
        results.filter(r => !r.ok).forEach(r => console.log('  - ' + r.name + (r.extra ? '  [' + r.extra + ']' : '')));
    }
    console.log('截图: ' + SHOT_DIR);
}

let exitCode = 0;
main()
    .catch(e => { console.error('\n✗ 探针异常：' + (e && e.stack || e)); exitCode = 1; })
    .finally(async () => {
        try { ws && ws.close(); } catch (e) {}
        try { server.close(); } catch (e) {}
        try { chrome.kill('SIGKILL'); } catch (e) {}
        await sleep(180);
        try { fs.rmSync(userDataDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 120 }); } catch (e) {}
        process.exit(exitCode || (results.every(r => r.ok) ? 0 : 1));
    });

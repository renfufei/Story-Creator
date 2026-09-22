#!/usr/bin/env node
/**
 * /learn/word-match 功能验证：真机 Chrome + CDP，真实鼠标事件（不是 element.click()）。
 *
 * 覆盖：
 *  S1 初始结构 / 每关随机打乱（不出现整片一一对应、重复进入排列会变）
 *  S1b 设置弹层（错题本 / 重做本关 / 朗读开关 / 重置进度 都收在齿轮里：图标、文字说明、
 *      开关状态胶囊、点条目自动收起、点外部收起、朗读状态落盘）
 *  S2 选中 / 取消选中
 *  S3 不匹配标红（保留先选中）+ 错题本自动记录与次数累计
 *  S4 匹配成功置灰
 *  S5 全部配对后自动进下一关 + 练习进度落盘
 *  S6 册次弹出框（选择册次、旧下拉已移除）
 *  S7 上一关 / 下一关 + 边界禁用
 *  S7b 本册最后一关：「下一关」变「下一册」+ 打完弹通关窗、点「继续看看」后仍能继续
 *  S8 错题本弹窗（列表 / 朗读 / 去练 / 删除 / 清空）
 *  S9 自动学习（真实节奏的 1s 间隔与 2s 思考；快速节奏跑完整关 + 4s 过关节奏 +
 *     暂停/继续/退出 + 学习进度与练习进度互不共享 + 点英文即朗读）
 *  另：朗读时机（点英文那一下）与过关提示「贴单词区上方、不遮挡卡片」单独断言
 *  S9d 同义关：同一个中文对应多个词，选哪个都算对（按释义判定而非配对 key）
 *  S10 移动端布局（含新按钮/底部导航不溢出、重排后仍可点）
 *  S10b 短屏 + 满员关（7 对，如三上第 13 关）：顶部信息压缩后不再溢出
 *       （375x667 与 320x568 两档；浮动胶囊不压单词、说明文字横向滑动）
 *  S12 导出为单页 HTML（设置 → 导出：册次多选 / 全选与本学段全选、默认勾当前册；
 *      真实落盘后校验产物零外链、只含被勾的册，再用 file:// 打开跑一遍配对）
 *  S11 运行时错误采集
 *
 * 静音约定：脚本开头把 word_match_sound_v1 写成 0，整轮默认不发声；
 *          需要验「点英文即朗读」时由 speakProbeSetup() 临时打开语音，
 *          并且探针只是记录、不调用原生 speak()（否则本机 TTS 会整轮不停地念）。
 *
 * 用法：node check_word_match.mjs
 * 可选环境变量：WM_BASE（默认 http://localhost:1888）、WM_OUT（截图目录）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/word-match';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9333;
const OUT = process.env.WM_OUT || '/tmp/wm-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-cdp-'));
/* 导出功能的真实下载目录（CDP 拦截下载到此，产物留在磁盘上供人工复查） */
const downloadDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-export-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    /* 导出验证要把产物从 file:// 打回来，必须放行本地文件访问 */
    '--allow-file-access-from-files',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let ws, msgId = 0;
const pending = new Map();
const runtimeErrors = [];
const consoleErrors = [];
const httpErrors = [];

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

async function clickAt(x, y) {
    const p = { x, y, button: 'left', buttons: 1, clickCount: 1, pointerType: 'mouse' };
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...p });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...p });
}

async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    const file = path.join(OUT, name + '.png');
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
    console.log('  截图 ' + file);
    return file;
}

/* 取元素中心点（只在可见元素里找，避免点到 display:none 的隐藏节点上） */
async function centerOf(selector, nth = 0, text = null) {
    return evalJs(`(() => {
        let els = Array.from(document.querySelectorAll(${JSON.stringify(selector)}))
            .filter(e => e.getClientRects().length > 0);
        ${text ? `els = els.filter(e => e.innerText.trim().includes(${JSON.stringify(text)}));` : ''}
        const el = els[${nth}];
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2),
                 text: el.innerText.trim().replace(/\\s+/g, ' ').slice(0, 40),
                 w: Math.round(r.width), h: Math.round(r.height) };
    })()`);
}

async function clickSel(selector, nth = 0, text = null) {
    const box = await centerOf(selector, nth, text);
    if (!box) throw new Error('找不到可点元素：' + selector + (text ? ' text=' + text : ''));
    await clickAt(box.x, box.y);
    return box;
}

/* 册次列表 24 册已限高滚动 + 按学段折叠，目标项可能在折叠组里/可视区外：
   先展开它所属的学段，再滚到中间，否则真实鼠标点不到 */
async function isStageOpen(stage) {
    return evalJs(`(() => {
        const h = Array.from(document.querySelectorAll('.wm-book-group-head'))
            .find(e => e.innerText.indexOf(${JSON.stringify(stage)}) >= 0);
        return !!h && h.classList.contains('is-open');
    })()`);
}

/* 用真实鼠标点学段标题（先滚进可视区，否则点不到） */
async function clickStageHead(stage) {
    const ok = await evalJs(`(() => {
        const h = Array.from(document.querySelectorAll('.wm-book-group-head'))
            .find(e => e.innerText.indexOf(${JSON.stringify(stage)}) >= 0);
        if (!h) return false;
        h.scrollIntoView({ block: 'center' });
        return true;
    })()`);
    if (!ok) throw new Error('学段分组不存在：' + stage);
    await sleep(200);
    await clickSel('.wm-book-group-head', 0, stage);
    await sleep(220);
}

async function openStage(stage) {
    if (!(await isStageOpen(stage))) await clickStageHead(stage);
}

/* 目标册所在的学段名（用于点册前自动展开） */
async function stageOfBook(text) {
    return evalJs(`(() => {
        const it = Array.from(document.querySelectorAll('.wm-book-item'))
            .find(e => e.innerText.indexOf(${JSON.stringify(text)}) >= 0);
        if (!it) return null;
        const g = it.closest('.wm-book-group');
        return g ? g.querySelector('.wm-book-group-name').innerText.trim() : null;
    })()`);
}

/* 册次弹层里每个学段分组的展开状态 / 可见册次数 / 标题是否落在可视区 */
async function pickerGroups() {
    return evalJs(`(() => {
        const back = Array.from(document.querySelectorAll('.wm-modal-backdrop'))
            .find(e => e.getClientRects().length > 0);
        if (!back) return null;
        const lr = back.querySelector('.wm-book-list').getBoundingClientRect();
        return Array.from(back.querySelectorAll('.wm-book-group')).map(g => {
            const head = g.querySelector('.wm-book-group-head');
            const items = Array.from(g.querySelectorAll('.wm-book-item'));
            const r = head.getBoundingClientRect();
            return { open: head.classList.contains('is-open'),
                     shown: items.filter(e => e.getClientRects().length > 0).length,
                     total: items.length,
                     headTop: Math.round(r.top), headBottom: Math.round(r.bottom),
                     listTop: Math.round(lr.top), listBottom: Math.round(lr.bottom),
                     caret: getComputedStyle(head.querySelector('.wm-book-group-caret')).transform };
        });
    })()`);
}
async function clickBookItem(text) {
    // 目标册可能在被折叠的学段里：先展开它所属的学段（手风琴，只会展开这一个）
    const stage = await stageOfBook(text);
    if (stage) await openStage(stage);
    const found = await evalJs(`(() => {
        const it = Array.from(document.querySelectorAll('.wm-book-item'))
            .find(e => e.innerText.indexOf(${JSON.stringify(text)}) >= 0);
        if (!it) return false;
        it.scrollIntoView({ block: 'center' });
        return true;
    })()`);
    if (!found) throw new Error('册次项不存在：' + text);
    await sleep(200);
    return clickSel('.wm-book-item', 0, text);
}

/* 「错题本 / 重做本关 / 朗读开关 / 重置进度」已收进工具栏的「设置」弹层：
   点这些项之前要先展开齿轮，点完（除朗读开关外）弹层会自己收起。 */
async function settingsOpen() {
    return evalJs(`(() => { const el = document.querySelector('.wm-set-pop'); return !!(el && el.getClientRects().length); })()`);
}

async function openSettings() {
    if (await settingsOpen()) return;
    await clickSel('.wm-set-btn');
    await sleep(180);
}

async function clickInSettings(title) {
    await openSettings();
    await clickSel(`.wm-set-item[title="${title}"]`);
    await sleep(180);
}

async function board() {
    return evalJs(`(() => {
        const grab = (sel) => Array.from(document.querySelectorAll(sel + ' .wm-card')).map(el => {
            const r = el.getBoundingClientRect();
            const cs = getComputedStyle(el);
            return {
                label: el.getAttribute('aria-label') || '',
                text: el.innerText.trim(),
                sel: el.classList.contains('is-sel'),
                done: el.classList.contains('is-done'),
                wrong: el.classList.contains('is-wrong'),
                bg: cs.backgroundColor,
                color: cs.color,
                x: Math.round(r.x + r.width / 2),
                y: Math.round(r.y + r.height / 2),
                w: Math.round(r.width),
                h: Math.round(r.height)
            };
        });
        const counter = document.querySelector('.wm-counter');
        const openModal = !!document.querySelector('.wm-modal-backdrop:not([style*="display: none"])');
        return {
            en: grab('.wm-col-en'),
            zh: grab('.wm-col-zh'),
            counter: counter ? counter.innerText.replace(/\\s+/g, '') : null,
            title: document.querySelector('.wm-title')?.innerText || null,
            theme: document.querySelector('.wm-theme')?.innerText.trim() || null,
            hint: document.querySelector('.wm-footer')?.innerText.trim().split('\\n')[0] || null,
            segs: {
                total: document.querySelectorAll('.wm-seg').length,
                done: document.querySelectorAll('.wm-seg.is-done').length,
                current: document.querySelectorAll('.wm-seg.is-current').length
            },
            finishedModal: openModal,
            cardCount: document.querySelectorAll('.wm-card').length
        };
    })()`);
}

/* 整页状态快照：自动学习 / 进度 / 本地存储 一次拿全 */
async function state() {
    return evalJs(`(() => {
        const vis = (el) => !!el && el.getClientRects().length > 0;
        const bar = document.querySelector('.wm-auto-bar');
        const flash = document.querySelector('.wm-flash');
        const read = (k) => localStorage.getItem(k);
        return {
            counter: document.querySelector('.wm-counter')?.innerText.replace(/\\s+/g, '') || null,
            title: document.querySelector('.wm-title')?.innerText.trim() || null,
            matched: document.querySelectorAll('.wm-card.is-done').length,
            sel: document.querySelectorAll('.wm-card.is-sel').length,
            wrong: document.querySelectorAll('.wm-card.is-wrong').length,
            total: document.querySelectorAll('.wm-card').length,
            autoBar: vis(bar),
            autoTip: bar ? bar.querySelector('.wm-auto-tip').innerText.trim() : '',
            autoCount: bar && vis(bar.querySelector('.wm-auto-count'))
                ? bar.querySelector('.wm-auto-count').innerText.trim() : '',
            autoPaused: vis(bar) && bar.classList.contains('is-paused'),
            autoProgressBar: !!document.querySelector('.wm-progress.is-auto'),
            segDone: document.querySelectorAll('.wm-seg.is-done').length,
            segCurrent: document.querySelectorAll('.wm-seg.is-current').length,
            flash: vis(flash) ? flash.innerText.trim() : '',
            practice: read('word_match_progress_v2'),
            autoProg: read('word_match_auto_progress_v2'),
            wrongStore: read('word_match_wrong_v1'),
            book: read('word_match_book_v1')
        };
    })()`);
}

async function levelPairs() {
    return evalJs(`(() => {
        const d = window.__WORD_MATCH_DATA__;
        const bookId = localStorage.getItem('word_match_book_v1') || d.books[0].id;
        const idx = parseInt(document.querySelector('.wm-counter b').innerText, 10) - 1;
        const lv = d.levels[bookId][idx];
        return { bookId, idx, theme: lv.theme, pairs: lv.pairs.map(p => ({ en: p.en, zh: p.zh })) };
    })()`);
}

/* 当前排列：两列顺序 + 「同行即同词」的行数 */
async function layout() {
    return evalJs(`(() => {
        const d = window.__WORD_MATCH_DATA__;
        const book = localStorage.getItem('word_match_book_v1');
        const idx = parseInt(document.querySelector('.wm-counter b').innerText, 10) - 1;
        const pairs = d.levels[book][idx].pairs;
        const keyOfEn = {}, keyOfZh = {};
        pairs.forEach(p => { keyOfEn[p.en] = p.key; keyOfZh[p.zh] = p.key; });
        const en = Array.from(document.querySelectorAll('.wm-col-en .wm-card')).map(e => e.innerText.trim());
        const zh = Array.from(document.querySelectorAll('.wm-col-zh .wm-card')).map(e => e.innerText.trim());
        let aligned = 0;
        for (let i = 0; i < en.length; i++) if (keyOfEn[en[i]] === keyOfZh[zh[i]]) aligned++;
        return { en: en.join('|'), zh: zh.join('|'), aligned,
                 sig: en.join(',') + '#' + zh.join(',') };
    })()`);
}

async function waitFor(desc, fn, timeout = 10000, interval = 100) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        if (await fn()) return true;
        await sleep(interval);
    }
    return false;
}

async function waitCards() {
    const ok = await waitFor('卡片', async () =>
        (await evalJs(`document.querySelectorAll('.wm-card').length`)) > 0, 15000, 150);
    if (!ok) throw new Error('卡片未渲染，后续断言无法进行');
}

const byText = (arr, text) => arr.find(c => c.text === text);

async function pick(label) {
    const b = await board();
    const card = [...b.en, ...b.zh].find(c => c.text === label);
    if (!card) throw new Error('找不到卡片：' + label);
    await clickAt(card.x, card.y);
    return card;
}

function wrongStore() {
    return evalJs(`JSON.parse(localStorage.getItem('word_match_wrong_v1') || '{}')`);
}

/* 朗读探针：接管 speechSynthesis.speak —— 只记录被朗读的文本，**不真的发声**，
   并在 120ms 后触发 u.onend 模拟「念完了」（否则自动学习要等 AUTO_SPEAK_MAX 上限才继续，拖慢用例）。
   返回 false 表示本机不支持语音。
   两点与之前不同：
   1. 冒烟默认静音（脚本开头写 word_match_sound_v1=0），这里临时打开，
      否则 speak() 会因为 soundOn=false 直接 return，探针什么都记不到；
   2. 不再调用原生 speak()，否则本机 TTS 会真的一直念，整轮测试都在响。 */
async function speakProbeSetup() {
    return evalJs(`(() => {
        if (!window.speechSynthesis || typeof SpeechSynthesisUtterance === 'undefined') return false;
        try {
            if (!window.__spokenHooked) {
                speechSynthesis.speak = function (u) {
                    try { window.__spoken.push(String((u && u.text) || '')); } catch (e) {}
                    setTimeout(function () {
                        try { if (u && typeof u.onend === 'function') u.onend(); } catch (e) {}
                    }, 120);
                };
                try { speechSynthesis.cancel = function () {}; } catch (e) { /* 只读就不动它 */ }
                window.__spokenHooked = true;
            }
            window.__spoken = [];
            const d = Alpine.$data(document.querySelector('.wm-root'));
            d.soundOn = true;
            localStorage.setItem('word_match_sound_v1', '1');
            return true;
        } catch (e) { return false; }
    })()`);
}

/* 探针用完把语音关回去：后续大量点击不该再触发朗读（探针本身也不再发声） */
async function speakProbeTeardown() {
    await evalJs(`(() => {
        try { speechSynthesis.cancel(); } catch (e) {}
        const d = Alpine.$data(document.querySelector('.wm-root'));
        if (d) { d.soundOn = false; }
        localStorage.setItem('word_match_sound_v1', '0');
        return true;
    })()`);
}

function spokenTexts() { return evalJs(`(window.__spoken || []).slice()`); }
function clearSpoken() { return evalJs(`(window.__spoken = []) && true`); }

/* 过关提示与单词区的重叠度量：提示必须完全落在单词区之外 */
async function flashGeom(shotName) {
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        if (d.flashTimer) clearTimeout(d.flashTimer);
        d.flashEmoji = '✅';
        d.flash = '本关完成！即将进入下一关';
        return true;
    })()`);
    await sleep(160);
    if (shotName) await shot(shotName);
    const g = await evalJs(`(() => {
        const f = document.querySelector('.wm-flash');
        const fr = f.getBoundingClientRect();
        const hit = (r) => !(fr.right <= r.left || fr.left >= r.right || fr.bottom <= r.top || fr.top >= r.bottom);
        const cards = Array.from(document.querySelectorAll('.wm-card')).map(e => e.getBoundingClientRect());
        const br = document.querySelector('.wm-board').getBoundingClientRect();
        const tr = document.querySelector('.wm-titlebar').getBoundingClientRect();
        return {
            visible: f.getClientRects().length > 0,
            top: Math.round(fr.top), bottom: Math.round(fr.bottom), vh: window.innerHeight,
            boardTop: Math.round(br.top), boardBottom: Math.round(br.bottom),
            titlebarTop: Math.round(tr.top), titlebarBottom: Math.round(tr.bottom),
            /* 提示必须落在单词区「上方」：此前在视口底部，桌面端离单词区 200~300px，等于看不见 */
            aboveBoard: fr.bottom <= br.top,
            /* 也必须在标题行那一带（提示已改成贴标题行居中） */
            onTitlebar: fr.top >= tr.top - 12 && fr.bottom <= tr.bottom + 12,
            overBoard: hit(br),
            overCards: cards.filter(hit).length,
            overNav: hit(document.querySelector('.wm-nav').getBoundingClientRect()),
            overFooter: hit(document.querySelector('.wm-footer').getBoundingClientRect())
        };
    })()`);
    await evalJs(`(() => { const d = Alpine.$data(document.querySelector('.wm-root')); d.flash = ''; return true; })()`);
    await sleep(80);
    return g;
}

/* 自动学习现场快照：册次 / 提示 / 状态条高度 / 提示条行数，一次取全（跨册用例高频轮询用） */
async function autoSnapshot() {
    return evalJs(`(() => {
        const vis = (el) => !!el && el.getClientRects().length > 0;
        const bar = document.querySelector('.wm-auto-bar');
        const flash = document.querySelector('.wm-flash');
        const bx = bar ? bar.getBoundingClientRect() : null;
        const fx = flash ? flash.getBoundingClientRect() : null;
        const lh = flash ? (parseFloat(getComputedStyle(flash).lineHeight) || 22) : 22;
        const ft = flash ? flash.lastElementChild : null;
        const tag = bar ? bar.querySelector('.wm-auto-tag') : null;
        return {
            book: localStorage.getItem('word_match_book_v1'),
            counter: document.querySelector('.wm-counter')?.innerText.replace(/\s+/g, '') || '',
            tip: bar && vis(bar) ? bar.querySelector('.wm-auto-tip').innerText.replace(/\s+/g, ' ').trim() : '',
            count: bar && vis(bar.querySelector('.wm-auto-count'))
                ? bar.querySelector('.wm-auto-count').innerText.trim() : '',
            flash: vis(flash) ? flash.innerText.trim() : '',
            barH: bar && vis(bar) ? Math.round(bx.height) : 0,
            flashLines: fx ? Math.round(fx.height / lh) : 0,
            flashClipped: ft ? ft.scrollWidth > ft.clientWidth + 1 : false,
            tagClipped: tag ? tag.scrollWidth > tag.clientWidth + 1 : false,
            matched: document.querySelectorAll('.wm-card.is-done').length,
            autoBar: vis(bar)
        };
    })()`);
}

/* 过关提示的长文案：移动端也必须只有一行（超出才省略） */
async function flashTextGeom(emoji, text, shotName) {
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        if (d.flashTimer) clearTimeout(d.flashTimer);
        d.flashEmoji = ${JSON.stringify(emoji)};
        d.flash = ${JSON.stringify(text)};
        return true;
    })()`);
    await sleep(150);
    if (shotName) await shot(shotName);          // 截图必须在清掉提示之前，否则拍不到
    const g = await evalJs(`(() => {
        const f = document.querySelector('.wm-flash');
        const txt = f.lastElementChild;
        const r = f.getBoundingClientRect();
        /* 行数直接数文本的 line box：用容器高度除 line-height 会被 padding 与 emoji 字号带偏 */
        const rg = document.createRange();
        rg.selectNodeContents(txt);
        const rects = Array.from(rg.getClientRects()).filter(x => x.height > 1 && x.width > 1);
        return {
            lines: rects.length,
            clipped: txt.scrollWidth > txt.clientWidth + 1,
            inViewport: r.left >= -1 && r.right <= window.innerWidth + 1,
            text: txt.innerText.trim(), vw: window.innerWidth
        };
    })()`);
    await evalJs(`(() => { const d = Alpine.$data(document.querySelector('.wm-root')); d.flash = ''; return true; })()`);
    await sleep(60);
    return g;
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
        } else if (m.method === 'Network.responseReceived' && m.params.response.status >= 400
            && !/favicon/.test(m.params.response.url)) {
            httpErrors.push(m.params.response.status + ' ' + m.params.response.url);
        }
    };

    await send('Page.enable');
    await send('Runtime.enable');
    await send('Network.enable');
    await send('Log.enable');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });

    /* 干净起点：清空本地存储 + 回到第一册第一关 */
    await send('Page.navigate', { url: PAGE });
    await sleep(600);
    await evalJs(`localStorage.clear()`);
    /* 冒烟期间默认静音：否则整轮测试机器会不停朗读，既吵又拖慢节奏。
       「点英文即朗读」那几条断言由 speakProbeSetup() 临时打开语音后再验。 */
    await evalJs(`localStorage.setItem('word_match_sound_v1', '0')`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(400);

    /* ---------- S1. 初始结构 ---------- */
    console.log('\n— S1 初始结构 / 洗牌 —');
    let b = await board();
    const lv = await levelPairs();

    /* clickAt 后的等待统一走这个，避免误点（页面在 620ms 判定锁内会忽略点击） */
    const clickCard = async (card) => { await clickAt(card.x, card.y); await sleep(180); };
    /* 用真实鼠标把当前关卡从头打通。
       同义关的中文列会有重复文案（例：两个「米」），按文案 find 会反复点到已经置灰的那张，
       `pick()` 对 done 卡直接 return，关卡永远打不完 —— 必须按 leftCard.mean 找「未完成」的那张。 */
    const autoPlayLevel = async (maxSteps) => {
        for (let i = 0; i < (maxSteps || 10); i++) {
            const plan = await evalJs(`(() => {
                const d = Alpine.$data(document.querySelector('.wm-root'));
                const l = d.leftCards.findIndex(c => !c.done);
                if (l < 0) return null;
                const r = d.rightCards.findIndex(c => !c.done && c.text === d.leftCards[l].mean);
                return { l, r };
            })()`);
            if (!plan) return true;
            const b = await board();
            await clickCard(b.en[plan.l]);
            await clickCard(b.zh[plan.r]);
        }
        return false;
    };

    check('两列卡片数量相等且在 3~7 之间（每关 6 对左右）',
        b.en.length === b.zh.length && b.en.length >= 3 && b.en.length <= 7,
        `en=${b.en.length} zh=${b.zh.length}`);
    check('左列是英文、右列是中文',
        b.en.every(c => /^[A-Za-z][A-Za-z\s'.-]*$/.test(c.text))
        && b.zh.every(c => /[\u4e00-\u9fa5]/.test(c.text)));
    check('标题为「选择配对」且显示本关主题', b.title === '选择配对' && !!b.theme, `theme=${b.theme}`);
    check('右上角显示 当前/总关数', /^1\/\d+$/.test(b.counter), `counter=${b.counter}`);
    check('进度条分段数 = 本册关卡数', b.segs.total > 0 && b.segs.total === Number(b.counter.split('/')[1]),
        `segs=${b.segs.total} counter=${b.counter}`);
    check('进度条当前关高亮在最左', b.segs.current === 1 && b.segs.done === 0);

    let lay = await layout();
    check('两列顺序不完全一样（已各自洗牌）', lay.en !== lay.zh);
    check('不出现「同行即同词」的一一对应', lay.aligned <= 1, `aligned=${lay.aligned}`);

    /* S1b. 区域分区：导航 / 说明文字必须在单词区「上方」，且各区域有浅色底 + 边框 */
    const reg = await evalJs(`(() => {
        const box = (sel) => { const el = document.querySelector(sel); if (!el) return null;
            const r = el.getBoundingClientRect();
            return { top: Math.round(r.top), bottom: Math.round(r.bottom) }; };
        const skin = (sel) => { const el = document.querySelector(sel); if (!el) return null;
            const s = getComputedStyle(el);
            return { bg: s.backgroundColor, border: s.borderTopWidth }; };
        return { titlebar: box('.wm-titlebar'), info: box('.wm-infobar'),
                 nav: box('.wm-nav'), footer: box('.wm-footer'), board: box('.wm-board'),
                 infoSkin: skin('.wm-infobar'), boardSkin: skin('.wm-board'),
                 titleSkin: skin('.wm-titlebar') };
    })()`);
    check('上一关/下一关已移到单词区上方', !!reg.nav && !!reg.board && reg.nav.bottom <= reg.board.top,
        `nav.bottom=${reg.nav && reg.nav.bottom} board.top=${reg.board && reg.board.top}`);
    check('说明文字也在单词区上方', !!reg.footer && !!reg.board && reg.footer.bottom <= reg.board.top,
        `footer.bottom=${reg.footer && reg.footer.bottom} board.top=${reg.board && reg.board.top}`);
    check('标题行 → 信息栏 → 棋盘 自上而下依次排列',
        !!reg.titlebar && !!reg.info && reg.titlebar.bottom <= reg.info.top + 1 && reg.info.bottom <= reg.board.top,
        JSON.stringify({ t: reg.titlebar, i: reg.info, b: reg.board }));
    check('信息栏 / 棋盘 / 标题行都有浅色底 + 边框（区域可分辨）',
        [reg.infoSkin, reg.boardSkin, reg.titleSkin].every(s => s && s.border !== '0px'
            && s.bg !== 'rgba(0, 0, 0, 0)' && s.bg !== 'transparent'),
        JSON.stringify([reg.infoSkin, reg.boardSkin, reg.titleSkin]));

    const seen = new Set([lay.sig]);
    let worstAligned = lay.aligned;
    for (let i = 0; i < 12; i++) {                       // 重做本关 12 次，看排列是否每次都变
        await clickInSettings('重新打乱本关');
        await sleep(110);
        const l = await layout();
        seen.add(l.sig);
        worstAligned = Math.max(worstAligned, l.aligned);
    }
    check('重复进入同一关时排列会变化', seen.size >= 5, `不同排列=${seen.size}/13`);
    check('13 次排列都不出现整片一一对应', worstAligned <= 1, `maxAligned=${worstAligned}`);
    await shot('01-desktop-initial');

    /* ---------- S1b. 设置弹层：错题本 / 重做本关 / 朗读 / 重置进度 归到齿轮里 ---------- */
    console.log('\n— S1b 设置弹层 —');
    check('初始状态下设置弹层是收起的', !(await settingsOpen()));
    check('工具栏上不再有散落的「错题本 / 重做本关 / 重置进度」按钮',
        (await evalJs(`Array.from(document.querySelectorAll('.wm-toolbar-actions > button'))
            .filter(e => /错题本|重做本关|重置进度/.test(e.innerText)).length`)) === 0);
    check('齿轮按钮带 aria 状态（可访问）', (await evalJs(
        `document.querySelector('.wm-set-btn')?.getAttribute('aria-haspopup')`)) === 'true');

    await openSettings();
    const pop = await evalJs(`(() => {
        const el = document.querySelector('.wm-set-pop');
        if (!el) return null;
        const items = Array.from(el.querySelectorAll('.wm-set-item')).map(it => {
            const r = it.getBoundingClientRect();
            return {
                title: it.querySelector('.wm-set-title')?.innerText.trim().replace(/\\s+/g, ' ') || '',
                desc: it.querySelector('.wm-set-desc')?.innerText.trim().replace(/\\s+/g, ' ') || '',
                hasIcon: !!it.querySelector('i.wm-set-icon'),
                arrow: !!it.querySelector('.wm-set-arrow'),
                sw: it.querySelector('.wm-set-switch')?.innerText.trim() || '',
                h: Math.round(r.height)
            };
        });
        const r = el.getBoundingClientRect();
        return { items, left: Math.round(r.left), right: Math.round(r.right), vw: window.innerWidth };
    })()`);
    check('弹层里正好 5 个条目', !!pop && pop.items.length === 5,
        pop ? pop.items.map(i => i.title).join(' / ') : 'null');
    check('条目名与功能一一对应（错题本/重做本关/音效与朗读/导出/重置进度）',
        !!pop && ['错题本', '重做本关', '音效与朗读', '导出', '重置进度']
            .every((t, i) => pop.items[i] && pop.items[i].title.indexOf(t) >= 0),
        pop ? pop.items.map(i => i.title).join(' / ') : 'null');
    check('每个条目都带图标', !!pop && pop.items.every(i => i.hasIcon));
    check('每个条目都有文字说明（不是只有标题）', !!pop && pop.items.every(i => i.desc.length >= 4),
        pop ? pop.items.map(i => i.desc).join(' | ') : 'null');
    check('错题本说明里带当前错题数', !!pop && /还没有错题|答错 \d+ 个/.test(pop.items[0].desc),
        pop ? pop.items[0].desc : 'null');
    check('朗读条目带开/关状态胶囊', !!pop && /^(开|关)$/.test(pop.items[2].sw), pop ? pop.items[2].sw : 'null');
    check('每条都 ≥44px 触控高度', !!pop && pop.items.every(i => i.h >= 44),
        pop ? pop.items.map(i => i.h).join(',') : 'null');
    check('弹层不超出视口', !!pop && pop.right <= pop.vw + 1 && pop.left >= 0,
        pop ? `left=${pop.left} right=${pop.right} vw=${pop.vw}` : 'null');
    await shot('01b-desktop-settings-pop');

    /* 朗读开关：点一下切换颜色/文案，且弹层保持打开（能看见状态变化） */
    const soundBefore = await evalJs(`document.querySelector('.wm-set-switch').innerText.trim()`);
    await clickSel('.wm-set-item[title*="朗读"]');
    await sleep(220);
    const soundAfter = await evalJs(`document.querySelector('.wm-set-switch').innerText.trim()`);
    const swCls = await evalJs(`document.querySelector('.wm-set-switch').className`);
    check('点朗读条目可切换开关', soundBefore !== soundAfter, `${soundBefore} -> ${soundAfter}`);
    check('切换后弹层保持打开（能看见状态变化）', await settingsOpen());
    check('开关状态与高亮同步', (soundAfter === '开') === /is-on/.test(swCls), `cls=${swCls}`);
    check('朗读状态写入 localStorage', (await evalJs(`localStorage.getItem('word_match_sound_v1')`))
        === (soundAfter === '开' ? '1' : '0'));
    await clickSel('.wm-set-item[title*="朗读"]');       // 切回原状态，不影响后面的朗读用例
    await sleep(220);

    /* 点弹层外部应自动收起 */
    await clickSel('.wm-set-item[title="重新打乱本关"]');
    await sleep(200);
    check('点了条目之后弹层自动收起', !(await settingsOpen()));
    await openSettings();
    await clickSel('.sc-page-title');
    await sleep(200);
    check('点击弹层外部会自动收起', !(await settingsOpen()));

    /* ---------- S2. 选中态 ---------- */
    console.log('\n— S2 选中 / 取消选中 —');
    b = await board();
    const first = b.en[0];
    const canSpeak = await speakProbeSetup();
    await clearSpoken();
    await clickAt(first.x, first.y);
    await sleep(140);
    b = await board();
    let sel = byText(b.en, first.text);
    check('点击英文卡片后进入选中态（蓝色背景）', sel.sel, `bg=${sel.bg}`);
    const spFirst = await spokenTexts();
    check('点英文卡片那一下就朗读该单词（此时还没点中文）',
        !canSpeak || (spFirst.length === 1 && spFirst[0] === first.text),
        canSpeak ? `spoken=${JSON.stringify(spFirst)}` : '本机无 speechSynthesis，跳过');
    check('选中背景色不同于未选中卡片',
        sel.bg !== byText(b.zh, b.zh[0].text).bg, `sel=${sel.bg} other=${byText(b.zh, b.zh[0].text).bg}`);
    await shot('02-desktop-selected');

    await clickAt(sel.x, sel.y);
    await sleep(120);
    b = await board();
    check('再次点击同一张取消选中', !byText(b.en, first.text).sel);

    /* ---------- S3. 不匹配：后选中的标红，先选中的保留 + 错题本 ---------- */
    console.log('\n— S3 不匹配标红 / 错题本记录 —');
    await clickAt(byText(b.en, first.text).x, byText(b.en, first.text).y);
    await sleep(120);
    b = await board();
    const correctZh = lv.pairs.find(p => p.en === first.text).zh;
    const wrongTarget = b.zh.find(c => c.text !== correctZh);
    await clickAt(wrongTarget.x, wrongTarget.y);
    await sleep(150);
    b = await board();
    const w = byText(b.zh, wrongTarget.text);
    check('不匹配时后选中的中文标红', w.wrong, `bg=${w.bg}`);
    check('先选中的英文保持选中', byText(b.en, first.text).sel);
    check('红色与选中蓝不同色', w.bg !== byText(b.en, first.text).bg, `${w.bg} vs ${byText(b.en, first.text).bg}`);
    await shot('03-desktop-wrong');

    let st = await wrongStore();
    const wrongKeys = Object.keys(st);
    const rec = wrongKeys.map(k => st[k]).find(e => e.en === first.text);
    check('答错的单词自动写入错题本（localStorage）', !!rec, `keys=${wrongKeys.length}`);
    check('错题本记录了正确释义与误选释义',
        rec && rec.zh === correctZh && rec.miss === wrongTarget.text,
        rec ? `${rec.zh} / 误选 ${rec.miss}` : '');
    check('错题本记录了来源（册 / 关 / 主题）',
        rec && rec.bookId === lv.bookId && rec.level === lv.idx && rec.theme === lv.theme,
        rec ? `${rec.bookLabel} 第${rec.level + 1}关 ${rec.theme}` : '');
    check('设置按钮上出现错题数量角标', (await evalJs(
        `document.querySelector('.wm-set-btn .wm-count')?.innerText.trim()`)) === String(wrongKeys.length),
        `badge=${await evalJs(`document.querySelector('.wm-set-btn .wm-count')?.innerText.trim()`)}`);

    await sleep(800);
    b = await board();
    check('标红约 0.6s 后自动清除，可重新选择候选', !byText(b.zh, wrongTarget.text).wrong);
    check('清除标红后英文仍保持选中', byText(b.en, first.text).sel);

    // 同一个词再错一次 -> 次数累计而不是新增一条
    await clickAt(byText(b.zh, wrongTarget.text).x, byText(b.zh, wrongTarget.text).y);
    await sleep(200);
    st = await wrongStore();
    const rec2 = Object.keys(st).map(k => st[k]).find(e => e.en === first.text);
    check('同一个词再次答错只累计次数', rec2 && rec2.count === 2, `count=${rec2 && rec2.count}`);
    await sleep(800);

    /* ---------- S3b. 反向：先选中文，再点错英文 ---------- */
    b = await board();
    await clickAt(byText(b.en, first.text).x, byText(b.en, first.text).y);   // 取消选中
    await sleep(130);
    b = await board();
    const zh2 = b.zh.find(c => !c.done && c.text !== correctZh);
    const en2correct = lv.pairs.find(p => p.zh === zh2.text).en;
    await clickAt(zh2.x, zh2.y);
    await sleep(130);
    b = await board();
    check('先选中文时中文进入选中态', byText(b.zh, zh2.text).sel);
    const wrongEn = b.en.find(c => !c.done && c.text !== en2correct && c.text !== first.text);
    await clickAt(wrongEn.x, wrongEn.y);
    await sleep(170);
    b = await board();
    check('反向不匹配：后选中的英文标红', byText(b.en, wrongEn.text).wrong, `bg=${byText(b.en, wrongEn.text).bg}`);
    check('反向不匹配：先选中的中文保持选中', byText(b.zh, zh2.text).sel);
    await shot('03b-desktop-wrong-reverse');

    st = await wrongStore();
    const recRev = Object.keys(st).map(k => st[k]).find(e => e.en === wrongEn.text);
    check('反向答错也进错题本，且误选记录为左侧选中的中文',
        recRev && recRev.miss === zh2.text, recRev ? `${recRev.en} 误选 ${recRev.miss}` : '');
    check('不同单词各自成条（错题本里有 2 条）', Object.keys(st).length === 2,
        `keys=${Object.keys(st).length} -> ${Object.keys(st).join(',')}`);

    /* ---------- S3c. 同侧改选不判错 ---------- */
    await sleep(800);
    b = await board();
    const zh2b = b.zh.find(c => !c.done && c.text !== zh2.text && c.text !== correctZh);
    await clickAt(zh2b.x, zh2b.y);
    await sleep(170);
    b = await board();
    const anyWrong = [...b.en, ...b.zh].filter(c => c.wrong).length;
    check('同侧改选只是替换选中，不产生错误提示',
        byText(b.zh, zh2b.text).sel && anyWrong === 0, `wrong=${anyWrong}`);

    /* ---------- S3d. 反向配对（先中文后英文）同样成功 ---------- */
    const en2b = lv.pairs.find(p => p.zh === zh2b.text).en;
    await clearSpoken();
    await clickAt(byText(b.en, en2b).x, byText(b.en, en2b).y);
    await sleep(220);
    b = await board();
    check('反向配对（先中文后英文）同样置灰',
        byText(b.en, en2b).done && byText(b.zh, zh2b.text).done);
    const spRev = await spokenTexts();
    check('中文先选时，也是在点英文那一刻朗读（不是点中文时）',
        !canSpeak || (spRev.length === 1 && spRev[0] === en2b),
        canSpeak ? `spoken=${JSON.stringify(spRev)}` : '本机无 speechSynthesis，跳过');

    /* ---------- S4. 匹配成功置灰 ---------- */
    console.log('\n— S4 匹配成功 —');
    b = await board();
    await clearSpoken();
    await clickAt(byText(b.en, first.text).x, byText(b.en, first.text).y);
    await sleep(130);
    const spEn = await spokenTexts();
    b = await board();
    const correctCard = byText(b.zh, correctZh);
    await clickAt(correctCard.x, correctCard.y);
    await sleep(200);
    const spZh = await spokenTexts();
    b = await board();
    check('匹配成功后英文与中文都置灰', byText(b.en, first.text).done && byText(b.zh, correctZh).done);
    check('英文先选中：朗读发生在点英文时',
        !canSpeak || (spEn.length === 1 && spEn[0] === first.text), `spoken=${JSON.stringify(spEn)}`);
    check('点中文完成配对时不重复朗读同一个词',
        !canSpeak || spZh.length === spEn.length, `spoken=${JSON.stringify(spZh)}`);
    check('置灰卡片不可再选中', !byText(b.en, first.text).sel);
    check('置灰后背景为中性灰', byText(b.en, first.text).bg !== 'rgb(216, 237, 253)',
        `bg=${byText(b.en, first.text).bg}`);
    await shot('04-desktop-matched');
    await speakProbeTeardown();          // 朗读用例验完，后面的批量点击不再发声

    /* ---------- S5. 全部配对 -> 自动进下一关 ---------- */
    console.log('\n— S5 全部配对 / 练习进度 —');
    for (const p of lv.pairs) {
        b = await board();
        if (byText(b.en, p.en).done) continue;
        await clickAt(byText(b.en, p.en).x, byText(b.en, p.en).y);
        await sleep(90);
        b = await board();
        await clickAt(byText(b.zh, p.zh).x, byText(b.zh, p.zh).y);
        await sleep(220);
    }
    await sleep(1500);
    b = await board();
    check('全部配对后自动进入下一关', b.counter.startsWith('2/'), `counter=${b.counter}`);
    check('第一关在进度条上标记为已完成', b.segs.done === 1, `done=${b.segs.done}`);
    st = await state();
    check('练习进度写入 word_match_progress_v2',
        (st.practice || '').includes(lv.bookId), `practice=${st.practice}`);
    check('进入新关卡后恢复未选中/未完成状态',
        b.en.every(c => !c.sel && !c.done) && b.zh.every(c => !c.sel && !c.done));
    await shot('05-desktop-level2');

    /* ---------- S6. 册次弹出框 ---------- */
    console.log('\n— S6 册次弹出框 —');
    check('旧的下拉选择器已移除', (await evalJs(`document.querySelectorAll('select.wm-select').length`)) === 0);
    await clickSel('.wm-book-btn');
    await sleep(250);
    const picker = await evalJs(`(() => {
        const back = Array.from(document.querySelectorAll('.wm-modal-backdrop'))
            .find(e => e.getClientRects().length > 0);
        if (!back) return null;
        const list = back.querySelector('.wm-book-list');
        const heads = Array.from(back.querySelectorAll('.wm-book-group-head'));
        const items = Array.from(back.querySelectorAll('.wm-book-item'));
        return { title: back.querySelector('h5').innerText.trim(),
                 items: items.map(e => e.innerText.replace(/\\s+/g, ' ').trim()),
                 groups: heads.map(e => e.innerText.replace(/\\s+/g, ' ').trim()),
                 open: heads.map(e => e.classList.contains('is-open')),
                 carets: heads.filter(e => !!e.querySelector('.wm-book-group-caret')).length,
                 shown: items.filter(e => e.getClientRects().length > 0).length,
                 scrollH: list.scrollHeight, clientH: list.clientHeight,
                 active: items.findIndex(e => e.classList.contains('is-active')) };
    })()`);
    check('点册次按钮弹出选择框', !!picker && /选择年级册次/.test(picker.title), picker ? picker.title : 'null');
    check('弹出框列出全部 24 册（小学 8 + 初中 5 + 高中 11）', picker && picker.items.length === 24,
        picker ? `items=${picker.items.length}` : '');
    check('弹出框标记了当前册', picker && picker.active === 0, picker ? `active=${picker.active}` : '');
    check('册次按学段分组显示（小学 / 初中 / 高中 三个分组）',
        picker && picker.groups.length === 3 && /小学/.test(picker.groups[0]) && /初中/.test(picker.groups[1])
        && /高中/.test(picker.groups[2]),
        picker ? picker.groups.join(' | ') : '');
    check('每个学段标题都是可折叠按钮（带箭头图标）', picker && picker.carets === 3,
        picker ? `carets=${picker.carets}` : '');
    check('默认只展开当前册所在学段（小学），初中 / 高中折叠',
        picker && JSON.stringify(picker.open) === JSON.stringify([true, false, false]),
        picker ? JSON.stringify(picker.open) : '');
    const g0 = await pickerGroups();
    check('折叠生效：初中 / 高中两个组的册次一个都不可见',
        picker && picker.shown === g0[0].shown && g0[1].shown === 0 && g0[2].shown === 0,
        JSON.stringify(g0.map(x => x.shown)));
    check('三个学段标题始终都在可视区内（不会被展开的册次顶出滚动区）',
        g0.every(x => x.headTop >= x.listTop - 1 && x.headBottom <= x.listBottom + 1),
        JSON.stringify(g0.map(x => `${x.headTop}~${x.headBottom}/${x.listTop}~${x.listBottom}`)));
    check('展开组的页码数正确（小学 8 / 初中 5 / 高中 11）',
        g0[0].total === 8 && g0[1].total === 5 && g0[2].total === 11,
        JSON.stringify(g0.map(x => x.total)));
    check('折叠状态箭头未旋转（transform=none）', g0[1].caret === 'none', `caret=${g0[1].caret}`);
    check('初中 5 册在列（七年级上册 ~ 九年级全一册）',
        picker && /七年级上册/.test(picker.items[8] || '') && /九年级全一册/.test(picker.items[12] || ''),
        picker ? `[8]=${(picker.items[8] || '').slice(0, 12)} [12]=${(picker.items[12] || '').slice(0, 12)}` : '');
    check('高中 11 册在列（必修1 ~ 选修11）',
        picker && /必修1/.test(picker.items[13] || '') && /选修11/.test(picker.items[23] || ''),
        picker ? `[13]=${(picker.items[13] || '').slice(0, 12)} [23]=${(picker.items[23] || '').slice(0, 12)}` : '');
    check('册次列表限高（不撑破弹窗）', picker && picker.clientH <= 561,
        picker ? `scrollH=${picker.scrollH} clientH=${picker.clientH}` : '');
    await shot('08-desktop-book-picker');

    /* 折叠交互：展开初中 -> 初中组的册次出现且箭头旋转；再点一次收起 */
    await clickStageHead('初中');
    const g1 = await pickerGroups();
    check('点学段标题可展开（初中组的册次出现，高中仍折叠）',
        g1[1].open && g1[1].shown > 0 && g1[2].shown === 0,
        JSON.stringify(g1.map(x => `${x.open ? 'open' : 'close'}:${x.shown}`)));
    check('手风琴：同时只展开一个学段（展开初中后小学自动收起）',
        JSON.stringify(g1.map(x => x.open)) === JSON.stringify([false, true, false]),
        JSON.stringify(g1.map(x => x.open)));
    check('展开后箭头旋转 90°（matrix 第二行 -1）',
        /matrix\(0,\s*1,\s*-1,\s*0/.test(g1[1].caret), `caret=${g1[1].caret}`);
    check('展开后三个学段标题仍全部可见',
        g1.every(x => x.headTop >= x.listTop - 1 && x.headBottom <= x.listBottom + 1),
        JSON.stringify(g1.map(x => `${x.headTop}~${x.headBottom}/${x.listTop}~${x.listBottom}`)));
    await shot('08b-desktop-book-picker-expanded');

    await clickStageHead('初中');
    const g2 = await pickerGroups();
    check('再点一次可收起（初中组的册次再次隐藏，箭头复位）',
        !g2[1].open && g2[1].shown === 0 && g2[1].caret === 'none',
        JSON.stringify({ open: g2[1].open, shown: g2[1].shown, caret: g2[1].caret }));

    // 小学册：点六年级下册（小学组本来就展开着）
    await clickBookItem('六年级下册');
    await sleep(400);
    b = await board();
    let bookLabel = await evalJs(`document.querySelector('.wm-book-btn').innerText.trim()`);
    check('选中另一册后按钮回显册名', /六年级下册/.test(bookLabel), `btn=${bookLabel}`);
    check('切换册次后关卡与卡片同步刷新',
        b.counter.endsWith('/' + b.segs.total) && b.segs.done === 0 && b.en.length >= 3,
        `counter=${b.counter} segs=${b.segs.total}`);
    check('切换册次后弹出框已关闭', (await evalJs(
        `Array.from(document.querySelectorAll('.wm-modal-backdrop')).filter(e => e.getClientRects().length > 0).length`)) === 0);

    // 初中册：需先展开「初中」学段
    await clickSel('.wm-book-btn');
    await sleep(250);
    await openStage('初中');
    await clickBookItem('九年级全一册');
    await sleep(400);
    b = await board();
    bookLabel = await evalJs(`document.querySelector('.wm-book-btn').innerText.trim()`);
    check('可以切到初中册（九年级全一册）', /九年级全一册/.test(bookLabel), `btn=${bookLabel}`);
    check('初中册的关卡数据完整（每关 3~7 对）',
        b.en.length >= 3 && b.en.length <= 7 && b.zh.length === b.en.length,
        `en=${b.en.length} zh=${b.zh.length}`);
    const jrThemes = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return d.levels.length + '|' + d.levels.map(l => l.theme).slice(0, 3).join(',');
    })()`);
    check('初中册按主题切关（关卡数与主题可见）', /^\d+\|.+/.test(jrThemes), jrThemes);

    // 高中册：需先展开「高中学段」，验证新学段可用且按课本单元成关
    await clickSel('.wm-book-btn');
    await sleep(250);
    await openStage('高中');
    await clickBookItem('必修1');
    await sleep(450);
    b = await board();
    bookLabel = await evalJs(`document.querySelector('.wm-book-btn').innerText.trim()`);
    check('可以切到高中册（必修1）', /必修1/.test(bookLabel), `btn=${bookLabel}`);
    check('高中册的关卡数据完整（每关 3~7 对）',
        b.en.length >= 3 && b.en.length <= 7 && b.zh.length === b.en.length,
        `en=${b.en.length} zh=${b.zh.length}`);
    const hsInfo = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const meta = d.books.filter(x => x.id === d.bookId)[0];
        return { levels: d.levels.length, theme: d.currentLevel ? d.currentLevel.theme : '',
                 bookWord: meta.wordCount, declared: meta.levelCount };
    })()`);
    check('高中册主题即课本单元（Unit N）', /^Unit [1-5]$/.test(hsInfo.theme || ''), `theme=${hsInfo.theme}`);
    // 词量是源清单口径（稳定）；关数随「每关几对」变化，故只与引导数据自洽校验，不写死
    check('必修1 收录 311 词且关数与引导数据自洽',
        hsInfo.bookWord === 311 && hsInfo.levels === hsInfo.declared && hsInfo.levels > 0,
        `words=${hsInfo.bookWord} levels=${hsInfo.levels} declared=${hsInfo.declared}`);
    check('换到高中册后学段分组自动展开高中',
        (await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            return d.stageOpen['高中'] === true;
        })()`)) === true);

    // 切回六年级下册第 1 关：后续用例（上下关、自动学习）沿用这个基准状态
    await clickSel('.wm-book-btn');
    await sleep(250);
    await clickBookItem('六年级下册');
    await sleep(400);
    b = await board();
    check('切回六年级下册并停在第 1 关（后续用例基准）',
        /六年级下册/.test(await evalJs(`document.querySelector('.wm-book-btn').innerText.trim()`)) &&
        b.counter.startsWith('1/'),
        b.counter);

    /* ---------- S7. 上一关 / 下一关 ---------- */
    console.log('\n— S7 上一关 / 下一关 —');
    const navTxt = await evalJs(`Array.from(document.querySelectorAll('.wm-nav button')).map(b =>
        b.innerText.trim() + (b.disabled ? '(禁用)' : ''))`);
    check('底部有上一关/下一关按钮', navTxt.length === 2 && /上一关/.test(navTxt[0]) && /下一关/.test(navTxt[1]),
        navTxt.join(' | '));
    check('第 1 关时「上一关」禁用', /上一关\(禁用\)/.test(navTxt[0]), navTxt[0]);

    await clickSel('.wm-nav button', 1);          // 下一关
    await sleep(220);
    b = await board();
    check('点「下一关」进入第 2 关', b.counter.startsWith('2/'), `counter=${b.counter}`);
    await clickSel('.wm-nav button', 0);          // 上一关
    await sleep(220);
    b = await board();
    check('点「上一关」回到第 1 关', b.counter.startsWith('1/'), `counter=${b.counter}`);
    const enabledPrev = await evalJs(`document.querySelectorAll('.wm-nav button')[0].disabled`);
    check('第 1 关时上一关按钮 disabled 属性为真', enabledPrev === true);

    let guard = 0;                                 // 一路点到最后一关
    while (guard++ < 40) {
        const disabled = await evalJs(`document.querySelectorAll('.wm-nav button')[1].disabled`);
        if (disabled) break;
        await clickSel('.wm-nav button', 1);
        await sleep(90);
    }
    b = await board();
    const lastCounter = b.counter;
    check('可以一路切换到最后一关', lastCounter === `${b.segs.total}/${b.segs.total}`,
        `counter=${lastCounter} segs=${b.segs.total}`);
    check('最后一关时「下一关」禁用', (await evalJs(
        `document.querySelectorAll('.wm-nav button')[1].disabled`)) === true);
    await clickSel('.wm-nav button', 0);
    await sleep(200);
    b = await board();
    check('最后一关可退回上一关', b.counter === `${b.segs.total - 1}/${b.segs.total}`, `counter=${b.counter}`);

    /* ---------- S7b. 本册最后一关：「下一关」->「下一册」 ----------
       旧版在最后一关打完、点掉通关窗的「继续看看」之后就彻底卡死了：
       canNext 在最后一关恒为 false，「下一关」永远禁用，也没有换册的入口。 */
    console.log('\n— S7b 本册最后一关 → 下一册 —');
    // 只留最后一关没打：载入时 applyBook(restore=true) 会自动落位到这一关
    const seed = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const done = []; for (let i = 0; i < d.levels.length - 1; i++) done.push(i);
        localStorage.setItem('word_match_progress_v2', JSON.stringify({ [d.bookId]: done }));
        return { book: d.bookId, levels: d.levels.length };
    })()`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(300);
    b = await board();
    check('只剩最后一关时自动落位到最后一关',
        b.counter === `${b.segs.total}/${b.segs.total}` && b.segs.done === b.segs.total - 1,
        `${seed.book} counter=${b.counter} done=${b.segs.done}/${b.segs.total}`);

    const navBtnTxt = () => evalJs(`(() => {
        const bs = Array.from(document.querySelectorAll('.wm-nav button'));
        return { prev: bs[0].innerText.trim(), next: bs[1].innerText.trim(),
                 nextDisabled: bs[1].disabled,
                 hasBtnCls: bs[1].classList.contains('btn') && bs[1].classList.contains('btn-sm'),
                 primary: bs[1].classList.contains('btn-primary') };
    })()`);

    let nb = await navBtnTxt();
    check('最后一关：按钮文案变成「下一册」（不再是「下一关」）',
        /下一册/.test(nb.next) && !/下一关/.test(nb.next), nb.next);
    check('最后一关：本关没打完前「下一册」禁用（先把这一关收掉）',
        nb.nextDisabled === true, `disabled=${nb.nextDisabled}`);
    check('动态 class 与静态 class 正确合并（btn / btn-sm 没被 :class 顶掉）',
        nb.hasBtnCls, `hasBtnCls=${nb.hasBtnCls}`);
    await shot('20-desktop-last-level');

    // 打掉这最后一关（本册最后一关可能是同义关、中文列有重复文案，交给 autoPlayLevel 按释义找）
    const lastLv = await levelPairs();
    const played = await autoPlayLevel(lastLv.pairs.length + 2);
    await sleep(800);
    b = await board();
    check('本册最后一关能全部配对完成', played === true,
        `played=${played} pairs=${lastLv.pairs.length}`);
    check('打完本册最后一关弹出通关窗', b.finishedModal === true, `modal=${b.finishedModal}`);
    const finishTxt = await evalJs(`(() => {
        const back = Array.from(document.querySelectorAll('.wm-modal-backdrop'))
            .find(e => e.getClientRects().length > 0);
        return back ? back.innerText.replace(/\\s+/g, ' ').trim() : '';
    })()`);
    check('通关窗保留「本册全部通关」与「下一册」入口',
        /本册全部通关/.test(finishTxt) && /下一册/.test(finishTxt), finishTxt.slice(0, 60));
    await shot('21-desktop-book-finished');

    // 点「继续看看」关掉通关窗 —— 旧版就是这一步之后没有出路
    await clickSel('.wm-modal-backdrop button', 0, '继续看看');
    await sleep(350);
    b = await board();
    check('点「继续看看」后通关窗关闭', b.finishedModal === false, `modal=${b.finishedModal}`);
    nb = await navBtnTxt();
    check('关掉通关窗后「下一册」仍可点（旧版此处被卡死）',
        /下一册/.test(nb.next) && nb.nextDisabled === false,
        `${nb.next} disabled=${nb.nextDisabled}`);
    check('本册收尾时「下一册」高亮为主按钮（btn-primary）', nb.primary === true, `primary=${nb.primary}`);

    await clickSel('.wm-nav button', 1);
    await sleep(650);
    b = await board();
    const afterBook = await evalJs(`Alpine.$data(document.querySelector('.wm-root')).bookId`);
    check('点「下一册」切到下一册第 1 关',
        afterBook !== seed.book && b.counter.startsWith('1/'),
        `${seed.book} -> ${afterBook} counter=${b.counter}`);
    nb = await navBtnTxt();
    check('换册后按钮复位为「下一关」并取消高亮',
        /下一关/.test(nb.next) && nb.primary === false, `${nb.next} primary=${nb.primary}`);
    await shot('22-desktop-next-book-level1');

    // 复原基准状态：册切回六年级下册 + 清掉练习进度（S8 起的用例沿用这个基准）
    // 注意「下一册」那一步已经把 BOOK_KEY 改成下一册了，不清回来基准就变了
    await evalJs(`localStorage.setItem('word_match_progress_v2', '{}');
                  localStorage.setItem('word_match_book_v1', ${JSON.stringify(seed.book)});`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(300);
    b = await board();
    check('S7b 收尾：基准状态已复原（六年级下册第 1 关，0 关已完成）',
        (await evalJs(`document.querySelector('.wm-book-btn').innerText.trim()`)).indexOf('六年级下册') >= 0
        && b.counter.startsWith('1/') && b.segs.done === 0,
        `counter=${b.counter} done=${b.segs.done}`);

    /* ---------- S8. 错题本弹窗（入口在设置弹层里） ---------- */
    console.log('\n— S8 错题本弹窗 —');
    await clickInSettings('查看答错的单词');
    await sleep(250);
    const wb = await wrongStore();
    const wbCount = Object.keys(wb).length;
    const modal = await evalJs(`(() => {
        const back = Array.from(document.querySelectorAll('.wm-modal-backdrop'))
            .find(e => e.getClientRects().length > 0);
        if (!back) return null;
        const items = Array.from(back.querySelectorAll('.wm-wrong-item'));
        return { head: back.querySelector('h5').innerText.trim(),
                 total: back.querySelector('.wm-wrong-total').innerText.trim(),
                 items: items.map(e => e.innerText.replace(/\\s+/g, ' ').trim()),
                 hasSpeak: !!back.querySelector('.wm-wrong-item .wm-speak') };
    })()`);
    check('错题本弹窗可打开', !!modal && /错题本/.test(modal.head), modal ? modal.head : 'null');
    check('错题本条目数与本地记录一致', modal && modal.items.length === wbCount,
        modal ? `items=${modal.items.length} store=${wbCount}` : '');
    check('条目显示「正确 / 误选 / 来源 / 次数」',
        modal && modal.items.every(t => /正确/.test(t) && /误选/.test(t) && /次/.test(t)),
        modal ? modal.items[0] : '');
    check('错题本条目带朗读按钮', !!(modal && modal.hasSpeak));
    await shot('09-desktop-wrong-book');

    // 去练：跳到该错题所在的册与关
    const firstWrong = Object.values(wb).sort((a, b) => (b.ts || 0) - (a.ts || 0))[0];
    await clickSel('.wm-wrong-item button.btn-outline-primary');
    await sleep(400);
    const afterGoto = await state();
    check('点「去练」跳到该错题所在册与关卡',
        afterGoto.book === firstWrong.bookId
        && afterGoto.counter.startsWith(`${firstWrong.level + 1}/`),
        `book=${afterGoto.book} counter=${afterGoto.counter} 期望=${firstWrong.bookId} 第${firstWrong.level + 1}关`);
    check('点「去练」后弹窗关闭', !(await evalJs(
        `Array.from(document.querySelectorAll('.wm-modal-backdrop')).some(e => e.getClientRects().length > 0)`)));

    // 单条删除
    await clickInSettings('查看答错的单词');
    await sleep(220);
    await clickSel('.wm-wrong-item button.btn-outline-secondary');
    await sleep(250);
    const wbAfterDel = Object.keys(await wrongStore()).length;
    check('可以单条删除错题', wbAfterDel === wbCount - 1, `before=${wbCount} after=${wbAfterDel}`);

    // 清空（走 SC.confirm）
    await clickSel('.wm-modal-backdrop:has(.wm-wrong-item) button.btn-outline-danger');
    await sleep(250);
    const confirmShown = await evalJs(
        `!!document.querySelector('.sc-modal-backdrop [data-act="ok"]')`);
    check('清空错题本有二次确认', confirmShown);
    await clickSel('.sc-modal-backdrop [data-act="ok"]');
    await sleep(400);
    const wbCleared = Object.keys(await wrongStore()).length;
    check('确认后错题本被清空', wbCleared === 0, `left=${wbCleared}`);
    check('清空后弹窗显示空态文案', /还没有错题/.test(await evalJs(
        `document.querySelector('.wm-wrong-empty')?.innerText.trim() || ''`)));
    check('清空后角标隐藏', (await evalJs(
        `document.querySelector('.wm-set-btn .wm-count')?.getClientRects().length || 0`)) === 0);
    await evalJs(`(() => {
        const back = Array.from(document.querySelectorAll('.wm-modal-backdrop'))
            .find(e => e.getClientRects().length > 0);
        if (back) back.querySelector('.btn-outline-secondary')?.click();
        return true;
    })()`);
    await sleep(250);
    check('关闭错题本后没有残留遮罩', !(await evalJs(
        `Array.from(document.querySelectorAll('.wm-modal-backdrop')).some(e => e.getClientRects().length > 0)`)));

    /* ---------- S9. 自动学习模式 ---------- */
    console.log('\n— S9 自动学习（真实节奏：1s 间隔 / 2s 思考） —');
    await evalJs(`localStorage.setItem('word_match_book_v1', 'pep-3-1');
                  localStorage.setItem('word_match_progress_v2', JSON.stringify({'pep-3-1': [0, 1]}));
                  localStorage.removeItem('word_match_auto_progress_v2');`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(300);

    const practiceBefore = (await state()).practice;
    await clickSel('.wm-toolbar-actions button[title*="自动"]');
    await sleep(300);
    st = await state();
    check('点【自动学习】后开启并显示状态条', st.autoBar && st.title === '自动学习', `title=${st.title}`);
    check('自动学习从第 1 关开始', st.counter.startsWith('1/'), `counter=${st.counter}`);
    check('自动学习进度条用独立配色', st.autoProgressBar);
    shot('10-desktop-auto-start');
    await sleep(400);
    st = await state();
    check('开启后 0.7s 内还没有动作（每步间隔 1 秒）',
        st.matched === 0 && st.sel === 0, `matched=${st.matched} sel=${st.sel}`);

    // 立刻暂停，验证「暂停期间不推进」且「自动模式下手动点击无效」
    await clickSel('.wm-auto-bar .wm-auto-btn', 0);
    await sleep(250);
    st = await state();
    check('可以暂停自动学习', st.autoPaused, `tip=${st.autoTip}`);
    const pausedMatched = st.matched;
    await clickSel('.wm-card', 0);
    await sleep(200);
    const manualInAuto = await evalJs(`document.querySelectorAll('.wm-card.is-sel').length`);
    check('自动学习模式下手动点击不生效', manualInAuto === 0, `sel=${manualInAuto}`);
    await sleep(1200);
    st = await state();
    check('暂停期间进度不推进', st.matched === pausedMatched, `${pausedMatched} -> ${st.matched}`);

    await clickSel('.wm-auto-bar .wm-auto-btn', 0);        // 继续
    await sleep(200);
    const resumed = await waitFor('第一个配对', async () => (await state()).matched >= 1, 6000, 100);
    check('点「继续」后恢复自动配对', resumed, `matched=${(await state()).matched}`);
    const tMatch = Date.now();

    // 配对完成 -> 思考 2s -> 间隔 1s -> 下一个单词被选中（合计约 3s）
    const nextPicked = await waitFor('下一个单词被选中', async () => (await state()).sel === 1, 14000, 150);
    const gapMs = Date.now() - tMatch;
    check('配对后有 2 秒思考 + 1 秒间隔才进入下一个单词',
        nextPicked && gapMs >= 2700 && gapMs <= 4600, `间隔=${gapMs}ms`);

    await clickSel('.wm-auto-bar .wm-auto-btn', 0);        // 再暂停，防干扰
    await sleep(150);
    await clickSel('.wm-auto-bar .wm-auto-btn.is-exit');   // 退出
    await sleep(500);
    st = await state();
    check('可以随时退出自动学习', !st.autoBar && st.title === '选择配对', `title=${st.title}`);
    check('退出后恢复未选中状态，可手动操作', st.sel === 0 && st.matched === 0,
        `sel=${st.sel} matched=${st.matched}`);
    check('退出后进度条回到练习进度（2 关已完成）', st.segDone === 2, `segDone=${st.segDone}`);
    await clickSel('.wm-card', 0);
    await sleep(200);
    check('退出后手动点击立即生效', (await state()).sel === 1);

    /* --- 快速节奏：完整跑通一关 + 过关节奏 + 学习/练习进度互不共享 --- */
    console.log('\n— S9b 自动学习（快速节奏：跑完整关 + 独立进度） —');
    await evalJs(`localStorage.setItem('word_match_progress_v2', JSON.stringify({'pep-3-1': [0, 1]}));
                  localStorage.removeItem('word_match_auto_progress_v2');`);
    await send('Page.navigate', { url: PAGE + '?wmStep=250&wmThink=300&wmGap=1200' });
    await waitCards();
    await sleep(300);
    const practiceBefore2 = (await state()).practice;

    await clickSel('.wm-toolbar-actions button[title*="自动"]');
    const canSpeakAuto = await speakProbeSetup();   // 自动学习同样走 speechSynthesis.speak
    let sawGapTip = false, sawGapFlash = false, sawNextWordTip = false, sawListenTip = 0;
    // 硬判据：英文卡片刚被选中（还没配对）时，该单词必须已经进入朗读
    let lastSelWord = null, spokenOnPick = 0, speakOrderOk = true;
    const t0 = Date.now();
    while (Date.now() - t0 < 20000) {
        const s = await state();
        if (/下一关/.test(s.autoTip)) sawGapTip = true;
        if (/即将进入下一关/.test(s.flash)) sawGapFlash = true;
        if (/找出/.test(s.autoTip)) sawNextWordTip = true;
        if (/听读音/.test(s.autoTip)) sawListenTip++;
        const enSel = await evalJs(`(() => {
            const e = document.querySelector('.wm-col-en .wm-card.is-sel');
            return e ? e.innerText.trim() : '';
        })()`);
        if (enSel && enSel !== lastSelWord) {
            lastSelWord = enSel;
            const spoken = await spokenTexts();
            if (spoken.includes(enSel)) spokenOnPick++; else speakOrderOk = false;
        } else if (!enSel) {
            lastSelWord = null;          // 配对成功后清零，下一个单词重新校验
        }
        if (/^2\//.test(s.counter || '')) break;
        await sleep(70);
    }
    b = await board();
    check('自动学习可以自动完成一整关并进入下一关', b.counter.startsWith('2/'), `counter=${b.counter}`);
    check('自动流程有「看英文 / 找中文」的步骤提示', sawNextWordTip);
    check('自动学习也是「选中英文即朗读」（朗读早于配对，非点中文之后）',
        !canSpeakAuto || (spokenOnPick >= 3 && speakOrderOk),
        canSpeakAuto ? `命中${spokenOnPick}个单词 全部早于配对=${speakOrderOk}` : '本机无 speechSynthesis，跳过');
    check('自动学习有「听读音」步骤提示', sawListenTip >= 1, `出现${sawListenTip}次`);
    check('本关完成时提示「即将进入下一关」', sawGapTip && sawGapFlash,
        `tip=${sawGapTip} flash=${sawGapFlash}`);
    await speakProbeTeardown();          // 自动学习的朗读也验完了，恢复静音
    await clickSel('.wm-auto-bar .wm-auto-btn', 0);        // 暂停，冻结现场
    await sleep(200);
    await shot('11-desktop-auto-gap');

    // 过关提示不能压住单词（此前是屏幕中央的浮层，会盖住卡片）
    const fgDesk = await flashGeom('14-desktop-flash-outside');
    check('过关提示完全落在单词区之外（桌面端）',
        fgDesk.visible && !fgDesk.overBoard && fgDesk.overCards === 0 && fgDesk.bottom <= fgDesk.vh,
        JSON.stringify(fgDesk));
    check('过关提示贴在单词区上方（不再甩到视口底部，桌面端根本看不见）',
        fgDesk.aboveBoard && fgDesk.onTitlebar,
        `flash=${fgDesk.top}~${fgDesk.bottom} titlebar=${fgDesk.titlebarTop}~${fgDesk.titlebarBottom} boardTop=${fgDesk.boardTop}`);

    st = await state();
    const autoProg = JSON.parse(st.autoProg || '{}');
    check('自动学习进度独立记录在 word_match_auto_progress_v2',
        Array.isArray(autoProg['pep-3-1']) && autoProg['pep-3-1'][0] === 0,
        `autoProg=${st.autoProg}`);
    check('自动学习不写练习进度', st.practice === practiceBefore2, `${practiceBefore2} -> ${st.practice}`);
    check('自动学习时进度条显示学习进度（1 关）', st.segDone === 1, `segDone=${st.segDone}`);

    await clickSel('.wm-auto-bar .wm-auto-btn.is-exit');
    await sleep(400);
    st = await state();
    check('退出学习后进度条切回练习进度（2 关）', st.segDone === 2, `segDone=${st.segDone}`);
    check('退出学习后练习进度未被污染', st.practice === practiceBefore2, `practice=${st.practice}`);
    check('退出学习后卡片全部复位', st.matched === 0 && st.sel === 0);

    /* ---------- S9c. 一册学完自动进入下一册（含 5 秒跳转提示） ---------- */
    console.log('\n— S9c 自动学习：一册学完自动续到下一册 —');
    // 三年级上册（pep-3-1）是 24 册里最短的一册（现 13 关）；把节奏压到最快，整册约 30 秒跑完
    await evalJs(`localStorage.setItem('word_match_book_v1', 'pep-3-1');
                  localStorage.removeItem('word_match_auto_progress_v2');
                  localStorage.removeItem('word_match_progress_v2');`);
    await send('Page.navigate', { url: PAGE + '?wmStep=0&wmThink=0&wmGap=150&wmBookGap=2500' });
    await waitCards();
    await sleep(300);

    const startBook = (await autoSnapshot()).book;
    await clickSel('.wm-toolbar-actions button[title*="自动"]');
    const s9cUp = await waitFor('自动学习状态条', async () => (await autoSnapshot()).autoBar, 5000, 100);
    check('S9c 自动学习已开启', s9cUp);
    let bookGapTip = '', bookGapFlash = '', bookGapCount = '', barH = 0, barHStable = true;
    let switchTip = '';
    const t9c = Date.now();
    while (Date.now() - t9c < 70000) {
        const s = await autoSnapshot();
        if (!s.autoBar) break;
        if (/秒后进入下一册/.test(s.tip)) {
            bookGapTip = s.tip;
            if (s.count) bookGapCount = s.count;
            if (s.flash) bookGapFlash = s.flash;
        }
        if (s.barH) { if (!barH) barH = s.barH; else if (s.barH !== barH) barHStable = false; }
        if (s.book !== startBook) { switchTip = s.tip; break; }
        await sleep(60);
    }
    const afterSwitch = await autoSnapshot();
    check('一册学完会自动跳到下一册（无需手动切换）',
        afterSwitch.book === 'pep-3-2', `${startBook} -> ${afterSwitch.book}`);
    check('跳册后从新一册第 1 关开始', /^1\//.test(afterSwitch.counter), `counter=${afterSwitch.counter}`);
    check('跳册后有「N 秒后进入下一册」提示', /秒后进入下一册/.test(bookGapTip), bookGapTip);
    check('跳册提示带倒计时', /^\d+s$/.test(bookGapCount), `count=${bookGapCount}`);
    check('跳册前后有过关提示条（写明去向）', /即将进入/.test(bookGapFlash), bookGapFlash);
    check('跳册后仍在自动学习，并继续配对新一册的单词',
        await waitFor('新册开始配对', async () => (await autoSnapshot()).matched >= 1, 8000, 120),
        `tip=${switchTip}`);
    check('全程状态条高度不变（提示不会把状态条撑高）', barHStable && barH > 0,
        `barH=${barH} stable=${barHStable}`);
    await shot('15-desktop-auto-book-switch');

    // 已学完的一册进度要落在学习进度里（整册全记），且不污染练习进度
    const afterProg = await evalJs(`(() => ({
        auto: localStorage.getItem('word_match_auto_progress_v2'),
        practice: localStorage.getItem('word_match_progress_v2'),
        /* 该册关卡数从引导数据现取：每关词数一调整关数就变，写死会假报失败 */
        expect: (window.__WORD_MATCH_DATA__.levels['pep-3-1'] || []).length
    }))()`);
    const autoMap = JSON.parse(afterProg.auto || '{}');
    check('学完的那一册在自动学习进度里记满整册关卡',
        afterProg.expect > 0 && (autoMap['pep-3-1'] || []).length === afterProg.expect,
        `auto=${afterProg.auto} expect=${afterProg.expect}`);
    check('跨册学习不写练习进度', afterProg.practice === null, `practice=${afterProg.practice}`);

    await clickSel('.wm-auto-bar .wm-auto-btn.is-exit');
    await sleep(400);

    /* ---------- S9d. 同义关：同一个中文对应多个词，选哪个都算对 ---------- */
    console.log('\n— S9d 同义关（释义相同，选哪个都对）—');
    await evalJs(`localStorage.setItem('word_match_book_v1', 'pep-7-1');
                  localStorage.removeItem('word_match_progress_v2');
                  localStorage.removeItem('word_match_auto_progress_v2');`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(300);

    const synPick = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const info = [];
        d.levels.forEach((l, i) => {
            if ((l.theme || '').indexOf('同义表达：') !== 0) return;
            const zmap = {};
            l.pairs.forEach(p => { (zmap[p.zh] = zmap[p.zh] || []).push(p); });
            const groups = Object.keys(zmap);
            const sizes = groups.map(z => zmap[z].length).filter(n => n > 1);
            info.push({ i, theme: l.theme, pairs: l.pairs.length,
                        maxGroup: sizes.length ? Math.max.apply(null, sizes) : 0,
                        allSame: groups.length === 1 });
        });
        return { total: info.length,
                 two: info.find(x => x.maxGroup === 2 && !x.allSame) || null,
                 degenerate: info.find(x => x.allSame) || null };
    })()`);
    check('本册存在同义表达关卡', synPick.total > 0, `n=${synPick.total}`);
    check('存在「同义组恰好 2 词」的同义关', !!synPick.two, JSON.stringify(synPick.two));
    check('存在「整关释义全相同」的极端同义关', !!synPick.degenerate, JSON.stringify(synPick.degenerate));
    check('同义表达主题名列出本关释义', /^同义表达：.+/.test((synPick.two || {}).theme || ''),
        (synPick.two || {}).theme);

    /* clickCard / autoPlayLevel 已在 main() 开头（S1 之前）定义，这里直接复用 */

    /* --- S9d-1. 同义组 2 词：两张同文案中文卡，配哪张都算对 --- */
    if (synPick.two) {
        const syn = await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            d.levelIndex = ${synPick.two.i};
            d.loadLevel();
            const lv = d.currentLevel;
            const zmap = {};
            lv.pairs.forEach(p => { (zmap[p.zh] = zmap[p.zh] || []).push(p); });
            const zh = Object.keys(zmap).find(z => zmap[z].length === 2);
            const a = zmap[zh][0], b = zmap[zh][1];
            const lIdx = d.leftCards.findIndex(c => c.key === a.key);
            const rIdx = d.rightCards.findIndex(c => c.text === a.zh && c.key !== a.key);
            const lA = d.leftCards[lIdx];
            const diffZh = d.rightCards.find(c => c.text !== lA.mean);
            return { theme: lv.theme, zh, enA: a.en, enB: b.en, pairs: lv.pairs.length, lIdx, rIdx,
                     sameAsB: d.sameMeaning(lA, d.rightCards.find(c => c.key === b.key)),
                     diff: diffZh ? d.sameMeaning(lA, diffZh) : null };
        })()`);
        check('判定按释义：A 的英文配 B 的中文卡（key 不同）判为对', syn.sameAsB === true,
            `sameAsB=${syn.sameAsB} (${syn.enA}/${syn.enB} = ${syn.zh})`);
        check('释义不同仍然判错（同义关不会变成「怎么点都对」）', syn.diff === false,
            `diff=${syn.diff}`);

        const sb1 = await board();
        check('关卡标题显示「同义表达」主题', /同义表达/.test(sb1.theme || ''), `${sb1.theme}`);
        check('中文列出现两张相同文案', sb1.zh.filter(c => c.text === syn.zh).length === 2,
            sb1.zh.map(c => c.text).join('|'));
        check('同义关仍是 3~7 对', sb1.cardCount === syn.pairs * 2 && syn.pairs >= 3 && syn.pairs <= 7,
            `pairs=${syn.pairs}`);

        await clickCard(sb1.en[syn.lIdx]);
        let zhSame = (await board()).zh.filter(c => c.text === syn.zh && !c.done);
        await clickCard(zhSame[0]);
        let sb2 = await board();
        check('同义关配对成功（不因 key 不同而标红）',
            sb2.en[syn.lIdx].done && !sb2.en.some(c => c.wrong) && !sb2.zh.some(c => c.wrong),
            `${syn.enA} -> ${syn.zh}`);
        await shot('18-desktop-synonym-level');

        // 剩下的那张同文案卡必须配得上「另一个同义词」——两张各点一次，必有一次是「别人的 key」
        await clickCard(sb2.en.find(c => c.text === syn.enB));
        zhSame = (await board()).zh.filter(c => c.text === syn.zh && !c.done);
        check('两张同文案中文卡各被点走一次', zhSame.length === 1, `remain=${zhSame.length}`);
        await clickCard(zhSame[0]);
        sb2 = await board();
        check('两个同义词各自都能配上同一文案（都判对）',
            sb2.zh.filter(c => c.text === syn.zh && c.done).length === 2,
            `done=${sb2.zh.filter(c => c.text === syn.zh && c.done).length}/2`);

        await autoPlayLevel(10);
        const advanced = await waitFor('同义关自动进入下一关', async () => {
            const b = await board();
            return b.counter !== sb1.counter;
        }, 8000, 150);
        check('打完同义关自动进入下一关', advanced, `${sb1.counter} -> ${(await board()).counter}`);
        const synProg = JSON.parse((await evalJs(`localStorage.getItem('word_match_progress_v2')`)) || '{}');
        check('同义关完成会记入练习进度', (synProg['pep-7-1'] || []).indexOf(synPick.two.i) >= 0,
            `prog=${JSON.stringify(synProg)}`);
    }

    /* --- S9d-2. 极端情况：整关释义全相同（中文列 4 张一样）也要能打完 --- */
    if (synPick.degenerate) {
        const dgi = synPick.degenerate.i;
        await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            d.levelIndex = ${dgi}; d.loadLevel(); return true;
        })()`);
        await sleep(250);
        const bd0 = await board();
        const zhAll = bd0.zh.map(c => c.text);
        check('极端同义关的中文列全是同一文案', zhAll.every(t => t === zhAll[0]),
            `${bd0.zh.length} 张：${zhAll.join('|')}`);
        const played = await autoPlayLevel(10);
        const bd1 = await board();
        check('极端同义关可以全部配对完成（不会卡住）',
            played && !bd1.en.some(c => !c.done) && !bd1.zh.some(c => !c.done),
            `left 未完成=${bd1.en.filter(c => !c.done).length} right 未完成=${bd1.zh.filter(c => !c.done).length}`);
        const moved = await waitFor('极端同义关收尾', async () => {
            const b = await board();
            if (b.counter !== bd0.counter) return true;
            return /本册完成|全部/.test((await state()).flash || '');
        }, 8000, 150);
        check('极端同义关打完后正常收尾（跳下一关或本册完成提示）', moved,
            `${bd0.counter} -> ${(await board()).counter}`);
        await shot('19-desktop-synonym-level-all-same');
    }

    /* ---------- S10. 移动端 ---------- */
    console.log('\n— S10 移动端 —');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 390, height: 844, deviceScaleFactor: 2, mobile: true
    });
    await sleep(700);
    const mobile = await evalJs(`(() => {
        const cards = Array.from(document.querySelectorAll('.wm-card')).map(el => {
            const r = el.getBoundingClientRect();
            return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
                     right: Math.round(r.right), bottom: Math.round(r.bottom) };
        });
        const navBtns = Array.from(document.querySelectorAll('.wm-nav button')).map(el => {
            const r = el.getBoundingClientRect();
            return { h: Math.round(r.height), bottom: Math.round(r.bottom) };
        });
        const tbBtns = Array.from(document.querySelectorAll('.wm-toolbar-actions > button, .wm-set-btn'))
            .filter(e => e.getClientRects().length > 0)
            .map(e => { const r = e.getBoundingClientRect(); return { right: Math.round(r.right) }; });
        return {
            vw: window.innerWidth, vh: window.innerHeight,
            cards, navBtns, tbBtns,
            overflowX: document.documentElement.scrollWidth > window.innerWidth + 1,
            overflowY: document.documentElement.scrollHeight > window.innerHeight + 1,
            navVisible: getComputedStyle(document.querySelector('#site-nav')).display !== 'none',
            stageTop: Math.round(document.querySelector('.wm-stage').getBoundingClientRect().top),
            stageVar: getComputedStyle(document.documentElement).getPropertyValue('--wm-stage-top').trim(),
            navBottom: Math.round(document.querySelector('.wm-nav').getBoundingClientRect().bottom),
            footerBottom: Math.round(document.querySelector('.wm-footer').getBoundingClientRect().bottom)
        };
    })()`);
    check('移动端无横向溢出', !mobile.overflowX, `scrollWidth>${mobile.vw}`);
    check('移动端工具栏按钮不超出视口', mobile.tbBtns.every(t => t.right <= mobile.vw + 1),
        mobile.tbBtns.map(t => t.right).join(','));

    /* 设置弹层在窄屏也必须完整可见、条目可点 */
    await openSettings();
    const mp = await evalJs(`(() => {
        const el = document.querySelector('.wm-set-pop');
        if (!el || !el.getClientRects().length) return null;
        const r = el.getBoundingClientRect();
        const items = Array.from(el.querySelectorAll('.wm-set-item')).map(it => it.getBoundingClientRect().height);
        return { left: Math.round(r.left), right: Math.round(r.right), w: Math.round(r.width),
                 items: items.map(h => Math.round(h)), vw: window.innerWidth };
    })()`);
    check('移动端设置弹层完整落在视口内', !!mp && mp.left >= 0 && mp.right <= mp.vw + 1,
        mp ? `left=${mp.left} right=${mp.right} w=${mp.w} vw=${mp.vw}` : 'null');
    check('移动端设置条目仍然可点（>=44px）', !!mp && mp.items.every(h => h >= 44),
        mp ? mp.items.join(',') : 'null');
    await shot('12b-mobile-settings-pop');
    await send('Input.dispatchKeyEvent',
        { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await send('Input.dispatchKeyEvent',
        { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await sleep(240);
    check('移动端按 Esc 收起设置弹层', !(await settingsOpen()));
    check('移动端无纵向溢出（底部提示与关卡导航不被裁）',
        !mobile.overflowY && mobile.footerBottom <= mobile.vh && mobile.navBottom <= mobile.vh,
        `overflowY=${mobile.overflowY} footer=${mobile.footerBottom} nav=${mobile.navBottom} vh=${mobile.vh}`);
    check('移动端所有卡片在视口内', mobile.cards.every(c => c.x >= 0 && c.right <= mobile.vw + 1),
        mobile.cards.map(c => `${c.x}~${c.right}`).join(' '));
    check('移动端卡片高度可点（>=44px 触控目标）', mobile.cards.every(c => c.h >= 44),
        'min=' + Math.min(...mobile.cards.map(c => c.h)));
    check('移动端上一关/下一关按钮可点（>=36px）', mobile.navBtns.every(n => n.h >= 36),
        mobile.navBtns.map(n => n.h).join(','));
    check('移动端整页无需滚动即可玩', mobile.cards.every(c => c.bottom <= mobile.vh), 'vh=' + mobile.vh);
    console.log('  移动端：导航栏可见=' + mobile.navVisible + ' stageTop=' + mobile.stageTop
        + ' stageVar=' + mobile.stageVar);
    await shot('06-mobile');

    const fgMob = await flashGeom('13-mobile-flash');
    check('过关提示完全落在单词区之外（移动端）',
        fgMob.visible && !fgMob.overBoard && fgMob.overCards === 0 && fgMob.bottom <= fgMob.vh,
        JSON.stringify(fgMob));
    check('移动端过关提示也贴在单词区上方', fgMob.aboveBoard && fgMob.onTitlebar,
        `flash=${fgMob.top}~${fgMob.bottom} titlebar=${fgMob.titlebarTop}~${fgMob.titlebarBottom} boardTop=${fgMob.boardTop}`);

    await clickAt(mobile.cards[0].x + 10, mobile.cards[0].y + 10);
    await sleep(200);
    check('移动端点击可选中卡片（重排后命中区域仍正确）',
        (await evalJs(`document.querySelectorAll('.wm-card.is-sel').length`)) === 1);
    await shot('07-mobile-selected');

    // 移动端也能开自动学习
    await clickSel('.wm-toolbar-actions button[title*="自动"]');
    const autoUp = await waitFor('自动学习状态条', async () => (await state()).autoBar, 4000, 100);
    check('移动端可开启自动学习并显示状态条', autoUp, `title=${(await state()).title}`);
    const cntOk = await waitFor('倒计时', async () => (await state()).autoCount !== '', 4000, 80);
    check('移动端自动学习显示倒计时提示', cntOk, `count=${(await state()).autoCount}`);
    await shot('12-mobile-auto');

    // 状态条只占一行：高度全程不变，且步骤标签不被压缩
    let mBarH = 0, mBarStable = true, mTagClipped = false, mTipSeen = [];
    const tMob = Date.now();
    while (Date.now() - tMob < 5000) {
        const s = await autoSnapshot();
        if (!s.autoBar) break;
        if (s.barH) { if (!mBarH) mBarH = s.barH; else if (s.barH !== mBarH) mBarStable = false; }
        if (s.tagClipped) mTagClipped = true;
        if (s.tip && mTipSeen.indexOf(s.tip) < 0) mTipSeen.push(s.tip);
        await sleep(60);
    }
    check('移动端自动学习状态条全程只有一行（高度不变）', mBarStable && mBarH > 0,
        `barH=${mBarH} stable=${mBarStable} tips=${mTipSeen.join(' / ')}`);
    check('移动端步骤标签完整可见（不被挤掉）', !mTagClipped, mTipSeen.join(' / '));
    check('移动端状态条高度是单行（<=46px）', mBarH > 0 && mBarH <= 46, `barH=${mBarH}`);

    /* 先退出自动学习再量提示条：否则自动循环自己的 showFlash 会顶掉注入的文案（测了个寂寞） */
    await clickSel('.wm-auto-bar .wm-auto-btn.is-exit');
    await sleep(300);
    check('退出自动学习后状态条隐藏', !(await autoSnapshot()).autoBar);

    const LONG_FLASH = '本册学完！即将进入「九年级全一册」';
    const fgLong = await flashTextGeom('📚', LONG_FLASH, '16-mobile-flash-long');
    check('移动端长过关提示仍只占一行（不撑成两行）',
        fgLong.text === LONG_FLASH && fgLong.lines === 1 && !fgLong.clipped && fgLong.inViewport,
        JSON.stringify(fgLong));
    const fgNorm = await flashTextGeom('✅', '本关完成！即将进入下一关', '17-mobile-flash-normal');
    check('移动端常规过关提示也是单行',
        fgNorm.lines === 1 && !fgNorm.clipped && fgNorm.text === '本关完成！即将进入下一关',
        JSON.stringify(fgNorm));

    /* ---------- S10b. 短屏 + 满员关（7 对）：顶部信息必须压得住 ----------
       用户实报：三上第 13/13 关（7 对）在手机上顶出屏幕。
       根因是 HUD(34) + 标题行(46) + 信息栏(115) 三段顶部信息吃掉 ~195px，
       7 行单词再一撑就溢出。现在改为：HUD 浮动、标题行单行、信息栏横向滑动、
       上一关/下一关浮动胶囊。这里用 375x667（iPhone SE2/8 档）锁住不再回归。 */
    console.log('\n— S10b 短屏 + 满员关（7 对）不溢出 —');
    await send('Emulation.setDeviceMetricsOverride',
        { width: 375, height: 667, deviceScaleFactor: 2, mobile: true });
    await evalJs(`localStorage.setItem('word_match_book_v1', 'pep-3-1');
                  localStorage.removeItem('word_match_progress_v2');`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(300);

    const shortSetup = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        d.levelIndex = d.levels.length - 1;      // 本册最后一关：三上第 13 关 = 7 对
        d.loadLevel();
        return { levels: d.levels.length, theme: d.levels[d.levels.length - 1].theme,
                 pairs: d.levels[d.levels.length - 1].pairs.length };
    })()`);
    await sleep(420);

    const short = await evalJs(`(() => {
        const R = (s) => { const e = document.querySelector(s); if (!e) return null;
            const r = e.getBoundingClientRect();
            return { top: Math.round(r.top), bottom: Math.round(r.bottom), h: Math.round(r.height) }; };
        const cs = (s, p) => { const e = document.querySelector(s); return e ? getComputedStyle(e)[p] : null; };
        const cards = Array.from(document.querySelectorAll('.wm-card')).map(e => e.getBoundingClientRect());
        const nav = R('.wm-nav');
        return {
            vw: window.innerWidth, vh: window.innerHeight,
            pairs: document.querySelectorAll('.wm-col-en .wm-card').length,
            overflowY: document.documentElement.scrollHeight - window.innerHeight,
            overflowX: document.documentElement.scrollWidth > window.innerWidth + 1,
            cardH: Math.round(cards[0].height),
            lastCardBottom: Math.round(Math.max(...cards.map(c => c.bottom))),
            cardRight: Math.round(Math.max(...cards.map(c => c.right))),
            titlebar: R('.wm-titlebar'), infobar: R('.wm-infobar'), hud: R('.wm-hud'), nav,
            navPos: cs('.wm-nav', 'position'), hudPos: cs('.wm-hud', 'position'),
            footerOverflowX: cs('.wm-footer', 'overflowX'),
            footerWrap: cs('.wm-footer', 'flexWrap'),
            footerH: R('.wm-footer').h,
            navBtnH: document.querySelector('.wm-nav button').getBoundingClientRect().height
        };
    })()`);

    check('短屏本关确实是满员关（7 对）', shortSetup.pairs === 7,
        `levels=${shortSetup.levels} theme=${shortSetup.theme} pairs=${shortSetup.pairs}`);
    check('短屏 7 对：整页无纵向溢出（曾被顶部信息顶出 100px）',
        short.overflowY <= 0, `overflowY=${short.overflowY} vh=${short.vh}`);
    check('短屏 7 对：整页无横向溢出', !short.overflowX, `vw=${short.vw}`);
    check('短屏 7 对：所有卡片都在视口内',
        short.lastCardBottom <= short.vh && short.cardRight <= short.vw + 1,
        `lastBottom=${short.lastCardBottom} right=${short.cardRight} vh=${short.vh}`);
    check('短屏 7 对：卡片仍 >=44px 触控下限', short.cardH >= 44, `cardH=${short.cardH}`);
    check('短屏：顶部信息已压扁（标题行 + 信息栏 <= 76px）',
        short.titlebar.h + short.infobar.h <= 76,
        `titlebar=${short.titlebar.h} infobar=${short.infobar.h}`);
    check('短屏：HUD 脱离文档流（进度条 / 计数 / 关闭按钮浮起来）',
        short.hudPos === 'absolute' && short.hud.h === 0 && short.hud.top <= short.titlebar.top,
        `hudPos=${short.hudPos} hud=${JSON.stringify(short.hud)} titlebarTop=${short.titlebar.top}`);
    check('短屏：说明文字单行 + 横向滑动（长文案不折行撑高）',
        short.footerOverflowX === 'auto' && short.footerWrap === 'nowrap' && short.footerH <= 26,
        `overflowX=${short.footerOverflowX} wrap=${short.footerWrap} h=${short.footerH}`);
    check('短屏：上一关/下一关改成浮动胶囊（不占纵向空间）',
        short.navPos === 'fixed' && short.navBtnH >= 36,
        `navPos=${short.navPos} btnH=${short.navBtnH}`);
    check('短屏：浮动胶囊不压住最后一行单词',
        short.nav.top >= short.lastCardBottom,
        `navTop=${short.nav.top} lastCardBottom=${short.lastCardBottom}`);
    check('短屏：浮动胶囊完整落在视口内且高于底部安全区',
        short.nav.bottom <= short.vh && short.nav.bottom >= short.vh - 60,
        `navBottom=${short.nav.bottom} vh=${short.vh}`);
    await shot('18-mobile-short-7pairs');
    console.log('  短屏 7 对：overflowY=' + short.overflowY + ' 卡片=' + short.cardH
        + ' 标题行=' + short.titlebar.h + ' 信息栏=' + short.infobar.h
        + ' 胶囊=' + short.nav.top + '~' + short.nav.bottom + ' 末行底=' + short.lastCardBottom);

    /* 更极端的 320x568（iPhone SE 一代）：再压一档后也必须不溢出 */
    await send('Emulation.setDeviceMetricsOverride',
        { width: 320, height: 568, deviceScaleFactor: 2, mobile: true });
    await sleep(600);
    const tiny = await evalJs(`(() => {
        const cards = Array.from(document.querySelectorAll('.wm-card')).map(e => e.getBoundingClientRect());
        return { overflowY: document.documentElement.scrollHeight - window.innerHeight,
                 cardH: Math.round(cards[0].height),
                 lastCardBottom: Math.round(Math.max(...cards.map(c => c.bottom))),
                 navTop: Math.round(document.querySelector('.wm-nav').getBoundingClientRect().top),
                 navBottom: Math.round(document.querySelector('.wm-nav').getBoundingClientRect().bottom),
                 vh: window.innerHeight };
    })()`);
    check('超小屏 320x568 + 7 对也不溢出',
        tiny.overflowY <= 0 && tiny.lastCardBottom <= tiny.vh && tiny.cardH >= 44,
        `overflowY=${tiny.overflowY} cardH=${tiny.cardH} lastBottom=${tiny.lastCardBottom} vh=${tiny.vh}`);
    check('超小屏浮动胶囊仍不压住单词',
        tiny.navTop >= tiny.lastCardBottom && tiny.navBottom <= tiny.vh,
        `nav=${tiny.navTop}~${tiny.navBottom} lastBottom=${tiny.lastCardBottom}`);
    await shot('19-mobile-tiny-7pairs');

    /* ---------- S12. 导出为单页 HTML ---------- */
    console.log('\n— S12 导出为单页 HTML —');
    /* S10b 把视口压到 320x568 了，这里恢复桌面尺寸（宽弹窗才点得准） */
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
    await send('Browser.setDownloadBehavior',
        { behavior: 'allow', downloadPath: downloadDir, eventsEnabled: true });
    await evalJs(`localStorage.setItem('word_match_book_v1', 'pep-3-1')`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();

    await openSettings();
    const exItem = await centerOf('.wm-set-item.is-export');
    check('S12 设置弹层里有「导出」入口',
        !!exItem && exItem.w > 0 && exItem.text.indexOf('导出') === 0,
        exItem ? exItem.text : 'missing');
    await clickSel('.wm-set-item.is-export');
    await sleep(420);

    const dlg = await evalJs(`(() => {
        const m = document.getElementById('wm-export-modal');
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { open: !!m && m.getBoundingClientRect().height > 0, flag: d.exportOpen,
                 items: document.querySelectorAll('.wm-export-item').length,
                 stages: document.querySelectorAll('.wm-export-stage').length,
                 count: d.exportSelCount, first: d.exportSelList[0],
                 all: d.exportAllSelected, part: d.exportPartSelected,
                 sum: document.querySelector('.wm-export-sum').textContent.trim(),
                 head: document.querySelector('#wm-export-modal .wm-wrong-total').textContent.trim() };
    })()`);
    check('S12 弹窗打开：24 册全列出、分三个学段',
        dlg.open && dlg.flag && dlg.items === 24 && dlg.stages === 3,
        `items=${dlg.items} stages=${dlg.stages}`);
    check('S12 默认只勾当前册（三上），呈「部分选中」态',
        dlg.count === 1 && dlg.first === 'pep-3-1' && dlg.part === true && dlg.all === false,
        `count=${dlg.count} first=${dlg.first} part=${dlg.part}`);
    await shot('23-export-dialog-default');

    /* --- 全选 / 取消全选 --- */
    await clickSel('#wm-export-all'); await sleep(340);
    const allSel = await evalJs(`(() => { const d = Alpine.$data(document.querySelector('.wm-root'));
        return { n: d.exportSelCount, all: d.exportAllSelected,
                 label: document.querySelector('.wm-export-all span').textContent.trim(),
                 sum: document.querySelector('.wm-export-sum').textContent.trim(),
                 goOn: !document.querySelector('#wm-export-go').disabled }; })()`);
    check('S12 全选：24 册全勾上、文案翻成「取消全选」',
        allSel.n === 24 && allSel.all === true && allSel.label === '取消全选',
        `n=${allSel.n} label=${allSel.label}`);
    check('S12 全选后汇总 = 24 册 / 1249 关 / 7032 词',
        /已选 24 册/.test(allSel.sum) && /1249 关/.test(allSel.sum) && /7032 词/.test(allSel.sum),
        allSel.sum);
    await shot('24-export-dialog-all');

    await clickSel('#wm-export-all'); await sleep(340);
    const noneSel = await evalJs(`(() => ({ n: Alpine.$data(document.querySelector('.wm-root')).exportSelCount,
        goOff: document.querySelector('#wm-export-go').disabled }))()`);
    check('S12 取消全选：一册不剩、「导出 HTML」置灰',
        noneSel.n === 0 && noneSel.goOff === true, JSON.stringify(noneSel));

    /* 学段级全选：只勾小学 8 册 */
    await clickSel('.wm-export-stage-btn', 0); await sleep(340);
    const stageSel = await evalJs(`(() => { const d = Alpine.$data(document.querySelector('.wm-root'));
        return { n: d.exportSelCount, ids: d.exportSelList.join(',') }; })()`);
    check('S12「全选本学段」只勾小学 8 册（不碰初中/高中）',
        stageSel.n === 8 && stageSel.ids.split(',').every(id => !/^pep-([789]|h)/.test(id)),
        stageSel.ids);

    /* --- 只勾三上 + 三下，真导出 --- */
    await evalJs(`(() => { Alpine.$data(document.querySelector('.wm-root')).exportSel =
        { 'pep-3-1': true, 'pep-3-2': true }; })()`);
    await sleep(320);
    const beforeFiles = new Set(fs.readdirSync(downloadDir));
    await clickSel('#wm-export-go');
    let outFile = null;
    for (let i = 0; i < 70 && !outFile; i++) {
        await sleep(400);
        const fresh = fs.readdirSync(downloadDir)
            .filter(n => !beforeFiles.has(n) && !n.endsWith('.crdownload'));
        if (fresh.length) outFile = path.join(downloadDir, fresh[0]);
    }
    check('S12 点「导出 HTML」真的落盘了文件', !!outFile, outFile || '没有新文件');
    if (!outFile) throw new Error('导出未产出文件，S12 后续断言无法进行');

    const outName = path.basename(outFile);
    const html = fs.readFileSync(outFile, 'utf8');
    console.log('  产物 ' + outName + '（' + Math.round(Buffer.byteLength(html, 'utf8') / 1024) + ' KB）');
    await shot('25-export-toast');

    check('S12 文件名形如 word-match-日期[-N册].html',
        /^word-match-\d{4}-\d{2}-\d{2}(-\d+册)?\.html$/.test(outName), outName);

    const extRefs = [...html.matchAll(/(?:src|href)="([^"]*)"/g)]
        .map(m => m[1]).filter(u => !/^data:/.test(u));
    check('S12 产物零外链：不存在任何非 data: 的 src / href',
        extRefs.length === 0, extRefs.slice(0, 3).join(' | '));
    const cssRefs = [...html.matchAll(/url\(([^)]+)\)/g)]
        .map(m => m[1].trim().replace(/^['"]|['"]$/g, '')).filter(u => !/^data:/.test(u));
    check('S12 产物 CSS 里也没有外链（字体是 data URI）',
        cssRefs.length === 0, cssRefs.slice(0, 3).join(' | '));
    check('S12 图标字体已内联（否则图标全变方框）',
        /url\(data:font\/woff2;base64,[A-Za-z0-9+/=]{5000,}\)/.test(html));
    check('S12 样式表已内联（产物里有 Bootstrap 的按钮样式）',
        html.indexOf('--bs-btn-') > 0);
    check('S12 站点导航与 nav.js 的脚本标签都已摘掉',
        !/id="site-nav"/.test(html) && !/<script[^>]+src="\/js\/nav\.js"/.test(html));

    const pm = html.match(/window\.__WORD_MATCH_DATA__ = (\{[\s\S]*?\});\n/);
    const payload = pm ? JSON.parse(pm[1]) : null;
    check('S12 数据载荷只含被勾选的 2 册（没把 24 册全塞进去）',
        !!payload && payload.books.length === 2
        && payload.books.every(b => b.id === 'pep-3-1' || b.id === 'pep-3-2')
        && Object.keys(payload.levels).length === 2,
        payload ? payload.books.map(b => b.id).join(',') : '载荷解析失败');
    check('S12 单机标记已写入',
        /window\.__WM_STANDALONE__ = true/.test(html) && /data-standalone="1"/.test(html));

    /* --- 用 file:// 打开产物，真跑一遍（这一步才证明「单个 HTML 即可使用」） --- */
    const errBefore = runtimeErrors.length;
    await send('Page.navigate', { url: 'file://' + outFile });
    await sleep(2200);
    const solo = await evalJs(`(() => {
        const root = document.querySelector('.wm-root');
        const d = root && window.Alpine ? Alpine.$data(root) : null;
        const closeBtn = document.querySelector('.wm-close');
        return { title: document.title, alpine: typeof window.Alpine,
                 cards: document.querySelectorAll('.wm-card').length,
                 books: d ? d.books.length : 0, levels: d ? d.levelCount : 0,
                 label: d ? d.bookLabel : '',
                 navHeader: !!document.getElementById('site-nav'),
                 closeShown: closeBtn ? getComputedStyle(closeBtn).display !== 'none' : null,
                 hScroll: document.documentElement.scrollWidth - window.innerWidth };
    })()`);
    check('S12 双击打开就能玩：Alpine 已就绪、卡片已渲染',
        solo.alpine === 'object' && solo.cards >= 6 && solo.levels > 0,
        `alpine=${solo.alpine} cards=${solo.cards} levels=${solo.levels}`);
    check('S12 产物里就只有被勾的 2 册（当前册仍是三上）',
        solo.books === 2 && solo.label === '三年级上册', `books=${solo.books} label=${solo.label}`);
    check('S12 站点导航与「退出」按钮都不在（单机件没有站内可退）',
        solo.navHeader === false && solo.closeShown === false,
        `nav=${solo.navHeader} close=${solo.closeShown}`);
    check('S12 产物标题不含项目名',
        /英语单词匹配/.test(solo.title) && !/AI故事创作/.test(solo.title), solo.title);
    await shot('26-standalone-opened');

    /* 真配一对：证明不只是「渲染出来了」，交互逻辑也活着 */
    const sb = await board();
    const soloPair = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const l = d.leftCards.find(c => !c.done);
        return { en: l.text, zh: l.mean };
    })()`);
    await clickAt(byText(sb.en, soloPair.en).x, byText(sb.en, soloPair.en).y);
    await sleep(300);
    const sb2 = await board();
    await clickAt(byText(sb2.zh, soloPair.zh).x, byText(sb2.zh, soloPair.zh).y);
    await sleep(520);
    const soloDone = await evalJs(`(() => { const d = Alpine.$data(document.querySelector('.wm-root'));
        return { matched: d.matchedCount, done: d.leftCards.filter(c => c.done).length }; })()`);
    check('S12 单机件里配对逻辑照常工作',
        soloDone.matched >= 1 && soloDone.done >= 1,
        `${soloPair.en}/${soloPair.zh} → ${JSON.stringify(soloDone)}`);

    await openSettings();
    const soloSet = await evalJs(`(() => {
        const ex = document.querySelector('.wm-set-item.is-export');
        return { exportDisplay: ex ? getComputedStyle(ex).display : 'missing',
                 items: document.querySelectorAll('.wm-set-item').length };
    })()`);
    check('S12 单机件里「导出」入口已隐藏、其余设置项保留',
        soloSet.exportDisplay === 'none' && soloSet.items === 5, JSON.stringify(soloSet));
    await shot('27-standalone-settings');

    check('S12 打开产物没有新增未捕获错误',
        runtimeErrors.length === errBefore, runtimeErrors.slice(errBefore).join(' | '));

    /* ---------- S11. 运行期错误 ---------- */
    console.log('\n— S11 运行期错误 —');
    check('无未捕获的运行时 JS 错误', runtimeErrors.length === 0, runtimeErrors.join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.join(' | '));
    check('无资源加载失败', httpErrors.length === 0, httpErrors.join(' | '));

    const failed = results.filter(r => !r.ok);
    console.log(`\n===== ${results.length - failed.length}/${results.length} 通过 =====`);
    if (failed.length) {
        failed.forEach(f => console.log('  FAILED: ' + f.name + (f.extra ? ' [' + f.extra + ']' : '')));
    }
    return failed.length === 0;
}

let ok = false;
try {
    ok = await main();
} catch (e) {
    console.error('执行异常：' + (e && e.stack || e));
} finally {
    try { ws && ws.close(); } catch (e) { /* ignore */ }
    try { chrome.kill('SIGKILL'); } catch (e) { /* ignore */ }
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* ignore */ }
}
process.exit(ok ? 0 : 1);

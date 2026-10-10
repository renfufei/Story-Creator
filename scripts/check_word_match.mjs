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
 *  S4 匹配成功：两张卡一起闪绿（浅绿底 + 变绿文字/边框，wm-ok-blink 动画 .5s），
 *      约 .5s 后自动收回「已完成 + 本组浅色」（tint-N）；配对逻辑（done/不可再选）不受动画影响
 *  S4b 闪绿的精确配色（用 ?wmOkFlash= 把「亮多久」拉长到 2.4s 再取样：
 *      常驻浅绿 = --wm-ok-bg，闪烁时压到 --wm-ok-hi，期望值从页面 CSS 变量取）
 *  S5 全部配对后自动进下一关 + 练习进度落盘
 *  S5b 已配对卡片「一组一色」（tint-N）：同一对的中英文同一个 N、不同对不同 N；
 *      底色/边框/文字都取自本组 CSS 变量；底色非白、冷色相（无红无黄）、对比度 ≥ 4.5:1；
 *      悬停不被 hover 抹回中性线色（含自动学习模式那条高特异性规则）
 *  S6 册次弹出框（选择册次、旧下拉已移除）
 *  S6c 大学独立词源（首屏只带册元信息、关卡按需拉 /learn/word-match/cet；
 *      点「四级」真进一关，逐条核对 2203 关 / 13159 条 / 关内英文不重复 / 细域主题（粒度 ≤150 条））
 *  S7 上一关 / 下一关 + 边界禁用
 *  S7b 本册最后一关：「下一关」变「下一册」+ 打完弹通关窗、点「继续看看」后仍能继续
 *  S8 错题本弹窗（列表 / 朗读 / 去练 / 删除 / 清空）
 *  S9 自动学习（真实节奏的 1s 间隔与 2s 思考；快速节奏跑完整关 + 4s 过关节奏 +
 *     暂停/继续/退出 + 学习进度与练习进度互不共享 + 点英文即朗读）
 *  另：朗读时机（点英文那一下）与过关提示「贴单词区上方、不遮挡卡片」单独断言
 *  S9d 同义关：同一个中文对应多个词，选哪个都算对（按释义判定而非配对 key）
 *  S10 移动端布局（含新按钮/底部导航不溢出、重排后仍可点；
 *      另含「上一关/下一关按钮在 hover/active/focus 下文字仍可见」的防回归 ——
 *      真机点完会留下粘滞 :hover，白底胶囊若只改 background 就会白字压白底）
 *  S10b 短屏 + 满员关（7 对，如三上第 13 关）：顶部信息压缩后不再溢出
 *       （375x667 与 320x568 两档；浮动胶囊不压单词、说明文字横向滑动）
 *  S12 导出为单页 HTML（设置 → 导出：册次多选 / 全选与本学段全选、默认勾当前册；
 *      真实落盘后校验产物零资源外链（只放行「源码」那条导航链接）、只含被勾的册，
 *      再用 file:// 打开跑一遍配对）
 *  S13 搜索单词（工具栏「设置」左边的「搜索」→ 弹窗：输入框 + 搜索按钮 + 右上角关闭键；
 *      完全匹配排最前 / 前缀匹配接后且最多 5 个单词 / 两者都空才做部分匹配；大小写不敏感；
 *      中文可反查；一个词的多处出处一起列出；出处行带学段前缀（「高中 · 必修4 · 第 12 关」——
 *      高中的必修/选修系列不带年级，只写册名会被误读成「4 年级」）；
 *      点【查看】跳到对应册次与关卡；点遮罩、按 Esc 都能关）
 *  S14 类别面板 / 分类练习：点页头的类别标签 -> 弹出面板，按关卡分组列出「本册这个类别的
 *      全部单词」（关号 / 每关单词数 / 已完成标记 / 朗读）；
 *      【练习本分类】进入过滤模式（作用域只收窄「能走哪几关」，不改写本册关卡数组，
 *      于是 levelIndex 仍是本册真实关卡下标 —— 进度 / 错题本 / 「第 N 关」口径全不变）：
 *      作用域内走关、范围外分段压暗、末关按钮变「结束分类」、练完自动退出、换册自动退出
 *  S11 运行时错误采集
 *
 * 静音约定：脚本开头把 word_match_sound_v1 写成 0，整轮默认不发声；
 *          需要验「点英文即朗读」时由 speakProbeSetup() 临时打开语音，
 *          并且探针只是记录、不调用原生 speak()（否则本机 TTS 会整轮不停地念）。
 *
 * 用法：node check_word_match.mjs
 * 可选环境变量：WM_BASE（默认 http://localhost:1888）、WM_OUT（截图目录）、STORY_BROWSER_PATH、
 *              WM_LOCAL=1（导航文档换成本地 HTML，改完静态页不必先打包重启就能跑全套断言）
 */
import { spawn } from 'node:child_process';
// 静音守门：--mute-audio + 注入 src/test/resources/silent-audio.js（管住平台 TTS）
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE = process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/word-match';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9333;
const OUT = process.env.WM_OUT || '/tmp/wm-shots';
/* WM_LOCAL=1：把导航文档的响应换成本地 src/main/resources/static/pages/learn-word-match.html，
   接口数据仍走真实后端 —— 静态页打进 jar，改完 HTML 不重启服务也能跑完整套断言。
   只拦**文档**请求（resourceType === 'Document'）且路径严格等于 /learn/word-match；
   /learn/word-match/cet 那种接口即使被 pattern 命中也会 continueRequest 放行。 */
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const WM_LOCAL = process.env.WM_LOCAL === '1';
const LOCAL_HTML = path.join(ROOT, 'src/main/resources/static/pages/learn-word-match.html');

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
    ...SILENT_AUDIO_FLAGS,            // ① 浏览器音频管线静音（媒体元素 + Web Audio）
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
                ok: el.classList.contains('is-ok'),
                wrong: el.classList.contains('is-wrong'),
                /* 已配对卡片会带 tint-N（同一对的中英文同一个 N），未配对时必须是 null。
                   正则里的 \b 必须写成 \\b：本串是外层模板字面量，单个 \b 会被吃成退格符 */
                tint: (el.className.match(/\\btint-(\\d)\\b/) || [])[1] ?? null,
                bg: cs.backgroundColor,
                line: cs.borderTopColor,
                color: cs.color,
                anim: cs.animationName,
                animDur: cs.animationDuration,
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
            /* 文案要取「文案那个 span 自己的 textContent」，不能用容器的 innerText：
               提示条到期时 Alpine 的 x-text（文案）会比 x-show（容器）先刷新，存在一帧
               「display:flex、getClientRects() 仍非空，但文案已空」——innerText 就只剩 emoji，
               于是 /即将进入/ 之类的断言偶发失败。实测复现过（页面内 rAF 逐帧采样抓到）。 */
            flash: vis(flash) ? (flash.lastElementChild ? flash.lastElementChild.textContent : '').trim() : '',
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

/* 把界面切进棋盘视图。**每次 Page.navigate 之后都必须做** —— 2026-10-07 起页面默认落在
   检索首页（view='search'），棋盘压根不渲染，直接等卡片会一路超时（而且是「等了 15 秒然后
   抛异常」这种最费时间的失败）。挂在 waitCards() 里统一处理，各段的调用点一行都不用改。
   这里直接写状态、不用真实点击 —— 视图切换**本身**的行为由 S0 用真实点击验，不重复验。 */
async function enterPlayView() {
    try {
        return await evalJs(`(() => {
            if (typeof Alpine === 'undefined' || !window.Alpine) return false;
            const r = document.querySelector('.wm-root');
            if (!r) return false;
            const d = Alpine.$data(r);
            if (!d || !d.bookId) return false;      // 还没选册：等 S0 真点选册
            if (d.view !== 'play') { d.view = 'play'; if (d.measureTop) d.measureTop(); }
            return true;
        })()`);
    } catch (e) { return false; }
}

async function waitCards() {
    await waitFor('棋盘视图', async () => await enterPlayView(), 15000, 150);
    const ok = await waitFor('卡片', async () =>
        (await evalJs(`document.querySelectorAll('.wm-card').length`)) > 0, 15000, 150);
    if (!ok) throw new Error('卡片未渲染，后续断言无法进行');
}

/* 搜索弹窗是否可见（x-show 只切 display，元素一直在 DOM 里） */
async function searchVisible() {
    return await evalJs(`(() => {
        const m = document.getElementById('wm-search-modal');
        if (!m) return false;
        const back = m.closest('.wm-modal-backdrop');
        return !!back && getComputedStyle(back).display !== 'none';
    })()`);
}

/* 搜索状态一次抓两层：Alpine 数据层（rank / bookId / level）+ 真实 DOM 行（用户看到的那份）。
   ⚠️ 数据层必须显式 map 出新对象：Alpine 的响应式 Proxy 直接 returnByValue 会序列化不出内容。 */
async function searchState() {
    return await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const txt = (el, sel) => { const e = el.querySelector(sel); return e ? e.innerText.trim() : ''; };
        const dom = Array.from(document.querySelectorAll('.wm-search-item')).map(el => ({
            en: txt(el, '.wm-search-en span'),
            zh: txt(el, '.wm-search-zh'),
            src: txt(el, '.wm-search-src').replace(/\\s+/g, ' '),
            badge: (el.querySelector('.wm-search-badge:not(.is-prefix)') || {}).innerText || '',
            act: txt(el, '.wm-search-acts button'),
            exact: el.classList.contains('is-exact')
        }));
        const emptyEl = document.querySelector('.wm-search-empty');
        const countEl = document.querySelector('#wm-search-modal .wm-count-total');
        return {
            q: d.searchQ, ran: d.searchRan, hint: d.searchHintText, pending: d.searchPending,
            hits: d.searchHits.map(h => ({ en: h.en, zh: h.zh, rank: h.rank,
                bookId: h.bookId, bookLabel: h.bookLabel, level: h.level, theme: h.theme })),
            dom: dom,
            empty: emptyEl ? emptyEl.innerText.trim() : '',
            count: countEl ? countEl.innerText.trim() : ''
        };
    })()`);
}

/* 清空输入框再敲入。走 CDP 输入法而不是直接改 value —— 和真人打字同一条路径，
   Alpine 的 x-model 本来也就是靠 input 事件同步的。 */
async function typeSearch(q) {
    await clickSel('#wm-search-input');
    await evalJs(`(() => {
        const el = document.getElementById('wm-search-input');
        el.value = '';
        el.dispatchEvent(new Event('input', { bubbles: true }));
        return 1;
    })()`);
    await sleep(60);
    await send('Input.insertText', { text: q });
    await sleep(140);
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
            /* 文案要取「文案那个 span 自己的 textContent」，不能用容器的 innerText：
               提示条到期时 Alpine 的 x-text（文案）会比 x-show（容器）先刷新，存在一帧
               「display:flex、getClientRects() 仍非空，但文案已空」——innerText 就只剩 emoji，
               于是 /即将进入/ 之类的断言偶发失败。实测复现过（页面内 rAF 逐帧采样抓到）。 */
            flash: vis(flash) ? (flash.lastElementChild ? flash.lastElementChild.textContent : '').trim() : '',
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
        if (m.method === 'Fetch.requestPaused') {
            const { requestId, request } = m.params;
            let isDoc = false;
            try {
                /* ⚠️ 别只认 request.resourceType === 'Document'：实测这台 Chrome 在
                   requestStage:'Request' 时**不给 resourceType**（undefined），
                   这么写会让判定恒为假、静默回落到线上旧页（踩过一次）。
                   路径严格等于 /learn/word-match 已经够精确：
                   /learn/word-match/data、/learn/word-match/cet 都不等于它。 */
                isDoc = new URL(request.url).pathname === '/learn/word-match'
                    && (!request.resourceType || request.resourceType === 'Document');
            } catch (e) { /* 非法 URL：照常放行 */ }
            if (WM_LOCAL && isDoc) {
                send('Fetch.fulfillRequest', {
                    requestId, responseCode: 200,
                    responseHeaders: [{ name: 'Content-Type', value: 'text/html; charset=utf-8' }],
                    body: Buffer.from(fs.readFileSync(LOCAL_HTML, 'utf8'), 'utf8').toString('base64')
                });
            } else {
                send('Fetch.continueRequest', { requestId });
            }
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

    // ② 平台 TTS 管不到就去注入守门脚本（addScriptToEvaluateOnNewDocument，对每次导航都生效）
    await installSilentAudio(send);
    await send('Runtime.enable');
    await send('Network.enable');
    await send('Log.enable');
    if (WM_LOCAL) {
        /* 只在 WM_LOCAL=1 时挂拦截（否则多一层 requestPaused 往返，纯属拖慢正常跑） */
        await send('Fetch.enable', {
            patterns: [{ urlPattern: PAGE + '*', requestStage: 'Request' }]
        });
        console.log(`— WM_LOCAL=1：导航文档改用本地 ${path.relative(ROOT, LOCAL_HTML)}（接口仍走后端）—`);
    }
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
    /* ⚠️ 这里**不能**等卡片：2026-10-07 起默认视图是检索首页，首屏没有棋盘；而且刚清空过
       localStorage，一个册次都没有，waitCards() 里的 enterPlayView 会一直等不到 bookId。 */
    const homeReady = await waitFor('检索首页', async () => await evalJs(`(() => {
        const el = document.getElementById('wm-home-input');
        return !!el && el.getClientRects().length > 0;
    })()`), 15000, 150);
    if (!homeReady) throw new Error('检索首页未渲染，后续断言无法进行');
    await sleep(400);

    /* ---------- S0. 默认落地：检索首页（2026-10-07 新增） ---------- */
    console.log('\n— S0 默认落地页（检索首页 / 视图切换 / 选册入口） —');

    /* 首页快照：可见性 + Alpine 数据层一次抓全 */
    const homeSnap = () => evalJs(`(() => {
        const vis = (id) => { const e = document.getElementById(id); return !!e && e.getClientRects().length > 0; };
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const book = document.querySelector('.wm-book-btn');
        const back = document.querySelector('.wm-back-btn');
        const search = document.querySelector('.wm-search-btn');
        const auto = document.querySelector('.wm-toolbar-actions button[title*="自动"]');
        const stage = document.querySelector('.wm-stage');
        return {
            view: d.view, bookId: d.bookId, levels: d.levels.length, q: d.searchQ, ran: d.searchRan,
            homeVisible: vis('wm-home-input'), dropVisible: vis('wm-home-drop'),
            resultVisible: vis('wm-home-result'), guideVisible: vis('wm-home-guide'),
            playBtnVisible: vis('wm-home-play'),
            stageVisible: !!stage && stage.getClientRects().length > 0,
            cards: document.querySelectorAll('.wm-card').length,
            bookText: book ? book.innerText.replace(/\\s+/g, ' ').trim() : '',
            bookTitle: book ? book.getAttribute('title') : '',
            backVisible: !!back && back.getClientRects().length > 0,
            searchBtnVisible: !!search && search.getClientRects().length > 0,
            autoBtnVisible: !!auto && auto.getClientRects().length > 0,
            stat: ((document.querySelector('.wm-home-stat') || {}).innerText || '').trim(),
            inputValue: (document.getElementById('wm-home-input') || {}).value || ''
        };
    })()`);

    let hs = await homeSnap();
    await shot('00-home-default');
    check('S0 默认落在检索首页：搜索框可见、棋盘未渲染、一张卡片都没有',
        hs.view === 'search' && hs.homeVisible && !hs.stageVisible && hs.cards === 0,
        `view=${hs.view} home=${hs.homeVisible} stage=${hs.stageVisible} cards=${hs.cards}`);
    check('S0 首次访问不预设册次：页头下拉停在【选择关卡练习】占位文案上',
        hs.bookId === '' && hs.bookText === '选择关卡练习' && hs.levels === 0,
        `bookId="${hs.bookId}" 按钮="${hs.bookText}" levels=${hs.levels}`);
    check('S0 未选册时按钮提示说清「选好即进入配对练习」（不是「未选择」那种没用的兜底文案）',
        /选好即进入配对练习/.test(hs.bookTitle), hs.bookTitle);
    check('S0 首屏三块齐了：统计行 + 常驻搜索框 + 「怎么查」说明卡（结果卡还没出现）',
        /^\d+ 册词库 · \d+ 关 · [\d,]+ 词$/.test(hs.stat) && hs.homeVisible
        && hs.guideVisible && !hs.resultVisible,
        `stat="${hs.stat}" guide=${hs.guideVisible} result=${hs.resultVisible}`);
    check('S0 检索页收起棋盘专属按钮：自动学习 / 搜索都不显示，也没有「返回搜索」',
        !hs.autoBtnVisible && !hs.searchBtnVisible && !hs.backVisible,
        `auto=${hs.autoBtnVisible} search=${hs.searchBtnVisible} back=${hs.backVisible}`);

    /* 册次弹层：没有当前册时**必须**兜底展开第一个学段，否则一册都看不见（回归守卫） */
    await clickSel('.wm-book-btn');
    await sleep(340);
    const picker0 = await evalJs(`(() => {
        const items = Array.from(document.querySelectorAll('.wm-book-item'))
            .filter(e => e.getClientRects().length > 0);
        const stages = Array.from(document.querySelectorAll('.wm-book-group-name')).map(e => e.innerText.trim());
        const open = Array.from(document.querySelectorAll('.wm-book-group-head.is-open .wm-book-group-name'))
            .map(e => e.innerText.trim());
        return { items: items.length, stages, open };
    })()`);
    check('S0 没有当前册时册次弹层也要有册可点（默认展开第一个学段，三个学段全折叠就点不着了）',
        picker0.items > 0 && picker0.open.length === 1 && picker0.open[0] === picker0.stages[0],
        `可见册次=${picker0.items} 展开=${JSON.stringify(picker0.open)} 学段=${JSON.stringify(picker0.stages)}`);
    await send('Input.dispatchKeyEvent',
        { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await send('Input.dispatchKeyEvent',
        { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await sleep(300);
    check('S0 册次弹层按 Esc 能收起', !(await evalJs(`Alpine.$data(document.querySelector('.wm-root')).bookPickerOpen`)));

    /* 输入即出：去抖后弹出下拉，且下面那块仍停在「怎么查」（与小程序一致） */
    await evalJs(`(() => {
        const el = document.getElementById('wm-home-input');
        el.focus();
        el.value = 'liberty';
        el.dispatchEvent(new Event('input', { bubbles: true }));
        return true;
    })()`);
    /* 第一次搜索要等大学词库（13159 条 / gzip 225KB）载完，给足时间 */
    const dropUp = await waitFor('输入即出的下拉', async () => (await homeSnap()).dropVisible, 30000, 150);
    hs = await homeSnap();
    const dropRows = await evalJs(`Array.from(document.querySelectorAll('#wm-home-drop .wm-home-hit')).map(r => ({
        en: ((r.querySelector('.wm-home-hit-en') || {}).innerText || '').replace(/\\s+/g, ' ').trim(),
        src: ((r.querySelector('.wm-home-hit-src') || {}).innerText || '').trim()
    }))`);
    await shot('00b-home-drop');
    check('S0 输入即出：去抖后弹出下拉，且下面那块仍停在「怎么查」（没被搜索抢走）',
        dropUp && hs.dropVisible && hs.guideVisible && !hs.resultVisible,
        `下拉=${hs.dropVisible} 说明卡=${hs.guideVisible} 结果卡=${hs.resultVisible}`);
    check('S0 下拉里最多 5 个不同单词、每行都带出处（与弹层同一套匹配口径）',
        dropRows.length > 0 && dropRows.length <= 5
        && new Set(dropRows.map(r => r.en)).size === dropRows.length
        && dropRows.every(r => /第 \d+ 关/.test(r.src)),
        `${dropRows.length} 行：` + dropRows.map(r => r.en + '@' + r.src.slice(0, 16)).join(' | '));
    check('S0 下拉第一行就是完全匹配的 liberty',
        /^liberty/i.test((dropRows[0] || {}).en || ''), (dropRows[0] || {}).en);

    /* 点【搜索】：下拉收起，结果落到下方列表（同一份命中，换一种呈现） */
    await clickSel('#wm-home-go');
    await sleep(420);
    hs = await homeSnap();
    const resRows = await evalJs(`Array.from(document.querySelectorAll('#wm-home-result .wm-home-row'))
        .map(r => ((r.querySelector('.wm-home-hit-en') || {}).innerText || '').replace(/\\s+/g, ' ').trim())`);
    /* 下拉一词一行（最多 5 个单词），结果列表把同一个词的每处出处都摊开 ——
       两者行数**本来就不该相等**（liberty 3 处出处：下拉 1 行、结果 3 行），
       相等才是巧合。要断言的是「同一份命中，只是聚合粒度不同」：
       结果列表里剥掉角标后的不同单词数 == 下拉行数。 */
    const bareWord = (s) => s.replace(/\s*(完全匹配|前缀)\s*$/, '').trim();
    check('S0 点【搜索】：下拉收起、结果卡出现、「怎么查」让位',
        !hs.dropVisible && hs.resultVisible && !hs.guideVisible,
        `下拉=${hs.dropVisible} 结果=${hs.resultVisible} 说明=${hs.guideVisible}`);
    check('S0 结果列表与下拉是同一份命中：下拉一词一行，结果列表把每处出处都摊开',
        resRows.length >= dropRows.length
        && new Set(resRows.map(bareWord)).size === dropRows.length,
        `结果 ${resRows.length} 行 / 剥角标后 ${new Set(resRows.map(bareWord)).size} 个单词，下拉 ${dropRows.length} 行`);
    check('S0 结果每行都有【查看】按钮', (await evalJs(`document.querySelectorAll(
        '#wm-home-result .wm-home-row button.btn-outline-primary').length`)) === resRows.length);

    /* 点【查看】= 去看那一关：切进棋盘 */
    const hit0 = await evalJs(`(() => {
        const h = Alpine.$data(document.querySelector('.wm-root')).searchHits[0];
        return { bookId: h.bookId, bookLabel: h.bookLabel, level: h.level, en: h.en };
    })()`);
    await clickSel('#wm-home-result .wm-home-row button.btn-outline-primary');
    /* 变量名带 0 后缀：S13 段落里已有一个 jumped（同名会 SyntaxError，整个脚本都跑不起来） */
    const jumped0 = await waitFor('跳转后棋盘出现', async () =>
        (await evalJs(`document.querySelectorAll('.wm-card').length`)) > 0, 20000, 150);
    await sleep(340);
    hs = await homeSnap();
    const jumpState = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { bookId: d.bookId, levelIndex: d.levelIndex, label: d.bookLabel };
    })()`);
    check('S0 点结果【查看】：直接切进棋盘并落在命中那一关（首页收起）',
        jumped0 && hs.view === 'play' && !hs.homeVisible && hs.stageVisible
        && jumpState.bookId === hit0.bookId && jumpState.levelIndex === hit0.level,
        `view=${hs.view} 落在「${jumpState.label}」第 ${jumpState.levelIndex + 1} 关（期望第 ${hit0.level + 1} 关）`);
    check('S0 进棋盘后棋盘专属按钮回来：【返回搜索】出现、自动学习与搜索按钮也都在',
        hs.backVisible && hs.autoBtnVisible && hs.searchBtnVisible,
        `back=${hs.backVisible} auto=${hs.autoBtnVisible} search=${hs.searchBtnVisible}`);

    /* 先在棋盘上配一对，用来验【继续练习】不会把本关重开 */
    {
        const plan = await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            const l = d.leftCards.findIndex(c => !c.done);
            const r = d.rightCards.findIndex(c => !c.done && c.text === d.leftCards[l].mean);
            return { l, r };
        })()`);
        const bb = await board();
        await clickAt(bb.en[plan.l].x, bb.en[plan.l].y);
        await sleep(220);
        const bb2 = await board();
        await clickAt(bb2.zh[plan.r].x, bb2.zh[plan.r].y);
        await sleep(360);
    }

    /* 【返回搜索】退回来：查询词与结果都留着 */
    await clickSel('.wm-back-btn');
    await sleep(380);
    hs = await homeSnap();
    check('S0 点【返回搜索】：回到检索首页、棋盘收起，且刚才的查询与结果都留着（不用重敲）',
        hs.view === 'search' && hs.homeVisible && !hs.stageVisible
        && hs.q === 'liberty' && hs.resultVisible && hs.inputValue === 'liberty',
        `view=${hs.view} q="${hs.q}" 输入框="${hs.inputValue}" 结果卡=${hs.resultVisible}`);

    /* 【继续练习】入口：已选过册才出现；点它回棋盘且**不重开本关** */
    const playBtnText = (await evalJs(`(document.getElementById('wm-home-play') || {}).innerText || ''`)
    ).replace(/\s+/g, ' ').trim();
    check('S0 选过册之后首页出现【继续练习 · 册名】入口',
        hs.playBtnVisible && /继续练习/.test(playBtnText), `visible=${hs.playBtnVisible} text="${playBtnText}"`);
    const beforeResume = await evalJs(`(() => { const d = Alpine.$data(document.querySelector('.wm-root'));
        return { levelIndex: d.levelIndex, matched: d.matchedCount }; })()`);
    await clickSel('#wm-home-play');
    await sleep(400);
    const afterResume = await evalJs(`(() => { const d = Alpine.$data(document.querySelector('.wm-root'));
        return { view: d.view, levelIndex: d.levelIndex, matched: d.matchedCount }; })()`);
    check('S0 点【继续练习】回到棋盘，且**不重开本关**（已配好的对子没被打散）',
        afterResume.view === 'play' && afterResume.levelIndex === beforeResume.levelIndex
        && beforeResume.matched >= 1 && afterResume.matched === beforeResume.matched,
        `第 ${beforeResume.levelIndex + 1} 关 已配对 ${beforeResume.matched} -> ${afterResume.matched}`);

    /* 用真实点击从册次弹层挑一册：这就是「选册 = 进棋盘」那条路径，
       同时把基准状态复位成「三年级上册第 1 关」供后面各段沿用 */
    await clickSel('.wm-book-btn');
    await sleep(360);
    let picked = null;
    for (let i = 0; i < 4 && !picked; i++) {
        const t = await evalJs(`(() => {
            const items = Array.from(document.querySelectorAll('.wm-book-item'))
                .filter(e => e.getClientRects().length > 0);
            const hit = items.find(e =>
                ((e.querySelector('.wm-book-label') || {}).innerText || '').trim() === '三年级上册');
            if (hit) { const r = hit.getBoundingClientRect();
                return { ok: true, x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) }; }
            /* 没展开就点开它所在的学段（手风琴：同时只展开一组），下一轮再找 */
            const head = Array.from(document.querySelectorAll('.wm-book-group-head'))
                .find(e => e.innerText.trim().indexOf('小学') === 0 && !e.classList.contains('is-open'));
            if (!head) return { ok: false };
            const hr = head.getBoundingClientRect();
            return { ok: false, x: Math.round(hr.x + hr.width / 2), y: Math.round(hr.y + hr.height / 2) };
        })()`);
        if (t.ok) { picked = t; break; }
        if (t.x === undefined) break;
        await clickAt(t.x, t.y);
        await sleep(340);
    }
    check('S0 册次弹层里点得到「三年级上册」（学段手风琴切换后册次可见）', !!picked, JSON.stringify(picked));
    if (picked) await clickAt(picked.x, picked.y);
    await sleep(560);
    const pickedState = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { view: d.view, bookId: d.bookId, label: d.bookLabel, levelIndex: d.levelIndex,
                 cards: document.querySelectorAll('.wm-card').length, pickerOpen: d.bookPickerOpen,
                 bookText: document.querySelector('.wm-book-btn').innerText.replace(/\\s+/g, ' ').trim() };
    })()`);
    check('S0 选册 = 直接进棋盘：弹层关闭、view 切到 play、按钮文案换成册名、卡片渲染出来',
        pickedState.view === 'play' && pickedState.bookId === 'pep-3-1'
        && pickedState.label === '三年级上册' && !pickedState.pickerOpen && pickedState.cards > 0,
        JSON.stringify(pickedState));
    check('S0 基准状态复位成「三年级上册第 1 关」（后面各段沿用）',
        pickedState.levelIndex === 0, 'levelIndex=' + pickedState.levelIndex);

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
                tag: it.tagName,
                href: it.getAttribute('href') || '',
                target: it.getAttribute('target') || '',
                rel: it.getAttribute('rel') || '',
                deco: getComputedStyle(it).textDecorationLine,
                color: getComputedStyle(it).color,
                h: Math.round(r.height)
            };
        });
        const r = el.getBoundingClientRect();
        return { items, left: Math.round(r.left), right: Math.round(r.right), vw: window.innerWidth };
    })()`);
    check('弹层里正好 6 个条目', !!pop && pop.items.length === 6,
        pop ? pop.items.map(i => i.title).join(' / ') : 'null');
    check('条目名与功能一一对应（错题本/重做本关/音效与朗读/导出/源码/重置进度）',
        !!pop && ['错题本', '重做本关', '音效与朗读', '导出', '源码', '重置进度']
            .every((t, i) => pop.items[i] && pop.items[i].title.indexOf(t) >= 0),
        pop ? pop.items.map(i => i.title).join(' / ') : 'null');
    /* 「源码」是唯一的外链条目：必须是真 <a>（中键/右键新标签页才照常可用），
       且指向仓库、带 target=_blank + rel=noopener */
    const srcItem = pop && pop.items[4];
    check('「源码」是外链 <a>：指向 GitHub 仓库、新标签页打开',
        !!srcItem && srcItem.tag === 'A'
            && srcItem.href === 'https://github.com/renfufei/Story-Creator'
            && srcItem.target === '_blank' && /noopener/.test(srcItem.rel),
        srcItem ? `${srcItem.tag} ${srcItem.href} target=${srcItem.target} rel=${srcItem.rel}` : 'null');
    /* 条目外观必须和 <button> 版一致：不能被浏览器渲染成带下划线的蓝链接 */
    check('「源码」看起来仍是设置条目（无下划线、颜色与其它条目一致）',
        !!srcItem && srcItem.deco === 'none' && srcItem.color === pop.items[0].color,
        srcItem ? `deco=${srcItem.deco} color=${srcItem.color} vs ${pop.items[0].color}` : 'null');
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

    /* ---------- S4. 匹配成功：先闪绿（约 .5s），再置灰 ---------- */
    console.log('\n— S4 匹配成功 —');
    b = await board();
    await clearSpoken();
    await clickAt(byText(b.en, first.text).x, byText(b.en, first.text).y);
    await sleep(130);
    const spEn = await spokenTexts();
    b = await board();
    const correctCard = byText(b.zh, correctZh);
    await clickAt(correctCard.x, correctCard.y);
    await sleep(160);                                 // 落在 .5s 闪绿窗口里取样
    const spZh = await spokenTexts();
    b = await board();
    const okEn = byText(b.en, first.text);
    const okZh = byText(b.zh, correctZh);
    /* 配对成功的反馈：两张卡一起变浅绿并闪两下（.wm-card.is-ok + wm-ok-blink），
       约 .5s 后摘掉 is-ok，交给 .is-done 的「本组浅色」（tint-N）。这里在闪的中途取样。
       注意：动画期间底色一直在两个绿之间插值，所以这里只断「绿系」，
       精确色号与「深一档」的验证放在 S4b（把闪绿拉长后取样，不受时机影响）。 */
    const chan = (s) => (String(s).match(/\d+/g) || []).map(Number);
    const greenish = (s) => { const c = chan(s); return c.length >= 3 && c[1] > c[0] + 10 && c[1] > c[2] + 10; };
    check('配对成功瞬间两张卡都进入 is-ok（英文 + 中文都闪）', okEn.ok && okZh.ok,
        `en.ok=${okEn.ok} zh.ok=${okZh.ok}`);
    check('闪绿期间两张卡底色都是绿系（不是灰、也不是选中蓝）',
        greenish(okEn.bg) && greenish(okZh.bg), `en=${okEn.bg} zh=${okZh.bg}`);
    check('闪绿期间边框与文字也切成绿系',
        greenish(okEn.line) && greenish(okEn.color), `line=${okEn.line} color=${okEn.color}`);
    check('用的是 wm-ok-blink 动画、时长 .5s（≈ 多邻国的闪烁时长）',
        okEn.anim === 'wm-ok-blink' && okEn.animDur === '0.5s', `anim=${okEn.anim} dur=${okEn.animDur}`);
    check('闪绿不影响配对逻辑：两张卡同时是 done', okEn.done && okZh.done);
    check('闪绿期间卡片不可再选中', !okEn.sel);
    check('闪绿时长的默认值是 500ms（?wmOkFlash= 只在自动化里覆盖）',
        (await evalJs(`Alpine.$data(document.querySelector('.wm-root')).okFlashMs`)) === 500,
        `okFlashMs=${await evalJs(`Alpine.$data(document.querySelector('.wm-root')).okFlashMs`)}`);
    await shot('04b-desktop-ok-flash');

    await sleep(700);                                 // 跨过 .5s
    b = await board();
    const afterEn = byText(b.en, first.text);
    check('闪绿约 .5s 后自动摘掉 is-ok',
        !afterEn.ok && !byText(b.zh, correctZh).ok,
        `en.ok=${afterEn.ok} zh.ok=${byText(b.zh, correctZh).ok}`);
    /* 闪完落到「已完成 + 本组自己的浅色」（tint-N）。期望色号现读页面变量，不硬编码。
       ⚠️ 判断「不再是闪绿」要拿 --wm-ok-bg 比，不能用「绿系」这种模糊判据 ——
       tint-0 本身就是淡绿（#f2fff6），一句 greenish() 会把它误杀。 */
    const wantTint = await evalJs(`(() => {
        const el = Array.from(document.querySelectorAll('.wm-card'))
            .find(e => e.classList.contains('is-done'));
        const n = Number((el.className.match(/\\btint-(\\d)\\b/) || [])[1]);
        const root = getComputedStyle(document.querySelector('.wm-root'));
        const hex = (h) => { const v = parseInt(String(h).trim().replace('#',''), 16);
            return 'rgb(' + ((v >> 16) & 255) + ', ' + ((v >> 8) & 255) + ', ' + (v & 255) + ')'; };
        const g = (k) => hex(root.getPropertyValue('--wm-tint-' + n + '-' + k));
        return { n: n, bg: g('bg'), line: g('line'), ink: g('ink'),
                 okBg: hex(root.getPropertyValue('--wm-ok-bg')) };
    })()`);
    check('闪绿结束后落到「已完成 + 本组浅色」（不再是闪绿、也不是旧的中性灰）',
        afterEn.done && afterEn.tint !== null
        && afterEn.bg !== wantTint.okBg && afterEn.bg !== 'rgb(241, 243, 245)'
        && afterEn.bg === wantTint.bg && afterEn.line === wantTint.line && afterEn.color === wantTint.ink,
        `tint=${afterEn.tint} bg=${afterEn.bg}/${wantTint.bg} okBg=${wantTint.okBg}`);
    check('英文先选中：朗读发生在点英文时',
        !canSpeak || (spEn.length === 1 && spEn[0] === first.text), `spoken=${JSON.stringify(spEn)}`);
    check('点中文完成配对时不重复朗读同一个词',
        !canSpeak || spZh.length === spEn.length, `spoken=${JSON.stringify(spZh)}`);
    await shot('04-desktop-matched');
    await speakProbeTeardown();          // 朗读用例验完，后面的批量点击不再发声

    /* ---------- S4b. 闪绿的精确配色 ----------
       默认 500ms 太短，取样时刻落在动画的哪一帧不可控（闪的过程里底色一直在插值）。
       ?wmOkFlash= 只拉长「亮多久」，CSS 的 wm-ok-blink 仍是 .5s ——
       于是 .5s 之后动画已结束、is-ok 还在，这时读到的就是稳定的常驻浅绿；
       再把动画冻在 20% 关键帧上读一次，验证「闪」的那一档深色确实存在。 */
    console.log('\n— S4b 闪绿配色（拉长时长取样） —');
    await send('Page.navigate', { url: PAGE + '?wmOkFlash=2400' });
    await sleep(2600);
    await enterPlayView();          // 这一段没走 waitCards，默认视图又是检索页，得自己切进棋盘
    b = await board();
    {
        const en1 = b.en.find(c => !c.done);
        const pr1 = lv.pairs.find(p => p.en === en1.text) || {};
        const zh1 = b.zh.find(c => !c.done && c.text === pr1.zh);
        await clickAt(en1.x, en1.y);
        await sleep(100);
        await clickAt(zh1.x, zh1.y);
        await sleep(150);                     // 动画还在跑（.5s），先把它冻住，保住 animationName
        await evalJs(`(() => { document.querySelectorAll('.wm-card.is-ok').forEach(el =>
            el.getAnimations().filter(a => a.animationName).forEach(a => a.pause())); return true; })()`);
        /* 冻住动画后再等一会儿：.wm-card 上 border-color/color 还有 .16s 过渡，
           不等它走完就取色会读到插值（实测差 ±2 个通道，而且两张卡的起点不同、读数还不一致）。
           动画已暂停，所以等多久都不会丢失要观察的状态。 */
        await sleep(420);
        const fl = await evalJs(`(() => {
            const root = getComputedStyle(document.querySelector('.wm-root'));
            const hex = (h) => { const n = parseInt(String(h).trim().replace('#', ''), 16);
                return 'rgb(' + ((n >> 16) & 255) + ', ' + ((n >> 8) & 255) + ', ' + (n & 255) + ')'; };
            const want = { bg: hex(root.getPropertyValue('--wm-ok-bg')), hi: hex(root.getPropertyValue('--wm-ok-hi')),
                           line: hex(root.getPropertyValue('--wm-ok')), ink: hex(root.getPropertyValue('--wm-ok-ink')) };
            const cards = Array.from(document.querySelectorAll('.wm-card.is-ok'));
            /* getAnimations() 里还混着 CSS 过渡（background-color 等），它们没有 animationName，
               所以要滤掉——顺带也别去暂停它们，免得把过渡冻在半途影响后续用例。 */
            const cssAnims = (el) => el.getAnimations().filter(a => a.animationName);
            const names = cards.map(el => cssAnims(el).map(a => a.animationName).join(','));
            const read = (el) => { const s = getComputedStyle(el);
                return { bg: s.backgroundColor, line: s.borderTopColor, color: s.color }; };
            /* 先暂停、再 seek 到目标帧，然后才取色 —— 顺序不能反：
               ① 不暂停的话取到的是插值中的颜色（浅绿↔深绿之间）；
               ② .5s 一过 CSS 动画就进入 finished，会从 getAnimations() 里消失，
                  那时既冻不住也读不到 animationName，所以必须趁动画还在时一次性读完。 */
            const seek = (t) => {
                cards.forEach(el => cssAnims(el).forEach(a => { a.pause(); a.currentTime = t; }));
                return cards.map(read);
            };
            const deep = seek(100);          // 20% 关键帧：闪的那一下（深一档）
            const base = seek(600);          // 过末帧：动画不再参与，读到 .is-ok 的常驻浅绿
            return { want, base, deep, names };
        })()`);
        check('S4b 拉长闪绿时长后，两张卡同时亮着（is-ok 命中左右各一张）',
            fl.base.length === 2, `cards=${fl.base.length}`);
        check('S4b 常驻浅绿 = --wm-ok-bg，文字 = --wm-ok-ink，边框 = --wm-ok（期望值取自页面变量，不硬编码色号）',
            fl.base.length === 2 && fl.base.every(c =>
                c.bg === fl.want.bg && c.line === fl.want.line && c.color === fl.want.ink),
            fl.base.map(c => `${c.bg} / ${c.line} / ${c.color}`).join(' | ') + '  want=' + JSON.stringify(fl.want));
        check('S4b 闪烁中途会压到更深的 --wm-ok-hi（证明是「闪」而不是静态绿）',
            fl.deep.length === 2 && fl.deep.every(c => c.bg === fl.want.hi)
            && chan(fl.want.hi)[1] < chan(fl.want.bg)[1],
            fl.deep.map(c => c.bg).join(' | ') + `  hi=${fl.want.hi} bg=${fl.want.bg}`);
        check('S4b 两张卡都在跑 wm-ok-blink',
            fl.names.length === 2 && fl.names.every(n => n === 'wm-ok-blink'), fl.names.join(' | '));
        await shot('04b2-desktop-ok-flash-steady');
    }

    /* ---------- S5. 全部配对 -> 自动进下一关 ---------- */
    console.log('\n— S5 全部配对 / 练习进度 —');
    /* ⚠️ S4b 那一页带着 ?wmOkFlash=2400（闪绿拉长到 2.4s），而 S5 要读「闪绿结束后」的稳定色。
       这里按默认参数重新进一次页面（此时进度还没落盘，仍是第一关的干净状态）。 */
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(300);
    /* 刻意**留一对不配**，等 S5b 检查完再补上。原因：最后一对配完的那一刻，
       .is-ok 闪绿会持续 .5s（把 tint 让位给绿），completeLevel() 又只等 950ms 就跳关 ——
       留给取样 + 悬停检查的窗口只有 400ms 左右，机器一慢就抖。
       留一对之后 S5b 的窗口是无限的（无闪绿、无定时器），最后再补配顺带覆盖「最后一对也成组」。 */
    b = await board();
    const deferredPair = lv.pairs.find(p => { const c = byText(b.en, p.en); return c && !c.done; })
        || lv.pairs[lv.pairs.length - 1];
    for (const p of lv.pairs) {
        if (p === deferredPair) continue;
        b = await board();
        if (byText(b.en, p.en).done) continue;
        await clickAt(byText(b.en, p.en).x, byText(b.en, p.en).y);
        await sleep(90);
        b = await board();
        await clickAt(byText(b.zh, p.zh).x, byText(b.zh, p.zh).y);
        await sleep(220);
    }
    await sleep(600);                 // 跨过最后一次闪绿（.5s），读稳定态

    /* ---------- S5b. 已配对卡片「一组一色」 ----------
       回顾整关时要能一眼认出「哪句英文配哪句中文」，所以同一对的两张卡（左英文 / 右中文）
       必须带同一个 tint-N，不同对必须是不同的 N；底色一律是冷色浅底（不许白、不许红黄），
       文字/底色对比度要达 WCAG AA。
       ⚠️ 这一块必须赶在 completeLevel() 那个 950ms 定时器跳关之前读完（上面最后一步只睡了 220ms）。 */
    console.log('\n— S5b 已配对「一组一色」 —');
    {
        const tint = await evalJs(`(() => {
            const hex = (h) => { const v = parseInt(String(h).trim().replace('#',''), 16);
                return 'rgb(' + ((v >> 16) & 255) + ', ' + ((v >> 8) & 255) + ', ' + (v & 255) + ')'; };
            const root = getComputedStyle(document.querySelector('.wm-root'));
            const pal = [];
            for (let i = 0; i < 7; i++) {
                const g = (k) => hex(root.getPropertyValue('--wm-tint-' + i + '-' + k));
                pal.push({ bg: g('bg'), line: g('line'), ink: g('ink') });
            }
            const cards = Array.from(document.querySelectorAll('.wm-card')).map(el => {
                const cs = getComputedStyle(el);
                const m = (el.className.match(/\\btint-(\\d)\\b/) || [])[1];
                return {
                    text: el.innerText.trim(),
                    side: el.closest('.wm-col').classList.contains('wm-col-en') ? 'en' : 'zh',
                    done: el.classList.contains('is-done'),
                    tint: m === undefined ? null : Number(m),
                    bg: cs.backgroundColor, line: cs.borderTopColor, color: cs.color
                };
            });
            return { pal, cards, boardBg: getComputedStyle(document.querySelector('.wm-board')).backgroundColor };
        })()`);
        const chan = (s) => (String(s).match(/\d+/g) || []).map(Number);
        const relLum = (c) => { const f = (v) => { v /= 255;
            return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
            return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]); };
        const contrast = (a, b) => { const la = relLum(a), lb = relLum(b);
            return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05); };
        const hueOf = (c) => { const r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
            const mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
            if (!d) return 0;
            let h = mx === r ? ((g - b) / d) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
            h *= 60; return h < 0 ? h + 360 : h; };
        const maxDiff = (a, b) => Math.max(...[0, 1, 2].map(k => Math.abs(a[k] - b[k])));

        const done = tint.cards.filter(c => c.done);
        const undone = tint.cards.filter(c => !c.done);

        check('S5b 调色板 7 组齐全（底色 / 边框 / 文字都能从页面变量读到）',
            tint.pal.length === 7 && tint.pal.every(p =>
                p.bg && p.line && p.ink && !p.bg.includes('NaN') && !p.bg.includes('undefined')),
            tint.pal.map(p => p.bg).join(' '));
        check('S5b 7 组调色板全是冷色相（115°~300°：没有红、没有黄）',
            tint.pal.every(p => { const h = hueOf(chan(p.bg)); return h >= 115 && h <= 300; }),
            tint.pal.map(p => hueOf(chan(p.bg)).toFixed(0)).join('/'));
        check('S5b 每组「文字 / 底色」对比度都 ≥ 4.5:1（WCAG AA，回顾时看得清）',
            tint.pal.every(p => contrast(chan(p.bg), chan(p.ink)) >= 4.5),
            tint.pal.map(p => contrast(chan(p.bg), chan(p.ink)).toFixed(2)).join('/'));
        let minBg = 999, minLine = 999;
        for (let i = 0; i < 7; i++) for (let j = i + 1; j < 7; j++) {
            minBg = Math.min(minBg, maxDiff(chan(tint.pal[i].bg), chan(tint.pal[j].bg)));
            minLine = Math.min(minLine, maxDiff(chan(tint.pal[i].line), chan(tint.pal[j].line)));
        }
        /* 底色只留 1/3 深度（每通道 ≥ 240）之后，它在物理上做不到「7 组两两可辨」——
           相邻 ΔE 只有 1.8~2.6。所以这里不再要求底色两两可辨，认组全部交给 1.5px 边框。 */
        check('S5b 7 组边框两两可辨（最大通道差 ≥ 8）—— 底色变浅后全靠它',
            minLine >= 8, `边框 MinDiff=${minLine}  底色（仅供参考，已不再要求）=${minBg}`);
        check('S5b 已配对的每张卡都带 tint-N', done.length > 0 && done.every(c => c.tint !== null),
            done.map(c => `${c.text}:${c.tint}`).join(' | '));
        check('S5b 未配对的卡片不带 tint-N（颜色不能提前泄题）',
            undone.every(c => c.tint === null), `未配对 ${undone.length} 张`);

        /* 核心不变量：按 tint 分组，每组必须恰好「1 张英文 + 1 张中文」，且这两张互为一对。
           用分组而不是按文案找卡，是为了兼容「同义关」——那里两张中文卡文案一模一样。 */
        const byTint = new Map();
        done.forEach(c => { if (!byTint.has(c.tint)) byTint.set(c.tint, []); byTint.get(c.tint).push(c); });
        const pairsOfEn = new Map(lv.pairs.map(p => [p.en, p.zh]));
        const groups = [...byTint.entries()].sort((a, b) => a[0] - b[0]);
        check('S5b 已配对的卡全部成组（每组恰好 2 张），没配的那对是唯一没上色的',
            done.length > 0 && byTint.size * 2 === done.length
            && groups.every(([, g]) => g.length === 2) && undone.length <= 2,
            `已配对 ${done.length} 张 / ${byTint.size} 组 / 未配对 ${undone.length} 张`);
        check('S5b 每个 tint 组 = 左英文 + 右中文，且两者确实是同一对',
            groups.every(([, g]) => {
                const e = g.find(c => c.side === 'en'), z = g.find(c => c.side === 'zh');
                return !!e && !!z && pairsOfEn.get(e.text) === z.text;
            }),
            groups.map(([t, g]) => t + ':' + g.map(c => c.side + '=' + c.text).join('+')).join(' | '));
        check('S5b 本关内 tint 不重复（一个 tint 最多 2 张卡）',
            byTint.size === done.length / 2, `去重后 ${byTint.size} 个 / ${done.length / 2} 对`);
        check('S5b 底色 / 边框 / 文字都取自本组那套变量（不硬编码色号）',
            done.every(c => c.bg === tint.pal[c.tint].bg && c.line === tint.pal[c.tint].line
                && c.color === tint.pal[c.tint].ink),
            done.slice(0, 2).map(c => `${c.tint}: ${c.bg} / ${c.line}`).join('  |  '));
        check('S5b 底色既不是纯白、也不是棋盘底色',
            done.every(c => c.bg !== 'rgb(255, 255, 255)' && c.bg !== tint.boardBg),
            `棋盘=${tint.boardBg} 卡片=${done[0] && done[0].bg}`);
        check('S5b 底色极浅（每通道 ≥ 240：只留 1/3 深度）但又确实不是纯白',
            done.every(c => chan(c.bg).every(v => v >= 240) && chan(c.bg).some(v => v <= 248)),
            done.map(c => c.bg).join(' '));

        /* 悬停在已配对卡片上：边框与底色不能被 hover 的中性线色抢走。
           （两条 hover 规则都要 :not(.is-done)：普通态那条 + 自动学习态那条，
             后者特异性 5 比 .wm-card.tint-N:not(.is-ok) 的 3 高，漏了就会把颜色抹掉）
           用 CSS.forcePseudoState 造 :hover —— 无头里 mouseMoved 不一定带出 :hover（踩过）。 */
        await send('DOM.enable');
        await send('CSS.enable');
        const docRoot = await send('DOM.getDocument', { depth: -1 });
        const hq = await send('DOM.querySelector',
            { nodeId: docRoot.root.nodeId, selector: '.wm-card.is-done' });
        await send('CSS.forcePseudoState', { nodeId: hq.nodeId, forcedPseudoClasses: ['hover'] });
        await sleep(200);
        const hovered = await evalJs(`(() => {
            const el = document.querySelector('.wm-card.is-done');
            if (!el) return null;
            const cs = getComputedStyle(el);
            return { line: cs.borderTopColor, bg: cs.backgroundColor,
                     tint: Number((el.className.match(/\\btint-(\\d)\\b/) || [])[1]) };
        })()`);
        check('S5b 悬停已配对卡片：边框与底色仍是本组颜色（不被 hover 抹回中性灰）',
            !!hovered && Number.isFinite(hovered.tint)
            && hovered.line === tint.pal[hovered.tint].line
            && hovered.bg === tint.pal[hovered.tint].bg,
            JSON.stringify(hovered) + '  期望=' + JSON.stringify(hovered && tint.pal[hovered.tint]));
        await send('CSS.forcePseudoState', { nodeId: hq.nodeId, forcedPseudoClasses: [] });
        const leaky = await evalJs(`(() => {
            for (const ss of document.styleSheets) {
                let rules; try { rules = ss.cssRules; } catch (e) { continue; }
                for (const r of rules) {
                    if (r.selectorText && r.selectorText.indexOf('.wm-board.is-auto') === 0
                        && r.selectorText.includes(':hover') && r.style.borderColor
                        && !r.selectorText.includes(':not(.is-done)')) return r.selectorText;
                }
            }
            return null;
        })()`);
        check('S5b 自动学习模式的 hover 规则排除了已配对卡片（否则 tint 边框会被抹掉）',
            leaky === null, `漏网规则: ${leaky}`);

        /* 补配留下的那一对：验证「最后配上的那对」也照同一套规则上色 */
        b = await board();
        const dEn = byText(b.en, deferredPair.en);
        await clickAt(dEn.x, dEn.y);
        await sleep(90);
        b = await board();
        const dZh = byText(b.zh, deferredPair.zh);
        await clickAt(dZh.x, dZh.y);
        await sleep(620);                     // 跨过 .5s 闪绿；跳关定时器 950ms 才到，来得及读
        const last = await evalJs(`(() => {
            /* ⚠️ 底色会「跳变」（上一个动画撤走时不算过渡），边框却老老实实走 .16s 过渡 ——
               闪绿刚结束就来读，边框会读到插值色（实测 156,218,175 vs 目标 161,219,178）。
               这里临时 transition:none + 强制重排，把边框一次落到目标值再取样，读完恢复。
               （与 S4b「先暂停动画再 seek」同类的取样技巧，不改页面代码。） */
            const cards = Array.from(document.querySelectorAll('.wm-card.is-done'));
            const hold = cards.map(el => { const t = el.style.transition; el.style.transition = 'none'; return t; });
            void document.body.offsetWidth;
            const g = (el) => {
                const cs = getComputedStyle(el);
                const m = (el.className.match(/\\btint-(\\d)\\b/) || [])[1];
                return { tint: m === undefined ? null : Number(m),
                         bg: cs.backgroundColor, line: cs.borderTopColor };
            };
            const en = cards.find(c => c.innerText.trim() === ${JSON.stringify(deferredPair.en)});
            const zh = cards.find(c => c.innerText.trim() === ${JSON.stringify(deferredPair.zh)});
            const out = { en: en ? g(en) : null, zh: zh ? g(zh) : null,
                          doneCount: cards.length,
                          total: document.querySelectorAll('.wm-card').length };
            cards.forEach((el, i) => { el.style.transition = hold[i]; });
            return out;
        })()`);
        const usedTints = groups.map(([t]) => t);
        check('S5b 最后配上的那一对同样成组（同一 tint、与前面各对不同、取自本组变量）',
            !!last.en && !!last.zh && last.en.tint !== null && last.en.tint === last.zh.tint
            && usedTints.indexOf(last.en.tint) < 0
            && last.en.bg === tint.pal[last.en.tint].bg
            && last.zh.line === tint.pal[last.zh.tint].line,
            `${JSON.stringify(last)}  已用 tint=${usedTints.join(',')}`);
        check('S5b 整关配完后每张卡都上了色（无遗漏）',
            last.doneCount === last.total, `${last.doneCount} / ${last.total}`);
        await shot('05b-desktop-tinted-review');
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
    /* openBookPicker 会顺手预取大学词库（约 225KB gzip）：先等它到货，
       免得下面几条字面量断言跟「正在载入」抢跑 */
    await sleep(1400);
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
    check('弹出框列出全部 26 册（小学 8 + 初中 5 + 高中 11 + 大学 2）',
        picker && picker.items.length === 26, picker ? `items=${picker.items.length}` : '');
    check('弹出框标记了当前册', picker && picker.active === 0, picker ? `active=${picker.active}` : '');
    check('册次按学段分组显示（小学 / 初中 / 高中 / 大学 四个分组）',
        picker && picker.groups.length === 4 && /小学/.test(picker.groups[0]) && /初中/.test(picker.groups[1])
        && /高中/.test(picker.groups[2]) && /大学/.test(picker.groups[3]),
        picker ? picker.groups.join(' | ') : '');
    check('大学分组标题是「大学 · 2 册 · 13159 词」',
        picker && /大学/.test(picker.groups[3] || '') && /2 册 · 13159 词/.test(picker.groups[3] || ''),
        picker ? picker.groups[3] : '');
    /* 学段标题展示的是「N 册 · M 词（已学 K）」而不是关卡数：词汇量是用户关心的量级。
       注意两处前缀/后缀：① 含当前册的那个学段前面会挂一枚「当前册名 · 」标记；
       ② 末尾追加「（已学 K）」。所以下面用 /(\d+) 册 · (\d+) 词/ 取数，
       期望值从页面数据算出来比硬编码稳（词库扩容后不会烂）。 */
    const pickerWords = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const heads = Array.from(document.querySelectorAll('.wm-book-group-head'))
            .map(e => e.querySelector('.wm-book-group-sub').innerText.replace(/\\s+/g, ' ').trim());
        const stages = {};
        d.books.forEach(b => {
            const s = b.stage || '小学';
            stages[s] = stages[s] || { books: 0, words: 0 };
            stages[s].books++; stages[s].words += b.wordCount;
        });
        return { heads, stages, total: d.books.reduce((a, b) => a + b.wordCount, 0) };
    })()`);
    check('学段标题是「N 册 · M 词（已学 K）」（词汇量优先），不显示关卡数',
        pickerWords.heads.length === 4
        && pickerWords.heads.every(h => /(\d+) 册 · (\d+) 词（已学 \d+）$/.test(h) && !/关/.test(h)),
        pickerWords.heads.join(' | '));
    check('每个学段的册数与词汇量 = 该学段各册之和（小学 8 / 初中 5 / 高中 11 / 大学 2）',
        ['小学', '初中', '高中', '大学'].every((s, i) => {
            const m = (pickerWords.heads[i] || '').match(/(\d+) 册 · (\d+) 词/);
            const e = pickerWords.stages[s];
            return !!m && !!e && +m[1] === e.books && +m[2] === e.words;
        }) && pickerWords.total >= 20000,
        JSON.stringify(pickerWords));
    check('每个学段标题都是可折叠按钮（带箭头图标）', picker && picker.carets === 4,
        picker ? `carets=${picker.carets}` : '');
    check('默认只展开当前册所在学段（小学），其余三个学段折叠',
        picker && JSON.stringify(picker.open) === JSON.stringify([true, false, false, false]),
        picker ? JSON.stringify(picker.open) : '');
    const g0 = await pickerGroups();
    check('折叠生效：初中 / 高中 / 大学三个组的册次一个都不可见',
        picker && picker.shown === g0[0].shown && g0[1].shown === 0 && g0[2].shown === 0
        && g0[3].shown === 0,
        JSON.stringify(g0.map(x => x.shown)));
    check('三个学段标题始终都在可视区内（不会被展开的册次顶出滚动区）',
        g0.every(x => x.headTop >= x.listTop - 1 && x.headBottom <= x.listBottom + 1),
        JSON.stringify(g0.map(x => `${x.headTop}~${x.headBottom}/${x.listTop}~${x.listBottom}`)));
    check('展开组的页码数正确（小学 8 / 初中 5 / 高中 11 / 大学 2）',
        g0[0].total === 8 && g0[1].total === 5 && g0[2].total === 11 && g0[3].total === 2,
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
        JSON.stringify(g1.map(x => x.open)) === JSON.stringify([false, true, false, false]),
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
        const themes = [...new Set(d.levels.map(l => l.theme))];
        return { levels: d.levels.length, theme: d.currentLevel ? d.currentLevel.theme : '',
                 themeCount: themes.length, first3: themes.slice(0, 3).join(','),
                 bookWord: meta.wordCount, declared: meta.levelCount };
    })()`);
    // 高中 11 册已按「跨学段统一语义域」重分类（原先主题是课本单元 Unit 1~5，
    // 段内词汇风马牛不相及）。此处只断言「主题是中文语义域名、不再是 Unit N」，
    // 不写死具体域名 —— 域表将来调整时这条不该红。
    check('高中册按语义域切关（主题不再是课本单元 Unit N）',
        /^[\u4e00-\u9fa5]{2,8}$/.test(hsInfo.theme || '') && !/^Unit/.test(hsInfo.theme || ''),
        `theme=${hsInfo.theme}`);
    check('高中册主题数 = 语义域数（远多于原来的 5 个课本单元）',
        hsInfo.themeCount >= 25, `themes=${hsInfo.themeCount} first3=${hsInfo.first3}`);
    // 词量是源清单口径（稳定）；关数随「每关几对」变化，故只与引导数据自洽校验，不写死
    check('必修1 收录 311 词且关数与引导数据自洽',
        hsInfo.bookWord === 311 && hsInfo.levels === hsInfo.declared && hsInfo.levels > 0,
        `words=${hsInfo.bookWord} levels=${hsInfo.levels} declared=${hsInfo.declared}`);
    check('换到高中册后学段分组自动展开高中',
        (await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            return d.stageOpen['高中'] === true;
        })()`)) === true);

    /* ---------- S6c. 大学独立词源（服务端契约 + 按需加载） ---------- */
    console.log('\n— S6c 大学独立词源 —');
    const boot = JSON.parse(await (await fetch(BASE + '/learn/word-match/data')).text());
    check('S6c 首屏引导数据只带大学**册元信息**（extraBooks），一个关卡都不带',
        boot.books.length === 24 && (boot.extraBooks || []).length === 2
        && !boot.levels['cet-4'] && !boot.levels['cet-6']
        && boot.extraBooks.every(x => x.stage === '大学'),
        `books=${boot.books.length} extra=${(boot.extraBooks || []).length}`
        + ` hasCetLevels=${!!boot.levels['cet-4']}`);
    /* 域清单不写死在脚本里：直接读源清单同目录的细域树，免得两处各抄一遍然后漂掉。
       （68 个粗域里有 35 个已按语义细分成 98 个小分类，共 131 个细域。） */
    const CET_TREE = fs.readFileSync(
        new URL('../src/test/resources/learn/cet-words-source/cet-domain-tree.tsv', import.meta.url), 'utf8');
    const CET_DOMAINS = CET_TREE.split('\n').filter(l => l.trim()).map(l => l.split('\t')[1]);
    check('S6c 细域树可读、域数上百（大域已细分）',
        CET_DOMAINS.length >= 120 && new Set(CET_DOMAINS).size === CET_DOMAINS.length,
        `${CET_DOMAINS.length} 个细域`);
    const cetBaseTheme = (t) => String(t || '').split(' · ')[0];

    check('S6c 大学元信息：四级 7508 词 / 1257 关、六级 5651 词 / 946 关',
        boot.extraBooks[0].id === 'cet-4' && boot.extraBooks[0].wordCount === 7508
        && boot.extraBooks[0].levelCount === 1257 && boot.extraBooks[1].id === 'cet-6'
        && boot.extraBooks[1].wordCount === 5651 && boot.extraBooks[1].levelCount === 946,
        boot.extraBooks.map(x => `${x.id}:${x.wordCount}词/${x.levelCount}关`).join(' | '));

    const cet = JSON.parse(await (await fetch(BASE + '/learn/word-match/cet')).text());
    check('S6c /learn/word-match/cet 结构与首屏一致（books + levels），前端无差别合并',
        cet.books.length === 2 && cet.levels['cet-4'].length === 1257
        && cet.levels['cet-6'].length === 946 && cet.books.every(x => x.stage === '大学'),
        cet.books.map(x => x.id).join(','));
    const cetLevels = (cet.levels['cet-4'] || []).concat(cet.levels['cet-6'] || []);
    const cetWords = cetLevels.reduce((a, l) => a + l.pairs.length, 0);
    let cetDupLevels = 0;                          // 关内英文重复 -> 棋盘上会出现两张同样的卡
    cetLevels.forEach(l => {
        const s = new Set(l.pairs.map(p => p.en.toLowerCase()));
        if (s.size !== l.pairs.length) cetDupLevels++;
    });
    check('S6c 大学合计 2203 关 / 13159 条（不去重：原表每条都在，同一个词的多条也都在）',
        cetWords === 13159 && cetLevels.length === 2203, `words=${cetWords} levels=${cetLevels.length}`);
    check('S6c 每关 3~7 对、且关内英文不重复',
        cetLevels.every(l => l.pairs.length >= 3 && l.pairs.length <= 7) && cetDupLevels === 0,
        `dupLevels=${cetDupLevels}`);
    /* 粒度回归：细分前「性质与特征」一个域就装了 818 条（四级 84 关全是形容词），
       同一关的词彼此无关。这里按主题聚合词条数，超过 150 就说明粒度又变粗了。
       ⚠️ 必须**按册**聚合：四级和六级各自 ≤150，但同一个细域在两册里都有词，
       合起来算会假报警（实测「职业与从业者」四级+六级 = 175，各自其实都没超）。 */
    const cetDomainWords = new Map();          // 四级那一本（域数用它衡量）
    const cetDomainByBook = { 'cet-4': cetDomainWords, 'cet-6': new Map() };
    ['cet-4', 'cet-6'].forEach(id => {
        const m = cetDomainByBook[id];
        (cet.levels[id] || []).forEach(l => {
            const base = cetBaseTheme(l.theme);
            m.set(base, (m.get(base) || 0) + l.pairs.length);
        });
    });
    const cetCoarse = [];
    ['cet-4', 'cet-6'].forEach(id => cetDomainByBook[id].forEach((n, d) => {
        if (n > 150) cetCoarse.push([id + ' ' + d, n]);
    }));
    check('S6c 每个细域在**单册内**不超过 150 条词（细分前最粗的域 818 条，现降到 149）',
        cetCoarse.length === 0 && cetDomainWords.size >= 110 && cetDomainByBook['cet-6'].size >= 100,
        `四级域数=${cetDomainWords.size} 六级域数=${cetDomainByBook['cet-6'].size} 超限=${JSON.stringify(cetCoarse.slice(0, 3))}`);
    check('S6c 主题只由细域树里登记的域而来（同一域多关时带「 · N」后缀）',
        cetLevels.every(l => CET_DOMAINS.indexOf(cetBaseTheme(l.theme)) >= 0),
        Array.from(new Set(cetLevels.map(l => cetBaseTheme(l.theme)))).join('/'));

    // 界面上真进一次：展开「大学」学段 -> 点「四级」
    await clickSel('.wm-book-btn');
    await sleep(260);
    await openStage('大学');
    const cetGroup = await evalJs(`(() => {
        const gs = Array.from(document.querySelectorAll('.wm-book-group'));
        const g = gs.find(e => /大学/.test(e.querySelector('.wm-book-group-name').innerText));
        if (!g) return null;
        const items = Array.from(g.querySelectorAll('.wm-book-item'));
        return { head: g.querySelector('.wm-book-group-name').innerText.trim(),
                 labels: items.map(e => e.querySelector('.wm-book-label').innerText.trim()),
                 metas: items.map(e => e.querySelector('.wm-book-meta').innerText.replace(/\\s+/g, ' ').trim()) };
    })()`);
    check('S6c 册次弹层新增「大学」分组，含四级 / 六级两册',
        !!cetGroup && cetGroup.head === '大学' && cetGroup.labels.join(',') === '四级,六级',
        cetGroup ? cetGroup.labels.join(',') : 'null');
    /* 卡片文案现在是「7508 词（已学 0） · 1257 关」——中间插了已学词汇的括号，
       所以别写死成「7508 词 · 1257 关」（上一轮加「已学词汇」时这里漏改了）。 */
    check('S6c 大学册卡片显示词数（含已学）与关数（四级 7508 词 · 1257 关 / 六级 5651 词 · 946 关）',
        !!cetGroup && /7508 词（已学 \d+） · 1257 关/.test(cetGroup.metas[0])
        && /5651 词（已学 \d+） · 946 关/.test(cetGroup.metas[1]),
        cetGroup ? cetGroup.metas.join(' | ') : 'null');
    await shot('08c-desktop-book-picker-cet');

    await clickBookItem('四级');
    await sleep(650);
    b = await board();
    bookLabel = await evalJs(`document.querySelector('.wm-book-btn').innerText.trim()`);
    check('S6c 可以切到四级册（词库按需拉取后沿用同一套渲染，无分支）',
        /四级/.test(bookLabel), `btn=${bookLabel}`);
    const cetState = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { loaded: d.cetLoaded, id: d.bookId, levels: d.levels.length,
                 theme: d.currentLevel ? d.currentLevel.theme : '',
                 pairs: d.currentLevel ? d.currentLevel.pairs.length : 0 };
    })()`);
    check('S6c 四级册：1257 关、本关主题是细域、棋盘按关卡渲染',
        cetState.loaded === true && cetState.id === 'cet-4' && cetState.levels === 1257
        && CET_DOMAINS.indexOf(cetBaseTheme(cetState.theme)) >= 0
        && cetState.pairs >= 3 && cetState.pairs <= 7 && b.en.length === cetState.pairs,
        JSON.stringify(cetState));
    check('S6c 四级关卡的释义是「词性 + 中文」形式（来自考试词表，不是课本词汇表）',
        b.zh.length > 0 && b.zh.every(c => /^[a-z]+[.&]/.test(c.text)),
        b.zh.slice(0, 2).map(c => c.text).join(' | '));
    await shot('08d-desktop-cet4-level1');

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

    // 点右上角的**关闭叉**关掉通关窗（「继续看看」按钮已移除，关闭只走叉/遮罩）
    await clickSel('.wm-modal.is-finish .wm-modal-close');
    await sleep(350);
    b = await board();
    check('点关闭叉后通关窗关闭', b.finishedModal === false, `modal=${b.finishedModal}`);
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

    /* 防回归：「点完之后按钮文字变空白」。
       真机点完会留下**粘滞 :hover**，而 btn-outline-secondary:hover 会把文字色切成
       --bs-btn-hover-color(#fff)（它本来是配深色底的）；白底胶囊若只改 background，
       就成了白字压白底 —— 文字看起来「消失」。这里用 CDP 强制各状态，断言对比度仍在。 */
    await send('DOM.enable');
    await send('CSS.enable');
    const docRoot = await send('DOM.getDocument', { depth: -1 });
    const navNodeIds = [];
    for (const sel of ['.wm-nav > button:first-child', '.wm-nav > button:last-child']) {
        const q = await send('DOM.querySelector', { nodeId: docRoot.root.nodeId, selector: sel });
        navNodeIds.push(q.nodeId);
    }
    const contrastOf = async (idx, pseudo) => {
        await send('CSS.forcePseudoState',
            { nodeId: navNodeIds[idx], forcedPseudoClasses: pseudo ? [pseudo] : [] });
        return evalJs(`(() => {
            const b = document.querySelectorAll('.wm-nav > button')[${idx}];
            const s = getComputedStyle(b);
            const toRgb = c => (c.match(/\\d+/g) || []).map(Number).slice(0, 3);
            return { color: s.color, bg: s.backgroundColor,
                     colorRgb: toRgb(s.color), bgRgb: toRgb(s.backgroundColor),
                     text: b.innerText.trim(), w: Math.round(b.getBoundingClientRect().width) };
        })()`);
    };
    const navStates = [];
    for (const [name, idx] of [['上一关', 0], ['下一关', 1]]) {
        for (const pseudo of [null, 'hover', 'active', 'focus']) {
            const st = await contrastOf(idx, pseudo);
            const delta = st.colorRgb.reduce((a, v, i) => a + Math.abs(v - st.bgRgb[i]), 0);
            navStates.push({ name, pseudo: pseudo || '默认', delta, ...st });
        }
        const wide = await evalJs(`(() => {
            const b = document.querySelectorAll('.wm-nav > button')[${idx}];
            return Math.round(b.getBoundingClientRect().width);
        })()`);
        check(`移动端「${name}」按钮在 hover/active/focus 下文字都看得见`,
            navStates.filter(s => s.name === name).every(s => s.delta > 60),
            navStates.filter(s => s.name === name).map(s => `${s.pseudo}:${s.delta}`).join(' '));
        check(`移动端「${name}」按钮够宽（>=88px，不换行）`, wide >= 88, wide + 'px');
    }
    await send('CSS.forcePseudoState', { nodeId: navNodeIds[0], forcedPseudoClasses: [] });
    await send('CSS.forcePseudoState', { nodeId: navNodeIds[1], forcedPseudoClasses: [] });

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
                 head: document.querySelector('#wm-export-modal .wm-count-total').textContent.trim() };
    })()`);
    check('S12 弹窗打开：26 册全列出、分四个学段',
        dlg.open && dlg.flag && dlg.items === 26 && dlg.stages === 4,
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
    check('S12 全选：26 册全勾上、文案翻成「取消全选」',
        allSel.n === 26 && allSel.all === true && allSel.label === '取消全选',
        `n=${allSel.n} label=${allSel.label}`);
    check('S12 全选后汇总 = 26 册 / 3546 关 / 20191 词（含大学 2 册 / 2203 关 / 13159 词）',
        /已选 26 册/.test(allSel.sum) && /3546 关/.test(allSel.sum) && /20191 词/.test(allSel.sum),
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

    /* 时间戳精确到分钟：word-match-YYYYMMDDHHmm[-N册].html */
    const stampMatch = outName.match(/^word-match-(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(-\d+册)?\.html$/);
    check('S12 文件名形如 word-match-YYYYMMDDHHmm[-N册].html', !!stampMatch, outName);
    if (stampMatch) {
        const d = new Date(+stampMatch[1], +stampMatch[2] - 1, +stampMatch[3],
            +stampMatch[4], +stampMatch[5]);
        const diffMin = Math.abs(Date.now() - d.getTime()) / 60000;
        check('S12 文件名里的时间就是导出时刻（分钟级，误差 < 2 分钟）',
            diffMin < 2, '与当前相差 ' + diffMin.toFixed(1) + ' 分钟');
    }

    const SOURCE_URL = 'https://github.com/renfufei/Story-Creator';
    const extRefs = [...html.matchAll(/(?:src|href)="([^"]*)"/g)]
        .map(m => m[1]).filter(u => !/^data:/.test(u));
    /* 「源码」那条要放行：它是**导航**链接，不加载任何东西，离线点了也只是没反应，
       与「零资源外链」并不冲突。除它之外，script / link / img / 字体 url() 仍必须全为零。 */
    const resRefs = extRefs.filter(u => u !== SOURCE_URL);
    check('S12 产物零资源外链：除「源码」那条跳转外，不存在任何非 data: 的 src / href',
        resRefs.length === 0, resRefs.slice(0, 3).join(' | '));
    check('S12 「源码」条目在产物里保留，且是唯一的绝对 URL（指向本仓库）',
        extRefs.length === 1 && extRefs[0] === SOURCE_URL, extRefs.join(' | '));
    const aSource = /<a[^>]*class="wm-set-item is-source"[^>]*>/;
    check('S12 产物里的「源码」仍是真 <a target=_blank rel=noopener>（不是 JS 跳转）',
        aSource.test(html)
        && /<a[^>]*class="wm-set-item is-source"[^>]*\btarget="_blank"[^>]*>/.test(html)
        && /<a[^>]*class="wm-set-item is-source"[^>]*\brel="noopener noreferrer"[^>]*>/.test(html),
        '需同时带 target=_blank 与 rel=noopener noreferrer');
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

    /* 起始册：写的是「导出时正在学的那一册」（当时是三上），不是勾选列表的第一册
       （本段两次恰好相同，所以另有一段专门验「当前册不在最前」的情形）。
       存储键：产物自带一个带时间戳的专属键 —— file:// 下所有本地文件同源，共用 BOOK_KEY
       会让几个导出件互相串册，也会被线上页面的记录影响。 */
    const dftM = html.match(/window\.__WM_DEFAULT_BOOK__ = "([^"]*)"/);
    check('S12 产物里锁定了起始册 = 导出时正在学的那一册（三上）',
        !!dftM && dftM[1] === 'pep-3-1', dftM ? dftM[1] : '没写 __WM_DEFAULT_BOOK__');
    const keyM = html.match(/window\.__WM_BOOK_KEY__ = "([^"]*)"/);
    check('S12 产物用的是本文件专属的册记忆键（文件名同款时间戳 + 随机后缀）',
        !!keyM && !!stampMatch
        && new RegExp('^word_match_book_solo_' + stampMatch.slice(1, 6).join('') + '-[a-z0-9]{4,10}$')
            .test(keyM[1]),
        keyM ? keyM[1] : '没写 __WM_BOOK_KEY__');

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
                 bookId: d ? d.bookId : '', bookKey: d ? d.bookKey : '',
                 label: d ? d.bookLabel : '',
                 navHeader: !!document.getElementById('site-nav'),
                 closeShown: closeBtn ? getComputedStyle(closeBtn).display !== 'none' : null,
                 hScroll: document.documentElement.scrollWidth - window.innerWidth };
    })()`);
    check('S12 双击打开就能玩：Alpine 已就绪、卡片已渲染',
        solo.alpine === 'object' && solo.cards >= 6 && solo.levels > 0,
        `alpine=${solo.alpine} cards=${solo.cards} levels=${solo.levels}`);
    check('S12 产物里就只有被勾的 2 册，且默认就落在锁定的起始册（三上）',
        solo.books === 2 && solo.bookId === 'pep-3-1' && solo.label === '三年级上册',
        `books=${solo.books} bookId=${solo.bookId} label=${solo.label}`);
    check('S12 单机件里读的是本文件专属的册记忆键（不碰线上的 word_match_book_v1）',
        /^word_match_book_solo_\d{12}-[a-z0-9]{4,10}$/.test(solo.bookKey), solo.bookKey);
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
        const src = document.querySelector('.wm-set-item.is-source');
        return { exportDisplay: ex ? getComputedStyle(ex).display : 'missing',
                 sourceVisible: !!src && getComputedStyle(src).display !== 'none',
                 sourceHref: src ? src.getAttribute('href') : '',
                 sourceTag: src ? src.tagName : '',
                 items: document.querySelectorAll('.wm-set-item').length,
                 titles: Array.from(document.querySelectorAll('.wm-set-item'))
                     .filter(it => getComputedStyle(it).display !== 'none')
                     .map(it => it.querySelector('.wm-set-title').innerText.trim()).join(' / ') };
    })()`);
    check('S12 单机件里「导出」隐藏、「源码」保留可见（能跳仓库），其余设置项保留',
        soloSet.exportDisplay === 'none' && soloSet.sourceVisible === true
        && soloSet.sourceTag === 'A' && soloSet.sourceHref === 'https://github.com/renfufei/Story-Creator'
        && soloSet.items === 6, JSON.stringify(soloSet));
    check('S12 单机件里可见的设置项顺序（错题本/重做本关/音效与朗读/源码/重置进度，无「导出」）',
        soloSet.titles === '错题本 / 重做本关 / 音效与朗读 / 源码 / 重置进度', soloSet.titles);
    await shot('27-standalone-settings');

    /* --- 回到线上页面，验「起始册」的两种情形 ---
           ① 当前册在勾选里、但**不在最前** ⇒ 必须用当前册（旧实现只会取第一个册，这条能抓住回退）
           ② 当前册根本没被勾 ⇒ 退到勾选册里最靠前的一册（函数级验，不必再落盘） --- */
    await send('Page.navigate', { url: PAGE });
    await sleep(2400);

    const fb = await evalJs(`(async () => {
        const r = await buildStandaloneWordMatch(['pep-3-1'], 'pep-6-1');
        const t1 = 'window.__WM_DEFAULT_BOOK__ = "';
        const a1 = r.html.indexOf(t1);
        const t2 = 'window.__WORD_MATCH_DATA__ = ';
        const a2 = r.html.indexOf(t2);
        return {
            dft: a1 < 0 ? '' : r.html.slice(a1 + t1.length).split('"')[0],
            books: a2 < 0 ? '' : JSON.parse(r.html.slice(a2 + t2.length).split(';\\n')[0])
                .books.map(function (b) { return b.id; }).join(','),
            name: r.filename
        };
    })()`);
    check('S12 没勾当前册（当前册=pep-6-1）时，起始册退到勾选册里最靠前的那一册',
        fb.dft === 'pep-3-1' && fb.books === 'pep-3-1'
        && /^word-match-\d{12}\.html$/.test(fb.name || ''),
        JSON.stringify(fb));

    const devSel = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        d.applyBook('pep-3-2', true);                         // 当前册切到「三年级下册」
        d.openExport();                                       // 打开导出弹窗（默认勾上当前册）
        d.exportSel = { 'pep-3-1': true, 'pep-3-2': true };   // 手动勾成「三上 + 三下」
        return { book: d.bookId, n: d.exportSelCount };
    })()`);
    check('S12 起始册分支准备：当前册 = 三下、勾选 = 三上 + 三下（当前册不在最前）',
        devSel.book === 'pep-3-2' && devSel.n === 2, JSON.stringify(devSel));
    await sleep(320);
    /* 第二次导出与第一次往往落在同一分钟 ⇒ 文件名完全相同。headless 下 Chrome 对同名下载是
       **直接覆盖**（桌面端会另存成 "xxx (1).html"），所以「目录里多了个文件」这条判据不成立，
       先清空下载目录再等新文件。 */
    for (const f of fs.readdirSync(downloadDir)) {
        try { fs.rmSync(path.join(downloadDir, f), { force: true }); } catch (e) { /* ignore */ }
    }
    await clickSel('#wm-export-go');
    let outFile2 = null;
    for (let i = 0; i < 70 && !outFile2; i++) {
        await sleep(400);
        const fresh = fs.readdirSync(downloadDir).filter(n => !n.endsWith('.crdownload'));
        if (fresh.length) outFile2 = path.join(downloadDir, fresh[0]);
    }
    check('S12 第二次导出也真的落盘了文件', !!outFile2, outFile2 || '没有新文件');

    if (outFile2) {
        const html2 = fs.readFileSync(outFile2, 'utf8');
        const d2 = html2.match(/window\.__WM_DEFAULT_BOOK__ = "([^"]*)"/);
        check('S12 起始册取「导出时正在学的那一册」（三下），而不是勾选列表的第一册（三上）',
            !!d2 && d2[1] === 'pep-3-2', d2 ? d2[1] : '没写 __WM_DEFAULT_BOOK__');
        await send('Page.navigate', { url: 'file://' + outFile2 });
        await sleep(2200);
        const solo2 = await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            return { bookId: d.bookId, label: d.bookLabel, books: d.books.length };
        })()`);
        check('S12 打开第二份产物默认就落在三下（专属键各记各的，没被第一份带跑）',
            solo2.bookId === 'pep-3-2' && solo2.label === '三年级下册' && solo2.books === 2,
            JSON.stringify(solo2));
    }

    check('S12 打开产物没有新增未捕获错误',
        runtimeErrors.length === errBefore, runtimeErrors.slice(errBefore).join(' | '));

    /* ---------- S13. 搜索单词（工具栏「搜索」→ 弹窗 → 查看跳转） ---------- */
    console.log('\n— S13 搜索单词 —');
    /* S12 最后停在 file:// 的导出件上，这里回线上页重新开始 */
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(320);

    const sBtn = await evalJs(`(() => {
        const btn = document.querySelector('.wm-search-btn');
        const set = document.querySelector('.wm-set-btn');
        if (!btn || !set) return null;
        const rb = btn.getBoundingClientRect(), rs = set.getBoundingClientRect();
        return { left: Math.round(rb.left), setLeft: Math.round(rs.left),
                 sameRow: Math.abs(rb.top - rs.top) <= 2,
                 icon: !!btn.querySelector('i.bi-search'),
                 text: btn.innerText.trim(),
                 domFirst: !!(btn.compareDocumentPosition(set) & Node.DOCUMENT_POSITION_FOLLOWING),
                 haspopup: btn.getAttribute('aria-haspopup'),
                 expanded: btn.getAttribute('aria-expanded'),
                 h: Math.round(rb.height) };
    })()`);
    check('S13 工具栏「搜索」按钮就在「设置」左边（同一行、DOM 顺序在前、left 更小）',
        !!sBtn && sBtn.sameRow && sBtn.domFirst && sBtn.left < sBtn.setLeft,
        sBtn ? `search.left=${sBtn.left} set.left=${sBtn.setLeft} sameRow=${sBtn.sameRow} domFirst=${sBtn.domFirst}` : 'null');
    check('S13 搜索按钮带放大镜图标 + 文字「搜索」，高度够点',
        !!sBtn && sBtn.icon && /搜索/.test(sBtn.text) && sBtn.h >= 28,
        sBtn ? `text=${sBtn.text} h=${sBtn.h}` : 'null');
    check('S13 搜索按钮带 aria 状态（可访问）',
        !!sBtn && sBtn.haspopup === 'true' && sBtn.expanded === 'false',
        sBtn ? `haspopup=${sBtn.haspopup} expanded=${sBtn.expanded}` : 'null');
    check('S13 初始状态下搜索弹窗是收起的', !(await searchVisible()));

    await clickSel('.wm-search-btn');
    await sleep(300);
    const sBox = await evalJs(`(() => {
        const m = document.getElementById('wm-search-modal');
        if (!m) return null;
        const r = m.getBoundingClientRect();
        const close = m.querySelector('.wm-modal-close');
        const cr = close ? close.getBoundingClientRect() : null;
        const inp = document.getElementById('wm-search-input');
        const go = document.getElementById('wm-search-go');
        const emptyEl = m.querySelector('.wm-search-empty');
        return {
            expanded: document.querySelector('.wm-search-btn').getAttribute('aria-expanded'),
            focused: document.activeElement === inp,
            hasInput: !!inp, hasGo: !!go,
            goText: go ? go.innerText.trim() : '',
            placeholder: inp ? (inp.getAttribute('placeholder') || '') : '',
            closeIcon: !!close && !!close.querySelector('i.bi-x-lg'),
            closeTop: cr ? Math.round(cr.top - r.top) : -1,
            closeRight: cr ? Math.round(r.right - cr.right) : -1,
            empty: emptyEl ? emptyEl.innerText.trim() : '',
            left: Math.round(r.left), right: Math.round(r.right), vw: window.innerWidth,
            inputAbove: (() => {
                if (!inp || !go) return false;
                const ri = inp.getBoundingClientRect(), rg = go.getBoundingClientRect();
                return Math.abs(ri.top - rg.top) <= 2 && ri.left < rg.left;
            })(),
            /* 「搜索」两个字曾经被输入框挤成竖排两行 —— 按钮必须 nowrap 且不被压缩 */
            goNoWrap: (() => {
                if (!go) return false;
                const cs = getComputedStyle(go);
                return cs.whiteSpace === 'nowrap' && go.scrollWidth <= go.clientWidth + 1;
            })()
        };
    })()`);
    check('S13 点搜索按钮弹出对话框（aria-expanded 变 true、遮罩可见）',
        !!sBox && (await searchVisible()) && sBox.expanded === 'true',
        sBox ? `expanded=${sBox.expanded}` : 'null');
    check('S13 对话框上方是「输入框 + 搜索按钮」（输入框在左、按钮在右同一行）',
        !!sBox && sBox.hasInput && sBox.hasGo && /搜索/.test(sBox.goText) && sBox.inputAbove,
        sBox ? `go=${sBox.goText} 同排=${sBox.inputAbove} ph=${sBox.placeholder}` : 'null');
    check('S13 输入框自动获得焦点（打开就能打字）', !!sBox && sBox.focused, sBox ? `focus=${sBox.focused}` : 'null');
    check('S13 搜索按钮不被输入框挤窄（「搜索」两字不竖排换行）',
        !!sBox && sBox.goNoWrap, sBox ? `nowrap=${sBox.goNoWrap}` : 'null');
    check('S13 右上角有关闭小图标（x-lg，落在弹窗右上角）',
        !!sBox && sBox.closeIcon && sBox.closeTop >= 0 && sBox.closeTop <= 24
        && sBox.closeRight >= 0 && sBox.closeRight <= 28,
        sBox ? `top=${sBox.closeTop} right=${sBox.closeRight}` : 'null');
    check('S13 弹窗不超出视口', !!sBox && sBox.left >= 0 && sBox.right <= sBox.vw + 1,
        sBox ? `left=${sBox.left} right=${sBox.right} vw=${sBox.vw}` : 'null');
    check('S13 还没搜时给的是引导文案（不是「没找到」）',
        !!sBox && /输入英文或中文/.test(sBox.empty), sBox ? sBox.empty : 'null');
    await shot('28-search-dialog');

    await clickSel('#wm-search-modal .wm-modal-close');
    await sleep(260);
    check('S13 点右上角的关闭图标可关闭弹窗', !(await searchVisible()));
    await clickSel('.wm-search-btn');
    await sleep(280);

    /* --- ① 完全匹配：排在最前面 --- */
    await typeSearch('liberty');
    await clickSel('#wm-search-go');
    await sleep(360);
    const sLib = await searchState();
    check('S13 搜 liberty：完全匹配排第一行（整行高亮 + 「完全匹配」徽标）',
        sLib.dom.length > 0 && sLib.dom[0].en.toLowerCase() === 'liberty'
        && sLib.dom[0].badge === '完全匹配' && sLib.dom[0].exact,
        JSON.stringify(sLib.dom[0] || null));
    check('S13 每行都含 单词 / 翻译 / 册次 + 关卡号 / 【查看】按钮',
        sLib.dom.length > 0
        && sLib.dom.every(i => i.en && i.zh && /第 \d+ 关/.test(i.src) && i.act === '查看'),
        JSON.stringify(sLib.dom.slice(0, 2)));
    check('S13 出处行带学段前缀（「高中 · 必修4 · 第 12 关」这种，光写册名看不出学段）',
        sLib.dom.length > 0
        && sLib.dom.every(i => /^(小学|初中|高中|大学) · .+ · 第 \d+ 关/.test(i.src)),
        sLib.dom.map(i => i.src).slice(0, 3).join(' | '));
    check('S13 高中册明确标出「高中」（必修1~5 / 选修6~11 不带年级，只写「必修4」会被当成 4 年级）',
        sLib.dom.some(i => /^高中 · (必修|选修)\d+ · 第 \d+ 关/.test(i.src)),
        sLib.dom.map(i => i.src).join(' | '));
    const srcLine = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const hi = d.books.filter(b => b.stage === '高中')[0];
        const lo = d.books.filter(b => b.stage === '小学')[0];
        const un = d.stageOfBook('__不存在的册__');
        return { hs: d.srcText(hi.id, hi.label, 11), ps: d.srcText(lo.id, lo.label, 0), un: un };
    })()`);
    check('S13 共用的出处行生成器：高中「高中 · 必修1 · 第 12 关」、小学「小学 · 三年级上册 · 第 1 关」（错题本行与跳转提示同用）',
        /^高中 · 必修1 · 第 12 关$/.test(srcLine.hs)
        && /^小学 · 三年级上册 · 第 1 关$/.test(srcLine.ps),
        JSON.stringify(srcLine));
    check('S13 册次查不到时不编造学段（宁可只显示册名）', srcLine.un === '', `stageOfBook(未知册)=${JSON.stringify(srcLine.un)}`);
    check('S13 完全匹配排在前缀匹配之前（rank 单调不减）',
        sLib.hits.every((h, i) => i === 0 || sLib.hits[i - 1].rank <= h.rank),
        sLib.hits.map(h => h.rank).join(','));
    check('S13 同一个词的每处出处都列出来（liberty 在四级册里出现两次，两条都要在）',
        sLib.hits.filter(h => h.en.toLowerCase() === 'liberty').length >= 2,
        `liberty 命中 ${sLib.hits.filter(h => h.en.toLowerCase() === 'liberty').length} 条`);
    check('S13 角标同时报「单词数」与「出处数」（一个词可能多出处，只报一个数会让人以为少列了）',
        /\d+ 个单词/.test(sLib.count) && /\d+ 处/.test(sLib.count), sLib.count);
    await shot('29-search-liberty');

    const sUpper = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const lower = d.searchHits.map(h => h.en).join(',');
        d.searchQ = 'LiBeRtY';
        d.runSearch();
        return { lower: lower, upper: d.searchHits.map(h => h.en).join(',') };
    })()`);
    check('S13 英文大小写不敏感（LiBeRtY 与 liberty 命中同一批词）',
        !!sUpper.lower && sUpper.lower === sUpper.upper, `${sUpper.lower}  VS  ${sUpper.upper}`);

    /* --- ② 前缀匹配：接在后面，最多 5 个单词 --- */
    await typeSearch('lib');
    await clickSel('#wm-search-go');
    await sleep(360);
    const sPre = await searchState();
    const preWords = Array.from(new Set(sPre.hits.map(h => h.en.toLowerCase())));
    check('S13 搜 lib：没有完全匹配时全部走前缀匹配（rank 只有 1）',
        sPre.hits.length > 0 && sPre.hits.every(h => h.rank === 1),
        sPre.hits.map(h => `${h.en}:${h.rank}`).join(','));
    check('S13 前缀匹配的英文全部以关键词开头',
        preWords.length > 0 && preWords.every(w => w.indexOf('lib') === 0), preWords.join(','));
    check('S13 前缀匹配最多取前 5 个单词',
        preWords.length > 0 && preWords.length <= 5, `${preWords.length} 个：${preWords.join(',')}`);
    check('S13 文案说明了当前用的是哪种匹配（前缀）', /前缀匹配/.test(sPre.hint), sPre.hint);

    /* --- ③ 部分匹配：只有前两类都空时才做 --- */
    await typeSearch('berty');
    await clickSel('#wm-search-go');
    await sleep(360);
    const sPart = await searchState();
    const partWords = Array.from(new Set(sPart.hits.map(h => h.en.toLowerCase())));
    check('S13 无完全匹配、也无前缀匹配时才退到部分匹配（berty 落在 rank=2，且命中 liberty）',
        sPart.hits.length > 0 && sPart.hits.every(h => h.rank === 2)
        && sPart.hits.some(h => h.en.toLowerCase() === 'liberty'),
        sPart.hits.map(h => `${h.en}:${h.rank}`).join(','));
    check('S13 部分匹配同样最多 5 个单词',
        partWords.length > 0 && partWords.length <= 5, `${partWords.length} 个：${partWords.join(',')}`);
    check('S13 文案说明了这是部分匹配（含关键词）', /关键词/.test(sPart.hint), sPart.hint);

    await typeSearch('zzqqxx');
    await clickSel('#wm-search-go');
    await sleep(320);
    const sNone = await searchState();
    check('S13 搜不到时给「没找到」文案、且不渲染任何结果行',
        sNone.hits.length === 0 && sNone.dom.length === 0 && /没有找到/.test(sNone.empty), sNone.empty);

    /* --- 中文也能查：释义里出现该中文的条目都该被翻出来 --- */
    await typeSearch('自由');
    await clickSel('#wm-search-go');
    await sleep(360);
    const sZh = await searchState();
    check('S13 支持按中文搜索（释义里含「自由」的条目都能查到）',
        sZh.hits.length > 0 && sZh.hits.every(h => /自由/.test(h.zh)),
        sZh.hits.slice(0, 4).map(h => `${h.en}=${h.zh}`).join(' | '));
    check('S13 中文义项全等算完全匹配（「自由」精确命中 liberty / freedom，不是靠「包含」）',
        sZh.hits.some(h => h.rank === 0 && /^(liberty|freedom)$/i.test(h.en)),
        sZh.hits.filter(h => h.rank === 0).map(h => h.en).join(',') || '(无 rank0)');

    /* --- 【查看】：跳到那一册那一关 --- */
    await typeSearch('liberty');
    await clickSel('#wm-search-go');
    await sleep(360);
    const sHit = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const h = d.searchHits[0];
        return h ? { en: h.en, bookId: h.bookId, bookLabel: h.bookLabel, level: h.level } : null;
    })()`);
    check('S13 准备跳转：取到了第一行命中（含册次与关卡号）',
        !!sHit && !!sHit.bookId && sHit.level >= 0,
        sHit ? `${sHit.bookId} 第 ${sHit.level + 1} 关 ${sHit.en}` : 'null');
    await clickSel('.wm-search-item .wm-search-acts button');
    const jumpedOk = await waitFor('跳到目标关', async () => {
        const j = await evalJs(`(() => { const d = Alpine.$data(document.querySelector('.wm-root'));
            return { book: d.bookId, level: d.levelIndex }; })()`);
        return j.book === sHit.bookId && j.level === sHit.level;
    }, 12000, 150);
    await sleep(220);
    const jumped = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { bookId: d.bookId, label: d.bookLabel, levelIndex: d.levelIndex,
                 cards: d.leftCards.length,
                 pairs: d.currentLevel ? d.currentLevel.pairs.map(p => p.en) : [] };
    })()`);
    check('S13 点【查看】后弹窗自动关闭', !(await searchVisible()));
    check('S13 点【查看】跳到命中那一条所在的册次与关卡',
        jumpedOk && jumped.bookId === sHit.bookId && jumped.levelIndex === sHit.level,
        `目标 ${sHit.bookId} 第 ${sHit.level + 1} 关 → 实际 ${jumped.bookId} 第 ${jumped.levelIndex + 1} 关`);
    check('S13 跳过去后棋盘真的渲染了那一关，且该词就在本关里',
        jumped.cards >= 3 && jumped.pairs.some(e => e.toLowerCase() === sHit.en.toLowerCase()),
        `${jumped.cards} 张卡 · 本关英文 ${jumped.pairs.join(',')}`);
    await shot('30-search-jumped');

    /* --- 关闭方式：点遮罩 / Esc（含焦点在输入框里） --- */
    await clickSel('.wm-search-btn');
    await sleep(280);
    await clickAt(8, 8);
    await sleep(280);
    check('S13 点弹窗外的遮罩可关闭', !(await searchVisible()));

    await clickSel('.wm-search-btn');
    await sleep(280);
    await clickSel('#wm-search-input');
    await sleep(140);
    await send('Input.dispatchKeyEvent',
        { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await send('Input.dispatchKeyEvent',
        { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await sleep(300);
    check('S13 按 Esc 可关闭（焦点在输入框里时同样有效）', !(await searchVisible()));

    /* --- 窄屏：弹窗要完整落在视口内、结果行与【查看】按钮都不出界 --- */
    await send('Emulation.setDeviceMetricsOverride',
        { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await sleep(280);
    await clickSel('.wm-search-btn');
    await sleep(320);
    const sMob = await evalJs(`(() => {
        const m = document.getElementById('wm-search-modal');
        const go = document.getElementById('wm-search-go');
        const r = m.getBoundingClientRect(), gr = go.getBoundingClientRect();
        const items = Array.from(document.querySelectorAll('.wm-search-item')).map(el => {
            const ri = el.getBoundingClientRect();
            const act = el.querySelector('.wm-search-acts button');
            return { right: Math.round(ri.right), h: Math.round(ri.height),
                     actRight: act ? Math.round(act.getBoundingClientRect().right) : -1 };
        });
        /* 右上角的关闭叉必须和标题行的角标错开：窄屏上两者都贴右边，曾经叠在一起 */
        const closeClear = (() => {
            const c = m.querySelector('.wm-modal-close'), n = m.querySelector('.wm-count-total');
            if (!c || !n) return false;
            const a = c.getBoundingClientRect(), b = n.getBoundingClientRect();
            return a.right <= b.left || a.left >= b.right || a.bottom <= b.top || a.top >= b.bottom;
        })();
        return { left: Math.round(r.left), right: Math.round(r.right), vw: window.innerWidth,
                 goW: Math.round(gr.width), goH: Math.round(gr.height),
                 goNoWrap: getComputedStyle(go).whiteSpace === 'nowrap' && go.scrollWidth <= go.clientWidth + 1,
                 items: items, closeClear: closeClear,
                 overflowX: document.documentElement.scrollWidth > window.innerWidth + 1 };
    })()`);
    check('S13 移动端弹窗完整落在视口内、无横向溢出',
        !!sMob && sMob.left >= 0 && sMob.right <= sMob.vw + 1 && !sMob.overflowX,
        sMob ? `left=${sMob.left} right=${sMob.right} vw=${sMob.vw} overflowX=${sMob.overflowX}` : 'null');
    check('S13 移动端「搜索」按钮仍是一行（文字不竖排）',
        !!sMob && sMob.goNoWrap, sMob ? `go=${sMob.goW}x${sMob.goH}` : 'null');
    check('S13 移动端结果行与【查看】按钮都在视口内',
        !!sMob && sMob.items.length > 0
        && sMob.items.every(i => i.right <= sMob.vw + 1 && i.actRight <= sMob.vw + 1),
        sMob ? sMob.items.map(i => `行${i.right}/钮${i.actRight}`).join(' ') : 'null');
    check('S13 移动端右上角的关闭叉不与标题角标重叠',
        !!sMob && sMob.closeClear, sMob ? `closeClear=${sMob.closeClear}` : 'null');
    await shot('31-search-mobile');
    /* ⚠️ 离场前必须关掉：弹窗遮罩是 fixed + inset:0，留着它，下一段的所有点击都会落在遮罩上
       （实测踩过：S14 点类别标签被遮罩吃掉 → 面板一直打不开，还顺手把搜索弹窗关了，
       现象看起来像「新功能坏了」，其实是上一段没收拾干净）。 */
    await clickSel('#wm-search-modal .wm-modal-close');
    await sleep(260);
    check('S13 移动端也能关掉搜索弹窗（离场不留遮挡，后续段落的点击才落得到页面上）',
        !(await searchVisible()));
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
    await sleep(240);

    /* ---------- S14. 类别面板 + 分类练习（过滤模式） ---------- */
    console.log('\n— S14 类别面板 / 分类练习 —');

    const s14PanelVisible = () => evalJs(`(() => {
        const m = document.getElementById('wm-theme-modal');
        return !!m && m.getClientRects().length > 0;
    })()`);

    /* 起点定死：三年级上册（13 关）+ 清空练习进度。
       选它是因为首关类别「学习用品」正好跨 2 关（下标 0、1）——
       进、走、收尾三条路径一套跑全。 */
    const s14 = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        localStorage.removeItem('word_match_progress_v2');
        d.progress = {};
        d.themeScope = null;
        d.themeOpen = false;
        /* 再把可能开着的弹窗一并收掉（遮罩 fixed + inset:0，留着会吃掉后面所有点击） */
        d.searchOpen = false; d.wrongOpen = false; d.bookPickerOpen = false;
        d.settingsOpen = false; d.exportOpen = false;
        d.applyBook('pep-3-1', false);
        const lv = d.allLevels['pep-3-1'];
        const want = [];
        lv.forEach((l, i) => { if (l.theme === lv[0].theme) want.push(i); });
        return { bookId: d.bookId, label: d.bookLabel, levels: lv.length,
                 theme: lv[0].theme, want: want };
    })()`);
    await waitCards();
    await sleep(240);
    check('S14 用例前提：三年级上册 13 关，首关类别正好跨 2 关',
        s14.bookId === 'pep-3-1' && s14.levels === 13 && s14.want.length === 2,
        s14.label + ' / ' + s14.levels + ' 关 / 类别「' + s14.theme + '」→ 关 ' + s14.want.join(','));

    /* 前置守卫：没有任何弹窗遮罩盖住页面，且类别标签中心点确实点得到它自己。
       少了这条，万一上一段留着遮挡，这里会以「面板打不开」的形式出红，
       排查方向会被带偏到新功能上。 */
    const s14Cover = await evalJs(`(() => {
        const open = Array.from(document.querySelectorAll('.wm-modal-backdrop'))
            .filter(b => getComputedStyle(b).display !== 'none');
        const el = document.querySelector('.wm-theme');
        const r = el ? el.getBoundingClientRect() : null;
        const top = r ? document.elementFromPoint(Math.round(r.x + r.width / 2),
                                                  Math.round(r.y + r.height / 2)) : null;
        return { openCount: open.length, hitBadge: !!top && !!el && el.contains(top),
                 topEl: top ? top.tagName + '.' + top.className : null };
    })()`);
    check('S14 前置：没有弹窗遮罩盖住页面，类别标签中心点真能点到它自己',
        !!s14Cover && s14Cover.openCount === 0 && s14Cover.hitBadge,
        s14Cover ? `openBackdrops=${s14Cover.openCount} hit=${s14Cover.topEl}` : 'null');

    const s14Badge = await evalJs(`(() => {
        const el = document.querySelector('.wm-theme');
        if (!el) return null;
        const cs = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        return { tag: el.tagName, cursor: cs.cursor, h: Math.round(r.height),
                 icon: !!el.querySelector('i.bi-tag-fill'),
                 more: !!el.querySelector('.wm-theme-more'),
                 title: el.getAttribute('title') || '',
                 panelOpen: (() => { const m = document.getElementById('wm-theme-modal');
                     return !!m && m.getClientRects().length > 0; })() };
    })()`);
    check('S14 页头类别标签是 <button>（Tab 可达），且有「点得动」的提示（指针 + 右侧小箭头）',
        !!s14Badge && s14Badge.tag === 'BUTTON' && s14Badge.cursor === 'pointer' && s14Badge.more
        && s14Badge.h >= 18,
        s14Badge ? `tag=${s14Badge.tag} cursor=${s14Badge.cursor} more=${s14Badge.more} h=${s14Badge.h}` : 'null');
    check('S14 类别标签带标签图标 + title（说清点开会看到什么）',
        !!s14Badge && s14Badge.icon && /全部单词/.test(s14Badge.title),
        s14Badge ? `title=${s14Badge.title}` : 'null');
    check('S14 初始状态下类别面板是收起的', !!s14Badge && !s14Badge.panelOpen);

    await clickSel('.wm-theme');
    await sleep(320);
    /* 期望值一律由探针自己扫本册关卡算出来（不读 themeInfo），否则是拿实现验自己 */
    const s14Panel = await evalJs(`(() => {
        const m = document.getElementById('wm-theme-modal');
        if (!m || !m.getClientRects().length) return null;
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const lv = d.allLevels[d.bookId];
        const theme = d.currentLevel.theme;
        const want = [];
        lv.forEach((l, i) => { if (l.theme === theme)
            want.push({ index: i, pairs: l.pairs.map(p => ({ en: p.en, zh: p.zh })) }); });
        const groups = Array.from(m.querySelectorAll('.wm-theme-group')).map(g => {
            const dn = g.querySelector('.wm-theme-done');
            return {
                head: g.querySelector('.wm-theme-lv').innerText.trim(),
                n: g.querySelector('.wm-theme-n').innerText.trim(),
                done: !!dn && dn.getClientRects().length > 0,
                words: Array.from(g.querySelectorAll('.wm-theme-word')).map(w => ({
                    en: w.querySelector('.wm-theme-en').innerText.trim(),
                    zh: w.querySelector('.wm-theme-zh').innerText.trim(),
                    speak: !!w.querySelector('.wm-speak')
                }))
            };
        });
        const r = m.getBoundingClientRect();
        return { theme: theme, want: want, groups: groups,
                 h5: m.querySelector('h5').innerText.replace(/\s+/g, ' ').trim(),
                 total: m.querySelector('.wm-count-total').innerText.trim(),
                 desc: m.querySelector('p').innerText.replace(/\s+/g, ' ').trim(),
                 practice: document.getElementById('wm-theme-practice').innerText.replace(/\s+/g, ' ').trim(),
                 practiceIcon: !!document.querySelector('#wm-theme-practice i.bi-play-fill'),
                 cancel: !!document.getElementById('wm-theme-cancel'),
                 closeIcon: !!m.querySelector('.wm-modal-close i.bi-x-lg'),
                 left: Math.round(r.left), right: Math.round(r.right), vw: window.innerWidth };
    })()`);
    check('S14 点类别标签弹出面板，标题写明「类别：<主题>」',
        !!s14Panel && s14Panel.h5 === '类别：' + s14.theme && s14Panel.theme === s14.theme,
        s14Panel ? s14Panel.h5 : 'null');
    check('S14 右上角角标 = 该类别在本册的关卡数 + 单词数（两个数都给）',
        !!s14Panel && s14Panel.total === s14Panel.want.length + ' 关 · '
        + s14Panel.want.reduce((n, g) => n + g.pairs.length, 0) + ' 个单词',
        s14Panel ? s14Panel.total : 'null');
    check('S14 说明文字点明「是哪一册的这个类别」',
        !!s14Panel && s14Panel.desc.includes(s14.label) && s14Panel.desc.includes('按关卡分组'),
        s14Panel ? s14Panel.desc : 'null');
    check('S14 关卡分组与真实数据一一对应（关号 / 每关单词数 / 顺序都按本册关卡顺序）',
        !!s14Panel && s14Panel.groups.length === s14Panel.want.length
        && s14Panel.groups.every((g, i) => g.head === '第 ' + (s14Panel.want[i].index + 1) + ' 关'
            && g.n === s14Panel.want[i].pairs.length + ' 个单词'),
        s14Panel ? s14Panel.groups.map(g => g.head + '/' + g.n).join(' ') : 'null');
    check('S14 每个分组里的单词与词库逐字一致（en/zh 全等、顺序不变、不重不漏）',
        !!s14Panel && s14Panel.groups.every((g, i) =>
            g.words.length === s14Panel.want[i].pairs.length
            && g.words.every((w, j) => w.en === s14Panel.want[i].pairs[j].en
                && w.zh === s14Panel.want[i].pairs[j].zh)),
        s14Panel ? s14Panel.groups.map((g, i) => g.head + ':' + g.words.map(w => w.en).join('/')).join(' | ') : 'null');
    check('S14 每个单词都带朗读按钮（与错题本 / 搜索面板同构）',
        !!s14Panel && s14Panel.groups.every(g => g.words.length > 0 && g.words.every(w => w.speak)));
    check('S14 还没练过时不显示「已完成」标记',
        !!s14Panel && s14Panel.groups.every(g => !g.done));
    check('S14 面板底部是【练习本分类】主按钮（带播放图标）+【关闭】，右上角有关闭叉',
        !!s14Panel && /练习本分类/.test(s14Panel.practice) && s14Panel.practiceIcon
        && s14Panel.cancel && s14Panel.closeIcon,
        s14Panel ? `practice=${s14Panel.practice}` : 'null');
    check('S14 面板完整落在视口内',
        !!s14Panel && s14Panel.left >= 0 && s14Panel.right <= s14Panel.vw + 1,
        s14Panel ? `left=${s14Panel.left} right=${s14Panel.right} vw=${s14Panel.vw}` : 'null');
    await shot('32-theme-panel');

    await clickSel('#wm-theme-modal .wm-modal-close');
    await sleep(260);
    check('S14 点右上角关闭叉可关面板', !(await s14PanelVisible()));
    await clickSel('.wm-theme');
    await sleep(280);
    await send('Input.dispatchKeyEvent',
        { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await send('Input.dispatchKeyEvent',
        { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
    await sleep(280);
    check('S14 按 Esc 也能关（与选册 / 错题本 / 搜索一致）', !(await s14PanelVisible()));

    /* --- 【练习本分类】-> 过滤模式 --- */
    await clickSel('.wm-theme');
    await sleep(280);
    await clickSel('#wm-theme-practice');
    await sleep(380);
    const s14Scope = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const bar = document.querySelector('.wm-scope-bar');
        const nav = document.querySelectorAll('.wm-nav .btn');
        return { modalOpen: (() => { const m = document.getElementById('wm-theme-modal');
                     return !!m && m.getClientRects().length > 0; })(),
                 barVisible: !!bar && bar.getClientRects().length > 0,
                 barText: bar ? bar.innerText.replace(/\s+/g, ' ').trim() : '',
                 theme: d.themeScope ? d.themeScope.theme : null,
                 indexes: d.themeScope ? d.themeScope.indexes.slice() : null,
                 wordCount: d.themeScope ? d.themeScope.wordCount : 0,
                 levelIndex: d.levelIndex, levelsLen: d.levels.length,
                 counter: document.querySelector('.wm-counter').innerText.replace(/\s+/g, ''),
                 navMid: document.querySelector('.wm-nav-mid').innerText.replace(/\s+/g, ' ').trim(),
                 segOut: document.querySelectorAll('.wm-seg.is-out').length,
                 segTotal: document.querySelectorAll('.wm-seg').length,
                 prevDisabled: nav[0].disabled,
                 nextText: nav[nav.length - 1].innerText.replace(/\s+/g, ''),
                 nextDisabled: nav[nav.length - 1].disabled };
    })()`);
    check('S14 点【练习本分类】：面板关闭 + 进入过滤模式（作用域状态条出现）',
        !!s14Scope && !s14Scope.modalOpen && s14Scope.barVisible && s14Scope.theme === s14.theme,
        s14Scope ? `bar=${s14Scope.barText}` : 'null');
    check('S14 作用域正好覆盖该类别那几关（下标与独立算出来的完全一致）',
        !!s14Scope && JSON.stringify(s14Scope.indexes) === JSON.stringify(s14.want),
        s14Scope ? `scope=${JSON.stringify(s14Scope.indexes)} want=${JSON.stringify(s14.want)}` : 'null');
    check('S14 起点 = 该类别第一关；「第 N 关」显示的仍是本册真实关号（不是作用域内的序号）',
        !!s14Scope && s14Scope.levelIndex === s14.want[0]
        && s14Scope.counter === (s14.want[0] + 1) + '/' + s14Scope.levelsLen
        && s14Scope.navMid === '第 ' + (s14.want[0] + 1) + ' 关 / 共 ' + s14Scope.levelsLen + ' 关',
        s14Scope ? `levelIndex=${s14Scope.levelIndex} counter=${s14Scope.counter} navMid=${s14Scope.navMid}` : 'null');
    check('S14 状态条报出类别名与「第 x/n 关 · 共 M 个单词」',
        !!s14Scope && /分类练习/.test(s14Scope.barText) && s14Scope.barText.includes(s14.theme)
        && new RegExp('第 1 / ' + s14.want.length + ' 关 · 共 ' + s14Scope.wordCount + ' 个单词')
            .test(s14Scope.barText),
        s14Scope ? s14Scope.barText : 'null');
    check('S14 进度条把范围外的关卡压暗（is-out = 本册关数 - 作用域关数，总分段数不变）',
        !!s14Scope && s14Scope.segOut === s14Scope.levelsLen - s14.want.length
        && s14Scope.segTotal === s14Scope.levelsLen,
        s14Scope ? `is-out=${s14Scope.segOut} total=${s14Scope.segTotal} levels=${s14Scope.levelsLen}` : 'null');

    /* 光有 is-out 这个类不算数：两种灰（#ebedf0 / #f4f6f8）在 13 段这种密度下**肉眼分不出**，
       等于没标。所以直接量计算出来的颜色，要求「范围内未完成段」与「范围外段」明显不同色。 */
    const s14Seg = await evalJs(`(() => {
        const bar = document.querySelector('.wm-progress');
        const segs = Array.from(bar.querySelectorAll('.wm-seg'));
        const bg = (el) => el ? getComputedStyle(el).backgroundColor : null;
        /* 期望值从页面自己的变量取：.wm-counter b 就是 --wm-accent */
        const counterB = document.querySelector('.wm-counter b');
        const rgb = (c) => (c || '').replace(/\s/g, '');
        return { scoped: bar.classList.contains('is-scoped'),
                 inScope: rgb(bg(segs[1])),            // 作用域内、未完成
                 out: rgb(bg(segs[segs.length - 1])),  // 作用域外
                 current: rgb(bg(segs[0])),
                 accent: rgb(counterB ? getComputedStyle(counterB).color : '') };
    })()`);
    check('S14 进度条标出「本次要练哪几段」：范围内未完成段与范围外段明显不同色（浅蓝 vs 淡灰）',
        !!s14Seg && s14Seg.scoped && s14Seg.inScope !== s14Seg.out
        && s14Seg.inScope !== s14Seg.current && s14Seg.out !== s14Seg.current,
        s14Seg ? `in=${s14Seg.inScope} out=${s14Seg.out} current=${s14Seg.current}` : 'null');
    check('S14 当前关仍是强调色（没被新的浅蓝规则盖掉）',
        !!s14Seg && s14Seg.current === s14Seg.accent,
        s14Seg ? `current=${s14Seg.current} accent=${s14Seg.accent}` : 'null');
    check('S14 站在作用域第一关：「上一关」禁用（走不出作用域）',
        !!s14Scope && s14Scope.prevDisabled && !s14Scope.nextDisabled
        && /下一关/.test(s14Scope.nextText),
        s14Scope ? `prevOff=${s14Scope.prevDisabled} next=${s14Scope.nextText}` : 'null');
    await shot('33-theme-scope');

    await clickSel('.wm-nav .btn:last-child');
    await sleep(340);
    const s14Next = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const nav = document.querySelectorAll('.wm-nav .btn');
        const bar = document.querySelector('.wm-scope-bar');
        return { levelIndex: d.levelIndex, theme: d.currentLevel.theme,
                 prevDisabled: nav[0].disabled,
                 nextText: nav[nav.length - 1].innerText.replace(/\s+/g, ''),
                 nextDisabled: nav[nav.length - 1].disabled,
                 bar: bar ? bar.innerText.replace(/\s+/g, ' ').trim() : '' };
    })()`);
    check('S14 作用域内「下一关」跳到该类别的下一关（不是本册下一关）',
        s14Next.levelIndex === s14.want[1] && s14Next.theme === s14.theme,
        `levelIndex=${s14Next.levelIndex}（期望 ${s14.want[1]}）theme=${s14Next.theme}`);
    check('S14 走到作用域末关：按钮变成「结束分类」（而不是「下一册」）且可点、上一关也可用',
        /结束分类/.test(s14Next.nextText) && !s14Next.nextDisabled && !s14Next.prevDisabled,
        `next=${s14Next.nextText} off=${s14Next.nextDisabled} prevOff=${s14Next.prevDisabled}`);
    check('S14 状态条进度跟着走（第 2/2 关）',
        new RegExp('第 2 / ' + s14.want.length + ' 关').test(s14Next.bar), s14Next.bar);

    await clickSel('.wm-nav .btn:last-child');
    await sleep(360);
    const s14Exit = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const bar = document.querySelector('.wm-scope-bar');
        const nav = document.querySelectorAll('.wm-nav .btn');
        return { scope: d.themeScope, levelIndex: d.levelIndex,
                 barVisible: !!bar && bar.getClientRects().length > 0,
                 segOut: document.querySelectorAll('.wm-seg.is-out').length,
                 nextText: nav[nav.length - 1].innerText.replace(/\s+/g, '') };
    })()`);
    check('S14 点「结束分类」退出过滤模式：作用域清空、状态条收起、进度条恢复整册',
        !!s14Exit && s14Exit.scope === null && !s14Exit.barVisible && s14Exit.segOut === 0,
        s14Exit ? `scope=${JSON.stringify(s14Exit.scope)} bar=${s14Exit.barVisible} is-out=${s14Exit.segOut}` : 'null');
    check('S14 退出时人不被挪走（仍站在刚才那一关），按钮回到「下一关」',
        !!s14Exit && s14Exit.levelIndex === s14.want[1] && /下一关/.test(s14Exit.nextText),
        s14Exit ? `levelIndex=${s14Exit.levelIndex} next=${s14Exit.nextText}` : 'null');

    /* --- 已练过一关：面板标「已完成」，再进过滤模式应从「第一个还没练过的关」起 --- */
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        d.progress[d.bookId] = [${s14.want[0]}];
        d.progress = Object.assign({}, d.progress);
        d.levelIndex = ${s14.want[0]};
        d.loadLevel();
        return true;
    })()`);
    await sleep(220);
    await clickSel('.wm-theme');
    await sleep(300);
    const s14Marks = await evalJs(`(() => {
        const m = document.getElementById('wm-theme-modal');
        return Array.from(m.querySelectorAll('.wm-theme-group')).map(g => {
            const dn = g.querySelector('.wm-theme-done');
            return !!dn && dn.getClientRects().length > 0;
        });
    })()`);
    check('S14 已练过的关卡在面板里标「已完成」（只标它一个）',
        s14Marks.length === 2 && s14Marks[0] === true && s14Marks[1] === false,
        s14Marks.join(','));
    await clickSel('#wm-theme-practice');
    await sleep(360);
    const s14Resume = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const bar = document.querySelector('.wm-scope-bar');
        return { levelIndex: d.levelIndex,
                 indexes: d.themeScope ? d.themeScope.indexes.slice() : null,
                 bar: bar ? bar.innerText.replace(/\s+/g, ' ').trim() : '' };
    })()`);
    check('S14 再点【练习本分类】从该类别第一个「还没练过」的关开始（练过的关不重来）',
        !!s14Resume.indexes && s14Resume.levelIndex === s14.want[1]
        && new RegExp('第 2 / ' + s14.want.length + ' 关').test(s14Resume.bar),
        `levelIndex=${s14Resume.levelIndex} bar=${s14Resume.bar}`);

    await clickSel('.wm-scope-exit');
    await sleep(320);
    check('S14 状态条上的【退出】也能退出过滤模式',
        await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            const bar = document.querySelector('.wm-scope-bar');
            return d.themeScope === null && !(bar && bar.getClientRects().length > 0);
        })()`));

    /* --- 过关收尾：作用域末关打完 -> 「本分类练完」+ 自动退出（不弹本册通关窗） --- */
    await clickSel('.wm-theme');
    await sleep(280);
    await clickSel('#wm-theme-practice');
    await sleep(360);
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        d.levelWrong = 0;
        d.completeLevel();
        return true;
    })()`);
    await sleep(220);
    const s14Finish = await evalJs(`(() => {
        const f = document.querySelector('.wm-flash');
        const fin = document.querySelector('.wm-modal.is-finish');
        return { flash: f && f.getClientRects().length ? f.lastElementChild.textContent.trim() : '',
                 finishModal: !!fin && fin.getClientRects().length > 0 };
    })()`);
    check('S14 作用域末关打完给的是「本分类练完」，不弹「本册全部通关」（那个弹窗讲的是整册 + 下一册）',
        /本分类练完/.test(s14Finish.flash) && !s14Finish.finishModal,
        `flash=${s14Finish.flash} finishModal=${s14Finish.finishModal}`);
    await sleep(1300);   // 等自动退出（1150ms）
    const s14After = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const bar = document.querySelector('.wm-scope-bar');
        const sortNum = a => a.slice().sort((x, y) => x - y);
        return { scope: d.themeScope, levelIndex: d.levelIndex,
                 barVisible: !!bar && bar.getClientRects().length > 0,
                 progress: sortNum(d.progress[d.bookId] || []),
                 stored: sortNum((JSON.parse(localStorage.getItem('word_match_progress_v2') || '{}')[d.bookId]) || []) };
    })()`);
    check('S14 分类练完自动退出过滤模式，人仍站在原地',
        !!s14After && s14After.scope === null && !s14After.barVisible
        && s14After.levelIndex === s14.want[1],
        s14After ? `scope=${JSON.stringify(s14After.scope)} bar=${s14After.barVisible} levelIndex=${s14After.levelIndex}` : 'null');
    check('S14 作用域内过关的进度记在**本册真实关卡下标**上（不是作用域内的序号），且已落盘',
        !!s14After && JSON.stringify(s14After.progress) === JSON.stringify(s14.want)
        && JSON.stringify(s14After.stored) === JSON.stringify(s14.want),
        s14After ? `progress=${JSON.stringify(s14After.progress)} stored=${JSON.stringify(s14After.stored)} want=${JSON.stringify(s14.want)}` : 'null');

    /* --- 换册：作用域里的下标只对上一册有意义，必须自动清掉 --- */
    await clickSel('.wm-theme');
    await sleep(280);
    await clickSel('#wm-theme-practice');
    await sleep(340);
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const other = d.books.find(b => b.id !== d.bookId && b.stage === '小学');
        d.applyBook(other.id, false);
        return true;
    })()`);
    await sleep(280);
    check('S14 换册自动退出分类练习（作用域里的关卡下标只对上一册有意义）',
        await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            const bar = document.querySelector('.wm-scope-bar');
            return d.themeScope === null && !(bar && bar.getClientRects().length > 0);
        })()`));

    /* --- 窄屏：类别标签仍可点、面板与单词行都不出界 --- */
    await send('Emulation.setDeviceMetricsOverride',
        { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await sleep(300);
    await clickSel('.wm-theme');
    await sleep(320);
    const s14Mob = await evalJs(`(() => {
        const m = document.getElementById('wm-theme-modal');
        const r = m.getBoundingClientRect();
        const el = document.querySelector('.wm-theme');
        const br = el.getBoundingClientRect();
        const rows = Array.from(document.querySelectorAll('.wm-theme-word')).map(w => {
            const wr = w.getBoundingClientRect();
            return { right: Math.round(wr.right), h: Math.round(wr.height) };
        });
        return { left: Math.round(r.left), right: Math.round(r.right), vw: window.innerWidth,
                 badgeVisible: el.getClientRects().length > 0 && br.width > 0,
                 badgeLeft: Math.round(br.left), badgeRight: Math.round(br.right),
                 rows: rows,
                 overflowX: document.documentElement.scrollWidth > window.innerWidth + 1 };
    })()`);
    check('S14 移动端：类别标签仍在视口内可点，面板不出横向滚动条',
        !!s14Mob && s14Mob.badgeVisible && s14Mob.badgeLeft >= 0 && s14Mob.badgeRight <= s14Mob.vw + 1
        && s14Mob.left >= 0 && s14Mob.right <= s14Mob.vw + 1 && !s14Mob.overflowX,
        s14Mob ? `badge=${s14Mob.badgeLeft}~${s14Mob.badgeRight} modal=${s14Mob.left}~${s14Mob.right} vw=${s14Mob.vw} overflowX=${s14Mob.overflowX}` : 'null');
    check('S14 移动端：单词行都没出界（自动列宽收成一列也不溢出）',
        !!s14Mob && s14Mob.rows.length > 0 && s14Mob.rows.every(w => w.right <= s14Mob.vw + 1),
        s14Mob ? `行数=${s14Mob.rows.length} right=${s14Mob.rows.map(w => w.right).join(',')}` : 'null');
    await shot('34-theme-mobile');
    await clickSel('#wm-theme-cancel');
    await sleep(260);
    check('S14 移动端点【关闭】可关面板', !(await s14PanelVisible()));
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
    await sleep(240);

    /* ---------- S14b. 分类作用域 × 自动学习 ---------- */
    /* 页面支持 ?wmStep=&wmThink=&wmGap=&wmBookGap= 覆盖自动学习节奏（见 AUTO_* 常量），
       否则「跑完一个分类」要一两分钟。节奏是页面加载时读一次的，所以必须重进页面。
       挑类别有三条约束，每条都对着下面一条断言 —— 少了任何一条，这段就失去判别力：
       ① 首关不在本册第 1 关：否则「从该分类第一关起」与「从第 1 关起」根本没差别；
       ② 末关不是本册最后一关：这样「练完分类就停」才与「本册学完才跨册」分得开
          （两个分支的判据都是 nextIndexOf < 0，混在一起就测不出分支选对没有）；
       ③ 优先跨 2 关、单词最少：2 关才能验「按分类推进」，单词少纯粹为了跑得快。 */
    console.log('\n— S14b 分类作用域 × 自动学习（快速节奏） —');
    const S14B_FAST = '?wmStep=120&wmThink=120&wmGap=150&wmBookGap=150';
    await send('Page.navigate', { url: PAGE + S14B_FAST });
    await waitCards();
    await evalJs(`(() => {
        localStorage.setItem('word_match_book_v1', 'pep-3-1');
        localStorage.removeItem('word_match_progress_v2');
        localStorage.removeItem('word_match_auto_progress_v2');
        return true;
    })()`);
    await send('Page.navigate', { url: PAGE + S14B_FAST });
    await waitCards();
    await sleep(380);

    const s14bPick = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const lv = d.levels;
        const done = {};
        const cands = [];
        /* ⚠️ 必须从 i=0 扫起。从 1 扫的话，首个类别（下标 0）会被当成「首次出现在 1」，
           firstIndex 与实际不符 → 约束①形同虚设，后面几条断言的期望值全跟着错
           （实测踩过：挑出「学习用品」却报 firstIndex=1，而它的 indexes 是 [0,1]）。 */
        for (let i = 0; i < lv.length; i++) {
            const theme = lv[i].theme || '';
            if (!theme || done[theme]) continue;
            done[theme] = true;
            if (i < 1) continue;                            /* 约束①：首关不能在本册第 1 关 */
            const idxs = [];
            lv.forEach((l, j) => { if ((l.theme || '') === theme) idxs.push(j); });
            const last = idxs[idxs.length - 1];
            if (last >= lv.length - 1) continue;            /* 约束②：末关不能是本册最后一关 */
            var words = 0;
            idxs.forEach(j => { words += (lv[j].pairs || []).length; });
            cands.push({ theme: theme, firstIndex: i, lastIndex: last, indexes: idxs,
                         levelCount: idxs.length, words: words });
        }
        const multi = cands.filter(c => c.levelCount >= 2);
        const pool = multi.length ? multi : cands;
        let best = null;
        pool.forEach(c => { if (!best || c.words < best.words) best = c; });
        return { best: best, levels: lv.length, bookId: d.bookId, cands: cands.length, multi: multi.length };
    })()`);
    check('S14b 用例前提：挑到「首关不在本册第 1 关 + 末关不是本册最后一关 + 跨 ≥2 关」的类别（这三条才是判别力所在）',
        !!s14bPick && !!s14bPick.best && s14bPick.best.firstIndex >= 1
        && s14bPick.best.firstIndex === s14bPick.best.indexes[0]
        && s14bPick.best.levelCount >= 2
        && s14bPick.best.lastIndex < s14bPick.levels - 1,
        s14bPick && s14bPick.best
            ? ('「' + s14bPick.best.theme + '」= 关 ' + s14bPick.best.indexes.join(',')
                + ' / 本册共 ' + s14bPick.levels + ' 关 / ' + s14bPick.best.words + ' 词 / 候选 '
                + s14bPick.cands + ' 个（跨多关的 ' + s14bPick.multi + ' 个）')
            : 'null');
    const s14bT = s14bPick.best;

    /* 站到该类别第一关 -> 点类别标签 -> 【练习本分类】 */
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        d.levelIndex = ${s14bT.firstIndex};
        d.loadLevel();
        return true;
    })()`);
    await sleep(300);
    await clickSel('.wm-theme');
    await sleep(320);
    /* 面板打开的这一刻 themeScope 还是 null —— 提示语本就该是「从第 1 关」。
       把这一刻也读下来，和进入分类后的读数对照，才能证明提示是**动态**的，
       而不是我把文案换成了另一个写死的字符串（在进作用域前断言，等于测了个寂寞，踩过）。 */
    const s14bTitleBefore = await evalJs(`(() => {
        const b = document.querySelector('.wm-toolbar-actions button[title*="自动"]');
        return b ? b.getAttribute('title') : '';
    })()`);
    await clickSel('#wm-theme-practice');
    await sleep(380);
    const s14bTitle = await evalJs(`(() => {
        const b = document.querySelector('.wm-toolbar-actions button[title*="自动"]');
        return b ? b.getAttribute('title') : '';
    })()`);
    check('S14b 【自动学习】按钮的提示是**动态**的：分类练习里改口成「从该分类第一关开始」（再写「从第 1 关」就是假话）',
        s14bTitleBefore.indexOf('从第 1 关') >= 0
        && s14bTitle.indexOf('「' + s14bT.theme + '」') >= 0 && s14bTitle.indexOf('分类') >= 0
        && s14bTitle.indexOf('从第 1 关') < 0,
        '进入前=「' + s14bTitleBefore + '」 / 进入后=「' + s14bTitle + '」');
    const s14bScope = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { levelIndex: d.levelIndex,
                 indexes: d.themeScope ? d.themeScope.indexes.slice() : null };
    })()`);
    check('S14b 【练习本分类】把作用域收到该类别那几关，并落在该分类第一关',
        !!s14bScope.indexes
        && JSON.stringify(s14bScope.indexes) === JSON.stringify(s14bT.indexes)
        && s14bScope.levelIndex === s14bT.firstIndex && s14bT.firstIndex !== 0,
        `levelIndex=${s14bScope.levelIndex} want=${s14bT.firstIndex} indexes=${JSON.stringify(s14bScope.indexes)}`);

    /* 挪到该分类**最后一关**再开自动学习：这样「起点」才是可判别的量 ——
       站在第一关点下去的话，接没接上 autoStartIndex 结果都一样，等于没测。 */
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        d.levelIndex = d.themeScope.indexes[d.themeScope.indexes.length - 1];
        d.loadLevel();
        return true;
    })()`);
    await sleep(300);
    const s14bStand = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const a = d.themeScope ? d.themeScope.indexes : [];
        return { levelIndex: d.levelIndex, last: a.length ? a[a.length - 1] : -1 };
    })()`);
    check('S14b 实验前提：开自动学习前人贴在该分类**最后一关**（起点这条才有判别力）',
        s14bStand.levelIndex === s14bT.lastIndex && s14bStand.levelIndex === s14bStand.last,
        `本册下标 ${s14bStand.levelIndex} / 分类末关 ${s14bStand.last}`);

    await clickSel('.wm-toolbar-actions button[title*="自动"]');
    /* 点完立刻同步读一次：startAuto 在点击回调里同步把 levelIndex 挪到 autoStartIndex()，
       这一刻读到的是真正的起点，不用靠轮询去猜（轮询可能错过一个短关） */
    const s14bStart = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const bar = document.querySelector('.wm-auto-bar');
        return { levelIndex: d.levelIndex, auto: d.autoMode,
                 scope: d.themeScope ? d.themeScope.indexes.slice() : null,
                 bar: !!bar && bar.getClientRects().length > 0 };
    })()`);
    const canSpeakB = await speakProbeSetup();   // 静音路径下 speakThenWait 固定等 600ms/词，接管后 120ms
    await sleep(500);
    await shot('35-theme-scope-auto');
    check('S14b 分类练习里点【自动学习】从该分类第一关起（不被拽回本册第 1 关，也不落在刚站的那关）',
        !!s14bStart && s14bStart.auto && s14bStart.levelIndex === s14bT.firstIndex
        && s14bStart.levelIndex !== s14bT.lastIndex
        && JSON.stringify(s14bStart.scope) === JSON.stringify(s14bT.indexes),
        `起点=本册下标 ${s14bStart.levelIndex} / want ${s14bT.firstIndex} / 刚站 ${s14bT.lastIndex} / scope=${JSON.stringify(s14bStart.scope)}`);

    /* 全程轮询：记下走到过哪些关，并盯住有没有踩到作用域外面 */
    let sawAuto = s14bStart.auto, overrun = null, crossBook = false;
    const seenIdx = [s14bStart.levelIndex];
    const t0b = Date.now();
    while (Date.now() - t0b < 30000) {
        const s = await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            const bar = document.querySelector('.wm-auto-bar');
            return { idx: d.levelIndex, auto: d.autoMode, book: d.bookId,
                     scope: d.themeScope ? d.themeScope.indexes.slice() : null,
                     bar: !!bar && bar.getClientRects().length > 0 };
        })()`);
        if (s.auto) {
            sawAuto = true;
            if (seenIdx.indexOf(s.idx) < 0) seenIdx.push(s.idx);
            if (!s.scope || s.scope.indexOf(s.idx) < 0) overrun = s.idx;
        }
        if (s.book !== 'pep-3-1') crossBook = true;
        if (sawAuto && !s.auto) break;      // 自动学习自己收尾了
        await sleep(70);
    }
    const s14bEnd = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const bar = document.querySelector('.wm-auto-bar');
        return { auto: d.autoMode, scope: d.themeScope, book: d.bookId, levelIndex: d.levelIndex,
                 bar: !!bar && bar.getClientRects().length > 0 };
    })()`);
    if (canSpeakB) await speakProbeTeardown();

    check('S14b 自动学习全程没走出分类作用域（没有跑到范围外的关卡上）',
        overrun === null && seenIdx.length > 0 && seenIdx.every(i => s14bT.indexes.indexOf(i) >= 0),
        `越界到本册下标 ${overrun} / 走过 ${JSON.stringify(seenIdx)} / 范围 ${JSON.stringify(s14bT.indexes)}`);
    check('S14b 自动学习按分类把每一关都走到了（不是只走了第一关就停）',
        s14bT.indexes.every(i => seenIdx.indexOf(i) >= 0),
        `走过 ${JSON.stringify(seenIdx)} / 范围 ${JSON.stringify(s14bT.indexes)}`);
    check('S14b 分类末关练完就收尾：自动学习已停 + 作用域已清 + 状态条已收起',
        !!s14bEnd && !s14bEnd.auto && s14bEnd.scope === null && !s14bEnd.bar,
        s14bEnd ? `auto=${s14bEnd.auto} scope=${s14bEnd.scope} bar=${s14bEnd.bar}` : 'null');
    check('S14b 分类练完不跨册、也不越到本册下一关（末关不是本册最后一关，走错分支就会滑到下一册或下标 -1）',
        !crossBook && !!s14bEnd && s14bEnd.book === 'pep-3-1' && s14bEnd.levelIndex === s14bT.lastIndex,
        s14bEnd ? `book=${s14bEnd.book} 停在 ${s14bEnd.levelIndex} / want ${s14bT.lastIndex} crossBook=${crossBook}` : 'null');

    /* 复位：别把 S14 的状态留给后面的运行时错误采集 */
    await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        localStorage.removeItem('word_match_progress_v2');
        d.progress = {};
        d.themeScope = null;
        d.applyBook('pep-3-1', false);
        return true;
    })()`);
    await sleep(240);

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

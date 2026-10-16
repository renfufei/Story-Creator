#!/usr/bin/env node
/**
 * /learn/word-match 定点验证：**自动学习模式的「小手」引导**（2026-10-16）。
 *
 * 背景（用户需求）：
 *   自动学习是机器替人点卡片，用户希望有一只卡通小手浮在**即将被点选**的那张卡
 *   右下角，起指示/引导作用，也更有趣味性。
 *
 * 实现：每张 .wm-card 里都挂一个 .wm-hand（inline SVG 卡通手），由
 *   isAutoSpot(card) 决定显不显示 —— 判据是 data 里的 autoSpot（即将被点选的卡 uid），
 *   autoLoop 在每个「点选前」的小步骤设上、点完立刻清掉；暂停时也收起。
 *   开关：?wmHand=0（整体关掉）。
 *
 * 本脚本逐条守护：
 *  H0 前提：每张卡都有一个 .wm-hand 元素；练习模式（非自动）下一只都不显示
 *  H1 【核心】「看英文」阶段：恰 1 只小手，就挂在即将被点选的那张英文卡上
 *  H2 【核心】「找出中文」阶段：恰 1 只，挂在配对的那张中文卡上
 *  H3 【核心】点完就收回：朗读/记一记阶段 0 只
 *  H4 小手落在卡片右下角（中心在右半 + 下半，只溢出几像素）
 *  H5 暂停 -> 收起；继续 -> 回来
 *  H6 不吃点击（pointer-events:none，小手位置点下去命中的仍是卡牌本身）
 *  H7 纯 SVG 无文字 —— 卡片 innerText 一点没被污染（探针按 innerText 读卡面文案）
 *  H8 ?wmHand=0：元素还在、一只都不显示（是「关掉了」而不是「没渲染」）
 *  H9 320x568：小手完全落在棋盘内（不越界、不额外撑高整页），且自动缩了一档
 *  H10 运行期无 JS 异常 / console.error
 *
 * 用法：node scripts/probe_wm_auto_hand.mjs
 * 可选环境变量：WM_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
// 静音守门：--mute-audio + 注入 src/test/resources/silent-audio.js（管住平台 TTS）
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/word-match';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9336;

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-hand-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    ...SILENT_AUDIO_FLAGS,
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

async function waitFor(label, fn, timeout = 15000, step = 150) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        if (await fn()) return true;
        await sleep(step);
    }
    return false;
}

/* 棋盘视图：默认落在检索首页，得先把 bookId 撑起来再切 view */
async function enterPlayView() {
    try {
        return await evalJs(`(() => {
            if (!window.Alpine) return false;
            const r = document.querySelector('.wm-root');
            if (!r) return false;
            const d = Alpine.$data(r);
            if (!d || !d.bookId) return false;
            if (d.view !== 'play') { d.view = 'play'; if (d.measureTop) d.measureTop(); }
            return true;
        })()`);
    } catch (e) { return false; }
}

async function waitCards() {
    await waitFor('棋盘视图', enterPlayView, 15000, 150);
    const ok = await waitFor('卡片', async () =>
        (await evalJs(`document.querySelectorAll('.wm-card').length`)) > 0, 15000, 150);
    if (!ok) throw new Error('卡片未渲染，后续断言无法进行');
}

/* 快照：自动学习阶段 + **可见**的小手（挂在哪张卡、在卡内什么位置、几何是否越界）。
   一次 evaluate 取全，避免逐字段读之间状态漂移。 */
const SNAP = `(() => {
    const root = document.querySelector('.wm-root');
    if (!root || !window.Alpine) return null;
    const d = Alpine.$data(root);
    const board = document.querySelector('.wm-board');
    const br = board ? board.getBoundingClientRect() : null;
    const hands = Array.from(document.querySelectorAll('.wm-hand'))
        .filter(h => h.getClientRects().length > 0);
    const info = hands.map(h => {
        const card = h.closest('.wm-card');
        const hr = h.getBoundingClientRect();
        const cr = card.getBoundingClientRect();
        const cs = getComputedStyle(h);
        return {
            text: (card.querySelector('.wm-card-text') || {}).innerText || '',
            col: card.closest('.wm-col').className.indexOf('wm-col-en') >= 0 ? 'en' : 'zh',
            cx: Math.round(hr.left + hr.width / 2 - cr.left),   // 小手中心相对卡片左上角
            cy: Math.round(hr.top + hr.height / 2 - cr.top),
            rightOver: Math.round(hr.right - cr.right),          // 向右溢出卡片的像素
            bottomOver: Math.round(hr.bottom - cr.bottom),       // 向下溢出卡片的像素
            cw: Math.round(cr.width), ch: Math.round(cr.height),
            w: Math.round(hr.width), h: Math.round(hr.height),
            pe: cs.pointerEvents, pos: cs.position, anim: cs.animationName,
            inBoard: !!br && hr.left >= br.left - 1 && hr.right <= br.right + 1
                     && hr.top >= br.top - 1 && hr.bottom <= br.bottom + 1,
            boardBottomGap: br ? Math.round(br.bottom - hr.bottom) : null,
            textless: (h.innerText || '').trim() === ''
        };
    });
    const firstEn = d.leftCards.filter(c => !c.done)[0];
    const zhCard = firstEn ? d.rightCards.filter(c => !c.done && c.text === firstEn.mean)[0] : null;
    return {
        stage: d.autoStage, auto: d.autoMode, paused: d.autoPaused, spot: d.autoSpot,
        enUid: firstEn ? firstEn.uid : '', enText: firstEn ? firstEn.text : '',
        zhUid: zhCard ? zhCard.uid : '', zhText: zhCard ? zhCard.text : '',
        n: hands.length, hands: info,
        cards: document.querySelectorAll('.wm-card').length,
        handTotal: document.querySelectorAll('.wm-hand').length,
        scrollH: document.documentElement.scrollHeight, vh: window.innerHeight,
        /* 卡片 innerText 必须 == 卡片里那个文案 span 的 innerText（小手不许混进文字） */
        textClean: Array.from(document.querySelectorAll('.wm-card')).every(c => {
            const t = c.querySelector('.wm-card-text');
            return !t || c.innerText.trim() === t.innerText.trim();
        })
    };
})()`;

/* 取「稳定」的一次快照：连续两次读数（阶段 + 可见小手 + 卡面）完全一致才算数。
   ⚠️ Alpine 的 x-show 走的是 effect + nextTick，阶段刚切换时立刻读会拿到上一帧的 DOM
   ——不 settle 就会误报「该有手的时候没有」。 */
async function settledSample(want, timeout = 30000) {
    const t0 = Date.now();
    let prev = '';
    while (Date.now() - t0 < timeout) {
        const s = await evalJs(SNAP);
        if (s && want(s)) {
            const key = JSON.stringify([s.stage, s.paused, s.n, s.spot, s.hands.map(h => h.text)]);
            if (key === prev) return s;
            prev = key;
        } else {
            prev = '';
        }
        await sleep(60);
    }
    return null;
}

/* 在静音守门之上再包一层：breakOnend=true 时**永不回调 onend** —— 这样 listen 阶段会走
   speakCapMs 的兜底（默认 1.5~6s/条），窗口足够长，「点完英文后小手已收回」才测得稳。 */
async function hookSpeech(breakOnend) {
    return evalJs(`(() => {
        if (!window.speechSynthesis) return false;
        const inner = window.speechSynthesis.speak;
        window.speechSynthesis.speak = function (u) {
            if (${breakOnend ? 'true' : 'false'}) return;
            return inner.call(this, u);
        };
        return true;
    })()`);
}

/* ⚠️ 冒烟默认静音（word_match_sound_v1=0）：soundOn=false 时朗读走「固定等 600ms、根本不调
   speak」的分支 —— 上面那层 hook 就白搭了，listen 阶段会缩回 600ms。要测长窗口就得显式打开。 */
const setSound = (on) => evalJs(`(() => {
    const d = Alpine.$data(document.querySelector('.wm-root'));
    if (d) d.soundOn = ${on ? 'true' : 'false'};
    localStorage.setItem('word_match_sound_v1', ${on ? "'1'" : "'0'"});
    return d ? d.soundOn : null;
})()`);

async function autoOn() {
    return evalJs(`(() => {
        const b = Array.from(document.querySelectorAll('.wm-toolbar-actions button'))
            .find(el => /自动/.test(el.getAttribute('title') || ''));
        if (!b) return false;
        b.click();
        return true;
    })()`);
}
async function autoOff() {
    return evalJs(`(() => {
        const b = document.querySelector('.wm-auto-bar .wm-auto-btn.is-exit');
        if (b) { b.click(); return true; }
        return false;
    })()`);
}
async function clickPause() {
    return evalJs(`(() => {
        const b = document.querySelector('.wm-auto-bar .wm-auto-btn:not(.is-exit)');
        if (!b) return false;
        b.click();
        return true;
    })()`);
}

/* 慢节奏：每步 1.2s —— 每个阶段都有上千米的窗口，settle 采样不会追着状态跑。
   （listen 阶段另靠 hookSpeech(true) 拉长到秒级） */
const SLOW = '?wmStep=1200&wmThink=1200&wmGap=1200';

async function fresh(url, book) {
    await send('Page.navigate', { url });
    await sleep(600);
    await evalJs(`localStorage.clear();
                  localStorage.setItem('word_match_sound_v1', '0');
                  localStorage.setItem('word_match_book_v1', ${JSON.stringify(book || 'pep-3-1')});
                  localStorage.removeItem('word_match_auto_progress_v2');
                  localStorage.removeItem('word_match_progress_v2');`);
    await send('Page.navigate', { url });
    await waitCards();
    await sleep(250);
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
        }
    };

    await installSilentAudio(send);
    await send('Runtime.enable');
    await send('Emulation.setDeviceMetricsOverride', {
        width: 1280, height: 940, deviceScaleFactor: 1, mobile: false
    });

    /* ---------- H0 前提：元素齐备 + 练习模式不显示 ---------- */
    console.log('\n— H0 前提 —');
    await fresh(PAGE + SLOW);
    const pre = await evalJs(SNAP);
    check('每张卡都挂着一个 .wm-hand（左右两列都挂了）',
        pre.cards > 0 && pre.handTotal === pre.cards, `cards=${pre.cards} hands=${pre.handTotal}`);
    check('练习模式（非自动）下一只小手都不显示',
        pre.auto === false && pre.n === 0, `auto=${pre.auto} n=${pre.n}`);

    /* ---------- H1~H3 三个阶段的小手 ---------- */
    console.log('\n— H1~H3 自动学习：小手跟着「即将点选」走 —');
    await hookSpeech(true);      // 朗读永不回调 onend -> listen 窗口拉到秒级，采样稳
    check('已打开语音开关（否则走 600ms 静音分支，上面的 hook 白搭）', (await setSound(true)) === true);
    check('点【自动学习】成功', await autoOn());

    const pickEn = await settledSample(s => s.auto && !s.paused && s.stage === 'pick-en');
    const listen = await settledSample(s => s.auto && !s.paused && s.stage === 'listen');
    const pickZh = await settledSample(s => s.auto && !s.paused && s.stage === 'pick-zh');
    const think = await settledSample(s => s.auto && !s.paused && s.stage === 'think');

    const enH = pickEn && pickEn.hands[0];
    check('【核心】「看英文」阶段：恰 1 只小手', !!pickEn && pickEn.n === 1,
        pickEn ? `n=${pickEn.n} stage=${pickEn.stage}` : '未采到 pick-en 稳定态');
    check('【核心】小手指的就是即将被点选的那张英文卡',
        !!enH && enH.col === 'en' && pickEn.spot === pickEn.enUid && enH.text === pickEn.enText,
        pickEn ? `spot=${pickEn.spot} enUid=${pickEn.enUid} 卡面=「${enH && enH.text}」行=${enH && enH.col}` : 'n/a');

    const zhH = pickZh && pickZh.hands[0];
    check('【核心】「找出中文」阶段：恰 1 只小手', !!pickZh && pickZh.n === 1,
        pickZh ? `n=${pickZh.n} stage=${pickZh.stage}` : '未采到 pick-zh 稳定态');
    check('【核心】小手指的就是配对的那张中文卡',
        !!zhH && zhH.col === 'zh' && pickZh.spot === pickZh.zhUid && zhH.text === pickZh.zhText
            && pickZh.zhText !== '',
        pickZh ? `spot=${pickZh.spot} zhUid=${pickZh.zhUid} 卡面=「${zhH && zhH.text}」行=${zhH && zhH.col}` : 'n/a');

    check('【核心】点完英文（听读音阶段）小手已收回', !!listen && listen.n === 0,
        listen ? `n=${listen.n} stage=${listen.stage}` : '未采到 listen 稳定态');
    check('【核心】点完中文（记一记阶段）小手已收回', !!think && think.n === 0,
        think ? `n=${think.n} stage=${think.stage}` : '未采到 think 稳定态');

    /* ---------- H4 位置：卡片右下角 ---------- */
    console.log('\n— H4 位置与样式 —');
    check('小手落在卡片右下角（中心在右半 + 下半，且只溢出几像素）',
        !!enH && enH.cx > enH.cw / 2 && enH.cy > enH.ch / 2
            && enH.rightOver <= 10 && enH.bottomOver <= 10,
        enH ? `中心(${enH.cx},${enH.cy}) 卡 ${enH.cw}x${enH.ch} 溢出 右${enH.rightOver}/下${enH.bottomOver} 尺寸 ${enH.w}x${enH.h}` : 'n/a');
    check('小手不吃点击（pointer-events:none）且是绝对定位，并带点头动画',
        !!enH && enH.pe === 'none' && enH.pos === 'absolute' && enH.anim === 'wm-hand-tap',
        enH ? `pe=${enH.pe} pos=${enH.pos} anim=${enH.anim}` : 'n/a');
    /* ⚠️ 命中测试必须和「找到可见小手」在同一次 evaluate 里做完：自动学习一直在跑，
       分两步的话等第二步执行时小手已经收回去了（首轮就是这么失败的：返回 null）。 */
    let hit = null;
    for (let i = 0; i < 80 && !hit; i++) {
        hit = await evalJs(`(() => {
            const h = Array.from(document.querySelectorAll('.wm-hand'))
                .filter(x => x.getClientRects().length > 0)[0];
            if (!h) return null;
            const card = h.closest('.wm-card');
            const cr = card.getBoundingClientRect();
            const hr = h.getBoundingClientRect();
            const x = cr.right - 6, y = cr.bottom - 6;   // 落在小手与卡片的交叠区里
            const el = document.elementFromPoint(x, y);
            return { insideHand: x >= hr.left && x <= hr.right && y >= hr.top && y <= hr.bottom,
                     hitTag: el ? el.tagName : null,
                     hitCls: el ? (el.getAttribute('class') || '') : '',
                     onCard: !!el && !!el.closest('.wm-card') };
        })()`);
        if (!hit) await sleep(50);
    }
    check('小手盖住的那片区域点下去命中的仍是卡牌本身（不挡操作）',
        !!hit && hit.insideHand && hit.onCard, JSON.stringify(hit));
    check('小手是纯 SVG、不含文字节点（不会污染卡片 innerText）',
        !!enH && enH.textless && (await evalJs(SNAP)).textClean === true,
        enH ? `textless=${enH.textless}` : 'n/a');

    /* ---------- H5 暂停收起 / 继续回来 ---------- */
    console.log('\n— H5 暂停时收起 —');
    const beforePause = await settledSample(s => s.auto && !s.paused && s.n === 1);
    check('暂停前先等到一只可见的小手', !!beforePause, beforePause ? `stage=${beforePause.stage}` : 'timeout');
    await clickPause();
    await sleep(400);
    const paused = await evalJs(SNAP);
    check('暂停后小手收起（暂停 = 什么都不点，还指着卡片会误导）',
        paused.paused === true && paused.n === 0,
        JSON.stringify({ paused: paused.paused, n: paused.n, stage: paused.stage }));
    await clickPause();
    const resumed = await settledSample(s => s.auto && !s.paused && s.n === 1, 15000);
    check('继续之后小手又回来了', !!resumed, resumed ? `stage=${resumed.stage} n=${resumed.n}` : 'timeout');
    await autoOff();
    await sleep(300);

    /* ---------- H6 ?wmHand=0 ---------- */
    console.log('\n— H6 ?wmHand=0（开关可关） —');
    await fresh(PAGE + SLOW + '&wmHand=0');
    await hookSpeech(true);
    await autoOn();
    const off = await settledSample(s => s.auto && !s.paused && s.stage === 'pick-en');
    await autoOff();
    check('?wmHand=0：小手元素都还在（是「关掉了」，不是「没渲染」）',
        !!off && off.handTotal === off.cards && off.cards > 0,
        off ? `hands=${off.handTotal} cards=${off.cards}` : 'timeout');
    check('?wmHand=0：一只都不显示，但状态机照样在设 autoSpot',
        !!off && off.n === 0 && off.spot !== '',
        off ? `n=${off.n} spot=${off.spot}` : 'timeout');

    /* ---------- H7 320x568 ---------- */
    console.log('\n— H7 超小屏 320x568 —');
    await send('Emulation.setDeviceMetricsOverride',
        { width: 320, height: 568, deviceScaleFactor: 2, mobile: true });
    await fresh(PAGE + SLOW);
    await hookSpeech(true);
    await autoOn();
    const tiny = await settledSample(s => s.auto && !s.paused && s.n === 1, 25000);
    const th = tiny && tiny.hands[0];
    check('320x568：小手完全落在棋盘内（不越界，也就不会额外撑高整页）',
        !!th && th.inBoard === true,
        th ? `inBoard=${th.inBoard} 距棋盘底 ${th.boardBottomGap}px` : 'timeout');
    check('320x568：小手跟着窄屏缩了一档（桌面 30px -> 窄屏更小）',
        !!th && th.w < 30 && th.w >= 18,
        th ? `尺寸 ${th.w}x${th.h}（溢出 右${th.rightOver}/下${th.bottomOver}）` : 'timeout');
    await autoOff();
    console.log(`  320x568 记录：scrollH=${tiny && tiny.scrollH} vh=${tiny && tiny.vh}`);
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 940, deviceScaleFactor: 1, mobile: false });

    /* ---------- H8 运行期异常 ---------- */
    console.log('\n— H8 运行期异常 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 3).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

    const bad = results.filter(r => !r.ok);
    console.log(`\n==== ${results.length - bad.length}/${results.length} 通过 ====`);
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

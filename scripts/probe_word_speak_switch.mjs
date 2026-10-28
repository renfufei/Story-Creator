#!/usr/bin/env node
/**
 * 探针：单词匹配页「朗读通道切换后不能哑掉」+「内置读音缺失时降级系统朗读」。
 *
 * 用户报的现象：在内置语音 / 系统朗读之间、以及不同音色之间来回切几次之后，
 * 再点单词就没声音了。
 *
 * 结构性根因（两处，都不靠听感就能钉死）：
 *   ① 系统朗读**说之前不 cancel**：切档/换音色每条都会塞一句试听，残留话语一直占着队列头，
 *      堆几条之后 Chrome 整条队列卡死 ⇒ 之后点单词一律无声。
 *      （老版本每次 speak 前都 cancel，重构双通道时漏掉了。）
 *   ② sprite **共用一个 Audio 元素**：快速连点两个词时，前一句的收尾（timeupdate / 强制收尾）
 *      无条件 pause，会把后一句刚起的音掐掉。所以收尾必须只在「我仍是最新那次播放」时暂停。
 *
 * 钉这些事：
 *   ① 每次 speechSynthesis.speak 之前都有一次 cancel（含超时收尾的 cancel）；
 *   ② 连点同一张卡 5 次，每次都有发声（media 或 tts），一次不落；
 *   ③ 内置 ↔ 系统 来回切 3 轮、再加换 2 次音色，之后点单词仍然有发声；
 *   ④ 切档试听念的是**本关英文词**，不再固定念 "hello"（内置读音里没有 hello，
 *      走 speakOne 会白等 3s 并把该册标成 giveup）；
 *   ⑤ 内置优先档 + 有内置读音的册：点词走 media（sprite），**不产生 tts**，音色行隐藏；
 *   ⑥ 内置优先档 + 没内置读音的册（初中 pep-7-1）：点词**自动降级**走 tts，
 *      说明文案写明「自动用系统朗读」，音色行出现；
 *   ⑦ 档位文案：两档是「内置优先 / 系统朗读」，说明随本册实际情况变化；
 *   ⑧ 全程零未捕获 JS 异常。
 *
 * ⚠️ 无头环境听不到声音：这里验的是「请求到底有没有发给某个发声通道」。
 * 用法：node scripts/probe_word_speak_switch.mjs
 *       WM_NO_INTERCEPT=1 node scripts/probe_word_speak_switch.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOCAL = {
    '/learn/word-match': path.join(ROOT, 'src/main/resources/static/pages/learn-word-match.html')
};
const BASE = process.env.WM_BASE || 'http://localhost:1888';
/* WM_PAGE 可直接指向另一个页面（例如已发布的线上单页件）：
      WM_PAGE='https://word-match-61798.app.workbuddy.host/?sharecode=XXX' WM_NO_INTERCEPT=1 \
        node scripts/probe_word_speak_switch.mjs
   线上是单机件（页面在根路径、不带 /learn/word-match），所以路径必须能改。 */
const PAGE = process.env.WM_PAGE || (BASE + '/learn/word-match');
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.WM_CDP_PORT || 9363);
const NO_INTERCEPT = process.env.WM_NO_INTERCEPT === '1';
const AUDIO_BOOK = 'pep-3-1';       // 小学三上：有内置读音
const PLAIN_BOOK = 'pep-7-1';       // 初中：没有内置读音

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wms-'));

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    ...SILENT_AUDIO_FLAGS,
    '--autoplay-policy=no-user-gesture-required',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let ws;
let msgId = 0;
const pending = new Map();
const pageErrors = [];

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
async function waitFor(predicate, tries = 90, gap = 150) {
    for (let i = 0; i < tries; i++) {
        try { if (await evalJs(predicate)) return true; } catch (e) { /* 导航中 */ }
        await sleep(gap);
    }
    return false;
}
const READY = `!!(window.Alpine && document.querySelector('.wm-root') && window.__wmVoice && window.__wmVoice.name)`;
const data = `window.Alpine.$data(document.querySelector('.wm-root'))`;

/* 记录「发声请求」，同时保留 cancel 的调用顺序：
   __wmLog 里只有两类事件 —— { ch:'tts', text } / { ch:'media', t } / { ch:'cancel' }。
   判「有没有发出声」只看 tts + media，不看 promise 是否 resolve。 */
const INSTALL_LOG = `(() => {
    window.__wmLog = [];
    var synth = window.speechSynthesis;
    var origSpeak = synth.speak.bind(synth);
    var origCancel = synth.cancel.bind(synth);
    synth.speak = function (u) {
        window.__wmLog.push({ ch: 'tts', text: String(u.text || ''), voice: u.voice ? u.voice.name : null });
        setTimeout(function () { try { u.onend && u.onend(); } catch (e) {} }, 0);
    };
    synth.cancel = function () {
        window.__wmLog.push({ ch: 'cancel' });
        try { origCancel(); } catch (e) {}
    };
    if (!window.__wmMediaPatched) {
        window.__wmMediaPatched = 1;
        var origPlay = HTMLMediaElement.prototype.play;
        HTMLMediaElement.prototype.play = function () {
            window.__wmLog.push({ ch: 'media', src: String(this.currentSrc || this.src || '').split('/').pop() });
            var p = origPlay.call(this);
            if (p && p.catch) p.catch(function () {});
            return p;
        };
    }
    return true;
})()`;

const CLEAR_LOG = `(window.__wmLog = [], true)`;
const LOG = `JSON.stringify(window.__wmLog)`;

/** 进某册的棋盘：applyBook 只装好关卡数据，卡片要等 view='play' 才渲染出 DOM */
async function openBook(id) {
    await evalJs(`(() => {
        var d = ${data};
        d.applyBook(${JSON.stringify(id)}, false);
        d.enterPlay();
        d.sel = null;
        return true;
    })()`);
    await sleep(700);
    const n = await evalJs(`document.querySelectorAll('.wm-col-en .wm-card').length`);
    if (!n) throw new Error('棋盘没渲染出英文卡：' + id);
    return n;
}

/* 真实鼠标点击某张英文卡（不用 element.click()，要走 Input.dispatchMouseEvent） */
async function clickCard(index = 0) {
    const box = JSON.parse(await evalJs(`(() => {
        var c = document.querySelectorAll('.wm-col-en .wm-card')[${index}]
             || document.querySelectorAll('.wm-card')[${index}];
        if (!c) return 'null';
        var b = c.getBoundingClientRect();
        return JSON.stringify({ x: b.x + b.width / 2, y: b.y + b.height / 2 });
    })()`));
    if (!box) throw new Error('找不到第 ' + index + ' 张卡');
    for (const type of ['mousePressed', 'mouseReleased']) {
        await send('Input.dispatchMouseEvent', {
            type, x: box.x, y: box.y, button: 'left', clickCount: 1
        });
    }
    await sleep(260);
}

/** 从日志里判断这次点击有没有「发出去」：tts 或 media 至少一条 */
const sounded = (log, from) => log.slice(from).some((e) => e.ch === 'tts' || e.ch === 'media');

async function main() {
    for (let i = 0; i < 80; i++) {
        try {
            const ver = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json();
            if (ver && ver['Browser']) break;
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`,
        { method: 'PUT' })).json();
    ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    ws.onmessage = (ev) => {
        const m = JSON.parse(ev.data);
        if (m.id && pending.has(m.id)) {
            const { res, rej } = pending.get(m.id);
            pending.delete(m.id);
            if (m.error) rej(new Error(m.error.message)); else res(m.result);
            return;
        }
        if (m.method === 'Fetch.requestPaused') {
            const { requestId, request } = m.params;
            const local = LOCAL[new URL(request.url).pathname];
            if (!NO_INTERCEPT && local) {
                send('Fetch.fulfillRequest', {
                    requestId, responseCode: 200,
                    responseHeaders: [{ name: 'Content-Type', value: 'text/html; charset=utf-8' }],
                    body: Buffer.from(fs.readFileSync(local, 'utf8'), 'utf8').toString('base64')
                });
            } else {
                send('Fetch.continueRequest', { requestId });
            }
            return;
        }
        if (m.method === 'Runtime.exceptionThrown') {
            pageErrors.push(m.params.exceptionDetails.exception?.description
                || m.params.exceptionDetails.text || 'unknown');
        }
    };
    await send('Page.enable');
    await send('Runtime.enable');
    await installSilentAudio(send);
    await send('Fetch.enable', { patterns: [{ urlPattern: '*' }] });

    /* ---------- 打开页面（有内置读音的册） ---------- */
    await send('Page.navigate', { url: PAGE });
    if (!await waitFor(READY)) throw new Error('页面没起来');
    await openBook(AUDIO_BOOK);
    await evalJs(INSTALL_LOG);

    /* ---------- ① 连点同一张卡 5 次，每次都得有发声 ---------- */
    const miss = [];
    for (let i = 0; i < 5; i++) {
        const before = JSON.parse(await evalJs(LOG)).length;
        await clickCard(0);
        await sleep(360);
        if (!sounded(JSON.parse(await evalJs(LOG)), before)) miss.push(i);
        /* 每次点完把选中状态清掉，保证下一击仍是「新选中」而不是取消选中 */
        await evalJs(`(() => { var d = ${data}; d.sel = null; return true; })()`);
    }
    check('连点 5 次都有发声（无一次落空）', miss.length === 0, '落空次数 ' + miss.length);

    /* ---------- ② 每次 tts 之前都有 cancel ---------- */
    const log2 = JSON.parse(await evalJs(LOG));
    const ttsIdx = log2.map((e, i) => (e.ch === 'tts' ? i : -1)).filter((i) => i >= 0);
    const noCancel = ttsIdx.filter((i) => i === 0 || log2[i - 1].ch !== 'cancel');
    check('每条系统朗读之前都 cancel 过（清队列，防卡死）', noCancel.length === 0,
        '缺 cancel 的条数 ' + noCancel.length);

    /* ---------- ③ 来回切 3 轮 + 换 2 次音色，之后仍然有声音 ---------- */
    await evalJs(`(() => {
        var d = ${data};
        d.setSpeakMode('system');
        return true;
    })()`);
    await sleep(200);
    const sample = JSON.parse(await evalJs(LOG)).filter((e) => e.ch === 'tts').pop() || {};
    const firstWord = await evalJs(`(() => { var d = ${data}; return (d.leftCards[0] || {}).text || ''; })()`);
    check('切档试听念的是本关英文词（不是固定 hello）',
        !!firstWord && sample.text === firstWord, `试听=${sample.text} 本关首词=${firstWord}`);

    const voiceNames = JSON.parse(await evalJs(`JSON.stringify(window.__wmVoice.options)`));
    for (let round = 0; round < 3; round++) {
        await evalJs(`(() => { var d = ${data}; d.setSpeakMode('builtin'); return true; })()`);
        await sleep(260);
        await evalJs(`(() => { var d = ${data}; d.setSpeakMode('system'); return true; })()`);
        await sleep(260);
        if (round < 2 && voiceNames.length > 1) {
            const name = voiceNames[round % voiceNames.length];
            await evalJs(`(() => { var d = ${data}; d.setSpeakVoice(${JSON.stringify(name)}); return true; })()`);
            await sleep(200);
        }
    }
    await evalJs(`(() => { var d = ${data}; d.setSpeakMode('system'); d.sel = null; return true; })()`);
    await sleep(260);
    await evalJs(CLEAR_LOG);
    await clickCard(1);
    await sleep(400);
    const afterSwitch = JSON.parse(await evalJs(LOG));
    check('来回切 3 轮 + 换音色 2 次之后，点单词仍有声音',
        sounded(afterSwitch, 0), JSON.stringify(afterSwitch.filter((e) => e.ch !== 'cancel')));

    /* ---------- ④ 内置优先 + 有内置读音：走 media、不产生 tts、音色行隐藏 ---------- */
    await evalJs(`(() => {
        var d = ${data};
        d.setSpeakMode('builtin');
        d.sel = null;
        return true;
    })()`);
    await sleep(400);
    await evalJs(CLEAR_LOG);
    await clickCard(0);
    await sleep(700);
    const builtinLog = JSON.parse(await evalJs(LOG));
    check('内置优先档的英文卡走内置读音（media）',
        builtinLog.some((e) => e.ch === 'media'), JSON.stringify(builtinLog.filter((e) => e.ch === 'media')));
    check('内置优先 + 本册有读音：不调用系统朗读',
        !builtinLog.some((e) => e.ch === 'tts'), JSON.stringify(builtinLog.filter((e) => e.ch === 'tts')));

    const rowState = async () => JSON.parse(await evalJs(`(() => {
        var row = document.querySelector('.is-speakvoice');
        var mode = document.querySelector('.is-speakmode');
        var desc = mode ? mode.querySelector('.wm-set-desc').textContent.trim() : '';
        if (!row) return JSON.stringify({ missing: 1, desc: desc });
        return JSON.stringify({ display: getComputedStyle(row).display, desc: desc });
    })()`));

    const audioRow = await rowState();
    check('内置优先 + 本册有读音：音色行隐藏', audioRow.display === 'none', JSON.stringify(audioRow));
    check('文案：本册有内置读音时说明「优先用它」', /优先/.test(audioRow.desc), audioRow.desc);

    /* ---------- ⑤ 内置优先 + 没有内置读音的册：自动降级系统朗读 ---------- */
    await openBook(PLAIN_BOOK);
    const plainRow = await rowState();
    check('没内置读音的册：说明写明「自动用系统朗读」', /自动用系统朗读/.test(plainRow.desc), plainRow.desc);
    check('没内置读音的册：音色行出现（降级后才有的选择）', plainRow.display !== 'none', JSON.stringify(plainRow));

    await evalJs(CLEAR_LOG);
    await clickCard(0);
    await sleep(500);
    const plainLog = JSON.parse(await evalJs(LOG));
    check('没内置读音的册：点单词自动降级为系统朗读', plainLog.some((e) => e.ch === 'tts'),
        JSON.stringify(plainLog.filter((e) => e.ch !== 'cancel')));
    check('没内置读音的册：不会去请求内置音频', !plainLog.some((e) => e.ch === 'media'),
        JSON.stringify(plainLog.filter((e) => e.ch === 'media')));

    /* ---------- ⑥ 档位文案 ---------- */
    const labels = JSON.parse(await evalJs(`JSON.stringify(
        Array.prototype.map.call(document.querySelectorAll('.is-speakmode .wm-set-seg button'),
            function (b) { return b.textContent.trim(); })
    )`));
    check('两档文案 = 内置优先 / 系统朗读',
        labels.join('/') === '内置优先/系统朗读', labels.join('/'));

    const sysDesc = await evalJs(`(() => {
        var d = ${data};
        d.speakMode = 'system';
        return document.querySelector('.is-speakmode .wm-set-desc').textContent.trim();
    })()`);
    await sleep(120);
    const sysDesc2 = await evalJs(`document.querySelector('.is-speakmode .wm-set-desc').textContent.trim()`);
    check('系统朗读档的说明是「一律用设备语音合成」', /一律/.test(sysDesc2 || sysDesc), sysDesc2 || sysDesc);

    /* ---------- ⑦ 零未捕获异常 ---------- */
    check('无未捕获 JS 异常', pageErrors.length === 0, pageErrors.slice(0, 3).join(' | '));

    const bad = results.filter((r) => !r.ok);
    console.log(`\n${results.length - bad.length}/${results.length} 通过`);
    if (bad.length) {
        console.log('失败项：');
        for (const b of bad) console.log('  - ' + b.name + (b.extra ? '  [' + b.extra + ']' : ''));
    }
    process.exitCode = bad.length ? 1 : 0;
}

main().catch((e) => {
    console.log('探针异常: ' + e.message);
    process.exitCode = 2;
}).finally(() => {
    try { ws && ws.close(); } catch (e) { /* 忽略 */ }
    try { chrome.kill('SIGKILL'); } catch (e) { /* 忽略 */ }
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch (e) { /* 忽略 */ }
    setTimeout(() => process.exit(process.exitCode || 0), 300);
});

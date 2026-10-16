#!/usr/bin/env node
/**
 * /learn/word-match 定点验证：**自动学习模式下「先读英文、再读中文释义」**。
 *
 * 背景（用户需求，2026-10-16）：
 *   自动学习原来是「选中英文 -> 朗读英文 -> 找中文 -> 记 2 秒」，
 *   用户希望中文释义也念出来，这样不看屏幕也能学；而且朗读时长必须算进节奏里
 *   ——念完**之后**再等原来的间隔，才进下一个单词；朗读失败不能报错、不能卡住流程。
 *
 * 实现：autoLoop 的 listen 阶段改成念一个数组 [英文, 中文]，
 *   中文先进 zhSpeechText() 清洗（剥掉 n. / adj. / vt. 这类词性缩写），
 *   每条朗读 onend / onerror / 超时三者谁先到算谁完，永不 reject。
 *   可调：?wmZh=0 只念英文，?wmZhRate= 中文语速（百分比，默认 88）。
 *
 * 本脚本逐条守护：
 *  Z1 清洗规则：词性缩写被剥掉、省略号换成停顿、`J.K.罗琳` 不被啃成 `K.罗琳`
 *  Z2 【核心】自动学习每个单词念两条：先 en-US、后 zh-CN，条数恰为 2×单词数
 *  Z3 念的中文 = 该词释义经清洗后的结果（不是原始释义、也不是别的词）
 *  Z4 ?wmZh=0 -> 只念英文（每条都是 en-US）
 *  Z5 【核心】speak 永不回调 onend（模拟朗读失败）时，流程照样往下走、照样能配对
 *  Z6 运行期无 JS 异常 / console error
 *
 * 用法：node scripts/probe_wm_auto_zh.mjs
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
const PORT = 9335;

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wm-zh-probe-'));
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

/* 在静音守门的记录器**之上**再叠一层：额外记下 lang / rate（守门只记文本字符串，
   改它的元素类型会波及 Java 侧共用用例，所以这里自己包一层，保留 60ms 回调 onend）。 */
async function hookSpeech(breakOnend) {
    return evalJs(`(() => {
        if (!window.speechSynthesis) return false;
        window.__zhProbe = [];
        const inner = window.speechSynthesis.speak;     // 静音守门的记录器（会异步回调 onend）
        window.speechSynthesis.speak = function (u) {
            window.__zhProbe.push({
                text: String((u && u.text) || ''),
                lang: String((u && u.lang) || ''),
                rate: (u && u.rate)
            });
            /* breakOnend = true 时**故意不回调 onend**，模拟「朗读卡住 / 引擎不响应」 */
            if (breakOnend) return;
            return inner.call(this, u);
        };
        return true;
    })()`);
}

const spoken = () => evalJs(`(window.__zhProbe || []).slice()`);
const clearSpoken = () => evalJs(`(window.__zhProbe = []) && true`);

/* ⚠️ 冒烟默认静音（word_match_sound_v1=0）。soundOn=false 时 speakAllThenWait 走的是
   「固定等 600ms、根本不调 speak」的分支 —— 忘了打开语音就会「0 条朗读」，
   断言全变成对空数组的真空通过。所以每次进新页面都要显式打开。 */
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

    /* ---------- Z0 前置：守门生效 + 固定在三上第 1 关 ---------- */
    console.log('\n— Z0 前置 —');
    await send('Page.navigate', { url: PAGE });
    await sleep(700);
    await evalJs(`localStorage.clear()`);
    await evalJs(`localStorage.setItem('word_match_sound_v1', '0');
                  localStorage.setItem('word_match_book_v1', 'pep-3-1');
                  localStorage.removeItem('word_match_auto_progress_v2');
                  localStorage.removeItem('word_match_progress_v2');`);
    await send('Page.navigate', { url: PAGE });
    await waitCards();
    await sleep(300);
    const guard = await evalJs(`(() => {
        const st = window.__smokeAudioOff;
        return !!(st && st.guard && window.speechSynthesis
                  && window.speechSynthesis.speak && window.speechSynthesis.speak.__smokeGuard);
    })()`);
    check('静音守门已接管 speechSynthesis（探针不会真的念出来）', guard);
    const lvInfo = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { book: d.bookId, level: d.levelIndex, pairs: d.currentLevel ? d.currentLevel.pairs.length : 0 };
    })()`);
    check('已在三上第 1 关，且本关有词', lvInfo.book === 'pep-3-1' && lvInfo.pairs >= 3,
        `book=${lvInfo.book} level=${lvInfo.level} pairs=${lvInfo.pairs}`);

    /* ---------- Z1 清洗规则 ---------- */
    console.log('\n— Z1 中文释义 → 朗读文本（清洗规则） —');
    const clean = await evalJs(`(() => {
        const f = Alpine.$data(document.querySelector('.wm-root')).zhSpeechText;
        return {
            pos1: f('n. 物体；对象 vi. 反对'),
            pos2: f('adj. 孪生的 n. 孪生儿'),
            pos3: f('n. 最大值 adj. 最大的'),
            ell:  f('在…上'),
            name: f('J.K.罗琳'),
            ts:   f('T恤衫'),
            slash:f('哥/弟'),
            blank:f(''),
            nul:  f(null),
            eng:  f('apple'),
            lead: f('n. X射线，X光')
        };
    })()`);
    check('剥掉词性缩写：n./vi. 都不留', clean.pos1 === '物体；对象 反对', `「${clean.pos1}」`);
    check('剥掉词性缩写：adj./n. 都不留', clean.pos2 === '孪生的 孪生儿', `「${clean.pos2}」`);
    check('剥掉词性缩写：n./adj. 都不留', clean.pos3 === '最大值 最大的', `「${clean.pos3}」`);
    check('省略号换成停顿（不再念出「…」）', clean.ell === '在 上', `「${clean.ell}」`);
    check('「J.K.罗琳」不被误啃成「K.罗琳」（POS 白名单，不是 [A-Za-z]+\\.）',
        clean.name === 'J.K.罗琳', `「${clean.name}」`);
    check('保留词里本来就有的字母（T恤衫）', clean.ts === 'T恤衫', `「${clean.ts}」`);
    check('斜杠释义保持原样交给 TTS（哥/弟）', clean.slash === '哥/弟', `「${clean.slash}」`);
    check('空释义返回空串（不念）', clean.blank === '' && clean.nul === '');
    check('纯英文释义返回空串（不念噪音）', clean.eng === '', `「${clean.eng}」`);
    check('词性标记后的字母词保留（n. X射线，X光 -> X射线，X光）',
        clean.lead === 'X射线，X光', `「${clean.lead}」`);

    /* ---------- Z2/Z3 自动学习连读英文 + 中文 ---------- */
    console.log('\n— Z2/Z3 自动学习：先英文、再中文 —');
    await send('Page.navigate', { url: PAGE + '?wmStep=250&wmThink=200&wmGap=800' });
    await waitCards();
    await sleep(250);
    await hookSpeech(false);
    check('已打开语音开关（否则会走「不调 speak」的静音分支，断言全真空）',
        (await setSound(true)) === true);
    await clearSpoken();
    check('点【自动学习】成功', await autoOn());
    const gotEnough = await waitFor('攒够 8 条朗读', async () => (await spoken()).length >= 8, 20000, 120);
    const seq = await spoken();
    await autoOff();
    check('自动学习开始新一轮朗读（>=8 条）', gotEnough, `实际 ${seq.length} 条`);

    const enAll = seq.filter(s => s.lang === 'en-US').length;
    const zhAll = seq.filter(s => s.lang === 'zh-CN').length;
    check('【核心】每个单词念两条：英文条数 == 中文条数', enAll > 0 && enAll === zhAll,
        `en=${enAll} zh=${zhAll}`);
    check('【核心】一个单词内先英文、后中文（奇偶位置严格交替）',
        seq.every((s, i) => (i % 2 === 0 ? s.lang === 'en-US' : s.lang === 'zh-CN')),
        seq.slice(0, 6).map(s => s.lang).join(','));
    check('中文语速用 ZH_SPEAK_RATE（默认 0.88）', seq.filter(s => s.lang === 'zh-CN').every(s => Math.abs(s.rate - 0.88) < 1e-6),
        `rates=${JSON.stringify(Array.from(new Set(seq.map(s => s.rate))))}`);
    check('英文语速保持原样 0.85', seq.filter(s => s.lang === 'en-US').every(s => Math.abs(s.rate - 0.85) < 1e-6));

    /* 念的中文必须是**该词释义**清洗后的结果，而不是原始释义（含 n.）或别的词 */
    const ttsMap = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const out = {};
        d.currentLevel.pairs.forEach(p => { out[p.en] = d.zhSpeechText(p.zh); });
        return out;
    })()`);
    const pairsOk = [];
    for (let i = 0; i + 1 < seq.length; i += 2) {
        const en = seq[i].text, zh = seq[i + 1].text;
        pairsOk.push(Object.prototype.hasOwnProperty.call(ttsMap, en) && ttsMap[en] === zh);
    }
    check('念的中文就是该词释义清洗后的文本（且英文是左右卡里的真词）',
        pairsOk.length > 0 && pairsOk.every(Boolean),
        `匹配 ${pairsOk.filter(Boolean).length}/${pairsOk.length}；样例 ${JSON.stringify(seq.slice(0, 2).map(s => s.text))}`);
    check('念的中文里不含词性缩写（n. / adj. / vi. …）',
        seq.filter(s => s.lang === 'zh-CN').every(s => !/(^|[\s；;，,、])(n|v|adj|adv|vt|vi|prep|pron|conj|num|int|art)\./.test(s.text)),
        JSON.stringify(seq.filter(s => s.lang === 'zh-CN').slice(0, 4).map(s => s.text)));

    /* ---------- Z4 ?wmZh=0 只念英文 ---------- */
    console.log('\n— Z4 ?wmZh=0：只念英文（开关可关） —');
    await send('Page.navigate', { url: PAGE + '?wmStep=250&wmThink=200&wmGap=800&wmZh=0' });
    await waitCards();
    await sleep(250);
    await hookSpeech(false);
    await setSound(true);
    await clearSpoken();
    await autoOn();
    const got4 = await waitFor('攒够 4 条', async () => (await spoken()).length >= 4, 15000, 120);
    const seq4 = await spoken();
    await autoOff();
    check('wmZh=0 时攒到朗读', got4, `实际 ${seq4.length} 条`);
    check('wmZh=0 -> 全部是英文，一条中文都没有',
        seq4.length > 0 && seq4.every(s => s.lang === 'en-US'),
        JSON.stringify(Array.from(new Set(seq4.map(s => s.lang)))));

    /* ---------- Z5 朗读失败（永不回调 onend）不阻塞流程 ---------- */
    console.log('\n— Z5 朗读失败不报错、不阻塞 —');
    await send('Page.navigate', { url: PAGE + '?wmStep=200&wmThink=150&wmGap=800' });
    await waitCards();
    await sleep(250);
    await hookSpeech(true);          // speak 只记录，**永不回调 onend**
    await setSound(true);
    const before = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        return { matched: d.matchedCount, level: d.levelIndex, prog: JSON.parse(localStorage.getItem('word_match_auto_progress_v2') || '{}') };
    })()`);
    await autoOn();
    /* 超时兜底：快节奏下每条 300ms，整关 6 词约 8 秒；给 25 秒足够走完一关并进下一关 */
    const advanced = await waitFor('自动学习继续推进（配对/换关）', async () => {
        const s = await evalJs(`(() => {
            const d = Alpine.$data(document.querySelector('.wm-root'));
            return { matched: d.matchedCount, level: d.levelIndex };
        })()`);
        return s.matched > before.matched || s.level !== before.level;
    }, 25000, 200);
    const tried = (await spoken()).length;
    await autoOff();
    await sleep(400);                // x-show 收起要等一拍，别在元素还在时读状态
    const stuck = await evalJs(`(() => {
        const d = Alpine.$data(document.querySelector('.wm-root'));
        const b = document.querySelector('.wm-auto-bar');
        return { matched: d.matchedCount, level: d.levelIndex, auto: d.autoMode,
                 bar: !!b && b.getClientRects().length > 0 };
    })()`);
    check('【核心】朗读引擎不回调 onend 时，自动学习照样往下配对（超时兜底生效）',
        advanced, `before=${JSON.stringify(before)} after=${JSON.stringify(stuck)}`);
    check('确实是「朗读失败」这条路径（speak 被调过、但一个 onend 都没回）',
        tried > 0, `speak 被调用 ${tried} 次`);
    check('朗读失败路径下没有抛异常（状态条仍受控、自动学习可正常退出）',
        stuck.auto === false && stuck.bar === false, JSON.stringify(stuck));

    /* ---------- Z6 运行期错误 ---------- */
    console.log('\n— Z6 运行期异常 —');
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

#!/usr/bin/env node
/**
 * 探针：单词匹配页「朗读三通道」——整册 sprite 加速包 → 按需单文件 → 系统朗读。
 *
 * 钉这些事：
 *   ① 设置弹层里「朗读方式」两档（不是开关），默认「内置优先」（有内置读音就用，没就降级系统朗读）；
 *   ② 音频格式是 AAC/m4a（Content-Type=audio/mp4），slug 规则与 Python 一致；
 *   ③ **sprite 加速包**：整册只有 1 个音频文件，索引覆盖全部词、区间合法；
 *      下载完成后播放走 seek，**不再产生新的音频请求**（这是"减少请求次数"的验收点）；
 *   ④ 切「系统朗读」后零音频请求；切回内置能恢复；选择写进 localStorage；
 *   ⑤ 没生成过音频的册不瞎请求；404 的册会被记住并回落系统朗读。
 *
 * 用法：
 *   WM_BASE=http://localhost:1889 node scripts/probe_word_audio_builtin.mjs
 *   WM_BASE=http://127.0.0.1:1890 WM_PAGE=http://127.0.0.1:1890/index.html node ...
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';

const BASE = process.env.WM_BASE || 'http://localhost:1888';
/* WM_PAGE 可指到任意页面：站内是 /learn/word-match，单页导出件是 /index.html */
const PAGE = process.env.WM_PAGE || (BASE + '/learn/word-match');
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.WM_CDP_PORT || 9361);
const OUT = process.env.WM_OUT || '/tmp/wm-audio-probe';
const BOOK = 'pep-3-1';
const EMPTY_BOOK = 'pep-7-1';      // 初中册，尚未生成音频

/* 拦截本地页面：静态页在 jar 里，不拦就只能验「上次打包的版本」。
   WM_NO_INTERCEPT=1 可关掉（打包重启之后直接验线上页）。 */
const NO_INTERCEPT = process.env.WM_NO_INTERCEPT === '1';
const LOCAL_MAP = {
    '/learn/word-match': path.resolve(path.dirname(new URL(import.meta.url).pathname),
        '../src/main/resources/static/pages/learn-word-match.html')
};

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wmaud-'));

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    ...SILENT_AUDIO_FLAGS,
    '--autoplay-policy=no-user-gesture-required',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let ws, msgId = 0;
const pending = new Map();
const pageErrors = [];
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
async function waitFor(predicate, tries = 80, gap = 150) {
    for (let i = 0; i < tries; i++) {
        try { if (await evalJs(predicate)) return true; } catch (e) { /* 导航中 */ }
        await sleep(gap);
    }
    return false;
}

/** 记录 Audio 的每一次 src 赋值、currentTime 定位与 play 调用。
 *  play 走真实现（--mute-audio 已静音），再补一个兜底 ended，避免 playBuiltin 干等 4s 超时。
 *  seek 记录是 sprite 通道的判据：走了 sprite 就只会有 seek，不会有新的 src。 */
const RECORDER = `(() => {
  window.__audioLog = [];
  const Orig = window.Audio;
  function Wrapped(src) {
    const a = new Orig(src);
    const dsrc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    Object.defineProperty(a, 'src', {
      configurable: true,
      get() { return dsrc.get.call(a); },
      set(v) { window.__audioLog.push('src:' + String(v)); dsrc.set.call(a, v); }
    });
    const dct = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'currentTime');
    Object.defineProperty(a, 'currentTime', {
      configurable: true,
      get() { return dct.get.call(a); },
      set(v) { if (Number(v) > 0.001) window.__audioLog.push('seek:' + Number(v).toFixed(3)); dct.set.call(a, v); }
    });
    const play = a.play.bind(a);
    a.play = function () {
      window.__audioLog.push('play');
      const p = play();
      setTimeout(function () {
        try { a.dispatchEvent(new Event('ended')); } catch (e) {}
      }, 900);
      return p && p.catch ? p : Promise.resolve();
    };
    return a;
  }
  Wrapped.prototype = Orig.prototype;
  window.Audio = Wrapped;
})()`;

const D = `Alpine.$data(document.querySelector('[x-data]'))`;
const LOG = 'window.__audioLog';
const clearLog = () => evalJs(`${LOG}.length = 0; true`);
const audioReqs = (re) => evalJs(`${LOG}.filter(function(u){ return ${re}.test(u); })`);

/** 页面资源里某个文件的下载完成情况（用来判断 sprite 是否已到手） */
const resourceDone = (name) => `(() => {
  const e = performance.getEntriesByType('resource').filter(function (x) {
    return x.name.indexOf(${JSON.stringify(name)}) >= 0;
  });
  return e.length ? { n: e.length, done: e.filter(function (x) { return x.responseEnd > 0; }).length,
                      size: e[e.length - 1].transferSize || e[e.length - 1].encodedBodySize || 0 } : null;
})()`;

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
        if (m.method === 'Runtime.exceptionThrown') {
            pageErrors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
            return;
        }
        if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
            consoleErrors.push((m.params.args || []).map(a => a.value || a.description || '').join(' '));
            return;
        }
        /* 静态页打进 jar：把 /learn/word-match 换成本地文件，其余请求照旧打真实后端。
           不这么做就只能验「上一次打包的页面」，改完页面到重新打包之间的改动完全测不到。 */
        if (m.method === 'Fetch.requestPaused') {
            const { requestId, request } = m.params;
            const file = LOCAL_MAP[new URL(request.url).pathname];
            if (!NO_INTERCEPT && file && fs.existsSync(file)) {
                send('Fetch.fulfillRequest', {
                    requestId, responseCode: 200,
                    responseHeaders: [{ name: 'Content-Type', value: 'text/html; charset=utf-8' }],
                    body: Buffer.from(fs.readFileSync(file, 'utf8'), 'utf8').toString('base64')
                });
            } else {
                send('Fetch.continueRequest', { requestId });
            }
            return;
        }
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id);
            pending.delete(m.id);
            m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
        }
    };
    await send('Runtime.enable');
    await installSilentAudio(send);
    await send('Page.addScriptToEvaluateOnNewDocument', { source: RECORDER });
    await send('Fetch.enable', { patterns: [{ urlPattern: '*' }] });
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });

    // ---------- 1. 打开页面 ----------
    await send('Page.navigate', { url: PAGE });
    const booted = await waitFor(`!!(window.Alpine && document.querySelector('[x-data]')
        && ${D}.books.length > 0)`, 120, 200);
    check('① 页面启动完成、词库已载入', booted,
        booted ? await evalJs(`${D}.books.length + ' 册'`) : '超时');

    /* 页面默认停在检索首页（还没选册），而朗读要用当前册去拼音频地址。
       这里走真实的选册入口 applyBook()，和点册次按钮是同一条路径。 */
    await evalJs(`${D}.applyBook('${BOOK}', true); true`);
    const picked = await waitFor(`${D}.bookId === '${BOOK}' && ${D}.levels.length > 0`, 40, 150);
    check('①b 选三年级上册后进入棋盘（关卡已加载）', picked,
        picked ? await evalJs(`${D}.bookId + ' / ' + ${D}.levels.length + ' 关'`) : '超时');

    // ---------- 2. 设置弹层条目 ----------
    await evalJs(`${D}.settingsOpen = true; true`);
    await sleep(120);
    const rows = await evalJs(`document.querySelectorAll('.wm-set-pop .wm-set-item').length`);
    check('② 设置弹层共 10 条（原 8 条 + 朗读方式 + 系统音色）', rows === 10, '实际 ' + rows);

    const segTexts = await evalJs(`Array.from(document.querySelectorAll('.wm-set-seg > button'))
        .map(function (b) { return b.textContent.trim(); })`);
    check('③ 朗读方式两档文案 = 内置优先 / 系统朗读',
        Array.isArray(segTexts) && segTexts.length === 2
        && segTexts[0] === '内置优先' && segTexts[1] === '系统朗读',
        JSON.stringify(segTexts));

    const segRow = await evalJs(`(() => {
        const el = document.querySelector('.wm-set-item.is-speakmode');
        if (!el) return null;
        const r = el.getBoundingClientRect();
        const desc = el.querySelector('.wm-set-desc');
        return {
            n: document.querySelectorAll('.wm-set-item.is-speakmode').length,
            h: Math.round(r.height),
            icon: !!el.querySelector('i.wm-set-icon'),
            desc: (desc ? desc.innerText : '').trim().length
        };
    })()`);
    check('④ 「朗读方式」是独立条目、带图标与说明、触控高度 ≥44px',
        !!segRow && segRow.n === 1 && segRow.h >= 44 && segRow.icon && segRow.desc >= 4,
        JSON.stringify(segRow));

    const mode0 = await evalJs(`${D}.speakMode`);
    check('⑤ 默认朗读方式 = builtin（内置优先）', mode0 === 'builtin', mode0);

    // ---------- 3. sprite 加速包（本次新增的核心能力） ----------
    /* 索引与整册音频都必须能直取，且索引里的区间要合法、覆盖全册 */
    const spJson = await evalJs(`(async () => {
        const r = await fetch('/audio/word-match/${BOOK}/sprite.json');
        if (!r.ok) return { status: r.status };
        const d = await r.json();
        const bad = d.words.filter(function (w) {
            return !(w[0] && w[1] >= 0 && w[2] > w[1] && w[2] <= d.duration + 0.5);
        });
        return { status: r.status, n: d.words.length, sr: d.sampleRate,
                 dur: d.duration, head: d.headMs, tail: d.tailMs, bad: bad.length,
                 first: d.words[0], hasSpace: d.words.some(function (w) { return w[0].indexOf(' ') >= 0; }) };
    })()`);
    check('⑥ sprite 索引可直取且格式正确（200 / 有采样率与时长 / 每词 start<end≤总长）',
        spJson && spJson.status === 200 && spJson.n > 50 && spJson.sr > 0
        && spJson.dur > 30 && spJson.bad === 0,
        JSON.stringify(spJson && { n: spJson.n, sr: spJson.sr, dur: spJson.dur, bad: spJson.bad }));

    const spAudio = await evalJs(`(async () => {
        const r = await fetch('/audio/word-match/${BOOK}/sprite.m4a');
        const buf = await r.arrayBuffer();
        return [r.status, r.headers.get('content-type') || '', buf.byteLength];
    })()`);
    check('⑦ sprite 音频可直取（200 + audio/mp4 + 体积 > 100KB）',
        Array.isArray(spAudio) && spAudio[0] === 200 && /audio\/mp4/.test(spAudio[1])
        && spAudio[2] > 100 * 1024,
        JSON.stringify(spAudio));

    /* 等整册音频真正下完（applyBook 里已经触发了 ensureSprite，不阻塞渲染） */
    const dl = await waitFor(`(() => {
        const e = performance.getEntriesByType('resource').filter(function (x) {
          return x.name.indexOf('sprite.m4a') >= 0;
        });
        return e.length > 0 && e[e.length - 1].responseEnd > 0;
    })()`, 100, 200);
    await sleep(500);       // 留给 canplaythrough / readyState 兜底
    const dlInfo = await evalJs(resourceDone('sprite.m4a'));
    check('⑧ 选册后整册音频自动预载完成（1 个请求，不再逐词拉）',
        dl && dlInfo && dlInfo.done >= 1,
        JSON.stringify(dlInfo));

    /* 关键验收：sprite 就绪后播放一个词，只应有 seek + play，**没有新的 src 赋值** */
    await clearLog();
    await evalJs(`${D}.speak('ruler'); true`);
    await sleep(320);
    const log1 = await evalJs(`${LOG}.slice()`);
    check('⑨ 播放走 sprite：有 seek 定位、无新增音频请求（src）',
        log1.some(u => /^seek:/.test(u)) && log1.some(u => u === 'play')
        && !log1.some(u => /^src:/.test(u)),
        JSON.stringify(log1));

    /* seek 的目标必须落在索引里 ruler 的起点上（误差 < 10ms，证明偏移算对了） */
    const rulerEntry = await evalJs(`(async () => {
        const d = await (await fetch('/audio/word-match/${BOOK}/sprite.json')).json();
        const e = d.words.filter(function (w) { return w[0] === 'ruler'; })[0];
        return e || null;
    })()`);
    const seekVal = (log1.find(u => /^seek:/.test(u)) || '').replace('seek:', '');
    check('⑩ seek 目标 = 索引里该词的起点（误差 ≤10ms）',
        !!rulerEntry && Math.abs(parseFloat(seekVal) - rulerEntry[1]) <= 0.01,
        `seek=${seekVal} 索引=${rulerEntry && rulerEntry[1]}`);

    /* 连播多个词：每次都是 seek，请求数不增长 */
    await clearLog();
    for (const w of ['book', 'pencil box', 'eraser']) {
        await evalJs(`${D}.speak(${JSON.stringify(w)}); true`);
        await sleep(200);
    }
    const log2 = await evalJs(`${LOG}.slice()`);
    const seeks = log2.filter(u => /^seek:/.test(u)).length;
    check('⑪ 连播 3 个词 ⇒ 3 次 seek、0 次新请求（整册只下过一次）',
        seeks === 3 && !log2.some(u => /^src:/.test(u)),
        `seek=${seeks} src=${log2.filter(u => /^src:/.test(u)).length}`);

    // ---------- 4. 单文件兜底仍然可用（sprite 之外的路径不能被改坏） ----------
    /* 单文件已 prune（AUDIO_BOOKS 里 single:0）：直接请求必须 404，
       而页面绝不能因此去请求它 —— 那个 404 会把整册标成 dead，连 sprite 一起废掉。 */
    const single = await evalJs(`(async () => {
        const r = await fetch('/audio/word-match/${BOOK}/pencil-box.m4a');
        return r.status;
    })()`);
    check('⑫ 单文件已移除（sprite-only 档）：直接请求返回 404', single === 404, 'status=' + single);

    /* 冷册首播：切到还没预载的册后**立刻**点播放。整册没下完时它会短暂等待，
       但绝不能去请求那个已经不存在的单文件 —— 那个 404 会把整册标成 dead，
       连 sprite 一起废掉。这里等整册真正到手后，再验一次「就绪即可 seek」。
       （headless 的首次 fetch 比真实浏览器慢一两个数量级，所以等待给得很宽。） */
    const COLD = 'pep-4-1';
    /* ⚠️ 必须用**该册真实存在**的词：各册词表不通用（ruler 是三年级上册的，
       四年级上册查不到，会走系统朗读，看起来像"就绪后仍不播"）。 */
    const coldWord = await evalJs(`(async () => {
        const d = await (await fetch('/audio/word-match/${COLD}/sprite.json')).json();
        return d.words[0][0];
    })()`);
    await evalJs(`${D}.applyBook('${COLD}', false); true`);
    await clearLog();
    await evalJs(`${D}.speak(${JSON.stringify(coldWord)}); true`);
    await sleep(6000);
    const coldLog = await evalJs(`${LOG}.slice()`);
    const coldDead = await evalJs(`!!${D}.audioDeadBooks['${COLD}']`);
    const noSingleReq = !coldLog.some(u => /^src:.*\.m4a/.test(u) && !/sprite/.test(u));
    await clearLog();
    await evalJs(`${D}.speak(${JSON.stringify(coldWord)}); true`);
    await sleep(500);
    const coldLog2 = await evalJs(`${LOG}.slice()`);
    check(`⑫b 冷册（${COLD}）首播：整册到手即可 seek，不请求单文件、不标 dead`,
        noSingleReq && !coldDead
        && coldLog2.some(u => /^seek:/.test(u)) && !coldLog2.some(u => /^src:/.test(u)),
        `词=${coldWord} 首次=${JSON.stringify(coldLog.slice(0, 2))} 就绪后=${JSON.stringify(coldLog2)} dead=${coldDead}`);
    await evalJs(`${D}.applyBook('${BOOK}', false); true`);
    await sleep(300);

    // ---------- 5. 切到系统朗读：不该再有音频请求 ----------
    await evalJs(`document.querySelector('.wm-set-seg > button:nth-child(2)').click(); true`);
    await sleep(200);
    const mode1 = await evalJs(`${D}.speakMode`);
    const stored1 = await evalJs(`localStorage.getItem('word_match_speak_mode_v1')`);
    check('⑬ 点「系统朗读」⇒ speakMode=system 且写进 localStorage',
        mode1 === 'system' && stored1 === 'system', mode1 + ' / ' + stored1);

    await clearLog();
    await evalJs(`${D}.speak('book'); true`);
    await sleep(250);
    const log3 = await evalJs(`${LOG}.slice()`);
    check('⑭ 系统朗读模式下零音频动作（无 seek、无 src）',
        !log3.some(u => /^(src:|seek:)/.test(u)), JSON.stringify(log3));

    // ---------- 6. 切回内置 ----------
    await evalJs(`document.querySelector('.wm-set-seg > button:nth-child(1)').click(); true`);
    await sleep(200);
    const mode2 = await evalJs(`${D}.speakMode`);
    const stored2 = await evalJs(`localStorage.getItem('word_match_speak_mode_v1')`);
    check('⑮ 切回「内置优先」⇒ 状态与存储都恢复 builtin',
        mode2 === 'builtin' && stored2 === 'builtin', mode2 + ' / ' + stored2);

    // ---------- 7. 没有音频的册：不瞎请求 ----------
    await clearLog();
    await evalJs(`${D}.bookId = '${EMPTY_BOOK}'; ${D}.speak('ruler'); true`);
    await sleep(250);
    const log4 = await evalJs(`${LOG}.slice()`);
    check(`⑯ 未生成音频的册（${EMPTY_BOOK}）不请求音频、直接走系统朗读`,
        !log4.some(u => /^(src:|seek:)/.test(u)), JSON.stringify(log4));
    await evalJs(`${D}.bookId = '${BOOK}'; true`);

    // ---------- 8. 词不在索引里：回落系统朗读，但绝不能污染该册状态 ----------
    await clearLog();
    await evalJs(`${D}.speak('zzzz-not-a-word'); true`);
    await sleep(900);
    const logBad = await evalJs(`${LOG}.slice()`);
    const dead = await evalJs(`!!${D}.audioDeadBooks['${BOOK}']`);
    check('⑰ 词不在索引里 ⇒ 直接系统朗读（无 seek、无 src、不发 404 请求）',
        !logBad.some(u => /^(src:|seek:)/.test(u)), JSON.stringify(logBad));
    check('⑱ 纯 sprite 册不会因「词不在索引」被标 dead（否则 sprite 会被一起废掉）',
        !dead, 'dead=' + dead);

    await clearLog();
    await evalJs(`${D}.speak('book'); true`);
    await sleep(300);
    const logAfter = await evalJs(`${LOG}.slice()`);
    check('⑱b 上面那次失败之后 sprite 仍然可用（仍有 seek、无新请求）',
        logAfter.some(u => /^seek:/.test(u)) && !logAfter.some(u => /^src:/.test(u)),
        JSON.stringify(logAfter));

    // ---------- 9. 窄屏不溢出 ----------
    await send('Emulation.setDeviceMetricsOverride',
        { width: 320, height: 568, deviceScaleFactor: 2, mobile: true });
    await sleep(200);
    await evalJs(`${D}.settingsOpen = true; true`);
    await sleep(250);
    const narrow = await evalJs(`(() => {
        const pop = document.querySelector('.wm-set-pop');
        const seg = document.querySelector('.wm-set-seg');
        if (!pop || !seg) return { ok: false, why: 'missing' };
        const r = pop.getBoundingClientRect();
        const s = seg.getBoundingClientRect();
        return {
            ok: pop.scrollWidth <= pop.clientWidth + 1 && s.right <= r.right + 1 && r.left >= -1,
            sw: pop.scrollWidth, cw: pop.clientWidth,
            segRight: Math.round(s.right), popRight: Math.round(r.right), popLeft: Math.round(r.left)
        };
    })()`);
    check('⑲ 320×568 下设置弹层与两档胶囊都不横向溢出',
        narrow && narrow.ok, JSON.stringify(narrow));

    // ---------- 10. 无脚本异常 ----------
    const ignorable = /favicon|net::ERR|Download the React/;
    const realPage = pageErrors.filter(e => !ignorable.test(String(e)));
    const realConsole = consoleErrors.filter(e => !ignorable.test(String(e)));
    check('⑳ 无未捕获异常 / 控制台报错',
        realPage.length === 0 && realConsole.length === 0,
        JSON.stringify(realPage.concat(realConsole).slice(0, 3)));

    const pass = results.filter(r => r.ok).length;
    console.log(`\n${pass}/${results.length} 通过`);
    try { chrome.kill('SIGKILL'); } catch (e) { /* 忽略 */ }
    process.exit(pass === results.length ? 0 : 1);
}

main().catch(e => {
    console.error('探针异常: ' + (e && e.stack || e));
    try { chrome.kill('SIGKILL'); } catch (err) { /* 忽略 */ }
    process.exit(2);
});

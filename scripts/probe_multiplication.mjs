#!/usr/bin/env node
/**
 * 探针：九九乘法口诀（/learn/multiplication）的**朗读降级链** + **格子配色**。
 *
 * 需求：服务端没有已生成音频时，改用浏览器本地朗读（Web Speech API）把汉字口诀读出来，
 * 而且**读不出来也不能报错**。这条链子有三段，任何一段都不能把播放打断：
 *
 *   ① 服务端音频 `/api/learn/multiplication/audio/{key}` → 200 就用它，**不该再走本地朗读**；
 *   ② 404（这一条还没生成）/ 网络异常 → 落到本地朗读，读的是汉字：
 *      `prefix_3` → 「三的乘法口诀」，`3x4` → 「三四十二」；
 *   ③ speechSynthesis 缺席 / speak 抛异常 → 静默收场，页面不出现任何未捕获错误。
 *
 * 为什么要拦导航：静态页打进 jar，改完不重启就看不到新代码 —— 这里把
 * /learn/multiplication 的导航响应换成本地文件，其余请求照旧走真实后端。
 * 打包重启后可 WM_NO_INTERCEPT=1 直接验线上页（那时 ③ 只能验"不报错"，验不了"读了什么"）。
 *
 * 用法：node scripts/probe_multiplication.mjs
 *       WM_OUT=/tmp/xxx node scripts/probe_multiplication.mjs
 */
import { spawn } from 'node:child_process';
// 静音守门：--mute-audio + 注入 src/test/resources/silent-audio.js（管住平台 TTS）
import { SILENT_AUDIO_FLAGS, installSilentAudio } from './lib/silent-audio.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOCAL = {
    [new URL('http://x/learn/multiplication').pathname]:
        path.join(ROOT, 'src/main/resources/static/pages/learn-multiplication.html'),
};
const BASE = process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/learn/multiplication';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9348;
const OUT = process.env.WM_OUT || '/tmp/mul-probe';
const NO_INTERCEPT = process.env.WM_NO_INTERCEPT === '1';
const AUDIO_API = '/api/learn/multiplication/audio/';

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'mul-'));

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    ...SILENT_AUDIO_FLAGS,            // ① 浏览器音频管线静音（媒体元素 + Web Audio）
    '--autoplay-policy=no-user-gesture-required',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userDataDir, 'about:blank'
], { stdio: 'ignore' });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let ws, msgId = 0;
const pending = new Map();
const pageErrors = [];
const consoleErrors = [];
const external = [];
const failures = [];
const audioRequests = [];

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
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    const file = path.join(OUT, name + '.png');
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
    console.log('  截图 ' + file);
    return file;
}
async function waitFor(predicate, tries = 80, gap = 120) {
    for (let i = 0; i < tries; i++) {
        try { if (await evalJs(predicate)) return true; } catch (e) { /* 导航中 */ }
        await sleep(gap);
    }
    return false;
}
async function nav(url, predicate, tries = 80, gap = 150) {
    await send('Page.navigate', { url });
    return waitFor(predicate, tries, gap);
}

/** 装桩：fetch（音频接口）/ Audio / speechSynthesis 全换成可控版本，其余逻辑跑真实代码 */
const INSTALL_STUBS = `(() => {
    window.__spoke = [];              // 本地朗读出去的每一条
    window.__audioMode = 'none';      // none = 服务端返回 404；present = 返回一段音频
    window.__fetchLog = [];
    const realFetch = window.fetch.bind(window);
    window.fetch = (input, init) => {
        const u = String(input && input.url ? input.url : input);
        if (u.indexOf('${AUDIO_API}') >= 0) {
            window.__fetchLog.push(u.slice(u.indexOf('${AUDIO_API}')));
            if (window.__audioMode === 'present') {
                return Promise.resolve(new Response(
                    new Blob(['not-really-audio'], { type: 'audio/mpeg' }), { status: 200 }));
            }
            return Promise.resolve(new Response('', { status: 404 }));
        }
        return realFetch(input, init);
    };
    // Audio 桩：play() 之后立刻触发 onended，避免无头环境没有声卡
    window.Audio = function () {
        const self = {
            src: '', onended: null, onerror: null, currentTime: 0,
            play() { setTimeout(() => { if (self.onended) self.onended(); }, 5); return Promise.resolve(); },
            pause() {},
        };
        return self;
    };
    // 朗读桩：记录文本，并异步回调 onend（真实浏览器里 onend 也是异步来的）
    window.__speech = {
        spoken: window.__spoke,
        speak(u) { window.__spoke.push({ text: u.text, lang: u.lang, rate: u.rate });
                   setTimeout(() => { if (u.onend) u.onend(); }, 5); },
        cancel() {},
        getVoices() { return []; },
    };
    window.SpeechSynthesisUtterance = function (text) { this.text = text; };
    Object.defineProperty(window, 'speechSynthesis',
        { value: window.__speech, configurable: true, writable: true });
    return true;
})()`;

/** 点「手动步进」→ 页面才出现音量按钮 */
const SWITCH_MANUAL = `(() => {
    const btn = Array.from(document.querySelectorAll('button'))
        .find(b => b.textContent.includes('手动'));
    if (!btn) return 'no-button';
    btn.click();
    return 'clicked';
})()`;

const CLICK_PLAY = `(() => {
    const btn = Array.from(document.querySelectorAll('button'))
        .find(b => b.querySelector('.bi-volume-up'));
    if (!btn) return 'no-button';
    btn.click();
    return 'clicked';
})()`;

/** 清掉「已知没音频」的缓存，让下一条真的再去问一次后端 */
const CLEAR_CACHE = `(() => {
    const el = document.querySelector('[x-data]');
    const data = window.Alpine && window.Alpine.$data(el);
    if (!data) return false;
    data.audioCache = {};
    return true;
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
                || m.params.exceptionDetails.text);
            return;
        }
        if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
            consoleErrors.push((m.params.args || []).map(a => a.value || a.description || '').join(' '));
            return;
        }
        if (m.method === 'Network.requestWillBeSent') {
            const u = m.params.request.url;
            if (u.indexOf(AUDIO_API) >= 0) audioRequests.push(u);
            if (!u.startsWith(BASE) && !u.startsWith('data:') && !u.startsWith('blob:')) external.push(u);
            return;
        }
        if (m.method === 'Network.loadingFailed') {
            failures.push(m.params.errorText || 'unknown');
            return;
        }
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id);
            pending.delete(m.id);
            m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
        }
    };

    // ② 平台 TTS 管不到就去注入守门脚本（addScriptToEvaluateOnNewDocument，对每次导航都生效）
    await installSilentAudio(send);
    await send('Runtime.enable');
    await send('Network.enable');
    if (!NO_INTERCEPT) {
        await send('Fetch.enable', { patterns: Object.keys(LOCAL).map(p => ({ urlPattern: BASE + p, requestStage: 'Request' })) });
    }
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });

    console.log(NO_INTERCEPT
        ? '— 直接验线上页（未拦截）—'
        : `— 已把 ${Object.keys(LOCAL).join(',')} 的导航响应替换为本地文件 —`);

    /* ---------- 1. 页面骨架：口诀与 11×11 网格都渲染出来了 ---------- */
    const ok = await nav(PAGE, `!!window.Alpine
        && document.querySelector('[x-data]') && window.Alpine.$data(document.querySelector('[x-data]'))
        && document.querySelectorAll('.multiplication-grid .grid-cell').length === 100`);
    check('乘法页渲染出 11×11 网格（100 个格子 10×10）', ok,
        `${await evalJs(`document.querySelectorAll('.multiplication-grid .grid-cell').length`)} 格`);
    const skeleton = await evalJs(`(() => {
        const el = document.querySelector('[x-data]');
        const data = window.Alpine.$data(el);
        const cells = document.querySelectorAll('.multiplication-grid .grid-label').length;
        return { formulas: data.formulas.length,
                 current: data.currentFormula.chineseText,
                 prefix: data.currentGroupPrefix,
                 labels: cells,
                 title: document.title };
    })()`);
    check('口诀表齐 45 句，起始停在「一一得一」+ 组前缀「一的乘法口诀」',
        skeleton.formulas === 45 && skeleton.current === '一一得一'
        && skeleton.prefix === '一的乘法口诀',
        `${skeleton.formulas} 句 / ${skeleton.current} / ${skeleton.prefix}`);
    check('行列号标签齐 21 个（左上角 0 + 顶部 1~10 + 左侧 1~10）', skeleton.labels === 21,
        skeleton.labels + ' 个');

    /* ---------- 2. 降级链 ②：没有服务端音频 → 本地朗读汉字 ---------- */
    await evalJs(INSTALL_STUBS);
    const manual = await evalJs(SWITCH_MANUAL);
    await waitFor(`!!Array.from(document.querySelectorAll('button')).find(b => b.querySelector('.bi-volume-up'))`, 20, 100);
    check('切到「手动步进」后出现朗读按钮（后续点击都点它）', manual === 'clicked',
        manual);

    await evalJs(`window.__audioMode = 'none'; window.__spoke = []; window.__fetchLog = []`);
    await evalJs(CLEAR_CACHE);
    await evalJs(CLICK_PLAY);
    await waitFor(`window.__spoke.length >= 2`, 30, 120);
    const spoke1 = await evalJs(`window.__spoke.map(s => s.text + '/' + s.lang + '/' + s.rate)`);
    check('服务端 404 时：本地朗读补位，先读组名「一的乘法口诀」再读口诀「一一得一」',
        spoke1.length === 2 && spoke1[0] === '一的乘法口诀/zh-CN/0.9' && spoke1[1] === '一一得一/zh-CN/0.9',
        spoke1.join(' → ') || '（没有朗读）');
    const fetched1 = await evalJs(`window.__fetchLog.slice()`);
    check('降级前确实先问过后端（两条 key 各一次，404 才算「没生成」）',
        fetched1.length === 2 && fetched1[0] === AUDIO_API + 'prefix_1' && fetched1[1] === AUDIO_API + '1x1',
        fetched1.join(' , '));
    await shot('50-multiplication-page');

    /* ---------- 3. 缓存：已知没音频，重复播同一句不该再打后端 ---------- */
    await evalJs(`window.__fetchLog = []; window.__spoke = []`);
    await evalJs(CLICK_PLAY);
    await waitFor(`window.__spoke.length >= 2`, 30, 120);
    const fetched2 = await evalJs(`window.__fetchLog.slice()`);
    check('同一句重复播放不再重复请求后端（audioCache 记住了「没生成」）',
        fetched2.length === 0, `${fetched2.length} 次请求`);

    /* ---------- 4. 降级链 ①：有服务端音频时不抢本地朗读的活 ---------- */
    await evalJs(CLEAR_CACHE);
    await evalJs(`window.__audioMode = 'present'; window.__spoke = []; window.__fetchLog = []`);
    await evalJs(CLICK_PLAY);
    await sleep(400);
    const spoke2 = await evalJs(`window.__spoke.slice()`);
    const fetched3 = await evalJs(`window.__fetchLog.slice()`);
    check('服务端有音频（200）时只放音频、不再叠加本地朗读（否则会读两遍）',
        fetched3.length === 2 && spoke2.length === 0,
        `请求 ${fetched3.length} 次 / 朗读 ${spoke2.length} 条`);

    /* ---------- 5. 降级链 ③：朗读失败一律静默，页面不报错 ---------- */
    const errBefore = pageErrors.length;
    await evalJs(CLEAR_CACHE);
    await evalJs(`window.__audioMode = 'none'; window.__spoke = [];
        window.__speech.speak = () => { throw new Error('tts boom'); }`);
    await evalJs(CLICK_PLAY);
    await sleep(500);
    const afterThrow = await evalJs(`({
        spoke: window.__spoke.length,
        alive: !!document.querySelector('.multiplication-grid .grid-cell')
    })`);
    check('speak() 直接抛异常时静默收场：无未捕获错误、页面照常可用',
        pageErrors.length === errBefore && afterThrow.alive && afterThrow.spoke === 0,
        `新增异常 ${pageErrors.length - errBefore} 个 / 朗读 ${afterThrow.spoke} 条`);

    const errBefore2 = pageErrors.length;
    await evalJs(CLEAR_CACHE);
    await evalJs(`Object.defineProperty(window, 'speechSynthesis',
        { value: undefined, configurable: true }); window.__spoke = []`);
    await evalJs(CLICK_PLAY);
    await sleep(400);
    check('浏览器根本没有 speechSynthesis 时也不报错（直接跳过降级）',
        pageErrors.length === errBefore2,
        `新增异常 ${pageErrors.length - errBefore2} 个`);

    /* ---------- 6. 自动播放整轮不炸（降级链在循环里也一样） ---------- */
    const autoShot = await nav(PAGE, `document.querySelectorAll('.multiplication-grid .grid-cell').length === 100`);
    await evalJs(INSTALL_STUBS);          // 页面刚重载，桩要重新装
    await evalJs(`window.__audioMode = 'none'`);
    const autoRun = await evalJs(`(async () => {
        const el = document.querySelector('[x-data]');
        const data = window.Alpine.$data(el);
        data.startAuto();
        await new Promise(r => setTimeout(r, 2600));
        data.stop();
        return { index: data.currentIndex, playing: data.playing,
                 spoke: window.__spoke.length, cacheKeys: Object.keys(data.audioCache).length };
    })()`);
    check('自动播放 2.6 秒内推进到第 3 句，每句都落到本地朗读（没卡在某一格）',
        autoShot && autoRun.index >= 2 && autoRun.playing === false
        && autoRun.spoke >= 4 && autoRun.cacheKeys >= 4,
        JSON.stringify(autoRun));

    /* ---------- 7. 格子配色：一句一色，按句轮换 ---------- */
    // 只改 currentIndex 直接量 DOM（不点按钮），因为要精确对到「第几句」。
    // 颜色一律从渲染后的 inline style 读，别信样式表推导。
    const paint = await evalJs(`(async () => {
        const el = document.querySelector('[x-data]');
        const d = window.Alpine.$data(el);
        const tick = () => new Promise(r => setTimeout(r, 40));
        const toRgb = (hex) => {
            const s = document.createElement('span');
            s.style.color = hex;
            document.body.appendChild(s);
            const v = getComputedStyle(s).color;
            s.remove();
            return v;
        };
        const read = () => Array.from(document.querySelectorAll('.multiplication-grid .grid-cell'))
            .map((c, i) => ({
                // grid-row 是 CSS 网格线号：第 1 行留给顶部列号，所以格子行 = gridRow - 1
                row: (parseInt(c.style.gridRow, 10) - 1) || (Math.floor(i / 10) + 1),
                bg: (c.style.backgroundColor || '').trim(),
            }))
            .filter(c => c.bg);
        const byRow = () => {
            const m = new Map();
            read().forEach(c => {
                if (!m.has(c.row)) m.set(c.row, new Set());
                m.get(c.row).add(c.bg);
            });
            return Array.from(m.entries()).sort((a, b) => a[0] - b[0])
                .map(([r, s]) => r + '=' + Array.from(s).join('+'));
        };
        const head = () => {
            const n = document.querySelector('main .display-6');
            return n ? getComputedStyle(n).color : '';
        };
        const out = { palette: d.colors.map(toRgb), layout: d.gridLayout, snap: {} };
        for (const idx of [38, 39, 44]) {
            d.currentIndex = idx;
            await tick();
            out.snap[idx] = { rows: byRow(), head: head() };
        }
        d.gridLayout = 'linear';
        d.currentIndex = 44;
        await tick();
        out.linear = { rows: byRow().length, distinct: new Set(read().map(c => c.bg)).size };
        d.gridLayout = 'rect';
        d.currentIndex = 0;
        await tick();
        return out;
    })()`);

    const rgbOf = (s) => (s.match(/\d+/g) || []).map(Number).slice(0, 3);
    const dist = (a, b) => Math.sqrt(a.reduce((acc, v, i) => acc + (v - b[i]) ** 2, 0));
    const pal = paint.palette.map(rgbOf);
    const ring = pal.map((c, i) => dist(c, pal[(i + 1) % pal.length]));
    const hexPal = paint.palette.map(c => '#' + rgbOf(c).map(v => v.toString(16).padStart(2, '0')).join(''));
    const rowMap = (key) => {
        const m = {};
        paint.snap[key].rows.forEach(s => { const [r, c] = s.split('='); m[r] = c; });
        return m;
    };
    const r38 = rowMap(38), r39 = rowMap(39), r44 = rowMap(44);

    check('配色板相邻两句的颜色足够分得开（原顺序里 #98D8C8 与 #96CEB4 相邻，ΔRGB≈22 ＝白换色）',
        pal.length === 4 && Math.min(...ring) >= 60,
        `${hexPal.join(' → ')} ｜ 相邻色距 ${ring.map(d => d.toFixed(0)).join('/')}`);
    // 期望值按浏览器序列化后的样子拼（inline style 读回来是 rgb(r, g, b)，带空格）
    const expect38 = ['1', '2', '3']
        .map((r, i) => r + '=rgb(' + pal[(36 + i) % 4].join(', ') + ')').join(' ');
    check('第 38 句（九的乘法口诀第 3 句）：前三行一句一条色带，颜色 = 第 1/2/3 句各自那一号',
        paint.snap[38].rows.length === 3 && paint.snap[38].rows.join(' ') === expect38,
        `${paint.snap[38].rows.join(' ') || '（无着色格子）'} ｜ 期望 ${expect38}`);
    check('第 39 句：前三行颜色原样保留（推进不重染整片），只有新长出来的第 4 行换色',
        Object.keys(r38).every(r => r39[r] === r38[r]) && r39['4'] && r39['4'] !== r39['3'],
        `38 句 ${JSON.stringify(r38)} → 39 句 ${JSON.stringify(r39)}`);
    check('第 44 句（一组 9 句走完）：4 号色全部轮上，不是一片同色',
        new Set(Object.values(r44)).size === 4,
        paint.snap[44].rows.join(' '));
    check('大字口诀的颜色 = 最新那一圈格子的颜色（' + r44['9'] + '）',
        paint.snap[44].head === r44['9'] && paint.snap[39].head === r39['4'],
        `44 句 大字 ${paint.snap[44].head} / 第 9 行 ${r44['9']}；39 句 大字 ${paint.snap[39].head} / 第 4 行 ${r39['4']}`);
    check('切到「累加」布局也一样是多色（不是只在矩形布局生效）',
        paint.linear.rows === 9 && paint.linear.distinct === 4,
        JSON.stringify(paint.linear));

    // 一组（九的乘法口诀）走到第 1 / 3 / 9 句的各留一张整页图：色带是一层层加上去的
    for (const [idx, name] of [[36, '54-multiplication-g9-step1'],
                               [38, '55-multiplication-g9-step3'],
                               [44, '56-multiplication-g9-step9']]) {
        await evalJs(`(async () => {
            const d = window.Alpine.$data(document.querySelector('[x-data]'));
            d.currentIndex = ${idx};
            await new Promise(r => setTimeout(r, 80));
            return d.currentIndex;
        })()`);
        await shot(name);
    }

    // 对照图：把高亮格全部压回「改造前」的单一组色（colors[(b-1)%4]，原配色板的顺序），
    // 拍一张 BEFORE，再还原成现在的逐格配色拍 AFTER —— 证明「不再一片同色」是这次改出来的。
    const beforeColor = await evalJs(`(async () => {
        const el = document.querySelector('[x-data]');
        const d = window.Alpine.$data(el);
        d.currentIndex = 14;                       // 五的乘法口诀第 5 句：5 行 5 列
        await new Promise(r => setTimeout(r, 60));  // 等 Alpine 把新状态刷到 DOM
        const oldPal = ['#98D8C8', '#96CEB4', '#4ECDC4', '#45B7D1'];
        const c = oldPal[(d.currentFormula.b - 1) % oldPal.length];
        let n = 0;
        document.querySelectorAll('.multiplication-grid .grid-cell').forEach(el2 => {
            if (el2.style.backgroundColor) { el2.style.backgroundColor = c; el2.style.borderColor = c; n++; }
        });
        return c + ' / ' + n + ' 格';   // 注意：这段是外层模板字符串的正文，别再嵌反引号
    })()`);
    console.log('  改前对照色 ' + beforeColor);
    await sleep(120);
    await shot('52-multiplication-before');
    await evalJs(`(async () => {
        const el = document.querySelector('[x-data]');
        const d = window.Alpine.$data(el);
        d.currentIndex = 14;
        await new Promise(r => setTimeout(r, 60));
        document.querySelectorAll('.multiplication-grid .grid-cell').forEach(el2 => {
            const row = parseInt(el2.style.gridRow, 10);
            const col = parseInt(el2.style.gridColumn, 10);
            const c = d.cellColorAt(row - 1, col - 1);
            el2.style.backgroundColor = c || '';
            el2.style.borderColor = c || '';
        });
        return true;
    })()`);
    await sleep(120);
    await shot('53-multiplication-after');

    /* ---------- 8. 整页自包含 ---------- */
    check('零外部请求（不引任何 CDN）', external.length === 0, external.join(' | ') || '零外部请求');
    check('无资源加载失败', failures.length === 0, failures.join(' | ') || '无');
    check('无未捕获的运行时 JS 错误', pageErrors.length === 0, pageErrors.join(' | ') || '无');
    check('无 console.error', consoleErrors.length === 0, consoleErrors.join(' | ') || '无');

    ws.close();
    chrome.kill();

    const bad = results.filter(r => !r.ok);
    console.log(`\n===== ${results.length - bad.length}/${results.length} 通过 =====`);
    if (bad.length) {
        bad.forEach(r => console.log('  FAIL ' + r.name));
        process.exit(1);
    }
    console.log('截图目录：' + OUT);
}

main().catch((e) => {
    console.error('探针异常：', e);
    try { chrome.kill(); } catch (_) { /* ignore */ }
    process.exit(1);
});

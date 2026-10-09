#!/usr/bin/env node
/**
 * 探针：乘法口诀（/learn/multiplication）的**朗读降级链** + **格子配色**
 *      + **两张口诀表（九九 / 大九九）** + **控制条（设置段 / 主控段与播放器三键的行为）**。
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

/** 主控段里的【重读】按钮 —— 认 🔊 图标那一个，点它会把当前这句再读一遍。 */
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
    // 改造后【重读】常驻主控段，不再需要先切「手动步进」档位才冒出来 —— 改验它一开始就在。
    const volAlways = await waitFor(
        `!!document.querySelector('.mul-ctl button .bi-volume-up')`, 20, 100);
    check('【重读】按钮一开始就在（不再需要先切档位才出现）', volAlways);

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
        data.playFromCurrent();
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

    /* ---------- 8. 大乘法口诀（大九九，81 句）----------
       九九（45 句）每组只数到"它自己"；大九九（81 句）每组都把另一个乘数数到 9。
       两张表是**同一批乘数对**，所以口诀原文与音频 key 完全复用 —— 服务端 54 条音频一条都不用加。
       最容易错的一处：组名前缀要按**组号**取（九九里「二三得六」在三组 → prefix_3，
       大九九里它属于二组 → prefix_2），下面单独钉住。 */
    const READ_TABLE = `(() => {
        const d = window.Alpine.$data(document.querySelector('[x-data]'));
        const texts = {};
        d.formulas.forEach(f => { (texts[f.b] = texts[f.b] || []).push(f.chineseText); });
        return { mode: d.tableMode, title: d.tableTitle, n: d.formulas.length,
                 index: d.currentIndex, playing: d.playing,
                 stored: localStorage.getItem('multiplication_table_mode'),
                 keys: d.formulas.map(f => f.itemKey),
                 sizes: Object.keys(texts).map(b => texts[b].length),
                 group2: texts[2] };
    })()`;
    // 按钮文案改成简称（小九九 / 大九九）之后必须**整串**比：
    // '九九' 是 '小九九' 的子串，用 indexOf 会先命中「小九九」，一按就切错表。
    const CLICK_TABLE = (label) => `(async () => {
        const btn = Array.from(document.querySelectorAll('button'))
            .find(b => b.textContent.trim() === '${label}');
        if (!btn) return 'no-button';
        btn.click();
        await new Promise(r => setTimeout(r, 60));
        return 'clicked';
    })()`;

    const bigNav = await nav(PAGE, `document.querySelectorAll('.multiplication-grid .grid-cell').length === 100`);
    await evalJs(INSTALL_STUBS);
    // 先摆成「第 31 句 + 正在自动播放」，用来看切表有没有 stop + 归零
    await evalJs(`(() => {
        const d = window.Alpine.$data(document.querySelector('[x-data]'));
        d.currentIndex = 30; d.playing = true;
        return true;
    })()`);
    const bigSwitch = await evalJs(CLICK_TABLE('大九九'));
    const big = await evalJs(READ_TABLE);
    check('「大九九」切出 81 句（9 组 × 9 句），页头标题与 localStorage 一起跟上',
        bigNav && bigSwitch === 'clicked' && big.n === 81 && big.mode === 'big'
        && big.title === '大九九乘法口诀' && big.stored === 'big',
        `${big.n} 句 / ${big.title} / stored=${big.stored}`);
    check('切表时自动播放停掉、下标归零（同一个下标在两张表里是两句不同的口诀）',
        big.index === 0 && big.playing === false, `index=${big.index} playing=${big.playing}`);
    check('大九九每组都 9 句（九九是 1..9 的递增组）',
        big.sizes.join(',') === '9,9,9,9,9,9,9,9,9', big.sizes.join(','));
    check('大九九「二的乘法口诀」= 一二得二 二二得四 二三得六 二四得八 二五一十 二六十二 二七十四 二八十六 二九十八',
        big.group2.join(' ') === '一二得二 二二得四 二三得六 二四得八 二五一十 二六十二 二七十四 二八十六 二九十八',
        big.group2.join(' '));
    check('大九九只用九九那 45 个音频 key（81 句零新增音频）',
        new Set(big.keys).size === 45, '去重后 ' + new Set(big.keys).size + ' 个');
    check('「二三得六」在两张表里是同一个 itemKey（音频复用靠它）',
        big.keys[11] === '2x3', String(big.keys[11]));

    // 组名前缀按「组号」取：大九九是"每 9 句一组"，组首 = 那句"×1"（一二得二、二二得四…）。
    // 大九九的组首必须落在 0,9,18,…,72，且组号就是那句的 b —— 组名音频 prefix_b 就靠它。
    await waitFor(`!!document.querySelector('.mul-ctl button .bi-volume-up')`, 20, 100);
    const starts = await evalJs(`(() => {
        const d = window.Alpine.$data(document.querySelector('[x-data]'));
        const out = [];
        d.formulas.forEach((f, i) => { if (d.isFirstInGroup(i)) out.push(i + ':' + f.b); });
        return out;
    })()`);
    check('大九九的组首正好 9 个（下标 0,9,…,72），且组号 = 那句的 b（prefix_b 才不会取错）',
        starts.join(' ') === '0:1 9:2 18:3 27:4 36:5 45:6 54:7 63:8 72:9',
        starts.join(' '));

    // want = 这一句预期会读几条：组首读「组名 + 口诀」两条，其余只读口诀一条。
    // （组首两条之间还有 300ms 停顿，所以必须等到 want 条，不然会读到一半就下结论。）
    const playAt = async (index, want) => {
        await evalJs(`(async () => {
            const d = window.Alpine.$data(document.querySelector('[x-data]'));
            d.currentIndex = ${index};
            d.audioCache = {};
            window.__audioMode = 'none'; window.__spoke = []; window.__fetchLog = [];
            await new Promise(r => setTimeout(r, 40));
            return true;
        })()`);
        await evalJs(CLICK_PLAY);
        await waitFor(`window.__spoke.length >= ${want}`, 40, 120);
        await sleep(150);
        return evalJs(`({ spoke: window.__spoke.map(s => s.text), text: window.Alpine.$data(
                            document.querySelector('[x-data]')).currentFormula.chineseText,
                        fetchLog: window.__fetchLog.slice() })`);
    };

    const firstOfG2 = await playAt(9, 2);       // 大九九 二组第 1 句 = 一二得二
    check('大九九组首按组号取组名：二组第 1 句问的是 prefix_2，再问口诀本身 1x2',
        firstOfG2.fetchLog.join(',') === AUDIO_API + 'prefix_2,' + AUDIO_API + '1x2',
        firstOfG2.fetchLog.join(' , ') || '（没有请求）');
    check('大九九组首连读照旧：先「二的乘法口诀」再「一二得二」',
        firstOfG2.spoke.join(' → ') === '二的乘法口诀 → 一二得二', firstOfG2.spoke.join(' → '));

    const thirdOfG2 = await playAt(11, 1);      // 二组第 3 句 = 二三得六，不是组首
    check('不是组首就不重复读组名：二组第 3 句只读「二三得六」，也不问 prefix 音频',
        thirdOfG2.text === '二三得六' && thirdOfG2.spoke.join(' → ') === '二三得六'
        && thirdOfG2.fetchLog.join(',') === AUDIO_API + '2x3',
        `${thirdOfG2.text} ｜ ${thirdOfG2.spoke.join(' → ')} ｜ ${thirdOfG2.fetchLog.join(' , ')}`);

    // 矩形语义：宽 = 组号、高 = 另一个乘数（与九九表同一套）
    const bigPaint = await evalJs(`(async () => {
        const d = window.Alpine.$data(document.querySelector('[x-data]'));
        d.gridLayout = 'rect';
        d.currentIndex = 17;                  // 大九九 二组第 9 句 = 二九十八 → 9 行 × 2 列
        await new Promise(r => setTimeout(r, 60));
        const cells = Array.from(document.querySelectorAll('.multiplication-grid .grid-cell'));
        const colored = cells.filter(c => c.style.backgroundColor);
        const col2 = cells.filter(c => c.style.gridColumn === '3')     // 第 2 列（网格线 3）
            .map(c => ({ row: parseInt(c.style.gridRow, 10) - 1,
                         label: c.querySelector('.cell-number').textContent }))
            .sort((a, b) => a.row - b.row);
        return { n: colored.length,
                 rows: new Set(colored.map(c => c.style.gridRow)).size,
                 colors: new Set(colored.map(c => c.style.backgroundColor)).size,
                 col2: col2.filter(c => c.label).map(c => c.label).join(',') };
    })()`);
    check('大九九「二九十八」画成 9 行 × 2 列（宽 = 组号），18 格全着色且不止一色',
        bigPaint.n === 18 && bigPaint.rows === 9 && bigPaint.colors >= 2, JSON.stringify(bigPaint));
    check('该矩形第 2 列自上而下依次标着 2,4,6,…,18（每句的乘积落在自己那一行）',
        bigPaint.col2 === '2,4,6,8,10,12,14,16,18', bigPaint.col2);
    await shot('57-multiplication-big');

    const smallSwitch = await evalJs(CLICK_TABLE('小九九'));
    const small = await evalJs(READ_TABLE);
    check('切回「小九九」：45 句、组数回到 1..9、localStorage 一并回写',
        smallSwitch === 'clicked' && small.n === 45 && small.mode === 'small'
        && small.stored === 'small' && small.sizes.join(',') === '1,2,3,4,5,6,7,8,9',
        `${small.n} 句 / ${small.sizes.join(',')} / stored=${small.stored}`);
    check('两张表的 itemKey 集合完全相同（同一句口诀 = 同一条音频）',
        JSON.stringify(Array.from(new Set(small.keys)).sort())
        === JSON.stringify(Array.from(new Set(big.keys)).sort()));

    /* ---------- 10. 控制条：设置段 + 主控段 ----------
       用户反馈「中间的按钮栏要更友好的交互和展示」。改造口径：
       ① 拆两段：设置段（口诀表 / 高亮，选中是**柔和底色**，不跟主控抢）+ 主控段（常驻五键）；
       ② 去掉「自动 / 手动」档位 —— 上一句 / 播放 / 下一句 任何时候都在，
          想暂停不必先确认自己在哪一档（旧实现是按住档位把整簇按钮换掉）；
       ③ 整条只剩播放键一个实色主色，层次靠颜色权重说话；
       ④ 分组名常驻（窄屏不再隐藏）、序号升成「第 N / M 句」+ 卡片底边 3px 进度条。
       全部从渲染后的 DOM 量，不读源码文本 —— 样式表没生效时量得出来是假绿。 */
    const READ_BAR = `(() => {
        const box = (el) => { const r = el.getBoundingClientRect();
            return { w: Math.round(r.width), h: Math.round(r.height),
                     mid: Math.round(r.top + r.height / 2) }; };
        const bar = document.querySelector('.mul-bar');
        const groups = Array.from(document.querySelectorAll('.mul-seg'));
        const seg = groups.map(g => Array.from(g.querySelectorAll('.btn')));
        const items = Array.from(document.querySelectorAll(
            '.mul-label, .mul-seg > .btn, .mul-counter, .mul-act, .mul-play'));
        const fill = document.querySelector('.mul-progress-fill');
        const d = window.Alpine.$data(document.querySelector('[x-data]'));
        return {
            n: groups.length,
            labels: Array.from(document.querySelectorAll('.mul-label')).map(e => e.textContent.trim()),
            texts: seg.map(g => g.map(b => b.textContent.trim())),
            tips: seg.map(g => g.map(b => (b.getAttribute('title') || '').trim().length)),
            pressed: seg.map(g => g.map(b => b.getAttribute('aria-pressed'))),
            onState: seg.map(g => g.map(b => b.classList.contains('is-on'))),
            outlines: seg.map(g => g.map(b => b.classList.contains('btn-outline-secondary'))),
            widths: seg.map(g => g.map(b => box(b).w)),
            groupW: groups.map(g => box(g).w),
            solidPrimary: Array.from(bar.querySelectorAll('.btn-primary'))
                .map(b => b.getAttribute('aria-label') || b.textContent.trim()),
            ctlBtns: Array.from(document.querySelectorAll('.mul-ctl .btn'))
                .map(b => b.getAttribute('aria-label') || ''),
            hasReplay: !!document.querySelector('.mul-ctl .bi-volume-up'),
            hasReset: !!document.querySelector('.mul-ctl .bi-arrow-counterclockwise'),
            counter: (document.querySelector('.mul-counter') || {}).textContent || '',
            fillPct: fill ? Math.round(parseFloat(fill.style.width) || 0) : -1,
            index: d.currentIndex, total: d.formulas.length,
            cardH: Math.round(bar.getBoundingClientRect().height),
            tallest: Math.max.apply(null, items.map(e => box(e).h)),
            mids: Array.from(new Set(items.map(e => box(e).mid))),
            hasHint: typeof d.tableHint !== 'undefined',
            hasMode: typeof d.mode !== 'undefined',
            title: d.tableTitle,
        };
    })()`;

    const barStopped = await evalJs(READ_BAR);
    const pair = (v) => JSON.stringify(v).replace(/","/g, ' | ');

    check('设置段只剩两组：口诀表 / 高亮（「播放方式」那组档位已去掉）',
        barStopped.n === 2 && barStopped.labels.join(',') === '口诀表,高亮',
        `${barStopped.n} 组：[${barStopped.labels.join(' / ')}]`);
    check('选项名换成同一维度、看得懂的对子：小九九/大九九 · 整块/逐格',
        JSON.stringify(barStopped.texts) === JSON.stringify([['小九九', '大九九'], ['整块', '逐格']]),
        barStopped.texts.map(g => g.join('/')).join(' ｜ '));
    check('未选项仍有描边（btn-outline-secondary）：不会退化成两段游离的文字',
        barStopped.outlines.every(g => g.every(Boolean)), pair(barStopped.outlines));
    check('设置段的选中是「柔和底色」（is-on）不是实色 —— 实色整条只留给主控的播放键',
        barStopped.onState.every(g => g.filter(Boolean).length === 1)
        && barStopped.solidPrimary.length === 1 && barStopped.solidPrimary[0] === '播放',
        `选中 ${pair(barStopped.onState)} ｜ 实色主色按钮：${barStopped.solidPrimary.join(' / ') || '无'}`);
    check('每组恰好一个选中，aria-pressed 同步（键控与读屏读得出当前选项）',
        barStopped.pressed.every(g => g.filter(v => v === 'true').length === 1),
        pair(barStopped.pressed));
    check('同组两个选项等宽（「小九九」与「大九九」不会再一胖一瘦）',
        barStopped.widths.every(g => Math.abs(g[0] - g[1]) <= 1),
        barStopped.widths.map(g => g.join('=')).join(' | ') + ' px');
    check('两个分段控件也等宽（设置段左半截是等距的）',
        new Set(barStopped.groupW).size === 1, barStopped.groupW.join(' / ') + ' px');
    check('每个选项都带 title 说明（文案看不明白时停一下鼠标就有解释）',
        barStopped.tips.every(g => g.every(n => n >= 8)),
        barStopped.tips.map(g => g.join('/')).join(' ｜ ') + ' 字');
    check('「每组只数到它自己，共 45 句」那句说明已删掉（tableHint 不复存在）',
        barStopped.hasHint === false, 'tableHint=' + barStopped.hasHint);
    check('「自动 / 手动」档位彻底移除：state 里没有 mode，localStorage 里也没有那一项',
        barStopped.hasMode === false
        && (await evalJs(`localStorage.getItem('multiplication_mode')`)) === null,
        `hasMode=${barStopped.hasMode}`);
    check('主控常驻五键：上一句 / 播放 / 下一句 / 重读 / 重置（不再按住档位整簇换掉）',
        barStopped.ctlBtns.join(',') === '上一句,播放,下一句,重读这一句,回到第一句'
        && barStopped.hasReplay && barStopped.hasReset,
        barStopped.ctlBtns.join(' | '));
    check('序号写清「第 N / M 句」（不再是一个没有上下文的 1 / 45 徽标）',
        new RegExp('^第 ' + (barStopped.index + 1) + ' / ' + barStopped.total + ' 句$')
            .test(barStopped.counter),
        barStopped.counter);
    check('底边进度条按当前句走，且不额外占高度（卡高只比最高控件多一点内边距）',
        barStopped.fillPct === Math.round((barStopped.index + 1) * 100 / barStopped.total)
        && barStopped.mids.length === 1
        && barStopped.cardH - barStopped.tallest <= 24,
        `${barStopped.counter} → ${barStopped.fillPct}% ｜ 卡高 ${barStopped.cardH} 最高 ${barStopped.tallest}`);
    check('页头标题用全名（九九乘法口诀 / 大九九乘法口诀），与按钮上的简称对得上',
        barStopped.title === '九九乘法口诀', barStopped.title);

    /* ---------- 10b. 播放器三键的行为（本次交互改造的核心） ---------- */

    /** 按 aria-label 精确点主控段里的某个键。 */
    const clickAct = (label) => evalJs(`(async () => {
        const btn = Array.from(document.querySelectorAll('.mul-ctl button'))
            .find(b => (b.getAttribute('aria-label') || '') === ${JSON.stringify(label)});
        if (!btn) return 'no-button';
        if (btn.disabled) return 'disabled';
        btn.click();
        await new Promise(r => setTimeout(r, 80));
        return 'clicked';
    })()`);
    const readState = () => evalJs(`(() => {
        const d = window.Alpine.$data(document.querySelector('[x-data]'));
        return { index: d.currentIndex, playing: d.playing,
                 spoke: window.__spoke.map(s => s.text) };
    })()`);
    const armFrom = (index) => evalJs(`(async () => {
        const d = window.Alpine.$data(document.querySelector('[x-data]'));
        d.stop(); d.currentIndex = ${index}; d.audioCache = {};
        window.__audioMode = 'none'; window.__spoke = [];
        await new Promise(r => setTimeout(r, 60));
        return true;
    })()`);

    // ① 停着点【下一句】：只前进一句 + 读这一句，不会顺手开始连播
    await armFrom(0);
    const nextClick = await clickAct('下一句');
    await waitFor(`window.__spoke.length >= 2`, 30, 120);
    const afterNext = await readState();
    check('停着点【下一句】：只前进一句并朗读（组首还会先读组名），不会顺手开始连播',
        nextClick === 'clicked' && afterNext.index === 1 && afterNext.playing === false
        && afterNext.spoke.join(' → ') === '二的乘法口诀 → 一二得二',
        `index=${afterNext.index} playing=${afterNext.playing} ｜ 朗读 ${afterNext.spoke.join(' → ')}`);

    // ② 停着点【上一句】：回退一句 + 读这一句
    await evalJs(`window.__spoke = []`);
    const prevClick = await clickAct('上一句');
    await waitFor(`window.__spoke.length >= 2`, 30, 120);
    const afterPrev = await readState();
    check('停着点【上一句】：回退一句并朗读（与【下一句】对称）',
        prevClick === 'clicked' && afterPrev.index === 0 && afterPrev.playing === false
        && afterPrev.spoke.join(' → ') === '一的乘法口诀 → 一一得一',
        `index=${afterPrev.index} ｜ 朗读 ${afterPrev.spoke.join(' → ')}`);

    // ③ 边界：第 1 句时【上一句】置灰
    const prevDisabled = await evalJs(`(() => {
        const b = Array.from(document.querySelectorAll('.mul-ctl button'))
            .find(x => (x.getAttribute('aria-label') || '') === '上一句');
        return !!b && b.disabled;
    })()`);
    check('第 1 句时【上一句】置灰（没有更早的一句，按了也不该有反应）', prevDisabled);

    // ④ 点【播放】→ 连播开始、按钮切成「暂停」、主控五键一个不少
    await armFrom(0);
    const playClick = await clickAct('播放');
    await sleep(900);
    const during = await evalJs(`(() => {
        const d = window.Alpine.$data(document.querySelector('[x-data]'));
        const btn = document.querySelector('.mul-play');
        return { playing: d.playing, index: d.currentIndex,
                 icon: btn.querySelector('i').className,
                 label: btn.getAttribute('aria-label'),
                 spoke: window.__spoke.length,
                 ctlN: document.querySelectorAll('.mul-ctl .btn').length };
    })()`);
    check('点【播放】开始连播：按钮切成「暂停」、主控五键一个不少、每句都落到朗读',
        playClick === 'clicked' && during.playing === true
        && during.icon.indexOf('bi-pause-fill') >= 0 && during.label === '暂停'
        && during.ctlN === 5 && during.spoke >= 2,
        `playing=${during.playing} 第 ${during.index + 1} 句 朗读 ${during.spoke} 条`);

    // ⑤ 播放中点【下一句】：只按用户的意思 +1，循环不许又替它 +1（否则一次点击跳两句）。
    //    做成确定性的：先连播起来、等它进到那一轮的 600ms 停顿里，这时才点。
    const manualJump = await evalJs(`(async () => {
        const d = window.Alpine.$data(document.querySelector('[x-data]'));
        d.stop(); d.currentIndex = 5; d.audioCache = {};
        window.__audioMode = 'none';
        await new Promise(r => setTimeout(r, 60));
        d.playFromCurrent();                          // 不 await：让循环跑在后台
        await new Promise(r => setTimeout(r, 300));   // 这时它应该正卡在那一轮的停顿里
        const playingBefore = d.playing;
        const btn = Array.from(document.querySelectorAll('.mul-ctl button'))
            .find(b => (b.getAttribute('aria-label') || '') === '下一句');
        btn.click();
        const atClick = d.currentIndex;
        await new Promise(r => setTimeout(r, 900));   // 等那一轮停顿走完 + 下一句读完
        const after = d.currentIndex;
        const stillPlaying = d.playing;
        d.stop();
        return { playingBefore, atClick, after, stillPlaying };
    })()`);
    check('播放中点【下一句】：下标按用户的意思只 +1，循环不会又替它 +1（连播也不中断）',
        manualJump.playingBefore === true && manualJump.atClick === 6
        && manualJump.after === 6 && manualJump.stillPlaying === true,
        JSON.stringify(manualJump));

    // ⑥ 连播到末句自然停，不空转、不越界
    const tail = await evalJs(`(async () => {
        const d = window.Alpine.$data(document.querySelector('[x-data]'));
        d.stop(); d.audioCache = {};
        window.__audioMode = 'none';
        d.currentIndex = d.formulas.length - 1;
        await new Promise(r => setTimeout(r, 60));
        d.playFromCurrent();
        await new Promise(r => setTimeout(r, 700));
        return { index: d.currentIndex, total: d.formulas.length, playing: d.playing };
    })()`);
    check('连播到末句自然停（不空转、不越界）',
        tail.index === tail.total - 1 && tail.playing === false, JSON.stringify(tail));

    // ⑦ 再点一次（此时按钮是【暂停】）立刻停
    await armFrom(0);
    await clickAct('播放');
    await sleep(250);
    const pauseClick = await clickAct('暂停');
    await sleep(140);
    const afterPause = await readState();
    check('再点一次（此时是【暂停】）立刻停：playing 归位、按钮变回「播放」',
        pauseClick === 'clicked' && afterPause.playing === false, JSON.stringify(afterPause));

    // ⑧ 窄屏：组名常驻、主控在上设置在下、不横向溢出
    for (const [w, h, name] of [[390, 844, '390px'], [320, 568, '320px']]) {
        await send('Emulation.setDeviceMetricsOverride',
            { width: w, height: h, deviceScaleFactor: 2, mobile: true });
        await sleep(280);
        const m = await evalJs(`(() => {
            const r = (sel) => document.querySelector(sel).getBoundingClientRect();
            return {
                labels: Array.from(document.querySelectorAll('.mul-label'))
                    .filter(e => e.getClientRects().length > 0)
                    .map(e => e.textContent.trim()).join(','),
                ctlTop: Math.round(r('.mul-ctl').top),
                setTop: Math.round(r('.mul-set').top),
                // 控制条总高 + 设置段折了几行：390 与 320 就靠这两个值区分开
                // （前两个字段在两种宽度下是一样的，只报它们等于没验 320）
                barH: Math.round(r('.mul-bar').height),
                setRows: new Set(Array.from(document.querySelectorAll('.mul-set > .d-flex'))
                    .map(e => Math.round(e.getBoundingClientRect().top))).size,
                overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
                ctlVisible: document.querySelector('.mul-ctl').getClientRects().length > 0,
                setVisible: document.querySelector('.mul-set').getClientRects().length > 0,
            };
        })()`);
        check(`窄屏 ${name}：组名照样看得见（口诀表 / 高亮）、主控在上设置在下、不横向溢出`,
            m.labels === '口诀表,高亮' && m.ctlTop < m.setTop && m.overflowX === 0
            && m.ctlVisible && m.setVisible && m.setRows <= 2 && m.barH <= 140,
            `组名[${m.labels}] 主控 ${m.ctlTop} / 设置 ${m.setTop} ｜ 设置段 ${m.setRows} 行 `
            + `控制条高 ${m.barH}px 溢出 ${m.overflowX}px`);
    }
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
    await sleep(220);

    /* ---------- 9. 整页自包含 ---------- */
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

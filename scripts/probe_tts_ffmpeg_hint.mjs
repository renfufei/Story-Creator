#!/usr/bin/env node
/**
 * /tts-export 定点验证：**「使用 ffmpeg 压缩音频」旁的问号说明可点击**（2026-10-16）。
 *
 * 背景（用户反馈）：
 *   向导第 4 步「高级选项」里，开关后面的问号提示**点了没反应**。
 *   真因：那里只写了 data-bs-toggle 的 tooltip 写法，而全站没有任何一处
 *   new bootstrap.Tooltip() —— Bootstrap 5 不认这个 data 属性做自动初始化，
 *   tooltip 从来没生效过；实际只剩浏览器原生 title 悬停提示（慢、样式不可控，
 *   移动端连悬停都没有）。
 *
 * 修法：换成 Alpine 控制的「点击展开」说明卡（.ffmpeg-hint-trigger + .ffmpeg-hint-pop），
 *   说明卡走正常文档流，不用绝对定位浮层，任何屏宽都不会被弹窗边缘裁掉。
 *
 * 本脚本逐条守护：
 *  Q0 前提：触发器 / 说明卡都在，初始收起，且 DOM 里再没有 data-bs-toggle 的 tooltip
 *  Q1 【核心】点一下 —— 说明卡展开，aria-expanded=true，内容含安装命令与「仅 MP3 生效」
 *  Q2 【核心】再点一下 —— 收起，aria-expanded=false
 *  Q3 展开后点别处 —— 收起（@click.outside）
 *  Q4 点说明卡自身 —— **不**收起（否则没法选中里面的 brew/apt 命令）
 *  Q5 键盘可达：聚焦后回车展开、空格收起
 *  Q6 点问号**不会**连带切换那个 checkbox（触发器必须在 label 之外）
 *  Q7 排版：问号与文字同一行、在文字右侧；说明卡不超出弹窗内容宽度
 *  Q8 375px 窄屏：说明卡仍不横向溢出，整页无横向滚动条
 *  Q9 点「上一步」这类外部真实按钮：既退步骤也顺手收起
 *  Q10 ffmpeg 探测：打开页面必真探测一次；后端说不可用时出现「重新检测」按钮，
 *      点它 → 后端改口说可用 → 徽标/开关立刻翻转（不用刷新页面，更不用重启服务）
 *  Q11 运行期无 JS 异常 / console.error
 *
 * ⚠️ 三个踩过的坑（都直接决定了本脚本怎么写）：
 *   1) CDP 发 Enter 必须带 text:'\\r'，否则 Chrome 不执行 button 的默认激活行为
 *      （Space 则不带 text 也行）；不带 text 时会静默「按了没反应」。
 *   2) 「先点一下再断言状态」的写法会因异步更新而读到旧值 —— 一律用
 *      ensureOpen()/ensureClosed() 这类**点到位再返回**的助手，避免断言真空通过。
 *   3) 想模拟「后端探测结果」时，别往页面里注入 fetch 包装器（指望
 *      Page.addScriptToEvaluateOnNewDocument 在页面脚本之前跑）—— 实测这套 attach
 *      流程下它没生效（页面里 window.__ffmpegProbe 一直 undefined）。改用 CDP 的
 *      Fetch 域在网络层拦，与「页面什么时机发请求」无关。
 *
 * Q10 用 CDP Fetch 域拦 /api/tts-export/ffmpeg-status 的响应，所以与「本机到底装没装
 * ffmpeg」无关，是确定性的。
 *
 * 用法：node scripts/probe_tts_ffmpeg_hint.mjs
 * 可选环境变量：TTS_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.TTS_BASE || 'http://localhost:1888';
const PAGE = BASE + '/tts-export';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9341;
const SHOT_DIR = '/tmp/tts-hint-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tts-hint-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--mute-audio',
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

async function waitFor(label, fn, timeout = 8000, step = 120) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeout) {
        if (await fn()) return true;
        await sleep(step);
    }
    return false;
}

/* 真鼠标点击（走用户真实路径：Alpine 的 @click / @click.outside 都监听 document 上的冒泡事件）。
   ⚠️ 鼠标事件有延迟，点完一律用 waitFor 等状态，不要 sleep 猜。 */
async function clickAt(x, y) {
    const common = { x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1 };
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...common, buttons: 0 });
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', ...common, buttons: 1 });
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...common, buttons: 0 });
}

async function clickSelector(sel) {
    const box = await evalJs(`(() => {
        const el = document.querySelector(${JSON.stringify(sel)});
        if (!el) return null;
        const r = el.getBoundingClientRect();
        if (r.width < 1 || r.height < 1) return null;
        return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    })()`);
    if (!box) throw new Error('点不到元素: ' + sel);
    await clickAt(box.x, box.y);
}

const popVisible = () => evalJs(`(() => {
    const p = document.querySelector('#ffmpegHintPop');
    return !!p && p.getClientRects().length > 0;
})()`);

/* 点到位再返回 —— 避免「点一下然后直接读状态」读到异步更新前的旧值（断言会真空通过） */
async function ensureOpen(max = 6) {
    for (let i = 0; i < max; i++) {
        if (await popVisible()) return true;
        await clickSelector('.ffmpeg-hint-trigger');
        await sleep(240);
    }
    return await popVisible();
}
async function ensureClosed(max = 6) {
    for (let i = 0; i < max; i++) {
        if (!(await popVisible())) return true;
        await clickSelector('.ffmpeg-hint-trigger');
        await sleep(240);
    }
    return !(await popVisible());
}

/* 回车 / 空格：聚焦的 <button> 靠浏览器默认激活行为产生 click。
   ⚠️ Enter 必须带 text:'\r'，否则 Chrome 不执行默认激活（实测 hits=0）。 */
async function pressKey(kind) {
    const base = kind === 'Enter'
        ? { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13, text: '\r', unmodifiedText: '\r' }
        : { key: ' ', code: 'Space', windowsVirtualKeyCode: 32, nativeVirtualKeyCode: 32, text: ' ' };
    await send('Input.dispatchKeyEvent', { type: 'keyDown', ...base });
    await send('Input.dispatchKeyEvent', {
        type: 'keyUp', key: base.key, code: base.code,
        windowsVirtualKeyCode: base.windowsVirtualKeyCode, nativeVirtualKeyCode: base.nativeVirtualKeyCode
    });
}

/* 一次 evaluate 取全，避免逐字段读之间状态漂移（Alpine 的 DOM 更新是异步的，
   取快照前一律先静置一拍） */
const SNAP = `(() => {
    const trig = document.querySelector('.ffmpeg-hint-trigger');
    const pop  = document.querySelector('#ffmpegHintPop');
    const box  = document.querySelector('#useFfmpeg');
    const modalBody = document.querySelector('#wizardModal .modal-body');
    const badge = document.querySelector('.form-check-label .badge');
    const vis = (el) => !!el && el.getClientRects().length > 0
        && getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden';
    const rect = (el) => { const r = el.getBoundingClientRect();
        return { l: Math.round(r.left), t: Math.round(r.top), r: Math.round(r.right),
                 b: Math.round(r.bottom), w: Math.round(r.width), h: Math.round(r.height),
                 cy: Math.round(r.top + r.height / 2) }; };
    return {
        hasTrig: !!trig,
        hasPop: !!pop,
        popVisible: vis(pop),
        trigVisible: vis(trig),
        popCloak: pop ? pop.hasAttribute('x-cloak') : null,
        aria: trig ? trig.getAttribute('aria-expanded') : null,
        popText: pop ? pop.innerText.replace(/\\s+/g, ' ').trim() : '',
        checked: box ? box.checked : null,
        legacyTooltips: document.querySelectorAll('[data-bs-toggle="tooltip"]').length,
        trigBox: trig ? rect(trig) : null,
        popBox: vis(pop) ? rect(pop) : null,
        labelBox: badge ? rect(badge) : null,
        bodyBox: modalBody ? rect(modalBody) : null,
        popBg: pop ? getComputedStyle(pop).backgroundColor : '',
        popBorderLeft: pop ? getComputedStyle(pop).borderLeftWidth : '',
        trigCursor: trig ? getComputedStyle(trig).cursor : '',
        trigTag: trig ? trig.tagName : null,
        docScrollW: document.documentElement.scrollWidth,
        docClientW: document.documentElement.clientWidth
    };
})()`;

const snap = async () => { await sleep(240); return evalJs(SNAP); };

/* ==================== Q10 用的网络拦截 ====================
   把 /api/tts-export/ffmpeg-status 的响应换掉，用来模拟「后端探测到什么」。
   用 CDP 的 Fetch 域在**网络层**拦，而不是往页面里注入 fetch 包装：
     · 注入法要求脚本必须在新文档的页面脚本之前跑（Page.addScriptToEvaluateOnNewDocument），
       实测在这套 attach 流程下没生效（window.__ffmpegProbe 一直是 undefined）；
     · 网络层拦截与「页面什么时机发请求」无关，稳。
   覆盖值只活在探针进程里，改一个变量即可，无需跨导航传递。 */
let ffmpegOverride = null;   // null = 放行到真后端；true/false = 直接返回这个值
let ffmpegCalls = 0;         // 被拦到的次数（证明页面确实发了请求）

async function handlePausedRequest(p) {
    if (p.request.url.indexOf('/api/tts-export/ffmpeg-status') >= 0) {
        ffmpegCalls++;
        if (ffmpegOverride === null) {
            await send('Fetch.continueRequest', { requestId: p.requestId });
        } else {
            await send('Fetch.fulfillRequest', {
                requestId: p.requestId,
                responseCode: 200,
                responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
                body: Buffer.from(JSON.stringify({ available: ffmpegOverride })).toString('base64')
            });
        }
        return;
    }
    await send('Fetch.continueRequest', { requestId: p.requestId });
}

const FFMPEG_SNAP = `(() => {
    const badge = document.querySelector('.form-check-label .badge');
    const box = document.querySelector('#useFfmpeg');
    const btn = document.querySelector('#ffmpegRecheck');
    return {
        badge: badge ? badge.innerText.trim() : null,
        disabled: box ? box.disabled : null,
        checked: box ? box.checked : null,
        recheckVisible: !!btn && btn.getClientRects().length > 0,
        recheckDisabled: btn ? btn.disabled : null
    };
})()`;
const ffmpegSnap = async () => { await sleep(180); return evalJs(FFMPEG_SNAP); };

/* 打开向导并直接跳到第 4 步（不必真的选项目/章节 —— 本探针只验问号交互） */
async function openStep4() {
    const ok = await evalJs(`(() => {
        if (!window.Alpine || !window.bootstrap) return false;
        const modalEl = document.getElementById('wizardModal');
        if (!modalEl) return false;
        const d = Alpine.$data(modalEl);
        if (!d) return false;
        d.wizardStep = 3;
        bootstrap.Modal.getOrCreateInstance(modalEl).show();
        return true;
    })()`);
    if (!ok) throw new Error('打不开向导弹窗');
    await waitFor('弹窗与第 4 步可见', async () => await evalJs(`(() => {
        const m = document.getElementById('wizardModal');
        const t = document.querySelector('.ffmpeg-hint-trigger');
        return !!m && m.classList.contains('show') && !!t && t.getClientRects().length > 0;
    })()`), 8000, 150);
    await sleep(320);   // Alpine 的 DOM effect 是异步的，设值后再等一拍
}

async function loadPage(width, height, mobile) {
    await send('Emulation.setDeviceMetricsOverride', {
        width, height, deviceScaleFactor: mobile ? 2 : 1, mobile
    });
    await send('Page.navigate', { url: PAGE });
    const ok = await waitFor('页面 Alpine 就绪', async () =>
        (await evalJs(`!!window.Alpine && !!document.querySelector('.ffmpeg-hint-trigger')`)) === true, 15000, 200);
    await openStep4();
    return ok;
}

async function shot(name) {
    try {
        fs.mkdirSync(SHOT_DIR, { recursive: true });
        const r = await send('Page.captureScreenshot', { format: 'png' });
        fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
    } catch (e) { /* 截图失败不影响判定 */ }
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
        } else if (m.method === 'Fetch.requestPaused') {
            // 拦截到的请求必须放行/应答，否则页面会一直挂在等响应上
            handlePausedRequest(m.params).catch(e => {
                consoleErrors.push('Fetch 拦截失败: ' + e.message);
            });
        }
    };

    await send('Runtime.enable');

    /* ---------- Q0 前提 ---------- */
    console.log('\n— Q0 前提 —');
    const ready = await loadPage(1280, 940, false);
    check('页面就绪且问号触发器已渲染', ready);
    const s0 = await snap();
    check('触发器是原生 <button>（键盘可达、语义正确）', s0.trigTag === 'BUTTON', 'tag=' + s0.trigTag);
    check('说明卡元素存在、x-cloak 已被 Alpine 摘掉（否则 !important 会永久藏住它）',
        s0.hasPop && s0.popCloak === false, `hasPop=${s0.hasPop} xCloak=${s0.popCloak}`);
    check('【回归】初始收起：说明卡不可见、aria-expanded="false"',
        s0.popVisible === false && s0.aria === 'false', `visible=${s0.popVisible} aria=${s0.aria}`);
    check('【回归】DOM 里已没有 data-bs-toggle 的 tooltip 属性（那是从来没生效的死写法）',
        s0.legacyTooltips === 0, 'count=' + s0.legacyTooltips);
    await shot('00-desktop-collapsed');

    /* ---------- Q1 点一下展开 ---------- */
    console.log('\n— Q1 点击展开 —');
    await clickSelector('.ffmpeg-hint-trigger');
    const opened = await waitFor('说明卡展开', popVisible, 4000, 120);
    const s1 = await snap();
    check('【核心】点一下问号，说明卡展开', opened && s1.popVisible === true,
        `opened=${opened} visible=${s1.popVisible}`);
    check('aria-expanded 同步变为 "true"', s1.aria === 'true', 'aria=' + s1.aria);
    check('说明内容含安装命令与「仅 MP3 格式生效」的关键口径',
        /brew install ffmpeg/.test(s1.popText) && /apt install ffmpeg/.test(s1.popText)
            && /仅 MP3 格式生效/.test(s1.popText),
        s1.popText.slice(0, 90));
    check('说明卡是样式化卡片（浅底 + 左侧主色边），不是裸文本',
        s1.popBorderLeft !== '0px' && s1.popBg !== 'rgba(0, 0, 0, 0)',
        `border-left=${s1.popBorderLeft} bg=${s1.popBg}`);
    check('触发器有 pointer 光标', s1.trigCursor === 'pointer', 'cursor=' + s1.trigCursor);
    await shot('01-desktop-expanded');

    /* ---------- Q2 再点一下收起 ---------- */
    console.log('\n— Q2 再点一下收起 —');
    await clickSelector('.ffmpeg-hint-trigger');
    const closed = await waitFor('说明卡收起', async () => (await popVisible()) === false, 4000, 120);
    const s2 = await snap();
    check('【核心】再点一下收起（toggle 生效）', closed && s2.popVisible === false,
        `closed=${closed} visible=${s2.popVisible}`);
    check('aria-expanded 归位 "false"', s2.aria === 'false', 'aria=' + s2.aria);

    /* ---------- Q3 点别处收起 ---------- */
    console.log('\n— Q3 点外部收起 —');
    check('先把说明卡点开（前置）', await ensureOpen());
    await clickSelector('#wizardModal .modal-title');   // 弹窗标题：在触发器容器之外
    const outClosed = await waitFor('外部点击后收起', async () => (await popVisible()) === false, 4000, 120);
    check('展开后点别处会自动收起（@click.outside）', outClosed, 'closed=' + outClosed);

    /* ---------- Q4 点说明卡自身不收起 ---------- */
    console.log('\n— Q4 点说明卡自身 —');
    check('先把说明卡点开（前置）', await ensureOpen());
    await clickSelector('#ffmpegHintPop');
    await sleep(300);
    const selfOpen = await popVisible();
    check('点说明卡自己不会把它关掉（里面的 brew/apt 命令可选可复制）', selfOpen === true, 'open=' + selfOpen);

    /* ---------- Q5 键盘可达 ---------- */
    console.log('\n— Q5 键盘可达 —');
    check('先收到收起态（前置）', await ensureClosed());
    await evalJs(`document.querySelector('.ffmpeg-hint-trigger').focus()`);
    const focused = await evalJs(`document.activeElement === document.querySelector('.ffmpeg-hint-trigger')`);
    check('问号可被键盘聚焦（原生 button，天然可聚焦）', focused === true, 'focused=' + focused);
    await pressKey('Enter');
    const kbOpen = await waitFor('回车展开', popVisible, 4000, 120);
    check('回车能展开', kbOpen === true, 'open=' + kbOpen);
    await pressKey('Space');
    const kbClose = await waitFor('空格收起', async () => (await popVisible()) === false, 4000, 120);
    check('空格能收起（非真空：上一步已确认它确实是展开的）', kbClose === true, 'closed=' + kbClose);

    /* ---------- Q6 不连带切换 checkbox（触发器必须在 label 之外） ---------- */
    console.log('\n— Q6 不误触开关 —');
    check('先收到收起态（前置）', await ensureClosed());
    /* ⚠️ 本机没装 ffmpeg 时这个开关是 disabled 的，而 disabled 的 checkbox 本来就不会被
       label 激活 —— 那样「点问号不误触开关」这条会**真空通过**。先把可用性临时置为 true，
       断言才有牙齿；紧接着再用「点 label 会不会真的切换」做对照组，把判别力摆出来。 */
    await evalJs(`(() => {
        const d = Alpine.$data(document.getElementById('wizardModal'));
        d.ffmpegAvailable = true;
        d.wizard.useFfmpeg = false;
        return true;
    })()`);
    await sleep(280);
    const interactive = await evalJs(`!document.querySelector('#useFfmpeg').disabled`);
    check('开关此刻确实可交互（否则下面的「不误触」会因 disabled 而真空通过）',
        interactive === true, 'interactive=' + interactive);
    const before = await evalJs(`document.querySelector('#useFfmpeg').checked`);
    await clickSelector('.ffmpeg-hint-trigger');
    const q6Open = await waitFor('说明卡展开', popVisible, 4000, 120);   // 证明这一下确实点在触发器上
    const after = await evalJs(`document.querySelector('#useFfmpeg').checked`);
    check('【核心】点问号不会连带切换「使用 ffmpeg 压缩音频」开关',
        q6Open === true && before === false && after === false,
        `opened=${q6Open} checked ${before} -> ${after}`);
    check('触发器确实在 <label> 之外（在 label 内点一下会连点开关）',
        (await evalJs(`!document.querySelector('.ffmpeg-hint-trigger').closest('label')`)) === true);
    // 对照组：同样状态下点 label 本身 -> 开关真的会被切换（证明上面那条不是真空断言）
    await clickSelector('.form-check-label');
    await sleep(300);
    const byLabel = await evalJs(`document.querySelector('#useFfmpeg').checked`);
    check('对照组：点 label 本身开关会被切换（证明「不误触」这条确有判别力）',
        byLabel === true, 'checked=' + byLabel);

    /* ---------- Q7 排版 ---------- */
    console.log('\n— Q7 排版 —');
    check('确保说明卡处于展开态（前置）', await ensureOpen());
    const s7 = await snap();
    check('问号与文字同处一行、且在徽标右侧',
        !!s7.trigBox && !!s7.labelBox
            && Math.abs(s7.trigBox.cy - s7.labelBox.cy) <= 5
            && s7.trigBox.l > s7.labelBox.r,
        s7.trigBox && s7.labelBox
            ? `问号中心y=${s7.trigBox.cy} 徽标中心y=${s7.labelBox.cy} 问号左=${s7.trigBox.l} 徽标右=${s7.labelBox.r}`
            : `trig=${JSON.stringify(s7.trigBox)} label=${JSON.stringify(s7.labelBox)}`);
    check('说明卡不超出弹窗内容区左右边界（不会被弹窗裁掉）',
        !!s7.popBox && !!s7.bodyBox
            && s7.popBox.l >= s7.bodyBox.l - 1 && s7.popBox.r <= s7.bodyBox.r + 1,
        s7.popBox && s7.bodyBox
            ? `说明卡 [${s7.popBox.l},${s7.popBox.r}] 内容区 [${s7.bodyBox.l},${s7.bodyBox.r}]`
            : `visible=${s7.popVisible} pop=${JSON.stringify(s7.popBox)} body=${JSON.stringify(s7.bodyBox)}`);
    check('展开说明卡后整页没有横向溢出', s7.docScrollW <= s7.docClientW + 1,
        `scrollW=${s7.docScrollW} clientW=${s7.docClientW}`);

    /* ---------- Q8 375px 窄屏 ---------- */
    console.log('\n— Q8 375px 窄屏 —');
    await loadPage(375, 720, true);
    check('375px：说明卡点得开（前置）', await ensureOpen());
    const s8 = await snap();
    check('375px：说明卡左右仍在弹窗内容区内',
        !!s8.popBox && !!s8.bodyBox
            && s8.popBox.l >= s8.bodyBox.l - 1 && s8.popBox.r <= s8.bodyBox.r + 1,
        s8.popBox && s8.bodyBox
            ? `说明卡 [${s8.popBox.l},${s8.popBox.r}] 内容区 [${s8.bodyBox.l},${s8.bodyBox.r}]`
            : `visible=${s8.popVisible}`);
    check('375px：整页无横向滚动条', s8.docScrollW <= s8.docClientW + 1,
        `scrollW=${s8.docScrollW} clientW=${s8.docClientW}`);
    check('375px：问号仍与文字同行、没被挤到下一行',
        !!s8.trigBox && !!s8.labelBox && Math.abs(s8.trigBox.cy - s8.labelBox.cy) <= 5,
        s8.trigBox && s8.labelBox ? `问号y=${s8.trigBox.cy} 徽标y=${s8.labelBox.cy}` : 'n/a');
    await shot('02-mobile-expanded');

    /* ---------- Q9 步骤切换后状态收敛 ---------- */
    console.log('\n— Q9 步骤切换 —');
    await loadPage(1280, 940, false);
    check('点开说明卡（前置）', await ensureOpen());
    await clickSelector('#wizardModal [data-wizard-back]');   // 「上一步」= 容器之外的真实按钮
    await sleep(340);
    const stepBack = await evalJs(`(() => {
        const p = document.querySelector('#ffmpegHintPop');
        const d = Alpine.$data(document.getElementById('wizardModal'));
        return { open: !!p && p.getClientRects().length > 0, step: d.wizardStep };
    })()`);
    check('点「上一步」既退步骤也顺手收起说明卡（外部点击）',
        stepBack.step === 2 && stepBack.open === false, JSON.stringify(stepBack));

    /* ---------- Q10 ffmpeg 探测：每次打开页面都真探测 + 「重新检测」 ---------- */
    console.log('\n— Q10 ffmpeg 重新检测 —');
    await send('Fetch.enable', {
        patterns: [{ urlPattern: '*/api/tts-export/ffmpeg-status*', requestStage: 'Request' }]
    });

    // (1) 后端说「可用」→ 打开页面就该是可用的。
    //     注意：ffmpegAvailable 的初始值是 false，所以「变成可用」只可能来自这次响应
    //     —— 这条同时证明了「打开页面确实会去问后端」。
    ffmpegOverride = true;
    ffmpegCalls = 0;
    await loadPage(1280, 940, false);
    const c1 = await waitFor('打开页面后已问过后端', async () => ffmpegCalls >= 1, 6000, 120);
    const g1 = await ffmpegSnap();
    check('【核心】打开页面就真探测一次（初始值是「不可用」，能翻成「可用」只可能来自这次响应）',
        c1 && g1.badge === '可用' && g1.disabled === false && g1.checked === true,
        `calls=${ffmpegCalls} badge=${g1.badge} disabled=${g1.disabled} checked=${g1.checked}`);
    check('可用时不给用户看「重新检测」按钮（无处可用）',
        g1.recheckVisible === false, 'visible=' + g1.recheckVisible);

    // (2) 换成「不可用」再开一次页面 → 又探测了一次，状态跟着变（没有沿用上一次页面的结论）
    ffmpegOverride = false;
    ffmpegCalls = 0;
    await loadPage(1280, 940, false);
    const c2 = await waitFor('第二次打开页面又问过后端', async () => ffmpegCalls >= 1, 6000, 120);
    check('每次打开页面都重新探测一次（不是把上一次页面的结论沿用下来）',
        c2 && ffmpegCalls >= 1, 'calls=' + ffmpegCalls);
    check('先把说明卡点开（前置）', await ensureOpen());
    await sleep(200);
    const g2 = await ffmpegSnap();
    check('后端说不可用 → 徽标「不可用」、开关禁用、说明卡里出现「重新检测」',
        g2.badge === '不可用' && g2.disabled === true && g2.recheckVisible === true,
        JSON.stringify(g2));
    await shot('03-unavailable-with-recheck');

    // (3) 对照组：仍然不可用时点「重新检测」，确实又发了一次请求（证明按钮不是自嗨）
    await sleep(400);           // 等上面那次页面加载的请求计数落定
    ffmpegCalls = 0;
    await clickSelector('#ffmpegRecheck');
    const hit = await waitFor('重新检测发出请求', async () => ffmpegCalls >= 1, 5000, 100);
    const g3 = await ffmpegSnap();
    check('点「重新检测」会真的再问一次后端', hit && ffmpegCalls >= 1, 'calls=' + ffmpegCalls);
    check('结果仍是不可用时界面保持原样、按钮还在（上一步已确认请求发出，非真空）',
        g3.badge === '不可用' && g3.recheckVisible === true && g3.recheckDisabled === false,
        JSON.stringify(g3));

    // (4) 【核心】用户装好 ffmpeg（后端改口说可用）→ 点「重新检测」立刻翻转，
    //     不刷新页面、更不重启后台服务
    ffmpegOverride = true;
    ffmpegCalls = 0;
    await clickSelector('#ffmpegRecheck');
    const flipped = await waitFor('界面翻转为可用', async () => {
        const s = await ffmpegSnap();
        return s.badge === '可用' && s.disabled === false && s.checked === true;
    }, 5000, 120);
    const g4 = await ffmpegSnap();
    check('【核心】点「重新检测」→ 徽标变「可用」、开关解禁并自动勾上、按钮收起',
        flipped === true && g4.recheckVisible === false, JSON.stringify(g4));
    check('翻转后说明卡仍是展开的（点按钮没有把它误关）',
        (await evalJs(`(() => {
            const p = document.querySelector('#ffmpegHintPop');
            return !!p && p.getClientRects().length > 0;
        })()`)) === true);
    await shot('04-rechecked-available');

    // 收尾
    ffmpegOverride = null;
    await send('Fetch.disable');

    /* ---------- Q11 运行期异常 ---------- */
    console.log('\n— Q11 运行期异常 —');
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 3).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

    const bad = results.filter(r => !r.ok);
    console.log(`\n==== ${results.length - bad.length}/${results.length} 通过 ====`);
    if (bad.length) bad.forEach(b => console.log('  FAIL: ' + b.name + '  [' + b.extra + ']'));
    console.log('截图目录: ' + SHOT_DIR);
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

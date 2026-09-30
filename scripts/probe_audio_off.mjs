#!/usr/bin/env node
/**
 * 探针：**冒烟测试的静音守门**（别让测试从音箱里念英文）。
 *
 * 背景（用户报的问题）：跑浏览器冒烟时本机会真的输出音频。原因是两道口子：
 *   ① 浏览器音频管线（媒体元素 / Web Audio）—— 靠启动参数 `--mute-audio` 掐；
 *   ② `speechSynthesis`（平台 TTS，macOS 上是 AVSpeechSynthesizer）—— **`--mute-audio` 管不到**，
 *      它不经过浏览器的音频输出管线，无头浏览器照样出声。而单词页默认 `soundOn: true`，
 *      点两下卡片整台电脑就开始念。
 * 所以守门必须是**两件套**：`--mute-audio` + 注入 `src/test/resources/silent-audio.js`。
 * 这份探针就是来钉住这两件套的：机制、持久性、以及"以后新增探针别忘了装"。
 *
 * 本探针刻意**不依赖后端**：机制部分走 `data:` 文档，真实页面只是在最后确认守门没把页面搞坏。
 *
 * 用法：node scripts/probe_audio_off.mjs
 *       WM_OUT=/tmp/xxx node scripts/probe_audio_off.mjs
 *       WM_BASE=http://localhost:1888 node scripts/probe_audio_off.mjs
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
    SILENT_AUDIO_FLAGS, SILENT_AUDIO_INIT, SILENT_AUDIO_GUARD, AUDIO_OFF_PROBE, installSilentAudio,
} from './lib/silent-audio.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const BASE = process.env.WM_BASE || 'http://localhost:1888';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9351;
const OUT = process.env.WM_OUT || '/tmp/audio-probe';
/** 纯本地文档：只验守门机制，不依赖后端是否在跑 */
const PLAIN = 'data:text/html,<meta charset="utf-8"><title>plain</title><h1 id="t">plain</h1>';

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'aud-'));

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    ...SILENT_AUDIO_FLAGS,            // ① 浏览器音频管线静音（媒体元素 + Web Audio）
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
async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    const file = path.join(OUT, name + '.png');
    fs.writeFileSync(file, Buffer.from(r.data, 'base64'));
    console.log('  截图 ' + file);
    return file;
}
async function waitFor(predicate, tries = 60, gap = 150) {
    for (let i = 0; i < tries; i++) {
        try { if (await evalJs(predicate)) return true; } catch (e) { /* 导航中 */ }
        await sleep(gap);
    }
    return false;
}
async function nav(url, predicate, tries = 60, gap = 150) {
    await send('Page.navigate', { url });
    return waitFor(predicate, tries, gap);
}

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
            pageErrors.push(m.params.exceptionDetails.exception?.description
                || m.params.exceptionDetails.text);
            return;
        }
        if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
            consoleErrors.push((m.params.args || []).map(a => a.value || a.description || '').join(' '));
            return;
        }
        if (m.id && pending.has(m.id)) {
            const p = pending.get(m.id);
            pending.delete(m.id);
            m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result);
        }
    };
    await send('Runtime.enable');
    await installSilentAudio(send);          // ← 与被测脚本用的是同一个函数
    await send('Emulation.setDeviceMetricsOverride',
        { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });

    /* ---------- 1. 启动参数：浏览器**真实**命令行里要带 --mute-audio ----------
       不去 ps/lsof（沙箱与 CI 里常被拒），改成让浏览器自己报：chrome://version 页面上
       就印着它的完整命令行。这是"这个开关真的传给了浏览器"的唯一硬证据。 */
    await send('Page.navigate', { url: 'chrome://version' });
    await waitFor(`!!document.getElementById('command_line')`, 40, 150);
    const cmdline = await evalJs(`(() => {
        const el = document.getElementById('command_line');
        return (el ? el.textContent : document.body.innerText) || '';
    })()`);
    check('① 浏览器真实命令行里带 --mute-audio（音频管线出口静音）',
        cmdline.includes('--mute-audio'),
        (cmdline.match(/--[a-z-]*mute[a-z-]*/g) || ['（命令行里没有 mute 开关）']).join(' '));
    check('① 命令行里没有"关掉静音"的反向开关（别被 --disable-audio 之类抵消）',
        !/--(no|disable|un)?mute-audio\s*=?\s*(0|false)/.test(cmdline),
        'ok');
    check('共享加载器暴露的启动参数含 --mute-audio（别把开关写死在各脚本里）',
        SILENT_AUDIO_FLAGS.includes('--mute-audio'), SILENT_AUDIO_FLAGS.join(' '));
    check('守门脚本体非空且带版本标记（从 src/test/resources/silent-audio.js 读同一份）',
        SILENT_AUDIO_INIT.includes(SILENT_AUDIO_GUARD) && SILENT_AUDIO_INIT.length > 500,
        `${SILENT_AUDIO_GUARD} / ${SILENT_AUDIO_INIT.length} 字节`);

    /* ---------- 2. 新文档里的守门：TTS 与媒体元素都被接管 ---------- */
    await nav(PLAIN, `document.getElementById('t') && document.getElementById('t').textContent === 'plain'`);
    const hooks = await evalJs(AUDIO_OFF_PROBE);
    check('② 新文档里守门已生效：speechSynthesis.speak 与 HTMLMediaElement.play 都换成守门版本',
        hooks.guard === SILENT_AUDIO_GUARD && hooks.ttsHooked && hooks.mediaHooked,
        JSON.stringify(hooks));

    /* ---------- 3. TTS：调用被记录，但原生引擎根本没被启动 ---------- */
    const tts = await evalJs(`(async () => {
        const before = window.__smokeAudioOff.spoken.length;
        const synth = window.speechSynthesis;
        const u = new SpeechSynthesisUtterance('静音守门测试');
        let ended = 0;
        u.onend = () => { ended++; };
        synth.speak(u);
        // 原生引擎真在念的话，speaking 会是 true；守门版本永远不碰引擎。
        const speakingRightAfter = synth.speaking;
        const pendingRightAfter = synth.pending;
        await new Promise(r => setTimeout(r, 400));
        return {
            recorded: window.__smokeAudioOff.spoken.slice(before),
            ended,
            speakingRightAfter,
            pendingRightAfter,
            speakingAfter: synth.speaking,
            nativeIsGone: !!(synth.speak && synth.speak.__smokeGuard === true),
        };
    })()`);
    check('③ TTS 调用被守门接管：文本被记录、onend 异步回调（"念完了再继续"的语义保住）',
        tts.recorded.length === 1 && tts.recorded[0] === '静音守门测试' && tts.ended === 1,
        `记录 ${JSON.stringify(tts.recorded)} / onend ${tts.ended} 次`);
    check('③ 原生语音引擎没有被启动（真在念的话 speaking 会是 true —— 这条是"没出声"的可测代理）',
        tts.speakingRightAfter === false && tts.pendingRightAfter === false
        && tts.speakingAfter === false && tts.nativeIsGone,
        `speak 当刻 speaking=${tts.speakingRightAfter} pending=${tts.pendingRightAfter}，400ms 后 speaking=${tts.speakingAfter}`);

    /* ---------- 4. 媒体元素：play() 之前被压成 muted + volume 0 ---------- */
    const media = await evalJs(`(async () => {
        const before = window.__smokeAudioOff.playCalls;
        const a = new Audio();
        a.play().catch(() => {});          // 没有 src，原生 play() 会 reject —— 属性已先被守门压上
        const box = { muted: a.muted, volume: a.volume, playCalls: window.__smokeAudioOff.playCalls - before };
        try { a.pause(); } catch (e) {}
        return box;
    })()`);
    check('④ 媒体元素 play() 前被强制 muted=true / volume=0（即便某环境忽略 --mute-audio 也兜住）',
        media.muted === true && media.volume === 0 && media.playCalls >= 1,
        JSON.stringify(media));

    /* ---------- 5. Web Audio 没被改坏（它的输出由 --mute-audio 在管线入口掐掉） ---------- */
    const wa = await evalJs(`(async () => {
        const Ctor = window.AudioContext || window.webkitAudioContext;
        if (!Ctor) return { supported: false };
        const ctx = new Ctor();
        const gain = ctx.createGain();
        const osc = ctx.createOscillator();
        osc.connect(gain);
        gain.connect(ctx.destination);      // 不插桩：图能正常搭起来，输出在管线入口被静音
        const box = { supported: true, state: ctx.state, hasGain: !!gain, hasOsc: !!osc,
                      hooked: !!AudioNode.prototype.connect.__smokeGuard };
        await ctx.close();
        return box;
    })()`);
    check('⑤ Web Audio 仍可正常建图（守门不插桩它，输出交给 --mute-audio）',
        wa.supported && wa.hasGain && wa.hasOsc && wa.hooked === false,
        JSON.stringify(wa));

    /* ---------- 6. 守门对每次导航都生效，用例里不用重新装 ---------- */
    await send('Page.reload');
    await waitFor(`!!document.getElementById('t')`);
    const afterReload = await evalJs(AUDIO_OFF_PROBE);
    const secondDoc = await nav(PLAIN + '&x=2', `!!document.getElementById('t')`);
    const afterSecondDoc = await evalJs(AUDIO_OFF_PROBE);
    check('⑥ 刷新 / 换文档之后守门依旧在（addScriptToEvaluateOnNewDocument 的语义就是"每个新文档"）',
        secondDoc && afterReload.guard === SILENT_AUDIO_GUARD && afterReload.ttsHooked
        && afterSecondDoc.guard === SILENT_AUDIO_GUARD && afterSecondDoc.mediaHooked,
        `刷新后 ${JSON.stringify(afterReload)} / 新文档 ${JSON.stringify(afterSecondDoc)}`);

    /* ---------- 7. 真实页面上守门不影响功能 ---------- */
    const errBefore = pageErrors.length;
    const realPage = await nav(BASE + '/learn/word-match',
        `!!document.querySelector('.wm-root') && document.querySelectorAll('.wm-card').length > 0`);
    const realHooks = await evalJs(AUDIO_OFF_PROBE);
    const realCards = await evalJs(`document.querySelectorAll('.wm-card').length`);
    check('⑦ 单词页照常渲染出卡片，且守门同样生效（TTS 是它唯一发声口，最该被守住的页面）',
        realPage && realCards > 0 && realHooks.ttsHooked && realHooks.mediaHooked,
        `${realCards} 张卡 / ${JSON.stringify(realHooks)}`);
    await shot('60-audio-off-word-match');

    /* ---------- 8. 静态检查：以后新加探针忘了装守门会在这里红 ---------- */
    const scriptFiles = fs.readdirSync(path.join(ROOT, 'scripts'))
        .filter(f => f.endsWith('.mjs')).map(f => 'scripts/' + f);
    const spawners = scriptFiles.filter(f => /spawn\(\s*CHROME/.test(fs.readFileSync(path.join(ROOT, f), 'utf8')));
    const missing = spawners.filter((f) => {
        const src = fs.readFileSync(path.join(ROOT, f), 'utf8');
        return !(src.includes('silent-audio.mjs') && src.includes('SILENT_AUDIO_FLAGS')
            && src.includes('installSilentAudio(send)'));
    });
    check('⑧ 所有会拉起 Chrome 的脚本都装了静音守门（新增探针忘了装就会红）',
        spawners.length >= 8 && missing.length === 0,
        `${spawners.length} 个脚本，缺守门：${missing.join(' , ') || '无'}`);

    const java = fs.readFileSync(
        path.join(ROOT, 'src/test/java/com/storycreator/testsupport/HeadlessChrome.java'), 'utf8');
    check('⑧ Java 端到端测试同样两件套齐备（启动参数 --mute-audio + 注入 /silent-audio.js）',
        java.includes('"--mute-audio"') && java.includes('"/silent-audio.js"')
        && java.includes('Page.addScriptToEvaluateOnNewDocument'),
        'HeadlessChrome.java');

    /* ---------- 9. 守门自己不能污染页面 ---------- */
    check('⑨ 守门脚本没有引入未捕获 JS 错误 / console.error（它会跑在每个被测页面上）',
        pageErrors.length === errBefore && consoleErrors.length === 0,
        `新增异常 ${pageErrors.length - errBefore} 个 / console.error ${consoleErrors.length} 条`);

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

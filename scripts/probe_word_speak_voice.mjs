#!/usr/bin/env node
/**
 * 探针：单词匹配页「系统朗读的音色必须由页面钉住」。
 *
 * 为什么要有这条探针：
 *   用户报「系统朗读完全变了，声音沙哑，什么都听不出来」。
 *   根因不是代码退化 —— 是页面只给 `u.lang = 'en-US'`、**不给 u.voice**，
 *   于是音色由系统默认决定。这台机器 macOS 默认语音是
 *   com.apple.voice.compact.zh-CN.Tingting（中文音色），Chrome 在这台机器上又暴露了
 *   199 个语音（en-US 44 个），里面混着 Albert / Zarvox / Trinoids / Bells / Bad News
 *   这类整蛊音色与 Eddy / Flo 这类 Eloquence 机器人音，且**一个都没标 default** ——
 *   英文单词落到什么音色上完全不可预测。
 *
 * 钉这些事：
 *   ① 页面启动后 __wmVoice.name 必须是白名单首选（本机 = Samantha），不能是空的；
 *   ② 可选音色清单里不许出现整蛊 / 机器人音色；
 *   ③ 「系统朗读」档说出去的 utterance 必须带 voice（name=Samantha）、lang=en-US、rate=0.85
 *      —— 没有 voice 就等于把音色交回系统默认，这正是这次的病根；
 *   ④ 手动换音色后，下一次 utterance 立刻用新音色，并且写进 localStorage；
 *   ⑤ 设置面板里的「朗读音色」行只在系统朗读档出现，下拉的选中项 = 当前钉住的音色；
 *   ⑥ ?wmVoice= 能强制指定（测试通道），用来复现「整蛊音色」这种坏情况；
 *   ⑦ 全程零未捕获 JS 异常。
 *
 * ⚠️ 无头环境发不出声音，所以这里**不验听感**，只验「交给合成引擎的是哪个音色」。
 *
 * 为什么要拦导航：静态页打进 jar，改完不重启服务看不到新东西。
 * 这里把 /learn/word-match 的响应换成本地文件，其余请求照旧走真实后端。
 * 打包重启后可以 WM_NO_INTERCEPT=1 直接验线上页。
 *
 * 用法：node scripts/probe_word_speak_voice.mjs
 *       WM_NO_INTERCEPT=1 node scripts/probe_word_speak_voice.mjs
 *   需要 managed Node 22（全局 WebSocket）。
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
const PAGE = BASE + '/learn/word-match';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.WM_CDP_PORT || 9362);
const OUT = process.env.WM_OUT || '/tmp/wm-voice-probe';
const NO_INTERCEPT = process.env.WM_NO_INTERCEPT === '1';
const EXPECT = process.env.WM_EXPECT_VOICE || 'Samantha';

fs.mkdirSync(OUT, { recursive: true });
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wmv-'));

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
async function nav(url, predicate) {
    await send('Page.navigate', { url });
    return waitFor(predicate);
}

/* 把 speechSynthesis.speak 换成记录器：只抓「交给合成引擎的 utterance」，
   然后立刻回调 onend，免得 playSystem 的兜底定时器白等 1.5s 拖慢探针。 */
const INSTALL_STUB = `(() => {
    window.__wmSpoken = [];
    var synth = window.speechSynthesis;
    synth.speak = function (u) {
        window.__wmSpoken.push({
            text: u.text,
            voice: u.voice ? u.voice.name : null,
            lang: u.lang || '',
            rate: u.rate
        });
        setTimeout(function () { try { u.onend && u.onend(); } catch (e) {} }, 0);
    };
    synth.cancel = function () {};
    return true;
})()`;

/* 注意：语音清单是**异步**到的（headless 下有时要等 1~3s，偶发 0 个）。
   所以就绪条件必须等「已经解析出一个音色」，只等 Alpine 起来会读到空清单，
   上一次就是这么假红的（199 个语音那轮过了，0 个那轮全 FAIL）。 */
const READY = `!!(window.Alpine && document.querySelector('.wm-root')
    && window.__wmVoice && window.__wmVoice.name)`;

/* 真拿不到语音时给一句能懂的话，而不是让上层报「页面没起来」 */
const VOICE_COUNT = `(window.speechSynthesis ? speechSynthesis.getVoices().length : -1)`;

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

    /* ---------- ① 打开页面，音色清单已就绪 ---------- */
    if (!await nav(PAGE, READY)) {
        const n = await evalJs(VOICE_COUNT).catch(() => -1);
        throw new Error(`等不到音色解析（speechSynthesis 语音数=${n}）`);
    }
    await sleep(400);
    const info = await evalJs(`JSON.stringify({
        name: window.__wmVoice.name,
        options: window.__wmVoice.options,
        total: speechSynthesis.getVoices().length
    })`);
    const v0 = JSON.parse(info);
    check(`语音清单非空（共 ${v0.total} 个，en 可选 ${v0.options.length} 个）`, v0.total > 0 && v0.options.length > 0);
    check(`默认钉住的音色 = ${EXPECT}`, v0.name === EXPECT, '实际 ' + v0.name);

    const BAD = ['albert', 'zarvox', 'trinoids', 'bells', 'bad news', 'eddy', 'flo', 'rocko', 'grandma', 'grandpa'];
    const leaked = v0.options.filter((n) => BAD.some((b) => n.toLowerCase().includes(b)));
    check('可选清单已剔除整蛊 / 机器人音色', leaked.length === 0, leaked.join(','));

    /* ---------- ② 系统朗读档：交给引擎的 utterance 必须带 voice ---------- */
    await evalJs(INSTALL_STUB);
    await evalJs(`(() => {
        var d = window.Alpine.$data(document.querySelector('.wm-root'));
        d.setSpeakMode('system');
        return true;
    })()`);
    await sleep(150);
    await evalJs(`(() => {
        window.__wmSpoken = [];
        var d = window.Alpine.$data(document.querySelector('.wm-root'));
        d.speak('ruler');
        return true;
    })()`);
    await sleep(120);
    const spoken = await evalJs(`JSON.stringify(window.__wmSpoken)`);
    const list = JSON.parse(spoken);
    const hit = list.find((x) => x.text === 'ruler') || {};
    check('系统朗读档确实走 SpeechSynthesis', list.length > 0, JSON.stringify(list));
    check('utterance 带 voice（不再把音色交回系统默认）', !!hit.voice, 'voice=' + hit.voice);
    check(`utterance 用 ${EXPECT} 念英文`, hit.voice === EXPECT, '实际 ' + hit.voice);
    check('lang = en-US', hit.lang === 'en-US', '实际 ' + hit.lang);
    /* rate 是 WebIDL float（32 位），0.85 读回来会变成 0.8500000238418579 —— 必须带容差比较 */
    check('rate = 0.85', Math.abs(hit.rate - 0.85) < 1e-4, '实际 ' + hit.rate);

    /* 中文释义走的是同一条 playSystem（自动学习里的第二条），音色也得钉住，
       否则「英文对了、中文跑到别的音色上」照样难听 */
    await evalJs(`(() => {
        window.__wmSpoken = [];
        var d = window.Alpine.$data(document.querySelector('.wm-root'));
        d.speakOne({ text: '尺子', lang: 'zh-CN', rate: 0.88 });
        return true;
    })()`);
    await sleep(150);
    const zhList = JSON.parse(await evalJs(`JSON.stringify(window.__wmSpoken)`));
    const zhHit = zhList.find((x) => x.text === '尺子') || {};
    check('中文释义也钉住了音色（不是交回系统默认）', !!zhHit.voice, 'voice=' + zhHit.voice);
    check('中文用 zh 音色', /^zh/i.test(zhHit.lang || ''), 'lang=' + zhHit.lang + ' voice=' + zhHit.voice);

    /* ---------- ③ 手动换音色：立刻生效并落盘 ---------- */
    const other = v0.options.find((n) => n !== v0.name);
    if (other) {
        await evalJs(`(() => {
            window.__wmSpoken = [];
            var d = window.Alpine.$data(document.querySelector('.wm-root'));
            d.setSpeakVoice(${JSON.stringify(other)});
            return true;
        })()`);
        await sleep(150);
        const after = JSON.parse(await evalJs(`JSON.stringify(window.__wmSpoken)`));
        const hit2 = after[after.length - 1] || {};
        const saved = await evalJs(`localStorage.getItem('word_match_speak_voice_v1:en')`);
        check(`换音色后立刻用 ${other}`, hit2.voice === other, '实际 ' + hit2.voice);
        check('选择已写进 localStorage', saved === other, '实际 ' + saved);
        /* 换回来，别污染后面的 localStorage 断言 */
        await evalJs(`(() => {
            var d = window.Alpine.$data(document.querySelector('.wm-root'));
            d.setSpeakVoice(${JSON.stringify(v0.name)});
            return true;
        })()`);
    } else {
        check('清单里还有第二个音色可供切换', false, '只有一个可选音色');
    }

    /* ---------- ④ 设置面板：「朗读音色」行只跟系统朗读档 ---------- */
    const rowState = async () => evalJs(`(() => {
        var row = document.querySelector('.is-speakvoice');
        if (!row) return 'missing';
        var sel = row.querySelector('.wm-set-select');
        return JSON.stringify({
            display: getComputedStyle(row).display,
            value: sel ? sel.value : '',
            count: sel ? sel.options.length : 0
        });
    })()`);
    const sysRow = JSON.parse(await rowState());
    check('系统朗读档显示「系统音色」行', sysRow !== 'missing' && sysRow.display !== 'none', JSON.stringify(sysRow));
    check('下拉选中项 = 当前钉住的音色', sysRow.value === v0.name, '实际 ' + sysRow.value);
    check('下拉列出了可选音色', sysRow.count > 1, 'count=' + sysRow.count);

    await evalJs(`(() => {
        var d = window.Alpine.$data(document.querySelector('.wm-root'));
        d.speakMode = 'builtin';
        return true;
    })()`);
    await sleep(120);
    /* 这一页此时还没选册（bookId 为空）⇒ 没有内置读音可用 ⇒ 靠系统朗读兜底，
       所以音色行**应当保留**。「有内置读音时隐藏」由 probe_word_speak_switch.mjs 覆盖。 */
    const biRow = JSON.parse(await rowState());
    check('内置优先档 + 无内置读音的册：音色行仍显示（降级要用它）',
        biRow.display !== 'none', JSON.stringify(biRow));

    /* ---------- ⑤ ?wmVoice= 强制指定（复现整蛊音色的测试通道） ---------- */
    if (!await nav(PAGE + '?wmVoice=Zarvox', READY)) throw new Error('强制通道等不到音色解析');
    await sleep(300);
    const forced = await evalJs(`window.__wmVoice.name`);
    check('?wmVoice=Zarvox 能强制指定音色', forced === 'Zarvox', '实际 ' + forced);

    /* ---------- ⑥ 零未捕获异常 ---------- */
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

#!/usr/bin/env node
/**
 * /chat 聊天流式请求：**必须是 POST + JSON body，聊天原文不进 URL**（2026-10-20）。
 *
 * 背景：原来是 `GET /api/chat/sessions/{id}/stream?configId=..&input=..`，三个问题：
 *   ① 有副作用的接口用 GET（落库用户消息/调模型/落库回复），刷新/预取/历史重放都可能重发；
 *   ② 长消息撞 URL 上限——中文 encodeURIComponent 后一个汉字 9 个字符，请求头 8KB，
 *      两三千字就 414/400；
 *   ③ 原文会进浏览器历史、服务端访问日志、Referer。
 *
 * 顺带修掉一个真 bug：Spring 的 SseEmitter 会把 token 里的 "\n" 改写成 "\ndata:"，
 * 前端原来对多行 data 只取**最后一行** ⇒ 模型输出换行时前半句被吞（刷新后才"自己好了"）。
 *
 * 本脚本逐条守护：
 *   P0 前提：聊天页可交互
 *   P1 【核心】按发送 → 请求是 POST，URL 里没有 query（configId/input 都不在）
 *   P2 【核心】原文完整在 JSON body 里（中文/换行一字不差），且 URL 长度与原文长度无关
 *   P3 【核心】多行 data 被**拼接**：一段含换行的 token 渲染成两行，不丢前半句
 *   P4 done 之后收尾：生成中消失、按钮恢复、助手气泡内容完整
 *   P5 error 事件：气泡里出现「[错误: …]」，不会卡在生成中
 *   P6 【核心】400 时透出后端说明（不是只显示 HTTP 400）
 *   P7 【核心】切会话会中止旧流：不再往新会话界面写 token，也不留「生成中」
 *   P8 运行期无 JS 异常 / console.error
 *
 * 流式响应由 CDP Fetch 域在网络层拦截后喂进来（后端不会真的调模型），
 * 页面 HTML/JS/CSS 仍走真实服务，所以验的是真页面。
 *
 * 用法：
 *   bash /tmp/settings-verify/run.sh scripts/probe_chat_stream_post.mjs    # 1889 临时实例
 *   node scripts/probe_chat_stream_post.mjs                                # 默认 1888
 * 环境变量：CHAT_BASE / WM_BASE（默认 http://localhost:1888）、STORY_BROWSER_PATH
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const BASE = process.env.CHAT_BASE || process.env.WM_BASE || 'http://localhost:1888';
const PAGE = BASE + '/chat';
const CHROME = process.env.STORY_BROWSER_PATH
    || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = 9351;
const SHOT_DIR = '/tmp/chat-post-shots';

const results = [];
const check = (name, ok, extra = '') => {
    results.push({ name, ok: !!ok, extra });
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`);
};

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'chat-post-probe-'));
const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--mute-audio',
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

/* ==================== 网络层拦截：所有 /stream 请求都由这里应答 ====================
   mode:
     reply     → 200 + text/event-stream，body 来自 replyBody
     hold      → 不回，卡住（用来验证"切会话中止旧流"）
     reject400 → 400 + JSON {"error": ...}（验证错误说明能透出）                        */
let mode = 'reply';
let replyBody = '';
const captured = [];     // 每次请求的方法/URL/请求体快照
const held = [];         // hold 模式下被挂起的 requestId

async function handlePaused(p) {
    const req = p.request;
    let body = null;
    try { body = req.postData ? JSON.parse(req.postData) : null; } catch (e) { body = { parseError: true }; }
    const h = req.headers || {};
    captured.push({
        method: req.method,
        url: req.url,
        hasQuery: req.url.indexOf('?') >= 0,
        contentType: h['Content-Type'] || h['content-type'] || '',
        body: body,
        rawBodyLength: (req.postData || '').length
    });

    if (mode === 'hold') {
        held.push(p.requestId);
        return;
    }
    if (mode === 'reject400') {
        return send('Fetch.fulfillRequest', {
            requestId: p.requestId,
            responseCode: 400,
            responseHeaders: [{ name: 'Content-Type', value: 'application/json; charset=utf-8' }],
            body: Buffer.from(JSON.stringify({ error: 'input 不能为空' })).toString('base64')
        });
    }
    return send('Fetch.fulfillRequest', {
        requestId: p.requestId,
        responseCode: 200,
        responseHeaders: [
            { name: 'Content-Type', value: 'text/event-stream; charset=utf-8' },
            { name: 'Cache-Control', value: 'no-cache' }
        ],
        body: Buffer.from(replyBody).toString('base64')
    });
}

async function releaseHeld() {
    for (const id of held.splice(0)) {
        try {
            await send('Fetch.failRequest', { requestId: id, errorReason: 'Aborted' });
        } catch (e) { /* 已经被浏览器取消，忽略 */ }
    }
}

/* ==================== 快照：读 Alpine 状态 + DOM 实渲染 ==================== */
const SNAP = `(() => {
    const host = document.querySelector('[x-data]');
    const data = host && window.Alpine ? Alpine.$data(host) : null;
    const box = document.querySelector('[x-ref="messageContainer"]');
    const items = box ? Array.from(box.querySelectorAll(':scope > div.mb-3')) : [];
    const bubbles = items.map(el => {
        const bubble = el.querySelector('.rounded-3');
        return {
            mine: el.classList.contains('text-end'),
            text: bubble ? bubble.innerText : '',
            raw: bubble ? bubble.textContent : ''
        };
    });
    const ta = document.querySelector('textarea');
    const sendBtn = Array.from(document.querySelectorAll('button'))
        .find(b => b.querySelector('.bi-send'));
    return {
        ready: !!(data && ta && sendBtn),
        streaming: data ? data.streaming : null,
        sending: data ? data.sending : null,
        streamingContent: data ? data.streamingContent : null,
        msgCount: data ? data.messages.length : -1,
        lastContent: data && data.messages.length ? data.messages[data.messages.length - 1].content : null,
        activeId: data ? data.activeSessionId : null,
        sessionCount: data ? data.sessions.length : -1,
        bubbleCount: bubbles.length,
        bubbleTexts: bubbles.map(b => b.text),
        streamingBox: box ? !!box.querySelector('.bg-light.border') && !!(data && data.streaming) : false,
        inputDisabled: ta ? ta.disabled : null,
        sendDisabled: sendBtn ? sendBtn.disabled : null
    };
})()`;

const snap = async (ms = 300) => {
    if (ms) await sleep(ms);
    return evalJs(SNAP);
};

/* ==================== 页面操作 ==================== */
const SEND_BTN = `Array.from(document.querySelectorAll('button')).find(b => b.querySelector('.bi-send'))`;

async function typeMessage(text) {
    return evalJs(`(() => {
        const ta = document.querySelector('textarea');
        ta.focus();
        ta.value = ${JSON.stringify(text)};
        ta.dispatchEvent(new Event('input', { bubbles: true }));
        return ta.value.length;
    })()`);
}

async function clickSend() {
    return evalJs(`(() => { const b = ${SEND_BTN}; if (!b) return false; b.click(); return true; })()`);
}

async function clickNewSession() {
    return evalJs(`(() => {
        const b = Array.from(document.querySelectorAll('button'))
            .find(x => x.querySelector('.bi-plus-lg'));
        if (!b) return false;
        b.click();
        return true;
    })()`);
}

async function closeStreamingIfAny() {
    // 兜底：让页面回到"没有流"的干净状态，避免前一段影响后一段
    await evalJs(`(() => {
        try { const d = Alpine.$data(document.querySelector('[x-data]')); if (d.abortStream) d.abortStream(); } catch (e) {}
        return true;
    })()`);
    await sleep(300);
}

async function shot(name) {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.mkdirSync(SHOT_DIR, { recursive: true });
    fs.writeFileSync(path.join(SHOT_DIR, name + '.png'), Buffer.from(r.data, 'base64'));
}

async function loadPage(width, height) {
    await send('Emulation.setDeviceMetricsOverride', {
        width, height, deviceScaleFactor: 1, mobile: false
    });
    await send('Page.navigate', { url: PAGE });
    const ok = await waitFor('页面就绪', async () => {
        try {
            return await evalJs(`(() => !!(window.Alpine && document.querySelector('textarea')
                && Alpine.$data(document.querySelector('[x-data]'))))()`);
        } catch (e) { return false; }
    }, 20000, 200);
    if (!ok) throw new Error('聊天页未就绪');
    // 引导数据已改成异步拉取：必须等 booting 结束，否则可能在会话还没选中时就开始操作
    const booted = await waitFor('引导数据就绪', async () => {
        try {
            return await evalJs(`(() => {
                const d = Alpine.$data(document.querySelector('[x-data]'));
                return !!d && d.booting === false;
            })()`);
        } catch (e) { return false; }
    }, 10000, 150);
    if (!booted) throw new Error('引导数据未就绪（booting 一直为 true）');
    await sleep(300);
}

/* ==================== 主流程 ==================== */
async function main() {
    let ver = null;
    for (let i = 0; i < 80; i++) {
        try {
            const r = await fetch(`http://127.0.0.1:${PORT}/json/version`);
            if (r.ok) { ver = await r.json(); break; }
        } catch (e) { /* 还没起来 */ }
        await sleep(250);
    }
    if (!ver) throw new Error('Chrome CDP 端点未就绪');
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
            handlePaused(m.params).catch(e => consoleErrors.push('Fetch 拦截失败: ' + e.message));
        }
    };

    await send('Runtime.enable');
    await send('Page.enable');
    await send('Fetch.enable', {
        patterns: [{ urlPattern: '*stream*', requestStage: 'Request' }]
    });

    /* ---------- P0 前提 ---------- */
    console.log('\n— P0 前提 —');
    await loadPage(1280, 900);
    let s = await snap(200);
    check('聊天页可交互（textarea + 发送按钮 + Alpine 已挂载）', s.ready === true);
    check('初始没有在生成的流', s.streaming === false, 'streaming=' + s.streaming);

    /* ---------- P1 + P2 请求形态 ---------- */
    console.log('\n— P1 发送请求的形态（GET → POST） —');
    // 一段又长又带换行的中文：如果是 GET，光 encodeURIComponent 后会接近 3 万字符
    const LONG = Array.from({ length: 60 },
        (_, i) => `第${i + 1}句：雨落在青石板上，檐角的水连成一条线，远处有人提着灯走过。`).join('\n');
    const typed = await typeMessage(LONG);
    check('输入框吃下长消息（60 句中文，含换行）', typed === LONG.length, `len=${typed}`);

    replyBody =
        'event:token\ndata:第一段落\n\n' +
        // 一个 token 内部带换行时，Spring 会写成多行 data:（这里刻意模拟）
        'event:token\ndata:第二行\n' +
        'data:换行之后\n\n' +
        'event:done\ndata:\n\n';

    await clickSend();
    await sleep(700);
    await shot('01-stream');

    const req = captured[captured.length - 1];
    check('确实发出了流式请求', !!req, 'captured=' + captured.length);
    check('【核心】请求方法是 POST（不是 GET）', req && req.method === 'POST', req && req.method);
    check('【核心】URL 里没有 query（configId/input 都不在 URL）',
        req && req.hasQuery === false, req && req.url);
    check('请求头是 application/json', !!req && /application\/json/i.test(req.contentType), req && req.contentType);
    check('【核心】原文完整落在 JSON body 里（中文/换行一字不差）',
        !!req && req.body && req.body.input === LONG,
        req && req.body ? `bodyInput=${String(req.body.input).length}` : 'no body');
    check('【核心】URL 长度与原文长度无关（长消息不再撑大 URL）',
        !!req && req.url.length < 120 && LONG.length > 2000,
        `url=${req && req.url.length} input=${LONG.length}`);

    /* ---------- P3 多行 data 拼接 ---------- */
    console.log('\n— P3 多行 data 拼接（换行不能吞） —');
    s = await snap(500);
    check('【核心】含换行的 token 渲染成两行，前半句没被吞',
        !!s.lastContent && s.lastContent.split('\n').length === 2
        && s.lastContent.indexOf('第一段落第二行') === 0
        && s.lastContent.indexOf('换行之后') > 0,
        JSON.stringify(s.lastContent));
    check('DOM 里也真的显示成两行（pre-wrap 生效）',
        !!s.bubbleTexts[1] && s.bubbleTexts[1].split('\n').length === 2,
        JSON.stringify(s.bubbleTexts[1]));

    /* ---------- P4 done 收尾 ---------- */
    console.log('\n— P4 done 收尾 —');
    check('done 之后不再显示「生成中」', s.streaming === false);
    check('用户消息 + 助手回复都在列表里', s.msgCount === 2, 'msgCount=' + s.msgCount);
    check('输入框与发送按钮恢复可用', s.inputDisabled === false && s.sendDisabled === true,
        `inputDisabled=${s.inputDisabled} sendDisabled=${s.sendDisabled}（发送按钮因输入为空而禁用是对的）`);

    /* ---------- P5 error 事件 ---------- */
    console.log('\n— P5 error 事件 —');
    replyBody = 'event:error\ndata:模型超时\n\n';
    await typeMessage('测试错误分支');
    await clickSend();
    await sleep(700);
    s = await snap(200);
    check('【核心】error 事件在气泡里显示「[错误: 模型超时]」',
        !!s.lastContent && s.lastContent.indexOf('[错误: 模型超时]') >= 0,
        JSON.stringify(s.lastContent));
    check('出错后不卡在「生成中」', s.streaming === false);

    /* ---------- P6 400 说明透出 ---------- */
    console.log('\n— P6 400 后端说明能透出 —');
    mode = 'reject400';
    await typeMessage('空输入测试');
    await clickSend();
    await sleep(800);
    s = await snap(200);
    check('【核心】400 时显示后端给的说明，而不是只有 "HTTP 400"',
        !!s.lastContent && s.lastContent.indexOf('input 不能为空') >= 0,
        JSON.stringify(s.lastContent));
    mode = 'reply';

    /* ---------- P7 切会话中止旧流 ---------- */
    console.log('\n— P7 切会话中止旧流 —');
    mode = 'hold';
    const before = await snap(0);
    await typeMessage('这条会一直挂着不返回');
    await clickSend();
    const started = await waitFor('进入生成中', async () => {
        const x = await evalJs(SNAP);
        return x.streaming === true;
    }, 6000, 120);
    check('挂起的流会让界面进入「生成中」', started === true);

    const switched = await clickNewSession();
    const switchedOk = await waitFor('切到新会话', async () => {
        const x = await evalJs(SNAP);
        return x.activeId !== before.activeId;
    }, 8000, 150);
    check('能新建并切到另一个会话', switched && switchedOk);
    s = await snap(500);
    check('【核心】切会话后「生成中」立刻收掉（旧流被 abort）', s.streaming === false,
        `streaming=${s.streaming}`);
    check('【核心】旧会话的 token 没有漏进新会话（消息列表还是空的）',
        s.msgCount === 0, 'msgCount=' + s.msgCount);

    await releaseHeld();
    await sleep(600);
    s = await snap(0);
    check('释放挂起请求后新会话依然干净（没有迟到内容/错误气泡）',
        s.msgCount === 0 && s.bubbleTexts.length === 0,
        `msgCount=${s.msgCount} bubbles=${s.bubbleTexts.length}`);
    await shot('02-after-abort');
    mode = 'reply';

    /* ---------- P8 无异常 ---------- */
    console.log('\n— P8 运行期异常 —');
    await closeStreamingIfAny();
    check('无 JS 运行时异常', runtimeErrors.length === 0, runtimeErrors.slice(0, 2).join(' | '));
    check('无 console.error', consoleErrors.length === 0, consoleErrors.slice(0, 2).join(' | '));

    const failed = results.filter(r => !r.ok).length;
    console.log(`\n===== ${results.length - failed}/${results.length} 通过 =====`);
    if (failed) console.log('失败项：\n' + results.filter(r => !r.ok).map(r => ' - ' + r.name).join('\n'));

    try { await send('Browser.close'); } catch (e) { /* ignore */ }
    try { chrome.kill(); } catch (e) { /* ignore */ }
    process.exit(failed ? 1 : 0);
}

main().catch(async (e) => {
    console.error('PROBE FAILED: ' + e.message);
    try { chrome.kill(); } catch (err) { /* ignore */ }
    process.exit(2);
});
